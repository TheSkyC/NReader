# Tag directory and rules

NReader modifications by TheSkyC (2026); see NOTICE.

The tag directory uses the site's positive tag IDs. Canonical names and counts
can change without changing the meaning of a rule. The directory feed must
agree with the known type of an existing ID. An omitted ID stays available, so
gallery links and rules continue to resolve. Search-only tags with no site ID
do not acquire a directory identity.

Every APK and app bundle includes the catalog and revision from its source
checkout. A background job can install that catalog without Internet access.
A bundled revision can replace an older bundled revision; it cannot replace an
installed remote catalog. Upstream and the repository's bundled dataset have
independent version histories. An open tag-management screen refreshes when
the installation commits.

## Data ownership

Public metadata, local rules, and the active account's blacklist use separate
tables. The public directory updates names and counts. A rule uses the site's
positive ID and retains the type and last known canonical name for display and
queries when metadata is missing. A matching directory entry takes precedence
for display and query text. If a restored rule disagrees with the known type
for its ID, it stays visible in Applied filters for correction or removal, but
is not used in gallery filters or site queries. The browse count threshold
does not hide saved rules. Legacy names that cannot be quoted safely are
preserved for display and editing but are excluded from automatic queries
until matching canonical metadata resolves them.

## Translation packs

Translations are stored independently from canonical directory names, local
rules, account data, and backups. A locale pack is a complete snapshot for one
canonical BCP 47 locale using a two- or three-letter language and optional
script and region, such as `ja`, `pt-BR`, or `zh-Hant`. Use `id`, `he`, and `yi`
in place of the legacy codes `in`, `iw`, and `ji`. The JSON envelope contains
`schemaVersion: 1`, the locale, a positive monotonically increasing revision,
and an `entries` array.
Each entry contains a positive site tag ID, one plain-text display name, and an
optional list of plain-text search aliases. Site IDs remain the only identity;
translation text never enters a site query or changes a saved rule.

An installer validates the whole bounded payload before committing it. It
rejects duplicate IDs, duplicate normalized aliases for one tag, unknown tag
IDs, invalid locale identifiers, control characters, oversized text, and
unsupported fields. Applying a newer pack replaces only that locale's names,
aliases, and revision in one SQLite transaction. An invalid, cancelled, equal,
or older pack leaves the installed locale unchanged. Removing a locale deletes
only that locale's translation rows and revision. Translations are public
replaceable data and are excluded from user backups. This local pack format
does not specify a publishing authority, signature policy, contribution
license, moderation workflow, or default display mode.

Search keys use NFC normalization and locale-independent lowercase. Greek final
sigma is normalized to ordinary sigma so a prefix produces the same key whether
it ends a query or continues inside a tag name. Pack authoring and Android
installation use the same rule for alias duplicate detection and indexed keys.
Database version 24 rebuilds installed translation keys without changing pack
text or revisions.

Prepare a pack from a local JSON array of entries, using the checked-in catalog
snapshot to reject unknown IDs:

```powershell
python scripts/build_tag_translation_pack.py .gradle/zh-Hans-source.json `
  --locale zh-Hans --revision 1 --output .gradle/tag-packs/zh-Hans.json
```

Each source entry has `id`, `name`, and an optional `aliases` string array.
The builder normalizes text, sorts entries by ID, checks limits and duplicate
identities, and writes the deterministic v1 envelope. Empty packs are rejected;
removal uses the locale-scoped database operation.

On Android, Settings → Tag languages opens the system document picker for a
local pack. Selecting a newer revision validates and installs it in the
background. The installed-locale list is independently removable; cancelling
the picker or an invalid pack leaves current translations intact. No remote
feed or publisher is configured by this workflow.

Tag suggestions use the app's active locale. An exact installed locale wins;
Chinese locales resolve to `zh-Hans` or `zh-Hant` from script and region, then
to a generic installed language pack. Suggestions search canonical names,
translated names, and aliases with a prefix query that returns at most 50
results. The translation and alias indexes restrict lookups to the matching
key range within the installed locale. A translated
suggestion displays its canonical name alongside it; selecting any suggestion
places the canonical site name in the query editor. Uninstalled locales use
canonical names only.

Local rule evaluation retains its existing context-specific behavior. The
editor cycles Default → Accepted → Avoided → Default; Default removes the
rule. Account blacklist entries do not modify the local status. Applying an
account blacklist still requires the existing opt-in and identity checks.
Only the active account's validated cache is exposed; an in-flight result
from a previous account or before restore cannot publish.

## Backups

Version 4 exports `TagRules` rows with `idTag`, `name`, `type`, and
`status` (1 for Avoided, 2 for Accepted). `TagReferences` contains
`idTag`, `name`, `type`, and `count` only for tags referenced by saved
galleries and bookmarks. The backup excludes the full directory, its applied
revisions, and account blacklist data. Restore replaces the saved rules,
merges missing reference metadata, and keeps the installed directory and its
revisions. An authoritative catalog can subsequently correct a provisional
reference. Undo restores the previous rules and library references; any
additional public metadata acquired during restore stays cached. The account
cache must be revalidated after a restore operation. Neither restore nor undo
imports account state.

Version 3 and manifestless legacy backups use the old combined `Tags` rows.
Restore reads their valid local rules and needed reference metadata, ignoring
the legacy online flags and unrelated directory rows. Invalid tag data or
duplicate identities abort the database restore. An invalid backup cannot
partially replace the rules. The database upgrade preserves public metadata,
local rules, and library references, and discards legacy account flags.

## Directory refresh

The upstream feed currently exposes an integer revision and a JSON array of
`[id, canonicalName, count, typeId]` rows. The app downloads the payload to a
private temporary file before starting the database transaction. It checks the
revision again after downloading because the upstream paths are mutable. The
legacy protocol has no digest that binds the revision to the payload.

A build can set the Gradle property `nreaderTagCatalogManifestUrl` to the
HTTPS URL of a NReader catalog manifest. A configured build checks that
feed instead of the legacy one. See [catalog distribution](tag-distribution.md)
for its independent revisions, integrity checks, and publishing procedure.

The legacy feed is limited to 16 MiB and 250,000 rows. It must contain at least
50,000 rows covering all seven known types. A canonical name must fit in the
site's quoted query syntax. Invalid fields, duplicate IDs, type conflicts,
cancellation and database errors reject the import. Rows and the
applied revision are committed together. Equal or older revisions do not
replace the directory.

Remote checks share one WorkManager job. A successful check is cached
for seven days. Failures retry a bounded number of times. Existing directory
rows and rules are usable without network access.
