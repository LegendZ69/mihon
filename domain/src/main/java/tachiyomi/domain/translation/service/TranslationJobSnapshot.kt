package tachiyomi.domain.translation.service

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import tachiyomi.domain.translation.model.TranslationJob

/** Old queued settings are immutable: absence of review configuration means no automatic paid review. */
object TranslationJobSnapshot {
    fun decode(json: Json, payload: String): TranslationJob {
        val root = json.parseToJsonElement(payload).jsonObject
        val settings = root.getValue("settings").jsonObject
        val review = settings["qualityReview"]?.jsonObject
            ?: buildJsonObject { put("enabled", false) }
        val compatibleReview = if ("includeRenderedPreview" in review) {
            review
        } else {
            JsonObject(review + ("includeRenderedPreview" to JsonPrimitive(false)))
        }
        val compatiblePolicy = settings["contentPolicy"] ?: buildJsonObject { put("ignoreSoundEffects", false) }
        val compatibleGeometry = settings["geometryRecovery"] ?: buildJsonObject { put("enabled", false) }
        val compatibleSettings = JsonObject(
            settings + ("qualityReview" to compatibleReview) + ("contentPolicy" to compatiblePolicy) +
                ("geometryRecovery" to compatibleGeometry),
        )
        val compatible = JsonObject(root + ("settings" to compatibleSettings))
        return json.decodeFromJsonElement(TranslationJob.serializer(), compatible)
    }
}
