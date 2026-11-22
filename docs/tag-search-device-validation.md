# Account tag search on Android

The search form passes validated automatic exclusions to the results screen
through Android's activity manager. A local Binder is usable in the original
process; after its blacklist revision changes, the results screen must pause
until the user agrees to continue without account exclusions. Recreating
that screen must preserve the pending decision and the user's own terms.

`com.theskyc.nreader.TagSearchActivityDeliveryTest` exercises this path with
the isolated `tagBenchmark` build. Use a fresh temporary foreground Android user,
then switch back to the original user and remove the temporary user. Use the
same APKs and runner as the [tag benchmarks](tags.md#device-scale-benchmark).
The test drives the actual search form and results activity and supplies a
synthetic account blacklist and search response, with image downloads disabled.
Verify both compatibility flavors and record the Android API level.

```powershell
# After installing the app and instrumentation APKs for one flavor:
adb -s $serial shell am switch-user $benchmarkUser
adb -s $serial shell wm dismiss-keyguard
# Finish setup, keep the display awake, and wait for the temporary user's launcher.
adb -s $serial shell am instrument --user $benchmarkUser -w -r -e class com.theskyc.nreader.TagSearchActivityDeliveryTest com.theskyc.nreader.tagbenchmark.test/com.theskyc.nreader.TagBenchmarkRunner
adb -s $serial shell am switch-user $originalUser
# Confirm the original user is in the foreground before removing the temporary user.
```

Activity recreation keeps the app process and original intent alive, so this
test does not establish that Android can restore the search without its
original Binder. Verify process death separately: after accepting the fallback,
send the task to the background, kill only the background process with
`adb -s <serial> shell am kill --user <user-id> <package>`, and reopen the same card from
Recents. Verify that the task ID stays the same and the process ID changes.
On a browsed tag page with local rules enabled, check that the tag title and
local rules survive, results reload, and the new request uses neither the
original nor the replacement account exclusions.

For suggestion and list responsiveness, run the separate
[tag interaction benchmarks](tag-interaction-device-validation.md).

The tag entry dialog belongs to its search activity. Destroying the activity
closes its suggestion popup and dialog and discards a pending exact lookup.
Results from the old activity must not add chips or start a search.
