import unittest
from types import SimpleNamespace

from scripts.build_zh_hans_translation_source import build_rows, clean_name, split_table_row


class ZhHansTranslationSourceTest(unittest.TestCase):
    def test_split_table_row_preserves_escaped_pipes(self):
        self.assertEqual(split_table_row(r"| raw | translated \| alias | note |"),
                         ["raw", "translated \\| alias", "note"])

    def test_clean_name_removes_markdown_presentation(self):
        self.assertEqual(clean_name("![icon](https://example.invalid/i) [Name](https://example.invalid) <br>"),
                         "Name")

    def test_build_rows_keeps_source_conflict_as_alias(self):
        catalog = {
            1: SimpleNamespace(title="foo", category=1),
            33172: SimpleNamespace(title="doujinshi", category=7),
        }
        translations = {(1, "foo"): ["中文名", "别名"]}

        rows, missing, conflicts, invalid = build_rows(catalog, translations)

        self.assertEqual(rows[0], {"id": 1, "name": "中文名", "aliases": ["别名"]})
        self.assertEqual(rows[1], {"id": 33172, "name": "同人志", "aliases": []})
        self.assertEqual(missing, [])
        self.assertEqual(conflicts[0]["candidates"], ["中文名", "别名"])
        self.assertEqual(invalid, [])


if __name__ == "__main__":
    unittest.main()
