package tachiyomi.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Fork releases up to translator-v22 numbered their translator migrations 15 to 17, the numbers upstream
 * later gave its own schema rework. Such a database reports user_version 16 to 18 without having run any
 * upstream migration past 14, so the driver would skip them. This brings its translator tables to the
 * shape of the idempotent translator migration and rewinds user_version to 15, after which the driver runs
 * every upstream migration and then the translator one.
 *
 * Only a database with translator tables and the pre-15 `mangas.last_modified_at` column qualifies: an
 * upstream database at those versions has no translator tables, and one this code already rewound reports 15.
 */
object TranslatorLegacySchema {

    /** Legacy fork translator migration 17, which the idempotent table definitions cannot express. */
    private val legacyEventColumns = listOf(
        "ALTER TABLE translation_events ADD COLUMN operation_id TEXT",
        "ALTER TABLE translation_events ADD COLUMN batch_id TEXT",
        "ALTER TABLE translation_events ADD COLUMN image_id TEXT",
        "ALTER TABLE translation_events ADD COLUMN stage TEXT NOT NULL DEFAULT 'legacy'",
        "ALTER TABLE translation_events ADD COLUMN level TEXT NOT NULL DEFAULT 'INFO'",
    )

    const val UPSTREAM_VERSION = 15L
    private val LEGACY_VERSIONS = 16L..18L

    fun normalize(connection: SQLiteConnection) {
        // Cheap check on every connection; only a legacy database takes the write lock.
        if (!isLegacy(connection)) return
        connection.execSQL("BEGIN IMMEDIATE")
        try {
            // Another connection or process may have normalized it while this one waited for the lock.
            if (isLegacy(connection)) {
                if (userVersion(connection) < 18L && !hasColumn(connection, "translation_events", "operation_id")) {
                    legacyEventColumns.forEach(connection::execSQL)
                }
                connection.execSQL("PRAGMA user_version = $UPSTREAM_VERSION")
            }
            connection.execSQL("COMMIT")
        } catch (error: Throwable) {
            runCatching { connection.execSQL("ROLLBACK") }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun isLegacy(connection: SQLiteConnection): Boolean =
        userVersion(connection) in LEGACY_VERSIONS &&
            hasTable(connection, "translation_jobs") &&
            hasColumn(connection, "mangas", "last_modified_at")

    private fun userVersion(connection: SQLiteConnection): Long =
        connection.prepare("PRAGMA user_version").use { if (it.step()) it.getLong(0) else 0L }

    private fun hasTable(connection: SQLiteConnection, table: String): Boolean =
        connection.prepare("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?").use {
            it.bindText(1, table)
            it.step()
        }

    private fun hasColumn(connection: SQLiteConnection, table: String, column: String): Boolean =
        connection.prepare("SELECT 1 FROM pragma_table_info(?) WHERE name = ?").use {
            it.bindText(1, table)
            it.bindText(2, column)
            it.step()
        }
}
