# NovelDokusha — Translation System

> **Scope**: Both translation systems in the app — on-device ML Kit translation (reader live translation) and server-side Gemini translation (TimoTxt Gemini source).

## 1. Two Independent Translation Systems

The codebase has **two completely separate translation systems** that do not interact:

| Aspect | ML Kit (on-device) | Gemini (server-side) |
|---|---|---|
| **Where the code lives** | `tooling/text_translator/translator` (full flavor only) | `scraper/sources/TimoTxtGemini` + `GeminiApiClient` |
| **Where it's surfaced** | Reader bottom-bar Translate icon + Settings → Translation models | Finder → "TimoTxt (Gemini)" source |
| **What gets translated** | Any chapter body, regardless of source | Only chapters fetched from `timotxt.com` via the Gemini source |
| **Requires internet?** | Only for initial model download (~30 MB per language pair) | Yes, every chapter |
| **Works offline?** | ✅ Yes (after model download) | ❌ No |
| **Works in foss flavor?** | ❌ No (UI is hidden) | ✅ Yes (network-only) |
| **Quality** | Medium (machine translation) | High (two-pass LLM refinement) |
| **Cost** | Free | Free tier: 10 RPM / 250 RPD on `gemini-2.5-flash` |
| **URL strategy** | No URL rewriting — translates in-memory after fetch | Uses fake domain `www-timotxt-com-gemini.goog` for routing; `transformChapterUrl()` converts back to `timotxt.com` for HTTP fetch |

## 2. ML Kit Translation (on-device, full flavor only)

### 2.1 Module structure

```
tooling/text_translator/
├── domain/                  ← TranslationManager interface + TranslatorState + TranslationModelState
│   └── src/main/java/.../
│       ├── TranslationManager.kt
│       └── (data classes)
├── translator/              ← ML Kit impl (full flavor)
│   └── src/main/java/.../
│       ├── TranslationManagerMLKit.kt
│       └── FullModule.kt    ← Hilt @Provides
└── translator_nop/          ← No-op stub (foss flavor)
    └── src/main/java/.../
        ├── TranslationManagerEmpty.kt
        └── FossModule.kt    ← Hilt @Provides
```

### 2.2 `domain` — the interface

```kotlin
data class TranslationModelState(
    val language: String,
    val available: Boolean,
    val downloading: Boolean,
    val downloadingFailed: Boolean
) {
    val locale = Locale(language)
}

data class TranslatorState(
    val source: String,
    val target: String,
    val translate: suspend (String) -> String
) {
    val sourceLocale = Locale(source)
    val targetLocale = Locale(target)
}

interface TranslationManager {
    val available: Boolean
    val models: SnapshotStateList<TranslationModelState>
    suspend fun hasModelDownloaded(language: String): TranslationModelState?
    fun getTranslator(source: String, target: String): TranslatorState
    fun downloadModel(language: String)
    fun removeModel(language: String)
}
```

This is the only thing the reader/settings modules depend on; the actual implementation is flavor-specific.

### 2.3 `translator` — ML Kit impl (full flavor)

#### `TranslationManagerMLKit(coroutineScope: AppCoroutineScope) : TranslationManager`

- `available = true` — this gates `SettingsViewModel.isTranslationSettingsVisible` and `ReaderLiveTranslation.state.isAvailable` (so the translation UI shows up).
- `models` initialized from `TranslateLanguage.getAllLanguages()` (every ML Kit-supported language).
- On init, queries `RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await()` and marks matching languages as `available = true`.
- `hasModelDownloaded(language)` — `RemoteModelManager.isModelDownloaded(TranslateRemoteModel.Builder(language).build()).await()` → returns `TranslationModelState` if downloaded, else null.
- `getTranslator(source, target)` — builds `TranslatorOptions` and `Translation.getClient(options)`. Returns a `TranslatorState` whose `translate` lambda is `{ input -> translator.translate(input).await() }` (uses `kotlinx-coroutines-playServices` for `Task.await()`).
- `downloadModel(language)` — sets `downloading = true` on the model entry, calls `RemoteModelManager.download(TranslateRemoteModel.Builder(language).build(), DownloadConditions.Builder().build())`. On success: `downloadingFailed = false, downloading = false, available = true`. On failure: `downloadingFailed = true, downloading = false`.
- `removeModel(language)` — refuses to remove `"en"` (English is required as a pivot); otherwise deletes via `RemoteModelManager.deleteDownloadedModel(...)`.

