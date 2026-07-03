#!/usr/bin/env bash
# Poll crime_info_service MongoDB (local Docker) and print the dataset in the terminal.
#
# Usage (from project root):
#   ./infra/watch-mongo-dataset.sh
#   INTERVAL=3 LIMIT=100 ./infra/watch-mongo-dataset.sh
#
set -euo pipefail

CONTAINER="${MONGO_CONTAINER:-crime-info-mongodb}"
DB="${MONGO_DB:-crime_info_service}"
COLLECTION="${MONGO_COLLECTION:-crime_incidents}"
INTERVAL="${INTERVAL:-5}"
LIMIT="${LIMIT:-0}"

if ! docker inspect "$CONTAINER" >/dev/null 2>&1; then
  echo "MongoDB container '$CONTAINER' is not running." >&2
  echo "Start it with: docker compose -f infra/docker-compose-mongo.yml up -d" >&2
  exit 1
fi

poll_once() {
  docker exec "$CONTAINER" mongosh --quiet "$DB" --eval "
    const limit = ${LIMIT};
    const total = db.${COLLECTION}.countDocuments();
    const cursor = db.${COLLECTION}.find(
      {},
      {
        title: 1,
        source: 1,
        crimeType: 1,
        severity: 1,
        status: 1,
        occurredAt: 1,
        updatedAt: 1,
        'location.city': 1,
        'location.state': 1
      }
    ).sort({ updatedAt: -1 });
    const docs = limit > 0 ? cursor.limit(limit).toArray() : cursor.toArray();

    print('Total documents: ' + total + (limit > 0 ? ' (showing ' + docs.length + ' most recently updated)' : ''));
    print('');
    print(
      '#'.padEnd(5) +
      ' | ' + 'Title'.padEnd(22) +
      ' | ' + 'Type'.padEnd(10) +
      ' | ' + 'Severity'.padEnd(8) +
      ' | ' + 'Status'.padEnd(10) +
      ' | ' + 'Occurred'.padEnd(12) +
      ' | ' + 'Location'.padEnd(24) +
      ' | Source'
    );
    print('-'.repeat(120));

    docs.forEach((doc, index) => {
      const city = doc.location && doc.location.city ? doc.location.city : '';
      const state = doc.location && doc.location.state ? doc.location.state : '';
      const location = [city, state].filter(Boolean).join(', ');
      const occurred = doc.occurredAt ? doc.occurredAt.toISOString().slice(0, 10) : '';
      print(
        String(index + 1).padEnd(5) +
        ' | ' + String(doc.title || '').slice(0, 22).padEnd(22) +
        ' | ' + String(doc.crimeType || '').padEnd(10) +
        ' | ' + String(doc.severity || '').padEnd(8) +
        ' | ' + String(doc.status || '').padEnd(10) +
        ' | ' + occurred.padEnd(12) +
        ' | ' + String(location).slice(0, 24).padEnd(24) +
        ' | ' + String(doc.source || '')
      );
    });
  "
}

echo "Watching ${DB}.${COLLECTION} in container ${CONTAINER} (refresh every ${INTERVAL}s, Ctrl+C to stop)"
echo ""

while true; do
  printf '\033[H\033[J'
  echo "=== ${DB}.${COLLECTION} — $(date '+%Y-%m-%d %H:%M:%S %Z') ==="
  echo ""
  poll_once
  sleep "$INTERVAL"
done
