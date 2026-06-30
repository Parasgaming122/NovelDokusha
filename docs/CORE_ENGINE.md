# NovelDokusha — Core Engine

> **Scope**: Domain primitives (`Response`, `PagedList`), preferences, networking, navigation, file resolution, theming, and Hilt modules shared across the app.

## 1. Module Layout

| Module | Namespace | Purpose |
|---|---|---|
| `core` | `my.noveldokusha.core` | Domain primitives, `AppPreferences`, file/URL utils, theme-independent logic |
| `networking` | `my.noveldokusha.networking` | OkHttp client, interceptors, request builders, `tryConnect` helpers |
| `navigation` | `my.noveldokusha.navigation` | `NavigationRoutes` interface (impl lives in `app`) |
| `coreui` | `my.noveldoksuha.coreui` ⚠️ (typo) | Theme, `BaseActivity`/`BaseViewModel`, Compose components, `NotificationsCenter` |
| `strings` | `my.noveldokusha.strings` | Localized string resources |

> **Package quirk**: `core` uses `my.noveldokusha.core.*`, but `coreui`, `data`, and `databaseExplorer` use `my.noveldoksuha.*` (missing `h`). This is "intentional by repetition" — the source files consistently use the typo'd package. Not breaking, but worth knowing when writing imports.

## 2. `Response<T>` — the universal I/O result type

```kotlin
sealed class Response<out T> {
    data class Success<T>(val data: T) : Response<T>()
    data class Error(val message: String, val exception: Exception) : Response<Nothing>()
}
```

### Extension functions

| Function | Signature | Behavior |
|---|---|---|
| `getOrNull` | `Response<T>.getOrNull(): T?` | Returns data on Success, null on Error |
| `map` | `suspend fun <T, R> Response<T>.map { T → R }: Response<R>` | Async transform on Success |
| `syncMap` | `fun <T, R> Response<T>.syncMap { T → R }: Response<R>` | Synchronous transform |
| `mapError` | `suspend fun <T, R> Response<T>.mapError { Response.Error → R }: Response<R>` | Recover from Error |
| `flatMap` | `suspend fun <T, R> Response<T>.flatMap { T → Response<R> }: Response<R>` | Async flat-map on Success |
| `flatMapError` | `suspend fun <T> Response<T>.flatMapError { Response.Error → Response<T> }: Response<T>` | Recover with another Response |
| `asNotNull` | `fun <T> Response<T?>.asNotNull(): Response<T>` | Convert `Response<T?>` to `Response<T>` (Error if data is null) |
| `flatten` | `fun <T> Response<Response<T>>.flatten(): Response<T>` | Unwrap nested Response |
| `toResult` | `fun <T> Response<T>.toResult(): Result<T>` | Convert to Kotlin `Result` |
| `onSuccess` | `fun <T> Response<T>.onSuccess { T → Unit }: Response<T>` | Side-effect on Success |
| `onError` | `fun <T> Response<T>.onError { Response.Error → Unit }: Response<T>` | Side-effect on Error |
| `toSuccessOrNull` | `fun <T> Response<T>.toSuccessOrNull(): T?` | Like `getOrNull` but as a method |

### Helpers

```kotlin
suspend inline fun <T> tryAsResponse(crossinline call: suspend () -> T): Response<T>
inline fun <T> runCatchingAsResponse(crossinline call: () -> T): Response<T>
```

`tryAsResponse` catches all exceptions except `CancellationException` (rethrown). Used throughout the scraper/data layers.

## 3. `PagedList<T>` — paginated list result

```kotlin
data class PagedList<T>(
    val list: List<T>,
    val index: Int,
    val isLastPage: Boolean
) {
    val hasNoNextPage: Boolean get() = list.isEmpty() || isLastPage

    companion object {
        fun <T> createEmpty(index: Int) = PagedList(list = emptyList(), index = index, isLastPage = true)
    }
}
```

Used by all `getCatalogList` / `getCatalogSearch` source methods.

### `PagedListIteratorState<T>` (in `coreui/states/`)

Stateful wrapper that drives paginated UI lists:

```kotlin
class PagedListIteratorState<T>(
    coroutineScope: CoroutineScope,
    list: SnapshotStateList<T> = mutableStateListOf(),
    fn: suspend (index: Int) -> Response<PagedList<T>>
)
```

