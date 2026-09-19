package mihon.feature.translation.acceptance

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.zacsweers.metro.createGraphFactory
import eu.kanade.tachiyomi.BuildConfig
import eu.kanade.tachiyomi.util.system.activeNetworkState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mihon.app.di.AppGraph
import mihon.core.metro.GraphProvider
import mihon.feature.translation.overlay.AndroidQualityReviewRenderer
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.OpenAiDialect
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.QualityReviewFinding
import tachiyomi.domain.translation.model.QualityReviewRenderEvidence
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.QualityReviewResponse
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationBatch
import tachiyomi.domain.translation.model.TranslationConcurrency
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationLogSettings
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.QualityReviewCoordinator
import tachiyomi.domain.translation.service.QualityReviewRenderer
import tachiyomi.domain.translation.service.TranslationProvider
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Real minified graph/SQL/manager/gateway; synthetic loopback only, never process-global WorkManager. */
@RunWith(AndroidJUnit4::class)
class MinifiedQualityReviewAcceptanceTest {
    private val json = Json {
        encodeDefaults = true
        prettyPrint = true
    }

    @Test
    fun aFreshTranslationSchedulesOneReviewAndUndoRestoresItsBaseline() = acceptance("automatic") { fixture ->
        val job = fixture.seed("review-repair", includeRenderedPreview = false)
        fixture.graph.translationManager.runQueue()
        val review = fixture.reviews(job).single()
        assertEquals(QualityReviewState.REPAIRED, review.state)
        assertEquals(1, review.attempts.size)
        assertTrue(review.visualComplete)
        assertEquals(TranslationJobState.COMPLETED, fixture.current(job).state)
        val repaired = fixture.result(job)
        assertTrue(repaired.regions.single().translatedText.startsWith("Fixture reviewed "))
        assertEquals(review.beforeResult.regions.single().sourceText, repaired.regions.single().sourceText)
        assertEquals(review.beforeResult.imageHash, repaired.imageHash)
        assertEquals(1, fixture.requests(job, "generateContent"))
        assertEquals(1, fixture.requests(job, "qualityReview"))
        fixture.snapshot(job, "automatic_repair")
        fixture.graph.translationManager.runQueue()
        assertEquals(1, fixture.requests(job, "qualityReview"))
        fixture.graph.translationManager.undoRepair(job.id, "0")
        assertEquals(review.beforeResult, fixture.result(job).copy(revision = review.beforeResult.revision))
        assertEquals(QualityReviewState.UNDONE, fixture.reviews(job).single().state)
        fixture.snapshot(job, "undone_without_resubmission")
    }

    @Test
    fun bTextReviewPreservesRawOcrAndDuplicateDeliveryDoesNotCreateMoreWork() = acceptance(
        "text-and-failure",
    ) { fixture ->
        val job = fixture.seed(
            "review-repair",
            saved = true,
            pipeline = OcrPipeline.PADDLE,
            includeRenderedPreview = false,
        )
        val baseline = fixture.result(job)
        val repository = fixture.graph.translationRepository
        repository.saveNewResult(job.id, baseline, job.settings.qualityReview)
        assertNull(repository.createReview(job.id, "0", job.settings.qualityReview))
        assertNull(repository.createReview(job.id, "0", job.settings.qualityReview))
        assertEquals(1, fixture.reviews(job).size)
        assertTrue(File(repository.images(job.id).single().filePath).delete())
        fixture.graph.translationManager.runQueue()
        val review = fixture.reviews(job).single()
        val repaired = fixture.result(job)
        assertEquals(QualityReviewState.REPAIRED, review.state)
        assertFalse(review.visualComplete)
        assertEquals(baseline.rawOcr, repaired.rawOcr)
        assertEquals(baseline.regions.single().sourceText, repaired.regions.single().sourceText)
        assertEquals(baseline.regions.single().correctedText, repaired.regions.single().correctedText)
        assertEquals(baseline.regions.single().points, repaired.regions.single().points)
        assertEquals(baseline.regions.single().rotation, repaired.regions.single().rotation, 0f)
        assertEquals(baseline.regions.single().recognitionConfidence, repaired.regions.single().recognitionConfidence)
        assertEquals(0, fixture.requests(job, "generateContent"))
        assertEquals(1, fixture.requests(job, "qualityReview"))
        fixture.snapshot(job, "text_only_repair_without_original_file")
        fixture.graph.translationManager.undoRepair(job.id, "0")
        assertEquals(baseline, fixture.result(job).copy(revision = baseline.revision))
        fixture.snapshot(job, "raw_ocr_undo")

        val invalid = fixture.seed("review-malformed", saved = true, includeRenderedPreview = false)
        val preserved = fixture.result(invalid)
        fixture.graph.translationManager.runQueue()
        assertEquals(QualityReviewState.NEEDS_REVIEW, fixture.reviews(invalid).single().state)
        assertEquals(1, fixture.reviews(invalid).single().attempts.size)
        assertEquals(preserved, fixture.result(invalid))
        assertEquals(0, fixture.requests(invalid, "generateContent"))
        assertEquals(1, fixture.requests(invalid, "qualityReview"))
        assertEquals(TranslationJobState.COMPLETED, fixture.current(invalid).state)
        fixture.snapshot(invalid, "malformed_review_preserves_completed_page")
    }

