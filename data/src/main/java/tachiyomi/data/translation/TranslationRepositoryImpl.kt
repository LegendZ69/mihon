package tachiyomi.data.translation

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.async.coroutines.awaitAsOneOrNull
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import tachiyomi.data.subscribeToList
import tachiyomi.domain.translation.model.GeometryCorrectionAttemptState
import tachiyomi.domain.translation.model.GeometryCorrectionCheckpoint
import tachiyomi.domain.translation.model.GeometryCorrectionState
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.QualityReviewFinding
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.QualityReviewSummary
import tachiyomi.domain.translation.model.TranslationBatch
import tachiyomi.domain.translation.model.TranslationBillingSnapshot
import tachiyomi.domain.translation.model.TranslationBillingStorageStats
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationLogQuery
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.model.TranslationUsageRecord
import tachiyomi.domain.translation.model.accountingProvider
import tachiyomi.domain.translation.service.TranslationJobSnapshot
import tachiyomi.domain.translation.service.TranslationOperationAttribution
import tachiyomi.domain.translation.service.TranslationRepository
import java.util.UUID

@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TranslationRepositoryImpl(private val database: Database) : TranslationRepository {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val operationStartup = Mutex()
    private var operationsReconciled = false

    private suspend fun reconcileOperations() = operationStartup.withLock {
        if (operationsReconciled) return@withLock
        database.transaction {
            queries.activeOperations().awaitAsList().forEach { payload ->
                val operation = json.decodeFromString<TranslationOperation>(payload)
                if (operation.processSession != PROCESS_SESSION) {
                    val ended = maxOf(System.currentTimeMillis(), operation.startedAt ?: 0)
                    val updated = operation.copy(
                        state = tachiyomi.domain.translation.model.TranslationOperationState.INTERRUPTED,
                        updatedAt = ended,
                        endedAt = ended,
                        message = "Previous process ended before this operation recorded completion. " +
                            "Saved pages and uncertain usage reservations are retained.",
                    )
                    writeOperation(updated)
                    addEvent(
                        TranslationEvent(
                            "${operation.id}:process-interrupted",
                            operation.jobId,
                            batchId = operation.batchId,
                            imageId = operation.imageId,
                            level = "WARN",
                            stage = operation.stage.name,
                            operationId = operation.id,
                            message = updated.message!!,
                        ),
                    )
                }
            }
        }
        operationsReconciled = true
    }

    private val queries get() = database.translationQueries

    override suspend fun resultRevision(jobId: String, imageId: String): Long? =
        database.translationDashboardQueries.geometrySourceRevision(jobId, imageId).awaitAsOneOrNull()

    override suspend fun geometryCorrection(id: String): GeometryCorrectionCheckpoint? =
        database.translationDashboardQueries.dashboardOperationIdentity(id).awaitAsOneOrNull()
            ?.let { json.decodeFromString<TranslationOperation>(it).geometryCorrection }

    override suspend fun createGeometryCorrection(
        checkpoint: GeometryCorrectionCheckpoint,
    ): GeometryCorrectionCheckpoint? = database.transactionWithResult {
        require(checkpoint.id.isNotBlank() && checkpoint.jobId.isNotBlank() && checkpoint.policyId.isNotBlank())
        checkpoint.transform.validate(checkpoint.image)
        val existing = database.translationDashboardQueries.dashboardOperationIdentity(checkpoint.id).awaitAsOneOrNull()
        if (existing != null) {
            val stored = json.decodeFromString<TranslationOperation>(existing).geometryCorrection
                ?: return@transactionWithResult null
            if (stored.jobId != checkpoint.jobId || stored.policyId != checkpoint.policyId) {
                return@transactionWithResult null
            }
            if (!geometrySourceCurrent(stored) && stored.state != GeometryCorrectionState.SUPERSEDED) {
                val superseded = stored.copy(
                    state = GeometryCorrectionState.SUPERSEDED,
                    version = stored.version + 1,
                    updatedAt = maxOf(System.currentTimeMillis(), stored.updatedAt),
                    message = "The source page or recovery policy changed; this geometry candidate is historical",
                )
                writeGeometryCorrection(superseded)
                return@transactionWithResult superseded
            }
            return@transactionWithResult stored
        }
        if (checkpoint.state != GeometryCorrectionState.QUEUED || checkpoint.version != 0L ||
            checkpoint.attempts.isNotEmpty() || checkpoint.result != null ||
            !checkpoint.settings.geometryRecovery.enabled ||
            !geometrySourceCurrent(checkpoint)
        ) {
            return@transactionWithResult null
        }
        writeGeometryCorrection(checkpoint)
        checkpoint
    }

    override suspend fun compareAndSetGeometryCorrection(
        expected: GeometryCorrectionCheckpoint,
        updated: GeometryCorrectionCheckpoint,
    ): Boolean = database.transactionWithResult {
        val stored = geometryCorrection(expected.id) ?: return@transactionWithResult false
        if (stored != expected || !validGeometryTransition(expected, updated)) return@transactionWithResult false
        if (!geometrySourceCurrent(stored)) {
            // No result is written here. A manual edit or explicit new retry invalidates this candidate.
            writeGeometryCorrection(
                stored.copy(
                    state = GeometryCorrectionState.SUPERSEDED,
                    version = stored.version + 1,
                    updatedAt = maxOf(System.currentTimeMillis(), stored.updatedAt),
                    message = "The source page or recovery policy changed; this geometry candidate was discarded",
                    result = null,
                ),
            )
            return@transactionWithResult false
        }
        writeGeometryCorrection(updated)
        true
    }

    private suspend fun geometrySourceCurrent(checkpoint: GeometryCorrectionCheckpoint): Boolean {
        val job = database.translationDashboardQueries.dashboardJobSnapshot(checkpoint.jobId).awaitAsOneOrNull()
            ?.let { TranslationJobSnapshot.decode(json, it) } ?: return false
        if (job.geometryRecoveryId != checkpoint.policyId) return false
        return database.translationDashboardQueries.geometrySourceRevision(
            checkpoint.jobId,
            checkpoint.transform.original.id,
        ).awaitAsOneOrNull() == checkpoint.sourceRevision
    }

    private fun validGeometryTransition(
        before: GeometryCorrectionCheckpoint,
        after: GeometryCorrectionCheckpoint,
    ): Boolean {
        if (before.state in setOf(
                GeometryCorrectionState.COMPLETED,
                GeometryCorrectionState.FAILED,
                GeometryCorrectionState.SUPERSEDED,
            ) || after.version != before.version + 1 || after.updatedAt < before.updatedAt
        ) {
            return false
        }
        if (after.copy(
                state = before.state,
                version = before.version,
                attempts = before.attempts,
                result = before.result,
                message = before.message,
                updatedAt = before.updatedAt,
            ) != before
        ) {
            return false
        }
        if (after.attempts.size > before.maxTransportAttempts ||
            (after.state == GeometryCorrectionState.COMPLETED) != (after.result != null) ||
            after.attempts.withIndex().any { (index, attempt) -> attempt.number != index + 1 }
        ) {
            return false
        }
        if (before.state == GeometryCorrectionState.PAUSED) {
            return after.state == GeometryCorrectionState.QUEUED && after.attempts == before.attempts &&
                before.attempts.size < before.maxTransportAttempts
        }
        if (after.attempts.size == before.attempts.size + 1) {
            val reserved = after.attempts.last()
            return before.state in setOf(GeometryCorrectionState.QUEUED, GeometryCorrectionState.INTERRUPTED) &&
                after.state == GeometryCorrectionState.RUNNING && after.attempts.dropLast(1) == before.attempts &&
                reserved.state == GeometryCorrectionAttemptState.RESERVED && reserved.completedAt == null &&
                reserved.failureKind == null &&
                before.attempts.lastOrNull()?.state != GeometryCorrectionAttemptState.RESERVED
        }
        if (after.attempts.size != before.attempts.size ||
            after.attempts.dropLast(1) != before.attempts.dropLast(1)
        ) {
            return false
        }
        val oldAttempt = before.attempts.lastOrNull()
        val newAttempt = after.attempts.lastOrNull()
        if (oldAttempt == newAttempt) {
            return after.state == GeometryCorrectionState.FAILED && before.attempts.size >= before.maxTransportAttempts
        }
        return oldAttempt?.state == GeometryCorrectionAttemptState.RESERVED && newAttempt != null &&
            newAttempt.number == oldAttempt.number && newAttempt.startedAt == oldAttempt.startedAt &&
            newAttempt.completedAt?.let { it >= newAttempt.startedAt } == true &&
            when (after.state) {
                GeometryCorrectionState.COMPLETED -> newAttempt.state == GeometryCorrectionAttemptState.SUCCEEDED
                GeometryCorrectionState.INTERRUPTED -> newAttempt.state == GeometryCorrectionAttemptState.INTERRUPTED
                GeometryCorrectionState.FAILED, GeometryCorrectionState.PAUSED, GeometryCorrectionState.QUEUED ->
                    newAttempt.state == GeometryCorrectionAttemptState.FAILED
                else -> false
            }
    }

    private suspend fun writeGeometryCorrection(checkpoint: GeometryCorrectionCheckpoint) {
        val state = when (checkpoint.state) {
            GeometryCorrectionState.QUEUED -> TranslationOperationState.QUEUED
            GeometryCorrectionState.RUNNING -> TranslationOperationState.ACTIVE
            GeometryCorrectionState.PAUSED -> TranslationOperationState.PAUSED
            GeometryCorrectionState.COMPLETED -> TranslationOperationState.COMPLETED
            GeometryCorrectionState.FAILED -> TranslationOperationState.FAILED
            GeometryCorrectionState.INTERRUPTED -> TranslationOperationState.INTERRUPTED
            GeometryCorrectionState.SUPERSEDED -> TranslationOperationState.CANCELLED
        }
        writeOperation(
            TranslationOperation(
                id = checkpoint.id,
                jobId = checkpoint.jobId,
                stage = TranslationStage.GEOMETRY_CORRECTION,
                parentId = checkpoint.batchId,
                batchId = checkpoint.batchId,
                imageId = checkpoint.transform.original.id,
                state = state,
                completed = if (checkpoint.state == GeometryCorrectionState.COMPLETED) 1 else 0,
                total = 1,
                unit = TranslationProgressUnit.PAGES,
                attempt = checkpoint.attempts.lastOrNull()?.number,
                startedAt = checkpoint.attempts.firstOrNull()?.startedAt,
                updatedAt = checkpoint.updatedAt,
                endedAt = checkpoint.updatedAt.takeIf { state.terminal },
                message = checkpoint.message,
                processSession = PROCESS_SESSION,
                provider = checkpoint.settings.provider.accountingProvider(),
                model = checkpoint.settings.provider.model,
                geometryCorrection = checkpoint,
            ),
        )
        if (checkpoint.settings.logs.enabled) {
            addEvent(
                TranslationEvent(
                    id = "${checkpoint.id}:checkpoint-${checkpoint.version}",
                    jobId = checkpoint.jobId,
                    batchId = checkpoint.batchId,
                    imageId = checkpoint.transform.original.id,
                    operationId = checkpoint.id,
                    stage = TranslationStage.GEOMETRY_CORRECTION.name,
                    level = when (state) {
                        TranslationOperationState.FAILED -> "ERROR"
                        TranslationOperationState.PAUSED, TranslationOperationState.INTERRUPTED,
                        TranslationOperationState.CANCELLED,
                        -> "WARN"
                        else -> "INFO"
                    },
                    message = checkpoint.message ?: "Geometry correction checkpoint saved",
                    details = mapOf(
                        "checkpointId" to checkpoint.id,
                        "targetInputId" to checkpoint.image.id,
                        "originalImageId" to checkpoint.transform.original.id,
                        "attempts" to checkpoint.attempts.size.toString(),
                        "maxTransportAttempts" to checkpoint.maxTransportAttempts.toString(),
                        "state" to checkpoint.state.name,
                    ),
                ),
            )
        }
    }

    override suspend fun chapterSeriesIds() = queries.chapterSeriesIds().awaitAsList()

    private fun compactCoverage(
        rows: List<tachiyomi.data.ChapterCoverageIdentities>,
    ): tachiyomi.domain.translation.service.TranslationCoverage {
        val originals = mutableListOf<tachiyomi.domain.translation.service.TranslationCoverageIdentity>()
        val saved = mutableListOf<tachiyomi.domain.translation.service.TranslationCoverageIdentity>()
        var total: Int? = null
        rows.forEach { row ->
            if (row.record_kind == "total") {
                total = row.known_total?.toInt()?.takeIf { it > 0 }
            } else {
                val identity = tachiyomi.domain.translation.service.TranslationCoverageIdentity(
                    row.image_id,
                    row.content_hash.orEmpty(),
                    row.image_width?.toInt() ?: 0,
                    row.image_height?.toInt() ?: 0,
                )
                if (row.record_kind == "original") originals += identity else saved += identity
            }
        }
        return tachiyomi.domain.translation.service.TranslationCoverage.identities(originals, saved, total)
    }

    override suspend fun chapterCoverage(chapterId: Long) = compactCoverage(
        queries.chapterCoverageIdentities(chapterId).awaitAsList(),
    )
    override fun observeChapterCoverage(
        chapterId: Long,
    ) = queries.chapterCoverageIdentities(chapterId).subscribeToList().map(::compactCoverage)

    override fun observeJobs() = queries.jobs().subscribeToList().map { values ->
        values.map { TranslationJobSnapshot.decode(json, it) }
    }

    override fun observeEvents(jobId: String?) = queries.events(jobId).subscribeToList().map { values ->
        values.map { json.decodeFromString<TranslationEvent>(it) }
    }

    override fun observeResults(chapterId: Long) = queries.chapterResults(chapterId).subscribeToList().map { values ->
        values.map { json.decodeFromString<TranslationPageResult>(it) }.distinctBy { it.imageId }
    }

    override suspend fun jobs() = queries.jobs().awaitAsList().map { TranslationJobSnapshot.decode(json, it) }

    override suspend fun saveJob(job: TranslationJob) {
        queries.upsertJob(job.id, job.mangaId, job.chapterId, job.priority, job.updatedAt, json.encodeToString(job))
    }

    override suspend fun replaceUnfinishedJob(source: TranslationJob, replacement: TranslationJob): TranslationJob? =
        database.transactionWithResult {
            val started = System.currentTimeMillis()
            val current = queries.replacementJob(source.id).awaitAsOneOrNull()?.let {
                TranslationJobSnapshot.decode(json, it)
            }
            if (current != source) return@transactionWithResult null
            require(source.mangaId >= 0 && source.chapterId >= 0) {
                "Relink an archived chapter before replacing its work"
            }
            require(
                source.state in setOf(
                    TranslationJobState.PAUSED,
                    TranslationJobState.PARTIAL,
                    TranslationJobState.FAILED,
                    TranslationJobState.COMPLETED,
                    TranslationJobState.CANCELLED,
                ),
            ) {
                "Checkpoint the source job before replacing it"
            }
            require(
                replacement.id.isNotBlank() && replacement.id != source.id && replacement.replacesJobId == source.id &&
                    replacement.mangaId == source.mangaId && replacement.chapterId == source.chapterId &&
                    replacement.archiveMetadata == source.archiveMetadata && replacement.reviewImageIds == null &&
                    replacement.reviewReturnState == null,
            ) {
                "Replacement must retain source identity and historical provenance"
            }
            replacement.settings.validate()
            queries.replacementSuccessor(
                source.id,
                source.mangaId,
                source.chapterId,
            ).awaitAsOneOrNull()?.let { payload ->
                val successor = TranslationJobSnapshot.decode(json, payload)
                require(successor.settings == replacement.settings) { "A different replacement already exists" }
                return@transactionWithResult successor
            }
            if (queries.replacementJob(replacement.id).awaitAsOneOrNull() != null) return@transactionWithResult null
            val originalCount = queries.replacementOriginalCount(source.id).awaitAsOne()
            require(originalCount <= Int.MAX_VALUE) { "Chapter exceeds supported page-count capacity" }
            val total = maxOf(source.imageCount, originalCount.toInt())
            val pending = replacement.copy(imageCount = total, completedImages = 0, state = TranslationJobState.QUEUED)
            saveJob(pending)
            // SQLite copies the original payload verbatim; raw OCR and saved revisions never enter a rewrite path.
            queries.copyReplacementOriginals(pending.id, source.id)
            queries.copyReplacementResults(pending.id, source.id)
            val saved = queries.replacementSavedCount(pending.id).awaitAsOne().toInt()
            val committed = pending.copy(
                completedImages = saved,
                state = if (total > 0 && saved == total) TranslationJobState.COMPLETED else TranslationJobState.QUEUED,
            )
            saveJob(committed)
            val finished = System.currentTimeMillis()
            val operationId = UUID.randomUUID().toString()
            val message = "Reused $saved saved pages without provider requests. " +
                "Review / Undo and usage history remain with the source job."
            writeOperation(
                TranslationOperation(
                    operationId, committed.id, tachiyomi.domain.translation.model.TranslationStage.SAVE,
                    state = tachiyomi.domain.translation.model.TranslationOperationState.COMPLETED,
                    completed = saved.toLong(),
                    total = saved.toLong(),
                    unit = tachiyomi.domain.translation.model.TranslationProgressUnit.PAGES,
                    startedAt = started,
                    updatedAt = finished,
                    endedAt = finished,
                    message = message,
                    processSession = PROCESS_SESSION,
                    provider = committed.settings.provider.kind.name, model = committed.settings.provider.model,
                ),
            )
            if (committed.settings.logs.enabled) {
                addEvent(
                    TranslationEvent(
                        "$operationId:replacement",
                        committed.id,
                        operationId = operationId,
                        operationState = tachiyomi.domain.translation.model.TranslationOperationState.COMPLETED,
                        stage = tachiyomi.domain.translation.model.TranslationStage.SAVE.name,
                        message = message,
                        details = mapOf(
                            "replacesJobId" to source.id,
                            "copiedPages" to saved.toString(),
                            "providerRequests" to "0",
                        ),
                    ),
                )
            }
            committed
        }

    override suspend fun removeJob(id: String) {
        queries.deleteJob(id)
    }

    override suspend fun images(jobId: String) = queries.images(jobId).awaitAsList().map {
        json.decodeFromString<TranslationImage>(it)
    }

    override suspend fun saveImages(jobId: String, images: List<TranslationImage>) {
        database.transaction {
            images.forEach { queries.upsertImage(jobId, it.id, it.index.toLong(), json.encodeToString(it)) }
        }
    }

    override suspend fun results(jobId: String) = queries.results(jobId).awaitAsList().map {
        json.decodeFromString<TranslationPageResult>(it)
    }

    override suspend fun saveResult(jobId: String, result: TranslationPageResult) {
        database.transaction {
            val current = results(jobId).firstOrNull { it.imageId == result.imageId }
            writeResult(jobId, result.copy(revision = nextRevision(current?.revision ?: result.revision)))
            supersedeReviews(jobId, result.imageId)
        }
    }

    override suspend fun replaceResult(jobId: String, result: TranslationPageResult, expectedRevision: Long): Boolean =
        database.transactionWithResult {
            if (results(jobId).firstOrNull { it.imageId == result.imageId }?.revision != expectedRevision) {
                return@transactionWithResult false
            }
            saveResult(jobId, result)
            true
        }

    override suspend fun batches(jobId: String) = queries.batches(jobId).awaitAsList().map {
        json.decodeFromString<TranslationBatch>(it)
    }

    override suspend fun saveBatch(batch: TranslationBatch) {
        queries.upsertBatch(batch.id, batch.jobId, json.encodeToString(batch))
    }

    override suspend fun addEvent(event: TranslationEvent) {
        queries.insertEvent(
            event.id, event.jobId, event.time,
            json.encodeToString(
                event,
            ),
            event.operationId, event.batchId, event.imageId, event.stage, event.level,
        )
    }

    override fun observeOperations(jobId: String?, limit: Long, offset: Long) = flow {
        reconcileOperations()
        emitAll(
            queries.operations(
                jobId,
                limit.coerceIn(1, 1000),
                offset.coerceAtLeast(0),
            ).subscribeToList().map { values ->
                values.map { json.decodeFromString<TranslationOperation>(it) }
            },
        )
    }

    override suspend fun operationPage(
        jobId: String?,
        limit: Long,
        offset: Long,
        stage: tachiyomi.domain.translation.model.TranslationStage?,
        since: Long?,
        until: Long?,
        provider: String?,
        model: String?,
        state: tachiyomi.domain.translation.model.TranslationOperationState?,
    ) =
        queries.filteredOperations(
            jobId = jobId,
            stage = stage?.name,
            state = state?.name,
            since = since,
            until = until,
            provider = provider,
            model = model,
            pageLimit = limit.coerceIn(1, 1000),
            pageOffset = offset.coerceAtLeast(0),
        ).awaitAsList().map {
            json.decodeFromString<TranslationOperation>(it)
        }

    override suspend fun saveOperation(operation: TranslationOperation) {
        reconcileOperations()
        database.transaction {
            val existing = database.translationDashboardQueries.dashboardOperationIdentity(
                operation.id,
            ).awaitAsOneOrNull()
                ?.let { json.decodeFromString<TranslationOperation>(it) }
            val job = if (existing == null && operation.provider == null && operation.model == null) {
                database.translationDashboardQueries.dashboardJobSnapshot(operation.jobId).awaitAsOneOrNull()
                    ?.let { TranslationJobSnapshot.decode(json, it) }
            } else {
                null
            }
            writeOperation(
                TranslationOperationAttribution.capture(operation, existing, job)
                    .copy(processSession = operation.processSession ?: PROCESS_SESSION),
            )
        }
    }

    private suspend fun writeOperation(operation: TranslationOperation) {
        operation.validate()
        queries.upsertOperation(
            operation.id, operation.jobId, operation.parentId, operation.batchId, operation.imageId,
            operation.stage.name, operation.state.name, operation.updatedAt, json.encodeToString(operation),
        )
    }

    private fun relatedBatchIds(query: TranslationLogQuery, payloads: List<String>): List<String> =
        payloads.map { json.decodeFromString<TranslationBatch>(it) }
            .filter { query.imageId in it.imageIds }.map { it.id }

    private fun eventQuery(query: TranslationLogQuery, relatedBatchIds: List<String> = emptyList()) = queries.eventPage(
        query.jobId,
        query.operationId,
        query.batchId,
        query.imageId,
        relatedBatchIds,
        query.since,
        query.until,
        query.provider,
        query.model,
        query.level, query.search, query.limit.coerceIn(1, 1000), query.offset.coerceAtLeast(0),
    )

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    override fun observeEventPage(query: TranslationLogQuery): kotlinx.coroutines.flow.Flow<List<TranslationEvent>> {
        val rows = if (query.imageId == null) {
            eventQuery(query).subscribeToList()
        } else {
            val jobId = requireNotNull(query.jobId) { "Page logs require a chapter job identity" }
            queries.relatedBatchPayloads(jobId).subscribeToList().flatMapLatest { payloads ->
                eventQuery(query, relatedBatchIds(query, payloads)).subscribeToList()
            }
        }
        return rows.map { values -> values.map { json.decodeFromString<TranslationEvent>(it) } }
    }

    override suspend fun eventPage(query: TranslationLogQuery): List<TranslationEvent> {
        val related = if (query.imageId == null) {
            emptyList()
        } else {
            val jobId = requireNotNull(query.jobId) { "Page logs require a chapter job identity" }
            relatedBatchIds(query, queries.relatedBatchPayloads(jobId).awaitAsList())
        }
        return eventQuery(query, related).awaitAsList().map { json.decodeFromString<TranslationEvent>(it) }
    }

    override suspend fun usage(id: String) = queries.usageById(id).awaitAsList().firstOrNull()?.let {
        json.decodeFromString<TranslationUsageRecord>(it)
    }

    override suspend fun usagePage(
        since: Long,
        until: Long,
        provider: String?,
        model: String?,
        limit: Long,
        offset: Long,
        jobId: String?,
    ) =
        database.translationDashboardQueries.dashboardUsageRecords(
            since,
            until,
            jobId,
            provider,
            model,
            limit.coerceIn(1, 1000),
            offset.coerceAtLeast(0),
        ).awaitAsList().map {
            json.decodeFromString<TranslationUsageRecord>(it)
        }

    override suspend fun saveUsage(record: TranslationUsageRecord) {
        queries.upsertUsage(
            record.id,
            record.jobId,
            record.time,
            record.provider,
            record.model,
            json.encodeToString(record),
        )
    }

    override fun observeUsage(since: Long) = queries.usageSince(since).subscribeToList().map { values ->
        values.map { json.decodeFromString<TranslationUsageRecord>(it) }
    }

    override suspend fun saveBilling(snapshot: TranslationBillingSnapshot) {
        queries.upsertBilling(
            snapshot.id,
            snapshot.connectionId,
            snapshot.periodStart,
            snapshot.periodEnd,
            snapshot.syncedAt,
            json.encodeToString(snapshot),
        )
    }

    override fun observeBilling() = queries.billing().subscribeToList().map { values ->
        values.map { json.decodeFromString<TranslationBillingSnapshot>(it) }
    }

    override suspend fun billingStats(connectionId: String) = queries.billingStats(connectionId) { records, bytes ->
        TranslationBillingStorageStats(records, bytes)
    }.awaitAsOne()

    override suspend fun deleteJobEvents(jobId: String) {
        queries.deleteJobEvents(jobId)
    }
    override suspend fun deleteOperations(jobId: String) {
        queries.deleteOperations(jobId)
    }
    override suspend fun deleteUsage(jobId: String) {
        queries.deleteUsage(jobId)
    }
    override suspend fun deleteBilling(connectionId: String) {
        queries.deleteBilling(connectionId)
    }

    override suspend fun deleteEvents(before: Long) {
        queries.deleteEvents(before)
    }
    override suspend fun deleteResults(jobId: String) {
        database.transaction {
            queries.deleteReviews(jobId)
            queries.deleteResults(jobId)
        }
    }

    private fun reviewSummaryQuery(jobId: String?) =
        database.translationReviewSummaryQueries.latestReviewSummaries(jobId) {
                id,
                job,
                image,
                state,
                updatedAt,
                message,
                findings,
            ->
            QualityReviewSummary(
                id = id,
                jobId = job,
                imageId = image,
                state = QualityReviewState.valueOf(state),
                updatedAt = updatedAt,
                message = message,
                findings = json.decodeFromString<List<QualityReviewFinding>>(findings),
            )
        }

    override fun observeReviewSummaries(jobId: String?) = reviewSummaryQuery(jobId).subscribeToList()

    override suspend fun reviewSummaries(jobId: String?) = reviewSummaryQuery(jobId).awaitAsList()

    override fun observeReviews(jobId: String?) = queries.reviews(jobId).subscribeToList().map { values ->
        values.map { json.decodeFromString<QualityReviewCheckpoint>(it) }
    }

    override suspend fun reviews(jobId: String?) = queries.reviews(jobId).awaitAsList().map {
        json.decodeFromString<QualityReviewCheckpoint>(it)
    }

    override suspend fun saveNewResult(jobId: String, result: TranslationPageResult, review: QualityReviewSettings) {
        database.transaction {
            val existing = results(jobId).firstOrNull { it.imageId == result.imageId }
            // Duplicate delivery and restored cached pages must not schedule another review.
            if (existing?.imageHash == result.imageHash) return@transaction
            val committed = result.copy(revision = nextRevision(existing?.revision ?: 0))
            supersedeReviews(jobId, result.imageId)
            writeResult(jobId, committed)
            if (review.enabled) {
                val snapshot = jobs().first { it.id == jobId }.settings
                writeReview(newReview(jobId, committed, review, snapshot.contentPolicy, snapshot.prompts.qualityReview))
            }
        }
    }

    override suspend fun createReview(jobId: String, imageId: String, settings: QualityReviewSettings) =
        database.transactionWithResult {
            settings.validate()
            val current = results(jobId).firstOrNull { it.imageId == imageId } ?: return@transactionWithResult null
            if (reviews(jobId).any { it.imageId == imageId && it.state.pending }) return@transactionWithResult null
            val snapshot = jobs().first { it.id == jobId }.settings
            newReview(jobId, current, settings, snapshot.contentPolicy, snapshot.prompts.qualityReview).also {
                writeReview(it)
            }
        }

    override suspend fun scheduleReviews(
        job: TranslationJob,
        imageIds: Set<String>,
        settings: QualityReviewSettings,
        contentPolicy: TranslationContentPolicy,
        prompts: tachiyomi.domain.translation.model.TranslationPromptPair,
        executionSettings: tachiyomi.domain.translation.model.TranslationSettings?,
    ): Boolean =
        database.transactionWithResult {
            if (imageIds.isEmpty()) return@transactionWithResult false
            val available = results(job.id).associateBy { it.imageId }
            require(imageIds.all { it in available }) { "A selected result is unavailable" }
            settings.validate()
            prompts.validate()
            executionSettings?.validate()
            for (imageId in imageIds) {
                val pending = reviews(job.id).firstOrNull { it.imageId == imageId && it.state.pending }
                if (pending == null) {
                    writeReview(
                        newReview(
                            job.id,
                            available.getValue(imageId),
                            settings,
                            contentPolicy,
                            prompts,
                            executionSettings,
                        ),
                    )
                } else {
                    writeReview(pending.copy(state = QualityReviewState.QUEUED))
                }
            }
            saveJob(job)
            true
        }

    override suspend fun saveReview(review: QualityReviewCheckpoint): Boolean = database.transactionWithResult {
        val stored = reviews(review.jobId).firstOrNull { it.id == review.id } ?: return@transactionWithResult false
        if (!stored.state.pending) return@transactionWithResult false
        if (review.prompts != stored.prompts || review.executionSettings != stored.executionSettings ||
            (stored.promptContext != null && review.promptContext != stored.promptContext)
        ) {
            return@transactionWithResult false
        }
        val current = results(review.jobId).firstOrNull { it.imageId == review.imageId }
        if (current?.revision != review.sourceRevision) {
            writeReview(stored.copy(state = QualityReviewState.SUPERSEDED, message = "The page was edited"))
            return@transactionWithResult false
        }
        writeReview(review)
        true
    }

    override suspend fun reserveReviewAttempt(
        review: QualityReviewCheckpoint,
    ): Boolean = database.transactionWithResult {
        val stored = reviews(review.jobId).firstOrNull { it.id == review.id } ?: return@transactionWithResult false
        if (review.attempts.size != stored.attempts.size + 1 ||
            review.attempts.size > stored.settings.maxTransportAttempts ||
            review.attempts.dropLast(1) != stored.attempts
        ) {
            return@transactionWithResult false
        }
        saveReview(review)
    }

    override suspend fun completeReview(review: QualityReviewCheckpoint, candidate: TranslationPageResult?): Boolean =
        database.transactionWithResult {
            val stored = reviews(review.jobId).firstOrNull { it.id == review.id } ?: return@transactionWithResult false
            if (!stored.state.pending) return@transactionWithResult false
            if (review.prompts != stored.prompts || review.executionSettings != stored.executionSettings ||
                (stored.promptContext != null && review.promptContext != stored.promptContext)
            ) {
                return@transactionWithResult false
            }
            val current = results(review.jobId).firstOrNull { it.imageId == review.imageId }
            if (current?.revision != review.sourceRevision) {
                writeReview(stored.copy(state = QualityReviewState.SUPERSEDED, message = "The page was edited"))
                return@transactionWithResult false
            }
            val revision = if (candidate != null) nextRevision(current.revision) else null
            if (candidate != null) writeResult(review.jobId, candidate.copy(revision = requireNotNull(revision)))
            writeReview(review.copy(repairedRevision = revision))
            true
        }

    override suspend fun undoRepair(jobId: String, imageId: String): Boolean = database.transactionWithResult {
        val review = reviews(jobId).firstOrNull { it.imageId == imageId && it.state == QualityReviewState.REPAIRED }
            ?: return@transactionWithResult false
        val current = results(jobId).firstOrNull { it.imageId == imageId } ?: return@transactionWithResult false
        if (current.revision != review.repairedRevision) return@transactionWithResult false
        writeResult(jobId, review.beforeResult.copy(revision = nextRevision(current.revision)))
        supersedeReviews(jobId, imageId)
        writeReview(review.copy(state = QualityReviewState.UNDONE, message = "Pre-repair result restored"))
        true
    }

    private fun newReview(
        jobId: String,
        result: TranslationPageResult,
        settings: QualityReviewSettings,
        contentPolicy: TranslationContentPolicy,
        prompts: tachiyomi.domain.translation.model.TranslationPromptPair,
        executionSettings: tachiyomi.domain.translation.model.TranslationSettings? = null,
    ) =
        QualityReviewCheckpoint(
            UUID.randomUUID().toString(),
            jobId,
            result.imageId,
            result.revision,
            result,
            settings,
            renderPreviewRequested = settings.includeRenderedPreview,
            contentPolicy = contentPolicy,
            prompts = prompts,
            executionSettings = executionSettings,
        )

    private suspend fun supersedeReviews(jobId: String, imageId: String) {
        reviews(jobId).filter { it.imageId == imageId && it.state.pending }.forEach {
            writeReview(it.copy(state = QualityReviewState.SUPERSEDED, message = "The source result changed"))
        }
    }

    private suspend fun writeReview(review: QualityReviewCheckpoint) {
        val updated = review.copy(updatedAt = System.currentTimeMillis())
        queries.upsertReview(
            updated.id,
            updated.jobId,
            updated.imageId,
            updated.updatedAt,
            json.encodeToString(updated),
        )
    }

    private suspend fun writeResult(jobId: String, result: TranslationPageResult) {
        queries.upsertResult(jobId, result.imageId, result.revision, json.encodeToString(result))
    }

    private fun nextRevision(previous: Long) = maxOf(System.currentTimeMillis(), previous + 1)
    private companion object {
        val PROCESS_SESSION: String = UUID.randomUUID().toString()
    }
}
