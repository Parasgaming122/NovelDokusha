# NovelDokusha — UI Update & Migration Log

> **Purpose**: Document the hybrid UI architecture (Compose + legacy XML), the migration strategy from XML to Compose, the live state-update patterns used throughout the app, and the screens/flows that have been (or still need to be) migrated.

---

## Table of Contents

1. [Hybrid UI Architecture](#1-hybrid-ui-architecture)
2. [Compose ↔ XML interop layers](#2-compose--xml-interop-layers)
3. [State Management & Live Updates](#3-state-management--live-updates)
4. [Theme System](#4-theme-system)
5. [Navigation](#5-navigation)
6. [Per-Screen Migration Status](#6-per-screen-migration-status)
7. [Live UI Update Patterns](#7-live-ui-update-patterns)
8. [Known UI Bugs / Quirks](#8-known-ui-bugs--quirks)
9. [Migration Guidelines for New Code](#9-migration-guidelines-for-new-code)

---

## 1. Hybrid UI Architecture

NovelDokusha uses a **hybrid UI** approach: the bulk of newer screens are built with **Jetpack Compose** (`androidx.compose.*`), while a few performance-critical or legacy screens remain as **XML views** inflated inside Compose via `AndroidView`. This incremental-migration strategy lets the project adopt Compose without a single mega-PR rewrite.

### Top-level stack

| Layer | Technology | Where |
|---|---|---|
| Activity hosting | `ComponentActivity` + `setContent { ... }` | `coreui/.../BaseActivity.kt`, all `*Activity.kt` |
| Screen composables | `@Composable fun *Screen(...)` | `features/*/.../*Screen.kt` |
| State holders | `@HiltViewModel class *ViewModel : BaseViewModel` | `features/*/.../*ViewModel.kt` |
| Design system | Material 3 + custom theme | `coreui/.../theme/*` |
| List rendering (legacy) | `RecyclerView` wrapped in `AndroidView` | `features/reader/...` (chapter list items) |
| Image loading | Landscapist (Coil) + Glide fallback | `coreui/.../components/ImageView*.kt` |

The reader is the most prominent example of the hybrid pattern: the chapter body is rendered as a `RecyclerView` inside a Compose `AndroidView` because the legacy list-item XML layouts (`activity_reader_list_item_*.xml`) handle rich text + image + translation-attribution layout details that would be costly to re-implement in Compose.

---

## 2. Compose ↔ XML interop layers

### 2.1 `AndroidView` for embedded XML

The reader uses this pattern:

```kotlin
AndroidView(
    factory = { ctx -> RecyclerView(ctx).apply { adapter = readerAdapter } },
    update = { rv -> /* re-bind adapter when state changes */ }
)
```

Adapter: `ReaderItemAdapter` (`features/reader/.../domain/ReaderItemAdapter.kt`) inflates one of the `activity_reader_list_item_*.xml` layouts per row type (`body`, `image`, `title`, `divider`, `translating`, `error`, `progress_bar`, `special_title`, `padding`, `google_translate_attribution`).

### 2.2 Compose APIs that wrap legacy views

| Composable | Wraps | File |
|---|---|---|
| `ImageViewGlide` | `ImageView` + Glide | `coreui/.../components/ImageViewGlide.kt` |
| `ImageView` | `ImageView` + Landscapist (Coil) | `coreui/.../components/ImageView.kt` |
| `MySlider` | Material `Slider` with custom step handling | `coreui/.../components/MySlider.kt` |
| `TopAppBarSearch` | Material `TopAppBar` + collapsing search field | `coreui/.../components/TopAppBarSearch.kt` |
| `ExpandableText` | `Text` with show-more toggle | `coreui/.../components/ExpandableText.kt` |
| `TernaryStateToggle` | Three-state checkbox | `coreui/.../components/TernaryStateToggle.kt` |

### 2.3 `BaseActivity` + `BaseViewModel`

All activities extend `my.noveldoksuha.coreui.BaseActivity` which provides:
- Hilt injection scaffolding
- Theme application (calls `AppThemeProvider.applyTheme(themeId)` in `onCreate`)
- A standard `setContent { InternalTheme(themeId) { screen() } }` host
- System bar transparency / color management

All view models extend `my.noveldoksuha.coreui.BaseViewModel` which provides:
- A `viewModelScope` for launches
- An `eventFlow` for one-shot events (toasts, navigation)
- SavedStateHandle integration via `asMutableStateOf(key) { default }`

---

## 3. State Management & Live Updates

### 3.1 Reactive state source

`AppPreferences` exposes each preference as both a `Flow<T>` (via `Preference.flow()`) and a Compose-observable `MutableState<T>` (via `Preference.state(scope)`). Both are backed by `SharedPreferences.OnSharedPreferenceChangeListener`, so changes propagate live across screens.

Example (`SettingsViewModel.kt`):
```kotlin
val state = SettingsScreenState(
    followsSystemTheme = appPreferences.THEME_FOLLOW_SYSTEM.state(viewModelScope),
    geminiSettings = SettingsScreenState.GeminiSettings(
        apiKey = appPreferences.GEMINI_API_KEY.state(viewModelScope),
        model = appPreferences.GEMINI_MODEL.state(viewModelScope),
        temperature = appPreferences.GEMINI_TEMPERATURE.state(viewModelScope),
    ),
    ...
)
```

When the user changes the temperature slider in `SettingsGemini.kt`, the slider calls `onTemperatureChange = { state.geminiSettings.temperature.value = it }`, which writes through to `SharedPreferences`. The `OnSharedPreferenceChangeListener` then fires, the `MutableState` value is updated, and any other observers (including the lazy `GeminiApiClient` constructed by `Scraper` next time the user opens a chapter) see the new value.

### 3.2 Room → Flow → UI

The Room DAOs return `Flow<List<*>>` for queries that the UI should observe live (e.g. `LibraryDao.getLibraryBooksFlow()`). The `data/...` repository classes expose these flows directly to view models:

```kotlin
class LibraryPageViewModel @Inject constructor(
    private val libraryRepo: LibraryBooksRepository,
    ...
) : BaseViewModel() {
    val libraryBooks by libraryRepo.getLibraryBooksFlow()
        .collectAsStateWithLifecycle(...)
}
```

When a chapter download completes and the `ChapterDao` inserts/updates a row, the flow re-emits, and the library list updates without any manual refresh.

### 3.3 NotificationsCenter

`coreui/.../states/NotificationsCenter.kt` is a simple in-memory event bus for cross-screen notifications (e.g. "chapter download started", "translation model download complete"). It's a `SharedFlow<AppNotification>` that any composable can collect.

### 3.4 IteratorState / PagedListIteratorState

For paged catalogs (`SourceCatalogScreen`, `CatalogExplorerScreen`), the `coreui/.../states/PagedListIteratorState.kt` class wraps the pagination state machine: loading, error, end-of-list, current page index. It exposes a `loadNext()` function that the screen calls when the user scrolls near the bottom.

---

## 4. Theme System

### 4.1 Theme IDs

Defined in `core/.../appPreferences/PreferenceThemes.kt`:
- `Light`, `Dark`, `Grey`, `Black` (AMOLED)

Mapped to Material 3 color schemes in `coreui/.../theme/Theme.kt` and `Themes.kt`.

### 4.2 Dynamic color

On Android 12+ (API 31+), the user can opt into dynamic color (Material You). This is handled in `coreui/.../theme/Theme.kt` via `dynamicColorScheme(context)`.

### 4.3 Theme application

`AppThemeProvider` (`coreui/.../AppThemeProvider.kt`) is called from `BaseActivity.onCreate` to set the platform theme (used for the activity window background, system bars, and any XML views that still reference `?attr/colorPrimary` etc.). Inside `setContent`, the `InternalTheme(themeId)` composable wraps everything with the matching `MaterialTheme(colorScheme = ..., typography = ..., shapes = ...)`.

### 4.4 Launcher theme

`AppTheme_Launcher.xml` (in `coreui/.../res/values/`) is a separate theme used only for the launcher icon background and splash screen, so the splash matches the user's chosen theme.

---

## 5. Navigation

### 5.1 Single-activity navigation

The app uses a single `MainActivity` (`app/.../MainActivity.kt`) hosting a Compose Navigation graph. Routes are defined in `app/.../AppNavigationRoutes.kt` as string constants.

### 5.2 Navigation graph

The graph lives in `app/.../App.kt` (the `@Composable fun AppNavigation()`). It wires up:
- Library explorer (root)
- Catalog explorer (source list)
- Source catalog (single source browser)
- Chapters list (for a book)
- Reader (for a chapter)
- Settings
- Database explorer
- Global source search
- Webview (for sources that need a real browser)

Some destinations receive arguments via NavType — book URLs, chapter URLs, source IDs.

### 5.3 Compose Navigation

Standard `androidx.navigation:navigation-compose` is used. Each destination is a composable lambda:

```kotlin
composable(
    route = "${AppNavigationRoutes.reader}/{bookUrl}/{chapterUrl}",
    arguments = listOf(
        navArgument("bookUrl") { type = NavType.StringType },
        navArgument("chapterUrl") { type = NavType.StringType },
    )
) { backStackEntry ->
    ReaderActivity.launch(...)  // or ReaderScreen(...) inline
}
```

---

## 6. Per-Screen Migration Status

| Screen | Module | Status | Notes |
|---|---|---|---|
| Library | `features/libraryExplorer` | ✅ Compose | `LibraryScreen.kt` |
| Catalog explorer | `features/catalogExplorer` | ✅ Compose | `CatalogExplorerScreen.kt` |
| Source catalog | `features/sourceExplorer` | ✅ Compose | `SourceCatalogScreen.kt` |
| Chapters list | `features/chaptersList` | ✅ Compose | `ChaptersScreen.kt` |
| Reader | `features/reader` | 🟡 Hybrid | Compose host + `RecyclerView` for chapter body |
| Reader settings dialogs | `features/reader/.../settingDialogs/` | ✅ Compose | `StyleSettingDialog.kt`, `VoiceReaderSettingDialog.kt`, `TranslatorSettingDialog.kt`, `MoreSettingDialog.kt` |
| Settings | `features/settings` | ✅ Compose | `SettingsScreen.kt` + section composables |
| Database explorer | `features/databaseExplorer` | ✅ Compose | `DatabaseSearchScreen.kt`, `DatabaseBookInfoScreen.kt` |
| Global source search | `features/globalSourceSearch` | ✅ Compose | `GlobalSourceSearchScreen.kt` |
| Webview | `features/webview` | 🟡 XML | `WebViewActivity` hosts a real `WebView` (not Compose) |

### 6.1 Reader: why still hybrid?

The reader is the most performance-sensitive screen. Each chapter can have hundreds of paragraphs, mixed text/image/title/divider items, live translation overlays, and TTS highlighting. The legacy `RecyclerView` adapter handles:
- View-type recycling (10 view types in `ReaderItemAdapter`)
- Image loading with Glide (`activity_reader_list_item_image.xml`)
- Translation-in-progress overlay (`activity_reader_list_item_translating.xml`)
- Google Translate attribution footer (`activity_reader_list_item_google_translate_attribution.xml`)

A pure-Compose rewrite would require:
- A `LazyColumn` with 10 distinct item types
- Re-implementing the TTS-highlight overlay (currently a `Spannable` on a `TextView`)
- Re-implementing the translation-in-progress state animation
- Performance testing against long chapters (1000+ paragraphs)

This work is planned but not started.

---

## 7. Live UI Update Patterns

### 7.1 Preference → UI

The `SettingsViewModel` reads preferences via `.state(viewModelScope)` which returns a `MutableState<T>` that is both readable and writable from Compose. Writes propagate to `SharedPreferences` and back to all observers.

This is what makes the **Gemini temperature slider** work: drag the slider → `onTemperatureChange` writes to `state.geminiSettings.temperature.value` → setter writes to `SharedPreferences` → listener fires → any other observer (including the lazy `GeminiApiClient` next time the user opens a chapter) sees the new value.

### 7.2 Database → UI

Room flows are collected with `collectAsStateWithLifecycle` in the screen composable:

```kotlin
@Composable
fun LibraryPageBody(viewModel: LibraryPageViewModel) {
    val books by viewModel.libraryBooks.collectAsStateWithLifecycle()
    BooksVerticalView(books = books)
}
```

### 7.3 Network → UI

For one-shot network calls (e.g. fetch chapter list), the view model exposes a `StateFlow<UiState>`:

```kotlin
sealed interface UiState {
    object Loading : UiState
    data class Success(val data: List<ChapterResult>) : UiState
    data class Error(val message: String) : UiState
}

class ChaptersViewModel ... {
    private val _state = MutableStateFlow<UiState>(UiState.Loading)
    val state: StateFlow<UiState> = _state
    fun load(bookUrl: String) = viewModelScope.launch {
        _state.value = UiState.Loading
        scraper.getChapterList(bookUrl)
            .onSuccess { _state.value = UiState.Success(it) }
            .onError { _state.value = UiState.Error(it.message ?: "Unknown") }
    }
}
```

### 7.4 TTS highlight updates

The TTS engine (`features/reader/.../features/ReaderTextToSpeech.kt`) emits highlight ranges via a `StateFlow<IntRange?>` which the reader adapter observes. Each row re-binds its `TextView` with a `BackgroundColorSpan` on the highlighted range. This is updated at audio-speech-rate (several times per second).

### 7.5 Live translation overlay

When the user enables live translation in the reader, `ReaderLiveTranslation.kt` subscribes to the active chapter's translation flow. As each paragraph is translated (via ML Kit), it updates a `MutableState<Map<Int, String>>` (paragraphIndex → translatedText). The reader adapter observes this map and re-renders only the affected rows.

---

## 8. Known UI Bugs / Quirks

### 8.1 `data`/`coreui`/`databaseExplorer` namespace typo

Three modules use the package `my.noveldoksuha.*` (missing `h` in `noveldokusha`). This is "intentional by repetition" — the source files consistently use the typo'd package. Renaming would require touching every file in those modules. The namespace typo doesn't affect runtime but can confuse developers searching for "noveldokusha" in the codebase.

### 8.2 Reader slow-scroll on long chapters

Some users report scroll-jank on chapters with 500+ paragraphs. The `RecyclerView` adapter uses `DiffUtil` but the diff is computed on the main thread. A fix would be to compute the diff on a background dispatcher.

### 8.3 Theme switching requires activity recreate

When the user changes the theme in Settings, the platform theme (used by the activity window) requires `recreate()`. This causes a brief flicker. A workaround would be to use `DynamicColors.applyToActivityIfAvailable` + a manual `setTheme()` call without recreate.

### 8.4 Compose `Slider` step rounding

The Gemini temperature slider uses `steps = 9` for a 0.0–1.0 range, which gives 11 discrete values (0.0, 0.1, 0.2, ..., 1.0). The displayed value uses `String.format("%.2f", temperature)` which can show "0.10" when the user expects "0.1". This is cosmetic.

---

## 9. Migration Guidelines for New Code

### 9.1 New screens → Compose only

All new screens must be Compose. Do not add new XML layouts (except for items inside existing `RecyclerView` adapters that haven't been migrated yet).

### 9.2 New components → put in `coreui/.../components/`

If a component is reusable across features, put it in `coreui`. If it's specific to one feature, put it in that feature's `.../components/` package.

### 9.3 State pattern

For each new screen:
1. Create a `*ScreenState` data class holding `MutableState` fields.
2. Create a `@HiltViewModel *ViewModel` that exposes a `val state = *ScreenState(...)`.
3. Create a `*Screen.kt` composable that takes `state` and event callbacks.
4. Create a `*ScreenBody.kt` composable that renders `state` and calls the callbacks.
5. The activity wires the view model to the screen.

### 9.4 Tests

For new view models, add unit tests under `src/test/`. The pattern in `ScraperTest.kt` (using `mock<AppPreferences>()`) shows how to mock Hilt-provided dependencies.

For UI tests, use `androidx.compose.ui.test.junit4.createComposeRule()` and the `features/reader/src/test/.../ReaderItemBinarySearchTest.kt` as a model.

### 9.5 Accessibility

All new Compose components should set content descriptions on icon-only buttons (`Modifier.semantics { contentDescription = "..." }`) and use `MaterialTheme.typography` for text styles (not hardcoded sp sizes).

---

## See Also

- `UI_LAYER.md` — Component catalog, theme details
- `ARCHITECTURE_OVERVIEW.md` — Module dependency graph
- `CORE_ENGINE.md` — AppPreferences, Response, PagedList, LiveEvent
- `TRANSLATION_SYSTEM.md` — ML Kit + Gemini integration
- `TTS_ENGINE.md` — TTS highlight mechanism
- `fixes.md` — Bug tracker (incl. UI bugs)
- `Build-fixes.md` — Build failure history
