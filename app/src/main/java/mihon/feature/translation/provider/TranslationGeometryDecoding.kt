package mihon.feature.translation.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonPrimitive
import tachiyomi.domain.translation.model.GeometryIssue
import tachiyomi.domain.translation.model.GeometryIssueCode
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.PolygonNormalizationOutcome
import tachiyomi.domain.translation.model.RegionGeometryIssue
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationRequest

internal data class RejectedGeometryPage(
    val image: TranslationImage,
    val candidate: JsonObject,
    val issues: List<RegionGeometryIssue>,
)

internal data class RegionGeometryNormalization(
    val imageId: String,
    val regionId: String,
    val outcome: PolygonNormalizationOutcome,
)

internal data class TranslationGeometryDecoding(
    val pages: List<TranslationPageResult>,
    val rejected: Map<String, String>,
    val geometry: List<RejectedGeometryPage>,
    val normalizations: List<RegionGeometryNormalization>,
) {
    companion object {
        fun decode(payload: JsonObject, request: TranslationRequest): TranslationGeometryDecoding {
            val supplied = payload["pages"] as? JsonArray ?: return TranslationGeometryDecoding(
                emptyList(),
                request.images.associate {
                    it.id to "Response has no pages array"
                },
                emptyList(),
                emptyList(),
            )
            fun JsonObject.id() = (get("imageId") as? JsonPrimitive)?.content
            val occurrences = supplied.mapNotNull { it as? JsonObject }.groupBy { it.id() }
            val accepted = mutableListOf<TranslationPageResult>()
            val rejected = mutableMapOf<String, String>()
            val geometry = mutableListOf<RejectedGeometryPage>()
            val normalized = mutableListOf<RegionGeometryNormalization>()
            request.images.forEach { image ->
                val matches = occurrences[image.id].orEmpty()
                if (matches.size != 1) {
                    rejected[image.id] =
                        if (matches.isEmpty()) {
                            "Response omitted the requested image"
                        } else {
                            "Response repeated the image ID"
                        }
                    return@forEach
                }
                val page = matches.single()
                val one = request.copy(images = listOf(image), ocr = request.ocr.filter { it.imageId == image.id })
                val result =
                    runCatching {
                        TranslationWireFormat.decodePages(
                            JsonObject(mapOf("pages" to JsonArray(listOf(page)))),
                            one,
                        ).single()
                    }
                if (result.isSuccess) {
                    accepted += result.getOrThrow()
                } else {
                    rejected[image.id] = result.exceptionOrNull()?.message ?: "Invalid provider page"
                }
                if (request.settings.ocr.pipeline == OcrPipeline.PADDLE) return@forEach
                val regions = page["regions"] as? JsonArray ?: return@forEach
                val issues = mutableListOf<RegionGeometryIssue>()
                regions.forEach { item ->
                    val region = item as? JsonObject ?: return@forEach
                    val id = (region["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@forEach
                    val polygon = ProviderPolygonGeometry.parse(region["polygon"], image.width, image.height)
                    polygon?.issues?.forEach { issues += RegionGeometryIssue(id, it) }
                    if (result.isSuccess && polygon?.changes?.isNotEmpty() == true) {
                        normalized += RegionGeometryNormalization(image.id, id, polygon)
                    }
                    val box = (region["box2d"] as? JsonArray)?.map {
                        (it as? JsonPrimitive)?.takeUnless { value -> value.isString }?.floatOrNull
                    }
                    if (box == null || box.size != 4 || box.any { it == null || !it.isFinite() || it !in 0f..1000f } ||
                        (box.size == 4 && box.all { it != null } && (box[0]!! >= box[2]!! || box[1]!! >= box[3]!!))
                    ) {
                        issues += RegionGeometryIssue(
                            id,
                            GeometryIssue(
                                GeometryIssueCode.INVALID_POLYGON,
                                reason = "box2d requires finite ordered [y_min,x_min,y_max,x_max] " +
                                    "coordinates within 0–1000",
                            ),
                        )
                    }
                    val rotationValue = region["rotation"]
                    if (rotationValue != null) {
                        val rotation = (rotationValue as? JsonPrimitive)?.takeUnless { it.isString }?.floatOrNull
                        if (rotation == null || !rotation.isFinite() || rotation !in -360f..360f) {
                            issues += RegionGeometryIssue(
                                id,
                                GeometryIssue(
                                    GeometryIssueCode.INVALID_POLYGON,
                                    reason = "Physical clockwise rotation must be a finite angle " +
                                        "from -360 to +360 degrees",
                                ),
                            )
                        }
                    }
                }
                if (result.isFailure && issues.isNotEmpty()) geometry += RejectedGeometryPage(image, page, issues)
            }
            return TranslationGeometryDecoding(
                accepted.sortedBy { page ->
                    request.images.first { it.id == page.imageId }.index
                },
                rejected,
                geometry,
                normalized,
            )
        }
    }
}
