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
 * Replacement for LightNovelWorld (which has permanently shut down).
 *
 * Site: https://allnovel.org/ - Free Light Novel Online
 *
 * Catalog example:
 *   https://allnovel.org/latest-release-novel
 *   https://allnovel.org/latest-release-novel?page=2
 * Book page example:
 *   https://allnovel.org/martial-peak.html
 * Chapter page example:
 *   https://allnovel.org/martial-peak/chapter-6113-cant-you-help.html
 *
 * HTML structure (verified 2026-06):
 *   Catalog row:    `.list.list-truyen .row` containing `.col-xs-3 img.cover`,
 *                    `.col-xs-7 .truyen-title a[href][title]`,
 *                    `.col-xs-2 a[href][title]` (latest chapter).
 *   Book cover:     `.col-truyen-side .book-info img[src]` OR meta[property="og:image"].
 *   Book desc:      `.desc-text` OR `.info `.info` `.desc`.
 *   Chapter list:   `#list-chapter .list-chapter li a[href][title]`
 *   Chapter text:   `#chapter-content`
 *   Pagination:     `ul.pagination li.active` indicates current page; last `<li>`
 *                    being `.active` means we are on the last page.
 */
class AllNovel(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "all_novel"
    override val nameStrId = R.string.source_name_all_novel
    override val baseUrl = "https://allnovel.org/"
    override val catalogUrl = "https://allnovel.org/latest-release-novel"
    override val language = LanguageCode.ENGLISH

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst(".chapter-title")?.text()
                ?: doc.title().substringBefore(" - Chapter").ifBlank { null }
        }

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val contentEl = doc.selectFirst("#chapter-content")
                ?: doc.selectFirst("#chapter")
                ?: throw NoSuchElementException("#chapter-content not found")
            // Remove ad iframes / report buttons inside the content div
            contentEl.select("script").remove()
            contentEl.select("style").remove()
            contentEl.select("iframe").remove()
            contentEl.select("ins").remove()
            contentEl.select(".ads").remove()
            contentEl.select(".chapter-nav").remove()
            TextExtractor.get(contentEl)
        }

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: doc.selectFirst(".book-info img[src], .col-truyen-side img[src]")
                    ?.attr("src")
        }
    }

    override suspend fun getBookDescription(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            val desc = doc.selectFirst(".desc-text, .book-info .desc, .info .desc, .description")
                ?: return@tryConnect null
            desc.select("h2, h3").remove()
            TextExtractor.get(desc).trim()
        }
    }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            doc.select("#list-chapter .list-chapter li a[href], #list-chapter a[href][title]")
                .mapNotNull {
                    val href = it.attr("href").ifBlank { return@mapNotNull null }
                    val title = it.attr("title").ifBlank { it.text() }
                    ChapterResult(
                        title = title.trim(),
                        url = URI(baseUrl).resolve(href).toString()
                    )
                }
                .distinctBy { it.url }
                // Site lists oldest first; reverse so newest is last (consistent with other sources).
                .reversed()
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect("index=$index") {
            val page = index + 1
            val url = catalogUrl
                .toUrlBuilderSafe()
                .apply { if (page > 1) add("page", page.toString()) }

            val doc = networkClient.get(url).toDocument()
            parseBooks(doc, index)
        }
    }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank()) {
                return@tryConnect PagedList.createEmpty(index = index)
            }
            // allnovel.org doesn't have a search endpoint; use the site search.
            val page = index + 1
            val url = baseUrl
                .toUrlBuilderSafe()
                .addPath("search")
                .add("keyword", input)
                .apply { if (page > 1) add("page", page.toString()) }

            val doc = networkClient.get(url).toDocument()
            parseBooks(doc, index)
        }
    }

    private fun parseBooks(doc: Document, index: Int): PagedList<BookResult> {
        val books = doc.select(".list.list-truyen .row, #list-page .row")
            .mapNotNull { row ->
                val link = row.selectFirst(".truyen-title a[href], .col-title-history a[href]")
                    ?: return@mapNotNull null
                val cover = row.selectFirst("img.cover, img[src]")?.attr("src") ?: ""
                BookResult(
                    title = link.attr("title").ifBlank { link.text() },
                    url = URI(baseUrl).resolve(link.attr("href")).toString(),
                    coverImageUrl = if (cover.isBlank()) "" else
                        if (cover.startsWith("http")) cover else
                            URI(baseUrl).resolve(cover).toString()
                )
            }
            .distinctBy { it.url }

        val isLastPage = when (val nav = doc.selectFirst("ul.pagination")) {
            null -> true
            // If the last visible page link has class "active", we are on the last page.
            else -> nav.children().last()?.`is`(".active") ?: true
        }

        return PagedList(
            list = books,
            index = index,
            isLastPage = isLastPage || books.isEmpty()
        )
    }
}
