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
The v1 manifest does not advertise a pack schema version. Current tooling and
the current client require pack version 2; older clients reject it during import
and retain their installed pack. The current feed is publicly hosted for beta
use, but this unsigned manifest is not a stable compatibility or rollout
mechanism: it does not authenticate the publisher or prevent first-install replay.

Android verifies the staged file's length and digest before starting a pack
import. The importer compares the payload's locale and revision with the
descriptor before the transaction, and checks the installed revision again
inside the transaction before replacing anything. A rejected or cancelled pack
leaves installed data intact. A descriptor can replace a locale only if its revision
is newer than the currently installed pack, including one installed from a
local file. The app does not automatically switch sources.

The downloader does not send account cookies or credentials. It uses the
configured manifest URL and derives the payload URL from the selected
descriptor. It requests identity encoding and bounds each response even
when the server does not declare a length. It stages only the selected
locale and discards the staging file on success, failure, or cancellation.
The Android public feed client rejects redirects and has no cookie jar,
authenticator, or application/network interceptors, so a configured feed
cannot silently hand off the request to another origin or account context.

## Enabling the Settings action

Set the Gradle property `nreaderTagTranslationManifestUrl` to the HTTPS URL of
the hosted `manifest.json`, and explicitly set
`nreaderEnableUnsignedTagFeeds=true` for the current unsigned v1 contract. The
default URL is empty. When configured, users can choose **Settings → Tag
languages → Browse online** to fetch the locale list. The initial beta release
selects the `zh-Hans` pack from `feed/translations/`; keep the
translation URL empty in other builds unless the feed and attribution record
are intentionally selected.
Only new languages and revisions newer than the installed pack appear. The
app shows the download size before installing the selected pack. Requests
are started only from this action and can be cancelled. Import from a file
and removal of installed packs continue to work offline.

## Preparing a release locally

Use CPython 3.14 with Unicode 16.0 data to validate or stage translation packs.
The verifier has the same requirement when checking a translation feed; a
catalog-only verification does not require it.

If an unpublished development release contains a version 1 pack or aliases that
collide under Unicode 16, verification or staging its replacement fails. Preserve
that release directory. Correct and review the affected source
as described in the contribution workflow, then stage the corrected packs in a
new development directory. Do not replace immutable payloads or reuse a locale
revision for different bytes. The public beta feed needs an explicit
compatibility and migration policy before using this recovery path.

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
and catalog IDs of each pack still referenced by the new manifest. Before
replacing a locale, it also verifies the old pack's bytes and identity without
requiring its IDs to exist in the new catalog. This allows a replacement to
remove obsolete IDs while preserving the old pack for clients with the old
manifest. Restore missing or damaged old packs before retrying; an identical
candidate can restore its own missing payload. It stages the new immutable file
first, then atomically updates `manifest.json`.
Retrying after an interrupted manifest write can reuse the staged file.

When a catalog change requires corrections in multiple locales, pass all
affected candidates in one invocation, before the options:

```powershell
python -m scripts.build_tag_translation_release .gradle/ja-candidate.json .gradle/de-candidate.json `
  --catalog data/tags.json --output .gradle/translation-feed
```

Each locale must appear at most once. The tool validates all candidates and
retained packs, stages the immutable payloads, then replaces the manifest once.
If staging or the manifest update fails, the previous manifest remains in use;
already staged files can be reused on retry. The publisher must still verify
the staged feed and upload all payloads before switching the hosted manifest.

The producer and Android feed tests share a small synthetic release fixture.
The producer regenerates its exact manifest and candidate bytes from a
proposal; Android verifies the payload digest, installs it, and finds the
canonical tag through its localized alias.

Release preparation is a packaging and integrity check. It accepts any
normalized pack, including one made directly by the pack builder. For a
reviewed candidate, pass `--review` as described in the contribution workflow
to check the candidate and current local base against its report. This does not
verify reviewer identity or approval. A publisher must apply the chosen review
and authorization process before hosting the generated files.

Before hosting a release, verify that every referenced payload is available
and matches its descriptor, then replace the hosted manifest. Keep older
payload files available for clients that already read the old manifest. To
correct a translation, publish a corrected pack at a higher revision for
that locale. The release tool does not drop a locale from the feed.

The checked-in translation feed is hosted at:

```text
https://raw.githubusercontent.com/TheSkyC/NReader/main/feed/translations/manifest.json
```

The initial `zh-Hans` pack is maintained by TheSkyC and targets catalog
revision 43. Unmatched catalog tags intentionally fall back to canonical
English names; source attribution and reproduction details are recorded in
[the zh-Hans source note](tag-translation-zh-Hans.md).
The feed directory also contains an [attribution notice](../feed/translations/README.md)
and a copy of the source license. These files are not included in the JSON
payload downloaded by the client.
For the exact published `zh-Hans` revision 1 payload, identified by its SHA-256,
the online installation confirmation displays the source and license links. The
notice is not inferred from a locale or revision alone, since another source can
use the same values. After export, a confirmation explains that the pack does not
embed attribution or license information; the user must obtain the relevant
information from its source.

Catalog and translation manifests are independent. When a new translation
references IDs added to a catalog, publish and verify the catalog first, then
publish the translations. This ordering alone cannot update every client:
offline devices, cached responses, and failed catalog updates may leave an
older catalog installed. Such a device rejects the entire translation pack and
keeps its installed locale unchanged; after its catalog receives the missing
IDs, the user can retry the same pack revision. A build without a configured
catalog feed may need an app update to receive a newer bundled catalog.

To support devices still using a specific older catalog, verify the staged
translation feed against that snapshot as well as the target catalog with
`scripts.verify_tag_feed --catalog <snapshot> --translation-feed <directory>`.
A successful check covers that snapshot's IDs, not all older or customized
devices. Retain old immutable payloads for clients that read earlier manifests.

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
