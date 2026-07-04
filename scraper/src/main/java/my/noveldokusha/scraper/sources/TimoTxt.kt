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
 * Novel info page example:
 * https://www.timotxt.com/1234/
 * Chapter directory example:
 * https://www.timotxt.com/1234/dir
 * Chapter page example:
 * https://www.timotxt.com/1234/1.html
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
        /** Junk text patterns to remove from chapter content */
        val JUNK_PATTERNS = listOf(
            "溫馨提示",
            "手機小說閱讀網",
            "手機用戶",
            "最新更新時間",
            "提莫書屋",
            "timotxt.com"
        )
    }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst(".chapter-content h1, .chapter-content .title, h1.chapter-title")
                ?.text()
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val contentEl = doc.selectFirst("div.chapter-content div.content")
                ?: doc.selectFirst("div.chapter-content")
                ?: doc.selectFirst("div.content")

            contentEl!!.also {
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
            val doc = networkClient.get(bookUrl).toDocument()
            doc.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?: doc.selectFirst(".cover img[src]")
                    ?.attr("src")
        }
    }

    override suspend fun getBookDescription(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
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
            // The chapter directory is at /{bookId}/dir
            val dirUrl = bookUrl.toUrlBuilderSafe()
                .addPath("dir")
                .toString()

            val doc = networkClient.get(dirUrl).toDocument()

            // Try .chaplist ul.all li a[href] first, fallback to .chaplist ul li a[href]
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
            // CRITICAL: catalogUrl already ends with /bookstack/ (trailing
            // slash). Without the trailing slash the site returns a 404 and
            // the catalog appears empty.
            val url = catalogUrl.toUrlBuilderSafe()
                .add("page", page.toString())
                .toString()

            val doc = networkClient.get(url).toDocument()
            // Verified selector: ul.list.flex > li
            // Each li has: <a href="/{id}/"><img.../></a> and <h3><a href>title</a></h3>
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

            // Pagination: the site serves <li class="next pagination-link">
            // on every page except the last (where it's either absent or
            // has the `disabled` class). The site loops around past the
            // last page, so we MUST rely on the `next` link presence —
            // not on "highest page number seen" — to detect the end.
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
            if (input.startsWith("http") && input.contains("timotxt.com")) {
                val doc = networkClient.get(input).toDocument()
                val title = doc.selectFirst("h1, .book-name, .book-title")
                    ?.text()?.trim() ?: return@tryConnect PagedList.createEmpty(index)
                val cover = doc.selectFirst("meta[property=og:image]")
                    ?.attr("content")
                    ?: doc.selectFirst(".cover img[src]")?.attr("src")
                    ?: ""
                return@tryConnect PagedList(
                    list = listOf(
                        BookResult(
                            title = title,
                            url = input,
                            coverImageUrl = cover
                        )
                    ),
                    index = index,
                    isLastPage = true
                )
            }

            // Search endpoint: /search/{keyword}
            // (Cloudflare-protected — the interceptor handles the challenge
            // automatically on-device.)
            val url = baseUrl.toUrlBuilderSafe()
                .addPath("search")
                .addPath(input)
                .toString()

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
    // Clean up multiple blank lines left behind
    return result.replace(Regex("\n{3,}"), "\n\n").trim()
}
