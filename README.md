# NReader

NReader is an unofficial NHentai Android client maintained and modified by **TheSkyC**.
It is a derivative of [NClientV3 by maxwai](https://github.com/maxwai/NClientV3),
which is based on [NClientV2 by Dar9586](https://github.com/Dar9586/NClientV2).

NReader modifications are Copyright 2026 TheSkyC and are distributed under
Apache License 2.0. The original authors retain copyright in their work.
See [LICENSE](LICENSE), [NOTICE](NOTICE), and [CHANGELOG](CHANGELOG.md).

## Development baseline

- Upstream baseline: NClientV3 commit `8877d1220b17ae3e3e669ef21c1106049027f0f5`.
- Original local capture change: `5084b77d0d9e11c85147220880477201a22e905f`
  (`fix(capture): allow screenshots and screen recording`, by TheSkyC).
- NReader initialization precedes the replayed capture change; upstream history is retained.
- Application ID: `com.theskyc.nreader` (`.debug` is added for debug builds).
- The Java namespace remains `com.maxwai.nclientv3` to keep the fork focused and
  simplify merging upstream changes. It does not identify the installed application.

NReader starts at version `1.0.0`, independently of the upstream `4.2.7` version.
Debug builds use `1.0.0-debug`.

The local Git remote `upstream` points to the original NClientV3 repository.
No NReader publishing remote is assumed. Add your own `origin` when a hosting
repository is available.

## Build on Windows

Use JDK 17 or newer, Android SDK platform 36, and the included Gradle wrapper.
Set `JAVA_HOME` to a JDK and configure `ANDROID_HOME` or an ignored
`local.properties` file with your SDK location.

```powershell
.\gradlew.bat assembleDebug
```

- `post28`: Android 9 / API 28 and newer.
- `pre28`: Android 8 / API 26–27, retaining the upstream compatibility flavor.
- APK filenames begin with `NReader_`. Debug builds are labeled `NReader Debug`.

Debug builds use Android's standard debug signing configuration and do not need
`keystore.properties`. To sign release builds, copy
[keystore.properties.example](keystore.properties.example) to
`keystore.properties`, supply your own keystore and credentials, and run:

```powershell
.\gradlew.bat assembleRelease
```

Without that local configuration, release APKs are **unsigned** and must be signed
before installation or distribution. Keep the same private signing key for future
NReader releases. Keystores, passwords, local SDK configuration, build outputs, and
IDE caches were not imported from the source working directory and must not be committed.

## Data and updates

NReader installs alongside NClientV3. App-private settings, databases, favorites,
and login state are separate; they are not automatically copied from NClientV3.
Export supported data in the old app and import it in NReader. The default shared
storage directory is `NReader`; back up and copy existing downloads manually if needed.
The old application's files are not renamed or removed.

The upstream application-update and bug-report destinations are not used for NReader.
Until your own repository is available, update checks and the bug-report entry are
hidden and no release-update requests are made. To enable them, edit these
`defaultConfig` fields in [app/build.gradle.kts](app/build.gradle.kts):

- `UPDATE_REPOSITORY`: your GitHub `owner/repository` for NReader releases.
- `ISSUE_TRACKER_URL`: your NReader issue-reporting URL.

Leave either field empty to keep that feature disabled. Publish APKs built with
NReader's application ID and your own signing key. Tag metadata continues to use
the upstream NClientV3 data feed; this is independent of APK updates.
Translations remain in `app/src/main/res/values-*/strings.xml`; `crowdin.yml`
is retained as a format mapping, without claiming ownership of the upstream
Crowdin project.

## Features

- Browse and search galleries by query or tags.
- Include, exclude, blur, or hide tags.
- Download manga and manage favorites and bookmarks.
- Share galleries or open them in a browser.
- Protect app access with a PIN.
- Take screenshots and screen recordings, as enabled by the preserved local change.

Screenshots and screen recording are permitted even on PIN-protected screens;
multitasking previews are no longer hidden by `FLAG_SECURE`.

## Upstream screenshots

These images are retained from NClientV3 and show the upstream interface.

| Main page | Lateral menu |
| --- | --- |
| ![Main page](docs/images/phoneScreenshots/img1.png) | ![Lateral menu](docs/images/phoneScreenshots/img2.png) |
| Search | Random manga |
| ![Search](docs/images/phoneScreenshots/img3.png) | ![Random manga](docs/images/phoneScreenshots/img4.jpg) |

## Contributors to NClientV3

- [shirokun20](https://github.com/shirokun20) for the initial bug fixes
- [w0x8m](https://github.com/w0x8m) for the new language picker & Chinese Simplified & Traditional translation
- [ananas7](https://github.com/ananas7) for Arabic translation
- [raymi7066](https://github.com/raymi7066) for Chinese Simplified translation
- Худі Таджик (tadzikhudi) for Ukrainian translation
- [PegadaDLancha](https://github.com/PegadaDLancha) for Brazilian Portuguese translation
- Ivan Lost (dovakin1886) for Ukrainian translation
- crorcetn for Japanese translation
- [Inori333](https://github.com/inori-3333) for the initial implementation of the new api
- [Kronos2308](https://github.com/kronos2308) for Spanish translation
- [Locked_Fog](https://github.com/locked-fog) for Chinese Simplified translation
- [zhongshen6](https://github.com/zhongshen6) for bug fixes

## Contributors to NClientV2

- [Still34](https://github.com/Still34) for code cleanup & Traditional Chinese translation
- [TacoTheDank](https://github.com/TacoTheDank) for XML and gradle cleanup
- [hmaltr](https://github.com/hmaltr) for Turkish translation and issue moderation
- [ZerOri](https://github.com/ZerOri) and [linsui](https://github.com/linsui) for Chinese translation
- [herrsunchess](https://github.com/herrsunchess) for German translation
- [eme22](https://github.com/herrsunchess) for Spanish translation
- [velosipedistufa](https://github.com/velosipedistufa) for Russian translation
- [bottomtextboy](https://github.com/bottomtextboy) for Arabic translation
- [MaticBabnik](https://github.com/MaticBabnik) for bug fixes
- [DontPayAttention](https://github.com/DontPayAttention) for French translation
- [kuragehimekurara1](https://github.com/kuragehimekurara1) for Japanese translation
- [chayleaf](https://github.com/chayleaf) for Cloudflare bypass
- [Atmosphelen](https://github.com/Atmosphelen) for Ukrainian translation

## Libraries

- OKHttp ([License](https://github.com/square/okhttp/blob/master/LICENSE.txt))
- PersistentCookieJar ([License](https://github.com/franmontiel/PersistentCookieJar/blob/master/LICENSE.txt))
- JSoup ([License](https://github.com/jhy/jsoup/blob/master/LICENSE))
- Glide ([License](https://github.com/bumptech/glide/blob/master/LICENSE))
- AmbilWarna ([License](https://github.com/yukuku/ambilwarna/blob/master/LICENSE))
- AndroidFastScroll ([License](https://github.com/zhanghai/AndroidFastScroll/blob/master/LICENSE))

These components retain their respective licenses and notices.

## License

```text
Copyright 2024 maxwai
Copyright 2026 TheSkyC (NReader modifications only)

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```

The full license and attribution notices are included in APK assets and are
readable from the NReader entry in Settings.