    @Test
    fun cInterruptedReviewKeepsItsSpentAttemptAndLateCandidateCannotOverwriteAnEdit() =
        acceptance("interrupted-and-cas") { fixture ->
            val job = fixture.seed("review-delay", saved = true, includeRenderedPreview = false)
            val baseline = fixture.result(job)
            coroutineScope {
                val running = launch { fixture.graph.translationManager.runQueue() }
                try {
                    fixture.awaitReviewRequest(job)
                    assertEquals(1, fixture.reviews(job).single().attempts.size)
                    assertEquals(baseline, fixture.result(job))
                    fixture.snapshot(job, "before_coroutine_interruption")
                } finally {
                    running.cancelAndJoin()
                }
            }
            fixture.reopen()
            val interrupted = fixture.reviews(job).single()
            assertEquals(1, interrupted.attempts.size)
            assertNull(interrupted.attempts.single().completedAt)
            assertEquals(baseline, fixture.result(job))
            fixture.graph.translationManager.runQueue()
            val recovered = fixture.reviews(job).single()
            assertEquals(QualityReviewState.REPAIRED, recovered.state)
            assertEquals(listOf(1, 2), recovered.attempts.map { it.number })
            assertNull(recovered.attempts.first().completedAt)
            assertEquals(baseline.rawOcr, fixture.result(job).rawOcr)
            assertEquals(2, fixture.requests(job, "qualityReview"))
            assertEquals(0, fixture.requests(job, "generateContent"))
            fixture.snapshot(job, "new_graph_resumes_second_reserved_attempt")

            val stale = fixture.seed("review-cas-delay", saved = true, includeRenderedPreview = false)
            val previous = fixture.result(stale)
            coroutineScope {
                val running = launch { fixture.graph.translationManager.runQueue() }
                try {
                    fixture.awaitReviewRequest(stale)
                    val edited = previous.copy(regions = previous.regions.map { it.copy(translatedText = "USER EDIT") })
                    assertTrue(fixture.graph.translationRepository.replaceResult(stale.id, edited, previous.revision))
                    val committed = fixture.result(stale)
                    running.join()
                    assertEquals(committed, fixture.result(stale))
                    assertEquals(QualityReviewState.SUPERSEDED, fixture.reviews(stale).single().state)
                    assertEquals(1, fixture.requests(stale, "qualityReview"))
                    assertEquals(0, fixture.requests(stale, "generateContent"))
                    fixture.snapshot(stale, "late_candidate_rejected_by_revision")
                } finally {
                    running.cancelAndJoin()
                }
            }
        }

    @Test
    fun dRestartReconcilesOrphanedRunningBatchWithoutProviderDispatch() = acceptance("orphaned-batch") { fixture ->
        val seeded = fixture.seed("review-repair", saved = true, qualityReviewEnabled = false)
        val repository = fixture.graph.translationRepository
        val styled = fixture.result(seeded).let { page ->
            page.copy(
                regions = page.regions.map {
                    it.copy(style = OverlayStyle(fontSize = 23f, backgroundOpacity = 0.5f))
                },
            )
        }
        repository.saveResult(seeded.id, styled)
        val baseline = fixture.result(seeded) // saveResult assigns the actual committed SQL revision.
        val job = seeded.copy(
            state = TranslationJobState.TRANSLATING,
            completedImages = 1,
            imageCount = 1,
            reviewReturnState = null,
            reviewImageIds = null,
        )
        repository.saveJob(job)
        val parent = TranslationBatch("parent-${job.id}", job.id, imageIds = listOf("0"), state = "SPLIT", attempts = 2)
        val orphaned = TranslationBatch("orphan-${job.id}", job.id, parent.id, listOf("0"), "RUNNING", 3)
        val completed = TranslationBatch("completed-${job.id}", job.id, parent.id, listOf("0"), "COMPLETED", 1)
        listOf(parent, orphaned, completed).forEach { repository.saveBatch(it) }
        assertTrue(fixture.reviews(job).isEmpty())
        fixture.snapshot(job, "orphaned_before_graph_recreation")
        val previousManager = fixture.graph.translationManager

        fixture.reopen()
        assertTrue(previousManager !== fixture.graph.translationManager)
        assertEquals(baseline, fixture.result(job))
        assertEquals(orphaned, fixture.graph.translationRepository.batches(job.id).single { it.id == orphaned.id })
        fixture.graph.translationManager.runQueue()

        val batches = fixture.graph.translationRepository.batches(job.id)
        val interrupted = batches.single { it.id == orphaned.id }
        assertEquals("INTERRUPTED", interrupted.state)
        assertEquals(orphaned.id, interrupted.id)
        assertEquals(orphaned.jobId, interrupted.jobId)
        assertEquals(orphaned.parentId, interrupted.parentId)
        assertEquals(orphaned.imageIds, interrupted.imageIds)
        assertEquals(orphaned.attempts, interrupted.attempts)
        assertEquals(parent, batches.single { it.id == parent.id })
        assertEquals(completed, batches.single { it.id == completed.id })
        assertEquals(3, batches.size)
        assertEquals(baseline, fixture.result(job))
        assertEquals(job.settings, fixture.current(job).settings)
        assertEquals(TranslationJobState.COMPLETED, fixture.current(job).state)
        assertEquals(1, fixture.current(job).completedImages)
        assertTrue(fixture.reviews(job).isEmpty())
        assertTrue(
            fixture.graph.translationRepository.observeEvents(job.id).first().none {
                it.message.startsWith("Request started")
            },
        )
        assertTrue(fixture.graph.translationDiagnostics.list().none { it.jobId == job.id })
        fixture.snapshot(job, "orphaned_reconciled_without_dispatch")
        fixture.graph.translationManager.runQueue()
        assertEquals(baseline, fixture.result(job))
        assertEquals(batches, fixture.graph.translationRepository.batches(job.id))
        assertTrue(
            fixture.graph.translationRepository.observeEvents(job.id).first().none {
                it.message.startsWith("Request started")
            },
        )
        fixture.snapshot(job, "orphaned_reconciliation_is_idempotent")
    }

