# zh-Hans tag translation pack

NReader modifications by TheSkyC (2026); see NOTICE.

## Maintainer and attribution

Pack maintainer and curator: **TheSkyC**.

The translated names are derived from [EhTagTranslation/Database](https://github.com/EhTagTranslation/Database), source commit `568e68b6e8e5811016fddd8d4d0c7a491ad1e663`, database version `7`. The source database is a collaborative work released under [CC BY-NC-SA 3.0 China Mainland](https://github.com/EhTagTranslation/Database/blob/master/LICENSE.md). This attribution and license apply to the translated content derived from that database; NReader does not claim authorship of the source translations.

The feed's [attribution notice](../feed/translations/README.md) includes a local
copy of that license and identifies the source contributors. The standalone
JSON payload does not embed this notice or the license text.

The converter keeps the source database's namespace distinction, strips Markdown presentation from display names, and matches rows by the NReader canonical tag name and tag type. It does not invent translations for unmatched tags. NReader falls back to the canonical English name for those tags.

The source database does not include a category namespace. The maintainer added
the seven catalog labels `doujinshi`, `manga`, `non-h`, `western`, `artistcg`,
and the two `misc` rows as `同人志`, `漫画`, `非H`, `欧美`, `画师CG`, and `杂项`.

## Generated artifact

| Field | Value |
| --- | --- |
| Locale | `zh-Hans` |
| Pack schema | `2` |
| Revision | `1` |
| Catalog revision | `43` |
| Matched entries | `38,035` of `86,030` |
| Fallback entries | `47,995` |
| Maintainer additions | `7` category labels absent from the source namespace files |
| Cross-namespace conflicts | `19` (retained as aliases) |
| Pack size | `1,875,565` bytes |
| SHA-256 | `49df957e214c4431f37b14444a52dc924777423f89bd2403005e2e2f4c8c1d64` |

The pack was generated locally, verified against the catalog feed, and passed
offline Android installation, display-language, alias, removal, and fallback
checks before publication under `feed/translations/`.

## Reproduction

Use CPython 3.14 with Unicode 16.0 data from the repository root:

```powershell
python -m scripts.build_zh_hans_translation_source `
  .dev/refs/eh-tag-translation/database `
  --catalog data/tags.json `
  --output .gradle/zh-Hans-source.json `
  --report .gradle/zh-Hans-report.json

python -m scripts.build_tag_translation_pack `
  .gradle/zh-Hans-source.json `
  --locale zh-Hans `
  --revision 1 `
  --catalog data/tags.json `
  --output .gradle/zh-Hans-pack.json

python -m scripts.build_tag_translation_release `
  .gradle/zh-Hans-pack.json `
  --catalog data/tags.json `
  --output .gradle/zh-Hans-translation-feed
```

The generated report records every unmatched canonical tag and each conflict so that later corrections can be reviewed without changing canonical IDs or types.
