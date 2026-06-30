# NovelDokusha — UI Layer

> **Scope**: Feature modules (`features/*`), the hybrid Compose/ViewBinding reader, navigation patterns, and the Compose-over-ListView architecture.

## 1. Feature Module Overview

| Module | Activity | Screen | Purpose |
|---|---|---|---|
| `features/reader` | `ReaderActivity` | `ReaderScreen` | Full-screen chapter reader with TTS + media controls |
| `features/chaptersList` | `ChaptersActivity` | `ChaptersScreen` | Chapter list for one book |
| `features/libraryExplorer` | (none — hosted in `MainActivity`) | `LibraryScreen` | User's library (Default + Completed tabs) |
| `features/catalogExplorer` | (none — hosted in `MainActivity`) | `CatalogExplorerScreen` | Finder tab — list of sources + databases |
| `features/sourceExplorer` | `SourceCatalogActivity` | `SourceCatalogScreen` | Browse one source's catalog |
| `features/databaseExplorer` | `DatabaseSearchActivity` + `DatabaseBookInfoActivity` | `DatabaseSearchScreen` / `DatabaseBookInfoScreen` | Search NovelUpdates/BakaUpdates + book detail |
| `features/globalSourceSearch` | `GlobalSourceSearchActivity` | `GlobalSourceSearchScreen` | Cross-source search |
| `features/settings` | (none — hosted in `MainActivity`) | `SettingsScreen` | Settings (theme, Gemini, library updates, etc.) |
| `features/webview` | `WebViewActivity` | `WebViewScreen` | In-app browser for CloudFlare bypass |

## 2. Common Patterns

### 2.1 `BaseActivity` + `BaseViewModel`
All activities extend `coreui/BaseActivity` (which is `@AndroidEntryPoint`). All ViewModels extend `coreui/BaseViewModel` (which is `@HiltViewModel`). Hilt injects repositories and helpers into the ViewModels.

### 2.2 `StateBundle` interface + `StateExtra_*` delegates
Each activity that accepts intent extras defines a `StateBundle` interface listing the extras it expects, then implements it in the ViewModel using `StateExtra_*` property delegates (from `core/utils/SavedStateHandleDelegates.kt`). This gives type-safe intent extras with `SavedStateHandle` backing for process-death survival.

Example from `ChaptersViewModel`:
```kotlin
interface ChapterStateBundle {
    val rawBookUrl: String
    val bookTitle: String
}

@HiltViewModel
class ChaptersViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle
) : ViewModel(), ChapterStateBundle {
    override val rawBookUrl: String by StateExtra_String("bookUrl")
    override val bookTitle: String by StateExtra_String("bookTitle")
}
```

### 2.3 `IntentData` companion
Each activity exposes an `IntentData` class (typically a subclass of `Intent`) that builds the launch intent with the right extras:

```kotlin
class ChaptersActivity : BaseActivity() {
    class IntentData : Intent {
        constructor(context: Context, bookMetadata: BookMetadata) : super(context, ChaptersActivity::class.java) {
            putExtra("bookUrl", bookMetadata.url)
            putExtra("bookTitle", bookMetadata.title)
        }
    }
}
```

The `app/AppNavigationRoutes` class wires these together — it's the concrete implementation of `NavigationRoutes`.

### 2.4 `PagedListIteratorState<T>` for paginated lists
Defined in `coreui/states/PagedListIteratorState.kt`. Drives paginated UI lists with auto-fetch-when-near-end via `ListLoadWatcher` / `ListGridLoadWatcher`. See `CORE_ENGINE.md` §3 for the full API.

Used by: `SourceCatalogScreen`, `DatabaseSearchScreen`, `GlobalSourceSearchScreen`.

### 2.5 Hybrid Compose + ViewBinding (reader only)
The reader uses a `ListView` + `ArrayAdapter` for the chapter text itself (for performance with very long chapters), with Compose overlaying the top/bottom bars and dialogs. This is the **only** feature that still uses legacy XML views.

