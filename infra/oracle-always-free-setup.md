# Oracle Cloud Always Free deployment

Deploy `crime-info-service` to Oracle Cloud's "Always Free" tier at $0/month.
See the parent [README.md](../README.md) for the application itself; this doc
only covers getting VM(s) up and the app running on them.

## One VM or two?

If you land an Ampere A1 instance (4 OCPU / 24 GB), run everything on it —
`infra/docker-compose-mongo.yml` + `docker-compose-app.yml` unchanged, app and
database co-located, plenty of headroom.

If Ampere capacity isn't available (see [Phase 1b](#phase-1b--out-of-host-capacity))
and you fall back to the AMD `E2.1.Micro` shapes instead, **use both of your two
Always Free slots and split the stack across them** — one for MongoDB + Redis,
one for the app + Caddy. This isn't optional at that size: co-locating the JVM
with Mongo and Redis on a single 1 GB box was tried first and reliably failed —
Spring context startup alone pushed memory tight enough to make `dockerd` itself
hang under pressure, `docker logs`/`docker stats` would stop responding, and the
whole VM occasionally needed a hypervisor-level reboot to recover. Two VMs at
1 GB each, single-purpose, is dramatically more stable than one VM at 1 GB
doing both jobs. The rest of this doc assumes the two-VM split — for a single
Ampere VM, skip the "DB host" / "app host" distinction and run every command
on the one box.

## Phase 0 — Prerequisites

