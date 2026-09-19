package mihon.feature.translation.provider

import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.BufferedSink
import org.junit.jupiter.api.Assertions.assertAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.function.Executable
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationLogSettings
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.zip.ZipInputStream

class TranslationCaptureBudgetTest {
    @TempDir lateinit var directory: File
    private val settings = TranslationLogSettings(captureRaw = true, maxStorageMb = 1)
    private val limit = 1024L * 1024

    @Test
    fun `import recomputes incomplete body status instead of trusting archived success metadata`() = runBlocking {
        val id = "12345678-1234-1234-1234-123456789abc"
        val metadata = CaptureMetadata(
            id, "source", null, "generateContent", completedAt = 19,
            requestBytes = 200,
            responseBytes = 200,
            state = "COMPLETED",
            requestBodyCompleted = true,
            responseBodyCompleted = true,
        )
        val payload = ByteArrayOutputStream().also { bytes ->
            java.util.zip.ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(java.util.zip.ZipEntry("$id/metadata.json"))
                zip.write(kotlinx.serialization.json.Json.encodeToString(metadata).toByteArray())
                zip.closeEntry()
                zip.putNextEntry(java.util.zip.ZipEntry("$id/request.json"))
                zip.write("""{"api_key":"never-persist-this-secret","broken":""".toByteArray())
                zip.closeEntry()
            }
        }.toByteArray()
        val store = TranslationDiagnosticsStore(File(directory, "invalid-import"))
        store.importArchive(ByteArrayInputStream(payload), mapOf("source" to "target"), settings)
        val restored = store.list().single()
        assertFalse(restored.requestBodyCompleted)
        assertFalse(restored.responseBodyCompleted)
        assertEquals("INCOMPLETE", restored.state)
        assertEquals(false, restored.requestSanitization?.complete)
        val all = store.directoryFor(restored.id).walkTopDown().filter { it.isFile }.joinToString { it.readText() }
        assertFalse(all.contains("never-persist-this-secret"))
    }

    @Test
    fun `restoring diagnostic archive sanitizes bodies maps jobs and deduplicates imported history`() = runBlocking {
        val source = TranslationDiagnosticsStore(File(directory, "capture-source"))
        val capture = source.start("source-job", "batch", "generateContent", settings)
        source.openBody(capture, CaptureBody.REQUEST).use {
            it.write(
                (
                    """{"api_key":"restored-secret","image_url":{"url":"data:image/png;base64,""" +
                        """AQIDBA=="},"thought_signature":"restored-signature",""" +
                        """"message":"Keep the door closed"}"""
                    ).toByteArray(),
            )
        }
        source.finish(capture, capture.metadata.copy(completedAt = 17, state = "COMPLETED"))
        val zip = ByteArrayOutputStream().also { source.export(listOf(capture.metadata.id), it) }.toByteArray()
        val destination = TranslationDiagnosticsStore(File(directory, "capture-restored"))
        val mapping = destination.importArchive(
            ByteArrayInputStream(zip),
            mapOf("source-job" to "target-job"),
            settings,
        )
        assertEquals(setOf(capture.metadata.id), mapping.keys)
        val restored = destination.list().single()
        assertEquals("target-job", restored.jobId)
        assertEquals(mapping.getValue(capture.metadata.id), restored.id)
        assertEquals(17L, restored.completedAt)
        val safe = File(destination.directoryFor(restored.id), "request.json").readText()
        assertTrue(safe.contains("Keep the door closed"))
        listOf("restored-secret", "restored-signature", "AQIDBA==").forEach { assertFalse(safe.contains(it)) }
        assertEquals(
            mapping,
            destination.importArchive(ByteArrayInputStream(zip), mapOf("source-job" to "target-job"), settings),
        )
        assertEquals(1, destination.list().size)
    }

    @Test
    fun `diagnostic captures omit images and thought signatures without changing network content`() = runBlocking {
        val image = "A".repeat(64 * 1024)
        val requestBody = (
            """{"contents":[{"parts":[{"text":"Translate this page"},""" +
                """{"inlineData":{"mimeType":"image/png","data":"$image"}}]}]}"""
            )
        val responseBody = (
            """{"candidates":[{"content":{"parts":[{"text":"Keep the door closed",""" +
                """"thoughtSignature":"opaque-signature"}]}}],""" +
                """"usageMetadata":{"promptTokenCount":123}}"""
            )
        var sent = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            sent = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(responseBody.toResponseBody()).build()
        }.build()
        val store = TranslationDiagnosticsStore(File(directory, "sanitized"))
        val transport = TranslationHttpTransport(client, store, mockk(relaxed = true), File(directory, "temporary"))
        val result = transport.execute(
            Request.Builder().url("https://example.com/v1/generateContent").post(requestBody.toRequestBody()).build(),
            "job",
            "batch",
            "generateContent",
            settings,
            1,
            30,
            emptyList(),
        )
        assertEquals(requestBody, sent)
        assertEquals(123, result.body["usageMetadata"]!!.jsonObject["promptTokenCount"]!!.jsonPrimitive.int)
        val metadata = store.list().single()
        val folder = store.directoryFor(metadata.id)
        val requestCapture = File(folder, "request.json").readText()
        val responseCapture = File(folder, "response.json").readText()
        assertFalse(requestCapture.contains(image.take(256)))
        assertFalse(responseCapture.contains("opaque-signature"))
        assertTrue(requestCapture.contains("Translate this page"))
        assertTrue(responseCapture.contains("Keep the door closed"))
        assertTrue(responseCapture.contains("promptTokenCount"))
        assertTrue(requestCapture.contains("omitted"))
        assertTrue(responseCapture.contains("omitted"))
        assertEquals("COMPLETED", metadata.state)
        assertTrue(requestCapture.length < 4096)
    }

    @Test
    fun `export sanitizes legacy captures without rewriting the saved evidence`() = runBlocking {
        val store = TranslationDiagnosticsStore(File(directory, "legacy"))
        val session = store.start("legacy-job", null, "generateContent", settings)
        val original = (
            """{"api_key":"legacy-private-key","image_url":{"url":"data:image/png;base64,""" +
                """AQIDBA=="},"thought_signature":"legacy-signature",""" +
                """"metadata":{"download":"https://example.com/model?X-Amz-Signature=legacy-signed-token"},""" +
                """"usage":{"output_tokens":42}}"""
            )
        store.openBody(session, CaptureBody.REQUEST).use { it.write(original.toByteArray()) }
        store.finish(session, session.metadata.copy(capturePolicy = "legacy-redacted", completedAt = 1L))
        val destination = ByteArrayOutputStream()
        store.export(listOf(session.metadata.id), destination)
        val entries = buildMap {
            ZipInputStream(ByteArrayInputStream(destination.toByteArray())).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    put(entry.name.substringAfter('/'), zip.readBytes().decodeToString())
                }
            }
        }
        val exported = entries.getValue("request.json")
        assertFalse(exported.contains("legacy-private-key"))
        assertFalse(exported.contains("AQIDBA=="))
        assertFalse(exported.contains("legacy-signature"))
        assertFalse(exported.contains("legacy-signed-token"))
        assertTrue(exported.contains("output_tokens"))
        assertTrue(exported.contains("42"))
        assertTrue(entries.getValue("export-policy.json").contains("sanitized-v1"))
        assertEquals(original, session.requestFile.readText())
        assertEquals("legacy-redacted", store.list().single().capturePolicy)
    }

    @Test
    fun `credentials in JSON keys and nested JSON strings are omitted from diagnostics`() = runBlocking {
        val body = (
            """{"credential-secret":"visible",""" +
                """"text":"{\"credential-secret\":\"nested text\",""" +
                """\"thought_signature\":\"nested-signature\"}","token":"opaque-token"}"""
            )
        var observed = ""
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            observed = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"ok\":true}".toResponseBody()).build()
        }.build()
        val store = TranslationDiagnosticsStore(File(directory, "key-redaction"))
        TranslationHttpTransport(client, store, mockk(relaxed = true), File(directory, "temporary")).execute(
            Request.Builder().url("https://example.com/v1/responses").post(body.toRequestBody()).build(),
            "job",
            null,
            "generateContent",
            settings,
            1,
            30,
            listOf("credential-secret"),
        )
        assertEquals(body, observed)
        val captured = File(store.directoryFor(store.list().single().id), "request.json").readText()
        assertFalse(captured.contains("credential-secret"))
        assertFalse(captured.contains("nested-signature"))
        assertFalse(captured.contains("opaque-token"))
        assertTrue(captured.contains("visible"))
        assertTrue(captured.contains("nested text"))
    }

    @Test
    fun `nested binary values whitespace JSON and forged omission markers cannot bypass capture policy`() {
        val cases = listOf(
            """{"binary":[137,80,78,71]}""" to "[137,80,78,71]",
            """{"image":{"bytes":[42,43,44]}}""" to "[42,43,44]",
            """{"text":"  {\"thought_signature\":\"whitespace-signature\"}"}""" to "whitespace-signature",
            """{"thought_signature":{"_omitted":false,"value":"forged-signature"}}""" to "forged-signature",
        )
        assertAll(
            cases.mapIndexed { index, (body, forbidden) ->
                Executable {
                    runBlocking {
                        var sent = ""
                        val client = OkHttpClient.Builder().addInterceptor { chain ->
                            sent = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                            Response.Builder().request(
                                chain.request(),
                            ).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                                .body("{\"ok\":true}".toResponseBody()).build()
                        }.build()
                        val store = TranslationDiagnosticsStore(File(directory, "nested-$index"))
                        TranslationHttpTransport(
                            client,
                            store,
                            mockk(relaxed = true),
                            File(directory, "temporary"),
                        ).execute(
                            Request.Builder().url(
                                "https://example.com/v1/responses",
                            ).post(body.toRequestBody()).build(),
                            "job",
                            null,
                            "generateContent",
                            settings,
                            1,
                            30,
                            emptyList(),
                        )
                        assertEquals(body, sent)
                        val captured = File(store.directoryFor(store.list().single().id), "request.json").readText()
                        assertFalse(captured.contains(forbidden), "Capture must omit $forbidden")
                    }
                }
            },
        )
    }

    @Test
    fun `malformed oversized and binary bodies retain only an incomplete manifest`() = runBlocking {
        val fragment = "PRIVATE_RAW_FRAGMENT"
        val inputs = listOf(
            "{\"text\":\"$fragment",
            "{\"text\":\"$fragment " + "readable text ".repeat(25000) + "\"}",
            "\u0000$fragment",
        )
        inputs.forEachIndexed { index, body ->
            var sent = ""
            val client = OkHttpClient.Builder().addInterceptor { chain ->
                sent = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                    .body("{\"ok\":true}".toResponseBody()).build()
            }.build()
            val store = TranslationDiagnosticsStore(File(directory, "invalid-$index"))
            val result = TranslationHttpTransport(client, store, mockk(relaxed = true), File(directory, "temporary"))
                .execute(
                    Request.Builder().url("https://example.com/v1/responses").post(body.toRequestBody()).build(),
                    "job",
                    null,
                    "generateContent",
                    settings,
                    1,
                    30,
                    emptyList(),
                )
            assertEquals(body, sent)
            assertEquals("true", result.body["ok"]!!.jsonPrimitive.content)
            val metadata = store.list().single()
            assertEquals("TRUNCATED", metadata.state)
            assertEquals(false, metadata.requestSanitization?.complete)
            assertTrue(metadata.requestBodyCompleted)
            val saved = File(store.directoryFor(metadata.id), "request.json").readText()
            assertTrue(saved.contains("incomplete"))
            assertFalse(saved.contains(fragment))
            assertTrue(saved.length < 1024)
        }
    }

    @Test
    fun `interrupted request cannot leave raw text in capture or export`() = runBlocking {
        val body = object : RequestBody() {
            override fun contentType() = null
            override fun writeTo(sink: BufferedSink) {
                sink.writeUtf8("{\"text\":\"PRIVATE_INTERRUPTED_FRAGMENT")
                sink.flush()
                throw IOException("Synthetic interrupted upload")
            }
        }
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.request().body!!.writeTo(Buffer())
            error("The interrupted request must not complete")
        }.build()
        val store = TranslationDiagnosticsStore(File(directory, "interrupted"))
        val transport = TranslationHttpTransport(client, store, mockk(relaxed = true), File(directory, "temporary"))
        assertThrows(IOException::class.java) {
            runBlocking {
                transport.execute(
                    Request.Builder().url("https://example.com/v1/responses").post(body).build(),
                    "job",
                    null,
                    "generateContent",
                    settings,
                    1,
                    30,
                    emptyList(),
                )
            }
        }
        val metadata = store.list().single()
        assertFalse(metadata.requestBodyCompleted)
        assertEquals(false, metadata.requestSanitization?.complete)
        assertTrue(checkNotNull(metadata.requestSanitization).sourceBytes > 0)
        assertFalse(
            File(store.directoryFor(metadata.id), "request.json").readText().contains("PRIVATE_INTERRUPTED_FRAGMENT"),
        )
        val output = ByteArrayOutputStream()
        store.export(listOf(metadata.id), output)
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (zip.nextEntry != null) {
                assertFalse(zip.readBytes().decodeToString().contains("PRIVATE_INTERRUPTED_FRAGMENT"))
            }
        }
    }

    @Test
    fun `large image is transmitted unchanged while its capture stays small and complete`() = runBlocking {
        val requestBody = "{\"secret\":\"private-api-key\",\"image\":\"" + "a".repeat(2 * 1024 * 1024) + "\"}"
        var sent = ""
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            sent = Buffer().also { chain.request().body!!.writeTo(it) }.readUtf8()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("{\"echo\":\"private-api-key\"}".toResponseBody()).build()
        }.build()
        val captures = File(directory, "captures")
        val store = TranslationDiagnosticsStore(captures)
        val temporary = File(directory, "temporary")
        val transport = TranslationHttpTransport(client, store, mockk<TranslationRepository>(relaxed = true), temporary)
        val response = transport.execute(
            Request.Builder().url("https://example.com/v1/responses")
                .post(requestBody.toRequestBody()).build(),
            "job",
            "batch",
            "generateContent",
            settings,
            1,
            30,
            listOf("private-api-key"),
        )
        assertEquals(requestBody, sent)
        assertEquals("private-api-key", response.body["echo"]!!.jsonPrimitive.content)
        assertEquals(1, calls)
        assertTrue(captures.walkTopDown().filter(File::isFile).sumOf(File::length) <= limit)
        assertTrue(temporary.listFiles().orEmpty().isEmpty())
        val metadata = store.list().single()
        assertEquals("COMPLETED", metadata.state)
        assertEquals(requestBody.toByteArray().size.toLong(), metadata.requestBytes)
        assertFalse(metadata.requestTruncated)
        assertTrue(metadata.requestCapturedBytes in 1 until metadata.requestBytes)
        assertTrue(metadata.requestBodyCompleted)
        assertTrue(metadata.responseBodyCompleted)
        val saved = File(store.directoryFor(metadata.id), "request.json").readText()
        assertEquals(metadata.requestCapturedBytes, saved.toByteArray().size.toLong())
        assertFalse(saved.contains("private-api-key"))
        val exported = ByteArrayOutputStream()
        store.export(listOf(metadata.id), exported)
        ZipInputStream(ByteArrayInputStream(exported.toByteArray())).use { zip ->
            assertTrue(zip.nextEntry.name.endsWith("metadata.json"))
            assertTrue(zip.readBytes().decodeToString().contains("sanitized-v1"))
        }
        assertEquals("COMPLETED", TranslationDiagnosticsStore(captures).list().single().state)
    }

    @Test
    fun `concurrent captures count all active bytes against the shared storage ceiling`() = runBlocking {
        val store = TranslationDiagnosticsStore(directory)
        val sessions = (1..2).map { store.start("job-$it", null, "generateContent", settings) }
        sessions.map { session ->
            async(Dispatchers.IO) {
                store.openBody(session, CaptureBody.REQUEST).use { output ->
                    repeat(256) { output.write(ByteArray(8192)) }
                }
                store.bodyFinished(session, CaptureBody.REQUEST, true)
            }
        }.awaitAll()
        assertTrue(directory.walkTopDown().filter(File::isFile).sumOf(File::length) <= limit)
        sessions.forEach { store.finish(it, it.metadata.copy(completedAt = System.currentTimeMillis())) }
        assertEquals(2, store.list().size)
        assertTrue(store.list().all { it.state == "TRUNCATED" })
        assertTrue(directory.walkTopDown().filter(File::isFile).sumOf(File::length) <= limit)
    }

    @Test
    fun `secret crossing the last available capture bytes remains redacted`() = runBlocking {
        val store = TranslationDiagnosticsStore(directory)
        val session = store.start("job", null, "generateContent", settings)
        val secret = "secret-at-the-budget-boundary"
        val prefix = "a".repeat((limit - 32 * 1024 - 3).toInt())
        store.openBody(session, CaptureBody.REQUEST).use { output ->
            val redactor = SecretRedactingOutputStream(output, listOf(secret))
            redactor.write((prefix + secret.take(5)).toByteArray())
            redactor.write((secret.drop(5) + "tail").toByteArray())
            redactor.finish(true)
        }
        store.finish(session, session.metadata.copy(completedAt = System.currentTimeMillis()))
        val saved = session.requestFile.readText()
        assertEquals(prefix + "[re", saved)
        assertFalse(saved.contains(secret.take(5)))
        assertTrue(store.list().single().requestTruncated)
    }

    @Test
    fun `old completed captures are reclaimed before truncating the active body`() = runBlocking {
        val store = TranslationDiagnosticsStore(directory)
        val old = store.start("old", null, "generateContent", settings)
        store.openBody(old, CaptureBody.REQUEST).use { it.write(ByteArray(700 * 1024)) }
        store.finish(old, old.metadata.copy(completedAt = System.currentTimeMillis()))
        val current = store.start("current", null, "generateContent", settings)
        store.openBody(current, CaptureBody.REQUEST).use { it.write(ByteArray(700 * 1024)) }
        store.finish(current, current.metadata.copy(completedAt = System.currentTimeMillis()))
        assertEquals("current", store.list().single().jobId)
        assertFalse(current.metadata.requestTruncated)
        assertFalse(old.directory.exists())
    }

    @Test
    fun `diagnostic body disk error does not affect the outgoing body or provider result`() = runBlocking {
        val store = TranslationDiagnosticsStore(File(directory, "captures"))
        val session = store.start("job", null, "generateContent", settings)
        assertTrue(session.requestFile.mkdirs()) // Simulate an unwritable capture destination.
        val redactor = SecretRedactingOutputStream(store.openBody(session, CaptureBody.REQUEST), emptyList())
        val captured = CapturingRequestBody("complete network bytes".toRequestBody(), redactor) {
            store.bodyFinished(session, CaptureBody.REQUEST, it)
        }
        val network = Buffer()
        captured.writeTo(network)
        assertEquals("complete network bytes", network.readUtf8())
        store.finish(session, session.metadata.copy(completedAt = System.currentTimeMillis()))
        assertEquals("TRUNCATED", store.list().single().state)
        assertTrue(store.list().single().captureNotes.any { it.contains("storage error") })
    }

    @Test
    fun `response without content length cannot exceed the parsing spool limit`() {
        val input = ByteArrayInputStream(ByteArray(2048) { 7 })
        val spool = ByteArrayOutputStream()
        var read = 0L
        val failure = assertThrows(TranslationException::class.java) {
            copyBoundedProviderResponse(input, spool, null, 1024) { read = it }
        }
        assertEquals(TranslationFailureKind.LIMIT, failure.kind)
        assertEquals(1024, spool.size())
        assertEquals(1025, read)
        assertEquals(1023, input.available())
    }

    @Test
    fun `interrupted secret prefix is withheld instead of leaking to a partial capture`() {
        val output = ByteArrayOutputStream()
        val redactor = SecretRedactingOutputStream(output, listOf("credential-secret"))
        redactor.write("before credential-se".toByteArray())
        redactor.finish(false)
        assertFalse(output.toString(Charsets.UTF_8).contains("credential"))
    }
}
