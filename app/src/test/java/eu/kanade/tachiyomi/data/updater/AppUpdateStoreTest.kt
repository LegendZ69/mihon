package eu.kanade.tachiyomi.data.updater

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.release.model.Release
import java.io.File
import java.io.IOException
import java.util.UUID

class AppUpdateStoreTest {
    @TempDir
    lateinit var directory: File

    private val persistence = MemoryPersistence()
    private val download = AppUpdateDownload(
        UUID.randomUUID().toString(),
        Release("translator-v20", "notes", "https://github.com/release", "https://github.com/update.apk"),
        AppUpdateStage.QUEUED,
    )

    @Test
    fun `transient progress survives in memory and only completion commits readiness`() {
        val store = AppUpdateStore(directory, persistence)
        assertTrue(store.save(download))
        assertTrue(
            store.update(download.id, persist = false) {
                it.copy(stage = AppUpdateStage.DOWNLOADING, progress = 50)
            },
        )
        assertEquals(50, store.state.value?.progress)
        assertEquals(AppUpdateStage.QUEUED, AppUpdateStore(directory, persistence).state.value?.stage)

        assertTrue(
            store.update(download.id, persist = false) {
                it.copy(stage = AppUpdateStage.DOWNLOADED, progress = 100)
            },
        )
        assertEquals(AppUpdateStage.DOWNLOADED, AppUpdateStore(directory, persistence).state.value?.stage)
    }

    @Test
    fun `disk full during completion never exposes downloaded and preserves the committed record`() {
        val store = AppUpdateStore(directory, persistence)
        store.save(download.copy(stage = AppUpdateStage.DOWNLOADING))
        val committed = persistence.bytes?.copyOf()
        persistence.failWrites = true

        assertFalse(store.update(download.id) { it.copy(stage = AppUpdateStage.DOWNLOADED, progress = 100) })
        assertEquals(AppUpdateStage.FAILED, store.state.value?.stage)
        assertNotNull(store.state.value?.error)
        assertArrayEquals(committed, persistence.bytes)
        assertEquals(AppUpdateStage.DOWNLOADING, AppUpdateStore(directory, persistence).state.value?.stage)
    }

    @Test
    fun `failure observer remains nonthrowing when its failure record cannot be written`() {
        val store = AppUpdateStore(directory, persistence)
        store.save(download)
        persistence.failWrites = true

        assertFalse(store.update(download.id) { it.copy(stage = AppUpdateStage.FAILED, error = "Transfer failed") })
        assertEquals(AppUpdateStage.FAILED, store.state.value?.stage)
        assertFalse(
            store.update(download.id, persist = false, onlyWhileActive = true) {
                it.copy(stage = AppUpdateStage.DOWNLOADING, progress = 60)
            },
        )
        assertEquals(AppUpdateStage.FAILED, store.state.value?.stage)
    }

    @Test
    fun `failed admission preserves an earlier downloaded record and reports the requested attempt as failed`() {
        val store = AppUpdateStore(directory, persistence)
        store.save(download.copy(stage = AppUpdateStage.DOWNLOADED))
        val committed = persistence.bytes?.copyOf()
        val retry = download.copy(id = UUID.randomUUID().toString())
        persistence.failWrites = true

        assertFalse(store.save(retry))
        assertEquals(retry.id, store.state.value?.id)
        assertEquals(AppUpdateStage.FAILED, store.state.value?.stage)
        assertArrayEquals(committed, persistence.bytes)
        assertEquals(download.id, AppUpdateStore(directory, persistence).state.value?.id)
    }

    @Test
    fun `failed deletion retains visible failure and can be retried after storage recovers`() {
        val store = AppUpdateStore(directory, persistence)
        store.save(download)
        val committed = persistence.bytes?.copyOf()
        persistence.failDeletes = true

        assertFalse(store.save(null))
        assertEquals(AppUpdateStage.FAILED, store.state.value?.stage)
        assertArrayEquals(committed, persistence.bytes)

        persistence.failDeletes = false
        assertTrue(store.save(null))
        assertNull(store.state.value)
        assertNull(persistence.bytes)
    }

    @Test
    fun `stale worker cannot modify replacement or a terminal record`() {
        val store = AppUpdateStore(directory, persistence)
        val replacement = download.copy(id = UUID.randomUUID().toString())
        store.save(replacement)
        assertFalse(store.update(download.id) { it.copy(stage = AppUpdateStage.DOWNLOADED) })
        assertEquals(replacement, store.state.value)

        store.update(replacement.id) { it.copy(stage = AppUpdateStage.FAILED) }
        assertFalse(store.update(replacement.id, onlyWhileActive = true) { it.copy(stage = AppUpdateStage.DOWNLOADED) })
        assertEquals(AppUpdateStage.FAILED, store.state.value?.stage)
    }

    @Test
    fun `stale active reconciliation cannot overwrite or rewrite a completed record`() {
        val store = AppUpdateStore(directory, persistence)
        store.save(download.copy(stage = AppUpdateStage.DOWNLOADING))
        val beforeWorkInfoLookup = store.state.value!!
        assertTrue(store.update(download.id, onlyWhileActive = true) { it.copy(stage = AppUpdateStage.DOWNLOADED) })
        val committed = persistence.bytes?.copyOf()

        // A WorkInfo lookup can finish after the worker commits readiness. The same guard also
        // prevents the collector from rewriting unchanged terminal metadata when storage is full.
        persistence.failWrites = true
        assertFalse(
            store.update(beforeWorkInfoLookup.id, onlyWhileActive = true) {
                it.copy(stage = AppUpdateStage.FAILED, error = "Download interrupted")
            },
        )
        assertEquals(AppUpdateStage.DOWNLOADED, store.state.value?.stage)
        assertArrayEquals(committed, persistence.bytes)
    }

    @Test
    fun `corrupt or unsafe persisted identities are ignored without modifying evidence`() {
        for (bytes in listOf(
            "interrupted JSON".encodeToByteArray(),
            Json.encodeToString(download.copy(id = "../../unrelated")).encodeToByteArray(),
        )) {
            persistence.bytes = bytes
            assertNull(AppUpdateStore(directory, persistence).state.value)
            assertArrayEquals(bytes, persistence.bytes)
        }
    }

    @Test
    fun `metadata write can recover through a new admitted attempt`() {
        val store = AppUpdateStore(directory, persistence)
        persistence.failWrites = true
        assertFalse(store.save(download))
        persistence.failWrites = false
        val retry = download.copy(id = UUID.randomUUID().toString())
        assertTrue(store.save(retry))
        assertEquals(retry, AppUpdateStore(directory, persistence).state.value)
    }

    private class MemoryPersistence : AppUpdateRecordPersistence {
        var bytes: ByteArray? = null
        var failWrites = false
        var failDeletes = false

        override fun read(): ByteArray? = bytes?.copyOf()

        override fun write(bytes: ByteArray) {
            if (failWrites) throw IOException("No space left on device")
            this.bytes = bytes.copyOf()
        }

        override fun delete() {
            if (failDeletes) throw IOException("Storage is not writable")
            bytes = null
        }
    }
}
