package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
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
import java.net.URI

/**
 * Direct Chinese source for timotxt.com.
 *
 * **URL routing**: Uses `https://www.timotxt.com/` as the baseUrl. All
 * stored URLs use the `timotxt.com` domain so `getCompatibleSource()`
 * matches this source (not the translate/gemini variants).
 *
 * **Fetching**: All HTTP fetches go through the `translate.goog` proxy
 * (with `_x_tr_*` params) for Cloudflare bypass. The proxy returns the
 * original Chinese HTML to OkHttp; this source does NOT translate —
 * the reader shows raw Chinese text.
 *
 * **WebView**: `transformChapterUrl()` converts `timotxt.com` URLs to
 * `translate.goog` URLs with params. The reader calls this before
 * opening webview, so the user sees the Google-Translate version in
 * the browser (a bonus for users who want quick translation without
 * switching sources).
 *
 * **Chapter content**: Raw Chinese text with junk patterns stripped.
 */
class TimoTxt(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "timotxt"
    override val nameStrId = R.string.source_name_timotxt
    override val baseUrl = "https://www.timotxt.com/"
    override val catalogUrl = "https://www.timotxt.com/bookstack/"
    override val language = LanguageCode.CHINESE

    companion object {
        private const val TRANSLATE_PARAMS =
            "_x_tr_sl=zh-CN&_x_tr_tl=en&_x_tr_hl=en&_x_tr_pto=wapp"

        val JUNK_PATTERNS = listOf(
            "溫馨提示",
            "手機小說閱讀網",
            "手機用戶",
            "最新更新時間",
            "提莫書屋",
            "timotxt.com"
        )
    }

    /**
     * Convert a timotxt.com URL to the translate.goog proxy URL with
     * params. Used for all HTTP fetching (CF bypass) and for webview.
     */
    private fun toTranslateUrl(url: String): String {
        if (url.isBlank()) return url

        var cleanUrl = url
            .replace(Regex("[?&]_x_tr_(sl|tl|hl|pto|pto_ctx)=[^&]*"), "")
            .replace("?&", "?")
            .replace(Regex("[?&]$"), "")
            .trimEnd('&', '?')

        cleanUrl = cleanUrl.replace(
            "https://www.timotxt.com",
            "https://www-timotxt-com.translate.goog"
        )

        val separator = if (cleanUrl.contains("?")) "&" else "?"
        return "$cleanUrl$separator$TRANSLATE_PARAMS"
    }

    /**
     * Transform chapter URL before fetching OR opening in webview.
     * Converts timotxt.com → translate.goog with params for CF bypass.
     */
    override suspend fun transformChapterUrl(url: String): String =
        toTranslateUrl(url)

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst(".chapter-content h1, .chapter-content .title, h1.chapter-title, h1.imgtext, h1")
                ?.text()
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val contentEl = doc.selectFirst("div.chapter-content div.content")
                ?: doc.selectFirst("div.chapter-content")
                ?: doc.selectFirst("div.content")
                ?: return@withContext ""

            contentEl.also {
                it.select(".gadBlock").remove()
                it.select("script").remove()
                it.select("style").remove()
                it.select("iframe").remove()
            }

            TextExtractor.get(contentEl).cleanJunk()
        }

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
            val metaDesc = doc.selectFirst("meta[name=description]")
                ?.attr("content")
                ?.trim()
            if (!metaDesc.isNullOrBlank()) {
                metaDesc
            } else {
                doc.selectFirst(".intro")
                    ?.let { TextExtractor.get(it).trim() }
            }
        }
    }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val dirUrl = toTranslateUrl(
                bookUrl.toUrlBuilderSafe().addPath("dir").toString()
            )

            val doc = networkClient.get(dirUrl).toDocument()

            // The /dir page has TWO <ul> lists inside .chaplist:
            //   1. ul.flex (12 links, newest first — sidebar)
            //   2. ul.flex.all (all links, oldest first — complete list)
            val chapterLinks = doc.select(".chaplist ul.all li a[href]")
                .takeIf { it.isNotEmpty() }
                ?: doc.select(".chaplist ul li a[href]")

            chapterLinks.map {
                ChapterResult(
                    title = it.text().trim(),
                    url = URI(baseUrl).resolve(it.attr("href")).toString()
                )
            }
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val page = index + 1
            val url = toTranslateUrl(
                catalogUrl.toUrlBuilderSafe()
                    .add("page", page.toString())
                    .toString()
            )

            val doc = networkClient.get(url).toDocument()
            val books = doc.select("ul.list.flex > li")
                .mapNotNull {
                    val link = it.selectFirst("h3 a[href], a[href]") ?: return@mapNotNull null
                    val cover = it.selectFirst("img[src]")?.attr("src") ?: ""
                    val title = link.text().trim()
                    if (title.isBlank()) return@mapNotNull null
                    BookResult(
                        title = title,
                        url = URI(baseUrl).resolve(link.attr("href")).toString(),
                        coverImageUrl = cover
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

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank() || index > 0)
                return@tryConnect PagedList.createEmpty(index = index)

            // If the input looks like a URL, treat it as a direct book URL
            if (input.startsWith("http") && (input.contains("timotxt.com") || input.contains("translate.goog"))) {
                val cleanUrl = input.replace(Regex("[?&]_x_tr_(sl|tl|hl|pto|pto_ctx)=[^&]*"), "")
                    .replace("?&", "?")
                    .replace(Regex("[?&]$"), "")
                    .trimEnd('&', '?')
                    .replace("https://www-timotxt-com.translate.goog", "https://www.timotxt.com")
                val doc = networkClient.get(toTranslateUrl(cleanUrl)).toDocument()
                val title = doc.selectFirst("h1, .book-name, .book-title")
                    ?.text()?.trim() ?: return@tryConnect PagedList.createEmpty(index)
                val cover = doc.selectFirst(".cover img[src]")
                    ?.attr("src")
                    ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                    ?: ""
                return@tryConnect PagedList(
                    list = listOf(
                        BookResult(
                            title = title,
                            url = cleanUrl,
                            coverImageUrl = cover
                        )
                    ),
                    index = index,
                    isLastPage = true
                )
            }

            val url = toTranslateUrl(
                baseUrl.toUrlBuilderSafe()
                    .addPath("search")
                    .addPath(input)
                    .toString()
            )

            val doc = networkClient.get(url).toDocument()
            val books = doc.select("ul.list.flex > li")
                .mapNotNull {
                    val link = it.selectFirst("h3 a[href], a[href]") ?: return@mapNotNull null
                    val cover = it.selectFirst("img[src]")?.attr("src") ?: ""
                    val title = link.text().trim()
                    if (title.isBlank()) return@mapNotNull null
                    BookResult(
                        title = title,
                        url = URI(baseUrl).resolve(link.attr("href")).toString(),
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
}

/** Remove junk text patterns from extracted content */
fun String.cleanJunk(): String {
    var result = this
    for (pattern in TimoTxt.JUNK_PATTERNS) {
        result = result.replace(pattern, "")
    }
    return result.replace(Regex("\n{3,}"), "\n\n").trim()
}
