package mihon.feature.translation.provider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.cash.sqldelight.db.SqlDriver
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteConfiguration
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDatabaseType
import com.eygraber.sqldelight.androidx.driver.AndroidxSqliteDriver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import mihon.app.di.AppBindings
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.data.Database
import tachiyomi.data.translation.TranslationRepositoryImpl
import tachiyomi.domain.translation.model.GeometryCorrectionAttemptState
import tachiyomi.domain.translation.model.GeometryCorrectionCheckpoint
import tachiyomi.domain.translation.model.GeometryCorrectionState
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.OcrSettings
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Real PNG preparation, private tile checkpoints and SQLite; every HTTP response is host-local and synthetic. */
@RunWith(AndroidJUnit4::class)
class GeometryRecoveryPipelineTest {
    @Test
    fun preparedImageIdentityMatchesItsActualPngBytesBeforeAnyProviderRequest() = fixtureTest { fixture ->
        fixture.assertPreparedByteIdentities()
        assertTrue(fixture.generated.isEmpty())
        assertTrue(fixture.counted.isEmpty())
    }

    @Test
    fun legacyPreparedCheckpointsKeepRawOcrAndRevisionsAndNeverResubmitAcceptedTiles() =
        fixtureTest(Behavior.LEGACY) { fixture ->
            fixture.seedLegacyAcceptedTiles()
            val response = fixture.gateway.translate(fixture.request)
            assertNull(response.deferredFailure)
            fixture.assertComplete(response.pages)
            assertEquals(2, fixture.generated.size)
            assertEquals(1, fixture.imageCount(fixture.generated.first()))
            fixture.assertLegacyTilesPreserved()
            fixture.assertAcceptedFilesUnchanged()
            fixture.reopen()
            fixture.assertComplete(fixture.gateway.translate(fixture.request).pages)
            fixture.assertLegacyTilesPreserved()
            assertEquals(2, fixture.generated.size)
        }

    @Test
    fun legacyPreparedCheckpointNeverCrossesSettingsOrAcceptsDifferentTileDimensions() = fixtureTest { fixture ->
        fixture.seedLegacyAcceptedTiles()
        fixture.assertLegacyMismatchRejected()
        assertTrue(fixture.generated.isEmpty())
    }

    @Test
    fun laterPreparedAuthenticationFailureTakesPrecedenceOverEarlierMissingContent() =
        fixtureTest(Behavior.MASKED_AUTHENTICATION) { fixture ->
            val error = runCatching { fixture.gateway.translate(fixture.request) }.exceptionOrNull()
            assertTrue("A terminal provider failure must reach the queue: $error", error is TranslationException)
            assertEquals(TranslationFailureKind.AUTHENTICATION, (error as TranslationException).kind)
            assertEquals(GeometryCorrectionState.PAUSED, fixture.checkpoint().state)
            assertEquals(4, fixture.generated.size)
            fixture.assertAcceptedFilesUnchanged()
        }

    @Test
    fun preparedHybridCorrectionRetainsAcceptedTilesAndMapsOnlyTheRejectedTileToOriginalCoordinates() =
        fixtureTest { fixture ->
            val response = fixture.gateway.translate(fixture.request)
            assertEquals(
                "Prepared geometry correction must finish both original pages: " +
                    "${response.deferredFailure?.message}; ${fixture.checkpoint().message}",
                2,
                response.pages.size,
            )
            assertNull(response.deferredFailure)
            fixture.assertComplete(response.pages)
            val completed = fixture.checkpoint()
            assertEquals(GeometryCorrectionState.COMPLETED, completed.state)
            assertEquals(1, completed.attempts.size)
            assertEquals(256, completed.transform.top)
            assertEquals(512, completed.transform.original.height)
            assertEquals("bad", completed.transform.original.id)
            assertEquals(
                fixture.request.ocr.last().regions.last().sourceText,
                completed.ocr.single().regions.single().sourceText,
            )
            assertEquals(.83f, completed.ocr.single().regions.single().detectionConfidence)
            assertEquals(2, fixture.generated.size)
            assertEquals(3, fixture.imageCount(fixture.generated.first()))
            assertEquals(1, fixture.imageCount(fixture.generated.last()))
            assertEquals(fixture.generated.last()["contents"], fixture.counted.last()["contents"])
            fixture.assertAcceptedFilesUnchanged()

            fixture.reopen()
            val reused = fixture.gateway.translate(fixture.request)
            fixture.assertComplete(reused.pages)
            assertEquals("Recreated Gateway must not submit a completed tile again", 2, fixture.generated.size)
            fixture.assertAcceptedFilesUnchanged()
        }

