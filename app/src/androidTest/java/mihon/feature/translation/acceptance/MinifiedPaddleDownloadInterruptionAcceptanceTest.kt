package mihon.feature.translation.acceptance

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mihon.feature.translation.ocr.PaddleModelManager
import mihon.feature.translation.ocr.PaddleModelRegistry
import mihon.feature.translation.ocr.PaddleModelStatus
import okhttp3.OkHttpClient
import okhttp3.Protocol
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.PaddleProfile
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/** Real local HTTP interruption and verified full-file restart; no installed pack is modified. */
@RunWith(AndroidJUnit4::class)
class MinifiedPaddleDownloadInterruptionAcceptanceTest {
    @Test
    fun interruptedTinyDownloadRestartsWithoutPublishingPartialFiles() =
        runBlocking<Unit> {
            assumeTrue(InstrumentationRegistry.getArguments().getString("translation.modelInterruption") == "true")
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            assertEquals("app.mihon.benchmark", context.packageName)
            assertTrue(BuildConfig.TRANSLATION_LOCAL_FIXTURES_ENABLED)
            assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            withContext(Dispatchers.IO) {
                val detector = PaddleModelRegistry.models.getValue("PP-OCRv6_tiny_det_onnx")
                val recognizer = PaddleModelRegistry.models.getValue("PP-OCRv6_tiny_rec_onnx")
                val key = "tiny-multi-${detector.revision}-${recognizer.revision}"
                val installed = File(context.noBackupFilesDir, "translation/paddle-models/$key")
                val artifacts =
                    listOf("det" to detector, "rec" to recognizer).flatMap { (folder, spec) ->
                        spec.artifacts.map { artifact ->
                            Artifact(
                                "https://huggingface.co/PaddlePaddle/${spec.id}/resolve/${spec.revision}/${artifact.name}",
                                "$folder/${artifact.name}",
                                File(installed, "$folder/${artifact.name}"),
                                artifact.bytes,
                                artifact.sha256,
                            )
                        }
                    }
                artifacts.forEach {
                    assertTrue("Install the official Tiny pack before this read-only source check", it.file.isFile)
                    assertEquals(it.bytes, it.file.length())
                    assertEquals(it.sha256, hash(it.file))
                }
                val root = File(context.noBackupFilesDir, "translation-model-interruption/${UUID.randomUUID()}")
                check(root.mkdirs())
                val destination = File(root, "models").apply { check(mkdir()) }
                val unrelated = File(destination, "unrelated-evidence.txt").apply {
                    writeText("Keep this file unchanged")
                }
                val unrelatedHash = hash(unrelated)
                val report =
                    JSONObject()
                        .put("schema", 1)
                        .put("run_id", root.name)
                        .put("cloud_forwarding", false)
                        .put("credential_access", false)
                        .put("source_pack", key)
                        .put("recovery_type", "full-file restart; no HTTP Range resume")
                        .put(
                            "limits",
                            "Real loopback socket interruption under minified instrumentation; " +
                                "not UI cancellation, Wi-Fi loss, or process death",
                        )
                val server = ArtifactServer(artifacts)
                var failure: Throwable? = null
                try {
                    val allowed = artifacts.mapIndexed { index, artifact -> artifact.url to index }.toMap()
                    val client =
                        OkHttpClient
                            .Builder()
                            .followRedirects(false)
                            .followSslRedirects(false)
                            .retryOnConnectionFailure(false)
                            .protocols(listOf(Protocol.HTTP_1_1))
                            .addInterceptor { chain ->
                                val index =
                                    allowed[chain.request().url.toString()]
                                        ?: throw IOException("Only pinned fixture artifact URLs are allowed")
                                chain.proceed(
                                    chain
                                        .request()
                                        .newBuilder()
                                        .url("http://127.0.0.1:${server.port}/artifact/$index")
                                        .build(),
                                )
                            }.build()
                    val first = PaddleModelManager(destination, client)
                    supervisorScope {
                        val download = async { first.download(PaddleProfile.TINY, "en") }
                        try {
                            withTimeout(15_000) {
                                first.states.first { states ->
                                    states.any {
                                        it.profile == PaddleProfile.TINY && !it.korean &&
                                            it.status == PaddleModelStatus.DOWNLOADING && it.downloadBytes >= 65_536
                                    }
                                }
                            }
                            report.put(
                                "observed_download_bytes",
                                first.states.value
                                    .single {
                                        it.profile == PaddleProfile.TINY && !it.korean
                                    }.downloadBytes,
                            )
                            server.interruptFirstResponse()
                            val error = runCatching { withTimeout(15_000) { download.await() } }.exceptionOrNull()
                            assertTrue("Socket truncation must fail the actual download", error is IOException)
                            report.put("interruption_failure_class", error!!.javaClass.name)
                        } finally {
                            server.interruptFirstResponse()
                            download.cancel()
                            download.join()
                        }
                    }
                    assertEquals(
                        PaddleModelStatus.FAILED,
                        first.states.value
                            .single {
                                it.profile == PaddleProfile.TINY && !it.korean
                            }.status,
                    )
                    assertFalse(File(destination, key).exists())
                    assertEquals(listOf(unrelated.name), destination.listFiles().orEmpty().map { it.name })
                    report.put("partial_pack_not_published", true).put("interrupted_staging_removed", true)
                    val restarted = PaddleModelManager(destination, client)
                    withTimeout(60_000) { restarted.download(PaddleProfile.TINY, "en") }
                    restarted.refresh()
                    assertEquals(
                        PaddleModelStatus.INSTALLED,
                        restarted.states.value
                            .single {
                                it.profile == PaddleProfile.TINY && !it.korean
                            }.status,
                    )
                    artifacts.forEach {
                        val copy = File(File(destination, key), it.relative)
                        assertEquals(it.bytes, copy.length())
                        assertEquals(it.sha256, hash(copy))
                        assertEquals("Existing model sources must remain unchanged", it.sha256, hash(it.file))
                    }
                    assertEquals(unrelatedHash, hash(unrelated))
                    assertEquals(4, server.ledger.size)
                    assertEquals(listOf(0, 0, 1, 2), server.ledger.map { it.getInt("artifact_index") })
                    assertTrue(server.ledger.all { !it.getBoolean("range_requested") })
                    assertTrue(server.ledger.none { it.getBoolean("authorization_present") })
                    report
                        .put("verified_restart", true)
                        .put("source_hashes_unchanged", true)
                        .put("unrelated_file_preserved", true)
                    server.failure.get()?.let { throw it }
                } catch (error: Throwable) {
                    failure = error
                } finally {
                    try {
                        server.close()
                        server.failure.get()?.let { throw it }
                    } catch (error: Throwable) {
                        val prior = failure
                        if (prior == null) {
                            failure = error
                        } else if (prior !== error) {
                            prior.addSuppressed(error)
                        }
                    }
                    try {
                        assertTrue(
                            "Only the owned disposable model directory is removed",
                            destination.deleteRecursively(),
                        )
                        report.put("owned_model_directory_removed", true)
                    } catch (error: Throwable) {
                        report.put("owned_model_directory_removed", false)
                        val prior = failure
                        if (prior == null) {
                            failure = error
                        } else if (prior !== error) {
                            prior.addSuppressed(error)
                        }
                    }
                    report
                        .put("status", if (failure == null) "passed" else "failed")
                        .put("request_ledger", JSONArray(server.ledger))
                        .put(
                            "artifacts",
                            JSONArray(
                                artifacts.map {
                                    JSONObject()
                                        .put("relative_path", it.relative)
                                        .put("bytes", it.bytes)
                                        .put("sha256", it.sha256)
                                },
                            ),
                        )
                    failure?.let { report.put("failure_class", it.javaClass.name).put("failure_message", it.message) }
                    File(root, "report.json").writeText(report.toString(2))
                    InstrumentationRegistry.getInstrumentation().sendStatus(
                        0,
                        Bundle().apply {
                            putString("translation_model_interruption_report_json", report.toString())
                        },
                    )
                    println("TRANSLATION_MODEL_INTERRUPTION_REPORT_JSON=$report")
                }
                failure?.let { throw it }
            }
        }

