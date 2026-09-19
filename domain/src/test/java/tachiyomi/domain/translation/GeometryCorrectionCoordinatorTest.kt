package tachiyomi.domain.translation

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.GeometryCorrectionAttempt
import tachiyomi.domain.translation.model.GeometryCorrectionAttemptState
import tachiyomi.domain.translation.model.GeometryCorrectionCheckpoint
import tachiyomi.domain.translation.model.GeometryCorrectionState
import tachiyomi.domain.translation.model.GeometryIssue
import tachiyomi.domain.translation.model.GeometryIssueCode
import tachiyomi.domain.translation.model.RegionGeometryIssue
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationInputTransform
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.GeometryCorrectionCoordinator
import tachiyomi.domain.translation.service.TranslationJobSnapshot
import tachiyomi.domain.translation.service.TranslationRepository

class GeometryCorrectionCoordinatorTest {
    private val image = TranslationImage("page", 0, "/owned/page.png", "image/png", 100, 100, "hash", 100)
    private val result = TranslationPageResult("page", "hash", 100, 100, emptyList())
    private val initial = GeometryCorrectionCheckpoint(
        id = "correction",
        jobId = "job",
        policyId = "explicit-retry",
        image = image,
        transform = TranslationInputTransform(image),
        settings = TranslationSettings(),
        ocr = emptyList(),
        context = "chapter context",
        candidateJson = "{\"regions\":[]}",
        issues = listOf(RegionGeometryIssue("passage", GeometryIssue(GeometryIssueCode.CONCAVE, reason = "concave"))),
    )
    private val repository = mockk<TranslationRepository>(relaxed = true)
    private var stored: GeometryCorrectionCheckpoint? = null
    private var eligible = true

    private fun prepareRepository() {
        coEvery { repository.geometryCorrection(any()) } answers { stored }
        coEvery { repository.createGeometryCorrection(any()) } coAnswers {
            if (!eligible) null else stored ?: firstArg<GeometryCorrectionCheckpoint>().also { stored = it }
        }
        coEvery { repository.compareAndSetGeometryCorrection(any(), any()) } coAnswers {
            val before = firstArg<GeometryCorrectionCheckpoint>()
            val after = secondArg<GeometryCorrectionCheckpoint>()
            if (eligible && before == stored && after.version == before.version + 1) {
                stored = after
                true
            } else {
                if (!eligible && before == stored) {
                    stored = before.copy(state = GeometryCorrectionState.SUPERSEDED, version = before.version + 1)
                }
                false
            }
        }
    }

    @Test
    fun `explicit Resume reopens a paused correction once without resetting its attempt history`() = runTest {
        prepareRepository()
        var requests = 0
        val coordinator = GeometryCorrectionCoordinator(repository) { _, _ ->
            requests++
            if (requests == 1) throw TranslationException(TranslationFailureKind.AUTHENTICATION, "Fix credentials")
            result
        }
        val paused = coordinator.run(initial)!!
        paused.state shouldBe GeometryCorrectionState.PAUSED
        coordinator.resumePaused(paused) shouldBe true
        coordinator.resumePaused(paused) shouldBe false
        stored!!.attempts shouldBe paused.attempts
        stored!!.version shouldBe paused.version + 1
        coordinator.run(initial)!!.state shouldBe GeometryCorrectionState.COMPLETED
        stored!!.attempts.size shouldBe 2
        requests shouldBe 2
    }

    @Test
    fun `explicit Resume cannot replenish an exhausted authentication attempt limit`() = runTest {
        prepareRepository()
        val bounded = initial.copy(
            settings = initial.settings.copy(
                provider = initial.settings.provider.copy(
                    totalAttempts = 1,
                ),
            ),
        )
        var requests = 0
        val coordinator = GeometryCorrectionCoordinator(repository) { _, _ ->
            requests++
            throw TranslationException(TranslationFailureKind.AUTHENTICATION, "Fix credentials")
        }
        val paused = coordinator.run(bounded)!!
        coordinator.resumePaused(paused) shouldBe false
        coordinator.run(bounded)!!.state shouldBe GeometryCorrectionState.PAUSED
        stored shouldBe paused
        requests shouldBe 1
    }

    @Test
    fun `concurrent coordinators share one pinned input and one provider dispatch`() = runTest {
        prepareRepository()
        val response = CompletableDeferred<TranslationPageResult>()
        var requests = 0
        val first = async {
            GeometryCorrectionCoordinator(repository) { _, _ ->
                requests++
                response.await()
            }.run(initial)
        }
        runCurrent()
        val duplicate = async {
            GeometryCorrectionCoordinator(repository) { _, _ ->
                requests++
                result
            }.run(initial.copy(candidateJson = "another gesture"))
        }
        runCurrent()
        requests shouldBe 1
        response.complete(result)
        first.await()!!.result shouldBe result
        duplicate.await()!!.result shouldBe result
        stored!!.candidateJson shouldBe initial.candidateJson
        requests shouldBe 1
    }

    @Test
    fun `cancellation consumes its reservation and restart may use only the remaining attempt`() = runTest {
        prepareRepository()
        val worker = launch {
            GeometryCorrectionCoordinator(repository) { _, _ -> awaitCancellation() }.run(initial)
        }
        runCurrent()
        stored!!.attempts.size shouldBe 1
        worker.cancelAndJoin()
        stored!!.state shouldBe GeometryCorrectionState.INTERRUPTED
        stored!!.attempts.single().state shouldBe GeometryCorrectionAttemptState.INTERRUPTED

        var requests = 0
        GeometryCorrectionCoordinator(repository) { pinned, attempt ->
            pinned.candidateJson shouldBe initial.candidateJson
            attempt.number shouldBe 2
            requests++
            result
        }.run(initial)!!.state shouldBe GeometryCorrectionState.COMPLETED
        stored!!.attempts.size shouldBe 2
        requests shouldBe 1
    }

