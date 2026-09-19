package mihon.feature.translation.accounting

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.translation.model.TranslationBillingReport
import tachiyomi.domain.translation.model.TranslationBillingSnapshot
import tachiyomi.domain.translation.model.TranslationBillingSource
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.io.IOException

class TranslationBillingStorageTest {
    @TempDir lateinit var directory: File

    @Test
    fun `interrupted SQL update replays its durable report without another request or double counting`() = runBlocking {
        val file = File(directory, "state.json")
        val storage = TranslationBillingStorage(file)
        val snapshots = (1..2).map { day ->
            TranslationBillingSnapshot(
                "day-$day", "account", TranslationBillingSource.GOOGLE_BIGQUERY,
                "project", day.toLong(), day + 1L, 100, "USD", "0.1", sourceUrl = "https://cloud.google.com",
            )
        }
        val report = TranslationBillingReport("account", snapshots, syncedAt = 100, periodStart = 1, periodEnd = 3)
        storage.write(BillingLocalState())
        val rows = mutableMapOf<String, TranslationBillingSnapshot>()
        val repository = mockk<TranslationRepository>(relaxed = true)
        var writes = 0
        coEvery { repository.saveBilling(any()) } coAnswers {
            if (++writes == 2) throw IOException("Synthetic interrupted SQL commit")
            firstArg<TranslationBillingSnapshot>().let { rows[it.id] = it }
        }
        assertThrows(IOException::class.java) {
            runBlocking { storage.commitReport(storage.read(), report, repository) }
        }
        assertTrue(storage.read().reports.isEmpty(), "An incomplete SQL update is not exposed as a completed report")
        assertEquals(
            report,
            storage.read().pendingReports["account"],
            "Recovery must retain the fetched report before touching SQL",
        )
        val recovered = TranslationBillingStorage(file).recover(repository)
        assertEquals(report, recovered.reports["account"])
        assertTrue(recovered.pendingReports.isEmpty())
        assertEquals(2, rows.size)
        assertEquals("0.2", rows.values.sumOf { it.amount.toBigDecimal() }.toPlainString())
        assertEquals(recovered, TranslationBillingStorage(file).read())
    }

    @Test
    fun `interrupted accounting deletion resumes locally and preserves other connections`() = runBlocking {
        val file = File(directory, "delete.json")
        val storage = TranslationBillingStorage(file)
        fun report(id: String) = TranslationBillingReport(id, emptyList(), syncedAt = 1, periodStart = 1, periodEnd = 2)
        val keep = report("keep")
        storage.write(BillingLocalState(reports = mapOf("remove" to report("remove"), "keep" to keep)))
        val repository = mockk<TranslationRepository>(relaxed = true)
        var interrupted = true
        coEvery { repository.deleteBilling("remove") } coAnswers {
            if (interrupted) {
                interrupted = false
                throw IOException("Synthetic interruption after deletion")
            }
        }
        assertThrows(IOException::class.java) {
            runBlocking { storage.deleteReport(storage.read(), "remove", repository) }
        }
        assertEquals(setOf("remove"), storage.read().pendingDeletes)
        val recovered = TranslationBillingStorage(file).recover(repository)
        assertEquals(mapOf("keep" to keep), recovered.reports)
        assertTrue(recovered.pendingDeletes.isEmpty())
    }
}
