import base64
import json
import unittest

from cryptography.hazmat.primitives.asymmetric import ec

from scripts.sign_tag_feed import sign_manifest, verify_manifest


class SignedTagFeedTest(unittest.TestCase):
    def setUp(self):
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.manifest = (b'{"schemaVersion":1,"source":"nreader-catalog",'
                         b'"kind":"catalog","revision":1,"sizeBytes":1,"sha256":"'
                         b'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}\n')

    def test_round_trip_and_domain_separation(self):
        envelope = sign_manifest(self.key, "catalog", self.manifest)
        self.assertEqual(self.manifest, verify_manifest(self.key.public_key(), "catalog", envelope))
        with self.assertRaises(ValueError):
            verify_manifest(self.key.public_key(), "translation", envelope)
        with self.assertRaises(ValueError):
            verify_manifest(ec.generate_private_key(ec.SECP256R1()).public_key(), "catalog", envelope)

    def test_tampered_and_invalid_envelopes_are_rejected(self):
        envelope = sign_manifest(self.key, "translation", self.manifest)
        parsed = json.loads(envelope)
        parsed["manifest"] = base64.b64encode(b"different").decode("ascii")
        with self.assertRaises(ValueError):
            verify_manifest(self.key.public_key(), "translation", json.dumps(parsed).encode())
        with self.assertRaises(ValueError):
            verify_manifest(self.key.public_key(), "catalog", b'{"schemaVersion":2,"schemaVersion":2}')
        with self.assertRaises(ValueError):
            verify_manifest(self.key.public_key(), "catalog", b"{}")

    def test_invalid_base64_is_rejected(self):
        with self.assertRaises(ValueError):
            verify_manifest(self.key.public_key(), "catalog",
                            b'{"schemaVersion":2,"manifest":"!","signature":"!"}')


if __name__ == "__main__":
    unittest.main()
