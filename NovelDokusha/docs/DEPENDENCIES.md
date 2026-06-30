# NovelDokusha — Dependencies

> **Scope**: Full version catalog, repositories, convention plugins, module dependency graph.

## 1. Build Toolchain Versions

| Component | Version | Source |
|---|---|---|
| Android Gradle Plugin (AGP) | `8.2.2` | `gradle/libs.versions.toml` → `plugin-agp` |
| Kotlin | `1.9.23` | `gradle/libs.versions.toml` → `kotlin` |
| KSP | `1.9.23-1.0.20` | `gradle/libs.versions.toml` → `ksp` |
| Gradle (wrapper) | `8.2` | `gradle/wrapper/gradle-wrapper.properties` |
| Compose Compiler | `1.5.13` | `gradle/libs.versions.toml` → `kotlin-compose-compilerVersion` |
| Hilt | `2.49` | `gradle/libs.versions.toml` → `hilt` |
| Hilt AndroidX | `1.2.0` | `gradle/libs.versions.toml` → `hilt-androidx` |
| Java / JVM target | `17` | `build-logic/.../AppConfig.kt` |
| Android Tools Common | `31.5.1` | `gradle/libs.versions.toml` → `androidTools` |
| dependency-analysis plugin | `1.31.0` | `gradle/libs.versions.toml` → `plugin-autonomousapps` |

## 2. SDK & App Metadata

| Property | Value |
|---|---|
| `compileSdk` | `34` |
| `minSdk` | `26` (Android 8.0) |
| `targetSdk` | `34` (= compileSdk) |
| `applicationId` | `my.noveldokusha` |
| `versionCode` | `18` |
| `versionName` | `2.2.0` |
| `archivesBaseName` | `NovelDokusha_v2.2.0` |
| `testInstrumentationRunner` | `androidx.test.runner.AndroidJUnitRunner` |
| `testOptions.execution` | `ANDROIDX_TEST_ORCHESTRATOR` |

## 3. Repositories

From root `settings.gradle.kts` `dependencyResolutionManagement`:
- `google()` — AndroidX, Compose, AGP
- `mavenCentral()` — Kotlin, OkHttp, Retrofit, Moshi, Coroutines
- `jcenter()` ⚠️ **deprecated** (has `noinspection JcenterRepositoryObsolete`) — kept for back-compat, no project deps actually use it
- `maven { setUrl("https://jitpack.io") }` — for `LazyColumnScrollbar` (`com.github.nanihadesuka`), `landscapist-glide` (`com.github.skydoves`)
- `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` — subprojects cannot declare their own repos

From `buildscript` in root `build.gradle.kts`: `google()`, `jcenter()`, `mavenCentral()`.

> ⚠️ **Recommendation**: Remove `jcenter()` from both blocks — it's deprecated and scheduled for sunset. No project dependencies actually use it.

## 4. Full Version Catalog

### 4.1 Versions (`[versions]` section)

