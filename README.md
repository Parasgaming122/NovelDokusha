# NovelDokusha

Android web novel reader. Reader focused on simplicity, improving reading immersion.
Search from a large catalog of content, open your pick and just enjoy.

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

## Build

### Prerequisites
- JDK 17
- Android SDK (API 34+, build-tools 34.0.0)
- Gradle 8.x (wrapper included)

### Build flavors

The app has two product flavors:

| Flavor | Translation backend | Notes |
|--------|--------------------|-------|
| `full` | Google MLKit | Includes on-device translation models |
| `foss` | No translation | Pure FOSS build, no proprietary dependencies |

### Build commands

```bash
# Debug APK (FOSS flavor)
./gradlew assembleFossDebug

# Release APK (FOSS flavor)
./gradlew assembleFossRelease

# Debug APK (Full flavor)
./gradlew assembleFullDebug

# Release APK (Full flavor)
./gradlew assembleFullRelease

# All variants
./gradlew assemble

# Run unit tests
./gradlew test
```

The built APKs are placed under `app/build/outputs/apk/<flavor>/<build-type>/`.

### GitHub Actions CI

A GitHub Actions workflow (`.github/workflows/build-apk.yml`) is included. It
builds all four APK variants (foss/full × debug/release) on every push and pull
request, and uploads the APKs as build artifacts.

### Signing

To sign the release build, create a `local.properties` file in the project root:

```properties
storeFile=/path/to/keystore.jks
storePassword=your-store-password
keyAlias=your-key-alias
keyPassword=your-key-password
```

If no signing config is provided, the release build will be unsigned (you can
still build it, but you'll need to sign it manually before installation).

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
  - Jetpack Compose + XML views
  - Material 3
  - Coroutines
  - LiveData
  - Room (SQLite) for storage
  - Jsoup (HTML parsing)
  - OkHttp 5 (networking)
  - Coil + Glide (image loading)
  - Gson + Moshi + kotlinx.serialization
  - Google MLKit for translation (Full flavor)
  - Android TTS
  - Android media (TTS playback notification controls)
  - Hilt (dependency injection)

## License

Copyright © 2023, [nani](https://github.com/nanihadesuka), Released under [GPL-3](LICENSE) FOSS
