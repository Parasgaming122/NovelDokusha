package my.noveldokusha.scraper

import my.noveldokusha.core.appPreferences.AppPreferences
import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.databases.BakaUpdates
import my.noveldokusha.scraper.databases.NovelUpdates
import my.noveldokusha.scraper.sources.AllNovel
import my.noveldokusha.scraper.sources.BacaLightnovel
import my.noveldokusha.scraper.sources.BestLightNovel
import my.noveldokusha.scraper.sources.BoxNovel
import my.noveldokusha.scraper.sources.IndoWebnovel
import my.noveldokusha.scraper.sources.KoreanNovelsMTL
import my.noveldokusha.scraper.sources.LightNovelsTranslations
import my.noveldokusha.scraper.sources.LocalSource
import my.noveldokusha.scraper.sources.MTLNovel
import my.noveldokusha.scraper.sources.NovelBin
import my.noveldokusha.scraper.sources.NovelHall
import my.noveldokusha.scraper.sources.NovelUpdates
import my.noveldokusha.scraper.sources.ReadLightNovel
import my.noveldokusha.scraper.sources.ReadNovelFull
import my.noveldokusha.scraper.sources.Reddit
import my.noveldokusha.scraper.sources.RoyalRoad
import my.noveldokusha.scraper.sources.SakuraNovel
import my.noveldokusha.scraper.sources.Sousetsuka
import my.noveldokusha.scraper.sources.TimoTxt
import my.noveldokusha.scraper.sources.TimoTxtGemini
import my.noveldokusha.scraper.sources.TimoTxtTranslate
import my.noveldokusha.scraper.sources.WuxiaWorld
import my.noveldokusha.scraper.sources._1stKissNovel
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

    /**
     * Final source list after the 2026-06 source audit.
     *
     * Removed (dead domains, no working replacement found):
     *  - AT (a-t.nu)              – DNS no longer resolves
     *  - MoreNovel (morenovel.net) – DNS gone; .com is a domain-sale placeholder
     *  - Novelku (novelku.id)     – DNS no longer resolves
     *  - MeioNovel (meionovel.id) – 301-redirects to meionovels.com which times out
     *  - WbNovel (wbnovel.com)    – domain repurposed (now serves "CoreSip" Next.js app)
     *  - Wuxia (wuxia.blog)       – DNS resolves but server does not respond on 443
     *  - LightNovelWorld (lightnovelworld.com) – site permanently shut down
     *  - Saikai (saikaiscan.com.br) – DNS no longer resolves; api.saikai.com.br also dead
     *
     * Updated (same site, new domain):
     *  - ReadLightNovel:  www.readlightnovel.meme → www.readlightnovel.org
     *  - NovelBin:        novelbin.me → novelbin.net
     *  - MTLNovel:        www.mtlnovel.com → mtlnovels.com
     *  - BoxNovel:        boxnovel.com → boxnovel.org
     *
     * Added:
     *  - AllNovel (allnovel.org) – English replacement for the shut-down LightNovelWorld
     */
    val sourcesList = setOf(
        localSource,
        // ---- English ----
        LightNovelsTranslations(networkClient),
        ReadLightNovel(networkClient),
        ReadNovelFull(networkClient),
        RoyalRoad(networkClient),
        my.noveldokusha.scraper.sources.NovelUpdates(networkClient),
        Reddit(),
        BestLightNovel(networkClient),
        _1stKissNovel(networkClient),
        Sousetsuka(),
        BoxNovel(networkClient),
        NovelHall(networkClient),
        MTLNovel(networkClient),
        WuxiaWorld(networkClient),
        KoreanNovelsMTL(networkClient),
        AllNovel(networkClient),
        // ---- Indonesian ----
        IndoWebnovel(networkClient),
        BacaLightnovel(networkClient),
        SakuraNovel(networkClient),
        NovelBin(networkClient),
        // ---- Chinese ----
        TimoTxt(networkClient),
        TimoTxtTranslate(networkClient),
        TimoTxtGemini(
            networkClient = networkClient,
            // T2 fix: read API key/model lazily so Settings changes take effect without an app restart.
            // Previously these were captured at Scraper construction (@Singleton), so updating
            // the key in Settings had no effect until the process was killed.
            geminiApiKeyProvider = { appPreferences.GEMINI_API_KEY.value },
            geminiModelProvider = { appPreferences.GEMINI_MODEL.value },
            geminiTemperatureProvider = { appPreferences.GEMINI_TEMPERATURE.value },
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
}
