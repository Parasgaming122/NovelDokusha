# NovelDokusha — Architecture Overview

> **Scope**: This document gives a top-level architectural map of the NovelDokusha Android app. For deeper detail on a specific subsystem, see the sibling docs in this folder (`DATA_LAYER.md`, `CORE_ENGINE.md`, `UI_LAYER.md`, `TTS_ENGINE.md`, `TRANSLATION_SYSTEM.md`, `DEPENDENCIES.md`, `SECURITY.md`, `Build-fixes.md`, `fixes.md`).

## 1. Project at a Glance

| Property | Value |
|---|---|
| Type | Android web-novel reader |
| Language | Kotlin 1.9.23 (no Java sources) |
| UI | Hybrid Jetpack Compose 1.6.8 + legacy XML views (incremental migration) |
| Design | Material 3 |
| Min SDK | 26 (Android 8.0) |
| Target/Compile SDK | 34 (Android 14) |
| Java target | 17 |
| Build system | Gradle 8.2 (wrapper) + AGP 8.2.2 + Kotlin DSL |
| DI | Hilt 2.49 + Dagger AndroidX 1.2.0 |
| Storage | Room 2.6.1 (SQLite) |
| Async | Kotlin Coroutines 1.7.3 + Flow |
| Networking | OkHttp 5.0.0-alpha.11 + Jsoup 1.17.2 |
| Translation (on-device) | Google ML Kit 17.0.2 (full flavor only) |
| Translation (server-side) | Google Gemini API (via direct REST) |
| TTS | Android `TextToSpeech` + MediaSession for background playback |
| Background work | WorkManager 2.9.0 |
| Logging | Timber 5.0.1 |
| License | GPL-3 (fork of `nanihadesuka/NovelDokusha` v2.2.0) |

## 2. Module Topology

The project uses a multi-module clean architecture with 27 modules (1 app + 9 features + 12 tooling + 5 shared libs + 1 build-logic):

```
NovelDokusha/
├── app/                          # Application entry point, DI graph, MainActivity
├── build-logic/                  # Gradle convention plugins (separate included build)
│   └── convention/               #   AppConfig (SDK/Java versions), Hilt/Compose plugins
│
├── core/                         # Domain primitives, AppPreferences, Response, PagedList
├── coreui/                       # Theme, BaseActivity/ViewModel, Compose components
├── data/                         # 8 repositories, interactors, mappers
├── networking/                   # OkHttp client, interceptors (UA / Brotli / CloudFlare)
├── navigation/                   # NavigationRoutes interface (impl in app)
├── scraper/                      # 30 sources + 2 databases + TextExtractor
├── strings/                      # Localized string resources
│
├── features/                     # Feature modules (one per screen area)
│   ├── reader/                   #   Reader screen + TTS + media controls
│   ├── chaptersList/             #   Chapter list for one book
│   ├── libraryExplorer/          #   User's library
│   ├── catalogExplorer/          #   Source/database catalog (Finder tab)
│   ├── sourceExplorer/           #   Browse one source's catalog
│   ├── databaseExplorer/         #   NovelUpdates/BakaUpdates search + book info
│   ├── globalSourceSearch/        #   Cross-source search
│   ├── settings/                 #   Settings (theme, Gemini, library updates, etc.)
│   └── webview/                  #   Embedded browser for CloudFlare bypass
│
└── tooling/                      # Reusable tooling libraries
    ├── algorithms/               #   delimiterAwareTextSplitter (used by TTS + reader)
    ├── application_workers/      #   WorkManager workers (library updates, app update checker)
    ├── backup_create/            #   Zip backup service + dialog
    ├── backup_restore/           #   Restore-from-zip service + launcher
    ├── epub_importer/            #   EPUB → local source service
    ├── epub_parser/              #   EPUB ZIP parser (container.xml → opf → ncx)
    ├── local_database/           #   Room DB, DAOs, entities, migrations
    ├── local_source/             #   SAF-backed local files as a "source"
    ├── text_to_speech/           #   TTS manager (Utterance<T> queue, sub-utterance IDs)
    └── text_translator/          #   Translation abstraction
        ├── domain/               #     TranslationManager interface
        ├── translator/           #     ML Kit impl (full flavor)
        └── translator_nop/       #     No-op stub (foss flavor)
```

