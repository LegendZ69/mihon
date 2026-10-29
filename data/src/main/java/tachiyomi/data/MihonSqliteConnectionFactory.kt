package tachiyomi.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConnectionFactory

/** Wait for short-lived locks held by another app process, including crash/restart overlap. */
class MihonSqliteConnectionFactory(
    private val busyTimeoutMillis: Int = 5_000,
    /** Runs on every new connection, after the timeout and before the driver configures or migrates it. */
    private val prepare: (SQLiteConnection) -> Unit = {},
) : AndroidxSqliteConnectionFactory {
    init {
        require(busyTimeoutMillis in 1..60_000)
    }

    override val driver = BundledSQLiteDriver()

    override fun createConnection(name: String): SQLiteConnection {
        val connection = driver.open(name)
        try {
            // This must precede the pool's WAL setup and apply to its readers as well as its writer.
            // onConfigure runs once per SQLDelight driver, not once per native connection.
            connection.execSQL("PRAGMA busy_timeout = $busyTimeoutMillis;")
            prepare(connection)
            return connection
        } catch (error: Throwable) {
            runCatching { connection.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }
}
