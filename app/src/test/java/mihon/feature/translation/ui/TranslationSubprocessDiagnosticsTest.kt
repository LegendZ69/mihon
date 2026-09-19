package mihon.feature.translation.ui

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationLogQuery
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class TranslationSubprocessDiagnosticsTest {
    @Test
    fun `sibling exports keep frozen identities after progress and chapter selection`() = runTest {
        val first = operation("ocr")
        val second = operation("request")
        var selection = TranslationQueueSelection(jobIds = setOf("unrelated-job")).toggleOperation(first)
        assertTrue(selection.jobIds.isEmpty())
        assertEquals(setOf(first.id), selection.operations.keys)
        selection = selection.toggleOperation(second)
        assertEquals(2, selection.count)
        val finished = first.copy(state = TranslationOperationState.COMPLETED, completed = 1)
        selection = selection.refreshOperations(listOf(second, finished))
        val frozen = selection.exportSnapshot(1000)
        assertEquals(setOf(first.id), selection.toggleOperation(second).operations.keys)
        selection = selection.selectJobs(setOf("another-job"))
        assertTrue(selection.operations.isEmpty())
        val repository = mockk<TranslationRepository>()
        coEvery { repository.eventPage(any()) } returns emptyList()
        val output = ByteArrayOutputStream()
        exportSelectedSubprocessDiagnostics(repository, frozen, output, false) { _, _ -> error("No capture export") }
        val records = records(output.toByteArray()).filter {
            type(it) == "operation"
        }.map { it.getValue("record").jsonObject }
        assertEquals(listOf("ocr", "request"), records.map { it.getValue("id").jsonPrimitive.content })
        assertEquals("COMPLETED", records.first().getValue("state").jsonPrimitive.content)
    }

    @Test
    fun `selected operation diagnostics paginate scoped events and deduplicate shared descendants`() = runTest {
        val queries = mutableListOf<TranslationLogQuery>()
        val repository = mockk<TranslationRepository>()
        coEvery { repository.eventPage(any()) } coAnswers {
            val query = firstArg<TranslationLogQuery>().also { queries += it }
            when (query.operationId to query.offset) {
                "parent" to 0L -> (0 until 500).map { event("event-$it") }
                "parent" to 500L -> listOf(event("event-500"))
                "child" to 0L -> listOf(event("event-500"), event("event-501"))
                else -> error("Unexpected export scope: $query")
            }
        }
        val frozen = TranslationQueueSelection().toggleOperation(operation("parent"))
            .toggleOperation(operation("child")).exportSnapshot(1234)
        val output = ByteArrayOutputStream()
        exportSelectedSubprocessDiagnostics(repository, frozen, output, false) { _, _ -> error("No capture export") }
        val records = records(output.toByteArray())
        assertEquals(502, records.count { type(it) == "event" })
        assertEquals(2, records.count { type(it) == "operation" })
        assertTrue(queries.all { it.jobId == "chapter" && it.until == 1235L && it.limit == 500L })
        assertEquals(listOf(0L, 500L, 0L), queries.map { it.offset })
    }

    @Test
    fun `capture ZIP includes operation evidence and only correlated sanitized captures`() = runTest {
        val repository = mockk<TranslationRepository>()
        coEvery { repository.eventPage(any()) } returns listOf(
            event("request").copy(
                capturePath = "/private/diagnostics/chosen-capture",
                details = mapOf("Authorization" to "Bearer sensitive-fixture-secret"),
            ),
        )
        val chosen = operation("request").copy(
            message = """{"geometryCorrection":{"candidate":"private resumable input"},"message":"Rejected"}""",
        )
        val output = ByteArrayOutputStream()
        exportSelectedSubprocessDiagnostics(
            repository,
            SelectedSubprocessDiagnostics(listOf(chosen), 5000),
            output,
            true,
        ) { ids, captureOutput ->
            assertEquals(listOf("chosen-capture"), ids)
            ZipOutputStream(captureOutput).use { zip ->
                zip.putNextEntry(ZipEntry("chosen-capture/metadata.json"))
                zip.write("""{"capturePolicy":"sanitized-v1"}""".toByteArray())
                zip.closeEntry()
            }
        }
        val entries = linkedMapOf<String, ByteArray>()
        ZipInputStream(output.toByteArray().inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("operations-and-logs.jsonl", "sanitized-api-captures.zip"), entries.keys)
        val text = entries.getValue("operations-and-logs.jsonl").decodeToString()
        assertFalse(text.contains("sensitive-fixture-secret"))
        assertFalse(text.contains("private resumable input"))
        assertEquals(1, records(text.toByteArray()).count { type(it) == "operation" })
        assertTrue(chosen.message!!.contains("private resumable input"), "Export must not mutate saved evidence")
    }

    @Test
    fun `cancelled export never fetches the next page or exports captures`() = runTest {
        val repository = mockk<TranslationRepository>()
        var calls = 0
        coEvery { repository.eventPage(any()) } coAnswers {
            calls++
            currentCoroutineContext().cancel()
            (0 until 500).map { event("event-$it") }
        }
        val export = async {
            exportSelectedSubprocessDiagnostics(
                repository,
                SelectedSubprocessDiagnostics(listOf(operation("parent")), 1000),
                ByteArrayOutputStream(),
                true,
            ) { _, _ -> error("Cancelled export must not capture") }
        }
        val error = runCatching { export.await() }.exceptionOrNull()
        assertTrue(error is CancellationException)
        assertEquals(1, calls)
    }

    private fun operation(id: String) = TranslationOperation(id, "chapter", TranslationStage.REQUEST)
    private fun event(id: String) = TranslationEvent(id, "chapter", stage = "REQUEST", message = "Fixture")
    private fun records(bytes: ByteArray) = bytes.decodeToString().lineSequence().filter { it.isNotBlank() }
        .map { Json.parseToJsonElement(it).jsonObject }.toList()
    private fun type(record: JsonObject) = record.getValue("recordType").jsonPrimitive.content
}
