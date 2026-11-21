# Tag translation distribution

NReader modifications by TheSkyC (2026); see NOTICE.

This feed is separate from the [canonical tag catalog](tag-distribution.md).
Each release adds or updates one locale. A locale pack never changes
canonical names, search query text, local rules, or account state.

## Translation manifest v1

The UTF-8 JSON object is limited to 32 KiB. Unknown, missing, and duplicate
fields are invalid at every level.

| Field | Value |
| --- | --- |
| `schemaVersion` | Integer `1` |
| `source` | String `nreader-translations` |
| `kind` | String `translations` |
| `packs` | Array of 1 to 128 descriptors with unique canonical locales |

Each descriptor has exactly these fields:

| Field | Value |
| --- | --- |
| `locale` | Canonical locale accepted by the [pack format](tags.md#translation-packs) |
| `revision` | Integer from 1 through 2,147,483,647, comparable within this locale and source |
| `sizeBytes` | Integer from 1 through 16 MiB |
| `sha256` | 64 lowercase hex digits |

The exact payload is the sibling file `translation-<sha256>.json`. There are
no URLs in the manifest. The payload's locale and revision must match the
descriptor. The byte count and digest cover the uncompressed JSON file as
written. For network use, the manifest and payload must come from the same
configured HTTPS origin without redirects or account credentials.

## Preparing a release locally

Start with a validated candidate from the
[local contribution workflow](tag-translation-contributions.md), or a pack
built by the authoring tool:

```powershell
python -m scripts.build_tag_translation_release .gradle/zh-Hans-candidate.json `
  --catalog data/tags.json --output .gradle/translation-feed
```

Use one publisher at a time and retain `manifest.json` and its referenced
payloads in the release directory. Use the exact bytes produced by the pack
builder or review tool. The tool validates the input pack against the
catalog, reuses an exact repeat, and rejects an older revision or a
revision reused for different bytes. It verifies the size and digest of each
retained locale, stages the new immutable file first, then atomically updates
`manifest.json`. Restore missing or damaged retained files before retrying.
Retrying after an interrupted manifest write can reuse the staged file.

Before hosting a release, verify that every referenced payload is available
and matches its descriptor, then replace the hosted manifest. Keep older
payload files available for clients that already read the old manifest. To
correct a translation, publish a corrected pack at a higher revision for
that locale. The release tool does not drop a locale from the feed.

This tool writes local release files. The digest binds bytes to the manifest
and does not establish approval, publisher identity, or freshness on a first
install. A production origin, publisher ownership, contribution license,
review process, and any stronger signing or expiration policy must be settled
before distribution is enabled.
