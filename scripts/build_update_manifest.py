#!/usr/bin/env python3
"""Build the signed-release metadata shape consumed by the NReader updater."""

from __future__ import annotations

import argparse
import hashlib
import json
from datetime import datetime, timezone
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--repository", required=True)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--version-name", required=True)
    parser.add_argument("--version-code", required=True, type=int)
    parser.add_argument("--package-name", required=True)
    parser.add_argument("--apk", action="append", nargs=3, metavar=("FLAVOR", "MIN_SDK", "PATH"), required=True)
    args = parser.parse_args()

    channel = "beta" if "-" in args.tag else "stable"
    assets = []
    for flavor, min_sdk, raw_path in args.apk:
        path = Path(raw_path)
        if not path.is_file():
            raise SystemExit(f"Missing APK: {path}")
        assets.append({
            "id": flavor,
            "flavor": flavor,
            "packageName": args.package_name,
            "minSdk": int(min_sdk),
            "sizeBytes": path.stat().st_size,
            "sha256": sha256(path),
            "url": f"https://github.com/{args.repository}/releases/download/{args.tag}/{path.name}",
        })

    manifest = {
        "schemaVersion": 1,
        "versionName": args.version_name,
        "versionCode": args.version_code,
        "channel": channel,
        "publishedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "assets": assets,
    }
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main()