    @Test
    fun eMetadataOnlyReviewDoesNotCreateARepairRevision() = acceptance("review-metadata-classification") { fixture ->
        for (kind in listOf("confidence", "language", "both", "finding", "geometry")) {
            val seeded = fixture.seed("review-repair", saved = true, qualityReviewEnabled = false)
            val repository = fixture.graph.translationRepository
            val job = seeded.copy(
                state = TranslationJobState.COMPLETED,
                imageCount = 1,
                completedImages = 1,
                reviewReturnState = null,
                reviewImageIds = null,
            )
            repository.saveJob(job)
            repository.saveResult(
                job.id,
                fixture.result(job).let { page ->
                    page.copy(
                        detectedLanguage = null,
                        regions = page.regions.map {
                            it.copy(aiConfidence = 0.95f, style = OverlayStyle(fontSize = 23f))
                        },
                    )
                },
            )
            val baseline = fixture.result(job)
            val review =
                requireNotNull(
                    repository.createReview(job.id, "0", QualityReviewSettings(includeRenderedPreview = false)),
                )
            val image = repository.images(job.id).single()
            val findings = if (kind == "finding") {
                listOf(
                    QualityReviewFinding(
                        "FIXTURE_UNRESOLVED",
                        "Authored unresolved finding",
                        listOf(baseline.regions.single().id),
                    ),
                )
            } else {
                emptyList()
            }
            val candidate = baseline.copy(
                detectedLanguage = if (kind != "confidence") "mul" else baseline.detectedLanguage,
                regions = baseline.regions.map { region ->
                    region.copy(
                        aiConfidence = if (kind != "language") 0.99f else region.aiConfidence,
                        points = if (kind ==
                            "geometry"
                        ) {
                            region.points.map { it.copy(x = it.x + 5f) }
                        } else {
                            region.points
                        },
                    )
                },
            )
            var calls = 0
            // Authored response at the provider interface; no HTTP or live model judgment in this regression.
            val authoredProvider = object : TranslationProvider by fixture.graph.translationProvider {
                override suspend fun review(request: QualityReviewRequest): QualityReviewResponse {
                    calls++
                    assertEquals(baseline, request.baseline)
                    return QualityReviewResponse(candidate, findings)
                }
            }
            fixture.snapshot(job, "metadata_${kind}_before_authored_response")
            val coordinator = QualityReviewCoordinator(repository, authoredProvider)
            coordinator.run(review, job.settings, image, "")
            coordinator.run(review, job.settings, image, "")
            val completed = fixture.reviews(job).single()
            assertEquals(1, calls)
            assertEquals(1, completed.attempts.size)
            assertTrue(completed.attempts.single().completedAt != null)
            assertEquals(baseline, completed.beforeResult)
            assertEquals(baseline.revision, completed.sourceRevision)
            assertEquals(findings, completed.findings)
            assertTrue(completed.visualComplete)
            assertEquals(job.settings, fixture.current(job).settings)
            if (kind == "geometry") {
                assertEquals(QualityReviewState.REPAIRED, completed.state)
                val repaired = fixture.result(job)
                assertTrue(repaired.revision > baseline.revision)
                assertEquals(repaired.revision, completed.repairedRevision)
                assertEquals(candidate.regions.single().points, repaired.regions.single().points)
                assertEquals(baseline.rawOcr, repaired.rawOcr)
                assertEquals(baseline.regions.single().style, repaired.regions.single().style)
            } else {
                assertEquals(
                    if (findings.isEmpty()) QualityReviewState.PASSED else QualityReviewState.NEEDS_REVIEW,
                    completed.state,
                )
                assertNull(completed.repairedRevision)
                assertEquals(baseline, fixture.result(job))
            }
            assertTrue(repository.observeEvents(job.id).first().none { it.message.startsWith("Request started") })
            assertTrue(fixture.graph.translationDiagnostics.list().none { it.jobId == job.id })
            val savedResult = fixture.result(job)
            fixture.reopen()
            assertEquals(savedResult, fixture.result(job))
            assertEquals(completed, fixture.reviews(job).single())
            fixture.snapshot(job, "metadata_${kind}_persisted_after_graph_recreation")
        }
    }