    @Test
    fun `process loss preserves uncertain requests and an exhausted pass cannot dispatch`() = runTest {
        prepareRepository()
        stored = initial.copy(
            state = GeometryCorrectionState.RUNNING,
            version = 3,
            attempts = listOf(
                GeometryCorrectionAttempt(1, 1, GeometryCorrectionAttemptState.FAILED, 2),
                GeometryCorrectionAttempt(2, 3),
            ),
        )
        var requests = 0
        val coordinator = GeometryCorrectionCoordinator(repository) { _, _ ->
            requests++
            result
        }

        coordinator.run(initial)!!.state shouldBe GeometryCorrectionState.FAILED
        stored!!.attempts.size shouldBe 2
        stored!!.attempts.last().state shouldBe GeometryCorrectionAttemptState.INTERRUPTED
        coordinator.run(initial)!!.state shouldBe GeometryCorrectionState.FAILED
        requests shouldBe 0
    }

    @Test
    fun `transient retry is bounded by the configured transport limit`() = runTest {
        listOf(1, 2).forEach { limit ->
            stored = null
            prepareRepository()
            var requests = 0
            val configured = initial.copy(
                settings = initial.settings.copy(
                    provider = initial.settings.provider.copy(
                        totalAttempts = limit,
                        initialRetryMillis = 1,
                        maxRetryMillis = 1,
                    ),
                ),
            )
            val outcome = GeometryCorrectionCoordinator(repository) { _, _ ->
                requests++
                if (requests == 1) throw TranslationException(TranslationFailureKind.TRANSIENT, "temporary")
                result
            }.run(configured)!!

            outcome.state shouldBe if (limit == 1) GeometryCorrectionState.FAILED else GeometryCorrectionState.COMPLETED
            outcome.attempts.size shouldBe limit
            requests shouldBe limit
        }
    }

    @Test
    fun `authentication pauses while malformed geometry ends one pass without another content request`() = runTest {
        listOf(
            TranslationFailureKind.AUTHENTICATION to GeometryCorrectionState.PAUSED,
            TranslationFailureKind.CONFIGURATION to GeometryCorrectionState.PAUSED,
            TranslationFailureKind.CONTENT to GeometryCorrectionState.FAILED,
            TranslationFailureKind.GEOMETRY to GeometryCorrectionState.FAILED,
            TranslationFailureKind.LIMIT to GeometryCorrectionState.FAILED,
        ).forEach { (kind, state) ->
            stored = null
            prepareRepository()
            var requests = 0
            val coordinator = GeometryCorrectionCoordinator(repository) { _, _ ->
                requests++
                throw TranslationException(kind, "bounded failure")
            }
            coordinator.run(initial)!!.state shouldBe state
            coordinator.run(initial)!!.state shouldBe state
            stored!!.attempts.size shouldBe 1
            requests shouldBe 1
        }
    }

    @Test
    fun `manual edit or a new explicit retry discards an in flight candidate`() = runTest {
        prepareRepository()
        val outcome = GeometryCorrectionCoordinator(repository) { _, _ ->
            eligible = false
            result
        }.run(initial)!!
        outcome.state shouldBe GeometryCorrectionState.SUPERSEDED
        outcome.result shouldBe null
        stored!!.attempts.size shouldBe 1
    }

    @Test
    fun `checkpoint and legacy operation payloads remain compatible`() {
        val json = Json { encodeDefaults = true }
        json.decodeFromString<GeometryCorrectionCheckpoint>(json.encodeToString(initial)) shouldBe initial
        val legacy = """{"id":"old","jobId":"job","stage":"VALIDATION"}"""
        json.decodeFromString<TranslationOperation>(legacy).geometryCorrection shouldBe null
    }

    @Test
    fun `correction reserves the attempt before dispatch and duplicate scheduling reuses the result`() = runTest {
        prepareRepository()
        var requests = 0
        val coordinator = GeometryCorrectionCoordinator(repository) { pinned, attempt ->
            stored shouldBe pinned
            stored!!.attempts.single() shouldBe attempt
            stored!!.state shouldBe GeometryCorrectionState.RUNNING
            requests++
            result
        }

        coordinator.run(initial)!!.state shouldBe GeometryCorrectionState.COMPLETED
        coordinator.run(initial.copy(candidateJson = "different input"))!!.result shouldBe result
        stored!!.result shouldBe result
        requests shouldBe 1
    }

    @Test
    fun `legacy queued snapshots do not opt into automatic geometry correction`() {
        val json = Json { encodeDefaults = true }
        val job = TranslationJob("job", 1, 1, "series", "chapter", TranslationSettings())
        val encoded = json.parseToJsonElement(json.encodeToString(job)).jsonObject
        val oldSettings = JsonObject(encoded.getValue("settings").jsonObject - "geometryRecovery")
        val old = JsonObject(encoded + ("settings" to oldSettings))

        TranslationJobSnapshot.decode(json, old.toString()).settings.geometryRecovery.enabled shouldBe false
        TranslationJobSnapshot.decode(json, encoded.toString()).settings.geometryRecovery.enabled shouldBe true
        json.decodeFromString<TranslationSettings>(oldSettings.toString()).geometryRecovery.enabled shouldBe true
    }
}
