package mihon.feature.translation.transfer

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.app.di.AppBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.data.translation.SqlDelightTranslationArchiveRepository
import tachiyomi.data.translation.TranslationRepositoryImpl
import tachiyomi.domain.translation.model.GeometryCorrectionCheckpoint
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationArchiveChapter
import tachiyomi.domain.translation.model.TranslationArchiveConflictPolicy
import tachiyomi.domain.translation.model.TranslationArchiveLink
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationInputTransform
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationSettings
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Uses a disposable real SQLite database; never touches application data or resumes provider work. */
@RunWith(AndroidJUnit4::class)
class TranslationArchivePersistenceTest {
    @Test
    fun cancelledStagedRestoreRollsBackAndClosesOnlyItsOwnedDirectory() = databaseTest { fixture ->
        val db = fixture.open()
        val source = chapter()
        val preservedId = db.archive.restore(sequenceOf(source)).jobIds.single()
        val preserved = db.archive.snapshot(preservedId)!!
        val beforeJobs = db.normal.jobs()
        val stagingRoot = File(fixture.directory, "transfer-staging").apply { check(mkdirs()) }
        val unrelated = File(stagingRoot, "unrelated-marker").apply { writeText("Preserve other staged work") }
        val codec = TranslationBackupCodec(stagingRoot)
        val encoded = ByteArrayOutputStream()
        codec.write(
            flowOf(
                source.copy(job = source.job.copy(id = "cancel-first"), reviews = emptyList()),
                source.copy(job = source.job.copy(id = "cancel-second"), reviews = emptyList()),
            ),
            encoded,
        )
        val staged = codec.read(encoded.toByteArray().inputStream())
        val stagedDirectory = staged.directory
        coroutineScope {
            val firstChapterApplied = CompletableDeferred<Unit>()
            val release = CountDownLatch(1)
            val operation = launch(Dispatchers.IO) {
                val owner = currentCoroutineContext()
                db.archive.restore(
                    sequence {
                        staged.chapters().forEachIndexed { index, incoming ->
                            if (index == 1) {
                                // The previous yield returned only after the first chapter's SQL writes.
                                firstChapterApplied.complete(Unit)
                                check(release.await(5, TimeUnit.SECONDS)) { "Cancellation barrier was not released" }
                                owner.ensureActive()
                            }
                            yield(incoming)
                        }
                    },
                )
            }
            try {
                withTimeout(5_000) { firstChapterApplied.await() }
                assertTrue("Staging must survive while restore still owns it", stagedDirectory.isDirectory)
                operation.cancel()
                release.countDown()
                operation.join()
                assertTrue(operation.isCancelled)
            } finally {
                withContext(NonCancellable) {
                    operation.cancel()
                    release.countDown()
                    operation.join()
                    // Match Management's cancellation boundary: close only after operation termination.
                    staged.close()
                }
            }
        }
        assertTrue("Owned staged archive must be removed after cancellation", !stagedDirectory.exists())
        assertEquals("Preserve other staged work", unrelated.readText())
        val reopened = fixture.reopen()
        assertEquals(beforeJobs, reopened.normal.jobs())
        assertEquals(preserved, reopened.archive.snapshot(preservedId))
    }

