package tachiyomi.domain.translation

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.QualityReviewAttempt
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.QualityReviewPresentation
import tachiyomi.domain.translation.model.QualityReviewRenderEvidence
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.QualityReviewResponse
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPromptPair
import tachiyomi.domain.translation.model.TranslationPrompts
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.QualityReviewCoordinator
import tachiyomi.domain.translation.service.QualityReviewRenderer
import tachiyomi.domain.translation.service.TranslationJobSnapshot
import tachiyomi.domain.translation.service.TranslationProvider
import tachiyomi.domain.translation.service.TranslationRepository

class QualityReviewRenderCheckpointTest {
    private val settings = TranslationSettings()
    private val image = TranslationImage("page", 0, "/original", "image/png", 100, 100, "hash", 100)
    private val baseline = TranslationPageResult("page", "hash", 100, 100, emptyList(), revision = 10)
    private val initial = QualityReviewCheckpoint(
        "review",
        "job",
        "page",
        10,
        baseline,
        settings.qualityReview,
        renderPreviewRequested = true,
    )
    private var stored = initial
    private val repository = mockk<TranslationRepository>(relaxed = true)
    private val provider = mockk<TranslationProvider>()
    private val renderer = mockk<QualityReviewRenderer>(relaxed = true)
    private val evidence = QualityReviewRenderEvidence(
        image.copy(filePath = "/private/original"),
        image.copy(id = "preview", filePath = "/private/preview", contentHash = "preview-hash"),
        10,
        "renderer-2",
        "presentation-hash",
        1.0,
        1.0,
        QualityReviewPresentation(settings.style, settings.style),
    )

    private fun coordinator(): QualityReviewCoordinator {
        coEvery { repository.reviews("job") } answers { listOf(stored) }
        coEvery { repository.saveReview(any()) } answers {
            stored = firstArg()
            true
        }
        coEvery { repository.reserveReviewAttempt(any()) } answers {
            stored = firstArg()
            true
        }
        coEvery { repository.completeReview(any(), any()) } answers {
            stored = firstArg()
            true
        }
        coEvery { renderer.prepare(any()) } returns evidence
        return QualityReviewCoordinator(repository, provider, renderer)
    }

    @Test
    fun `interrupted new review pins template context before dispatch and reuses it after restart`() = runTest {
        stored =
            initial.copy(
                prompts = TranslationPromptPair("Review {{chapter_context}}", "User"),
                renderPreviewRequested = false,
            )
        val first = coordinator()
        coEvery { provider.review(any()) } coAnswers {
            firstArg<QualityReviewRequest>().context shouldBe "Original context"
            stored.promptContext shouldBe "Original context"
            throw CancellationException("process interruption")
        }
        try {
            first.run(stored, settings, image, "Original context")
            error("Cancellation must propagate")
        } catch (_: CancellationException) {
            stored.attempts.size shouldBe 1
        }
        val resumed = coordinator()
        coEvery { provider.review(any()) } coAnswers {
            firstArg<QualityReviewRequest>().context shouldBe "Original context"
            QualityReviewResponse(baseline, emptyList())
        }
        resumed.run(stored, settings, image, "New pages completed since interruption")
        stored.state shouldBe QualityReviewState.PASSED
        stored.attempts.size shouldBe 2
        stored.promptContext shouldBe "Original context"
    }

    @Test
    fun `resumed review uses its pinned prompts when settings change`() = runTest {
        val pinned = TranslationPromptPair("Saved review system", "Saved review user")
        stored = initial.copy(prompts = pinned, renderPreviewRequested = false)
        val coordinator = coordinator()
        var calls = 0
        coEvery { provider.review(any()) } coAnswers {
            firstArg<QualityReviewRequest>().settings.prompts.qualityReview shouldBe pinned
            if (++calls == 1) throw TranslationException(TranslationFailureKind.TRANSIENT, "fixture")
            QualityReviewResponse(baseline, emptyList())
        }
        coordinator.run(
            stored,
            settings.copy(
                prompts = TranslationPrompts(
                    qualityReview = TranslationPromptPair("New unrelated wording", null),
                ),
            ),
            image,
            "",
        )
        stored.state shouldBe QualityReviewState.PASSED
        stored.attempts.size shouldBe 2
        stored.prompts shouldBe pinned
    }

    @Test
    fun `legacy review uses saved job prompts and decodes no execution override`() = runTest {
        val pair = TranslationPromptPair("Job review", null)
        stored = initial.copy(renderPreviewRequested = false)
        val coordinator = coordinator()
        coEvery { provider.review(any()) } coAnswers {
            firstArg<QualityReviewRequest>().settings.prompts.qualityReview shouldBe pair
            QualityReviewResponse(baseline, emptyList())
        }
        coordinator.run(stored, settings.copy(prompts = TranslationPrompts(qualityReview = pair)), image, "")
        val json = Json { encodeDefaults = true }
        val encoded = json.parseToJsonElement(json.encodeToString(initial)).jsonObject
        val legacy = json.decodeFromString<QualityReviewCheckpoint>(
            JsonObject(encoded - setOf("prompts", "executionSettings")).toString(),
        )
        legacy.prompts shouldBe null
        legacy.executionSettings shouldBe null
    }

