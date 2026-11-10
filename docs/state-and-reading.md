# State and reading contracts

NReader modifications by TheSkyC (2026); see NOTICE.

## Reading positions

- Site gallery IDs identify automatic positions. Unknown local IDs are excluded
  until a stable local identity model exists.
  Local numeric ID markers accept one through nine digits, including legacy
  six-digit markers; folder names do not establish a site identity.
- `Resume` retains its released meaning: an explicit, one-based page bookmark.
- Database version 14 adds `ReadingProgress` without changing existing tables
  or rows. Its page is one-based; scroll offset is a nonnegative fraction of the
  page height, clamped below one. Paged reading stores offset zero.
- Automatic positions are saved after page selection, idle scrolling and before
  leaving the reader, through a serialized background writer. Only single-column
  reading saves scroll positions; thumbnails and gallery details do not count.
- Opening a gallery offers Continue separately from the manual bookmark. Explicit
  page links and thumbnail taps take precedence over an automatic position.
- Viewing details, opening a page or reaching the last page never assigns a
  custom reading status. Completion remains a deliberate user action.
- A missing or out-of-range position falls back to a valid page. Saved activity
  state takes precedence when Android recreates an active reader.

## Database and backups

Upgrading version 13 preserves favorites, history, downloads, bookmarks, tags
and custom statuses. Older supported migrations still run in order. Initialization
must not query tables before their migration has created them.

New backups include automatic positions. Legacy backups without this table remain
importable; replacing the database clears automatic positions absent from the
backup. Database restore is transactional, including failures and invalid rows.
Unknown future tables are skipped. This is backward compatibility for existing
data and backups; an older application cannot be assumed to understand new data.
After successful restore, the in-memory custom status catalog is reloaded from
the committed database. A failed archive leaves both the database and catalog
unchanged. Orphaned status references fall back to the default status.

Before a successful archive import, a cache snapshot captures the database,
Settings and ScrapedTags in a single file published by an atomic rename. Existing
multi-file snapshots remain readable. Restoring it verifies and reads the complete
set before changing state, then replaces those preference sets, including removing
keys added after the snapshot. Downloaded files are not included. Cache eviction or a
30-day expiry can make the snapshot unavailable; it is not a substitute for an
exported backup. Database and preferences are separate storage systems and cannot
be committed atomically together across process or device failure.

## Local and personal state

Favoriting a loaded online list card stores its available summary locally without
a network request. Summary-only records preserve their page count and thumbnail,
but do not contain page URLs. Opening or downloading such a favorite requires a
successful detail fetch; complete existing records are not replaced by summaries.

Existing search bookmarks retain the complete query encoded in their saved URL,
including tag and range terms, rather than adding the current default filters
when reopened. Sort and page are restored separately. Database version 17 adds
an independent search-plan table with stable row IDs, names, complete queries,
sort, page and pinned state. Existing URL-keyed bookmarks remain intact. Plans
are included in backups; restoring a legacy backup clears plans absent from it.
The management interface for named and editable plans remains future work.

Favorite search query, page, sort and search expansion survive activity recreation.
Collapsing search retains its query; clearing text explicitly while expanded resets
it. Favorite and tag queries run in the background. Only the latest requested
query may publish, and its cursor is closed on replacement or view destruction.

Local files, favorites, manual reading status and automatic position are separate
facts. A directory alone never proves a complete download. Completion requires
known expected page count and a readable, nonempty image for every expected page.
Missing pages mean partial; unreadable images mean damaged; missing metadata or
unavailable storage means unverified. This lightweight check does not replace a
full image integrity scan. State is resolved off the UI thread and refreshed on
returning to a screen and after local mutations.

New download metadata preserves the original total in `num_pages`. Legacy
metadata without that field remains readable, but local file count must not be
used as evidence of the original total or of a complete download.