#### `FullModule.kt`
```kotlin
@Module @InstallIn(SingletonComponent::class)
object FullModule {
    @Provides @Singleton
    fun provideTranslationManager(coroutineScope: AppCoroutineScope): TranslationManager =
        TranslationManagerMLKit(coroutineScope)
}
```

#### Dependencies
- `core`, `tooling.text_translator.domain`
- `kotlinx-coroutines-playServices` (for `Task.await()`)
- `com.google.mlkit:translate:17.0.2` (Android ML Translation Kit)

This module is only pulled in by the **`full` flavor** in `app/build.gradle.kts`:
```kotlin
fullImplementation(projects.tooling.textTranslator.translator)
```

### 2.4 `translator_nop` — no-op stub (foss flavor)

#### `TranslationManagerEmpty : TranslationManager`
- `available = false` — this is what gates `SettingsViewModel.isTranslationSettingsVisible` and `ReaderLiveTranslation.state.isAvailable` (so the translation UI is **hidden** in the FOSS build).
- `models = mutableStateListOf()` (empty).
- `hasModelDownloaded(...) = null`.
- `getTranslator(...)` returns a stub `TranslatorState(translate = { _ -> "" })`.
- `downloadModel/removeModel = Unit`.

#### `FossModule.kt`
```kotlin
@Module @InstallIn(SingletonComponent::class)
object FossModule {
    @Provides @Singleton
    fun provideTranslationManager(): TranslationManager = TranslationManagerEmpty()
}
```

#### Dependencies
- `core`, `tooling.text_translator.domain` — no ML Kit, no Play Services.

This module is only pulled in by the **`foss` flavor**:
```kotlin
fossImplementation(projects.tooling.textTranslator.translatorNop)
```

### 2.5 Reader live translation flow

```
Reader bottom-bar → Translate icon → TranslatorSettingDialog
  │
  ▼
ReaderLiveTranslation(translationManager, appPreferences)
  ├─ state: LiveTranslationSettingData (enable, source, target, listOfAvailableModels, callbacks)
  ├─ translatorState: TranslatorState? — null when disabled, source==target, or missing source/target
  ├─ init() reads appPreferences.GLOBAL_TRANSLATION_PREFERRED_SOURCE/TARGET
  │     validates via translationManager.hasModelDownloaded(language)
  ├─ onEnable/onSourceChange/onTargetChange → updates prefs + translatorState
  │     if a real change happened, emits _onTranslatorChanged: MutableSharedFlow<Unit>
  │
  ▼
ReaderActivity collects onTranslatorChanged → viewModel.reloadReader()
  │
  ▼
ReaderChaptersLoader.reload() + tryLoadRestartedInitial(currentChapter)
  │
  ▼
addChapter(chapterIndex):
  ├─ downloadChapter(url) → fetch body
  ├─ textToItemsConverter(text) → List<ReaderItem.Body|Image>
  ├─ If translatorIsActive():
  │     • Insert ReaderItem.Translating(sourceLang, targetLang)
  │     • For each Body item, run translatorTranslateOrNull(text)
  │     • After done, remove Translating; insert GoogleTranslateAttribution
  └─ Insert all items + ReaderItem.Divider
```

`translatorTranslateOrNull: suspend (String) -> String?` resolves to `readerLiveTranslation.translatorState?.translate?.invoke(it)`.

> **Note**: ML Kit translation happens **after** the chapter body is fetched and split into items. Each `Body` item is translated individually. The translated text replaces the original in `item.textToDisplay`, which is what the `ReaderItemAdapter` renders and what TTS reads.

## 3. Gemini Translation (server-side, both flavors)

### 3.1 Source structure

