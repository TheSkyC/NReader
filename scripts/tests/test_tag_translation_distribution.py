import hashlib
import json
import sys
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

    def test_replaced_payload_must_be_available_before_publishing(self):
        old_data, old_payload = self.candidate("ja", 1)
        pointer = self.publish()
        original = pointer.read_bytes()
        _, new_payload = self.candidate("ja", 2)

        for damaged in (None, b"corrupt"):
            with self.subTest(damaged=damaged):
                if damaged is None:
                    old_payload.unlink()
                else:
                    old_payload.write_bytes(damaged)
                with self.assertRaisesRegex(ValueError, "retained translation payload for ja"):
                    self.publish()
                self.assertEqual(original, pointer.read_bytes())
                self.assertFalse(new_payload.exists())

        old_payload.write_bytes(old_data)
        self.publish()
        self.assertEqual(2, release.parse_manifest(pointer.read_bytes())["ja"]["revision"])
        self.assertEqual(old_data, old_payload.read_bytes())

    def test_identical_candidate_restores_missing_payload(self):
        data, payload = self.candidate("ja", 1)
        pointer = self.publish()
        original = pointer.read_bytes()
        payload.unlink()
        self.publish()
        self.assertEqual(data, payload.read_bytes())
        self.assertEqual(original, pointer.read_bytes())

    def test_replaced_pack_can_reference_tags_absent_from_current_catalog(self):
        old_data, old_payload = self.candidate("ja", 1)
        pointer = self.publish()
        new_catalog = {12}
        self.pack.write_bytes(encode_pack([{"id": 12, "name": "New"}], "ja", 2, new_catalog))
        release.prepare_release(self.pack, new_catalog, self.output)
        self.assertEqual(2, release.parse_manifest(pointer.read_bytes())["ja"]["revision"])
        self.assertEqual(old_data, old_payload.read_bytes())

    def test_replaced_payload_must_match_manifest_identity(self):
        self.candidate("ja", 1)
        pointer = self.publish()
        manifest = json.loads(pointer.read_bytes())
        manifest["packs"][0]["revision"] = 2
        original = json.dumps(manifest).encode()
        pointer.write_bytes(original)
        _, new_payload = self.candidate("ja", 3)
        with self.assertRaisesRegex(ValueError, "Payload locale or revision does not match manifest"):
            self.publish()
        self.assertEqual(original, pointer.read_bytes())
        self.assertFalse(new_payload.exists())

    def test_batch_replaces_obsolete_packs_with_one_manifest_switch(self):
        old_payloads = []
        for locale in ("ja", "de"):
            data, payload = self.candidate(locale, 1)
            self.publish()
            old_payloads.append((payload, data))
        pointer = self.output / "manifest.json"
        original = pointer.read_bytes()
        new_catalog = {12}
        candidates = []
        for locale in ("ja", "de"):
            path = self.root / f"{locale}.json"
            path.write_bytes(encode_pack([{"id": 12, "name": locale}], locale, 2, new_catalog))
            candidates.append(path)

        for path in candidates:
            with self.assertRaisesRegex(ValueError, "unknown tag ID"):
                release.prepare_release(path, new_catalog, self.output)
            self.assertEqual(original, pointer.read_bytes())

        original_write = release.atomic_write

        def fail_pointer(path, content):
            if path == pointer:
                raise OSError("Simulated interruption")
            original_write(path, content)

        with patch.object(release, "atomic_write", side_effect=fail_pointer):
            with self.assertRaisesRegex(OSError, "interruption"):
                release.prepare_releases(candidates, new_catalog, self.output)
        self.assertEqual(original, pointer.read_bytes())
        for path, data in old_payloads:
            self.assertEqual(data, path.read_bytes())
        for path in candidates:
            data = path.read_bytes()
            self.assertEqual(data, (self.output / release.payload_name(hashlib.sha256(data).hexdigest())).read_bytes())

        release.prepare_releases(candidates, new_catalog, self.output)
        packs = release.parse_manifest(pointer.read_bytes())
        self.assertEqual({"de": 2, "ja": 2}, {locale: row["revision"] for locale, row in packs.items()})
        for descriptor in packs.values():
            release.verify_payload(self.output, descriptor, new_catalog)
        for path, data in old_payloads:
            self.assertEqual(data, path.read_bytes())

    def test_batch_rejects_duplicate_invalid_and_missing_retained_packs_before_staging(self):
        old_data, old_payload = self.candidate("ja", 1)
        pointer = self.publish()
        original = pointer.read_bytes()
        new_data, new_payload = self.candidate("ja", 2)
        first = self.root / "first.json"
        first.write_bytes(new_data)
        second = self.root / "second.json"
        second.write_bytes(new_data)
        with self.assertRaisesRegex(ValueError, "Duplicate candidate locale"):
            release.prepare_releases([first, second], self.catalog_ids, self.output)
        second.write_bytes(b"invalid")
        with self.assertRaises(ValueError):
            release.prepare_releases([first, second], self.catalog_ids, self.output)
        second.write_bytes(encode_pack([{"id": 11, "name": "Deutsch"}], "de", 1, self.catalog_ids))
        old_payload.unlink()
        with self.assertRaisesRegex(ValueError, "retained translation payload for ja"):
            release.prepare_releases([first, second], self.catalog_ids, self.output)
        self.assertEqual(original, pointer.read_bytes())
        self.assertFalse(new_payload.exists())
        old_payload.write_bytes(old_data)
        release.prepare_releases([first, second], self.catalog_ids, self.output)
        self.assertEqual({"ja", "de"}, set(release.parse_manifest(pointer.read_bytes())))

    def test_batch_rejects_changed_candidate_before_publishing(self):
        self.candidate("ja", 1)
        pointer = self.publish()
        original = pointer.read_bytes()
        self.candidate("ja", 2)
        original_verify = release.verify_payload

        def change_candidate(directory, descriptor, catalog_ids, names):
            original_verify(directory, descriptor, catalog_ids, names)
            self.pack.write_bytes(b"changed")

        with patch.object(release, "verify_payload", side_effect=change_candidate):
            with self.assertRaisesRegex(ValueError, "Payload size or digest|Input exceeds size limit"):
                release.prepare_releases([self.pack], self.catalog_ids, self.output)
        self.assertEqual(original, pointer.read_bytes())

    def test_command_line_accepts_multiple_candidates(self):
        paths = []
        for locale in ("de", "ja"):
            path = self.root / f"{locale}.json"
            path.write_bytes(encode_pack([{"id": 11, "name": locale}], locale, 1, self.catalog_ids))
            paths.append(path)
        catalog_path = self.root / "catalog.json"
        catalog_path.write_bytes(b"[]")
        args = ["build_tag_translation_release", *(str(path) for path in paths),
                "--catalog", str(catalog_path), "--output", str(self.output)]
        with patch.object(sys, "argv", args), patch.object(release, "parse_catalog", return_value=self.catalog_ids):
            release.main()
        self.assertEqual({"de", "ja"}, set(release.parse_manifest((self.output / "manifest.json").read_bytes())))

    def test_retained_pack_keeps_its_original_encoding_when_another_locale_is_published(self):
        self.candidate("ja", 1)
        pointer = self.publish()
        manifest = json.loads(pointer.read_bytes())
        retained = json.loads(self.pack.read_bytes())
        del retained["entries"][0]["aliases"]
        data = json.dumps(retained, indent=2).encode("utf-8")
        digest = hashlib.sha256(data).hexdigest()
        path = self.output / f"translation-{digest}.json"
        path.write_bytes(data)
        manifest["packs"][0].update(sizeBytes=len(data), sha256=digest)
        pointer.write_text(json.dumps(manifest), encoding="utf-8")

        self.pack.write_bytes(data)
        with self.assertRaisesRegex(ValueError, "exact pack produced"):
            self.publish()

        self.candidate("zh-Hant", 1)
        self.publish()
        packs = release.parse_manifest(pointer.read_bytes())
        self.assertEqual({"ja", "zh-Hant"}, set(packs))
        self.assertEqual(manifest["packs"][0], packs["ja"])
        self.assertEqual(data, path.read_bytes())

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
