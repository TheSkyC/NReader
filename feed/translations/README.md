# Tag translation feed

This directory contains an unsigned beta feed. The manifest references immutable
translation payloads; the Android client does not include the attribution files
when downloading a payload. Keep this notice and the applicable license with a
copy of the translation when sharing it separately.

## Simplified Chinese (`zh-Hans`), revision 1

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
seven category labels. Unmatched tags use their canonical names. See the
[source and reproduction note](../../docs/tag-translation-zh-Hans.md) for
coverage, changes, and the payload digest.

The license above applies to the derived translation content. NReader's source
code and other components have their own licenses; see the repository's
[LICENSE](../../LICENSE) and [NOTICE](../../NOTICE).

The v1 manifest provides a digest for detecting byte mismatches, but does not
authenticate a publisher or protect a first-time installation from replay. See
the [distribution contract](../../docs/tag-translation-distribution.md) for
the beta feed's limitations.