    private data class Artifact(
        val url: String,
        val relative: String,
        val file: File,
        val bytes: Long,
        val sha256: String,
    )

    private class ArtifactServer(
        private val artifacts: List<Artifact>,
    ) {
        private val listener = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int = listener.localPort
        val ledger = CopyOnWriteArrayList<JSONObject>()
        val failure = AtomicReference<Throwable?>()
        private val closed = AtomicBoolean(false)
        private val active = AtomicReference<Socket?>()
        private val releaseFirst = CountDownLatch(1)
        private val worker =
            thread(name = "model-interruption-fixture", isDaemon = true) {
                try {
                    while (!closed.get()) {
                        listener.accept().use { socket ->
                            active.set(socket)
                            socket.soTimeout = 10_000
                            val input = socket.getInputStream().bufferedReader(Charsets.US_ASCII)
                            val request = requireNotNull(input.readLine()).split(' ')
                            check(request.size == 3 && request[0] == "GET")
                            val index =
                                requireNotNull(Regex("/artifact/([0-2])").matchEntire(request[1]))
                                    .groupValues[1]
                                    .toInt()
                            val headers = mutableListOf<String>()
                            while (true) {
                                val line = requireNotNull(input.readLine())
                                if (line.isEmpty()) break
                                headers += line
                            }
                            val first = ledger.isEmpty()
                            val artifact = artifacts[index]
                            val entry =
                                JSONObject()
                                    .put("artifact_index", index)
                                    .put("expected_sha256", artifact.sha256)
                                    .put("range_requested", headers.any { it.startsWith("Range:", true) })
                                    .put("authorization_present", headers.any { it.startsWith("Authorization:", true) })
                                    .put("deliberately_interrupted", first)
                            ledger += entry
                            val output = socket.getOutputStream()
                            output.write(
                                (
                                    "HTTP/1.1 200 OK\r\nContent-Length: ${artifact.bytes}\r\n" +
                                        "Content-Type: application/octet-stream\r\nConnection: close\r\n\r\n"
                                    ).toByteArray(Charsets.US_ASCII),
                            )
                            artifact.file.inputStream().use { source ->
                                if (first) {
                                    val bytes = ByteArray(65_536)
                                    var read = 0
                                    while (read < bytes.size) {
                                        val count = source.read(bytes, read, bytes.size - read)
                                        check(count > 0)
                                        read += count
                                    }
                                    output.write(bytes)
                                    output.flush()
                                    entry.put("body_bytes_sent", bytes.size)
                                    check(releaseFirst.await(20, TimeUnit.SECONDS))
                                } else {
                                    entry.put("body_bytes_sent", source.copyTo(output))
                                    output.flush()
                                }
                            }
                            active.compareAndSet(socket, null)
                        }
                    }
                } catch (error: Throwable) {
                    if (!closed.get()) failure.set(error)
                }
            }

        fun interruptFirstResponse() {
            if (releaseFirst.count != 0L) {
                active.get()?.close()
                releaseFirst.countDown()
            }
        }

        fun close() {
            closed.set(true)
            interruptFirstResponse()
            active.getAndSet(null)?.close()
            listener.close()
            worker.join(5000)
            check(!worker.isAlive) { "Fixture server did not stop" }
        }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
