#!/usr/bin/env bash
# Builds the war locally and redeploys it to an Oracle Always Free
# deployment, split across two hosts: DB_HOST runs MongoDB + Redis,
# APP_HOST runs the app container + Caddy. Run from the repo root, on your
# own machine — never on either VM (see infra/oracle-always-free-setup.md
# for why source/JDK/git never belong on the deployment target).
#
# One-time provisioning (VM creation, firewall, Docker install, Caddy,
# .env with real secrets) is NOT handled here — see Phases 1-3, 5, 8-9 of
# the setup guide. This script only automates the repeatable build-and-ship
# cycle (Phases 4, 6, 7), and assumes that provisioning is already done.
#
# For a single-VM (Ampere) deployment instead of the two-host split, set
# DB_HOST=$APP_HOST and DB_PRIVATE_HOST=localhost.
set -euo pipefail

# ---- fill these in for your deployment ----
DB_HOST="REPLACE_ME_DB_HOST_PUBLIC_IP"
DB_PRIVATE_HOST="REPLACE_ME_DB_HOST_PRIVATE_IP"   # e.g. 10.0.0.21 — used by the app's MONGODB_URI/REDIS_HOST
APP_HOST="REPLACE_ME_APP_HOST_PUBLIC_IP"
APP_PUBLIC_URL="https://REPLACE_ME.sslip.io"      # for the final external health check
SSH_KEY="$HOME/.ssh/crimewatch-oracle"
SSH_USER="ubuntu"
REMOTE_DIR="crimewatch-deploy"
JVM_XMX="700m"
JVM_MAX_METASPACE="192m"
# --------------------------------------------

ssh_db()  { ssh -i "$SSH_KEY" -o BatchMode=yes "$SSH_USER@$DB_HOST" "$@"; }
ssh_app() { ssh -i "$SSH_KEY" -o BatchMode=yes "$SSH_USER@$APP_HOST" "$@"; }
scp_db()  { scp -i "$SSH_KEY" "$@" "$SSH_USER@$DB_HOST:~/$REMOTE_DIR/infra/"; }
scp_app() { scp -i "$SSH_KEY" "$@" "$SSH_USER@$APP_HOST:~/$REMOTE_DIR/infra/"; }

echo "==> Building war locally"
./gradlew bootWar --console=plain

WAR=$(ls build/libs/*.war | head -1)
echo "==> Built: $WAR"

echo "==> Ensuring remote directories exist"
ssh_db  "mkdir -p ~/$REMOTE_DIR/infra"
ssh_app "mkdir -p ~/$REMOTE_DIR/infra ~/$REMOTE_DIR/build/libs"

echo "==> Syncing DB host files (Mongo/Redis compose + init script — no app code)"
scp_db infra/docker-compose-mongo.yml infra/mongo-init.js

echo "==> Syncing app host files (Dockerfile + compose files + built war — no source)"
scp_app infra/Dockerfile infra/docker-compose-mongo.yml infra/docker-compose-app.yml
scp -i "$SSH_KEY" "$WAR" "$SSH_USER@$APP_HOST:~/$REMOTE_DIR/build/libs/"

echo "==> Writing app host override (DB host private IP + capped JVM heap)"
ssh_app "cat > ~/$REMOTE_DIR/infra/docker-compose.override.yml" <<EOF
services:
  app:
    environment:
      MONGODB_URI: mongodb://${DB_PRIVATE_HOST}:27017/crime_info_service
      REDIS_HOST: ${DB_PRIVATE_HOST}
    command: ["java", "-Xmx${JVM_XMX}", "-XX:MaxMetaspaceSize=${JVM_MAX_METASPACE}", "-jar", "app.war"]
EOF

echo "==> Starting Mongo + Redis on DB host (idempotent — no-op if already up)"
ssh_db "cd ~/$REMOTE_DIR && docker compose --env-file ./.env -f infra/docker-compose-mongo.yml up -d"
ssh_db "cd ~/$REMOTE_DIR && docker exec -i crime-info-mongodb mongosh --quiet < infra/mongo-init.js"

# --env-file is not optional: with multiple -f files, Compose defaults its
# "project directory" (where it looks for .env) to the first -f file's
# directory — here, infra/, where no .env exists — and silently falls back
# to each variable's hardcoded default instead of erroring. Discovered the
# hard way: a fully healthy, fully-connected deployment running on the
# compose file's "change-me-read" fallback key with no indication anything
# was wrong.
echo "==> Deploying app on app host (--no-deps: never touches DB host's containers)"
ssh_app "cd ~/$REMOTE_DIR && docker compose --env-file ./.env -f infra/docker-compose-mongo.yml -f infra/docker-compose-app.yml -f infra/docker-compose.override.yml up -d --force-recreate --no-deps app"

echo "==> Confirming the app actually picked up the real API key, not the compose fallback"
RUNNING_KEY=$(ssh_app "docker inspect crime-info-service --format='{{range .Config.Env}}{{println .}}{{end}}'" | grep '^CRIME_READ_API_KEY=' | cut -d= -f2)
ENV_FILE_KEY=$(ssh_app "grep '^CRIME_READ_API_KEY=' ~/$REMOTE_DIR/.env" | cut -d= -f2)
if [ "$RUNNING_KEY" != "$ENV_FILE_KEY" ] || [ "$RUNNING_KEY" = "change-me-read" ]; then
  echo "Container's CRIME_READ_API_KEY ('$RUNNING_KEY') doesn't match .env ('$ENV_FILE_KEY')."
  echo "This is the --env-file gotcha above, or .env on the app host still has a placeholder — check both."
  exit 1
fi

echo "==> Waiting for the app to report healthy (can take over a minute on a constrained host)"
for i in $(seq 1 30); do
  if ssh_app "curl -sf localhost:8080/actuator/health" >/dev/null 2>&1; then
    echo "App is healthy locally."
    break
  fi
  if [ "$i" -eq 30 ]; then
    echo "Timed out waiting for local health check — check 'docker logs crime-info-service' on the app host."
    exit 1
  fi
  sleep 5
done

echo "==> Verifying externally through Caddy"
if curl -sf -m 10 "$APP_PUBLIC_URL/actuator/health"; then
  echo ""
  echo "Deployed and reachable at $APP_PUBLIC_URL"
else
  echo ""
  echo "Local health check passed but the external URL didn't respond — check Caddy on the app host."
  exit 1
fi
