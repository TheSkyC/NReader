"""Stage a translation pack and update the local feed manifest."""

import argparse
import hashlib
import json
import re
from pathlib import Path

from scripts.build_tag_translation_pack import encode_normalized_pack, parse_pack, validate_locale, validate_revision
from scripts.tag_catalog import MAX_BYTES, atomic_write, decode_json, parse_catalog, read_bounded

MAX_MANIFEST_BYTES = 32 * 1024
MAX_LOCALES = 128
IDENTITY = {"schemaVersion": 1, "source": "nreader-translations", "kind": "translations"}
PACK_FIELDS = {"locale", "revision", "sizeBytes", "sha256"}


def parse_manifest(data: bytes) -> dict:
    if len(data) > MAX_MANIFEST_BYTES:
        raise ValueError("Translation manifest exceeds size limit")
    manifest = decode_json(data)
    if (not isinstance(manifest, dict) or manifest.keys() != {*IDENTITY, "packs"}
            or type(manifest["schemaVersion"]) is not int
            or any(manifest[key] != value for key, value in IDENTITY.items())):
        raise ValueError("Invalid translation manifest")
    rows = manifest["packs"]
    if not isinstance(rows, list) or not 1 <= len(rows) <= MAX_LOCALES:
        raise ValueError("Translation manifest locale count outside limits")

    packs = {}
    for row in rows:
        if (not isinstance(row, dict) or row.keys() != PACK_FIELDS
                or type(row["sizeBytes"]) is not int or not 1 <= row["sizeBytes"] <= MAX_BYTES
                or not isinstance(row["sha256"], str) or not re.fullmatch("[0-9a-f]{64}", row["sha256"])):
            raise ValueError("Invalid translation descriptor")
        locale = validate_locale(row["locale"])
        validate_revision(row["revision"])
        if locale in packs:
            raise ValueError("Duplicate translation manifest locale")
        packs[locale] = row
    return packs


def prepare_release(pack_path: Path, catalog_ids, output: Path) -> Path:
    data = read_bounded(pack_path, MAX_BYTES)
    pack = parse_pack(data, catalog_ids)
    locale = pack["locale"]
    revision = pack["revision"]
    # Publish the reviewed bytes exactly and keep the wire format within the
    # Android stream parser's bounds even if a hand-edited pack parses locally.
    normalized = {key: pack[key] for key in ("schemaVersion", "locale", "revision", "entries")}
    if data != encode_normalized_pack(normalized):
        raise ValueError("Release input must be the exact pack produced by the builder or review tool")
    digest = hashlib.sha256(data).hexdigest()
    descriptor = {"locale": locale, "revision": revision, "sizeBytes": len(data), "sha256": digest}

    pointer = output / "manifest.json"
    packs = parse_manifest(read_bounded(pointer, MAX_MANIFEST_BYTES)) if pointer.exists() else {}
    prior = packs.get(locale)
    if prior is None and len(packs) >= MAX_LOCALES:
        raise ValueError("Translation manifest locale count outside limits")
    if prior is not None and (revision < prior["revision"] or (revision == prior["revision"] and descriptor != prior)):
        raise ValueError("A locale revision cannot be lowered or reused for different bytes")

    for other_locale, retained in packs.items():
        if other_locale == locale:
            continue
        retained_path = output / f'translation-{retained["sha256"]}.json'
        try:
            retained_data = read_bounded(retained_path, retained["sizeBytes"])
        except (OSError, ValueError) as error:
            raise ValueError(f"Cannot verify retained translation payload for {other_locale}") from error
        if (len(retained_data) != retained["sizeBytes"]
                or hashlib.sha256(retained_data).hexdigest() != retained["sha256"]):
            raise ValueError(f"Cannot verify retained translation payload for {other_locale}")

    output.mkdir(parents=True, exist_ok=True)
    payload = output / f"translation-{digest}.json"
    if payload.exists():
        if read_bounded(payload, MAX_BYTES) != data:
            raise ValueError("Existing immutable file does not match its digest")
    else:
        atomic_write(payload, data)

    if descriptor != prior:
        packs[locale] = descriptor
        manifest = {**IDENTITY, "packs": [packs[key] for key in sorted(packs)]}
        atomic_write(pointer, (json.dumps(manifest, separators=(",", ":")) + "\n").encode("utf-8"))
    return pointer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path)
    parser.add_argument("--catalog", type=Path, default=Path(__file__).resolve().parents[1] / "data" / "tags.json")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        catalog = parse_catalog(read_bounded(args.catalog, MAX_BYTES))
        print(prepare_release(args.pack, catalog, args.output))
    except (OSError, ValueError) as error:
        parser.exit(1, f"Could not prepare translation release: {error}\n")


if __name__ == "__main__":
    main()
