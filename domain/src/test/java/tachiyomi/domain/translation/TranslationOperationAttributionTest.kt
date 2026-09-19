package tachiyomi.domain.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.TranslationOperationAttribution

class TranslationOperationAttributionTest {
    private val job =
        TranslationJob(
            "job",
            1,
            2,
            "Series",
            "Chapter",
            TranslationSettings(
                provider = ProviderSettings(
                    kind = TranslationProviderKind.OPENAI,
                    baseUrl = "https://api.groq.com/openai/v1",
                    model = "first-model",
                ),
            ),
        )

    @Test
    fun `new operations pin their real job provider but explicit review settings take precedence`() {
        val operation = TranslationOperation("operation", job.id, TranslationStage.SAVE)
        val captured = TranslationOperationAttribution.capture(operation, null, job)
        captured.provider shouldBe "GROQ"
        captured.model shouldBe "first-model"
        val review = operation.copy(provider = "VERTEX_SERVICE_ACCOUNT", model = "review-model")
        TranslationOperationAttribution.capture(review, null, job) shouldBe review
    }

    @Test
    fun `updates preserve measured attribution and never invent it for historical rows`() {
        val operation = TranslationOperation("operation", job.id, TranslationStage.SAVE)
        val original = operation.copy(provider = "OPENAI", model = "original-model")
        val updated = TranslationOperationAttribution.capture(operation, original, job)
        updated.provider shouldBe "OPENAI"
        updated.model shouldBe "original-model"
        TranslationOperationAttribution.capture(original, operation, job).provider shouldBe null
        TranslationOperationAttribution.capture(operation, null, null).provider shouldBe null
    }
}
