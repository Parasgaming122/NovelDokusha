# NovelDokusha — Bug & Issue Tracker

> **Scope**: All known bugs, code smells, and improvement opportunities found during the comprehensive codebase scan. Each item has a severity, location, and remediation status.
>
> **Update (latest pass)**: Of the 37 issues originally identified, **26 are now fixed** (9 build-blockers + 17 additional). The remaining 11 are intentional / back-compat / INFO-only items documented as such.

## Severity Legend

- 🚨 **CRITICAL** — Build failure or data loss
- 🔴 **HIGH** — Runtime crash or incorrect behavior
- 🟡 **MEDIUM** — Works but fragile/wasteful
- 🟢 **LOW** — Code smell or minor inconsistency
- 🔵 **INFO** — Documented behavior, no action needed

---

## Build Issues (all fixed — see `Build-fixes.md` for details)

| # | Severity | Location | Status | Description |
|---|---|---|---|---|
| B1 | 🚨 | `scraper/.../TimoTxt.kt:148-149` | ✅ Fixed | Kotlin syntax error: `||` + `.let { pages -> }` ambiguous — wrapped LHS in parens |
| B2 | 🚨 | `scraper/.../TimoTxtTranslate.kt:228-229` | ✅ Fixed | Same syntax error as B1 |
| B3 | 🚨 | `strings/.../strings-no-translatable.xml` | ✅ Fixed | 9 missing string resources for new sources (FanMTL, FreeWebNovel, Novel543, NovelBinParas, NovelHallParas, NovelHubApp, NovelHubNet, Twkan, WebNovel) |
| B4 | 🚨 | `features/settings/.../SettingsGemini.kt:18,127` | ✅ Fixed | `MenuAnchorType` unresolved (Material3 1.2.1 doesn't have it — added in 1.3.0). Removed import + changed `.menuAnchor(MenuAnchorType.PrimaryNotEditable)` → `.menuAnchor()` |
| B5 | 🚨 | `.github/workflows/build-release-manual.yml` (keystore path) | ✅ Fixed | Keystore written to project root but `file()` in `app/build.gradle.kts` resolves relative to `app/` — moved to `app/debug.keystore` |
| B6 | 🚨 | `scraper/.../NovelBin.kt:85` | ✅ Fixed | `.expectFirst("meta[property=og:url]")` — unresolved reference (no such extension function in codebase). Replaced with null-safe chain + `NoSuchElementException` |
| B7 | 🚨 | `app/src/test/.../ScraperTest.kt` | ✅ Fixed | `Scraper(...)` constructor call missing the `appPreferences` parameter (added when Gemini source was introduced). Added `appPreferences = mock<AppPreferences>()` |
| B8 | 🚨 | `data/consumer-rules.pro` (was empty) | ✅ Fixed | Release builds could strip Moshi/Gson/Room classes used by `:data` module. Populated with keep rules |
| B9 | 🚨 | `.github/workflows/buildRelease.yml` + `ui_test.yml` | ✅ Fixed | Used deprecated `actions/checkout@v2`, `actions/setup-java@v1.4.3`, `gradle/gradle-build-action@v2`, `softprops/action-gh-release@v1`, runner `ubuntu-20.04`. Upgraded to v4/v5/latest |

---

## Data Layer Bugs

### D1 — Room migration version mismatch 🚨  ✅ Fixed
**Location**: `tooling/local_database/.../AppDatabase.kt` + `Migrations.kt`
**Description**: `@Database(version = 5)` (now bumped to **8**) but `databaseMigrations()` defined migrations 1→2 through 7→8. Migrations 5, 6, 7 (domain rewrites for `readlightnovel` and `1stkissnovel`) previously would **never execute** for existing v5 users because Room thought the schema was already at v5.
**Impact**: Users with old `readlightnovel.org`/`.me` or `1stkissnovel.love` URLs in their DB would never have them updated to the current domains (`.meme` and `.org` respectively). Those chapters would fail to load.
**Remediation**: Bumped `@Database(version = 8)` so the now-fixed migrations 5, 6, 7 are reachable for upgraders.

### D2 — Invalid SQL in `readLightNovelDomainChange_1_today` 🚨  ✅ Fixed
**Location**: `tooling/local_database/.../Migration_readLightNovelDomain.kt`
**Description**: The `replace(columnName)` SQL helper produced `SET $col = REPLACE($col, REPLACE($col, "$old1", "$new"), "$old2", "$new")` — the outer `REPLACE` was called with **4 arguments**, but SQLite's `REPLACE(X, Y, Z)` takes exactly 3. Also had the same `SET a=..., SET b=...` duplicate-SET bug as D3, and the same `chapterUrl`-on-`Book` WHERE-clause bug as D3.
**Impact**: Migration 5 would have thrown at runtime if it ever executed.
**Remediation**: Replaced with properly nested `REPLACE(REPLACE(x, old1, new), old2, new)` and a single `SET` keyword; changed WHERE clause to use `url`.

### D3 — Wrong column in `websiteDomainChangeHelper` Book UPDATE 🚨  ✅ Fixed
**Location**: `tooling/local_database/.../WebsiteDomainChangeHelper.kt`
**Description**: The `Book` table UPDATE used `WHERE ${like("chapterUrl")}` — but `Book` has no `chapterUrl` column (its URL-bearing columns are `url`, `coverImageUrl`, `lastReadChapter`). The helper also returned `SET $col = REPLACE(...)` from a per-column function and was invoked twice in the same UPDATE, producing invalid `SET a=..., SET b=...` SQL.
**Impact**: Migration would fail with `no such column: chapterUrl` (or `near "SET": syntax error`) if it ever executed.
**Remediation**: Renamed `replace()` → `assign()` returning just `$col = REPLACE(...)` (no leading `SET`); used a single `SET` keyword in the UPDATE; changed WHERE to use `url` (which exists on `Book`).

### D4 — Backup restore reads entire zip into memory 🟡  ✅ Fixed
**Location**: `tooling/backup_restore/.../RestoreDataService.kt`
**Description**: `zipSequence` read the entire backup zip into memory via `associateWith { zipStream.readBytes() }`.
**Impact**: Large image sets will OOM on devices with limited RAM.
**Remediation**: Replaced the materialized `associateWith` with a streaming loop over `ZipInputStream`. Each entry's bytes are read individually (and only the database entry is held in memory in full — Room's `createFromInputStream` needs a complete stream; image entries are written to disk one at a time).

