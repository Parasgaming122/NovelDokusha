package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import org.jsoup.nodes.Document

/**
 * TimoTxt (Gemini) — Chinese novels auto-translated to English via Gemini AI.
 *
 * **URL routing strategy**: Uses `https://www-timotxt-com-gemini.goog/` as
 * the baseUrl for routing — this fake domain lets `getCompatibleSource()`
 * distinguish Gemini books from TimoTxtTranslate books. The domain does
 * NOT resolve in DNS; it exists only as a routing key.
 *
 * **Fetching**: All HTTP fetches go through the `translate.goog` proxy
 * (with `_x_tr_*` params) for Cloudflare bypass — same as TimoTxtTranslate.
 * The proxy returns Chinese HTML to OkHttp. The app then translates the
 * extracted text via the **Gemini API** (not Google Translate API).
 *
 * **WebView**: `transformChapterUrl()` converts `gemini.goog` URLs to
 * `translate.goog` URLs with params. The reader calls this before
 * opening webview, so the user sees the Google-Translate version in
 * the browser. For the actual chapter reading experience, the Gemini
 * translation (higher quality) is used.
 *
 * **Gemini API**:
 *   - Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 *   - Models: gemini-2.5-flash (default, 10 RPM/250 RPD), gemini-2.5-flash-lite (30 RPM/1000 RPD)
 *   - JSON I/O: [{"id": 0, "text": "..."}, ...]
 *   - Two-pass translation: literal → polished English
 *
 * **Settings**: API key stored in AppPreferences (user provides their own).
 * Without a key, all translation is skipped and Chinese text is shown.
 */
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

    companion object {
        private const val TRANSLATE_PARAMS =
            "_x_tr_sl=zh-CN&_x_tr_tl=en&_x_tr_hl=en&_x_tr_pto=wapp"
        const val CHINESE_THRESHOLD = 0.12f
    }

    private val junkPatterns = listOf(
        Regex("""(?s)溫馨提示.*?諒解!?\s*"""),
        Regex("""手機小說閱讀網"""),
        Regex("""手機用戶.*?最新更新時間"""),
        Regex("""提莫書屋"""),
        Regex("""(?s)温馨提示.*?谅解!?\s*"""),
        Regex("""手机小说阅读网"""),
        Regex("""手机用户.*?最新更新时间"""),
        Regex("""提莫书屋"""),
        Regex("""(?s)Friendly reminder.*?inconvenience!?\s*"""),
        Regex("""(?s)Warm reminder.*?understanding!?\s*"""),
        Regex("""timotxt\.com"""),
        Regex("""Mobile novel reading network"""),
        Regex("""Timo Bookhouse"""),
        Regex("""(?s)Translated from.*?by Google\s*"""),
        Regex("""(?s)Here is the translation.*?:"""),
        Regex("""(?s)Note:.*"""),
    )

    // ── URL conversion ──────────────────────────────────────────────────

    /**
     * Convert any URL to the translate.goog proxy URL with params.
     * Used for all HTTP fetching (CF bypass) and for webview.
     */
    private fun toTranslateUrl(url: String): String {
        if (url.isBlank()) return url

        var cleanUrl = url
            .replace(Regex("[?&]_x_tr_(sl|tl|hl|pto|pto_ctx)=[^&]*"), "")
            .replace("?&", "?")
            .replace(Regex("[?&]$"), "")
            .trimEnd('&', '?')

        // Convert gemini.goog → timotxt.com → translate.goog
        cleanUrl = cleanUrl.replace(
            "https://www-timotxt-com-gemini.goog",
            "https://www-timotxt.com"
        )
        cleanUrl = cleanUrl.replace(
            "https://www.timotxt.com",
            "https://www-timotxt-com.translate.goog"
        )

        val separator = if (cleanUrl.contains("?")) "&" else "?"
        return "$cleanUrl$separator$TRANSLATE_PARAMS"
    }

    /**
     * Convert a timotxt.com URL to the gemini.goog routing URL (for storage).
     */
    private fun toGeminiUrl(originalUrl: String): String {
        // Strip translate params first
        var url = originalUrl
            .replace(Regex("[?&]_x_tr_(sl|tl|hl|pto|pto_ctx)=[^&]*"), "")
            .replace("?&", "?")
            .replace(Regex("[?&]$"), "")
            .trimEnd('&', '?')

        url = url.replace(
            "https://www-timotxt-com.translate.goog",
            "https://www.timotxt.com"
        )
        if (url.contains("gemini.goog")) return url

        val uri = java.net.URI(url)
        val path = uri.path ?: "/"
        return "https://www-timotxt-com-gemini.goog$path"
    }

    /**
     * Transform chapter URL before fetching OR opening in webview.
     * Converts gemini.goog → translate.goog with params.
     *
     * This is called by:
     * - DownloaderRepository.bookChapter() before OkHttp fetch
     * - ReaderActivity.onOpenChapterInWeb() before opening webview
     */
    override suspend fun transformChapterUrl(url: String): String =
        toTranslateUrl(url)

    // ── Chapter content extraction ──────────────────────────────────────

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            val rawTitle = doc.selectFirst("h1.imgtext[data-type=c]")?.text()?.trim()
                ?: doc.selectFirst("h1.title")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim()
                ?: return@withContext null

            if (geminiApiKey.isBlank() || !isPrimarilyCJK(rawTitle)) {
                rawTitle
            } else {
                runCatching { geminiClient.translateText(rawTitle) }.getOrDefault(rawTitle)
            }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val contentEl = doc.selectFirst(".chapter-content .content")
                ?: doc.selectFirst(".chapter-content")
                ?: doc.selectFirst(".content")
                ?: return@withContext ""

            contentEl.select(".gadBlock").remove()
            contentEl.select(".adBlock").remove()
            contentEl.select(".cf-unit").remove()
            contentEl.select("ins.clickforceads").remove()
            contentEl.select("ins.PopIn").remove()
            contentEl.select("script").remove()
            contentEl.select("style").remove()
            contentEl.select("iframe").remove()

            var text = TextExtractor.get(contentEl)

            for (pattern in junkPatterns) {
                text = pattern.replace(text, "")
            }

            text = text.trim()

            if (text.isBlank()) return@withContext ""

            if (geminiApiKey.isNotBlank() && isPrimarilyCJK(text)) {
                val translated = runCatching { translateChapterText(text) }.getOrDefault(text)
                cleanTranslatedText(translated)
            } else {
                text
            }
        }

    private suspend fun translateChapterText(text: String): String {
        val paragraphs = text.split("\n")
            .filter { it.isNotBlank() }
            .mapIndexed { index, line ->
                GeminiApiClient.ParagraphItem(id = index, text = line.trim())
            }

        if (paragraphs.isEmpty()) return text

        val translated = geminiClient.translateParagraphs(paragraphs)

        val idToTranslated = translated.associateBy { it.id }
        return paragraphs.mapNotNull { original ->
            idToTranslated[original.id]?.text ?: original.text
        }.joinToString("\n\n")
    }

    private fun cleanTranslatedText(text: String): String {
        var cleaned = text
        cleaned = Regex("\n{3,}").replace(cleaned, "\n\n")
        cleaned = Regex("""\bSelect Language\b.*?\bTranslate\b""", RegexOption.IGNORE_CASE)
            .replace(cleaned, "")
        cleaned = Regex("""(?i)here('s| is) the (translation|translated )?text:?""")
            .replace(cleaned, "")
        return cleaned.trim()
    }

    // ── Book metadata ───────────────────────────────────────────────────

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(toTranslateUrl(bookUrl)).toDocument()
            doc.selectFirst(".cover img[src]")
                ?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")
                    ?.attr("content")
        }
    }

    override suspend fun getBookDescription(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(toTranslateUrl(bookUrl)).toDocument()

            val description = doc.selectFirst("meta[name=description]")
                ?.attr("content")
                ?.trim()
                ?: doc.selectFirst(".intro")
                    ?.let { TextExtractor.get(it).trim() }
                ?: return@tryConnect null

            if (geminiApiKey.isBlank() || !isPrimarilyCJK(description)) {
                description
            } else {
                runCatching { geminiClient.translateText(description) }.getOrDefault(description)
            }
        }
    }

    // ── Chapter list ────────────────────────────────────────────────────

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val dirUrl = toTranslateUrl(
                bookUrl.toUrlBuilderSafe().addPath("dir").toString()
            )

            val doc = networkClient.get(dirUrl).toDocument()
            val allChapterLinks = doc.select(".chaplist ul.all li a[href]")

            val rawChapters = if (allChapterLinks.isNotEmpty()) {
                allChapterLinks.map { link ->
                    ChapterResult(
                        title = link.text().trim(),
                        url = toGeminiUrl(resolveUrl(link.attr("href"), originalBaseUrl))
                    )
                }
            } else {
                val infoDoc = networkClient.get(toTranslateUrl(bookUrl)).toDocument()
                infoDoc.select(".chaplist ul li a[href]").map { link ->
                    ChapterResult(
                        title = link.text().trim(),
                        url = toGeminiUrl(resolveUrl(link.attr("href"), originalBaseUrl))
                    )
                }
            }

            if (geminiApiKey.isBlank()) return@tryConnect rawChapters

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
        }
    }

    // ── Catalog browsing ────────────────────────────────────────────────

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val page = index + 1
            val url = toTranslateUrl(
                "https://www.timotxt.com/bookstack/?page=$page"
            )

            val doc = networkClient.get(url).toDocument()
            val rawBooks = doc.select("ul.list.flex > li").mapNotNull { item ->
                val link = item.selectFirst("h3 a[href], a[href]") ?: return@mapNotNull null
                val cover = item.selectFirst("img[src]")?.attr("src") ?: ""
                val rawTitle = link.text().trim()
                if (rawTitle.isBlank()) return@mapNotNull null
                Triple(rawTitle, link.attr("href"), cover)
            }

            val translatedMap = if (geminiApiKey.isNotBlank() && rawBooks.isNotEmpty()) {
                val cjkTitles = rawBooks.map { it.first }.filter { isPrimarilyCJK(it) }
                if (cjkTitles.isNotEmpty()) {
                    val titleItems = cjkTitles.mapIndexed { index, title ->
                        GeminiApiClient.ParagraphItem(id = index, text = title)
                    }
                    val translated = geminiClient.translateParagraphs(titleItems)
                    cjkTitles.zip(translated.map { it.text }).toMap()
                } else {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            val books = rawBooks.map { (rawTitle, href, cover) ->
                BookResult(
                    title = translatedMap[rawTitle] ?: rawTitle,
                    url = toGeminiUrl(resolveUrl(href, originalBaseUrl)),
                    coverImageUrl = resolveUrl(cover, originalBaseUrl)
                )
            }

            val hasNextPage = doc.selectFirst("li.next.pagination-link:not(.disabled)") != null

            PagedList(
                list = books,
                index = index,
                isLastPage = !hasNextPage
            )
        }
    }

    // ── Search ──────────────────────────────────────────────────────────

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank())
                return@tryConnect PagedList.createEmpty(index = index)

            if (isUrlLike(input)) {
                val bookUrl = normalizeUrl(input)
                val doc = networkClient.get(toTranslateUrl(bookUrl)).toDocument()
                val rawTitle = doc.selectFirst("h1.title")?.text()?.trim()
                    ?: doc.selectFirst("h1")?.text()?.trim()
                    ?: doc.selectFirst("meta[name=og:novel:book_name]")?.attr("content")
                    ?: return@tryConnect PagedList.createEmpty(index)

                val displayTitle = if (geminiApiKey.isNotBlank() && isPrimarilyCJK(rawTitle)) {
                    runCatching { geminiClient.translateText(rawTitle) }.getOrDefault(rawTitle)
                } else {
                    rawTitle
                }

                val cover = doc.selectFirst(".cover img[src]")?.attr("src")
                    ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: ""

                return@tryConnect PagedList(
                    list = listOf(
                        BookResult(
                            title = displayTitle,
                            url = toGeminiUrl(bookUrl),
                            coverImageUrl = cover
                        )
                    ),
                    index = index,
                    isLastPage = true
                )
            }

            val searchUrl = toTranslateUrl(
                "https://www.timotxt.com/search/".toUrlBuilderSafe()
                    .addPath(input)
                    .toString()
            )

            val doc = networkClient.get(searchUrl).toDocument()
            val rawBooks = doc.select("ul.list.flex > li").mapNotNull { item ->
                val link = item.selectFirst("h3 a[href], a[href]") ?: return@mapNotNull null
                val cover = item.selectFirst("img[src]")?.attr("src") ?: ""
                val rawTitle = link.text().trim()
                if (rawTitle.isBlank()) return@mapNotNull null
                Triple(rawTitle, link.attr("href"), cover)
            }

            val translatedMap = if (geminiApiKey.isNotBlank() && rawBooks.isNotEmpty()) {
                val cjkTitles = rawBooks.map { it.first }.filter { isPrimarilyCJK(it) }
                if (cjkTitles.isNotEmpty()) {
                    val titleItems = cjkTitles.mapIndexed { idx, title ->
                        GeminiApiClient.ParagraphItem(id = idx, text = title)
                    }
                    val translated = geminiClient.translateParagraphs(titleItems)
                    cjkTitles.zip(translated.map { it.text }).toMap()
                } else {
                    emptyMap()
                }
            } else {
                emptyMap()
            }

            val books = rawBooks.map { (rawTitle, href, cover) ->
                BookResult(
                    title = translatedMap[rawTitle] ?: rawTitle,
                    url = toGeminiUrl(resolveUrl(href, originalBaseUrl)),
                    coverImageUrl = resolveUrl(cover, originalBaseUrl)
                )
            }

            PagedList(
                list = books,
                index = index,
                isLastPage = true
            )
        }
    }

    // ── Language detection helpers ──────────────────────────────────────

    private fun isPrimarilyCJK(text: String): Boolean {
        if (text.isBlank()) return false

        val cjkChars = text.count { ch ->
            ch.code in 0x4E00..0x9FFF ||
            ch.code in 0x3400..0x4DBF ||
            ch.code in 0x20000..0x2A6DF ||
            ch.code in 0x2A700..0x2B73F ||
            ch.code in 0x2B740..0x2B81F ||
            ch.code in 0xF900..0xFAFF ||
            ch.code in 0xAC00..0xD7AF ||
            ch.code in 0x3040..0x309F ||
            ch.code in 0x30A0..0x30FF
        }

        val totalNonWhitespace = text.count { !it.isWhitespace() }
        return totalNonWhitespace > 0 &&
                cjkChars.toFloat() / totalNonWhitespace > CHINESE_THRESHOLD
    }

    // ── URL helper utilities ────────────────────────────────────────────

    private fun isUrlLike(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.startsWith("http://") ||
                trimmed.startsWith("https://") ||
                trimmed.startsWith("www.") ||
                trimmed.contains("timotxt.com") ||
                trimmed.contains("timotxt.cn") ||
                trimmed.contains("gemini.goog") ||
                trimmed.contains("translate.goog")
    }

    private fun normalizeUrl(input: String): String {
        var url = input.trim()
        // Strip translate params
        url = url.replace(Regex("[?&]_x_tr_(sl|tl|hl|pto|pto_ctx)=[^&]*"), "")
            .replace("?&", "?")
            .replace(Regex("[?&]$"), "")
            .trimEnd('&', '?')

        if (url.contains("gemini.goog")) {
            url = url.replace(
                "https://www-timotxt-com-gemini.goog",
                "https://www.timotxt.com"
            )
        }
        if (url.contains("translate.goog")) {
            url = url.replace(
                "https://www-timotxt-com.translate.goog",
                "https://www.timotxt.com"
            )
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = if (url.startsWith("www.")) "https://$url" else "https://www.timotxt.com/$url"
        }
        if (!url.endsWith("/") && !url.contains(".html")) {
            url = "$url/"
        }
        return url
    }

    private fun resolveUrl(href: String, base: String): String {
        // Strip translate params from href if present
        val cleanHref = href.replace(Regex("[?&]_x_tr_(sl|tl|hl|pto|pto_ctx)=[^&]*"), "")
            .replace("?&", "?")
            .replace(Regex("[?&]$"), "")
            .trimEnd('&', '?')

        if (cleanHref.startsWith("http")) {
            // Convert translate.goog URLs back to timotxt.com
            return cleanHref.replace(
                "https://www-timotxt-com.translate.goog",
                "https://www.timotxt.com"
            )
        }
        if (cleanHref.startsWith("/")) return originalBaseUrl.trimEnd('/') + cleanHref
        return base.trimEnd('/') + "/" + cleanHref.removePrefix("/")
    }
}
