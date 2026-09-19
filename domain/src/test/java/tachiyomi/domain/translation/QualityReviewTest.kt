package tachiyomi.domain.translation

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.QualityReviewAttempt
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.QualityReviewFinding
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.QualityReviewResponse
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationUsage
import tachiyomi.domain.translation.service.QualityReviewCoordinator
import tachiyomi.domain.translation.service.QualityReviewValidation
import tachiyomi.domain.translation.service.TranslationJobSnapshot
import tachiyomi.domain.translation.service.TranslationProvider
import tachiyomi.domain.translation.service.TranslationRepository

class QualityReviewTest {
    private val region = TextRegion(
        "passage",
        listOf(
            TranslationPoint(2f, 2f),
            TranslationPoint(70f, 2f),
            TranslationPoint(70f, 80f),
            TranslationPoint(2f, 80f),
        ),
        "raw source",
        "initial translation",
        detectionConfidence = 0.9f,
        recognitionConfidence = 0.8f,
    )
    private val baseline =
        TranslationPageResult(
            "page",
            "hash",
            100,
            100,
            listOf(region),
            OcrPageResult("page", listOf(region)),
            revision = 10,
        )
    private val settings = TranslationSettings()
    private val image = TranslationImage("page", 0, "/fixture", "image/png", 100, 100, "hash", 100)
    private val checkpoint = QualityReviewCheckpoint("review", "job", "page", 10, baseline, settings.qualityReview)
    private val repository = mockk<TranslationRepository>(relaxed = true)
    private val provider = mockk<TranslationProvider>()
    private var stored = checkpoint
    private var result = baseline

    private fun coordinator(): QualityReviewCoordinator {
        coEvery { repository.reviews("job") } answers { listOf(stored) }
        coEvery { repository.saveReview(any()) } coAnswers {
            val update = firstArg<QualityReviewCheckpoint>()
            if (!stored.state.pending || result.revision != update.sourceRevision) {
                false
            } else {
                stored = update
                true
            }
        }
        coEvery { repository.reserveReviewAttempt(any()) } coAnswers {
            val update = firstArg<QualityReviewCheckpoint>()
            if (update.attempts.size != stored.attempts.size + 1 || !stored.state.pending) {
                false
            } else {
                stored = update
                true
            }
        }
        coEvery { repository.completeReview(any(), any()) } coAnswers {
            val update = firstArg<QualityReviewCheckpoint>()
            if (!stored.state.pending || result.revision != update.sourceRevision) {
                false
            } else {
                stored = update
                secondArg<TranslationPageResult?>()?.let { result = it.copy(revision = result.revision + 1) }
                true
            }
        }
        return QualityReviewCoordinator(repository, provider)
    }

    private fun repair() = QualityReviewResponse(
        baseline.copy(
            regions = listOf(
                region.copy(sourceText = "AI corrected source", translatedText = "repaired", detectionConfidence = 1f),
            ),
        ),
        emptyList(),
    )

    @Test
    fun `new application default enabled but old queued snapshots remain disabled`() {
        val json = Json { encodeDefaults = true }
        val job = TranslationJob("job", 1, 1, "series", "chapter", settings)
        val full = json.parseToJsonElement(json.encodeToString(job)).jsonObject
        val old = JsonObject(full + ("settings" to JsonObject(full.getValue("settings").jsonObject - "qualityReview")))
        TranslationJobSnapshot.decode(json, old.toString()).settings.qualityReview.enabled shouldBe false
        TranslationJobSnapshot.decode(json, full.toString()).settings.qualityReview.enabled shouldBe true
        TranslationSettings().qualityReview.maxTransportAttempts shouldBe 2
    }