```toml
[versions]
kotlin = "1.9.23"
kotlin-compose-compilerVersion = "1.5.13"
plugin-agp = "8.2.2"
plugin-autonomousapps = "1.31.0"
hilt = "2.49"
hilt-androidx = "1.2.0"
ksp = "1.9.23-1.0.20"
kotlinx-coroutines-core = "1.7.3"
kotlinx-coroutines-playServices = "1.7.3"
kotlinx-serialization-json = "1.5.1"
testing-mockito-kotlin = "5.0.0"
androidTools = "31.5.1"
androidx-navigation-ui-ktx = "2.7.7"
androidx-navigation-fragment-ktx = "2.7.7"
androidx-preference-ktx = "1.2.1"
androidx-core-ktx = "1.13.1"
androidx-activity-ktx = "1.9.1"
androidx-fragment-ktx = "1.8.2"
androidx-material = "1.12.0"
androidx-runtime-livedata = "1.6.8"
androidx-documentfile = "1.0.1"
androidx-media = "1.7.0"
androidx-lifecycle = "2.8.4"
androidx-workmanager = "2.9.0"
androidx-startup = "1.1.1"
androidx-constraintLayout = "2.1.4"
androidx-coordinatorLayout = "1.2.0"
androidx-appcompat = "1.7.0"
androidx-testing-core = "1.6.1"
androidx-testing-runner = "1.6.1"
androidx-testing-rules = "1.6.1"
androidx-testing-orchestrator = "1.5.0"
androidx-testing-junit = "1.2.1"
androidx-testing-espresso = "3.6.1"
compose-coil = "2.4.0"
compose-accompanist = "0.30.1"
compose-constraintlayout = "1.0.1"
compose-animation = "1.6.8"
compose-activity = "1.9.1"
compose-lazyColumnScrollbar = "2.2.0"
compose-landscapist-glide = "2.2.5"
compose-testing-ui = "1.6.8"
compose-material-icons-extended = "1.6.8"
compose-material3 = "1.2.1"
compose-material3Android = "1.2.1"
crux = "5.0"
gson = "2.10.1"
jsoup = "1.17.2"
translate = "17.0.2"
moshi = "1.15.0"
room = "2.6.1"
junit = "4.13.2"
okhttp = "5.0.0-alpha.11"
okhttp-interceptor-logging = "5.0.0-alpha.11"
okhttp-glideIntegration = "4.15.1"
readability4j = "1.0.8"
retrofit = "2.9.0"
timber = "5.0.1"
junitVersion = "1.2.1"
jetbrainsKotlinJvm = "1.9.0"
```

### 4.2 Libraries (`[libraries]` section)

#### Compose

| Alias | Coordinate |
|---|---|
| `compose-accompanist-pager-indicators` | `com.google.accompanist:accompanist-pager-indicators:0.30.1` |
| `compose-accompanist-pager` | `com.google.accompanist:accompanist-pager:0.30.1` |
| `compose-accompanist-insets` | `com.google.accompanist:accompanist-insets:0.30.1` |
| `compose-accompanist-swiperefresh` | `com.google.accompanist:accompanist-swiperefresh:0.30.1` |
| `compose-accompanist-systemuicontroller` | `com.google.accompanist:accompanist-systemuicontroller:0.30.1` |
| `compose-androidx-constraintlayout` | `androidx.constraintlayout:constraintlayout-compose:1.0.1` |
| `compose-androidx-animation` | `androidx.compose.animation:animation:1.6.8` |
| `compose-androidx-activity` | `androidx.activity:activity-compose:1.9.1` |
| `compose-androidx-lifecycle-viewmodel` | `androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4` |
| `compose-androidx-material-icons-extended` | `androidx.compose.material:material-icons-extended:1.6.8` |
| `compose-androidx-runtime-livedata` | `androidx.compose.runtime:runtime-livedata:1.6.8` |
| `compose-androidx-ui` | `androidx.compose.ui:ui:1.6.8` |
| `compose-androidx-ui-test-junit4` | `androidx.compose.ui:ui-test-junit4:1.6.8` |
| `compose-androidx-ui-tooling` | `androidx.compose.ui:ui-tooling:1.6.8` |
| `compose-androidx-material3` | `androidx.compose.material3:material3:1.2.1` |
| `compose-lazyColumnScrollbar` | `com.github.nanihadesuka:LazyColumnScrollbar:2.2.0` *(JitPack)* |
| `compose-coil` | `io.coil-kt:coil-compose:2.4.0` |
| `compose-landscapist-glide` | `com.github.skydoves:landscapist-glide:2.2.5` *(JitPack)* |
| `compose-material3-android` | `androidx.compose.material3:material3-android:1.2.1` |

#### Hilt

| Alias | Coordinate |
|---|---|
| `hilt-compiler` | `com.google.dagger:hilt-compiler:2.49` |
| `hilt-android` | `com.google.dagger:hilt-android:2.49` |
| `hilt-workmanager` | `androidx.hilt:hilt-work:1.2.0` |
| `hilt-androidx-compiler` | `androidx.hilt:hilt-compiler:1.2.0` |

#### Room

| Alias | Coordinate |
|---|---|
| `androidx-room-testing` | `androidx.room:room-testing:2.6.1` |
| `androidx-room-compiler` | `androidx.room:room-compiler:2.6.1` |
| `androidx-room-ktx` | `androidx.room:room-ktx:2.6.1` |
| `androidx-room-runtime` | `androidx.room:room-runtime:2.6.1` |