    @Test
    fun preparedAuthenticationPauseSurvivesDatabaseReopenAndResumeNeverResubmitsTheCompletedPage() =
        fixtureTest(Behavior.AUTHENTICATION) { fixture ->
            val partial = fixture.gateway.translate(fixture.request)
            assertEquals(listOf("good"), partial.pages.map { it.imageId })
            assertEquals(TranslationFailureKind.AUTHENTICATION, partial.deferredFailure?.kind)
            fixture.repository.saveResult(fixture.request.jobId, partial.pages.single())
            val saved = fixture.repository.results(fixture.request.jobId).single()
            assertEquals(GeometryCorrectionState.PAUSED, fixture.checkpoint().state)
            fixture.reopen()
            assertEquals(saved, fixture.repository.results(fixture.request.jobId).single())
            val paused = runCatching { fixture.gateway.translate(fixture.request) }.exceptionOrNull()
            assertTrue(paused is TranslationException)
            assertEquals(TranslationFailureKind.AUTHENTICATION, (paused as TranslationException).kind)
            assertEquals(saved, fixture.repository.results(fixture.request.jobId).single())
            assertEquals("Paused correction must not dispatch before explicit Resume", 2, fixture.generated.size)

            fixture.gateway.resumeGeometryCorrections(setOf(fixture.request.jobId))
            val completed = fixture.gateway.translate(fixture.request)
            fixture.assertComplete(completed.pages)
            assertEquals(saved, fixture.repository.results(fixture.request.jobId).single())
            assertEquals(2, fixture.checkpoint().attempts.size)
            assertEquals(3, fixture.generated.size)
            assertEquals(
                "Resume must use identical pinned correction input",
                fixture.generated[1],
                fixture.generated[2],
            )
            fixture.assertAcceptedFilesUnchanged()
        }

    @Test
    fun cancellationDuringPreparedCorrectionKeepsItsReservationAndAcceptedTilesAcrossRecreation() =
        fixtureTest(Behavior.DELAY) { fixture ->
            coroutineScope {
                val running = async { fixture.gateway.translate(fixture.request) }
                try {
                    assertTrue(
                        "Correction request was not reached",
                        fixture.correctionStarted.await(10, TimeUnit.SECONDS),
                    )
                    val reserved = fixture.checkpoint()
                    assertEquals(GeometryCorrectionState.RUNNING, reserved.state)
                    assertEquals(1, reserved.attempts.size)
                    fixture.assertAcceptedFilesUnchanged()
                    running.cancel()
                } finally {
                    fixture.releaseCorrection.countDown()
                    running.cancelAndJoin()
                }
            }
            assertEquals(GeometryCorrectionState.INTERRUPTED, fixture.checkpoint().state)
            assertEquals(GeometryCorrectionAttemptState.INTERRUPTED, fixture.checkpoint().attempts.single().state)
            fixture.reopen()
            val completed = fixture.gateway.translate(fixture.request)
            fixture.assertComplete(completed.pages)
            assertEquals(listOf(1, 2), fixture.checkpoint().attempts.map { it.number })
            assertEquals(3, fixture.generated.size)
            assertEquals(fixture.generated[1], fixture.generated[2])
            fixture.assertAcceptedFilesUnchanged()
        }

    private enum class Behavior { SUCCESS, AUTHENTICATION, DELAY, LEGACY, MASKED_AUTHENTICATION }

    private fun fixtureTest(behavior: Behavior = Behavior.SUCCESS, block: suspend (Fixture) -> Unit) = runBlocking {
        withContext(Dispatchers.IO) {
            val fixture = Fixture(behavior)
            try {
                withTimeout(45000) {
                    fixture.initialize()
                    block(fixture)
                }
            } finally {
                fixture.close()
            }
        }
    }

