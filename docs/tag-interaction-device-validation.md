# Tag interaction benchmarks on Android

Run `com.theskyc.nreader.async.database.TagInteractionDeviceBenchmarkTest` using
the isolated app and instrumentation APKs from the
[tag benchmarks](tags.md#device-scale-benchmark). On a shared device, set up a
temporary foreground Android user and restore the original user when finished
as in the [account search check](tag-search-device-validation.md). A disposable
AVD with no existing app data can run the tests in its initial user. Confirm
the selected serial and that the device is unlocked before injecting input.

```powershell
adb -s $serial shell am instrument --user $benchmarkUser -w -r -e class com.theskyc.nreader.async.database.TagInteractionDeviceBenchmarkTest com.theskyc.nreader.tagbenchmark.test/com.theskyc.nreader.TagBenchmarkRunner
adb -s $serial logcat -d -s TagInteractionBenchmark:I TagCatalogBenchmark:I '*:S'
```

The four cases use the installed bundled catalog and a private file-backed SQLite
database with no account or network fixtures. The search and translation UI tests
also install synthetic translations for the activity's locale. Use a locale
supported by the pack format, such as `en-US` or `zh-CN`.

- **Search suggestions:** Open the search options and the add-tag dialog, inject
  key events for an alias, and wait for its translated label in the suggestion
  window. The two measurements start before key injection and after all keys
  are sent, respectively, and end at the same accessibility observation. A
  partial prefix may already match the alias. The measurements include any
  observation delay and do not include typing through a real keyboard or IME.
- **List rows:** Jump to three distant, previously unvisited list positions,
  then swipe across the next page boundary, initially below the viewport. Each
  sample ends at the first pre-draw callback with a visible populated title
  at or beyond its target.
  The additional interval starts at the first pre-draw with a row there, so it
  reports whether filling the title took another frame. The swipe may show
  prefetched rows; it does not measure a guaranteed page miss. Pre-draw
  timestamps do not measure frame presentation or dropped frames.
- **Translation invalidation:** Load the real tag page, install a higher
  revision containing one visible tag translation, and wait for that same row
  to show the translated label. This checks the committed catalog event,
  fragment observer, and adapter refilter path. It does not test a feed
  download or a translation pack with every catalog row.
- **Translation removal:** Install one visible translation, remove its locale,
  and wait for the same row to return to its canonical label. This checks the
  removal event and the same live adapter refresh path. It does not test
  removal through the Settings document picker.

Record the APK flavor, Android API, host or hardware, catalog size, and repeated
samples. The tests assert UI behavior and log durations; they do not impose
performance thresholds or measure interaction during a concurrent import.
Keep the device awake and unlocked for the whole run. On Android 9 emulators,
`adb shell cmd window dismiss-keyguard` may be needed after switching to a
temporary user; a locked or dreaming display can pause the launched activity and
make the harness time out before any UI assertion runs.

## Local translation pack picker

On a disposable device, use **Settings → Tag languages → Install or update**
to select a valid pack through the system document picker. Reopen **Tag
languages** and confirm that its locale and revision are listed. This checks
the picker, provider permission, and installation path; a successful dialog
alone does not verify translated search or replacement and recovery behavior.

When testing with multiple Android users, ensure the document is visible to the
foreground user's document provider. A file pushed to the shell user's
`/sdcard` can belong to a different user from the one running the app. Check
the foreground user with `adb -s $serial shell am get-current-user` and confirm
the document is visible in the picker before interpreting an empty list as an
import failure. Remove the test document and restore the original user after
the check.

To check recovery guidance, select a valid higher-revision pack containing a
positive ID absent from the installed catalog. The app should show the missing
tags dialog; after dismissing it, the previously installed locale and revision
should still be listed. This check does not establish that the catalog can be
updated on the device or that the pack will become compatible.

To check export picker recovery, install a synthetic pack with the current app,
then select its locale and **Export**. With the system save picker in front,
record the app process ID and terminate only that process, without force-stopping
the package. Confirm the picker remains in the original task and the old process
is gone. Save to a new document, then verify that a new app process receives the
result and that the saved JSON has the expected locale, revision, IDs, names, and
alias order. Reopen **Tag languages** and confirm the installed locale remains.
This exercises picker-result recovery and that provider's temporary write grant;
it does not establish recovery from process death during the subsequent write.
On 2026-11-23 this passed on an API 28 emulator using the MinApi28 isolated
benchmark app and a temporary Android user. A pack installed by an older build
first correctly failed export because its alias order was unknown; removing and
reinstalling the fixture with the current build allowed the successful check.