    @Test
    fun `selected review preserves its content policy across an older job snapshot`() = runTest {
        stored = checkpoint.copy(contentPolicy = TranslationContentPolicy())
        val coordinator = coordinator()
        var observedPolicy: TranslationContentPolicy? = null
        coEvery { provider.review(any()) } coAnswers {
            observedPolicy = firstArg<QualityReviewRequest>().settings.contentPolicy
            QualityReviewResponse(baseline, emptyList())
        }

        coordinator.run(stored, settings.copy(contentPolicy = TranslationContentPolicy.Legacy), image, "")

        observedPolicy shouldBe TranslationContentPolicy()
        result shouldBe baseline
        stored.state shouldBe QualityReviewState.PASSED
    }

    @Test
    fun `legacy queued job keeps sound effects while old editable preferences adopt ignore`() {
        val json = Json { encodeDefaults = true }
        val job = TranslationJob("job", 1, 1, "series", "chapter", settings)
        val encoded = json.parseToJsonElement(json.encodeToString(job)).jsonObject
        val oldSettings = JsonObject(encoded.getValue("settings").jsonObject - "contentPolicy")
        val oldJob = JsonObject(encoded + ("settings" to oldSettings))

        TranslationJobSnapshot.decode(json, oldJob.toString()).settings.contentPolicy.ignoreSoundEffects shouldBe false
        json.decodeFromString<TranslationSettings>(oldSettings.toString()).contentPolicy.ignoreSoundEffects shouldBe
            true
        TranslationJobSnapshot.decode(json, encoded.toString()).settings.contentPolicy.ignoreSoundEffects shouldBe true
    }

    @Test
    fun `repair preserves raw OCR scores and moves transcription change to corrections`() = runTest {
        val coordinator = coordinator()
        coEvery { provider.review(any()) } coAnswers {
            stored.state shouldBe QualityReviewState.RUNNING
            stored.attempts.size shouldBe 1
            result shouldBe baseline
            repair()
        }
        coordinator.run(checkpoint, settings, image, "chapter context")
        result.rawOcr shouldBe baseline.rawOcr
        result.regions.single().sourceText shouldBe region.sourceText
        result.regions.single().correctedText shouldBe "AI corrected source"
        result.regions.single().detectionConfidence shouldBe 0.9f
        stored.state shouldBe QualityReviewState.REPAIRED
        stored.beforeResult shouldBe baseline
        coordinator.run(checkpoint, settings, image, "")
        coVerify(exactly = 1) { provider.review(any()) }
    }

    private suspend fun metadataAssessment(
        confidence: Boolean,
        language: Boolean,
        findings: List<QualityReviewFinding> = emptyList(),
        visualComplete: Boolean = true,
    ) {
        val coordinator = coordinator()
        val usage = TranslationUsage(inputTokens = 11, outputTokens = 12, reasoningTokens = 13)
        coEvery { provider.review(any()) } returns QualityReviewResponse(
            baseline.copy(
                detectedLanguage = if (language) "mul" else baseline.detectedLanguage,
                regions = baseline.regions.map { if (confidence) it.copy(aiConfidence = 0.99f) else it },
            ),
            findings,
            usage = usage,
            visualComplete = visualComplete,
        )

        coordinator.run(checkpoint, settings, image, "")

        stored.state shouldBe when {
            !visualComplete -> QualityReviewState.INCOMPLETE
            findings.isNotEmpty() -> QualityReviewState.NEEDS_REVIEW
            else -> QualityReviewState.PASSED
        }
        result shouldBe baseline
        stored.beforeResult shouldBe baseline
        stored.sourceRevision shouldBe baseline.revision
        stored.repairedRevision shouldBe null
        coVerify(exactly = 1) { repository.completeReview(any(), null) }
        stored.findings shouldBe findings
        stored.attempts.size shouldBe 1
        stored.attempts.single().usage shouldBe usage
        (stored.attempts.single().completedAt != null) shouldBe true
        coordinator.run(checkpoint, settings, image, "")
        coVerify(exactly = 1) { provider.review(any()) }
    }

    @Test
    fun `confidence-only assessment preserves the saved page and does not report repair`() = runTest {
        metadataAssessment(confidence = true, language = false)
    }

