# Tag translation distribution

NReader modifications by TheSkyC (2026); see NOTICE.

This feed is separate from the [canonical tag catalog](tag-distribution.md).
Each release adds or updates one locale. A locale pack never changes
canonical names, search query text, local rules, or account state.

## Translation manifest v1

The UTF-8 JSON object is limited to 32 KiB and must not have a byte order
mark. Unknown, missing, and duplicate fields are invalid at every level.

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

Android verifies the staged file's length and digest before starting a pack
import. The importer compares the payload's locale and revision inside the
transaction before replacing anything. A rejected or cancelled pack leaves
installed data intact. A descriptor can replace a locale only if its revision
is newer than the currently installed pack, including one installed from a
local file. The app does not automatically switch sources.

The downloader does not send account cookies or credentials. It uses the
configured manifest URL and derives the payload URL from the selected
descriptor. It requests identity encoding and bounds each response even
when the server does not declare a length. It stages only the selected
locale and discards the staging file on success, failure, or cancellation.

## Enabling the Settings action

Set the Gradle property `nreaderTagTranslationManifestUrl` to the HTTPS URL of
the hosted `manifest.json`. The default is empty. When configured, users can
choose **Settings → Tag languages → Browse online** to fetch the locale list.
Only new languages and revisions newer than the installed pack appear. The
app shows the download size before installing the selected pack. Requests
are started only from this action and can be cancelled. Import from a file
and removal of installed packs continue to work offline.

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
revision reused for different bytes. It verifies the bytes, locale, revision,
and catalog IDs of each retained pack. It stages the new immutable file
first, then atomically updates `manifest.json`. Restore missing or damaged
retained files before retrying.
Retrying after an interrupted manifest write can reuse the staged file.

The producer and Android feed tests share a small synthetic release fixture.
The producer regenerates its exact manifest and candidate bytes from a
proposal; Android verifies the payload digest, installs it, and finds the
canonical tag through its localized alias.

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

## Verifying a staged copy

Before switching the hosted pointer, check a local copy containing the new
`manifest.json` and all payloads it references against the target catalog:

```powershell
python -m scripts.verify_tag_feed --catalog data/tags.json --translation-feed .gradle/staged-translations
```

If the target catalog is itself staged as a feed, use
`--catalog-feed .gradle/staged-catalog` in place of `--catalog data/tags.json`.
The verifier checks exact filename casing, manifest format and limits, payload
size and digest, locale, revision, pack format, and canonical ID coverage for
every entry. It lists the validated locales and revisions on success, exits
nonzero on failure, and writes no files. It does not exercise installation in
Android or verify approval, freshness, or the hosted HTTP responses.
