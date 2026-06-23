package my.noveldokusha.scraper

import my.noveldokusha.network.NetworkClient
import my.noveldokusha.scraper.databases.BakaUpdates
import my.noveldokusha.scraper.databases.NovelUpdates
import my.noveldokusha.scraper.sources.BacaLightnovel
import my.noveldokusha.scraper.sources.FanMTL
import my.noveldokusha.scraper.sources.FreeWebNovel
import my.noveldokusha.scraper.sources.IndoWebnovel
import my.noveldokusha.scraper.sources.LightNovelsTranslations
import my.noveldokusha.scraper.sources.LocalSource
import my.noveldokusha.scraper.sources.MTLNovel
import my.noveldokusha.scraper.sources.MeioNovel
import my.noveldokusha.scraper.sources.Novel543
import my.noveldokusha.scraper.sources.NovelBin
import my.noveldokusha.scraper.sources.NovelBinParas
import my.noveldokusha.scraper.sources.NovelHall
import my.noveldokusha.scraper.sources.NovelHallParas
import my.noveldokusha.scraper.sources.NovelHubApp
import my.noveldokusha.scraper.sources.NovelHubNet
import my.noveldokusha.scraper.sources.ReadNovelFull
import my.noveldokusha.scraper.sources.Reddit
import my.noveldokusha.scraper.sources.RoyalRoad
import my.noveldokusha.scraper.sources.SakuraNovel
import my.noveldokusha.scraper.sources.Sousetsuka
import my.noveldokusha.scraper.sources.TimoTxt
import my.noveldokusha.scraper.sources.TimoTxtTranslate
import my.noveldokusha.scraper.sources.Twkan
import my.noveldokusha.scraper.sources.WebNovel
import my.noveldokusha.scraper.sources.WuxiaWorld
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Scraper @Inject constructor(
    networkClient: NetworkClient,
    localSource: LocalSource
) {
    val databasesList = setOf(
        NovelUpdates(networkClient),
        BakaUpdates(networkClient)
    )

    val sourcesList = setOf(
        localSource,
        LightNovelsTranslations(networkClient),
        ReadNovelFull(networkClient),
        RoyalRoad(networkClient),
        my.noveldokusha.scraper.sources.NovelUpdates(networkClient),
        Reddit(),
        Sousetsuka(),
        NovelHall(networkClient),
        MTLNovel(networkClient),
        WuxiaWorld(networkClient),
        IndoWebnovel(networkClient),
        BacaLightnovel(networkClient),
        SakuraNovel(networkClient),
        MeioNovel(networkClient),
        NovelBin(networkClient),
        // New sources
        TimoTxt(networkClient),
        TimoTxtTranslate(networkClient),
        FreeWebNovel(networkClient),
        FanMTL(networkClient),
        Twkan(networkClient),
        Novel543(networkClient),
        NovelHubNet(networkClient),
        NovelHubApp(networkClient),
        WebNovel(networkClient),
        // Alternative (Paras) sources from novelscraper project
        NovelHallParas(networkClient),
        NovelBinParas(networkClient),
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