    @Test
    fun `language-only assessment preserves the saved page and does not report repair`() = runTest {
        metadataAssessment(confidence = false, language = true)
    }

    @Test
    fun `confidence and language metadata alone preserve the saved revision`() = runTest {
        metadataAssessment(confidence = true, language = true)
    }

    @Test
    fun `metadata-only assessment with findings needs review without replacing the page`() = runTest {
        metadataAssessment(
            confidence = true,
            language = true,
            findings = listOf(
                QualityReviewFinding("CLIPPED_TEXT", "Passage still needs inspection", listOf(region.id)),
            ),
        )
    }

    @Test
    fun `incomplete visual coverage wins over metadata-only assessment`() = runTest {
        metadataAssessment(confidence = true, language = true, visualComplete = false)
    }

    @Test
    fun `material geometry correction still commits a repair with its assessment metadata`() = runTest {
        val coordinator = coordinator()
        val moved = region.copy(points = region.points.map { it.copy(x = it.x + 1) }, aiConfidence = 0.99f)
        coEvery { provider.review(any()) } returns QualityReviewResponse(
            baseline.copy(regions = listOf(moved), detectedLanguage = "mul"),
            emptyList(),
        )

        coordinator.run(checkpoint, settings, image, "")

        stored.state shouldBe QualityReviewState.REPAIRED
        result.revision shouldBe baseline.revision + 1
        result.regions.single().points shouldBe moved.points
        result.regions.single().aiConfidence shouldBe 0.99f
        result.detectedLanguage shouldBe "mul"
        result.rawOcr shouldBe baseline.rawOcr
        stored.attempts.size shouldBe 1
        coVerify(exactly = 1) { provider.review(any()) }
    }

    @Test
    fun `manual edit during a delayed repair wins the revision check`() = runTest {
        val coordinator = coordinator()
        val response = CompletableDeferred<Unit>()
        coEvery { provider.review(any()) } coAnswers {
            response.await()
            repair()
        }
        val task = launch { coordinator.run(checkpoint, settings, image, "") }
        runCurrent()
        val edited = baseline.copy(regions = listOf(region.copy(translatedText = "manual")), revision = 11)
        result = edited
        response.complete(Unit)
        advanceUntilIdle()
        task.join()
        result shouldBe edited
    }

    @Test
    fun `interrupted requests keep their reserved attempt and only one restart attempt remains`() = runTest {
        val coordinator = coordinator()
        val response = CompletableDeferred<Unit>()
        coEvery { provider.review(any()) } coAnswers {
            withContext(NonCancellable) { response.await() }
            repair()
        }
        val task = launch { coordinator.run(checkpoint, settings, image, "") }
        runCurrent()
        task.cancel()
        response.complete(Unit)
        advanceUntilIdle()
        result shouldBe baseline
        stored.attempts.size shouldBe 1
        stored.attempts.single().completedAt shouldBe null
        coEvery { provider.review(any()) } throws TranslationException(TranslationFailureKind.TRANSIENT, "offline")
        coordinator.run(checkpoint, settings, image, "")
        stored.attempts.size shouldBe 2
        stored.state shouldBe QualityReviewState.NEEDS_REVIEW
        result shouldBe baseline
        coordinator.run(checkpoint, settings, image, "")
        coVerify(exactly = 2) { provider.review(any()) }
    }

    @Test
    fun `malformed content never starts another repair pass`() = runTest {
        val coordinator = coordinator()
        coEvery { provider.review(any()) } returns repair().copy(candidate = baseline.copy(imageId = "wrong"))
        coordinator.run(checkpoint, settings, image, "")
        stored.state shouldBe QualityReviewState.NEEDS_REVIEW
        stored.attempts.size shouldBe 1
        result shouldBe baseline
    }