    private class Fixture(private val behavior: Behavior) {
        private val context = ApplicationProvider.getApplicationContext<Context>()
        private val directory = File(context.cacheDir, "geometry-pipeline-${UUID.randomUUID()}").apply {
            check(mkdirs())
        }
        private val databaseFile = File(directory, "fixture.db")
        private var driver: SqlDriver? = null
        lateinit var repository: TranslationRepositoryImpl
            private set
        lateinit var gateway: TranslationProviderGateway
            private set
        private val preparationDirectory = File(directory, "prepared")
        private var preparation = ImagePreparation(preparationDirectory)
        private lateinit var prepared: PreparedTranslationRequest
        val generated = Collections.synchronizedList(mutableListOf<JsonObject>())
        val counted = Collections.synchronizedList(mutableListOf<JsonObject>())
        private val corrections = AtomicInteger()
        val correctionStarted = CountDownLatch(1)
        val releaseCorrection = CountDownLatch(1)
        private val acceptedBytes = linkedMapOf<File, ByteArray>()
        private val legacy = linkedMapOf<String, Pair<File, TranslationPageResult>>()
        private val legacyBytes = linkedMapOf<File, ByteArray>()
        private val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = TranslationWireFormat.json.parseToJsonElement(
                Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8(),
            ).jsonObject
            val countOnly = chain.request().url.encodedPath.endsWith(":countTokens")
            var status = 200
            val response = if (countOnly) {
                counted += body
                """{"totalTokens":200}"""
            } else {
                generated += body
                val correction = generated.size > if (behavior == Behavior.MASKED_AUTHENTICATION) 3 else 1
                val output = if (!correction) {
                    when (behavior) {
                        Behavior.LEGACY -> pages(page(BOTTOM, crossing = true))
                        Behavior.MASKED_AUTHENTICATION -> when (generated.size) {
                            1 -> pages()
                            2 -> pages(page(TOP))
                            else -> pages(page(BOTTOM, crossing = true))
                        }
                        else -> pages(page(GOOD), page(TOP), page(BOTTOM, crossing = true))
                    }
                } else {
                    runBlocking {
                        assertAcceptedTilesSavedBeforeCorrection()
                        assertEquals(GeometryCorrectionState.RUNNING, checkpoint().state)
                    }
                    val attempt = corrections.incrementAndGet()
                    correctionStarted.countDown()
                    if (behavior == Behavior.DELAY && attempt == 1) {
                        check(releaseCorrection.await(15, TimeUnit.SECONDS)) { "Fixture correction was not released" }
                    }
                    if (behavior in setOf(Behavior.AUTHENTICATION, Behavior.MASKED_AUTHENTICATION) && attempt == 1) {
                        status = 401
                    }
                    pages(page(BOTTOM))
                }
                if (status == 200) vertex(output) else """{"error":{"message":"Synthetic authentication failure"}}"""
            }
            Response.Builder().request(
                chain.request(),
            ).protocol(Protocol.HTTP_1_1).code(status).message("Local fixture")
                .header("x-request-id", "fixture-${generated.size}").body(response.toResponseBody()).build()
        }.build()
        private val images = listOf(image("good", 0, 256), image("bad", 1, 512))
        private val settings = TranslationSettings(
            provider = ProviderSettings(
                kind = TranslationProviderKind.VERTEX_EXPRESS,
                credentialId = "synthetic-fixture",
                totalAttempts = 2,
                initialRetryMillis = 1,
                maxRetryMillis = 2,
                imageTileLongEdge = 256,
                imageTileMaxPixels = 65536,
                imageTileOverlap = 0,
            ),
            mode = if (behavior == Behavior.MASKED_AUTHENTICATION) TranslationMode.VERTEX else TranslationMode.CUSTOM,
            customBatchSize = 3,
            ocr = OcrSettings(pipeline = OcrPipeline.PADDLE_AI),
        )
        val request = TranslationRequest(
            "fixture-job",
            "fixture-batch",
            settings,
            images,
            ocr = listOf(
                OcrPageResult("good", listOf(region("r0", 30f, "待って")), rawJson = "unchanged-good-raw-ocr"),
                OcrPageResult(
                    "bad",
                    listOf(region("r0", 30f, "上の文章"), region("r1", 290f, "下の文章")),
                    rawJson = "unchanged-bad-raw-ocr",
                ),
            ),
            context = "Earlier speaker: Hana. Preserve this context.",
            geometryRecoveryId = "fixture-recovery-epoch",
        )

        suspend fun initialize() {
            open()
            repository.saveJob(
                TranslationJob(
                    request.jobId,
                    1,
                    10,
                    "Disposable geometry fixture",
                    "Prepared pipeline",
                    settings,
                    geometryRecoveryId = request.geometryRecoveryId,
                ),
            )
            repository.saveImages(request.jobId, images)
            prepared = preparation.prepare(request, OfficialProviderCapabilities.forSettings(settings.provider))
            assertEquals(listOf(GOOD, TOP, BOTTOM), prepared.tiles.map { it.image.id })
        }