    @Test
    fun fRenderedReviewReusesPinnedInputsAfterRecreationAndUndoRestoresBaseline() = acceptance(
        "rendered-recreation",
    ) { fixture ->
        val job = fixture.seed("review-repair", saved = true, includeRenderedPreview = true)
        val repository = fixture.graph.translationRepository
        val baseline = fixture.result(job)
        val original = repository.images(job.id).single()
        val checkpoint = fixture.reviews(job).single()
        assertTrue(checkpoint.renderPreviewRequested)
        assertTrue(checkpoint.settings.includeRenderedPreview)
        assertNull(checkpoint.renderEvidence)
        val responseReceived = CompletableDeferred<QualityReviewRequest>()
        val gateway = fixture.graph.translationProvider
        val intercepted = object : TranslationProvider by gateway {
            override suspend fun review(request: QualityReviewRequest): QualityReviewResponse {
                gateway.review(request) // Actual local HTTP, capture, decoding and validation complete.
                responseReceived.complete(request)
                awaitCancellation() // Deterministic interruption before the coordinator can commit.
            }
        }
        val renderer = fixture.renderer()
        val coordinator = QualityReviewCoordinator(repository, intercepted, renderer)
        coroutineScope {
            val running = launch { coordinator.run(checkpoint, job.settings, original, "") }
            try {
                val request = responseReceived.await()
                val evidence = requireNotNull(request.renderEvidence)
                val persisted = fixture.reviews(job).single()
                assertEquals(evidence, persisted.renderEvidence)
                assertEquals(job.settings.style, persisted.renderStyle)
                assertEquals(baseline, fixture.result(job))
                assertEquals(1, persisted.attempts.size)
                assertNull(persisted.attempts.single().completedAt)
                fixture.verifyPinnedEvidence(evidence, baseline)
                fixture.assertReviewCaptures(job, evidence, expectedAttempts = 1)
                fixture.snapshot(job, "rendered_response_received_before_cancelled_commit")
            } finally {
                running.cancelAndJoin()
            }
        }
        val interrupted = fixture.reviews(job).single()
        val evidence = requireNotNull(interrupted.renderEvidence)
        fixture.reopen()
        assertEquals(interrupted, fixture.reviews(job).single())
        fixture.verifyPinnedEvidence(evidence, baseline)
        assertTrue("Only the private disposable original is removed", File(original.filePath).delete())
        // Global preferences may change while a saved review is interrupted. Its chosen presentation
        // and pinned bytes must survive unchanged, even when the source chapter file is unavailable.
        fixture.graph.translationPreferences.update(
            job.settings.copy(
                style = job.settings.style.copy(fontSize = 31f, backgroundColor = Color.YELLOW.toLong()),
                qualityReview = job.settings.qualityReview.copy(includeRenderedPreview = false),
            ),
        )
        fixture.graph.translationManager.runQueue()
        val recovered = fixture.reviews(job).single()
        assertEquals(QualityReviewState.REPAIRED, recovered.state)
        assertEquals(evidence, recovered.renderEvidence)
        assertEquals(interrupted.renderStyle, recovered.renderStyle)
        assertEquals(listOf(1, 2), recovered.attempts.map { it.number })
        assertNull(recovered.attempts.first().completedAt)
        assertTrue(recovered.attempts.last().completedAt != null)
        assertEquals(baseline, recovered.beforeResult)
        assertEquals(baseline.rawOcr, fixture.result(job).rawOcr)
        assertEquals(job.settings, fixture.current(job).settings)
        assertEquals(listOf("0"), fixture.graph.translationRepository.results(job.id).map { it.imageId })
        assertEquals(1, fixture.graph.translationRepository.images(job.id).size)
        assertEquals(0, fixture.requests(job, "generateContent"))
        assertEquals(2, fixture.requests(job, "qualityReview"))
        fixture.assertReviewCaptures(job, evidence, expectedAttempts = 2)
        assertFalse(
            "Terminal review releases only its temporary source copy",
            File(evidence.original.filePath).exists(),
        )
        assertFalse("Terminal review releases only its temporary preview", File(evidence.preview.filePath).exists())
        fixture.snapshot(job, "rendered_second_attempt_reuses_persisted_descriptor")
        fixture.graph.translationManager.undoRepair(job.id, "0")
        assertEquals(baseline, fixture.result(job).copy(revision = baseline.revision))
        assertEquals(QualityReviewState.UNDONE, fixture.reviews(job).single().state)
        assertEquals(2, fixture.requests(job, "qualityReview"))
        fixture.snapshot(job, "rendered_undo_restores_complete_baseline_without_dispatch")
    }

