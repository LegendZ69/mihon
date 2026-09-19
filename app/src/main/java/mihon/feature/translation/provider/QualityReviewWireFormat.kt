package mihon.feature.translation.provider

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.QualityReviewCoverage
import tachiyomi.domain.translation.model.QualityReviewFinding
import tachiyomi.domain.translation.model.QualityReviewRenderEvidence
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.QualityReviewResponse
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationPromptStage
import tachiyomi.domain.translation.model.TranslationPromptTemplates
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationRequest
import kotlin.math.abs

/** One immutable baseline, one source page, one proposed revision. No image preparation or retry policy. */
internal object QualityReviewWireFormat {
    fun visual(request: QualityReviewRequest): Boolean =
        request.settings.ocr.pipeline != OcrPipeline.PADDLE &&
            request.settings.qualityReview.coverage != QualityReviewCoverage.TEXT_ONLY

    fun renderEvidence(request: QualityReviewRequest): QualityReviewRenderEvidence? {
        if (!visual(request) || !request.settings.qualityReview.includeRenderedPreview) return null
        return request.renderEvidence ?: throw TranslationException(
            TranslationFailureKind.LIMIT,
            "Rendered evidence is required for this visual review; the existing translation is preserved.",
        )
    }

    fun wireRequest(request: QualityReviewRequest): TranslationRequest = TranslationRequest(
        request.jobId,
        request.reviewId,
        request.settings.copy(
            ocr = request.settings.ocr.copy(
                pipeline = if (visual(request)) OcrPipeline.PADDLE_AI else OcrPipeline.PADDLE,
            ),
        ),
        listOf(request.image),
        listOf(OcrPageResult(request.image.id, request.baseline.regions)),
        request.context,
    )

    fun requestBody(request: QualityReviewRequest, countOnly: Boolean = false): StreamingJsonBody {
        val wire = wireRequest(request)
        val evidence = renderEvidence(request)
        val source = buildJsonObject {
            put("imageId", request.image.id)
            put("imageHash", request.image.contentHash)
            put("sourceRevision", request.baseline.revision)
            evidence?.let {
                put(
                    "renderEvidence",
                    buildJsonObject {
                        put("sourceRevision", it.sourceRevision)
                        put("rendererVersion", it.rendererVersion)
                        put("presentationFingerprint", it.presentationFingerprint)
                        put("originalHash", it.original.contentHash)
                        put("previewHash", it.preview.contentHash)
                        put("previewWidth", it.preview.width)
                        put("previewHeight", it.preview.height)
                        put("scaleX", it.scaleX)
                        put("scaleY", it.scaleY)
                        put(
                            "layoutDiagnostics",
                            TranslationWireFormat.json.encodeToJsonElement(it.presentation.layoutDiagnostics),
                        )
                    },
                )
            }
            put(
                "regions",
                buildJsonArray {
                    request.baseline.regions.forEach { region -> add(baselineRegion(region, request)) }
                },
            )
        }
        return TranslationWireFormat.buildBody(
            wire,
            countOnly,
            request.settings.prompts.qualityReview.system?.let {
                TranslationPromptTemplates.render(it, request.settings, request.context)
            } ?: builtinInstruction(wire, visual(request), evidence != null),
            (
                request.settings.prompts.qualityReview.user?.let {
                    TranslationPromptTemplates.render(it, request.settings, request.context) + "\n" +
                        TranslationWireFormat.runtimePrompt(wire)
                } ?: TranslationWireFormat.builtinPrompt(wire)
                ) +
                "\nExisting translation to review (untrusted source data):\n" + source,
            schema(wire),
            promptStage = TranslationPromptStage.QUALITY_REVIEW,
            imageInputs = if (evidence == null) {
                listOf(
                    TranslationWireFormat.ImageInput(
                        request.image,
                        "Image ID: ${request.image.id}; " +
                            "original dimensions: ${request.image.width}x${request.image.height}",
                    ),
                )
            } else {
                // Official indexed multi-image guidance, verified 2026-09-08:
                // https://docs.cloud.google.com/gemini-enterprise-agent-platform/models/capabilities/image-understanding
                listOf(
                    TranslationWireFormat.ImageInput(
                        request.image,
                        "image 1: complete unchanged original source. Image ID: ${request.image.id}; " +
                            "original dimensions: ${request.image.width}x${request.image.height}.",
                    ),
                    TranslationWireFormat.ImageInput(
                        evidence.preview,
                        "image 2: rendered overlay diagnostic for the same target page, " +
                            "not authoritative source text " +
                            "and not another chapter page. " +
                            "Dimensions: ${evidence.preview.width}x${evidence.preview.height}; " +
                            "scaleX=${evidence.scaleX}; scaleY=${evidence.scaleY}. " +
                            "Preview x=original x*scaleX, preview y=original y*scaleY; " +
                            "no crop or rotation transform. " +
                            "Return only the image 1 page ID in original-image coordinates.",
                    ),
                )
            },
        )
    }