- `state: IteratorState` — `IDLE` / `LOADING` / `CONSUMED`
- `error: Response.Error?`
- `fetchNext()` — only fires if `IDLE`; sets `LOADING`, calls `fn(index)`, appends results, sets `IDLE` or `CONSUMED` (if `isLastPage`)
- `reset()`, `reloadFailedLastLoad()`, `setFunction(fn)`
- `hasFinished: Boolean` — `state == CONSUMED || (state == IDLE && list.size != 0) || error != null`

Used by `BooksVerticalView` (in `coreui/components/`) via `ListLoadWatcher` / `ListGridLoadWatcher` which auto-call `fetchNext()` when the user scrolls near the end.

## 4. `AppPreferences` — the central preference store

```kotlin
@Singleton
class AppPreferences @Inject constructor(@ApplicationContext context: Context)
```

Uses `PreferenceManager.getDefaultSharedPreferences(context)`. Each preference is an `object : Preference<T>("KEY")` with a `value: T` property backed by a `SharedPreference_*` delegate.

### Full preference key table

| Property name | Storage key | Type | Default |
|---|---|---|---|
| `THEME_ID` | `"THEME_ID"` | `PreferenceThemes` enum | `Light` |
| `THEME_FOLLOW_SYSTEM` | `"THEME_FOLLOW_SYSTEM"` | Boolean | `true` |
| `READER_FONT_SIZE` | `"READER_FONT_SIZE"` | Float | `14f` |
| `READER_FONT_FAMILY` | `"READER_FONT_FAMILY"` | String | `"serif"` |
| `READER_TEXT_TO_SPEECH_VOICE_ID` | same | String | `""` |
| `READER_TEXT_TO_SPEECH_VOICE_SPEED` | same | Float | `1f` |
| `READER_TEXT_TO_SPEECH_VOICE_PITCH` | same | Float | `1f` |
| `READER_TEXT_TO_SPEECH_SAVED_PREDEFINED_LIST` | same | `List<VoicePredefineState>` (JSON) | `listOf()` |
| `READER_SELECTABLE_TEXT` | same | Boolean | `false` |
| `READER_KEEP_SCREEN_ON` | same | Boolean | `false` |
| `READER_FULL_SCREEN` | same | Boolean | `true` |
| `CHAPTERS_SORT_ASCENDING` | same | `TernaryState` | `Active` |
| `SOURCES_LANGUAGES_ISO639_1` | `"SOURCES_LANGUAGES"` | `Set<String>` | `setOf("en")` |
| `FINDER_SOURCES_PINNED` | same | `Set<String>` | `setOf()` |
| `LIBRARY_FILTER_READ` | same | `TernaryState` | `Inactive` |
| `LIBRARY_SORT_LAST_READ` | same | `TernaryState` | `Inverse` |
| `BOOKS_LIST_LAYOUT_MODE` | same | `ListLayoutMode` | `VerticalGrid` |
| `GLOBAL_TRANSLATION_ENABLED` | same | Boolean | `false` |
| `GLOBAL_TRANSLATION_PREFERRED_SOURCE` | `"GLOBAL_TRANSLATIOR_PREFERRED_SOURCE"` ⚠️ (typo) | String | `"en"` |
| `GLOBAL_TRANSLATION_PREFERRED_TARGET` | same | String | `""` |
| `GLOBAL_APP_UPDATER_CHECKER_ENABLED` | same | Boolean | `true` |
| `GLOBAL_APP_AUTOMATIC_LIBRARY_UPDATES_ENABLED` | same | Boolean | `true` |
| `GLOBAL_APP_AUTOMATIC_LIBRARY_UPDATES_INTERVAL_HOURS` | same | Int | `24` |
| **`GEMINI_API_KEY`** | `"GEMINI_API_KEY"` | String | `""` |
| **`GEMINI_MODEL`** | `"GEMINI_MODEL"` | String | `"gemini-2.5-flash"` |
| **`GEMINI_TEMPERATURE`** | `"GEMINI_TEMPERATURE"` | Float | `0.55f` |
| `@Deprecated LOCAL_SOURCES_URI_DIRECTORIES` (HIDDEN) | `"LOCAL_SOURCES_URI_DIRECTORIES"` | `Set<String>` | `setOf()` |
| `@Deprecated LIBRARY_SORT_READ` (HIDDEN) | `"LIBRARY_SORT_READ"` | `TernaryState` | `Active` |

