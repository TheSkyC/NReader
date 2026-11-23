"""Stage translation packs and update the local feed manifest."""

import argparse
import hashlib
import json
import re
from pathlib import Path

from scripts.build_tag_translation_pack import (
    encode_normalized_pack, encode_pack, parse_pack, validate_locale, validate_revision,
)
from scripts.review_tag_translations import REPORT_SCHEMA_VERSION
from scripts.tag_catalog import (
    MAX_BYTES, MAX_MANIFEST_STRING_UNITS, atomic_write, check_feed_filename, decode_json, feed_filename_index,
    parse_catalog,
    read_bounded, read_verified_payload,
)

MAX_MANIFEST_BYTES = 32 * 1024
MAX_LOCALES = 128
IDENTITY = {"schemaVersion": 1, "source": "nreader-translations", "kind": "translations"}
PACK_FIELDS = {"locale", "revision", "sizeBytes", "sha256"}
REPORT_FIELDS = {"schemaVersion", "locale", "baseRevision", "baseSha256", "candidateRevision",
                 "proposalSha256", "candidateSha256", "changes"}


def parse_manifest(data: bytes) -> dict:
    if len(data) > MAX_MANIFEST_BYTES:
        raise ValueError("Translation manifest exceeds size limit")
    manifest = decode_json(data, max_wire_string_units=MAX_MANIFEST_STRING_UNITS)
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


def parse_release_pack(data: bytes, catalog_ids) -> dict:
    pack = parse_pack(data, catalog_ids)
    # Publish the reviewed bytes exactly and keep the wire format within the
    # Android stream parser's bounds even if a hand-edited pack parses locally.
    normalized = {key: pack[key] for key in ("schemaVersion", "locale", "revision", "entries")}
    if data != encode_normalized_pack(normalized):
        raise ValueError("Release input must be the exact pack produced by the builder or review tool")
    return normalized


def payload_name(digest: str) -> str:
    return f"translation-{digest}.json"


def verify_payload(directory: Path, descriptor: dict, catalog_ids, names=None) -> dict:
    digest = descriptor["sha256"]
    payload = directory / payload_name(digest)
    check_feed_filename(payload, names)
    data = read_verified_payload(payload, descriptor["sizeBytes"], digest)
    pack = parse_pack(data, catalog_ids)
    if pack["locale"] != descriptor["locale"] or pack["revision"] != descriptor["revision"]:
        raise ValueError("Payload locale or revision does not match manifest")
    return pack


def parse_review(data: bytes) -> dict:
    report = decode_json(data)
    if (not isinstance(report, dict) or report.keys() != REPORT_FIELDS
            or type(report["schemaVersion"]) is not int or report["schemaVersion"] != REPORT_SCHEMA_VERSION
            or not isinstance(report["changes"], list) or not report["changes"]):
        raise ValueError("Invalid translation review report")
    validate_locale(report["locale"])
    validate_revision(report["candidateRevision"])
    base_revision = report["baseRevision"]
    if base_revision is not None:
        validate_revision(base_revision)
        if base_revision >= report["candidateRevision"]:
            raise ValueError("Invalid translation review base revision")
    for key in ("proposalSha256", "candidateSha256"):
        if not isinstance(report[key], str) or not re.fullmatch("[0-9a-f]{64}", report[key]):
            raise ValueError("Invalid translation review digest")
    base_digest = report["baseSha256"]
    if ((base_revision is None) != (base_digest is None)
            or (base_digest is not None and (not isinstance(base_digest, str)
                                             or not re.fullmatch("[0-9a-f]{64}", base_digest)))):
        raise ValueError("Invalid translation review base digest")
    return report


def prepare_release(pack_path: Path, catalog_ids, output: Path) -> Path:
    return prepare_releases([pack_path], catalog_ids, output)


