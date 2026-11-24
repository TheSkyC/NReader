# Tag translation feed

This directory contains an unsigned beta feed. The manifest references immutable
translation payloads; the Android client does not include the attribution files
when downloading a payload. Keep this notice and the applicable license with a
copy of the translation when sharing it separately.

## Simplified Chinese (`zh-Hans`), revision 2

The translated names are derived from the collaborative
[EhTagTranslation database](https://github.com/EhTagTranslation/Database) and
its contributors, at commit `568e68b6e8e5811016fddd8d4d0c7a491ad1e663`.
The source states that its database text is provided under
[CC BY-NC-SA 3.0 China Mainland](LICENSE-zh-Hans.md), except where otherwise
stated. TheSkyC maintains and curates the NReader pack; the source contributors
retain credit for their translations. This attribution does not imply their
endorsement of NReader.

NReader matched source entries to canonical tag names and types, removed
Markdown presentation, retained conflicting names as search aliases, and added
seven category labels. Revision 2 also removes decorative emoji and invisible
formatting from descriptive names, unescapes Markdown, moves `A | B` alternates
into search aliases, and applies a small curated table for a few ambiguous
terms. Artist, group, and character names keep the source spelling. Unmatched
tags use their canonical names. See the
[source and reproduction note](../../docs/tag-translation-zh-Hans.md) for
coverage, changes, and the payload digest.

Revision 1 remains published as an immutable payload for clients that read the
earlier manifest.

The license above applies to the derived translation content. NReader's source
code and other components have their own licenses; see the repository's
[LICENSE](../../LICENSE) and [NOTICE](../../NOTICE).

The v1 manifest provides a digest for detecting byte mismatches, but does not
authenticate a publisher or protect a first-time installation from replay. See
the [distribution contract](../../docs/tag-translation-distribution.md) for
the beta feed's limitations.

## Report a translation problem

Use the [tag translation correction form](https://github.com/TheSkyC/NReader/issues/new?template=tag_translation.yaml)
to report an incorrect or missing translation. Include the original tag name,
type, language, and pack revision. A suggested correction is optional; if it
comes from another source, include its origin and reuse permission. Reports are
public, so do not include account details, personal data, or logs.
