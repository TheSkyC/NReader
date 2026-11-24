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

# Curated display names take priority over the source database. They resolve
# meanings that the source encodes with emoji or gender glyphs, and correct
# translations that contradict the source description.
CURATED_TRANSLATIONS = {
    (3, "bull"): ["公牛"],
    (3, "cow"): ["母牛"],
    (3, "lion"): ["雄狮"],
    (3, "lioness"): ["雌狮"],
    (3, "cuntboy"): ["人妖（有阴道）"],
    (3, "shemale"): ["人妖（有阴茎）"],
    (3, "compilation"): ["合辑"],
    (3, "domination loss"): ["主导权丧失"],
    (3, "body writing"): ["人体书写"],
}

# Tag types whose names describe content rather than reproduce a proper name.
# Only these lose decorative emoji; artist, group, and character names keep the
# source spelling.
DESCRIPTIVE_TYPES = frozenset({1, 3, 6, 7})

# Formatting characters that never carry display meaning.
INVISIBLE_CHARACTERS = frozenset(
    {0x00AD, 0x180E, 0x200B, 0x200C, 0x200E, 0x200F, 0x2060, 0xFEFF}
)
# Emoji cluster components that are not `Extended_Pictographic` themselves.
EMOJI_COMPONENTS = frozenset({0x200D, 0x20E3, 0xFE0E, 0xFE0F})
# Gender glyphs that render as text and are not all `Extended_Pictographic`.
GENDER_GLYPHS = frozenset({0x26A2, 0x26A3})

# Unicode 16 `Extended_Pictographic` ranges.
EXTENDED_PICTOGRAPHIC_RANGES = (
    (0x00A9, 0x00A9), (0x00AE, 0x00AE), (0x203C, 0x203C), (0x2049, 0x2049),
    (0x2122, 0x2122), (0x2139, 0x2139), (0x2194, 0x2199), (0x21A9, 0x21AA),
    (0x231A, 0x231B), (0x2328, 0x2328), (0x23CF, 0x23CF), (0x23E9, 0x23F3),
    (0x23F8, 0x23FA), (0x24C2, 0x24C2), (0x25AA, 0x25AB), (0x25B6, 0x25B6),
    (0x25C0, 0x25C0), (0x25FB, 0x25FE), (0x2600, 0x2604), (0x260E, 0x260E),
    (0x2611, 0x2611), (0x2614, 0x2615), (0x2618, 0x2618), (0x261D, 0x261D),
    (0x2620, 0x2620), (0x2622, 0x2623), (0x2626, 0x2626), (0x262A, 0x262A),
    (0x262E, 0x262F), (0x2638, 0x263A), (0x2640, 0x2640), (0x2642, 0x2642),
    (0x2648, 0x2653), (0x265F, 0x2660), (0x2663, 0x2663), (0x2665, 0x2666),
    (0x2668, 0x2668), (0x267B, 0x267B), (0x267E, 0x267F), (0x2692, 0x2697),
    (0x2699, 0x2699), (0x269B, 0x269C), (0x26A0, 0x26A1), (0x26A7, 0x26A7),
    (0x26AA, 0x26AB), (0x26B0, 0x26B1), (0x26BD, 0x26BE), (0x26C4, 0x26C5),
    (0x26C8, 0x26C8), (0x26CE, 0x26CF), (0x26D1, 0x26D1), (0x26D3, 0x26D4),
    (0x26E9, 0x26EA), (0x26F0, 0x26F5), (0x26F7, 0x26FA), (0x26FD, 0x26FD),
    (0x2702, 0x2702), (0x2705, 0x2705), (0x2708, 0x270D), (0x270F, 0x270F),
    (0x2712, 0x2712), (0x2714, 0x2714), (0x2716, 0x2716), (0x271D, 0x271D),
    (0x2721, 0x2721), (0x2728, 0x2728), (0x2733, 0x2734), (0x2744, 0x2744),
    (0x2747, 0x2747), (0x274C, 0x274C), (0x274E, 0x274E), (0x2753, 0x2755),
    (0x2757, 0x2757), (0x2763, 0x2764), (0x2795, 0x2797), (0x27A1, 0x27A1),
    (0x27B0, 0x27B0), (0x27BF, 0x27BF), (0x2934, 0x2935), (0x2B05, 0x2B07),
    (0x2B1B, 0x2B1C), (0x2B50, 0x2B50), (0x2B55, 0x2B55), (0x3030, 0x3030),
    (0x303D, 0x303D), (0x3297, 0x3297), (0x3299, 0x3299), (0x1F004, 0x1F004),
    (0x1F02C, 0x1F02F), (0x1F094, 0x1F09F), (0x1F0AF, 0x1F0B0), (0x1F0C0, 0x1F0C0),
    (0x1F0CF, 0x1F0D0), (0x1F0F6, 0x1F0FF), (0x1F170, 0x1F171), (0x1F17E, 0x1F17F),
    (0x1F18E, 0x1F18E), (0x1F191, 0x1F19A), (0x1F1AE, 0x1F1E5), (0x1F201, 0x1F20F),
    (0x1F21A, 0x1F21A), (0x1F22F, 0x1F22F), (0x1F232, 0x1F23A), (0x1F23C, 0x1F23F),
    (0x1F249, 0x1F25F), (0x1F266, 0x1F321), (0x1F324, 0x1F393), (0x1F396, 0x1F397),
    (0x1F399, 0x1F39B), (0x1F39E, 0x1F3F0), (0x1F3F3, 0x1F3F5), (0x1F3F7, 0x1F3FA),
    (0x1F400, 0x1F4FD), (0x1F4FF, 0x1F53D), (0x1F549, 0x1F54E), (0x1F550, 0x1F567),
    (0x1F56F, 0x1F570), (0x1F573, 0x1F57A), (0x1F587, 0x1F587), (0x1F58A, 0x1F58D),
    (0x1F590, 0x1F590), (0x1F595, 0x1F596), (0x1F5A4, 0x1F5A5), (0x1F5A8, 0x1F5A8),
    (0x1F5B1, 0x1F5B2), (0x1F5BC, 0x1F5BC), (0x1F5C2, 0x1F5C4), (0x1F5D1, 0x1F5D3),
    (0x1F5DC, 0x1F5DE), (0x1F5E1, 0x1F5E1), (0x1F5E3, 0x1F5E3), (0x1F5E8, 0x1F5E8),
    (0x1F5EF, 0x1F5EF), (0x1F5F3, 0x1F5F3), (0x1F5FA, 0x1F64F), (0x1F680, 0x1F6C5),
    (0x1F6CB, 0x1F6D2), (0x1F6D5, 0x1F6E5), (0x1F6E9, 0x1F6E9), (0x1F6EB, 0x1F6F0),
    (0x1F6F3, 0x1F6FF), (0x1F7DA, 0x1F7FF), (0x1F80C, 0x1F80F), (0x1F848, 0x1F84F),
    (0x1F85A, 0x1F85F), (0x1F888, 0x1F88F), (0x1F8AE, 0x1F8AF), (0x1F8BC, 0x1F8BF),
    (0x1F8C2, 0x1F8CF), (0x1F8D9, 0x1F8FF), (0x1F90C, 0x1F93A), (0x1F93C, 0x1F945),
    (0x1F947, 0x1F9FF), (0x1FA58, 0x1FA5F), (0x1FA6E, 0x1FAFF), (0x1FC00, 0x1FFFD),
)
_EXTENDED_PICTOGRAPHIC = frozenset(
    code_point
    for first, last in EXTENDED_PICTOGRAPHIC_RANGES
    for code_point in range(first, last + 1)
)
# Emoji modifiers (skin tones) and regional indicators (flags).
_EMOJI_MODIFIERS = frozenset(range(0x1F3FB, 0x1F400)) | frozenset(range(0x1F1E6, 0x1F200))