        fun assertPreparedByteIdentities() {
            prepared.tiles.forEach { tile ->
                val actual = MessageDigest.getInstance("SHA-256")
                    .digest(File(tile.image.filePath).readBytes()).joinToString("") { "%02x".format(it) }
                assertEquals(
                    "Prepared input ${tile.image.id} must identify its actual PNG bytes",
                    actual,
                    tile.image.contentHash,
                )
            }
        }

        fun seedLegacyAcceptedTiles() {
            val json = Json { encodeDefaults = true }
            val policy = JsonObject(json.encodeToJsonElement(settings).jsonObject - "geometryRecovery").toString()
            for (id in listOf(GOOD, TOP)) {
                val image = prepared.tiles.single { it.image.id == id }.image
                val recipe = File(image.filePath).nameWithoutExtension
                val ocr = prepared.wire.ocr.single { it.imageId == id }
                val result = TranslationPageResult(
                    id,
                    recipe,
                    image.width,
                    image.height,
                    ocr.regions.map {
                        it.copy(
                            translatedText = if (id ==
                                GOOD
                            ) {
                                "Wait"
                            } else {
                                "Upper passage"
                            },
                            correctedText = "Retained correction",
                        )
                    },
                    rawOcr = ocr,
                    detectedLanguage = "ja",
                    revision = 123456L,
                )
                // v10's on-disk fixture format: recipe hash, pre-recovery settings, same per-image OCR snapshot.
                val key =
                    hash((policy + request.context + json.encodeToString(listOf(ocr)) + recipe + id).toByteArray())
                val file = File(preparationDirectory, "results-${hash(request.jobId.toByteArray())}/$key.json")
                check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                file.writeText(json.encodeToString(result))
                legacy[id] = file to result
                legacyBytes[file] = file.readBytes()
            }
        }

        fun assertLegacyTilesPreserved() {
            for ((id, saved) in legacy) {
                val image = prepared.tiles.single { it.image.id == id }.image
                assertEquals(saved.second.copy(imageHash = image.contentHash), preparation.cached(prepared.wire, image))
                assertTrue(legacyBytes.getValue(saved.first).contentEquals(saved.first.readBytes()))
            }
        }

        fun assertLegacyMismatchRejected() {
            val image = prepared.tiles.first().image
            assertNull(
                preparation.cached(prepared.wire.copy(settings = settings.copy(instructions = "Changed names")), image),
            )
            assertNull(preparation.cached(prepared.wire, image.copy(height = image.height - 1)))
            assertNull(preparation.cached(prepared.wire, image.copy(id = "another-input")))
            assertNull(preparation.cached(prepared.wire, image.copy(contentHash = "0".repeat(64))))
        }

        private fun open() {
            val opened = AndroidxSqliteDriver(
                driver = BundledSQLiteDriver(),
                databaseType = AndroidxSqliteDatabaseType.File(databaseFile.absolutePath),
                schema = Database.Schema,
                configuration = AndroidxSqliteConfiguration(isForeignKeyConstraintsEnabled = true),
            )
            driver = opened
            repository = TranslationRepositoryImpl(AppBindings.providesDatabase(opened))
            gateway = TranslationProviderGateway(
                { CredentialParser.apiKey("synthetic-only", "Fixture", "synthetic-fixture") },
                repository,
                TranslationDiagnosticsStore(File(directory, "captures")),
                client,
                File(directory, "http"),
                preparation = preparation,
            )
        }

        fun reopen() {
            driver!!.close()
            preparation = ImagePreparation(preparationDirectory)
            open()
        }

        suspend fun checkpoint(): GeometryCorrectionCheckpoint = repository.operationPage(request.jobId)
            .mapNotNull { it.geometryCorrection }.single()

        private fun assertAcceptedTilesSavedBeforeCorrection() {
            for (id in if (behavior == Behavior.MASKED_AUTHENTICATION) listOf(TOP) else listOf(GOOD, TOP)) {
                val image = prepared.tiles.single { it.image.id == id }.image
                assertNotNull(
                    "Accepted tile $id must be committed before a correction request",
                    preparation.cached(prepared.wire, image),
                )
                val file = preparation.checkpointFile(prepared.wire, image)
                val previous = acceptedBytes.putIfAbsent(file, file.readBytes())
                if (previous != null) assertTrue(previous.contentEquals(file.readBytes()))
            }
        }

