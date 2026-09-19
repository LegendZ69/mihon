package mihon.feature.translation.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.translation.model.GeometryCorrectionCheckpoint
import tachiyomi.domain.translation.model.GeometryIssue
import tachiyomi.domain.translation.model.GeometryIssueCode
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.RegionGeometryIssue
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationInputTransform
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationPromptPair
import tachiyomi.domain.translation.model.TranslationPrompts
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationSettings
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class GeometryCorrectionWireFormatTest {
    @TempDir
    lateinit var directory: File

    @Test
    fun `geometry custom task roles retain candidate reasons and transform without hidden builtin instructions`() {
        val original = checkpoint()
        val providers = listOf(
            original.settings.provider,
            original.settings.provider.copy(kind = TranslationProviderKind.VERTEX_EXPRESS),
            original.settings.provider.copy(kind = TranslationProviderKind.OPENAI, dialect = OpenAiDialect.RESPONSES),
            original.settings.provider.copy(
                kind = TranslationProviderKind.OPENAI,
                dialect = OpenAiDialect.CHAT_COMPLETIONS,
            ),
        )
        for (provider in providers) {
            val checkpoint = original.copy(
                settings = original.settings.copy(
                    provider = provider,
                    prompts = TranslationPrompts(
                        translation = TranslationPromptPair(
                            "Do not leak translation task",
                            "Do not leak translation user",
                        ),
                        geometryCorrection = TranslationPromptPair("CORRECTION ONLY", "Use {{target_language}}"),
                    ),
                ),
            )
            val body = GeometryCorrectionWireFormat.body(checkpoint)
            val prompt = requireNotNull(body.promptDiagnostics)
            assertEquals("CORRECTION ONLY", prompt.system)
            assertTrue(prompt.user.startsWith("Use en"))
            assertTrue(prompt.user.contains("validationIssues"))
            assertTrue(prompt.user.contains("cropLeft"))
            assertTrue(prompt.user.contains("candidate"))
            assertFalse(prompt.user.contains("Available chapter context"))
            assertEquals(prompt, GeometryCorrectionWireFormat.body(checkpoint, true).promptDiagnostics)
            val serialized = serialized(body)
            if (provider.kind == TranslationProviderKind.OPENAI) {
                if (provider.dialect == OpenAiDialect.RESPONSES) {
                    assertEquals("CORRECTION ONLY", serialized.getValue("instructions").jsonPrimitive.content)
                } else {
                    assertEquals(
                        "CORRECTION ONLY",
                        serialized.getValue("messages").jsonArray.first().jsonObject
                            .getValue("content").jsonPrimitive.content,
                    )
                }
            } else {
                assertEquals(
                    "CORRECTION ONLY",
                    serialized.getValue("systemInstruction").jsonObject.getValue("parts")
                        .jsonArray.single().jsonObject.getValue("text").jsonPrimitive.content,
                )
            }
        }
    }

    @Test
    fun `null or string rotation cannot silently reset a physically rotated passage`() {
        val checkpoint = checkpoint()
        for (rotation in listOf(JsonNull, JsonPrimitive("90"))) {
            val candidate = changeRegion(correctedPage(), 0, mapOf("rotation" to rotation))
            val error = assertThrows(TranslationException::class.java) {
                GeometryCorrectionWireFormat.decode(response(candidate), checkpoint)
            }
            assertEquals(TranslationFailureKind.GEOMETRY, error.kind)
        }
    }

    @Test
    fun `string coordinates are rejected even when a valid polygon would otherwise mask the malformed box`() {
        val candidate = changeRegion(
            correctedPage(),
            0,
            mapOf("box2d" to JsonArray(listOf("100", "100", "900", "900").map(::JsonPrimitive))),
        )
        val error = assertThrows(TranslationException::class.java) {
            GeometryCorrectionWireFormat.decode(response(candidate), checkpoint())
        }
        assertEquals(TranslationFailureKind.GEOMETRY, error.kind)
    }

    @Test
    fun `token count and generation contain the same single tile and recorded original transform`() {
        val checkpoint = checkpoint()
        val generation = serialized(GeometryCorrectionWireFormat.body(checkpoint))
        val counted = serialized(GeometryCorrectionWireFormat.body(checkpoint, countOnly = true))
        assertEquals(generation["contents"], counted["contents"])
        assertEquals(generation["systemInstruction"], counted["systemInstruction"])
        assertEquals(generation["generationConfig"], counted["generationConfig"])
        val parts = generation.getValue("contents").jsonArray.single().jsonObject.getValue("parts").jsonArray
        val attachment = parts.map { it.jsonObject }.mapNotNull { it["inlineData"] }.single().jsonObject
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4),
            Base64.getDecoder().decode(attachment.getValue("data").jsonPrimitive.content),
        )
        assertEquals("image/png", attachment.getValue("mimeType").jsonPrimitive.content)
        val input = TranslationWireFormat.json.parseToJsonElement(
            parts.last().jsonObject.getValue("text").jsonPrimitive.content.substringAfter('\n').substringAfter('\n'),
        ).jsonObject
        assertEquals("original", input.getValue("originalImageId").jsonPrimitive.content)
        assertEquals(checkpoint.transform.original.contentHash, input.getValue("originalHash").jsonPrimitive.content)
        assertEquals(JsonPrimitive(100), input["cropLeft"])
        assertEquals(JsonPrimitive(200), input["cropTop"])
        assertEquals(JsonPrimitive(400), input["cropWidth"])
        assertEquals(JsonPrimitive(200), input["cropHeight"])
        assertEquals(JsonPrimitive(200), input["inputWidth"])
        assertEquals(JsonPrimitive(100), input["inputHeight"])
        assertEquals(baselinePage(), input["candidate"])
        assertEquals(listOf(checkpoint.image), GeometryCorrectionWireFormat.request(checkpoint).images)
    }

    @Test
    fun `valid correction retains passages and returns tile coordinates for one original mapping`() {
        val checkpoint = checkpoint()
        val result = GeometryCorrectionWireFormat.decode(response(correctedPage()), checkpoint)
        assertEquals("tile", result.imageId)
        assertEquals(checkpoint.image.contentHash, result.imageHash)
        assertEquals(200, result.width)
        assertEquals(100, result.height)
        assertEquals(listOf("target", "sign"), result.regions.map { it.id })
        assertEquals(listOf("待って", "出口"), result.regions.map { it.sourceText })
        assertEquals(listOf("Wait!", "Exit"), result.regions.map { it.translatedText })
        assertEquals(listOf(0, 1), result.regions.map { it.readingOrder })
        assertEquals(90f, result.regions.first().rotation)
        assertEquals(
            listOf(
                TranslationPoint(20f, 10f),
                TranslationPoint(180f, 10f),
                TranslationPoint(180f, 90f),
                TranslationPoint(20f, 90f),
            ),
            result.regions.first().points,
        )
        assertEquals(
            listOf(
                TranslationPoint(140f, 220f),
                TranslationPoint(460f, 220f),
                TranslationPoint(460f, 380f),
                TranslationPoint(140f, 380f),
            ),
            result.regions.first().points.map(checkpoint.transform::toOriginal),
        )
        assertEquals(
            listOf(
                TranslationPoint(120f, 2f),
                TranslationPoint(180f, 2f),
                TranslationPoint(180f, 10f),
                TranslationPoint(120f, 10f),
            ),
            result.regions.last().points,
        )
        assertNull(result.rawOcr)
    }

    @Test
    fun `correction cannot change transcription translation inclusion ordering or metadata`() {
        val changes = mapOf(
            "id" to JsonPrimitive("replacement"),
            "sourceText" to JsonPrimitive("違う"),
            "translatedText" to JsonPrimitive("Different"),
            "included" to JsonPrimitive(false),
            "type" to JsonPrimitive("sound_effect"),
            "readingOrder" to JsonPrimitive(2),
            "ignoredReason" to JsonPrimitive("irrelevant"),
            "aiConfidence" to JsonPrimitive(0.99),
        )
        for ((field, value) in changes) {
            rejected(response(changeRegion(correctedPage(), 0, mapOf(field to value))), field)
        }
        rejected(response(JsonObject(correctedPage() + ("detectedLanguage" to JsonPrimitive("ko")))), "language")
        rejected(response(JsonObject(correctedPage() + ("imageId" to JsonPrimitive("original")))), "image")
    }

    @Test
    fun `correction cannot alter unaffected geometry or replace a polygon with a rectangle`() {
        rejected(response(changeRegion(correctedPage(), 1, mapOf("rotation" to JsonPrimitive(90)))), "neighbor angle")
        rejected(
            response(
                changeRegion(
                    correctedPage(),
                    1,
                    mapOf("box2d" to JsonArray(listOf(30, 600, 100, 900).map(::JsonPrimitive))),
                ),
            ),
            "neighbor box",
        )
        rejected(response(changeRegion(correctedPage(), 0, mapOf("polygon" to JsonNull))), "null fallback")
        val page = correctedPage()
        val regions = page.getValue("regions").jsonArray
        rejected(
            response(
                JsonObject(
                    page + (
                        "regions" to JsonArray(
                            listOf(
                                JsonObject(regions.first().jsonObject - "polygon"),
                                regions.last(),
                            ),
                        )
                        ),
                ),
            ),
            "missing fallback",
        )
        rejected(response(baselinePage()), "crossed outline remains invalid")
    }

    @Test
    fun `correction requires exactly one page and stable complete region identities`() {
        val page = correctedPage()
        val regions = page.getValue("regions").jsonArray
        rejected(JsonObject(mapOf("pages" to JsonArray(emptyList()))), "no page")
        rejected(JsonObject(mapOf("pages" to JsonArray(listOf(page, page)))), "duplicate page")
        for ((name, changed) in listOf(
            "missing region" to listOf(regions.first()),
            "added region" to (regions + regions.last()),
            "reordered regions" to regions.reversed(),
            "duplicate identity" to listOf(regions.first(), regions.first()),
        )) {
            rejected(response(JsonObject(page + ("regions" to JsonArray(changed)))), name)
        }
    }

    @Test
    fun `pure Paddle rejects visual correction before accessing images and ordinary translation stays text only`() {
        val base = checkpoint()
        val checkpoint = base.copy(
            settings = base.settings.copy(ocr = base.settings.ocr.copy(pipeline = OcrPipeline.PADDLE)),
        )
        File(checkpoint.image.filePath).delete()
        File(checkpoint.transform.original.filePath).delete()
        for (operation in listOf<() -> Unit>(
            { GeometryCorrectionWireFormat.body(checkpoint) },
            { GeometryCorrectionWireFormat.body(checkpoint, countOnly = true) },
            { GeometryCorrectionWireFormat.decode(response(correctedPage()), checkpoint) },
        )) {
            assertEquals(
                TranslationFailureKind.CONFIGURATION,
                assertThrows(TranslationException::class.java) {
                    operation()
                }.kind,
            )
        }
        val body = serialized(TranslationWireFormat.requestBody(GeometryCorrectionWireFormat.request(checkpoint)))
        assertFalse(body.toString().contains("inlineData"))
        assertFalse(body.toString().contains("image_url"))
        assertFalse(body.toString().contains("input_image"))
        assertTrue(body.toString().contains("text-only translation"))
    }

    @Test
    fun `hybrid correction preserves raw OCR and measured scores independently of transcription correction`() {
        val base = checkpoint()
        val rawRegion = TextRegion(
            "target",
            listOf(
                TranslationPoint(0f, 0f),
                TranslationPoint(190f, 0f),
                TranslationPoint(190f, 95f),
                TranslationPoint(0f, 95f),
            ),
            "待っ",
            detectionConfidence = 0.92f,
            recognitionConfidence = 0.81f,
        )
        val ocr = OcrPageResult("tile", listOf(rawRegion))
        val checkpoint = base.copy(
            settings = base.settings.copy(ocr = base.settings.ocr.copy(pipeline = OcrPipeline.PADDLE_AI)),
            ocr = listOf(ocr),
        )
        val result = GeometryCorrectionWireFormat.decode(response(correctedPage()), checkpoint)
        assertEquals(ocr, result.rawOcr)
        val corrected = result.regions.first()
        assertEquals("待っ", corrected.sourceText)
        assertEquals("待って", corrected.correctedText)
        assertEquals("Wait!", corrected.translatedText)
        assertEquals(0.92f, corrected.detectionConfidence)
        assertEquals(0.81f, corrected.recognitionConfidence)
        assertEquals(0.73f, corrected.aiConfidence)
    }

    @Test
    fun `invalid tile transforms are rejected before request serialization or candidate application`() {
        val base = checkpoint()
        val checkpoint = base.copy(transform = base.transform.copy(left = 900))
        assertThrows(IllegalArgumentException::class.java) { GeometryCorrectionWireFormat.body(checkpoint) }
        assertThrows(IllegalArgumentException::class.java) {
            GeometryCorrectionWireFormat.decode(response(correctedPage()), checkpoint)
        }
    }

    private fun rejected(value: JsonObject, description: String) {
        val error =
            assertThrows(TranslationException::class.java, {
                GeometryCorrectionWireFormat.decode(value, checkpoint())
            }, description)
        assertEquals(TranslationFailureKind.GEOMETRY, error.kind, description)
    }

    private fun serialized(body: StreamingJsonBody): JsonObject = Buffer().let { buffer ->
        body.writeTo(buffer)
        TranslationWireFormat.json.parseToJsonElement(buffer.readUtf8()).jsonObject
    }

    private fun checkpoint(): GeometryCorrectionCheckpoint {
        val original = image("original", byteArrayOf(9, 8, 7), 1000, 800)
        val tile = image("tile", byteArrayOf(1, 2, 3, 4), 200, 100)
        return GeometryCorrectionCheckpoint(
            "checkpoint", "job", "explicit-epoch", tile,
            TranslationInputTransform(original, 100, 200, 400, 200, 200, 100),
            TranslationSettings(), emptyList(), "A retained chapter context", baselinePage().toString(),
            listOf(
                RegionGeometryIssue("target", GeometryIssue(GeometryIssueCode.SELF_INTERSECTION, reason = "crossing")),
            ),
        )
    }

    private fun image(id: String, bytes: ByteArray, width: Int, height: Int): TranslationImage {
        val file = File(directory, "$id.png").apply { writeBytes(bytes) }
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return TranslationImage(id, 0, file.path, "image/png", width, height, hash, file.length())
    }

    private fun baselinePage() = TranslationWireFormat.json.parseToJsonElement(
        """
        {"imageId":"tile","detectedLanguage":"ja","regions":[
        {"id":"target","sourceText":"待って","translatedText":"Wait!","box2d":[100,100,900,900],
        "polygon":[[100,100],[900,900],[900,100],[100,900]],"type":"dialogue","readingOrder":0,
        "rotation":90,"included":true,"ignoredReason":null,"aiConfidence":0.73},
        {"id":"sign","sourceText":"出口","translatedText":"Exit","box2d":[20,600,100,900],
        "polygon":null,"type":"sign","readingOrder":1,"rotation":0,"included":true,
        "ignoredReason":null,"aiConfidence":null}]}
        """,
    ).jsonObject

    private fun correctedPage() = changeRegion(
        baselinePage(),
        0,
        mapOf(
            "polygon" to TranslationWireFormat.json.parseToJsonElement("[[100,100],[900,100],[900,900],[100,900]]"),
        ),
    )

    private fun changeRegion(page: JsonObject, index: Int, changes: Map<String, JsonElement>): JsonObject = JsonObject(
        page + (
            "regions" to JsonArray(
                page.getValue("regions").jsonArray.mapIndexed { current, region ->
                    if (index == current) JsonObject(region.jsonObject + changes) else region
                },
            )
            ),
    )

    private fun response(page: JsonObject) = JsonObject(mapOf("pages" to JsonArray(listOf(page))))
}
