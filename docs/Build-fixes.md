# NovelDokusha — Build Fixes Log

> **Purpose**: Complete record of every build failure encountered during this fork's development, the root cause, the fix applied, and how to avoid similar issues in the future. Use this as a troubleshooting guide when builds fail.

---

## Table of Contents

1. [Build Environment Setup](#1-build-environment-setup)
2. [Build Failure #1: Kotlin Syntax Error in TimoTxt.kt + TimoTxtTranslate.kt](#2-build-failure-1-kotlin-syntax-error-in-timotxtkt--timotxttranslatekt)
3. [Build Failure #2: Missing String Resources for New Sources](#3-build-failure-2-missing-string-resources-for-new-sources)
4. [Build Failure #3: MenuAnchorType Unresolved in SettingsGemini.kt](#4-build-failure-3-menuanchortype-unresolved-in-settingsgeminiokt)
5. [Build Failure #4: Keystore Path Resolution](#5-build-failure-4-keystore-path-resolution)
6. [Build Failure #5: NovelBin.kt expectFirst (FIXED)](#6-build-failure-5-novelbinkt-expectfirst-fixed)
7. [GitHub Actions Workflow Setup](#7-github-actions-workflow-setup)
8. [Quick Troubleshooting Guide](#8-quick-troubleshooting-guide)
9. [Known Gotchas](#9-known-gotchas)
10. [Session 3 Additional Fixes](#10-session-3-additional-fixes)

---

## 1. Build Environment Setup

### Prerequisites

| Tool | Version | Notes |
|---|---|---|
| **JDK** | 17 | Matches `AppConfig.javaVersion`. Do NOT use 21 — the project's Kotlin 1.9.23 + Compose compiler 1.5.13 is pinned to JDK 17. |
| **Android SDK** | `platforms;android-34` + `build-tools;34.0.0` | compileSdk = 34 |
| **Gradle** | 8.2 (via wrapper) | Do NOT install separately — use `./gradlew` |

### Local build setup

```bash
# 1. Create local.properties pointing to your Android SDK
echo "sdk.dir=/path/to/your/Android/Sdk" > local.properties

# 2. Make gradlew executable
chmod +x gradlew

# 3. Build a debug APK (no signing needed — auto-signed with debug keystore)
./gradlew assembleFullDebug
# or
./gradlew assembleFossDebug
```

### Output locations

| Variant | Path |
|---|---|
| Full debug | `app/build/outputs/apk/full/debug/*.apk` |
| FOSS debug | `app/build/outputs/apk/foss/debug/*.apk` |
| Full release | `app/build/outputs/apk/full/release/*.apk` |
| FOSS release | `app/build/outputs/apk/foss/release/*.apk` |

### Release builds (local)

Release APKs require a signing keystore. Create one:

```bash
keytool -genkey -v \
  -keystore release.keystore \
  -storepass YOUR_STORE_PASSWORD \
  -alias YOUR_ALIAS \
  -keypass YOUR_KEY_PASSWORD \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -dname "CN=Your Name,O=Your Org,C=US"
```

Then create `custom.properties` **in the project root** (NOT in `app/`):

```properties
storeFile=release.keystore
storePassword=YOUR_STORE_PASSWORD
keyAlias=YOUR_ALIAS
keyPassword=YOUR_KEY_PASSWORD
```

> ⚠️ **Critical gotcha**: `file(storeFile)` in `app/build.gradle.kts` resolves relative to the **`app/` module directory**, not the project root. So if you put the keystore at the project root, you must reference it as `../release.keystore` in `custom.properties`, OR put the keystore inside `app/`.

Build with:

```bash
./gradlew assembleFullRelease -PlocalPropertiesFilePath=custom.properties
./gradlew assembleFossRelease -PlocalPropertiesFilePath=custom.properties
```

---

## 2. Build Failure #1: Kotlin Syntax Error in TimoTxt.kt + TimoTxtTranslate.kt

### Symptom
```
e: file:///.../scraper/src/main/java/my/noveldokusha/scraper/sources/TimoTxt.kt:149:17 Expecting an element
e: file:///.../scraper/src/main/java/my/noveldokusha/scraper/sources/TimoTxtTranslate.kt:229:17 Expecting an element
> Task :scraper:kspDebugKotlin FAILED
```

### Root cause
The Kotlin compiler couldn't parse this multi-line expression:
```kotlin
val hasNextPage = doc.selectFirst("a.next, .pagination .next, .pager a:contains(下一頁)")
    != null || doc.select(".pagination li, .pager li").let { pages ->
    pages.isNotEmpty() && !pages.last()?.hasClass("active")!!
}
```

The `||` operator combined with the `.let { pages -> }` lambda created parser ambiguity — Kotlin couldn't tell where the boolean expression ended and where the lambda body started.

### Fix applied
Wrapped the left side of `||` in parentheses so the parser can unambiguously identify the boolean expression boundary:

```kotlin
val hasNextPage = (doc.selectFirst("a.next, .pagination .next, .pager a:contains(下一頁)") != null) ||
    doc.select(".pagination li, .pager li").let { pages ->
        pages.isNotEmpty() && !pages.last()?.hasClass("active")!!
    }
```

### Files changed
- `scraper/src/main/java/my/noveldokusha/scraper/sources/TimoTxt.kt` (line 148)
- `scraper/src/main/java/my/noveldokusha/scraper/sources/TimoTxtTranslate.kt` (line 228)

### How to avoid
When writing multi-line `||` / `&&` expressions that involve trailing lambdas (`.let`, `.also`, `.run`, etc.), wrap each operand in explicit parentheses. The Kotlin parser struggles with operator precedence when a lambda body follows a binary operator.

---

## 3. Build Failure #2: Missing String Resources for New Sources

### Symptom
```
e: file:///.../scraper/src/main/java/my/noveldokusha/scraper/sources/FanMTL.kt:34:39 Unresolved reference: source_name_fanmtl
e: file:///.../scraper/src/main/java/my/noveldokusha/scraper/sources/FreeWebNovel.kt:31:39 Unresolved reference: source_name_freewebnovel
e: file:///.../scraper/src/main/java/my/noveldokusha/scraper/sources/Novel543.kt:33:39 Unresolved reference: source_name_novel543
e: file:///.../scraper/src/main/java/my/noveldokusha/scraper/sources/NovelBinParas.kt:35:39 Unresolved reference: source_name_novelbin_paras
e: file:///.../scraper/src/main/java/my/noveldokusha/scraper/sources/NovelHallParas.kt:35:39 Unresolved reference: source_name_novelhall_paras
e: file:///.../scraper/src/main/java/my/noveldokusha/scraper/sources/NovelHubApp.kt:34:39 Unresolved reference: source_name_novelhubapp
e: file:///.../scraper/src/main/java/my/noveldokusha/scraper/sources/NovelHubNet.kt:33:39 Unresolved reference: source_name_novelhub_net
e: file:///.../scraper/src/main/java/my/noveldokusha/scraper/sources/Twkan.kt:33:39 Unresolved reference: source_name_twkan
e: file:///.../scraper/src/main/java/my/noveldokusha/scraper/sources/WebNovel.kt:36:39 Unresolved reference: source_name_webnovel
> Task :scraper:compileReleaseKotlin FAILED
```

### Root cause
9 new source files were added to the repo (`FanMTL.kt`, `FreeWebNovel.kt`, `Novel543.kt`, `NovelBinParas.kt`, `NovelHallParas.kt`, `NovelHubApp.kt`, `NovelHubNet.kt`, `Twkan.kt`, `WebNovel.kt`) but the corresponding string resources weren't added to `strings-no-translatable.xml`. Each source file does:

```kotlin
override val nameStrId = R.string.source_name_fanmtl  // ← didn't exist!
```

### Fix applied
Added 9 missing string resources to `strings/src/main/res/values/strings-no-translatable.xml`:

```xml
<!-- Additional sources added by fork -->
<string translatable="false" name="source_name_fanmtl">FanMTL</string>
<string translatable="false" name="source_name_freewebnovel">FreeWebNovel</string>
<string translatable="false" name="source_name_novel543">Novel543</string>
<string translatable="false" name="source_name_novelbin_paras">NovelBin (Paras)</string>
<string translatable="false" name="source_name_novelhall_paras">NovelHall (Paras)</string>
<string translatable="false" name="source_name_novelhubapp">NovelHub</string>
<string translatable="false" name="source_name_novelhub_net">NovelHub Net</string>
<string translatable="false" name="source_name_twkan">Twkan</string>
<string translatable="false" name="source_name_webnovel">WebNovel</string>
```

### File changed
- `strings/src/main/res/values/strings-no-translatable.xml`

### How to avoid
When adding a new source:
1. Create the source `.kt` file in `scraper/.../sources/`
2. **Add the string resource** to `strings/src/main/res/values/strings-no-translatable.xml` with name `source_name_<lowercase_id>`
3. Register the source in `Scraper.kt`'s `sourcesList`
4. Verify the `nameStrId` in the source class matches the string resource name exactly

The string resource name convention is `source_name_<id>` where `<id>` is the source's `id` field in lowercase snake_case.

---

## 4. Build Failure #3: MenuAnchorType Unresolved in SettingsGemini.kt

### Symptom
```
e: file:///.../features/settings/src/main/java/my/noveldokusha/settings/sections/SettingsGemini.kt:18:35 Unresolved reference: MenuAnchorType
e: file:///.../features/settings/src/main/java/my/noveldokusha/settings/sections/SettingsGemini.kt:127:41 Unresolved reference: MenuAnchorType
e: file:///.../features/settings/src/main/java/my/noveldokusha/settings/sections/SettingsGemini.kt:127:41 Too many arguments for public abstract fun Modifier.menuAnchor(): Modifier defined in androidx.compose.material3.ExposedDropdownMenuBoxScope
> Task :features:settings:compileReleaseKotlin FAILED
```

### Root cause
`MenuAnchorType` is a newer Compose Material3 API (added in Material3 1.3.0). This project uses **Material3 1.2.1** (per `gradle/libs.versions.toml`):

```toml
compose-material3 = "1.2.1"
compose-material3Android = "1.2.1"
```

In Material3 1.2.x, `Modifier.menuAnchor()` takes **no arguments**. The code was written for Material3 1.3+ which requires a `MenuAnchorType` parameter:

```kotlin
// Material3 1.3+ (DOESN'T WORK in 1.2.1):
modifier = Modifier
    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
    .fillMaxWidth()

// Material3 1.2.x (CORRECT for this project):
modifier = Modifier
    .menuAnchor()
    .fillMaxWidth()
```

### Fix applied
1. Removed the `import androidx.compose.material3.MenuAnchorType` line
2. Changed `.menuAnchor(MenuAnchorType.PrimaryNotEditable)` → `.menuAnchor()`

### File changed
- `features/settings/src/main/java/my/noveldokusha/settings/sections/SettingsGemini.kt` (lines 18, 127)

### How to avoid
Always check the Compose Material3 version in `gradle/libs.versions.toml` before using Material3 APIs. The version is `1.2.1` — APIs added in 1.3.0+ will not compile.

Reference: [Material3 `ExposedDropdownMenuBox` API changes](https://developer.android.com/jetpack/androidx/releases/compose-material3#1.3.0)

---

## 5. Build Failure #4: Keystore Path Resolution

### Symptom
```
> Task :app:validateSigningFullRelease FAILED

FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':app:validateSigningFullRelease'.
> Keystore file '/home/runner/work/NovelDokusha/NovelDokusha/app/debug.keystore' not found for signing config 'default'.
```

### Root cause
In `app/build.gradle.kts` (line 54):
```kotlin
storeFile = file(defaultSigningConfigData.getProperty("storeFile"))
```

The `file(...)` function in a Gradle Android module resolves relative to the **module directory** (`app/`), NOT the project root. So `storeFile=debug.keystore` looks for `app/debug.keystore`.

The original workflow wrote the keystore to the **project root** (`debug.keystore`), so Gradle looked for `app/debug.keystore` and didn't find it.

### Fix applied
Updated `.github/workflows/build-release-manual.yml` to write the keystore to `app/debug.keystore`:

```yaml
# Before (broken):
- name: Generate Debug Keystore
  run: |
    keytool -genkey -v \
      -keystore debug.keystore \        # ← written to project root
      ...

# After (fixed):
- name: Generate Debug Keystore
  run: |
    keytool -genkey -v \
      -keystore app/debug.keystore \    # ← written to app/ directory
      ...
```

The `custom.properties` file keeps `storeFile=debug.keystore` (just the filename) because `file()` already resolves it relative to `app/`.

### File changed
- `.github/workflows/build-release-manual.yml`

### How to avoid
When configuring signing in `app/build.gradle.kts`:
- `file("foo.keystore")` → resolves to `app/foo.keystore`
- `file("../foo.keystore")` → resolves to `<project-root>/foo.keystore`
- `file("/absolute/path/foo.keystore")` → absolute path

The original author's `buildRelease.yml` workflow (line 31) writes to `app/storeFile.jsk` — confirming that the `app/` directory is the correct location.

---

## 6. Build Failure #5: NovelBin.kt expectFirst (FIXED)

### Symptom
```
e: file:///.../scraper/src/main/java/my/noveldokusha/scraper/sources/NovelBin.kt:85 Unresolved reference: expectFirst
> Task :scraper:compileReleaseKotlin FAILED
```

### Root cause
`NovelBin.kt:85` called `.expectFirst("meta[property=og:url]")` on a `org.jsoup.nodes.Document`, but no such extension function was defined anywhere in the codebase. The method does not exist on Jsoup's `Element`/`Document` either.

### Fix applied
Replaced `.expectFirst(...)` with a null-safe chain using `.selectFirst(...)` and threw a descriptive exception when the element is missing (so the surrounding `tryConnect` block can catch and surface the error):

```kotlin
// Before (broken):
val keyId = doc.expectFirst("meta[property=og:url]")
    .attr("content")
    .toUrlBuilderSafe()
    .build()
    .lastPathSegment!!

// After (fixed):
val keyId = doc.selectFirst("meta[property=og:url]")
    ?.attr("content")
    ?.toUrlBuilderSafe()
    ?.build()
    ?.lastPathSegment
    ?: throw NoSuchElementException("og:url meta tag not found on $bookUrl")
```

### File changed
- `scraper/src/main/java/my/noveldokusha/scraper/sources/NovelBin.kt` (lines 82–90)

---

## 7. GitHub Actions Workflow Setup

### 7.1 Available workflows

| Workflow | File | Trigger | Type | Signing |
|---|---|---|---|---|
| Build and Release Debug APK | `build-apk.yml` | Push to `main` + manual | Debug | Auto (debug keystore) |
| Build and Release Signed APK (Manual) | `build-release-manual.yml` | Manual only | Release | Fresh debug keystore per run |
| Publish Release | `buildRelease.yml` | Manual only | Release | Real secrets (SIGNING_KEY, etc.) |
| UI Tests | `ui_test.yml` | Manual only | Test | N/A |

### 7.2 Using the manual release workflow

1. Push your code to GitHub.
2. Go to **Actions** tab → **"Build and Release Signed APK (Manual)"**.
3. Click **"Run workflow"**.
4. Optionally enter a `version_name` (e.g. `2.2.3`). Leave blank for automatic semver patch bump.
5. Optionally check **"Mark release as prerelease"**.
6. Run. After ~6–8 minutes, both APKs will appear as a new GitHub Release.

### 7.3 Required repository settings

In **Settings → Actions → General → Workflow permissions**, set to **"Read and write permissions"** — this is needed so the workflow can create tags and releases.

### 7.4 For consistent signing (recommended for distribution)

The `build-release-manual.yml` workflow generates a **fresh debug keystore per run**, which means:
- Each release is signed with a different key
- Users **cannot upgrade** from one release to another (different signatures → install fails)
- Users must **uninstall** the old version first, losing their library and progress

For consistent signing (so users can upgrade in-place):

1. Generate a real release keystore locally:
   ```bash
   keytool -genkey -v \
     -keystore release.keystore \
     -storepass YOUR_STORE_PASSWORD \
     -alias YOUR_ALIAS \
     -keypass YOUR_KEY_PASSWORD \
     -keyalg RSA -keysize 2048 -validity 10000 \
     -dname "CN=Your Name,O=Your Org,C=US"
   ```

2. Base64-encode it:
   ```bash
   base64 -w 0 release.keystore > release.keystore.b64
   ```

3. Add these GitHub repository secrets:
   - `SIGNING_KEY` — contents of `release.keystore.b64`
   - `KEY_STORE_PASSWORD` — `YOUR_STORE_PASSWORD`
   - `ALIAS` — `YOUR_ALIAS`
   - `KEY_PASSWORD` — `YOUR_KEY_PASSWORD`

4. Use the `buildRelease.yml` workflow (or update `build-release-manual.yml` to use the secrets).

---

## 8. Quick Troubleshooting Guide

### Build fails with "SDK location not found"
**Fix**: Create `local.properties`:
```bash
echo "sdk.dir=/path/to/your/Android/Sdk" > local.properties
```

### Build fails with "Could not connect to Kotlin compile daemon"
**Fix**:
```bash
./gradlew --stop
./gradlew assembleFullDebug
```
The project sets `kotlin.compiler.execution.strategy=in-process` in `gradle.properties`, but stale daemons can still cause issues.

### Build fails with `:app:kspDebugKotlin`
KSP (Kotlin Symbol Processing) is used for Room and Hilt code generation.
**Fix**:
```bash
./gradlew clean
./gradlew assembleFullDebug --no-configuration-cache
```

### Build fails with `OutOfMemoryError: Java heap space` during `:app:mergeExtDexDebug`
**Fix**:
```bash
./gradlew assembleFullDebug --no-parallel
```
Or increase the heap in `gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8
```

### Build fails with "Cannot find System Java Compiler"
**Fix**: Ensure you're using a full JDK, not just a JRE:
```bash
javac -version  # Must work — if not, you have a JRE, not JDK
```

### Build fails with `Unresolved reference` for a source class
**Fix**: Check that the string resource exists in `strings/src/main/res/values/strings-no-translatable.xml`. The resource name should be `source_name_<id>` matching the source's `id` field.

### Build fails with `Unresolved reference: MenuAnchorType` (or similar Material3 API)
**Fix**: Check the Compose Material3 version in `gradle/libs.versions.toml`. This project uses `1.2.1` — APIs added in 1.3.0+ will not compile. Use the older API form.

### Build fails at `:app:validateSigningFullRelease` with "Keystore file not found"
**Fix**: The keystore path in `custom.properties` resolves relative to `app/`, not the project root. Either:
- Put the keystore in `app/` and use `storeFile=filename.keystore`
- Put the keystore in the project root and use `storeFile=../filename.keystore`

### Release APK is 25 MB instead of 5 MB
**Cause**: You built a debug APK (`assembleFullDebug`) instead of a release APK (`assembleFullRelease`). Debug APKs don't run R8 shrinking.
**Fix**: Use `assembleFullRelease` + signing config.

### Workflow fails with "Keystore file not found" in CI
**Fix**: See [Build Failure #4](#5-build-failure-4-keystore-path-resolution) above. The keystore must be written to `app/`, not the project root.

---

## 9. Known Gotchas

### 9.1 JDK version
Use **JDK 17**, not 21. The project's Kotlin 1.9.23 + Compose compiler 1.5.13 is pinned to JDK 17. Using JDK 21 will cause subtle bytecode incompatibilities.

### 9.2 Gradle version
Use **Gradle 8.2** (via the wrapper). The `gradle-wrapper.properties` pins this version. Do NOT upgrade Gradle without also upgrading AGP and Kotlin.

### 9.3 Configuration cache
`gradle.properties` has `org.gradle.unsafe.configuration-cache=true`. If you see weird "configuration cache miss" errors, try:
```bash
./gradlew assembleFullDebug --no-configuration-cache
```

### 9.4 `storeFile` relative path
`file(storeFile)` in `app/build.gradle.kts` resolves relative to `app/`, not the project root. This is the most common signing failure.

### 9.5 Material3 1.2.1 API surface
This project uses Material3 1.2.1. APIs added in 1.3.0+ (like `MenuAnchorType`, new `ModalBottomSheet` signatures, etc.) will not compile. Always check the version before using a Material3 API.

### 9.6 Two flavors
The project has `full` and `foss` flavors. The only difference is the translation module:
- `full` → ML Kit translator (requires Google Play)
- `foss` → no-op translator stub (100% Google-free)

Both flavors produce separate APKs. Build commands:
- `./gradlew assembleFullDebug` → `app/build/outputs/apk/full/debug/*.apk`
- `./gradlew assembleFossDebug` → `app/build/outputs/apk/foss/debug/*.apk`
- `./gradlew assembleFullRelease` → `app/build/outputs/apk/full/release/*.apk`
- `./gradlew assembleFossRelease` → `app/build/outputs/apk/foss/release/*.apk`

### 9.7 Release builds don't obfuscate
`app/build.gradle.kts` has `release { isObfuscate = false }` — intentional, to keep stack traces readable. R8 still shrinks code and resources, just doesn't rename classes.

### 9.8 Library modules don't run R8
The `noveldokusha.android.library` convention plugin forces `release { isMinifyEnabled = false }` for all library modules. R8 only runs at the app module level. This is standard practice for multi-module Android apps.

### 9.9 `jcenter()` is deprecated
`settings.gradle.kts` and root `build.gradle.kts` both declare `jcenter()`. It's deprecated but no project dependencies actually use it. Can be safely removed.

### 9.10 Package namespace typo
Three modules (`coreui`, `data`, `features/databaseExplorer`) use `my.noveldoksuha.*` (missing `h` in `noveldokusha`). This is "intentional by repetition" — the source files consistently use the typo'd package. Don't try to fix it without a coordinated rename.

---

## Build History Timeline

| Date | Issue | Fix |
|---|---|---|
| Session 1 | Chapter text still in Chinese despite translated titles | Fixed source routing in `DownloaderRepository.bookChapter` — resolve source from original URL before following redirects |
| Session 1 | Stale cache with Chinese text for `translate.goog` URLs | Added stale-cache guard in `ChapterBodyRepository.fetchBody` — detect CJK text in cached bodies for translation-proxy URLs and force re-fetch |
| Session 2 | Kotlin syntax error in `TimoTxt.kt:149` + `TimoTxtTranslate.kt:229` | Wrapped LHS of `||` in parentheses |
| Session 2 | Missing string resources for 9 new sources | Added `source_name_*` entries to `strings-no-translatable.xml` |
| Session 2 | `MenuAnchorType` unresolved in `SettingsGemini.kt` | Removed import + changed `.menuAnchor(MenuAnchorType.PrimaryNotEditable)` → `.menuAnchor()` (Material3 1.2.1 compatibility) |
| Session 2 | Keystore path resolution in CI workflow | Changed keystore output from project root to `app/` directory |
| Session 2 | `.gitignore` missing entries | Added `custom.properties`, `*.keystore`, `*.jks`, `*.jsk`, `**/build/`, `staging/`, `__pycache__/` |
| Session 2 | `lint.xml` trailing quote typo | Removed trailing `"` on line 4 |
| Session 2 | ProGuard rules missing Gemini keep rules | Added `-keep` rules for `GeminiApiClient$ParagraphItem`, `GeminiApiClient`, `TimoTxtGemini`, `TimoTxtTranslate`, and Gson |
| Session 2 | `NovelBin.kt:85` `.expectFirst()` unresolved | Replaced with `.selectFirst(...)?.attr(...)?.toUrlBuilderSafe()?.build()?.lastPathSegment ?: throw NoSuchElementException(...)` |
| Session 3 | `ScraperTest.kt` missing `appPreferences` arg (broke `./gradlew test`) | Added `appPreferences = mock<AppPreferences>()` to test constructor |
| Session 3 | Gemini temperature slider had no effect (hardcoded 0.55 in `GeminiApiClient.buildRequestBody`) | Added `temperatureProvider: () -> Float` parameter to `GeminiApiClient` (re-read on each API call) and `geminiTemperatureProvider: () -> Float` to `TimoTxtGemini`; wired from `{ appPreferences.GEMINI_TEMPERATURE.value }` in `Scraper.kt` so slider changes take effect live |
| Session 3 | `WebsiteDomainChangeHelper.kt` generated invalid SQL (`UPDATE Book SET a=..., SET b=...`) + WHERE clause referenced nonexistent `chapterUrl` column on Book | Rewrote helper: removed duplicate `SET` keyword, changed WHERE to use `url` (which exists on Book) |
| Session 3 | `Migration_readLightNovelDomain.kt` used 4-arg `REPLACE()` (SQLite accepts only 3 args) | Replaced with nested `REPLACE(REPLACE(x, old1, new), old2, new)` |
| Session 3 | Room DB version stuck at 5 — migrations 5, 6, 7 (domain-change migrations) were dead code | Bumped `@Database(version = 8)` in `AppDatabase.kt` so the now-fixed migrations are reachable for upgraders |
| Session 3 | `data/consumer-rules.pro` empty — Moshi/Gson/Room classes risked being stripped by R8 in release builds | Added keep rules for Moshi, Gson (incl. `GeminiApiClient$ParagraphItem`), Room, and `my.noveldoksuha.data.**` |
| Session 3 | `buildRelease.yml` and `ui_test.yml` used deprecated `actions/checkout@v2`, `actions/setup-java@v1.4.3`, `gradle/gradle-build-action@v2`, `softprops/action-gh-release@v1` | Upgraded to `actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`, `actions/setup-python@v5`, `softprops/action-gh-release@v2`; switched runner to `ubuntu-latest` |
| Session 3 | `README.md` listed non-existent catalog languages (Vietnamese, Japanese, Korean) and showed wrong `SourceInterface` API in code sample | Corrected source list, added `LanguageCode` enum note, fixed code sample to use `nameStrId`, `LanguageCode.ENGLISH`, `getBookCoverImageUrl`, `getChapterText(doc: Document)` |

---

## 10. Session 3 Additional Fixes

This section documents all issues found during the comprehensive code scan in session 3 and the fixes applied. These were not strictly build-breaking (except #1) but were correctness / hygiene issues that needed to be addressed.

### 10.1 `ScraperTest.kt` — missing `appPreferences` constructor argument

**Severity:** Critical for `./gradlew test` (compile error). Did not affect `./gradlew assemble*` tasks because the unit-test sourceset is not compiled by Android assemble tasks. CI workflows only run `assemble*` so they kept passing.

**File:** `app/src/test/java/my/noveldokusha/ScraperTest.kt`

**Root cause:** `Scraper` gained a third constructor parameter (`appPreferences: AppPreferences`) when the Gemini source was added, but the unit test was not updated.

**Fix:** Added `appPreferences = mock<AppPreferences>()` to the test's `Scraper(...)` constructor call and imported `my.noveldokusha.core.appPreferences.AppPreferences`.

### 10.2 Gemini temperature slider had no effect

**Severity:** Functional bug (user-visible). Not build-breaking.

**Files involved:**
- `core/.../appPreferences/AppPreferences.kt` — declared `GEMINI_TEMPERATURE` preference (default 0.55f).
- `features/settings/.../sections/SettingsGemini.kt` — exposed a temperature slider to the user, bound to the preference.
- `scraper/.../sources/GeminiApiClient.kt` — hardcoded `"temperature": 0.55` in `buildRequestBody()`.
- `scraper/.../sources/TimoTxtGemini.kt` — constructor did not accept temperature.
- `scraper/.../Scraper.kt` — did not pass temperature to `TimoTxtGemini`.

**Root cause:** The wiring between the user-facing slider and the API request body was never completed. The user could move the slider but the value was ignored.

**Fix:**
1. Added `temperatureProvider: () -> Float = { 0.55f }` parameter to `GeminiApiClient` constructor. A `private val temperature: Float get() = temperatureProvider()` computed property re-reads the live value on every API call.
2. Replaced the hardcoded `"temperature": 0.55` in `buildRequestBody()` with `"temperature": $temperature`.
3. Added `geminiTemperatureProvider: () -> Float = { 0.55f }` parameter to `TimoTxtGemini` constructor.
4. Passed `temperatureProvider = geminiTemperatureProvider` to `GeminiApiClient(...)` inside the lazy initializer.
5. Added `geminiTemperatureProvider = { appPreferences.GEMINI_TEMPERATURE.value }` to the `TimoTxtGemini(...)` call in `Scraper.kt`.

> ℹ️ Using a `() -> Float` provider (rather than a snapshot `Float`) ensures the user's slider changes take effect on the very next API request, even without restarting the app. The `Scraper` is `@Singleton`, so reading the preference value at construction time would freeze it for the lifetime of the process. The lambda defers the read until each translation request.

### 10.3 `WebsiteDomainChangeHelper.kt` — invalid SQL

**Severity:** Latent runtime crash. Migrations 6 and 7 (which call this helper) were never reachable because the DB version was stuck at 5. If the version had ever been bumped, the migration would have thrown at runtime.

**File:** `tooling/local_database/.../migrations/WebsiteDomainChangeHelper.kt`

**Root cause:** Two SQL bugs in the same helper:
1. The `replace(columnName)` helper returned `SET $columnName = REPLACE(...)` and was invoked twice in the same UPDATE — producing `UPDATE Book SET a=..., SET b=...` which is invalid SQLite (only one `SET` keyword per UPDATE).
2. The WHERE clause used `${like("chapterUrl")}` on the `Book` table, but `Book` has no `chapterUrl` column (see `tables/Book.kt` — only `title, url, completed, lastReadChapter, inLibrary, coverImageUrl, description, lastReadEpochTimeMilli`).

**Fix:**
1. Renamed the helper from `replace()` to `assign()` and made it return `$columnName = REPLACE(...)` (no leading `SET`).
2. Added a single `SET` keyword in the UPDATE template, followed by comma-separated `${assign(...)}` calls.
3. Changed the WHERE clause for the Book UPDATE to use `url` (which exists on Book) instead of the nonexistent `chapterUrl`.

### 10.4 `Migration_readLightNovelDomain.kt` — 4-arg REPLACE

**Severity:** Latent runtime crash (same reachability note as 10.3).

**File:** `tooling/local_database/.../migrations/Migration_readLightNovelDomain.kt`

**Root cause:** The migration replaced two old domains with one new domain. The SQL used `REPLACE($columnName, REPLACE($columnName, "$old1", "$new"), "$old2", "$new")` — that's **4 arguments** to `REPLACE()`, but SQLite's `REPLACE(X, Y, Z)` accepts exactly **3**. The intent was a chained replace (first old1→new, then on the result old2→new) but the parentheses were wrong.

**Fix:** Replaced with a properly nested 3-arg call:
```sql
$columnName = REPLACE(REPLACE($columnName, "$old1", "$new"), "$old2", "$new")
```

Also fixed the same `chapterUrl`-on-Book WHERE-clause bug as 10.3.

### 10.5 Room DB version bumped 5 → 8

**Severity:** Hygiene. The previously-dead migrations (5, 6, 7) become reachable for users upgrading from any prior install.

**File:** `tooling/local_database/.../AppDatabase.kt`

**Root cause:** The `@Database(version = 5)` annotation did not match the migrations registered in `Migrations.kt` (which go up to `migration(7)`, i.e. v7→v8). The result was that migrations 5, 6, 7 were registered but never executed, so any user with old `readlightnovel.org` / `1stkissnovel.love` URLs in their DB would never have them updated to the new domains.

**Fix:** Bumped `@Database(version = 8)`. Now any user with a DB at version ≤ 7 will run the appropriate chain of migrations (including the now-fixed SQL in 10.3 and 10.4).

### 10.6 `data/consumer-rules.pro` and `data/proguard-rules.pro` were empty

**Severity:** Potential runtime crash in release builds if Moshi/Gson/Room classes were stripped by R8. Did not affect debug builds.

**Files:** `data/consumer-rules.pro`, `data/proguard-rules.pro`

**Root cause:** The `:data` module uses Moshi (`PersistentCacheDataLoader`), Gson (via `GeminiApiClient`), and Room (transitively via `:tooling:local_database`), but had no consumer ProGuard rules. The app's `app/proguard-rules.pro` covered Gson for `GeminiApiClient` and `TimoTxt*` classes, but did not cover `:data` module's Moshi/Kotlin-reflection classes.

**Fix:** Populated both files with keep rules for:
- Moshi (`com.squareup.moshi.**`, `@JsonClass`-annotated classes, `Signature` and `*Annotation*` attributes).
- Gson (`com.google.gson.**`, `GeminiApiClient$ParagraphItem`, `@SerializedName` fields).
- Room (`androidx.room.**`).
- `:data` module classes (`my.noveldoksuha.data.**`, including `storage.**`).
- Kotlinx Serialization metadata (`RuntimeVisibleAnnotations,AnnotationDefault`).

### 10.7 Deprecated GitHub Actions in `buildRelease.yml` and `ui_test.yml`

**Severity:** Cosmetic (deprecation warnings) but increasingly likely to break as GitHub retires old runner images.

**Files:** `.github/workflows/buildRelease.yml`, `.github/workflows/ui_test.yml`

**Root cause:** Both files used long-deprecated action versions:
- `actions/checkout@v2` (current: v4)
- `actions/setup-java@v1.4.3` (current: v4)
- `gradle/gradle-build-action@v2` (replaced by `gradle/actions/setup-gradle@v4`)
- `actions/setup-python@v2` (current: v5)
- `softprops/action-gh-release@v1` (current: v2)
- Runner `ubuntu-20.04` (deprecated; current: `ubuntu-latest`)

**Fix:** Upgraded all action versions to current; switched runner to `ubuntu-latest`; added `--no-daemon --no-parallel` to the gradle invocation in `buildRelease.yml` (to match the pattern used by the newer `build-release-manual.yml`); added `chmod +x ./gradlew` and switched from bare `gradle` to `./gradlew` (the wrapper was already pinned to 8.2 — using the wrapper is more reliable than asking the action to install a separate Gradle).

### 10.8 README factual inaccuracies

**Severity:** Documentation only.

**File:** `README.md`

**Root cause:** Three inaccuracies:
1. Listed "Korean" and "Vietnamese" as catalog languages, but `LanguageCode.kt` only defines `ENGLISH`, `PORTUGUESE`, `SPANISH`, `FRENCH`, `INDONESIAN`, `CHINESE`. `Wuxia.kt` declares `LanguageCode.ENGLISH` (not Vietnamese). `KoreanNovelsMTL` similarly declares English.
2. Listed "Japanese: AT" but `AT.kt` extends `SourceInterface.Base` (not `Catalog`) and has no language assignment.
3. The "Minimal example" Kotlin snippet used a fictional API (`override val name = "..."`, `override val language = "English"`, `getBookCover(url): Response<BookResult>`, `getChapterText(url): Response<String>`) that does not match the real `SourceInterface.Catalog` (`nameStrId: Int`, `language: LanguageCode?`, `getBookCoverImageUrl(bookUrl): Response<String?>`, `getChapterText(doc: Document): String?`).

**Fix:**
1. Corrected the source list — moved `Wuxia`, `TimoTxt (Translate)`, `TimoTxt (Gemini)` to the English section (since they all declare `LanguageCode.ENGLISH`). Removed the "Vietnamese: Wuxia" and "Japanese: AT" entries.
2. Added an explanatory note about the `LanguageCode` enum.
3. Added a note about the 9 placeholder source string IDs (`source_name_fanmtl`, etc.) — they exist in `strings-no-translatable.xml` as placeholders for sources not yet implemented.
4. Rewrote the code sample to use the actual `SourceInterface.Catalog` API.
5. Added a note about adding a matching `<string>` resource to `strings-no-translatable.xml`.

---

## See Also

- `fixes.md` — full bug tracker with severity ratings
- `DEPENDENCIES.md` — version catalog, convention plugins
- `ARCHITECTURE_OVERVIEW.md` — build flavors, types, signing
- `SECURITY.md` — signing, API key storage