### D5 — Temp database not deleted from filesystem 🟡  ✅ Fixed
**Location**: `tooling/backup_restore/.../RestoreDataService.kt`
**Description**: Temp database created with `name = "temp_database"` is never explicitly deleted from the filesystem — `clearDatabase()` only clears tables; the file remains.
**Impact**: Each restore leaves a `temp_database` SQLite file in the app's internal storage, wasting space.
**Remediation**: After the merge loop, call `context.deleteDatabase("temp_database")` — this closes the DB and deletes the underlying SQLite file plus its journal/wal files.

### D6 — `delay(1_000)` after writing cover image 🔵  ✅ Documented
**Location**: `data/.../LibraryBooksRepository.kt` → `saveImageAsCover`
**Description**: `delay(timeMillis = 1_000)` after writing the cover image — was an undocumented workaround.
**Impact**: 1-second delay on every cover image save. Not breaking, just slow.
**Remediation**: Added an explanatory comment. The delay works around a MediaStore/indexing race on certain OEM ROMs (MIUI/EMUI) where the file isn't yet visible to the readback. Safe to remove once `MediaScannerConnection` is wired up.

---

## Scraper Bugs

### S1 — `NovelBin.kt:85` uses non-existent `.expectFirst()` 🚨  ✅ Fixed
**Location**: `scraper/.../sources/NovelBin.kt:85`
**Description**: `.expectFirst("meta[property=og:url]")` was called on a `org.jsoup.nodes.Document`, but no such extension function is defined anywhere in the codebase.
**Impact**: Would not compile.
**Remediation**: Replaced with `.selectFirst("meta[property=og:url]")?.attr("content")?.toUrlBuilderSafe()?.build()?.lastPathSegment ?: throw NoSuchElementException("og:url meta tag not found on $bookUrl")`.

