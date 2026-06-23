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
 * TimoTxt (Translate) — Chinese novels auto-translated via Google Translate proxy.
 *
 * Uses Google Translate's server-side proxy to fetch translated content.
 * The proxy URL format converts the original URL:
 *   https://www.timotxt.com/1302186772/1.html
 * becomes:
 *   https://www-timotxt-com.translate.goog/1302186772/1.html?_x_tr_sl=ur&_x_tr_tl=en&_x_tr_hl=en&_x_tr_pto=wapp
 *
 * The source language is set to "ur" (Urdu) as specified to bypass certain restrictions,
 * and target is "en" (English). Google auto-detects the actual source language.
 *
 * Includes a delay when fetching translated pages to allow Google Translate
 * time to process, and removes Google Translate UI elements and junk text.
 *
 * Search supports direct URL input: if the search query looks like a URL,
 * it will be treated as a direct book URL instead of a text search.
 */
class TimoTxtTranslate(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "timotxt_translate"
    override val nameStrId = R.string.source_name_timotxt_translate
    override val baseUrl = "https://www-timotxt-com.translate.goog/"
    override val catalogUrl = "https://www-timotxt-com.translate.goog/?_x_tr_sl=ur&_x_tr_tl=en&_x_tr_hl=en&_x_tr_pto=wapp"
    override val language = LanguageCode.ENGLISH

    /** Original timotxt.com base URL for resolving cover images and non-translated resources */
    private val originalBaseUrl = "https://www.timotxt.com/"

    /** Google Translate proxy query parameters */
    private val translateParams = "?_x_tr_sl=ur&_x_tr_tl=en&_x_tr_hl=en&_x_tr_pto=wapp"

    /** Delay in milliseconds to wait for Google Translate processing */
    private companion object {
        const val TRANSLATE_DELAY_MS = 1500L
    }

    /** Junk text patterns to remove from chapter content */
    private val junkPatterns = listOf(
        Regex("""(?s)溫馨提示.*?諒解!?\s*"""),
        Regex("""(?s)Friendly reminder.*?inconvenience!?\s*"""),
        Regex("""(?s)温馨提示.*?谅解!?\s*"""),
        Regex("""手機小說閱讀網"""),
        Regex("""手機用戶.*?最新更新時間"""),
        Regex("""timotxt\.com"""),
        Regex("""提莫書屋"""),
        Regex("""手机小说阅读网"""),
        Regex("""手机用户.*?最新更新时间"""),
        Regex("""提莫书屋"""),
    )

    /**
     * Convert an original timotxt URL to its Google Translate proxy equivalent.
     *
     * Encoding algorithm:
     * 1. Remove "www." prefix from host
     * 2. Replace all "-" with "--" (double-hyphen encoding)
     * 3. Replace all "." with "-" (single-hyphen encoding)
     * 4. Append ".translate.goog"
     * 5. Preserve original path
     * 6. Append translation query parameters
     */
    private fun toTranslateUrl(originalUrl: String): String {
        // Already a translate URL
        if (originalUrl.contains("translate.goog")) return originalUrl

        val uri = java.net.URI(originalUrl)
        val host = uri.host.removePrefix("www.")
        val encodedHost = host
            .replace("-", "--")
            .replace(".", "-")
        val proxyHost = "$encodedHost.translate.goog"
        val path = uri.path ?: "/"
        val scheme = "https"

        return "$scheme://$proxyHost$path$translateParams"
    }

    /**
     * Convert a translate proxy URL back to the original timotxt URL.
     */
    private fun fromTranslateUrl(translateUrl: String): String {
        if (!translateUrl.contains("translate.goog")) return translateUrl

        val uri = java.net.URI(translateUrl)
        val proxyHost = uri.host
        val encodedPart = proxyHost.substringBefore(".translate.goog")

        // Reverse encoding: "--" → "-", "-" → "."
        val originalHost = encodedPart
            .replace("--", "__HYPHEN__")
            .replace("-", ".")
            .replace("__HYPHEN__", "-")

        val path = uri.path ?: "/"
        return "https://www.$originalHost$path"
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.imgtext[data-type=c]")?.text()?.trim()
                ?: doc.selectFirst("h1.title")?.text()?.trim()
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val contentEl = doc.selectFirst(".chapter-content .content")
                ?: doc.selectFirst(".chapter-content")
                ?: doc.selectFirst(".content")
                ?: return@withContext ""

            // Remove ad blocks and Google Translate UI elements
            contentEl.select(".gadBlock").remove()
            contentEl.select(".adBlock").remove()
            contentEl.select("ins.clickforceads").remove()
            contentEl.select("ins.PopIn").remove()
            contentEl.select("script").remove()
            contentEl.select("style").remove()
            contentEl.select("#goog-gt-tt").remove()
            contentEl.select(".goog-te-banner-frame").remove()
            contentEl.select(".skiptranslate").remove()

            var text = TextExtractor.get(contentEl)

            // Remove junk reminder text
            for (pattern in junkPatterns) {
                text = pattern.replace(text, "")
            }

            text.trim()
        }

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            // Fetch from original URL for cover (translation not needed for images)
            val originalUrl = fromTranslateUrl(bookUrl)
            networkClient.get(originalUrl).toDocument()
                .selectFirst("meta[name=og:image]")
                ?.attr("content")
                ?: networkClient.get(originalUrl).toDocument()
                    .selectFirst(".cover img[src]")
                    ?.attr("src")
        }
    }

    override suspend fun getBookDescription(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            // Wait for translation processing
            delay(TRANSLATE_DELAY_MS)

            val translateUrl = toTranslateUrl(bookUrl)
            val doc = networkClient.get(translateUrl).toDocument()
            doc.selectFirst("meta[name=description]")
                ?.attr("content")
                ?.trim()
                ?: doc.selectFirst(".intro")
                    ?.let { TextExtractor.get(it).trim() }
        }
    }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            // Fetch chapter list from the original URL (directory page, translation not critical)
            val originalUrl = fromTranslateUrl(bookUrl)
            val dirUrl = if (originalUrl.endsWith("/dir") || originalUrl.endsWith("/dir/")) {
                originalUrl
            } else {
                originalUrl.trimEnd('/') + "/dir"
            }

            val doc = networkClient.get(dirUrl).toDocument()
            val allChapterLinks = doc.select(".chaplist ul.all li a[href]")
            if (allChapterLinks.isNotEmpty()) {
                allChapterLinks.map { link ->
                    ChapterResult(
                        title = link.text().trim(),
                        url = toTranslateUrl(resolveUrl(link.attr("href"), originalUrl))
                    )
                }
            } else {
                val infoDoc = networkClient.get(originalUrl).toDocument()
                infoDoc.select(".chaplist ul li a[href]").map { link ->
                    ChapterResult(
                        title = link.text().trim(),
                        url = toTranslateUrl(resolveUrl(link.attr("href"), originalUrl))
                    )
                }
            }
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            delay(TRANSLATE_DELAY_MS)

            val page = index + 1
            // Use translated catalog URL
            val url = "https://www-timotxt-com.translate.goog/bookstack/".toUrlBuilderSafe().apply {
                add("_x_tr_sl", "ur")
                add("_x_tr_tl", "en")
                add("_x_tr_hl", "en")
                add("_x_tr_pto", "wapp")
                if (page > 1) add("page", page.toString())
            }

            val doc = networkClient.get(url).toDocument()
            val books = doc.select(".flex li, .list li").mapNotNull { item ->
                val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                val cover = item.selectFirst("img[src]")?.attr("src") ?: ""
                val href = link.attr("href")
                BookResult(
                    title = link.attr("title").ifBlank { link.text() },
                    url = toTranslateUrl(resolveUrl(href, originalBaseUrl)),
                    coverImageUrl = resolveUrl(cover, originalBaseUrl)
                )
            }

            PagedList(
                list = books,
                index = index,
                isLastPage = doc.selectFirst("a.next") == null
            )
        }
    }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank())
                return@tryConnect PagedList.createEmpty(index = index)

            // URL-as-search: if the input looks like a URL, treat it as a direct book URL
            if (isUrlLike(input)) {
                val bookUrl = normalizeUrl(input)
                val translateBookUrl = toTranslateUrl(bookUrl)
                return@tryConnect tryConnect {
                    delay(TRANSLATE_DELAY_MS)
                    val doc = networkClient.get(translateBookUrl).toDocument()
                    val title = doc.selectFirst("h1.title")?.text()?.trim()
                        ?: doc.selectFirst("meta[name=og:novel:book_name]")?.attr("content")
                        ?: return@tryConnect PagedList.createEmpty(index)
                    val cover = doc.selectFirst("meta[name=og:image]")?.attr("content") ?: ""
                    PagedList(
                        list = listOf(
                            BookResult(
                                title = title,
                                url = translateBookUrl,
                                coverImageUrl = cover
                            )
                        ),
                        index = index,
                        isLastPage = true
                    )
                }
            }

            // Regular search — use original timotxt search, then translate result URLs
            delay(TRANSLATE_DELAY_MS)
            val searchUrl = "https://www-timotxt-com.translate.goog/search/".toUrlBuilderSafe()
                .addPath(input)
                .add("_x_tr_sl", "ur")
                .add("_x_tr_tl", "en")
                .add("_x_tr_hl", "en")
                .add("_x_tr_pto", "wapp")
                .toString()

            val doc = networkClient.get(searchUrl).toDocument()
            val books = doc.select(".flex li, .list li, .chaplist li").mapNotNull { item ->
                val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                val cover = item.selectFirst("img[src]")?.attr("src") ?: ""
                val href = link.attr("href")
                BookResult(
                    title = link.attr("title").ifBlank { link.text() },
                    url = toTranslateUrl(resolveUrl(href, originalBaseUrl)),
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

    private fun isUrlLike(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.startsWith("http://") ||
                trimmed.startsWith("https://") ||
                trimmed.startsWith("www.") ||
                trimmed.contains("timotxt.com") ||
                trimmed.contains("timotxt.cn") ||
                trimmed.contains("translate.goog")
    }

    private fun normalizeUrl(input: String): String {
        var url = input.trim()
        // If it's already a translate URL, convert back to original first
        if (url.contains("translate.goog")) {
            url = fromTranslateUrl(url)
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
}