## 3. Module Dependency Graph

```
                         ┌─────────────┐
                         │     app     │
                         └──────┬──────┘
            ┌───────────────────┼───────────────────┐
            │                   │                   │
       ┌────▼────┐        ┌─────▼─────┐       ┌─────▼────┐
       │features │        │   data    │       │ scraper  │
       └────┬────┘        └─────┬─────┘       └─────┬────┘
            │                   │                   │
            └───────────────────┼───────────────────┘
                                │
            ┌───────────────────┼───────────────────┐
            │                   │                   │
       ┌────▼────┐        ┌─────▼─────┐       ┌─────▼─────┐
       │ coreui  │        │   core    │       │networking │
       └─────────┘        └───────────┘       └───────────┘

       ┌────────────┐
       │  strings   │ ← leaf module, depended on by core/scraper
       └────────────┘

       ┌─────────────┐
       │ navigation  │ ← depends on core + local_database
       └─────────────┘

Tooling modules hang off the feature/app layers:
   tooling/local_database      → core, used by data, navigation, most features
   tooling/epub_parser         → core, used by epub_importer, local_source
   tooling/epub_importer       → core, coreui, strings, data, epub_parser, local_database
   tooling/backup_create       → core, coreui, strings, data, local_database
   tooling/backup_restore      → core, coreui, strings, data, local_database
   tooling/local_source        → core, coreui, strings, data, scraper, networking, epub_parser
   tooling/text_to_speech      → tooling/algorithms
   tooling/text_translator/translator     → core, text_translator/domain, ML Kit (full flavor)
   tooling/text_translator/translator_nop → core, text_translator/domain (foss flavor)
   tooling/application_workers → core, coreui, strings, data, navigation, local_database
   tooling/algorithms          → (leaf, pure Kotlin)
```

## 4. Build Flavors & Types

### 4.1 Product flavors (single dimension: `dependencies`)

| Flavor | Translation module wired | Use case |
|---|---|---|
| **full** | `tooling.text_translator.translator` (ML Kit + Play Services) | Default — on-device translation between 50+ languages; requires Google Play |
| **foss** | `tooling.text_translator.translator_nop` (no-op stub) | 100% Google-free APK for F-Droid / degoogled devices. Translation UI is hidden. |

The Gemini AI translation source (`scraper/sources/TimoTxtGemini`) works in **both** flavors — it depends only on the networking layer, not on ML Kit.

### 4.2 Build types

| Build type | R8 shrinking | R8 optimization | Resource shrinking | Obfuscation | ProGuard rules |
|---|---|---|---|---|---|
| `debug` | ❌ off | ❌ off | ❌ off | ❌ off | none |
| `release` | ✅ on | ✅ on | ✅ on | ❌ **off** (intentional) | `proguard-rules.pro` |

> **Note**: Release builds do NOT obfuscate. This is intentional — it keeps stack traces readable while still stripping unused code and resources. Typical APK size: ~25 MB (debug) → ~5 MB (release).

### 4.3 Signing

Signing config is **dynamic** — read from a properties file (default `local.properties`, overridable via `-PlocalPropertiesFilePath=...`). If the file contains a `storeFile` entry, that config is applied to all build types. Otherwise debug falls back to AGP's auto-generated debug keystore, and release builds would be unsigned.

> **⚠️ Gotcha**: `file(storeFile)` in `app/build.gradle.kts` resolves relative to the **`app/` module directory**, not the project root. So `storeFile=debug.keystore` looks for `app/debug.keystore`.

### 4.4 Custom Gradle properties

| Property | Default | Purpose |
|---|---|---|
| `splitByAbi` | `false` | Enables per-ABI APK splits |
| `splitByAbiDoUniversal` | `false` | When `splitByAbi=true`, also produces a universal APK |
| `localPropertiesFilePath` | `"local.properties"` | Path to the signing config properties file |

