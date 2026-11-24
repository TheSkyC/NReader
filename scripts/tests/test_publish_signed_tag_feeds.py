import base64
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import ec

from scripts import publish_signed_tag_feeds as publisher, sign_tag_feed

PINNED_PUBLIC_KEY = publisher.PUBLIC_KEY


class PublishSignedTagFeedsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.catalog = self.root / "catalog"
        self.translations = self.root / "translations"
        self.catalog.mkdir()
        self.translations.mkdir()
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.pem = self.key.private_bytes(serialization.Encoding.PEM,
                                          serialization.PrivateFormat.PKCS8,
                                          serialization.NoEncryption())
        public = self.key.public_key().public_bytes(serialization.Encoding.DER,
                                                    serialization.PublicFormat.SubjectPublicKeyInfo)
        key_patch = patch.object(publisher, "PUBLIC_KEY", base64.b64encode(public).decode())
        key_patch.start()
        self.addCleanup(key_patch.stop)
        self.write_catalog(1)
        self.write_translation(1)

    def write_catalog(self, revision):
        manifest = {"schemaVersion": 1, "source": "nreader-catalog", "kind": "catalog",
                    "revision": revision, "sizeBytes": 1, "sha256": "a" * 64}
        (self.catalog / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    def write_translation(self, revision):
        manifest = {"schemaVersion": 1, "source": "nreader-translations", "kind": "translations",
                    "packs": [{"locale": "zh-Hans", "revision": revision,
                               "sizeBytes": 1, "sha256": "b" * 64}]}
        (self.translations / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")

    def run_publisher(self):
        return publisher.publish(self.catalog, self.translations, self.pem)

    def test_signs_and_keeps_existing_signatures_when_unchanged(self):
        self.assertEqual(2, self.run_publisher())
        pointers = [self.catalog / "manifest-v2.json", self.translations / "manifest-v2.json"]
        original = [path.read_bytes() for path in pointers]
        for path, kind in zip(pointers, ("catalog", "translation")):
            self.assertEqual((path.parent / "manifest.json").read_bytes(),
                             sign_tag_feed.verify_manifest(self.key.public_key(), kind, path.read_bytes()))
        self.assertEqual(0, self.run_publisher())
        self.assertEqual(original, [path.read_bytes() for path in pointers])
        self.write_translation(2)
        self.assertEqual(1, self.run_publisher())
        self.assertEqual(original[0], pointers[0].read_bytes())

    def test_rejects_wrong_key_and_rollback_without_writing_either_pointer(self):
        self.assertEqual(2, self.run_publisher())
        pointers = [self.catalog / "manifest-v2.json", self.translations / "manifest-v2.json"]
        with patch.object(publisher, "PUBLIC_KEY", base64.b64encode(b"wrong").decode()):
            with self.assertRaises(ValueError):
                self.run_publisher()
        self.write_catalog(2)
        self.write_translation(2)
        self.assertEqual(2, self.run_publisher())
        original = [path.read_bytes() for path in pointers]
        self.write_catalog(3)
        self.write_translation(1)
        with self.assertRaises(ValueError):
            self.run_publisher()
        self.assertEqual(original, [path.read_bytes() for path in pointers])

    def test_publisher_key_matches_android_key(self):
        source = (Path(__file__).resolve().parents[2] / "app/src/main/java/com/theskyc/nreader/async/"
                  "SignedTagManifest.java").read_text(encoding="utf-8")
        self.assertIn('"' + PINNED_PUBLIC_KEY + '"', source)

    def test_invalid_existing_signature_or_reused_revision_does_not_publish(self):
        self.assertEqual(2, self.run_publisher())
        catalog_pointer = self.catalog / "manifest-v2.json"
        translation_pointer = self.translations / "manifest-v2.json"
        old_catalog = catalog_pointer.read_bytes()
        old_translation = translation_pointer.read_bytes()
        damaged = json.loads(old_translation)
        damaged["signature"] = base64.b64encode(b"incorrect signature").decode()
        translation_pointer.write_text(json.dumps(damaged), encoding="utf-8")
        self.write_catalog(2)
        with self.assertRaises(ValueError):
            self.run_publisher()
        self.assertEqual(old_catalog, catalog_pointer.read_bytes())
        translation_pointer.write_bytes(old_translation)
        self.write_translation(1)
        data = json.loads((self.translations / "manifest.json").read_text(encoding="utf-8"))
        data["packs"][0]["sha256"] = "c" * 64
        (self.translations / "manifest.json").write_text(json.dumps(data), encoding="utf-8")
        with self.assertRaises(ValueError):
            self.run_publisher()
        self.assertEqual(old_catalog, catalog_pointer.read_bytes())
        self.assertEqual(old_translation, translation_pointer.read_bytes())


if __name__ == "__main__":
    unittest.main()
