package mihon.feature.translation.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings

class PaddleWireFormatTest {
    @Test
    fun `empty Paddle input constrains the generated region count to zero`() {
        val body = body(request(emptyList()))
        val pages = properties(schema(body)).getValue("pages").jsonObject
        assertEquals(1, pages.getValue("minItems").jsonPrimitive.int)
        assertEquals(1, pages.getValue("maxItems").jsonPrimitive.int)
        val page = properties(pages.getValue("items").jsonObject)
        assertEquals(listOf("page-1"), enumValues(page.getValue("imageId").jsonObject))
        val regions = page.getValue("regions").jsonObject
        assertEquals(0, regions.getValue("minItems").jsonPrimitive.int)
        assertEquals(0, regions.getValue("maxItems").jsonPrimitive.int)
        assertFalse(body.toString().contains("inlineData"))
    }

    @Test
    fun `Paddle schema keeps exact counts and IDs independently for each page`() {
        val original = request(listOf(region("page-1:0", "."), region("page-1:1", "0.")))
        val request = original.copy(
            images = original.images + original.images.single().copy(id = "page-2", index = 1),
            ocr = original.ocr + OcrPageResult("page-2", listOf(region("page-2:0", "Hello"))),
        )
        val pages = properties(schema(body(request))).getValue("pages").jsonObject
        val alternatives = pages.getValue("items").jsonObject.getValue("anyOf").jsonArray
        assertEquals(2, alternatives.size)
        alternatives.forEachIndexed { index, value ->
            val page = properties(value.jsonObject)
            assertEquals(listOf("page-${index + 1}"), enumValues(page.getValue("imageId").jsonObject))
            val regions = page.getValue("regions").jsonObject
            val expected = request.ocr[index].regions.map { it.id }
            assertEquals(expected.size, regions.getValue("minItems").jsonPrimitive.int)
            assertEquals(expected.size, regions.getValue("maxItems").jsonPrimitive.int)
            assertEquals(
                expected,
                enumValues(properties(regions.getValue("items").jsonObject).getValue("id").jsonObject),
            )
        }
    }

    @Test
    fun `Paddle instructions explicitly distinguish absent source from ignored OCR noise`() {
        val body = body(request(emptyList()))
        val instructions = body.getValue("systemInstruction").jsonObject.getValue("parts").jsonArray
            .single().jsonObject.getValue("text").jsonPrimitive.content
        assertTrue(instructions.contains("No images are attached"))
        assertTrue(instructions.contains("empty OCR region list"))
        assertTrue(instructions.contains("Do not add, merge, split, rename or omit"))
        assertTrue(instructions.contains("unreadable"))
        assertTrue(instructions.contains("included=false"))
    }

    @Test
    fun `Express constrains Paddle IDs and OpenAI keeps its compatibility schema`() {
        val original = request(emptyList())
        val express = original.copy(
            settings = original.settings.copy(
                provider = original.settings.provider.copy(kind = TranslationProviderKind.VERTEX_EXPRESS),
            ),
        )
        assertEquals(schema(body(original)), schema(body(express)))
        for (dialect in OpenAiDialect.entries) {
            val request = original.copy(
                settings = original.settings.copy(
                    provider = original.settings.provider.copy(
                        kind = TranslationProviderKind.OPENAI,
                        dialect = dialect,
                    ),
                ),
            )
            val body = body(request)
            val actual = if (dialect == OpenAiDialect.RESPONSES) {
                body.getValue("text").jsonObject.getValue("format").jsonObject.getValue("schema")
            } else {
                body.getValue("response_format").jsonObject.getValue("json_schema").jsonObject.getValue("schema")
            }
            assertEquals(TranslationWireFormat.schema, actual)
            assertTrue(body.toString().contains("No images are attached"))
        }
    }

    @Test
    fun `invented dialogue on an empty OCR page remains a content failure`() {
        val error = assertThrows(TranslationException::class.java) {
            TranslationWireFormat.decodePages(
                payload(listOf(wireRegion("invented", "Unseen dialogue"))),
                request(emptyList()),
            )
        }
        assertEquals("Text-only translation introduced a region absent from OCR.", error.message)
        assertTrue(
            TranslationWireFormat.decodePages(payload(emptyList()), request(emptyList())).single().regions.isEmpty(),
        )
    }

