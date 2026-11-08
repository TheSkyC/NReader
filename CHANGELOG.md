# NReader changes

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
