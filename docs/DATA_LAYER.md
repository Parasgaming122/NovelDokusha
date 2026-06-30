# NovelDokusha — Data Layer

> **Scope**: Repositories, Room database, source-routing, chapter caching, backup/restore.

## 1. Module Layout

The data layer lives in two modules:

| Module | Namespace | Purpose |
|---|---|---|
| `data` | `my.noveldoksuha.data` ⚠️ (typo: `noveldoksuha` not `noveldokusha`) | 8 repositories + 2 interactors + mappers |
| `tooling/local_database` | `my.noveldokusha.tooling.local_database` | Room DB, 3 entities, 3 DAOs, 7 migrations |

The `data` module depends on `core`, `networking`, `scraper`, `tooling/local_database`, and `tooling/epub_parser`. It does NOT depend on any feature module or on `coreui` — repositories are UI-agnostic.

## 2. Repositories

All repositories are `@Singleton @Inject constructor` and live in `data/src/main/java/my/noveldoksuha/data/`.

### 2.1 `AppRepository` — top-level aggregator

Holds references to the other 7 repositories and exposes a `Settings` inner class. Key responsibilities:

- `toggleBookmark(bookUrl, bookTitle)` — if URL is a `content://` URI (EPUB import), delegates to `EpubImporterRepository`; otherwise calls `libraryBooks.toggleBookmark`.
- `getDatabaseSizeBytes()` — returns the on-disk size of the Room SQLite file.
- `close()`, `delete()`, `vacuum()` — database lifecycle.
- `eventDataRestored: MutableSharedFlow<Unit>` — emitted by `RestoreDataService` after a backup restore completes (consumed by `SettingsViewModel` to refresh UI).
- `Settings.clearNonLibraryData()` — deletes non-library Books, their Chapters, and orphaned ChapterBodies.

### 2.2 `LibraryBooksRepository`

| Method | SQL / Behavior |
|---|---|
| `getBooksInLibraryWithContextFlow` | `SELECT Book.*, COUNT(Chapter.read) AS chaptersCount, SUM(Chapter.read) AS chaptersReadCount FROM Book LEFT JOIN Chapter ON Chapter.bookUrl = Book.url WHERE Book.inLibrary == 1 GROUP BY Book.url` |
| `getFlow(url)` | `Flow<Book?>` |
| `insert(book)` | Filtered by `isValid` (URL matches `^(https?|local)://.*`) |
| `insertReplace(books)` | Bulk insert with REPLACE strategy |
| `remove(bookUrl)`, `update(book)` | Standard CRUD |
| `updateLastReadEpochTimeMilli(bookUrl, time)` | |
| `updateCover(bookUrl, coverUrl)` | |
| `updateDescription(bookUrl, description)` | |
| `updateLastReadChapter(bookUrl, chapterUrl)` | |
| `toggleBookmark(bookUrl, bookTitle)` | Atomic transaction: if book missing, insert with `inLibrary=true`; otherwise flip `inLibrary`. Returns new state. |
| `saveImageAsCover(imageUri, bookUrl)` | Reads bytes from ContentResolver, writes to `appFileResolver.getStorageBookCoverImageFile(bookFolderName)`, then `updateCover(bookUrl, appFileResolver.getLocalBookCoverPath())` |

### 2.3 `BookChaptersRepository`

| Method | Behavior |
|---|---|
| `chapters(bookUrl)` | `WHERE bookUrl == :bookUrl ORDER BY position ASC` |
| `getChaptersWithContextFlow(bookUrl)` | `Flow<List<ChapterWithContext>>` — LEFT JOIN ChapterBody (downloaded flag) + Book (lastReadChapter flag) |
| `merge(newChapters, bookUrl)` | Fetches current chapters, merges by URL keeping new chapter's `position` only |
| `setAsRead(chaptersUrl: List<String>)` | Chunked by 500 |
| `setAsUnread(chaptersUrl: List<String>)` | Chunked by 500 |
| `updatePosition(chapterUrl, lastReadPosition, lastReadOffset)` | |
| `updateTitle(url, title)` | |
| `getFirstChapter(bookUrl)` | `ORDER BY position ASC LIMIT 1` |
| `removeAllFromBook(bookUrl)` | |

### 2.4 `ChapterBodyRepository` — the chapter-text cache

This is the most complex repository because it implements the stale-cache guard and coordinates with `DownloaderRepository` for network fetches.

#### `fetchBody(urlChapter, tryCache = true): Response<String>`