    @Test
    fun `explicit imported review pins its own execution settings and respects text-only input`() = runTest {
        val execution = settings.copy(
            provider = settings.provider.copy(model = "configured-text-model"),
            ocr = settings.ocr.copy(pipeline = OcrPipeline.PADDLE),
        )
        stored = initial.copy(executionSettings = execution, prompts = TranslationPromptPair("Imported review", null))
        val coordinator = coordinator()
        coEvery { provider.review(any()) } coAnswers {
            val request = firstArg<QualityReviewRequest>()
            request.settings.provider.model shouldBe "configured-text-model"
            request.settings.ocr.pipeline shouldBe OcrPipeline.PADDLE
            request.settings.prompts.qualityReview.system shouldBe "Imported review"
            request.renderEvidence shouldBe null
            QualityReviewResponse(baseline, emptyList(), visualComplete = false)
        }
        coordinator.run(stored, settings, image, "")
        coVerify(exactly = 0) { renderer.prepare(any()) }
        stored.state shouldBe QualityReviewState.PASSED
    }

    @Test
    fun `render evidence is durable before dispatch and reused after transient failure`() = runTest {
        val coordinator = coordinator()
        var calls = 0
        coEvery { provider.review(any()) } coAnswers {
            val request = firstArg<QualityReviewRequest>()
            request.renderEvidence shouldBe evidence
            stored.renderEvidence shouldBe evidence
            request.image shouldBe evidence.original
            if (++calls == 1) throw TranslationException(TranslationFailureKind.TRANSIENT, "fixture")
            QualityReviewResponse(baseline, emptyList())
        }
        coordinator.run(initial, settings, image, "context")
        stored.state shouldBe QualityReviewState.PASSED
        stored.attempts.size shouldBe 2
        coVerify(exactly = 1) { renderer.prepare(any()) }
        coVerify(exactly = 1) { renderer.cleanup(evidence) }
    }

    @Test
    fun `legacy checkpoint disables auxiliary images despite new settings default`() = runTest {
        stored = initial.copy(renderPreviewRequested = false)
        val coordinator = coordinator()
        coEvery { provider.review(any()) } coAnswers {
            firstArg<QualityReviewRequest>().settings.qualityReview.includeRenderedPreview shouldBe false
            firstArg<QualityReviewRequest>().renderEvidence shouldBe null
            QualityReviewResponse(baseline, emptyList())
        }
        coordinator.run(stored, settings, image, "")
        stored.state shouldBe QualityReviewState.PASSED
        coVerify(exactly = 0) { renderer.prepare(any()) }
    }

    @Test
    fun `Paddle review never prepares or attaches visual evidence`() = runTest {
        val coordinator = coordinator()
        coEvery { provider.review(any()) } coAnswers {
            firstArg<QualityReviewRequest>().renderEvidence shouldBe null
            firstArg<QualityReviewRequest>().settings.qualityReview.includeRenderedPreview shouldBe false
            QualityReviewResponse(baseline, emptyList(), visualComplete = false)
        }
        coordinator.run(initial, settings.copy(ocr = settings.ocr.copy(pipeline = OcrPipeline.PADDLE)), image, "")
        stored.state shouldBe QualityReviewState.PASSED
        coVerify(exactly = 0) { renderer.prepare(any()) }
    }

    @Test
    fun `failed rendering retains baseline without provider dispatch`() = runTest {
        val coordinator = coordinator()
        coEvery { renderer.prepare(any()) } throws TranslationException(TranslationFailureKind.LIMIT, "fixture cap")
        coordinator.run(initial, settings, image, "")
        stored.state shouldBe QualityReviewState.INCOMPLETE
        stored.beforeResult shouldBe baseline
        stored.attempts.size shouldBe 0
        coVerify(exactly = 0) { provider.review(any()) }
    }

    @Test
    fun `legacy serialized checkpoints do not acquire render policy`() {
        val json = Json { encodeDefaults = true }
        val encoded = json.parseToJsonElement(json.encodeToString(initial)).jsonObject
        val old = JsonObject(encoded - setOf("renderPreviewRequested", "renderStyle", "renderEvidence"))
        json.decodeFromString<QualityReviewCheckpoint>(old.toString()).renderPreviewRequested shouldBe false
        QualityReviewSettings().includeRenderedPreview shouldBe true
    }

    @Test
    fun `legacy content snapshots remain unset on checkpoints and preserve sound effects in prepared evidence`() {
        val json = Json { encodeDefaults = true }
        val checkpoint = json.parseToJsonElement(json.encodeToString(initial)).jsonObject
        val oldCheckpoint = JsonObject(checkpoint - "contentPolicy")
        json.decodeFromString<QualityReviewCheckpoint>(oldCheckpoint.toString()).contentPolicy shouldBe null
        val presentation = json.parseToJsonElement(json.encodeToString(evidence.presentation)).jsonObject
        val oldPresentation = JsonObject(presentation - "contentPolicy")
        json.decodeFromString<QualityReviewPresentation>(oldPresentation.toString()).contentPolicy shouldBe
            TranslationContentPolicy.Legacy
    }

