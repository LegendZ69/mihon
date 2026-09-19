package mihon.feature.translation.provider

import android.util.Log
import androidx.exifinterface.media.ExifInterface
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.QualityReviewCoverage
import tachiyomi.domain.translation.model.QualityReviewPresentation
import tachiyomi.domain.translation.model.QualityReviewRenderEvidence
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.QualityReviewValidation
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QualityReviewProviderTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `content policy review preserves saved SFX when provider blanks or relabels it`() = runBlocking {
        for (pipeline in listOf(OcrPipeline.AI, OcrPipeline.PADDLE)) {
            for (returnedType in listOf("sound_effect", "unknown")) {
                val base = request()
                val original = base.baseline.regions.single().copy(
                    type = "sound_effect",
                    translatedText = "Saved manual THUD",
                    correctedText = "Saved corrected transcription",
                    detectionConfidence = 0.91f,
                    style = OverlayStyle(fontSize = 23f),
                )
                val rawOcr = OcrPageResult(base.image.id, listOf(original.copy(translatedText = "")))
                val requested = base.copy(
                    settings = base.settings.copy(ocr = base.settings.ocr.copy(pipeline = pipeline)),
                    baseline = base.baseline.copy(regions = listOf(original), rawOcr = rawOcr),
                )
                val page = payload().getValue("pages").jsonArray.single().jsonObject
                val proposed = JsonObject(
                    page.getValue("regions").jsonArray.single().jsonObject + mapOf(
                        "type" to JsonPrimitive(returnedType),
                        "translatedText" to JsonPrimitive(""),
                        "correctedText" to JsonNull,
                        "polygon" to JsonNull,
                        "rotation" to JsonPrimitive(original.rotation),
                    ),
                )
                val response = JsonObject(
                    payload() + (
                        "pages" to JsonArray(listOf(JsonObject(page + ("regions" to JsonArray(listOf(proposed))))))
                        ),
                )
                val reviewed = gateway { 200 to completion(response) }.review(requested).candidate
                val actual = reviewed.regions.single()
                assertFalse(actual.included)
                assertTrue(actual.ignoredReason?.isNotBlank() == true)
                assertEquals(
                    original,
                    actual.copy(included = original.included, ignoredReason = original.ignoredReason),
                )
                assertEquals(rawOcr, reviewed.rawOcr)
                assertEquals(original, requested.baseline.regions.single(), "Saved baseline must remain immutable")
            }
        }
    }

    @Test
    fun `content policy visual review explains intentionally unmasked sound effects`() {
        for (ignore in listOf(true, false)) {
            val base = renderedRequest()
            val requested = base.copy(
                settings = base.settings.copy(contentPolicy = TranslationContentPolicy(ignore)),
            )
            val body = TranslationWireFormat.json.parseToJsonElement(
                Buffer().also { QualityReviewWireFormat.requestBody(requested).writeTo(it) }.readUtf8(),
            ).jsonObject
            val instructions = body.getValue("messages").jsonArray.first().jsonObject.getValue("content")
                .jsonPrimitive.content
            assertEquals(ignore, instructions.contains("Unmasked ignored sound effects are intentional"))
            if (ignore) {
                assertTrue(instructions.contains("not omissions or exposed-source-lettering defects"))
                assertTrue(instructions.contains("Preserve their saved translations"))
            }
        }
    }

    @Test
    fun `content policy review opt out permits translated sound effects`() = runBlocking {
        val base = request()
        val requested = base.copy(settings = base.settings.copy(contentPolicy = TranslationContentPolicy.Legacy))
        val page = payload().getValue("pages").jsonArray.single().jsonObject
        val region = JsonObject(
            page.getValue("regions").jsonArray.single().jsonObject + ("type" to JsonPrimitive("sound_effect")),
        )
        val response = JsonObject(
            payload() + ("pages" to JsonArray(listOf(JsonObject(page + ("regions" to JsonArray(listOf(region))))))),
        )
        val translated = gateway { 200 to completion(response) }.review(requested).candidate.regions.single()
        assertTrue(translated.included)
        assertEquals("Wait!", translated.translatedText)
    }

    @Test
    fun `rendered review sends two indexed images but requests one target page`() = runBlocking<Unit> {
        val base = renderedRequest()
        val request = base.copy(
            settings = base.settings.copy(
                provider = base.settings.provider.copy(kind = TranslationProviderKind.VERTEX_EXPRESS),
                logs = base.settings.logs.copy(captureRaw = true),
            ),
        )
        val evidence = checkNotNull(request.renderEvidence)
        val originals = listOf(evidence.original, evidence.preview).map { File(it.filePath).readBytes() }
        val bodies = mutableListOf<JsonObject>()
        val gateway = gateway { body ->
            bodies += TranslationWireFormat.json.parseToJsonElement(body).jsonObject
            if (bodies.size == 1) {
                // Chapter and evidence files can change after the count response. Generation must keep its snapshot.
                File(evidence.original.filePath).writeBytes(byteArrayOf(41, 42, 43, 44, 45))
                File(evidence.preview.filePath).writeBytes(byteArrayOf(51, 52, 53, 54, 55))
                200 to "{\"totalTokens\":123}"
            } else {
                200 to vertexCompletion(payload())
            }
        }
        assertEquals(request.image.id, gateway.review(request).candidate.imageId)
        assertEquals(2, bodies.size)
        val inputs = bodies.map { it.getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray }
        assertEquals(inputs.first(), inputs.last(), "Count and generation must use identical indexed evidence")
        val parts = inputs.first()
        val attachments = parts.mapNotNull { it.jsonObject["inlineData"]?.jsonObject }
        assertEquals(2, attachments.size, "Visual review must include original plus rendered preview")
        assertEquals(
            originals.map {
                Base64.getEncoder().encodeToString(it)
            },
            attachments.map { it.getValue("data").jsonPrimitive.content },
        )
        assertTrue(parts[0].jsonObject.getValue("text").jsonPrimitive.content.contains("image 1", ignoreCase = true))
        assertTrue(parts[2].jsonObject.getValue("text").jsonPrimitive.content.contains("image 2", ignoreCase = true))
        assertTrue(parts[2].jsonObject.getValue("text").jsonPrimitive.content.contains("not authoritative source text"))
        assertTrue(parts[2].jsonObject.getValue("text").jsonPrimitive.content.contains("scaleX=0.5"))
        val pages = bodies.last().getValue("generationConfig").jsonObject.getValue("responseJsonSchema").jsonObject
            .getValue("properties").jsonObject.getValue("pages").jsonObject
        assertEquals("1", pages.getValue("minItems").jsonPrimitive.content)
        assertEquals("1", pages.getValue("maxItems").jsonPrimitive.content)
        assertEquals(
            JsonArray(listOf(JsonPrimitive("page"))),
            pages.getValue("items").jsonObject
                .getValue("properties").jsonObject.getValue("imageId").jsonObject.getValue("enum"),
        )
        val captures = File(directory, "captures").walkTopDown().filter { it.name == "request.json" }.toList()
        assertEquals(2, captures.size)
        assertTrue(
            captures.all { file ->
                originals.none { file.readText().contains(Base64.getEncoder().encodeToString(it)) } &&
                    file.readText().contains("_omitted")
            },
        )
        assertTrue(captures.none { it.readText().contains("synthetic-only") })
        assertFalse(File(directory, "temporary").walkTopDown().any { it.name.startsWith("review-") })
    }

    @Test
    fun `required rendered preview is never silently omitted`() {
        val base = request()
        val request = base.copy(
            settings = base.settings.copy(
                qualityReview = base.settings.qualityReview.copy(includeRenderedPreview = true),
            ),
        )
        var calls = 0
        val gateway = gateway {
            calls++
            200 to completion(payload())
        }
        val error = assertThrows(TranslationException::class.java) { runBlocking { gateway.review(request) } }
        assertEquals(TranslationFailureKind.LIMIT, error.kind)
        assertEquals(0, calls)
    }

    @Test
    fun `both OpenAI dialects label the preview as auxiliary input`() {
        for (dialect in OpenAiDialect.entries) {
            val base = renderedRequest()
            val request = base.copy(
                settings = base.settings.copy(provider = base.settings.provider.copy(dialect = dialect)),
            )
            val buffer = Buffer()
            QualityReviewWireFormat.requestBody(request).writeTo(buffer)
            val body = TranslationWireFormat.json.parseToJsonElement(buffer.readUtf8()).jsonObject
            val parts = if (dialect == OpenAiDialect.RESPONSES) {
                body.getValue("input").jsonArray.single().jsonObject.getValue("content").jsonArray
            } else {
                body.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonArray
            }
            assertEquals(
                2,
                parts.count {
                    it.jsonObject["type"]?.jsonPrimitive?.content in
                        setOf("input_image", "image_url")
                },
            )
            assertTrue(
                parts[0].jsonObject.getValue("text").jsonPrimitive.content.contains("image 1", ignoreCase = true),
            )
            assertTrue(
                parts[2].jsonObject.getValue("text").jsonPrimitive.content.contains("image 2", ignoreCase = true),
            )
            assertTrue(
                parts[2].jsonObject.getValue("text").jsonPrimitive.content.contains("not authoritative source text"),
            )
            assertEquals(listOf("page"), QualityReviewWireFormat.wireRequest(request).images.map { it.id })
        }
    }

    @Test
    fun `text only reviews ignore even unavailable render evidence`() = runBlocking<Unit> {
        for (pipeline in listOf(OcrPipeline.AI, OcrPipeline.PADDLE)) {
            val base = renderedRequest()
            val request = base.copy(
                settings = base.settings.copy(
                    ocr = base.settings.ocr.copy(pipeline = pipeline),
                    qualityReview = base.settings.qualityReview.copy(
                        coverage = if (pipeline == OcrPipeline.PADDLE) {
                            QualityReviewCoverage.FULL_PAGE_WHEN_AVAILABLE
                        } else {
                            QualityReviewCoverage.TEXT_ONLY
                        },
                    ),
                ),
            )
            File(request.image.filePath).delete()
            File(checkNotNull(request.renderEvidence).preview.filePath).delete()
            var calls = 0
            val gateway = gateway(orientation = { error("Text-only review must never inspect image files") }) { body ->
                calls++
                assertFalse(body.contains("data:image"))
                assertFalse(body.contains("input_image"))
                assertFalse(body.contains("inlineData"))
                assertFalse(body.contains("review-preview"))
                200 to completion(textPayload())
            }
            assertFalse(gateway.review(request).visualComplete)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `invalid rendered evidence cannot dispatch provider work`() {
        for (invalid in listOf("revision", "identity", "scale", "upscale", "pixels", "mime", "hash", "missing")) {
            val base = renderedRequest()
            val evidence = checkNotNull(base.renderEvidence)
            val changed = when (invalid) {
                "revision" -> evidence.copy(sourceRevision = evidence.sourceRevision + 1)
                "identity" -> evidence.copy(original = evidence.original.copy(contentHash = "f".repeat(64)))
                "scale" -> evidence.copy(scaleX = Double.NaN)
                "upscale" -> evidence.copy(preview = evidence.preview.copy(width = 400), scaleX = 2.0)
                "pixels" -> evidence.copy(preview = evidence.preview.copy(width = 4096, height = 4096))
                "mime" -> evidence.copy(preview = evidence.preview.copy(mimeType = "image/jpeg"))
                "hash" -> evidence.copy(preview = evidence.preview.copy(contentHash = "f".repeat(64)))
                else -> evidence.copy(preview = evidence.preview.copy(filePath = File(directory, "missing.png").path))
            }
            var calls = 0
            val gateway = gateway {
                calls++
                200 to completion(payload())
            }
            val error = assertThrows(TranslationException::class.java, {
                runBlocking { gateway.review(base.copy(renderEvidence = changed)) }
            }, invalid)
            assertEquals(TranslationFailureKind.LIMIT, error.kind, invalid)
            assertEquals(0, calls, invalid)
            assertFalse(File(directory, "temporary").walkTopDown().any { it.name.startsWith("review-") }, invalid)
        }
    }

    @Test
    fun `rendered review includes both attachments in inline body and token limits`() {
        for (limit in listOf("inline", "body", "tokens")) {
            val base = renderedRequest()
            var evidence = checkNotNull(base.renderEvidence)
            var provider = base.settings.provider.copy(
                kind = TranslationProviderKind.VERTEX_EXPRESS,
                model = OfficialProviderCapabilities.GEMINI_MODEL,
            )
            if (limit == "inline") {
                val preview = File(evidence.preview.filePath)
                preview.writeBytes(ByteArray(7_000_001) { 1 })
                evidence = evidence.copy(preview = evidence.preview.copy(byteSize = preview.length()))
            }
            if (limit == "body") provider = provider.copy(maxRequestBytes = 1)
            val request = base.copy(renderEvidence = evidence, settings = base.settings.copy(provider = provider))
            var calls = 0
            val gateway = gateway { body ->
                calls++
                assertEquals(
                    2,
                    TranslationWireFormat.json.parseToJsonElement(body).jsonObject
                        .getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray
                        .count { "inlineData" in it.jsonObject },
                )
                200 to "{\"totalTokens\":65537}"
            }
            val error = assertThrows(TranslationException::class.java) { runBlocking { gateway.review(request) } }
            assertEquals(TranslationFailureKind.LIMIT, error.kind, limit)
            assertEquals(if (limit == "tokens") 1 else 0, calls, limit)
            assertFalse(File(directory, "temporary").walkTopDown().any { it.name.startsWith("review-") })
        }
    }

    @Test
    fun `both review snapshots are cleaned on interruption count failure and invalid output`() {
        for (failure in listOf("preview", "count", "output")) {
            val base = renderedRequest()
            val request = base.copy(
                settings = base.settings.copy(
                    provider = base.settings.provider.copy(kind = TranslationProviderKind.VERTEX_EXPRESS),
                ),
            )
            var orientations = 0
            var calls = 0
            val gateway = gateway(orientation = {
                orientations++
                if (failure == "preview" && orientations == 2) {
                    throw kotlinx.coroutines.CancellationException("Synthetic preview interruption")
                }
                1
            }) {
                calls++
                when {
                    failure == "count" -> 503 to "{}"
                    calls == 1 -> 200 to "{\"totalTokens\":123}"
                    else -> 200 to "{}"
                }
            }
            assertThrows(Exception::class.java) { runBlocking { gateway.review(request) } }
            assertEquals(2, orientations, failure)
            assertEquals(
                when (failure) {
                    "preview" -> 0
                    "count" -> 1
                    else -> 2
                },
                calls,
                failure,
            )
            assertTrue(File(request.image.filePath).isFile)
            assertTrue(File(checkNotNull(request.renderEvidence).preview.filePath).isFile)
            assertFalse(File(directory, "temporary").walkTopDown().any { it.name.startsWith("review-") }, failure)
        }
    }

    @Test
    fun `render evidence never permits a second candidate page`() {
        val request = renderedRequest()
        val onePage = payload().getValue("pages").jsonArray.single().jsonObject
        val bad = JsonObject(
            payload() + (
                "pages" to JsonArray(
                    listOf(
                        onePage,
                        JsonObject(onePage + ("imageId" to JsonPrimitive("review-preview"))),
                    ),
                )
                ),
        )
        assertThrows(TranslationException::class.java) {
            QualityReviewWireFormat.decode(
                TranslationWireFormat.json.parseToJsonElement(completion(bad)).jsonObject,
                request,
                "extra-candidate",
            )
        }
    }

    @Test
    fun `full review sends the original once and preserves a proposed polygon`() = runBlocking<Unit> {
        val request = request()
        val bodies = mutableListOf<String>()
        val gateway = gateway { body ->
            bodies += body
            200 to completion(payload())
        }
        val result = gateway.review(request)
        assertEquals(1, bodies.size)
        assertTrue(
            bodies.single().contains(Base64.getEncoder().encodeToString(File(request.image.filePath).readBytes())),
        )
        assertEquals(
            listOf(
                TranslationPoint(40f, 10f),
                TranslationPoint(120f, 20f),
                TranslationPoint(110f, 50f),
                TranslationPoint(30f, 40f),
            ),
            result.candidate.regions.single().points,
        )
        assertEquals("Wait!", result.candidate.regions.single().translatedText)
        assertEquals("source", result.candidate.regions.single().sourceText)
        assertEquals("review-http-id", result.requestId)
        assertTrue(result.visualComplete)
    }

    @Test
    fun `review never repeats a failed generation inside one persisted attempt`() {
        for (status in listOf(401, 429, 503)) {
            var calls = 0
            val gateway = gateway {
                calls++
                status to "{}"
            }
            val error = assertThrows(TranslationException::class.java) { runBlocking { gateway.review(request()) } }
            assertEquals(status, error.httpStatus)
            assertEquals(1, calls, "HTTP $status must consume one generation attempt")
        }
    }

    @Test
    fun `count preflight uses the review body and refuses an oversized full page before generation`() {
        val base = request()
        val request = base.copy(
            settings = base.settings.copy(
                provider = base.settings.provider.copy(
                    kind = TranslationProviderKind.VERTEX_EXPRESS,
                ),
            ),
        )
        val bodies = mutableListOf<String>()
        val gateway = gateway { body ->
            bodies += body
            200 to "{\"totalTokens\":65537}"
        }
        val error = assertThrows(TranslationException::class.java) { runBlocking { gateway.review(request) } }
        assertEquals(TranslationFailureKind.LIMIT, error.kind)
        assertEquals(1, bodies.size)
        assertTrue(bodies.single().contains("Existing translation to review"))
        assertTrue(bodies.single().contains("visualComplete"))
        assertTrue(
            bodies.single().contains(Base64.getEncoder().encodeToString(File(request.image.filePath).readBytes())),
        )
    }

    @Test
    fun `failed token preflight is not retried and cannot dispatch generation`() {
        val base = request()
        val request = base.copy(
            settings = base.settings.copy(
                provider = base.settings.provider.copy(
                    kind = TranslationProviderKind.VERTEX_EXPRESS,
                ),
            ),
        )
        var calls = 0
        val gateway = gateway {
            calls++
            503 to "{}"
        }
        assertThrows(TranslationException::class.java) { runBlocking { gateway.review(request) } }
        assertEquals(1, calls)
    }

    @Test
    fun `successful token preflight precedes exactly one review generation`() = runBlocking<Unit> {
        val base = request()
        val request = base.copy(
            settings = base.settings.copy(
                provider = base.settings.provider.copy(kind = TranslationProviderKind.VERTEX_EXPRESS),
            ),
        )
        val bodies = mutableListOf<String>()
        val gateway = gateway { body ->
            bodies += body
            200 to if (bodies.size == 1) {
                "{\"totalTokens\":1234}"
            } else {
                buildJsonObject {
                    put(
                        "candidates",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("finishReason", "STOP")
                                    put(
                                        "content",
                                        buildJsonObject {
                                            put(
                                                "parts",
                                                buildJsonArray {
                                                    add(buildJsonObject { put("text", payload().toString()) })
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                    put(
                        "usageMetadata",
                        buildJsonObject {
                            put("promptTokenCount", 1234)
                            put("candidatesTokenCount", 99)
                        },
                    )
                }.toString()
            }
        }
        val result = gateway.review(request)
        assertEquals(2, bodies.size)
        assertEquals(1234L, result.usage.inputTokens)
        assertEquals(99L, result.usage.outputTokens)
        assertEquals("Wait!", result.candidate.regions.single().translatedText)
    }

    @Test
    fun `text coverage and pure Paddle never read or attach the original image`() = runBlocking<Unit> {
        for (pipeline in listOf(OcrPipeline.AI, OcrPipeline.PADDLE)) {
            val base = request()
            val request = base.copy(
                settings = base.settings.copy(
                    ocr = base.settings.ocr.copy(pipeline = pipeline),
                    qualityReview = base.settings.qualityReview.copy(
                        coverage = if (pipeline ==
                            OcrPipeline.PADDLE
                        ) {
                            QualityReviewCoverage.FULL_PAGE_WHEN_AVAILABLE
                        } else {
                            QualityReviewCoverage.TEXT_ONLY
                        },
                    ),
                ),
            )
            File(request.image.filePath).delete()
            val bodies = mutableListOf<String>()
            val gateway = gateway { body ->
                bodies += body
                200 to completion(textPayload())
            }
            val result = gateway.review(request)
            assertEquals(1, bodies.size)
            assertFalse(bodies.single().contains("image_url"))
            assertFalse(bodies.single().contains("inlineData"))
            assertFalse(result.visualComplete)
            val candidate = result.candidate.regions.single()
            assertEquals(request.baseline.regions.single().points, candidate.points)
            assertEquals(0f, candidate.rotation)
            assertEquals(0.7f, candidate.recognitionConfidence)
        }
    }

    @Test
    fun `source identity and full request size failures dispatch nothing`() {
        val base = request()
        var calls = 0
        val gateway = gateway {
            calls++
            error("Preflight must prevent HTTP")
        }
        val tinyBudget = base.copy(
            settings = base.settings.copy(provider = base.settings.provider.copy(maxRequestBytes = 20)),
        )
        assertEquals(
            TranslationFailureKind.LIMIT,
            assertThrows(TranslationException::class.java) {
                runBlocking { gateway.review(tinyBudget) }
            }.kind,
        )
        File(base.image.filePath).writeBytes(byteArrayOf(5, 4, 3, 2, 1))
        assertEquals(
            TranslationFailureKind.CONTENT,
            assertThrows(TranslationException::class.java) {
                runBlocking { gateway.review(base) }
            }.kind,
        )
        assertEquals(0, calls)
    }

    @Test
    fun `text review rejects changed identity transcription and geometry instead of silently accepting them`() {
        val base = request()
        val request = base.copy(
            settings = base.settings.copy(
                qualityReview = base.settings.qualityReview.copy(coverage = QualityReviewCoverage.TEXT_ONLY),
            ),
        )
        val mutations = listOf(
            "sourceText" to JsonPrimitive("invented source"),
            "rotation" to JsonPrimitive(90),
            "id" to JsonPrimitive("new-region"),
            "box2d" to JsonArray(listOf(0, 0, 500, 600).map(::JsonPrimitive)),
            "polygon" to
                JsonArray(
                    listOf(listOf(0, 0), listOf(600, 100), listOf(600, 500), listOf(150, 500)).map {
                        JsonArray(it.map(::JsonPrimitive))
                    },
                ),
        )
        for ((field, value) in mutations) {
            val payload = editRegion(textPayload()) { JsonObject(it + (field to value)) }
            assertThrows(TranslationException::class.java, {
                QualityReviewWireFormat.decode(
                    TranslationWireFormat.json.parseToJsonElement(completion(payload)).jsonObject,
                    request,
                    null,
                )
            }, field)
        }
    }

    @Test
    fun `review requires complete baseline identity and rejects invalid findings or truncated generation`() {
        val request = request()
        val unknownFinding = JsonObject(
            payload() + (
                "findings" to JsonArray(
                    listOf(
                        buildJsonObject {
                            put("code", "MASK")
                            put("description", "Wrong region")
                            put("regionIds", JsonArray(listOf(JsonPrimitive("missing"))))
                            put("aiConfidence", 0.8)
                        },
                    ),
                )
                ),
        )
        val invalid = listOf(
            unknownFinding,
            editRegion(payload()) { JsonObject(it + ("id" to JsonPrimitive("renamed"))) },
            editRegion(payload()) { JsonObject(it + ("sourceText" to JsonPrimitive("changed"))) },
            JsonObject(payload() + ("pages" to JsonArray(emptyList()))),
        )
        invalid.forEach { value ->
            assertThrows(TranslationException::class.java) {
                QualityReviewWireFormat.decode(
                    TranslationWireFormat.json.parseToJsonElement(completion(value)).jsonObject,
                    request,
                    null,
                )
            }
        }
        val truncated = completion(payload()).replace("\"stop\"", "\"length\"")
        assertThrows(TranslationException::class.java) {
            QualityReviewWireFormat.decode(
                TranslationWireFormat.json.parseToJsonElement(truncated).jsonObject,
                request,
                null,
            )
        }
    }

    @Test
    fun `known model custom token guards can lower but cannot raise official ceilings`() {
        val base = request()
        val request = base.copy(
            settings = base.settings.copy(
                provider = base.settings.provider.copy(
                    kind = TranslationProviderKind.VERTEX_EXPRESS,
                    model = OfficialProviderCapabilities.GEMINI_MODEL,
                    customInputTokenLimit = 500,
                    customOutputTokenLimit = 8192,
                ),
            ),
        )
        var calls = 0
        val gateway = gateway {
            calls++
            200 to "{\"totalTokens\":501}"
        }
        assertEquals(
            TranslationFailureKind.LIMIT,
            assertThrows(TranslationException::class.java) {
                runBlocking { gateway.review(request) }
            }.kind,
        )
        assertEquals(1, calls)
        val outputTooSmall = request.copy(
            settings = request.settings.copy(provider = request.settings.provider.copy(customOutputTokenLimit = 100)),
        )
        assertEquals(
            TranslationFailureKind.CONFIGURATION,
            assertThrows(TranslationException::class.java) {
                runBlocking { gateway.review(outputTooSmall) }
            }.kind,
        )
        assertEquals(1, calls)
    }

    @Test
    fun `service account refresh occurs only on the next explicitly invoked review attempt`() = runBlocking<Unit> {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pem = "-----BEGIN PRIVATE KEY-----\n${Base64.getEncoder().encodeToString(
            pair.private.encoded,
        )}\n-----END PRIVATE KEY-----\n"
        val credential = CredentialParser.serviceAccount(
            buildJsonObject {
                put("type", "service_account")
                put("project_id", "fixture-project")
                put("client_email", "test@fixture-project.iam.gserviceaccount.com")
                put("private_key", pem)
            }.toString(),
            "Fixture",
            "fixture",
        )
        val base = request()
        val request = base.copy(
            settings = base.settings.copy(
                provider = base.settings.provider.copy(kind = TranslationProviderKind.VERTEX_SERVICE_ACCOUNT),
            ),
        )
        val paths = mutableListOf<String>()
        val auth = mutableListOf<String?>()
        var grants = 0
        var generations = 0
        val gateway = gateway(credential = credential, inspect = {
            paths += it.url.encodedPath
            if (it.url.encodedPath.endsWith(":generateContent")) auth += it.header("Authorization")
        }) {
            when {
                paths.last() == "/token" -> {
                    grants++
                    200 to
                        "{\"access_token\":\"synthetic-$grants\",\"expires_in\":3600}"
                }
                paths.last().endsWith(":countTokens") -> 200 to "{\"totalTokens\":123}"
                else -> {
                    generations++
                    if (generations == 1) 401 to "{}" else 200 to vertexCompletion(payload())
                }
            }
        }
        assertEquals(
            401,
            assertThrows(TranslationException::class.java) {
                runBlocking { gateway.review(request) }
            }.httpStatus,
        )
        assertEquals(1, generations)
        assertEquals(1, grants)
        assertEquals("Wait!", gateway.review(request).candidate.regions.single().translatedText)
        assertEquals(2, generations)
        assertEquals(2, grants)
        assertEquals(listOf("Bearer synthetic-1", "Bearer synthetic-2"), auth)
        assertEquals(6, paths.size)
    }

    @Test
    fun `Responses review uses its count endpoint and preserves explicit incomplete coverage`() = runBlocking<Unit> {
        val base = request()
        val request = base.copy(
            settings = base.settings.copy(
                provider = base.settings.provider.copy(
                    baseUrl = "https://api.openai.com/v1",
                    dialect = OpenAiDialect.RESPONSES,
                ),
            ),
        )
        val paths = mutableListOf<String>()
        val gateway = gateway(inspect = { paths += it.url.encodedPath }) { body ->
            assertTrue(body.contains("input_image"))
            if (paths.last().endsWith("input_tokens")) {
                200 to "{\"input_tokens\":123}"
            } else {
                200 to buildJsonObject {
                    put("status", "completed")
                    put(
                        "output",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("type", "message")
                                    put(
                                        "content",
                                        buildJsonArray {
                                            add(
                                                buildJsonObject {
                                                    put("type", "output_text")
                                                    put(
                                                        "text",
                                                        JsonObject(
                                                            payload() + ("visualComplete" to JsonPrimitive(false)),
                                                        ).toString(),
                                                    )
                                                },
                                            )
                                        },
                                    )
                                },
                            )
                        },
                    )
                }.toString()
            }
        }
        assertFalse(gateway.review(request).visualComplete)
        assertEquals(listOf("/v1/responses/input_tokens", "/v1/responses"), paths)
    }

    @Test
    fun `malformed review keeps usage and exact capture without credentials`() {
        val events = mutableListOf<TranslationEvent>()
        val repository = mockk<TranslationRepository>(relaxed = true)
        coEvery { repository.addEvent(capture(events)) } returns Unit
        val base = request()
        val request = base.copy(settings = base.settings.copy(logs = base.settings.logs.copy(captureRaw = true)))
        val gateway = gateway(repository = repository) {
            200 to "{\"choices\":[],\"usage\":{\"prompt_tokens\":123,\"completion_tokens\":45}}"
        }
        assertThrows(TranslationException::class.java) { runBlocking { gateway.review(request) } }
        val usage = events.single { it.stage == "USAGE" }
        assertEquals(request.reviewId, usage.batchId)
        assertEquals("123", usage.details["inputTokens"])
        assertEquals("45", usage.details["outputTokens"])
        val capture = File(checkNotNull(usage.capturePath))
        assertTrue(capture.isDirectory)
        assertFalse(capture.walkTopDown().filter { it.isFile }.any { it.readText().contains("synthetic-only") })
        assertEquals(1, events.count { it.stage == "qualityReview" && it.message.startsWith("Request started") })
    }

    @Test
    fun `rotated mirrored or unverifiable original orientation sends no review request`() {
        for (orientation in listOf(-1, 2, 3, 4, 5, 6, 7, 8, 9)) {
            var calls = 0
            val gateway =
                gateway(orientation = { orientation }) {
                    calls++
                    error("Oriented image cannot be dispatched")
                }
            assertEquals(
                TranslationFailureKind.LIMIT,
                assertThrows(TranslationException::class.java) {
                    runBlocking { gateway.review(request()) }
                }.kind,
            )
            assertEquals(0, calls)
        }
    }

    @Test
    fun `transport does not automatically resend a review after 503 retry after zero`() {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 4, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 1500
            val received = executor.submit<Int> {
                var requests = 0
                while (requests < 2) {
                    val socket = try {
                        server.accept()
                    } catch (_: SocketTimeoutException) {
                        break
                    }
                    socket.use {
                        it.soTimeout = 5000
                        val input = it.getInputStream()
                        val header = StringBuilder()
                        while (!header.endsWith("\r\n\r\n")) {
                            val byte = input.read()
                            check(byte >= 0 && header.length < 16384)
                            header.append(byte.toChar())
                        }
                        val length =
                            Regex("(?im)^Content-Length: (\\d+)").find(header)?.groupValues?.get(1)?.toInt() ?: 0
                        check(input.readNBytes(length).size == length)
                        requests++
                        it.getOutputStream().write(
                            (
                                "HTTP/1.1 503 Service Unavailable\r\nContent-Length: 2\r\n" +
                                    "Retry-After: 0\r\nConnection: close\r\n\r\n{}"
                                ).toByteArray(),
                        )
                    }
                }
                requests
            }
            try {
                val base = request()
                val request = base.copy(
                    settings = base.settings.copy(
                        provider = base.settings.provider.copy(
                            baseUrl = "http://127.0.0.1:${server.localPort}/v1",
                            model = "mihon-fixture",
                        ),
                    ),
                )
                val gateway = TranslationProviderGateway(
                    { CredentialParser.apiKey("mihon-fixture-only", "Fixture", "fixture") },
                    mockk<TranslationRepository>(relaxed = true),
                    TranslationDiagnosticsStore(File(directory, "captures")),
                    OkHttpClient.Builder().retryOnConnectionFailure(false).build(),
                    File(directory, "temporary"),
                    localFixturesEnabled = true,
                    originalOrientation = { 1 },
                )
                assertEquals(
                    503,
                    assertThrows(TranslationException::class.java) {
                        runBlocking { gateway.review(request) }
                    }.httpStatus,
                )
                assertEquals(
                    1,
                    received.get(10, TimeUnit.SECONDS),
                    "A persisted review attempt must dispatch only once",
                )
            } finally {
                executor.shutdownNow()
            }
        }
    }

    @Test
    fun `visual review rejects missing null text and malformed physical angles`() {
        val values =
            listOf<JsonElement?>(
                null,
                JsonNull,
                JsonPrimitive("clockwise sideways"),
                JsonPrimitive("90"),
                JsonPrimitive(true),
            )
        for (angle in values) {
            val invalid = editRegion(payload()) {
                JsonObject(if (angle == null) it - "rotation" else it + ("rotation" to angle))
            }
            val request = request()
            assertThrows(TranslationException::class.java) {
                val decoded = QualityReviewWireFormat.decode(
                    TranslationWireFormat.json.parseToJsonElement(completion(invalid)).jsonObject,
                    request,
                    "malformed-angle",
                )
                QualityReviewValidation.candidate(request, decoded)
            }
        }
    }

    @Test
    fun `count and generation share unchanged original bytes despite chapter source replacement`() = runBlocking<Unit> {
        val base = request()
        val request = base.copy(
            settings = base.settings.copy(
                provider = base.settings.provider.copy(kind = TranslationProviderKind.VERTEX_EXPRESS),
                logs = base.settings.logs.copy(captureRaw = true),
            ),
        )
        val before = File(request.image.filePath).readBytes()
        val replaced = byteArrayOf(6, 7, 8, 9, 10)
        val bodies = mutableListOf<String>()
        val gateway = gateway { body ->
            bodies += body
            if (bodies.size == 1) {
                File(request.image.filePath).writeBytes(replaced)
                200 to "{\"totalTokens\":123}"
            } else {
                200 to vertexCompletion(payload())
            }
        }
        val reviewed = gateway.review(request)
        assertEquals(request.baseline.imageHash, reviewed.candidate.imageHash)
        assertEquals(2, bodies.size)
        val encoded = Base64.getEncoder().encodeToString(before)
        assertTrue(bodies.all { it.contains(encoded) }, "Both requests must carry the snapshotted original")
        assertFalse(bodies.any { it.contains(Base64.getEncoder().encodeToString(replaced)) })
        val capturedRequests = File(directory, "captures").walkTopDown().filter { it.name == "request.json" }.toList()
        assertEquals(2, capturedRequests.size)
        assertTrue(capturedRequests.none { it.readText().contains(encoded) })
        assertTrue(capturedRequests.all { it.readText().contains("_omitted") })
        assertTrue(capturedRequests.none { it.readText().contains("synthetic-only") })
        assertFalse(File(directory, "temporary").walkTopDown().any { it.name.startsWith("review-original-") })
        assertTrue(File(request.image.filePath).readBytes().contentEquals(replaced))
    }

    @Test
    fun `review snapshot cleanup covers failed count invalid output and cancellation`() {
        for (failure in listOf("count", "output", "cancel")) {
            val base = request()
            val request = base.copy(
                settings = base.settings.copy(
                    provider = base.settings.provider.copy(kind = TranslationProviderKind.VERTEX_EXPRESS),
                ),
            )
            var calls = 0
            val gateway = gateway(orientation = {
                if (failure == "cancel") throw kotlinx.coroutines.CancellationException("Synthetic interruption")
                1
            }) {
                calls++
                if (failure == "count") {
                    503 to "{}"
                } else if (calls == 1) {
                    200 to "{\"totalTokens\":123}"
                } else {
                    200 to "{}"
                }
            }
            assertThrows(Exception::class.java) { runBlocking { gateway.review(request) } }
            assertEquals(
                when (failure) {
                    "output" -> 2
                    "cancel" -> 0
                    else -> 1
                },
                calls,
            )
            assertTrue(File(request.image.filePath).isFile)
            assertFalse(File(directory, "temporary").walkTopDown().any { it.name.startsWith("review-original-") })
        }
    }

    @Test
    fun `actual untagged PNG orientation is undefined and review accepts unchanged pixels`() = runBlocking<Unit> {
        mockkStatic(Log::class)
        try {
            every { Log.isLoggable(any(), any()) } returns false
            val base = request()
            val source = File(base.image.filePath)
            val pixels = java.awt.image.BufferedImage(200, 100, java.awt.image.BufferedImage.TYPE_INT_RGB)
            assertTrue(javax.imageio.ImageIO.write(pixels, "png", source))
            val bytes = source.readBytes()
            val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            val request = base.copy(
                image = base.image.copy(contentHash = hash, byteSize = bytes.size.toLong()),
                baseline = base.baseline.copy(imageHash = hash),
            )
            var calls = 0
            val gateway = gateway(orientation = { file ->
                file.inputStream().buffered().use { input ->
                    val exif = ExifInterface(input)
                    assertEquals(
                        ExifInterface.ORIENTATION_UNDEFINED,
                        exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
                    )
                    assertEquals(0, exif.rotationDegrees)
                    assertFalse(exif.isFlipped)
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                }
            }) {
                calls++
                200 to completion(payload())
            }
            assertEquals(hash, gateway.review(request).candidate.imageHash)
            assertEquals(1, calls)
            assertTrue(source.readBytes().contentEquals(bytes))
            assertFalse(File(directory, "temporary").walkTopDown().any { it.name.startsWith("review-original-") })
        } finally {
            unmockkStatic(Log::class)
        }
    }

    private fun vertexCompletion(payload: JsonObject): String = buildJsonObject {
        put(
            "candidates",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("finishReason", "STOP")
                        put(
                            "content",
                            buildJsonObject {
                                put(
                                    "parts",
                                    buildJsonArray {
                                        add(buildJsonObject { put("text", payload.toString()) })
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }.toString()

    private fun textPayload() = editRegion(payload()) {
        JsonObject(it + mapOf("rotation" to JsonPrimitive(0), "polygon" to JsonNull))
    }

    private fun editRegion(value: JsonObject, transform: (JsonObject) -> JsonObject): JsonObject {
        val page = value.getValue("pages").jsonArray.single().jsonObject
        return JsonObject(
            value +
                (
                    "pages" to
                        JsonArray(
                            listOf(
                                JsonObject(
                                    page +
                                        (
                                            "regions" to JsonArray(
                                                page.getValue("regions").jsonArray.map {
                                                    transform(it.jsonObject)
                                                },
                                            )
                                            ),
                                ),
                            ),
                        )
                    ),
        )
    }

    private fun renderedRequest(): QualityReviewRequest {
        val base = request()
        val bytes = byteArrayOf(11, 12, 13, 14, 15)
        val preview = File(directory, "preview.png").apply { writeBytes(bytes) }
        return base.copy(
            settings = base.settings.copy(
                qualityReview = base.settings.qualityReview.copy(includeRenderedPreview = true),
            ),
            renderEvidence = QualityReviewRenderEvidence(
                original = base.image,
                preview = base.image.copy(
                    id = "review-preview",
                    filePath = preview.absolutePath,
                    width = 100,
                    height = 50,
                    contentHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
                        "%02x".format(it)
                    },
                    byteSize = preview.length(),
                ),
                sourceRevision = base.baseline.revision,
                rendererVersion = "fixture-renderer-v1",
                presentationFingerprint = "a".repeat(64),
                scaleX = 0.5,
                scaleY = 0.5,
                presentation = QualityReviewPresentation(OverlayStyle(), OverlayStyle()),
            ),
        )
    }

    private fun request(): QualityReviewRequest {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        val source = File(directory, "source.png").apply { writeBytes(bytes) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val image = TranslationImage("page", 0, source.absolutePath, "image/png", 200, 100, hash, source.length())
        return QualityReviewRequest(
            "job",
            "review",
            TranslationSettings(
                qualityReview = QualityReviewSettings(includeRenderedPreview = false),
                provider = ProviderSettings(
                    kind = TranslationProviderKind.OPENAI, credentialId = "fixture", model = "custom-model",
                    baseUrl = "https://fixture.invalid/v1", dialect = OpenAiDialect.CHAT_COMPLETIONS,
                    customInputTokenLimit = 65536, customOutputTokenLimit = 8192, maxOutputTokens = 8192,
                    totalAttempts = 5, initialRetryMillis = 0, maxRetryMillis = 0,
                ),
            ),
            image,
            TranslationPageResult(
                image.id,
                hash,
                image.width,
                image.height,
                listOf(
                    TextRegion(
                        "region",
                        listOf(
                            TranslationPoint(30f, 10f),
                            TranslationPoint(120f, 10f),
                            TranslationPoint(120f, 50f),
                            TranslationPoint(30f, 50f),
                        ),
                        "source",
                        "Old translation",
                        recognitionConfidence = 0.7f,
                    ),
                ),
            ),
        )
    }

    private fun gateway(
        credential: StoredTranslationCredential = CredentialParser.apiKey("synthetic-only", "Test", "fixture"),
        repository: TranslationRepository = mockk<TranslationRepository>(relaxed = true),
        inspect: (okhttp3.Request) -> Unit = {},
        orientation: (File) -> Int = { 1 },
        handler: (String) -> Pair<Int, String>,
    ): TranslationProviderGateway {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            inspect(chain.request())
            val buffer = Buffer()
            chain.request().body!!.writeTo(buffer)
            val (status, body) = handler(buffer.readUtf8())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Test")
                .header("x-request-id", "review-http-id").body(body.toResponseBody()).build()
        }.build()
        return TranslationProviderGateway(
            { credential },
            repository,
            TranslationDiagnosticsStore(File(directory, "captures")),
            client,
            File(directory, "temporary"),
            ImagePreparation(File(directory, "prepared")),
            originalOrientation = orientation,
        )
    }

    private fun payload(): JsonObject = TranslationWireFormat.json.parseToJsonElement(
        """{"pages":[{"imageId":"page","detectedLanguage":"ja","regions":[{"id":"region",
            "sourceText":"source","correctedText":null,"translatedText":"Wait!","box2d":[100,150,500,600],
            "polygon":[[200,100],[600,200],[550,500],[150,400]],"type":"dialogue","readingOrder":0,"rotation":17,
            "included":true,"ignoredReason":null,"aiConfidence":0.8}]}],"findings":[],"visualComplete":true}""",
    ).jsonObject

    private fun completion(payload: JsonObject): String = buildJsonObject {
        put(
            "choices",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("finish_reason", "stop")
                        put("message", buildJsonObject { put("content", payload.toString()) })
                    },
                )
            },
        )
    }.toString()
}
