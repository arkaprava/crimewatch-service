#!/usr/bin/env python3
"""Import australian-suburbs.geojson into MongoDB via batched mongosh scripts."""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
GEOJSON = ROOT / "data" / "suburbs" / "australian-suburbs.geojson"
BUILD_SCRIPT = ROOT / "infra" / "build-australian-suburbs-geojson.py"
POSTCODES_CSV = ROOT.parent / "crime_watch_au" / "tool" / "data" / "australian-postcodes.csv"
CONTAINER = os.environ.get("MONGO_CONTAINER", "crime-info-mongodb")
DB = os.environ.get("MONGO_DB", "crime_info_service")
COLLECTION = os.environ.get("MONGO_COLLECTION", "australian_suburbs")
BATCH_SIZE = 500


def run(cmd: list[str], *, input_text: str | None = None, check: bool = True) -> subprocess.CompletedProcess:
    return subprocess.run(
        cmd,
        input=input_text,
        text=True,
        capture_output=True,
        check=check,
    )


def docker_mongosh(js: str) -> str:
    uri = f"mongodb://127.0.0.1:27017/{DB}?serverSelectionTimeoutMS=600000"
    result = run(
        ["docker", "exec", "-i", CONTAINER, "mongosh", uri, "--quiet"],
        input_text=js,
    )
    if result.stderr.strip():
        print(result.stderr.strip(), file=sys.stderr)
    return result.stdout.strip()


def ensure_geojson() -> None:
    if GEOJSON.exists():
        return
    if not BUILD_SCRIPT.exists():
        raise SystemExit(f"Missing build script: {BUILD_SCRIPT}")
    print(f"Building GeoJSON from {POSTCODES_CSV}...")
    run(["python3", str(BUILD_SCRIPT), str(POSTCODES_CSV)])


def feature_to_doc(feature: dict, cached_at: str) -> dict | None:
    props = feature.get("properties") or {}
    suburb_id = props.get("id")
    name = props.get("name")
    state = props.get("state")
    if not suburb_id or not name or not state:
        return None

    centroid = None
    if isinstance(props.get("centroid"), list) and len(props["centroid"]) >= 2:
        centroid = {"type": "Point", "coordinates": props["centroid"]}

    perimeter = None
    geometry = feature.get("geometry") or {}
    if geometry.get("type") == "Polygon":
        perimeter = {"type": "Polygon", "coordinates": geometry["coordinates"]}

    return {
        "_id": suburb_id,
        "name": name,
        "state": str(state).upper(),
        "postcode": props.get("postcode"),
        "aliases": props.get("aliases") or [],
        "centroid": centroid,
        "perimeter": perimeter,
        "source": props.get("source") or "australian-postcodes",
        "cachedAt": {"$date": cached_at},
    }


def import_batches(docs: list[dict]) -> int:
    print(f"Replacing existing documents in {DB}.{COLLECTION}...")
    docker_mongosh(f"db.{COLLECTION}.deleteMany({{}})")

    inserted = 0
    total_batches = (len(docs) + BATCH_SIZE - 1) // BATCH_SIZE
    for index in range(0, len(docs), BATCH_SIZE):
        batch = docs[index : index + BATCH_SIZE]
        batch_no = index // BATCH_SIZE + 1
        payload = json.dumps(batch, separators=(",", ":"))

        with tempfile.NamedTemporaryFile("w", suffix=".js", delete=False) as handle:
            handle.write(f"db.{COLLECTION}.insertMany({payload}, {{ordered: false}});\n")
            temp_path = Path(handle.name)

        remote = f"/tmp/import-suburbs-{batch_no}.js"
        run(["docker", "cp", str(temp_path), f"{CONTAINER}:{remote}"])
        temp_path.unlink(missing_ok=True)

        docker_mongosh(f'load("{remote}")')
        inserted += len(batch)
        print(f"  Inserted batch {batch_no}/{total_batches} ({inserted}/{len(docs)})", flush=True)

    return inserted


def verify() -> None:
    count = docker_mongosh(f"db.{COLLECTION}.estimatedDocumentCount()")
    print(f"Estimated document count: {count}")

    for city, state in [("Adelaide", "SA"), ("Sydney", "NSW"), ("Balga", "WA")]:
        query = json.dumps({"name": city, "state": state})
        result = docker_mongosh(
            f"JSON.stringify(db.{COLLECTION}.findOne({query}, {{_id:1,name:1,state:1,postcode:1}}))"
        )
        print(f"  {city}, {state}: {result or 'NOT FOUND'}")


def main() -> None:
    if run(["docker", "inspect", CONTAINER], check=False).returncode != 0:
        raise SystemExit(f"MongoDB container '{CONTAINER}' is not running.")

    ensure_geojson()
    data = json.loads(GEOJSON.read_text())
    cached_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")

    docs = []
    for feature in data.get("features", []):
        doc = feature_to_doc(feature, cached_at)
        if doc is not None:
            docs.append(doc)

    if not docs:
        raise SystemExit("No suburb documents to import.")

    print(f"Importing {len(docs)} suburbs from {GEOJSON}...")
    inserted = import_batches(docs)
    print(f"Imported {inserted} suburbs.")
    verify()


if __name__ == "__main__":
    main()
