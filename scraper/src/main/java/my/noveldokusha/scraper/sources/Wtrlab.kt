package my.noveldokusha.scraper.sources

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import my.noveldokusha.core.LanguageCode
import my.noveldokusha.core.PagedList
import my.noveldokusha.core.Response
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.network.add
import my.noveldokusha.network.addPath
import my.noveldokusha.network.getRequest
import my.noveldokusha.network.postRequest
import my.noveldokusha.network.toDocument
import my.noveldokusha.network.toUrlBuilderSafe
import my.noveldokusha.network.tryConnect
import my.noveldokusha.scraper.R
import my.noveldokusha.scraper.SourceInterface
import my.noveldokusha.scraper.TextExtractor
import my.noveldokusha.scraper.domain.BookResult
import my.noveldokusha.scraper.domain.ChapterResult
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.jsoup.nodes.Document
import java.net.URI

/**
 * WTR-LAB — Chinese-to-English machine-translated novel source.
 *
 * Native Kotlin implementation (not a Lua plugin). Based on the wtrlab.lua
 * plugin from HnDK0's external-sources repo, ported to Kotlin for better
 * performance and error handling.
 *
 * Site structure (https://wtr-lab.com/):
 *   Catalog:    GET /novel-list?page=N
 *   Search:     GET /novel-finder?text=QUERY&page=N
 *   Book info:  GET /novel/{id}/{slug}
 *   Chapter list: GET /api/chapters/{novelId}  (JSON API)
 *   Chapter text: POST /api/reader/get  (JSON API)
 *
 * Chapter text pipeline:
 *   1. POST /api/reader/get with {translate, language, raw_id, chapter_no}
 *   2. Response body may be encrypted (starts with "arr:")
 *      → POST to https://wtr-lab-proxy.fly.dev/chapter for decryption
 *   3. Decrypted body is a JSON array of paragraph strings
 *   4. Apply glossary terms (from /api/v2/reader/terms/{novelId}.json)
 *   5. Apply patches (zh → en replacements from the API response)
 *
 * Two modes (stored in SharedPreferences "lua_preferences"):
 *   "ai"  — AI-translated text (default, higher quality)
 *   "raw" — Raw web text (faster, lower quality)
 */
