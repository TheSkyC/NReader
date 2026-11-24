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

## Curation

Revision 2 applies deterministic cleanup while converting the source database
instead of carrying source presentation into the app:

- Display names of tags, parodies, categories, and languages drop decorative
  emoji and invisible formatting characters, so `熊🐻` becomes `熊` and
  `丧尸🧟‍♀️` becomes `丧尸`. Artist, group, and character names keep the source
  spelling because decorative marks can be part of a proper name.
- Escaped Markdown in the source is unescaped, so `YUI\_7` becomes `YUI_7` and
  `cherry\*pepper` becomes `cherry*pepper`. Invisible joiners and byte order
  marks are removed from every type.
- The source's `A | B` alternatives become a single display name plus search
  aliases, so `丽王 | 利昂 | 雷欧` displays as `丽王` and remains searchable by its
  alternatives. Pipes inside brackets, such as a list in parentheses, are not
  split.
- A small curated table resolves meanings the source encodes with gender glyphs
  or that contradict its description: `bull`/`cow` become `公牛`/`母牛`,
  `lion`/`lioness` become `雄狮`/`雌狮`, `cuntboy`/`shemale` become
  `人妖（有阴道）`/`人妖（有阴茎）`, and `compilation`, `domination loss`, and
  `body writing` become `合辑`, `主导权丧失`, and `人体书写`.

Cross-namespace conflicts that remain after cleanup are retained as aliases, and
the generated report records them. No canonical tag ID, type, or search query
text changes.

## Generated artifact

| Field | Value |
| --- | --- |
| Locale | `zh-Hans` |
| Pack schema | `2` |
| Revision | `2` |
| Catalog revision | `43` |
| Matched entries | `38,035` of `86,030` |
| Fallback entries | `47,995` |
| Maintainer additions | `7` category labels absent from the source namespace files |
| Aliases | `254` |
| Pack size | `1,874,650` bytes |
| SHA-256 | `26e1672781f481fc135dad920766441b7efd53c75295088991c403d3cb9df40f` |

Revision 1 remains published for clients that still read the earlier manifest.
The pack was generated locally, verified against the catalog feed, and signed
before publication under `feed/translations/`.

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
  --revision 2 `
  --catalog data/tags.json `
  --output .gradle/zh-Hans-pack.json

python -m scripts.build_tag_translation_release `
  .gradle/zh-Hans-pack.json `
  --catalog data/tags.json `
  --output .gradle/zh-Hans-translation-feed
```

The generated report records every unmatched canonical tag and each conflict so that later corrections can be reviewed without changing canonical IDs or types.
