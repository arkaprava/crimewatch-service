#!/usr/bin/env python3
"""Build data/suburbs/australian-suburbs.geojson from the bundled postcodes CSV."""

from __future__ import annotations

import csv
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CSV = ROOT.parent / "crime_watch_au" / "tool" / "data" / "australian-postcodes.csv"
OUT = ROOT / "data" / "suburbs" / "australian-suburbs.geojson"

# Approximate half-width of the bounding box in degrees (~1 km at mid-latitudes).
BOX_DELTA = 0.009


def display_name(name: str) -> str:
    cleaned = name.strip()
    if cleaned.isupper():
        return cleaned.title()
    return cleaned


def normalise(name: str) -> str:
    return re.sub(r"\s+", " ", re.sub(r"[^a-zA-Z0-9\s]", " ", name.strip())).strip().upper()


def slug(name: str) -> str:
    return re.sub(r"-+", "-", re.sub(r"[^A-Z0-9]+", "-", normalise(name))).strip("-")


def box_polygon(lng: float, lat: float, delta: float = BOX_DELTA) -> list[list[list[float]]]:
    return [
        [
            [lng - delta, lat - delta],
            [lng + delta, lat - delta],
            [lng + delta, lat + delta],
            [lng - delta, lat + delta],
            [lng - delta, lat - delta],
        ]
    ]


def add(
    entries: dict[str, dict],
    name: str,
    state: str,
    postcode: str | None = None,
    lat: float | None = None,
    lng: float | None = None,
) -> None:
    name = display_name(name)
    state = (state or "").strip().upper()
    if not name or not state:
        return

    key = f"{name.lower()}|{state}"
    record = {
        "name": name,
        "state": state,
        "postcode": str(postcode).strip() if postcode else None,
        "lat": round(float(lat), 6) if lat is not None else None,
        "lng": round(float(lng), 6) if lng is not None else None,
    }

    existing = entries.get(key)
    if existing:
        for field in ("postcode", "lat", "lng"):
            if record[field] is not None and existing.get(field) is None:
                existing[field] = record[field]
    else:
        entries[key] = record


def load_postcodes(csv_path: Path, entries: dict[str, dict]) -> None:
    csv.field_size_limit(sys.maxsize)
    with csv_path.open(newline="", encoding="utf-8") as handle:
        for row in csv.DictReader(handle):
            lat = lng = None
            if row.get("Lat") and row.get("Lon"):
                lat = float(row["Lat"])
                lng = float(row["Lon"])
            add(
                entries,
                row["Suburb"],
                row["State"],
                postcode=row.get("Postcode"),
                lat=lat,
                lng=lng,
            )


def to_feature(record: dict) -> dict | None:
    lat = record.get("lat")
    lng = record.get("lng")
    if lat is None or lng is None:
        return None

    name = record["name"]
    state = record["state"]
    suburb_id = f"{state}:{slug(name)}"
    aliases = sorted({normalise(name), name.upper(), name})

    return {
        "type": "Feature",
        "properties": {
            "id": suburb_id,
            "name": name,
            "state": state,
            "postcode": record.get("postcode"),
            "source": "australian-postcodes",
            "aliases": aliases,
            "centroid": [lng, lat],
        },
        "geometry": {
            "type": "Polygon",
            "coordinates": box_polygon(lng, lat),
        },
    }


def main() -> None:
    csv_path = Path(sys.argv[1]) if len(sys.argv) > 1 else DEFAULT_CSV
    if not csv_path.exists():
        raise SystemExit(f"Postcodes CSV not found: {csv_path}")

    entries: dict[str, dict] = {}
    load_postcodes(csv_path, entries)

    features = []
    skipped = 0
    for record in sorted(entries.values(), key=lambda item: (item["name"].lower(), item["state"])):
        feature = to_feature(record)
        if feature is None:
            skipped += 1
            continue
        features.append(feature)

    collection = {"type": "FeatureCollection", "features": features}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(collection, separators=(",", ":")))
    print(f"Wrote {len(features)} suburbs to {OUT} (skipped {skipped} without coordinates)")


if __name__ == "__main__":
    main()
