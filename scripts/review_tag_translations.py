"""Draft, review, and accept local tag translation proposals."""

import argparse
import hashlib
import json
from pathlib import Path

from scripts.build_tag_translation_pack import (
    encode_pack, normalize_translation, parse_pack, validate_locale, validate_revision, validate_text,
)
from scripts.tag_catalog import MAX_BYTES, atomic_write, decode_json, parse_catalog, read_bounded


SCHEMA_VERSION = 1
REPORT_SCHEMA_VERSION = 2
MAX_CHANGES = 1_000
CHANGE_FIELDS = {"id", "canonicalName", "type", "before", "after"}


def encode_document(document):
    data = (json.dumps(document, ensure_ascii=True, indent=2) + "\n").encode("utf-8")
    if len(data) > MAX_BYTES:
        raise ValueError("Review document exceeds size limit; split the proposal")
    return data


def base_entries(base, locale):
    if base is None:
        return {}
    if base["locale"] != locale:
        raise ValueError("Base pack locale does not match the proposal")
    return {entry["id"]: {"name": entry["name"], "aliases": entry["aliases"]} for entry in base["entries"]}


def tag_context(catalog, tag_id):
    if type(tag_id) is not int or tag_id not in catalog:
        raise ValueError("Proposal references an unknown tag ID")
    tag = catalog[tag_id]
    return {"id": tag_id, "canonicalName": tag.title, "type": tag.category}


def draft_proposal(catalog, base, locale, ids):
    validate_locale(locale)
    if not 1 <= len(ids) <= MAX_CHANGES or len(set(ids)) != len(ids):
        raise ValueError("Expected 1 to 1,000 unique tag IDs")
    entries = base_entries(base, locale)
    return encode_document({
        "schemaVersion": SCHEMA_VERSION,
        "locale": locale,
        "changes": [
            {**tag_context(catalog, tag_id), "before": entries.get(tag_id), "after": entries.get(tag_id)}
            for tag_id in sorted(ids)
        ],
    })


def optional_translation(value):
    return None if value is None else normalize_translation(value)


def review_proposal(catalog, base, proposal_bytes, revision):
    validate_revision(revision)
    proposal = decode_json(proposal_bytes)
    if (not isinstance(proposal, dict) or proposal.keys() != {"schemaVersion", "locale", "changes"}
            or type(proposal["schemaVersion"]) is not int or proposal["schemaVersion"] != SCHEMA_VERSION):
        raise ValueError("Invalid translation proposal envelope")
    locale = validate_locale(proposal["locale"])
    entries = base_entries(base, locale)
    base_revision = base["revision"] if base is not None else None
    if base_revision is not None and revision <= base_revision:
        raise ValueError("Candidate revision must be newer than the base revision")
    changes = proposal["changes"]
    if not isinstance(changes, list) or not 1 <= len(changes) <= MAX_CHANGES:
        raise ValueError("Proposal change count outside limits")

    reviewed = {}
    for change in changes:
        if (not isinstance(change, dict) or not CHANGE_FIELDS <= change.keys()
                or change.keys() - (CHANGE_FIELDS | {"note"})):
            raise ValueError("Invalid proposed change fields")
        tag_id = change["id"]
        context = tag_context(catalog, tag_id)
        if tag_id in reviewed:
            raise ValueError(f"Duplicate proposed change for tag ID {tag_id}")
        if (change["canonicalName"] != context["canonicalName"] or type(change["type"]) is not int
                or change["type"] != context["type"]):
            raise ValueError(f"Canonical tag changed for ID {tag_id}; draft the change again")

        before = optional_translation(change["before"])
        after = optional_translation(change["after"])
        if before != entries.get(tag_id):
            raise ValueError(f"Current translation changed for ID {tag_id}; draft the change again")
        if before == after:
            raise ValueError(f"No translation change for tag ID {tag_id}")
        reviewed[tag_id] = {**context, "before": before, "after": after}
        if "note" in change:
            reviewed[tag_id]["note"] = validate_text(change["note"])
        if after is None:
            del entries[tag_id]
        else:
            entries[tag_id] = after

    if not entries:
        raise ValueError("A proposal cannot remove the last translation in a locale")
    pack = encode_pack([{"id": tag_id, **entry} for tag_id, entry in entries.items()], locale, revision, catalog)
    report = encode_document({
        "schemaVersion": REPORT_SCHEMA_VERSION,
        "locale": locale,
        "baseRevision": base_revision,
        "baseSha256": (hashlib.sha256(encode_pack(base["entries"], locale, base_revision, None)).hexdigest()
                       if base is not None else None),
        "candidateRevision": revision,
        "proposalSha256": hashlib.sha256(proposal_bytes).hexdigest(),
        "candidateSha256": hashlib.sha256(pack).hexdigest(),
        "changes": [reviewed[tag_id] for tag_id in sorted(reviewed)],
    })
    return report, pack


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    default_catalog = Path(__file__).resolve().parents[1] / "data" / "tags.json"
    for command in ("draft", "review", "accept"):
        sub = commands.add_parser(command)
        sub.add_argument("--catalog", type=Path, default=default_catalog)
        sub.add_argument("--base", type=Path, help="Current locale pack; omit for a new locale")
        sub.add_argument("--output", type=Path, required=True)
        if command == "draft":
            sub.add_argument("--locale", required=True)
            sub.add_argument("--id", type=int, action="append", required=True)
        else:
            sub.add_argument("proposal", type=Path)
            sub.add_argument("--revision", type=int, required=True)
            if command == "accept":
                sub.add_argument("--review", type=Path, required=True)
    args = parser.parse_args(argv)

    try:
        inputs = (args.catalog, args.base, getattr(args, "proposal", None), getattr(args, "review", None))
        if any(path is not None and path.resolve() == args.output.resolve() for path in inputs):
            raise ValueError("Output must be separate from the input files")
        catalog = parse_catalog(read_bounded(args.catalog, MAX_BYTES))
        base = parse_pack(read_bounded(args.base, MAX_BYTES), catalog) if args.base else None
        if args.command == "draft":
            output = draft_proposal(catalog, base, args.locale, args.id)
        else:
            report, pack = review_proposal(catalog, base, read_bounded(args.proposal, MAX_BYTES), args.revision)
            if args.command == "accept":
                if read_bounded(args.review, MAX_BYTES) != report:
                    raise ValueError("Review does not match the current proposal and candidate")
                output = pack
            else:
                output = report
        args.output.parent.mkdir(parents=True, exist_ok=True)
        atomic_write(args.output, output)
    except (OSError, ValueError) as error:
        parser.exit(1, f"Could not process translation proposal: {error}\n")
    print(f"Wrote {args.command} result ({len(output)} bytes)")


if __name__ == "__main__":
    main()