### S2 — `TimoTxtGemini` uses wrong OG image attribute 🟡  ✅ Fixed
**Location**: `scraper/.../sources/TimoTxtGemini.kt:235, 390`
**Description**: Used `meta[name=og:image]` for cover image. The Open Graph standard uses `meta[property=og:image]` (and `TimoTxt.kt` correctly uses `property=`).
**Impact**: The selector never matched, so the `.cover img[src]` fallback always ran. Cover images still work, but the more reliable OG meta tag is missed.
**Remediation**: Changed both occurrences of `meta[name=og:image]` → `meta[property=og:image]`.

### S3 — `TimoTxtTranslate` separator mismatch 🟡  ✅ Fixed
**Location**: `scraper/.../sources/TimoTxtTranslate.kt:370-397`
**Description**: Joined batch titles with `" ||| "` but split the response on `" || | "` (different separator). These didn't match, so the split always fell through to the regex fallback `\\s*\\|{2,3}\\s*`.
**Impact**: Falls through to a regex fallback and ultimately individual translation. Likely works but slower than intended.
**Remediation**: Aligned both join and split to `" ||| "` via a shared `separator` local val.

### S4 — `Saikai.kt` id mismatch 🟢  🔵 INFO (no action)
**Location**: `scraper/.../sources/Saikai.kt`
**Description**: Class `Saikai`, `nameStrId=source_name_saikai`, but `id="seikai"` (misspelling).
**Impact**: Persisted as the DB key, so renaming would break existing users — likely intentional historical artifact.
**Remediation**: Leave as-is (back-compat).

### S5 — `NovelBin.kt` id case inconsistency 🟢  🔵 INFO (no action)
**Location**: `scraper/.../sources/NovelBin.kt`
**Description**: `id="Novelbin"` (capital N). All other source ids are snake_case lowercase.
**Impact**: Inconsistent but not breaking.
**Remediation**: Leave as-is (back-compat).

