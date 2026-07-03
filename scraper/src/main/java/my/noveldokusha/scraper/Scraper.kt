package my.noveldokusha.scraper

import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.databases.BakaUpdates
import my.noveldokusha.scraper.databases.NovelUpdates
import my.noveldokusha.scraper.sources.AT
import my.noveldokusha.scraper.sources.BacaLightnovel
import my.noveldokusha.scraper.sources.BoxNovel
import my.noveldokusha.scraper.sources.IndoWebnovel
import my.noveldokusha.scraper.sources.LightNovelWorld
import my.noveldokusha.scraper.sources.LightNovelsTranslations
import my.noveldokusha.scraper.sources.Lnori
import my.noveldokusha.scraper.sources.LocalSource
import my.noveldokusha.scraper.sources.MeioNovel
import my.noveldokusha.scraper.sources.MoreNovel
import my.noveldokusha.scraper.sources.NovelCool
import my.noveldokusha.scraper.sources.NovelFire
import my.noveldokusha.scraper.sources.NovelHall
import my.noveldokusha.scraper.sources.NovelPhoenix
import my.noveldokusha.scraper.sources.Novelku
import my.noveldokusha.scraper.sources.ReadNovelFull
import my.noveldokusha.scraper.sources.Reddit
import my.noveldokusha.scraper.sources.RoyalRoad
import my.noveldokusha.scraper.sources.Saikai
import my.noveldokusha.scraper.sources.SakuraNovel
import my.noveldokusha.scraper.sources.Sousetsuka
import my.noveldokusha.scraper.sources.TimoTxt
import my.noveldokusha.scraper.sources.TimoTxtGemini
import my.noveldokusha.scraper.sources.TimoTxtTranslate
import my.noveldokusha.scraper.sources.WbNovel
import my.noveldokusha.scraper.sources.Wuxia
import my.noveldokusha.scraper.sources.WuxiaBox
import my.noveldokusha.scraper.sources.WuxiaWorld
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Scraper @Inject constructor(
    networkClient: NetworkClient,
    localSource: LocalSource,
    appPreferences: AppPreferences,
) {
    val databasesList = setOf(
        NovelUpdates(networkClient),
        BakaUpdates(networkClient)
    )

    val sourcesList = setOf(
        localSource,
        // ---- English ----
        LightNovelsTranslations(networkClient),
        ReadNovelFull(networkClient),
        RoyalRoad(networkClient),
        my.noveldokusha.scraper.sources.NovelUpdates(networkClient),
        Reddit(),
        AT(),
        Wuxia(networkClient),
        NovelFire(networkClient),
        NovelPhoenix(networkClient),
        NovelCool(networkClient),
        Lnori(networkClient),
        WuxiaBox(networkClient),
        Sousetsuka(),
        Saikai(networkClient),
        BoxNovel(networkClient),
        LightNovelWorld(networkClient),
        NovelHall(networkClient),
        WuxiaWorld(networkClient),
        MeioNovel(networkClient),
        MoreNovel(networkClient),
        Novelku(networkClient),
        WbNovel(networkClient),
        // ---- Indonesian ----
        IndoWebnovel(networkClient),
        BacaLightnovel(networkClient),
        SakuraNovel(networkClient),
        // ---- Chinese ----
        TimoTxt(networkClient),
        TimoTxtTranslate(networkClient),
        TimoTxtGemini(
            networkClient = networkClient,
            geminiApiKey = appPreferences.GEMINI_API_KEY.value,
            geminiModel = appPreferences.GEMINI_MODEL.value,
        ),
    )

    val sourcesCatalogsList = sourcesList.filterIsInstance<SourceInterface.Catalog>()
    val sourcesCatalogsLanguagesList = sourcesCatalogsList.mapNotNull { it.language }.toSet()

    private fun String.isCompatibleWithBaseUrl(baseUrl: String): Boolean {
        val normalizedUrl = if (this.endsWith("/")) this else "$this/"
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"
        return normalizedUrl.startsWith(normalizedBaseUrl)
    }

    fun getCompatibleSource(url: String): SourceInterface? =
        sourcesList.find { url.isCompatibleWithBaseUrl(it.baseUrl) }

    fun getCompatibleSourceCatalog(url: String): SourceInterface.Catalog? =
        sourcesCatalogsList.find { url.isCompatibleWithBaseUrl(it.baseUrl) }

    fun getCompatibleDatabase(url: String): DatabaseInterface? =
        databasesList.find { url.isCompatibleWithBaseUrl(it.baseUrl) }

    /**
     * Find alternative sources that can serve the same book as [url].
     *
     * This enables the "switch source" feature: a novel favorited on one
     * TimoTxt source (e.g. TimoTxt) can be opened in either of the other
     * two TimoTxt variants (TimoTxtTranslate, TimoTxtGemini) because they
     * all share the same path structure on timotxt.com
     * (`/{novelId}/` and `/{novelId}/{chNum}.html`).
     *
     * The three TimoTxt sources use distinct baseUrls for routing:
     *   - TimoTxt:           `https://www.timotxt.com/`
     *   - TimoTxtTranslate:  `https://www-timotxt-com.translate.goog/`
     *   - TimoTxtGemini:     `https://www-timotxt-com-gemini.goog/`
     *
     * To convert a URL from one source to another, we extract the path
     * (`/{novelId}/...`) and prepend the target source's baseUrl.
     *
     * Returns a list of (source, convertedUrl) pairs, EXCLUDING the current
     * source. Returns an empty list if the URL doesn't belong to a TimoTxt
     * source or there are no alternatives.
     */
    fun getAlternativeSources(url: String): List<Pair<SourceInterface.Catalog, String>> {
        // The three TimoTxt source IDs. All share the same URL path structure.
        val timotxtSourceIds = setOf("timotxt", "timotxt_translate", "timotxt_gemini")

        val currentSource = getCompatibleSourceCatalog(url) ?: return emptyList()
        if (currentSource.id !in timotxtSourceIds) return emptyList()

        // Extract the path portion: everything after the host.
        // e.g. "https://www.timotxt.com/0910595344/dir" → "/0910595344/dir"
        val path = runCatching {
            val uri = android.net.Uri.parse(url)
            val p = uri.path ?: return emptyList()
            // Uri.path returns "/0910595344/dir" — already starts with /
            if (p.isEmpty()) return emptyList()
            p
        }.getOrNull() ?: return emptyList()

        return sourcesCatalogsList
            .filter { it.id in timotxtSourceIds && it.id != currentSource.id }
            .map { source ->
                val convertedUrl = source.baseUrl.trimEnd('/') + path
                source to convertedUrl
            }
    }
}