```kotlin
class ReaderActivity : BaseActivity() {
    private val viewBind by viewBinding(ActivityReaderBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(viewBind.root)  // ListView
        setContent { ReaderScreen(...) }  // Compose overlay
    }
}
```

All other features are pure Compose.

### 2.6 `AnimatedTransition` for tab/page swapping
`MainActivity` uses `AnimatedTransition` (in `coreui/components/AnimatedTransition.kt`) over a saved `activePageIndex` to switch between Library / Finder / Settings — no Jetpack Navigation Compose graph.

## 3. Feature: Reader (`features/reader`)

The most complex feature. Hosts the chapter reader with infinite scroll, live translation, TTS with media controls, and extensive settings.

### 3.1 Architecture

```
ReaderActivity
  ├─ ReaderViewModel @HiltViewModel (implements ReaderStateBundle)
  │     └─ readerManager.initiateOrGetSession(bookUrl, chapterUrl)
  │           └─ ReaderSessionProvider.create(...)
  │                 └─ ReaderSession.init()
  │                       ├─ initLoadData() → loads book, chapter, chaptersList, translator
  │                       │     └─ readerChaptersLoader.tryLoadInitial(chapterIndex)
  │                       │           └─ addChapter(chapterIndex)
  │                       │                 ├─ Insert ReaderItem.Title + Progressbar
  │                       │                 ├─ readerRepository.downloadChapter(chapterUrl)
  │                       │                 │     └─ appRepository.chapterBody.fetchBody(urlChapter)
  │                       │                 │           (cache → stale-cache guard → network fetch)
  │                       │                 ├─ textToItemsConverter(text) → List<ReaderItem.Body|Image>
  │                       │                 ├─ If translatorIsActive(): translate each Body item
  │                       │                 └─ Insert all items + ReaderItem.Divider
  │                       ├─ appRepository.libraryBooks.updateLastReadEpochTimeMilli(bookUrl, now)
  │                       └─ initReaderTTSObservers() — wires TTS end-of-chapter → load next
  │
  ├─ ReaderScreen (Compose)
  │     ├─ TopAppBar (chapter title + progress %)
  │     ├─ AndroidView(factory = { viewBind.root }) — the ListView
  │     └─ BottomBar with 4 toggle panels:
  │           ├─ LiveTranslation
  │           ├─ TextToSpeech
  │           ├─ Style (font/size/theme)
  │           └─ More (text selection, keep screen on, fullscreen)
  │
  └─ NarratorMediaControlsService (foreground service with MediaStyle notification)
```

### 3.2 `ReaderItem` types (11 view types for the ArrayAdapter)

| Type | Purpose |
|---|---|
| `Title` | Chapter title (clickable: scroll to top) |
| `Body` | Paragraph of text |
| `Image` | Embedded image (parsed from `BookTextMapper.ImgEntry` XML) |
| `Divider` | Visual separator between chapters |
| `Progressbar` | Loading indicator |
| `Translating` | "Translating..." placeholder during ML Kit translation |
| `GoogleTranslateAttribution` | "Translated by Google" footer (after ML Kit translation) |
| (a few more) | |

### 3.3 `ReaderItemAdapter` (ArrayAdapter with 11 view types)

```kotlin
class ReaderItemAdapter(
    context: Context,
    readerSpeaker: ReaderTextToSpeech,
    readerTextSize: State<Float>,
    readerTextFontFamily: State<FontFamily>,
    readerSelectableText: State<Boolean>,
    chaptersStats: Map<ChapterUrl, ChapterStats>
) : ArrayAdapter<ReaderItem>(context, 0)
```

- `getItemViewType(position)` returns the view type ID based on `ReaderItem` subtype
- `getView(position, convertView, parent)` inflates the appropriate XML layout (`activity_reader_list_item_*.xml`)
- `getItemReadingStateBackground(item)` returns the highlight color if the item is currently being spoken by TTS

### 3.4 Chapter loading flow