> ⚠️ **Typo**: The storage key `"GLOBAL_TRANSLATIOR_PREFERRED_SOURCE"` (TRANSLATIOR with an extra I) is kept for backward-compat — do NOT "fix" it without a migration.

### `Preference<T>` API

```kotlin
abstract inner class Preference<T>(val name: String) {
    abstract var value: T
    fun flow(): Flow<T>            // emits on SharedPreferences change
    fun state(scope: CoroutineScope): MutableState<T>  // Compose state
}
```

The `toFlow()` helper registers an `OnSharedPreferenceChangeListener` on subscription. The `toState()` helper wraps a Compose `mutableStateOf` and updates it from the flow on `Dispatchers.Main`.

> ⚠️ The `toState()` helper is self-described as *"probably some details wrong. Use only OUTSIDE of composable scope (e.g. viewModel)"*. In practice it's used inside `SettingsViewModel` and feature ViewModels, never directly in composables.

### Supporting types

| Type | Definition |
|---|---|
| `TernaryState` | `enum { Active, Inverse, Inactive }` with `next()` cycle `Active→Inverse→Inactive→Active` |
| `ListLayoutMode` | `enum { VerticalList, VerticalGrid }` |
| `PreferenceThemes` | `enum { Light, Dark, Black }` |
| `VoicePredefineState` | `@Serializable data class(savedName, voiceId, pitch, speed)` |

## 5. `LanguageCode`

```kotlin
enum class LanguageCode(val iso639_1: String, @StringRes val nameResId: Int) {
    ENGLISH("en", R.string.language_english),
    PORTUGUESE("pt", R.string.language_portuguese),
    SPANISH("es", R.string.language_spanish),    // unused
    FRENCH("fr", R.string.language_french),      // unused
    INDONESIAN("id", R.string.language_indonesian),
    CHINESE("zh", R.string.language_chinese)     // unused
}
```

Used by `SourceInterface.Catalog.language` and `ScraperRepository.sourcesLanguagesListFlow()`.

## 6. `AppFileResolver` — local file paths

```kotlin
@Singleton
class AppFileResolver @Inject constructor(@ApplicationContext context: Context)
```

- `folderBooks = File(context.filesDir, "books")`
- `COVER_PATH_RELATIVE_TO_BOOK = "__cover_image"`

### API

| Method | Returns |
|---|---|
| `getLocalIfContentType(url, bookFolderName)` | Converts `content://` URIs to `local://bookFolderName` |
| `getLocalBookCoverPath()` | `"local://__cover_image"` |
| `getLocalBookChapterPath(bookFolderName, chapterName)` | `local://<bookFolderName>/<chapterName>` |
| `getLocalBookPath(bookFolderName)` | `local://<bookFolderName>` |
| `getStorageBookCoverImageFile(bookFolderName)` | `File(folderBooks, "$bookFolderName/$COVER_PATH_RELATIVE_TO_BOOK")` |
| `getStorageBookImageFile(bookFolderName, imagePath)` | `File(folderBooks, "$bookFolderName/$imagePath")` |
| `getLocalBookFolderName(bookUrl)` | Base64-encoded HTTPS book URL (for stable folder names); strips `local://` prefix for local URLs |
| `resolvedBookImagePath(bookUrl, imagePath)` | `String` for HTTPS/content-URI images, `File` for local |

### Composable helper
```kotlin
@Composable
fun rememberResolvedBookImagePath(bookUrl: String, imagePath: String): Any
```

## 7. `BookTextMapper` — embedded image XML format

`object BookTextMapper` with `data class ImgEntry(path: String, yrel: Float)`.

Serializes book images embedded in chapter text as XML. Two formats are supported:

### V0 (legacy, deprecated)
```xml
<img yrel="0.50">/path/to/img</img>
```
Parsed with `DocumentBuilderFactory` (W3C DOM). Validated by regex `^\W*<img .*>.+</img>\W*$`.

### V1 (current)
```xml
<img src="path" yrel="0.50" />
```
Parsed with Jsoup. `toXMLString()` always emits V1.

`ImgEntry.fromXMLString(text)` tries V0 first, then V1 (back-compat read path).

## 8. Networking Module

### 8.1 `NetworkClient` interface

