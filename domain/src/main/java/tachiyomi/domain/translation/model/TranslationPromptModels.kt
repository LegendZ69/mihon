package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

/** Null retains built-in task wording; an empty string is an intentional replacement. */
@Serializable
data class TranslationPromptPair(val system: String? = null, val user: String? = null) {
    fun validate() {
        system?.let(TranslationPromptTemplates::validate)
        user?.let(TranslationPromptTemplates::validate)
    }
}

@Serializable
data class TranslationPrompts(
    val translation: TranslationPromptPair = TranslationPromptPair(),
    val qualityReview: TranslationPromptPair = TranslationPromptPair(),
    val geometryCorrection: TranslationPromptPair = TranslationPromptPair(),
) {
    fun validate() {
        translation.validate()
        qualityReview.validate()
        geometryCorrection.validate()
    }

    fun stage(stage: TranslationPromptStage): TranslationPromptPair = when (stage) {
        TranslationPromptStage.TRANSLATION -> translation
        TranslationPromptStage.QUALITY_REVIEW -> qualityReview
        TranslationPromptStage.GEOMETRY_CORRECTION -> geometryCorrection
    }

    fun withStage(stage: TranslationPromptStage, value: TranslationPromptPair): TranslationPrompts = when (stage) {
        TranslationPromptStage.TRANSLATION -> copy(translation = value)
        TranslationPromptStage.QUALITY_REVIEW -> copy(qualityReview = value)
        TranslationPromptStage.GEOMETRY_CORRECTION -> copy(geometryCorrection = value)
    }
}

@Serializable
enum class TranslationPromptStage { TRANSLATION, QUALITY_REVIEW, GEOMETRY_CORRECTION }

/** Literal substitution only: inserted source values are never interpreted as another template. */
object TranslationPromptTemplates {
    val variables = setOf(
        "source_language",
        "target_language",
        "glossary",
        "chapter_context",
        "additional_instructions",
        "content_policy",
    )

    // Android uses ICU regex syntax, which requires literal closing braces to be escaped too.
    private val placeholder = Regex("\\{\\{([^{}]*)\\}\\}")

    fun validate(template: String) {
        require(template.length <= 100_000) { "A prompt cannot exceed 100,000 characters" }
        placeholder.findAll(template).forEach {
            require(it.groupValues[1] in variables) { "Unknown prompt variable: ${it.value}" }
        }
        val remainder = placeholder.replace(template, "")
        require("{{" !in remainder && "}}" !in remainder) { "Unclosed or malformed prompt variable" }
    }

    fun render(template: String, settings: TranslationSettings, chapterContext: String): String {
        validate(template)
        val values = mapOf(
            "source_language" to settings.sourceLanguage,
            "target_language" to settings.targetLanguage,
            "glossary" to settings.glossary,
            "chapter_context" to chapterContext,
            "additional_instructions" to settings.instructions,
            "content_policy" to if (settings.contentPolicy.ignoreSoundEffects) {
                "Ignore non-spoken sound effects; preserve dialogue, narration, names, meaningful signs and titles."
            } else {
                "Include meaningful sound effects, dialogue, narration, names, signs and titles."
            },
        )
        return placeholder.replace(template) { values.getValue(it.groupValues[1]) }
    }
}