```
ReaderChaptersLoader.addChapter(chapterIndex)
  ├─ Insert ReaderItem.Title + ReaderItem.Progressbar immediately
  ├─ readerRepository.downloadChapter(chapterUrl)  [on Dispatchers.Default]
  │     └─ appRepository.chapterBody.fetchBody(urlChapter)
  │           ├─ Try cache (ChapterBodyDao.get(url))
  │           │   • If url contains "translate.goog" or "gemini.goog"
  │           │     AND body is primarily CJK → drop cache (re-translate)
  │           │   • Else: return cached body
  │           └─ downloaderRepository.bookChapter(urlChapter)
  │                 → Scraper source fetch + TextExtractor
  ├─ textToItemsConverter(text) → List<ReaderItem.Body|Image>
  │     (splits on \n\n, then delimiterAwareTextSplitter maxSliceLength=512, delimiter='.')
  │     (Image items detected via BookTextMapper.ImgEntry.fromXMLString)
  ├─ If translatorIsActive():
  │     • Insert ReaderItem.Translating(sourceLang, targetLang)
  │     • For each Body item, run translatorTranslateOrNull(text)
  │     • After done, remove Translating; insert GoogleTranslateAttribution
  └─ Insert all items + ReaderItem.Divider
       (preserves scroll position via maintainPosition callbacks)
```

### 3.5 Translation in the reader

`ReaderLiveTranslation(translationManager, appPreferences)` manages ML Kit translation state:

- `state: LiveTranslationSettingData` — enable, source, target, listOfAvailableModels, callbacks → exposed to `TranslatorSettingDialog`
- `translatorState: TranslatorState?` — null when disabled, source==target, or missing source/target
- `onEnable/onSourceChange/onTargetChange` → updates prefs + `translatorState`, emits `_onTranslatorChanged: MutableSharedFlow<Unit>`

`ReaderActivity` collects `onTranslatorChanged` → calls `viewModel.reloadReader()` which calls `readerChaptersLoader.reload()` + `tryLoadRestartedInitial(currentChapter)`.

> **Note**: This is **ML Kit on-device translation** (full flavor only), NOT Gemini. Gemini translation happens at the source level via `TimoTxtGemini` (server-side). See `TRANSLATION_SYSTEM.md`.

### 3.6 TTS integration

`ReaderTextToSpeech` is constructed by `ReaderSession` with: items, `chapterLoadedFlow`, `isChapterIndexValid`/`isChapterIndexLoaded` callbacks, `tryLoadPreviousChapter`/`loadNextChapter` callbacks, voice prefs getters/setters.

Key behaviors:
- Builds a `TextToSpeechManager` with `initialItemState = TextSynthesis(itemPos = ReaderItem.Title(...), playState = FINISHED)`
- **Half-buffer of 2 utterances**: while playing, when queue drops to `halfBuffer` items remaining, pre-fetches the next 4 (`halfBuffer * 2`) utterances for the current chapter
- When queue reaches 0, emits `reachedChapterEndFlowChapterIndex` → `ReaderSession.initReaderTTSObservers()` listens, loads the next chapter if needed, then calls `readChapterStartingFromStart(nextChapterIndex)`
- When `isActive` becomes true, `NarratorMediaControlsService.start(context)` is called (foreground service with media-style notification)

See `TTS_ENGINE.md` for the full TTS architecture.

### 3.7 Reader session lifecycle

`ReaderManager` is `@Singleton`, holds `session: ReaderSession?`:
- `initiateOrGetSession(bookUrl, chapterUrl)`:
  - If same book+chapter → returns existing session, sets `readerViewHandlersActions.introScrollToCurrentChapter = true`
  - Otherwise → closes the old session and creates a new one via `ReaderSessionProvider.create(...)`

`ReaderSession` is **not** `@Inject`-constructed — it's manually `new`'d by the provider with all its dependencies. Owns its own `CoroutineScope(SupervisorJob + Dispatchers.Default + CoroutineName("ReaderSession"))`.

- `currentChapter: ChapterState` is observable; on change, if `SavePositionMode.Reading` (i.e., not speaking), saves the position
- `SavePositionMode` is derived: `Speaking` if `readerTextToSpeech.isSpeaking`, else `Reading`
- `init()`: `initLoadData()` + `updateLastReadEpochTimeMilli` + `initReaderTTSObservers()`
- `close()`: cancels chapter loader children, saves position (mode-dependent), calls `readerTextToSpeech.onClose()` (which calls `service.shutdown()`), cancels scope, stops `NarratorMediaControlsService`
- `reloadReader()`: `readerChaptersLoader.reload()` + `readerTextToSpeech.stop()`

