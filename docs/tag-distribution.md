# Tag catalog distribution

NReader modifications by TheSkyC (2026); see NOTICE.

The catalog tool builds an independent feed from a validated full snapshot.
The files in `data/` still supply the bundled catalog. A data pull request does
not publish a runtime feed. The app continues to use the legacy upstream
feed until a NReader feed is configured.

## Catalog manifest v1

The manifest is a UTF-8 JSON object of at most 4 KiB with these required
fields. Duplicate, missing, and unknown fields are invalid.

| Field | Value |
| --- | --- |
| `schemaVersion` | Integer `1` |
| `source` | String `nreader-catalog` |
| `kind` | String `catalog` |
| `revision` | Integer from 1 through 2,147,483,647 |
| `sizeBytes` | Integer from 1 through 16 MiB |
| `sha256` | 64 lowercase hex digits |

The exact payload is the sibling file `catalog-<sha256>.json`; the manifest
contains no URL. HTTP transport uses an uncompressed response from the
configured HTTPS origin, without redirects or credentials. The digest and
size describe those exact bytes. The payload is the catalog array defined in
[the tag data contract](tags.md). A full catalog contains 50,000 to 250,000
unique positive IDs and covers all seven known types. Matching names are
limited to 256 UTF-16 code units and must fit the site's quote syntax.

Revisions are comparable only within this source. Once installed, a
configured NReader catalog takes precedence over the bundled and legacy
sources. A stale or failed refresh keeps the installed catalog and rules.
To correct a published catalog, publish a corrected snapshot at a higher
revision; do not reset the counter. An omitted ID continues to resolve
under the existing catalog merge policy.

The HTTPS origin is the trust boundary. The digest detects byte mismatch
and does not authenticate a compromised publisher or provide first-install
replay protection. A production origin, release owner, and any independent
signing or expiry policy need to be established before configuring a feed.
The app build accepts the manifest URL through Gradle property
`nreaderTagCatalogManifestUrl`. The default is empty, which keeps the
existing legacy feed active.

## Preparing a release

Run `python -m scripts.build_tag_catalog --catalog data/tags.json --revision N
--output PATH` with an independent feed revision and a dedicated output
directory. Run one publisher per directory. The tool verifies the payload,
writes its immutable filename first, and replaces `manifest.json` last.
If interrupted before the pointer changes, the prior pointer and payload
remain usable; rerunning can reuse the new payload. Stage and verify the
immutable payload on the host before replacing the hosted pointer. Keep
payloads available for clients that already read an older manifest. Do not
publish a feed as part of the bundled data PR.

Retain the current manifest and its payload in the publisher's input
directory between releases. The tool checks the prior payload against that
manifest and rejects releases that omit an existing ID or change its type.

## Collecting a bundled snapshot

The collector follows bounded pagination across all seven API categories,
validates each row, and rejects duplicate IDs, type conflicts, short pages,
or changing page totals. A crawl needs at least 50,000 rows and at least 90%
of the prior snapshot's IDs in each category before it can propose changes.
It retains omitted IDs, accepts canonical renames, and updates counts when
they change by more than 10%. Count changes alone can advance the revision.
The collector uses connection and read-idle timeouts and checks elapsed
time between body reads. The CI job also has an overall timeout.
The pull request is the publication unit for the bundled files, so review
the row changes and revision together. The collector journals these related
files and restores the previous set before the next run if a process or write
fails during commit.
