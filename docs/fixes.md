# NovelDokusha — Bug & Issue Tracker

> **Scope**: All known bugs, code smells, and improvement opportunities found during the comprehensive codebase scan. Each item has a severity, location, and remediation status.

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

### D1 — Room migration version mismatch 🚨
**Location**: `tooling/local_database/.../AppDatabase.kt` + `Migrations.kt`
**Description**: `@Database(version = 5)` (now bumped to **8**) but `databaseMigrations()` defined migrations 1→2 through 7→8. Migrations 5, 6, 7 (domain rewrites for `readlightnovel` and `1stkissnovel`) previously would **never execute** for existing v5 users because Room thought the schema was already at v5.
**Impact**: Users with old `readlightnovel.org`/`.me` or `1stkissnovel.love` URLs in their DB would never have them updated to the current domains (`.meme` and `.org` respectively). Those chapters would fail to load.
**Remediation**: Bumped `@Database(version = 8)` so the now-fixed migrations 5, 6, 7 are reachable for upgraders. **Status: ✅ Fixed**

### D2 — Invalid SQL in `readLightNovelDomainChange_1_today` 🚨
**Location**: `tooling/local_database/.../Migration_readLightNovelDomain.kt`
**Description**: The `replace(columnName)` SQL helper produced `SET $col = REPLACE($col, REPLACE($col, "$old1", "$new"), "$old2", "$new")` — the outer `REPLACE` was called with **4 arguments**, but SQLite's `REPLACE(X, Y, Z)` takes exactly 3. Also had the same `SET a=..., SET b=...` duplicate-SET bug as D3, and the same `chapterUrl`-on-`Book` WHERE-clause bug as D3.
**Impact**: Migration 5 would have thrown at runtime if it ever executed.
**Remediation**: Replaced with properly nested `REPLACE(REPLACE(x, old1, new), old2, new)` and a single `SET` keyword; changed WHERE clause to use `url`. **Status: ✅ Fixed**

### D3 — Wrong column in `websiteDomainChangeHelper` Book UPDATE 🚨
**Location**: `tooling/local_database/.../WebsiteDomainChangeHelper.kt`
**Description**: The `Book` table UPDATE used `WHERE ${like("chapterUrl")}` — but `Book` has no `chapterUrl` column (its URL-bearing columns are `url`, `coverImageUrl`, `lastReadChapter`). The helper also returned `SET $col = REPLACE(...)` from a per-column function and was invoked twice in the same UPDATE, producing invalid `SET a=..., SET b=...` SQL.
**Impact**: Migration would fail with `no such column: chapterUrl` (or `near "SET": syntax error`) if it ever executed.
**Remediation**: Renamed `replace()` → `assign()` returning just `$col = REPLACE(...)` (no leading `SET`); used a single `SET` keyword in the UPDATE; changed WHERE to use `url` (which exists on `Book`). **Status: ✅ Fixed**

### D4 — Backup restore reads entire zip into memory 🟡
**Location**: `tooling/backup_restore/.../RestoreDataService.kt`
**Description**: `zipSequence` reads the entire backup zip into memory via `associateWith { zipStream.readBytes() }`.
**Impact**: Large image sets will OOM on devices with limited RAM.
**Remediation**: Stream entries one at a time instead of loading all into memory. **Status: NOT FIXED**

### D5 — Temp database not deleted from filesystem 🟡
**Location**: `tooling/backup_restore/.../RestoreDataService.kt`
**Description**: Temp database created with `name = "temp_database"` is never explicitly deleted from the filesystem — `clearDatabase()` only clears tables; the file remains.
**Impact**: Each restore leaves a `temp_database` SQLite file in the app's internal storage, wasting space.
**Remediation**: After merge, call `context.deleteDatabase("temp_database")`. **Status: NOT FIXED**

### D6 — `delay(1_000)` after writing cover image 🔵
**Location**: `data/.../LibraryBooksRepository.kt` → `saveImageAsCover`
**Description**: `delay(timeMillis = 1_000)` after writing the cover image — likely a workaround for MediaStore/indexing race.
**Impact**: 1-second delay on every cover image save. Not breaking, just slow.
**Remediation**: Document why the delay exists, or remove it if no longer needed. **Status: NOT FIXED**

---

