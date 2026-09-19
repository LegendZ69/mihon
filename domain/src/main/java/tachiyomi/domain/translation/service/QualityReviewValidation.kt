package tachiyomi.domain.translation.service

import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.QualityReviewCoverage
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.QualityReviewResponse
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import kotlin.math.abs

object QualityReviewValidation {
    fun candidate(request: QualityReviewRequest, response: QualityReviewResponse): TranslationPageResult {
        val before = request.baseline
        val received = response.candidate
        requireContent(
            received.imageId == before.imageId && received.imageHash == before.imageHash &&
                received.width == before.width && received.height == before.height,
            "Review changed the original image identity",
        )
        val textOnly = request.settings.ocr.pipeline == OcrPipeline.PADDLE ||
            request.settings.qualityReview.coverage == QualityReviewCoverage.TEXT_ONLY
        val after = if (textOnly) {
            received
        } else {
            received.copy(
                regions = received.regions.map { region ->
                    val geometry = TranslationGeometry.normalizeAndValidate(region.points, before.width, before.height)
                    requireContent(
                        geometry.accepted,
                        "Region ${region.id}: ${geometry.issues.joinToString { it.reason }}",
                    )
                    region.copy(points = geometry.points)
                },
            )
        }
        validatePage(after)
        val orders = after.regions.filter { it.included }.map { it.readingOrder }
        requireContent(orders.toSet().size == orders.size, "Review returned ambiguous reading order")
        val original = before.regions.associateBy { it.id }
        val proposed = after.regions.associateBy { it.id }
        requireContent(proposed.keys.containsAll(original.keys), "Review omitted a stable region identity")
        if (textOnly) {
            requireContent(proposed.keys == original.keys, "Text-only review invented a source region")
            after.regions.forEach { region ->
                val source = original.getValue(region.id)
                requireContent(
                    region.points == source.points && region.rotation == source.rotation,
                    "Text-only review changed OCR geometry",
                )
                requireContent(region.sourceText == source.sourceText, "Text-only review changed raw OCR")
            }
        }
        response.findings.forEach { finding ->
            requireContent(finding.code.isNotBlank() && finding.description.isNotBlank(), "Empty AI finding")
            requireContent(finding.regionIds.all { it in proposed }, "AI finding references an unknown region")
            requireContent(
                finding.aiConfidence == null ||
                    (finding.aiConfidence.isFinite() && finding.aiConfidence in 0f..1f),
                "Invalid AI assessment confidence",
            )
        }
        return after.copy(
            revision = before.revision,
            rawOcr = before.rawOcr,
            regions = after.regions.map { candidate ->
                val source = original[candidate.id]
                if (source == null) {
                    // A visual addition is an AI transcription, not measured local recognition.
                    candidate.copy(detectionConfidence = null, recognitionConfidence = null, style = null)
                } else {
                    candidate.copy(
                        sourceText = source.sourceText,
                        correctedText = candidate.correctedText ?: candidate.sourceText.takeIf {
                            it != source.sourceText
                        } ?: source.correctedText,
                        detectionConfidence = source.detectionConfidence,
                        recognitionConfidence = source.recognitionConfidence,
                        style = source.style,
                    )
                }
            },
        )
    }

    fun validatePage(page: TranslationPageResult) {
        requireContent(page.width > 0 && page.height > 0, "Invalid original dimensions")
        requireContent(page.regions.map { it.id }.toSet().size == page.regions.size, "Duplicate region identity")
        page.regions.forEach { region ->
            requireContent(region.id.isNotBlank(), "Empty region identity")
            requireContent(region.rotation.isFinite() && region.rotation in -360f..360f, "Invalid rotation")
            requireContent(region.readingOrder >= 0, "Invalid reading order")
            requireContent(
                region.aiConfidence == null || (region.aiConfidence.isFinite() && region.aiConfidence in 0f..1f),
                "Invalid AI confidence",
            )
            requireContent(
                region.points.all { it.x in 0f..page.width.toFloat() && it.y in 0f..page.height.toFloat() } &&
                    isConvexPolygon(region.points),
                "Region must be a convex, nonintersecting polygon inside the original image",
            )
        }
    }

    fun isConvexPolygon(points: List<TranslationPoint>): Boolean {
        if (points.size !in 3..32 || points.toSet().size != points.size ||
            points.any { !it.x.isFinite() || !it.y.isFinite() }
        ) {
            return false
        }
        var direction = 0
        for (i in points.indices) {
            val turn = cross(points[i], points[(i + 1) % points.size], points[(i + 2) % points.size])
            if (abs(turn) < 0.000001) return false
            val sign = if (turn > 0) 1 else -1
            if (direction != 0 && sign != direction) return false
            direction = sign
            for (j in i + 1 until points.size) {
                if (j == i + 1 || (i == 0 && j == points.lastIndex)) continue
                val a = points[i]
                val b = points[(i + 1) % points.size]
                val c = points[j]
                val d = points[(j + 1) % points.size]
                if (cross(a, b, c) * cross(a, b, d) <= 0 && cross(c, d, a) * cross(c, d, b) <= 0) return false
            }
        }
        return true
    }

    private fun cross(a: TranslationPoint, b: TranslationPoint, c: TranslationPoint): Double =
        (b.x.toDouble() - a.x) * (c.y.toDouble() - a.y) - (b.y.toDouble() - a.y) * (c.x.toDouble() - a.x)

    private fun requireContent(condition: Boolean, message: String) {
        if (!condition) throw TranslationException(TranslationFailureKind.CONTENT, message)
    }
}