def prepare_releases(pack_paths, catalog_ids, output: Path, review_paths=None) -> Path:
    candidates = {}
    for pack_path in pack_paths:
        data = read_bounded(pack_path, MAX_BYTES)
        pack = parse_release_pack(data, catalog_ids)
        locale = pack["locale"]
        if locale in candidates:
            raise ValueError(f"Duplicate candidate locale: {locale}")
        if len(candidates) >= MAX_LOCALES:
            raise ValueError("Translation manifest locale count outside limits")
        digest = hashlib.sha256(data).hexdigest()
        descriptor = {"locale": locale, "revision": pack["revision"], "sizeBytes": len(data), "sha256": digest}
        candidates[locale] = (pack_path, descriptor)
    if not candidates:
        raise ValueError("At least one translation pack is required")

    reviews = None
    if review_paths is not None:
        reviews = {}
        for path in review_paths:
            report = parse_review(read_bounded(path, MAX_BYTES))
            locale = report["locale"]
            if locale in reviews:
                raise ValueError(f"Duplicate review locale: {locale}")
            reviews[locale] = report
        if reviews.keys() != candidates.keys():
            raise ValueError("Provide one review report for every candidate locale")
        for locale, (_, descriptor) in candidates.items():
            report = reviews[locale]
            if (report["candidateRevision"] != descriptor["revision"]
                    or report["candidateSha256"] != descriptor["sha256"]):
                raise ValueError(f"Review does not match candidate for {locale}")

    pointer = output / "manifest.json"
    names = feed_filename_index(output)
    check_feed_filename(pointer, names)
    packs = parse_manifest(read_bounded(pointer, MAX_MANIFEST_BYTES)) if pointer.exists() else {}
    if len(packs.keys() | candidates.keys()) > MAX_LOCALES:
        raise ValueError("Translation manifest locale count outside limits")
    for locale, (_, descriptor) in candidates.items():
        prior = packs.get(locale)
        if prior is not None and (descriptor["revision"] < prior["revision"]
                or (descriptor["revision"] == prior["revision"] and descriptor != prior)):
            raise ValueError("A locale revision cannot be lowered or reused for different bytes")

    for retained_locale, retained in packs.items():
        replacement = candidates.get(retained_locale)
        if replacement is not None and retained == replacement[1]:
            continue
        try:
            # A superseded pack may refer to IDs removed from the new catalog.
            pack = verify_payload(output, retained, None if replacement is not None else catalog_ids, names)
            if reviews is not None and replacement is not None:
                report = reviews[retained_locale]
                normalized = encode_pack(pack["entries"], retained_locale, retained["revision"], None)
                digest = hashlib.sha256(normalized).hexdigest()
                if report["baseRevision"] != retained["revision"] or report["baseSha256"] != digest:
                    raise ValueError("Review base does not match the current release")
        except (OSError, ValueError) as error:
            raise ValueError(f"Cannot verify retained translation payload for {retained_locale}: {error}") from error

    if reviews is not None:
        for locale in candidates.keys() - packs.keys():
            if reviews[locale]["baseRevision"] is not None:
                raise ValueError(f"Review base does not match the current release for {locale}")

    output.mkdir(parents=True, exist_ok=True)
    for pack_path, descriptor in candidates.values():
        data = read_verified_payload(pack_path, descriptor["sizeBytes"], descriptor["sha256"])
        payload = output / payload_name(descriptor["sha256"])
        check_feed_filename(payload, names)
        if payload.exists():
            if read_bounded(payload, MAX_BYTES) != data:
                raise ValueError("Existing immutable file does not match its digest")
        else:
            atomic_write(payload, data)

    if any(packs.get(locale) != descriptor for locale, (_, descriptor) in candidates.items()):
        packs.update({locale: descriptor for locale, (_, descriptor) in candidates.items()})
        manifest = {**IDENTITY, "packs": [packs[key] for key in sorted(packs)]}
        atomic_write(pointer, (json.dumps(manifest, separators=(",", ":")) + "\n").encode("utf-8"))
    return pointer


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pack", type=Path, nargs="+")
    parser.add_argument("--catalog", type=Path, default=Path(__file__).resolve().parents[1] / "data" / "tags.json")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--review", type=Path, action="append", help="Review report; repeat for each candidate")
    args = parser.parse_args()
    try:
        catalog = parse_catalog(read_bounded(args.catalog, MAX_BYTES))
        print(prepare_releases(args.pack, catalog, args.output, args.review))
    except (OSError, ValueError) as error:
        parser.exit(1, f"Could not prepare translation release: {error}\n")


if __name__ == "__main__":
    main()
