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

class MTLNovel(
    private val networkClient: NetworkClient
) : SourceInterface.Catalog {
    override val id = "mtlnovel"
    override val nameStrId = R.string.source_name_mtlnovel
    override val baseUrl = "https://mtlnovel.me/"
    override val catalogUrl = "https://mtlnovel.me/list/"
    override val language = LanguageCode.ENGLISH

    override suspend fun getChapterTitle(doc: Document): String? = null

    override suspend fun getChapterText(doc: Document): String = withContext(Dispatchers.Default) {
        doc.selectFirst("div.content")!!.let { TextExtractor.get(it) }
    }

    override suspend fun getBookCoverImageUrl(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            networkClient.get(bookUrl).toDocument()
                .selectFirst("img[itemprop=image]")
                ?.attr("src")
                ?.let { if (it.startsWith("/")) "$baseUrl${it.removePrefix("/")}" else it }
        }
    }

    override suspend fun getBookDescription(
        bookUrl: String
    ): Response<String?> = withContext(Dispatchers.Default) {
        tryConnect {
            val doc = networkClient.get(bookUrl).toDocument()
            // Extract description from meta tag as primary source
            doc.selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.trim()
                ?.ifEmpty { null }
                ?: doc.selectFirst("meta[name=description]")
                    ?.attr("content")
                    ?.trim()
        }
    }

    override suspend fun getChapterList(
        bookUrl: String
    ): Response<List<ChapterResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            // Extract the slug from the book URL
            val slug = bookUrl.trimEnd('/').substringAfterLast("/")
            val ajaxUrl = "${baseUrl}ajax/chapters/?slug=$slug"

            networkClient.get(ajaxUrl)
                .toDocument()
                .select("p.update-box-chapter a[href]")
                .map {
                    ChapterResult(
                        title = it.attr("title").ifBlank { it.text() },
                        url = it.attr("href").let { href ->
                            if (href.startsWith("/")) "$baseUrl${href.removePrefix("/")}" else href
                        },
                    )
                }
                .reversed()
        }
    }

    override suspend fun getCatalogList(
        index: Int
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            val page = index + 1
            val url = catalogUrl.toUrlBuilderSafe().apply {
                if (page > 1) addPath("page", "$page")
            }.toString()

            val doc = networkClient.get(url).toDocument()
            doc.select("div.novel-box")
                .mapNotNull {
                    val link = it.selectFirst("a[href]") ?: return@mapNotNull null
                    val img = it.selectFirst("img[src]")
                    BookResult(
                        title = link.attr("title").ifBlank { img?.attr("alt") ?: link.text() },
                        url = link.attr("href").let { href ->
                            if (href.startsWith("/")) "$baseUrl${href.removePrefix("/")}" else href
                        },
                        coverImageUrl = img?.attr("src")?.let { src ->
                            if (src.startsWith("/")) "$baseUrl${src.removePrefix("/")}" else src
                        } ?: ""
                    )
                }
                .let {
                    PagedList(
                        list = it,
                        index = index,
                        isLastPage = doc.selectFirst("ul.pagination li.next") == null ||
                                doc.selectFirst("a[href*=page=${page + 1}]") == null
                    )
                }
        }
    }

    override suspend fun getCatalogSearch(
        index: Int,
        input: String
    ): Response<PagedList<BookResult>> = withContext(Dispatchers.Default) {
        tryConnect {
            if (input.isBlank() || index > 0)
                return@tryConnect PagedList.createEmpty(index = index)

            val searchInput = input.lowercase().replace("\\s+".toRegex(), "-")
            val url = "${baseUrl}search/$searchInput/".toUrlBuilderSafe().toString()

            val doc = networkClient.get(url).toDocument()
            doc.select("div.novel-box")
                .mapNotNull {
                    val link = it.selectFirst("a[href]") ?: return@mapNotNull null
                    val img = it.selectFirst("img[src]")
                    BookResult(
                        title = link.attr("title").ifBlank { img?.attr("alt") ?: link.text() },
                        url = link.attr("href").let { href ->
                            if (href.startsWith("/")) "$baseUrl${href.removePrefix("/")}" else href
                        },
                        coverImageUrl = img?.attr("src")?.let { src ->
                            if (src.startsWith("/")) "$baseUrl${src.removePrefix("/")}" else src
                        } ?: ""
                    )
                }
                .let {
                    PagedList(
                        list = it,
                        index = index,
                        isLastPage = true
                    )
                }
        }
    }
}