```
1. If tryCache AND ChapterBody row exists for urlChapter:
   a. STALE-CACHE GUARD:
      If urlChapter.contains("translate.goog") OR urlChapter.contains("gemini.goog")
         AND isPrimarilyCJK(cachedBody)   // CJK ratio > 0.12
      THEN: delete the cached row and re-fetch from network.
      ELSE: return Response.Success(cachedBody).

2. If urlChapter is "local://" AND no cache:
   Return Response.Error("Source is local but chapter content missing.")

3. Otherwise:
   downloaderRepository.bookChapter(urlChapter)
     .map { body, title ->
       // Persist in a Room transaction:
       //   - ChapterBody(url=urlChapter, body=body)
       //   - Chapter.title = title (if non-null)
       return Response.Success(body)
     }
```

#### Why the stale-cache guard exists
Before the source-routing fix in `DownloaderRepository.bookChapter` (see §2.5), `translate.goog` URLs would redirect to `timotxt.com`, match the plain `TimoTxt` source (no translation), and cache **untranslated Chinese text**. The guard detects this situation (cached CJK text for a translation-proxy URL) and forces a re-fetch so the translation source can re-translate.

The guard remains as a safety net for users who already have bad cached data from before the fix.

#### `isPrimarilyCJK(text)` — local helper (mirrors scraper module)
Counts chars in CJK ranges:
- CJK Unified Ideographs: `0x4E00–0x9FFF`
- CJK Extension A: `0x3400–0x4DBF`
- CJK Extension B: `0x20000–0x2A6DF`
- CJK Compatibility: `0xF900–0xFAFF`
- Hiragana: `0x3040–0x309F`
- Katakana: `0x30A0–0x30FF`

Returns true if `cjkChars / nonWhitespaceCount > 0.12f`. This duplicates the logic in `TimoTxtGemini.isPrimarilyCJK` to avoid a cross-module dependency.

### 2.5 `DownloaderRepository` — source routing & network fetching

#### `bookChapter(chapterUrl): Response<ChapterDownload>`

```kotlin
suspend fun bookChapter(chapterUrl: String): Response<ChapterDownload> {
    return tryFlatConnect {
        // 1. Resolve source from the ORIGINAL chapter URL FIRST (before redirects).
        val originalSource = scraper.getCompatibleSource(chapterUrl)

        val realUrl = if (originalSource != null) {
            // Source matched the original URL — keep it as-is.
            // transformChapterUrl() will handle any URL transformations
            // (e.g. translate.goog → timotxt.com) on the source side.
            chapterUrl
        } else {
            // No source matched the original URL — follow redirects in case
            // the URL is a shortener/alias that redirects to a known domain.
            val request = getRequest(chapterUrl)
            networkClient.call(request, followRedirects = true).request.url.toString()
        }

        val source = originalSource ?: scraper.getCompatibleSource(realUrl)

        source?.also {
            val doc = networkClient.get(it.transformChapterUrl(realUrl)).toDocument()
            val data = ChapterDownload(
                body = it.getChapterText(doc) ?: return@also,
                title = it.getChapterTitle(doc)
            )
            return@tryFlatConnect Response.Success(data)
        }

        // No predefined source matched → heuristic extraction with Readability4J
        val chapter = heuristicChapterExtraction(realUrl, networkClient.get(realUrl).toDocument())
        ...
    }
}
```

**Why the original-URL-first check matters**: sources like `TimoTxtTranslate` and `TimoTxtGemini` store chapter URLs under fake domains (`translate.goog` / `gemini.goog`), but the content actually lives on `timotxt.com`. If redirects were followed first, the proxy would redirect to `timotxt.com`, and `scraper.getCompatibleSource(...)` would match the plain `TimoTxt` source (no translation), causing untranslated Chinese text to be cached.

By resolving the source against the **original** URL first, the translate/gemini source is selected, then its `transformChapterUrl(realUrl)` performs the appropriate domain rewrite (`translate.goog` → `timotxt.com` or `gemini.goog` → `timotxt.com`) before the HTTP fetch.

#### Other methods
- `bookCoverImageUrl(bookUrl): Response<String?>` — calls `scraper.getCompatibleSourceCatalog(bookUrl)` then `scrap.getBookCoverImageUrl(bookUrl)`.
- `bookDescription(bookUrl): Response<String?>` — same pattern.
- `bookChaptersList(bookUrl): Response<List<Chapter>>` — fetches chapter list, maps to `Chapter(title, url, bookUrl, position=index)`.
- `heuristicChapterExtraction(url, document)` — `Readability4JExtended(url, document).parse()` extracts `articleContent`; passes through `TextExtractor.get(content)`; returns `ChapterDownload(body, title)`.