    @Test
    fun selectedReviewPinsPromptsAndProviderAcrossDatabaseRestart() = databaseTest { fixture ->
        val db = fixture.open()
        val source = chapter().copy(reviews = emptyList())
        val jobId = db.archive.restore(sequenceOf(source)).jobIds.single()
        val savedJob = db.normal.jobs().single { it.id == jobId }
        val pair = tachiyomi.domain.translation.model.TranslationPromptPair("Pinned system", "Pinned user")
        val execution = TranslationSettings(
            ocr = tachiyomi.domain.translation.model.OcrSettings(
                pipeline = tachiyomi.domain.translation.model.OcrPipeline.PADDLE,
            ),
        )
        assertTrue(
            db.normal.scheduleReviews(
                savedJob.copy(reviewReturnState = savedJob.state, reviewImageIds = listOf("page")),
                setOf("page"),
                QualityReviewSettings(),
                prompts = pair,
                executionSettings = execution,
            ),
        )
        val restarted = fixture.reopen()
        val checkpoint = restarted.normal.reviews(jobId).single()
        assertEquals(pair, checkpoint.prompts)
        assertEquals(execution, checkpoint.executionSettings)
        assertTrue(restarted.normal.saveReview(checkpoint.copy(promptContext = "Pinned chapter context")))
        val pinned = restarted.normal.reviews(jobId).single()
        assertTrue(!restarted.normal.saveReview(pinned.copy(prompts = pair.copy(system = "Changed"))))
        assertTrue(!restarted.normal.saveReview(pinned.copy(promptContext = "Changed context")))
        assertTrue(!restarted.normal.saveReview(pinned.copy(executionSettings = execution.copy(targetLanguage = "fr"))))
        assertEquals(source.results, restarted.normal.results(jobId))
        assertEquals(pinned, restarted.normal.reviews(jobId).single())
    }