```kotlin
interface NetworkClient {
    suspend fun call(request: Request.Builder, followRedirects: Boolean = false): Response
    suspend fun get(url: String): Response
    suspend fun get(url: Uri.Builder): Response
}
```

### 8.2 `ScraperNetworkClient` implementation

```kotlin
@Singleton
class ScraperNetworkClient @Inject constructor(
    @ApplicationContext appContext: Context,
    appInternalState: AppInternalState
) : NetworkClient
```

**OkHttp configuration**:
- `cacheDir = File(appContext.cacheDir, "network_cache")`, `cacheSize = 5 MiB`
- `cookieJar = ScraperCookieJar()` — bridges OkHttp ↔ `android.webkit.CookieManager`
- `HttpLoggingInterceptor` with `Level.BODY` via Timber — only added if `appInternalState.isDebugMode`
- Interceptors in order:
  1. `UserAgentInterceptor` — adds `Mozilla/5.0 (Windows NT 6.3; WOW64)` if missing
  2. `DecodeResponseInterceptor` — handles `Content-Encoding: br` (Brotli) and `gzip`
  3. `CloudFareVerificationInterceptor(appContext)` — WebView-based bypass
- `connectTimeout(30s)`, `readTimeout(30s)`
- Two clients: `client` (no redirects) and `clientWithRedirects` (built via `client.newBuilder().followRedirects(true).followSslRedirects(true)`)

### 8.3 OkHttp extensions

```kotlin
suspend fun Call.await(): Response                    // via suspendCoroutine + enqueue
suspend fun OkHttpClient.call(builder: Request.Builder): Response
fun Response.toDocument(): Document                   // Jsoup.parse(body.string())
fun Response.toJson(): JsonElement                    // JsonParser.parseString(body.string())
```

### 8.4 Request builders

```kotlin
fun getRequest(url: String, headers = DEFAULT_HEADERS, cache = DEFAULT_CACHE_CONTROL): Request.Builder
fun postRequest(url: String, headers = DEFAULT_HEADERS, body: RequestBody, cache = DEFAULT_CACHE_CONTROL): Request.Builder
fun Request.Builder.postPayload(scope: FormBody.Builder.() -> Unit): Request.Builder
```

- `DEFAULT_HEADERS` includes the standard browser User-Agent
- `DEFAULT_CACHE_CONTROL = CacheControl.Builder().maxAge(10, TimeUnit.MINUTES).build()`

### 8.5 `tryConnect` / `tryFlatConnect`

```kotlin
suspend fun <T> tryFlatConnect(extraErrorInfo: String = "", call: suspend () -> Response<T>): Response<T>
suspend fun <T> tryConnect(extraErrorInfo: String = "", call: suspend () -> T): Response<T>
```

- `tryConnect` wraps `tryAsResponse { call() }.specifyNetworkErrors(...)`
- `tryFlatConnect` does the same but also calls `.flatten()` (unwraps `Response<Response<T>>`)
- `specifyNetworkErrors()` distinguishes `SocketTimeoutException` ("Timeout error.") from other exceptions ("Unknown error." + full stacktrace)

### 8.6 Interceptors

#### `UserAgentInterceptor`
Adds `"User-Agent: Mozilla/5.0 (Windows NT 6.3; WOW64)"` header if the request has no User-Agent.

#### `DecodeResponseInterceptor`
Handles `Content-Encoding: br` (via `org.brotli.dec.BrotliInputStream`) and `gzip` (via `okio.GzipSource`). Strips `Content-Encoding` / `Content-Length` headers and sets body length to -1.

#### `CloudFareVerificationInterceptor`
Detects CloudFlare responses: status code in `[403, 503]` AND `Server` header in `["cloudflare-nginx", "cloudflare"]`.

Bypass mechanism:
1. Uses a `ReentrantLock` to serialize bypass attempts.
2. Removes old `cf_clearance` cookie from `CookieManager`.
3. Spawns a `WebView` on `Dispatchers.Main` with JS enabled, DOM storage, database, third-party cookies.
4. Loads the URL with request headers.
5. `delay(20.seconds)` to allow the CloudFlare JS challenge to complete.
6. Stops/destroys the WebView.
7. Re-runs the request.
8. Throws `CloudfareVerificationBypassFailedException` if still blocked.

> ⚠️ **Concern**: Hard-coded 20-second `delay(20.seconds)` blocks the calling OkHttp thread. The comment acknowledges: *"This will won't be often executed so no need for eager delay exit"*.

