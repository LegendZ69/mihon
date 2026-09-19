package mihon.feature.translation.accounting

import android.content.Context
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.app.di.AppBindings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.data.translation.TranslationDashboardRepositoryImpl
import tachiyomi.data.translation.TranslationRepositoryImpl
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.QualityReviewFinding
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationBillingSnapshot
import tachiyomi.domain.translation.model.TranslationBillingSource
import tachiyomi.domain.translation.model.TranslationDashboardQuery
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationLogQuery
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.model.TranslationUsage
import tachiyomi.domain.translation.model.TranslationUsageRecord
import tachiyomi.domain.translation.service.TranslationCoverageState
import java.io.File
import java.util.UUID

/** Only a disposable local database; no app jobs, credentials, network or paid work. */
@RunWith(AndroidJUnit4::class)
class TranslationDashboardPersistenceTest {
    @Test
    fun requestDrilldownUsesUpdatedTimeAndExactScopeAcrossPagesWithoutRequiringUsage() =
        databaseTest { fixture ->
            val opened = fixture.open()
            val first = TranslationOperation(
                "first", "job", TranslationStage.REQUEST,
                state = TranslationOperationState.COMPLETED, startedAt = 1, endedAt = 20, updatedAt = 20,
                provider = "GROQ", model = "model-a",
            )
            val operations = listOf(
                first, first.copy(id = "second", updatedAt = 21),
                first.copy(id = "other-job", jobId = "other", updatedAt = 26),
                first.copy(id = "before", updatedAt = 9), first.copy(id = "exclusive-end", updatedAt = 30),
                first.copy(id = "other-provider", provider = "OPENAI", updatedAt = 22),
                first.copy(id = "other-model", model = "model-b", updatedAt = 23),
                first.copy(id = "token-count", stage = TranslationStage.TOKEN_COUNT, updatedAt = 24),
                first.copy(id = "unattributed", provider = null, model = null, updatedAt = 25),
                first.copy(id = "saved", stage = TranslationStage.SAVE, updatedAt = 22),
                first.copy(
                    id = "failed-save",
                    stage = TranslationStage.SAVE,
                    state = TranslationOperationState.FAILED,
                    updatedAt = 23,
                ),
            )
            operations.forEach { opened.repository.saveOperation(it) }

            val page = opened.repository.operationPage(null, 2, 0, TranslationStage.REQUEST, 10, 30, "GROQ", "model-a")
            val next = opened.repository.operationPage(null, 2, 2, TranslationStage.REQUEST, 10, 30, "GROQ", "model-a")
            assertEquals(listOf("other-job", "second", "first"), (page + next).map { it.id })
            assertEquals(2, page.size)
            assertTrue(page.map { it.id }.intersect(next.map { it.id }.toSet()).isEmpty())
            assertTrue(
                opened.repository.operationPage(
                    null,
                    2,
                    4,
                    TranslationStage.REQUEST,
                    10,
                    30,
                    "GROQ",
                    "model-a",
                ).isEmpty(),
            )
            assertEquals(
                listOf("second", "first"),
                opened.repository.operationPage(
                    "job",
                    100,
                    0,
                    TranslationStage.REQUEST,
                    10,
                    30,
                    "GROQ",
                    "model-a",
                ).map { it.id },
            )
            val aggregate = opened.dashboard.aggregate(
                TranslationDashboardQuery(10, 30, provider = "GROQ", model = "model-a"),
            )
            assertEquals(aggregate.requests, (page.size + next.size).toLong())
            assertEquals(0L, aggregate.usage.attempts)
            val saved = opened.repository.operationPage(
                stage = TranslationStage.SAVE,
                since = 10,
                until = 30,
                provider = "GROQ",
                model = "model-a",
                state = TranslationOperationState.COMPLETED,
            )
            assertEquals(listOf("saved"), saved.map { it.id })
            assertEquals(aggregate.pageSaves, saved.size.toLong())
            assertEquals(
                listOf("token-count"),
                opened.repository.operationPage(
                    null,
                    stage = TranslationStage.TOKEN_COUNT,
                    since = 10,
                    until = 30,
                    provider = "GROQ",
                    model = "model-a",
                ).map { it.id },
            )
        }

