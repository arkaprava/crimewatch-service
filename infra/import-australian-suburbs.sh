#!/usr/bin/env bash
# Build and import all Australian suburbs into MongoDB.
#
# Usage (from crimewatch-service root):
#   ./infra/import-australian-suburbs.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
POSTCODES_CSV="${POSTCODES_CSV:-${ROOT}/../crime_watch_au/tool/data/australian-postcodes.csv}"

export MONGO_CONTAINER="${MONGO_CONTAINER:-crime-info-mongodb}"
export MONGO_DB="${MONGO_DB:-crime_info_service}"
export MONGO_COLLECTION="${MONGO_COLLECTION:-australian_suburbs}"

if ! docker inspect "$MONGO_CONTAINER" >/dev/null 2>&1; then
  echo "MongoDB container '$MONGO_CONTAINER' is not running." >&2
  echo "Start it with: docker compose -f infra/docker-compose-mongo.yml up -d" >&2
  exit 1
fi

echo "Building suburb GeoJSON from ${POSTCODES_CSV}..."
python3 "${ROOT}/infra/build-australian-suburbs-geojson.py" "${POSTCODES_CSV}"

echo "Importing into ${MONGO_DB}.${MONGO_COLLECTION}..."
python3 "${ROOT}/infra/import-australian-suburbs.py"

echo "Done."
