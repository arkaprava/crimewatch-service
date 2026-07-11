#!/usr/bin/env bash
# Build and import all Australian suburbs into MongoDB.
#
# Usage (from crimewatch-service root):
#   ./infra/import-australian-suburbs.sh
#
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CONTAINER="${MONGO_CONTAINER:-crime-info-mongodb}"
DB="${MONGO_DB:-crime_info_service}"
COLLECTION="${MONGO_COLLECTION:-australian_suburbs}"
GEOJSON="${ROOT}/data/suburbs/australian-suburbs.geojson"
POSTCODES_CSV="${POSTCODES_CSV:-${ROOT}/../crime_watch_au/tool/data/australian-postcodes.csv}"

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "MongoDB container '$CONTAINER' is not running." >&2
  echo "Start it with: docker compose -f infra/docker-compose-mongo.yml up -d" >&2
  exit 1
fi

echo "Building suburb GeoJSON from ${POSTCODES_CSV}..."
python3 "${ROOT}/infra/build-australian-suburbs-geojson.py" "${POSTCODES_CSV}"

if [[ ! -f "${GEOJSON}" ]]; then
  echo "GeoJSON file not found after build: ${GEOJSON}" >&2
  exit 1
fi

REMOTE="/tmp/australian-suburbs.geojson"
echo "Copying GeoJSON into container ${CONTAINER}:${REMOTE}..."
docker cp "${GEOJSON}" "${CONTAINER}:${REMOTE}"

echo "Importing into ${DB}.${COLLECTION}..."
docker exec "$CONTAINER" mongosh --quiet "$DB" --eval "
const path = '${REMOTE}';
const raw = cat(path);
const parsed = JSON.parse(raw);
const features = parsed.features || [];
const now = new Date();

function toPoint(coords) {
  return { type: 'Point', coordinates: coords };
}

function toPolygon(rings) {
  return { type: 'Polygon', coordinates: rings };
}

const docs = [];
for (const feature of features) {
  const props = feature.properties || {};
  const id = props.id;
  const name = props.name;
  const state = props.state;
  if (!id || !name || !state) continue;

  const centroid = Array.isArray(props.centroid) && props.centroid.length >= 2
    ? toPoint(props.centroid)
    : null;
  const perimeter = feature.geometry && feature.geometry.type === 'Polygon'
    ? toPolygon(feature.geometry.coordinates)
    : null;

  docs.push({
    _id: id,
    name,
    state: String(state).toUpperCase(),
    postcode: props.postcode || null,
    aliases: Array.isArray(props.aliases) ? props.aliases : [],
    centroid,
    perimeter,
    source: props.source || 'australian-postcodes',
    cachedAt: now,
  });
}

const existing = db.${COLLECTION}.countDocuments();
if (existing > 0) {
  print('Replacing ' + existing + ' existing suburb documents...');
  db.${COLLECTION}.deleteMany({});
}

if (docs.length === 0) {
  print('No suburb documents to import.');
  quit(1);
}

const batchSize = 1000;
let inserted = 0;
for (let i = 0; i < docs.length; i += batchSize) {
  const batch = docs.slice(i, i + batchSize);
  db.${COLLECTION}.insertMany(batch, { ordered: false });
  inserted += batch.length;
}

print('Imported ' + inserted + ' suburbs into ${DB}.${COLLECTION}.');
print('Indexes:');
db.${COLLECTION}.getIndexes().forEach(idx => print('  ' + idx.name));
"

echo "Done."
