package mihon.feature.translation.provider

import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File

class GroqProviderCompatibilityTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `Groq text translation uses supported string input for both API dialects`() {
        assertAll(
            *OpenAiDialect.entries.map { dialect ->
                Executable {
                    runBlocking {
                        var calls = 0
                        val provider = gateway { outgoing ->
                            calls++
                            val body = outgoing.bodyJson()
                            val content = if (dialect == OpenAiDialect.RESPONSES) {
                                body["input"]
                            } else {
                                body.getValue("messages").jsonArray.last().jsonObject["content"]
                            }
                            assertTrue(
                                content is JsonPrimitive && content.isString,
                                "$dialect must send text as a string",
                            )
                            assertTrue(content!!.jsonPrimitive.content.contains("こんにちは"))
                            assertFalse(body.containsKey("store"), "Groq does not support store in either dialect")
                            assertFalse(body.toString().contains("image_url"))
                            assertEquals(
                                if (dialect ==
                                    OpenAiDialect.RESPONSES
                                ) {
                                    "/openai/v1/responses"
                                } else {
                                    "/openai/v1/chat/completions"
                                },
                                outgoing.url.encodedPath,
                            )
                            response(dialect)
                        }
                        val requested = request(dialect)
                        val page = provider.translate(requested).pages.single()
                        assertEquals("Hello", page.regions.single().translatedText)
                        assertEquals(requested.ocr.single(), page.rawOcr)
                        assertEquals(requested.ocr.single().regions.single().points, page.regions.single().points)
                        assertEquals(1, calls)
                    }
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun `Groq rejects visual translation and visual review before credentials or image preparation`() {
        for (pipeline in listOf(OcrPipeline.AI, OcrPipeline.PADDLE_AI)) {
            val base = request(OpenAiDialect.CHAT_COMPLETIONS)
            val configured = base.copy(settings = base.settings.copy(ocr = base.settings.ocr.copy(pipeline = pipeline)))
            val provider = gateway { error("Unsupported visual request must never reach transport") }
            val baseline = TranslationPageResult("page-1", "hash", 100, 100, base.ocr.single().regions)
            assertAll(
                {
                    val failure =
                        assertThrows(TranslationException::class.java) {
                            runBlocking { provider.translate(configured) }
                        }
                    assertEquals(TranslationFailureKind.CONFIGURATION, failure.kind)
                    assertTrue(failure.message!!.contains("PaddleOCR"))
                },
                {
                    val failure = assertThrows(TranslationException::class.java) {
                        runBlocking {
                            provider.review(
                                QualityReviewRequest(
                                    "job",
                                    "review",
                                    configured.settings,
                                    base.images.single(),
                                    baseline,
                                ),
                            )
                        }
                    }
                    assertEquals(TranslationFailureKind.CONFIGURATION, failure.kind)
                },
            )
        }
    }

    @Test
    fun `Groq rejects unsupported advanced settings without sending a request`() {
        val invalid = listOf(
            OpenAiDialect.RESPONSES to """{"verbosity":"high"}""",
            OpenAiDialect.RESPONSES to """{"prompt_cache_key":"cache"}""",
            OpenAiDialect.RESPONSES to """{"reasoning":{"effort":"xhigh"}}""",
            OpenAiDialect.CHAT_COMPLETIONS to """{"reasoning_effort":"none"}""",
            OpenAiDialect.CHAT_COMPLETIONS to """{"frequency_penalty":1}""",
            OpenAiDialect.CHAT_COMPLETIONS to """{"service_tier":"priority"}""",
        )
        assertAll(
            *invalid.map { (dialect, advanced) ->
                Executable {
                    var calls = 0
                    val provider = gateway {
                        calls++
                        response(dialect)
                    }
                    val base = request(dialect)
                    val failure = assertThrows(TranslationException::class.java) {
                        runBlocking {
                            provider.translate(
                                base.copy(
                                    settings = base.settings.copy(
                                        provider = base.settings.provider.copy(advancedJson = advanced),
                                    ),
                                ),
                            )
                        }
                    }
                    assertEquals(TranslationFailureKind.CONFIGURATION, failure.kind)
                    assertEquals(0, calls)
                }
            }.toTypedArray(),
        )
    }

    @Test
    fun `Groq Paddle review remains text only and retains every measured OCR region`() = runBlocking {
        for (dialect in OpenAiDialect.entries) {
            val base = request(dialect)
            val baseline = TranslationPageResult(
                "page-1",
                "hash",
                100,
                100,
                base.ocr.single().regions.map { it.copy(translatedText = "Hello") },
                rawOcr = base.ocr.single(),
            )
            var sent: JsonObject? = null
            val provider = gateway {
                sent = it.bodyJson()
                response(dialect, review = true)
            }
            val result = provider.review(
                QualityReviewRequest("job", "review", base.settings, base.images.single(), baseline),
            )
            assertFalse(result.visualComplete)
            assertFalse(checkNotNull(sent).toString().contains("image_url"))
            assertFalse(checkNotNull(sent).toString().contains("base64"))
            assertEquals(baseline.rawOcr, result.candidate.rawOcr)
            assertEquals(baseline.regions.single().points, result.candidate.regions.single().points)
            assertEquals(0.92f, result.candidate.regions.single().detectionConfidence)
            assertEquals(0.87f, result.candidate.regions.single().recognitionConfidence)
        }
    }

    @Test
    fun `Groq connection diagnostic probes text and schema without image access`() = runBlocking {
        for (dialect in OpenAiDialect.entries) {
            var sent: JsonObject? = null
            val provider = gateway {
                sent = it.bodyJson()
                response(dialect, probe = true)
            }
            val message = provider.testConnection(request(dialect).settings.provider)
            assertTrue(message.contains("accepted text input"))
            assertFalse(checkNotNull(sent).toString().contains("image_url"))
            assertFalse(File(directory, "temporary").walkTopDown().any { it.name.startsWith("vision-probe") })
        }
    }

    @Test
    fun `billing administration references cannot enter inference translation review counting or diagnostics`() {
        var credentialReads = 0
        var requests = 0
        val dialect = OpenAiDialect.CHAT_COMPLETIONS
        val base = request(dialect).let {
            it.copy(settings = it.settings.copy(provider = it.settings.provider.copy(credentialId = "billing-admin")))
        }
        val baseline = TranslationPageResult("page-1", "hash", 100, 100, base.ocr.single().regions)
        val provider = gateway(onCredential = { credentialReads++ }) {
            requests++
            response(dialect)
        }
        val actions: List<suspend () -> Unit> = listOf(
            {
                provider.translate(base)
                Unit
            },
            {
                provider.review(QualityReviewRequest("job", "review", base.settings, base.images.single(), baseline))
                Unit
            },
            {
                provider.countTokens(base)
                Unit
            },
            {
                provider.testConnection(base.settings.provider)
                Unit
            },
        )
        assertAll(
            *actions.map { action ->
                Executable {
                    val failure = assertThrows(TranslationException::class.java) { runBlocking { action() } }
                    assertEquals(TranslationFailureKind.CONFIGURATION, failure.kind)
                    assertTrue(failure.message!!.contains("billing", ignoreCase = true))
                    assertEquals(0, credentialReads)
                    assertEquals(0, requests)
                }
            }.toTypedArray(),
        )
    }

    private fun request(dialect: OpenAiDialect): TranslationRequest {
        val source = File(directory, "missing-original.png")
        val region = TextRegion(
            "region-1",
            listOf(
                TranslationPoint(20f, 10f),
                TranslationPoint(80f, 10f),
                TranslationPoint(80f, 40f),
                TranslationPoint(20f, 40f),
            ),
            "こんにちは",
            detectionConfidence = 0.92f,
            recognitionConfidence = 0.87f,
        )
        return TranslationRequest(
            "job",
            "batch",
            TranslationSettings().let {
                it.copy(
                    provider = ProviderSettings(
                        kind = TranslationProviderKind.OPENAI,
                        baseUrl = "https://api.groq.com/openai/v1",
                        model = "openai/gpt-oss-120b",
                        dialect = dialect,
                        totalAttempts = 1,
                    ),
                    ocr = it.ocr.copy(pipeline = OcrPipeline.PADDLE),
                )
            },
            listOf(TranslationImage("page-1", 0, source.path, "image/png", 100, 100, "hash", 3)),
            listOf(OcrPageResult("page-1", listOf(region))),
        )
    }

    private fun gateway(
        onCredential: () -> Unit = {
        },
        handler: (okhttp3.Request) -> String,
    ) = TranslationProviderGateway(
        {
            onCredential()
            CredentialParser.apiKey("synthetic-groq-key", "Test", "default")
        },
        mockk<TranslationRepository>(relaxed = true),
        TranslationDiagnosticsStore(File(directory, "captures")),
        OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Test")
                .body(handler(chain.request()).toResponseBody()).build()
        }.build(),
        File(directory, "temporary"),
    )

    private fun okhttp3.Request.bodyJson(): JsonObject = TranslationWireFormat.json.parseToJsonElement(
        Buffer().also { body!!.writeTo(it) }.readUtf8(),
    ).jsonObject

    private fun response(dialect: OpenAiDialect, review: Boolean = false, probe: Boolean = false): String {
        val page = if (probe) {
            """{"imageId":"text-probe","detectedLanguage":"unknown","regions":[]}"""
        } else {
            """{"imageId":"page-1","detectedLanguage":"ja","regions":[{"id":"region-1","sourceText":"こんにちは","correctedText":null,"translatedText":"Hello","box2d":[100,200,400,800],"type":"dialogue","readingOrder":0,"rotation":0,"included":true,"ignoredReason":null,"aiConfidence":null}]}"""
        }
        val text = JsonPrimitive(
            if (review) """{"pages":[$page],"findings":[],"visualComplete":false}""" else """{"pages":[$page]}""",
        )
        return if (dialect == OpenAiDialect.RESPONSES) {
            """{"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":$text}]}]}"""
        } else {
            """{"choices":[{"finish_reason":"stop","message":{"content":$text}}]}"""
        }
    }
}
