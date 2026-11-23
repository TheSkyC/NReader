"""Compare Python and the prepared ICU's behavior for every Unicode scalar."""

import argparse
import hashlib
import subprocess
import unicodedata

from scripts.build_tag_translation_pack import require_unicode_runtime


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--classpath", required=True)
    args = parser.parse_args()
    require_unicode_runtime()
    digest = hashlib.sha256()
    for code_point in range(0x110000):
        if 0xD800 <= code_point <= 0xDFFF:
            continue
        normalized = unicodedata.normalize("NFC", chr(code_point))
        for value in (normalized, normalized.lower().replace("\u03c2", "\u03c3")):
            encoded = value.encode("utf-8")
            digest.update(len(encoded).to_bytes(4, "big"))
            digest.update(encoded)
    actual = subprocess.check_output(
        ["java", "-cp", args.classpath, "TagUnicodeProbe", "--scalar-digest"], text=True).strip()
    if actual != digest.hexdigest():
        raise SystemExit("Python and ICU Unicode scalar digests differ")
    print("Python and ICU match for NFC and search keys of all Unicode scalar values")


if __name__ == "__main__":
    main()
