# Signed tag feed manifests

NReader modifications by TheSkyC (2026); see NOTICE.

This document defines a separate signed manifest endpoint for the catalog and
translation feeds. Existing `manifest.json` endpoints and version 1 readers are
unchanged. This protocol does not authenticate an unsigned manifest.

The signed endpoint is `manifest-v2.json` in the same directory as the existing
manifest and payload files. It is a UTF-8 JSON object, limited to 64 KiB, with
exactly three fields: integer `schemaVersion` equal to 2, `manifest` containing
standard padded Base64 of the exact version 1 manifest bytes, and `signature`
containing standard padded Base64 of a DER-encoded ECDSA signature. Duplicate,
missing, and unknown fields are invalid. Base64 must be canonical. The embedded
manifest keeps all of the version 1 limits and validation rules.

The signature algorithm is SHA256withECDSA using a P-256 key. Its input is the
UTF-8 bytes `NReader tag catalog manifest v2\n` for a catalog or
`NReader tag translation manifest v2\n` for a translation feed, followed by
the exact embedded manifest bytes. The domain prefix prevents reuse across
feed types. The public key is distributed with the app; the private key is
never published. Signed feed mode must verify before parsing or using the
embedded manifest and must not fall back to an unsigned source on failure.

The signed envelope and referenced immutable payloads are staged before a
publisher updates the hosted `manifest-v2.json` pointer. A single envelope
avoids a separately updated signature and manifest becoming mismatched. Old
payloads remain available for clients that already read an earlier manifest.
The payload digest and revision checks continue to apply after verification.

An installed higher revision is not replaced by a lower revision. A valid old
signed manifest can still be replayed on a first installation, and key compromise
is not prevented by this format. Replacing a lost or compromised key requires a
trusted app update containing the replacement public key. Keep a secure backup
of the private key and restrict access to the publisher.

## Publishing procedure

The `Publish Signed Tag Feeds` GitHub Actions workflow builds the catalog,
validates both feeds and their payloads, and signs changed manifests using the
repository Actions secret `NREADER_FEED_SIGNING_KEY`. It verifies the key
against the public key embedded in the app, verifies the resulting signatures,
and publishes the immutable payloads and pointers in a single commit. Unchanged
manifests keep their existing signatures. The workflow runs after relevant
changes on `main`, and can also be started manually. If signing or validation
fails, the workflow does not publish its changes. Concurrent unrelated pushes
are retried; if feed inputs change concurrently or retries are exhausted, rerun
the workflow against the current `main`.

For manual recovery, first run `python -m scripts.verify_tag_feed --catalog-feed
feed/catalog --translation-feed feed/translations`. Then sign the exact v1 pointer
bytes with the local key:

```text
python -m scripts.sign_tag_feed sign --kind catalog --key <private-key> \
  --input feed/catalog/manifest.json --output feed/catalog/manifest-v2.json
python -m scripts.sign_tag_feed sign --kind translation --key <private-key> \
  --input feed/translations/manifest.json --output feed/translations/manifest-v2.json
```

Verify both generated envelopes with the public key before publishing the
immutable payloads and then the matching `manifest-v2.json` files. The release
workflow configures the signed endpoints.
An installed unsigned payload at the same revision is not reinstalled or
retroactively authenticated when the client changes to the signed endpoint.