#### Networking

| Alias | Coordinate |
|---|---|
| `retrofit` | `com.squareup.retrofit2:retrofit:2.9.0` |
| `okhttp-interceptor-logging` | `com.squareup.okhttp3:logging-interceptor:5.0.0-alpha.11` |
| `okhttp` | `com.squareup.okhttp3:okhttp:5.0.0-alpha.11` |
| `okhttp-interceptor-brotli` | `com.squareup.okhttp3:okhttp-brotli:5.0.0-alpha.11` |
| `okhttp-glideIntegration` | `com.github.bumptech.glide:okhttp3-integration:4.15.1` |

#### Data / Serialization

| Alias | Coordinate |
|---|---|
| `gson` | `com.google.code.gson:gson:2.10.1` |
| `jsoup` | `org.jsoup:jsoup:1.17.2` |
| `moshi` | `com.squareup.moshi:moshi:1.15.0` |
| `moshi-kotlin` | `com.squareup.moshi:moshi-kotlin:1.15.0` |
| `kotlinx-serialization-json` | `org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1` |

#### Text extractors

| Alias | Coordinate |
|---|---|
| `readability4j` | `net.dankito.readability4j:readability4j:1.0.8` |
| `crux` | `com.chimbori.crux:crux:5.0` |

#### Testing

| Alias | Coordinate |
|---|---|
| `test-mockito-kotlin` | `org.mockito.kotlin:mockito-kotlin:5.0.0` |
| `test-junit` | `junit:junit:4.13.2` |
| `test-androidx-orchestrator` | `androidx.test:orchestrator:1.5.0` |
| `test-androidx-junit-ktx` | `androidx.test.ext:junit-ktx:1.2.1` |
| `test-androidx-espresso-core` | `androidx.test.espresso:espresso-core:3.6.1` |
| `test-androidx-runner` | `androidx.test:runner:1.6.1` |
| `test-androidx-rules` | `androidx.test:rules:1.6.1` |
| `test-androidx-core-ktx` | `androidx.test:core-ktx:1.6.1` |

#### Kotlin

| Alias | Coordinate |
|---|---|
| `kotlin-stdlib` | `org.jetbrains.kotlin:kotlin-stdlib:1.9.23` |
| `kotlin-script-runtime` | `org.jetbrains.kotlin:kotlin-script-runtime:1.9.23` |
| `kotlinx-coroutines-core` | `org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3` |
| `kotlinx-coroutines-playServices` | `org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3` |

#### AndroidX

| Alias | Coordinate |
|---|---|
| `androidx-constraintlayout` | `androidx.constraintlayout:constraintlayout:2.1.4` |
| `androidx-fragment-ktx` | `androidx.fragment:fragment-ktx:1.8.2` |
| `androidx-activity-ktx` | `androidx.activity:activity-ktx:1.9.1` |
| `androidx-core-ktx` | `androidx.core:core-ktx:1.13.1` |
| `androidx-appcompat` | `androidx.appcompat:appcompat:1.7.0` |
| `androidx-lifecycle-common-java8` | `androidx.lifecycle:lifecycle-common-java8:2.8.4` |
| `androidx-coordinatorlayout` | `androidx.coordinatorlayout:coordinatorlayout:1.2.0` |
| `androidx-lifecycle-livedata-ktx` | `androidx.lifecycle:lifecycle-livedata-ktx:2.8.4` |
| `androidx-lifecycle-viewmodel-ktx` | `androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.4` |
| `androidx-startup` | `androidx.startup:startup-runtime:1.1.1` |
| `androidx-workmanager` | `androidx.work:work-runtime-ktx:2.9.0` |
| `androidx-documentfile` | `androidx.documentfile:documentfile:1.0.1` |
| `androidx-media` | `androidx.media:media:1.7.0` |
| `androidx-navigation-ui-ktx` | `androidx.navigation:navigation-ui-ktx:2.7.7` |
| `androidx-navigation-fragment-ktx` | `androidx.navigation:navigation-fragment-ktx:2.7.7` |
| `androidx-preference-ktx` | `androidx.preference:preference-ktx:1.2.1` |
| `material` | `com.google.android.material:material:1.12.0` |
| `androidx-junit` | `androidx.test.ext:junit:1.2.1` |

