"""Verify local staged tag feed files without modifying them."""

import argparse
from pathlib import Path

from scripts import build_tag_catalog as catalog_release
from scripts import build_tag_translation_release as translation_release
from scripts.tag_catalog import MAX_BYTES, parse_catalog, read_bounded


def require_filename(directory: Path, names: set[str], name: str) -> Path:
    if name not in names:
        raise ValueError(f"Missing file with exact name: {directory / name}")
    return directory / name


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    catalog = parser.add_mutually_exclusive_group(required=True)
    catalog.add_argument("--catalog-feed", type=Path, help="Staged catalog feed directory")
    catalog.add_argument("--catalog", type=Path, help="Canonical catalog snapshot for translation verification")
    parser.add_argument("--translation-feed", type=Path, help="Staged translation feed directory")
    args = parser.parse_args(argv)
    if args.catalog is not None and args.translation_feed is None:
        parser.error("--catalog requires --translation-feed")

    verified = []
    try:
        if args.catalog_feed is not None:
            names = {entry.name for entry in args.catalog_feed.iterdir()}
            pointer = require_filename(args.catalog_feed, names, "manifest.json")
            manifest = catalog_release.parse_manifest(read_bounded(pointer, catalog_release.MAX_MANIFEST_BYTES))
            require_filename(args.catalog_feed, names, catalog_release.payload_name(manifest["sha256"]))
            tags = catalog_release.read_payload(args.catalog_feed, manifest)
            verified.append(f"Catalog revision {manifest['revision']}: {len(tags)} tags")
        else:
            tags = parse_catalog(read_bounded(args.catalog, MAX_BYTES))

        if args.translation_feed is not None:
            names = {entry.name for entry in args.translation_feed.iterdir()}
            pointer = require_filename(args.translation_feed, names, "manifest.json")
            packs = translation_release.parse_manifest(read_bounded(pointer, translation_release.MAX_MANIFEST_BYTES))
            for locale, descriptor in sorted(packs.items()):
                try:
                    require_filename(args.translation_feed, names, translation_release.payload_name(descriptor["sha256"]))
                    translation_release.verify_payload(args.translation_feed, descriptor, tags)
                except (OSError, ValueError) as error:
                    raise ValueError(f"Cannot verify translation payload for {locale}: {error}") from error
                verified.append(f"Translation {locale}: revision {descriptor['revision']}")
    except (OSError, ValueError) as error:
        parser.exit(1, f"Tag feed verification failed: {error}\n")
    print("\n".join(verified))


if __name__ == "__main__":
    main()