### 8.7 `ScraperCookieJar`
Bridges OkHttp ↔ `android.webkit.CookieManager`:
- `loadForRequest(url)` — parses the WebView cookie string for the URL
- `saveFromResponse(url, cookies)` — calls `manager.setCookie(url.toString(), "name=value")` for each cookie then `flush()`

This bridge is needed because CloudFlare bypass uses WebView cookies.

### 8.8 URI helpers (`Utils.kt`)

```kotlin
fun String.toUrlBuilderSafe(): Uri.Builder   // null-safe
fun String.toUrl(): Uri
fun String.toUrlBuilder(): Uri.Builder
fun Uri.Builder.ifCase(case: Boolean, action: Uri.Builder.() -> Unit): Uri.Builder
fun Uri.Builder.addPath(vararg path: String): Uri.Builder
fun Uri.Builder.add(vararg query: Pair<String, Any>): Uri.Builder
fun Uri.Builder.add(key: String, value: Any): Uri.Builder
```

## 9. Navigation Module

### `NavigationRoutes` interface

```kotlin
interface NavigationRoutes {
    fun main(context: Context): Intent
    fun chapters(context: Context, bookMetadata: BookMetadata): Intent
    fun webView(context: Context, url: String): Intent
    fun reader(context: Context, bookUrl: String, chapterUrl: String, scrollToSpeakingItem: Boolean = false): Intent
    fun databaseSearch(context: Context, input: String, databaseUrlBase: String = "https://www.novelupdates.com/"): Intent
    fun databaseSearch(context: Context, databaseBaseUrl: String): Intent  // overload
    fun globalSearch(context: Context, text: String): Intent
    fun sourceCatalog(context: Context, sourceBaseUrl: String): Intent
}
```

### `NavigationRouteViewModel`
```kotlin
@HiltViewModel
class NavigationRouteViewModel @Inject constructor(
    private val appNavigationRoutes: NavigationRoutes
) : NavigationRoutes by appNavigationRoutes, ViewModel()
```

Exposes the routes through a ViewModel so Compose can call them without injecting the `NavigationRoutes` directly.

The concrete implementation (`AppNavigationRoutes`) lives in the `app` module.

## 10. CoreUI Module

### 10.1 Theme system

`@Composable fun Theme(themeProvider: ThemeProvider, content)` picks the theme based on `followSystem` and system dark mode, then calls `InternalTheme(theme, content)`.

Three full M3 `ColorScheme` instances: `light_colorScheme`, `dark_colorScheme`, `black_colorScheme` (true black for AMOLED).

`AppColor` data class adds app-specific colors: `tabSurface`, `bookSurface`, `checkboxPositive/Negative/Neutral`, `tintedSurface`, `tintedSelectedSurface`. Exposed via `LocalAppColor` compositionLocal.

```kotlin
enum class Themes(isLight: Boolean, @StringRes nameId: Int, themeId: Int) {
    LIGHT(true, R.string.theme_name_light, R.style.AppTheme_Light),
    DARK(false, R.string.theme_name_dark, R.style.AppTheme_Dark),
    BLACK(false, R.string.theme_name_black, R.style.AppTheme_Black)
}
```

### 10.2 `BaseActivity` / `BaseViewModel`

```kotlin
@AndroidEntryPoint
open class BaseActivity : AppCompatActivity() {
    @Inject lateinit var themeProvider: ThemeProvider
    @Inject lateinit var toasty: Toasty
    // Lazily creates AppPreferences(applicationContext) — separate from the Hilt-injected one
}

@HiltViewModel
open class BaseViewModel @Inject constructor() : ViewModel()
```

> ⚠️ **Concern**: `BaseActivity` lazily creates its own `AppPreferences(applicationContext)` instance instead of injecting the Hilt singleton. Reads/writes go to the same backing SharedPreferences, but the `OnSharedPreferenceChangeListener` flow machinery is duplicated across the two instances.

### 10.3 `NotificationsCenter`

```kotlin
@Singleton
class NotificationsCenter @Inject constructor(@ApplicationContext context: Context) {
    fun showNotification(channelId, channelName, notificationId, importance = DEFAULT, builder)
    fun close(notificationId)
    fun modifyNotification(builder, notificationId, modifierBlock)
}
```

