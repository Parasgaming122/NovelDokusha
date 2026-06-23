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
 * NovelHall (Paras) — Alternative NovelHall scraper from the novelscraper project.
 *
 * Content structure: div#htmlContent / div.entry-content.
 * Uses BR separators (NO <p> tags). BR preparation may be needed.
 * This is an alternative to the built-in NovelHall source.
 * Both are interchangeable — if one fails to parse, the other may succeed.
 *
 * Adapted from the novelscraper project (Paras).
 */
class NovelHallParas(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "novelhall_paras"
    override val nameStrId = R.string.source_name_novelhall_paras
    override val baseUrl = "https://www.novelhall.com/"
    override val catalogUrl = "https://www.novelhall.com/all.html"
    override val language = LanguageCode.ENGLISH

    override suspend fun getChapterTitle(doc: Document): String? = null

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val contentEl = doc.selectFirst("#htmlContent")
                ?: doc.selectFirst(".entry-content")
                ?: return@withContext ""

            // Remove navigation and ads
            contentEl.select(".chapter-nav-btn").remove()
            contentEl.select(".post-navigation").remove()
            contentEl.select(".sidebar").remove()
            contentEl.select("#sidebar").remove()
            contentEl.select(".breadcrumb").remove()
            contentEl.select("script").remove()
            contentEl.select("style").remove()

            var text = TextExtractor.get(contentEl)

            // Remove junk keywords
            text = text.replace(Regex("""(?i)novelhall\.com"""), "")
            text = text.replace(Regex("""(?i)read novel free"""), "")
            text = text.replace(Regex("""(?i)novelxo"""), "")

            text.trim()
        }

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument()
                .selectFirst(".book-img.hidden-xs img[src]")
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
            networkClient.get(bookUrl).toDocument()
                .selectFirst("span.js-close-wrap")
                ?.let { TextExtractor.get(it).trim() }
                ?: networkClient.get(bookUrl).toDocument()
                    .selectFirst("meta[property=og:description]")
                    ?.attr("content")
                    ?.trim()
        }
    }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl)
                .toDocument()
                .select("#morelist a[href]")
                .map {
                    ChapterResult(
                        title = it.text(),
                        url = baseUrl + it.attr("href")
                    )
                }
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val page = index + 1
            val url = baseUrl.toUrlBuilderSafe().apply {
                if (page == 1) addPath("all.html")
                else addPath("all-$page.html")
            }
            val doc = networkClient.get(url).toDocument()
            doc.select("li.btm")
                .mapNotNull {
                    val link = it.selectFirst("a[href]") ?: return@mapNotNull null
                    BookResult(
                        title = link.text(),
                        url = baseUrl + link.attr("href").removePrefix("/"),
                    )
                }
                .let {
                    PagedList(
                        list = it,
                        index = index,
                        isLastPage = isLastPage(doc)
                    )
                }
        }
    }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank())
                return@tryConnect PagedList.createEmpty(index = index)

            val url = baseUrl.toUrlBuilderSafe().apply {
                addPath("index.php")
                add("s", "so")
                add("module", "book")
                add("keyword", input)
            }
            val doc = networkClient.get(url).toDocument()
            doc.selectFirst(".section3.inner.mt30 > table")
                ?.select("tr > td:nth-child(2) > a[href]")
                .let { it ?: listOf() }
                .map { link ->
                    BookResult(
                        title = link.text(),
                        url = baseUrl + link.attr("href").removePrefix("/"),
                    )
                }
                .let {
                    PagedList(
                        list = it,
                        index = index,
                        isLastPage = isLastPage(doc)
                    )
                }
        }
    }

    private fun isLastPage(doc: Document) = when (val nav = doc.selectFirst("div.page-nav")) {
        null -> true
        else -> nav.children().last()?.`is`("span") ?: true
    }
}