### 2.6 `ScraperRepository`

Wraps `Scraper` and combines it with `AppPreferences` to produce reactive flows:

- `databaseList(): List<DatabaseInterface>` — `scraper.databasesList.toList()`
- `sourcesCatalogListFlow(): Flow<List<CatalogItem>>` — combines `SOURCES_LANGUAGES_ISO639_1` and `FINDER_SOURCES_PINNED` preferences; filters catalogs by active languages; tags each with `pinned = it.id in pinnedSourcesIds`; sorts pinned-first.
- `sourcesLanguagesListFlow(): Flow<List<LanguageItem>>` — maps active-languages set to list of `LanguageItem`.

### 2.7 `AppRemoteRepository`

- `getLastAppVersion(): Response<RemoteAppVersion>` — GET `https://api.github.com/repos/nanihadesuka/NovelDokusha/releases/latest`, parses `tag_name` and `html_url`.
- `getCurrentAppVersion()` — `AppVersion.fromString(appInternalState.versionName)`.

### 2.8 `EpubImporterRepository`

- `importEpubFromContentUri(contentUri, bookTitle, addToLibrary)` — opens stream via ContentResolver, calls `epubParser(inputStream)`, then `epubImporter(...)`.
- `epubImporter(storageFolderName, epub, addToLibrary)` — on `Dispatchers.IO`:
  1. Compute `localBookUrl = appFileResolver.getLocalBookPath(storageFolderName)`.
  2. Clean previous entries for this book (chapters, bodies, library row).
  3. If `epub.coverImage != null`, write to `getStorageBookCoverImageFile(storageFolderName)`.
  4. Insert `Book(title=storageFolderName, url=localBookUrl, coverImageUrl=appFileResolver.getLocalBookCoverPath(), inLibrary=addToLibrary)`.
  5. Insert chapters as `Chapter(title, url=getLocalBookChapterPath(storageFolderName, chapter.absPath), bookUrl=localBookUrl, position=i)`.
  6. Insert `ChapterBody(url=same, body=chapter.body)` for each.
  7. Concurrently write each image via `fileImporter(getStorageBookImageFile(...), imageData)`.

## 3. Room Database (`tooling/local_database`)

### 3.1 Schema (version 5)

```kotlin
@Database(entities = [Book, Chapter, ChapterBody], version = 5, exportSchema = false)
internal abstract class AppRoomDatabase : RoomDatabase(), AppDatabase
```

#### Entity: `Book`
```kotlin
@Parcelize @Entity
data class Book(
    val title: String,
    @PrimaryKey val url: String,
    val completed: Boolean = false,
    val lastReadChapter: String? = null,
    val inLibrary: Boolean = false,
    val coverImageUrl: String = "",
    val description: String = "",
    val lastReadEpochTimeMilli: Long = 0
)
```

#### Entity: `Chapter`
```kotlin
@Entity
data class Chapter(
    val title: String,
    @PrimaryKey val url: String,
    val bookUrl: String,
    val position: Int,
    val read: Boolean = false,
    val lastReadPosition: Int = 0,
    val lastReadOffset: Int = 0
)
```

#### Entity: `ChapterBody`
```kotlin
@Entity
data class ChapterBody(
    @PrimaryKey val url: String,
    val body: String
)
```

### 3.2 Derived data classes

| Class | Purpose |
|---|---|
| `BookMetadata(title, url, coverImageUrl, description)` | Equality by `url` only |
| `ChapterMetadata(title, url)` | Equality by `url` only |
| `BookWithContext(@Embedded book, chaptersCount, chaptersReadCount)` | Library list — `SUM(Chapter.read)` works because `read` is 0/1 INTEGER |
| `ChapterWithContext(@Embedded chapter, downloaded, lastReadChapter)` | Chapters list — LEFT JOIN ChapterBody (downloaded) + Book (lastReadChapter) |

### 3.3 Migrations

⚠️ **KNOWN BUG**: `@Database(version = 5)` but `databaseMigrations()` defines migrations 1→2 through 7→8. Migrations 5, 6, 7 (domain rewrites for `readlightnovel` and `1stkissnovel`) will **never execute** for existing v5 users because Room thinks the schema is already at v5. Either bump `version` to 7 (and ensure migrations match schema bumps) or remove the dead migrations. See `fixes.md` for details.