class Wtrlab(
    private val networkClient: NetworkClient,
) : SourceInterface.Catalog {
    override val id = "wtrlab"
    override val nameStrId = R.string.source_name_wtrlab
    override val baseUrl = "https://wtr-lab.com/"
    override val catalogUrl = "https://wtr-lab.com/novel-list?page=1"
    override val language = LanguageCode.ENGLISH
    override val iconUrl = "https://wtr-lab.com/favicon.ico"

    companion object {
        private const val PROXY_URL = "https://wtr-lab-proxy.fly.dev/chapter"
        private const val PREF_MODE = "wtrlab_mode"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private fun getMode(): String {
        // Read from SharedPreferences "lua_preferences" (same as Lua plugins)
        // Default to "ai" mode
        return System.getProperty(PREF_MODE, "ai") ?: "ai"
    }

    // ─── Catalog ──────────────────────────────────────────────────────

    override suspend fun getCatalogList(index: Int): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val page = index + 1
                val url = "${baseUrl}novel-list?page=$page"
                val doc = networkClient.get(url).toDocument()
                val items = parseNovelCards(doc)
                PagedList(list = items, index = index, isLastPage = items.isEmpty())
            }
        }

    override suspend fun getCatalogSearch(index: Int, input: String): Response<PagedList<BookResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                if (input.isBlank()) return@tryConnect PagedList.createEmpty(index)
                val page = index + 1
                val url = "${baseUrl}novel-finder?text=${java.net.URLEncoder.encode(input, "UTF-8")}&page=$page"
                val doc = networkClient.get(url).toDocument()
                val items = parseNovelCards(doc)
                PagedList(list = items, index = index, isLastPage = items.isEmpty())
            }
        }

    private fun parseNovelCards(doc: Document): List<BookResult> {
        val items = mutableListOf<BookResult>()
        val cards = doc.select("div.series-list [data-slot='card']")
        for (card in cards) {
            val titleEl = card.selectFirst("a[href*='/novel/']") ?: continue
            val title = card.selectFirst("h3")?.text()?.trim()
                ?: titleEl.text().trim()
            if (title.isBlank()) continue
            val href = titleEl.attr("href")
            val url = if (href.startsWith("http")) href else URI(baseUrl).resolve(href).toString()
            val cover = card.selectFirst(".image-wrap img[alt]:not([aria-hidden])")?.attr("src") ?: ""
            val coverUrl = if (cover.startsWith("http")) cover
                else if (cover.isNotBlank()) URI(baseUrl).resolve(cover).toString()
                else ""
            items.add(BookResult(title = title, url = url, coverImageUrl = coverUrl))
        }
        return items
    }

    // ─── Book metadata ────────────────────────────────────────────────

    override suspend fun getBookCoverImageUrl(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                val cover = doc.selectFirst(".image-wrap img[alt]:not([aria-hidden])")?.attr("src")
                cover?.let { if (it.startsWith("http")) it else URI(baseUrl).resolve(it).toString() }
            }
        }

    override suspend fun getBookDescription(bookUrl: String): Response<String?> =
        withContext(Dispatchers.Default) {
            tryConnect {
                val doc = networkClient.get(bookUrl).toDocument()
                doc.selectFirst(".desc-wrap .description")?.text()?.trim()
            }
        }

    // ─── Chapter list ────────────────────────────────────────────────

    override suspend fun getChapterList(bookUrl: String): Response<List<ChapterResult>> =
        withContext(Dispatchers.Default) {
            tryConnect {
                // Extract novelId and slug from the book URL
                // URL format: https://wtr-lab.com/novel/{novelId}/{slug}
                val novelId = Regex("/novel/(\\d+)/").find(bookUrl)?.groupValues?.get(1)
                    ?: return@tryConnect emptyList()
                val slug = Regex("/novel/\\d+/([^/?#]+)").find(bookUrl)?.groupValues?.get(1) ?: ""

                delay(300) // Small delay to avoid rate limiting

                val apiUrl = "${baseUrl}api/chapters/$novelId"
                val response = networkClient.get(apiUrl)
                val jsonBody = response.body?.string() ?: return@tryConnect emptyList()
                response.close()

                val data = JsonParser.parseString(jsonBody).asJsonObject
                val chaptersData = data.getAsJsonArray("chapters") ?: return@tryConnect emptyList()

                val chapters = mutableListOf<ChapterResult>()
                for (i in 0 until chaptersData.size()) {
                    val ch = chaptersData[i].asJsonObject
                    val order = ch.get("order")?.asInt ?: (i + 1)
                    val title = ch.get("title")?.asString ?: "Chapter $order"
                    val chUrl = "${baseUrl}novel/$novelId/$slug/chapter-$order"
                    chapters.add(ChapterResult(
                        title = "$order: $title",
                        url = chUrl
                    ))
                }
                chapters
            }
        }

    // ─── Chapter text ────────────────────────────────────────────────

    override suspend fun getChapterText(doc: Document): String =
        withContext(Dispatchers.Default) {
            val chapterUrl = doc.location().ifBlank {
                doc.selectFirst("link[rel='canonical']")?.attr("href") ?: ""
            }
            if (chapterUrl.isBlank()) return@withContext ""

            // Extract novelId and chapterNo from the URL
            // URL format: https://wtr-lab.com/novel/{novelId}/{slug}/chapter-{N}
            val novelId = Regex("/novel/(\\d+)/").find(chapterUrl)?.groupValues?.get(1)
                ?: return@withContext ""
            val chapterNo = Regex("/chapter-(\\d+)").find(chapterUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val mode = getMode()
            val translateParam = if (mode == "raw") "web" else "ai"

            // POST /api/reader/get
            val requestBody = """{"translate":"$translateParam","language":"none","raw_id":"$novelId","chapter_no":$chapterNo,"retry":false,"force_retry":false}"""
            val request = postRequest("${baseUrl}api/reader/get", body = requestBody.toRequestBody(JSON_MEDIA_TYPE))
                .addHeader("Content-Type", "application/json")
                .addHeader("Referer", chapterUrl)
                .addHeader("Origin", baseUrl.trimEnd('/'))

            val response = networkClient.call(request)
            val respBody = response.body?.string() ?: run {
                response.close()
                return@withContext ""
            }
            response.close()

            val json = JsonParser.parseString(respBody).asJsonObject
            if (json.get("success")?.asBoolean == false) return@withContext ""

            // Navigate: json.data.data.body
            val outerData = json.getAsJsonObject("data") ?: return@withContext ""
            val innerData = outerData.getAsJsonObject("data") ?: outerData
            val body = innerData.get("body") ?: return@withContext ""
            val rawBody = if (body.isJsonArray) body.toString() else body.asString

            if (rawBody.isBlank() || rawBody == "null") return@withContext ""

            // Decrypt if encrypted (starts with "arr:")
            val resolvedBody = if (rawBody.startsWith("arr:")) {
                decryptBody(rawBody) ?: rawBody
            } else {
                rawBody
            }

            // Build paragraphs
            val paragraphs = buildParagraphs(resolvedBody)

            // Apply glossary (in ai mode)
            val finalText = if (mode != "raw" && paragraphs.isNotEmpty()) {
                applyGlossary(paragraphs, novelId, chapterUrl, innerData)
            } else {
                paragraphs
            }

            finalText.joinToString("\n\n")
        }

    override suspend fun getChapterTitle(doc: Document): String? =
        withContext(Dispatchers.Default) {
            doc.selectFirst("h1")?.text()?.trim()
        }

    // ─── Decryption ──────────────────────────────────────────────────

    private suspend fun decryptBody(rawBody: String): String? {
        return try {
            val request = postRequest(
                PROXY_URL,
                body = """{"payload":"$rawBody"}""".toRequestBody(JSON_MEDIA_TYPE)
            ).addHeader("Content-Type", "application/json")

            val response = networkClient.call(request)
            val respBody = response.body?.string()
            response.close()
            if (respBody.isNullOrBlank()) return null

            val data = JsonParser.parseString(respBody)
            when {
                data.isJsonArray -> data.toString()
                data.isJsonObject -> {
                    val obj = data.asJsonObject
                    if (obj.has("body")) obj.get("body").toString()
                    else obj.toString()
                }
                else -> respBody
            }
        } catch (e: Exception) {
            null
        }
    }

    // ─── Paragraph building ──────────────────────────────────────────

    private fun buildParagraphs(resolvedBody: String): List<String> {
        val paragraphs = mutableListOf<String>()

        try {
            val bodyArray = JsonParser.parseString(resolvedBody)
            if (bodyArray.isJsonArray) {
                for (item in bodyArray.asJsonArray) {
                    if (item.isJsonPrimitive && item.asJsonPrimitive.isString) {
                        val text = cleanParagraph(item.asString)
                        if (text != "[image]" && text.isNotBlank()) {
                            paragraphs.add(text)
                        }
                    }
                }
                return paragraphs
            }
        } catch (_: Exception) {}

        // Fallback: split by newlines
        for (line in resolvedBody.split("\n")) {
            val text = line.trim()
            if (text.isNotBlank()) paragraphs.add(text)
        }
        return paragraphs
    }

    private fun cleanParagraph(text: String): String {
        var result = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFKC)
        result = Regex("(?i)^\\s*(Translator|Editor|Proofreader|Read\\s+(at|on|latest))[:\\s][^\\n\\r]{0,70}").replace(result, "")
        return result.trim()
    }

    // ─── Glossary ────────────────────────────────────────────────────

    private suspend fun applyGlossary(
        paragraphs: List<String>,
        novelId: String,
        chapterUrl: String,
        chapterData: com.google.gson.JsonObject,
    ): List<String> {
        // Fetch book-level glossary terms
        val termByOriginal = mutableMapOf<String, String>()
        try {
            val v2Url = "${baseUrl}api/v2/reader/terms/$novelId.json"
            val v2Response = networkClient.get(v2Url)
            val v2Body = v2Response.body?.string()
            v2Response.close()
            if (v2Body != null) {
                val v2Data = JsonParser.parseString(v2Body).asJsonObject
                val glossaries = v2Data.getAsJsonArray("glossaries")
                if (glossaries != null) {
                    for (g in glossaries) {
                        val terms = g.asJsonObject?.getAsJsonObject("data")?.getAsJsonArray("terms")
                        if (terms != null) {
                            for (term in terms) {
                                val termArr = term.asJsonArray
                                if (termArr.size() >= 2) {
                                    val translations = termArr[0].asJsonArray
                                    val original = termArr[1].asString
                                    if (original.isNotBlank() && translations.size() > 0) {
                                        termByOriginal[original] = translations[0].asString
                                    }
                                }
                            }
                            break
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // Apply chapter-level glossary
        val glossaryMap = mutableMapOf<Int, String>()
        chapterData.getAsJsonObject("glossary_data")?.getAsJsonArray("terms")?.let { terms ->
            for (i in 0 until terms.size()) {
                val termEntry = terms[i].asJsonArray
                if (termEntry.size() >= 2) {
                    val raw = termEntry[0].asString
                    val original = if (termEntry.size() > 1) termEntry[1].asString else ""
                    val matched = termByOriginal[original] ?: raw
                    if (matched.isNotBlank()) {
                        glossaryMap[i] = matched
                    }
                }
            }
        }

        // Apply patches
        val patches = mutableListOf<Pair<String, String>>()
        chapterData.getAsJsonArray("patch")?.let { patchArr ->
            for (p in patchArr) {
                val pObj = p.asJsonObject
                val zh = pObj.get("zh")?.asString ?: ""
                val en = pObj.get("en")?.asString ?: ""
                if (zh.isNotBlank()) patches.add(zh to en)
            }
        }

        return paragraphs.map { para ->
            var text = para
            // Apply glossary markers: ※{idx}⛬ and ※{idx}〓
            for ((idx, term) in glossaryMap) {
                text = text.replace("※${idx}⛬", term)
                text = text.replace("※${idx}〓", term)
            }
            // Apply patches
            for ((zh, en) in patches) {
                text = text.replace(zh, en)
            }
            text
        }
    }
}