```
scraper/src/main/java/my/noveldokusha/scraper/sources/
├── TimoTxtGemini.kt          ← the source (SourceInterface.Catalog)
└── GeminiApiClient.kt        ← the API client (standalone, NOT a source)
```

### 3.2 `TimoTxtGemini` — the source

```kotlin
class TimoTxtGemini(
    private val networkClient: NetworkClient,
    private val geminiApiKey: String,
    private val geminiModel: String = "gemini-2.5-flash"
) : SourceInterface.Catalog {

    override val id = "timotxt_gemini"
    override val nameStrId = R.string.source_name_timotxt_gemini
    override val baseUrl = "https://www-timotxt-com-gemini.goog/"
    override val catalogUrl = "https://www-timotxt-com-gemini.goog/"
    override val language = LanguageCode.ENGLISH

    private val originalBaseUrl = "https://www.timotxt.com/"

    private val geminiClient by lazy {
        GeminiApiClient(apiKey = geminiApiKey, model = geminiModel)
    }
    ...
}
```

### 3.3 URL strategy

Book/chapter URLs are stored with a fake domain `www-timotxt-com-gemini.goog` so the app's URL-router (`Scraper.getCompatibleSource`) directs them to `TimoTxtGemini`. The `transformChapterUrl()` method converts them back to `timotxt.com` for actual HTTP fetching.

```kotlin
private fun toGeminiUrl(originalUrl: String): String {
    if (originalUrl.contains("gemini.goog")) return originalUrl
    val uri = java.net.URI(originalUrl)
    val path = uri.path ?: "/"
    return "https://www-timotxt-com-gemini.goog$path"
}

private fun fromGeminiUrl(geminiUrl: String): String {
    if (!geminiUrl.contains("gemini.goog")) return geminiUrl
    val uri = java.net.URI(geminiUrl)
    val path = uri.path ?: "/"
    return "https://www.timotxt.com$path"
}

override suspend fun transformChapterUrl(url: String): String = fromGeminiUrl(url)
```

This is the same pattern as `TimoTxtTranslate` (which uses `translate.goog`), but with a distinct domain so the router can distinguish between the Google Translate proxy and the Gemini proxy.

### 3.4 Chapter text translation

```kotlin
override suspend fun getChapterText(doc: Document): String =
    withContext(Dispatchers.Default) {
        val contentEl = doc.selectFirst(".chapter-content .content")
            ?: doc.selectFirst(".chapter-content")
            ?: doc.selectFirst(".content")
            ?: return@withContext ""

        // Remove ad blocks and non-content elements
        contentEl.select(".gadBlock, .adBlock, .cf-unit, ins.clickforceads, ins.PopIn, script, style, iframe").remove()

        var text = TextExtractor.get(contentEl)

        // Remove junk / reminder text (both Chinese and English variants)
        for (pattern in junkPatterns) {
            text = pattern.replace(text, "")
        }

        text = text.trim()
        if (text.isBlank()) return@withContext ""

        // Translate if the text is primarily Chinese/CJK
        if (isPrimarilyCJK(text)) {
            val translated = runCatching { translateChapterText(text) }.getOrDefault(text)
            cleanTranslatedText(translated)
        } else {
            text
        }
    }
```

### 3.5 `translateChapterText` — paragraph-level translation

```kotlin
private suspend fun translateChapterText(text: String): String {
    val paragraphs = text.split("\n")
        .filter { it.isNotBlank() }
        .mapIndexed { index, line ->
            GeminiApiClient.ParagraphItem(id = index, text = line.trim())
        }

    if (paragraphs.isEmpty()) return text

    val translated = geminiClient.translateParagraphs(paragraphs)

    // Reassemble preserving paragraph structure
    val idToTranslated = translated.associateBy { it.id }
    return paragraphs.mapNotNull { original ->
        idToTranslated[original.id]?.text ?: original.text
    }.joinToString("\n\n")
}
```

### 3.6 `isPrimarilyCJK` — CJK detection guard

