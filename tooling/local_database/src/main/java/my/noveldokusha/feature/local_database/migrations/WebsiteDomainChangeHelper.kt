package my.noveldokusha.feature.local_database.migrations

import androidx.sqlite.db.SupportSQLiteDatabase

@Suppress("UnusedReceiverParameter")
internal fun MigrationsList.websiteDomainChangeHelper(
    it: SupportSQLiteDatabase,
    oldDomain: String,
    newDomain: String,
) {
    // Replace the old domain with the new domain in every URL-bearing column.
    //
    // Important SQLite syntax notes:
    //   * UPDATE has exactly one SET keyword followed by comma-separated assignments.
    //     Earlier code emitted a leading "SET" inside every assignment which produced
    //     invalid SQL like "UPDATE Book SET a=..., SET b=...".
    //   * The WHERE clause must reference a column that actually exists on the table.
    //     The Book table has no `chapterUrl` column, so we filter on `url` instead.
    fun assign(columnName: String) =
        """$columnName = REPLACE($columnName, "$oldDomain", "$newDomain")"""

    fun like(columnName: String) =
        """($columnName LIKE "%$oldDomain%")"""

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
