package mihon.feature.translation.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings

class TranslationWireGeometryTest {
    @Test
    fun `explicit null or string rotation is rejected while an absent legacy rotation defaults to zero`() {
        val original = payload("null")
        val page = original.getValue("pages").jsonArray.single().jsonObject
        val region = page.getValue("regions").jsonArray.single().jsonObject
        fun replaced(value: JsonObject) = JsonObject(
            original + ("pages" to JsonArray(listOf(JsonObject(page + ("regions" to JsonArray(listOf(value))))))),
        )
        for (rotation in listOf(JsonNull, JsonPrimitive("90"))) {
            assertThrows(TranslationException::class.java) {
                TranslationWireFormat.decodePages(replaced(JsonObject(region + ("rotation" to rotation))), request())
            }
        }
        val legacy = TranslationWireFormat.decodePages(replaced(JsonObject(region - "rotation")), request()).single()
        assertEquals(0f, legacy.regions.single().rotation)
    }

    @Test
    fun `string box coordinates do not satisfy the numeric geometry schema`() {
        val malformed = payload("null").toString().replace("[100,150,500,600]", "[\"100\",150,500,600]")
        assertThrows(TranslationException::class.java) {
            TranslationWireFormat.decodePages(
                TranslationWireFormat.json.parseToJsonElement(malformed).jsonObject,
                request(),
            )
        }
    }

    @Test
    fun `pure Paddle keeps the authoritative OCR angle even when the provider returns a different angle`() {
        val original = TextRegion(
            "region",
            listOf(
                TranslationPoint(0f, 0f),
                TranslationPoint(100f, 0f),
                TranslationPoint(100f, 50f),
                TranslationPoint(0f, 50f),
            ),
            "待って",
            rotation = 90f,
            detectionConfidence = 0.94f,
            recognitionConfidence = 0.87f,
        )
        val base = request()
        val pure = base.copy(
            settings = base.settings.copy(ocr = base.settings.ocr.copy(pipeline = OcrPipeline.PADDLE)),
            ocr = listOf(OcrPageResult("page", listOf(original))),
        )
        val result = TranslationWireFormat.decodePages(payload("[[0,0],[1000,1000],[1000,0],[0,1000]]"), pure).single()
        assertEquals(original.rotation, result.regions.single().rotation)
        assertEquals(original.points, result.regions.single().points)
        assertEquals(pure.ocr.single(), result.rawOcr)
    }

    @Test
    fun `convex polygon preserves the artwork outside a tilted passage`() {
        val page = TranslationWireFormat.decodePages(
            payload("[[200,100],[600,200],[550,500],[150,400]]"),
            request(),
        ).single()
        assertEquals(
            listOf(
                TranslationPoint(40f, 10f),
                TranslationPoint(120f, 20f),
                TranslationPoint(110f, 50f),
                TranslationPoint(30f, 40f),
            ),
            page.regions.single().points,
        )
    }

    @Test
    fun `redundant closed ring preserves candidate text IDs and perimeter without rejecting the page`() {
        val page = TranslationWireFormat.decodePages(
            payload("[[200,100],[600,200],[550,500],[150,400],[200,100]]"),
            request(),
        ).single()
        val region = page.regions.single()
        assertEquals("region", region.id)
        assertEquals("待って", region.sourceText)
        assertEquals("Wait", region.translatedText)
        assertEquals(17f, region.rotation)
        assertEquals(
            listOf(
                TranslationPoint(40f, 10f),
                TranslationPoint(120f, 20f),
                TranslationPoint(110f, 50f),
                TranslationPoint(30f, 40f),
            ),
            region.points,
        )
    }

    @Test
    fun `missing and null polygons keep the legacy rectangle`() {
        val expected =
            listOf(
                TranslationPoint(30f, 10f),
                TranslationPoint(120f, 10f),
                TranslationPoint(120f, 50f),
                TranslationPoint(30f, 50f),
            )
        assertEquals(
            expected,
            TranslationWireFormat.decodePages(payload("null"), request()).single().regions.single().points,
        )
        val missing = payload("null").toString().replace("\"polygon\":null,", "")
        assertEquals(
            expected,
            TranslationWireFormat.decodePages(
                TranslationWireFormat.json.parseToJsonElement(missing).jsonObject,
                request(),
            ).single().regions.single().points,
        )
    }

    @Test
    fun `a triangular split region retains its source-space vertices`() {
        assertEquals(
            listOf(TranslationPoint(0f, 0f), TranslationPoint(120f, 0f), TranslationPoint(120f, 60f)),
            TranslationWireFormat.decodePages(
                payload("[[0,0],[600,0],[600,600]]"),
                request(),
            ).single().regions.single().points,
        )
    }

    @Test
    fun `redundant vertices preserve a triangular passage without creating a rectangle`() {
        val expected = listOf(TranslationPoint(0f, 0f), TranslationPoint(120f, 0f), TranslationPoint(0f, 60f))
        for (polygon in listOf("[[0,0],[600,0],[600,0],[0,600]]", "[[0,0],[300,0],[600,0],[0,600]]")) {
            assertEquals(
                expected,
                TranslationWireFormat.decodePages(payload(polygon), request()).single().regions.single().points,
            )
        }
    }

    @Test
    fun `concave crossing degenerate and out of bounds polygons remain content errors`() {
        for (polygon in listOf(
            "[[0,0],[600,0],[200,200],[0,600]]",
            "[[0,0],[600,600],[600,0],[0,600]]",
            "[[0,0],[1001,0],[600,600],[0,600]]",
            "[[0,0],[600,0]]",
            "[[0,0],[600,0],[600,600],[0]]",
        )) {
            assertThrows(TranslationException::class.java, {
                TranslationWireFormat.decodePages(payload(polygon), request())
            }, polygon)
        }
    }

    private fun request() = TranslationRequest(
        "job",
        "batch",
        TranslationSettings(),
        listOf(TranslationImage("page", 0, "/not-opened", "image/png", 200, 100, "hash", 0)),
    )

    private fun payload(polygon: String): JsonObject = TranslationWireFormat.json.parseToJsonElement(
        """{"pages":[{"imageId":"page","regions":[{"id":"region","sourceText":"待って",
            "translatedText":"Wait","box2d":[100,150,500,600],"polygon":$polygon,"type":"dialogue",
            "readingOrder":0,"rotation":17,"included":true,"ignoredReason":null,"aiConfidence":null}]}]}""",
    ).jsonObject
}
