package mihon.app.di

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Independent pools reproduce the contention that a second app process can introduce. */
@RunWith(AndroidJUnit4::class)
class DatabaseContentionTest {
    @Test
    fun everyNativeConnectionReceivesTheTimeoutBeforePoolConfiguration() = fixture { file ->
        val factory = MihonSqliteConnectionFactory()
        repeat(4) {
            factory.createConnection(file.absolutePath).use { connection ->
                connection.prepare("PRAGMA busy_timeout;").use { query ->
                    assertTrue(query.step())
                    assertEquals(5_000L, query.getLong(0))
                }
            }
        }
    }

    @Test
    fun independentWriterWaitsForShortLockAndBothWritesArePreserved() = fixture { file ->
        contention(file, 5_000, shouldSucceed = true)
    }

    @Test
    fun longerLockFailsWithinBoundAndTheConnectionCanBeReused() = fixture { file ->
        contention(file, 100, shouldSucceed = false)
    }

    private suspend fun contention(file: File, timeoutMillis: Int, shouldSucceed: Boolean) {
        val first = driver(file, timeoutMillis)
        val second = driver(file, timeoutMillis)
        val executor = Executors.newSingleThreadExecutor()
        try {
            first.execute(null, "CREATE TABLE lock_probe(id INTEGER PRIMARY KEY);", 0).await()
            // Initialize both pools before taking the lock so the assertion concerns writes.
            second.execute(null, "INSERT INTO lock_probe VALUES(0);", 0).await()
            first.execute(null, "DELETE FROM lock_probe;", 0).await()
            first.execute(null, "BEGIN IMMEDIATE;", 0).await()
            first.execute(null, "INSERT INTO lock_probe VALUES(1);", 0).await()
            val started = CountDownLatch(1)
            val write = executor.submit {
                runBlocking {
                    started.countDown()
                    second.execute(null, "INSERT INTO lock_probe VALUES(2);", 0).await()
                }
            }
            assertTrue(started.await(5, TimeUnit.SECONDS))
            try {
                if (shouldSucceed) {
                    try {
                        write.get(150, TimeUnit.MILLISECONDS)
                        fail("The second writer must wait while the first holds the lock")
                    } catch (_: TimeoutException) {
                        // A zero busy timeout instead throws ExecutionException here.
                    }
                } else {
                    try {
                        write.get(5, TimeUnit.SECONDS)
                        fail("A lock held beyond the configured timeout must remain a visible error")
                    } catch (error: ExecutionException) {
                        assertTrue(error.cause?.message.orEmpty().contains("database is locked"))
                    }
                }
            } finally {
                first.execute(null, "COMMIT;", 0).await()
            }
            if (shouldSucceed) {
                write.get(5, TimeUnit.SECONDS)
            } else {
                second.execute(null, "INSERT INTO lock_probe VALUES(2);", 0).await()
            }
            BundledSQLiteDriver().open(file.absolutePath).use { connection ->
                connection.prepare("SELECT COUNT(*) FROM lock_probe;").use { query ->
                    assertTrue(query.step())
                    assertEquals(2L, query.getLong(0))
                }
            }
        } finally {
            executor.shutdownNow()
            first.close()
            second.close()
        }
    }

    private fun driver(file: File, timeoutMillis: Int) = AndroidxSqliteDriver(
        connectionFactory = MihonSqliteConnectionFactory(timeoutMillis),
        databaseType = AndroidxSqliteDatabaseType.File(file.absolutePath),
        schema = Database.Schema,
        configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
    )

    private fun fixture(block: suspend (File) -> Unit) = runBlocking {
        withContext(Dispatchers.IO) {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val directory = File(context.cacheDir, "database-contention-${UUID.randomUUID()}")
            check(directory.mkdirs())
            try {
                block(File(directory, "test.db"))
            } finally {
                directory.deleteRecursively()
            }
        }
    }
}
