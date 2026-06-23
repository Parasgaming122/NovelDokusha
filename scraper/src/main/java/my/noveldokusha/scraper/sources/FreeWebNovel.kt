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
 * FreeWebNovel — English novels.
 *
 * Content structure: div.txt → div#article with <p> tags.
 * Adapted from the novelscraper project (Paras).
 */
class FreeWebNovel(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "freewebnovel"
    override val nameStrId = R.string.source_name_freewebnovel
    override val baseUrl = "https://freewebnovel.com/"
    override val catalogUrl = "https://freewebnovel.com/latest/"
    override val language = LanguageCode.ENGLISH

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.title")?.text()?.trim()
                ?: doc.selectFirst(".txt h1")?.text()?.trim()
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val contentEl = doc.selectFirst("#article")
                ?: doc.selectFirst(".txt #article")
                ?: doc.selectFirst(".chapter-content")
                ?: doc.selectFirst("#htmlContent")
                ?: return@withContext ""

            // Remove ads and navigation
            contentEl.select(".read-ads").remove()
            contentEl.select(".chapter-end").remove()
            contentEl.select(".chapter-start").remove()
            contentEl.select("script").remove()
            contentEl.select("style").remove()

            TextExtractor.get(contentEl).trim()
        }

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument()
                .selectFirst(".book-img img[src]")
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
            doc.selectFirst(".inner &gt; .desc")
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
            doc.select(".m-newest2 li a[href], .chapter-list li a[href]")
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
                if (page > 1) addPath("$page")
                add("page", page.toString())
            }

            val doc = networkClient.get(url).toDocument()
            val books = doc.select(".li-row .g-img, .book-list li").mapNotNull { item ->
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
            val books = doc.select(".li-row .g-img, .book-list li").mapNotNull { item ->
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
