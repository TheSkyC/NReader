import unittest
from unittest.mock import patch

from scripts.publish_update_feed import bootstrap, merge


def manifest(version, code, channel):
    return {"schemaVersion": 1, "versionName": version, "versionCode": code,
            "channel": channel, "assets": [{"id": "apk"}]}


class UpdateFeedTest(unittest.TestCase):
    def test_preserves_channels_and_does_not_downgrade(self):
        feed = {"schemaVersion": 1, "stable": None, "beta": None}
        stable = manifest("1.0.0", 100, "stable")
        beta = manifest("1.1.0-beta.2", 110, "beta")
        merge(feed, stable)
        merge(feed, beta)
        merge(feed, manifest("1.1.0-beta.1", 109, "beta"))
        self.assertEqual(stable, feed["stable"])
        self.assertEqual(beta, feed["beta"])

    def test_rejects_conflicting_version_code(self):
        feed = {"schemaVersion": 1, "stable": manifest("1.0.0", 100, "stable"), "beta": None}
        with self.assertRaises(ValueError):
            merge(feed, manifest("1.0.1", 100, "stable"))

    @patch("scripts.publish_update_feed.subprocess.check_output", return_value='[[{"draft":false,"prerelease":false,"published_at":"2026-01-01","assets":[]}] ]')
    def test_bootstrap_refuses_to_silently_drop_release(self, _check_output):
        with self.assertRaises(ValueError):
            bootstrap("owner/repository")


if __name__ == "__main__":
    unittest.main()
