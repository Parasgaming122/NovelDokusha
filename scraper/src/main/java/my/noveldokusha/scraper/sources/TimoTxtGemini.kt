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
 * Architecture:
 *   1. All content is fetched from the original timotxt.com site
 *   2. Chinese text is extracted and sent to Google Gemini API for translation
 *   3. Translated English text is returned to the reader with professional
 *      light-novel quality (two-pass, idiom localization, pronoun consistency)
 *
 * URL strategy:
 *   - Book/chapter URLs stored in the DB use a unique "gemini.goog" domain
 *     so the app's URL-routing can direct them to this source.
 *   - transformChapterUrl() converts them back to timotxt.com for actual
 *     HTTP fetching (Jsoup sees the original Chinese).
 *
 * Gemini API:
 *   - Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent
 *   - Models: gemini-2.5-flash (default, 10 RPM/250 RPD), gemini-2.5-flash-lite (30 RPM/1000 RPD)
 *   - JSON I/O: [{"id": 0, "text": "..."}, ...]
 *   - Rate limiting enforced automatically
 *   - Two-pass translation: literal → polished English
 *
 * Settings:
 *   - API key stored in AppPreferences (user provides their own key)
 *   - Model selection: user picks from flash/flash-lite or enters custom model name
 *   - Temperature configurable (default 0.55 for fiction sweet spot)
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

    /** Original timotxt.com base URL for fetching content */
    private val originalBaseUrl = "https://www.timotxt.com/"

    /** Lazy-initialized Gemini API client */
    private val geminiClient by lazy {
        GeminiApiClient(apiKey = geminiApiKey, model = geminiModel)
    }

    /** Junk text patterns to remove from chapter content (both Chinese and English variants) */
    private val junkPatterns = listOf(
        // Traditional Chinese junk
        Regex("""(?s)溫馨提示.*?諒解!?\s*"""),
        Regex("""手機小說閱讀網"""),
        Regex("""手機用戶.*?最新更新時間"""),
        Regex("""提莫書屋"""),
        // Simplified Chinese junk
        Regex("""(?s)温馨提示.*?谅解!?\s*"""),
        Regex("""手机小说阅读网"""),
        Regex("""手机用户.*?最新更新时间"""),
        Regex("""提莫书屋"""),
        // English-translated junk (from Gemini translation)
        Regex("""(?s)Friendly reminder.*?inconvenience!?\s*"""),
        Regex("""(?s)Warm reminder.*?understanding!?\s*"""),
        // Site name references
        Regex("""timotxt\.com"""),
        Regex("""Mobile novel reading network"""),
        Regex("""Timo Bookhouse"""),
        // AI translation artifacts
        Regex("""(?s)Translated from.*?by Google\s*"""),
        Regex("""(?s)Here is the translation.*?:"""),
        Regex("""(?s)Note:.*"""),
    )

    // ── URL conversion ──────────────────────────────────────────────────

    /**
     * Convert an original timotxt.com URL to its Gemini-translation proxy equivalent.
     * Uses a unique domain "gemini.goog" to differentiate from the Google Translate
     * version (translate.goog) so the app routes to the correct source.
     */
    private fun toGeminiUrl(originalUrl: String): String {
        if (originalUrl.contains("gemini.goog")) return originalUrl

        val uri = java.net.URI(originalUrl)
        val path = uri.path ?: "/"

        // Use unique domain: www-timotxt-com-gemini.goog
        return "https://www-timotxt-com-gemini.goog$path"
    }

    /**
     * Convert a gemini.goog proxy URL back to the original timotxt.com URL.
     */
    private fun fromGeminiUrl(geminiUrl: String): String {
        if (!geminiUrl.contains("gemini.goog")) return geminiUrl

        val uri = java.net.URI(geminiUrl)
        val path = uri.path ?: "/"
        return "https://www.timotxt.com$path"
    }

    /**
     * Transform chapter URL before fetching.
     * Converts gemini.goog → timotxt.com so we get the original page,
     * then translate the extracted text via Gemini API.
     */
    override suspend fun transformChapterUrl(url: String): String = fromGeminiUrl(url)

    // ── Chapter content extraction ──────────────────────────────────────

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            val rawTitle = doc.selectFirst("h1.imgtext[data-type=c]")?.text()?.trim()
                ?: doc.selectFirst("h1.title")?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim()
                ?: return@withContext null

            // Skip translation if no API key configured — return Chinese title.
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

            // Remove ad blocks and non-content elements
            contentEl.select(".gadBlock").remove()
            contentEl.select(".adBlock").remove()
            contentEl.select(".cf-unit").remove()
            contentEl.select("ins.clickforceads").remove()
            contentEl.select("ins.PopIn").remove()
            contentEl.select("script").remove()
            contentEl.select("style").remove()
            contentEl.select("iframe").remove()

            var text = TextExtractor.get(contentEl)

            // Remove junk / reminder text
            for (pattern in junkPatterns) {
                text = pattern.replace(text, "")
            }

            text = text.trim()

            if (text.isBlank()) return@withContext ""

            // Translate if the text is primarily Chinese/CJK.
            // Skip if no API key — return raw Chinese so the reader still works.
            if (geminiApiKey.isNotBlank() && isPrimarilyCJK(text)) {
                val translated = runCatching { translateChapterText(text) }.getOrDefault(text)
                cleanTranslatedText(translated)
            } else {
                text
            }
        }

    /**
     * Translate a full chapter text using Gemini with paragraph-level
     * precision. Splits text into paragraphs, sends as JSON array,
     * and reassembles the translated result.
     */
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

    /**
     * Post-translation cleanup: remove artifacts that Gemini
     * may introduce or that survived the junk-pattern pass.
     */
    private fun cleanTranslatedText(text: String): String {
        var cleaned = text
        // Remove empty lines that pile up after junk removal
        cleaned = Regex("\n{3,}").replace(cleaned, "\n\n")
        // Remove stray AI commentary
        cleaned = Regex("""\bSelect Language\b.*?\bTranslate\b""", RegexOption.IGNORE_CASE)
            .replace(cleaned, "")
        // Remove "Here is the translation" style preambles
        cleaned = Regex("""(?i)here('s| is) the (translation|translated )?text:?""")
            .replace(cleaned, "")
        return cleaned.trim()
    }

    // ── Book metadata ───────────────────────────────────────────────────

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val originalUrl = fromGeminiUrl(bookUrl)
            val doc = networkClient.get(originalUrl).toDocument()
            // The timotxt.com info page has the cover inside
            //   <div class="col ... cover"><img src="https://i1.timotxt.com/thumb/v1/<id>.png" ...></div>
            // There is no og:image meta tag on timotxt.com info pages.
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
            val originalUrl = fromGeminiUrl(bookUrl)
            val doc = networkClient.get(originalUrl).toDocument()

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
            val originalUrl = fromGeminiUrl(bookUrl)
            val dirUrl = if (originalUrl.endsWith("/dir") || originalUrl.endsWith("/dir/")) {
                originalUrl
            } else {
                originalUrl.trimEnd('/') + "/dir"
            }

            val doc = networkClient.get(dirUrl).toDocument()
            val allChapterLinks = doc.select(".chaplist ul.all li a[href]")

            // Collect raw chapter data (title + url).
            //
            // The /dir page has TWO <ul> lists inside .chaplist:
            //   1. ul.flex...three-900 (12 links, NEWEST first — sidebar)
            //   2. ul.flex...three-900.all (all links, OLDEST first — complete list)
            // The `.all` selector targets #2 which is already oldest-first,
            // so no reversal is needed.
            val rawChapters = if (allChapterLinks.isNotEmpty()) {
                allChapterLinks.map { link ->
                    ChapterResult(
                        title = link.text().trim(),
                        url = toGeminiUrl(resolveUrl(link.attr("href"), originalUrl))
                    )
                }
            } else {
                val infoDoc = networkClient.get(originalUrl).toDocument()
                infoDoc.select(".chaplist ul li a[href]").map { link ->
                    ChapterResult(
                        title = link.text().trim(),
                        url = toGeminiUrl(resolveUrl(link.attr("href"), originalUrl))
                    )
                }
            }

            // Batch-translate chapter titles using Gemini.
            // If no API key is configured, skip translation and return the
            // original Chinese titles — the user can still read chapters
            // (chapter body translation also gracefully falls back).
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
            val url = "https://www.timotxt.com/bookstack/".toUrlBuilderSafe().apply {
                if (page > 1) add("page", page.toString())
            }

            val doc = networkClient.get(url).toDocument()
            // Verified selector: ul.list.flex > li
            val rawBooks = doc.select("ul.list.flex > li").mapNotNull { item ->
                val link = item.selectFirst("h3 a[href], a[href]") ?: return@mapNotNull null
                val cover = item.selectFirst("img[src]")?.attr("src") ?: ""
                val rawTitle = link.text().trim()
                if (rawTitle.isBlank()) return@mapNotNull null

                Triple(rawTitle, link.attr("href"), cover)
            }

            // Batch-translate titles that are CJK using Gemini.
            // If no API key is set, skip translation and show Chinese titles
            // — the catalog must always load so the user can browse books.
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

            // Pagination: the site serves <li class="next pagination-link">
            // on every page except the last (where it has the `disabled`
            // class). Mirrors the TimoTxt.kt / TimoTxtTranslate.kt selector.
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

            // URL-as-search: treat URL-like input as a direct book URL
            if (isUrlLike(input)) {
                val bookUrl = normalizeUrl(input)
                val doc = networkClient.get(bookUrl).toDocument()
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

            // Regular search via original timotxt.com
            val searchUrl = "https://www.timotxt.com/search/".toUrlBuilderSafe()
                .addPath(input)
                .toString()

            val doc = networkClient.get(searchUrl).toDocument()
            val rawBooks = doc.select("ul.list.flex > li").mapNotNull { item ->
                val link = item.selectFirst("h3 a[href], a[href]") ?: return@mapNotNull null
                val cover = item.selectFirst("img[src]")?.attr("src") ?: ""
                val rawTitle = link.text().trim()
                if (rawTitle.isBlank()) return@mapNotNull null
                Triple(rawTitle, link.attr("href"), cover)
            }

            // Batch-translate search result titles using Gemini.
            // Skip if no API key — show Chinese titles.
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
            ch.code in 0x4E00..0x9FFF ||    // CJK Unified Ideographs
            ch.code in 0x3400..0x4DBF ||    // CJK Extension A
            ch.code in 0x20000..0x2A6DF ||  // CJK Extension B
            ch.code in 0x2A700..0x2B73F ||  // CJK Extension C
            ch.code in 0x2B740..0x2B81F ||  // CJK Extension D
            ch.code in 0xF900..0xFAFF ||    // CJK Compatibility Ideographs
            ch.code in 0xAC00..0xD7AF ||    // Korean syllables (encoding artifacts)
            ch.code in 0x3040..0x309F ||    // Hiragana
            ch.code in 0x30A0..0x30FF       // Katakana
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
                trimmed.contains("gemini.goog")
    }

    private fun normalizeUrl(input: String): String {
        var url = input.trim()
        // If it's already a gemini URL, convert back to original first
        if (url.contains("gemini.goog")) {
            url = fromGeminiUrl(url)
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
        if (href.startsWith("http")) return href
        if (href.startsWith("/")) return originalBaseUrl.trimEnd('/') + href
        return base.trimEnd('/') + "/" + href.removePrefix("/")
    }

    companion object {
        const val CHINESE_THRESHOLD = 0.12f
    }
}
