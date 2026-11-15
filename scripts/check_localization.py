"""Validate Android translation catalogs without an Android SDK."""

from collections import Counter
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


RESOURCE_ROOT = Path(__file__).resolve().parents[1] / "app/src/main/res"
RESOURCE_TYPES = {"string", "string-array", "plurals"}
FORMAT = re.compile(r"%(?:(\d+)\$)?[-#+ 0,(<]*\d*(?:\.\d+)?([a-zA-Z%])")
REQUIRED_QUANTITIES = {
    "ar": {"zero", "one", "two", "few", "many", "other"},
    "ru": {"one", "few", "many", "other"},
    "uk": {"one", "few", "many", "other"},
    "zh": {"other"},
    "ja": {"other"},
    "es": {"one", "other"},
    "fr": {"one", "other"},
    "it": {"one", "other"},
    "pt": {"one", "other"},
}
EXPECTED_LOCALE_FOLDERS = {
    "values-ar-rSA",
    "values-de-rDE",
    "values-es-rES",
    "values-fr-rFR",
    "values-it-rIT",
    "values-ja-rJP",
    "values-pt-rBR",
    "values-ru-rRU",
    "values-tr-rTR",
    "values-uk-rUA",
    "values-zh-rCN",
    "values-zh-rTW",
}


def text(node):
    return "".join(node.itertext())


def signature(value):
    arguments = []
    implicit_position = 0
    for match in FORMAT.finditer(value):
        position, kind = match.groups()
        if kind in {"%", "n"}:
            continue
        if position is None:
            implicit_position += 1
            position = implicit_position
        arguments.append((int(position), kind))
    return Counter(arguments)


def collect(folder, errors):
    entries = {}
    for path in sorted(folder.glob("*.xml")):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            errors.append(f"{path}: {error}")
            continue
        for node in root:
            if node.tag not in RESOURCE_TYPES:
                continue
            key = (node.tag, node.get("name"))
            if key in entries:
                errors.append(f"{path}: duplicate resource {key}")
            entries[key] = (node, path)
    return entries


def validate():
    errors = []
    base = collect(RESOURCE_ROOT / "values", errors)
    expected = {key: value for key, value in base.items() if value[0].get("translatable") != "false"}
    locales = [folder for folder in sorted(RESOURCE_ROOT.iterdir())
               if folder.is_dir() and re.fullmatch(r"values-[a-z]{2,3}(?:-r[A-Z]{2})?", folder.name)]
    locale_names = {folder.name for folder in locales}
    for missing_locale in sorted(EXPECTED_LOCALE_FOLDERS - locale_names):
        errors.append(f"missing supported locale folder: {missing_locale}")
    for unexpected_locale in sorted(locale_names - EXPECTED_LOCALE_FOLDERS):
        errors.append(f"unexpected locale folder: {unexpected_locale}")
    for folder in [RESOURCE_ROOT / "values", *locales]:
        entries = collect(folder, errors)
        language = folder.name.split("-")[1] if folder.name != "values" else "en"
        if folder.name != "values":
            for key in sorted(expected.keys() - entries.keys()):
                errors.append(f"{folder.name}: missing {key}")
            for key in sorted(entries.keys() - base.keys()):
                errors.append(f"{folder.name}: unknown {key}")
        for key, (node, path) in entries.items():
            if key not in expected:
                continue
            original = expected[key][0]
            location = f"{path.relative_to(RESOURCE_ROOT)}:{key[1]}"
            if re.search(r"\\[ \t]+n", text(node)):
                errors.append(f"{location}: malformed newline escape")
            if key[0] == "string":
                if not text(node).strip():
                    errors.append(f"{location}: empty translation")
                if signature(text(node)) != signature(text(original)):
                    errors.append(f"{location}: formatter arguments differ from English")
                if text(node).count("\\n") != text(original).count("\\n"):
                    errors.append(f"{location}: newline escapes differ from English")
                words = re.findall(r"[A-Za-z]{2,}", FORMAT.sub("", text(original)))
                if folder.name != "values" and len(words) > 1 and text(node) == text(original):
                    errors.append(f"{location}: untranslated English sentence or label")
            elif key[0] == "string-array":
                if len(node) != len(original):
                    errors.append(f"{location}: array length differs from English")
                for index, (item, source_item) in enumerate(zip(node, original)):
                    if not text(item).strip():
                        errors.append(f"{location}[{index}]: empty translation")
                    if signature(text(item)) != signature(text(source_item)):
                        errors.append(f"{location}[{index}]: formatter arguments differ")
            else:
                quantities = [item.get("quantity") for item in node]
                if len(quantities) != len(set(quantities)):
                    errors.append(f"{location}: duplicate plural quantities")
                required = REQUIRED_QUANTITIES.get(language, {"one", "other"})
                if not required.issubset(quantities):
                    errors.append(f"{location}: missing quantities {sorted(required - set(quantities))}")
                source_signature = signature(text(original.find("item[@quantity='other']")))
                for item in node:
                    if not text(item).strip():
                        errors.append(f"{location}[{item.get('quantity')}]: empty translation")
                    actual = signature(text(item))
                    fixed_arabic = language == "ar" and item.get("quantity") in {"zero", "one", "two"} and not actual
                    if actual != source_signature and not fixed_arabic:
                        errors.append(f"{location}[{item.get('quantity')}]: formatter arguments differ")
    if errors:
        for error in errors:
            print(error)
        print(f"Localization validation failed: {len(errors)} issue(s).")
        return 1
    print(f"Validated English and {len(locales)} translations: {len(expected)} resources per locale.")
    return 0


if __name__ == "__main__":
    sys.exit(validate())