    @Test
    fun completeHistoryAggregatesAcrossPagesWithoutDoubleCountingReplayAndSurvivesJobRemoval() =
        databaseTest { fixture ->
            var opened = fixture.open()
            val repository = opened.repository
            repository.saveJob(TranslationJob("job", 1, 2, "Series", "Chapter", TranslationSettings()))
            opened.database.transaction {
                repeat(1005) { index ->
                    repository.saveUsage(
                        TranslationUsageRecord(
                            "u-$index",
                            "job",
                            provider = "GROQ",
                            model = "model-a",
                            time =
                            10L + index,
                            usage = TranslationUsage(inputTokens = 100),
                            estimatedUsd = "0.01",
                        ),
                    )
                }
                repository.saveUsage(
                    TranslationUsageRecord(
                        "u-0",
                        "job",
                        provider = "GROQ",
                        model = "model-a",
                        time = 10,
                        usage = TranslationUsage(inputTokens = 200),
                        estimatedUsd = "0.02",
                    ),
                )
                repository.saveUsage(
                    TranslationUsageRecord(
                        "uncertain",
                        "job",
                        provider = "GROQ",
                        model = "model-a",
                        time = 11,
                        reservedCurrency = "SGD",
                        reservedAmount = "1.5",
                        outcomeUncertain = true,
                    ),
                )
                repeat(2) { index ->
                    repository.saveUsage(
                        TranslationUsageRecord(
                            "b-$index",
                            "job",
                            provider = "GROQ",
                            model = "model-b",
                            time = 12,
                            usage = TranslationUsage(inputTokens = 10),
                            estimatedUsd = "0",
                        ),
                    )
                }
                repeat(3) { index ->
                    repository.saveOperation(
                        TranslationOperation(
                            "save-$index",
                            "job",
                            TranslationStage.SAVE,
                            imageId = "image-${index % 2}",
                            state = TranslationOperationState.COMPLETED,
                            updatedAt = 20,
                            provider = "GROQ",
                            model = "model-a",
                        ),
                    )
                }
            }
            repository.removeJob("job")
            opened = fixture.reopen()
            val query = TranslationDashboardQuery(0, 2000, provider = "GROQ", jobId = "job", breakdownLimit = 1)
            val all = opened.dashboard.aggregate(query)
            assertEquals(1008L, all.usage.attempts)
            assertEquals("100620", all.usage.input.value)
            assertEquals(1L, all.usage.input.unavailable)
            assertEquals("10.06", all.usage.estimatedUsd.value)
            assertEquals("1.5", all.usage.reservations.getValue("SGD").value)
            assertEquals(3L, all.pageSaves)
            assertEquals(2L, all.uniqueSavedPages)
            assertTrue(all.moreBreakdowns)
            assertEquals("model-a", all.breakdowns.single().key.model)
            assertEquals(1006L, all.breakdowns.single().usage.attempts)
            assertEquals(
                "model-b",
                opened.dashboard.aggregate(query.copy(breakdownOffset = 1)).breakdowns.single().key.model,
            )
            val filtered = opened.dashboard.aggregate(query.copy(model = "model-a"))
            assertEquals(1006L, filtered.usage.attempts)
            val first = opened.repository.usagePage(0, 2000, "GROQ", "model-a", 256, 0, "job")
            val second = opened.repository.usagePage(0, 2000, "GROQ", "model-a", 256, 256, "job")
            assertEquals(256, first.size)
            assertEquals(256, second.size)
            assertTrue(first.map { it.id }.intersect(second.map { it.id }.toSet()).isEmpty())
            assertTrue(first.all { it.provider == "GROQ" && it.model == "model-a" && it.jobId == "job" })
        }

