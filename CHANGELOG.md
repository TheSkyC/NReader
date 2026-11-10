# NReader changes

## Unreleased

- Split gallery details, page previews, and comments into separate tabs, with a
  direct reading action and retained single-column reading. Load comments using
  the paginated API and provide a browser entry while posting remains unavailable.
- Make gallery tabs readable and swipeable, compact tag chips, and size related
  gallery cards to show multiple covers on a phone screen.
- Make gallery pages follow horizontal drags, with an animated transition or
  return when released, while retaining nested scrolling and reading progress.
- Expand long tag categories independently, prefer related-item credit metadata
  from the API response, and fit the default comment avatar within its bounds.
- Resolve related-gallery tag IDs through the batch tag API so author and group
  credits are available even when those tags are absent from the local cache.

- Replace gallery-list and random-gallery language flag emoji with local Tabler
  vector artwork, consistent dimensions and accessible language descriptions.

- Retain favorite searches when closing the search field or recreating the screen.
  Discard stale favorite/tag query results and close their cursors with the view.

- Prevent a failed or canceled first reader image from blocking later pages and
  release image requests through Glide's activity lifecycle.

- Switch online list columns from the toolbar, with separate compact/wide
  choices, width-aware four-column availability and preserved scroll position.
  Move language filtering into the overflow menu.
- Add the same toolbar column control to downloaded galleries, remembering
  independent compact/wide choices and keeping search and selection behavior.
- Use one consistent single-column page placeholder while requests wait or fail,
  with balanced logo padding in light and dark themes.
- Keep cover loading and error placeholders centered at a bounded size while
  loaded cover images continue to fill their cards.
- Refine gallery cards with rounded covers, reading position and total at the top
  right, favorite and download controls inside the cover, and aligned title and
  author rows below. Keep touch targets separated by adapting grid columns.
- Save automatic reading positions separately from manual bookmarks, with
  page and single-column restoration and a Continue action.
- Add database version 14 through an additive migration. Preserve existing
  tables and import legacy backups; roll back failed database restores.
- Bound metadata and page retries, classify failures, honor rate-limit delays,
  and allow explicit retry after failure. Write pages through temporary files.
- Stop downloads with an Undo window that resumes the same gallery and page range
  from completed pages; delete the local folder when the message is dismissed
  without Undo. Preserve favorites, statuses and reading positions.
- Show independent local favorite, custom status, automatic reading position and
  verified local download state across gallery lists, with asynchronous refresh.
- Preserve expected page totals in partial downloads; distinguish missing pages,
  damaged images and unavailable storage. Recognize longer local site ID markers.
- Reject conflicting queued download ranges without replacing the original task.
- Reload the custom status catalog after successful backup restore.
- Add an optional opaque debug image mask for covers and reader pages, enabled by
  default in debug builds. Translate the new controls into the existing locales.
- Replace named APKs on repeat Windows builds while retaining the canonical APK.

## 1.0.0 — 2026-11-09

Modified by TheSkyC; see NOTICE and LICENSE.

- Import NClientV3 with its original Git history.
- Replay the original capture change after NReader initialization
  (source commit `5084b77`, `fix(capture): allow screenshots and screen recording`).
- Rename the Gradle project, app labels, version display, APKs, storage root,
  and HTTP user agent to NReader.
- Start NReader at version `1.0.0`; debug builds use `1.0.0-debug`.
- Use `com.theskyc.nreader` as the application ID; preserve the upstream Java
  namespace to simplify ongoing maintenance.
- Add maintainer and upstream credits, with LICENSE and NOTICE included in APKs.
- Allow debug builds without release-signing files. Release builds without a
  local signing configuration produce unsigned APKs.
- Make the fork's release repository and issue tracker configurable and disabled
  by default. Retain the upstream tag-data feed.
- Adapt documentation and CI for independent fork maintenance.
- Define Git text normalization, LF source files, CRLF Windows scripts,
  and binary-file handling in `.gitattributes`.

The upstream source baseline is NClientV3 `4.2.7`; NReader versions are independent.
