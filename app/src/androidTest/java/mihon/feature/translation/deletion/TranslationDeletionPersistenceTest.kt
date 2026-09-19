package mihon.feature.translation.deletion

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import mihon.app.di.AppBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.data.translation.TranslationDeletionRepositoryImpl
import tachiyomi.data.translation.TranslationRepositoryImpl
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationDeletionScope
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.model.TranslationUsageRecord
import java.io.File
import java.util.UUID

/** Disposable real SQLite boundary; no application rows, credentials or provider work are used. */
@RunWith(AndroidJUnit4::class)
class TranslationDeletionPersistenceTest {
    @Test
    fun translationDeletionRetainsOriginalsAccountingLogsAndOtherJobsAcrossRestart() = databaseTest { fixture ->
        var db = fixture.open()
        val first = fixture.seed(db, "first")
        val second = fixture.seed(db, "second")
        val images = db.normal.images(first.id)
        val other = db.normal.results(second.id)
        val preview = db.deletion.deletionSnapshots(setOf(first.id))
        assertEquals(2L, preview.single().groups.getValue(TranslationDeletionScope.TRANSLATIONS).records)
        assertTrue(preview.single().groups.getValue(TranslationDeletionScope.TRANSLATIONS).payloadBytes > 0)

        assertTrue(db.deletion.deleteRecords(preview, setOf(TranslationDeletionScope.TRANSLATIONS)))

        db = fixture.reopen()
        assertTrue(db.normal.results(first.id).isEmpty())
        assertTrue(db.normal.reviews(first.id).isEmpty())
        assertEquals(images, db.normal.images(first.id))
        assertEquals("original source first", File(images.single().filePath).readText())
        assertEquals(other, db.normal.results(second.id))
        assertEquals(1, db.normal.observeEvents(first.id).first().size)
        assertEquals(1, db.normal.observeOperations(first.id).first().size)
        assertEquals("3.00", db.normal.usage("first-usage")!!.reservedAmount)
        assertTrue(db.normal.usage("first-usage")!!.outcomeUncertain)
        val shell = db.normal.jobs().single { it.id == first.id }
        assertEquals(TranslationJobState.PAUSED, shell.state)
        assertEquals(0, shell.completedImages)
        assertEquals(first.settings.provider.credentialId, shell.settings.provider.credentialId)
    }

    @Test
    fun manualEditAfterPreviewRejectsDeletionOfEverySelectedJob() = databaseTest { fixture ->
        val db = fixture.open()
        val first = fixture.seed(db, "first")
        val second = fixture.seed(db, "second")
        val preview = db.deletion.deletionSnapshots(setOf(first.id, second.id))
        val old = db.normal.results(first.id).single()
        val corrected = old.copy(
            regions = old.regions.map {
                it.copy(correctedText = "manual correction")
            },
            revision = 43,
        )
        assertTrue(db.normal.replaceResult(first.id, corrected, old.revision))

        assertFalse(db.deletion.deleteRecords(preview, setOf(TranslationDeletionScope.TRANSLATIONS)))

        assertEquals(corrected.regions, db.normal.results(first.id).single().regions)
        assertEquals(1, db.normal.results(second.id).size)
        assertEquals(1, db.normal.reviews(second.id).size)
    }

    @Test
    fun deletingApplicationLogsAndAccountingDoesNotChangeSavedCorrectionsOrUndo() = databaseTest { fixture ->
        val db = fixture.open()
        val job = fixture.seed(db, "first")
        val saved = db.normal.results(job.id)
        val reviews = db.normal.reviews(job.id)
        val preview = db.deletion.deletionSnapshots(setOf(job.id))
        assertTrue(
            db.deletion.deleteRecords(
                preview,
                setOf(TranslationDeletionScope.LOGS, TranslationDeletionScope.ACCOUNTING),
            ),
        )
        assertEquals(saved, db.normal.results(job.id))
        assertEquals(reviews, db.normal.reviews(job.id))
        assertTrue(db.normal.observeEvents(job.id).first().isEmpty())
        assertTrue(db.normal.observeOperations(job.id).first().isEmpty())
        assertEquals(null, db.normal.usage("first-usage"))
        assertTrue(db.normal.undoRepair(job.id, "page"))
        assertEquals("before review", db.normal.results(job.id).single().regions.single().translatedText)
        assertEquals(saved.single().rawOcr, db.normal.results(job.id).single().rawOcr)
    }

