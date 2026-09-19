package mihon.feature.translation

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import eu.kanade.tachiyomi.util.system.activeNetworkState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import mihon.app.di.appGraph
import mihon.feature.translation.ocr.PaddleOcrEngine
import mihon.feature.translation.overlay.AndroidQualityReviewRenderer
import mihon.feature.translation.overlay.resolveReviewTheme
import mihon.feature.translation.provider.TranslationProviderGateway
import tachiyomi.domain.chapter.interactor.GetChaptersByMangaId
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.chapter.service.getChapterSort
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.QualityReviewCoverage
import tachiyomi.domain.translation.model.QualityReviewRenderEvidence
import tachiyomi.domain.translation.model.QualityReviewRequest
import tachiyomi.domain.translation.model.QualityReviewResponse
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TranslationBatch
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.QualityReviewCoordinator
import tachiyomi.domain.translation.service.QualityReviewRenderer
import tachiyomi.domain.translation.service.QualityReviewValidation
import tachiyomi.domain.translation.service.TranslationBatchPlanner
import tachiyomi.domain.translation.service.TranslationOperationRecorder
import tachiyomi.domain.translation.service.TranslationProvider
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.coroutines.coroutineContext

@Inject
@SingleIn(AppScope::class)
class TranslationManager(
    private val context: Context,
    val preferences: TranslationPreferences,
    val repository: TranslationRepository,
    private val provider: TranslationProviderGateway,
    private val imageSource: TranslationImageSource,
    private val ocrEngine: PaddleOcrEngine,
    private val getChapters: GetChaptersByMangaId,
    private val getManga: GetManga,
) {
    private val operationRecorder = TranslationOperationRecorder(repository)
    private val mutation = Mutex()
    private val replacementMutation = Mutex()
    private val execution = Mutex()
    private val children = mutableMapOf<String, Job>()
    private val manualRequests = mutableMapOf<String, Job>()
    private val managementOwner = Any()

    // Every mutation and admission shares this generation fence. A cancelled HTTP call may
    // still complete, but its old generation can never commit over a newer user action.
    private val generations = mutableMapOf<String, Long>()
    private val wake = MutableStateFlow(0L)
    private val requestGate = Mutex()
    private val requestsInFlight = MutableStateFlow(0)
    private val reviewRenderer by lazy {
        AndroidQualityReviewRenderer(context, presentationContext = {
            val ui = context.appGraph.uiPreferences
            resolveReviewTheme(context, ui.themeMode.get(), ui.appTheme.get(), ui.themeDarkAmoled.get())
        })
    }
    private val planner = TranslationBatchPlanner(object : TranslationProvider by provider {
        override suspend fun countTokens(request: TranslationRequest): Long? =
            withRequestPermit { provider.countTokens(request) }
    })
    private val reviewCoordinator = QualityReviewCoordinator(
        repository,
        object : TranslationProvider by provider {
            override suspend fun review(request: QualityReviewRequest): QualityReviewResponse =
                withRequestPermit { provider.review(request) }
        },
        object : QualityReviewRenderer {
            override suspend fun prepare(request: QualityReviewRequest) = reviewRenderer.prepare(request)
            override suspend fun cleanup(evidence: QualityReviewRenderEvidence) =
                reviewRenderer.cleanup(evidence)
        },
    )
    private val ocrPermit = Semaphore(1)

    val jobs = repository.observeJobs()

    suspend fun reviewPresentationFingerprint(
        result: TranslationPageResult,
        style: OverlayStyle,
        contentPolicy: TranslationContentPolicy = TranslationContentPolicy(),
    ) = reviewRenderer.presentationFingerprint(result, style, contentPolicy)

    suspend fun enqueue(manga: Manga, chapters: List<Chapter>, settings: TranslationSettings? = null) {
        val added = mutation.withLock {
            val existing = repository.jobs().toMutableList()
            var added = false
            val config = settings ?: preferences.effectiveSettings(manga.id)
            config.validate()
            require(config.mode != TranslationMode.STRUCTURED_FILES) {
                "Open Structured files import to add translated pages"
            }
            val createdAt = maxOf(System.currentTimeMillis(), (existing.maxOfOrNull { it.updatedAt } ?: 0L) + 1)
            chapters.distinctBy { it.id }.forEachIndexed { index, chapter ->
                val duplicate = existing.any {
                    it.chapterId == chapter.id && it.settings.sameTranslationAs(config) &&
                        it.state !in setOf(TranslationJobState.CANCELLED, TranslationJobState.FAILED)
                }
                if (!duplicate) {
                    require(chapter.mangaId == manga.id) { "Chapter belongs to another series" }
                    val job = TranslationJob(
                        id = UUID.randomUUID().toString(),
                        mangaId = manga.id,
                        chapterId = chapter.id,
                        mangaTitle = manga.title,
                        chapterTitle = chapter.name,
                        settings = config,
                        createdAt = createdAt + index,
                        geometryRecoveryId = UUID.randomUUID().toString(),
                    )
                    repository.saveJob(job)
                    existing += job
                    added = true
                }
            }
            added
        }
        if (added) TranslationWorker.start(context)
        wake.update { it + 1 }
    }

    suspend fun onChapterOpened(manga: Manga, chapterId: Long, readingOrder: List<Long>? = null) {
        val settings = preferences.effectiveSettings(manga.id)
        if (!settings.autoTranslate || settings.mode == TranslationMode.STRUCTURED_FILES) return
        val chapters = getChapters.await(manga.id, applyScanlatorFilter = true)
        val ordered = if (readingOrder == null) {
            chapters.sortedWith(getChapterSort(manga, sortDescending = false))
        } else {
            val byId = chapters.associateBy { it.id }
            readingOrder.distinct().mapNotNull(byId::get)
        }
        val index = ordered.indexOfFirst { it.id == chapterId }
        if (index >= 0) enqueue(manga, ordered.drop(index).take(settings.chaptersAhead + 1), settings)
    }

    suspend fun chapterChoices(mangaId: Long): Pair<Manga, List<Chapter>> {
        val manga = requireNotNull(getManga.await(mangaId)) { "Series no longer exists" }
        return manga to getChapters.await(mangaId).sortedWith(getChapterSort(manga, sortDescending = false))
    }

    suspend fun enqueueSelected(mangaId: Long, chapterIds: List<Long>) {
        val (manga, chapters) = chapterChoices(mangaId)
        val selected = chapters.filter { it.id in chapterIds }
        require(selected.size == chapterIds.distinct().size) { "A selected chapter no longer exists" }
        enqueue(manga, selected)
    }

    suspend fun replacementSettings(jobId: String): TranslationSettings {
        val job =
            requireNotNull(repository.jobs().firstOrNull { it.id == jobId }) { "Translation job no longer exists" }
        require(job.mangaId >= 0 && job.chapterId >= 0) { "Relink this archived chapter before creating paid work" }
        return preferences.effectiveSettings(job.mangaId)
    }

    suspend fun replaceUnfinished(jobId: String, settings: TranslationSettings): TranslationJob {
        return replacementMutation.withLock replacement@{
            settings.validate()
            require(settings.mode != TranslationMode.STRUCTURED_FILES) { "Open Structured files import instead" }
            require(
                provider.capabilities(settings.provider).supportsVision || settings.ocr.pipeline == OcrPipeline.PADDLE,
            ) {
                "This model accepts text only. Select PaddleOCR for translation and text-only review " +
                    "before replacing the job."
            }
            mutation.withLock {
                val jobs = repository.jobs()
                val source = requireNotNull(jobs.firstOrNull { it.id == jobId }) { "Translation job no longer exists" }
                require(source.mangaId >= 0 && source.chapterId >= 0) {
                    "Relink this archived chapter before creating paid work"
                }
                jobs.firstOrNull {
                    it.replacesJobId == jobId && it.mangaId == source.mangaId &&
                        it.chapterId == source.chapterId
                }?.let { existing ->
                    require(existing.settings == settings) {
                        "A replacement already exists. Open that job to review or replace its configuration."
                    }
                    return@replacement existing
                }
            }
            val checkpoint = checkpointForManagement(setOf(jobId))
            val created = withManagementCheckpoint(checkpoint) {
                val jobs = repository.jobs()
                val source = requireNotNull(jobs.firstOrNull { it.id == jobId }) { "Translation job no longer exists" }
                jobs.firstOrNull {
                    it.replacesJobId == jobId && it.mangaId == source.mangaId &&
                        it.chapterId == source.chapterId
                }?.let { existing ->
                    require(existing.settings == settings) {
                        "A replacement already exists. Open that job to review or replace its configuration."
                    }
                    return@withManagementCheckpoint existing
                }
                val time = maxOf(System.currentTimeMillis(), (jobs.maxOfOrNull { it.updatedAt } ?: 0) + 1)
                val replacement = source.copy(
                    id = UUID.randomUUID().toString(), settings = settings,
                    archiveMetadata = source.archiveMetadata?.copy(structuredFiles = false),
                    state = TranslationJobState.QUEUED, createdAt = time, updatedAt = time,
                    message = "Only unfinished pages use this configuration. " +
                        "Saved pages and prior review history are retained.",
                    reviewReturnState = null, reviewImageIds = null, replacesJobId = source.id,
                    geometryRecoveryId = UUID.randomUUID().toString(),
                )
                checkNotNull(repository.replaceUnfinishedJob(source, replacement)) {
                    "The source job changed before replacement was committed. Review the selection again."
                }
            }
            if (created.state == TranslationJobState.QUEUED) TranslationWorker.start(context)
            wake.update { it + 1 }
            created
        }
    }

    suspend fun pause(id: String? = null) = control(id) {
        if (it.state !in pausableStates) it else it.copy(state = TranslationJobState.PAUSED, message = "Paused by you")
    }

    suspend fun resume(id: String? = null) {
        val targets = repository.jobs().filter {
            (id == null || it.id == id) && it.state !in terminalSuccess &&
                (!it.isStructuredFiles || it.reviewReturnState != null)
        }
        if (targets.isEmpty()) return
        provider.resumeGeometryCorrections(targets.map { it.id }.toSet())
        control(id) {
            if ((it.isStructuredFiles && it.reviewReturnState == null) || it.state in terminalSuccess ||
                (id == null && it.state in activeStates)
            ) {
                it
            } else {
                // Explicit Resume of an active job cancels its stalled generation through control's fence.
                // Resume all leaves healthy active jobs alone; saved pages and review reservations remain intact.
                it.copy(state = TranslationJobState.QUEUED, message = null)
            }
        }
        TranslationWorker.start(context)
    }

    suspend fun cancel(id: String) = control(id) {
        // Mixed or stale selections may include completed chapters; cancel only their explicit in-flight request.
        if (it.state ==
            TranslationJobState.COMPLETED
        ) {
            it
        } else {
            it.copy(state = TranslationJobState.CANCELLED, message = "Cancelled")
        }
    }

    suspend fun checkpointForManagement(
        jobIds: Set<String>,
    ): mihon.feature.translation.deletion.TranslationManagementCheckpoint {
        require(jobIds.isNotEmpty()) { "Select translation jobs to manage" }
        val self = coroutineContext.job
        val (checkpoint, running) = mutation.withLock {
            val jobs = repository.jobs().filter { it.id in jobIds }
            check(jobs.map { it.id }.toSet() == jobIds) { "A selected job no longer exists; refresh the selection" }
            val running = jobIds.flatMap { listOfNotNull(children[it], manualRequests[it]) }.distinct()
            check(self !in running) { "Running translation work cannot manage itself" }
            jobs.forEach { job ->
                invalidate(job.id)
                if (job.state !in terminalSuccess || running.any { it == manualRequests[job.id] && !it.isCompleted }) {
                    repository.saveJob(
                        job.copy(
                            state = TranslationJobState.PAUSED,
                            updatedAt = System.currentTimeMillis(),
                            message = "Paused for data management; Resume is explicit",
                        ),
                    )
                }
            }
            mihon.feature.translation.deletion.TranslationManagementCheckpoint(
                managementOwner,
                jobIds.associateWith { generations.getValue(it) },
            ) to running
        }
        wake.update { it + 1 }
        // Await provider/OCR/export finally handlers outside mutation so their checkpoints can finish.
        running.forEach { it.join() }
        return withManagementCheckpoint(checkpoint) { checkpoint }
    }

    suspend fun <T> withManagementCheckpoint(
        checkpoint: mihon.feature.translation.deletion.TranslationManagementCheckpoint,
        block: suspend () -> T,
    ): T = mutation.withLock {
        check(checkpoint.owner === managementOwner && checkpoint.generations.isNotEmpty()) {
            "Management preview belongs to another process"
        }
        check(checkpoint.generations.all { (id, token) -> generations[id] == token }) {
            "A selected job was resumed or edited. Refresh the deletion preview."
        }
        val jobs = repository.jobs().filter { it.id in checkpoint.jobIds }
        check(
            jobs.size == checkpoint.jobIds.size && jobs.none {
                it.state in activeStates || it.state in setOf(TranslationJobState.QUEUED, TranslationJobState.WAITING)
            },
        ) { "Selected work is no longer paused; refresh the preview" }
        check(
            checkpoint.jobIds.none {
                children[it]?.isCompleted == false || manualRequests[it]?.isCompleted == false
            },
        ) {
            "Selected work has not finished checkpointing"
        }
        block()
    }

    suspend fun prioritize(id: String) {
        mutateJobs(id) { it.copy(priority = System.currentTimeMillis()) }
        wake.update { it + 1 }
    }

    /** Reorders pending work without cancelling any running chapter or changing its state. */
    suspend fun reorder(jobIds: List<String>) {
        mutation.withLock {
            val current = repository.jobs()
            val byId = current.associateBy { it.id }
            val selected = jobIds.distinct().mapNotNull(byId::get)
            val selectedIds = selected.map { it.id }.toSet()
            val ordered = selected + current.filter { it.id !in selectedIds }
            val firstPriority = System.currentTimeMillis() + ordered.size
            ordered.forEachIndexed { index, job ->
                repository.saveJob(job.copy(priority = firstPriority - index))
            }
        }
        wake.update { it + 1 }
    }

    suspend fun retry(
        id: String,
        mode: TranslationMode? = null,
        force: Boolean = false,
        captureDiagnostics: Boolean = false,
    ) {
        val stopped = mutation.withLock {
            val current = repository.jobs().firstOrNull { it.id == id } ?: return
            if (current.isStructuredFiles) return
            require(mode != TranslationMode.STRUCTURED_FILES) { "Open Structured files import instead" }
            if (!force && (
                    current.state in activeStates || current.state in setOf(
                        TranslationJobState.QUEUED,
                        TranslationJobState.WAITING,
                        TranslationJobState.COMPLETED,
                    )
                    )
            ) {
                return
            }
            invalidate(id)
            repository.saveJob(
                current.copy(
                    state = if (force) TranslationJobState.PAUSED else TranslationJobState.QUEUED,
                    message = if (force) "Resetting translation checkpoints" else null,
                    updatedAt = System.currentTimeMillis(),
                    settings = current.settings.copy(
                        mode = mode ?: current.settings.mode,
                        geometryRecovery = preferences.effectiveSettings(current.mangaId).geometryRecovery,
                        prompts = current.settings.prompts.copy(
                            geometryCorrection = preferences.effectiveSettings(
                                current.mangaId,
                            ).prompts.geometryCorrection,
                        ),
                        logs = if (captureDiagnostics) {
                            current.settings.logs.copy(
                                enabled = true,
                                captureRaw = true,
                            )
                        } else {
                            current.settings.logs
                        },
                    ),
                    geometryRecoveryId = UUID.randomUUID().toString(),
                ),
            )
            requireNotNull(generations[id]) to listOfNotNull(children[id], manualRequests[id])
        }
        if (force) {
            // Physical tile checkpoints must not be recreated by an older request while clearing.
            stopped.second.forEach { it.join() }
            mutation.withLock {
                if (generations[id] != stopped.first) return
                val current = repository.jobs().firstOrNull { it.id == id } ?: return
                provider.clearCheckpoints(id)
                repository.deleteResults(id)
                repository.saveJob(
                    current.copy(state = TranslationJobState.QUEUED, completedImages = 0, message = null),
                )
            }
        }
        wake.update { it + 1 }
        TranslationWorker.start(context)
    }

    suspend fun remove(id: String) {
        val (token, child) = mutation.withLock {
            invalidate(id)
            repository.jobs().firstOrNull { it.id == id }?.let {
                repository.saveJob(it.copy(state = TranslationJobState.CANCELLED, message = "Removing"))
            }
            generations[id] to listOfNotNull(children[id], manualRequests[id])
        }
        child.forEach { it.join() }
        mutation.withLock {
            // A later resume/retry takes precedence over an earlier removal.
            if (generations[id] == token) {
                val hadRenderedReviews = repository.reviews(id).any { it.renderPreviewRequested }
                repository.removeJob(id)
                File(context.noBackupFilesDir, "translation/images/$id").deleteRecursively()
                if (hadRenderedReviews) {
                    reviewRenderer.cleanupExcept(
                        repository.reviews().filter { it.state.pending }.map { it.id }.toSet(),
                    )
                }
            }
        }
    }

    suspend fun editResult(jobId: String, result: TranslationPageResult) {
        validateGeometry(result)
        mutation.withLock {
            val job = repository.jobs().firstOrNull { it.id == jobId } ?: return@withLock
            invalidate(jobId)
            if (job.state in activeStates || job.state == TranslationJobState.QUEUED) {
                repository.saveJob(job.copy(state = TranslationJobState.PAUSED, message = "Paused for editing"))
            }
            val image = repository.images(jobId).first { it.id == result.imageId }
            require(
                image.contentHash == result.imageHash && image.width == result.width && image.height == result.height,
            ) {
                "The original image has changed"
            }
            val saved = repository.results(jobId).firstOrNull { it.imageId == result.imageId }
            val originals = saved?.regions?.associateBy { it.id }.orEmpty()
            check(
                repository.replaceResult(
                    jobId,
                    result.copy(
                        rawOcr = saved?.rawOcr ?: result.rawOcr,
                        regions = result.regions.map { region ->
                            originals[region.id]?.let { original ->
                                region.copy(
                                    sourceText = original.sourceText,
                                    correctedText = if (region.sourceText != original.sourceText) {
                                        region.correctedText ?: region.sourceText
                                    } else {
                                        region.correctedText
                                    },
                                    detectionConfidence = original.detectionConfidence,
                                    recognitionConfidence = original.recognitionConfidence,
                                )
                            } ?: region.copy(detectionConfidence = null, recognitionConfidence = null)
                        },
                    ),
                    expectedRevision = result.revision,
                ),
            ) { "The page changed while you were editing; reload the latest result before saving" }
        }
        wake.update { it + 1 }
    }

    /** Explicit selection is the only way to review already cached pages. It never re-submits the chapter. */
    suspend fun reviewPages(jobId: String, imageIds: Set<String>) {
        if (imageIds.isEmpty()) return
        val added = mutation.withLock {
            val job = repository.jobs().firstOrNull { it.id == jobId } ?: return@withLock false
            if (job.state in activeStates || job.state == TranslationJobState.QUEUED) {
                val pendingIds = repository.reviews(jobId).filter { it.state.pending }.map { it.imageId }.toSet()
                if (imageIds.all { it in pendingIds }) return@withLock false
            }
            val currentSettings = preferences.effectiveSettings(job.mangaId)
            val reviewExecution = if (job.isStructuredFiles) {
                currentSettings.copy(mode = TranslationMode.VERTEX).also {
                    it.validate()
                    require(
                        provider.capabilities(it.provider).supportsVision || it.ocr.pipeline == OcrPipeline.PADDLE ||
                            it.qualityReview.coverage == QualityReviewCoverage.TEXT_ONLY,
                    ) {
                        "This provider requires PaddleOCR text-only review"
                    }
                }
            } else {
                null
            }
            val reviewSettings = currentSettings.qualityReview.copy(enabled = true)
            val returnState = job.reviewReturnState ?: job.state.takeUnless {
                it in activeStates || it in setOf(TranslationJobState.QUEUED, TranslationJobState.WAITING)
            } ?: TranslationJobState.PAUSED
            val scheduled = repository.scheduleReviews(
                job.copy(
                    state = TranslationJobState.QUEUED,
                    reviewReturnState = returnState,
                    reviewImageIds = imageIds.toList(),
                    message = "Reviewing selected saved pages only",
                ),
                imageIds,
                reviewSettings,
                currentSettings.contentPolicy,
                currentSettings.prompts.qualityReview,
                reviewExecution,
            )
            if (scheduled) invalidate(jobId)
            scheduled
        }
        if (added) TranslationWorker.start(context)
        wake.update { it + 1 }
    }

    suspend fun undoRepair(jobId: String, imageId: String) {
        mutation.withLock {
            check(repository.undoRepair(jobId, imageId)) {
                "Repair cannot be undone because the page has since changed"
            }
            invalidate(jobId)
            repository.jobs().firstOrNull { it.id == jobId }?.let { job ->
                if (job.state in activeStates || job.state == TranslationJobState.QUEUED) {
                    repository.saveJob(
                        job.copy(state = TranslationJobState.PAUSED, message = "Paused after undoing AI repair"),
                    )
                }
            }
        }
        event(jobId, "INFO", "quality-review", "Pre-repair result restored for $imageId")
        wake.update { it + 1 }
    }

    suspend fun retryRegion(jobId: String, imageId: String, regionId: String) {
        val operation = coroutineContext.job
        val (job, token, contentPolicy) = mutation.withLock {
            val job = repository.jobs().first { it.id == jobId }
            require(!job.isStructuredFiles) { "Imported pages use an explicit provider review or a replacement job" }
            val contentPolicy = preferences.effectiveSettings(job.mangaId).contentPolicy
            val selectedRegion = repository.results(jobId).first { it.imageId == imageId }
                .regions.first { it.id == regionId }
            check(!contentPolicy.excludes(selectedRegion)) {
                "Sound effects are ignored. Change the region type if it was misclassified, " +
                    "or disable Ignore sound effects."
            }
            invalidate(jobId)
            if (job.state in activeStates || job.state == TranslationJobState.QUEUED) {
                repository.saveJob(job.copy(state = TranslationJobState.PAUSED, message = "Retrying selected region"))
            }
            manualRequests[jobId] = operation
            Triple(job, requireNotNull(generations[jobId]), contentPolicy)
        }
        try {
            val image = repository.images(jobId).first { it.id == imageId }
            val page = repository.results(jobId).first { it.imageId == imageId }
            val region = page.regions.first { it.id == regionId }
            require((region.correctedText ?: region.sourceText).isNotBlank()) {
                "Edit this region's original text or retry the image before translating it"
            }
            val network = context.activeNetworkState()
            check(network.isOnline) { "Waiting for the required network" }
            val batchId = UUID.randomUUID().toString()
            val result = withRequestPermit {
                provider.translate(
                    TranslationRequest(
                        jobId,
                        batchId,
                        job.settings.copy(
                            ocr = job.settings.ocr.copy(pipeline = OcrPipeline.PADDLE),
                            contentPolicy = contentPolicy,
                        ),
                        listOf(image),
                        listOf(OcrPageResult(imageId, listOf(region))),
                        "Translate only the supplied region ID. Preserve its geometry and ID.",
                    ),
                )
            }.pages.firstOrNull { it.imageId == imageId }?.regions?.firstOrNull { it.id == regionId }
                ?: throw TranslationException(TranslationFailureKind.CONTENT, "Response omitted the selected region")
            check(!contentPolicy.excludes(result)) {
                "The provider classified this region as a sound effect; its saved translation was retained."
            }
            // A text retry changes the translation; user-edited geometry and appearance remain authoritative.
            val translated = region.copy(
                translatedText = result.translatedText,
                aiConfidence = result.aiConfidence ?: region.aiConfidence,
            )
            val updated = page.copy(regions = page.regions.map { if (it.id == regionId) translated else it })
            validateGeometry(updated)
            coroutineContext.ensureActive()
            mutation.withLock {
                if (generations[jobId] != token) throw CancellationException("Region retry superseded")
                if (!repository.replaceResult(jobId, updated, expectedRevision = page.revision)) {
                    throw CancellationException("Region retry superseded by a newer page revision")
                }
            }
        } finally {
            withContext(NonCancellable) {
                mutation.withLock {
                    if (manualRequests[jobId] === operation) manualRequests.remove(jobId)
                }
            }
        }
    }

    /** Owned by one foreground worker; SQL state is authoritative across process death. */
    suspend fun runQueue() = execution.withLock {
        val previousReviews = repository.reviews()
        if (previousReviews.any { it.renderPreviewRequested }) {
            reviewRenderer.cleanupExcept(previousReviews.filter { it.state.pending }.map { it.id }.toSet())
        }
        val retentionMillis = preferences.settings.value.logs.retentionDays.coerceIn(1, 365) * 24L * 60 * 60 * 1000
        repository.deleteEvents(System.currentTimeMillis() - retentionMillis)
        // execution excludes any live queue owner; process death cannot run batch cancellation handlers.
        mutation.withLock {
            repository.jobs().forEach { job ->
                repository.batches(job.id).filter { it.state == "RUNNING" }.forEach { batch ->
                    repository.saveBatch(
                        batch.copy(
                            state = "INTERRUPTED",
                            message =
                            batch.message
                                ?: "Interrupted before worker restart; prior request outcome may be unknown",
                        ),
                    )
                }
            }
        }
        mutateJobs(null) {
            if (it.isStructuredFiles && it.reviewReturnState == null && it.state != TranslationJobState.CANCELLED) {
                it.copy(
                    state = if (it.imageCount > 0 &&
                        it.completedImages >= it.imageCount
                    ) {
                        TranslationJobState.COMPLETED
                    } else {
                        TranslationJobState.PAUSED
                    },
                    message = if (it.imageCount >
                        0 &&
                        it.completedImages >= it.imageCount
                    ) {
                        "Imported translations saved"
                    } else {
                        "Awaiting imported pages"
                    },
                )
            } else if (it.state in activeStates || it.state == TranslationJobState.WAITING) {
                it.copy(state = TranslationJobState.QUEUED, message = "Resuming checkpoint")
            } else {
                it
            }
        }
        try {
            coroutineScope {
                while (true) {
                    coroutineContext.ensureActive()
                    val keepRunning = mutation.withLock {
                        children.entries.removeAll { it.value.isCompleted }
                        val all = repository.jobs()
                        val network = context.activeNetworkState()
                        // Preserve checkpoints when the active connection is lost; any connected transport is eligible.
                        all.filter { it.id in children && it.state in activeStates }.forEach { job ->
                            if (!network.isOnline) {
                                invalidate(job.id)
                                repository.saveJob(
                                    job.copy(
                                        state = TranslationJobState.WAITING,
                                        message = "Waiting for the required network",
                                    ),
                                )
                            }
                        }
                        val limits = preferences.settings.value.concurrency
                        val activeIds = children.keys.toSet()
                        val activeSeries = all.filter { it.id in activeIds }.map { it.mangaId }.toSet()
                        val queued = all.filter { it.state == TranslationJobState.QUEUED && it.id !in activeIds }
                        val waiting = queued.filter { job ->
                            val ready = network.isOnline
                            if (!ready) {
                                repository.saveJob(
                                    job.copy(
                                        state = TranslationJobState.WAITING,
                                        message = "Waiting for the required network",
                                    ),
                                )
                            }
                            ready
                        }
                        val availableSeries = waiting.map { it.mangaId }.distinct()
                            .filter { it !in activeSeries }.take((limits.series - activeSeries.size).coerceAtLeast(0))
                        val allowedSeries = activeSeries + availableSeries
                        waiting.groupBy {
                            it.mangaId
                        }.filterKeys { it in allowedSeries }.forEach { (seriesId, candidates) ->
                            val already = all.count { it.mangaId == seriesId && it.id in activeIds }
                            candidates.take((limits.chapters - already).coerceAtLeast(0)).forEach { job ->
                                val token = (generations[job.id] ?: 0L) + 1
                                generations[job.id] = token
                                repository.saveJob(
                                    job.copy(
                                        state = TranslationJobState.ACQUIRING,
                                        message = "Acquiring original images",
                                    ),
                                )
                                // Register before starting so a simultaneous Pause always sees the child.
                                val child = launch(start = CoroutineStart.LAZY) { process(job, token) }
                                children[job.id] = child
                                child.invokeOnCompletion { wake.update { it + 1 } }
                                child.start()
                            }
                        }
                        waiting.filter { it.id !in children }.forEach { job ->
                            val reason = if (job.mangaId !in
                                allowedSeries
                            ) {
                                "Waiting for a series slot"
                            } else {
                                "Waiting for a chapter slot"
                            }
                            if (job.message != reason) repository.saveJob(job.copy(message = reason))
                        }
                        children.isNotEmpty() || waiting.isNotEmpty()
                    }
                    if (!keepRunning) break
                    val revision = wake.value
                    withTimeoutOrNull(1000) { wake.first { it != revision } }
                }
            }
        } finally {
            withContext(NonCancellable) {
                val remaining = mutation.withLock {
                    children.values.toList().also { it.forEach { child -> child.cancel() } }
                }
                remaining.forEach { it.join() }
                mutation.withLock {
                    children.clear()
                    repository.jobs().filter { it.state in activeStates }.forEach {
                        generations[it.id] = (generations[it.id] ?: 0L) + 1
                        repository.saveJob(
                            it.copy(state = TranslationJobState.QUEUED, message = "Interrupted; checkpoint saved"),
                        )
                    }
                }
                ocrEngine.release()
            }
        }
    }

    private suspend fun process(original: TranslationJob, token: Long) {
        try {
            state(original.id, token, TranslationJobState.ACQUIRING, "Acquiring original images")
            val cached = repository.images(original.id)
            val images = if (original.reviewReturnState != null ||
                (cached.isNotEmpty() && cached.size >= original.imageCount && cached.all { File(it.filePath).isFile })
            ) {
                cached
            } else {
                operationRecorder.run(
                    TranslationOperation(
                        UUID.randomUUID().toString(),
                        original.id,
                        TranslationStage.ACQUISITION,
                        unit = TranslationProgressUnit.PAGES,
                    ),
                    completedUnits = { acquired: List<TranslationImage> -> acquired.size.toLong() },
                ) {
                    imageSource.acquire(original).also { acquired ->
                        checkpoint(original.id, token) { repository.saveImages(original.id, acquired) }
                    }
                }
            }
            checkpoint(original.id, token) { repository.saveJob(it.copy(imageCount = images.size)) }
            val completed = repository.results(original.id).associateBy { it.imageId }
            val pending = images.filter { completed[it.id]?.imageHash != it.contentHash }
            if (pending.isNotEmpty() && original.reviewReturnState == null) {
                val config = original.settings
                val localOcr = if (config.ocr.pipeline == OcrPipeline.AI) {
                    emptyList()
                } else {
                    state(original.id, token, TranslationJobState.OCR, "Recognizing text locally")
                    val ocr = config.ocr.copy(language = ocrLanguage(config, original.mangaId))
                    pending.map { image ->
                        ocrPermit.withPermit {
                            val localOcr = TranslationOperation(
                                UUID.randomUUID().toString(),
                                original.id,
                                TranslationStage.LOCAL_OCR,
                                imageId = image.id,
                                total = 1,
                                unit = TranslationProgressUnit.PAGES,
                            )
                            operationRecorder.run(localOcr) {
                                ocrEngine.recognize(
                                    image,
                                    ocr,
                                    config.concurrency.decodedMemoryMb,
                                    mihon.feature.translation.ocr.PaddleOperationContext(
                                        original.id,
                                        localOcr.id,
                                        image.id,
                                        config.logs,
                                    ),
                                )
                            }
                        }
                    }
                }
                state(original.id, token, TranslationJobState.TRANSLATING, "Translating")
                val caps = provider.capabilities(config.provider)
                val effective = if (config.mode == TranslationMode.MAX && config.provider.maxOutputTokens == null) {
                    config.copy(
                        provider = config.provider.copy(
                            maxOutputTokens = caps.maxOutputTokens.takeIf {
                                it > 0
                            },
                        ),
                    )
                } else {
                    config
                }
                val request = TranslationRequest(
                    original.id,
                    "plan",
                    effective,
                    pending,
                    localOcr,
                    config.glossary,
                    geometryRecoveryId = original.geometryRecoveryId,
                )
                val batches = planner.plan(request)
                if (config.mode in setOf(TranslationMode.MAX, TranslationMode.HALVING)) {
                    for (batch in batches) {
                        runBatch(
                            request.copy(images = batch),
                            null,
                            config.mode == TranslationMode.HALVING,
                            token,
                        )
                    }
                } else {
                    val permits = Semaphore(
                        (
                            config.concurrency.images /
                                if (config.mode == TranslationMode.CUSTOM) config.customBatchSize else 1
                            ).coerceAtLeast(1),
                    )
                    coroutineScope {
                        batches.map { batch ->
                            async { permits.withPermit { runBatch(request.copy(images = batch), null, false, token) } }
                        }.awaitAll()
                    }
                }
            }
            runReviews(original, images, token)
            val needsReview = repository.reviews(original.id).count {
                it.state in setOf(QualityReviewState.NEEDS_REVIEW, QualityReviewState.INCOMPLETE)
            }
            val count = repository.results(original.id).count { result ->
                images.any {
                    it.id == result.imageId &&
                        it.contentHash == result.imageHash
                }
            }
            checkpoint(original.id, token) {
                repository.saveJob(
                    it.copy(
                        completedImages = count,
                        state = original.reviewReturnState ?: if (count ==
                            images.size
                        ) {
                            TranslationJobState.COMPLETED
                        } else {
                            TranslationJobState.PARTIAL
                        },
                        message = when {
                            needsReview > 0 -> "$needsReview pages need review; saved translations remain available"
                            count == images.size -> null
                            else -> "${images.size - count} images need retry"
                        },
                        reviewReturnState = null,
                        reviewImageIds = null,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val state = when ((e as? TranslationException)?.kind) {
                TranslationFailureKind.AUTHENTICATION,
                TranslationFailureKind.CONFIGURATION,
                -> TranslationJobState.PAUSED
                TranslationFailureKind.RATE_LIMIT, TranslationFailureKind.TRANSIENT -> TranslationJobState.WAITING
                else -> if (e is IOException) TranslationJobState.WAITING else TranslationJobState.FAILED
            }
            state(original.id, token, state, e.message ?: e.javaClass.simpleName)
            if ((e as? TranslationException)?.kind == TranslationFailureKind.AUTHENTICATION) {
                val related = repository.jobs().filter {
                    it.settings.provider.credentialId ==
                        original.settings.provider.credentialId
                }
                related.filter { it.state == TranslationJobState.QUEUED }.forEach {
                    control(it.id) { current ->
                        if (current.state ==
                            TranslationJobState.QUEUED
                        ) {
                            current.copy(
                                state = TranslationJobState.PAUSED,
                                message = "Provider authentication needs attention",
                            )
                        } else {
                            current
                        }
                    }
                }
            }
        }
    }

    private suspend fun runBatch(request: TranslationRequest, parentId: String?, halving: Boolean, token: Long) {
        val batch =
            TranslationBatch(UUID.randomUUID().toString(), request.jobId, parentId, request.images.map { it.id })
        checkpoint(request.jobId, token) { repository.saveBatch(batch.copy(state = "RUNNING", attempts = 1)) }
        val actual = request.copy(
            batchId = batch.id,
            ocr = request.ocr.filter { ocr ->
                request.images.any {
                    it.id ==
                        ocr.imageId
                }
            },
        )
        var unresolved = request.images
        try {
            if (!planner.fits(actual) && !(halving && actual.images.size == 1)) {
                throw TranslationException(TranslationFailureKind.LIMIT, "Batch exceeds documented limits")
            }
            // A single very tall original can still be split into bounded physical tiles by the gateway.
            val response = operationRecorder.run(
                TranslationOperation(
                    batch.id,
                    request.jobId,
                    if (actual.settings.ocr.pipeline == OcrPipeline.PADDLE) {
                        TranslationStage.TEXT_TRANSLATION
                    } else {
                        TranslationStage.AI_OCR_TRANSLATION
                    },
                    parentId = parentId,
                    batchId = batch.id,
                    total = 1,
                    unit = TranslationProgressUnit.REQUESTS,
                ),
            ) {
                withRequestPermit { provider.translate(actual) }
            }
            val expected = actual.images.associateBy { it.id }
            val duplicates = response.pages.groupingBy { it.imageId }.eachCount().filterValues { it > 1 }.keys
            val accepted = response.pages.filter { page ->
                val image = expected[page.imageId]
                image != null && page.imageId !in duplicates && page.imageHash == image.contentHash &&
                    page.width == image.width && page.height == image.height &&
                    runCatching { validateGeometry(page) }.isSuccess
            }
            repository.saveOperation(
                TranslationOperation(
                    "${batch.id}:validation", request.jobId,
                    TranslationStage.VALIDATION, parentId = batch.id, batchId = batch.id,
                    state = if (accepted.size == actual.images.size) {
                        TranslationOperationState.COMPLETED
                    } else {
                        TranslationOperationState.PARTIAL
                    },
                    completed = accepted.size.toLong(),
                    total = actual.images.size.toLong(),
                    unit = TranslationProgressUnit.PAGES,
                    message = if (accepted.size == actual.images.size) {
                        "Image identities and geometry validated"
                    } else {
                        "Missing or invalid pages require retry; accepted pages will be saved"
                    },
                ),
            )
            checkpoint(request.jobId, token) { current ->
                accepted.forEach { page ->
                    operationRecorder.run(
                        TranslationOperation(
                            "${batch.id}:save:${page.imageId}",
                            request.jobId,
                            TranslationStage.SAVE,
                            parentId = batch.id,
                            batchId = batch.id,
                            imageId = page.imageId,
                            total = 1,
                            unit = TranslationProgressUnit.PAGES,
                        ),
                    ) {
                        repository.saveNewResult(
                            request.jobId,
                            page.copy(
                                rawOcr =
                                actual.ocr.firstOrNull { it.imageId == page.imageId } ?: page.rawOcr,
                            ),
                            actual.settings.qualityReview,
                        )
                    }
                }
                repository.saveJob(current.copy(completedImages = repository.results(request.jobId).size))
            }
            unresolved = actual.images.filter { image -> accepted.none { it.imageId == image.id } }
            response.deferredFailure?.let { throw it }
            if (unresolved.isNotEmpty()) {
                throw TranslationException(
                    TranslationFailureKind.CONTENT,
                    "Provider omitted or returned invalid data for ${unresolved.size} images; valid images were saved",
                )
            }
            checkpoint(request.jobId, token) { repository.saveBatch(batch.copy(state = "COMPLETED", attempts = 1)) }
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                mutation.withLock {
                    if (repository.jobs().any {
                            it.id == request.jobId
                        }
                    ) {
                        repository.saveBatch(batch.copy(state = "INTERRUPTED", attempts = 1))
                    }
                }
            }
            throw e
        } catch (e: Exception) {
            checkpoint(request.jobId, token) {
                repository.saveBatch(batch.copy(state = "FAILED", attempts = 1, message = e.message))
            }
            event(request.jobId, "ERROR", "batch", e.message ?: "Batch failed", batch.id)
            if (halving && request.images.size > 1 && TranslationBatchPlanner.canSplit(e)) {
                checkpoint(request.jobId, token) {
                    repository.saveBatch(batch.copy(state = "SPLIT", attempts = 1, message = e.message))
                }
                if (unresolved.size == 1) {
                    runBatch(actual.copy(images = unresolved), batch.id, true, token)
                } else {
                    val (first, second) = TranslationBatchPlanner.halves(unresolved)
                    runBatch(actual.copy(images = first), batch.id, true, token)
                    runBatch(actual.copy(images = second), batch.id, true, token)
                }
            } else if (!TranslationBatchPlanner.canSplit(e)) {
                throw e
            }
        }
    }

    /** One gate survives worker restarts and includes manual retries and Count Tokens calls. */
    private suspend fun <T> withRequestPermit(block: suspend () -> T): T {
        while (true) {
            coroutineContext.ensureActive()
            val acquired = requestGate.withLock {
                if (requestsInFlight.value < preferences.settings.value.concurrency.requests) {
                    requestsInFlight.value++
                    true
                } else {
                    false
                }
            }
            if (acquired) break
            withTimeoutOrNull(1000) {
                requestsInFlight.first { it < preferences.settings.value.concurrency.requests }
            }
        }
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                requestGate.withLock { requestsInFlight.value-- }
            }
        }
    }

    private suspend fun ocrLanguage(settings: TranslationSettings, mangaId: Long): String =
        settings.ocr.language.trim().takeUnless { it.isBlank() || it.equals("auto", true) }
            ?: settings.sourceLanguage.trim().takeUnless { it.isBlank() || it.equals("auto", true) }
            ?: imageSource.sourceLanguage(mangaId)
            ?: "auto"

    private fun TranslationSettings.sameTranslationAs(other: TranslationSettings): Boolean = copy(
        style = other.style,
        logs = other.logs,
        queueColors = other.queueColors,
        qualityReview = other.qualityReview,
        geometryRecovery = other.geometryRecovery,
        contentPolicy = other.contentPolicy,
        concurrency = other.concurrency,
        autoTranslate = other.autoTranslate,
        chaptersAhead = other.chaptersAhead,
        wifiOnly = other.wifiOnly,
    ) == other

    private suspend fun control(id: String?, transform: (TranslationJob) -> TranslationJob) {
        mutation.withLock {
            repository.jobs().filter { id == null || it.id == id }.forEach { current ->
                val updated = transform(current)
                if (updated != current || current.id in manualRequests ||
                    (updated.state == TranslationJobState.PAUSED && current.state !in terminalSuccess)
                ) {
                    invalidate(current.id)
                    repository.saveJob(updated.copy(updatedAt = System.currentTimeMillis()))
                }
            }
        }
        wake.update { it + 1 }
    }

    /** Called only under mutation. */
    private fun invalidate(id: String) {
        generations[id] = (generations[id] ?: 0L) + 1
        children[id]?.cancel()
        manualRequests[id]?.cancel()
    }

    private suspend fun <T> checkpoint(id: String, token: Long, block: suspend (TranslationJob) -> T): T {
        coroutineContext.ensureActive()
        return mutation.withLock {
            coroutineContext.ensureActive()
            val job = repository.jobs().firstOrNull { it.id == id }
            if (generations[id] != token || job == null || job.state !in activeStates) {
                throw CancellationException("Translation attempt superseded")
            }
            block(job)
        }
    }

    private fun validateGeometry(result: TranslationPageResult) = QualityReviewValidation.validatePage(result)

    private suspend fun runReviews(job: TranslationJob, images: List<TranslationImage>, token: Long) {
        val selected = job.reviewImageIds
        val pending = repository.reviews(job.id).filter {
            it.state.pending && (selected == null || it.imageId in selected)
        }
            .sortedBy { review -> images.indexOfFirst { it.id == review.imageId } }
        for (review in pending) {
            state(job.id, token, TranslationJobState.TRANSLATING, "Reviewing saved page ${review.imageId}")
            val image = images.firstOrNull { it.id == review.imageId }?.let { original ->
                review.renderEvidence?.original?.takeIf {
                    it.id == original.id && it.contentHash == original.contentHash &&
                        it.width == original.width && it.height == original.height
                } ?: original
            }
            val needsImage = (review.executionSettings ?: job.settings).ocr.pipeline != OcrPipeline.PADDLE &&
                review.settings.coverage == QualityReviewCoverage.FULL_PAGE_WHEN_AVAILABLE
            if (image == null || (needsImage && !File(image.filePath).isFile)) {
                checkpoint(job.id, token) {
                    repository.completeReview(
                        review.copy(
                            state = QualityReviewState.INCOMPLETE,
                            message = "Original image unavailable for review",
                        ),
                        null,
                    )
                }
                review.renderEvidence?.let { evidence ->
                    withContext(NonCancellable) {
                        runCatching { reviewRenderer.cleanup(evidence) }.onFailure {
                            event(
                                job.id,
                                "WARNING",
                                "quality-review",
                                "Temporary review evidence cleanup deferred",
                                review.id,
                            )
                        }
                    }
                }
                continue
            }
            val imageOrder = images.associate { it.id to it.index }
            val contentPolicy = review.contentPolicy ?: job.settings.contentPolicy
            val context = repository.results(job.id)
                .filter { it.imageId != image.id && it.imageId in imageOrder }
                .sortedBy { imageOrder.getValue(it.imageId) }
                .joinToString("\n\n") { page ->
                    val passages = page.regions.sortedBy { it.readingOrder }
                        .filter { it.included && !contentPolicy.excludes(it) }
                        .joinToString("\n") { region ->
                            "${region.correctedText ?: region.sourceText} → ${region.translatedText}"
                        }
                    "Page ${imageOrder.getValue(page.imageId) + 1} (image ID: ${page.imageId})\n$passages"
                }
            val config = review.executionSettings ?: job.settings
            val effective = if (config.mode == TranslationMode.MAX && config.provider.maxOutputTokens == null) {
                config.copy(
                    provider = config.provider.copy(
                        maxOutputTokens = provider.capabilities(config.provider).maxOutputTokens.takeIf { it > 0 },
                    ),
                )
            } else {
                config
            }
            reviewCoordinator.run(
                review,
                effective.copy(style = review.renderStyle ?: preferences.effectiveSettings(job.mangaId).style),
                image,
                context,
            )
        }
    }

    private suspend fun mutateJobs(id: String?, transform: (TranslationJob) -> TranslationJob) = mutation.withLock {
        repository.jobs().filter { id == null || it.id == id }.forEach { current ->
            val updated = transform(current)
            if (updated != current) repository.saveJob(updated.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    private suspend fun state(id: String, token: Long, state: TranslationJobState, message: String?) {
        checkpoint(id, token) {
            repository.saveJob(it.copy(state = state, message = message, updatedAt = System.currentTimeMillis()))
        }
        event(
            id,
            if (state ==
                TranslationJobState.FAILED
            ) {
                "ERROR"
            } else {
                "INFO"
            },
            state.name.lowercase(),
            message ?: state.name,
        )
    }

    private suspend fun event(id: String, level: String, stage: String, message: String, batchId: String? = null) {
        repository.addEvent(
            TranslationEvent(
                UUID.randomUUID().toString(),
                id,
                batchId,
                level = level,
                stage = stage,
                message = message,
            ),
        )
    }

    companion object {
        val activeStates =
            setOf(TranslationJobState.ACQUIRING, TranslationJobState.OCR, TranslationJobState.TRANSLATING)
        val terminalSuccess = setOf(TranslationJobState.COMPLETED, TranslationJobState.CANCELLED)
        private val pausableStates =
            activeStates + setOf(TranslationJobState.QUEUED, TranslationJobState.WAITING, TranslationJobState.PAUSED)
    }
}