        fun assertAcceptedFilesUnchanged() {
            assertEquals(if (behavior == Behavior.MASKED_AUTHENTICATION) 1 else 2, acceptedBytes.size)
            acceptedBytes.forEach { (file, bytes) ->
                assertTrue("Completed tile changed: ${file.name}", bytes.contentEquals(file.readBytes()))
            }
        }

        fun assertComplete(pages: List<TranslationPageResult>) {
            assertEquals(listOf("good", "bad"), pages.map { it.imageId })
            pages.zip(request.ocr).forEach { (page, originalOcr) ->
                assertEquals(originalOcr, page.rawOcr)
                assertEquals(originalOcr.regions.map { it.id }, page.regions.map { it.id })
                assertEquals(originalOcr.regions.map { it.sourceText }, page.regions.map { it.sourceText })
                page.regions.forEach {
                    assertEquals(.83f, it.detectionConfidence)
                    assertEquals(.92f, it.recognitionConfidence)
                }
            }
            assertEquals("Wait", pages.first().regions.single().translatedText)
            val bottom = pages.last().regions.single { it.id == "r1" }
            assertEquals("Lower passage", bottom.translatedText)
            assertEquals(51.2f, bottom.points.first().x, .001f)
            assertEquals(307.2f, bottom.points.first().y, .001f)
            assertEquals(460.8f, bottom.points[2].y, .001f)
            assertEquals(90f, bottom.rotation, .001f)
        }

        fun imageCount(body: JsonObject) = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
            .count { "inlineData" in it.jsonObject }

        fun close() {
            releaseCorrection.countDown()
            client.dispatcher.cancelAll()
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdown()
            driver?.close()
            directory.deleteRecursively()
        }

        private fun image(id: String, index: Int, height: Int): TranslationImage {
            val file = File(directory, "$id.png")
            val bitmap = Bitmap.createBitmap(256, height, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(Color.WHITE)
                Canvas(bitmap).drawRect(20f, 30f, 120f, 130f, Paint().apply { color = Color.BLACK })
                if (height > 256) Canvas(bitmap).drawRect(30f, 290f, 130f, 390f, Paint().apply { color = Color.BLUE })
                file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                bitmap.recycle()
            }
            val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") {
                "%02x".format(it)
            }
            return TranslationImage(id, index, file.path, "image/png", 256, height, hash, file.length())
        }

        private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

        private fun region(id: String, top: Float, source: String) = TextRegion(
            id,
            listOf(
                TranslationPoint(30f, top),
                TranslationPoint(130f, top),
                TranslationPoint(130f, top + 100),
                TranslationPoint(
                    30f,
                    top + 100,
                ),
            ),
            source,
            detectionConfidence = .83f,
            recognitionConfidence = .92f,
        )
    }

    companion object {
        private const val GOOD = "good~0_0_256_256"
        private const val TOP = "bad~0_0_256_256"
        private const val BOTTOM = "bad~0_256_256_256"

        private fun page(id: String, crossing: Boolean = false): JsonObject {
            val regionId = if (id == BOTTOM) "r1" else "r0"
            val source = when (id) {
                GOOD -> "待って"
                TOP -> "上の文章"
                else -> "下の文章"
            }
            val translated = when (id) {
                GOOD -> "Wait"
                TOP -> "Upper passage"
                else -> "Lower passage"
            }
            val polygon = if (crossing) {
                "[[200,200],[800,800],[800,200],[200,800]]"
            } else {
                "[[200,200],[800,200],[800,800],[200,800]]"
            }
            return TranslationWireFormat.json.parseToJsonElement(
                """{"imageId":"$id","detectedLanguage":"ja","regions":[{"id":"$regionId",
                    "sourceText":"$source","translatedText":"$translated","box2d":[200,200,800,800],
                    "polygon":$polygon,
                    "type":"dialogue","readingOrder":0,"rotation":${if (id == BOTTOM) 90 else 0},
                    "included":true,"ignoredReason":null,"aiConfidence":null}]}""",
            ).jsonObject
        }

        private fun pages(vararg pages: JsonObject) = JsonObject(mapOf("pages" to JsonArray(pages.toList())))

        private fun vertex(payload: JsonObject) = buildJsonObject {
            put(
                "candidates",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("finishReason", "STOP")
                            put(
                                "content",
                                buildJsonObject {
                                    put(
                                        "parts",
                                        buildJsonArray {
                                            add(buildJsonObject { put("text", payload.toString()) })
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put(
                "usageMetadata",
                buildJsonObject {
                    put("promptTokenCount", 100)
                    put("candidatesTokenCount", 100)
                },
            )
        }.toString()
    }
}
