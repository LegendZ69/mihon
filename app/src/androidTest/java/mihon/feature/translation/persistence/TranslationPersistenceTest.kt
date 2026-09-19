package mihon.feature.translation.persistence

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.async.coroutines.awaitAsOne
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.app.di.AppBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.data.translation.TranslationDeletionRepositoryImpl
import tachiyomi.data.translation.TranslationRepositoryImpl
import tachiyomi.domain.translation.model.GeometryCorrectionAttempt
import tachiyomi.domain.translation.model.GeometryCorrectionAttemptState
import tachiyomi.domain.translation.model.GeometryCorrectionCheckpoint
import tachiyomi.domain.translation.model.GeometryCorrectionState
import tachiyomi.domain.translation.model.GeometryIssue
import tachiyomi.domain.translation.model.GeometryIssueCode
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.QualityReviewAttempt
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.RegionGeometryIssue
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationBatch
import tachiyomi.domain.translation.model.TranslationContentPolicy
import tachiyomi.domain.translation.model.TranslationDeletionScope
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationInputTransform
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.GeometryCorrectionCoordinator
import java.io.File
import java.util.UUID

/** Real SQLite and the production database factory, always using disposable test-owned files. */
@RunWith(AndroidJUnit4::class)
class TranslationPersistenceTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun geometryCorrectionReservationSurvivesReopenAndRejectsDuplicateOrNewPolicy() = databaseTest { fixture ->
        val repository = fixture.open().repository
        val job = job("geometry-job").copy(geometryRecoveryId = "epoch-one")
        repository.saveJob(job)
        repository.saveResult(job.id, page("completed", "Preserved completed page"))
        val completed = repository.results(job.id).single()
        val checkpoint = geometryCheckpoint(job)
        assertEquals(checkpoint, repository.createGeometryCorrection(checkpoint))
        assertEquals(
            checkpoint,
            repository.createGeometryCorrection(checkpoint.copy(candidateJson = "another gesture")),
        )

        val reserved = reserveGeometry(checkpoint)
        assertTrue(repository.compareAndSetGeometryCorrection(checkpoint, reserved))
        assertTrue(!repository.compareAndSetGeometryCorrection(checkpoint, reserved))
        val reopened = fixture.reopen().repository
        assertEquals(reserved, reopened.geometryCorrection(checkpoint.id))
        assertEquals(listOf(completed), reopened.results(job.id))
        val operation = reopened.operationPage(job.id).single()
        assertEquals("target", operation.imageId)
        assertEquals("batch", operation.parentId)
        assertEquals(reserved, operation.geometryCorrection)

