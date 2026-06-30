@file:Suppress("FunctionName")

package my.noveldokusha.feature.local_database.migrations

import androidx.sqlite.db.SupportSQLiteDatabase

@Suppress("UnusedReceiverParameter")
internal fun MigrationsList.readLightNovelDomainChange_1_today(
    it: SupportSQLiteDatabase
) {
    // readlightnovel source changed its domain twice; both legacy hosts need to
    // be rewritten to the new one. We nest two REPLACE calls so a single column
    // value is rewritten in one pass without re-matching the new domain.
    //
    // SQLite's REPLACE(X, A, B) accepts exactly 3 arguments. An earlier version
    // of this migration passed 4 arguments (REPLACE(X, REPLACE(...), old2, new))
    // which is invalid SQL and would throw at runtime.
    val old1 = "www.readlightnovel.org"
    val old2 = "www.readlightnovel.me"
    val new = "www.readlightnovel.today"

    fun assign(columnName: String) =
        """$columnName = REPLACE(REPLACE($columnName, "$old1", "$new"), "$old2", "$new")"""

    fun like(columnName: String) =
        """($columnName LIKE "%$old1%" OR $columnName LIKE "%$old2%")"""

    it.execSQL(
        """
            UPDATE Book
            SET ${assign("url")},
                ${assign("coverImageUrl")}
            WHERE ${like("url")};
        """.trimIndent()
    )
    it.execSQL(
        """
            UPDATE Chapter
            SET ${assign("url")},
                ${assign("bookUrl")}
            WHERE ${like("bookUrl")};
        """.trimIndent()
    )
    it.execSQL(
        """
            UPDATE ChapterBody
            SET ${assign("url")}
            WHERE ${like("url")};
        """.trimIndent()
    )
}

internal fun MigrationsList.readLightNovelDomainChange_2_meme(
    it: SupportSQLiteDatabase,
) = this.websiteDomainChangeHelper(
    it,
    oldDomain = "www.readlightnovel.today",
    newDomain = "www.readlightnovel.meme",
)