## 5. Convention Plugins (`build-logic/`)

Three custom Gradle plugins auto-apply common configuration so individual modules don't repeat themselves:

| Plugin ID | Applies | What it configures |
|---|---|---|
| `noveldokusha.android.application` | `com.android.application`, Kotlin, Hilt | `compileSdk=34`, `targetSdk=34`, `minSdk=26`, Java 17 |
| `noveldokusha.android.library` | `com.android.library`, Kotlin, Hilt | Same as above + `resourcePrefix` derived from module path + forces `release.isMinifyEnabled=false` (R8 only runs at the app module) |
| `noveldokusha.android.compose` | (none — works on existing extension) | `buildFeatures.compose=true`, Compose compiler ext v1.5.13, Robolectric `isIncludeAndroidResources=true` |

### `AppConfig` constants

```kotlin
internal object appConfig {
    val javaVersion = JavaVersion.VERSION_17
    const val JAVA_VERSION_STRING = "17"
    const val COMPILE_SDK = 34
    const val TARGET_SDK = COMPILE_SDK   // = 34
    const val MIN_SDK = 26
}
```

## 6. Key Architectural Patterns

### 6.1 Single Activity with Compose-hosted tabs
The app uses a single `MainActivity` with bottom-nav tabs (Library / Finder / Settings). Tab content is switched via `AnimatedTransition` over a saved `activePageIndex` — there's no Jetpack Navigation Compose graph. Sub-screens (`ReaderActivity`, `ChaptersActivity`, `SourceCatalogActivity`, `DatabaseSearchActivity`, `DatabaseBookInfoActivity`, `GlobalSourceSearchActivity`, `WebViewActivity`) are launched as separate activities with type-safe intent extras (`StateBundle` interface + `StateExtra_*` property delegates).

### 6.2 Hybrid Compose-over-ViewBinding reader
The reader screen (`features/reader`) uses a `ListView` + 11-view-type `ArrayAdapter` for the chapter text itself (for performance with very long chapters), with Compose overlaying the top/bottom bars and dialogs. This is the only place where legacy XML views are still in use.

### 6.3 Hilt graph
`App` is `@HiltAndroidApp`. `AppModule` binds `AppNavigationRoutes → NavigationRoutes` and `ToastyToast → Toasty`. Most repositories are `@Singleton @Inject constructor` and discovered by Hilt automatically. The `Scraper` (in the `scraper` module) is also `@Singleton` and instantiates all 30 sources eagerly in its constructor body — sources are NOT individually Hilt-injectable (except `LocalSource`, which is bound via `LocalSourceModule`).

### 6.4 WorkManager with Hilt-injected workers
`App implements Configuration.Provider` and exposes a `HiltAppEntryPoint` to obtain the `AppWorkerFactory`. Two workers run periodically:
- `UpdatesCheckerWorker` — checks GitHub for a newer app release (every 2 days)
- `LibraryUpdatesWorker` — fetches new chapters for library books (interval configurable: 6h / 12h / 1d / 2d)

### 6.5 Reactive preferences
`AppPreferences` exposes `Preference<T>` properties backed by `SharedPreferences`. Each preference can be observed as a `Flow<T>` or as a Compose `MutableState<T>` (via `.state(scope)`). Custom listeners bridge `OnSharedPreferenceChangeListener` to Flow/Compose state.

> ⚠️ The `toState()` helper is self-described as "probably some details wrong. Use only OUTSIDE of composable scope (e.g. viewModel)".

### 6.6 Response + tryConnect pattern
All I/O returns `Response<T>` (sealed: `Success` / `Error`). The `tryConnect { ... }` and `tryFlatConnect { ... }` helpers in `networking` catch exceptions and convert them to `Response.Error` with formatted messages. Extensions `map`, `syncMap`, `flatMap`, `flatMapError`, `asNotNull`, `flatten` provide functional chaining.