        reopened.saveJob(job.copy(geometryRecoveryId = "epoch-two"))
        assertTrue(!reopened.compareAndSetGeometryCorrection(reserved, completeGeometry(reserved)))
        assertEquals(GeometryCorrectionState.SUPERSEDED, reopened.geometryCorrection(checkpoint.id)!!.state)
        assertEquals(listOf(completed), reopened.results(job.id))
    }

    @Test
    fun geometryCorrectionChecksOriginalTileRevisionAndPreservesManualEditsAndRawOcr() = databaseTest { fixture ->
        val repository = fixture.open().repository
        val job = job("geometry-manual-race").copy(geometryRecoveryId = "epoch-one")
        repository.saveJob(job)
        repository.saveResult(job.id, page("target", "Initial translation"))
        val before = repository.results(job.id).single()
        val checkpoint = geometryCheckpoint(job).copy(sourceRevision = before.revision)
        assertEquals(checkpoint, repository.createGeometryCorrection(checkpoint))
        val reserved = reserveGeometry(checkpoint)
        assertTrue(repository.compareAndSetGeometryCorrection(checkpoint, reserved))
        val manual = before.copy(regions = before.regions.map { it.copy(translatedText = "Manual correction") })
        assertTrue(repository.replaceResult(job.id, manual, before.revision))
        val savedManual = repository.results(job.id).single()

        assertTrue(!repository.compareAndSetGeometryCorrection(reserved, completeGeometry(reserved)))
        assertEquals(GeometryCorrectionState.SUPERSEDED, repository.geometryCorrection(checkpoint.id)!!.state)
        assertEquals(savedManual, repository.results(job.id).single())
        assertEquals(before.rawOcr, savedManual.rawOcr)
        assertEquals(before.regions.single().detectionConfidence, savedManual.regions.single().detectionConfidence)
        assertEquals(before.regions.single().recognitionConfidence, savedManual.regions.single().recognitionConfidence)
    }

    @Test
    fun geometryCorrectionRejectsChangedPinnedInputsAndExtraReservations() = databaseTest { fixture ->
        val repository = fixture.open().repository
        val job = job("geometry-pinned").copy(geometryRecoveryId = "epoch-one")
        repository.saveJob(job)
        val checkpoint = geometryCheckpoint(job)
        repository.createGeometryCorrection(checkpoint)
        val reserved = reserveGeometry(checkpoint)
        assertTrue(!repository.compareAndSetGeometryCorrection(checkpoint, reserved.copy(candidateJson = "changed")))
        assertTrue(repository.compareAndSetGeometryCorrection(checkpoint, reserved))
        val second = reserved.copy(
            version = reserved.version + 1,
            attempts = reserved.attempts + GeometryCorrectionAttempt(2, reserved.updatedAt),
        )
        assertTrue(!repository.compareAndSetGeometryCorrection(reserved, second))
        assertEquals(reserved, repository.geometryCorrection(checkpoint.id))
    }

    @Test
    fun explicitGeometryResumePreservesReservationsAndCannotReopenAnExhaustedPass() = databaseTest { fixture ->
        val repository = fixture.open().repository
        val job = job("geometry-resume").copy(geometryRecoveryId = "epoch-one")
        repository.saveJob(job)
        val coordinator = GeometryCorrectionCoordinator(repository) { _, _ -> error("Resume must not dispatch") }
        for (limit in listOf(1, 2)) {
            val checkpoint = geometryCheckpoint(job).copy(
                id = "${job.id}:limit-$limit",
                settings = job.settings.copy(provider = job.settings.provider.copy(totalAttempts = limit)),
            )
            repository.createGeometryCorrection(checkpoint)
            val reserved = reserveGeometry(checkpoint)
            assertTrue(repository.compareAndSetGeometryCorrection(checkpoint, reserved))
            val paused = reserved.copy(
                state = GeometryCorrectionState.PAUSED,
                version = reserved.version + 1,
                attempts = reserved.attempts.map {
                    it.copy(
                        state = GeometryCorrectionAttemptState.FAILED,
                        completedAt = reserved.updatedAt,
                        failureKind = TranslationFailureKind.AUTHENTICATION,
                    )
                },
            )
            assertTrue(repository.compareAndSetGeometryCorrection(reserved, paused))
            assertEquals(limit == 2, coordinator.resumePaused(paused))
            assertTrue(!coordinator.resumePaused(paused))
            val current = repository.geometryCorrection(checkpoint.id)!!
            assertEquals(paused.attempts, current.attempts)
            assertEquals(
                if (limit == 2) GeometryCorrectionState.QUEUED else GeometryCorrectionState.PAUSED,
                current.state,
            )
        }
    }

    @Test
    fun logDeletionRetainsRecoveryCapsWhileTranslationDeletionClearsResultCopiesAndPolicy() = databaseTest { fixture ->
        val opened = fixture.open()
        val repository = opened.repository
        val deletion = TranslationDeletionRepositoryImpl(opened.database)
        val job = job("geometry-delete").copy(geometryRecoveryId = "epoch-one")
        repository.saveJob(job)
        repository.saveResult(job.id, page("completed", "Preserved completed page"))
        val saved = repository.results(job.id).single()
        val checkpoint = geometryCheckpoint(job)
        repository.createGeometryCorrection(checkpoint)
        val reserved = reserveGeometry(checkpoint)
        assertTrue(repository.compareAndSetGeometryCorrection(checkpoint, reserved))
        val completed = completeGeometry(reserved)
        assertTrue(repository.compareAndSetGeometryCorrection(reserved, completed))
        repository.saveOperation(
            tachiyomi.domain.translation.model.TranslationOperation(
                "ordinary-log",
                job.id,
                tachiyomi.domain.translation.model.TranslationStage.REQUEST,
            ),
        )
        val before = deletion.deletionSnapshots(setOf(job.id))
        assertEquals(2L, before.single().groups.getValue(TranslationDeletionScope.TRANSLATIONS).records)
        assertTrue(deletion.deleteRecords(before, setOf(TranslationDeletionScope.LOGS)))
        assertEquals(completed, repository.geometryCorrection(checkpoint.id))
        assertEquals(listOf(saved), repository.results(job.id))
        val afterLogs = deletion.deletionSnapshots(setOf(job.id))
        assertEquals(0L, afterLogs.single().groups.getValue(TranslationDeletionScope.LOGS).records)
        assertEquals(2L, afterLogs.single().groups.getValue(TranslationDeletionScope.TRANSLATIONS).records)

        assertTrue(deletion.deleteRecords(afterLogs, setOf(TranslationDeletionScope.TRANSLATIONS)))
        assertTrue(repository.results(job.id).isEmpty())
        assertEquals(null, repository.geometryCorrection(checkpoint.id))
        assertEquals(null, repository.jobs().single().geometryRecoveryId)
        val coordinator = GeometryCorrectionCoordinator(repository) { _, _ -> error("Deleted work must not dispatch") }
        assertEquals(null, coordinator.run(checkpoint))
    }

    private fun geometryCheckpoint(job: TranslationJob): GeometryCorrectionCheckpoint {
        val original = image("target", 1)
        val tile = original.copy(id = "target:tile", width = 100, height = 150, contentHash = "tile-hash")
        return GeometryCorrectionCheckpoint(
            id = "${job.id}:geometry",
            jobId = job.id,
            policyId = requireNotNull(job.geometryRecoveryId),
            image = tile,
            transform = TranslationInputTransform(original, 50, 50, 100, 150),
            settings = job.settings,
            ocr = emptyList(),
            context = "retained chapter context",
            candidateJson = "{\"regions\":[]}",
            issues = listOf(
                RegionGeometryIssue("speech", GeometryIssue(GeometryIssueCode.CONCAVE, reason = "concave")),
            ),
            batchId = "batch",
        )
    }

    private fun reserveGeometry(checkpoint: GeometryCorrectionCheckpoint): GeometryCorrectionCheckpoint {
        val time = maxOf(checkpoint.updatedAt, System.currentTimeMillis())
        return checkpoint.copy(
            state = GeometryCorrectionState.RUNNING,
            version = checkpoint.version + 1,
            updatedAt = time,
            attempts = listOf(GeometryCorrectionAttempt(1, time)),
        )
    }

    private fun completeGeometry(checkpoint: GeometryCorrectionCheckpoint): GeometryCorrectionCheckpoint {
        val time = maxOf(checkpoint.updatedAt, System.currentTimeMillis())
        return checkpoint.copy(
            state = GeometryCorrectionState.COMPLETED,
            version = checkpoint.version + 1,
            updatedAt = time,
            attempts = checkpoint.attempts.map {
                it.copy(state = GeometryCorrectionAttemptState.SUCCEEDED, completedAt = time)
            },
            result = TranslationPageResult("target:tile", "tile-hash", 100, 150, emptyList()),
        )
    }

    @Test
    fun interruptedOperationLineageAndUsageSurviveReopeningAndTranslationDeletion() = databaseTest { fixture ->
        val first = fixture.open()
        val repository = first.repository
        repository.saveJob(job("operation-job", 0, 1))
        val parent = tachiyomi.domain.translation.model.TranslationOperation(
            "review-parent",
            "operation-job",
            tachiyomi.domain.translation.model.TranslationStage.REVIEW,
            state = tachiyomi.domain.translation.model.TranslationOperationState.ACTIVE,
            startedAt = 1,
            updatedAt = 2,
            processSession = "deliberate-prior-process",
        )
        // Seed the old-process checkpoint directly so the normal write path cannot rewrite its ownership.
        first.database.translationQueries.upsertOperation(
            parent.id, parent.jobId, null, null, null,
            parent.stage.name, parent.state.name, parent.updatedAt, json.encodeToString(parent),
        )
        val child = parent.copy(
            id = "request-child",
            parentId = parent.id,
            stage = tachiyomi.domain.translation.model.TranslationStage.REQUEST,
            state = tachiyomi.domain.translation.model.TranslationOperationState.COMPLETED,
            processSession = null,
            endedAt = 3,
        )
        repository.saveOperation(child)
        repository.addEvent(
            TranslationEvent(
                "request-event",
                parent.jobId,
                operationId = child.id,
                stage = "REQUEST",
                message = "HTTP 200; content validation is separate",
            ),
        )
        val usage = tachiyomi.domain.translation.model.TranslationUsageRecord(
            "attempt-stable", parent.jobId, operationId = child.id, provider = "GROQ", model = "test", time = 3,
            reservedCurrency = "USD", reservedAmount = "1", outcomeUncertain = true,
        )
        repository.saveUsage(usage)
        repository.saveUsage(usage.copy(usage = tachiyomi.domain.translation.model.TranslationUsage(inputTokens = 5)))
        val reopened = fixture.reopen().repository
        val operations = reopened.observeOperations(parent.jobId).first()
        assertEquals(
            tachiyomi.domain.translation.model.TranslationOperationState.INTERRUPTED,
            operations.single { it.id == parent.id }.state,
        )
        assertEquals(
            tachiyomi.domain.translation.model.TranslationOperationState.COMPLETED,
            operations.single { it.id == child.id }.state,
        )
        val related = reopened.eventPage(
            tachiyomi.domain.translation.model.TranslationLogQuery(jobId = parent.jobId, operationId = parent.id),
        )
        assertTrue(related.any { it.id == "request-event" })
        assertTrue(related.any { it.id == "review-parent:process-interrupted" })
        assertEquals(1, reopened.observeUsage().first().size)
        reopened.removeJob(parent.jobId)
        assertEquals(1, reopened.observeUsage().first().size)
        assertEquals("1", reopened.usage(usage.id)!!.reservedAmount)
        assertEquals(5L, reopened.usage(usage.id)!!.usage.inputTokens)
        assertTrue(reopened.usage(usage.id)!!.outcomeUncertain)
    }

    @Test
    fun pageLogIncludesSharedBatchAndReviewAttemptsWithinTimeBounds() = databaseTest { fixture ->
        val repo = fixture.open().repository
        val job = job("log-page", 0, 1)
        repo.saveJob(job)
        repo.saveBatch(TranslationBatch("shared", job.id, imageIds = listOf("0", "1")))
        repo.saveBatch(TranslationBatch("other", job.id, imageIds = listOf("2")))
        val review = tachiyomi.domain.translation.model.TranslationOperation(
            "review-page",
            job.id,
            tachiyomi.domain.translation.model.TranslationStage.REVIEW,
            imageId = "0",
        )
        repo.saveOperation(review)
        repo.saveOperation(review.copy(id = "review-request", parentId = review.id, imageId = null))
        repo.addEvent(
            TranslationEvent(
                "shared-http",
                job.id,
                batchId = "shared",
                time = 10,
                stage = "REQUEST",
                message = "HTTP success",
            ),
        )
        repo.addEvent(
            TranslationEvent(
                "other-http",
                job.id,
                batchId = "other",
                time = 11,
                stage = "REQUEST",
                message = "Unrelated",
            ),
        )
        repo.addEvent(
            TranslationEvent(
                "review-http",
                job.id,
                operationId = "review-request",
                time = 12,
                stage = "REQUEST",
                message = "Review request",
            ),
        )
        val query = tachiyomi.domain.translation.model.TranslationLogQuery(
            jobId = job.id,
            imageId = "0",
            since = 10,
            until = 13,
        )
        assertEquals(listOf("review-http", "shared-http"), repo.eventPage(query).map { it.id })
        assertEquals(listOf("shared-http"), repo.observeEventPage(query.copy(until = 11)).first().map { it.id })
    }

    @Test
    fun queueSnapshotsImagesBatchesResultsAndEventsSurviveDatabaseRecreation() = databaseTest { fixture ->
        val original = fixture.open()
        val repository = original.repository
        val settings = TranslationSettings(
            sourceLanguage = "ja",
            targetLanguage = "fr",
            mode = TranslationMode.HALVING,
            ocr = OcrSettings(pipeline = OcrPipeline.PADDLE_AI),
            glossary = "勇者 = héros",
        )
        val active = job(
            "active",
            priority = 20,
            updatedAt = 100,
        ).copy(settings = settings, state = TranslationJobState.TRANSLATING)
        val second = job("second", priority = 20, updatedAt = 200)
        val low = job("low", priority = 0, updatedAt = 1)
        listOf(low, second, active).forEach { repository.saveJob(it) }
        val images = listOf(image("page-1", 1), image("page-0", 0))
        repository.saveImages(active.id, images)
        val parent =
            TranslationBatch("parent", active.id, imageIds = images.map { it.id }, state = "SPLIT", attempts = 1)
        val child =
            TranslationBatch(
                "child",
                active.id,
                parentId = parent.id,
                imageIds = listOf("page-0"),
                state = "COMPLETED",
                attempts = 2,
            )
        repository.saveBatch(parent)
        repository.saveBatch(child)
        val page = page("page-0", "Bonjour")
        repository.saveResult(active.id, page)
        // Updating queue state must not delete dependent images, results, or halving batches.
        val paused = active.copy(state = TranslationJobState.PAUSED, completedImages = 1, imageCount = 2)
        repository.saveJob(paused)
        val older =
            TranslationEvent(
                "event-old",
                active.id,
                time = 100,
                stage = "OCR",
                message = "Detected",
                details = mapOf("coordinates" to "10,20,110,60"),
            )
        val newer = older.copy(
            id = "event-new",
            time = 200,
            stage = "TRANSLATING",
            message = "Saved",
            capturePath = "capture-fixture.json",
        )
        repository.addEvent(newer)
        repository.addEvent(older)
        fixture.reopen().let { reopened ->
            assertEquals(listOf(paused, second, low), reopened.repository.jobs())
            assertEquals(listOf("page-0", "page-1"), reopened.repository.images(active.id).map { it.id })
            assertEquals(images.sortedBy { it.index }, reopened.repository.images(active.id))
            assertEquals(setOf(parent, child), reopened.repository.batches(active.id).toSet())
            val persisted = reopened.repository.results(active.id).single()
            assertTrue(persisted.revision > 1L)
            assertEquals(page.copy(revision = persisted.revision), persisted)
            assertEquals(
                listOf(newer, older),
                withTimeout(10000) {
                    reopened.repository.observeEvents(active.id).first()
                },
            )
        }
    }

    @Test
    fun chapterOverlayUsesNewestCommittedResultAfterRecreationAndFallsBackWhenDeleted() = databaseTest { fixture ->
        val opened = fixture.open()
        val latest = job("latest-result", priority = 0, updatedAt = 1)
        val newestQueueRow = job("newest-queue-row", priority = 100, updatedAt = 999999)
        opened.repository.saveJob(latest)
        opened.repository.saveJob(newestQueueRow)
        val oldPage = page("same-page", "Old").copy(revision = 2000)
        val newPage = page("same-page", "Latest").copy(revision = 4000)
        // Explicit commit clocks make ordering deterministic and independent of queue update time.
        opened.database.translationQueries.upsertResult(
            newestQueueRow.id,
            oldPage.imageId,
            2000,
            json.encodeToString(oldPage),
        )
        opened.database.translationQueries.upsertResult(latest.id, newPage.imageId, 4000, json.encodeToString(newPage))
        val reopened = fixture.reopen().repository
        assertEquals(listOf(newPage), withTimeout(10000) { reopened.observeResults(latest.chapterId).first() })
        reopened.deleteResults(latest.id)
        assertEquals(listOf(oldPage), withTimeout(10000) { reopened.observeResults(latest.chapterId).first() })
    }

    @Test
    fun jobDeletionCascadesWorkRowsWhileAuditRetentionUsesItsOwnBoundary() = databaseTest { fixture ->
        val repository = fixture.open().repository
        val removed = job("removed")
        val retained = job("retained")
        for (job in listOf(removed, retained)) {
            repository.saveJob(job)
            repository.saveImages(job.id, listOf(image("page", 0)))
            repository.saveResult(job.id, page("page", job.id))
            repository.saveBatch(TranslationBatch("batch-${job.id}", job.id, imageIds = listOf("page")))
        }
        val beforeBoundary = TranslationEvent("old", removed.id, time = 149, stage = "QUEUE", message = "Queued")
        val boundary = beforeBoundary.copy(id = "boundary", time = 150)
        val afterBoundary = beforeBoundary.copy(id = "new", jobId = retained.id, time = 151)
        listOf(beforeBoundary, boundary, afterBoundary).forEach { repository.addEvent(it) }
        repository.removeJob(removed.id)
        val reopened = fixture.reopen().repository
        assertEquals(listOf(retained), reopened.jobs())
        assertTrue(reopened.images(removed.id).isEmpty())
        assertTrue(reopened.results(removed.id).isEmpty())
        assertTrue(reopened.batches(removed.id).isEmpty())
        assertEquals(1, reopened.images(retained.id).size)
        assertEquals(
            listOf(boundary, beforeBoundary),
            withTimeout(10000) {
                reopened.observeEvents(removed.id).first()
            },
        )
        reopened.deleteEvents(150)
        assertEquals(listOf(afterBoundary, boundary), withTimeout(10000) { reopened.observeEvents(null).first() })
    }

    @Test
    fun migrationFromVersion15KeepsExistingLibraryDataAndCreatesDurableTranslationTables() = databaseTest { fixture ->
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        val legacy = assets.open("translation/legacy-v15-schema.json").bufferedReader().use {
            json.parseToJsonElement(it.readText()).jsonObject
        }
        assertEquals("15", legacy.getValue("schemaVersion").jsonPrimitive.content)
        BundledSQLiteDriver().open(fixture.file.absolutePath).use { connection ->
            for (statement in legacy.getValue("statements").jsonArray) {
                connection.prepare(statement.jsonPrimitive.content).use { it.step() }
            }
            connection.prepare(
                "INSERT INTO categories (_id, name, sort, flags) VALUES (42, 'Existing library category', 3, 9)",
            ).use {
                it.step()
            }
            connection.prepare("PRAGMA user_version = 15").use { it.step() }
        }
        val migrated = fixture.open()
        assertEquals("Existing library category", migrated.database.categoriesQueries.getCategory(42).awaitAsOne().name)
        assertTrue(migrated.repository.jobs().isEmpty())
        val queued = job("after-migration")
        migrated.repository.saveJob(queued)
        migrated.repository.saveImages(queued.id, listOf(image("page", 0)))
        migrated.repository.saveResult(queued.id, page("page", "Migrated"))
        migrated.repository.saveBatch(TranslationBatch("migrated-batch", queued.id, imageIds = listOf("page")))
        val event = TranslationEvent("migrated-event", queued.id, time = 100, stage = "QUEUE", message = "Saved")
        migrated.repository.addEvent(event)
        val reopened = fixture.reopen()
        assertEquals(listOf(queued), reopened.repository.jobs())
        assertEquals(1, reopened.repository.images(queued.id).size)
        assertEquals("Migrated", reopened.repository.results(queued.id).single().regions.single().translatedText)
        assertEquals(1, reopened.repository.batches(queued.id).size)
        assertEquals(listOf(event), withTimeout(10000) { reopened.repository.observeEvents(queued.id).first() })
        assertEquals(9L, reopened.database.categoriesQueries.getCategory(42).awaitAsOne().flags)
        fixture.close()
        BundledSQLiteDriver().open(fixture.file.absolutePath).use { connection ->
            connection.prepare("PRAGMA user_version").use { statement ->
                assertTrue(statement.step())
                assertEquals(Database.Schema.version, statement.getLong(0))
            }
        }
    }

    @Test
    fun currentVersion16UpgradeKeepsOldQueueDisabledAndCachedResultsUntouched() = databaseTest { fixture ->
        val opened = fixture.open()
        val oldJob = job("old-queue")
        val full = json.parseToJsonElement(json.encodeToString(oldJob)).jsonObject
        val legacy =
            JsonObject(full + ("settings" to JsonObject(full.getValue("settings").jsonObject - "qualityReview")))
        opened.database.translationQueries.upsertJob(
            oldJob.id,
            oldJob.mangaId,
            oldJob.chapterId,
            oldJob.priority,
            oldJob.updatedAt,
            legacy.toString(),
        )
        opened.repository.saveResult(oldJob.id, page("page", "Existing translation"))
        val before = opened.repository.results(oldJob.id).single()
        fixture.close()
        BundledSQLiteDriver().open(fixture.file.absolutePath).use { connection ->
            connection.prepare("DROP TABLE translation_reviews").use { it.step() }
            connection.prepare("PRAGMA user_version = 16").use { it.step() }
        }
        val migrated = fixture.open().repository
        assertTrue(!migrated.jobs().single().settings.qualityReview.enabled)
        assertEquals(before, migrated.results(oldJob.id).single())
        assertTrue(migrated.reviews(oldJob.id).isEmpty())
    }

    @Test
    fun initialResultAndReviewSurviveRecreationWithoutDuplicateScheduling() = databaseTest { fixture ->
        val repository = fixture.open().repository
        val job = job("review-checkpoint")
        repository.saveJob(job)
        repository.saveNewResult(job.id, page("page", "Initial"), QualityReviewSettings())
        val before = repository.results(job.id).single()
        val review = repository.reviews(job.id).single()
        assertEquals(before, review.beforeResult)
        assertEquals(before.revision, review.sourceRevision)
        assertEquals(job.settings.contentPolicy, review.contentPolicy)
        val reopened = fixture.reopen().repository
        reopened.saveNewResult(job.id, page("page", "Duplicate delivery"), QualityReviewSettings())
        assertEquals(before, reopened.results(job.id).single())
        assertEquals(listOf(review), reopened.reviews(job.id))
        assertEquals(null, reopened.createReview(job.id, "page", QualityReviewSettings()))
        val reserved = review.copy(
            state = QualityReviewState.RUNNING,
            attempts = listOf(QualityReviewAttempt(1, System.currentTimeMillis())),
        )
        assertTrue(reopened.reserveReviewAttempt(reserved))
        assertTrue(!reopened.reserveReviewAttempt(reserved))
        assertEquals(1, fixture.reopen().repository.reviews(job.id).single().attempts.size)
    }

    @Test
    fun selectedContentPolicySurvivesRecreationWithoutChangingLegacyPendingReviewOrSavedPages() {
        databaseTest { fixture ->
            val repository = fixture.open().repository
            val job = job("review-content-policy").let {
                it.copy(settings = it.settings.copy(contentPolicy = TranslationContentPolicy.Legacy))
            }
            repository.saveJob(job)
            repository.saveNewResult(job.id, page("legacy", "Retained sound effect"), QualityReviewSettings())
            val legacyReview = repository.reviews(job.id).single()
            assertEquals(TranslationContentPolicy.Legacy, legacyReview.contentPolicy)
            assertTrue(repository.saveReview(legacyReview.copy(contentPolicy = null)))
            repository.saveResult(job.id, page("selected", "Manual correction"))
            val preserved = repository.results(job.id).associateBy { it.imageId }
            assertTrue(
                repository.scheduleReviews(job, setOf("selected"), QualityReviewSettings(), TranslationContentPolicy()),
            )

            val reopened = fixture.reopen().repository
            val selected = reopened.reviews(job.id).single { it.imageId == "selected" }
            assertEquals(TranslationContentPolicy(), selected.contentPolicy)
            assertEquals(null, reopened.reviews(job.id).single { it.imageId == "legacy" }.contentPolicy)
            val reserved = selected.copy(
                state = QualityReviewState.RUNNING,
                attempts = listOf(QualityReviewAttempt(1, System.currentTimeMillis())),
            )
            assertTrue(reopened.reserveReviewAttempt(reserved))
            assertTrue(
                reopened.scheduleReviews(
                    job,
                    setOf("selected", "legacy"),
                    QualityReviewSettings(maxTransportAttempts = 1),
                    TranslationContentPolicy.Legacy,
                ),
            )

            val collected = fixture.reopen().repository
            val retained = collected.reviews(job.id).single { it.imageId == "selected" }
            assertEquals(selected.id, retained.id)
            assertEquals(selected.settings, retained.settings)
            assertEquals(TranslationContentPolicy(), retained.contentPolicy)
            assertEquals(reserved.attempts, retained.attempts)
            assertEquals(null, collected.reviews(job.id).single { it.imageId == "legacy" }.contentPolicy)
            assertEquals(preserved, collected.results(job.id).associateBy { it.imageId })
            assertEquals(job.settings, collected.jobs().single().settings)
        }
    }

    @Test
    fun repairAndUndoAreAtomicAndKeepRawOcrAcrossRecreation() = databaseTest { fixture ->
        val repository = fixture.open().repository
        val job = job("review-undo")
        repository.saveJob(job)
        repository.saveNewResult(job.id, page("page", "Initial"), QualityReviewSettings())
        val before = repository.results(job.id).single()
        val review = repository.reviews(job.id).single()
        val candidate = before.copy(regions = before.regions.map { it.copy(translatedText = "Repaired") })
        assertTrue(repository.completeReview(review.copy(state = QualityReviewState.REPAIRED), candidate))
        val reopened = fixture.reopen().repository
        val repaired = reopened.results(job.id).single()
        assertTrue(repaired.revision > before.revision)
        assertEquals(before.rawOcr, repaired.rawOcr)
        assertEquals(repaired.revision, reopened.reviews(job.id).single().repairedRevision)
        assertTrue(reopened.undoRepair(job.id, "page"))
        val afterUndo = fixture.reopen().repository
        val undone = afterUndo.results(job.id).single()
        assertEquals(before.copy(revision = undone.revision), undone)
        assertTrue(undone.revision > repaired.revision)
        assertTrue(!afterUndo.undoRepair(job.id, "page"))
    }

    @Test
    fun manualEditRejectsAnOlderRepairAndUndoCannotOverwriteANewerEdit() = databaseTest { fixture ->
        val repository = fixture.open().repository
        val job = job("review-edit-race")
        repository.saveJob(job)
        repository.saveNewResult(job.id, page("page", "Initial"), QualityReviewSettings())
        val review = repository.reviews(job.id).single()
        repository.saveResult(job.id, page("page", "Manual correction"))
        val manual = repository.results(job.id).single()
        assertTrue(
            !repository.completeReview(review.copy(state = QualityReviewState.REPAIRED), page("page", "Stale repair")),
        )
        val reopened = fixture.reopen().repository
        assertEquals(manual, reopened.results(job.id).single())
        assertEquals(QualityReviewState.SUPERSEDED, reopened.reviews(job.id).single().state)
        val next = requireNotNull(reopened.createReview(job.id, "page", QualityReviewSettings()))
        assertTrue(
            reopened.completeReview(next.copy(state = QualityReviewState.REPAIRED), page("page", "New repair")),
        )
        reopened.saveResult(job.id, page("page", "Final manual correction"))
        assertTrue(!reopened.undoRepair(job.id, "page"))
        assertEquals("Final manual correction", reopened.results(job.id).single().regions.single().translatedText)
    }

    private fun job(id: String, priority: Long = 0, updatedAt: Long = 100) = TranslationJob(
        id = id,
        mangaId = 1,
        chapterId = 10,
        mangaTitle = "Fixture manga",
        chapterTitle = "Fixture chapter",
        settings = TranslationSettings(),
        priority = priority,
        createdAt = 1,
        updatedAt = updatedAt,
    )

    private fun image(id: String, index: Int) = TranslationImage(
        id,
        index,
        "fixture/$id.png",
        "image/png",
        200,
        300,
        "hash-$id",
        100,
    )

    private fun page(id: String, translated: String): TranslationPageResult {
        val region = TextRegion(
            id = "speech",
            points = listOf(
                TranslationPoint(10f, 20f),
                TranslationPoint(110f, 20f),
                TranslationPoint(110f, 60f),
                TranslationPoint(10f, 60f),
            ),
            sourceText = "こんにちは",
            translatedText = translated,
            detectionConfidence = 0.94f,
            recognitionConfidence = 0.87f,
        )
        return TranslationPageResult(
            id,
            "hash-$id",
            200,
            300,
            listOf(region),
            rawOcr = OcrPageResult(id, listOf(region), rawJson = "{\"fixture\":true}"),
        )
    }

    private fun databaseTest(block: suspend (Fixture) -> Unit) = runBlocking {
        withContext(Dispatchers.IO) {
            val fixture = Fixture()
            try {
                withTimeout(30000) { block(fixture) }
            } finally {
                fixture.close()
                fixture.directory.deleteRecursively()
            }
        }
    }

    private class Fixture {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "translation-database-test-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        val file = File(directory, "translation-test.db")
        private var opened: OpenDatabase? = null

        fun open(): OpenDatabase = opened ?: OpenDatabase(
            AndroidxSqliteDriver(
                driver = BundledSQLiteDriver(),
                databaseType = AndroidxSqliteDatabaseType.File(file.absolutePath),
                schema = Database.Schema,
                configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
            ),
        ).also { opened = it }

        fun reopen(): OpenDatabase {
            close()
            return open()
        }

        fun close() {
            opened?.driver?.close()
            opened = null
        }
    }

    private class OpenDatabase(val driver: SqlDriver) {
        val database = AppBindings.providesDatabase(driver)
        val repository = TranslationRepositoryImpl(database)
    }
}
