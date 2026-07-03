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
import my.noveldokusha.network.getRequest
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document
import java.net.URI

/**
 * Auto-translating source for timotxt.com via Google Translate proxy.
 *
 * The site is accessed through translate.goog, which rewrites the domain:
 *   www.timotxt.com → www-timotxt-com.translate.goog
 * Chapters are fetched from the original domain for clean HTML,
 * then text content is translated via Google Translate free API.
 */
class TimoTxtTranslate(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "timotxt_translate"
    override val nameStrId = R.string.source_name_timotxt_translate
    override val baseUrl = "https://www-timotxt-com.translate.goog/"
    override val catalogUrl = "https://www-timotxt-com.translate.goog/"
    override val language = LanguageCode.ENGLISH

    private val originalBaseUrl = "https://www.timotxt.com/"

    companion object {
        /** Chinese junk text patterns to remove before translation */
        val CHINESE_JUNK_PATTERNS = listOf(
            "溫馨提示",
            "手機小說閱讀網",
            "手機用戶",
            "最新更新時間",
            "提莫書屋",
            "timotxt.com"
        )

        /** English junk text patterns to remove after translation (translated variants) */
        val ENGLISH_JUNK_PATTERNS = listOf(
            "warm reminder",
            "warm tip",
            "friendly reminder",
            "mobile novel reading network",
            "mobile phone novel",
            "mobile user",
            "latest update time",
            "timo book house",
            "timotxt.com"
        )

        private const val TRANSLATE_API_URL =
            "https://translate.googleapis.com/translate_a/single"
        private const val MAX_CHUNK_SIZE = 4500
        private const val MAX_RETRIES = 3

        /**
         * Check if text is primarily CJK characters.
         * Returns true if CJK ratio > 0.12
         */
        fun isPrimarilyCJK(text: String): Boolean {
            if (text.isBlank()) return false
            val cjkCount = text.count { ch ->
                val code = ch.code
                (code in 0x4E00..0x9FFF) ||   // CJK Unified Ideographs
                (code in 0x3400..0x4DBF) ||   // CJK Unified Ideographs Extension A
                (code in 0x2E80..0x2EFF) ||   // CJK Radicals Supplement
                (code in 0x3000..0x303F) ||   // CJK Symbols and Punctuation
                (code in 0xFF00..0xFFEF) ||   // Halfwidth and Fullwidth Forms
                (code in 0x3040..0x309F) ||   // Hiragana
                (code in 0x30A0..0x30FF)       // Katakana
            }
            return cjkCount.toDouble() / text.length > 0.12
        }

        /**
         * Check if text is primarily Latin/English characters.
         * Returns true if Latin ratio > 0.25
         */
        fun isPrimarilyEnglish(text: String): Boolean {
            if (text.isBlank()) return false
            val latinCount = text.count { it.code in 0x0041..0x024F }
            return latinCount.toDouble() / text.length > 0.25
        }
    }

    /**
     * Convert translate.goog URLs back to original timotxt.com URLs
     * so we can fetch clean HTML without Google Translate's DOM modifications.
     */
    override suspend fun transformChapterUrl(url: String): String =
        withContext(Dispatchers.Default) {
            if (url.contains("translate.goog")) {
                // Convert www-timotxt-com.translate.goog/path → www.timotxt.com/path
                url.replace("https://www-timotxt-com.translate.goog", "https://www.timotxt.com")
                    // Remove Google Translate query parameters
                    .replace(Regex("[?&]_x_tr_(sl|tl|hl|pto|pto_ctx)=[^&]*"), "")
                    .replace("?&", "?")
                    .replace(Regex("[?&]$"), "")
            } else {
                url
            }
        }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst(".chapter-content h1, .chapter-content .title, h1.chapter-title")
                ?.text()
                ?.let { translateText(it) }
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
            }

            val rawText = TextExtractor.get(contentEl).cleanChineseJunk()
            val paragraphs = rawText.split("\n\n").filter { it.isNotBlank() }

            // Translate paragraphs in chunks
            val translatedParagraphs = translateParagraphs(paragraphs)
            translatedParagraphs.joinToString("\n\n").cleanEnglishJunk()
        }

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val fetchUrl = resolveOriginalUrl(bookUrl)
            val doc = networkClient.get(fetchUrl).toDocument()
            doc.selectFirst("meta[property=og:image]")
                ?.attr("content")
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
                ?: doc.selectFirst(".intro")
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

            val chapterLinks = doc.select(".chaplist ul.all li a[href]")
                .takeIf { it.isNotEmpty() }
                ?: doc.select(".chaplist ul li a[href]")

            // Batch translate chapter titles
            val titles = chapterLinks.map { it.text().trim() }
            val translatedTitles = translateBatchTitles(titles)

            chapterLinks.mapIndexed { index, element ->
                ChapterResult(
                    title = translatedTitles.getOrElse(index) { titles[index] },
                    url = URI(originalBaseUrl).resolve(element.attr("href")).toString()
                )
            }
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val page = index + 1
            val url = originalBaseUrl.toUrlBuilderSafe()
                .addPath("bookstack")
                .add("page", page.toString())
                .toString()

            val doc = networkClient.get(url).toDocument()
            val books = doc.select(".book-list li, .book-list .item, .list-item")
                .mapNotNull {
                    val link = it.selectFirst("a[href]") ?: return@mapNotNull null
                    val cover = it.selectFirst("img[src]")?.attr("src") ?: ""
                    val originalTitle = link.text().trim()
                    BookResult(
                        title = translateText(originalTitle),
                        url = URI(originalBaseUrl).resolve(link.attr("href")).toString(),
                        coverImageUrl = cover
                    )
                }

            val hasNextPage = doc.selectFirst("a.next, .pagination .next, .pager a:contains(下一頁)")
                != null || doc.select(".pagination li, .pager li").let { pages ->
                pages.isNotEmpty() && !pages.last()?.hasClass("active")!!
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

            // If the input looks like a URL, treat it as a direct book URL
            if (input.startsWith("http") && (input.contains("timotxt.com") || input.contains("translate.goog"))) {
                val fetchUrl = resolveOriginalUrl(input)
                val doc = networkClient.get(fetchUrl).toDocument()
                val rawTitle = doc.selectFirst("h1, .book-name, .book-title")
                    ?.text()?.trim() ?: return@tryConnect PagedList.createEmpty(index)
                val cover = doc.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?: doc.selectFirst(".cover img[src]")?.attr("src")
                    ?: ""
                return@tryConnect PagedList(
                    list = listOf(
                        BookResult(
                            title = translateText(rawTitle),
                            url = fetchUrl,
                            coverImageUrl = cover
                        )
                    ),
                    index = index,
                    isLastPage = true
                )
            }

            val url = originalBaseUrl.toUrlBuilderSafe()
                .addPath("bookstack")
                .add("keyword", input)
                .toString()

            val doc = networkClient.get(url).toDocument()
            val books = doc.select(".book-list li, .book-list .item, .list-item, .search-result li")
                .mapNotNull {
                    val link = it.selectFirst("a[href]") ?: return@mapNotNull null
                    val cover = it.selectFirst("img[src]")?.attr("src") ?: ""
                    val originalTitle = link.text().trim()
                    BookResult(
                        title = translateText(originalTitle),
                        url = URI(originalBaseUrl).resolve(link.attr("href")).toString(),
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

    // ─── Translation helpers ──────────────────────────────────────────

    /**
     * Convert a translate.goog URL back to the original timotxt.com URL.
     */
    private fun resolveOriginalUrl(url: String): String {
        if (url.contains("translate.goog")) {
            return url.replace("https://www-timotxt-com.translate.goog", "https://www.timotxt.com")
                .replace(Regex("[?&]_x_tr_(sl|tl|hl|pto|pto_ctx)=[^&]*"), "")
                .replace("?&", "?")
                .replace(Regex("[?&]$"), "")
        }
        return url
    }

    /**
     * Translate a single text string using Google Translate free API.
     */
    private suspend fun translateText(text: String): String = withContext(Dispatchers.IO) {
        if (text.isBlank() || !isPrimarilyCJK(text)) return@withContext text

        retryWithBackoff(MAX_RETRIES) {
            val url = TRANSLATE_API_URL.toUrlBuilderSafe()
                .add("client", "gtx")
                .add("sl", "auto")
                .add("tl", "en")
                .add("dt", "t")
                .add("q", text)
                .toString()

            val request = getRequest(url)
            val response = networkClient.call(request)
            val json = response.body.string()

            parseTranslationResponse(json)
        } ?: text
    }

    /**
     * Translate a list of paragraphs, splitting into chunks of max 4500 chars
     * at paragraph boundaries.
     */
    private suspend fun translateParagraphs(paragraphs: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            if (paragraphs.isEmpty()) return@withContext emptyList()

            val chunks = chunkParagraphs(paragraphs, MAX_CHUNK_SIZE)
            val result = mutableListOf<String>()

            for (chunk in chunks) {
                val combined = chunk.joinToString("\n\n")
                val translated = translateText(combined)
                // Split the translated text back into paragraphs
                val translatedParagraphs = translated.split("\n\n")
                result.addAll(translatedParagraphs)
            }

            result
        }

    /**
     * Batch translate chapter titles by joining with " ||| " separator,
     * translating as one unit, then splitting back.
     */
    private suspend fun translateBatchTitles(titles: List<String>): List<String> =
        withContext(Dispatchers.IO) {
            if (titles.isEmpty()) return@withContext emptyList()

            val chunkSize = 15  // reasonable batch size for titles
            val result = mutableListOf<String>()

            for (batch in titles.chunked(chunkSize)) {
                val joined = batch.joinToString(" ||| ")
                if (!isPrimarilyCJK(joined)) {
                    result.addAll(batch)
                    continue
                }

                val translated = retryWithBackoff(MAX_RETRIES) {
                    val url = TRANSLATE_API_URL.toUrlBuilderSafe()
                        .add("client", "gtx")
                        .add("sl", "auto")
                        .add("tl", "en")
                        .add("dt", "t")
                        .add("q", joined)
                        .toString()

                    val request = getRequest(url)
                    val response = networkClient.call(request)
                    val json = response.body.string()

                    parseTranslationResponse(json)
                } ?: joined

                val split = translated.split(" ||| ")
                if (split.size == batch.size) {
                    result.addAll(split)
                } else {
                    // Fallback: try splitting on "|||" or just translate individually
                    val fallbackSplit = translated.split(Regex("\\s*\\|{2,3}\\s*"))
                    if (fallbackSplit.size == batch.size) {
                        result.addAll(fallbackSplit)
                    } else {
                        // Last resort: translate individually
                        result.addAll(batch.map { translateText(it) })
                    }
                }
            }

            result
        }

    /**
     * Split paragraphs into chunks of at most [maxSize] characters,
     * breaking at paragraph boundaries.
     */
    private fun chunkParagraphs(paragraphs: List<String>, maxSize: Int): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var currentChunk = mutableListOf<String>()
        var currentSize = 0

        for (para in paragraphs) {
            val paraSize = para.length + 2 // account for \n\n separator
            if (currentChunk.isNotEmpty() && currentSize + paraSize > maxSize) {
                chunks.add(currentChunk.toList())
                currentChunk = mutableListOf()
                currentSize = 0
            }
            currentChunk.add(para)
            currentSize += paraSize
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk.toList())
        }

        return chunks
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
                if (innerArray.size() > 0) {
                    translatedParts.add(innerArray[0].asString)
                }
            }
            translatedParts.joinToString("")
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Retry with exponential backoff.
     * Returns null if all retries fail.
     */
    private suspend fun <T> retryWithBackoff(
        maxRetries: Int,
        initialDelayMs: Long = 1000,
        block: suspend () -> T?
    ): T? {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                val delayMs = initialDelayMs * (1L shl attempt) // exponential backoff
                delay(delayMs)
            }
        }
        return null
    }
}

/** Remove Chinese junk text patterns */
fun String.cleanChineseJunk(): String {
    var result = this
    for (pattern in TimoTxtTranslate.CHINESE_JUNK_PATTERNS) {
        result = result.replace(pattern, "")
    }
    return result.replace(Regex("\n{3,}"), "\n\n").trim()
}

/** Remove English junk text patterns (post-translation) */
fun String.cleanEnglishJunk(): String {
    var result = this
    for (pattern in TimoTxtTranslate.ENGLISH_JUNK_PATTERNS) {
        result = result.replace(pattern, "")
    }
    return result.replace(Regex("\n{3,}"), "\n\n").trim()
}
