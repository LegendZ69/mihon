package mihon.feature.translation.ocr

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import mihon.feature.translation.provider.TranslationDiagnosticsStore
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.translation.model.PaddleProfile
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationLogSettings
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PaddleModelManagerTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `successful model HTTP transfer and failed integrity validation have separate linked operations`() = runTest {
        val operations = mutableListOf<TranslationOperation>()
        val events = mutableListOf<TranslationEvent>()
        val repository = mockk<TranslationRepository>(relaxed = true)
        coEvery { repository.saveOperation(capture(operations)) } returns Unit
        coEvery { repository.addEvent(capture(events)) } returns Unit
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .header("X-Request-Id", "model-request-1").body("truncated model".toResponseBody()).build()
        }.build()
        val captures = temporary.resolve("captures").toFile()
        val models = temporary.resolve("models").toFile()
        val manager = PaddleModelManager(
            models,
            client,
            repository,
            TranslationDiagnosticsStore(captures),
            { TranslationLogSettings(captureRaw = true) },
        )
        val failure = runCatching { manager.download(PaddleProfile.TINY) }.exceptionOrNull()
        assertTrue(failure is IOException)
        val transfers = operations.filter { it.stage == TranslationStage.MODEL_DOWNLOAD }
        assertTrue(transfers.isNotEmpty(), "A successful HTTP response must have a durable download operation")
        val transfer = transfers.last()
        assertEquals(TranslationOperationState.COMPLETED, transfer.state)
        assertEquals(15L, transfer.completed)
        val verification = operations.last { it.stage == TranslationStage.MODEL_VERIFY }
        assertEquals(TranslationOperationState.FAILED, verification.state)
        assertEquals(transfer.id, verification.parentId)
        assertEquals(transfer.jobId, verification.jobId)
        assertTrue(events.any { it.operationId == transfer.id && it.details["httpStatus"] == "200" })
        assertTrue(events.any { it.operationId == verification.id && it.details.containsKey("expectedSha256") })
        assertTrue(models.listFiles().orEmpty().isEmpty())
        val capture = checkNotNull(transfer.captureId).let { captures.resolve(it) }
        val files = capture.listFiles().orEmpty().filter { it.isFile }.map { it.readText() }
        assertTrue(files.isNotEmpty())
        assertFalse(files.any { "truncated model" in it }, "Model bodies must never enter diagnostic captures")
        assertTrue(capture.resolve("response.json").readText().contains("receivedBytes"))
    }

    @Test
    fun `truncated artifact cannot be published as an installed model`() = runTest {
        val paths = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            paths += chain.request().url.encodedPath
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body("truncated model".toResponseBody())
                .build()
        }.build()
        val directory = temporary.resolve("models").toFile()
        val manager = PaddleModelManager(directory, client)
        val failure = runCatching { manager.download(PaddleProfile.TINY) }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertEquals(
            PaddleModelStatus.FAILED,
            manager.states.value.first {
                it.profile == PaddleProfile.TINY &&
                    !it.korean
            }.status,
        )
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        assertTrue(paths.single().contains("/resolve/2ba1506c0380b8f0b03dd142459aac66d4421f6c/"))
    }

    @Test
    fun `cancelled stream cannot restore a downloading state after cancellation`() = runTest {
        val readStarted = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val streamClosed = CountDownLatch(1)
        val delayedSource = object : Source {
            override fun read(sink: Buffer, byteCount: Long): Long {
                readStarted.countDown()
                check(releaseRead.await(5, TimeUnit.SECONDS))
                sink.writeUtf8("truncated model")
                return 15L
            }
            override fun timeout() = Timeout.NONE
            override fun close() {
                streamClosed.countDown()
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength() = -1L
            override fun source() = delayedSource
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body).build()
        }.build()
        val manager = PaddleModelManager(temporary.toFile(), client)
        val download = async(Dispatchers.IO) { manager.download(PaddleProfile.TINY) }
        try {
            assertTrue(withContext(Dispatchers.IO) { readStarted.await(5, TimeUnit.SECONDS) })
            val operationId = checkNotNull(
                manager.states.value.first {
                    it.profile == PaddleProfile.TINY && !it.korean
                }.operationId,
            )
            assertFalse(manager.cancel("unrelated-operation"))
            assertTrue(manager.cancel(operationId))
            download.join()
            assertFalse(manager.cancel(operationId))
            // Let an already-running network read return bytes after the cancelled coroutine exits.
            releaseRead.countDown()
            assertTrue(withContext(Dispatchers.IO) { streamClosed.await(5, TimeUnit.SECONDS) })
            assertEquals(
                PaddleModelStatus.NOT_DOWNLOADED,
                manager.states.value.first {
                    it.profile == PaddleProfile.TINY &&
                        !it.korean
                }.status,
            )
            assertTrue(temporary.toFile().listFiles().orEmpty().isEmpty())
        } finally {
            releaseRead.countDown()
            download.cancelAndJoin()
        }
    }

    @Test
    fun `Korean packs use the actual Korean recognizer at every detector size`() {
        val manager = PaddleModelManager(temporary.toFile(), OkHttpClient())
        val koreanPacks = manager.states.value.filter { it.korean }
        assertEquals(3, koreanPacks.size)
        assertTrue(koreanPacks.all { it.recognizerModel == "korean_PP-OCRv5_mobile_rec_onnx" })
        assertTrue(PaddleModelManager.isKorean("ko-KR"))
        assertFalse(PaddleModelManager.isKorean("ja"))
    }

    @Test
    fun `refresh never treats a partial directory as installed`() = runTest {
        val detector = PaddleModelRegistry.models.getValue("PP-OCRv6_tiny_det_onnx").revision
        val recognizer = PaddleModelRegistry.models.getValue("PP-OCRv6_tiny_rec_onnx").revision
        val partial = temporary.resolve(".tiny-multi-$detector-$recognizer-interrupted.part").toFile()
        partial.mkdirs()
        partial.resolve("inference.onnx").writeText("partial")
        val manager = PaddleModelManager(temporary.toFile(), OkHttpClient())
        manager.refresh()
        assertTrue(manager.states.value.all { it.status == PaddleModelStatus.NOT_DOWNLOADED })
        assertFalse(partial.exists())
    }
}
