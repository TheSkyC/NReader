import contextlib
import hashlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from scripts import review_tag_translations as review
from scripts.build_tag_translation_pack import encode_pack, parse_pack
from scripts.tag_catalog import TagInfo


class TranslationReviewTest(unittest.TestCase):
    def setUp(self):
        self.catalog = {
            11: TagInfo(11, "one", 8, 3),
            22: TagInfo(22, "two", 10, 3),
            33: TagInfo(33, "three", 4, 4),
        }
        self.base = parse_pack(
            encode_pack([{"id": 11, "name": "old", "aliases": ["alias"]},
                         {"id": 22, "name": "unchanged"}], "ja", 4, self.catalog), self.catalog)

    def proposal(self, *ids):
        return json.loads(review.draft_proposal(self.catalog, self.base, "ja", ids))

    def test_updates_adds_and_removes_translations_with_explicit_before_values(self):
        proposal = self.proposal(33, 22, 11)
        update, removal, addition = proposal["changes"]
        update["after"] = {"name": " Cafe\u0301 ", "aliases": ["new"]}
        update["note"] = "Check \u202e notation"
        removal["after"] = None
        addition["after"] = {"name": "\u753b\u5bb6"}

        report_bytes, pack = review.review_proposal(self.catalog, self.base, review.encode_document(proposal), 5)
        report = json.loads(report_bytes)
        result = parse_pack(pack, self.catalog)

        self.assertEqual([{"id": 11, "name": "Caf\u00e9", "aliases": ["new"]},
                          {"id": 33, "name": "\u753b\u5bb6", "aliases": []}], result["entries"])
        self.assertEqual([11, 22, 33], [change["id"] for change in report["changes"]])
        self.assertEqual("one", report["changes"][0]["canonicalName"])
        self.assertEqual({"name": "old", "aliases": ["alias"]}, report["changes"][0]["before"])
        self.assertIn(b"\\u202e", report_bytes)
        self.assertEqual(hashlib.sha256(pack).hexdigest(), report["candidateSha256"])

    def test_rejects_stale_names_types_and_translations_but_not_unrelated_changes(self):
        proposal = self.proposal(11)
        proposal["changes"][0]["after"] = {"name": "translation"}
        data = review.encode_document(proposal)

        for tag in (TagInfo(11, "renamed", 8, 3), TagInfo(11, "one", 8, 4)):
            with self.subTest(tag=tag), self.assertRaisesRegex(ValueError, "Canonical tag changed"):
                review.review_proposal({**self.catalog, 11: tag}, self.base, data, 6)
        different_pack = {**self.base, "entries": [{"id": 11, "name": "concurrent", "aliases": []}]}
        with self.assertRaisesRegex(ValueError, "Current translation changed"):
            review.review_proposal(self.catalog, different_pack, data, 6)

        current_pack = {**self.base, "entries": [self.base["entries"][0],
                                                {"id": 22, "name": "concurrent", "aliases": []}]}
        counts_only = {**self.catalog, 11: TagInfo(11, "one", 99, 3)}
        _, pack = review.review_proposal(counts_only, current_pack, data, 6)
        self.assertEqual("concurrent", parse_pack(pack, self.catalog)["entries"][1]["name"])

    def test_rejects_noops_stale_revisions_duplicates_and_empty_pack(self):
        proposal = self.proposal(11, 22)
        original = review.encode_document(proposal)
        with self.assertRaisesRegex(ValueError, "must be newer"):
            review.review_proposal(self.catalog, self.base, original, 4)
        with self.assertRaisesRegex(ValueError, "No translation change"):
            review.review_proposal(self.catalog, self.base, original, 5)
        for change in proposal["changes"]:
            change["after"] = None
        with self.assertRaisesRegex(ValueError, "cannot remove the last"):
            review.review_proposal(self.catalog, self.base, review.encode_document(proposal), 5)
        proposal["changes"][1] = proposal["changes"][0]
        with self.assertRaisesRegex(ValueError, "Duplicate proposed change"):
            review.review_proposal(self.catalog, self.base, review.encode_document(proposal), 5)

    def test_accept_writes_only_the_candidate_matching_the_review_report(self):
        with tempfile.TemporaryDirectory() as folder:
            catalog, proposal, report, output = (Path(folder) / name for name in
                                                  ("catalog.json", "proposal.json", "review.json", "candidate.json"))
            catalog.write_bytes(b"fixture")
            proposal_data = json.loads(review.draft_proposal(self.catalog, None, "ja", [11]))
            proposal_data["changes"][0]["after"] = {"name": "accepted"}
            proposal.write_bytes(review.encode_document(proposal_data))
            common = [str(proposal), "--catalog", str(catalog), "--revision", "1"]
            accept = ["accept", *common, "--review", str(report), "--output", str(output)]
            with patch.object(review, "parse_catalog", return_value=self.catalog), contextlib.redirect_stdout(io.StringIO()):
                review.main(["review", *common, "--output", str(report)])
                review.main(accept)
                previous = output.read_bytes()
                self.assertEqual("accepted", parse_pack(previous, self.catalog)["entries"][0]["name"])

                proposal_data["changes"][0]["after"] = {"name": "unreviewed"}
                proposal.write_bytes(review.encode_document(proposal_data))
                with contextlib.redirect_stderr(io.StringIO()) as error, self.assertRaises(SystemExit):
                    review.main(accept)
                self.assertIn("Review does not match", error.getvalue())
                self.assertEqual(previous, output.read_bytes())

    def test_accept_requires_a_new_report_after_an_unrelated_base_edit(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            catalog = root / "catalog.json"
            proposal = root / "proposal.json"
            report = root / "review.json"
            base = root / "base.json"
            output = root / "candidate.json"
            catalog.write_bytes(b"fixture")
            base.write_bytes(encode_pack(self.base["entries"], "ja", 4, self.catalog))
            proposal_data = self.proposal(11)
            proposal_data["changes"][0]["after"] = {"name": "accepted"}
            proposal.write_bytes(review.encode_document(proposal_data))
            common = [str(proposal), "--catalog", str(catalog), "--base", str(base), "--revision", "6"]
            generate = ["review", *common, "--output", str(report)]
            accept = ["accept", *common, "--review", str(report), "--output", str(output)]
            with patch.object(review, "parse_catalog", return_value=self.catalog), contextlib.redirect_stdout(io.StringIO()):
                review.main(generate)
                review.main(accept)
                previous = output.read_bytes()

                edited = [self.base["entries"][0], {"id": 22, "name": "concurrent"}]
                base.write_bytes(encode_pack(edited, "ja", 5, self.catalog))
                with contextlib.redirect_stderr(io.StringIO()) as error, self.assertRaises(SystemExit):
                    review.main(accept)
                self.assertIn("Review does not match", error.getvalue())
                self.assertEqual(previous, output.read_bytes())

                review.main(generate)
                review.main(accept)
                entries = parse_pack(output.read_bytes(), self.catalog)["entries"]
                self.assertEqual(["accepted", "concurrent"], [entry["name"] for entry in entries])

    def test_cli_reviews_removal_of_an_id_missing_from_target_catalog(self):
        with tempfile.TemporaryDirectory() as folder:
            root = Path(folder)
            target_file, old_file, base_file, proposal_file, report_file, output = (
                root / name for name in ("target.json", "old.json", "base.json", "proposal.json",
                                         "review.json", "candidate.json"))
            target_file.write_bytes(b"target")
            old_file.write_bytes(b"old")
            target_catalog = {22: self.catalog[22], 33: self.catalog[33]}
            base_file.write_bytes(encode_pack(self.base["entries"], "ja", 4, self.catalog))
            common = ["--catalog", str(target_file), "--base-catalog", str(old_file), "--base", str(base_file)]

            def parse_fixture(data):
                return self.catalog if data == b"old" else target_catalog

            with patch.object(review, "parse_catalog", side_effect=parse_fixture), contextlib.redirect_stdout(io.StringIO()):
                review.main(["draft", *common, "--locale", "ja", "--id", "11", "--output", str(proposal_file)])
                proposal = json.loads(proposal_file.read_bytes())
                self.assertEqual("one", proposal["changes"][0]["canonicalName"])
                proposal["changes"][0]["after"] = None
                proposal_file.write_bytes(review.encode_document(proposal))
                args = [str(proposal_file), *common, "--revision", "5"]
                review.main(["review", *args, "--output", str(report_file)])
                review.main(["accept", *args, "--review", str(report_file), "--output", str(output)])
            self.assertEqual([22], [row["id"] for row in parse_pack(output.read_bytes(), target_catalog)["entries"]])
            self.assertEqual(11, json.loads(report_file.read_bytes())["changes"][0]["id"])

    def test_missing_target_ids_can_only_be_removed_and_must_all_be_resolved(self):
        target = {33: self.catalog[33]}
        proposal = json.loads(review.draft_proposal(target, self.base, "ja", [11], self.catalog))
        proposal["changes"][0]["after"] = {"name": "still present"}
        with self.assertRaisesRegex(ValueError, "must be removed"):
            review.review_proposal(target, self.base, review.encode_document(proposal), 5, self.catalog)
        proposal["changes"][0]["after"] = None
        with self.assertRaisesRegex(ValueError, "unknown tag ID"):
            review.review_proposal(target, self.base, review.encode_document(proposal), 5, self.catalog)
        with self.assertRaisesRegex(ValueError, "unknown tag ID"):
            review.draft_proposal(target, self.base, "ja", [11], target)
        conflicting = {**self.catalog, 11: TagInfo(11, "one", 8, 4)}
        with self.assertRaisesRegex(ValueError, "Catalog type changed"):
            review.draft_proposal(conflicting, self.base, "ja", [11], self.catalog)


if __name__ == "__main__":
    unittest.main()