Cards use a consistent rounded cover with favorite and download controls at the
bottom corners and the automatic page position / total at the top right. Unstarted
galleries show only the known total. A bottom gradient keeps controls legible.
Titles reserve two lines below the cover, followed by a single credit line.
Credits prefer nonblank artist names, falling back to group names with the
Bootstrap Icons outline people glyph. Blank and duplicate names are omitted.
Missing credits hide the text and glyph while preserving the row height and
language-flag alignment. Group credits retain a localized group label for
accessibility and tooltips; recycled cards clear all previous credit state.
The selected column count is an upper bound: cards require at least 116dp per
column, including margins, so the two 48dp action targets do not overlap.
Online and downloaded gallery lists offer two or three columns from the toolbar. Four columns
are available when the list's usable width is at least 600dp. Large font sizes
increase the minimum column width; an unusually narrow window can temporarily
fall back to one column to preserve action targets. Compact and wide window
choices are stored separately and restored when the window grows again.
Online and downloaded lists store their choices independently. The old
portrait/landscape preferences seed each list's new choices once, without
changing their keys or meaning. The main-list and download settings now edit
these window choices; other screens retain their existing column settings.
Downloaded lists keep search, sort order and download tasks when changing
columns. Their column action is hidden during multi-selection, and temporary
one-column fallback keeps the same card layout. Changing columns
preserves the visible gallery and its vertical offset without replacing the
adapter. If a deeply clipped row becomes too short to remain visible, that
gallery is anchored at the top instead. Language filtering remains available
in the overflow menu.
Three- and four-column lists reduce visible action glyphs to 16dp and the
download ring to 34dp with 40dp touch targets to reduce accidental taps;
two-column lists keep their existing centered glyphs, 48dp targets and 36dp ring.
API list cards retain `num_pages`, including their in-app Parcelable transfer;
legacy history without a known total continues to show `page/?` when started.
Custom status names appear at the top of the cover with their own label color.
Favorite controls toggle local favorites and offer Undo after removal. Download
controls use both icon shape and color, including verified completion in the local
library, and open task actions or a file-state explanation. Removing local files
is a separate, explicit action. Progress uses `page/total`
with an original metadata total, or `page/?` when that total is unknown. Unknown
storage without evidence of a local copy does
not produce a per-gallery file warning. Truncated labels keep their full
accessibility description. State callbacks must not update a recycled card bound
to another gallery. A complete copy in another indexed directory can establish
completion.

## Download lifecycle

Database version 15 adds DownloadTasks independently of Gallery metadata. Enqueue
is persisted before work is scheduled, including ID-only requests. State, failure,
range, metadata, queue order and bounded attempt counters survive process death.
Waiting/running tasks resume as waiting; paused/failed tasks require explicit user
action. Legacy Downloads rows migrate as paused. Completed/canceled tasks are
removed without altering personal state. Retry-After deadlines survive restart.
New backups include tasks; old backups reconstruct paused tasks from Downloads.
Successful restore invalidates old task writers and replaces the in-memory queue;
failed restore leaves the existing queue and task data unchanged.

Reader image requests are submitted independently to Glide, which owns request
cancellation and activity cleanup. A failed or canceled first image must never
block later pages. Rebinding an image clears its previous request, including when
the image loading policy is disabled.

Transient network/server/rate-limit failures have a finite retry budget and
backoff. Authentication, missing resources and storage failures stop the task
with an actionable reason. A failed task requires an explicit retry. Metadata
failures obey the same bounded policy. Pausing retains the queued task; stopping
temporarily retains completed pages for the Undo window, then deletes the local
folder after the active writer has stopped. Undo restarts the same gallery and
range, allowing the downloader to skip completed pages. Stopping does not remove
favorites, custom statuses or reading positions.

Each metadata request and page has at most four attempts, with exponential
backoff from one second and a 30-second delay ceiling. A valid `Retry-After`
above that ceiling stops automatic retries instead of retrying early. This
policy applies to the existing page downloader, not the API archive endpoint.
Legacy download tasks without saved intent start paused.
Pages are validated in a temporary sibling file before replacing the final
filename. Stopping removes the queued download row; if Undo is not used, its
local gallery folder is deleted after the Snackbar is dismissed.

A second request is accepted only when the queued range covers it. A conflicting
range is rejected with an explanation, preserving the original task and files.