    @Test
    fun zipPreviewRejectsChangedLocalResultsWithoutLinkingOrOverwriting() = databaseTest { fixture ->
        val db = fixture.open()
        val source = chapter()
        db.archive.restore(sequenceOf(source))
        val before = db.archive.snapshot(source.job.id)!!
        val preview = db.archive.preview()
        db.normal.saveResult(
            source.job.id,
            before.results.single().copy(
                regions = before.results.single().regions.map {
                    it.copy(translatedText = "Manual correction after ZIP preview")
                },
            ),
        )
        val manual = db.archive.snapshot(source.job.id)!!
        val failure = runCatching {
            db.archive.restore(sequenceOf(source), TranslationArchiveConflictPolicy.REPLACE, preview = preview)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        val reopened = fixture.reopen().archive.snapshot(source.job.id)!!
        assertEquals(manual.results, reopened.results)
        assertEquals(manual.reviews, reopened.reviews)
    }

    @Test
    fun zipPreviewGuardsAbsentTargetsAndAllowsUnrelatedBookkeeping() = databaseTest { fixture ->
        val db = fixture.open()
        val source = chapter()
        val emptyPreview = db.archive.preview()
        db.archive.restore(sequenceOf(source))
        val current = db.archive.snapshot(source.job.id)!!
        val failure = runCatching {
            db.archive.restore(sequenceOf(source), TranslationArchiveConflictPolicy.REPLACE, preview = emptyPreview)
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        val preview = db.archive.preview()
        db.normal.saveJob(current.job.copy(priority = current.job.priority + 1, message = "Paused for import"))
        db.normal.saveJob(current.job.copy(id = "unrelated-job", mangaId = 200, chapterId = 201))
        val result = db.archive.restore(sequenceOf(source), preview = preview)
        assertEquals(1, result.identicalPages)
        assertEquals(current.results, db.archive.snapshot(source.job.id)!!.results)
    }

    @Test
    fun linkedZipPreviewRejectsNewChapterJobCreatedBeforeConfirmation() = databaseTest { fixture ->
        val db = fixture.open()
        val source = chapter().copy(reviews = emptyList())
        val preview = db.archive.preview()
        val target = source.job.copy(
            id = "chapter-created-after-preview",
            mangaId = 100,
            chapterId = 101,
            state = TranslationJobState.PAUSED,
        )
        val original = source.images.single().copy(filePath = "/owned/original.png")
        db.normal.saveJob(target)
        db.normal.saveImages(target.id, listOf(original))
        val link = TranslationArchiveLink(100, 101, listOf(original), targetJobId = target.id)
        val failure = runCatching {
            db.archive.restore(
                sequenceOf(source),
                TranslationArchiveConflictPolicy.REPLACE,
                links = mapOf(source.job.id to link),
                preview = preview,
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(db.normal.results(target.id).isEmpty())
        assertEquals(1, db.normal.jobs().size)
    }

    @Test
    fun structuredImportRechecksPreviewRevisionAndPreservesRawOcrOnConflict() = databaseTest { fixture ->
        val db = fixture.open()
        val source = chapter().copy(reviews = emptyList())
        val target = source.job.copy(
            id = "structured-target",
            mangaId = 100,
            chapterId = 101,
            state = TranslationJobState.PAUSED,
            completedImages = 0,
        )
        val original = source.images.single().copy(filePath = "/owned/original.png")
        db.normal.saveJob(target)
        db.normal.saveImages(target.id, listOf(original))
        val structured = source.copy(
            job = source.job.copy(
                settings = source.job.settings.copy(
                    mode = tachiyomi.domain.translation.model.TranslationMode.STRUCTURED_FILES,
                ),
            ),
        )
        val link = TranslationArchiveLink(
            100,
            101,
            listOf(original),
            targetJobId = target.id,
            expectedRevisions = mapOf(original.id to null),
        )
        val first = db.archive.restore(sequenceOf(structured), links = mapOf(source.job.id to link))
        assertEquals(1, first.importedPages)
        val saved = db.normal.results(target.id).single()
        val selectedLink = link.copy(
            expectedRevisions = mapOf(original.id to saved.revision),
            replaceImageIds = setOf(original.id),
        )
        db.normal.saveResult(
            target.id,
            saved.copy(
                regions = saved.regions.map {
                    it.copy(translatedText = "Manual edit while import preview is open")
                },
            ),
        )
        val manual = db.normal.results(target.id).single()
        val rejected = runCatching {
            db.archive.restore(sequenceOf(structured), links = mapOf(source.job.id to selectedLink))
        }.exceptionOrNull()
        assertTrue(rejected is IllegalArgumentException)
        val reopened = fixture.reopen()
        assertEquals(manual, reopened.normal.results(target.id).single())
        assertEquals(source.results.single().rawOcr, manual.rawOcr)
        assertTrue(reopened.normal.jobs().single().isStructuredFiles)
        assertTrue(reopened.normal.batches(target.id).isEmpty())
        assertTrue(reopened.normal.reviews(target.id).isEmpty())
    }

    @Test
    fun changedUnselectedOriginalsRejectPartialImportWithoutOrphaningSavedPages() = databaseTest { fixture ->
        val db = fixture.open()
        val source = chapter().copy(reviews = emptyList())
        val target = source.job.copy(
            id = "saved-source-revision",
            mangaId = 100,
            chapterId = 101,
            state = TranslationJobState.COMPLETED,
            imageCount = 3,
            completedImages = 3,
        )
        val originals = (0..2).map { index ->
            source.images.single().copy(
                id = index.toString(),
                index = index,
                contentHash = ('a' + index).toString().repeat(64),
                filePath = "/owned/old-$index.png",
            )
        }
        db.normal.saveJob(target)
        db.normal.saveImages(target.id, originals)
        originals.forEach { image ->
            val page = source.results.single()
            db.normal.saveResult(
                target.id,
                page.copy(
                    imageId = image.id,
                    imageHash = image.contentHash,
                    rawOcr = page.rawOcr?.copy(imageId = image.id),
                ),
            )
        }
        requireNotNull(db.normal.createReview(target.id, "0", QualityReviewSettings()))
        val before = requireNotNull(db.archive.snapshot(target.id))
        val selected = before.results.single { it.imageId == "1" }
        val incoming = TranslationArchiveChapter(
            source.job.copy(id = "selected-import"),
            listOf(originals[1].copy(filePath = "")),
            listOf(selected),
        )
        val changed = originals.map {
            if (it.index == 0) it.copy(contentHash = "d".repeat(64)) else it
        }
        val reordered = listOf(
            originals[2].copy(id = "0", index = 0),
            originals[1],
            originals[0].copy(id = "2", index = 2),
        )
        val shortened = originals.take(2)
        for (verified in listOf(changed, reordered, shortened)) {
            val link = TranslationArchiveLink(
                100,
                101,
                verified,
                targetJobId = target.id,
                expectedRevisions = mapOf("1" to selected.revision),
            )
            val failure = runCatching {
                db.archive.restore(sequenceOf(incoming), links = mapOf(incoming.job.id to link))
            }.exceptionOrNull()
            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("originals changed"))
            assertEquals(before, db.archive.snapshot(target.id))
        }
        val reopened = fixture.reopen()
        assertEquals(before, reopened.archive.snapshot(target.id))
        assertTrue(reopened.normal.batches(target.id).isEmpty())
    }

    @Test
    fun sameOriginalIdentitiesCanRefreshPrivatePathsWithoutChangingSavedResults() = databaseTest { fixture ->
        val db = fixture.open()
        val source = chapter().copy(reviews = emptyList())
        val target = source.job.copy(id = "same-originals", mangaId = 100, chapterId = 101)
        val original = source.images.single().copy(filePath = "/owned/missing.png")
        db.normal.saveJob(target)
        db.normal.saveImages(target.id, listOf(original))
        db.normal.saveResult(target.id, source.results.single())
        val before = requireNotNull(db.archive.snapshot(target.id))
        val verified = original.copy(filePath = "/owned/reacquired.png")
        val link = TranslationArchiveLink(
            100,
            101,
            listOf(verified),
            targetJobId = target.id,
            expectedRevisions = mapOf(original.id to before.results.single().revision),
        )
        val report = db.archive.restore(sequenceOf(source), links = mapOf(source.job.id to link))
        assertEquals(1, report.identicalPages)
        val saved = requireNotNull(fixture.reopen().archive.snapshot(target.id))
        assertEquals(listOf(verified), saved.images)
        assertEquals(before.results, saved.results)
        assertEquals(before.reviews, saved.reviews)
        assertEquals(before.job.settings, saved.job.settings)
    }

    @Test
    fun structuredImportCompletesAnExistingUnacquiredJobUsingVerifiedPageCount() = databaseTest { fixture ->
        val db = fixture.open()
        val source = chapter().copy(reviews = emptyList()).let {
            it.copy(
                job = it.job.copy(
                    settings = it.job.settings.copy(
                        mode = tachiyomi.domain.translation.model.TranslationMode.STRUCTURED_FILES,
                    ),
                ),
            )
        }
        val target = source.job.copy(
            id = "existing-unacquired",
            mangaId = 100,
            chapterId = 101,
            settings = source.job.settings.copy(mode = tachiyomi.domain.translation.model.TranslationMode.VERTEX),
            state = TranslationJobState.PAUSED,
            imageCount = 0,
            completedImages = 0,
        )
        db.normal.saveJob(target)
        val original = source.images.single().copy(filePath = "/owned/verified-original.png")
        val link = TranslationArchiveLink(
            100,
            101,
            listOf(original),
            targetJobId = target.id,
            expectedRevisions = mapOf(original.id to null),
        )
        val report = db.archive.restore(sequenceOf(source), links = mapOf(source.job.id to link))
        assertEquals(1, report.importedPages)
        val reopened = fixture.reopen()
        val saved = reopened.archive.snapshot(target.id)!!
        assertEquals(1, saved.job.imageCount)
        assertEquals(1, saved.job.completedImages)
        assertEquals(TranslationJobState.COMPLETED, saved.job.state)
        assertEquals("Imported translations saved", saved.job.message)
        assertTrue(saved.job.isStructuredFiles)
        assertEquals(target.settings, saved.job.settings)
        assertEquals(listOf(original), saved.images)
        assertEquals(source.results, saved.results)
        assertTrue(saved.reviews.isEmpty())
        assertTrue(reopened.normal.batches(target.id).isEmpty())
    }

    @Test
    fun structuredPartialImportRemainsAwaitingAndDuplicateDoesNotAdvanceRevision() = databaseTest { fixture ->
        val db = fixture.open()
        val source = chapter().copy(reviews = emptyList()).let {
            it.copy(
                job = it.job.copy(
                    settings = it.job.settings.copy(
                        mode = tachiyomi.domain.translation.model.TranslationMode.STRUCTURED_FILES,
                    ),
                ),
            )
        }
        val page = source.images.single().copy(filePath = "/owned/one.png")
        val missing = page.copy(id = "unimported", index = 1, contentHash = "b".repeat(64), filePath = "/owned/two.png")
        val link = TranslationArchiveLink(
            100,
            101,
            listOf(page, missing),
            targetJobId = "partial-import",
            expectedRevisions = mapOf(page.id to null),
        )
        db.archive.restore(sequenceOf(source), links = mapOf(source.job.id to link))
        val initial = db.archive.snapshot("partial-import")!!
        assertEquals(TranslationJobState.PAUSED, initial.job.state)
        assertEquals("Awaiting imported pages", initial.job.message)
        assertEquals(1, initial.job.completedImages)
        assertEquals(2, initial.job.imageCount)
        val duplicate = db.archive.restore(
            sequenceOf(source),
            links = mapOf(
                source.job.id to
                    link.copy(expectedRevisions = mapOf(page.id to initial.results.single().revision)),
            ),
        )
        assertEquals(1, duplicate.identicalPages)
        val reopened = fixture.reopen().archive.snapshot("partial-import")!!
        assertEquals(initial.results, reopened.results)
        assertEquals(initial.job.archiveMetadata, reopened.job.archiveMetadata)
        assertTrue(reopened.reviews.isEmpty())
    }

    @Test
    fun restoredResultKeepsRawOcrRevisionAndUndoAcrossRestartAndDuplicateImport() = databaseTest { fixture ->
        val chapter = chapter()
        val first = fixture.open()
        val imported = first.archive.restore(sequenceOf(chapter))
        assertEquals(1, imported.importedPages)
        assertEquals(1, imported.unlinkedJobs)
        val restoredId = imported.jobIds.single()
        val reopened = fixture.reopen()
        val restored = reopened.archive.snapshot(restoredId)!!
        assertEquals(chapter.results.single(), restored.results.single())
        assertEquals(chapter.reviews.single().beforeResult, restored.reviews.single().beforeResult)
        assertEquals(-1L, restored.job.chapterId)
        assertEquals(TranslationJobState.COMPLETED, restored.job.state)
        assertEquals("", restored.images.single().filePath)
        val duplicate = reopened.archive.restore(sequenceOf(chapter))
        assertEquals(0, duplicate.importedPages)
        assertEquals(1, duplicate.identicalPages)
        assertTrue(reopened.normal.undoRepair(restoredId, "page"))
        val undone = reopened.normal.results(restoredId).single()
        assertEquals("The door was open", undone.regions.single().translatedText)
        assertTrue(undone.revision > 42L)
        assertEquals(chapter.results.single().rawOcr, undone.rawOcr)
        val preserved = reopened.archive.restore(sequenceOf(chapter))
        assertEquals(1, preserved.preservedConflicts)
        assertEquals(undone, reopened.normal.results(restoredId).single())
    }

    @Test
    fun explicitReplacementAdvancesRevisionPreservesHistoryAndRebindsImportedUndo() = databaseTest { fixture ->
        val chapter = chapter()
        val db = fixture.open()
        val jobId = db.archive.restore(sequenceOf(chapter)).jobIds.single()
        val source = chapter.results.single()
        db.normal.saveResult(
            jobId,
            source.copy(
                regions = source.regions.map {
                    it.copy(translatedText = "Local manual correction")
                },
            ),
        )
        val local = db.normal.results(jobId).single()
        val pending = requireNotNull(db.normal.createReview(jobId, source.imageId, QualityReviewSettings()))
        val report = db.archive.restore(sequenceOf(chapter), TranslationArchiveConflictPolicy.REPLACE)
        assertEquals(1, report.importedPages)
        val restored = fixture.reopen().archive.snapshot(jobId)!!
        val replacement = restored.results.single()
        assertTrue(replacement.revision > local.revision)
        assertEquals(source.regions, replacement.regions)
        assertEquals(source.rawOcr, replacement.rawOcr)
        assertEquals(42L, restored.job.archiveMetadata!!.sourceRevisions.getValue(source.imageId))
        assertEquals(QualityReviewState.SUPERSEDED, restored.reviews.single { it.id == pending.id }.state)
        assertTrue(restored.reviews.any { it.id == chapter.reviews.single().id })
        assertEquals(
            replacement.revision,
            restored.reviews.single {
                it.state == QualityReviewState.REPAIRED
            }.repairedRevision,
        )
        assertTrue(fixture.open().normal.undoRepair(jobId, source.imageId))
        assertEquals("The door was open", fixture.open().normal.results(jobId).single().regions.single().translatedText)
    }

    @Test
    fun relinkUsesMatchingOriginalIdentityAndPreservesItsPathWithoutSchedulingWork() = databaseTest { fixture ->
        val chapter = chapter()
        val db = fixture.open()
        val target = chapter.job.copy(
            id = "local-target",
            mangaId = 100,
            chapterId = 101,
            state = TranslationJobState.PAUSED,
            completedImages = 0,
        )
        val localImage = chapter.images.single().copy(id = "0", filePath = "/owned/original.png")
        db.normal.saveJob(target)
        db.normal.saveImages(target.id, listOf(localImage))
        val report = db.archive.restore(
            sequenceOf(chapter),
            links = mapOf(chapter.job.id to TranslationArchiveLink(100, 101)),
        )
        assertEquals(1, report.linkedJobs)
        assertEquals(target.id, report.jobIds.single())
        val restored = fixture.reopen().archive.snapshot(target.id)!!
        assertEquals(localImage, restored.images.single())
        assertEquals("0", restored.results.single().imageId)
        assertEquals("0", restored.results.single().rawOcr!!.imageId)
        assertEquals(chapter.results.single().regions, restored.results.single().regions)
        assertEquals(TranslationJobState.COMPLETED, restored.job.state)
        assertTrue(fixture.open().normal.batches(target.id).isEmpty())
        assertTrue(restored.reviews.none { it.state.pending })
        assertTrue(fixture.open().normal.undoRepair(target.id, "0"))
    }

    @Test
    fun laterInvalidChapterRollsBackEveryEarlierPage() = databaseTest { fixture ->
        val db = fixture.open()
        val failure = runCatching {
            db.archive.restore(
                sequence {
                    yield(chapter())
                    throw IllegalArgumentException("Invalid later entry")
                },
            )
        }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertTrue(db.normal.jobs().isEmpty())
        assertTrue(db.normal.reviews().isEmpty())
    }

    @Test
    fun importedHistoryIsInertDeduplicatedAndNeverOverwritesAnExistingEvent() = databaseTest { fixture ->
        val db = fixture.open()
        val jobId = db.archive.restore(sequenceOf(chapter())).jobIds.single()
        val event = tachiyomi.domain.translation.model.TranslationEvent(
            "historic-event",
            jobId,
            imageId = "page",
            stage = "REQUEST",
            message = "Original diagnostic",
            operationId = "historic-request",
        )
        val operation = tachiyomi.domain.translation.model.TranslationOperation(
            "historic-request",
            jobId,
            tachiyomi.domain.translation.model.TranslationStage.REQUEST,
            imageId = "page",
            state = tachiyomi.domain.translation.model.TranslationOperationState.COMPLETED,
        )
        db.archive.importHistory(listOf(event), listOf(operation))
        db.archive.importHistory(listOf(event.copy(message = "Archive tries to replace history")), listOf(operation))
        val restored = fixture.reopen()
        val events = restored.normal.eventPage(tachiyomi.domain.translation.model.TranslationLogQuery(jobId = jobId))
        assertEquals(listOf(event), events)
        val operations = restored.normal.observeOperations(jobId).first()
        assertEquals(1, operations.size)
        assertEquals(operation.id, operations.single().id)
        assertTrue(operations.single().state.terminal)
        assertTrue(restored.normal.batches(jobId).isEmpty())
    }

    @Test
    fun archiveRepositoryDropsExecutableCheckpointAtItsPersistenceBoundary() = databaseTest { fixture ->
        val db = fixture.open()
        val jobId = db.archive.restore(sequenceOf(chapter())).jobIds.single()
        val saved = db.archive.snapshot(jobId)!!
        val image = saved.images.single()
        val checkpoint = GeometryCorrectionCheckpoint(
            "private-checkpoint", jobId, "policy", image, TranslationInputTransform(image), saved.job.settings,
            emptyList(), "private context", "private candidate", emptyList(),
        )
        val history = tachiyomi.domain.translation.model.TranslationOperation(
            "imported-operation",
            jobId,
            tachiyomi.domain.translation.model.TranslationStage.GEOMETRY_CORRECTION,
            imageId = image.id,
            completed = 1,
            total = 2,
            state = tachiyomi.domain.translation.model.TranslationOperationState.COMPLETED,
            geometryCorrection = checkpoint,
        )
        db.archive.importHistory(emptyList(), listOf(history))
        val reopened = fixture.reopen()
        val stored = reopened.normal.observeOperations(jobId).first().single()
        assertEquals(history.id, stored.id)
        assertEquals(1L, stored.completed)
        assertEquals(2L, stored.total)
        assertNull(stored.geometryCorrection)
        assertNull(reopened.normal.geometryCorrection(history.id))
        assertEquals(saved.results, reopened.normal.results(jobId))
    }

    private fun chapter(): TranslationArchiveChapter {
        val region =
            TextRegion(
                "bubble",
                listOf(
                    TranslationPoint(1f, 1f),
                    TranslationPoint(99f, 1f),
                    TranslationPoint(99f, 79f),
                    TranslationPoint(1f, 79f),
                ),
                "raw source",
                "The door was closed",
                correctedText = "corrected source",
                recognitionConfidence = 0.93f,
            )
        val image = TranslationImage("page", 0, "", "image/png", 100, 80, "a".repeat(64), 100)
        val result =
            TranslationPageResult(
                "page",
                image.contentHash,
                100,
                80,
                listOf(region),
                rawOcr = OcrPageResult("page", listOf(region.copy(correctedText = null))),
                revision = 42,
            )
        val before = result.copy(regions = listOf(region.copy(translatedText = "The door was open")), revision = 41)
        val job =
            TranslationJob(
                "archive-job", 7, 9, "Series", "Chapter",
                TranslationSettings(
                    provider = tachiyomi.domain.translation.model.ProviderSettings(credentialId = ""),
                ),
                state = TranslationJobState.COMPLETED, imageCount = 1, completedImages = 1,
            )
        val review =
            QualityReviewCheckpoint(
                "review",
                job.id,
                "page",
                41,
                before,
                QualityReviewSettings(),
                state = QualityReviewState.REPAIRED,
                repairedRevision = 42,
            )
        return TranslationArchiveChapter(job, listOf(image), listOf(result), listOf(review))
    }

    private fun databaseTest(block: suspend (Fixture) -> Unit) = runBlocking {
        withContext(Dispatchers.IO) { withTimeout(30_000) { Fixture().use { block(it) } } }
    }

    private class Fixture : java.io.Closeable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "translation-archive-test-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        private var active: Opened? = null
        fun open(): Opened = active ?: Opened(
            AndroidxSqliteDriver(
                driver = BundledSQLiteDriver(),
                databaseType = AndroidxSqliteDatabaseType.File(File(directory, "archive.db").absolutePath),
                schema = Database.Schema,
                configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
            ),
        ).also { active = it }
        fun reopen(): Opened {
            active?.driver?.close()
            active = null
            return open()
        }
        override fun close() {
            active?.driver?.close()
            directory.deleteRecursively()
        }
    }

    private class Opened(val driver: SqlDriver) {
        val database = AppBindings.providesDatabase(driver)
        val archive = SqlDelightTranslationArchiveRepository(database)
        val normal = TranslationRepositoryImpl(database)
    }
}