    @Test
    fun gRenderedReviewHonorsManualRevisionAndPaddleNeverRendersOrUploads() = acceptance(
        "rendered-cas-and-paddle",
    ) { fixture ->
        val job = fixture.seed("review-repair", saved = true, includeRenderedPreview = true)
        val repository = fixture.graph.translationRepository
        val baseline = fixture.result(job)
        val responseReceived = CompletableDeferred<Unit>()
        val deliver = CompletableDeferred<Unit>()
        val gateway = fixture.graph.translationProvider
        val intercepted = object : TranslationProvider by gateway {
            override suspend fun review(request: QualityReviewRequest): QualityReviewResponse {
                val response = gateway.review(request)
                responseReceived.complete(Unit)
                deliver.await()
                return response
            }
        }
        val coordinator = QualityReviewCoordinator(repository, intercepted, fixture.renderer())
        coroutineScope {
            val running = launch {
                coordinator.run(fixture.reviews(job).single(), job.settings, repository.images(job.id).single(), "")
            }
            try {
                responseReceived.await()
                val evidence = requireNotNull(fixture.reviews(job).single().renderEvidence)
                fixture.graph.translationManager.editResult(
                    job.id,
                    baseline.copy(
                        regions = baseline.regions.map { it.copy(translatedText = "USER SAVED AFTER RENDER") },
                    ),
                )
                val committed = fixture.result(job)
                deliver.complete(Unit)
                running.join()
                assertEquals(committed, fixture.result(job))
                assertEquals(baseline.rawOcr, committed.rawOcr)
                assertEquals(QualityReviewState.SUPERSEDED, fixture.reviews(job).single().state)
                assertEquals(1, fixture.reviews(job).single().attempts.size)
                fixture.assertReviewCaptures(job, evidence, expectedAttempts = 1)
                assertEquals(0, fixture.requests(job, "generateContent"))
                assertFalse(File(evidence.preview.filePath).exists())
                fixture.snapshot(job, "rendered_late_candidate_preserves_manual_revision")
            } finally {
                deliver.complete(Unit)
                running.cancelAndJoin()
            }
        }

        val paddle = fixture.seed(
            "review-repair",
            saved = true,
            pipeline = OcrPipeline.PADDLE,
            includeRenderedPreview = true,
        )
        val paddleBaseline = fixture.result(paddle)
        val paddleImage = repository.images(paddle.id).single()
        assertTrue(File(paddleImage.filePath).delete())
        var renderCalls = 0
        val actualRenderer = fixture.renderer()
        val countedRenderer = object : QualityReviewRenderer by actualRenderer {
            override suspend fun prepare(request: QualityReviewRequest): QualityReviewRenderEvidence {
                renderCalls++
                return actualRenderer.prepare(request)
            }
        }
        val paddleReview = fixture.reviews(paddle).single()
        assertTrue(paddleReview.renderPreviewRequested)
        QualityReviewCoordinator(
            repository,
            gateway,
            countedRenderer,
        ).run(paddleReview, paddle.settings, paddleImage, "")
        val completed = fixture.reviews(paddle).single()
        assertEquals(0, renderCalls)
        assertNull(completed.renderEvidence)
        assertNull(completed.renderStyle)
        assertFalse(completed.visualComplete)
        assertEquals(QualityReviewState.REPAIRED, completed.state)
        assertEquals(paddleBaseline.rawOcr, fixture.result(paddle).rawOcr)
        assertEquals(paddleBaseline.regions.single().points, fixture.result(paddle).regions.single().points)
        assertEquals(paddleBaseline.regions.single().rotation, fixture.result(paddle).regions.single().rotation, 0f)
        assertEquals(0, fixture.requests(paddle, "generateContent"))
        fixture.assertReviewCaptures(paddle, evidence = null, expectedAttempts = 1)
        fixture.snapshot(paddle, "rendered_setting_enabled_but_paddle_prepares_and_uploads_zero_images")
    }

    @Test
    fun hMissingPinnedOriginalRetainsBaselineAndReleasesTheWholeEvidenceDirectory() = acceptance(
        "rendered-missing-original-cleanup",
    ) { fixture ->
        val job = fixture.seed("review-repair", saved = true, includeRenderedPreview = true)
        val repository = fixture.graph.translationRepository
        val baseline = fixture.result(job)
        val checkpoint = fixture.reviews(job).single()
        val source = repository.images(job.id).single()
        val evidence = fixture.renderer().prepare(
            QualityReviewRequest(job.id, checkpoint.id, job.settings, source, baseline),
        )
        fixture.verifyPinnedEvidence(evidence, baseline)
        assertTrue(repository.saveReview(checkpoint.copy(renderStyle = job.settings.style, renderEvidence = evidence)))
        val directory = requireNotNull(File(evidence.preview.filePath).parentFile)
        // This is an owned cleanup sentinel, not a font-rendering claim. A terminal cleanup must
        // remove the directory completely, including any privately pinned auxiliary files.
        val sentinel = File(directory, "synthetic-cleanup-sentinel").apply { writeText("fixture only") }
        assertTrue(File(evidence.original.filePath).delete())
        assertTrue(File(source.filePath).isFile)
        assertTrue(File(evidence.preview.filePath).isFile)
        fixture.snapshot(job, "pinned_original_missing_before_manager_preflight")
        fixture.graph.translationManager.runQueue()
        val completed = fixture.reviews(job).single()
        assertEquals(QualityReviewState.INCOMPLETE, completed.state)
        assertTrue(completed.attempts.isEmpty())
        assertEquals(baseline, fixture.result(job))
        assertEquals(baseline, completed.beforeResult)
        assertEquals(job.settings, fixture.current(job).settings)
        assertTrue(repository.observeEvents(job.id).first().none { it.message.startsWith("Request started") })
        assertTrue(fixture.graph.translationDiagnostics.list().none { it.jobId == job.id })
        assertTrue("The source chapter file remains untouched", File(source.filePath).isFile)
        assertFalse("Terminal preflight must clean the preview", File(evidence.preview.filePath).exists())
        assertFalse("Terminal preflight must clean owned auxiliary files", sentinel.exists())
        assertFalse("Terminal preflight must remove the complete evidence directory", directory.exists())
        fixture.snapshot(job, "missing_pinned_original_terminal_cleanup_without_dispatch")
    }

