# Oracle Cloud Always Free deployment

Deploy `crime-info-service` to an Oracle Cloud "Always Free" VM at $0/month —
`infra/docker-compose-mongo.yml` + `docker-compose-app.yml` run unchanged, with
the app, MongoDB, and Redis co-located on one Ampere A1 instance (4 OCPU / 24 GB,
free forever). See the parent [README.md](../README.md) for the application
itself; this doc only covers getting a VM up and the app running on it.

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

## Phase 2 — Open the firewall (both layers)

Oracle has *two* firewalls — the cloud Security List and the OS firewall on
the instance. Missing either gives a silent connection refused.

**Console:** your VCN → Security Lists → Default Security List → **Add
Ingress Rules**: source `0.0.0.0/0`, TCP, port `80`; another for port `443`.
(Port 22 is open by default — consider narrowing its source CIDR to your own
IP once access is confirmed.)

**On the VM:**

```bash
sudo iptables -I INPUT -p tcp --dport 80 -j ACCEPT
sudo iptables -I INPUT -p tcp --dport 443 -j ACCEPT
sudo netfilter-persistent save 2>/dev/null || sudo iptables-save | sudo tee /etc/iptables/rules.v4
```

## Phase 3 — First login and base packages

```bash
ssh -i ~/.ssh/crimewatch-oracle ubuntu@<your-reserved-public-ip>

sudo apt update && sudo apt upgrade -y

# Docker Engine + Compose plugin (arm64 build, official repo)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER
newgrp docker

# JDK 21 — this repo's settings.gradle has no foojay toolchain resolver, so
# Gradle needs a JDK already installed rather than auto-downloading one
sudo apt install -y openjdk-21-jdk git
java -version   # confirm 21.x, arm64
```

## Phase 4 — Build the app

```bash
git clone https://github.com/<your-username>/crimewatch-service.git
cd crimewatch-service
./gradlew bootWar
```

This produces the war that `infra/Dockerfile` packages as-is — no changes
needed for ARM, since it's plain JVM bytecode; Docker resolves the correct
`eclipse-temurin` image architecture automatically.

## Phase 5 — Configure real secrets

```bash
cp .env.example .env
python3 -c "import secrets; print(secrets.token_urlsafe(32))"   # run twice
```

Replace `CRIME_READ_API_KEY` / `CRIME_INGEST_API_KEY` in `.env` with the
generated values — these are the only thing standing between the public
internet and `ingestCrimeData` once port 443 is open. Export them (or
`source` a version of `.env` written as `export KEY=value`) before the next
step, since `docker-compose-app.yml` reads them from the shell environment.

## Phase 6 — Start Mongo and Redis, then initialize

```bash
docker compose -f infra/docker-compose-mongo.yml up -d
docker exec -i crime-info-mongodb mongosh --quiet < infra/mongo-init.js
```

## Phase 7 — Start the app and verify locally

```bash
docker compose -f infra/docker-compose-mongo.yml -f infra/docker-compose-app.yml up -d --build
curl localhost:8080/actuator/health

curl -X POST localhost:8080/graphql \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: $CRIME_READ_API_KEY" \
  -d '{"query":"{ ingestionSources }"}'
```

## Phase 8 — TLS with Caddy

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

**Survive reboots:**

```bash
docker update --restart unless-stopped crime-info-mongodb crime-info-redis crime-info-service
```

**Nightly backups to free Object Storage** (create a bucket once in Console:
Storage → Object Storage → Create Bucket):

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

**Cap the ingestion job's CPU share** so the twice-daily cron (PDFBox/POI
parsing) can't starve live GraphQL requests on shared hardware:

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

curl -X POST https://your-domain-or-ip.sslip.io/graphql \
  -H 'Content-Type: application/json' \
  -H "X-API-Key: <your read key>" \
  -d '{"query":"{ crimeIncidents(state: \"SA\") { title crimeType location { city } } }"}'
```
