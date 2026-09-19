package mihon.feature.translation.transfer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.GeometryCorrectionCheckpoint
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationInputTransform
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationStage

class TranslationArchiveHistoryTest {
    @Test
    fun `export and restored histories cannot carry private resumable geometry checkpoints`() {
        val image = TranslationImage("page", 0, "/private/input.png", "image/png", 100, 200, "a".repeat(64), 1234)
        val checkpoint = GeometryCorrectionCheckpoint(
            "correction", "source", "policy", image, TranslationInputTransform(image), TranslationSettings(),
            emptyList(), "private context", "private candidate", emptyList(),
        )
        val operation = TranslationOperation(
            "operation",
            "source",
            TranslationStage.GEOMETRY_CORRECTION,
            imageId = image.id,
            state = TranslationOperationState.ACTIVE,
            completed = 1,
            total = 2,
            geometryCorrection = checkpoint,
        )
        val history = TranslationArchiveHistory(operations = listOf(operation))
        val exported = history.exportJson(Json { encodeDefaults = true })
        val exportedOperation = Json.parseToJsonElement(
            exported,
        ).jsonObject.getValue("operations").jsonArray.single().jsonObject
        assertFalse("geometryCorrection" in exportedOperation)
        assertFalse(exported.contains("private candidate"))
        assertFalse(exported.contains("/private/input.png"))
        assertEquals("operation", exportedOperation.getValue("id").jsonPrimitive.content)
        assertEquals("1", exportedOperation.getValue("completed").jsonPrimitive.content)
        assertEquals("2", exportedOperation.getValue("total").jsonPrimitive.content)
        val restored = history.remap("source", "target", mapOf("page" to "0"), emptyMap())
        assertNull(restored.operations.single().geometryCorrection)
        assertEquals(TranslationOperationState.INTERRUPTED, restored.operations.single().state)
        assertEquals(
            checkpoint,
            history.operations.single().geometryCorrection,
            "Export must not alter the durable checkpoint",
        )
    }

    @Test
    fun `imported logs keep page and capture correlation without resuming operations or multiplying identities`() {
        val source = TranslationArchiveHistory(
            events = listOf(
                TranslationEvent(
                    "event",
                    "source",
                    imageId = "page",
                    stage = "REQUEST",
                    message = "Request",
                    capturePath = "capture",
                    operationId = "attempt",
                ),
            ),
            operations = listOf(
                TranslationOperation(
                    "attempt",
                    "source",
                    TranslationStage.REQUEST,
                    parentId = "batch",
                    imageId = "page",
                    state = TranslationOperationState.ACTIVE,
                    captureId = "capture",
                    processSession = "prior-session",
                ),
            ),
        )
        val remapped = source.remap("source", "target", mapOf("page" to "0"), mapOf("capture" to "new-capture"))
        assertEquals("target", remapped.operations.single().jobId)
        assertEquals("0", remapped.operations.single().imageId)
        assertEquals("new-capture", remapped.operations.single().captureId)
        assertEquals(TranslationOperationState.INTERRUPTED, remapped.operations.single().state)
        assertNull(remapped.operations.single().processSession)
        assertEquals(remapped.operations.single().id, remapped.events.single().operationId)
        assertEquals("new-capture", remapped.events.single().capturePath)
        assertEquals(
            remapped,
            source.remap("source", "target", mapOf("page" to "0"), mapOf("capture" to "new-capture")),
        )
    }
}