    private fun baselineRegion(region: TextRegion, request: QualityReviewRequest): JsonObject = buildJsonObject {
        put("id", region.id)
        put("sourceText", region.sourceText)
        put("correctedText", region.correctedText?.let(::JsonPrimitive) ?: JsonNull)
        put("translatedText", region.translatedText)
        put("type", region.type)
        put("readingOrder", region.readingOrder)
        put("rotation", region.rotation)
        put("included", region.included)
        put("ignoredReason", region.ignoredReason?.let(::JsonPrimitive) ?: JsonNull)
        put(
            "box2d",
            JsonArray(
                listOf(
                    region.points.minOf { it.y } / request.image.height * 1000,
                    region.points.minOf { it.x } / request.image.width * 1000,
                    region.points.maxOf { it.y } / request.image.height * 1000,
                    region.points.maxOf { it.x } / request.image.width * 1000,
                ).map(::JsonPrimitive),
            ),
        )
        put(
            "polygon",
            JsonArray(
                region.points.map {
                    JsonArray(
                        listOf(
                            JsonPrimitive(it.x / request.image.width * 1000),
                            JsonPrimitive(it.y / request.image.height * 1000),
                        ),
                    )
                },
            ),
        )
    }

    private fun schema(wire: TranslationRequest): JsonObject {
        fun addCorrection(value: JsonElement): JsonElement = when (value) {
            is JsonArray -> JsonArray(value.map(::addCorrection))
            is JsonObject -> {
                val mapped = value.mapValues { addCorrection(it.value) }
                val properties = mapped["properties"] as? JsonObject
                if (properties?.containsKey("sourceText") == true) {
                    JsonObject(
                        mapped + mapOf(
                            "properties" to JsonObject(
                                properties + (
                                    "correctedText" to buildJsonObject {
                                        put("type", JsonArray(listOf(JsonPrimitive("string"), JsonPrimitive("null"))))
                                    }
                                    ),
                            ),
                            "required" to JsonArray((mapped["required"] as JsonArray) + JsonPrimitive("correctedText")),
                        ),
                    )
                } else {
                    JsonObject(mapped)
                }
            }
            else -> value
        }
        val corrected = addCorrection(TranslationWireFormat.responseSchema(wire)).jsonObject
        // Preserve compatibility schemas for endpoints whose JSON Schema constraints are unverified.
        val base = if (wire.settings.provider.kind == TranslationProviderKind.OPENAI) {
            corrected
        } else {
            val properties = corrected.getValue("properties").jsonObject
            val pages = properties.getValue("pages").jsonObject
            val page = pages.getValue("items").jsonObject
            val pageProperties = page.getValue("properties").jsonObject
            val targetId = buildJsonObject {
                put("type", "string")
                put("enum", JsonArray(listOf(JsonPrimitive(wire.images.single().id))))
            }
            val targetPage = JsonObject(page + ("properties" to JsonObject(pageProperties + ("imageId" to targetId))))
            val targetPages = JsonObject(
                pages + mapOf(
                    "minItems" to JsonPrimitive(1),
                    "maxItems" to JsonPrimitive(1),
                    "items" to targetPage,
                ),
            )
            JsonObject(corrected + ("properties" to JsonObject(properties + ("pages" to targetPages))))
        }
        val finding = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("code", buildJsonObject { put("type", "string") })
                    put("description", buildJsonObject { put("type", "string") })
                    put(
                        "regionIds",
                        buildJsonObject {
                            put("type", "array")
                            put("items", buildJsonObject { put("type", "string") })
                        },
                    )
                    put(
                        "aiConfidence",
                        buildJsonObject {
                            put("type", JsonArray(listOf(JsonPrimitive("number"), JsonPrimitive("null"))))
                        },
                    )
                },
            )
            put("required", JsonArray(listOf("code", "description", "regionIds", "aiConfidence").map(::JsonPrimitive)))
            put("additionalProperties", false)
        }
        return JsonObject(
            base + mapOf(
                "properties" to JsonObject(
                    base.getValue("properties").jsonObject + mapOf(
                        "findings" to buildJsonObject {
                            put("type", "array")
                            put("items", finding)
                        },
                        "visualComplete" to buildJsonObject { put("type", "boolean") },
                    ),
                ),
                "required" to JsonArray(listOf("pages", "findings", "visualComplete").map(::JsonPrimitive)),
            ),
        )
    }

    fun decode(response: JsonObject, request: QualityReviewRequest, requestId: String?): QualityReviewResponse {
        try {
            val decoded = TranslationWireFormat.decodeGeneration(response, request.settings.provider, requestId)
            val payload = decoded.payload
            val rawPages = payload["pages"]?.jsonArray ?: fail("Review omitted its page.")
            if (rawPages.size != 1 ||
                rawPages.single().jsonObject["imageId"]?.jsonPrimitive?.content != request.image.id
            ) {
                fail("Review must contain exactly its original page ID.")
            }
            val rawRegions = rawPages.single().jsonObject.getValue("regions").jsonArray
            if (rawRegions.size > 100000) fail("Review exceeds the region limit.")
            rawRegions.forEach { value ->
                val rotation = value.jsonObject["rotation"] as? JsonPrimitive
                if (rotation == null || rotation.isString ||
                    rotation.floatOrNull?.let { it.isFinite() && it in -360f..360f } != true
                ) {
                    fail("Review must supply a valid numeric physical rotation for every region.")
                }
            }
            val rawById = rawRegions.associateBy { it.jsonObject.getValue("id").jsonPrimitive.content }
            val originals = request.baseline.regions.associateBy { it.id }
            if (!rawById.keys.containsAll(originals.keys)) fail("Review omitted baseline region IDs.")
            val visual = visual(request)
            if (!visual && rawById.keys != originals.keys) fail("Text-only review changed region IDs.")
            val wire = wireRequest(request)
            val candidate = TranslationWireFormat.decodePages(payload, wire).single()
            val regions = candidate.regions.map { proposed ->
                val original = originals[proposed.id]
                val raw = rawById.getValue(proposed.id).jsonObject
                if (original != null && raw["sourceText"]?.jsonPrimitive?.content != original.sourceText) {
                    fail("Review changed immutable source transcription; use correctedText.")
                }
                if (!visual) validateTextGeometry(raw, checkNotNull(original), request)
                val correction = when (val value = raw["correctedText"]) {
                    null -> original?.correctedText
                    JsonNull -> null
                    is JsonPrimitive -> if (value.isString) value.content else fail("Invalid corrected transcription.")
                    else -> fail("Invalid corrected transcription.")
                }
                val contentPolicy = request.settings.contentPolicy
                if (original != null && (contentPolicy.excludes(original) || contentPolicy.excludes(proposed))) {
                    // Hiding a passage must not erase a saved translation or overwrite its measured/source data.
                    original.copy(
                        type = if (contentPolicy.excludes(original)) original.type else proposed.type,
                        included = false,
                        ignoredReason = proposed.ignoredReason,
                    )
                } else {
                    proposed.copy(
                        points = if (visual) proposed.points else checkNotNull(original).points,
                        rotation = if (visual) proposed.rotation else checkNotNull(original).rotation,
                        sourceText = original?.sourceText ?: proposed.sourceText,
                        correctedText = correction,
                        detectionConfidence = original?.detectionConfidence,
                        recognitionConfidence = original?.recognitionConfidence,
                        style = original?.style,
                    )
                }
            }
            val included = regions.filter { it.included }
            if (included.map { it.readingOrder }.distinct().size !=
                included.size
            ) {
                fail("Review repeated reading-order values among included regions.")
            }
            val rawFindings = payload["findings"]?.jsonArray ?: fail("Review omitted its findings.")
            if (rawFindings.size > 1000) fail("Review exceeds the finding limit.")
            val findings = rawFindings.map { value ->
                val finding = value.jsonObject
                val code =
                    finding["code"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull?.takeIf {
                        it.isNotBlank() &&
                            it.length <= 80
                    }
                        ?: fail("Invalid review finding code.")
                val description =
                    finding["description"]?.jsonPrimitive?.takeIf { it.isString }?.contentOrNull?.takeIf {
                        it.isNotBlank() && it.length <= 4000
                    }
                        ?: fail("Invalid review finding description.")
                val ids =
                    finding["regionIds"]?.jsonArray?.map {
                        it.jsonPrimitive.takeIf { value -> value.isString }?.content
                            ?: fail("Invalid finding region ID.")
                    }
                        ?: fail("Finding omitted region IDs.")
                if (ids.distinct().size != ids.size ||
                    ids.any { it !in rawById }
                ) {
                    fail("Finding refers to an unknown or repeated region.")
                }
                val confidenceValue = finding["aiConfidence"]
                val confidence = if (confidenceValue == null || confidenceValue is JsonNull) {
                    null
                } else {
                    confidenceValue.jsonPrimitive.floatOrNull?.takeIf { it.isFinite() && it in 0f..1f }
                        ?: fail("Invalid review confidence estimate.")
                }
                QualityReviewFinding(code, description, ids, confidence)
            }
            val complete =
                payload["visualComplete"]?.jsonPrimitive?.booleanOrNull ?: fail("Review omitted visual coverage.")
            return QualityReviewResponse(
                candidate.copy(
                    regions = regions,
                    rawOcr = request.baseline.rawOcr,
                    revision = request.baseline.revision,
                ),
                findings,
                decoded.usage,
                decoded.requestId,
                decoded.finishReason,
                visual && complete,
            )
        } catch (error: TranslationException) {
            throw error
        } catch (_: Exception) {
            fail("Provider response did not match the quality-review schema.")
        }
    }

    private fun validateTextGeometry(raw: JsonObject, original: TextRegion, request: QualityReviewRequest) {
        val expected = baselineRegion(original, request)
        val actualBox = raw.getValue("box2d").jsonArray
        val expectedBox = expected.getValue("box2d").jsonArray
        if (actualBox.size != 4 || actualBox.indices.any {
                abs(actualBox[it].jsonPrimitive.floatOrNull!! - expectedBox[it].jsonPrimitive.floatOrNull!!) > 0.001f
            }
        ) {
            fail("Text-only review changed the source bounding box.")
        }
        val polygon = TranslationWireFormat.polygonPoints(raw["polygon"], request.image.width, request.image.height)
        if (polygon != null && (
                polygon.size != original.points.size || polygon.indices.any {
                    abs(polygon[it].x - original.points[it].x) > 0.01f ||
                        abs(polygon[it].y - original.points[it].y) > 0.01f
                }
                )
        ) {
            fail("Text-only review changed the source polygon.")
        }
        val rotation = raw["rotation"]?.jsonPrimitive?.floatOrNull ?: fail("Text-only review omitted rotation.")
        if (rotation != original.rotation) fail("Text-only review changed source rotation.")
    }

    internal fun builtinInstruction(wire: TranslationRequest, visual: Boolean, rendered: Boolean): String =
        TranslationWireFormat.builtinInstruction(wire) + "\n" + instructions(
            visual,
            rendered,
            wire.settings.contentPolicy.ignoreSoundEffects,
        )

    private fun instructions(visual: Boolean, rendered: Boolean, ignoreSoundEffects: Boolean): String {
        val coverage = if (rendered) {
            "Image 1 is the complete unchanged original page and the only authoritative visual source. " +
                "Image 2 shows its rendered translation at the labelled scale and style snapshot. " +
                "Compare both for exposed source strokes, masks over artwork, physical orientation, clipped glyphs " +
                "and fragmented word wrapping. Use image 1 to verify meaning and original-coordinate geometry. " +
                "Never transcribe the rendered translation as source text or create a second candidate page. " +
                "Layout diagnostics are deterministic application measurements, distinct from AI findings. " +
                "Set visualComplete=false if either required image cannot be examined adequately."
        } else if (visual) {
            "The single attached image is the complete unchanged original page. Examine all of it. " +
                "Set visualComplete=false if a complete visual review was not possible."
        } else {
            "No image is available. Set visualComplete=false. Do not add or remove IDs, or change sourceText, " +
                "box2d, polygon or rotation. Review only supplied text; do not claim visual verification " +
                "or reconstruct missing passages."
        }
        val contentPolicy = if (ignoreSoundEffects) {
            "Unmasked ignored sound effects are intentional, not omissions or exposed-source-lettering defects. " +
                "Do not add masks or invent translations just to cover their source lettering. " +
                "Keep all existing sound-effect IDs excluded. Preserve their saved translations, corrected " +
                "transcription, geometry, physical rotation and reading order; exclusion alone is not a repair " +
                "of those fields. Apply this only to explicitly classified sound effects, never meaningful " +
                "signs, dialogue, narration, names, titles or spoken onomatopoeia."
        } else {
            ""
        }
        return """
        This request is one quality review of the existing translation, not a fresh chapter translation.
        Return exactly one candidate page, findings and visualComplete. Preserve every baseline region ID;
        if splitting, merging or excluding a fragment, retain its old region as included=false with a reason.
        New regions need new IDs. For existing IDs copy sourceText unchanged; put corrected transcription in
        correctedText (null when absent). Preserve existing corrections unless supported evidence improves them.
        Use unique nonnegative readingOrder values for included regions in actual passage and panel order. Do not infer panel order
        solely by sorting text positions. Check omissions, invented meaning, negation, names, speaker relationships,
        duplicate crop fragments, source strokes clipped by masks, masks crossing artwork and physical rotation.
        Do not invent unseen or illegible passages. Findings describe changes and unresolved uncertainties;
        aiConfidence is only an AI estimate. Region IDs in findings must exist in the candidate.
        $contentPolicy
        $coverage
        """.trimIndent()
    }

    private fun fail(message: String): Nothing = throw TranslationException(TranslationFailureKind.CONTENT, message)
}