def _strip_invisible(value):
    return "".join(character for character in value if ord(character) not in INVISIBLE_CHARACTERS)


def _strip_emoji(value):
    return "".join(
        character for character in value
        if ord(character) not in _EXTENDED_PICTOGRAPHIC
        and ord(character) not in EMOJI_COMPONENTS
        and ord(character) not in GENDER_GLYPHS
        and ord(character) not in _EMOJI_MODIFIERS
    )


def curate_name(tag_type, value):
    """Remove invisible or emoji characters from a translated display name."""
    value = _strip_invisible(value)
    if tag_type in DESCRIPTIVE_TYPES:
        value = _strip_emoji(value)
    return re.sub(r"\s+", " ", value).strip(" ")


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
    value = re.sub(r"\\([!-/:-@\[-`{-~])", r"\1", value)
    value = value.replace("`", "")
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


BRACKET_PAIRS = {"(": ")", "（": "）", "[": "]", "【": "】", "「": "」", "『": "』", "{": "}"}
BRACKET_CLOSERS = frozenset(BRACKET_PAIRS.values())


def split_top_level_pipes(value):
    """Split on ` | ` only where no bracket is open, so parenthetical lists stay intact."""
    parts = []
    current = []
    stack = []
    index = 0
    while index < len(value):
        character = value[index]
        if value.startswith(" | ", index) and not stack:
            parts.append("".join(current))
            current = []
            index += 3
            continue
        if character in BRACKET_PAIRS:
            stack.append(BRACKET_PAIRS[character])
        elif character in BRACKET_CLOSERS:
            if stack and stack[-1] == character:
                stack.pop()
        current.append(character)
        index += 1
    parts.append("".join(current))
    return [part.strip() for part in parts if part.strip()]


def expand_alternates(value):
    """Split the source's `A | B` alternative names so later ones become aliases."""
    if " | " not in value:
        return [value]
    parts = split_top_level_pipes(value)
    return parts if len(parts) > 1 else [value]


def build_rows(catalog, translations):
    rows = []
    missing = []
    conflicts = []
    invalid = []
    for tag_id, tag in catalog.items():
        canonical, tag_type = tag.title, tag.category
        candidates = CURATED_TRANSLATIONS.get((tag_type, canonical.casefold()))
        if candidates is None:
            candidates = MANUAL_TRANSLATIONS.get((tag_type, canonical.casefold()))
        if candidates is None:
            candidates = translations.get((tag_type, canonical.casefold()))
        if not candidates:
            missing.append({"id": tag_id, "canonicalName": canonical, "type": tag_type})
            continue

        valid = []
        for candidate in candidates:
            try:
                candidate = validate_text(curate_name(tag_type, candidate))
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
        expanded = []
        for candidate in valid:
            try:
                parts = [validate_text(part) for part in expand_alternates(candidate)]
            except ValueError as error:
                invalid.append({"id": tag_id, "canonicalName": canonical, "value": candidate,
                                "reason": str(error)})
                continue
            for part in parts:
                if part not in expanded:
                    expanded.append(part)
        if not expanded:
            missing.append({"id": tag_id, "canonicalName": canonical, "type": tag_type})
            continue
        name = expanded[0]
        name_key = search_key(name)
        aliases = []
        for alias in expanded[1:]:
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
