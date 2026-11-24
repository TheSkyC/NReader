"""Update signed feed pointers using the publisher key from the environment."""

import argparse
import base64
import os
from pathlib import Path

from cryptography.hazmat.primitives import serialization

from scripts import build_tag_catalog, build_tag_translation_release, sign_tag_feed
from scripts.tag_catalog import atomic_write

PUBLIC_KEY = (
    "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE8H2GwUYavL11UtqDdVk7iYNTP"
    "cespbX4UisBe+ezvxIP8LsRJlKXrrGOufcNx3c8lzkJYmRvKNCHhpq2rjPZlQ=="
)


def prepare(directory, kind, key, public_key):
    manifest = sign_tag_feed._read(directory / "manifest.json", sign_tag_feed.MANIFEST_LIMITS[kind])
    parser = (build_tag_catalog.parse_manifest if kind == "catalog"
              else build_tag_translation_release.parse_manifest)
    current = parser(manifest)
    pointer = directory / "manifest-v2.json"
    if pointer.exists():
        previous_bytes = sign_tag_feed.verify_manifest(
            public_key, kind, sign_tag_feed._read(pointer, sign_tag_feed.ENVELOPE_LIMIT))
        previous = parser(previous_bytes)
        if kind == "catalog":
            if (current["revision"] < previous["revision"] or
                    (current["revision"] == previous["revision"] and current != previous)):
                raise ValueError("Catalog revision cannot be lowered or reused")
        else:
            for locale, old in previous.items():
                new = current.get(locale)
                if (new is None or new["revision"] < old["revision"] or
                        (new["revision"] == old["revision"] and new != old)):
                    raise ValueError("Translation revision cannot be removed, lowered, or reused")
        if manifest == previous_bytes:
            return pointer, None
    envelope = sign_tag_feed.sign_manifest(key, kind, manifest)
    if sign_tag_feed.verify_manifest(public_key, kind, envelope) != manifest:
        raise ValueError("Signed manifest mismatch")
    return pointer, envelope


def publish(catalog, translations, private_key_pem):
    key = sign_tag_feed._key(private_key_pem, True)
    public_bytes = key.public_key().public_bytes(
        serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo)
    if public_bytes != base64.b64decode(PUBLIC_KEY):
        raise ValueError("Signing key does not match the app public key")
    public_key = key.public_key()
    outputs = [prepare(catalog, "catalog", key, public_key),
               prepare(translations, "translation", key, public_key)]
    for path, envelope in outputs:
        if envelope is not None:
            atomic_write(path, envelope)
    return sum(envelope is not None for _, envelope in outputs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--catalog-feed", type=Path, required=True)
    parser.add_argument("--translation-feed", type=Path, required=True)
    args = parser.parse_args()
    try:
        secret = os.environ.pop("NREADER_FEED_SIGNING_KEY", "").encode("utf-8")
        if not secret:
            raise ValueError("Missing NREADER_FEED_SIGNING_KEY")
        changed = publish(args.catalog_feed, args.translation_feed, secret)
    except (OSError, ValueError, TypeError) as error:
        parser.exit(1, f"Could not sign tag feeds: {error}\n")
    print(f"Signed pointers updated: {changed}")


if __name__ == "__main__":
    main()
