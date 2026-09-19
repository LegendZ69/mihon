package mihon.feature.translation.provider

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationUsage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.UUID
import java.util.zip.ZipInputStream

class TranslationDiagnosticsPricingTest {
    @TempDir lateinit var directory: File

    @Test
    fun `capture survives process death as visibly interrupted with exportable metadata`() = runBlocking {
        val store = TranslationDiagnosticsStore(directory)
        val capture = store.start("job", "batch", "generateContent")
        assertTrue(File(capture.directory, "metadata.json").isFile)
        capture.requestFile.writeText("{\"partial\":true}")
        assertEquals("RUNNING", store.list().single().state)
        assertThrows(IllegalStateException::class.java) { runBlocking { store.delete(capture.metadata.id) } }
        val restarted = TranslationDiagnosticsStore(directory)
        val recovered = restarted.list().single()
        assertEquals("INTERRUPTED", recovered.state)
        assertEquals("job", recovered.jobId)
        assertNull(recovered.completedAt)
        assertTrue(recovered.error!!.contains("interrupted"))
        val output = ByteArrayOutputStream()
        restarted.export(listOf(recovered.id), output)
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            assertTrue(zip.nextEntry.name.endsWith("metadata.json"))
            assertTrue(zip.readBytes().decodeToString().contains("INTERRUPTED"))
            assertTrue(zip.nextEntry.name.endsWith("request.json"))
            assertEquals("{\"partial\":true}", zip.readBytes().decodeToString())
        }
    }

    @Test
    fun `metadata-less orphan is listed and completed or cancelled captures remain distinct`() = runBlocking {
        val orphan = File(directory, UUID.randomUUID().toString()).apply { mkdirs() }
        File(orphan, "request.json").writeText("partial")
        val store = TranslationDiagnosticsStore(directory)
        assertEquals("INTERRUPTED", store.list().single().state)
        val complete = store.start("complete", null, "generateContent")
        store.finish(complete, complete.metadata.copy(completedAt = 123, responseCode = 200))
        val cancel = store.start("cancel", null, "generateContent")
        store.finish(cancel, cancel.metadata.copy(completedAt = 124, error = "Cancelled"))
        val recovered = TranslationDiagnosticsStore(directory).list().associateBy { it.jobId }
        assertEquals("COMPLETED", recovered.getValue("complete").state)
        assertEquals("CANCELLED", recovered.getValue("cancel").state)
    }

    @Test
    fun `pricing applies cached input and reasoning once with dated promotional cutoff`() {
        val settings = ProviderSettings()
        val usage = TranslationUsage(1000, 30, 200, 10)
        val promo = OfficialProviderPricing.estimate(settings, usage, LocalDate.of(2026, 12, 31))!!
        assertEquals("0.000765", promo.estimatedUsd)
        assertEquals("0.00153", promo.beforePromotionalCreditUsd)
        assertTrue(promo.price.promotionalCredit)
        assertEquals("2026-12-31", promo.price.effectiveThrough)
        val standard = OfficialProviderPricing.estimate(settings, usage, LocalDate.of(2027, 1, 1))!!
        assertEquals("0.00153", standard.estimatedUsd)
        assertFalse(standard.price.promotionalCredit)
        assertEquals("2027-01-01", standard.price.effectiveFrom)
        assertEquals(
            "1.485",
            OfficialProviderPricing.price(
                settings.copy(priorityPaygo = true, location = "us"),
                LocalDate.of(2026, 9, 5),
            )!!.inputPerMillionUsd,
        )
    }

    @Test
    fun `unverified models missing usage and provisioned traffic have no fabricated cost`() {
        val settings = ProviderSettings()
        val date = LocalDate.of(2026, 9, 5)
        assertNull(OfficialProviderPricing.estimate(settings, TranslationUsage(), date))
        assertNull(OfficialProviderPricing.price(settings.copy(model = "custom-model"), date))
        assertNull(OfficialProviderPricing.price(settings.copy(kind = TranslationProviderKind.OPENAI), date))
        assertNull(OfficialProviderPricing.price(settings.copy(provisionedThroughput = true), date))
        assertNull(
            OfficialProviderPricing.estimate(
                settings,
                TranslationUsage(10, 10, trafficType = "PROVISIONED_THROUGHPUT"),
                date,
            ),
        )
    }

    @Test
    fun `usage remains parseable for invalid translation content and malformed accounting is unknown`() {
        val response = TranslationWireFormat.json.parseToJsonElement(
            """
                {"candidates":[],"usageMetadata":{"promptTokenCount":123,"candidatesTokenCount":45,
                "thoughtsTokenCount":6}}
            """.trimIndent(),
        ).jsonObject
        assertEquals(
            TranslationUsage(123, 45, reasoningTokens = 6),
            TranslationWireFormat.decodeUsage(response, ProviderSettings()),
        )
        val malformed = TranslationWireFormat.json.parseToJsonElement(
            """{"usageMetadata":{"promptTokenCount":{},"candidatesTokenCount":-1}}""",
        ).jsonObject
        assertEquals(TranslationUsage(), TranslationWireFormat.decodeUsage(malformed, ProviderSettings()))
    }
}
