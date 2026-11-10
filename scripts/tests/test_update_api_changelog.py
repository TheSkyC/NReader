import hashlib
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch

from scripts import update_api_changelog as changelog


PAGE = """<html><h1>nhentai API Changelog</h1>
<h2>2026-01-02</h2><ul><li>Added <code>foo</code> &amp; bar.</li></ul></html>"""


class ChangelogTest(unittest.TestCase):
    def fetch(self, page):
        response = Mock(text=page)
        with patch.object(changelog.requests, "get", return_value=response):
            return changelog.fetch_changelog()

    def test_nested_markup_and_entities(self):
        expected = hashlib.sha256(b"Added foo & bar.").hexdigest()
        self.assertEqual([{"date": "2026-01-02", "entries": [expected]}], self.fetch(PAGE))

    def test_rejects_error_invalid_and_incomplete_pages(self):
        for page in ("<html><h1>Access denied</h1></html>",
                     PAGE.replace("2026-01-02", "2026-99-99"),
                     PAGE.replace("</html>", ""),
                     PAGE.replace("<li>Added <code>foo</code> &amp; bar.</li>", "")):
            with self.subTest(page=page), self.assertRaises(ValueError):
                self.fetch(page)

    def test_unchanged_and_failed_fetch_preserve_snapshot(self):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "snapshot.json"
            with patch.object(changelog, "OUTPUT", output):
                payload = self.fetch(PAGE)
                self.assertTrue(changelog.write_if_changed(payload))
                original = output.read_bytes()
                self.assertFalse(changelog.write_if_changed(payload))
                with self.assertRaises(ValueError):
                    changelog.write_if_changed(self.fetch("<html>error</html>"))
                self.assertEqual(original, output.read_bytes())


if __name__ == "__main__":
    unittest.main()