    @Test
    fun `existing queued job keeps its original only policy but new jobs retain preview`() {
        val json = Json { encodeDefaults = true }
        val job = TranslationJob("job", 1, 1, "series", "chapter", settings)
        val encoded = json.parseToJsonElement(json.encodeToString(job)).jsonObject
        val savedSettings = encoded.getValue("settings").jsonObject
        val oldReview = JsonObject(savedSettings.getValue("qualityReview").jsonObject - "includeRenderedPreview")
        val old = JsonObject(encoded + ("settings" to JsonObject(savedSettings + ("qualityReview" to oldReview))))
        val legacy = TranslationJobSnapshot.decode(json, old.toString()).settings.qualityReview
        legacy.enabled shouldBe true
        legacy.includeRenderedPreview shouldBe false
        TranslationJobSnapshot.decode(json, encoded.toString()).settings.qualityReview.includeRenderedPreview shouldBe
            true
    }

    @Test
    fun `interrupted dispatch resumes pinned evidence with no free attempt or render`() = runTest {
        val first = coordinator()
        coEvery { provider.review(any()) } throws CancellationException("process interruption")
        try {
            first.run(initial, settings, image, "")
            error("Cancellation must propagate")
        } catch (_: CancellationException) {
            stored.state shouldBe QualityReviewState.RUNNING
            stored.attempts.size shouldBe 1
            stored.renderEvidence shouldBe evidence
        }
        coVerify(exactly = 0) { renderer.cleanup(any()) }
        val resumed = coordinator()
        coEvery { provider.review(any()) } coAnswers {
            firstArg<QualityReviewRequest>().renderEvidence shouldBe evidence
            firstArg<QualityReviewRequest>().settings.style shouldBe settings.style
            QualityReviewResponse(baseline, emptyList())
        }
        resumed.run(stored, settings.copy(style = settings.style.copy(fontSize = 44f)), image, "")
        stored.state shouldBe QualityReviewState.PASSED
        stored.attempts.size shouldBe 2
        stored.attempts.first().completedAt shouldBe null
        coVerify(exactly = 1) { renderer.prepare(any()) }
        coVerify(exactly = 1) { renderer.cleanup(evidence) }
    }

    @Test
    fun `interrupted preparation keeps its style and reserves no transport attempt`() = runTest {
        val first = coordinator()
        coEvery { renderer.prepare(any()) } throws CancellationException("preparation interrupted")
        try {
            first.run(initial, settings, image, "")
            error("Cancellation must propagate")
        } catch (_: CancellationException) {
            stored.state shouldBe QualityReviewState.QUEUED
            stored.renderStyle shouldBe settings.style
            stored.renderEvidence shouldBe null
            stored.attempts.size shouldBe 0
            stored.beforeResult shouldBe baseline
        }
        coVerify(exactly = 0) { provider.review(any()) }
        val resumed = coordinator()
        coEvery { renderer.prepare(any()) } coAnswers {
            firstArg<QualityReviewRequest>().settings.style shouldBe settings.style
            evidence
        }
        coEvery { provider.review(any()) } returns QualityReviewResponse(baseline, emptyList())
        resumed.run(stored, settings.copy(style = settings.style.copy(fontSize = 44f)), image, "")
        stored.state shouldBe QualityReviewState.PASSED
        stored.attempts.size shouldBe 1
        coVerify(exactly = 2) { renderer.prepare(any()) }
    }

    @Test
    fun `manual revision change during rendering discards unpublished evidence`() = runTest {
        val coordinator = coordinator()
        coEvery { repository.saveReview(match { it.renderEvidence != null }) } returns false
        coordinator.run(initial, settings, image, "")
        stored.renderEvidence shouldBe null
        stored.beforeResult shouldBe baseline
        coVerify(exactly = 0) { provider.review(any()) }
        coVerify(exactly = 1) { renderer.cleanup(evidence) }
    }

    @Test
    fun `exhausted uncertain attempts do not prepare another preview`() = runTest {
        stored = initial.copy(attempts = listOf(QualityReviewAttempt(1, 1), QualityReviewAttempt(2, 2)))
        val coordinator = coordinator()
        coordinator.run(stored, settings, image, "")
        stored.state shouldBe QualityReviewState.NEEDS_REVIEW
        stored.attempts.size shouldBe 2
        coVerify(exactly = 0) { renderer.prepare(any()) }
        coVerify(exactly = 0) { provider.review(any()) }
    }

    @Test
    fun `mismatched rendered source never reaches provider`() = runTest {
        val coordinator = coordinator()
        val unrelated = evidence.copy(original = evidence.original.copy(id = "other-page"))
        coEvery { renderer.prepare(any()) } returns unrelated
        coordinator.run(initial, settings, image, "")
        stored.state shouldBe QualityReviewState.INCOMPLETE
        stored.beforeResult shouldBe baseline
        coVerify(exactly = 0) { provider.review(any()) }
        coVerify(exactly = 1) { renderer.cleanup(unrelated) }
    }
}
