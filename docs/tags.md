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

The separate [tag format versioning contract](tag-format-versioning.md)
defines how database versions, wire schemas, and snapshot revisions evolve.

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
Write schema version, revision, and site IDs as JSON integers without decimal
points or exponents, as in the pack builder and manifest formats.
Each JSON string's wire spelling is limited to 3,073 UTF-16 units, counting
the opening quote and escape characters but not the closing quote. This bound
is separate from the 256-code-point limit on normalized translation text.

The installer reads, validates, and normalizes the bounded payload outside the
database write transaction. It keeps prepared rows in an app-private scratch
file that closes with the import. It rejects duplicate IDs, duplicate
normalized aliases for one tag, invalid locale identifiers, control characters,
oversized text, and unsupported fields. The transaction rechecks the installed
revision and canonical IDs against the current database. Applying a newer pack
replaces only that locale's names, aliases, and revision. An invalid, cancelled,
equal, or older pack leaves the installed locale unchanged. Removing a locale
deletes only that locale's translation rows and revision. Translations are public
replaceable data and are excluded from user backups. This local pack format
does not specify a publishing authority, signature policy, contribution
license, moderation workflow, or default display mode.

Database version 25 records a durable change generation for each locale,
including removed locales. Settings checks the displayed installation's revision
and generation in the removal transaction, including after a same-revision
reinstall. Local imports capture a generation before opening the document;
online installs capture one before downloading. If that locale changes before
an otherwise eligible import commits, the import leaves the current state intact
and asks the user to retry. Changes to other locales do not prevent installation.
These checks do not hold a database write transaction while reading, validating,
or downloading.

Database version 26 records the original order of aliases for newly installed
packs. Aliases installed before this upgrade have unknown order, marked by -1;
the upgrade preserves their searchable text but cannot reconstruct their input
order. Removing and importing a pack again records its order. This storage
change does not add an export feature or alter the pack format.
The export snapshot layer serializes one selected installation to an app-private
temporary file under a database transaction, checks its revision and generation,
and rejects unknown alias order. It limits the UTF-8 JSON to the 16 MiB import
limit; the caller closes the temporary file. It does not write an external
document while holding the transaction. The Settings export flow is not yet
available.

Search keys use NFC normalization and locale-independent lowercase. Greek final
sigma is normalized to ordinary sigma so a prefix produces the same key whether
it ends a query or continues inside a tag name. Pack authoring and Android
installation use the same rule for alias duplicate detection and indexed keys.
Database version 24 updates installed search keys without changing display names
or pack revisions. If a tag has legacy aliases that become equivalent, the
upgrade keeps the first stored alias for that key.

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

For contributions, use the [local proposal and review workflow](tag-translation-contributions.md)
to compare a change with its canonical tag and the current translation before
producing a candidate pack.
The [translation distribution contract](tag-translation-distribution.md)
defines how to stage these packs and a separate manifest for a release.