    private fun acceptance(label: String, block: suspend (Fixture) -> Unit) = runBlocking<Unit> {
        assumeTrue(
            "Opt-in quality-review acceptance requires a fresh local fixture server",
            InstrumentationRegistry.getArguments().getString("translation.qualityReviewAcceptance") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.mihon.benchmark", context.packageName)
        assertTrue(BuildConfig.TRANSLATION_LOCAL_FIXTURES_ENABLED)
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        assertTrue(context.activeNetworkState().isOnline)
        withContext(Dispatchers.IO) {
            val fixture = Fixture(context, label)
            var failure: Throwable? = null
            try {
                withTimeout(90_000) {
                    fixture.initialize()
                    block(fixture)
                }
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                withContext(NonCancellable) {
                    if (failure != null) {
                        try {
                            fixture.snapshotFailure()
                        } catch (snapshotError: Throwable) {
                            if (failure !== snapshotError) failure.addSuppressed(snapshotError)
                        }
                    }
                    try {
                        fixture.close()
                    } catch (cleanup: Throwable) {
                        if (failure == null) {
                            failure = cleanup
                            throw cleanup
                        }
                        if (failure !== cleanup) failure.addSuppressed(cleanup)
                    } finally {
                        fixture.report(if (failure == null) "passed" else "failed", failure?.javaClass?.name)
                    }
                }
            }
        }
    }

    private inner class Fixture(private val base: Context, private val label: String) {
        private val id = UUID.randomUUID().toString()
        private val root = File(base.noBackupFilesDir, "quality-review-acceptance/$id").apply { check(mkdirs()) }
        private var isolated = IsolatedContext(base, root)
        var graph = isolated.graph
            private set
        private val credentialId = "quality-fixture-$id"
        private val records = mutableListOf<JsonElement>()
        private var server: JsonObject? = null
        private var credentialRemoved = false
        private var nextChapter = 0L
        private val barrierClient = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()

        suspend fun initialize() {
            val client = OkHttpClient.Builder().callTimeout(10, TimeUnit.SECONDS).build()
            client.newCall(Request.Builder().url("http://127.0.0.1:8765/health").build()).execute().use { response ->
                assertEquals(200, response.code)
                server = json.parseToJsonElement(requireNotNull(response.body).string()).jsonObject
                assertEquals("true", server!!["fixture"]?.jsonPrimitive?.content)
                assertEquals("false", server!!["cloud_forwarding"]?.jsonPrimitive?.content)
            }
            assertTrue(graph.translationRepository.jobs().isEmpty())
            graph.translationCredentialVault.import(credentialId, "mihon-fixture-only", TranslationProviderKind.OPENAI)
        }

        suspend fun seed(
            scenario: String,
            saved: Boolean = false,
            pipeline: OcrPipeline = OcrPipeline.AI,
            qualityReviewEnabled: Boolean = true,
            includeRenderedPreview: Boolean = false, // Legacy cases retain their original single-image policy.
        ): TranslationJob {
            if (scenario.endsWith("delay")) {
                assertEquals(
                    "Delayed review scenarios require a fresh fixture server",
                    0,
                    server!!["review_dispatches"]?.jsonObject?.get(scenario)?.jsonPrimitive?.content?.toInt() ?: 0,
                )
            }
            val settings = TranslationSettings(
                provider = ProviderSettings(
                    kind = TranslationProviderKind.OPENAI, credentialId = credentialId, model = "mihon-fixture",
                    baseUrl = "http://127.0.0.1:8765/v1", dialect = OpenAiDialect.CHAT_COMPLETIONS,
                    extraHeaders = mapOf("X-Mihon-Fixture-Scenario" to scenario),
                    maxOutputTokens = 4096, customInputTokenLimit = 65536, customOutputTokenLimit = 4096,
                    totalAttempts = 5, timeoutSeconds = 30, initialRetryMillis = 100, maxRetryMillis = 1000,
                    imagePreparationEnabled = false,
                ),
                ocr = OcrSettings(pipeline = pipeline),
                qualityReview = QualityReviewSettings(
                    enabled = qualityReviewEnabled,
                    includeRenderedPreview = includeRenderedPreview,
                ),
                concurrency = TranslationConcurrency(series = 1, chapters = 1, images = 1, requests = 1),
                logs = TranslationLogSettings(captureRaw = true, maxStorageMb = 32),
                wifiOnly = false,
            )
            graph.translationPreferences.update(settings)
            val job = TranslationJob(
                UUID.randomUUID().toString(),
                1,
                ++nextChapter,
                "Synthetic review fixture",
                scenario,
                settings,
                reviewReturnState = if (saved) TranslationJobState.COMPLETED else null,
                reviewImageIds = if (saved) listOf("0") else null,
            )
            graph.translationRepository.saveJob(job)
            val image = image(job.id)
            graph.translationRepository.saveImages(job.id, listOf(image))
            val exif = ExifInterface(File(image.filePath))
            records += buildJsonObject {
                put("stage", "seeded_original")
                put("job_id", job.id)
                put("image_sha256", image.contentHash)
                put(
                    "exif_orientation",
                    exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL),
                )
                put("exif_rotation_degrees", exif.rotationDegrees)
                put("exif_flipped", exif.isFlipped)
            }
            if (saved) {
                val region = TextRegion(
                    "original-region",
                    listOf(
                        TranslationPoint(40f, 40f),
                        TranslationPoint(600f, 40f),
                        TranslationPoint(600f, 150f),
                        TranslationPoint(40f, 150f),
                    ),
                    "RAW FIXTURE",
                    "Baseline translation",
                    correctedText = "Corrected fixture",
                    rotation = 17f,
                    detectionConfidence = 0.8f,
                    recognitionConfidence = 0.7f,
                )
                val baseline = TranslationPageResult(
                    image.id,
                    image.contentHash,
                    image.width,
                    image.height,
                    listOf(region),
                    rawOcr = OcrPageResult(
                        image.id,
                        listOf(region.copy(correctedText = null, translatedText = "")),
                        "fixture-detector",
                        "fixture-recognizer",
                        rawJson = "{\"synthetic\":true}",
                    ),
                )
                graph.translationRepository.saveNewResult(job.id, baseline, settings.qualityReview)
            }
            return job
        }

        suspend fun current(job: TranslationJob) = graph.translationRepository.jobs().single { it.id == job.id }
        suspend fun result(job: TranslationJob) = graph.translationRepository.results(job.id).single()
        suspend fun reviews(job: TranslationJob) = graph.translationRepository.reviews(job.id)
        suspend fun requests(
            job: TranslationJob,
            stage: String,
        ) = graph.translationRepository.observeEvents(job.id).first()
            .count { it.stage == stage && it.message.startsWith("Request started") }

        suspend fun awaitReviewRequest(job: TranslationJob) = withTimeout(20_000) {
            graph.translationRepository.observeEvents(job.id).first { events ->
                events.any { it.stage == "qualityReview" && it.message.startsWith("Request started") }
            }
            // An app start event precedes the socket write. Wait for the host's durable dispatch
            // ledger before cancellation/editing so this exercises a response already in flight.
            val scenario = job.settings.provider.extraHeaders.getValue("X-Mihon-Fixture-Scenario")
            while (true) {
                val received = barrierClient.newCall(Request.Builder().url("http://127.0.0.1:8765/health").build())
                    .execute().use { response ->
                        assertEquals(200, response.code)
                        val health = json.parseToJsonElement(requireNotNull(response.body).string()).jsonObject
                        assertEquals(server!!["run_id"], health["run_id"])
                        health["review_dispatches"]?.jsonObject?.get(scenario)?.jsonPrimitive?.content?.toInt() ?: 0
                    }
                if (received > 0) break
                delay(25)
            }
        }

        fun renderer() = AndroidQualityReviewRenderer(isolated)

        fun verifyPinnedEvidence(evidence: QualityReviewRenderEvidence, baseline: TranslationPageResult) {
            assertEquals(baseline.revision, evidence.sourceRevision)
            assertEquals(baseline.imageId, evidence.original.id)
            assertEquals(baseline.imageHash, evidence.original.contentHash)
            assertTrue(evidence.preview.id != evidence.original.id)
            assertEquals(evidence.preview.width.toDouble() / baseline.width, evidence.scaleX, 0.0)
            assertEquals(evidence.preview.height.toDouble() / baseline.height, evidence.scaleY, 0.0)
            assertTrue(evidence.rendererVersion.isNotBlank())
            assertTrue(evidence.presentationFingerprint.matches(Regex("[a-f0-9]{64}")))
            for (image in listOf(evidence.original, evidence.preview)) {
                val file = File(image.filePath)
                assertTrue(
                    "Only isolated review evidence is read",
                    file.canonicalPath.startsWith(root.canonicalPath + File.separator),
                )
                assertEquals(image.byteSize, file.length())
                assertEquals(image.contentHash, digest(file.readBytes()))
            }
            assertTrue(
                "Preview must include actual translated glyphs/masks",
                evidence.original.contentHash != evidence.preview.contentHash,
            )
        }

        suspend fun assertReviewCaptures(
            job: TranslationJob,
            evidence: QualityReviewRenderEvidence?,
            expectedAttempts: Int,
        ) {
            val captures = graph.translationDiagnostics.list().filter {
                it.jobId == job.id &&
                    it.operation == "qualityReview"
            }
            assertEquals(expectedAttempts, captures.size)
            val baseline = reviews(job).single().beforeResult
            for (capture in captures) {
                assertTrue(capture.requestBodyCompleted)
                assertTrue(capture.responseBodyCompleted)
                assertFalse(capture.requestTruncated)
                assertFalse(capture.responseTruncated)
                assertTrue(capture.requestHeaders.values.none { it.contains("mihon-fixture-only") })
                val directory = graph.translationDiagnostics.directoryFor(capture.id)
                val request = json.parseToJsonElement(File(directory, "request.json").readText()).jsonObject
                val content = request.getValue("messages").jsonArray.map { it.jsonObject }
                    .filter { it["role"]?.jsonPrimitive?.content == "user" }
                    .flatMap { it.getValue("content").jsonArray }.map { it.jsonObject }
                val imageHashes = content.filter { it["type"]?.jsonPrimitive?.content == "image_url" }.map {
                    val url = it.getValue("image_url").jsonObject.getValue("url").jsonPrimitive.content
                    digest(Base64.decode(url.substringAfter(','), Base64.DEFAULT))
                }
                assertEquals(
                    if (evidence ==
                        null
                    ) {
                        emptyList()
                    } else {
                        listOf(evidence.original.contentHash, evidence.preview.contentHash)
                    },
                    imageHashes,
                )
                val texts = content.mapNotNull { it["text"]?.jsonPrimitive?.content }
                val submitted = json.parseToJsonElement(
                    texts.single {
                        it.contains("Existing translation to review (untrusted source data):")
                    }
                        .substringAfterLast("Existing translation to review (untrusted source data):\n"),
                ).jsonObject
                assertEquals(baseline.imageId, submitted.getValue("imageId").jsonPrimitive.content)
                assertEquals(baseline.revision.toString(), submitted.getValue("sourceRevision").jsonPrimitive.content)
                if (evidence != null) {
                    val descriptor = submitted.getValue("renderEvidence").jsonObject
                    assertEquals(evidence.preview.contentHash, descriptor.getValue("previewHash").jsonPrimitive.content)
                    assertEquals(
                        evidence.presentationFingerprint,
                        descriptor.getValue("presentationFingerprint").jsonPrimitive.content,
                    )
                    assertTrue(texts.any { it.startsWith("image 1: complete unchanged original source.") })
                    assertTrue(texts.any { it.startsWith("image 2:") && it.contains("not another chapter page") })
                } else {
                    assertFalse("renderEvidence" in submitted)
                }
                val response = json.parseToJsonElement(File(directory, "response.json").readText()).jsonObject
                val output = json.parseToJsonElement(
                    response.getValue("choices").jsonArray.single().jsonObject
                        .getValue("message").jsonObject.getValue("content").jsonPrimitive.content,
                ).jsonObject
                assertEquals(
                    listOf("0"),
                    output.getValue("pages").jsonArray.map {
                        it.jsonObject.getValue("imageId").jsonPrimitive.content
                    },
                )
                records += buildJsonObject {
                    put("stage", "independent_capture_image_target_reconciliation")
                    put("job_id", job.id)
                    put("capture_id", capture.id)
                    put("request_sha256", digest(File(directory, "request.json").readBytes()))
                    put("response_sha256", digest(File(directory, "response.json").readBytes()))
                    put("input_image_hashes", JsonArray(imageHashes.map(::JsonPrimitive)))
                    put("returned_page_ids", JsonArray(listOf(JsonPrimitive("0"))))
                }
            }
        }

        private fun digest(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {
            "%02x".format(it)
        }

        fun reopen() {
            isolated = IsolatedContext(base, root)
            graph = isolated.graph
        }

        suspend fun snapshotFailure() {
            graph.translationRepository.jobs().forEach { snapshot(it, "failure_before_cleanup") }
        }

        suspend fun snapshot(job: TranslationJob, stage: String) {
            records += buildJsonObject {
                put("stage", stage)
                put("job", json.parseToJsonElement(json.encodeToString(current(job))))
                put("images", json.parseToJsonElement(json.encodeToString(graph.translationRepository.images(job.id))))
                put(
                    "results",
                    json.parseToJsonElement(json.encodeToString(graph.translationRepository.results(job.id))),
                )
                put(
                    "batches",
                    json.parseToJsonElement(json.encodeToString(graph.translationRepository.batches(job.id))),
                )
                put("reviews", json.parseToJsonElement(json.encodeToString(reviews(job))))
                put(
                    "events",
                    json.parseToJsonElement(
                        json.encodeToString(graph.translationRepository.observeEvents(job.id).first()),
                    ),
                )
            }
            report("running")
        }

        fun report(status: String, failure: String? = null) {
            val value = buildJsonObject {
                put("schema", 1)
                put("run_id", id)
                put("label", label)
                put("package", base.packageName)
                put("status", status)
                put("failure_class", failure?.let(::JsonPrimitive) ?: JsonNull)
                put("credential_removed", credentialRemoved)
                put("fixture_server", server ?: JsonNull)
                put("records", JsonArray(records))
                put(
                    "scope",
                    if (label == "review-metadata-classification") {
                        "Authored provider-interface responses; real minified coordinator and isolated SQL. " +
                            "No HTTP generation, gateway output, live model repair or human meaning approval."
                    } else {
                        "Synthetic loopback; real isolated manager/repository/gateway. Coroutine interruption and " +
                            "graph recreation, not Android process death, WorkManager or human meaning review."
                    },
                )
            }
            File(root, "report.json").writeText(json.encodeToString(value))
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString("QUALITY_REVIEW_ACCEPTANCE_REPORT", json.encodeToString(value))
                },
            )
        }

