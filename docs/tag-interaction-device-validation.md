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

The three cases use the installed bundled catalog and a private file-backed SQLite
database with no account or network fixtures. The search test also installs a
synthetic translation and two aliases per tag for the activity's locale. Use a
locale supported by the pack format, such as `en-US` or `zh-CN`.

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

Record the APK flavor, Android API, host or hardware, catalog size, and repeated
samples. The tests assert UI behavior and log durations; they do not impose
performance thresholds or measure interaction during a concurrent import.
