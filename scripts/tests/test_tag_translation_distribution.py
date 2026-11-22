import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import build_tag_translation_release as release
from scripts import review_tag_translations as review
from scripts.build_tag_translation_pack import encode_pack
from scripts.tag_catalog import TagInfo


class TranslationReleaseTest(unittest.TestCase):
    def setUp(self):
        workspace = tempfile.TemporaryDirectory()
        self.addCleanup(workspace.cleanup)
        self.root = Path(workspace.name)
        self.output = self.root / "feed"
        self.pack = self.root / "candidate.json"
        self.catalog_ids = {11}

    def candidate(self, locale, revision, name="Translation"):
        data = encode_pack([{"id": 11, "name": name}], locale, revision, self.catalog_ids)
        self.pack.write_bytes(data)
        return data, self.output / f"translation-{hashlib.sha256(data).hexdigest()}.json"

    def publish(self):
        return release.prepare_release(self.pack, self.catalog_ids, self.output)

    def test_review_candidate_and_release_match_the_android_fixture(self):
        fixtures = Path(__file__).resolve().parents[2] / "app/src/test/resources/tag-translation-release"
        catalog = {8378: TagInfo(8378, "canonical", 100, 3)}
        proposal = json.loads(review.draft_proposal(catalog, None, "ja", [8378]))
        proposal["changes"][0]["after"] = {"name": "\u753b\u5bb6", "aliases": [" Cafe\u0301 ", "\u7d75\u5e2b"]}
        report, candidate = review.review_proposal(catalog, None, review.encode_document(proposal), 7)
        digest = json.loads(report)["candidateSha256"]
        self.pack.write_bytes(candidate)
        pointer = release.prepare_release(self.pack, catalog, self.output)
        self.assertEqual((fixtures / "pack.json").read_bytes(), candidate)
        self.assertEqual((fixtures / "manifest.json").read_bytes(), pointer.read_bytes())
        self.assertEqual(candidate, (self.output / f"translation-{digest}.json").read_bytes())

    def test_revisions_are_independent_and_bind_the_unchanged_payload_bytes(self):
        old_data, old_payload = self.candidate("zh-Hant", 7)
        pointer = self.publish()
        self.candidate("ja", 2)
        self.publish()
        current_data, current_payload = self.candidate("zh-Hant", 8, "Corrected")
        self.publish()

        packs = release.parse_manifest(pointer.read_bytes())
        self.assertEqual(["ja", "zh-Hant"], list(packs))
        self.assertEqual(2, packs["ja"]["revision"])
        self.assertEqual({"locale": "zh-Hant", "revision": 8, "sizeBytes": len(current_data),
                          "sha256": hashlib.sha256(current_data).hexdigest()}, packs["zh-Hant"])
        self.assertEqual(current_data, current_payload.read_bytes())
        self.assertEqual(old_data, old_payload.read_bytes())
        with patch.object(release, "atomic_write", side_effect=AssertionError("Unexpected rewrite")):
            self.publish()

        previous = pointer.read_bytes()
        for revision in (7, 8):
            self.candidate("zh-Hant", revision, "Different bytes")
            with self.assertRaisesRegex(ValueError, "cannot be lowered or reused"):
                self.publish()
            self.assertEqual(previous, pointer.read_bytes())

    def test_failed_pointer_update_leaves_previous_release_available(self):
        data, payload = self.candidate("ja", 1)
        pointer = self.publish()
        original = pointer.read_bytes()
        next_data, next_payload = self.candidate("ja", 2)
        original_write = release.atomic_write

        def fail_pointer(path, content):
            if path == pointer:
                raise OSError("Simulated interruption")
            original_write(path, content)

        with patch.object(release, "atomic_write", side_effect=fail_pointer):
            with self.assertRaisesRegex(OSError, "interruption"):
                self.publish()
        self.assertEqual(original, pointer.read_bytes())
        self.assertEqual(data, payload.read_bytes())
        self.assertEqual(next_data, next_payload.read_bytes())
        self.publish()
        self.assertEqual(2, release.parse_manifest(pointer.read_bytes())["ja"]["revision"])

    def test_invalid_pack_or_immutable_bytes_leave_pointer_unchanged(self):
        self.candidate("ja", 1)
        pointer = self.publish()
        original = pointer.read_bytes()
        data, payload = self.candidate("ja", 2)
        cases = (
            (data.replace(b'"id":11', b'"id":12'), "unknown tag ID"),
            (data + b"\n", "exact pack"),
        )
        for candidate, error in cases:
            self.pack.write_bytes(candidate)
            with self.assertRaisesRegex(ValueError, error):
                self.publish()
            self.assertFalse(payload.exists())
            self.assertEqual(original, pointer.read_bytes())
        self.pack.write_bytes(data)
        payload.write_bytes(b"different bytes")
        with self.assertRaisesRegex(ValueError, "does not match its digest"):
            self.publish()
        self.assertEqual(original, pointer.read_bytes())
        self.assertEqual(b"different bytes", payload.read_bytes())

    def test_manifest_rejects_invalid_descriptors_duplicates_and_limits(self):
        self.candidate("ja", 1)
        manifest = json.loads(self.publish().read_bytes())
        row = manifest["packs"][0]
        cases = (
            {**manifest, "source": "nreader-catalog"},
            {**manifest, "schemaVersion": True},
            {**manifest, "packs": []},
            {**manifest, "packs": [row, row]},
            {**manifest, "packs": [row] * (release.MAX_LOCALES + 1)},
            *({**manifest, "packs": [{**row, **changes}]} for changes in (
                {"locale": "ja/.."}, {"locale": "iw"}, {"revision": 0},
                {"sizeBytes": release.MAX_BYTES + 1}, {"sizeBytes": True},
                {"sha256": "A" * 64}, {"url": "https://example.invalid/payload"},
            )),
        )
        for invalid in cases:
            with self.subTest(invalid=invalid if len(invalid["packs"]) < 3 else "too many locales"):
                with self.assertRaises(ValueError):
                    release.parse_manifest(json.dumps(invalid).encode())
        with self.assertRaisesRegex(ValueError, "Duplicate JSON field"):
            release.parse_manifest(json.dumps(manifest).encode().replace(b'"locale":', b'"locale":"ja","locale":'))
        with self.assertRaisesRegex(ValueError, "size limit"):
            release.parse_manifest(b" " * release.MAX_MANIFEST_BYTES + json.dumps(manifest).encode())

    def test_missing_or_corrupt_retained_locale_prevents_publishing(self):
        data, retained = self.candidate("ja", 1)
        pointer = self.publish()
        original = pointer.read_bytes()
        _, new_payload = self.candidate("zh-Hant", 1)
        retained.unlink()
        with self.assertRaisesRegex(ValueError, "retained translation payload for ja"):
            self.publish()
        self.assertEqual(original, pointer.read_bytes())
        self.assertFalse(new_payload.exists())

        retained.write_bytes(data.replace(b"Translation", b"AlteredName"))
        with self.assertRaisesRegex(ValueError, "retained translation payload for ja"):
            self.publish()
        self.assertEqual(original, pointer.read_bytes())
        self.assertFalse(new_payload.exists())

        retained.write_bytes(data)
        self.publish()
        self.assertEqual({"ja", "zh-Hant"}, set(release.parse_manifest(pointer.read_bytes())))

    def test_retained_pack_must_match_manifest_and_current_catalog(self):
        self.candidate("ja", 1)
        pointer = self.publish()
        original = pointer.read_bytes()
        _, new_payload = self.candidate("zh-Hant", 1)
        for field, value in (("locale", "de"), ("revision", 2)):
            with self.subTest(field=field):
                manifest = json.loads(original)
                manifest["packs"][0][field] = value
                invalid = json.dumps(manifest).encode()
                pointer.write_bytes(invalid)
                with self.assertRaisesRegex(ValueError, "Payload locale or revision does not match manifest"):
                    self.publish()
                self.assertEqual(invalid, pointer.read_bytes())
                self.assertFalse(new_payload.exists())

        pointer.write_bytes(original)
        catalog = {12}
        self.pack.write_bytes(encode_pack([{"id": 12, "name": "New"}], "zh-Hant", 1, catalog))
        with self.assertRaisesRegex(ValueError, "retained translation payload for ja: .*unknown tag ID"):
            release.prepare_release(self.pack, catalog, self.output)
        self.assertEqual(original, pointer.read_bytes())
        self.assertEqual(1, len(list(self.output.glob("translation-*.json"))))


if __name__ == "__main__":
    unittest.main()
