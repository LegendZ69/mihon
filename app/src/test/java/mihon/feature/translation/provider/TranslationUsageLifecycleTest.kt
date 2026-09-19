package mihon.feature.translation.provider

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationLogSettings
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationUsageRecord
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File

class TranslationUsageLifecycleTest {
    @TempDir lateinit var directory: File

    @Test
    fun `disabled verbose logging still records and settles one distinct provider attempt`() = runBlocking {
        val records = linkedMapOf<String, TranslationUsageRecord>()
        val repository = repository(records)
        val gateway =
            gateway(
                repository,
                """, "usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":4,"thoughtsTokenCount":2}""",
            )
        gateway.translate(request())
        val record = records.values.single()
        assertEquals(10L, record.usage.inputTokens)
        assertEquals(4L, record.usage.outputTokens)
        assertFalse(record.outcomeUncertain)
        assertNull(record.reservedAmount)
        assertNotNull(record.estimatedUsd)
        assertEquals("provider-request", record.requestId)
    }

    @Test
    fun `missing provider usage preserves the maximum reservation as uncertain`() = runBlocking {
        val records = linkedMapOf<String, TranslationUsageRecord>()
        gateway(repository(records), "").translate(request())
        val record = records.values.single()
        assertTrue(record.outcomeUncertain)
        assertNotNull(record.reservedAmount)
        assertEquals("USD", record.reservedCurrency)
        assertEquals(OfficialProviderPricing.SOURCE_URL, record.pricingSource)
        assertEquals(OfficialProviderPricing.VERIFIED_AT, record.pricingVerifiedAt)
        assertNull(record.estimatedUsd)
        assertNull(record.usage.inputTokens)
    }

    @Test
    fun `missing provider usage retains the exchange rate recorded with its reservation`() = runBlocking {
        val records = linkedMapOf<String, TranslationUsageRecord>()
        val repository = repository(records)
        var reservation: TranslationUsageRecord? = null
        coEvery { repository.usage(any()) } coAnswers {
            records[firstArg<String>()]?.copy(
                exchangeRate = "1.267857142858",
                exchangeRateSource = "https://www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml",
            ).also { reservation = it }
        }

        gateway(repository, "").translate(request())

        val before = requireNotNull(reservation)
        val after = records.values.single()
        assertTrue(after.outcomeUncertain)
        assertNotNull(before.reservedAmount)
        assertEquals(before.id, after.id)
        assertEquals(before.time, after.time)
        assertEquals(before.reservedAmount, after.reservedAmount)
        assertEquals(before.reservedCurrency, after.reservedCurrency)
        assertEquals(before.pricingSource, after.pricingSource)
        assertEquals(before.pricingVerifiedAt, after.pricingVerifiedAt)
        assertEquals(before.exchangeRate, after.exchangeRate)
        assertEquals(before.exchangeRateSource, after.exchangeRateSource)
        assertNull(after.estimatedUsd)
    }

    @Test
    fun `failed operation persistence still removes the temporary unsanitized response`() {
        val repository = mockk<TranslationRepository>(relaxed = true)
        coEvery { repository.saveOperation(any()) } coAnswers {
            if (firstArg<TranslationOperation>().state ==
                TranslationOperationState.COMPLETED
            ) {
                error("Synthetic database write failure")
            }
        }
        val temporary = File(directory, "response-cache")
        val transport = TranslationHttpTransport(
            client("""{"secret":"temporary-body-marker"}"""),
            TranslationDiagnosticsStore(File(directory, "captures")),
            repository,
            temporary,
        )
        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                transport.execute(
                    Request.Builder().url("https://aiplatform.googleapis.com/test").post("{}".toRequestBody()).build(),
                    "job",
                    "batch",
                    "generate",
                    TranslationLogSettings(enabled = false),
                    1,
                    30,
                    emptyList(),
                )
            }
        }
        assertTrue(
            temporary.listFiles().orEmpty().isEmpty(),
            "Temporary provider response survived the failed accounting write",
        )
    }

    @Test
    fun `HTTP error summaries omit short image bodies and thought signatures before queue persistence`() {
        val response =
            """{"error":{"message":"Invalid image detail","image_url":{"url":"data:image/png;base64,AAAA"},""" +
                """"inlineData":{"data":"tiny-image-marker"},"thoughtSignature":"short-signature-marker"}}"""
        val transport =
            TranslationHttpTransport(
                client(response, 400),
                TranslationDiagnosticsStore(File(directory, "error-captures")),
                mockk<TranslationRepository>(relaxed = true),
                File(directory, "error-cache"),
            )
        val error = assertThrows(TranslationException::class.java) {
            runBlocking {
                transport.execute(
                    Request.Builder().url("https://aiplatform.googleapis.com/test").post("{}".toRequestBody()).build(),
                    "job",
                    "batch",
                    "generate",
                    TranslationLogSettings(enabled = false),
                    1,
                    30,
                    emptyList(),
                )
            }
        }
        assertTrue(error.message.orEmpty().contains("Invalid image detail"))
        assertFalse(error.message.orEmpty().contains("tiny-image-marker"))
        assertFalse(error.message.orEmpty().contains("short-signature-marker"))
    }

    private fun repository(records: MutableMap<String, TranslationUsageRecord>): TranslationRepository {
        val repository = mockk<TranslationRepository>(relaxed = true)
        coEvery { repository.saveUsage(any()) } coAnswers
            { firstArg<TranslationUsageRecord>().let { records[it.id] = it } }
        coEvery { repository.usage(any()) } coAnswers { records[firstArg<String>()] }
        return repository
    }

    private fun gateway(repository: TranslationRepository, usage: String): TranslationProviderGateway {
        val page = """{"pages":[{"imageId":"0","regions":[]}]}"""
        val response = """{"candidates":[{"finishReason":"STOP","content":{"parts":[{"text":${JsonPrimitive(
            page,
        )}}]}}]$usage}"""
        return TranslationProviderGateway(
            { CredentialParser.apiKey("synthetic-key", "Test", "default") },
            repository,
            TranslationDiagnosticsStore(File(directory, "captures")),
            client(response),
            File(directory, "temporary"),
        )
    }

    private fun client(body: String, code: Int = 200) = OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Test")
            .header("x-request-id", "provider-request").body(body.toResponseBody()).build()
    }.build()

    private fun request() = TranslationRequest(
        "job",
        "batch",
        TranslationSettings(
            provider = ProviderSettings(kind = TranslationProviderKind.VERTEX_EXPRESS, totalAttempts = 1),
            ocr = OcrSettings(pipeline = OcrPipeline.PADDLE),
            logs = TranslationLogSettings(enabled = false),
        ),
        listOf(TranslationImage("0", 0, "/missing-original-is-not-read", "image/png", 100, 100, "hash", 2)),
        listOf(OcrPageResult("0", emptyList())),
    )
}
