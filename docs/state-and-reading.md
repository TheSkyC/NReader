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

## Local and personal state

Local files, favorites, manual reading status and automatic position are separate
facts. A directory alone never proves a complete download. Completion requires
known expected page count and a readable, nonempty image for every expected page.
Missing pages mean partial; unreadable images mean damaged; missing metadata or
unavailable storage means unverified. This lightweight check does not replace a
full image integrity scan. State is resolved off the UI thread and refreshed on
returning to a screen and after local mutations.

Cards display local favorites, custom status names, automatic page positions and
local file state independently. Truncated labels keep their full accessibility
description. State callbacks must not update a recycled card bound to another
gallery. A complete copy in another indexed directory can establish completion.

## Download lifecycle

Transient network/server/rate-limit failures have a finite retry budget and
backoff. Authentication, missing resources and storage failures stop the task
with an actionable reason. A failed task requires an explicit retry. Metadata
failures obey the same bounded policy. Pausing retains the queued task; stopping
can retain files or delete them after the active writer has stopped. Neither
choice removes favorites, custom statuses or reading positions.

Each metadata request and page has at most four attempts, with exponential
backoff from one second and a 30-second delay ceiling. A valid `Retry-After`
above that ceiling stops automatic retries instead of retrying early. This
policy applies to the existing page downloader, not the API archive endpoint.
Failure details are in memory; restored legacy download tasks start paused.
Pages are validated in a temporary sibling file before replacing the final
filename. Stopping removes the queued download row only; retained files remain
available in the local library.

A second request is accepted only when the queued range covers it. A conflicting
range is rejected with an explanation, preserving the original task and files.
