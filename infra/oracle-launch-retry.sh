#!/usr/bin/env bash
# Retries an Oracle Cloud Always Free instance launch until capacity is
# available. Cycles through Ampere A1.Flex shape sizes and both AMD
# E2.1.Micro slots on every pass — two separate Always Free capacity pools,
# so trying both roughly doubles the odds during a regional capacity crunch.
#
# Intended to run periodically (e.g. every 5 minutes via launchd/cron) rather
# than looping internally; exits 0 and unloads its own launchd job on an
# Ampere success (an AMD micro success keeps it running, since up to two of
# those can coexist and an Ampere win is still worth catching after).
#
# See infra/oracle-always-free-setup.md, Phase 1b, for setup instructions
# and infra/com.example.oraclelaunch.plist.template for the launchd job.
#
# Gather the values below with (after `oci setup config`):
#   grep '^tenancy' ~/.oci/config                                  # COMPARTMENT_ID
#   oci iam availability-domain list --compartment-id <tenancy>    # ADS
#   oci network subnet list --compartment-id <tenancy> \
#     --query "data[?contains(\"display-name\",'<name>')].{name:\"display-name\",id:id}"   # SUBNET_ID
#   oci compute image list --compartment-id <tenancy> \
#     --operating-system "Canonical Ubuntu" --operating-system-version "24.04" \
#     --shape "VM.Standard.A1.Flex" --query "data[0].{name:\"display-name\",id:id}"          # IMAGE_ID_ARM
#   (repeat with --shape "VM.Standard.E2.1.Micro" for IMAGE_ID_X86)
set -uo pipefail

# launchd runs jobs with a minimal PATH that doesn't include Homebrew —
# add it explicitly so `oci` resolves.
export PATH="/opt/homebrew/bin:/opt/homebrew/sbin:$PATH"

# ---- fill these in for your tenancy ----
COMPARTMENT_ID="REPLACE_ME_TENANCY_OCID"
SUBNET_ID="REPLACE_ME_PUBLIC_SUBNET_OCID"
IMAGE_ID_ARM="REPLACE_ME_UBUNTU_ARM64_IMAGE_OCID"
IMAGE_ID_X86="REPLACE_ME_UBUNTU_X86_IMAGE_OCID"
ADS=(
  "REPLACE_ME:REGION-AD-1"
  # add more availability domains here if your region has them —
  # each one is tried in turn on every run
)
SSH_KEY_PATH="$HOME/.ssh/crimewatch-oracle.pub"
PLIST_LABEL="com.crimewatch.oraclelaunch"

# Tried in order on every pass: shape : ocpus : memoryGB : image : display-name
# E2.1.Micro is a fixed shape (no --shape-config, ocpus/mem fields unused,
# image must be x86).
SHAPE_PROFILES=(
  "VM.Standard.A1.Flex:4:24:$IMAGE_ID_ARM:crimewatch-vm"
  "VM.Standard.A1.Flex:2:12:$IMAGE_ID_ARM:crimewatch-vm"
  "VM.Standard.A1.Flex:1:6:$IMAGE_ID_ARM:crimewatch-vm"
  "VM.Standard.E2.1.Micro:0:0:$IMAGE_ID_X86:crimewatch-vm-micro-1"
  "VM.Standard.E2.1.Micro:0:0:$IMAGE_ID_X86:crimewatch-vm-micro-2"
)
# ------------------------------------------

LOG_DIR="$HOME/oracle-launch"
LOG="$LOG_DIR/attempts.log"
mkdir -p "$LOG_DIR"

cat > /tmp/crimewatch-metadata.json <<EOF
{"ssh_authorized_keys": "$(cat "$SSH_KEY_PATH")"}
EOF

for AD in "${ADS[@]}"; do
  for PROFILE in "${SHAPE_PROFILES[@]}"; do
    IFS=':' read -r SHAPE OCPUS MEMORY_GB IMAGE_ID DISPLAY_NAME <<< "$PROFILE"

    LAUNCH_ARGS=(
      --compartment-id "$COMPARTMENT_ID"
      --availability-domain "$AD"
      --shape "$SHAPE"
      --subnet-id "$SUBNET_ID"
      --assign-public-ip true
      --image-id "$IMAGE_ID"
      --display-name "$DISPLAY_NAME"
      --metadata file:///tmp/crimewatch-metadata.json
    )

    LABEL="$SHAPE"
    if [[ "$SHAPE" == *.Flex ]]; then
      cat > /tmp/crimewatch-shape-config.json <<EOF
{"ocpus": $OCPUS, "memoryInGBs": $MEMORY_GB}
EOF
      LAUNCH_ARGS+=(--shape-config file:///tmp/crimewatch-shape-config.json)
      LABEL="$SHAPE @ ${OCPUS} OCPU/${MEMORY_GB}GB"
    fi

    echo "$(date '+%F %T') trying $AD :: $LABEL ($DISPLAY_NAME)" >> "$LOG"

    RESULT=$(oci compute instance launch "${LAUNCH_ARGS[@]}" 2>&1)
    STATUS=$?

    if [ $STATUS -eq 0 ]; then
      echo "$(date '+%F %T') SUCCESS on $AD :: $LABEL ($DISPLAY_NAME)" >> "$LOG"
      {
        echo "=== $DISPLAY_NAME — $(date '+%F %T') ==="
        echo "$RESULT"
      } >> "$LOG_DIR/instance-created.json"
      osascript -e "display notification \"$LABEL ($DISPLAY_NAME) — check the Oracle Console.\" with title \"Crimewatch VM launched\" sound name \"Glass\"" 2>/dev/null || true
      # keep looping — E2.1.Micro allows up to 2, and a later Ampere
      # success is still worth catching, so don't unload on a micro win
      if [[ "$SHAPE" == *.Flex ]]; then
        launchctl unload "$HOME/Library/LaunchAgents/${PLIST_LABEL}.plist" 2>/dev/null || true
        exit 0
      fi
    else
      REASON=$(echo "$RESULT" | grep -io "out of host capacity[^\"]*\|LimitExceeded[^\"]*\|too many requests[^\"]*" | head -1)
      echo "$(date '+%F %T') failed on $AD :: $LABEL ($DISPLAY_NAME): ${REASON:-unknown error, see full log below}" >> "$LOG"
      echo "$RESULT" >> "$LOG"
    fi
    # brief pause between attempts on the same AD to stay well under
    # Oracle's per-tenancy rate limit for launch_instance
    sleep 5
  done
done

exit 0
