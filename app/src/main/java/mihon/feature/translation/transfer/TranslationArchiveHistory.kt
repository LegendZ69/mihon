package mihon.feature.translation.transfer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState

@Serializable
internal data class TranslationArchiveHistory(
    val events: List<TranslationEvent> = emptyList(),
    val operations: List<TranslationOperation> = emptyList(),
)

/** Export history facts without private executable correction inputs, even when defaults are encoded. */
internal fun TranslationArchiveHistory.exportJson(json: Json): String {
    val inert = copy(operations = operations.map { it.copy(geometryCorrection = null) })
    return json.encodeToJsonElement(inert).withoutGeometryCorrectionInputs().toString()
}

/** Remove omitted or legacy checkpoint objects before typed history decoding. Other facts remain available. */
internal fun JsonElement.withoutGeometryCorrectionInputs(): JsonElement {
    val history = this as? JsonObject ?: return this
    val operations = history["operations"] as? JsonArray ?: return this
    return JsonObject(
        history + (
            "operations" to JsonArray(
                operations.map { value ->
                    val operation = value as? JsonObject ?: return@map value
                    JsonObject(
                        operation.filterKeys { key ->
                            key.lowercase().filter(Char::isLetterOrDigit) != "geometrycorrection"
                        },
                    )
                },
            )
            ),
    )
}

/** Historical operations are inert. Stable IDs keep repeated imports and parent/child links coherent. */
internal fun TranslationArchiveHistory.remap(
    sourceJobId: String,
    targetJobId: String,
    imageIds: Map<String, String>,
    captureIds: Map<String, String>,
    reviewIds: Map<String, String> = emptyMap(),
): TranslationArchiveHistory {
    fun identity(value: String?): String? = value?.let {
        if (sourceJobId ==
            targetJobId
        ) {
            it
        } else {
            java.util.UUID.nameUUIDFromBytes("translation-history:$targetJobId:$it".toByteArray()).toString()
        }
    }
    return copy(
        events = events.filter { it.jobId == sourceJobId }.map { event ->
            event.copy(
                id = identity(event.id)!!,
                jobId = targetJobId,
                batchId = identity(event.batchId),
                imageId = event.imageId?.let { imageIds[it] },
                operationId = identity(event.operationId),
                capturePath = event.capturePath?.let { captureIds[java.io.File(it).name] },
            )
        },
        operations = operations.filter { it.jobId == sourceJobId }.map { operation ->
            operation.copy(
                id = identity(operation.id)!!, jobId = targetJobId, parentId = identity(operation.parentId),
                batchId = identity(operation.batchId), imageId = operation.imageId?.let { imageIds[it] },
                reviewId = operation.reviewId?.let {
                    reviewIds[it] ?: it.takeIf { sourceJobId == targetJobId }
                },
                captureId = operation.captureId?.let(captureIds::get),
                state = if (operation.state.terminal) operation.state else TranslationOperationState.INTERRUPTED,
                processSession = null,
                geometryCorrection = null,
            )
        },
    )
}