#### Other

| Alias | Coordinate |
|---|---|
| `translate` (ML Kit) | `com.google.mlkit:translate:17.0.2` |
| `timber` | `com.jakewharton.timber:timber:5.0.1` |
| `android-gradlePlugin` | `com.android.tools.build:gradle:8.2.2` |
| `android-tools-common` | `com.android.tools:common:31.5.1` |
| `kotlin-gradlePlugin` | `org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.23` |
| `ksp-gradlePlugin` | `com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:1.9.23-1.0.20` |

### 4.3 Plugins (`[plugins]` section)

| Alias | Plugin ID | Version |
|---|---|---|
| `android-application` | `com.android.application` | `8.2.2` |
| `android-library` | `com.android.library` | `8.2.2` |
| `hilt` | `com.google.dagger.hilt.android` | `2.49` |
| `kotlin-android` | `org.jetbrains.kotlin.android` | `1.9.23` |
| `kotlin-parcelize` | `org.jetbrains.kotlin.plugin.parcelize` | `1.9.23` |
| `kotlin-scripting` | `org.jetbrains.kotlin.plugin.scripting` | `1.9.23` |
| `kotlin-serialization` | `org.jetbrains.kotlin.plugin.serialization` | `1.9.23` |
| `ksp` | `com.google.devtools.ksp` | `1.9.23-1.0.20` |
| `dependency-analysis` | `com.autonomousapps.dependency-analysis` | `1.31.0` |
| `noveldokusha-android-application` | `noveldokusha.android.application` | `unspecified` (convention) |
| `noveldokusha-android-library` | `noveldokusha.android.library` | `unspecified` (convention) |
| `noveldokusha-android-compose` | `noveldokusha.android.compose` | `unspecified` (convention) |
| `jetbrains-kotlin-jvm` | `org.jetbrains.kotlin.jvm` | `1.9.0` |

### 4.4 Bundles
**None defined** — the `[bundles]` section is empty.

## 5. Convention Plugins (`build-logic/`)

### 5.1 Plugin registration

`build-logic/convention/build.gradle.kts` uses the `kotlin-dsl` plugin and registers three Gradle plugins:

| ID | Implementation class |
|---|---|
| `noveldokusha.android.application` | `NoveldokushaAndroidApplicationBestPracticesConventionPlugin` |
| `noveldokusha.android.library` | `NoveldokushaAndroidLibraryBestPracticesConventionPlugin` |
| `noveldokusha.android.compose` | `NoveldokushaAndroidComposeBestPracticesConventionPlugin` |

### 5.2 Plugin behaviors

#### `noveldokusha.android.application`
- Applies: `com.android.application`, `org.jetbrains.kotlin.android`, then `applyHilt()` (which applies `com.google.devtools.ksp` + `com.google.dagger.hilt.android` and adds `hilt-android`, `hilt-compiler`, `hilt-androidx-compiler` deps).
- Configures `ApplicationExtension` via `configureAndroid(this)` + sets `defaultConfig.targetSdk = appConfig.TARGET_SDK`.

#### `noveldokusha.android.library`
- Applies: `com.android.library`, `org.jetbrains.kotlin.android`, `applyHilt()`.
- Configures `LibraryExtension` via `configureAndroid(this)` + `defaultConfig.targetSdk`.
- Sets `resourcePrefix` automatically derived from the module path: `path.split("""\W""".toRegex()).drop(1).distinct().joinToString("_").lowercase() + "_"`. So `:tooling:epub_parser` → `"tooling_epub_parser_"`.
- Forces `release { isMinifyEnabled = false }` for all library modules (R8 only runs at the app module level).

#### `noveldokusha.android.compose`
- Does NOT apply application or library plugins (works on whichever extension already exists).
- Enables `buildFeatures.compose = true`.
- Sets `composeOptions.kotlinCompilerExtensionVersion = libs.findVersion("kotlin-compose-compilerVersion")` → `1.5.13`.
- Adds `implementation` deps for `compose-androidx-ui` and `compose-androidx-ui-tooling`.
- Enables `testOptions.unitTests.isIncludeAndroidResources = true` (for Robolectric).

