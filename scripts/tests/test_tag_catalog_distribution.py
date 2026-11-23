import hashlib
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import MagicMock, Mock, patch

import requests

from scripts import build_tag_catalog as release
from scripts import update_tags as collector
from scripts.tag_catalog import MIN_ROWS, TAG_TYPES, TagInfo, parse_catalog


def snapshot():
    return json.dumps([[index + 1, "tag", 100, index % len(TAG_TYPES) + 1]
                       for index in range(MIN_ROWS)]).encode("utf-8")


class CatalogReleaseTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.snapshot = snapshot()

    def test_pointer_binds_exact_payload_bytes_and_revisions(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            source = path / "tags.json"
            source.write_bytes(self.snapshot)
            output = path / "feed"
            pointer = release.prepare_release(source, 7, output)
            first = pointer.read_bytes()
            manifest = release.parse_manifest(first)
            digest = hashlib.sha256(self.snapshot).hexdigest()
            payload = output / f"catalog-{digest}.json"
            self.assertEqual(self.snapshot, payload.read_bytes())
            self.assertEqual(digest, manifest["sha256"])
            self.assertEqual(len(self.snapshot), manifest["sizeBytes"])
            self.assertEqual(7, manifest["revision"])
            release.prepare_release(source, 7, output)
            self.assertEqual(first, pointer.read_bytes())
            with self.assertRaises(ValueError):
                release.prepare_release(source, 6, output)

            source.write_bytes(self.snapshot + b"\n")
            with self.assertRaises(ValueError):
                release.prepare_release(source, 7, output)
            release.prepare_release(source, 8, output)
            self.assertEqual(8, release.parse_manifest(pointer.read_bytes())["revision"])
            self.assertEqual(self.snapshot, payload.read_bytes())

    def test_interrupted_pointer_write_keeps_previous_release(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            source = path / "tags.json"
            source.write_bytes(self.snapshot)
            output = path / "feed"
            pointer = release.prepare_release(source, 1, output)
            first = pointer.read_bytes()
            source.write_bytes(self.snapshot + b"\n")
            original_write = release.atomic_write

            def fail_pointer(dest, data):
                if dest == pointer:
                    raise OSError("simulated pointer failure")
                original_write(dest, data)

            with patch.object(release, "atomic_write", side_effect=fail_pointer), self.assertRaises(OSError):
                release.prepare_release(source, 2, output)
            self.assertEqual(first, pointer.read_bytes())
            self.assertEqual(2, len(list(output.glob("catalog-*.json"))))
            release.prepare_release(source, 2, output)
            self.assertEqual(2, release.parse_manifest(pointer.read_bytes())["revision"])

    def test_manifest_rejects_unsupported_metadata(self):
        valid = {**release.IDENTITY, "revision": 7, "sizeBytes": 100, "sha256": "a" * 64}
        for fields in ({"schemaVersion": True}, {"revision": 2.0}, {"kind": "locale"},
                       {"source": "legacy-upstream"}, {"sizeBytes": 0}, {"sha256": "A" * 64},
                       {"url": "https://unexpected.invalid"}):
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                release.parse_manifest(json.dumps(valid | fields).encode())
        duplicate_revision = json.dumps(valid)[:-1] + ', "revision": 8}'
        with self.assertRaises(ValueError):
            release.parse_manifest(duplicate_revision.encode())

    def test_manifest_stays_unchanged_on_bad_catalog_or_corrupt_immutable_payload(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            source = path / "tags.json"
            source.write_bytes(self.snapshot)
            output = path / "feed"
            pointer = release.prepare_release(source, 1, output)
            first = pointer.read_bytes()
            source.write_bytes(b"[]")
            with self.assertRaises(ValueError):
                release.prepare_release(source, 2, output)
            source.write_bytes(self.snapshot)
            next(output.glob("catalog-*.json")).write_bytes(b"bad file")
            with self.assertRaises(ValueError):
                release.prepare_release(source, 2, output)
            self.assertEqual(first, pointer.read_bytes())

    def test_release_preserves_existing_identities(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            source = path / "tags.json"
            source.write_bytes(self.snapshot)
            output = path / "feed"
            pointer = release.prepare_release(source, 1, output)
            first = pointer.read_bytes()
            for mutation in (b'[1, "tag", 100, 2]', b'[50001, "tag", 100, 1]'):
                source.write_bytes(self.snapshot.replace(b'[1, "tag", 100, 1]', mutation, 1))
                with self.assertRaisesRegex(ValueError, "identity must be retained"):
                    release.prepare_release(source, 2, output)
                self.assertEqual(first, pointer.read_bytes())


class CatalogCollectorTest(unittest.TestCase):
    def setUp(self):
        self.previous = {type_id: TagInfo(type_id, "name", 100, type_id) for _, type_id in TAG_TYPES}
        self.pages = {f"tags/{name}?page=1&per_page=100": {
            "num_pages": 1, "result": [{"id": type_id, "name": "name", "count": 100}],
        } for name, type_id in TAG_TYPES}

    def collect_small(self):
        with patch.object(collector, "MIN_ROWS", len(TAG_TYPES)):
            return collector.collect(self.pages.__getitem__, self.previous)

    def test_complete_crawl_and_incomplete_coverage(self):
        self.assertEqual(self.previous, self.collect_small())
        self.pages["tags/group?page=1&per_page=100"]["result"][0]["id"] = 400
        with self.assertRaisesRegex(ValueError, "Too many known group IDs missing"):
            self.collect_small()

    def test_duplicate_id_and_type_changes_are_not_collapsed(self):
        row = {"id": 1, "name": "name", "count": 100}
        self.pages["tags/parody?page=1&per_page=100"]["result"].append(row)
        with self.assertRaisesRegex(ValueError, "Duplicate tag ID"):
            self.collect_small()
        with self.assertRaisesRegex(ValueError, "type changed"):
            collector.merge(self.previous, {1: TagInfo(1, "name", 100, 2)})

    def test_pagination_rejects_short_pages_and_changing_totals(self):
        page = self.pages["tags/parody?page=1&per_page=100"]
        page["num_pages"] = 2
        with self.assertRaisesRegex(ValueError, "Incomplete page"):
            self.collect_small()
        page["result"] += [{"id": id_, "name": "extra", "count": 1} for id_ in range(100, 199)]
        self.pages["tags/parody?page=2&per_page=100"] = {"num_pages": 3, "result": []}
        with self.assertRaisesRegex(ValueError, "Inconsistent page count"):
            self.collect_small()

    def test_count_only_change_advances_revision_and_failure_leaves_files_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            data = path / "tags.json"
            version = path / "tagsVersion"
            data.write_bytes(snapshot())
            version.write_bytes(b"43\n")
            with self.assertRaises(ValueError):
                collector.update_snapshot(path, lambda _endpoint: {})
            self.assertEqual(b"43\n", version.read_bytes())
            self.assertFalse((path / "tagsPretty.json").exists())
            with patch.object(collector, "collect", return_value={1: TagInfo(1, "tag", 110, 1)}):
                self.assertFalse(collector.update_snapshot(path, None))
            with patch.object(collector, "collect", return_value={1: TagInfo(1, "tag", 111, 1)}):
                self.assertTrue(collector.update_snapshot(path, None))
            self.assertEqual(b"44\n", version.read_bytes())
            self.assertEqual(111, parse_catalog(data.read_bytes())[1].amount)

    def test_snapshot_recovery_rolls_back_interrupted_multi_file_commit(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            old = {"tagsPretty.json": b"old pretty", "tags.json": b"old catalog",
                   "tagsVersion": b"43\n"}
            for name, content in old.items():
                (path / name).write_bytes(content)
            staging = path / ".tag-catalog-interrupted"
            staging.mkdir()
            for name, content in old.items():
                (staging / (name + ".previous")).write_bytes(content)
                (staging / (name + ".next")).write_bytes(b"new " + content)
            (path / "tagsPretty.json").write_bytes(b"new pretty")
            (path / collector.TRANSACTION).write_text(json.dumps(
                {"directory": staging.name, "previous": {name: True for name in old}}))
            collector.recover_snapshot(path)
            self.assertEqual(old, {name: (path / name).read_bytes() for name in old})
            self.assertFalse((path / collector.TRANSACTION).exists())
            self.assertFalse(staging.exists())

    def test_snapshot_recovery_preserves_targets_when_later_backup_is_missing(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            old = {"tagsPretty.json": b"old pretty", "tags.json": b"old catalog",
                   "tagsVersion": b"43\n"}
            interrupted = {**old, "tagsPretty.json": b"new pretty"}
            for name, content in interrupted.items():
                (path / name).write_bytes(content)
            staging = path / ".tag-catalog-interrupted"
            staging.mkdir()
            for name in ("tagsPretty.json", "tags.json"):
                (staging / (name + ".previous")).write_bytes(old[name])
            journal = path / collector.TRANSACTION
            journal.write_text(json.dumps(
                {"directory": staging.name, "previous": {name: True for name in old}}))

            with self.assertRaisesRegex(ValueError, "recovery files are incomplete"):
                collector.recover_snapshot(path)
            self.assertEqual(interrupted, {name: (path / name).read_bytes() for name in old})
            self.assertTrue(journal.is_file())

            (staging / "tagsVersion.previous").write_bytes(old["tagsVersion"])
            collector.recover_snapshot(path)
            self.assertEqual(old, {name: (path / name).read_bytes() for name in old})
            self.assertFalse(journal.exists())
            self.assertFalse(staging.exists())

    def test_snapshot_write_failure_restores_every_previous_file(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory)
            old = {"tagsPretty.json": b"old pretty", "tags.json": b"old catalog",
                   "tagsVersion": b"43\n"}
            for name, content in old.items():
                (path / name).write_bytes(content)
            original_write = collector.atomic_write
            failed = False

            def fail_once(target, content):
                nonlocal failed
                if target == path / "tags.json" and not failed:
                    failed = True
                    raise OSError("simulated disk failure")
                original_write(target, content)

            with patch.object(collector, "atomic_write", side_effect=fail_once):
                with self.assertRaisesRegex(OSError, "disk failure"):
                    collector.write_snapshot(path, {
                        "tagsPretty.json": b"new pretty", "tags.json": b"new catalog",
                        "tagsVersion": b"44\n",
                    })
            self.assertEqual(old, {name: (path / name).read_bytes() for name in old})
            self.assertFalse((path / collector.TRANSACTION).exists())

    def test_rate_limit_is_bounded_and_requests_have_timeouts(self):
        session = MagicMock()
        session.get.return_value.__enter__.return_value = Mock(status_code=429, headers={"Retry-After": "3"})
        with patch.object(collector.time, "sleep") as sleep, self.assertRaisesRegex(ValueError, "exhausted retries"):
            collector.CatalogApi(session).get("fixture")
        self.assertEqual(3, session.get.call_count)
        self.assertEqual(2, sleep.call_count)
        self.assertEqual((5, 10), session.get.call_args.kwargs["timeout"])
        self.assertFalse(session.get.call_args.kwargs["allow_redirects"])

    def test_transient_connection_and_partial_body_failures_retry_the_whole_page(self):
        session = MagicMock()
        partial = MagicMock()
        partial_response = partial.__enter__.return_value
        partial_response.status_code = 200
        partial_response.headers = {}

        def interrupted_body(_size):
            yield b'{"partial":'
            raise requests.exceptions.ChunkedEncodingError("interrupted")

        partial_response.iter_content.side_effect = interrupted_body
        success = MagicMock()
        success_response = success.__enter__.return_value
        success_response.status_code = 200
        success_response.headers = {}
        success_response.iter_content.return_value = [b'{"result":[]}']
        session.get.side_effect = [requests.exceptions.ConnectTimeout("timeout"), partial, success]
        with patch.object(collector.time, "sleep") as sleep:
            self.assertEqual({"result": []}, collector.CatalogApi(session).get("fixture"))
        self.assertEqual(3, session.get.call_count)
        self.assertEqual(2, sleep.call_count)
        partial.__exit__.assert_called_once()
        success.__exit__.assert_called_once()

    def test_network_retry_budget_is_bounded_and_certificate_errors_fail_immediately(self):
        for error in (requests.exceptions.ReadTimeout("timeout"), requests.exceptions.ConnectionError("lost"),
                      requests.exceptions.ChunkedEncodingError("interrupted")):
            with self.subTest(error=error):
                session = MagicMock()
                session.get.side_effect = error
                with patch.object(collector.time, "sleep"), self.assertRaisesRegex(ValueError, "exhausted retries"):
                    collector.CatalogApi(session).get("fixture")
                self.assertEqual(collector.MAX_ATTEMPTS, session.get.call_count)
        session = MagicMock()
        session.get.side_effect = requests.exceptions.SSLError("certificate rejected")
        with self.assertRaises(requests.exceptions.SSLError):
            collector.CatalogApi(session).get("fixture")
        self.assertEqual(1, session.get.call_count)

        unavailable = MagicMock()
        unavailable.__enter__.return_value = Mock(status_code=503, headers={})
        session = MagicMock()
        session.get.side_effect = [unavailable, requests.exceptions.ReadTimeout("timeout"), unavailable]
        with patch.object(collector.time, "sleep"), self.assertRaisesRegex(ValueError, "HTTP 503"):
            collector.CatalogApi(session).get("fixture")
        self.assertEqual(collector.MAX_ATTEMPTS, session.get.call_count)

    def test_invalid_json_does_not_retry(self):
        session = MagicMock()
        response = session.get.return_value.__enter__.return_value
        response.status_code = 200
        response.headers = {}
        response.iter_content.return_value = [b"invalid json"]
        with self.assertRaises(ValueError):
            collector.CatalogApi(session).get("fixture")
        self.assertEqual(1, session.get.call_count)

    def test_oversized_api_response_is_rejected(self):
        session = MagicMock()
        response = session.get.return_value.__enter__.return_value
        response.status_code = 200
        response.headers = {}
        response.iter_content.return_value = [b" " * 4096] * 257
        with self.assertRaisesRegex(ValueError, "size limit"):
            collector.CatalogApi(session).get("fixture")

    def test_completed_body_over_time_budget_is_not_accepted(self):
        session = MagicMock()
        response = session.get.return_value.__enter__.return_value
        response.status_code = 200
        response.headers = {}
        response.iter_content.return_value = [b"{}"]
        with patch.object(collector.time, "monotonic", side_effect=[1, 1, 2, 32]):
            with self.assertRaisesRegex(ValueError, "time limit"):
                collector.CatalogApi(session).get("fixture")

    def test_redirect_body_is_never_read(self):
        class RedirectAdapter(requests.adapters.BaseAdapter):
            def send(self, request, **kwargs):
                response = requests.Response()
                response.status_code = 302
                response.request = request
                response.url = request.url
                response.headers["Location"] = "/elsewhere"
                response.raw = Mock()
                response.raw._original_response = None
                response.raw.stream.side_effect = AssertionError("Read a redirect body")
                return response

            def close(self):
                pass

        with collector.CatalogSession() as session:
            session.trust_env = False
            session.mount("https://", RedirectAdapter())
            with self.assertRaisesRegex(ValueError, "HTTP 302"):
                collector.CatalogApi(session).get("fixture")


if __name__ == "__main__":
    unittest.main()
