package mihon.feature.translation.provider

import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.QualityReviewCoverage
import tachiyomi.domain.translation.model.TranslationPromptStage
import tachiyomi.domain.translation.model.TranslationPromptTemplates
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings

internal data class TranslationPromptPreview(val system: String, val userTask: String, val runtimeInputs: String)

/** No image files, provider connection or queued work are needed for a settings preview. */
internal object TranslationPromptPresentation {
    fun preview(settings: TranslationSettings, stage: TranslationPromptStage): TranslationPromptPreview {
        val visual = settings.ocr.pipeline != OcrPipeline.PADDLE &&
            settings.qualityReview.coverage != QualityReviewCoverage.TEXT_ONLY
        val wireSettings = if (stage == TranslationPromptStage.QUALITY_REVIEW) {
            settings.copy(ocr = settings.ocr.copy(pipeline = if (visual) OcrPipeline.PADDLE_AI else OcrPipeline.PADDLE))
        } else {
            settings
        }
        val request = TranslationRequest("preview", "preview", wireSettings, emptyList())
        val pair = settings.prompts.stage(stage)
        val system = pair.system?.let { TranslationPromptTemplates.render(it, settings, "") } ?: when (stage) {
            TranslationPromptStage.TRANSLATION -> TranslationWireFormat.builtinInstruction(request)
            TranslationPromptStage.QUALITY_REVIEW -> QualityReviewWireFormat.builtinInstruction(
                request,
                visual,
                visual && settings.qualityReview.includeRenderedPreview,
            )
            TranslationPromptStage.GEOMETRY_CORRECTION -> GeometryCorrectionWireFormat.builtinInstruction()
        }
        val user = pair.user?.let { TranslationPromptTemplates.render(it, settings, "") } ?: when (stage) {
            TranslationPromptStage.TRANSLATION, TranslationPromptStage.QUALITY_REVIEW ->
                TranslationWireFormat.builtinTaskPrompt(request)
            TranslationPromptStage.GEOMETRY_CORRECTION ->
                GeometryCorrectionWireFormat.builtinTaskPrompt(settings.glossary, "")
        }
        val runtime = when (stage) {
            TranslationPromptStage.TRANSLATION ->
                "Image identities/dimensions; original images for AI or hybrid only; " +
                    "supplied OCR regions as source data."
            TranslationPromptStage.QUALITY_REVIEW ->
                "Saved baseline and source revision; OCR; original/preview identities, scale and presentation " +
                    "when visual review is available. Text-only review has no image attachments."
            TranslationPromptStage.GEOMETRY_CORRECTION ->
                "One rejected candidate, exact validation reasons, original image/tile and its recorded transform. " +
                    "Unavailable for pure Paddle."
        }
        return TranslationPromptPreview(system, user, runtime)
    }
}