    @Test
    fun `noise regions must be returned explicitly and keep their measured OCR geometry`() {
        val originals = listOf(region("page-1:0", "."), region("page-1:1", "0."))
        val request = request(originals)
        val error = assertThrows(TranslationException::class.java) {
            TranslationWireFormat.decodePages(payload(emptyList()), request)
        }
        assertEquals("Text-only translation omitted OCR regions.", error.message)
        val result = TranslationWireFormat.decodePages(
            payload(
                originals.map {
                    wireRegion(it.id, it.sourceText, false)
                },
            ),
            request,
        )
            .single()
        assertEquals(originals, result.rawOcr!!.regions)
        result.regions.forEachIndexed { index, region ->
            assertFalse(region.included)
            assertEquals("Uninterpretable OCR fragment", region.ignoredReason)
            assertEquals("", region.translatedText)
            assertEquals(originals[index].points, region.points)
            assertEquals(originals[index].recognitionConfidence, region.recognitionConfidence)
        }
    }

    @Test
    fun `matching count still rejects duplicated IDs and missing translations`() {
        val request = request(listOf(region("page-1:0", "Hello"), region("page-1:1", "Goodbye")))
        val duplicate = wireRegion("page-1:0", "Hello")
        assertThrows(TranslationException::class.java) {
            TranslationWireFormat.decodePages(payload(listOf(duplicate, duplicate)), request)
        }
        val missing = JsonObject(wireRegion("page-1:1", "Goodbye") + ("translatedText" to JsonPrimitive("")))
        assertThrows(TranslationException::class.java) {
            TranslationWireFormat.decodePages(payload(listOf(duplicate, missing)), request)
        }
    }

    private fun request(regions: List<TextRegion>) = TranslationRequest(
        "synthetic-job",
        "synthetic-batch",
        TranslationSettings(
            provider = ProviderSettings(kind = TranslationProviderKind.VERTEX_SERVICE_ACCOUNT),
            ocr = OcrSettings(pipeline = OcrPipeline.PADDLE),
        ),
        listOf(TranslationImage("page-1", 0, "/unused-text-only.png", "image/png", 1200, 1600, "synthetic-hash", 1)),
        ocr = listOf(OcrPageResult("page-1", regions)),
    )

    private fun region(id: String, text: String) = TextRegion(
        id,
        listOf(
            TranslationPoint(10f, 20f),
            TranslationPoint(110f, 20f),
            TranslationPoint(110f, 90f),
            TranslationPoint(10f, 90f),
        ),
        text,
        detectionConfidence = 0.8f,
        recognitionConfidence = 0.7f,
    )

    private fun body(request: TranslationRequest) = TranslationWireFormat.json.parseToJsonElement(
        Buffer().also { TranslationWireFormat.requestBody(request).writeTo(it) }.readUtf8(),
    ).jsonObject

    private fun schema(body: JsonObject) = body.getValue(
        "generationConfig",
    ).jsonObject.getValue("responseJsonSchema").jsonObject
    private fun properties(schema: JsonObject) = schema.getValue("properties").jsonObject
    private fun enumValues(schema: JsonObject) = schema.getValue("enum").jsonArray.map { it.jsonPrimitive.content }

    private fun payload(regions: List<JsonObject>) = buildJsonObject {
        put(
            "pages",
            JsonArray(
                listOf(
                    buildJsonObject {
                        put("imageId", "page-1")
                        put("detectedLanguage", "en")
                        put("regions", JsonArray(regions))
                    },
                ),
            ),
        )
    }

    private fun wireRegion(id: String, source: String, included: Boolean = true) = buildJsonObject {
        put("id", id)
        put("sourceText", source)
        put("translatedText", if (included) "Translated source" else "")
        put("box2d", JsonArray(listOf(10, 20, 100, 200).map(::JsonPrimitive)))
        put("type", if (included) "dialogue" else "unreadable")
        put("readingOrder", 0)
        put("rotation", 0)
        put("included", included)
        put("ignoredReason", if (included) null else "Uninterpretable OCR fragment")
        put("aiConfidence", 0.5)
    }
}
