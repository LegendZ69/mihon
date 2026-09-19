package mihon.feature.translation.transfer

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class TranslationExportStoreTest {
    @TempDir lateinit var temporary: File

    @Test
    fun `deleting a selected owned render preserves other jobs and source chapters`() = runTest {
        val source = File(temporary, "source.cbz").apply { writeText("original chapter") }
        val store = TranslationExportStore(File(temporary, "exports"))
        val first = store.begin("first-job", "CBZ")
        first.file.writeText("first rendered chapter")
        val saved = store.commit(first, complete = true, pages = 2)
        val second = store.begin("second-job", "PDF")
        second.file.writeText("other rendered chapter")
        store.commit(second, complete = true, pages = 4)
        assertEquals(listOf(saved.id), store.list(setOf("first-job")).map { it.id })

        val deletedBytes = store.delete(setOf(saved.id))

        assertEquals(saved.storageBytes, deletedBytes)
        assertFalse(first.file.exists())
        assertEquals(listOf(second.id), store.list().map { it.id })
        assertEquals("other rendered chapter", second.file.readText())
        assertEquals("original chapter", source.readText())
        assertTrue(store.list().single().complete)
    }

    @Test
    fun `late export cleanup cannot erase an already committed rendered chapter`() = runTest {
        val store = TranslationExportStore(File(temporary, "exports"))
        val handle = store.begin("job", "CBZ")
        handle.file.writeText("completed rendered chapter")
        val committed = store.commit(handle, complete = true, pages = 3)

        store.abandon(handle)

        assertEquals(listOf(committed.id), store.list().map { it.id })
        assertEquals("completed rendered chapter", handle.file.readText())
        assertTrue(store.list().single().complete)
    }

    @Test
    fun `confirmed management fences selected export starts while other chapters remain available`() = runTest {
        val store = TranslationExportStore(File(temporary, "exports"))

        store.withManagement(setOf("selected-job")) {
            val rejected = runCatching { store.begin("selected-job", "CBZ") }.exceptionOrNull()
            assertTrue(rejected is IllegalStateException, "A selected export must not start during confirmed deletion")
            val other = store.begin("other-job", "PDF")
            other.file.writeText("unrelated rendered chapter")
            store.commit(other, complete = true, pages = 1)
        }

        val later = store.begin("selected-job", "CBZ")
        later.file.writeText("new explicitly requested render")
        store.commit(later, complete = true, pages = 1)
        assertEquals(setOf("selected-job", "other-job"), store.list().map { it.jobId }.toSet())
    }

    @Test
    fun `cancelled management releases its fence and preserves previously completed exports`() = runTest {
        val store = TranslationExportStore(File(temporary, "exports"))
        val retained = store.begin("selected-job", "CBZ")
        retained.file.writeText("retained chapter")
        store.commit(retained, complete = true, pages = 1)
        val entered = CompletableDeferred<Unit>()
        val management = async {
            store.withManagement(setOf("selected-job")) {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        management.cancelAndJoin()

        val next = store.begin("selected-job", "PDF")
        next.file.writeText("later export")
        store.commit(next, complete = true, pages = 1)
        assertEquals(setOf(retained.id, next.id), store.list().map { it.id }.toSet())
        assertEquals("retained chapter", retained.file.readText())
    }
}
