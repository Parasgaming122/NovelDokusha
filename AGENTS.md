# AGENTS.md — Hand-off Guide for AI Agents

> This file is for AI agents (Claude, GPT, Gemini, local LLMs, etc.)
> working on the ParasDokusha codebase. It captures the non-obvious
> context that a human maintainer would know but an agent starting cold
> would not. Read this BEFORE making any changes.

## TL;DR

- **App**: Android web novel reader (Kotlin, Compose + XML hybrid, Hilt DI, Room, OkHttp, 29 Gradle modules)
- **Application ID**: `com.paras.noveldokusha` (NEVER change — breaks updates)
- **Namespace**: `my.noveldokusha` (different from applicationId — intentional)
- **Display name**: ParasDokusha (was NovelDokusha; renamed in v2.2.9)
- **Build**: JDK 17 (not 21!), AGP 8.2.2, Gradle 8.2, Kotlin 1.9.23 — pinned, don't upgrade without testing
- **Signing**: Release APKs MUST be re-signed with `apksigner` (v1+v2); AGP 8.2 silently drops v1
- **Sources**: 30 sources in `scraper/.../sources/`, registered in `Scraper.kt`
- **TimoTxt**: 3 variants (raw, Google Translate, Gemini) — see "TimoTxt traps" below

## Where things live

```
NovelDokusha/
├── app/                          # Application entry, MainActivity, DI, signing
├── scraper/                      # ⭐ Source registry + 30 source impls + 2 databases
│   └── src/main/java/my/noveldokusha/scraper/
│       ├── Scraper.kt            # Source registry — add new sources here
│       ├── SourceInterface.kt    # Sealed interface contract
│       ├── DatabaseInterface.kt  # Database (cross-source search) contract
│       ├── sources/              # 30 source impls (RoyalRoad.kt, Wuxia.kt, etc.)
│       ├── databases/            # NovelUpdates.kt, BakaUpdates.kt
│       └── TextExtractor.kt      # HTML → plain text extractor
├── networking/                   # OkHttp client + interceptor chain
│   └── src/main/java/my/noveldokusha/network/
│       ├── NetworkClient.kt      # ScraperNetworkClient — the singleton client
│       ├── okhttpExtensions.kt   # Call.await(), Response.toDocument(), Response.toJson()
│       ├── ScraperCookieJar.kt   # OkHttp ↔ WebView CookieManager bridge
│       └── interceptors/
│           ├── UserAgentInterceptor.kt       # Tier 0: Pixel 7 / Chrome 120 UA
│           ├── BrowserHeadersInterceptor.kt  # Tier 1: Sec-Fetch-*, Sec-CH-UA-*
│           ├── DecodeResponseInterceptor.kt  # gzip + br decompression
│           └── CloudfareVerificationInterceptor.kt  # Tier 2/3: WebView CF solver
├── core/                         # Response, PagedList, AppPreferences, utils
├── coreui/                       # Compose theme, components, BaseViewModel
├── data/                         # Repositories (consume Scraper + Room)
│   └── src/main/java/my/noveldoksuha/data/
│       ├── DownloaderRepository.kt     # Fetches chapters via Scraper
│       ├── ChapterBodyRepository.kt    # Caches chapter bodies in Room
│       └── ScraperRepository.kt        # Source catalog list + language filter
├── features/                     # UI features (9 sub-modules)
│   ├── reader/                   # ⭐ Reader Activity + session + TTS + live translation
│   ├── chaptersList/             # Chapter list screen
│   ├── globalSourceSearch/       # Search across all sources
│   ├── databaseExplorer/         # Novel Updates / Baka-Updates search
│   ├── sourceExplorer/           # Source catalog browse
│   ├── catalogExplorer/          # Language filter + catalog list
│   ├── settings/                 # Settings screen
│   ├── libraryExplorer/          # Library browse
│   └── webview/                  # In-app WebView browser
├── tooling/                      # Infrastructure (12 sub-modules)
│   ├── local_database/           # Room schema, DAOs, migrations
│   ├── epub_parser/              # EPUB parsing
│   ├── text_to_speech/           # Android TTS wrapper
│   ├── text_translator/          # MLKit (full) / no-op (foss)
│   ├── application_workers/      # WorkManager (library updates, app update checker)
│   └── ...
├── strings/                      # i18n + source display names
├── navigation/                   # Navigation routes interface
├── docs/                         # ⭐ Read these first
│   ├── ARCHITECTURE.md           # Module graph, data flow, source contract
│   ├── SOURCES.md                # All sources + CF bypass + TimoTxt pipeline
│   ├── PERFORMANCE.md            # Bottlenecks, audit findings
│   └── TROUBLESHOOTING.md        # Runtime issues
├── BUILD.md                      # Build guide, signing, CI
├── AGENTS.md                     # ⭐ This file
└── README.md
```

