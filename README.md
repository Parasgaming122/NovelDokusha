# NovelDokusha

Android web novel reader. Reader focused on simplicity, improving reading immersion.
Search from a large catalog of content, open your pick and just enjoy.

**Current version**: 2.2.9 (versionCode 22)  
**Application ID**: `com.paras.noveldokusha`  
**Min Android**: 8.0 (API 26)  
**Target Android**: 14 (API 34)

## Features
  - Multiple sources from where to read novels (28 online sources across 3
    languages + 1 local EPUB source)
  - Multiple databases to search for novels (Novel Updates, Baka-Updates)
  - Local source to read local EPUBs
  - Easy backup and restore
  - Light and dark themes
  - Follows modern Material 3 guidelines
  - Reader
    - Infinite scroll
    - Custom font, font size
    - Live translation (MLKit + optional Gemini AI)
    - Text to speech
      - Background playback
      - Adjust voice, pitch, speed
      - Save your preferred voices
  - Multi-tier Cloudflare evasion (browser-headers + headless WebView with
    Turnstile click, force-re-navigation, and IP-block fast-fail; inspired by
    the [trawl](https://github.com/germondai/trawl) project)
  - TimoTxt sources use a `translate.goog` proxy pipeline to bypass
    Cloudflare and translate Chinese novels to English in real time
    (two variants: Google Translate API and Gemini AI)

## Build

See [BUILD.md](BUILD.md) for the complete build guide, including:
- Environment setup (JDK 17, Android SDK, keystore)
- Common build errors and their fixes
- APK signing with v1 + v2 schemes (required for Android TV compatibility)
- Precautions around the keystore and applicationId
- CI / GitHub Actions integration

### Quick start

```bash
# Prerequisites
export JAVA_HOME=$HOME/jdk17        # JDK 17 (not 21!)
export ANDROID_HOME=$HOME/android-sdk
export PATH=$JAVA_HOME/bin:$ANDROID_HOME/build-tools/34.0.0:$PATH

# Create local.properties with keystore info (see BUILD.md §2.3)

# Build release APK (full flavor with MLKit + Gemini translation)
./gradlew assembleFullRelease --no-daemon --no-configuration-cache --console=plain

# The APK appears at:
# app/build/outputs/apk/full/release/NovelDokusha_v2.2.9-full-release.apk
```

### Build flavors

| Flavor | Translation backend | Notes |
|--------|--------------------|-------|
| `full` | Google MLKit + Gemini AI | Includes on-device translation models. **Recommended.** |
| `foss` | No translation | Pure FOSS build, no proprietary dependencies |

### Signing

**Critical**: The release APK must be signed with **both v1 (JAR) and v2
(APK Signature Scheme v2)** schemes. AGP 8.2 defaults to v2-only, which can
break the package installer on Android TV devices and some phones. See
[BUILD.md §4](BUILD.md#4-signing-the-apk-v1--v2) for the full signing
procedure and the manual re-sign script.

### CI / GitHub Actions

Four workflows live under `.github/workflows/`, one per flavor × build-type:

| Workflow | Trigger | Purpose |
|----------|---------|---------|
| `debug_foss.yml` | push, PR, manual | Builds a debug FOSS APK with `versionCode = 1000 + commit-count` (smoke test) |
| `debug_full.yml` | manual | Builds a debug Full APK (translator codepaths) |
| `release_foss.yml` | manual | Builds, **v1+v2 signs**, verifies, and publishes a FOSS release |
| `release_full.yml` | manual | Builds, **v1+v2 signs**, verifies, and publishes a Full release |

Both release workflows **fail-fast** if (a) v1 signing is missing, or
(b) `applicationId != com.paras.noveldokusha`. See
[BUILD.md §8](BUILD.md#8-ci--github-actions) for details.

## Sources

See [docs/SOURCES.md](docs/SOURCES.md) for the full list of supported sources,
their URLs, and notes on each.

### English sources (22)
Light Novel Translations, Read Novel Full, Royal Road, Novel Updates, Reddit,
AT, Wuxia, Novel Fire, Novel Phoenix, Novel Cool, Lnori, Wuxia Box, Sousetsuka,
Box Novel, NovelHall, Wuxia World, Saikai, Light Novel World, Meio Novel,
More Novel, NovelKu, Wb Novel

### Indonesian sources (3)
Indonesia Webnovel, Baca Lightnovel, Sakura Novel

### Chinese sources (3)
TimoTxt, TimoTxt (Translated), TimoTxt (Gemini)

### Local source
Read local EPUB files from device storage.

## Screenshots

|              Library               |                Finder                |
|:----------------------------------:|:------------------------------------:|
|    ![](screenshots/library.png)    |     ![](screenshots/finder.png)      |
|             Book info              |            Book chapters             |
|   ![](screenshots/book_info.png)   |  ![](screenshots/book_chapers.png)   |
|               Reader               |           Database search            |
|    ![](screenshots/reader.png)     | ![](screenshots/database_search.png) |
|           Global search            |                                      |
| ![](screenshots/global_search.png) |                                      |

## Tech stack
  - Kotlin 1.9.23
  - Android Gradle Plugin 8.2.2
  - Gradle 8.2 (wrapper included)
  - Jetpack Compose + XML views
  - Material 3 (v1.2.1)
  - Coroutines
  - LiveData
  - Room (SQLite) for storage
  - Jsoup (HTML parsing)
  - OkHttp 5 (networking)
  - Coil + Glide (image loading)
  - Gson + Moshi + kotlinx.serialization
  - Google MLKit for translation (Full flavor)
  - Google Gemini AI for chapter translation (TimoTxt Gemini source)
  - Android TTS
  - Android media (TTS playback notification controls)
  - Hilt (dependency injection)

## Documentation

- [BUILD.md](BUILD.md) — Complete build guide, common errors, signing, CI
- [docs/SOURCES.md](docs/SOURCES.md) — Full list of supported sources and
  Cloudflare bypass architecture

## License

Copyright © 2023, [nani](https://github.com/nanihadesuka), Released under [GPL-3](LICENSE) FOSS
