@file:Suppress("FunctionName")

package my.noveldokusha.feature.local_database.migrations

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration: Remove dead source references from the database.
 *
 * The following sources have gone offline permanently:
 * - readlightnovel.meme (all previous domains: .org, .me, .today, .meme)
 * - 1stkissnovel.love, 1stkissnovel.org
 * - a-t.nu
 * - boxnovel.com
 * - saikaiscan.com.br
 * - novelku.id
 * - bestlightnovel.com
 * - wbnovel.com
 * - morenovel.net
 * - lightnovelworld.com
 * - koreanmtl.online
 * - wuxia.blog
 *
 * This migration:
 * 1. Removes dead source books from the library (inLibrary = 0)
 * 2. Updates MTLNovel domain from mtlnovel.com -> mtlnovel.me
 * 3. Updates MeioNovel domain from meionovel.id -> meionovels.com
 */
@Suppress("UnusedReceiverParameter")
internal fun MigrationsList.deadSourcesCleanup_8(
    it: SupportSQLiteDatabase
) {
    // Mark dead source books as not in library so they don't clutter the library view
    val deadDomains = listOf(
        "www.readlightnovel.org",
        "www.readlightnovel.me",
        "www.readlightnovel.today",
        "www.readlightnovel.meme",
        "1stkissnovel.love",
        "1stkissnovel.org",
        "a-t.nu",
        "boxnovel.com",
        "saikaiscan.com.br",
        "novelku.id",
        "bestlightnovel.com",
        "wbnovel.com",
        "morenovel.net",
        "www.lightnovelworld.com",
        "www.koreanmtl.online",
        "www.wuxia.blog",
    )

    for (domain in deadDomains) {
        it.execSQL(
            """UPDATE Book SET inLibrary = 0 WHERE url LIKE "%$domain%""""
        )
    }

    // MTLNovel domain migration: mtlnovel.com -> mtlnovel.me
    it.execSQL(
        """UPDATE Book SET url = REPLACE(url, "www.mtlnovel.com", "mtlnovel.me"), coverImageUrl = REPLACE(coverImageUrl, "www.mtlnovel.com", "mtlnovel.me") WHERE url LIKE "%mtlnovel.com%""""
    )
    it.execSQL(
        """UPDATE Chapter SET url = REPLACE(url, "www.mtlnovel.com", "mtlnovel.me"), bookUrl = REPLACE(bookUrl, "www.mtlnovel.com", "mtlnovel.me") WHERE bookUrl LIKE "%mtlnovel.com%""""
    )
    it.execSQL(
        """UPDATE ChapterBody SET url = REPLACE(url, "www.mtlnovel.com", "mtlnovel.me") WHERE url LIKE "%mtlnovel.com%""""
    )

    // MeioNovel domain migration: meionovel.id -> meionovels.com
    it.execSQL(
        """UPDATE Book SET url = REPLACE(url, "meionovel.id", "meionovels.com"), coverImageUrl = REPLACE(coverImageUrl, "meionovel.id", "meionovels.com") WHERE url LIKE "%meionovel.id%""""
    )
    it.execSQL(
        """UPDATE Chapter SET url = REPLACE(url, "meionovel.id", "meionovels.com"), bookUrl = REPLACE(bookUrl, "meionovel.id", "meionovels.com") WHERE bookUrl LIKE "%meionovel.id%""""
    )
    it.execSQL(
        """UPDATE ChapterBody SET url = REPLACE(url, "meionovel.id", "meionovels.com") WHERE url LIKE "%meionovel.id%""""
    )
}