```kotlin
private fun isPrimarilyCJK(text: String): Boolean {
    if (text.isBlank()) return false

    val cjkChars = text.count { ch ->
        ch.code in 0x4E00..0x9FFF ||    // CJK Unified Ideographs
        ch.code in 0x3400..0x4DBF ||    // CJK Extension A
        ch.code in 0x20000..0x2A6DF ||  // CJK Extension B
        ch.code in 0x2A700..0x2B73F ||  // CJK Extension C
        ch.code in 0x2B740..0x2B81F ||  // CJK Extension D
        ch.code in 0xF900..0xFAFF ||    // CJK Compatibility Ideographs
        ch.code in 0xAC00..0xD7AF ||    // Korean syllables
        ch.code in 0x3040..0x309F ||    // Hiragana
        ch.code in 0x30A0..0x30FF       // Katakana
    }

    val totalNonWhitespace = text.count { !it.isWhitespace() }
    return totalNonWhitespace > 0 &&
            cjkChars.toFloat() / totalNonWhitespace > CHINESE_THRESHOLD  // 0.12f
}

companion object {
    const val CHINESE_THRESHOLD = 0.12f
}
```

This guard ensures we only call the Gemini API when the text actually needs translation. If the API returns untranslated Chinese (e.g. due to a safety filter), the stale-cache guard in `ChapterBodyRepository` detects it and forces a re-fetch.

### 3.7 Batch title translation

```kotlin
// In getChapterList():
val cjkChapters = rawChapters.filter { isPrimarilyCJK(it.title) }
if (cjkChapters.isNotEmpty()) {
    val titleItems = cjkChapters.mapIndexed { index, ch ->
        GeminiApiClient.ParagraphItem(id = index, text = ch.title)
    }
    val translatedTitles = geminiClient.translateParagraphs(titleItems)
    val titleMap = cjkChapters.mapIndexed { index, ch ->
        ch.title to (translatedTitles.find { it.id == index }?.text ?: ch.title)
    }.toMap()
    rawChapters.map { chapter ->
        chapter.copy(title = titleMap[chapter.title] ?: chapter.title)
    }
} else {
    rawChapters
}
```

Same pattern for catalog list titles and search result titles — batch-translate CJK titles using Gemini.

## 4. `GeminiApiClient` — the API client

Standalone OkHttp client (does NOT use `NetworkClient`) — created with its own timeouts:
- `connectTimeout(60s)`, `readTimeout(120s)`, `writeTimeout(30s)`

### 4.1 Endpoint

```
POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent?key={apiKey}
```

### 4.2 `ParagraphItem` — JSON envelope

```kotlin
data class ParagraphItem(
    val id: Int,
    val text: String
)
```

Input/output JSON format:
```json
[{"id": 0, "text": "translated paragraph"}, {"id": 1, "text": "translated paragraph"}, ...]
```

### 4.3 System prompt

The system prompt is a 20+ line "professional light novel translator" persona:

```
You are a professional light novel translator with 20 years of experience. You specialize in
Chinese-to-English translation of web novels and light novels.

TRANSLATION PROCESS:
1. First pass: Produce a literal, faithful translation preserving the original meaning.
2. Second pass: Polish the translation for natural English flow while maintaining the author's
   voice, tone, and style.

OUTPUT FORMAT:
You MUST output valid JSON matching this exact structure:
[{"id": 0, "text": "translated paragraph"}, {"id": 1, "text": "translated paragraph"}, ...]

CRITICAL RULES:
- Output ONLY the JSON array. No intro, no outro, no notes, no commentary.
- Translate 100% of the text. Leave NO Chinese characters or pinyin in the output.
- Preserve the "id" field exactly as provided in the input.
- Each paragraph must be translated as a complete, coherent unit.

STYLE GUIDELINES:
- Punctuation: Convert …… (Chinese ellipsis) to — (em dash) or ... (English ellipsis) based on context.
- Sound effects (SFX): Translate contextually. E.g., "嗖" → "whoosh", "砰" → "BANG".
- Gender pronouns: Maintain consistency throughout. Infer from context when ambiguous.
- Idioms: Localize to natural English equivalents. E.g., "畫蛇添足" → "gilding the lily".
- Paragraph pacing: Follow Western publishing conventions.
- Number scaling: 万 → 10,000; 亿 → 100 million; 千 → 1,000. Write out numbers naturally.
- Brackets: Preserve 【】, 《》, 「」 as [ ], < >, " " respectively, or adapt to context.
- Honorifics: Keep if culturally relevant (e.g., Shifu, Da-shixiong) with brief context on first use.
- Dialogue: Use proper English dialogue formatting with quotation marks.
- Tone: Match the original tone — humorous, dramatic, tense, casual, etc.
```

