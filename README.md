# ParasDokusha

> Android web novel reader — focused on simplicity and reading immersion.
> Search a large catalog, open your pick, and just enjoy.

**Current version**: 2.2.9 (versionCode 22)
**Application ID**: `com.paras.noveldokusha`
**Display name**: ParasDokusha
**Min Android**: 8.0 (API 26)
**Target Android**: 14 (API 34)

> The `applicationId` (`com.paras.noveldokusha`) and the `namespace`
> (`my.noveldokusha`) intentionally differ. The applicationId is the app's
> identity on the device and on the Play Store; the namespace is the
> internal package for generated `R` and `BuildConfig` classes. Don't
> change either — see [BUILD.md §7](BUILD.md#7-precautions-and-gotchas).

## Features

- **28 online sources** across 3 languages + 1 local EPUB source
- **2 databases** for cross-source novel search (Novel Updates, Baka-Updates)
- **Local source** — read EPUB files from device storage via SAF
- **Backup & restore** — full library/database export and import
- **Light, dark, grey, black, and launcher themes** — Material 3
- **Reader**
  - Infinite vertical scroll across chapters
  - Custom font family, font size, selectable text, keep-screen-on, full-screen
  - Live translation (Google MLKit on-device + optional Gemini AI cloud)
  - Text-to-speech with background playback, voice/pitch/speed control,
    saved voice presets, media-style notification with play/pause/skip
- **Multi-tier Cloudflare evasion** — browser-headers interceptor + headless
  WebView with Turnstile-click, force-re-navigation, and IP-block fast-fail
  (inspired by the [trawl](https://github.com/germondai/trawl) project)
- **TimoTxt translation pipeline** — three TimoTxt sources share the same
  path structure on `timotxt.com`:
  - `TimoTxt` — raw Chinese
  - `TimoTxtTranslate` — Chinese → English via Google Translate free API
  - `TimoTxtGemini` — Chinese → English via Google Gemini AI
  - "Open in WebView" opens the translate.goog proxy URL so the browser
    shows the JS-translated page

## Build

See [BUILD.md](BUILD.md) for the complete build guide. Quick start:

```bash
# Prerequisites
export JAVA_HOME=$HOME/jdk17        # JDK 17 (not 21!)
export ANDROID_HOME=$HOME/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/build-tools/34.0.0:$PATH

# Create local.properties with keystore info (see BUILD.md §2.3)

# Build release APK (full flavor with MLKit + Gemini translation)
./gradlew assembleFullRelease --no-daemon --no-configuration-cache --console=plain

# The unsigned APK appears at:
# app/build/outputs/apk/full/release/NovelDokusha_v2.2.9-full-release-unsigned.apk
```

### Build flavors

| Flavor | Translation backend | Notes |
|--------|--------------------|-------|
| `full` | Google MLKit + Gemini AI | Includes on-device translation models. **Recommended.** |
| `foss` | No translation | Pure FOSS build, no proprietary dependencies |

### Signing

**Critical**: The release APK must be signed with **both v1 (JAR) and v2
(APK Signature Scheme v2)** schemes. AGP 8.2 defaults to v2-only, which
breaks the package installer on many Android TV devices and some phones.
After Gradle produces the unsigned APK, re-sign manually with `apksigner`:

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner sign \
    --ks <keystore.jks> --ks-key-alias <alias> \
    --ks-pass pass:<pass> --key-pass pass:<pass> \
    --v1-signing-enabled true --v2-signing-enabled true \
    --v3-signing-enabled false --v4-signing-enabled false \
    --min-sdk-version 23 \
    --out <signed.apk> <unsigned.apk>
```

Verify with `apksigner verify --verbose --min-sdk-version 23 <apk>` —
both v1 and v2 must show `true`.

### CI / GitHub Actions

Four workflows live under `.github/workflows/`, one per flavor × build-type:

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `debug_foss.yml` | push, PR, manual | Debug FOSS APK, `versionCode = 1000 + commit-count` (smoke test) |
| `debug_full.yml` | manual | Debug Full APK (translator codepaths) |
| `release_foss.yml` | manual | Builds, **v1+v2 signs**, verifies, publishes FOSS release |
| `release_full.yml` | manual | Builds, **v1+v2 signs**, verifies, publishes Full release |

Both release workflows **fail-fast** if (a) v1 signing is missing, or
(b) `applicationId != com.paras.noveldokusha`.

## Sources

See [docs/SOURCES.md](docs/SOURCES.md) for the full list of supported
sources, their URLs, and the Cloudflare bypass architecture.

### English sources (22)
Light Novel Translations, Read Novel Full, Royal Road, Novel Updates,
Reddit, AT, Wuxia, Novel Fire, Novel Phoenix, Novel Cool, Lnori, Wuxia
Box, Sousetsuka, Box Novel, NovelHall, Wuxia World, Saikai, Light Novel
World, Meio Novel, More Novel, NovelKu, Wb Novel

### Indonesian sources (3)
Indonesia Webnovel, Baca Lightnovel, Sakura Novel

### Chinese sources (3)
TimoTxt (raw Chinese), TimoTxt (Translated via Google Translate API),
TimoTxt (Gemini AI translation)

### Local source
Read local EPUB files from device storage.

## Screenshots

| Library | Finder |
|:---:|:---:|
| ![](screenshots/library.png) | ![](screenshots/finder.png) |
| **Book info** | **Book chapters** |
| ![](screenshots/book_info.png) | ![](screenshots/book_chapers.png) |
| **Reader** | **Database search** |
| ![](screenshots/reader.png) | ![](screenshots/database_search.png) |
| **Global search** | |
| ![](screenshots/global_search.png) | |

## Tech stack

- Kotlin 1.9.23, AGP 8.2.2, Gradle 8.2 (wrapper included)
- Jetpack Compose + XML views (hybrid migration)
- Material 3 (v1.2.1)
- Coroutines + LiveData
- Room (SQLite) for storage
- Jsoup (HTML parsing)
- OkHttp 5 (networking) with custom interceptor chain
- Coil + Glide (image loading)
- Gson + Moshi + kotlinx.serialization
- Google MLKit for on-device translation (Full flavor)
- Google Gemini AI for chapter translation (TimoTxt Gemini source)
- Android TTS + MediaSession for background narration
- Hilt (dependency injection)
- WorkManager for library updates + app update checker

## Documentation

| Doc | Purpose |
|-----|---------|
| [BUILD.md](BUILD.md) | Complete build guide, common errors, signing, CI |
| [docs/SOURCES.md](docs/SOURCES.md) | All supported sources + Cloudflare bypass architecture |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Module graph, data flow, source interface contracts |
| [docs/PERFORMANCE.md](docs/PERFORMANCE.md) | Performance notes, known bottlenecks, audit findings |
| [docs/TROUBLESHOOTING.md](docs/TROUBLESHOOTING.md) | Common runtime issues and fixes |
| [AGENTS.md](AGENTS.md) | Hand-off doc for AI agents working on this repo |

## License

Copyright © 2023, [nani](https://github.com/nanihadesuka). Released under
[GPL-3](LICENSE) FOSS.