On Android, Settings → Tag languages opens the system document picker for a
local pack. The picker accepts JSON, text, and generic binary MIME labels
because some document providers do not classify `.json` consistently;
the importer still validates the complete pack before changing the database.
Selecting a newer revision validates and installs it in the
background. The installed-locale list is independently removable; cancelling
the picker or an invalid pack leaves current translations intact. A build
with a [configured translation feed](tag-translation-distribution.md#enabling-the-settings-action)
also allows users to browse and install newer packs online on demand.
When a pack references tags missing from the catalog, Settings offers a catalog
check only if a catalog feed is configured. Otherwise it recommends updating
the app or selecting a compatible pack without queuing a network request.
The local import continues through Settings configuration changes and reports
its result once to the recreated screen. A second local import cannot start
while the first is running. An unknown-tag dialog remains available through
configuration changes until dismissed. Import feedback is not persisted across
process death.

Tag suggestions use the app's active locale. Variants and Unicode extensions
are ignored when matching a pack. An exact installed locale wins;
otherwise an explicitly specified script pack takes precedence over a generic
language pack. Chinese locales without an explicit script infer `zh-Hans` or
`zh-Hant` from the region before falling back to the generic language pack.
Suggestions search canonical names,
translated names, and aliases with a prefix query that returns at most 50
results. The translation and alias indexes restrict lookups to the matching
key range within the installed locale. A translated
suggestion displays its canonical name alongside it; selecting any suggestion
places the canonical site name in the query editor and keeps its site ID.
Confirming a selection refreshes the tag by ID. Editing the text clears the
selection. Typed names are looked up within their type in the background,
preferring the exact spelling, then an ASCII case-insensitive match, then
the exact lowercased spelling. An unmatched valid name is still usable as a
custom search term. Search does not submit while an entry is being resolved.
Uninstalled locales use canonical names only.

Tags of the same type can share a canonical name and still have different
site IDs. Search keeps their selections separate. An account blacklist entry
only changes an existing selection with the same ID and type. The request
sends identical signed tag terms once; terms with opposite signs are kept.
Because the site query uses names, opposing signs for one name can leave no
site results even when the local IDs differ.

With Match all search tags enabled, results must contain the site IDs of
included structured tags and must omit the site IDs of excluded structured
tags. This check uses the IDs returned with the gallery, so a missing directory
entry does not affect it. Custom terms without a site ID and free-text terms
are still sent to the site for matching.
If a page has no results and the site reports multiple pages, the numbered
controls remain available even in infinite scroll mode. The app waits for the
user to choose a page instead of automatically requesting more empty pages.

Custom terms have no site ID. A bookmark for a custom term saves the query,
page, and sort. A single positive site-tag bookmark uses its site ID to
follow canonical renames. If the ID is missing, it replays the saved query;
if neither is available, it cannot be opened.

The tag manager keeps substring search for canonical names and also matches
translated names and aliases in the active locale. It checks aliases by tag ID
within the eligible type or rule list. It shows both names when
a translation is available. The order, count threshold, applied-rule
visibility, and account blocklist eligibility still use the canonical data.
A shared alias can return each matching tag, and `%` and `_` in a search are
matched literally rather than treated as SQL wildcards.
A rule with an incompatible catalog type keeps its own saved label and
cannot match a translation for the catalog tag.

Gallery details show canonical labels immediately, then look up installed
translations for that gallery's positive site IDs in the background. A
translated tag button shows both names and can wrap onto multiple lines.
Tapping it still searches by the original site tag, and long-press still
copies the canonical name.
When showing both names together, the app ignores explicit text direction
controls in the labels so neither can reorder the other.

Local rule evaluation retains its existing context-specific behavior. The
editor cycles Default → Accepted → Avoided → Default; Default removes the
rule. Edits and clear operations run in the background in the order submitted.
Each edit reads the saved status inside its write transaction, so a delayed
edit does not cycle from a stale row or icon. Open tag lists refresh after
commit, and the home feed rebuilds its request with the committed rules,
even if the editor has already closed. The gallery tag menu shows the saved
rule for the tag that opened the page, even when the gallery query includes
other active filters. Tag links resolve the canonical name within the link's
tag type. A queued edit cannot create a rule of an obsolete type; an existing
incompatible rule can still be edited or removed from Applied filters.
A backup restore skips edits that have not passed the restore check inside
their write transaction and rejects edits submitted while it is active. An
edit already admitted to SQLite may commit before the restore acquires its
own write lock; a completed restore still takes precedence. Views refresh
after restore and ignore callbacks from the previous state.
Account blacklist entries do not modify the local status. Applying an
account blacklist still requires the existing opt-in and identity checks.
When submitting a search after opting out, automatic blacklist entries are
removed from the selection, while manual tag choices are preserved.
Only the active account's validated cache is exposed; an in-flight result
from a previous account or before restore cannot publish.
Home, searches, and tag browsing bind automatic exclusions to the validated
cache revision that supplied them, including an empty blacklist. The search
form passes automatic selections in a process-local handoff. Its regular
launch arguments contain only manual and local selections. If the launch
arguments still indicate a handoff but it is missing or its revision changed,
the result screen requires confirmation to search without automatic
exclusions. A form submission made with account tags off stays that way.
Editing or removing an automatic selection makes it
a manual choice for the life of that form, even if the blacklist refreshes.
A bare positive gallery ID opens that gallery directly as before; this is
a gallery lookup and does not use the tag filters.
Turning off account tags invalidates requests bound to that revision as soon
as the preference is saved. When the revision is no longer current, the home
feed rebuilds; other affected result lists discard the old results and offer
to repeat the search without online tags. If the user accepts, the result
screen can restore that search after recreation using only its portable
conditions, without saving account terms or loaded results. A search still
waiting for confirmation carries that requirement across recreation and does
not automatically apply a newer blacklist. Newly created bookmarks and
search plans from these queries omit automatic exclusions and retain explicit
search terms and local rules. An empty saved search still replays as a search;
it does not reload the home feed's filters.

The tag list reads its cursor and copies rows in bounded batches on a worker.
Cached rows remain usable while a directory update is holding the database;
an uncached position waits asynchronously and cannot be edited before its
row loads. Changing the search or leaving the list discards pending results.
Account blacklist rows are cleared when their account or revision changes.

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
HTTPS URL of a NReader catalog manifest. Because manifest v1 is unsigned, the
same build must explicitly set `nreaderEnableUnsignedTagFeeds=true`; leaving
the URL empty keeps the default path. A configured build checks that feed
instead of the legacy one. See [catalog distribution](tag-distribution.md) for
its independent revisions, integrity checks, and publishing procedure.

Directory payloads are limited to 16 MiB and 250,000 rows. They must contain at least
50,000 rows covering all seven known types. A canonical name must fit in the
site's quoted query syntax. Invalid fields, duplicate IDs, type conflicts,
cancellation and database errors reject the import. Rows and the
applied revision are committed together. Equal or older revisions do not
replace the directory. The importer reads and validates the input before
taking the app database writer lock. It rechecks the source revision and
checks for conflicts with installed metadata inside the transaction, then
merges the validated rows in batches. Unchanged authoritative rows are left
alone. A newer revision is still recorded when its rows are identical.

Remote checks share one WorkManager job. A successful check is cached
for seven days. Failures retry a bounded number of times. Existing directory
rows and rules are usable without network access.

## Format evolution

The SQLite database version is a monotonically increasing schema version.
Database upgrades must preserve the meanings of site IDs, local rule states,
account ownership, catalog source revisions, and installed locale revisions.
When a schema change alters storage, add an ordered upgrade and a migration
regression that checks both retained data and the new constraints. A destructive
database downgrade is not a supported migration path.

Serialized formats carry their own versions: translation packs use
`schemaVersion`, and portable backups use their manifest version. A change that
cannot be read under the current contract requires a new format version and an
explicit reader path; do not silently reinterpret an existing field. Keep the
current full-snapshot pack behavior and backup ownership boundaries unless a
new format deliberately specifies otherwise. Development data does not require
a long-term compatibility path, but every in-repository format change still
needs a deterministic fixture and a documented version.

A failed database upgrade leaves the previous schema version and data intact.
The migration regression reopens a version 23 database after an injected SQLite
failure, then retries the upgrade after the failure is removed.

## Device-scale benchmark

`TagCatalogDeviceBenchmarkTest` uses a separate file-backed SQLite database
in the isolated benchmark app's private cache for each case. Choose one method
with the `Class#method` syntax: `importsBundledCatalog`,
`importsFullCatalogTranslationPack`, or `queriesInstalledAliases`.
Each case logs setup and measured phases separately. Translation data is
generated from the bundled catalog and checked against the normal pack size
limit. No phase uses the installed app database or a live translation source.
Run it on an attached API 26+ emulator or device with the isolated
`tagBenchmark` variant, whose APKs are kept out of the release archive:

```powershell
.\gradlew.bat :app:assembleMinApi26TagBenchmark :app:assembleMinApi26TagBenchmarkAndroidTest
# Use the numeric ID reported by pm create-user below.
adb -s $serial shell pm create-user NReaderTagBenchmark
adb -s $serial shell am start-user $benchmarkUser
adb -s $serial install --no-incremental --user $benchmarkUser app/build/outputs/apk/minApi26/tagBenchmark/app-minApi26-tagBenchmark.apk
adb -s $serial install --no-incremental --user $benchmarkUser -t app/build/outputs/apk/androidTest/minApi26/tagBenchmark/app-minApi26-tagBenchmark-androidTest.apk
adb -s $serial shell am instrument --user $benchmarkUser -w -r -e class 'com.theskyc.nreader.async.database.TagCatalogDeviceBenchmarkTest#importsBundledCatalog' com.theskyc.nreader.tagbenchmark.test/com.theskyc.nreader.TagBenchmarkRunner
adb -s $serial logcat -d -s TagCatalogBenchmark:I
adb -s $serial shell pm uninstall --user $benchmarkUser com.theskyc.nreader.tagbenchmark.test
adb -s $serial shell pm uninstall --user $benchmarkUser com.theskyc.nreader.tagbenchmark
adb -s $serial shell pm remove-user $benchmarkUser
```

Set `$serial` from `adb devices`, and wait for the temporary user to start
before installing the packages. Keep the test app off the owner profile.
Use the equivalent `minApi28` paths for API 28+ builds. Benchmark output is
logged under `TagCatalogBenchmark`; results are device- and workload-specific.
The test reports import duration, first and warm alias-query timings, and
SQLite journal mode, synchronous setting, and page count. The first query
runs after import in the same process and is not a cold-start measurement.
The test does not measure peak memory, battery use, UI input-to-render latency,
process-death recovery, or reviewed production translations. Do not compare
numbers across devices as a release threshold without controlling hardware,
thermal state, and data revision.

## Offline first-launch check

The ordinary debug application also needs a runtime check because the
benchmark application deliberately skips production startup. Use a disposable
API 26+ emulator or a temporary Android user, install the matching debug APK,
disable Wi-Fi and mobile data, and launch the application. After the first
startup, force-stop and relaunch it before checking the tag manager. The
database must contain the bundled catalog revision and the tag page must show
catalog names while the device has no default network.

For a disposable API 28 AVD, the check can be recorded with:

```powershell
adb -s $serial shell svc wifi disable
adb -s $serial shell svc data disable
adb -s $serial install --no-incremental -R --user 0 app/build/outputs/apk/minApi28/debug/app-minApi28-debug.apk
adb -s $serial shell am start -W -n com.theskyc.nreader.debug/com.maxwai.nclientv3.components.launcher.LauncherReal
adb -s $serial shell am force-stop com.theskyc.nreader.debug
adb -s $serial shell am start -W -n com.theskyc.nreader.debug/com.theskyc.nreader.TagFilterActivity -d https://nhentai.net/tags/
```

The check is complete only when the installed database reports schema 25,
bundled revision 43, and 86,030 catalog rows, and the live tag page displays
an expected catalog name such as `glasses`. Record the Android API, flavor,
network state, and whether the database was empty before launch. This is a
runtime smoke check rather than a release performance threshold.

To check database contention, run
`com.theskyc.nreader.async.database.TagImportContentionTest` with the same
runner and a fresh benchmark app installation in a temporary Android user.
The test opens a file-backed database through the application's database
helper. Separate cases pause the catalog or translation input and verify that
a tag read and local rule write finish before the installer resumes. A third
case holds a small catalog transaction immediately before commit.
It reports under `TagImportContention` whether an independent tag read and
local rule edit can finish while the transaction is held. It then verifies
that the new canonical row and the saved rule both survive. This isolates
database waiting from file size and does not measure UI latency.

`com.theskyc.nreader.async.database.TagListReadContentionTest` uses the
same runner and isolation with a 36,000-row synthetic tag category. It
compares the default database configuration with test-only WAL, using the
same search query as the tag list. It measures both a new list read and a
move beyond an already loaded cursor window while the catalog transaction
is paused. It records the journal mode, synchronous setting, first window
size, and whether each read completed during the observation window. After
commit it verifies the catalog name and the last list row. It does not
measure interactive scrolling, power-loss durability, or the performance of
real translated names, and does not change the production journal mode.

For process-death checks, build the matching target and instrumentation APKs.
Run `scripts/test_tag_process_recovery.ps1` with `-Flavor MinApi26` or
`-Flavor MinApi28` and one of these scenarios. When several devices are
attached, pass `-Serial` with the exact ADB device serial:

- `-Scenario CatalogTransaction` stops the process after catalog rows and
  revision are written but before SQLite commits. Restart checks the previous
  revision, rows, and local rule, then retries the import.
- `-Scenario TranslationStaging` stops the process while the downloader is
  waiting for the rest of a synthetic HTTP response. Restart checks the
  previous translation and rule, then retries the digest-verified download
  and checks that the partial file is removed.
- `-Scenario TranslationTransaction` stops the process after the translated
  name, alias, revision, and change generation are written but before SQLite
  commits. Restart checks the previous translation, generation, and rule and
  the absence of a stranded prepared pack, then retries the import.

Each run installs the benchmark packages for its own temporary Android user.
It uninstalls them for that user before removing the user. The driver checks
that the process that reached the stop point is alive before force-stop and
gone afterward.
Connect exactly one device that supports secondary users and does not
already have either benchmark package installed. Run one scenario at a
time. The driver does not replace existing packages. The instrumentation
runner skips normal application startup and its scheduled background work.

The staging scenario uses a synthetic response. These checks do not
simulate a network interruption over TLS or a device power loss.

Run `com.theskyc.nreader.async.database.TagImportStorageLimitTest` with the
same runner and a fresh benchmark installation to exercise SQLite's
out-of-space rollback. It lowers the maximum page count on a private test
database, verifies that directory and locale installs fail without replacing
previous data or local rules, then raises the limit and retries. The fixture
does not fill device storage or test failure to write the scratch file.

The host test `TagTranslationCatalogTest` separately injects an I/O failure
after a partial write to the private prepared-pack file. It verifies closure
of the input and scratch channel, preservation of both installed locales and
the local rule, and successful retry at the same candidate revision. This
does not simulate a full Android storage partition.