| Migration | Description |
|---|---|
| 1 → 2 | Add `Chapter.position INTEGER NOT NULL DEFAULT 0` |
| 2 → 3 | Add `Book.inLibrary INTEGER NOT NULL DEFAULT 0` + `UPDATE Book SET inLibrary = 1` |
| 3 → 4 | Add `Book.coverImageUrl TEXT NOT NULL DEFAULT ''` + `Book.description TEXT NOT NULL DEFAULT ''` |
| 4 → 5 | Add `Book.lastReadEpochTimeMilli INTEGER NOT NULL DEFAULT 0` |
| 5 → 6 ⚠️ | `readlightnovel.org`/`.me` → `.today` (NEVER RUNS — see bug above) |
| 6 → 7 ⚠️ | `.today` → `.meme` (NEVER RUNS) |
| 7 → 8 ⚠️ | `1stkissnovel.love` → `.org` (NEVER RUNS) |

### 3.4 Bug in `websiteDomainChangeHelper`

The `Book` table UPDATE in `MigrationsList.websiteDomainChangeHelper` uses `WHERE chapterUrl LIKE ...` — but `Book` has no `chapterUrl` column (it has `lastReadChapter`). This migration would throw `no such column: chapterUrl` at runtime if it ever executed. (Currently moot because of the version mismatch bug, but should be fixed.)

Additionally, `readLightNovelDomainChange_1_today` produces invalid SQL: `REPLACE(col, REPLACE(col, "$old1", "$new"), "$old2", "$new")` — the outer REPLACE has 4 arguments, but SQLite's `REPLACE(X, Y, Z)` takes exactly 3.

### 3.5 DAO summary

#### `LibraryDao`
- `getAll`, `getAllInLibrary`, `booksInLibraryFlow`, `getBooksInLibraryWithContextFlow`
- `insert` (IGNORE), `insertReplace` (REPLACE), `remove`, `update`
- `updateCover`, `updateLastReadEpochTimeMilli`, `updateDescription`, `updateLastReadChapter`
- `get`, `getFlow`, `existInLibrary`
- `removeAllNonLibraryRows`

#### `ChapterDao`
- `chapters(bookUrl)` ordered by position; `getChaptersWithContextFlow(bookUrl)`
- `setAsRead`/`setAsUnread` (bulk or single)
- `updatePosition`, `updateTitle`
- `removeAllFromBook`, `removeAllNonLibraryRows`
- `getFirstChapter`, `hasChapters`

#### `ChapterBodyDao`
- `getAll`, `insertReplace(chapterBody | List<ChapterBody>)`, `get(url)`
- `removeAllNonChapterRows`, `removeChapterRows(urls)`

### 3.6 Transactions & VACUUM

```kotlin
suspend fun <T> transaction(block: suspend () -> T): T =
    withTransaction { block() }

suspend fun vacuum() = withContext(Dispatchers.IO) {
    execSQL("VACUUM")
}
```

`AppDatabase.createRoomFromStream(context, name, inputStream)` — used during backup restore to open the backup's SQLite file as a temporary Room DB for merging.

## 4. Interactors (`data/interactor/`)

### 4.1 `WorkersInteractions`
```kotlin
interface WorkersInteractions {
    fun checkForLibraryUpdates(libraryCategory: LibraryCategory)
}
```
Implemented in `tooling/application_workers` — enqueues a `LibraryUpdatesWorker` (one-time, manual).

### 4.2 `LibraryUpdatesInteractions`
`@Singleton class LibraryUpdatesInteractions @Inject constructor(appRepository, downloaderRepository)`

#### `updateLibraryBooks(completedOnes, countingUpdating, currentUpdating, newUpdates, failedUpdates)`

1. Gets `appRepository.libraryBooks.getAllInLibrary()` filtered by `completed == completedOnes` and `!url.isLocalUri`.
2. Initializes `countingUpdating` with `CountingUpdating(0, list.size)`.
3. **Groups books by host** (`url.toHttpUrlOrNull()?.host`) — this avoids hammering one site with concurrent requests.
4. Launches an `async` per host group; sequentially updates each book in the group.
5. Per book: marks `currentUpdating += book`, fetches old chapter URLs (concurrent IO), calls `downloaderRepository.bookChaptersList(book.url)`. On success: `bookChapters.merge(chapters, book.url)`, computes `newChapters = chapters.filter { it.url !in oldChapters }`, appends to `newUpdates` if non-empty. On error: appends to `failedUpdates`. Always: `currentUpdating -= book`, `countingUpdating.updated++`.

Data classes:
- `NewUpdate(newChapters: List<Chapter>, book: Book)`
- `CountingUpdating(updated: Int, total: Int)`