    @Test
    fun replacementReusesSavedRevisionsRetainsHistoryAndDeduplicatesCreationAcrossRestart() = databaseTest { fixture ->
        var db = fixture.open()
        val source = fixture.seed(db, "first").copy(imageCount = 5)
        db.normal.saveJob(source)
        val images = db.normal.images(source.id)
        val saved = db.normal.results(source.id)
        db.normal.saveResult(source.id, saved.single().copy(imageId = "unmatched", imageHash = "b".repeat(64)))
        val sourceSaved = db.normal.results(source.id)
        val history = db.normal.reviews(source.id)
        val request = source.copy(
            id = "replacement",
            settings = source.settings.copy(targetLanguage = "fr"),
            state = TranslationJobState.QUEUED,
            createdAt = source.updatedAt + 1,
            updatedAt = source.updatedAt + 1,
            replacesJobId = source.id,
        )

        val created = db.normal.replaceUnfinishedJob(source, request)

        assertNotNull("Atomic replacement must create a separate job", created)
        assertEquals(request.settings, created!!.settings)
        assertEquals(5, created.imageCount)
        assertEquals(1, created.completedImages)
        assertEquals(TranslationJobState.QUEUED, created.state)
        assertEquals(images, db.normal.images(created.id))
        assertEquals(saved, db.normal.results(created.id))
        assertTrue(db.normal.reviews(created.id).isEmpty())
        assertEquals(created.id, db.normal.replaceUnfinishedJob(source, request.copy(id = "duplicate"))!!.id)
        assertEquals(
            null,
            db.normal.replaceUnfinishedJob(source.copy(updatedAt = source.updatedAt + 1), request.copy(id = "stale")),
        )
        db = fixture.reopen()
        assertEquals(2, db.normal.jobs().size)
        assertEquals(source, db.normal.jobs().single { it.id == source.id })
        assertEquals(sourceSaved, db.normal.results(source.id))
        assertEquals(saved, db.normal.results(created.id))
        assertEquals(history, db.normal.reviews(source.id))
        assertEquals("3.00", db.normal.usage("first-usage")!!.reservedAmount)
    }

    private fun databaseTest(block: suspend (Fixture) -> Unit) = runBlocking {
        withContext(Dispatchers.IO) { Fixture().use { block(it) } }
    }

    private class Fixture : java.io.Closeable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val directory = File(context.cacheDir, "translation-deletion-test-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        private var active: Opened? = null
        fun open(): Opened = active ?: Opened(
            AndroidxSqliteDriver(
                driver = BundledSQLiteDriver(),
                databaseType = AndroidxSqliteDatabaseType.File(File(directory, "deletion.db").absolutePath),
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
        suspend fun seed(db: Opened, id: String): TranslationJob {
            val source = File(directory, "$id.source").apply { writeText("original source $id") }
            val job = TranslationJob(
                id, 7, if (id == "first") 9 else 10, "Series", "Chapter $id",
                TranslationSettings(), state = TranslationJobState.PAUSED, imageCount = 1, completedImages = 1,
            )
            val image = TranslationImage("page", 0, source.path, "image/png", 100, 80, "a".repeat(64), source.length())
            val region = TextRegion(
                "bubble",
                listOf(
                    TranslationPoint(1f, 1f),
                    TranslationPoint(99f, 1f),
                    TranslationPoint(99f, 79f),
                    TranslationPoint(1f, 79f),
                ),
                "raw source",
                "saved translation",
                correctedText = "corrected source",
                recognitionConfidence = 0.93f,
            )
            val result = TranslationPageResult(
                "page",
                image.contentHash,
                100,
                80,
                listOf(region),
                rawOcr = OcrPageResult("page", listOf(region.copy(correctedText = null))),
                revision = 42,
            )
            db.normal.saveJob(job)
            db.normal.saveImages(job.id, listOf(image))
            db.normal.saveResult(job.id, result)
            val committed = db.normal.results(job.id).single()
            db.database.translationQueries.upsertReview(
                "$id-review",
                job.id,
                "page",
                1,
                Json { encodeDefaults = true }.encodeToString(
                    QualityReviewCheckpoint(
                        "$id-review", job.id, "page", 41,
                        result.copy(regions = listOf(region.copy(translatedText = "before review")), revision = 41),
                        QualityReviewSettings(),
                        state = QualityReviewState.REPAIRED,
                        repairedRevision = committed.revision,
                        updatedAt = 1,
                    ),
                ),
            )
            db.normal.addEvent(TranslationEvent("$id-event", job.id, stage = "fixture", message = "retained log"))
            db.normal.saveOperation(
                TranslationOperation(
                    "$id-operation",
                    job.id,
                    TranslationStage.REQUEST,
                    state = TranslationOperationState.COMPLETED,
                ),
            )
            db.normal.saveUsage(
                TranslationUsageRecord(
                    "$id-usage",
                    job.id,
                    provider = "fixture",
                    model = "local",
                    time = 1,
                    reservedAmount = "3.00",
                    reservedCurrency = "SGD",
                    outcomeUncertain = true,
                ),
            )
            return job
        }
    }

    private class Opened(val driver: SqlDriver) {
        val database = AppBindings.providesDatabase(driver)
        val normal = TranslationRepositoryImpl(database)
        val deletion = TranslationDeletionRepositoryImpl(database)
    }
}