### 3.8 `ReaderRepository` responsibilities

`@Singleton internal class ReaderRepository @Inject constructor(...)`:
- `saveBookLastReadPositionState(bookUrl, newChapter, oldChapter?)` — Room transaction that updates `Book.lastReadChapter` and `Chapter.lastReadPosition`/`lastReadOffset` for both old and new chapters
- `getInitialChapterItemPosition(bookUrl, chapterIndex, chapter)` — returns the saved position OR `(0, 0)` if the chapter is already read but isn't the last-read chapter
- `downloadChapter(chapterUrl)` — delegates to `appRepository.chapterBody.fetchBody`

### 3.9 Reader binary search

`domain/ReaderItemBinarySearch.kt`:
- `indexOfReaderItem` switches: linear search if `list.size < 128`, else binary search
- Binary search handles non-`Position` items by scanning forward to find one within the same chapter
- Tested in `ReaderItemBinarySearchTest`

### 3.10 Fonts

`tools/FontsLoader.kt` — 13 hardcoded Android typeface names:
`casual, cursive, monospace, sans-serif, sans-serif-black, sans-serif-condensed, sans-serif-condensed-light, sans-serif-light, sans-serif-medium, sans-serif-smallcaps, sans-serif-thin, serif, serif-monospace`

Cached `Typeface` and `FontFamily` instances.

### 3.11 ChaptersIsReadRoutine

`tools/ChaptersIsReadRoutine.kt` — tracks per-chapter `startSeen`/`endSeen` in memory; when both are seen, calls `appRepository.bookChapters.setAsRead(chapterUrl, read = true)`. Initial state: if chapter is already `read`, both seen; else none seen.

### 3.12 Media controls service

`services/NarratorMediaControlsService` — `@AndroidEntryPoint` foreground `Service`:
- `startForeground(notificationId, notification)` with a media-style notification built by `NarratorMediaControlsNotification`
- `START_STICKY` if intent is non-null
- Notification actions: Previous, Rewind, Pause/Play (toggled), FastForward, Next
- Compact view shows actions 0/2/4 = Previous/Pause/Next
- PendingIntents built via `MediaButtonReceiver.buildMediaButtonPendingIntent`
- Three coroutines observe the reader session's Compose state and update the notification in real time:
  1. `state.isPlaying` → toggles play/pause action icon
  2. `currentTextPlaying` → updates the chapter title
  3. `currentTextPlaying` → updates the content intent stack (Main → Chapters → Reader for the currently-speaking chapter)
  4. `speakerStats` → updates "Chapter X/N  Progress Y%"
- Notification tap opens a synthetic back-stack: `main → chapters(bookMetadata) → ReaderActivity(scrollToSpeakingItem=true)`

`NarratorMediaControlsCallback` — `MediaSessionCompat.Callback` that routes `KEYCODE_MEDIA_*` keys to `readerTextToSpeech.state.setPlaying/playNextChapter/playPreviousChapter/playNextItem/playPreviousItem`.

## 4. Feature: Settings (`features/settings`)

Single scrollable column with the following sections (in order):

1. **Theme** (`SettingsTheme`) — Follow-system toggle + `Themes.entries` FilterChips
2. **Data** (`SettingsData`) — Database size + Clean database; Images folder size + Clean images folder
3. **Backup** (`SettingsBackup`) — Backup data (uses `onBackupCreate()`) + Restore data (uses `onBackupRestore()`)
4. **Translation models** (only if `translationManager.available`, i.e., `full` flavor) — opens `SettingsTranslationModelsDialog` with per-language download/remove
5. **Gemini AI Translation** (`SettingsGemini`)
6. **Library updates** (`LibraryAutoUpdate`) — Auto-update toggle + interval chips (6h/12h/1d/2d)
7. **App updates** (`AppUpdates`) — Auto-check toggle + Check-now button (with spinner) + `NewAppUpdateDialog` if a new version was found
8. A `(°.°)` ASCII face Spacer at the bottom for fun

