package my.noveldokusha.scraper.sources

import kotlinx.coroutines.Dispatchers
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
 * WebNovel — English/Translated novels (webnovel.com).
 *
 * Content structure (m.webnovel.com):
 *   div.cha-content → div.cha-words → div.cha-paragraph → p text
 * Uses Next.js SSR. Chapter content available in __NEXT_DATA__.
 *
 * Adapted from the novelscraper project (Paras).
 */
class WebNovel(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "webnovel"
    override val nameStrId = R.string.source_name_webnovel
    override val baseUrl = "https://www.webnovel.com/"
    override val catalogUrl = "https://www.webnovel.com/stories"
    override val language = LanguageCode.ENGLISH

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst(".cha-tit h1, .chapter-title")?.text()?.trim()
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val contentEl = doc.selectFirst(".cha-content")
                ?: doc.selectFirst(".chapter-content")
                ?: doc.selectFirst(".cha-words")
                ?: doc.selectFirst(".chapter-inner")
                ?: return@withContext ""

            // Remove ads and navigation
            contentEl.select(".cha-page-ft").remove()
            contentEl.select(".cha-fly").remove()
            contentEl.select(".cha-tit").remove()
            contentEl.select("script").remove()
            contentEl.select("style").remove()
            contentEl.select(".j_recommendation").remove()
            contentEl.select(".comment-area").remove()
            contentEl.select("[id^=video-]").remove()

            TextExtractor.get(contentEl).trim()
        }

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument()
                .selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?: networkClient.get(bookUrl).toDocument()
                    .selectFirst(".g_thumb img[src]")
                    ?.attr("src")
        }
    }

    override suspend fun getBookDescription(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            doc.selectFirst(".g_txt_over, .det-abt")
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
            doc.select(".cha-list li a[href], .content-list li a[href]")
                .map { link ->
                    ChapterResult(
                        title = link.text().trim(),
                        url = URI(baseUrl).resolve(link.attr("href")).toString()
                    )
                }
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val page = index + 1
            val url = "https://www.webnovel.com/stories".toUrlBuilderSafe().apply {
                add("page", page.toString())
            }

            val request = getRequest(url.toString())
                .addHeader("accept", "*/*")
                .addHeader("accept-encoding", "gzip, deflate, br")
                .addHeader("accept-language", "en-US,en;q=0.9")
                .addHeader("referer", "https://www.webnovel.com")

            val doc = networkClient.call(request).toDocument()
            val books = doc.select(".book-item, .lst-item").mapNotNull { item ->
                val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                val cover = item.selectFirst("img[src]")?.attr("src") ?: ""
                BookResult(
                    title = link.attr("title").ifBlank { link.text() },
                    url = URI(baseUrl).resolve(link.attr("href")).toString(),
                    coverImageUrl = if (cover.startsWith("http")) cover else "https:$cover"
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

            val url = "https://www.webnovel.com/search".toUrlBuilderSafe()
                .add("keyword", input)
                .toString()

            val request = getRequest(url.toString())
                .header("accept", "*/*")
                .header("accept-encoding", "gzip, deflate, br")
                .header("accept-language", "en-US,en;q=0.9")
                .header("referer", "https://www.webnovel.com")

            val doc = networkClient.call(request).toDocument()
            val books = doc.select(".book-item, .lst-item").mapNotNull { item ->
                val link = item.selectFirst("a[href]") ?: return@mapNotNull null
                val cover = item.selectFirst("img[src]")?.attr("src") ?: ""
                BookResult(
                    title = link.attr("title").ifBlank { link.text() },
                    url = URI(baseUrl).resolve(link.attr("href")).toString(),
                    coverImageUrl = if (cover.startsWith("http")) cover else "https:$cover"
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