    @Test
    fun `authentication pauses without falling into content retry or losing translation`() = runTest {
        val coordinator = coordinator()
        coEvery { provider.review(any()) } throws
            TranslationException(TranslationFailureKind.AUTHENTICATION, "credential unavailable")
        runCatching { coordinator.run(checkpoint, settings, image, "") }.isFailure shouldBe true
        stored.state shouldBe QualityReviewState.PAUSED
        stored.attempts.size shouldBe 1
        result shouldBe baseline
    }

    @Test
    fun `full image over limit and incomplete visual review retain baseline`() = runTest {
        val coordinator = coordinator()
        coEvery { provider.review(any()) } throws
            TranslationException(TranslationFailureKind.LIMIT, "original exceeds inline limit")
        coordinator.run(checkpoint, settings, image, "")
        stored.state shouldBe QualityReviewState.INCOMPLETE
        result shouldBe baseline
        stored = checkpoint
        coEvery { provider.review(any()) } returns repair().copy(visualComplete = false)
        coordinator.run(checkpoint, settings, image, "")
        stored.state shouldBe QualityReviewState.INCOMPLETE
        result shouldBe baseline
    }

    @Test
    fun `throttling has two attempts and exhausted restart cannot dispatch`() = runTest {
        val coordinator = coordinator()
        coEvery { provider.review(any()) } throws TranslationException(TranslationFailureKind.RATE_LIMIT, "limited")
        coordinator.run(checkpoint, settings, image, "")
        stored.state shouldBe QualityReviewState.NEEDS_REVIEW
        stored.attempts.size shouldBe 2
        stored = checkpoint.copy(attempts = listOf(QualityReviewAttempt(1, 1), QualityReviewAttempt(2, 2)))
        coordinator.run(checkpoint, settings, image, "")
        coVerify(exactly = 2) { provider.review(any()) }
    }

    @Test
    fun `text only review rejects invented regions and changed geometry`() {
        val request =
            QualityReviewRequest(
                "job",
                "review",
                settings.copy(ocr = OcrSettings(pipeline = OcrPipeline.PADDLE)),
                image,
                baseline,
            )
        listOf(
            baseline.copy(regions = listOf(region.copy(rotation = 90f))),
            baseline.copy(regions = listOf(region, region.copy(id = "invented"))),
            baseline.copy(regions = listOf(region.copy(points = region.points.map { it.copy(x = it.x + 1) }))),
            baseline.copy(regions = listOf(region.copy(points = region.points + region.points.first()))),
        ).forEach {
            runCatching {
                QualityReviewValidation.candidate(request, QualityReviewResponse(it, emptyList()))
            }.isFailure shouldBe
                true
        }
    }

    @Test
    fun `visual review accepts a redundant closing vertex without changing the outline or raw OCR`() {
        val request = QualityReviewRequest("job", "review", settings, image, baseline)
        val candidate = baseline.copy(
            regions = listOf(region.copy(points = region.points + region.points.first(), translatedText = "repaired")),
        )

        val accepted = QualityReviewValidation.candidate(request, QualityReviewResponse(candidate, emptyList()))

        accepted.regions.single().points shouldBe region.points
        accepted.regions.single().translatedText shouldBe "repaired"
        accepted.rawOcr shouldBe baseline.rawOcr
        accepted.revision shouldBe baseline.revision
    }

    @Test
    fun `convex geometry accepts triangles and tilted boxes but rejects crossed concave or degenerate regions`() {
        QualityReviewValidation.isConvexPolygon(region.points) shouldBe true
        QualityReviewValidation.isConvexPolygon(region.points.take(3)) shouldBe true
        QualityReviewValidation.isConvexPolygon(
            listOf(region.points[0], region.points[2], region.points[1], region.points[3]),
        ) shouldBe
            false
        QualityReviewValidation.isConvexPolygon(
            listOf(TranslationPoint(1f, 1f), TranslationPoint(2f, 2f), TranslationPoint(3f, 3f)),
        ) shouldBe
            false
        QualityReviewValidation.isConvexPolygon(region.points.map { it.copy(x = Float.NaN) }) shouldBe false
    }
}