## 5. Backup / Restore

### 5.1 Backup format

A zip file with this structure:
```
noveldokusha_backup_2024-01-15_14-30.zip
├── database.sqlite3              # Live Room SQLite file (DEFLATED)
└── books/                        # Only if "Save images" was checked
    ├── <bookFolderName1>/
    │   ├── __cover_image
    │   └── <imageFile1>.jpg
    └── <bookFolderName2>/
        └── ...
```

`<bookFolderName>` is the Base64-encoded HTTPS book URL (see `AppFileResolver.getLocalBookFolderName`).

### 5.2 `BackupDataService` (create)

`@AndroidEntryPoint Service` with foreground notification. Stages:
1. `creating_backup` → open `contentResolver.openOutputStream(uri)`, wrap in `ZipOutputStream`.
2. `copying_database` → `ZipEntry("database.sqlite3")`, copy bytes from `getDatabasePath(appDatabase.name)`.
3. `copying_images` (if `backupImages`) → walk `folderBooks` bottom-up, add each file as `ZipEntry(name = basePath.relativize(file.toPath()).toString())`.
4. `backup_saved` (or `failed_to_make_backup` if stream was null).

Default filename: `noveldokusha_backup_yyyy-MM-dd_HH-mm.zip`.

### 5.3 `RestoreDataService` (restore)

`@AndroidEntryPoint Service` with foreground notification. Stages:
1. `loading_data` → open `contentResolver.openInputStream(uri)`.
2. Walk zip entries:
   - `"database.sqlite3"` → `mergeToDatabase`:
     - Create temp Room DB via `AppDatabase.createRoomFromStream(context, "temp_database", inputStream)`.
     - Build temporary repositories (`LibraryBooksRepository`, `BookChaptersRepository`, `ChapterBodyRepository`) on the temp DB.
     - Bulk-copy: `libraryBooks.insertReplace(getAll())` → `bookChapters.insert(getAll())` → `chapterBody.insertReplace(getAll())`.
     - Close + clear temp DB.
   - Files starting with `"books/"` → write to `folderBooks.parentFile/<entry.name>` (resolves to `filesDir/books/<...>`).
3. Emit `appRepository.eventDataRestored`.
4. Post `backup_restored` (or `failed_to_restore_invalid_backup_database`) notification.

⚠️ **Memory concern**: `zipSequence` reads the entire backup zip into memory (`associateWith { zipStream.readBytes() }`). Large image sets will OOM. Should stream entries one at a time. See `fixes.md`.

⚠️ **Temp DB cleanup**: The temp database created with `name = "temp_database"` is never explicitly deleted from the filesystem — `clearDatabase()` only clears tables; the file remains.

## 6. Persistent Caches (`data/storage/`)

### 6.1 `PersistentCacheDataLoader<T>`
Generic file-backed cache with Moshi serialization. API:
- `hasFile()`, `getFileContent(): Response<T>`, `set(value)`, `cache(fn)` (try cache first, `flatMapError { fn() }`, `onSuccess { set(it) }`).
- `fetch(tryCache=true, getRemote: suspend PersistentCacheDataLoader<T>.() -> Response<T>)` — if `tryCache`, runs `cache { getRemote() }`, else just `getRemote()`.

### 6.2 `PersistentCacheDatabaseSearchGenresProvider`
`@Singleton` providing `PersistentCacheDataLoader<List<SearchGenre>>` per `DatabaseInterface`, backed by `File(appContext.cacheDir, database.searchGenresCacheFileName)`.

## 7. Mappers (`data/mappers/ScraperMappers.kt`)

Maps scraper-domain types to local-database metadata:
- `ChapterResult.mapToChapterMetadata()` → `ChapterMetadata`
- `BookResult.mapToBookMetadata()` → `BookMetadata`
- `PagedList<BookResult>.mapToBookMetadata()` — preserves `index` and `isLastPage`
- All with `Response<>` variants using `syncMap`

## 8. Validators

```kotlin
fun isValid(book: Book) = book.url.matches(Regex("^(https?|local)://.*"))
fun isValid(chapter: Chapter) = chapter.url.matches(Regex("^(https?|local)://.*"))
```

Used by `LibraryBooksRepository.insert` and `BookChaptersRepository.insert` to filter out invalid URLs before persistence.

## 9. See Also

- `CORE_ENGINE.md` — `AppPreferences`, `Response`, `PagedList`, networking
- `TRANSLATION_SYSTEM.md` — Gemini source, stale-cache guard context
- `fixes.md` — Room migration bugs, backup OOM concern
