package mihon.feature.translation.provider

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class TranslationGeometryCheckpointExportTest {
    @Test
    fun `geometry prompt pairs are configuration and never mistaken for executable checkpoints`() {
        for (pair in listOf(
            "{}",
            "{\"system\":null,\"user\":null}",
            "{\"system\":\"Correct geometry\",\"user\":\"Use {{target_language}}\"}",
        )) {
            val settings = "{\"prompts\":{\"geometryCorrection\":$pair}}"
            assertEquals(
                Json.parseToJsonElement(settings),
                Json.parseToJsonElement(sanitizedDiagnosticRecord(settings)),
            )
        }
        val impostor = """{"geometryCorrection":{"system":"harmless","user":"also harmless",""" +
            """"candidateJson":"private executable candidate"}}"""
        assertFalse(sanitizedDiagnosticRecord(impostor).contains("private executable candidate"))
    }

    @Test
    fun `diagnostics omit executable geometry correction inputs but retain operation evidence`() {
        val operation = buildJsonObject {
            put("id", "geometry-attempt")
            put("stage", "GEOMETRY_CORRECTION")
            put("attempt", 2)
            put("imageId", "tile-3")
            put(
                "geometryCorrection",
                buildJsonObject {
                    put("candidateJson", "private candidate")
                    put("filePath", "/private/correction-original.png")
                    put("settings", buildJsonObject { put("credentialId", "personal-credential") })
                    put("maxTransportAttempts", 2)
                },
            )
        }
        val sanitized = sanitizedDiagnosticRecord(operation.toString())
        val parsed = Json.parseToJsonElement(sanitized).jsonObject
        assertFalse(sanitized.contains("private candidate"))
        assertFalse(sanitized.contains("/private/correction-original.png"))
        assertFalse(sanitized.contains("personal-credential"))
        assertEquals("geometry-attempt", parsed.getValue("id").jsonPrimitive.content)
        assertEquals("tile-3", parsed.getValue("imageId").jsonPrimitive.content)
        assertEquals("2", parsed.getValue("attempt").jsonPrimitive.content)
        assertEquals(
            "resumable_work",
            parsed.getValue("geometryCorrection").jsonObject.getValue("reason").jsonPrimitive.content,
        )
        assertEquals(sanitized, sanitizedDiagnosticRecord(sanitized), "Omission metadata must survive repeated exports")
    }

    @Test
    fun `nested historical event JSON cannot reintroduce resumable geometry work`() {
        val nested = """{"geometry_correction":{"candidate":"private nested candidate"},""" +
            """"message":"Geometry rejected"}"""
        val event = buildJsonObject { put("detail", nested) }
        val sanitized = sanitizedDiagnosticRecord(event.toString())
        assertFalse(sanitized.contains("private nested candidate"))
        assertEquals(
            "Geometry rejected",
            Json.parseToJsonElement(
                Json.parseToJsonElement(sanitized).jsonObject.getValue("detail").jsonPrimitive.content,
            )
                .jsonObject.getValue("message").jsonPrimitive.content,
        )
    }
}