Helpers:
- `NotificationCompat.Builder.removeProgressBar()` extension
- `var NotificationCompat.Builder.title: String` and `var ... .text: String` — auto-call `setContentTitle` / `setContentText` on set
- Uses `R.mipmap.ic_logo` as the small icon

### 10.4 Compose components

Key reusable composables in `coreui/components/`:

| Component | Purpose |
|---|---|
| `RoundedContentLayout` | Row with `backgroundCircle().outlineCircle()` |
| `ErrorView` | Red-bordered box with optional reload/copy buttons + selectable monospace text |
| `BookSettingsDialog` | AlertDialog with cover, title, "Completed" checkbox |
| `PosNegCheckbox` | TriStateCheckbox with positive color for On/Off, negative for Indeterminate |
| `BooksVerticalView` | LazyVerticalGrid (1 col for list, adaptive for grid) with paginated loading + CloudFlare error items |
| `MyButton` | Full-featured button with `combinedClickable`, border, animateColor/Size, selected state |
| `MySlider` | Custom draggable slider (no M3 Slider) with overlay content support |
| `TernaryStateToggle` | AnimatedContent icon transitions for ternary state |
| `BookImageButtonView` | Book cover with overlaid or below title |
| `CollapsibleDivider` | Animates divider alpha based on scroll |
| `AnimatedTransition` | Wraps `AnimatedContent` with default fade (220ms in, 90ms out) |
| `TopAppBarSearch` | M3 TopAppBar with embedded TextField |
| `ExpandableText` | Clickable text that expands with vertical-gradient fade when collapsed |
| `Section` | Card with optional title in ColorAccent |

## 11. Hilt Modules

| Module | Provides |
|---|---|
| `CoreModule` (in `core`) | `AppCoroutineScope` singleton: `SupervisorJob() + Dispatchers.Main.immediate + CoroutineName("App")` |
| `NetworkingModule` (in `networking`) | `ScraperNetworkClient → NetworkClient` as `@Singleton` |
| `LocalDatabaseModule` (in `tooling/local_database`) | `AppDatabase` (name=`"bookEntry"`) + each DAO as singleton |
| `CoreUIModule` (in `coreui`) | `AppThemeProvider → ThemeProvider` as `@Singleton` |
| `AppModule` (in `app`) | `AppNavigationRoutes → NavigationRoutes`, `ToastyToast → Toasty`, `App`, `AppInternalState` |

## 12. Custom Exceptions

```kotlin
class WebViewCookieManagerInitializationFailedException : IOException
class CloudfareVerificationBypassFailedException : IOException
```

Both live in `core/domain/CustomExceptions.kt`.

## 13. Utility Delegates

### `SharedPreference_*` (in `core/SharedPreferenceDelegates.kt`)
Internal property delegate classes for `SharedPreferences`:
- `SharedPreference_Serializable<T>` (JSON via kotlinx.serialization)
- `SharedPreference_Enum<T:Enum<T>>`
- `SharedPreference_Int`, `SharedPreference_Float`, `SharedPreference_String`
- `SharedPreference_StringSet`, `SharedPreference_Boolean`

All persist via `edit().apply()`.

### `Extra_*` / `StateExtra_*` (in `core/utils/`)
Property delegates for type-safe intent extras and SavedStateHandle:
- `Extra_StringArrayList`, `Extra_String`, `Extra_StringNullable`, `Extra_Parcelable<T>`, `Extra_Uri`, `Extra_Int`, `Extra_Float`, `Extra_Boolean`
- `StateExtra_StringArrayList`, `StateExtra_String`, `StateExtra_StringNullable`, `StateExtra_Parcelable<T>`, `StateExtra_Uri`, `StateExtra_Int`, `StateExtra_Float`, `StateExtra_Boolean`

`StateExtra_*` uses `property.name` as the SavedStateHandle key.

### `LiveEvent<T>`
```kotlin
class LiveEvent<T> : MutableLiveData<T>()
```
Single-shot event LiveData — skips observers registered before the value was set (by comparing `setTime` vs `observerTime`). Used for one-off navigation events.

## 14. See Also

- `DATA_LAYER.md` — repositories, Room, source-routing
- `UI_LAYER.md` — feature modules, BaseActivity usage
- `TRANSLATION_SYSTEM.md` — Gemini API client (bypasses NetworkClient)
- `DEPENDENCIES.md` — networking library versions
