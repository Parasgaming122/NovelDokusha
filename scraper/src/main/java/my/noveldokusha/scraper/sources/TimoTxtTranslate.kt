package my.noveldokusha.scraper.sources

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import okhttp3.FormBody
import org.jsoup.nodes.Document
import java.net.URI

/**
 * Auto-translating source for timotxt.com via the Google Translate free API.
 *
 * **URL routing strategy**: This source uses a unique fake baseUrl
 * `https://www-timotxt-com.translate.goog/` so the app's `getCompatibleSource()`
 * can distinguish books belonging to this source from the plain `TimoTxt`
 * source (which uses `https://www.timotxt.com/`). All actual HTTP fetching
 * goes to the real `https://www.timotxt.com/` domain — the fake domain is
 * only used for URL storage and routing.
 *
 * **Cross-source transfer**: The three TimoTxt sources (TimoTxt,
 * TimoTxtTranslate, TimoTxtGemini) share the same path structure
 * (`/{novelId}/` and `/{novelId}/{chNum}.html`). A novel favorited on one
 * source can be opened in either of the other two by rewriting the URL's
 * host portion. See `Scraper.getAlternativeSources()`.
 *
 * **Translation pipeline** (mirrors the proven `timotxt_extractor.py`):
 *   1. Fetch Chinese chapter HTML directly from `www.timotxt.com`.
 *   2. Extract text from `.content` div.
 *   3. Strip site notices (regex) — `溫馨提示.*`, `PS：.*`, etc.
 *   4. Remove Unicode garbage (Korean Hangul, rare CJK compat artifacts).
 *   5. Collapse whitespace.
 *   6. Split at Chinese/English sentence boundaries (`。！？」!?`).
 *   7. Group sentences into batches of ≤ 2000 chars.
 *   8. POST each batch to `translate.googleapis.com/translate_a/single`.
 *   9. Concatenate the translated batches with spaces.
 *
 * **Cloudflare handling**: all HTTP calls go through `networkClient`, which
 * has the `CloudfareVerificationInterceptor` in its interceptor chain. If
 * timotxt.com serves a CF challenge, the interceptor automatically fires up
 * a headless WebView, solves the challenge (including Turnstile clicks),
 * captures the clearance cookie, and retries the request.
 */
