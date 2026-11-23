"""Validate local tag translations and build a deterministic locale pack."""

import argparse
import json
import re
import unicodedata
from pathlib import Path

if __package__:
    from scripts.tag_catalog import MAX_BYTES, MAX_INTEGER, atomic_write, decode_json, parse_catalog, read_bounded
else:
    from tag_catalog import MAX_BYTES, MAX_INTEGER, atomic_write, decode_json, parse_catalog, read_bounded

MAX_ENTRIES = 250_000
MAX_TEXT_LENGTH = 256
MAX_WIRE_STRING_UNITS = MAX_TEXT_LENGTH * 12 + 1
MAX_ALIASES = 20
PACK_SCHEMA_VERSION = 1
LOCALE_PATTERN = re.compile(r"[a-z]{2,3}(?:-[A-Z][a-z]{3})?(?:-(?:[A-Z]{2}|[0-9]{3}))?\Z")


def validate_locale(locale):
    if (not isinstance(locale, str) or not LOCALE_PATTERN.fullmatch(locale)
            or locale.split("-", 1)[0] in {"in", "iw", "ji", "und"}):
        raise ValueError("Invalid canonical translation locale")
    return locale


def validate_text(value):
    if not isinstance(value, str):
        raise ValueError("Translation text must be a string")
    value = unicodedata.normalize("NFC", value).strip(" ")
    if not value or len(value) > MAX_TEXT_LENGTH:
        raise ValueError("Translation text length outside limits")
    if any(unicodedata.category(character) in {"Cc", "Cs"} for character in value):
        raise ValueError("Translation text contains control characters")
    return value


def search_key(value):
    # Lowercase the full string, then remove contextual final-sigma behavior so
    # a prefix key is stable when the same text continues with more characters.
    return unicodedata.normalize("NFC", value).lower().replace("\u03c2", "\u03c3")


def validate_revision(revision):
    if type(revision) is not int or not 1 <= revision <= MAX_INTEGER:
        raise ValueError("Invalid translation revision")


def normalize_translation(value):
    if not isinstance(value, dict) or value.keys() - {"name", "aliases"} or "name" not in value:
        raise ValueError("Invalid translation fields")
    name = validate_text(value["name"])
    aliases = value.get("aliases", [])
    if not isinstance(aliases, list) or len(aliases) > MAX_ALIASES:
        raise ValueError("Translation alias count outside limits")
    normalized_aliases = []
    seen_aliases = set()
    for alias in aliases:
        alias = validate_text(alias)
        alias_key = search_key(alias)
        if alias_key in seen_aliases:
            raise ValueError("Duplicate normalized translation alias")
        seen_aliases.add(alias_key)
        normalized_aliases.append(alias)
    return {"name": name, "aliases": normalized_aliases}


def normalize_entries(rows, catalog_ids):
    if not isinstance(rows, list) or not 1 <= len(rows) <= MAX_ENTRIES:
        raise ValueError("Translation entry count outside limits")

    entries = []
    seen_ids = set()
    for row in rows:
        if not isinstance(row, dict) or "id" not in row:
            raise ValueError("Invalid translation entry fields")
        tag_id = row["id"]
        if (type(tag_id) is not int or not 1 <= tag_id <= MAX_INTEGER
                or (catalog_ids is not None and tag_id not in catalog_ids)):
            raise ValueError("Translation references an unknown tag ID")
        if tag_id in seen_ids:
            raise ValueError("Duplicate translation tag ID")
        seen_ids.add(tag_id)

        translation = normalize_translation({key: value for key, value in row.items() if key != "id"})
        entries.append({"id": tag_id, **translation})

    entries.sort(key=lambda entry: entry["id"])
    return entries


def parse_pack(data, catalog_ids):
    pack = decode_json(data, max_wire_string_units=MAX_WIRE_STRING_UNITS)
    if (not isinstance(pack, dict) or pack.keys() != {"schemaVersion", "locale", "revision", "entries"}
            or type(pack["schemaVersion"]) is not int or pack["schemaVersion"] != PACK_SCHEMA_VERSION):
        raise ValueError("Invalid translation pack envelope")
    validate_locale(pack["locale"])
    validate_revision(pack["revision"])
    pack["entries"] = normalize_entries(pack["entries"], catalog_ids)
    return pack


def encode_pack(rows, locale, revision, catalog_ids):
    validate_locale(locale)
    validate_revision(revision)
    entries = normalize_entries(rows, catalog_ids)
    return encode_normalized_pack(
        {"schemaVersion": PACK_SCHEMA_VERSION, "locale": locale, "revision": revision, "entries": entries})


def encode_normalized_pack(pack):
    encoded = (json.dumps(pack, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
    if len(encoded) > MAX_BYTES:
        raise ValueError("Translation pack exceeds size limit")
    return encoded


def build_pack(source, locale, revision, catalog_ids):
    return encode_pack(decode_json(source), locale, revision, catalog_ids)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="JSON array of translated tag entries")
    parser.add_argument("--locale", required=True, help="Canonical BCP 47 locale, for example zh-Hans")
    parser.add_argument("--revision", required=True, type=int, help="Positive locale pack revision")
    parser.add_argument("--catalog", type=Path,
                        default=Path(__file__).resolve().parents[1] / "data" / "tags.json")
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args(argv)

    try:
        if args.output.resolve() in (args.source.resolve(), args.catalog.resolve()):
            raise ValueError("Output must be separate from the input files")
        catalog = parse_catalog(read_bounded(args.catalog, MAX_BYTES))
        output = build_pack(read_bounded(args.source, MAX_BYTES), args.locale, args.revision, set(catalog))
        args.output.parent.mkdir(parents=True, exist_ok=True)
        atomic_write(args.output, output)
    except (OSError, ValueError) as error:
        parser.error(str(error))
    print(f"Wrote revision {args.revision} for {args.locale} ({len(output)} bytes)")


if __name__ == "__main__":
    main()