## Read these first

Before making any change, read:

1. **`docs/ARCHITECTURE.md`** — module graph, data flow, `SourceInterface` contract
2. **`docs/SOURCES.md`** — all sources, the TimoTxt translation pipeline, CF bypass
3. **`docs/PERFORMANCE.md`** — known bottlenecks and the v2.2.9 audit fixes
4. **`BUILD.md` §7 (Precautions)** — applicationId traps, signing traps

## The TimoTxt traps (read this if you touch TimoTxt)

The three TimoTxt sources share the same path structure on `timotxt.com`
but use **different stored URL hosts** as routing keys:

| Source | Stored URL host | Why |
|---|---|---|
| `TimoTxt` | `www.timotxt.com` | Default — direct fetch |
| `TimoTxtTranslate` | `www-timotxt-com.translate.goog` | Routing key so `getCompatibleSource()` matches this source, not `TimoTxt` |
| `TimoTxtGemini` | `www-timotxt-com-gemini.goog` | Same — routing key |

**Two URL transforms** (override both on `SourceInterface`):

- `transformChapterUrl(url)` — called by `DownloaderRepository.bookChapter()` before OkHttp fetch. For `TimoTxtTranslate` and `TimoTxtGemini`, this converts the routing host back to `www.timotxt.com` (strip `_x_tr_*` params). For `TimoTxt`, returns the URL unchanged.
- `transformWebviewUrl(url)` — called by `ReaderViewModel.transformUrlForWeb()` before opening the in-app WebView. For all three sources, this converts to `https://www-timotxt-com.translate.goog/{path}?_x_tr_sl=zh-CN&_x_tr_tl=en&_x_tr_hl=en&_x_tr_pto=wapp` so the browser shows the JS-translated page.

**DO NOT conflate these two.** If you make `transformChapterUrl` return the translate.goog URL, OkHttp fetches Chinese HTML from the proxy and the app's translation pipeline breaks (the proxy injects `<font>` tags and translation scripts that confuse the text extractor). If you make `transformWebviewUrl` return the timotxt.com URL, the browser shows raw Chinese (no JS translation).

