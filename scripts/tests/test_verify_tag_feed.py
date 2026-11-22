import hashlib
import io
import json
import shutil
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from pathlib import Path

from scripts import build_tag_catalog, build_tag_translation_release, verify_tag_feed
from scripts.build_tag_translation_pack import encode_pack
from scripts.tag_catalog import MIN_ROWS, TYPE_IDS


class VerifyTagFeedTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        types = sorted(TYPE_IDS)
        cls.catalog_data = json.dumps([
            [index + 1, "Canonical", 100, types[index % len(types)]] for index in range(MIN_ROWS)
        ]).encode("utf-8")

    def setUp(self):
        workspace = tempfile.TemporaryDirectory()
        self.addCleanup(workspace.cleanup)
        self.root = Path(workspace.name)
        self.catalog = self.root / "canonical.json"
        self.catalog.write_bytes(self.catalog_data)
        self.catalog_feed = self.root / "catalog"
        self.translation_feed = self.root / "translations"

    def publish_translation(self, locale, revision, tag_id=1):
        data = encode_pack([{"id": tag_id, "name": "Translation"}], locale, revision, {1, tag_id})
        candidate = self.root / "pack.json"
        candidate.write_bytes(data)
        build_tag_translation_release.prepare_release(candidate, {1, tag_id}, self.translation_feed)

    def failed_verification(self, error, *args):
        stdout, stderr = io.StringIO(), io.StringIO()
        with redirect_stdout(stdout), redirect_stderr(stderr), self.assertRaises(SystemExit) as failure:
            verify_tag_feed.main(args)
        self.assertEqual(1, failure.exception.code)
        self.assertRegex(stderr.getvalue(), error)
        self.assertEqual("", stdout.getvalue())

    def test_copied_feeds_can_be_verified_without_writing_files(self):
        build_tag_catalog.prepare_release(self.catalog, 7, self.catalog_feed)
        self.publish_translation("ja", 3)
        self.publish_translation("zh-Hans", 1)
        stage = self.root / "staged"
        catalog_feed = stage / "catalog"
        translation_feed = stage / "translations"
        shutil.copytree(self.catalog_feed, catalog_feed)
        shutil.copytree(self.translation_feed, translation_feed)
        before = self.capture_files(stage)

        stdout = io.StringIO()
        with redirect_stdout(stdout):
            verify_tag_feed.main([
                "--catalog-feed", str(catalog_feed), "--translation-feed", str(translation_feed),
            ])
        self.assertEqual([
            f"Catalog revision 7: {MIN_ROWS} tags",
            "Translation ja: revision 3",
            "Translation zh-Hans: revision 1",
        ], stdout.getvalue().splitlines())
        self.assertEqual(before, self.capture_files(stage))

    @staticmethod
    def capture_files(directory):
        return {path.relative_to(directory): (path.read_bytes(), path.stat().st_mtime_ns)
                for path in directory.rglob("*") if path.is_file()}

    def test_catalog_rejects_missing_truncated_or_corrupt_payload(self):
        build_tag_catalog.prepare_release(self.catalog, 7, self.catalog_feed)
        payload = next(self.catalog_feed.glob("catalog-*.json"))
        args = ("--catalog-feed", str(self.catalog_feed))
        payload.unlink()
        self.failed_verification("catalog-.*[.]json", *args)
        self.assertFalse(payload.exists())

        for damaged in (self.catalog_data[:-1], self.catalog_data.replace(b"Canonical", b"Different", 1)):
            with self.subTest(len=len(damaged)):
                payload.write_bytes(damaged)
                self.failed_verification("Payload size or digest does not match manifest", *args)
                self.assertEqual(damaged, payload.read_bytes())

    def test_feed_filenames_must_match_exactly(self):
        build_tag_catalog.prepare_release(self.catalog, 7, self.catalog_feed)
        self.publish_translation("ja", 3)
        args = ("--catalog-feed", str(self.catalog_feed), "--translation-feed", str(self.translation_feed))
        paths = (
            self.catalog_feed / "manifest.json",
            next(self.catalog_feed.glob("catalog-*.json")),
            self.translation_feed / "manifest.json",
            next(self.translation_feed.glob("translation-*.json")),
        )
        for path in paths:
            with self.subTest(path=path):
                intermediate = path.with_suffix(".renaming")
                wrong_case = path.with_name(path.name.upper())
                path.rename(intermediate)
                intermediate.rename(wrong_case)
                self.failed_verification("Missing file with exact name: .*" + path.name, *args)
                wrong_case.rename(intermediate)
                intermediate.rename(path)

    def test_client_manifest_limit_counts_the_opening_quote_and_escape_spelling(self):
        build_tag_catalog.prepare_release(self.catalog, 7, self.catalog_feed)
        self.publish_translation("ja", 3)
        args = ("--catalog-feed", str(self.catalog_feed), "--translation-feed", str(self.translation_feed))
        for feed in (self.catalog_feed, self.translation_feed):
            with self.subTest(feed=feed):
                pointer = feed / "manifest.json"
                original = pointer.read_bytes()
                manifest = json.loads(original)
                digest = manifest.get("packs", [manifest])[0]["sha256"]
                escaped_digest = "".join(f"\\u{ord(char):04x}" for char in digest)
                pointer.write_bytes(original.replace(digest.encode("ascii"), escaped_digest.encode("ascii")))
                self.failed_verification("JSON string exceeds limit", *args)
                pointer.write_bytes(original)

    def test_digest_does_not_make_an_invalid_catalog_valid(self):
        malformed = b"[]"
        digest = hashlib.sha256(malformed).hexdigest()
        self.catalog_feed.mkdir()
        (self.catalog_feed / f"catalog-{digest}.json").write_bytes(malformed)
        manifest = dict(build_tag_catalog.IDENTITY, revision=1, sizeBytes=len(malformed), sha256=digest)
        (self.catalog_feed / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        self.failed_verification("Catalog row count outside limits", "--catalog-feed", str(self.catalog_feed))

    def test_translation_must_match_the_descriptor_and_target_catalog(self):
        self.publish_translation("ja", 3, MIN_ROWS + 1)
        args = ("--catalog", str(self.catalog), "--translation-feed", str(self.translation_feed))
        self.failed_verification("translation payload for ja: .*unknown tag ID", *args)

        self.catalog.write_bytes(self.catalog_data[:-1] + f', [{MIN_ROWS + 1}, "Known", 100, 1]]'.encode("ascii"))
        pointer = self.translation_feed / "manifest.json"
        manifest = json.loads(pointer.read_bytes())
        manifest["packs"][0]["revision"] = 4
        pointer.write_text(json.dumps(manifest), encoding="utf-8")
        self.failed_verification("Payload locale or revision does not match manifest", *args)

        manifest["packs"][0]["revision"] = 3
        pointer.write_text(json.dumps(manifest), encoding="utf-8")
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            verify_tag_feed.main(args)
        self.assertEqual("Translation ja: revision 3\n", stdout.getvalue())


if __name__ == "__main__":
    unittest.main()