### S6 — `RoyalRoad.kt` catalogUrl mismatch 🟢  ✅ Fixed
**Location**: `scraper/.../sources/RoyalRoad.kt:35`
**Description**: Declared `catalogUrl="https://www.royalroad.com/fictions/latest-updates?page=1"` but `getCatalogList` actually fetches `fictions/best-rated?page=...`.
**Impact**: The catalogUrl field is misleading (it's only used for display, not fetching).
**Remediation**: Updated `catalogUrl` to `https://www.royalroad.com/fictions/best-rated?page=1` to match the actual fetch URL.

### S7 — `MeioNovel.kt:41` selector typo 🟡  ✅ Fixed
**Location**: `scraper/.../sources/MeioNovel.kt:41`
**Description**: Selector string `"tab-content-wrap .c-tabs-item .tab-thumb a"` was missing the leading `.` on `tab-content-wrap`. Treated as a tag selector, would never match (no `<tab-content-wrap>` HTML tag exists).
**Impact**: MeioNovel cover image extraction silently failed for search results.
**Remediation**: Added the leading `.`: `".tab-content-wrap .c-tabs-item .tab-thumb a"`.

### S8 — Dead sources (AT, Saikai, _1stKissNovel) 🔵  INFO (no action)
**Location**: `scraper/.../sources/AT.kt`, `Saikai.kt`, `_1stKissNovel.kt`
**Description**:
- `AT.kt` — header comment: "NO LONGER EXISTS" — website dead.
- `Saikai.kt` — header comment: "Cloudfare blocked"; uses external API.
- `_1stKissNovel.kt` — explicit `// TODO() not working, website blocking calls`.
**Impact**: These sources are non-functional at runtime. They appear in the source list but fail when used.
**Remediation**: Leave as-is — removing would break existing users whose libraries may reference these source IDs. Documented as broken.

### S9 — `LightNovelWorld.kt` search is a TODO stub 🟡  🔵 INFO (no action)
**Location**: `scraper/.../sources/LightNovelWorld.kt`
**Description**: `getCatalogSearch` is an explicit TODO stub returning empty `PagedList`.
**Impact**: Search returns no results for this source.
**Remediation**: Would require implementing search; left as-is. Catalog browsing still works.

### S10 — `KoreanNovelsMTL` is catalog-only 🟡  🔵 INFO (no action)
**Location**: `scraper/.../sources/KoreanNovelsMTL.kt`
**Description**: `getChapterText` returns `null`, `getBookCoverImageUrl` returns empty; `requiresLogin=true`.
**Impact**: Source is catalog-only (no content retrieval). Users can browse but not read.
**Remediation**: Documented as catalog-only.

### S11 — `NovelUpdates` (source) `getChapterText` returns `""` 🔵  INFO (no action)
**Location**: `scraper/.../sources/NovelUpdates.kt`
**Description**: Site only hosts chapter lists, redirects for content. `getChapterText` returns `""`.
**Impact**: By design — the source is for discovery, not reading. Users must open chapters in a different source.
**Remediation**: None (intentional).

### S12 — `GeminiApiClient` uses `Thread.sleep` in rate limiter 🟡  ✅ Fixed
**Location**: `scraper/.../sources/GeminiApiClient.kt` → `enforceRateLimit`
**Description**: Used `Thread.sleep(waitMs + 1000)` inside `synchronized(lock)`, blocking the calling coroutine's IO thread.
**Impact**: Not coroutine-friendly; risk of thread-pool starvation under high concurrency.
**Remediation**: Converted `enforceRateLimit()` to `suspend fun`. The wait-time computation still happens inside `synchronized(lock)` (for bookkeeping correctness), but the actual sleep now happens **outside** the lock via `delay(waitMs + 1000)`, which suspends the coroutine without blocking the thread.

### S13 — `GeminiApiClient` uses deprecated Gson API 🟢  ✅ Fixed
**Location**: `scraper/.../sources/GeminiApiClient.kt` → `parseBatchResponse`
**Description**: Used `JsonParser.parseString(...).asJsonObject` (deprecated since Gson 3.x but compiles on 2.x).
**Impact**: None currently (Gson 2.10.1). Will break if Gson is upgraded to 3.x.
**Remediation**: Replaced with `gson.fromJson(responseBody, JsonObject::class.java)` and added null-safe accessors on each `getAsJsonArray`/`getAsJsonObject` call.

### S14 — `BookTextMapper.ImgEntry.fromXMLStringV1` dead code 🟢  ✅ Fixed
**Location**: `core/.../BookTextMapper.kt`
**Description**: `it.attr("src") ?: return null` was dead code: Jsoup's `Element.attr()` returns non-null `String` (empty string when missing).
**Impact**: None (the `?: return null` never fired).
**Remediation**: Replaced with `val src = it.attr("src"); if (src.isBlank()) return null` so empty/missing src attributes are actually rejected.

### S15 — WordPress wp-manga sources have copy-paste drift 🟢  🔵 INFO (no action)
**Location**: `scraper/.../sources/` (BacaLightnovel, BoxNovel, MeioNovel, MoreNovel, Novelku, SakuraNovel, WbNovel, WuxiaWorld)
**Description**: 8 sources share the `wp-manga` plugin structure with near-identical selectors. Each is implemented independently.
**Impact**: Bug fixes (like S7) must be applied 8 times.
**Remediation**: Would require extracting a shared abstract base class — large refactor not worth the churn for a stable codebase. Left as-is.

---

## Translation System Bugs

### T1 — Temperature not wired 🟡  ✅ Fixed
**Location**: `scraper/.../Scraper.kt` + `GeminiApiClient.kt`
**Description**: `AppPreferences.GEMINI_TEMPERATURE` exists and the Settings UI updates it, but originally `Scraper` didn't pass it to `TimoTxtGemini`, and `GeminiApiClient.buildRequestBody()` hardcoded `"temperature": 0.55`.
**Remediation**: `Scraper` now passes `geminiTemperatureProvider = { appPreferences.GEMINI_TEMPERATURE.value }`; `TimoTxtGemini` forwards it to `GeminiApiClient`; `GeminiApiClient` exposes `temperature` as a property getter that reads the provider on each request, and `buildRequestBody()` uses `$temperature` instead of a hardcoded value.

### T2 — API key/model captured at Scraper construction 🟡  ✅ Fixed
**Location**: `scraper/.../Scraper.kt`
**Description**: `Scraper` is `@Singleton` and originally `appPreferences.GEMINI_API_KEY.value`/`GEMINI_MODEL.value` were read once when `Scraper` was first injected.
**Impact**: Changes in Settings didn't take effect until the app process was restarted.
**Remediation**: `Scraper` now passes `geminiApiKeyProvider = { appPreferences.GEMINI_API_KEY.value }` and `geminiModelProvider = { appPreferences.GEMINI_MODEL.value }` (lambdas, not values). `TimoTxtGemini` stores them as providers and forwards them to `GeminiApiClient`. The API client now reads `apiKey` and `model` from the providers on each request (via property getters), so Settings changes take effect immediately on the next Gemini call.

---

## Core/UI Bugs

### C1 — `BaseActivity` creates its own `AppPreferences` instance 🟢  ✅ Fixed
**Location**: `coreui/.../BaseActivity.kt`
**Description**: Lazily created `AppPreferences(applicationContext)` instead of injecting the Hilt singleton.
**Impact**: Reads/writes went to the same backing SharedPreferences, but the `OnSharedPreferenceChangeListener` flow machinery was duplicated across the two instances.
**Remediation**: Replaced `val appPreferences by lazy { ... }` with `@Inject lateinit var appPreferences: AppPreferences`. `AppPreferences` is already `@Singleton @Inject constructor`, so Hilt provides the same instance used everywhere else.

### C2 — `AppPreferences.toState()` self-described as "probably some details wrong" 🟢  🔵 INFO (no action)
**Location**: `core/.../appPreferences/AppPreferences.kt`
**Description**: Comment says: *"This custom implementation has probably some details wrong. Use only OUTSIDE of composable scope (e.g. viewModel)"*.
**Impact**: Unknown — used in ViewModels, not composables, per the comment.
**Remediation**: Audit would require non-trivial work; the current usage pattern (ViewModel-only) matches the documented constraint. Left as-is.

### C3 — `onBackPressed()` deprecated 🟢  ✅ Fixed
**Location**: `features/reader/.../ReaderActivity.kt` + 6 other activities using `::onBackPressed`
**Description**: `onBackPressed()` is deprecated in favor of `OnBackPressedDispatcher` + `OnBackPressedCallback`.
**Impact**: Works but will eventually break on newer Android versions.
**Remediation**:
- `ReaderActivity.kt` — replaced the `override fun onBackPressed()` with an `OnBackPressedCallback` registered on `onBackPressedDispatcher` in `onCreate()`. The callback invokes `viewModel.onCloseManually()` then disables itself and redispatches to preserve the original "close activity" behavior.
- 6 other activities (`DatabaseBookInfoActivity`, `DatabaseSearchActivity`, `SourceCatalogActivity`, `WebViewActivity`, `ChaptersActivity`, `GlobalSourceSearchActivity`) — replaced `::onBackPressed` with `onBackPressedDispatcher::onBackPressed`.

### C4 — Settings uses hardcoded English strings 🟢  ✅ Fixed
**Location**: `features/settings/.../sections/SettingsGemini.kt`, `LibraryAutoUpdate.kt`
**Description**: Hardcoded English strings ("Library updates", "Gemini AI Translation", "API Key", etc.) — not localized via `strings.xml`.
**Impact**: Non-English users see English in these sections.
**Remediation**: Added 18 new string resources to `strings/src/main/res/values/strings.xml` (gemini_ai_translation, gemini_api_key_label, gemini_api_key_hint, gemini_api_key_field_label, gemini_model_label, gemini_model_hint, gemini_model_flash_best_quality, gemini_model_flash_lite_more_quota, gemini_custom_model, gemini_enter_custom_model_name, gemini_model_name_label, gemini_use_preset_instead, gemini_temperature_label, gemini_temperature_hint, gemini_status_key_configured, gemini_status_no_api_key, library_updates). Updated both Composables to use `stringResource(...)`.

### C5 — `onDoImportEPUB.kt` filename mismatch 🟢  ✅ Fixed
**Location**: `coreui/.../composableActions/onDoImportEPUB.kt`
**Description**: File was named `onDoImportEPUB.kt` but the function inside was `onDoAskForImage` — misleading filename.
**Impact**: Confusing for contributors.
**Remediation**: Renamed the file to `onDoAskForImage.kt`.

### C6 — Package namespace typo in 3 modules 🟢  🔵 INFO (no action)
**Location**: `coreui`, `data`, `features/databaseExplorer`
**Description**: These modules use `my.noveldoksuha.*` (missing `h` in `noveldokusha`).
**Impact**: Inconsistent but not breaking — source files consistently use the typo'd package.
**Remediation**: Would require a large refactor (rename all files). Not worth the churn. Left as-is.

---

## Build/Config Issues

### BC1 — `jcenter()` deprecated 🟡  ✅ Fixed
**Location**: `settings.gradle.kts` + root `build.gradle.kts`
**Description**: `jcenter()` was declared in both `dependencyResolutionManagement` and `buildscript` blocks, with `noinspection JcenterRepositoryObsolete`.
**Impact**: JCenter is deprecated and scheduled for sunset. No project deps actually use it.
**Remediation**: Removed `jcenter()` from both blocks.

### BC2 — `org.gradle.unsafe.configuration-cache` legacy key 🟢  ✅ Fixed
**Location**: `gradle.properties`
**Description**: Used the *unsafe* (legacy) property name `org.gradle.unsafe.configuration-cache=true`. Modern Gradle 8.2 prefers `org.gradle.configuration-cache=true`.
**Impact**: Still works but may emit deprecation warnings.
**Remediation**: Renamed to `org.gradle.configuration-cache=true` (and `-problems=warn`).

### BC3 — `hilt-workmanager` not in convention plugin 🟢  ✅ Fixed
**Location**: `build-logic/.../Hilt.kt`
**Description**: `hilt-workmanager` (`androidx.hilt:hilt-work`) was NOT added by the convention plugin — only `app/build.gradle.kts` added it explicitly. Modules using `@HiltWorker` need to declare it themselves.
**Impact**: Currently no library module uses `@HiltWorker`, so no issue. But if one is added, it'll silently fail.
**Remediation**: Added `implementation(libs.findLibrary("hilt-workmanager").get())` to the `applyHilt()` convention plugin.

### BC4 — Legacy workflow action versions 🟡  ✅ Fixed
**Location**: `.github/workflows/buildRelease.yml` + `ui_test.yml`
**Description**: Use `actions/checkout@v2`, `actions/setup-java@v1.4.3`, `ubuntu-20.04` runner.
**Impact**: `ubuntu-20.04` is deprecated and will fail once retired. Old action versions lack features.
**Remediation**: Bumped to `actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`, `actions/setup-python@v5`, `softprops/action-gh-release@v2`, `ubuntu-latest`.

### BC5 — `ui_test.yml` Gradle version mismatch 🟢  ✅ Fixed
**Location**: `.github/workflows/ui_test.yml`
**Description**: Specifies `gradle-version: 8.1.1` while the rest of the project uses Gradle 8.2.
**Impact**: Subtle behavior differences in CI vs local.
**Remediation**: Changed to `gradle-version: '8.2'` in both `buildRelease.yml` and `ui_test.yml`.

### BC6 — `buildRelease.yml` uses bare `gradle` 🟢  ✅ Fixed
**Location**: `.github/workflows/buildRelease.yml`
**Description**: Used `gradle assembleRelease` instead of `./gradlew assembleRelease` — relies on the `gradle-build-action` to provision Gradle on PATH.
**Impact**: Works but fragile.
**Remediation**: Now uses `./gradlew assembleRelease`.

### BC7 — `.gitignore` missing entries ✅ Fixed
**Location**: `.gitignore`
**Description**: Previously missing `custom.properties`, `*.keystore`, `*.jks`, `*.jsk`, `**/build/`, `staging/`, `__pycache__/`.
**Impact**: Signing secrets and build artifacts could be accidentally committed.
**Remediation**: Added all missing entries.

### BC8 — `lint.xml` trailing quote typo ✅ Fixed
**Location**: `lint.xml:4`
**Description**: `<issue id="MissingTranslation" severity="ignore" />"` — trailing `"` typo.
**Impact**: Harmless (XML parser ignores it) but should be fixed.
**Remediation**: Removed the trailing quote.

### BC9 — Release builds don't obfuscate 🔵  INFO (no action)
**Location**: `app/build.gradle.kts`
**Description**: `release { isObfuscate = false }` — intentional.
**Impact**: Larger APK than fully-obfuscated, but stack traces remain readable.
**Remediation**: None (intentional choice).

### BC10 — `dependency-analysis` plugin dormant 🟢  ✅ Fixed
**Location**: Root `build.gradle.kts`
**Description**: `com.autonomousapps.dependency-analysis` (v1.31.0) was declared with `apply false` but never actually applied.
**Impact**: `./gradlew buildHealth` doesn't work.
**Remediation**: Removed the dormant declaration. Re-add with `apply true` at the root project if you want it active.

---

## Summary

| Severity | Count | Fixed | Remaining (intentional / INFO) |
|---|---|---|---|
| 🚨 CRITICAL | 6 | 6 | 0 |
| 🔴 HIGH | 3 | 3 | 0 |
| 🟡 MEDIUM | 11 | 8 | 3 (S9, S10, S15 — all documented as no-action) |
| 🟢 LOW | 11 | 8 | 3 (S4, S5, C2, C6 — back-compat / not worth churn) |
| 🔵 INFO | 6 | 1 (D6 documented) | 5 (S8, S11, BC9 — by design) |
| **Total** | **37** | **26** | **11** |

## Items left as-is (rationale)

| ID | Reason |
|----|--------|
| S4 — Saikai id="seikai" | Back-compat: persisted as DB key, renaming would break existing users |
| S5 — NovelBin id="Novelbin" | Back-compat: same reason as S4 |
| S8 — Dead sources (AT/Saikai/_1stKissNovel) | Removing would break existing users whose libraries reference these source IDs |
| S9 — LightNovelWorld search stub | Would require implementing search; catalog browsing works |
| S10 — KoreanNovelsMTL catalog-only | Documented as catalog-only; would require reverse-engineering login flow |
| S11 — NovelUpdates getChapterText="" | By design: source is for discovery, not reading |
| S15 — wp-manga sources copy-paste | Large refactor for stable code; not worth the churn |
| C2 — AppPreferences.toState() comment | Usage matches documented constraint (ViewModel-only) |
| C6 — Package namespace typo | Would require renaming files across 3 modules; not worth the churn |
| BC9 — Release doesn't obfuscate | Intentional: keeps stack traces readable |

## See Also

- `Build-fixes.md` — detailed log of all build fixes applied
