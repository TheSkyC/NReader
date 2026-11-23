"""Prepare a local manifest and immutable payload for a catalog release."""

import argparse
import hashlib
import json
import re
from pathlib import Path

from scripts.tag_catalog import (
    MAX_BYTES, MAX_INTEGER, MAX_MANIFEST_STRING_UNITS, atomic_write, check_feed_filename, decode_json, parse_catalog,
    read_bounded, read_verified_payload,
)

MAX_MANIFEST_BYTES = 4096
IDENTITY = {"schemaVersion": 1, "source": "nreader-catalog", "kind": "catalog"}
MANIFEST_FIELDS = {*IDENTITY, "revision", "sizeBytes", "sha256"}


def parse_manifest(data: bytes) -> dict:
    if len(data) > MAX_MANIFEST_BYTES:
        raise ValueError("Catalog manifest exceeds size limit")
    manifest = decode_json(data, max_wire_string_units=MAX_MANIFEST_STRING_UNITS)
    if (not isinstance(manifest, dict) or manifest.keys() != MANIFEST_FIELDS
            or type(manifest["schemaVersion"]) is not int
            or any(manifest[key] != value for key, value in IDENTITY.items())
            or type(manifest["revision"]) is not int or not 1 <= manifest["revision"] <= MAX_INTEGER
            or type(manifest["sizeBytes"]) is not int or not 1 <= manifest["sizeBytes"] <= MAX_BYTES
            or not isinstance(manifest["sha256"], str)
            or not re.fullmatch("[0-9a-f]{64}", manifest["sha256"])):
        raise ValueError("Invalid catalog manifest")
    return manifest


def payload_name(digest: str) -> str:
    return f"catalog-{digest}.json"


def read_payload(directory: Path, manifest: dict) -> dict:
    digest = manifest["sha256"]
    payload = directory / payload_name(digest)
    check_feed_filename(payload)
    data = read_verified_payload(payload, manifest["sizeBytes"], digest)
    return parse_catalog(data)


def prepare_release(catalog: Path, revision: int, output: Path) -> Path:
    if type(revision) is not int or not 1 <= revision <= MAX_INTEGER:
        raise ValueError("Invalid catalog release revision")
    data = read_bounded(catalog, MAX_BYTES)
    tags = parse_catalog(data)
    digest = hashlib.sha256(data).hexdigest()
    manifest = {**IDENTITY, "revision": revision, "sizeBytes": len(data), "sha256": digest}

    pointer = output / "manifest.json"
    check_feed_filename(pointer)
    previous = parse_manifest(read_bounded(pointer, MAX_MANIFEST_BYTES)) if pointer.exists() else None
    if previous is not None and (revision < previous["revision"]
                                  or (revision == previous["revision"] and manifest != previous)):
        raise ValueError("A catalog revision cannot be lowered or reused for different bytes")
    if previous is not None and previous["sha256"] != digest:
        for tag_id, tag in read_payload(output, previous).items():
            if tag_id not in tags or tag.category != tags[tag_id].category:
                raise ValueError(f"Catalog identity must be retained for tag ID {tag_id}")

    output.mkdir(parents=True, exist_ok=True)
    payload = output / payload_name(digest)
    check_feed_filename(payload)
    if payload.exists():
        if read_bounded(payload, MAX_BYTES) != data:
            raise ValueError("Existing immutable file does not match its digest")
    else:
        atomic_write(payload, data)
    if previous != manifest:
        atomic_write(pointer, (json.dumps(manifest, separators=(",", ":")) + "\n").encode("utf-8"))
    return pointer


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--revision", type=int, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        print(prepare_release(args.catalog, args.revision, args.output))
    except (OSError, ValueError) as error:
        parser.exit(1, f"Could not prepare catalog release: {error}\n")


if __name__ == "__main__":
    main()
