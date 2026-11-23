import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import build_tag_translation_pack as builder


class TagTranslationPackTest(unittest.TestCase):
    def test_cli_preserves_inputs_when_output_aliases_source_or_catalog(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            source = root / "source.json"
            catalog = root / "catalog.json"
            source.write_bytes(b'[{"id":1,"name":"translated"}]')
            catalog.write_bytes(b"catalog fixture")
            originals = {path: path.read_bytes() for path in (source, catalog)}
            common = [str(source), "--catalog", str(catalog), "--locale", "ja", "--revision", "1"]
            for target in (source, catalog):
                alias = target.parent / "." / target.name
                with self.subTest(target=target), contextlib.redirect_stderr(io.StringIO()) as error:
                    with self.assertRaises(SystemExit):
                        builder.main([*common, "--output", str(alias)])
                    self.assertIn("Output must be separate", error.getvalue())
                self.assertEqual(originals, {path: path.read_bytes() for path in originals})

            output = root / "pack.json"
            with patch.object(builder, "parse_catalog", return_value={1}), contextlib.redirect_stdout(io.StringIO()):
                builder.main([*common, "--output", str(output)])
            self.assertEqual("translated", builder.parse_pack(output.read_bytes(), {1})["entries"][0]["name"])
            self.assertEqual(originals, {path: path.read_bytes() for path in originals})

    def test_builds_sorted_deterministic_pack_with_normalized_text(self):
        source = json.dumps([
            {"id": 2, "name": " Cafe\u0301 ", "aliases": ["coffee"]},
            {"id": 1, "name": "\u4e3b\u9898", "aliases": ["\u9898\u6750"]},
        ]).encode("utf-8")

        result = builder.build_pack(source, "zh-Hans", 4, {1, 2})

        self.assertEqual(
            ("{\"schemaVersion\":1,\"locale\":\"zh-Hans\",\"revision\":4,\"entries\":["
             "{\"id\":1,\"name\":\"\u4e3b\u9898\",\"aliases\":[\"\u9898\u6750\"]},"
             "{\"id\":2,\"name\":\"Caf\u00e9\",\"aliases\":[\"coffee\"]}]}\n").encode("utf-8"),
            result,
        )
        self.assertEqual(result, builder.build_pack(source, "zh-Hans", 4, {1, 2}))

    def test_rejects_invalid_identity_fields_text_and_aliases(self):
        invalid_cases = [
            ("und", 1, [{"id": 1, "name": "name"}], {1}),
            ("und-TW", 1, [{"id": 1, "name": "name"}], {1}),
            ("iw-IL", 1, [{"id": 1, "name": "name"}], {1}),
            ("zh-Hans", True, [{"id": 1, "name": "name"}], {1}),
            ("zh-Hans", 1, [{"id": True, "name": "name"}], {1}),
            ("zh-Hans", 1, [{"id": 9, "name": "name"}], {1}),
            ("zh-Hans", 1, [{"id": 1, "name": "name"}, {"id": 1, "name": "again"}], {1}),
            ("zh-Hans", 1, [{"id": 1, "name": "name", "unknown": True}], {1}),
            ("zh-Hans", 1, [{"id": 1, "name": "bad\nname"}], {1}),
            ("zh-Hans", 1, [{"id": 1, "name": "\tname"}], {1}),
            ("zh-Hans", 1, [{"id": 1, "name": "\ud800"}], {1}),
            ("zh-Hans", 1, [{"id": 1, "name": "name", "aliases": ["Alias", "alias"]}], {1}),
        ]
        for locale, revision, rows, catalog in invalid_cases:
            with self.subTest(locale=locale, revision=revision, rows=rows), self.assertRaises(ValueError):
                builder.build_pack(json.dumps(rows).encode("utf-8"), locale, revision, catalog)

    def test_enforces_entry_text_alias_and_output_limits(self):
        with self.assertRaises(ValueError):
            builder.build_pack(b"[]", "zh-Hans", 1, {1})
        with patch.object(builder, "MAX_TEXT_LENGTH", 4):
            with self.assertRaises(ValueError):
                builder.build_pack(b'[{"id":1,"name":"longer"}]', "zh-Hans", 1, {1})
        with patch.object(builder, "MAX_ALIASES", 1):
            with self.assertRaises(ValueError):
                builder.build_pack(b'[{"id":1,"name":"ok","aliases":["one","two"]}]',
                                   "zh-Hans", 1, {1})
        with patch.object(builder, "MAX_BYTES", 10):
            with self.assertRaises(ValueError):
                builder.build_pack(b'[{"id":1,"name":"ok"}]', "zh-Hans", 1, {1})
        with patch.object(builder, "MAX_ENTRIES", 1):
            with self.assertRaises(ValueError):
                builder.build_pack(b'[{"id":1,"name":"ok"},{"id":2,"name":"also ok"}]',
                                   "zh-Hans", 1, {1, 2})

    def test_rejects_duplicate_json_fields(self):
        with self.assertRaises(ValueError):
            builder.build_pack(b'[{"id":1,"name":"first","name":"second"}]', "zh-Hans", 1, {1})

    def test_search_keys_keep_greek_sigma_prefixes_stable(self):
        self.assertEqual("\u03bf\u03c3", builder.search_key("\u039f\u03a3"))
        self.assertEqual("\u03bf\u03c3\u03b1", builder.search_key("\u039f\u03a3\u0391"))
        with self.assertRaises(ValueError):
            builder.build_pack(
                '[{"id":1,"name":"name","aliases":["\u039f\u03a3","\u03bf\u03c2"]}]'.encode(),
                "el", 1, {1})


if __name__ == "__main__":
    unittest.main()