**Why fetching from timotxt.com (not translate.goog)?** The app extracts text and translates it via the Google Translate API or Gemini API. The translate.goog proxy returns Chinese HTML to OkHttp (which doesn't run JS), so fetching from the proxy gives the same Chinese HTML but with extra injected junk. Fetching directly from timotxt.com is cleaner.

**Why use translate.goog for webview?** The WebView DOES run JavaScript. Loading the translate.goog URL with `_x_tr_*` params makes the proxy's translation script translate the page to English in the browser. This gives the user a "view in browser" experience that matches what they'd see on translate.goog in a desktop browser.

## The applicationId trap

The `applicationId` is `com.paras.noveldokusha`. The `namespace` (used
for `R` and `BuildConfig` generation) is `my.noveldokusha`. **These
intentionally differ.** Don't "fix" the mismatch.

The original upstream project used `applicationId = "my.noveldokusha"`.
The v2.2.x release line is published under `com.paras.noveldokusha`.
Changing the applicationId makes Android treat the new build as a
completely different app — users can't upgrade, and data from the old
app is orphaned.

Both release workflows (`release_foss.yml`, `release_full.yml`) fail-fast
if `applicationId != com.paras.noveldokusha`.

## The signing trap (v1 + v2)

AGP 8.2 defaults to v2-only signing when `minSdk >= 24`. v2-only APKs
break the package installer on many Android TV devices and some phones,
and can leave the OS package installer service in a broken state where
**all** subsequent APK installs fail.

**Always re-sign release APKs manually with `apksigner`:**

```bash
$ANDROID_HOME/build-tools/34.0.0/apksigner sign \
    --ks <keystore.jks> --ks-key-alias <alias> \
    --ks-pass pass:<pass> --key-pass pass:<pass> \
    --v1-signing-enabled true --v2-signing-enabled true \
    --v3-signing-enabled false --v4-signing-enabled false \
    --min-sdk-version 23 \
    --out <signed.apk> <unsigned.apk>
```

Verify: `apksigner verify --verbose --min-sdk-version 23 <apk>` — both
v1 and v2 must show `true`. The `--min-sdk-version 23` flag on verify
is critical (without it, apksigner skips v1 verification when v2 is
present and minSdk >= 24).

## The build environment

- **JDK**: 17 (Temurin recommended). JDK 21 causes `Unsupported class
  file major version 65` errors in R8 and random Kotlin daemon crashes.
- **AGP**: 8.2.2. Don't upgrade without testing — AGP 8.3+ requires
  Gradle 8.4+, which changes task graph semantics.
- **Gradle**: 8.2 (wrapper included). Don't upgrade to 9.x — AGP 8.2
  uses deprecated APIs that Gradle 9 removes.
- **Kotlin**: 1.9.23. Don't upgrade to 2.x — K2 compiler breaks KSP
  plugins.
- **Material 3**: 1.2.1. Don't upgrade to 1.3+ without checking
  `MenuAnchorType` compatibility (see BUILD.md §6.4).

Build command (from project root):

```bash
./gradlew assembleFullRelease \
  --no-daemon \
  --no-configuration-cache \
  --console=plain \
  -x lintVitalAnalyzeRelease \
  -x lintAnalyzeFullRelease
```

The `-x lint*` flags skip lint (which OOMs on machines with <4GB RAM).
The release workflows in CI run lint, but local builds can skip it.

Build time: 5-8 min clean, 2-3 min incremental, 5-8 min for R8 alone on
a 2-core runner.

## Adding a new source (checklist)

1. Create `scraper/src/main/java/my/noveldokusha/scraper/sources/<Name>.kt`
2. Implement `SourceInterface.Base` (URL-only) or `SourceInterface.Catalog`
3. Add string `source_name_<id>` in `strings/src/main/res/values/strings-no-translatable.xml`
4. Register in `Scraper.kt` → `sourcesList` set
5. If the source needs URL transformation, override `transformChapterUrl`
   and/or `transformWebviewUrl`
6. Add an instrumented test in
   `app/src/androidTest/.../SourcesCatalogTest.kt`
7. ProGuard keep rules in `app/proguard-rules.pro` already cover
   `my.noveldokusha.scraper.sources.**` via wildcard

Source IDs MUST be unique. Base URLs MUST end with `/`. Both are
unit-tested in `app/src/test/.../ScraperTest.kt`.

## Common mistakes to avoid

1. **Don't route all fetches through translate.goog.** The proxy returns
   Chinese HTML with injected `<font>` tags that break the text extractor.
   Fetch from timotxt.com directly; use translate.goog only for webview.

2. **Don't use `transformChapterUrl` for webview.** It returns the
   timotxt.com URL, which the browser renders as raw Chinese. Use
   `transformWebviewUrl` (which adds the `_x_tr_*` params).

3. **Don't change `applicationId`.** See "The applicationId trap" above.

4. **Don't ship a release APK without v1 signing.** See "The signing
   trap" above.

5. **Don't upgrade Kotlin / AGP / Gradle / Material 3** without
   testing the full build + R8 + signing.

6. **Don't use `GlobalScope`** — use the injected `AppCoroutineScope`
   or a `CoroutineScope(SupervisorJob() + Dispatchers.Default)` owned
   by a `@Singleton` or a ViewModel.

7. **Don't call `response.body.string()` without closing the response
   on exception.** Use `response.use { ... }` or extract the body
   string first, then parse.

8. **Don't iterate a `Cursor` without closing it.** Use the
   `Cursor?.asSequence()` extension in `core/CursorExtensions.kt` —
   it closes the cursor in a `finally` block.

## Testing

- **Unit tests**: `./gradlew test` — runs `ScraperTest.kt` (verifies
  source IDs unique, base URLs end with `/`, all sources compatible)
- **Instrumented tests**: `./gradlew connectedCheck` — runs
  `SourcesCatalogTest.kt` (verifies each source's catalog can be opened
  in the app — requires a device/emulator)
- **Manual smoke test**: open each source in Finder, browse the catalog,
  open a book, read a chapter, test TTS, test "open in webview"

## When in doubt

- Read `docs/ARCHITECTURE.md` for the big picture
- Read `docs/SOURCES.md` for source-specific details
- Read `docs/PERFORMANCE.md` for performance pitfalls
- Read `docs/TROUBLESHOOTING.md` for runtime issues
- Read `BUILD.md` for build/sign/CI issues
- Read the actual source code — the comments are detailed and explain
  the "why" behind non-obvious decisions