        suspend fun close() {
            graph.translationCredentialVault.remove(credentialId)
            check(graph.translationCredentialVault.info(credentialId) == null)
            credentialRemoved = true
            graph.translationPreferences.reset()
        }

        private fun image(jobId: String): TranslationImage {
            val file = File(root, "$jobId.png")
            val bitmap = Bitmap.createBitmap(960, 320, Bitmap.Config.ARGB_8888)
            try {
                Canvas(bitmap).apply {
                    drawColor(Color.WHITE)
                    drawText(
                        "SYNTHETIC REVIEW",
                        40f,
                        150f,
                        Paint(Paint.ANTI_ALIAS_FLAG).apply {
                            color = Color.BLACK
                            textSize = 64f
                        },
                    )
                }
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                "%02x".format(it)
            }
            return TranslationImage("0", 0, file.absolutePath, "image/png", 960, 320, hash, file.length())
        }
    }

    private class IsolatedContext(base: Context, private val root: File) :
        ContextWrapper(
            base,
        ),
        GraphProvider<AppGraph> {
        override val graph: AppGraph by lazy {
            createGraphFactory<AppGraph.Factory>().create(context = this, isDebugBuild = false)
        }
        override fun getApplicationContext(): Context = this
        override fun getDataDir(): File = root
        override fun getCacheDir(): File = directory("cache")
        override fun getCodeCacheDir(): File = directory("code-cache")
        override fun getFilesDir(): File = directory("files")
        override fun getNoBackupFilesDir(): File = directory("no-backup")
        override fun getExternalFilesDir(type: String?): File = directory("external-${type ?: "default"}")
        override fun getDir(name: String, mode: Int): File = directory("app-$name")
        override fun getDatabasePath(name: String): File = File(directory("databases"), File(name).name)
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            baseContext.getSharedPreferences("quality-acceptance-${root.name}-$name", mode)
        private fun directory(name: String) = ensureReviewAcceptanceDirectory(File(root, name))
    }
}
