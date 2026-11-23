"""Convert the EhTagTranslation Markdown database into NReader source rows."""

import argparse
import html
import json
import re
from collections import defaultdict
from pathlib import Path

from scripts.build_tag_translation_pack import search_key, validate_text
from scripts.tag_catalog import MAX_BYTES, atomic_write, parse_catalog, read_bounded


NAMESPACE_FILES = {
    1: ("parody",),
    2: ("character",),
    3: ("female", "male", "mixed", "location", "other", "reclass"),
    4: ("artist",),
    5: ("group",),
    6: ("language",),
}
# The source database has no category namespace; these seven canonical catalog
# rows use stable Chinese labels rather than being silently left untranslated.
MANUAL_TRANSLATIONS = {
    (7, "doujinshi"): ["同人志"],
    (7, "manga"): ["漫画"],
    (7, "non-h"): ["非H"],
    (7, "western"): ["欧美"],
    (7, "artistcg"): ["画师CG"],
    (7, "misc"): ["杂项"],
}
MAX_SOURCE_BYTES = MAX_BYTES


def split_table_row(line):
    """Split a Markdown row while preserving escaped pipe characters."""
    line = line.rstrip("\r\n")
    if not line.startswith("| ") or not line.endswith("|"):
        return None

    cells = []
    current = []
    escaped = False
    for character in line[1:-1]:
        if character == "|" and not escaped:
            cells.append("".join(current).strip())
            current = []
            continue
        current.append(character)
        escaped = character == "\\" and not escaped
    cells.append("".join(current).strip())
    return cells


def clean_name(value):
    """Keep the display text from a Markdown name cell."""
    value = html.unescape(value)
    value = re.sub(r"!\[[^\]]*\]\([^)]*\)", "", value)
    value = re.sub(r"\[([^\]]+)\]\([^)]*\)", r"\1", value)
    value = re.sub(r"<[^>]*>", "", value)
    value = value.replace("`", "").replace("\\|", "|").replace("\\\\", "\\")
    return re.sub(r"\s+", " ", value).strip()


def read_namespace(path):
    rows = []
    for line in path.read_text(encoding="utf-8").splitlines():
        cells = split_table_row(line)
        if not cells or len(cells) < 2 or "原始标签" in line or line.startswith("| ---"):
            continue
        raw, translated = cells[0].strip().casefold(), clean_name(cells[1])
        if not raw or raw.startswith("==") or raw.startswith("▼") or raw.startswith("▲") or not translated:
            continue
        rows.append((raw, translated))
    return rows


def collect_translations(source_dir):
    translations = defaultdict(list)
    source_rows = 0
    for tag_type, namespaces in NAMESPACE_FILES.items():
        for namespace in namespaces:
            path = source_dir / f"{namespace}.md"
            if not path.is_file():
                raise ValueError(f"Missing source namespace: {path}")
            for raw, translated in read_namespace(path):
                source_rows += 1
                key = (tag_type, raw)
                if translated not in translations[key]:
                    translations[key].append(translated)
    return translations, source_rows


def build_rows(catalog, translations):
    rows = []
    missing = []
    conflicts = []
    invalid = []
    for tag_id, tag in catalog.items():
        canonical, tag_type = tag.title, tag.category
        candidates = MANUAL_TRANSLATIONS.get((tag_type, canonical.casefold()))
        if candidates is None:
            candidates = translations.get((tag_type, canonical.casefold()))
        if not candidates:
            missing.append({"id": tag_id, "canonicalName": canonical, "type": tag_type})
            continue

        valid = []
        for candidate in candidates:
            try:
                candidate = validate_text(candidate)
            except ValueError as error:
                invalid.append({"id": tag_id, "canonicalName": canonical, "value": candidate,
                                "reason": str(error)})
                continue
            if candidate not in valid:
                valid.append(candidate)
        if not valid:
            missing.append({"id": tag_id, "canonicalName": canonical, "type": tag_type})
            continue

        if len(valid) > 1:
            conflicts.append({"id": tag_id, "canonicalName": canonical, "type": tag_type,
                              "candidates": valid})
        name = valid[0]
        name_key = search_key(name)
        aliases = []
        for alias in valid[1:]:
            if search_key(alias) != name_key and search_key(alias) not in {search_key(value) for value in aliases}:
                aliases.append(alias)
        rows.append({"id": tag_id, "name": name, "aliases": aliases})
    return rows, missing, conflicts, invalid


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path, help="EhTagTranslation database directory")
    parser.add_argument("--catalog", type=Path, default=Path(__file__).resolve().parents[1] / "data" / "tags.json")
    parser.add_argument("--output", type=Path, required=True, help="JSON source rows for the pack builder")
    parser.add_argument("--report", type=Path, required=True, help="Matching and conflict report")
    args = parser.parse_args(argv)

    catalog = parse_catalog(read_bounded(args.catalog, MAX_BYTES))
    translations, source_rows = collect_translations(args.source)
    rows, missing, conflicts, invalid = build_rows(catalog, translations)
    document = {"entries": rows}
    report = {
        "catalogEntries": len(catalog),
        "sourceRows": source_rows,
        "sourceKeys": len(translations),
        "matchedEntries": len(rows),
        "missingEntries": len(missing),
        "conflicts": conflicts,
        "invalidSourceRows": invalid,
        "manualEntries": [tag_id for tag_id, tag in catalog.items()
                          if (tag.category, tag.title.casefold()) in MANUAL_TRANSLATIONS],
        "missing": missing,
    }

    output = (json.dumps(document["entries"], ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
    report_bytes = (json.dumps(report, ensure_ascii=False, indent=2) + "\n").encode("utf-8")
    if len(output) > MAX_SOURCE_BYTES or len(report_bytes) > MAX_SOURCE_BYTES:
        raise ValueError("Generated source or report exceeds the bounded file size")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    atomic_write(args.output, output)
    atomic_write(args.report, report_bytes)
    print(f"Matched {len(rows)} of {len(catalog)} catalog entries; {len(conflicts)} conflicts")


if __name__ == "__main__":
    main()
