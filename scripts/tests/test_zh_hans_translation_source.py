import unittest
from types import SimpleNamespace

from scripts.build_zh_hans_translation_source import (
    build_rows, clean_name, curate_name, expand_alternates, split_table_row, split_top_level_pipes,
)


class ZhHansTranslationSourceTest(unittest.TestCase):
    def test_split_table_row_preserves_escaped_pipes(self):
        self.assertEqual(split_table_row(r"| raw | translated \| alias | note |"),
                         ["raw", "translated \\| alias", "note"])

    def test_clean_name_removes_markdown_presentation(self):
        self.assertEqual(clean_name("![icon](https://example.invalid/i) [Name](https://example.invalid) <br>"),
                         "Name")

    def test_clean_name_unescapes_markdown_punctuation(self):
        self.assertEqual(clean_name(r"YUI\_7"), "YUI_7")
        self.assertEqual(clean_name(r"\*Cherish\*"), "*Cherish*")
        self.assertEqual(clean_name("希望的力量～大人的光之美少女\\`23"), "希望的力量～大人的光之美少女23")

    def test_curate_name_strips_emoji_only_from_descriptive_types(self):
        self.assertEqual(curate_name(3, "熊🐻"), "熊")
        self.assertEqual(curate_name(3, "丧尸🧟\u200d♀️"), "丧尸")
        self.assertEqual(curate_name(1, "Charlotte🌠"), "Charlotte")
        self.assertEqual(curate_name(4, "Greko🔞"), "Greko🔞")

    def test_curate_name_keeps_text_gender_glyphs_outside_emoji_tags(self):
        self.assertEqual(curate_name(4, "♀こみ"), "♀こみ")
        self.assertEqual(curate_name(3, "纯女性⚢"), "纯女性")

    def test_curate_name_removes_invisible_characters_for_every_type(self):
        self.assertEqual(curate_name(2, "\u200c高坂千奈"), "高坂千奈")
        self.assertEqual(curate_name(5, "\u200b文釣DOOM"), "文釣DOOM")

    def test_split_top_level_pipes_keeps_parenthetical_lists_intact(self):
        self.assertEqual(split_top_level_pipes("露娜（素晴 | 死或生 | 影之诗）"), ["露娜（素晴 | 死或生 | 影之诗）"])
        self.assertEqual(split_top_level_pipes("丽王 | 利昂 | 雷欧"), ["丽王", "利昂", "雷欧"])
        self.assertEqual(split_top_level_pipes("A | B（x | y） | C"), ["A", "B（x | y）", "C"])

    def test_expand_alternates_leaves_names_without_pipes_untouched(self):
        self.assertEqual(expand_alternates("竹兰"), ["竹兰"])

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

    def test_build_rows_moves_pipe_alternates_into_aliases(self):
        catalog = {1: SimpleNamespace(title="foo", category=3)}
        translations = {(3, "foo"): ["主名 | 别名"]}

        rows, _, conflicts, _ = build_rows(catalog, translations)

        self.assertEqual(rows[0], {"id": 1, "name": "主名", "aliases": ["别名"]})
        self.assertEqual(conflicts, [])

    def test_build_rows_curates_names_and_prefers_curated_translation(self):
        catalog = {
            7019: SimpleNamespace(title="bull", category=3),
            3350: SimpleNamespace(title="working", category=1),
        }
        translations = {
            (3, "bull"): ["牛🐂"],
            (1, "working"): ["迷糊餐厅🍴"],
        }

        rows, _, _, _ = build_rows(catalog, translations)
        by_id = {row["id"]: row for row in rows}

        self.assertEqual(by_id[7019], {"id": 7019, "name": "公牛", "aliases": []})
        self.assertEqual(by_id[3350], {"id": 3350, "name": "迷糊餐厅", "aliases": []})


if __name__ == "__main__":
    unittest.main()
