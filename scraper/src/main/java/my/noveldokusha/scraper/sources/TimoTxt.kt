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
 * TimoTxt — Chinese novels (繁體中文).
 *
 * Info page: https://www.timotxt.com/{bookId}/
 * Chapter directory: https://www.timotxt.com/{bookId}/dir
 * Chapter page: https://www.timotxt.com/{bookId}/{chapterNum}.html
 *
 * Content structure: div.chapter-content → div.content with <p> tags.
 * Contains .gadBlock ad elements that must be removed.
 * Contains junk reminder text (溫馨提示 / Friendly reminder) that must be filtered.
 *
 * Search supports direct URL input: if the search query looks like a URL,
 * it will be treated as a direct book URL instead of a text search.
 */
class TimoTxt(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "timotxt"
    override val nameStrId = R.string.source_name_timotxt
    override val baseUrl = "https://www.timotxt.com/"
    override val catalogUrl = "https://www.timotxt.com/bookstack/"
    override val language = LanguageCode.CHINESE

    /** Junk text patterns to remove from chapter content */
    private val junkPatterns = listOf(
        Regex("""(?s)溫馨提示.*?諒解!?\s*"""),
        Regex("""(?s)Friendly reminder.*?inconvenience!?\s*"""),
        Regex("""手機小說閱讀網"""),
        Regex("""手機用戶.*?最新更新時間"""),
        Regex("""timotxt\.com"""),
        Regex("""提莫書屋"""),
    )

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1.imgtext[data-type=c]")?.text()?.trim()
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val contentEl = doc.selectFirst(".chapter-content .content")
                ?: doc.selectFirst(".chapter-content")
                ?: doc.selectFirst(".content")
                ?: return@withContext ""

            // Remove ad blocks
            contentEl.select(".gadBlock").remove()
            contentEl.select(".adBlock").remove()
            contentEl.select("ins.clickforceads").remove()
            contentEl.select("ins.PopIn").remove()
            contentEl.select("script").remove()
            contentEl.select("style").remove()

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
            // Fetch the full chapter directory page
            val dirUrl = if (bookUrl.endsWith("/dir") || bookUrl.endsWith("/dir/")) {
                bookUrl
            } else {
                bookUrl.trimEnd('/') + "/dir"
            }

            val doc = networkClient.get(dirUrl).toDocument()
            // The full chapter list is in .chaplist ul.all li a
            val allChapterLinks = doc.select(".chaplist ul.all li a[href]")
            if (allChapterLinks.isNotEmpty()) {
                allChapterLinks.map { link ->
                    ChapterResult(
                        title = link.text().trim(),
                        url = resolveUrl(link.attr("href"), bookUrl)
                    )
                }
            } else {
                // Fallback: try the latest chapters on the info page
                val infoDoc = networkClient.get(bookUrl).toDocument()
                infoDoc.select(".chaplist ul li a[href]").map { link ->
                    ChapterResult(
                        title = link.text().trim(),
                        url = resolveUrl(link.attr("href"), bookUrl)
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
                isLastPage = doc.selectFirst("a.next") == null &&
                        doc.selectFirst(".pagination .next") == null
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
                return@tryConnect tryConnect {
                    val doc = networkClient.get(bookUrl).toDocument()
                    val title = doc.selectFirst("h1.title")?.text()?.trim() ?: return@tryConnect PagedList.createEmpty(index)
                    val cover = doc.selectFirst("meta[name=og:image]")?.attr("content") ?: ""
                    PagedList(
                        list = listOf(
                            BookResult(
                                title = title,
                                url = bookUrl,
                                coverImageUrl = cover
                            )
                        ),
                        index = index,
                        isLastPage = true
                    )
                }
            }

            // Regular search via timotxt search endpoint
            val searchUrl = baseUrl.toUrlBuilderSafe()
                .addPath("search", input)
                .toString()

            val doc = networkClient.get(searchUrl).toDocument()
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

    private fun isUrlLike(input: String): Boolean {
        val trimmed = input.trim()
        return trimmed.startsWith("http://") ||
                trimmed.startsWith("https://") ||
                trimmed.startsWith("www.") ||
                (trimmed.contains("timotxt.com") || trimmed.contains("timotxt.cn"))
    }

    private fun normalizeUrl(input: String): String {
        var url = input.trim()
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
        if (href.startsWith("/")) return baseUrl.trimEnd('/') + href
        return base.trimEnd('/') + "/" + href.removePrefix("/")
    }
}
