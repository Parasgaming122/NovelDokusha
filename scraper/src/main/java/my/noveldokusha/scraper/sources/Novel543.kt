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
 * Novel543 — Taiwanese novels (繁體中文).
 *
 * Content structure: div.chapter-content px-3 → div.content py-5 with <p> tags.
 * Uses traditional Chinese. Heavy ads (OneAd, PopIn).
 *
 * Adapted from the novelscraper project (Paras).
 */
class Novel543(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "novel543"
    override val nameStrId = R.string.source_name_novel543
    override val baseUrl = "https://novel543.com/"
    override val catalogUrl = "https://novel543.com/"
    override val language = LanguageCode.CHINESE

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
                ?: doc.selectFirst(".article-content")
                ?: return@withContext ""

            // Remove ad blocks
            contentEl.select(".gadBlock").remove()
            contentEl.select(".adBlock").remove()
            contentEl.select("ins.clickforceads").remove()
            contentEl.select("ins.PopIn").remove()
            contentEl.select("script").remove()
            contentEl.select("style").remove()

            var text = TextExtractor.get(contentEl)

            // Remove junk text patterns
            text = text.replace(Regex("""手機小說閱讀網"""), "")
            text = text.replace(Regex("""手機用戶.*?最新更新時間"""), "")
            text = text.replace(Regex("""novel543\.com"""), "")

            text.trim()
        }

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument()
                .selectFirst("meta[name=og:image]")
                ?.attr("content")
                ?: networkClient.get(bookUrl).toDocument()
                    .selectFirst(".cover img[src]")
                    ?.attr("src")
        }
    }

    override suspend fun getBookDescription(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            doc.selectFirst(".intro")
                ?.let { TextExtractor.get(it).trim() }
                ?: doc.selectFirst("meta[name=description]")
                    ?.attr("content")
                    ?.trim()
        }
    }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val dirUrl = if (bookUrl.endsWith("/dir") || bookUrl.endsWith("/dir/")) {
                bookUrl
            } else {
                bookUrl.trimEnd('/') + "/dir"
            }

            val doc = networkClient.get(dirUrl).toDocument()
            val allChapterLinks = doc.select(".chaplist ul.all li a[href]")
            if (allChapterLinks.isNotEmpty()) {
                allChapterLinks.map { link ->
                    ChapterResult(
                        title = link.text().trim(),
                        url = resolveUrl(link.attr("href"), baseUrl)
                    )
                }
            } else {
                val infoDoc = networkClient.get(bookUrl).toDocument()
                infoDoc.select(".chaplist ul li a[href]").map { link ->
                    ChapterResult(
                        title = link.text().trim(),
                        url = resolveUrl(link.attr("href"), baseUrl)
                    )
                }
            }
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val page = index + 1
            val url = catalogUrl.toUrlBuilderSafe().apply {
                addPath("bookstack")
                if (page > 1) add("page", page.toString())
            }

            val doc = networkClient.get(url).toDocument()
            val books = doc.select(".flex li, .list li").mapNotNull { item ->
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
                .addPath("search", input)
                .toString()

            val doc = networkClient.get(url).toDocument()
            val books = doc.select(".flex li, .list li, .chaplist li").mapNotNull { item ->
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