### 5.3 `AppConfig` constants

```kotlin
internal object appConfig {
    val javaVersion = JavaVersion.VERSION_17
    const val JAVA_VERSION_STRING = "17"
    const val COMPILE_SDK = 34
    const val TARGET_SDK = COMPILE_SDK   // = 34
    const val MIN_SDK = 26
}
```

### 5.4 `KotlinAndroid.kt` (`configureAndroid`)

Configures any `CommonExtension`:
- `compileSdk = 34`
- `defaultConfig.minSdk = 26`
- `testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"`
- `testInstrumentationRunnerArguments["clearPackageData"] = "true"`
- `buildFeatures.buildConfig = true`
- `compileOptions.sourceCompatibility/targetCompatibility = VERSION_17`
- `lint { showAll = true; abortOnError = false; lintConfig = rootProject.file("lint.xml") }`
- `testOptions.execution = "ANDROIDX_TEST_ORCHESTRATOR"`
- Then `configureKotlin()`:
  - `tasks.withType<KotlinCompile>().configureEach` sets `jvmTarget = "17"`.
  - Adds free compiler args: `-opt-in=kotlin.RequiresOptIn`, `-Xjvm-default=all-compatibility`.

### 5.5 `Hilt.kt` (`applyHilt` / `applyKSP`)

- `applyKSP()`: applies `com.google.devtools.ksp`.
- `applyHilt()`: applies KSP, then `com.google.dagger.hilt.android`, then adds:
  - `implementation(hilt-android)`
  - `ksp(hilt-compiler)`
  - `ksp(hilt-androidx-compiler)`

> ⚠️ **Note**: `hilt-workmanager` (`androidx.hilt:hilt-work`) is NOT added by the convention plugin — only `app/build.gradle.kts` adds it explicitly. Modules using `@HiltWorker` must add it themselves.

## 6. Gradle Properties

### 6.1 Root `gradle.properties`

```properties
android.enableJetifier=false
android.nonFinalResIds=false
android.nonTransitiveRClass=false
android.useAndroidX=true
kotlin.code.style=official
org.gradle.jvmargs=-Xmx3g -Dkotlin.daemon.jvm.options="-Xmx3g"
org.gradle.unsafe.configuration-cache=true
org.gradle.unsafe.configuration-cache-problems=warn
```

> ⚠️ `org.gradle.unsafe.configuration-cache=true` uses the *unsafe* (legacy) property name. Modern Gradle 8.2 prefers `org.gradle.configuration-cache=true`. The `unsafe` prefix still works but may emit deprecation warnings.

### 6.2 `build-logic/gradle.properties`

```properties
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configureondemand=true
```