### 6.7 Source-routing with URL prefix matching
`Scraper.getCompatibleSource(url)` matches by `url.startsWith(baseUrl)` after slash-normalization. This is why translation-proxy sources (`TimoTxtTranslate`, `TimoTxtGemini`) use fake domains (`translate.goog`, `gemini.goog`) — they need distinct `baseUrl` values so the router can pick the right source for the same underlying `timotxt.com` content, then `transformChapterUrl()` rewrites back to the real domain before HTTP fetch.

> ⚠️ **Source-routing fix**: `DownloaderRepository.bookChapter` now resolves the source from the **original** URL first (before following redirects). This was a critical bug fix — previously, `translate.goog` URLs would redirect to `timotxt.com`, match the plain `TimoTxt` source (no translation), and cache untranslated Chinese text.

## 7. Two Independent Translation Systems

The codebase has **two completely separate translation systems** that do not interact:

| Aspect | ML Kit (on-device) | Gemini (server-side) |
|---|---|---|
| Where the code lives | `tooling/text_translator/translator` (full flavor) | `scraper/sources/TimoTxtGemini` + `GeminiApiClient` |
| Where it's surfaced | Reader bottom-bar Translate icon + Settings → Translation models | Finder → "TimoTxt (Gemini)" source |
| What gets translated | Any chapter body, regardless of source | Only chapters fetched from `timotxt.com` via the Gemini source |
| Requires internet? | Only for initial model download (~30 MB per language pair) | Yes, every chapter |
| Works offline? | ✅ Yes (after model download) | ❌ No |
| Works in foss flavor? | ❌ No (UI is hidden) | ✅ Yes (network-only) |
| Quality | Medium (machine translation) | High (two-pass LLM refinement) |
| Cost | Free | Free tier: 10 RPM / 250 RPD on `gemini-2.5-flash` |

See `TRANSLATION_SYSTEM.md` for the full architecture of both systems.

## 8. App Entry Points

| Route | Activity | Purpose |
|---|---|---|
| `main` | `MainActivity` | 3-tab bottom nav: Library / Finder / Settings |
| `reader` | `ReaderActivity` | Full-screen chapter reader with TTS |
| `chapters` | `ChaptersActivity` | Chapter list for one book |
| `sourceCatalog` | `SourceCatalogActivity` | Browse one source's catalog |
| `databaseSearch` | `DatabaseSearchActivity` | Search NovelUpdates / BakaUpdates |
| `globalSearch` | `GlobalSourceSearchActivity` | Search across all enabled sources |
| `webView` | `WebViewActivity` | In-app browser (for CloudFlare bypass) |

`MainActivity` also accepts `ACTION_SEND` and `ACTION_VIEW` intents for `application/epub+zip` — tapping an EPUB in a file manager launches the app and starts `EpubImportService`.

## 9. Permissions

```xml
INTERNET, ACCESS_NETWORK_STATE,
FOREGROUND_SERVICE,
FOREGROUND_SERVICE_DATA_SYNC,    <!-- backup / restore / EPUB import / library update -->
FOREGROUND_SERVICE_MEDIA_PLAYBACK, <!-- reader narrator -->
POST_NOTIFICATIONS                <!-- Android 13+ -->
```

## 10. See Also

- `DATA_LAYER.md` — repositories, Room schema, source-routing, backup/restore
- `CORE_ENGINE.md` — Response, PagedList, AppPreferences, networking, interceptors
- `UI_LAYER.md` — feature modules, hybrid Compose/ViewBinding reader
- `TTS_ENGINE.md` — Utterance queue, MediaSession, half-buffer prefetch
- `TRANSLATION_SYSTEM.md` — ML Kit vs Gemini, two-pass translation, glossary
- `DEPENDENCIES.md` — full version catalog, repositories, convention plugins
- `SECURITY.md` — API key storage, CloudFlare bypass, signing
- `Build-fixes.md` — every build fix applied during this fork's development
- `fixes.md` — bug list with severity and remediation status
