# Localization

NReader uses Android resources for English defaults and the twelve existing
translations under `app/src/main/res/values-*/`. Locale-specific terminology
should read naturally rather than follow English word order.

## Resource contracts

- Keep resource names, preference values, array order, format argument positions
  and argument types stable. Translate display text, not stored identifiers.
- `strings.xml` contains the main catalog. The `*_strings.xml` files contain
  feature catalogs and quantity resources. `crowdin.yml` maps both groups.
- Page counts, favorite counts, tag limits, minimum comment lengths and snapshot
  ages use Android `plurals`. Their existing resource names are retained, and
  callers must use `getQuantityString` with the displayed count as the selector.
- Provide every quantity required by a supported locale, including Arabic zero,
  one, two, few and many, and Russian/Ukrainian one, few and many. Every plural
  must include `other`. Chinese and Japanese use `other` only.
- Keep deliberate `\n` escapes in multiline messages. Do not put literal line
  breaks inside strings to approximate Android runtime line breaks.
- New or revised upstream-derived resource files retain a modification notice.

## Meaning and tone

- Distinguish app unlocking from website account sign-in. Unlocking supports
  biometrics and the device lock-screen credentials; it does not sign in online.
- Describe the selected account model. An expired password account does not
  automatically fall back to a separately saved API-key account. Password storage
  is optional, and account removal affects saved credentials on this device.
- Distinguish local favorites from online favorites. Favorite action settings
  explain tap and touch-and-hold behavior rather than relying on icon shape.
- Reading-progress filters test for a saved automatic position. They do not test
  a manually assigned reading status or whether the last page has been reached.
- Saving a reader page saves an image. Sharing may attach the page link as text;
  it does not draw a watermark on the image.
- Ignored-gallery visibility settings hide or blur results; they do not delete
  downloaded files. Network image policy controls loading rather than removing
  existing local images.
- Backup import confirmation explains that it replaces database data and imports
  the settings included in the archive. Downloaded image files are unchanged.
  Integrity verification does not establish who created an archive.
- State the requested operation and its consequences in confirmations. Keep
  labels brief and errors actionable; do not blame the user or promise success
  before verification or server-side eligibility checks finish.
- For Simplified Chinese, use 账号, 本地收藏, 在线收藏, 屏蔽列表, 画廊, 画师,
  社团 and 图片 consistently. For Traditional Chinese, use 帳號, 本機收藏,
  線上收藏, 封鎖清單, 圖庫, 繪師, 社團, 圖片 and 剪貼簿 consistently.
- Keep API, PIN, PDF, ZIP, GitHub and NReader casing. Translate surrounding prose,
  including accessibility descriptions, errors and permission explanations.

## Validation

Build both compatibility flavors and inspect Android lint findings, including
translation and plural diagnostics. Resource tests exercise quantity selection
and formatted values across the existing locales. Review locale resources as a
whole, including the smaller feature catalogs, for missing or duplicate keys,
array shape and formatter compatibility.

Run `python scripts/check_localization.py` for the SDK-independent catalog checks
and `./gradlew.bat :app:testMinApi28DebugUnitTest --tests '*LocalizationTest'` for
Android resource selection and formatting tests on API 26 and API 28.

Compilation and resource tests do not establish native-language editorial
quality or visual fit. Check representative screens with long translations,
large text and right-to-left layout on an available device. Keep device checks
and static checks distinct when reporting results.