Comment notes: "Gradle properties are not passed to included builds" (referring to gradle/gradle#2534) — hence the duplication.

## 7. Module Dependency Graph

```
app
├── tooling:local_database, epub_parser, text_translator:domain, text_to_speech,
│   epub_importer, application_workers, local_source
├── features:reader, chaptersList, globalSourceSearch, databaseExplorer,
│   sourceExplorer, catalogExplorer, libraryExplorer, settings, webview
├── data, core, coreui, navigation, networking, strings, scraper
├── (fullImplementation) tooling:text_translator:translator
└── (fossImplementation) tooling:text_translator:translator_nop

scraper ──► strings, core, networking
strings  ──► (none beyond libs)
navigation ──► core, tooling:local_database
data ──► core, networking, scraper, tooling:local_database, tooling:epub_parser
networking ──► core
core ──► strings (+ compose, kotlinx-serialization, jsoup, timber, livedata, preference)
coreui ──► strings, core, tooling:local_database

features:reader ──► core, coreui, data, navigation, tooling:local_database,
                    tooling:text_to_speech, tooling:text_translator:domain, tooling:algorithms
features:chaptersList ──► core, coreui, strings, data, scraper, navigation, tooling:local_database
features:globalSourceSearch ──► core, coreui, strings, data, scraper, navigation,
                                networking, tooling:local_database
features:databaseExplorer ──► (same as globalSourceSearch)
features:sourceExplorer ──► (same as globalSourceSearch)
features:catalogExplorer ──► core, coreui, strings, data, scraper, navigation, tooling:local_database
features:settings ──► core, coreui, strings, data, scraper, navigation, tooling:local_database,
                      tooling:text_translator:domain, tooling:backup_restore, tooling:backup_create
features:libraryExplorer ──► core, coreui, strings, data, scraper, navigation,
                             tooling:local_database, tooling:text_translator:domain, tooling:epub_importer
features:webview ──► core, coreui, strings, networking, navigation

tooling:local_database ──► core
tooling:epub_parser ──► core
tooling:epub_importer ──► core, coreui, strings, data, tooling:local_database, tooling:epub_parser
tooling:backup_create ──► core, coreui, strings, data, tooling:local_database
tooling:backup_restore ──► core, coreui, strings, data, tooling:local_database
tooling:application_workers ──► core, coreui, strings, data, navigation, tooling:local_database
tooling:local_source ──► core, coreui, strings, data, scraper, networking,
                         tooling:epub_parser, tooling:local_database
tooling:text_to_speech ──► tooling:algorithms
tooling:text_translator:domain ──► (none beyond libs)
tooling:text_translator:translator ──► core, tooling:text_translator:domain,
                                       kotlinx-coroutines-playServices, mlkit:translate
tooling:text_translator:translator_nop ──► core, tooling:text_translator:domain
tooling:algorithms ──► (none beyond libs)
```

## 8. ProGuard / R8 Rules (`app/proguard-rules.pro`)

### 8.1 Lifecycle
```proguard
-keep public class * extends androidx.lifecycle.ViewModel { *; }
```

### 8.2 Kotlin Serialization (3 blocks)
1. Keep `Companion` object fields of `@Serializable` classes.
2. Keep `serializer()` on companion objects (default and named) of `@Serializable` classes.
3. Keep `INSTANCE.serializer()` of `@Serializable` objects.

Plus:
```proguard
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
```

### 8.3 Logging
```proguard
-dontwarn org.slf4j.impl.StaticLoggerBinder

-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int d(...);
    public static int v(...);
    public static int i(...);
}

-assumenosideeffects class timber.log.Timber* {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}
```
Removes debug/verbose/info log calls in release — but keeps `w` and `e` warnings/errors.

### 8.4 Project-specific: Gemini API Client

```proguard
-keep class my.noveldokusha.scraper.sources.GeminiApiClient$ParagraphItem { *; }
-keep class my.noveldokusha.scraper.sources.GeminiApiClient { *; }
-keep class my.noveldokusha.scraper.sources.TimoTxtGemini { *; }
-keep class my.noveldokusha.scraper.sources.TimoTxtTranslate { *; }
```

Gson uses reflection to serialize/deserialize `ParagraphItem` (the JSON chunk envelope used by the Gemini two-pass translator), so all fields must be preserved.

### 8.5 Gson itself

```proguard
-keep class com.google.gson.** { *; }
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn com.google.gson.**
```

### 8.6 Empty files
- `data/proguard-rules.pro` — empty (0 bytes).
- `data/consumer-rules.pro` — empty (0 bytes).

Library-module ProGuard rules are not currently used (consistent with the convention plugin forcing `isMinifyEnabled = false` for libraries).

## 9. Lint Configuration

### `lint.xml`
```xml
<lint>
    <issue id="VectorPath" severity="ignore" />
    <issue id="MissingTranslation" severity="ignore" />
</lint>
```

Convention plugin `KotlinAndroid.kt` configures lint:
- `showAll = true`
- `abortOnError = false`
- `lintConfig = rootProject.file("lint.xml")`

## 10. Dependency Analysis Plugin

The `com.autonomousapps.dependency-analysis` plugin (v1.31.0) is declared in `libs.versions.toml` and applied (with `apply false`) at the root. To use it:

1. Apply the plugin at the root or per-module: `plugins { id("com.autonomousapps.dependency-analysis") }` (or alias `alias(libs.plugins.dependency.analysis) apply false` is already in root).
2. Run `./gradlew buildHealth` to get a report of unused dependencies, misused configurations, etc.

Currently dormant — the plugin is declared but not actively applied.

## 11. See Also

- `ARCHITECTURE_OVERVIEW.md` — module topology, build flavors, convention plugins
- `Build-fixes.md` — dependency-related build fixes