    @Test
    fun operationProviderSnapshotsRemainPinnedAndBillingStorageCountsOnlyTheSelectedConnection() =
        databaseTest { fixture ->
            val opened = fixture.open()
            val repository = opened.repository
            val job =
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
                            model = "original-model",
                        ),
                    ),
                )
            repository.saveJob(job)
            val operation = TranslationOperation(
                "operation",
                job.id,
                TranslationStage.SAVE,
                imageId = "page",
                state = TranslationOperationState.ACTIVE,
                updatedAt = 10,
            )
            repository.saveOperation(operation)
            repository.saveJob(
                job.copy(settings = job.settings.copy(provider = job.settings.provider.copy(model = "later-model"))),
            )
            repository.saveOperation(operation.copy(state = TranslationOperationState.COMPLETED, updatedAt = 20))
            val saved = repository.observeOperations(job.id).first().single()
            assertEquals("GROQ", saved.provider)
            assertEquals("original-model", saved.model)
            repository.addEvent(
                TranslationEvent(
                    "operation-log",
                    job.id,
                    operationId = operation.id,
                    time = 20,
                    stage = "SAVE",
                    message = "Saved original provider snapshot",
                ),
            )
            val logs =
                TranslationLogQuery(jobId = job.id, since = 0, until = 30, provider = "GROQ", model = "original-model")
            assertEquals(1, repository.eventPage(logs).size)
            assertTrue(repository.eventPage(logs.copy(model = "later-model")).isEmpty())
            assertTrue(repository.eventPage(logs.copy(provider = "OPENAI")).isEmpty())
            val amount = TranslationBillingSnapshot(
                "first", "selected", TranslationBillingSource.OPENAI_ORGANIZATION,
                "scope", 0, 10, 20, "USD", "1.25", sourceUrl = "https://example.invalid",
            )
            repository.saveBilling(amount)
            repository.saveBilling(amount.copy(id = "other", connectionId = "other"))
            val stats = repository.billingStats("selected")
            assertEquals(1L, stats.records)
            assertTrue(stats.payloadBytes!! > 0)
            repository.deleteBilling("selected")
            assertEquals(0L, repository.billingStats("selected").records)
            assertEquals(1L, repository.billingStats("other").records)
            val unrelated = TranslationOperation(
                "unrelated-stale",
                "unrelated",
                TranslationStage.SAVE,
                state = TranslationOperationState.ACTIVE,
                updatedAt = 10,
                processSession = "prior-process",
            )
            val unrelatedPayload = Json { encodeDefaults = true }.encodeToString(unrelated)
            opened.database.translationQueries.upsertOperation(
                unrelated.id, unrelated.jobId, null, null, null,
                unrelated.stage.name, unrelated.state.name, unrelated.updatedAt, unrelatedPayload,
            )
            val reopened = fixture.reopen()
            assertEquals(listOf(operation.id), reopened.repository.operationPage(job.id, 1).map { it.id })
            assertTrue(reopened.repository.operationPage(job.id, 1, 1).isEmpty())
            assertEquals(
                listOf(unrelatedPayload),
                reopened.database.translationQueries.operations("unrelated", 100, 0).awaitAsList(),
            )
        }

    @Test
    fun compactReviewSummariesKeepLatestPerPageWithStableTiesWithoutLoadingTheBaseline() =
        databaseTest { fixture ->
            val opened = fixture.open()
            opened.repository.saveJob(TranslationJob("first", 1, 2, "Fixture", "First", TranslationSettings()))
            opened.repository.saveJob(TranslationJob("other", 1, 3, "Fixture", "Other", TranslationSettings()))
            val json = Json { encodeDefaults = true }
            suspend fun seed(id: String, job: String, image: String, state: QualityReviewState, updated: Long) {
                val review = QualityReviewCheckpoint(
                    id,
                    job,
                    image,
                    1,
                    TranslationPageResult(
                        image,
                        "hash",
                        10,
                        10,
                        emptyList(),
                        rawOcr = OcrPageResult(
                            image,
                            emptyList(),
                            rawJson = "{\"sentinel\":\"${"x".repeat(200_000)}\"}",
                        ),
                    ),
                    QualityReviewSettings(),
                    state = state,
                    message = "Review $id",
                    findings = listOf(QualityReviewFinding("layout", "Searchable finding $id")),
                    updatedAt = updated,
                )
                // A status projection has no reason to load this baseline's original OCR document.
                opened.database.translationQueries.upsertReview(id, job, image, updated, json.encodeToString(review))
            }
            seed("older", "first", "page", QualityReviewState.PASSED, 100)
            seed("b-tie", "first", "page", QualityReviewState.NEEDS_REVIEW, 200)
            seed("a-tie", "first", "page", QualityReviewState.INCOMPLETE, 200)
            seed("next-page", "first", "page-2", QualityReviewState.PAUSED, 150)
            seed("other-job", "other", "page", QualityReviewState.REPAIRED, 200)
            val all = opened.repository.reviewSummaries()
            assertEquals(3, all.size)
            assertTrue(all.toString().length < 4096)
            assertTrue(all.none { it.toString().contains("sentinel") })
            val latest = all.single { it.jobId == "first" && it.imageId == "page" }
            assertEquals("Review a-tie", latest.message)
            assertEquals(listOf("Searchable finding a-tie"), latest.findings.map { it.description })
            assertEquals("a-tie", latest.id)
            assertEquals(
                QualityReviewState.INCOMPLETE,
                all.single {
                    it.jobId == "first" && it.imageId == "page"
                }.state,
            )
            assertEquals(2, opened.repository.observeReviewSummaries("first").first().size)
            seed("older", "first", "page", QualityReviewState.PASSED, 300)
            assertEquals("older", opened.repository.reviewSummaries("first").single { it.imageId == "page" }.id)
            assertEquals(QualityReviewState.REPAIRED, opened.repository.reviewSummaries("other").single().state)
        }

    @Test
    fun compactChapterCoveragePreservesDeclaredTotalsAndCountsSavedBlankAndIgnoredPages() =
        databaseTest { fixture ->
            val opened = fixture.open()
            val repository = opened.repository
            val rawMarker = "RAW_OCR_MUST_NOT_ENTER_COVERAGE_PROJECTION"
            val raw = "{\"sentinel\":\"$rawMarker${"x".repeat(250_000)}\"}"
            fun image(id: String, index: Int) = TranslationImage(
                id,
                index,
                "/fixture/$id.png",
                "image/png",
                100,
                200,
                "hash-$id",
                123,
            )
            fun result(image: TranslationImage, sfx: Boolean = false) = TranslationPageResult(
                image.id,
                image.contentHash,
                image.width,
                image.height,
                if (sfx) {
                    listOf(
                        TextRegion(
                            "sfx",
                            emptyList(),
                            "쾅",
                            type = "sound_effect",
                            included = false,
                            ignoredReason = "Sound effects are intentionally untranslated",
                        ),
                    )
                } else {
                    emptyList()
                },
                rawOcr = OcrPageResult(image.id, emptyList(), rawJson = raw),
            )
            val partial =
                TranslationJob(
                    "partial",
                    1,
                    51,
                    "Fixture",
                    "Partial original cache",
                    TranslationSettings(),
                    imageCount = 5,
                )
            val images = listOf(image("blank", 0), image("sfx", 1))
            repository.saveJob(partial)
            repository.saveImages(partial.id, images)
            repository.saveResult(partial.id, result(images[0]))
            repository.saveResult(partial.id, result(images[1], sfx = true))
            val coverage = repository.chapterCoverage(51)
            assertEquals(TranslationCoverageState.PARTIAL, coverage.state)
            assertEquals(2, coverage.saved)
            assertEquals(5, coverage.total)
            val projection = opened.database.translationQueries.chapterCoverageIdentities(51).awaitAsList()
            assertEquals(5, projection.size) // Two originals, two saved identities and the declared total.
            assertFalse(projection.toString().contains(rawMarker))
            assertTrue("Only identity fields reach the caller", projection.sumOf { it.toString().length } < 4_000)

            val complete = partial.copy(id = "complete", chapterId = 52, imageCount = 2)
            repository.saveJob(complete)
            repository.saveImages(complete.id, images)
            repository.saveResult(complete.id, result(images[0]))
            repository.saveResult(complete.id, result(images[1], sfx = true))
            val observed = repository.observeChapterCoverage(52).first()
            assertEquals(TranslationCoverageState.COMPLETED, observed.state)
            assertEquals(2, observed.saved)
            assertEquals(2, observed.total)

            val unknown = partial.copy(id = "unknown", chapterId = 53, imageCount = 0)
            repository.saveJob(unknown)
            repository.saveResult(unknown.id, result(images[0]))
            val missingOriginalList = repository.chapterCoverage(53)
            assertEquals(TranslationCoverageState.UNKNOWN, missingOriginalList.state)
            assertEquals(1, missingOriginalList.saved)
            assertEquals(null, missingOriginalList.total)
        }

    private fun databaseTest(block: suspend (Fixture) -> Unit) = runBlocking {
        withContext(Dispatchers.IO) { Fixture().use { block(it) } }
    }

    private class Fixture : java.io.Closeable {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val directory = File(context.cacheDir, "translation-dashboard-test-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        private var active: Opened? = null
        fun open(): Opened = active ?: Opened(
            AndroidxSqliteDriver(
                driver = BundledSQLiteDriver(),
                databaseType = AndroidxSqliteDatabaseType.File(File(directory, "dashboard.db").absolutePath),
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
        val repository = TranslationRepositoryImpl(database)
        val dashboard = TranslationDashboardRepositoryImpl(database)
    }
}