### 4.1 `SettingsGemini` composable

`@OptIn(ExperimentalMaterial3Api::class)` because it uses `ExposedDropdownMenuBox` + `ExposedDropdownMenu` + `ExposedDropdownMenuDefaults.TrailingIcon`.

#### API key input
- `OutlinedTextField` with `PasswordVisualTransformation` by default
- "Show API key" `Switch` toggles to plain `VisualTransformation`
- `onApiKeyChange = { state.geminiSettings.apiKey.value = it }` → `AppPreferences.GEMINI_API_KEY.value = it` → persisted to SharedPreferences

#### Model selection
- `ExposedDropdownMenuBox` with preset models:
  - `gemini-2.5-flash` → label "Flash (Best quality, 10 RPM/250 RPD)"
  - `gemini-2.5-flash-lite` → label "Flash Lite (More quota, 30 RPM/1000 RPD)"
  - "Custom model..." → switches to a free-text `OutlinedTextField` for arbitrary model name
- `onModelChange = { state.geminiSettings.model.value = it }` → `AppPreferences.GEMINI_MODEL.value = it`

#### Temperature slider
- `Slider` from 0.0 to 1.0, 9 steps = 10 distinct values
- Hint: `"0.3 = consistent, 0.55 = balanced, 0.7 = creative"`
- `onTemperatureChange = { state.geminiSettings.temperature.value = it }` → `AppPreferences.GEMINI_TEMPERATURE.value = it`

#### Status indicator
- Green "Status: Key configured..." when API key is non-blank
- Red "Status: No API key..." otherwise

> ⚠️ **Known limitation**: `Scraper` is `@Singleton` and reads `appPreferences.GEMINI_API_KEY.value` / `GEMINI_MODEL.value` **once** at construction time. Changes in Settings won't take effect until the app process is restarted. Additionally, `GEMINI_TEMPERATURE` is NOT passed to `TimoTxtGemini`/`GeminiApiClient` — the temperature slider is currently inert. See `fixes.md`.

### 4.2 Settings → Gemini → Scraper → TimoTxtGemini flow

```
SettingsGemini composable
  ├─ OutlinedTextField for API key
  │    onApiKeyChange = { state.geminiSettings.apiKey.value = it }
  │       → AppPreferences.GEMINI_API_KEY.value = it (via MutableState setter)
  │       → persisted to SharedPreferences
  ├─ Model dropdown OR custom text field
  │    onModelChange = { state.geminiSettings.model.value = it }
  │       → AppPreferences.GEMINI_MODEL.value = it
  └─ Temperature slider
       onTemperatureChange = { state.geminiSettings.temperature.value = it }
          → AppPreferences.GEMINI_TEMPERATURE.value = it

AppPreferences.GEMINI_* are SharedPreferences-backed; their `.state(viewModelScope)`
wraps them in Compose MutableState that observes SharedPreferences changes.

Flow from prefs to Scraper:
   Scraper @Singleton @Inject constructor(networkClient, localSource, appPreferences)
      └─ At construction time (singleton, app-startup):
           TimoTxtGemini(
               networkClient = networkClient,
               geminiApiKey = appPreferences.GEMINI_API_KEY.value,    ← captured ONCE
               geminiModel  = appPreferences.GEMINI_MODEL.value,       ← captured ONCE
           )
         Note: GEMINI_TEMPERATURE is NOT passed to TimoTxtGemini / GeminiApiClient.
         GeminiApiClient.buildRequestBody hardcodes "temperature": 0.55.
```

## 5. Feature: Chapters List (`features/chaptersList`)

Top app bar with: back, library-favorite toggle, filter (opens `ChaptersBottomSheet` with sort toggle), overflow menu (open in browser / find in database / resume reading / change cover).

Selection mode swaps the top bar + adds a bottom bar with delete-downloads / download / set-read-unread / set-read-up-to. Floating "Read" button resumes from last read chapter.