- An Oracle Cloud account at [cloud.oracle.com](https://cloud.oracle.com). A card
  is required for identity verification only — Always Free resources never
  charge it.
- An SSH key pair:

  ```bash
  ssh-keygen -t ed25519 -C "crimewatch-oracle" -f ~/.ssh/crimewatch-oracle
  ```

- Optional: a domain you control, for TLS. Without one, `sslip.io` gives a
  free working hostname for the VM's own IP (see Phase 8).

## Phase 1 — Create the VM

Console: **Compute → Instances → Create Instance**.

1. Name it (e.g. `crimewatch-vm`).
2. **Image and shape → Edit** — pick **Ubuntu 24.04**, **Change shape** →
   **Ampere** series → `VM.Standard.A1.Flex`.
3. Expand the shape row (click the **▸** triangle next to the shape name —
   it starts collapsed showing 1 OCPU / 6 GB, which is *editable*, not a
   ceiling) and set **OCPU = 4**, **Memory = 24 GB**. Confirm "Always
   Free-eligible" is still shown.
4. **Networking** — see Phase 1a below before continuing; a bare "create new
   public subnet" here frequently fails to attach a working public IP.
5. **Add SSH keys** — paste `~/.ssh/crimewatch-oracle.pub`.
6. **Create.**

If creation fails with *"Out of host capacity"*: this is common for the
Ampere shape in busy regions and is not specific to your account. See
[Phase 1b](#phase-1b--out-of-host-capacity) for an automated retry approach.

### Phase 1a — Networking: build the VCN properly first

The instance wizard's inline **"Create new public subnet"** option does not
reliably attach an Internet Gateway or route table — the resulting subnet
looks public in the UI but the **"Automatically assign public IPv4 address"**
toggle stays disabled with *"You must select a public subnet..."* even
though a public subnet was ostensibly just created.

Build the VCN outside the instance wizard instead:

1. **Networking → Virtual Cloud Networks → Start VCN Wizard →** "Create VCN
   with Internet Connectivity" *(the full wizard, not the plain "Create VCN"
   dialog — the plain dialog only creates a bare VCN with no subnet, IGW, or
   route table)*.
2. Name it, IPv4 CIDR block `10.0.0.0/16` (standard default, room for both a
   public and private subnet). If the DNS Label field rejects your VCN name
   as invalid, it's because DNS labels can't contain hyphens — type a plain
   alphanumeric label (e.g. `crimewatch`) instead; it's purely internal and
   unrelated to your public hostname.
3. If you already created a bare VCN and don't want to redo it, add the
   missing pieces manually instead: **Internet Gateway** (Networking →
   your VCN → Internet Gateways → Create), a **route rule** on the Default
   Route Table (`0.0.0.0/0` → that Internet Gateway), then a **Subnet**
   with **Subnet Access: Public Subnet** using that route table.
4. Back in instance creation, under Networking, choose **"Select existing
   subnet"** (not "Create new public subnet") and pick the wizard-created
   public subnet. The public IPv4 toggle should now be selectable.
5. **Networking → Virtual Cloud Networks → your VCN → IP Management →
   Reserve Public IP** once the instance exists, so the address survives
   a reboot.

### Phase 1b — Out of host capacity

Ampere A1 capacity varies a lot by region and can stay unavailable for days
at a time — this has been observed firsthand in `ap-sydney-1`. Two things
help:

- **Try a smaller Always Free shape.** 2 OCPU/12 GB or 1 OCPU/6 GB succeed
  more often than 4/24 during a crunch (easier to fit into a partially-full
  host) — a flexible-shape instance can be resized up later with
  `oci compute instance update` without recreating it.
- **Try the AMD micro shapes too.** Always Free also includes up to two
  `VM.Standard.E2.1.Micro` VMs (1/8 OCPU, 1 GB RAM, x86) — a separate
  capacity pool from Ampere A1, sometimes available when A1 isn't. Much
  tighter on RAM (plan on a trimmed JVM heap, e.g. `-Xmx400m`, if running
  the full stack on one), but it's a real fallback to get something live
  sooner.

[`oracle-launch-retry.sh`](oracle-launch-retry.sh) automates both: it cycles
through Ampere shape sizes and both AMD micro slots via the OCI CLI, scheduled
as a periodic job, and stops itself the moment any launch succeeds.

```bash
brew install oci-cli
oci setup config   # interactive — see prompts below

cp infra/oracle-launch-retry.sh ~/oracle-launch/try-launch.sh
chmod +x ~/oracle-launch/try-launch.sh
# fill in the placeholders — see the "gather these first" block at the top
# of the script for the exact `oci ...` commands to get each value

cp infra/com.example.oraclelaunch.plist.template \
   ~/Library/LaunchAgents/com.crimewatch.oraclelaunch.plist
# edit the two /Users/<you>/... paths in that file to match your home directory

launchctl load -w ~/Library/LaunchAgents/com.crimewatch.oraclelaunch.plist
tail -f ~/oracle-launch/attempts.log
```

`oci setup config` needs your **User OCID** and **Tenancy OCID** (Console →
profile icon → *My profile*, and the tenancy link on that same page) and a
region. It generates an API key pair and prints a public key — add it under
*My profile → API keys → Add API Key → Paste a public key*.

The job runs every 5 minutes, tries every shape/region-pool combination
configured, and writes `~/oracle-launch/instance-created.json` plus a macOS
notification on success. It only fires while the machine is awake; run
`caffeinate -s &` to keep retrying through the night if you want maximum
coverage, or just let it opportunistically retry whenever the Mac is on.

## Phase 2 — Open the firewall (both layers, on the app host)

Oracle has *two* firewalls — the cloud Security List and the OS firewall on
the instance. Missing either gives a silent connection refused.

**Console:** your VCN → Security Lists → Default Security List → **Add
Ingress Rules**: source `0.0.0.0/0`, TCP, port `80`; another for port `443`.
(Port 22 is open by default — consider narrowing its source CIDR to your own
IP once access is confirmed.)

**On the app host:**

```bash
sudo iptables -I INPUT -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save 2>/dev/null || sudo iptables-save | sudo tee /etc/iptables/rules.v4
```

If you're running the two-VM split, also add Mongo/Redis ingress rules — but
scoped to the VCN's private CIDR only (`10.0.0.0/16`), never `0.0.0.0/0`, so
the database is reachable from the app host but not the public internet:

```bash
# Console → Security List → Add Ingress Rule, twice:
#   source 10.0.0.0/16, TCP, port 27017
#   source 10.0.0.0/16, TCP, port 6379
```

## Phase 3 — First login and base packages

Both hosts only ever need Docker — nothing else. In particular, **don't
install a JDK or clone the source repo onto either VM.** The war is built
once on your own machine and shipped as a built artifact (Phase 4) — a
production host has no business holding a full JDK, Gradle, git history, and
Java sources just to compile something that runs the same way regardless of
where it was built.

```bash
ssh -i ~/.ssh/crimewatch-oracle ubuntu@<host-public-ip>

sudo apt update && sudo apt upgrade -y
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
newgrp docker
mkdir -p ~/crimewatch-deploy/infra
```

Repeat on both hosts if running the two-VM split.

## Phase 4 — Build the war locally and ship only the artifact

**On your own machine**, not either VM:

```bash
cd crimewatch-service
./gradlew bootWar
```

This needs a local JDK 21 (this repo's `settings.gradle` has no foojay
toolchain resolver, so Gradle needs one already installed, same as it would
on a VM — the difference is *where* that JDK lives, not whether one exists
anywhere). The build produces a plain, architecture-independent war —
`infra/Dockerfile` packages it as-is, and Docker resolves the correct
`eclipse-temurin` base image architecture (arm64 for Ampere, amd64 for the
micro shapes) automatically on whichever host builds the image.

Ship only what each host actually needs — no `src/`, no `.git`, no Gradle:

```bash
# DB host: just the Mongo/Redis compose file + init script
scp -i ~/.ssh/crimewatch-oracle infra/docker-compose-mongo.yml infra/mongo-init.js \
  ubuntu@<db-host-ip>:~/crimewatch-deploy/infra/

# App host: Dockerfile + both compose files + the built war
mkdir -p build/libs   # already exists after bootWar
scp -i ~/.ssh/crimewatch-oracle infra/Dockerfile infra/docker-compose-mongo.yml infra/docker-compose-app.yml \
  ubuntu@<app-host-ip>:~/crimewatch-deploy/infra/
scp -i ~/.ssh/crimewatch-oracle build/libs/*.war \
  ubuntu@<app-host-ip>:~/crimewatch-deploy/build/libs/
```

(On a single-VM Ampere deployment, both sets of files go to the same host.)

[`infra/deploy-to-oracle.sh`](deploy-to-oracle.sh) automates this entire
phase plus 6 and 7 below — see [Automating redeploys](#automating-redeploys)
once you've done it manually once and understand what it's doing.

## Phase 5 — Configure real secrets (app host only)

```bash
ssh -i ~/.ssh/crimewatch-oracle ubuntu@<app-host-ip>
cd ~/crimewatch-deploy
python3 -c "import secrets; print(secrets.token_urlsafe(32))"   # run twice
cat > .env <<'EOF'
CRIME_READ_API_KEY=<paste first generated value>
CRIME_INGEST_API_KEY=<paste second generated value>
EOF
```

**Every `docker compose` command from here on must include `--env-file
./.env` explicitly.** Compose does *not* reliably auto-load a `.env` sitting
in the current directory here — when multiple `-f` files are given, Compose
defaults its "project directory" (where it looks for `.env`) to the
directory of the *first* `-f` file, not your working directory. Since every
compose file in this repo lives under `infra/`, Compose silently looks for
`infra/.env` (which doesn't exist) and falls back to each variable's
hardcoded default (`change-me-read`, etc.) with no error or warning — the
container starts fine, just with the wrong key. This is easy to lose an hour
to, because everything *looks* like it worked. `--env-file ./.env` bypasses
the project-directory guessing entirely and points Compose straight at the
file.

## Phase 6 — Start Mongo and Redis (DB host), then initialize

```bash
cd ~/crimewatch-deploy
docker compose --env-file ./.env -f infra/docker-compose-mongo.yml up -d
docker exec -i crime-info-mongodb mongosh --quiet < infra/mongo-init.js
```

## Phase 7 — Start the app (app host), pointed at the DB host

If running the two-VM split, the app needs to reach Mongo/Redis over the
VCN's private network instead of the local Docker network, and the JVM heap
needs capping to fit a 1 GB host. Both go in a local override file — **never
committed**, since it's specific to this deployment's topology:

```bash
cat > infra/docker-compose.override.yml <<'EOF'
services:
  app:
    environment:
      MONGODB_URI: mongodb://<db-host-private-ip>:27017/crime_info_service
      REDIS_HOST: <db-host-private-ip>
    command: ["java", "-Xmx700m", "-XX:MaxMetaspaceSize=192m", "-jar", "app.war"]
EOF

docker compose --env-file ./.env \
  -f infra/docker-compose-mongo.yml -f infra/docker-compose-app.yml -f infra/docker-compose.override.yml \
  up -d --force-recreate --no-deps app
```

**`--no-deps` is not optional here.** `docker-compose-app.yml`'s `app`
service declares `depends_on: mongodb, redis` — without `--no-deps`, Compose
starts those dependencies too even when `app` is the only service named on
the command line, silently bringing up a second, unwanted local Mongo/Redis
on the app host and defeating the entire point of the split. This is the
single most important thing to get right on every redeploy, not just the
first one.

On a single-VM deployment, drop the override file and MONGODB_URI/REDIS_HOST
lines (the defaults already point at the local `mongodb`/`redis` containers)
and just run `docker compose --env-file ./.env -f infra/docker-compose-mongo.yml -f infra/docker-compose-app.yml up -d --build`.

Verify locally before moving on — expect this to take noticeably longer than
you'd expect (a minute or more) on a memory-constrained micro host, since
JVM startup competes for CPU with everything else running:

```bash
docker logs -f crime-info-service   # watch for "Started CrimeInfoServiceApp"
curl localhost:8080/actuator/health
```

## Phase 8 — TLS with Caddy (app host only)

Install Caddy natively on the host (not in Docker) so it can bind 80/443 and
reverse-proxy to the app container's published `8080`:

```bash
sudo apt install -y debian-keyring debian-archive-keyring apt-transport-https curl
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/gpg.key' | sudo gpg --dearmor -o /usr/share/keyrings/caddy-stable-archive-keyring.gpg
curl -1sLf 'https://dl.cloudsmith.io/public/caddy/stable/debian.deb.txt' | sudo tee /etc/apt/sources.list.d/caddy-stable.list
sudo apt update && sudo apt install -y caddy
```

Point an A record at your reserved public IP, or use `sslip.io` for a free
hostname with no signup (`140-238-12-34.sslip.io` for IP `140.238.12.34`):

```bash
sudo tee /etc/caddy/Caddyfile <<'EOF'
your-domain-or-140-238-12-34.sslip.io {
    reverse_proxy localhost:8080
}
EOF
sudo systemctl reload caddy
curl https://your-domain-or-ip.sslip.io/actuator/health   # from your own machine
```

Caddy requests and auto-renews the Let's Encrypt certificate on first hit —
port 8080 itself is never exposed to the internet.

## Phase 9 — Make it durable

**Survive reboots** (on whichever host runs each container):

```bash
docker update --restart unless-stopped crime-info-mongodb crime-info-redis   # DB host
docker update --restart unless-stopped crime-info-service                    # app host
```

Without this, a reboot — including one Oracle's hypervisor forces through on
its own if the guest OS stops responding to a graceful shutdown signal, which
can happen under the kind of memory pressure a 1 GB host sees — leaves every
container stopped rather than restarting automatically.

**Nightly backups to free Object Storage** (create a bucket once in Console:
Storage → Object Storage → Create Bucket; run on the DB host):

```bash
sudo apt install -y python3-pip && pip3 install oci-cli
oci setup config

sudo tee /usr/local/bin/backup-mongo.sh <<'EOF'
#!/bin/bash
set -e
STAMP=$(date +%F)
docker exec crime-info-mongodb mongodump --archive --gzip --db crime_info_service \
  > /tmp/crime-info-$STAMP.gz
oci os object put --bucket-name crimewatch-backups --file /tmp/crime-info-$STAMP.gz --force
rm /tmp/crime-info-$STAMP.gz
EOF
sudo chmod +x /usr/local/bin/backup-mongo.sh
( crontab -l 2>/dev/null; echo "0 2 * * * /usr/local/bin/backup-mongo.sh" ) | crontab -
```

**Cap the ingestion job's CPU share** (app host) so the twice-daily cron
(PDFBox/POI parsing) can't starve live GraphQL requests on shared hardware:

```bash
docker update --cpus 2 crime-info-service
```

**Uptime monitor** — [uptimerobot.com](https://uptimerobot.com), free plan,
HTTP(S) monitor on `https://your-domain/actuator/health`, 5-minute interval.
This also keeps utilization above Oracle's idle-reclamation threshold
(instances sitting under ~10% CPU/network/memory for 7 straight days can be
reclaimed).

## Phase 10 — Confirm end-to-end

From your own machine, not the VM:

```bash
curl https://your-domain-or-ip.sslip.io/actuator/health

# unauthenticated request should be rejected — confirms the API key filter
# is actually active, not just that something is listening
curl -o /dev/null -w '%{http_code}\n' -X POST https://your-domain-or-ip.sslip.io/graphql \
  -H 'Content-Type: application/json' -d '{"query":"{ ingestionSources }"}'   # expect 401

curl -X POST https://your-domain-or-ip.sslip.io/graphql \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: <your read key>" \
  -d '{"query":"{ crimeIncidents(state: \"SA\") { title crimeType location { city } } }"}'
```

Don't assume that last one is using the real key just because you set it in
`.env` — the `--env-file` gotcha above means it's entirely possible to reach
this point with a healthy, fully-connected app that's still running on the
`change-me-read` fallback. Confirm explicitly:

```bash
docker inspect crime-info-service --format='{{range .Config.Env}}{{println .}}{{end}}' | grep CRIME_READ_API_KEY
```

should match what's actually in `.env`, not the compose file's hardcoded
default.

If running the two-VM split, two more checks confirm the app is really
talking to the DB host over the private network rather than a stale local
container (a healthy `/actuator/health` alone is good evidence — Spring Boot
aggregates the Mongo/Redis health indicators into the overall status, so
`UP` already implies both connections work — but these are unambiguous):

```bash
# on the app host — should show the DB host's private IP, not "mongodb"/"redis"
docker inspect crime-info-service --format='{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -E 'MONGODB_URI|REDIS_HOST'

# on the DB host — a non-zero, ideally growing count confirms real traffic
# arriving from across the VCN
docker exec crime-info-mongodb mongosh --quiet --eval "db.serverStatus().connections"
```

## Automating redeploys

Once you've been through Phases 4–7 by hand and understand what each step
does, [`infra/deploy-to-oracle.sh`](deploy-to-oracle.sh) automates the
repeatable build-and-ship cycle — everything except the one-time VM
provisioning (Phases 1–3, 8–9), which stays manual since it's Console-driven
and only happens once. Edit the host IPs at the top of the script, then:

```bash
./infra/deploy-to-oracle.sh
```

It builds the war locally, ships only the artifact and compose/config files
(never source) to the right host(s), and redeploys the app with `--no-deps`
so it never disturbs the DB host's containers.
