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

/**
 * Twkan / Ttkan — Chinese novels (繁體中文).
 *
 * Content structure: div#txtcontent0 with BR-split text nodes.
 * Cloudflare protected. Uses traditional Chinese.
 *
 * Adapted from the novelscraper project (Paras).
 */
class Twkan(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "twkan"
    override val nameStrId = R.string.source_name_twkan
    override val baseUrl = "https://www.twkan.com/"
    override val catalogUrl = "https://www.twkan.com/"
    override val language = LanguageCode.CHINESE

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1, .chapter-title")?.text()?.trim()
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val contentEl = doc.selectFirst("#txtcontent0")
                ?: doc.selectFirst(".content")
                ?: doc.selectFirst("#content")
                ?: return@withContext ""

            // Remove ad elements
            contentEl.select("script").remove()
            contentEl.select("style").remove()
            contentEl.select("ins").remove()

            TextExtractor.get(contentEl).trim()
        }

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument()
                .selectFirst(".book-img img[src], .cover img[src]")
                ?.attr("src")
                ?: networkClient.get(bookUrl).toDocument()
                    .selectFirst("meta[property=og:image]")
                    ?.attr("content")
        }
    }

    override suspend fun getBookDescription(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            doc.selectFirst(".intro, .description")
                ?.let { TextExtractor.get(it).trim() }
                ?: doc.selectFirst("meta[property=og:description]")
                    ?.attr("content")
                    ?.trim()
        }
    }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            doc.select(".chapter-list li a[href], #chapterlist li a[href]")
                .map { link ->
                    ChapterResult(
                        title = link.text().trim(),
                        url = resolveUrl(link.attr("href"), baseUrl)
                    )
                }
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val page = index + 1
            val url = catalogUrl.toUrlBuilderSafe().apply {
                if (page > 1) addPath("page", "$page")
            }

            val doc = networkClient.get(url).toDocument()
            val books = doc.select(".book-item, .novel-item").mapNotNull { item ->
                val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                val cover = item.selectFirst("img[src]")?.attr("src") ?: ""
                BookResult(
                    title = link.attr("title").ifBlank { link.text() },
                    url = resolveUrl(link.attr("href"), baseUrl),
                    coverImageUrl = resolveUrl(cover, baseUrl)
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
            if (input.isBlank() || index > 0)
                return@tryConnect PagedList.createEmpty(index = index)

            val url = baseUrl.toUrlBuilderSafe()
                .addPath("search")
                .add("q", input)
                .toString()

            val doc = networkClient.get(url).toDocument()
            val books = doc.select(".book-item, .novel-item").mapNotNull { item ->
                val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                val cover = item.selectFirst("img[src]")?.attr("src") ?: ""
                BookResult(
                    title = link.attr("title").ifBlank { link.text() },
                    url = resolveUrl(link.attr("href"), baseUrl),
                    coverImageUrl = resolveUrl(cover, baseUrl)
                )
            }

            PagedList(
                list = books,
                index = index,
                isLastPage = true
            )
        }
    }

    private fun resolveUrl(href: String, base: String): String {
        if (href.startsWith("http")) return href
        if (href.startsWith("/")) return base.trimEnd('/') + href
        return base.trimEnd('/') + "/" + href.removePrefix("/")
    }
}