class TimoTxtTranslate(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "timotxt_translate"
    override val nameStrId = R.string.source_name_timotxt_translate

    /** Fake routing baseUrl — unique per source so getCompatibleSource() works. */
    override val baseUrl = "https://www-timotxt-com.translate.goog/"
    override val catalogUrl = "https://www-timotxt-com.translate.goog/"
    override val language = LanguageCode.ENGLISH

    /** Real timotxt.com domain for all HTTP fetching. */
    private val originalBaseUrl = "https://www.timotxt.com/"

    companion object {
        // ─── Translation API ────────────────────────────────────────────
        private const val TRANSLATE_API_URL =
            "https://translate.googleapis.com/translate_a/single"
        private const val BATCH_CHAR_LIMIT = 2000
        private const val MAX_RETRIES = 3
        private const val RETRY_DELAY_MS = 1500L
        private const val TRANSLATE_DELAY_MS = 300L

        // ─── Text cleaning ──────────────────────────────────────────────
        /**
         * Regex patterns to strip from extracted Chinese text BEFORE
         * translation. Each pattern matches from its start marker to the
         * end of the line/string — site notices, ads, and author
         * postscript notes that shouldn't be translated.
         *
         * Mirrors `STRIP_PATTERNS` in `timotxt_extractor.py`.
         */
        internal val STRIP_PATTERNS = listOf(
            Regex("溫馨提示.*"),
            Regex("網站即將改版.*"),
            Regex("書架.*閱讀記錄.*"),
            Regex("手機端閱讀.*"),
            Regex("PS[：:].*"),
            Regex("嘤嘤嘤.*"),
            Regex("手機小說閱讀網.*"),
            Regex("手機用戶.*"),
            Regex("最新更新時間.*"),
            Regex("提莫書屋.*"),
            Regex("(?i)timotxt\\.com.*"),
        )

        /**
         * Whitelist of Unicode ranges to KEEP in the Chinese text.
         * Everything else (Korean Hangul, rare CJK compat artifacts, etc.)
         * is stripped. Mirrors the regex in `scrape_chapter()` in the
         * Python script.
         */
        internal val UNICODE_WHITELIST = Regex(
            "[^\\u0000-\\u007F" +       // Basic Latin (ASCII)
            "\\u00A0-\\u00FF" +          // Latin-1 Supplement
            "\\u2000-\\u206F" +          // General Punctuation
            "\\u3000-\\u303F" +          // CJK Symbols and Punctuation
            "\\uFF00-\\uFFEF" +          // Fullwidth Forms
            "\\u4E00-\\u9FFF" +          // CJK Unified Ideographs (main)
            "\\u3400-\\u4DBF" +          // CJK Unified Ideographs Extension A
            "\\uF900-\\uFAFF" +          // CJK Compatibility Ideographs
            "\\u2E80-\\u2EFF" +          // CJK Radicals Supplement
            "\\u2014\\u2013" +           // Em dash, en dash
            "\\u201C\\u201D" +           // Left/Right double quotation marks
            "\\u2018\\u2019" +           // Left/Right single quotation marks
            "\\u2026" +                  // Horizontal ellipsis …
            "\\u3001\\u3002" +           // Ideographic comma , full stop .
            "]+"
        )

        /**
         * English junk text patterns to remove AFTER translation.
         * Case-insensitive.
         */
        internal val ENGLISH_JUNK_PATTERNS = listOf(
            Regex("(?i)warm reminder.*"),
            Regex("(?i)warm tip.*"),
            Regex("(?i)friendly reminder.*"),
            Regex("(?i)mobile novel reading network.*"),
            Regex("(?i)mobile phone novel.*"),
            Regex("(?i)mobile user.*"),
            Regex("(?i)latest update time.*"),
            Regex("(?i)timo book house.*"),
            Regex("(?i)timotxt\\.com.*"),
        )

        /**
         * Check if text is primarily CJK characters.
         * Returns true if CJK ratio > 0.12
         */
        fun isPrimarilyCJK(text: String): Boolean {
            if (text.isBlank()) return false
            val cjkCount = text.count { ch ->
                val code = ch.code
                (code in 0x4E00..0x9FFF) ||
                (code in 0x3400..0x4DBF) ||
                (code in 0x2E80..0x2EFF) ||
                (code in 0x3000..0x303F) ||
                (code in 0xFF00..0xFFEF) ||
                (code in 0x3040..0x309F) ||
                (code in 0x30A0..0x30FF)
            }
            return cjkCount.toDouble() / text.length > 0.12
        }
    }

    // ─── URL handling ───────────────────────────────────────────────────

    /**
     * Convert a stored (fake) translate.goog URL to the real timotxt.com URL
     * for HTTP fetching. Also strips any Google Translate query params.
     */
    private fun resolveOriginalUrl(url: String): String {
        return url
            .replace("https://www-timotxt-com.translate.goog", originalBaseUrl.trimEnd('/'))
            .replace(Regex("[?&]_x_tr_(sl|tl|hl|pto|pto_ctx)=[^&]*"), "")
            .replace("?&", "?")
            .replace(Regex("[?&]$"), "")
    }

    /**
     * No transform needed at the chapter-fetch level — we resolve to the
     * original URL in each getter. (Kept for SourceInterface compatibility.)
     */
    override suspend fun transformChapterUrl(url: String): String = url

    // ─── Chapter content ────────────────────────────────────────────────

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            val titleEl = doc.selectFirst("h1.imgtext, .chapter-content h1, .chapter-content .title, h1.chapter-title, h1")
                ?: return@withContext null
            val titleCn = titleEl.text().trim()
            if (titleCn.isBlank()) null else translateText(titleCn)
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val contentEl = doc.selectFirst("div.chapter-content div.content")
                ?: doc.selectFirst("div.chapter-content")
                ?: doc.selectFirst("div.content")

            contentEl!!.also {
                it.select(".gadBlock").remove()
                it.select("script").remove()
                it.select("style").remove()
                it.select("iframe").remove()
                it.select(".ads").remove()
                it.select(".share-buttons").remove()
                it.select(".pager").remove()
            }

            val rawText = TextExtractor.get(contentEl)
            val cleanedCn = rawText.cleanChineseText()
            val translatedEn = translateText(cleanedCn)
            translatedEn.cleanEnglishJunk()
        }

    // ─── Book metadata ──────────────────────────────────────────────────

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val fetchUrl = resolveOriginalUrl(bookUrl)
            val doc = networkClient.get(fetchUrl).toDocument()
            doc.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?: doc.selectFirst("img.bookinfo-pic-img[src]")
                    ?.attr("src")
                ?: doc.selectFirst(".cover img[src]")
                    ?.attr("src")
        }
    }

    override suspend fun getBookDescription(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val fetchUrl = resolveOriginalUrl(bookUrl)
            val doc = networkClient.get(fetchUrl).toDocument()
            val rawDesc = doc.selectFirst("meta[name=description]")
                ?.attr("content")
                ?.trim()
                ?: doc.selectFirst(".intro, .desc, .bookinfo-detail .desc")
                    ?.let { TextExtractor.get(it).trim() }
            if (rawDesc.isNullOrBlank()) null else translateText(rawDesc)
        }
    }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val fetchUrl = resolveOriginalUrl(bookUrl)
            val dirUrl = fetchUrl.toUrlBuilderSafe()
                .addPath("dir")
                .toString()

            val doc = networkClient.get(dirUrl).toDocument()

            // Verified selectors from the real /dir page:
            //   .chaplist ul.all li a  → 398 chapter links (primary)
            //   .chaplist ul li a      → fallback
            val chapterLinks = doc.select(".chaplist ul.all li a[href]")
                .takeIf { it.isNotEmpty() }
                ?: doc.select(".chaplist ul li a[href]")

            val titles = chapterLinks.map { it.text().trim() }
            val translatedTitles = translateBatchTitles(titles)

            chapterLinks.mapIndexed { index, element ->
                // Store chapter URLs with THIS source's baseUrl so the reader
                // routes them back to TimoTxtTranslate (not TimoTxt).
                val realUrl = URI(originalBaseUrl).resolve(element.attr("href")).toString()
                val storedUrl = realUrl.replace(originalBaseUrl, baseUrl)
                ChapterResult(
                    title = translatedTitles.getOrElse(index) { titles[index] },
                    url = storedUrl
                )
            }
        }
    }

    // ─── Catalog ────────────────────────────────────────────────────────

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val page = index + 1
            // CRITICAL: trailing slash on /bookstack/ — without it, the site
            // returns a 404 page and the catalog appears empty.
            val url = originalBaseUrl.toUrlBuilderSafe()
                .addPath("bookstack")
                .add("page", page.toString())
                .toString()

            val doc = networkClient.get(url).toDocument()

            // Verified selector: ul.list.flex > li
            // Each li contains: <a href="/{id}/"><img .../></a> and <h3><a href>title</a></h3>
            val books = doc.select("ul.list.flex > li").mapNotNull { li ->
                val link = li.selectFirst("h3 a[href], a[href]") ?: return@mapNotNull null
                val cover = li.selectFirst("img[src]")?.attr("src") ?: ""
                val originalTitle = link.text().trim()
                if (originalTitle.isBlank()) return@mapNotNull null
                // Store book URL with THIS source's baseUrl for routing.
                val realUrl = URI(originalBaseUrl).resolve(link.attr("href")).toString()
                val storedUrl = realUrl.replace(originalBaseUrl, baseUrl)
                BookResult(
                    title = translateText(originalTitle),
                    url = storedUrl,
                    coverImageUrl = cover
                )
            }

            // Pagination: .pagination-list > a with hrefs like /bookstack/?page=2
            // "Last page" has no "Next" link (» / next).
            val hasNextPage = (doc.selectFirst("a:contains(»), a:contains(下一頁), a.next") != null) ||
                doc.select(".pagination-list a").let { pages ->
                    // If the current page number is not the last numbered link
                    val lastNum = pages.mapNotNull { it.text().trim().toIntOrNull() }.maxOrNull() ?: 1
                    lastNum > page
                }

            PagedList(
                list = books,
                index = index,
                isLastPage = !hasNextPage
            )
        }
    }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank() || index > 0)
                return@tryConnect PagedList.createEmpty(index = index)

            // Direct URL input: convert to this source's routing URL.
            if (input.startsWith("http") && (input.contains("timotxt.com") || input.contains("translate.goog"))) {
                val fetchUrl = resolveOriginalUrl(input)
                val doc = networkClient.get(fetchUrl).toDocument()
                val rawTitle = doc.selectFirst("h1, .book-name, .book-title")
                    ?.text()?.trim() ?: return@tryConnect PagedList.createEmpty(index)
                val cover = doc.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?: doc.selectFirst("img.bookinfo-pic-img[src]")?.attr("src")
                    ?: ""
                val storedUrl = fetchUrl.replace(originalBaseUrl, baseUrl)
                return@tryConnect PagedList(
                    list = listOf(
                        BookResult(
                            title = translateText(rawTitle),
                            url = storedUrl,
                            coverImageUrl = cover
                        )
                    ),
                    index = index,
                    isLastPage = true
                )
            }

            // Search endpoint: /search/{keyword}
            // (Cloudflare-protected — the interceptor handles the challenge
            // automatically on-device.)
            val searchUrl = originalBaseUrl.toUrlBuilderSafe()
                .addPath("search")
                .addPath(input)
                .toString()

            val doc = networkClient.get(searchUrl).toDocument()
            val books = doc.select("ul.list.flex > li").mapNotNull { li ->
                val link = li.selectFirst("h3 a[href], a[href]") ?: return@mapNotNull null
                val cover = li.selectFirst("img[src]")?.attr("src") ?: ""
                val originalTitle = link.text().trim()
                if (originalTitle.isBlank()) return@mapNotNull null
                val realUrl = URI(originalBaseUrl).resolve(link.attr("href")).toString()
                val storedUrl = realUrl.replace(originalBaseUrl, baseUrl)
                BookResult(
                    title = translateText(originalTitle),
                    url = storedUrl,
                    coverImageUrl = cover
                )
            }

            PagedList(
                list = books,
                index = index,
                isLastPage = true
            )
        }
    }

    // ─── Translation engine (mirrors timotxt_extractor.py) ──────────────

    /**
     * Translate Chinese text to English using the Google Translate free API.
     *
     * Pipeline (identical to `translate_text()` in the Python script):
     *   1. Split text at Chinese/English sentence boundaries.
     *   2. Group sentences into batches of ≤ [BATCH_CHAR_LIMIT] chars.
     *   3. POST each batch to the translate API.
     *   4. Join translated batches with spaces.
     *   5. 0.3s delay between batches to avoid rate-limiting.
     */
    private suspend fun translateText(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""
        if (!isPrimarilyCJK(text)) return@withContext text

        val batches = splitIntoSentenceBatches(text, BATCH_CHAR_LIMIT)
        val translatedParts = mutableListOf<String>()

        for (batch in batches) {
            val translated = translateBatch(batch) ?: batch
            translatedParts.add(translated)
            delay(TRANSLATE_DELAY_MS)
        }

        translatedParts.joinToString(" ").trim()
    }

    /**
     * Translate a single batch (≤ ~2000 chars) via POST to Google Translate.
     * Retries up to [MAX_RETRIES] times with exponential backoff.
     * Returns null if all retries fail.
     */
    private suspend fun translateBatch(text: String): String? = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext ""

        repeat(MAX_RETRIES) { attempt ->
            try {
                val url = TRANSLATE_API_URL.toUrlBuilderSafe()
                    .add("client", "gtx")
                    .add("sl", "zh-CN")
                    .add("tl", "en")
                    .add("dt", "t")
                    .toString()

                val formBody = FormBody.Builder()
                    .add("q", text)
                    .build()

                val request = postRequest(url, body = formBody)
                val response = networkClient.call(request)
                val json = response.body.string()

                return@withContext parseTranslationResponse(json)
            } catch (e: Exception) {
                delay(RETRY_DELAY_MS * (1L shl attempt))
            }
        }
        null
    }

    /**
     * Batch translate chapter titles by joining with " ||| " separator,
     * translating as one unit, then splitting back.
     */
    private suspend fun translateBatchTitles(titles: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            if (titles.isEmpty()) return@withContext emptyList()

            val chunkSize = 15
            val result = mutableListOf<String>()

            for (batch in titles.chunked(chunkSize)) {
                val joined = batch.joinToString(" ||| ")
                if (!isPrimarilyCJK(joined)) {
                    result.addAll(batch)
                    continue
                }

                val translated = translateBatch(joined) ?: joined
                val split = translated.split(" ||| ")

                if (split.size == batch.size) {
                    result.addAll(split)
                } else {
                    val fallbackSplit = translated.split(Regex("\\s*\\|{2,3}\\s*"))
                    if (fallbackSplit.size == batch.size) {
                        result.addAll(fallbackSplit)
                    } else {
                        for (title in batch) {
                            result.add(translateText(title))
                            delay(TRANSLATE_DELAY_MS)
                        }
                    }
                }
                delay(TRANSLATE_DELAY_MS)
            }

            result
        }

    /**
     * Split text into batches of at most [maxSize] characters, breaking at
     * Chinese/English sentence boundaries.
     *
     * Sentence boundaries: `。！？」.!?`
     */
    private fun splitIntoSentenceBatches(text: String, maxSize: Int): List<String> {
        val sentenceBoundary = Regex("(?<=[。！？」.!?])")
        val sentences = sentenceBoundary.split(text)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        val batches = mutableListOf<String>()
        val currentBatch = StringBuilder()

        for (sentence in sentences) {
            if (currentBatch.isNotEmpty() && currentBatch.length + sentence.length > maxSize) {
                batches.add(currentBatch.toString())
                currentBatch.clear()
                currentBatch.append(sentence)
            } else {
                currentBatch.append(sentence)
            }
        }

        if (currentBatch.isNotEmpty()) {
            batches.add(currentBatch.toString())
        }

        return batches
    }

    /**
     * Parse the Google Translate API JSON response.
     * Response format: [[["translated","original",...],...], ..., "detectedLang", ...]
     */
    private fun parseTranslationResponse(json: String): String? {
        return try {
            val root = JsonParser.parseString(json).asJsonArray
            val translatedParts = mutableListOf<String>()
            for (item in root[0].asJsonArray) {
                val innerArray = item.asJsonArray
                if (innerArray.size() > 0 && !innerArray[0].isJsonNull) {
                    translatedParts.add(innerArray[0].asString)
                }
            }
            translatedParts.joinToString("")
        } catch (e: Exception) {
            null
        }
    }
}

// ─── String cleaning extensions ─────────────────────────────────────────

/**
 * Clean Chinese text before translation.
 * Pipeline: strip notices → remove Unicode garbage → collapse whitespace.
 */
fun String.cleanChineseText(): String {
    var result = this
    for (pattern in TimoTxtTranslate.STRIP_PATTERNS) {
        result = result.replace(pattern, "")
    }
    result = result.replace(TimoTxtTranslate.UNICODE_WHITELIST, "")
    result = result.replace(Regex("\\s{3,}"), "  ").trim()
    return result
}

/**
 * Clean English text after translation (remove translated junk notices).
 */
fun String.cleanEnglishJunk(): String {
    var result = this
    for (pattern in TimoTxtTranslate.ENGLISH_JUNK_PATTERNS) {
        result = result.replace(pattern, "")
    }
    return result.replace(Regex("\n{3,}"), "\n\n").trim()
}