### 4.4 Request body

```json
{
  "system_instruction": {"parts": [{"text": "<escaped system prompt>"}]},
  "contents": [{"parts": [{"text": "<escaped input JSON>"}]}],
  "generationConfig": {
    "temperature": 0.55,
    "responseMimeType": "application/json"
  },
  "safetySettings": [
    {"category": "HARM_CATEGORY_HARASSMENT", "threshold": "BLOCK_ONLY_HIGH"},
    {"category": "HARM_CATEGORY_HATE_SPEECH", "threshold": "BLOCK_ONLY_HIGH"},
    {"category": "HARM_CATEGORY_SEXUALLY_EXPLICIT", "threshold": "BLOCK_ONLY_HIGH"},
    {"category": "HARM_CATEGORY_DANGEROUS_CONTENT", "threshold": "BLOCK_ONLY_HIGH"}
  ]
}
```

> ⚠️ **Temperature is hardcoded**: `GeminiApiClient.buildRequestBody()` hardcodes `"temperature": 0.55`. The `GEMINI_TEMPERATURE` preference exists and the Settings UI updates it, but it's NOT passed to `GeminiApiClient`. The temperature slider is currently inert. See `fixes.md`.

### 4.5 Smart batching

```kotlin
private fun smartBatch(
    paragraphs: List<ParagraphItem>,
    maxCharsPerBatch: Int = 12000
): List<List<ParagraphItem>>
```

- Each batch represents 2-3 chapters worth of text, respecting approximate token/character limits.
- Detects chapter breaks (heuristic: short lines containing `第...章/回/節/卷` or `chapter N`).
- Starts a new batch every 3 chapter breaks.
- Also breaks on size limit (12000 chars per batch).

### 4.6 Rate limiting

Per-model rate limits:
- `gemini-2.5-flash`: 10 RPM / 250 RPD
- `gemini-2.5-flash-lite`: 30 RPM / 1000 RPD

```kotlin
private val rateLimits = mapOf(
    "gemini-2.5-flash" to RateLimit(rpm = 10, rpd = 250),
    "gemini-2.5-flash-lite" to RateLimit(rpm = 30, rpd = 1000)
)
```

`enforceRateLimit()`:
- Tracks `requestTimestamps: mutableListOf<Long>` (synchronized).
- Cleans up timestamps older than 1 day.
- Checks daily limit: if `requestTimestamps.size >= limit.rpd`, sleeps until the oldest timestamp is > 24h old.
- Checks per-minute limit: if recent requests >= `limit.rpm`, sleeps until the oldest recent timestamp is > 60s old.
- Adds the current timestamp to the list.

> ⚠️ **Concern**: Uses `Thread.sleep(waitMs + 1000)` inside `synchronized(lock)`, blocking the calling coroutine's IO thread. Not coroutine-friendly (should use `delay()`); risk of thread-pool starvation under high concurrency.

### 4.7 Retry with backoff

```kotlin
private suspend fun <T> retryWithBackoff(
    maxRetries: Int,
    initialDelayMs: Long = 2000L,
    block: suspend () -> T
): T?
```

- 3 retries max.
- On `RateLimitException`: waits `(initialDelayMs * 2) * (1L shl attempt)` — double the normal backoff.
- On other exceptions: waits `initialDelayMs * (1L shl attempt)` — exponential backoff.
- Returns `null` if all retries exhausted (caller falls back to original text).

### 4.8 Response parsing

```kotlin
private fun parseBatchResponse(
    responseBody: String,
    originalBatch: List<ParagraphItem>
): List<ParagraphItem>
```