### 5.1 Notable behaviors
- `removeCommonTextFromTitles(list)` strips repetitive prefix/suffix from chapter titles (e.g., "Book Name Chapter 1", "Book Name Chapter 2" → "1", "2")
- Long-pressing in selection mode lets you shift-select a range
- Import-from-epub: if `rawBookUrl.isContentUri` and book isn't in DB yet, `epubImporterRepository.importEpubFromContentUri(...)` is called automatically on init

### 5.2 `ChaptersRepository @Singleton`
- `getChaptersSortedFlow(bookUrl)` — combines `chaptersWithContext` flow with `CHAPTERS_SORT_ASCENDING` ternary preference (Active = ascending position, Inverse = descending, Inactive = as-is)
- `downloadBookMetadata(bookUrl, bookTitle)` — parallel-fetches cover + description, inserts a `Book`

## 6. Feature: Library (`features/libraryExplorer`)

Two-tab pager: **Default** (in-library, non-completed books) and **Completed**. Grid of book covers with a red unread-count badge. Pull-to-refresh. Long-press a book → `BookSettingsDialog` (toggle completed). Top-bar actions: filter (ModalBottomSheet with read filter + last-read sort), overflow menu (import EPUB).

## 7. Feature: Catalog Explorer (`features/catalogExplorer`) — Finder tab

Top app bar with: search icon (jumps to `globalSearch("")`), languages icon (opens `LanguagesDropDown` to filter sources by language). Body lists all databases then all sources; each source has pin + (if `Configurable`) settings buttons. Pinned sources bubble to the top.

## 8. Feature: Source Explorer (`features/sourceExplorer`)

Lists books from a single source's catalog. Top app bar with: back, open-in-webview, search (toggles `ToolbarMode.SEARCH`), overflow (layout-mode dropdown: List/Grid). Paged loading via `BooksVerticalView`.

## 9. Feature: Database Explorer (`features/databaseExplorer`)

Two activities: `DatabaseSearchActivity` and `DatabaseBookInfoActivity`.

### 9.1 Database search
- Top app bar with title-text search
- FilterChips (Catalog / Title / Genres)
- Genres mode opens a ModalBottomSheet with ternary-state FilterChips (On=include, Indeterminate=exclude, Off=skip)
- Uses `PersistentCacheDatabaseSearchGenresProvider` to cache genre filters per database
- `PagedListIteratorState<BookMetadata>` fetches from `database.getCatalog(index)` / `database.searchByTitle(index, input)` / `database.searchByFilters(index, includedIds, excludedIds)`

### 9.2 Database book info
- Scrollable detail page with cover image header, title, "Search for sources" button (jumps to `globalSearch(title)`)
- Description (expandable), alternative titles, authors
- Genres (clickable → opens `databaseSearch` with genre filters)
- Tags, related books, similar recommendations

## 10. Feature: Global Source Search (`features/globalSourceSearch`)

Search bar at top; under it a `LinearProgressIndicator` showing `% of sources finished`. Body is a `LazyColumn` of sections: one per source, each rendering a `LazyRow` of `BookImageButtonView`. Loading/error/empty/consumed states per source.

`sourcesResults: SnapshotStateList<SourceResults>` — each entry wraps a `CatalogItem` + a `PagedListIteratorState<BookResult>` that calls `source.catalog.getCatalogSearch(index, searchInput)`. Construction auto-fetches page 0.

## 11. Feature: WebView (`features/webview`)

Top app bar with close + reload. Body is an `AndroidView` wrapping a `WebView` with JS enabled. `WebViewClient.onPageFinished` shows a "Cookies saved" toast — this is how users persist cookies for Cloudflare-protected sources.

Pre-flight checks: `FEATURE_WEBVIEW` available + valid URL authority.

## 12. See Also

- `TTS_ENGINE.md` — TTS manager, MediaSession, half-buffer prefetch
- `TRANSLATION_SYSTEM.md` — ML Kit vs Gemini, reader live translation
- `CORE_ENGINE.md` — `PagedListIteratorState`, `BaseActivity`, navigation
- `DATA_LAYER.md` — chapter caching, source routing
