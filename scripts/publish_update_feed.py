#!/usr/bin/env python3
"""Merge a published release manifest into the update feed."""

import argparse
import json
import subprocess
import tempfile
from pathlib import Path


def merge(feed, manifest):
    if feed.get("schemaVersion") != 1 or set(feed) != {"schemaVersion", "stable", "beta"}:
        raise ValueError("Invalid update feed")
    channel = manifest.get("channel")
    if channel not in ("stable", "beta") or manifest.get("schemaVersion") != 1:
        raise ValueError("Invalid release manifest")
    code = manifest.get("versionCode")
    if not isinstance(code, int) or isinstance(code, bool) or code <= 0 or not manifest.get("assets"):
        raise ValueError("Invalid release manifest version or assets")
    previous = feed[channel]
    if previous is not None:
        old_code = previous.get("versionCode")
        if not isinstance(old_code, int) or isinstance(old_code, bool) or old_code <= 0:
            raise ValueError("Invalid existing release manifest")
        if code < old_code:
            return
        if code == old_code and previous.get("versionName") != manifest.get("versionName"):
            raise ValueError("Conflicting releases with the same version code")
    feed[channel] = manifest


def bootstrap(repository):
    output = subprocess.check_output(
        ["gh", "api", "--paginate", "--slurp", f"repos/{repository}/releases?per_page=100"], text=True
    )
    releases = [item for page in json.loads(output) for item in page if not item.get("draft")]
    feed = {"schemaVersion": 1, "stable": None, "beta": None}
    for channel in ("stable", "beta"):
        candidates = [item for item in releases if bool(item.get("prerelease")) == (channel == "beta")]
        if not candidates:
            continue
        latest = max(candidates, key=lambda item: item.get("published_at") or "")
        if not any(asset.get("name") == "nreader-update.json" for asset in latest.get("assets", [])):
            raise ValueError(f"Latest {channel} release has no update manifest; initialize the feed manually")
        for release in candidates:
            if not any(asset.get("name") == "nreader-update.json" for asset in release.get("assets", [])):
                continue
            with tempfile.TemporaryDirectory() as directory:
                subprocess.run(["gh", "release", "download", release["tag_name"], "--repo", repository,
                                "--pattern", "nreader-update.json", "--dir", directory], check=True)
                manifest = json.loads((Path(directory) / "nreader-update.json").read_text(encoding="utf-8"))
            if manifest.get("channel") != channel:
                raise ValueError(f"Release channel mismatch: {release['tag_name']}")
            merge(feed, manifest)
    return feed


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--feed", type=Path, required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--repository", required=True)
    args = parser.parse_args()
    feed = json.loads(args.feed.read_text(encoding="utf-8")) if args.feed.exists() else bootstrap(args.repository)
    manifest = json.loads(args.manifest.read_text(encoding="utf-8"))
    merge(feed, manifest)
    args.feed.parent.mkdir(parents=True, exist_ok=True)
    args.feed.write_text(json.dumps(feed, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