1. Parses the Gemini response JSON: `candidates[0].content.parts[0].text`.
2. Parses the inner text as a JSON array of `ParagraphItem` (via Gson + TypeToken).
3. **Verifies count**: if `translated.size != originalBatch.size`, returns the original batch (fallback).
4. **Verifies IDs**: if any `t.id != o.id`, returns the original batch (fallback).
5. Returns the translated paragraphs.

On any exception, returns the original batch (graceful degradation).

### 4.9 Custom exceptions

```kotlin
class RateLimitException(message: String) : Exception(message)
class GeminiApiException(message: String) : Exception(message)
```

### 4.10 JSON escaping

Manual JSON escaping (no Gson serialization for the request envelope):
```kotlin
private fun escapeJson(text: String): String {
    return text
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}
```

## 5. Stale-cache guard (data layer)

`ChapterBodyRepository.fetchBody` (in `data/`) implements a stale-cache guard for translation-proxy URLs:

```
If urlChapter.contains("translate.goog") OR urlChapter.contains("gemini.goog")
   AND isPrimarilyCJK(cachedBody)   // CJK ratio > 0.12
THEN: delete the cached row and re-fetch from network.
```

This guard exists because **before the source-routing fix** in `DownloaderRepository.bookChapter`, `translate.goog` URLs would redirect to `timotxt.com`, match the plain `TimoTxt` source (no translation), and cache **untranslated Chinese text**. The guard detects this situation and forces a re-fetch so the translation source can re-translate.

The guard remains as a safety net for users who already have bad cached data from before the fix. See `DATA_LAYER.md` §2.4 for details.

## 6. Comparison: TimoTxt variants

Three TimoTxt variants exist for the same upstream site (`timotxt.com`):

| Source | Mechanism | Quality | Speed | Cost | URL domain |
|---|---|---|---|---|---|
| **TimoTxt** | None (original Chinese) | — | Instant | Free | `www.timotxt.com` |
| **TimoTxt (Translate)** | Google Translate proxy via `translate.googleapis.com/translate_a/single` | Medium | Fast | Free | `www-timotxt-com.translate.goog` |
| **TimoTxt (Gemini)** | Gemini 2.5 Flash API with two-pass refinement | High | 4–8 s per chapter batch | Free tier (10 RPM / 250 RPD on flash) | `www-timotxt-com-gemini.goog` |

They share junk-pattern lists and CJK-detection heuristics but duplicate the logic rather than sharing a base class.

## 7. Known Limitations & Bugs

See `fixes.md` for the full list. Key translation-related issues:

1. **Temperature not wired**: `GEMINI_TEMPERATURE` preference exists and the Settings UI updates it, but `Scraper` doesn't pass it to `TimoTxtGemini`, and `GeminiApiClient.buildRequestBody()` hardcodes `"temperature": 0.55`. The temperature slider is currently inert.

2. **API key / model captured at Scraper construction**: `Scraper` is `@Singleton` and `appPreferences.GEMINI_API_KEY.value` / `GEMINI_MODEL.value` are read once when `Scraper` is first injected. Changes in Settings won't take effect until the app process is restarted.

3. **`Thread.sleep` in rate limiter**: `GeminiApiClient.enforceRateLimit()` uses `Thread.sleep` inside `synchronized(lock)`, blocking the IO dispatcher thread. Should use `delay()` for coroutine-friendliness.

4. **`meta[name=og:image]` likely wrong**: `TimoTxtGemini.getBookCoverImageUrl` uses `meta[name=og:image]` for cover image. The Open Graph standard uses `meta[property=og:image]` (and `TimoTxt.kt` correctly uses `property=`). This likely never matches and the `.cover img[src]` fallback always runs.

5. **`TimoTxtTranslate` separator mismatch**: Joins batch titles with `" ||| "` but splits the response on `" || | "` (with spaces). Falls through to a regex fallback. Likely works but slower than intended.

## 8. See Also

- `CORE_ENGINE.md` — `AppPreferences` (GEMINI_* keys), networking
- `DATA_LAYER.md` — stale-cache guard, source-routing
- `UI_LAYER.md` — reader live translation, SettingsGemini
- `SECURITY.md` — API key storage
