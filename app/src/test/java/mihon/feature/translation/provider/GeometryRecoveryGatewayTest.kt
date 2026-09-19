package mihon.feature.translation.provider

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.translation.model.GeometryCorrectionCheckpoint
import tachiyomi.domain.translation.model.GeometryCorrectionState
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.OcrPipeline
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationProviderKind
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.security.MessageDigest

class GeometryRecoveryGatewayTest {
    @TempDir lateinit var directory: File

    @Test
    fun `one rejected image gets one geometry-only correction while the other page is preserved`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.gateway.translate(fixture.request)
        assertEquals(listOf("good", "bad"), result.pages.map { it.imageId })
        assertEquals(2, fixture.generated.size)
        assertEquals(1, fixture.counted.size)
        assertEquals(1, images(fixture.generated.last()))
        assertEquals(fixture.generated.last()["contents"], fixture.counted.last()["contents"])
        assertEquals(listOf("Wait", "Wait"), result.pages.flatMap { it.regions }.map { it.translatedText })
        assertEquals(GeometryCorrectionState.COMPLETED, fixture.checkpoint().state)
        val again = fixture.gateway.translate(fixture.onlyBad())
        assertEquals("bad", again.pages.single().imageId)
        assertEquals(2, fixture.generated.size, "Restart must reuse completed correction without generation")
    }

    @Test
    fun `malformed correction never changes saved dialogue or starts another correction pass`() = runBlocking {
        val fixture = Fixture { number ->
            Reply(
                payload = if (number == 1) {
                    pages(page("good"), page("bad", crossing = true))
                } else {
                    pages(page("bad", translation = "Invented dialogue"))
                },
            )
        }
        val result = fixture.gateway.translate(fixture.request)
        assertEquals(listOf("good"), result.pages.map { it.imageId })
        assertEquals("Wait", result.pages.single().regions.single().translatedText)
        assertEquals(TranslationFailureKind.GEOMETRY, result.deferredFailure?.kind)
        assertEquals(GeometryCorrectionState.FAILED, fixture.checkpoint().state)
        assertEquals(1, fixture.checkpoint().attempts.size)
        val error = assertThrows(TranslationException::class.java) {
            runBlocking { fixture.gateway.translate(fixture.onlyBad()) }
        }
        assertEquals(TranslationFailureKind.GEOMETRY, error.kind)
        assertEquals(2, fixture.generated.size, "A content failure must not open an unbounded repair loop")
    }

    @Test
    fun `transport retry is durably numbered and capped at two even with five initial attempts`() = runBlocking {
        val fixture = Fixture { number ->
            if (number == 2) {
                Reply(status = 503)
            } else {
                Reply(
                    payload = if (number == 1) {
                        pages(page("good"), page("bad", crossing = true))
                    } else {
                        pages(page("bad"))
                    },
                )
            }
        }
        val result = fixture.gateway.translate(
            fixture.request.copy(
                settings = fixture.request.settings.copy(
                    provider = fixture.request.settings.provider.copy(totalAttempts = 5),
                ),
            ),
        )
        assertEquals(2, result.pages.size)
        assertEquals(2, fixture.checkpoint().attempts.size)
        assertEquals(
            listOf(1, 2),
            fixture.operations.filter {
                it.stage == TranslationStage.REQUEST && it.parentId == fixture.checkpoint().id
            }.groupBy { it.id }.values.map { it.last().attempt },
        )
        assertEquals(3, fixture.generated.size)
        assertEquals(fixture.generated[1], fixture.generated[2], "Both attempts reuse identical correction input")
    }

    @Test
    fun `exhausted transport preserves the complete page and cannot restart the same pass`() = runBlocking {
        val fixture = Fixture { number ->
            if (number > 1) Reply(status = 503) else Reply(payload = pages(page("good"), page("bad", true)))
        }
        val result = fixture.gateway.translate(fixture.request)
        assertEquals(listOf("good"), result.pages.map { it.imageId })
        assertEquals(TranslationFailureKind.GEOMETRY, result.deferredFailure?.kind)
        assertEquals(2, fixture.checkpoint().attempts.size)
        assertThrows(TranslationException::class.java) { runBlocking { fixture.gateway.translate(fixture.onlyBad()) } }
        assertEquals(3, fixture.generated.size)
    }

    @Test
    fun `authentication failure pauses correction until explicit Resume without resetting attempts`() = runBlocking {
        val fixture = Fixture { number ->
            if (number == 2) {
                Reply(status = 401)
            } else {
                Reply(
                    payload = if (number == 1) {
                        pages(page("good"), page("bad", true))
                    } else {
                        pages(page("bad"))
                    },
                )
            }
        }
        val result = fixture.gateway.translate(fixture.request)
        assertEquals(listOf("good"), result.pages.map { it.imageId })
        assertEquals(TranslationFailureKind.AUTHENTICATION, result.deferredFailure?.kind)
        assertEquals(GeometryCorrectionState.PAUSED, fixture.checkpoint().state)
        assertThrows(TranslationException::class.java) { runBlocking { fixture.gateway.translate(fixture.onlyBad()) } }
        assertEquals(2, fixture.generated.size)
        fixture.gateway.resumeGeometryCorrections(setOf("job"))
        assertEquals(GeometryCorrectionState.QUEUED, fixture.checkpoint().state)
        assertEquals("bad", fixture.gateway.translate(fixture.onlyBad()).pages.single().imageId)
        assertEquals(2, fixture.checkpoint().attempts.size)
        assertEquals(3, fixture.generated.size)
    }

    @Test
    fun `legacy request with no recovery epoch does not schedule retrospective correction`() = runBlocking {
        val fixture = Fixture()
        val result = fixture.gateway.translate(fixture.request.copy(geometryRecoveryId = null))
        assertEquals(listOf("good"), result.pages.map { it.imageId })
        assertTrue(fixture.checkpoints.isEmpty())
        assertEquals(1, fixture.generated.size)
    }

    @Test
    fun `pure Paddle keeps all authoritative OCR IDs and scores and attaches no images`() = runBlocking {
        val fixture = Fixture()
        val source = fixture.request.images.map { image ->
            OcrPageResult(
                image.id,
                listOf(
                    TextRegion(
                        "r",
                        listOf(
                            TranslationPoint(2f, 2f),
                            TranslationPoint(90f, 2f),
                            TranslationPoint(90f, 90f),
                            TranslationPoint(2f, 90f),
                        ),
                        "Measured source",
                        detectionConfidence = .83f,
                        recognitionConfidence = .92f,
                    ),
                ),
            )
        }
        val request = fixture.request.copy(
            ocr = source,
            settings = fixture.request.settings.copy(
                ocr = fixture.request.settings.ocr.copy(pipeline = OcrPipeline.PADDLE),
            ),
        )
        val result = fixture.gateway.translate(request)
        assertEquals(2, result.pages.size)
        result.pages.zip(source).forEach { (page, ocr) ->
            val region = page.regions.single()
            assertEquals(ocr, page.rawOcr)
            assertEquals(ocr.regions.single().points, region.points)
            assertEquals("Measured source", region.sourceText)
            assertEquals(.83f, region.detectionConfidence)
            assertEquals(.92f, region.recognitionConfidence)
            assertEquals("r", region.id)
        }
        assertTrue(fixture.checkpoints.isEmpty())
        assertEquals(1, fixture.generated.size)
        assertEquals(0, images(fixture.generated.single()))
    }

    @Test
    fun `changed image content snapshots the prior result revision before correcting its replacement`() = runBlocking {
        val fixture = Fixture()
        val prior = previousResult(77)
        fixture.savedResults += prior
        val outcome = runCatching { fixture.gateway.translate(fixture.onlyBad()) }
        assertEquals(77L, fixture.proposedCheckpoints.first().sourceRevision)
        assertEquals("bad", outcome.getOrThrow().pages.single().imageId)
        assertEquals(2, fixture.generated.size)
        assertEquals(listOf(prior), fixture.savedResults, "Only Manager may commit the replacement source page")
    }

    @Test
    fun `manual edit during initial generation invalidates correction using the earlier revision`() = runBlocking {
        lateinit var fixture: Fixture
        val edited = previousResult(78, "Manual correction")
        fixture = Fixture {
            fixture.savedResults.clear()
            fixture.savedResults += edited
            Reply(payload = pages(page("bad", crossing = true)))
        }
        fixture.savedResults += previousResult(77)
        val outcome = runCatching { fixture.gateway.translate(fixture.onlyBad()) }
        assertEquals(77L, fixture.proposedCheckpoints.first().sourceRevision, "Snapshot must precede initial HTTP")
        assertEquals(TranslationFailureKind.GEOMETRY, (outcome.exceptionOrNull() as? TranslationException)?.kind)
        assertEquals(1, fixture.generated.size, "Do not dispatch geometry correction after the source revision changed")
        assertTrue(fixture.checkpoints.isEmpty())
        assertEquals(listOf(edited), fixture.savedResults)
    }

    @Test
    fun `a retained authentication pause blocks other pending input before an explicit Resume`() = runBlocking {
        val fixture = pausedFixture()
        val attempts = fixture.checkpoint().attempts
        val pending = fixture.inputs.first().copy(id = "unrelated")
        val mixed = fixture.request.copy(images = listOf(fixture.inputs.last(), pending))
        val previousCounts = fixture.counted.size
        val counted = runCatching { fixture.gateway.countTokens(mixed) }
        assertEquals(previousCounts, fixture.counted.size, "An authentication pause also blocks preflight HTTP")
        assertEquals(TranslationFailureKind.AUTHENTICATION, (counted.exceptionOrNull() as? TranslationException)?.kind)
        val outcome = runCatching { fixture.gateway.translate(mixed) }
        assertEquals(2, fixture.generated.size, "A retained authentication pause must precede any new generation")
        assertEquals(TranslationFailureKind.AUTHENTICATION, (outcome.exceptionOrNull() as? TranslationException)?.kind)
        assertEquals(attempts, fixture.checkpoint().attempts)

        fixture.gateway.resumeGeometryCorrections(setOf("job"))
        val resumed = fixture.gateway.translate(fixture.request.copy(images = listOf(pending)))
        assertEquals("unrelated", resumed.pages.single().imageId)
        assertEquals(3, fixture.generated.size)
        assertEquals(attempts, fixture.checkpoint().attempts)
    }

    @Test
    fun `sibling group authentication pause blocks this job and epoch before generation`() = runBlocking {
        val fixture = pausedFixture()
        val pending = fixture.inputs.first().copy(id = "unrelated")
        val outcome = runCatching { fixture.gateway.translate(fixture.request.copy(images = listOf(pending))) }
        assertEquals(2, fixture.generated.size, "A different physical group must still honor this job's retained pause")
        assertEquals(TranslationFailureKind.AUTHENTICATION, (outcome.exceptionOrNull() as? TranslationException)?.kind)
        assertEquals(GeometryCorrectionState.PAUSED, fixture.checkpoint().state)
    }

    @Test
    fun `historical authentication pause does not block a new explicitly requested recovery epoch`() = runBlocking {
        val fixture = pausedFixture()
        val paused = fixture.checkpoint()
        val pending = fixture.inputs.first().copy(id = "unrelated")
        val replacement = fixture.request.copy(images = listOf(pending), geometryRecoveryId = "new-explicit-pass")

        val result = fixture.gateway.translate(replacement)

        assertEquals("unrelated", result.pages.single().imageId)
        assertEquals(3, fixture.generated.size)
        assertEquals(paused, fixture.checkpoint(), "The historical pass and its consumed attempt stay unchanged")
    }

    private suspend fun pausedFixture(): Fixture = Fixture { number ->
        when (number) {
            1 -> Reply(payload = pages(page("good"), page("bad", crossing = true)))
            2 -> Reply(status = 401)
            else -> Reply(payload = pages(page("unrelated")))
        }
    }.also { fixture ->
        val initial = fixture.gateway.translate(fixture.request)
        assertEquals(TranslationFailureKind.AUTHENTICATION, initial.deferredFailure?.kind)
        assertEquals(GeometryCorrectionState.PAUSED, fixture.checkpoint().state)
    }

    private fun previousResult(
        revision: Long,
        translation: String = "Previous saved translation",
    ) = TranslationPageResult(
        "bad",
        "previous-image-content",
        200,
        100,
        listOf(
            TextRegion(
                "r",
                listOf(
                    TranslationPoint(0f, 0f),
                    TranslationPoint(200f, 0f),
                    TranslationPoint(200f, 100f),
                    TranslationPoint(0f, 100f),
                ),
                "待って",
                translatedText = translation,
            ),
        ),
        revision = revision,
    )

    private data class Reply(val status: Int = 200, val payload: JsonObject? = null)

    private inner class Fixture(
        val reply: (Int) -> Reply = { number ->
            Reply(
                payload = if (number == 1) {
                    pages(page("good"), page("bad", crossing = true))
                } else {
                    pages(page("bad"))
                },
            )
        },
    ) {
        val checkpoints = linkedMapOf<String, GeometryCorrectionCheckpoint>()
        val proposedCheckpoints = mutableListOf<GeometryCorrectionCheckpoint>()
        val savedResults = mutableListOf<TranslationPageResult>()
        val operations = mutableListOf<TranslationOperation>()
        val repository = mockk<TranslationRepository>(relaxed = true).apply {
            coEvery { results(any()) } coAnswers { savedResults.toList() }
            coEvery { resultRevision(any(), any()) } coAnswers {
                savedResults.firstOrNull { it.imageId == secondArg<String>() }?.revision
            }
            coEvery { geometryCorrection(any()) } coAnswers { checkpoints[firstArg()] }
            coEvery { createGeometryCorrection(any()) } coAnswers {
                val proposed = firstArg<GeometryCorrectionCheckpoint>()
                proposedCheckpoints += proposed
                checkpoints[proposed.id] ?: proposed.takeIf {
                    savedResults.firstOrNull { it.imageId == proposed.transform.original.id }?.revision ==
                        proposed.sourceRevision
                }?.also { checkpoints[it.id] = it }
            }
            coEvery { compareAndSetGeometryCorrection(any(), any()) } coAnswers {
                val previous = firstArg<GeometryCorrectionCheckpoint>()
                val next = secondArg<GeometryCorrectionCheckpoint>()
                if (checkpoints[previous.id] == previous) {
                    checkpoints[previous.id] = next
                    true
                } else {
                    false
                }
            }
            coEvery { saveOperation(any()) } coAnswers { operations += firstArg<TranslationOperation>() }
            coEvery { operationPage(any(), any(), any(), any(), any(), any(), any(), any(), any()) } coAnswers {
                val selectedJob = firstArg<String?>()
                val selectedState = arg<TranslationOperationState?>(8)
                checkpoints.values.map {
                    TranslationOperation(
                        it.id,
                        it.jobId,
                        TranslationStage.GEOMETRY_CORRECTION,
                        state = when (it.state) {
                            GeometryCorrectionState.QUEUED -> TranslationOperationState.QUEUED
                            GeometryCorrectionState.RUNNING -> TranslationOperationState.ACTIVE
                            GeometryCorrectionState.PAUSED -> TranslationOperationState.PAUSED
                            GeometryCorrectionState.COMPLETED -> TranslationOperationState.COMPLETED
                            GeometryCorrectionState.FAILED -> TranslationOperationState.FAILED
                            GeometryCorrectionState.INTERRUPTED -> TranslationOperationState.INTERRUPTED
                            GeometryCorrectionState.SUPERSEDED -> TranslationOperationState.CANCELLED
                        },
                        geometryCorrection = it,
                    )
                }.filter {
                    (selectedJob == null || it.jobId == selectedJob) &&
                        (selectedState == null || it.state == selectedState)
                }
            }
        }
        val generated = mutableListOf<JsonObject>()
        val counted = mutableListOf<JsonObject>()
        var correctionGenerationCount = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val body = TranslationWireFormat.json.parseToJsonElement(
                Buffer().also {
                    chain.request().body!!.writeTo(it)
                }.readUtf8(),
            ).jsonObject
            var status = 200
            val response = if (chain.request().url.encodedPath.endsWith(":countTokens")) {
                counted += body
                """{"totalTokens":200}"""
            } else {
                generated += body
                if (body["systemInstruction"].toString().contains("Correct only rejected geometry")) {
                    correctionGenerationCount++
                    assertNotNull(checkpoints.values.singleOrNull(), "Checkpoint must be saved before correction HTTP")
                    assertEquals(correctionGenerationCount, checkpoint().attempts.size)
                    assertEquals(GeometryCorrectionState.RUNNING, checkpoint().state)
                }
                val output = reply(generated.size)
                status = output.status
                output.payload?.let(::vertex) ?: """{"error":{"message":"Synthetic failure"}}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(status).message("Fixture")
                .header("x-request-id", "fixture-${generated.size}").body(response.toResponseBody()).build()
        }.build()
        val image = File(directory, "original.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val hash = MessageDigest.getInstance("SHA-256").digest(image.readBytes()).joinToString("") { "%02x".format(it) }
        val inputs = listOf("good", "bad").mapIndexed { index, id ->
            TranslationImage(id, index, image.path, "image/png", 200, 100, hash, image.length())
        }
        val request = TranslationRequest(
            "job",
            "batch",
            TranslationSettings(
                provider = ProviderSettings(
                    kind = TranslationProviderKind.VERTEX_EXPRESS,
                    totalAttempts = 2,
                    initialRetryMillis = 1,
                    maxRetryMillis = 2,
                ),
            ),
            inputs,
            geometryRecoveryId = "explicit-pass",
        )
        val gateway = TranslationProviderGateway(
            { CredentialParser.apiKey("synthetic-only", "Fixture", "default") },
            repository,
            TranslationDiagnosticsStore(File(directory, "captures")),
            client,
            File(directory, "temporary"),
        )
        fun checkpoint() = checkpoints.values.single()
        fun onlyBad() = request.copy(images = listOf(inputs.last()))
    }

    private fun images(body: JsonObject) = body["contents"]!!.jsonArray.single().jsonObject["parts"]!!.jsonArray
        .count { "inlineData" in it.jsonObject }

    private fun page(id: String, crossing: Boolean = false, translation: String = "Wait") =
        TranslationWireFormat.json.parseToJsonElement(
            """
            {"imageId":"$id","detectedLanguage":"ja","regions":[
            {"id":"r","sourceText":"待って","translatedText":"$translation","box2d":[0,0,1000,1000],
            "polygon":${if (crossing) "[[0,0],[1000,1000],[1000,0],[0,1000]]" else "[[0,0],[1000,0],[1000,1000],[0,1000]]"},
            "type":"dialogue","readingOrder":0,"rotation":0,"included":true,"ignoredReason":null,"aiConfidence":null}]}
            """,
        ).jsonObject

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
                                        add(
                                            buildJsonObject {
                                                put("text", payload.toString())
                                            },
                                        )
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