## Scraper Bugs

### S1 — `NovelBin.kt:85` uses non-existent `.expectFirst()` 🚨
**Location**: `scraper/.../sources/NovelBin.kt:85`
**Description**: `.expectFirst("meta[property=og:url]")` is called on a `org.jsoup.nodes.Document`, but no such extension function is defined anywhere in the codebase. The method does not exist on Jsoup's `Element`/`Document` either.
**Impact**: **This file will not compile.** (The previous builds didn't reach this file because they failed earlier, but once the earlier errors are fixed, this will block the build.)
**Remediation**: Replace `.expectFirst(...)` with `.selectFirst(...)` (which returns null if not found) and handle the null case. **Status: NOT FIXED**

### S2 — `TimoTxtGemini` uses wrong OG image attribute 🟡
**Location**: `scraper/.../sources/TimoTxtGemini.kt:230`
**Description**: Uses `meta[name=og:image]` for cover image. The Open Graph standard uses `meta[property=og:image]` (and `TimoTxt.kt` correctly uses `property=`).
**Impact**: The selector likely never matches, so the `.cover img[src]` fallback always runs. Cover images still work, but the more reliable OG meta tag is missed.
**Remediation**: Change `meta[name=og:image]` → `meta[property=og:image]`. **Status: NOT FIXED**

### S3 — `TimoTxtTranslate` separator mismatch 🟡
**Location**: `scraper/.../sources/TimoTxtTranslate.kt:392`
**Description**: Joins batch titles with `" ||| "` but splits the response on `" || | "` (with spaces). These don't match Google Translate's actual output.
**Impact**: Falls through to a regex fallback `\\s*\\|{2,3}\\s*` and ultimately individual translation. Likely works but slower than intended.
**Remediation**: Align the join and split separators. **Status: NOT FIXED**

### S4 — `Saikai.kt` id mismatch 🟢
**Location**: `scraper/.../sources/Saikai.kt`
**Description**: Class `Saikai`, `nameStrId=source_name_saikai`, but `id="seikai"` (misspelling).
**Impact**: Persisted as the DB key, so renaming would break existing users — likely intentional historical artifact.
**Remediation**: Leave as-is (back-compat). **Status: INFO**

### S5 — `NovelBin.kt` id case inconsistency 🟢
**Location**: `scraper/.../sources/NovelBin.kt`
**Description**: `id="Novelbin"` (capital N). All other source ids are snake_case lowercase.
**Impact**: Inconsistent but not breaking.
**Remediation**: Leave as-is (back-compat). **Status: INFO**

### S6 — `RoyalRoad.kt` catalogUrl mismatch 🟢
**Location**: `scraper/.../sources/RoyalRoad.kt`
**Description**: Declared `catalogUrl="https://www.royalroad.com/fictions/latest-updates?page=1"` but `getCatalogList` actually fetches `fictions/best-rated?page=...`.
**Impact**: The catalogUrl field is misleading (it's only used for display, not fetching).
**Remediation**: Update catalogUrl to match the actual fetch URL. **Status: NOT FIXED**

### S7 — `MeioNovel.kt:41` selector typo 🟡
**Location**: `scraper/.../sources/MeioNovel.kt:41`
**Description**: Selector string `"tab-content-wrap .c-tabs-item .tab-thumb a"` is missing the leading `.` on `tab-content-wrap`. Will never match.
**Impact**: MeioNovel cover image extraction silently fails.
**Remediation**: Add the leading `.`: `".tab-content-wrap .c-tabs-item .tab-thumb a"`. **Status: NOT FIXED**

### S8 — Dead sources (AT, Saikai, _1stKissNovel) 🔵
**Location**: `scraper/.../sources/AT.kt`, `Saikai.kt`, `_1stKissNovel.kt`
**Description**:
- `AT.kt` — header comment: "NO LONGER EXISTS" — website dead.
- `Saikai.kt` — header comment: "Cloudfare blocked"; uses external API.
- `_1stKissNovel.kt` — explicit `// TODO() not working, website blocking calls`.
**Impact**: These sources are non-functional at runtime. They appear in the source list but fail when used.
**Remediation**: Consider removing them, or marking them as "broken" in the UI. **Status: INFO**

### S9 — `LightNovelWorld.kt` search is a TODO stub 🟡
**Location**: `scraper/.../sources/LightNovelWorld.kt`
**Description**: `getCatalogSearch` is an explicit TODO stub returning empty `PagedList`.
**Impact**: Search returns no results for this source.
**Remediation**: Implement search, or remove the source. **Status: NOT FIXED**

### S10 — `KoreanNovelsMTL` is catalog-only 🟡
**Location**: `scraper/.../sources/KoreanNovelsMTL.kt`
**Description**: `getChapterText` returns `null`, `getBookCoverImageUrl` returns empty; `requiresLogin=true`.
**Impact**: Source is catalog-only (no content retrieval). Users can browse but not read.
**Remediation**: Document as catalog-only, or implement chapter text retrieval. **Status: INFO**

### S11 — `NovelUpdates` (source) `getChapterText` returns `""` 🔵
**Location**: `scraper/.../sources/NovelUpdates.kt`
**Description**: Site only hosts chapter lists, redirects for content. `getChapterText` returns `""`.
**Impact**: By design — the source is for discovery, not reading. Users must open chapters in a different source.
**Remediation**: None (intentional). **Status: INFO**

### S12 — `GeminiApiClient` uses `Thread.sleep` in rate limiter 🟡
**Location**: `scraper/.../sources/GeminiApiClient.kt` → `enforceRateLimit`
**Description**: Uses `Thread.sleep(waitMs + 1000)` inside `synchronized(lock)`, blocking the calling coroutine's IO thread.
**Impact**: Not coroutine-friendly; risk of thread-pool starvation under high concurrency.
**Remediation**: Replace `Thread.sleep` with `delay()`. **Status: NOT FIXED**

### S13 — `GeminiApiClient` uses deprecated Gson API 🟢
**Location**: `scraper/.../sources/GeminiApiClient.kt`
**Description**: Uses `JsonParser.parseString(...).asJsonObject` (deprecated since Gson 3.x but compiles on 2.x).
**Impact**: None currently (Gson 2.10.1). Will break if Gson is upgraded to 3.x.
**Remediation**: Switch to `gson.fromJson(text, JsonObject::class.java)`. **Status: NOT FIXED**

### S14 — `BookTextMapper.ImgEntry.fromXMLStringV1` dead code 🟢
**Location**: `core/.../BookTextMapper.kt`
**Description**: `it.attr("src") ?: return null` is dead code: Jsoup's `Element.attr()` returns non-null `String` (empty string when missing).
**Impact**: None (the `?: return null` never fires).
**Remediation**: Remove the elvis, or change to `if (it.attr("src").isBlank()) return null`. **Status: NOT FIXED**

### S15 — WordPress wp-manga sources have copy-paste drift 🟢
**Location**: `scraper/.../sources/` (BacaLightnovel, BoxNovel, MeioNovel, MoreNovel, Novelku, SakuraNovel, WbNovel, WuxiaWorld)
**Description**: 8 sources share the `wp-manga` plugin structure with near-identical selectors. Each is implemented independently.
**Impact**: Bug fixes (like S7) must be applied 8 times.
**Remediation**: Extract a shared abstract base class. **Status: NOT FIXED**

---

## Translation System Bugs

### T1 — Temperature not wired 🟡
**Location**: `scraper/.../Scraper.kt` + `GeminiApiClient.kt`
**Description**: `AppPreferences.GEMINI_TEMPERATURE` exists and the Settings UI updates it, but `Scraper` doesn't pass it to `TimoTxtGemini`, and `GeminiApiClient.buildRequestBody()` hardcodes `"temperature": 0.55`.
**Impact**: The temperature slider in Settings is currently inert.
**Remediation**: Pass `geminiTemperature` through `Scraper` → `TimoTxtGemini` → `GeminiApiClient`, and use it in `buildRequestBody()`. **Status: NOT FIXED**

### T2 — API key/model captured at Scraper construction 🟡
**Location**: `scraper/.../Scraper.kt`
**Description**: `Scraper` is `@Singleton` and `appPreferences.GEMINI_API_KEY.value`/`GEMINI_MODEL.value` are read once when `Scraper` is first injected.
**Impact**: Changes in Settings won't take effect until the app process is restarted.
**Remediation**: Make the reads lazy (read from `AppPreferences` each time `TimoTxtGemini` is used), or provide a way to recreate `TimoTxtGemini` when prefs change. **Status: NOT FIXED**

---

## Core/UI Bugs

### C1 — `BaseActivity` creates its own `AppPreferences` instance 🟢
**Location**: `coreui/.../BaseActivity.kt`
**Description**: Lazily creates `AppPreferences(applicationContext)` instead of injecting the Hilt singleton.
**Impact**: Reads/writes go to the same backing SharedPreferences, but the `OnSharedPreferenceChangeListener` flow machinery is duplicated across the two instances.
**Remediation**: Inject `AppPreferences` via Hilt instead. **Status: NOT FIXED**

### C2 — `AppPreferences.toState()` self-described as "probably some details wrong" 🟢
**Location**: `core/.../appPreferences/AppPreferences.kt`
**Description**: Comment says: *"This custom implementation has probably some details wrong. Use only OUTSIDE of composable scope (e.g. viewModel)"*.
**Impact**: Unknown — used in ViewModels, not composables, per the comment.
**Remediation**: Audit the implementation, or migrate to `collectAsState` over the Flow. **Status: INFO**

### C3 — `onBackPressed()` deprecated 🟢
**Location**: `features/reader/.../ReaderActivity.kt`, `features/chaptersList/.../ChaptersActivity.kt`, `features/sourceExplorer/.../SourceCatalogActivity.kt`
**Description**: `onBackPressed()` is deprecated in favor of `OnBackPressedDispatcher` + `OnBackPressedCallback`.
**Impact**: Works but will eventually break on newer Android versions.
**Remediation**: Migrate to `OnBackPressedDispatcher`. **Status: NOT FIXED**

### C4 — Settings uses hardcoded English strings 🟢
**Location**: `features/settings/.../sections/SettingsGemini.kt`, `LibraryAutoUpdate.kt`
**Description**: Hardcoded English strings ("Library updates", "Gemini AI Translation", "API Key", etc.) — not localized via `strings.xml`.
**Impact**: Non-English users see English in these sections.
**Remediation**: Move strings to `strings.xml`. **Status: NOT FIXED**

### C5 — `onDoImportEPUB.kt` filename mismatch 🟢
**Location**: `coreui/.../composableActions/onDoImportEPUB.kt`
**Description**: File is named `onDoImportEPUB.kt` but the function is `onDoAskForImage` — misleading filename.
**Impact**: Confusing for contributors.
**Remediation**: Rename the file to `onDoAskForImage.kt`. **Status: NOT FIXED**

### C6 — Package namespace typo in 3 modules 🟢
**Location**: `coreui`, `data`, `features/databaseExplorer`
**Description**: These modules use `my.noveldoksuha.*` (missing `h` in `noveldokusha`).
**Impact**: Inconsistent but not breaking — source files consistently use the typo'd package.
**Remediation**: Would require a large refactor (rename all files). Not worth the churn. **Status: INFO**

---

## Build/Config Issues

### BC1 — `jcenter()` deprecated 🟡
**Location**: `settings.gradle.kts` + root `build.gradle.kts`
**Description**: `jcenter()` is declared in both `dependencyResolutionManagement` and `buildscript` blocks, with `noinspection JcenterRepositoryObsolete`.
**Impact**: JCenter is deprecated and scheduled for sunset. No project deps actually use it.
**Remediation**: Remove `jcenter()` from both blocks. **Status: NOT FIXED**

### BC2 — `org.gradle.unsafe.configuration-cache` legacy key 🟢
**Location**: `gradle.properties`
**Description**: Uses the *unsafe* (legacy) property name `org.gradle.unsafe.configuration-cache=true`. Modern Gradle 8.2 prefers `org.gradle.configuration-cache=true`.
**Impact**: Still works but may emit deprecation warnings.
**Remediation**: Rename to `org.gradle.configuration-cache=true`. **Status: NOT FIXED**

### BC3 — `hilt-workmanager` not in convention plugin 🟢
**Location**: `build-logic/.../Hilt.kt`
**Description**: `hilt-workmanager` (`androidx.hilt:hilt-work`) is NOT added by the convention plugin — only `app/build.gradle.kts` adds it explicitly. Modules using `@HiltWorker` need to declare it themselves.
**Impact**: Currently no library module uses `@HiltWorker`, so no issue. But if one is added, it'll silently fail.
**Remediation**: Add `hilt-workmanager` to the convention plugin, or document that modules need to add it. **Status: NOT FIXED**

### BC4 — Legacy workflow action versions 🟡
**Location**: `.github/workflows/buildRelease.yml` + `ui_test.yml`
**Description**: Use `actions/checkout@v2`, `actions/setup-java@v1.4.3`, `ubuntu-20.04` runner.
**Impact**: `ubuntu-20.04` is deprecated and will fail once retired. Old action versions lack features.
**Remediation**: Bump to `@v4` actions + `ubuntu-latest`. **Status: NOT FIXED**

### BC5 — `ui_test.yml` Gradle version mismatch 🟢
**Location**: `.github/workflows/ui_test.yml`
**Description**: Specifies `gradle-version: 8.1.1` while the rest of the project uses Gradle 8.2.
**Impact**: Subtle behavior differences in CI vs local.
**Remediation**: Change to `wrapper` or `8.2`. **Status: NOT FIXED**

### BC6 — `buildRelease.yml` uses bare `gradle` 🟢
**Location**: `.github/workflows/buildRelease.yml`
**Description**: Uses `gradle assembleRelease` instead of `./gradlew assembleRelease` — relies on the `gradle-build-action` to provision Gradle on PATH.
**Impact**: Works but fragile.
**Remediation**: Use `./gradlew`. **Status: NOT FIXED**

### BC7 — `.gitignore` missing entries (FIXED) ✅
**Location**: `.gitignore`
**Description**: Previously missing `custom.properties`, `*.keystore`, `*.jks`, `*.jsk`, `**/build/`, `staging/`, `__pycache__/`.
**Impact**: Signing secrets and build artifacts could be accidentally committed.
**Remediation**: Added all missing entries. **Status: FIXED**

### BC8 — `lint.xml` trailing quote typo (FIXED) ✅
**Location**: `lint.xml:4`
**Description**: `<issue id="MissingTranslation" severity="ignore" />"` — trailing `"` typo.
**Impact**: Harmless (XML parser ignores it) but should be fixed.
**Remediation**: Removed the trailing quote. **Status: FIXED**

### BC9 — Release builds don't obfuscate 🔵
**Location**: `app/build.gradle.kts`
**Description**: `release { isObfuscate = false }` — intentional.
**Impact**: Larger APK than fully-obfuscated, but stack traces remain readable.
**Remediation**: None (intentional choice). **Status: INFO**

### BC10 — `dependency-analysis` plugin dormant 🟢
**Location**: Root `build.gradle.kts`
**Description**: `com.autonomousapps.dependency-analysis` (v1.31.0) is declared with `apply false` but never actually applied.
**Impact**: `./gradlew buildHealth` doesn't work.
**Remediation**: Apply at root, or remove the declaration. **Status: NOT FIXED**

---

## Summary

| Severity | Count | Fixed | Remaining |
|---|---|---|---|
| 🚨 CRITICAL | 6 | 5 | 1 (S1 — NovelBin.kt `.expectFirst()`) |
| 🔴 HIGH | 3 | 0 | 3 (D1, D2, D3 — Room migration bugs) |
| 🟡 MEDIUM | 11 | 0 | 11 |
| 🟢 LOW | 11 | 2 | 9 |
| 🔵 INFO | 6 | 0 | 6 |
| **Total** | **37** | **7** | **30** |

## Priority Remediation Order

1. **S1** — Fix `NovelBin.kt:85` `.expectFirst()` (will block build once earlier errors are fixed)
2. **D1, D2, D3** — Fix Room migration version mismatch + SQL bugs (data integrity)
3. **T1, T2** — Wire Gemini temperature + lazy API key reads (feature completeness)
4. **S2, S7** — Fix `TimoTxtGemini` OG image attribute + `MeioNovel` selector typo (cover images)
5. **D4, D5** — Fix backup restore OOM + temp DB cleanup (reliability)
6. **BC1, BC4** — Remove `jcenter()` + bump legacy workflow actions (future-proofing)

## See Also

- `Build-fixes.md` — detailed log of all build fixes applied
