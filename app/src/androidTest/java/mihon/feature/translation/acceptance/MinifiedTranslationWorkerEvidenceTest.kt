package mihon.feature.translation.acceptance

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import eu.kanade.tachiyomi.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mihon.app.di.appGraph
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationLogQuery
import tachiyomi.domain.translation.model.TranslationPageResult
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Separately invoked collection and receipt-gated cleanup of one terminal synthetic Worker fixture. */
@RunWith(AndroidJUnit4::class)
class MinifiedTranslationWorkerEvidenceTest {
    @Test
    fun collectOrCleanupWorkerFixture() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("translation.workerEvidence") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("app.mihon.benchmark", context.packageName)
        assertTrue(BuildConfig.TRANSLATION_LOCAL_FIXTURES_ENABLED)
        assertFalse(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val runId = requireNotNull(arguments.getString("translation.workerRunId"))
        require(UUID.fromString(runId).toString() == runId)
        val phase = requireNotNull(arguments.getString("translation.workerEvidencePhase"))
        require(phase in setOf("collect", "cleanup"))
        withContext(Dispatchers.IO) {
            val root = File(context.noBackupFilesDir, "translation-worker-acceptance/$runId")
            val inspection = inspect(context, root, runId)
            val jobId = "worker-fixture-$runId"
            val telemetry = telemetry(context, jobId)
            val entries = inspection.files.map { Entry(it.name, it.length(), hash(it), it) } +
                Entry("telemetry.json", telemetry.size.toLong(), hash(telemetry), bytes = telemetry)
            require(entries.sumOf { it.size } <= MAX_TOTAL_BYTES)
            val manifest = JSONObject().put("schema", 1).put("run_id", runId).put("package", context.packageName)
                .put("mode", inspection.report.getString("mode"))
                .put("first_committed_page", inspection.firstCommitted)
                .put("ordering_scope", "First committed result; not necessarily source image index zero")
                .put("queue_background_events_preserved", true)
                .put(
                    "files",
                    JSONArray(
                        entries.map {
                            JSONObject().put("name", it.name).put("bytes", it.size).put("sha256", it.sha256)
                        },
                    ),
                )
                .toString(2).toByteArray(Charsets.UTF_8)
            val archive = File.createTempFile("worker-evidence-$runId-", ".zip", context.cacheDir)
            try {
                ZipOutputStream(archive.outputStream().buffered()).use { zip ->
                    entries.forEach { entry ->
                        zip.putNextEntry(ZipEntry(entry.name).apply { time = 0 })
                        val digest = MessageDigest.getInstance("SHA-256")
                        var count = 0L
                        val input = entry.file?.inputStream() ?: requireNotNull(entry.bytes).inputStream()
                        input.use { source ->
                            val buffer = ByteArray(32 * 1024)
                            while (true) {
                                val read = source.read(buffer)
                                if (read < 0) break
                                count += read
                                require(count <= entry.size) { "Fixture changed during collection" }
                                digest.update(buffer, 0, read)
                                zip.write(buffer, 0, read)
                            }
                        }
                        require(count == entry.size && hex(digest.digest()) == entry.sha256) {
                            "Fixture changed during collection"
                        }
                        zip.closeEntry()
                    }
                    zip.putNextEntry(ZipEntry("manifest.json").apply { time = 0 })
                    zip.write(manifest)
                    zip.closeEntry()
                }
                require(archive.length() <= MAX_TOTAL_BYTES + 64 * 1024)
                val digest = hash(archive)
                if (phase == "collect") {
                    val chunks = (archive.length() + CHUNK_BYTES - 1) / CHUNK_BYTES
                    status(
                        JSONObject().put("phase", "collect").put("run_id", runId).put("sha256", digest)
                            .put(
                                "bytes",
                                archive.length(),
                            ).put("chunks", chunks).put("manifest", JSONObject(String(manifest, Charsets.UTF_8))),
                    )
                    archive.inputStream().use { input ->
                        val buffer = ByteArray(CHUNK_BYTES)
                        var index = 0
                        while (true) {
                            var count = 0
                            while (count < buffer.size) {
                                val read = input.read(buffer, count, buffer.size - count)
                                if (read < 0) break
                                count += read
                            }
                            if (count == 0) break
                            InstrumentationRegistry.getInstrumentation().sendStatus(
                                0,
                                Bundle().apply {
                                    putString("translation_worker_evidence_run", runId)
                                    putInt("translation_worker_evidence_chunk_index", index++)
                                    putString(
                                        "translation_worker_evidence_chunk",
                                        Base64.encodeToString(buffer, 0, count, Base64.NO_WRAP),
                                    )
                                },
                            )
                        }
                        require(index.toLong() == chunks)
                    }
                    status(
                        JSONObject().put(
                            "phase",
                            "collect_complete",
                        ).put("run_id", runId).put("sha256", digest).put("bytes", archive.length()),
                    )
                } else {
                    val receipt = requireNotNull(arguments.getString("translation.workerEvidenceVerifiedSha256"))
                    require(receipt.matches(Regex("[0-9a-f]{64}")) && receipt == digest) {
                        "Host-verified archive digest does not match; nothing was deleted"
                    }
                    val report = inspection.report
                    require(report.getString("status") == "passed" && !report.has("failure_class")) {
                        "Failed runs require separate investigation; nothing was deleted"
                    }
                    require(
                        report.getBoolean("credential_removed") && report.getBoolean("owned_job_removed") &&
                            report.getBoolean("settings_unchanged"),
                    )
                    val graph = context.appGraph
                    require(
                        graph.translationRepository.jobs().none {
                            it.id == jobId
                        },
                    ) { "Owned job still exists; nothing was deleted" }
                    require(graph.translationCredentialVault.info(jobId) == null) {
                        "Owned credential still exists; nothing was deleted"
                    }
                    require(telemetry.contentEquals(telemetry(context, jobId))) {
                        "Owned telemetry changed after collection; nothing was deleted"
                    }
                    require(
                        inspect(context, root, runId).files.map { it.name to hash(it) } ==
                            inspection.files.map {
                                it.name to entries.single { entry -> entry.name == it.name }.sha256
                            },
                    )
                    // These APIs scope by the complete owned UUID, never by title, time or a shared queue parent.
                    graph.translationRepository.deleteJobEvents(jobId)
                    graph.translationRepository.deleteOperations(jobId)
                    graph.translationRepository.deleteUsage(jobId)
                    require(
                        graph.translationRepository.eventPage(TranslationLogQuery(jobId = jobId, limit = 1)).isEmpty(),
                    )
                    require(graph.translationRepository.operationPage(jobId, 1).isEmpty())
                    require(
                        graph.translationRepository.usagePage(0, Long.MAX_VALUE, limit = 1, jobId = jobId).isEmpty(),
                    )
                    inspection.files.filter { it.name != "report.json" }.forEach { check(it.delete()) }
                    check(File(root, "report.json").delete())
                    check(root.delete())
                    status(
                        JSONObject().put(
                            "phase",
                            "cleanup_complete",
                        ).put("run_id", runId).put("verified_sha256", digest)
                            .put("owned_root_removed", !root.exists()).put("owned_telemetry_removed", true)
                            .put("queue_background_events_preserved", true),
                    )
                }
            } finally {
                check(archive.delete() || !archive.exists())
            }
        }
    }

    private fun inspect(context: Context, root: File, runId: String): Inspection {
        val expected = File(File(context.noBackupFilesDir, "translation-worker-acceptance").canonicalFile, runId)
        require(root.isDirectory && root.canonicalFile == expected) {
            "Named owned fixture root is unavailable or redirected"
        }
        val files = requireNotNull(root.listFiles()).sortedBy { it.name }
        require(
            files.map {
                it.name
            }.toSet() == setOf("page-0.png", "page-1.png", "report.json"),
        ) {
            "Unexpected fixture files; nothing may be deleted"
        }
        files.forEach {
            require(it.isFile && it.canonicalFile == File(expected, it.name) && it.length() in 1..MAX_FILE_BYTES)
        }
        val reportFile = files.single { it.name == "report.json" }
        require(reportFile.length() <= MAX_JSON_BYTES)
        val report = JSONObject(reportFile.readText())
        require(
            report.getInt("schema") == 1 && report.getString("run_id") == runId &&
                report.getString("package") == context.packageName,
        )
        require(report.getString("status") in setOf("passed", "failed") && !report.getBoolean("cloud_forwarding"))
        require(
            report.getString("mode") in
                setOf(
                    "notification",
                    "notification-actions",
                    "notification-cancel",
                    "network",
                    "screen-off",
                    "active-resume",
                ),
        )
        val server = report.getJSONObject("fixture_server")
        require(server.getBoolean("fixture") && !server.getBoolean("cloud_forwarding"))
        val json = Json { ignoreUnknownKeys = true }
        val images = json.decodeFromString<List<TranslationImage>>(report.getJSONArray("images").toString())
        require(images.size == 2 && images.map { it.index }.toSet() == setOf(0, 1))
        images.forEach { image ->
            val file = File(root, "page-${image.index}.png")
            require(
                image.filePath == file.absolutePath && image.contentHash == hash(file) &&
                    image.byteSize == file.length(),
            )
            require(image.width == 960 && image.height == 320 && image.mimeType == "image/png")
        }
        val records = report.getJSONArray("records")
        require(records.length() in 1..100)
        val results = mutableListOf<List<TranslationPageResult>>()
        repeat(records.length()) { index ->
            val record = records.getJSONObject(index)
            val jobs = record.getJSONArray("jobs")
            repeat(jobs.length()) { jobIndex ->
                val job = json.decodeFromString<TranslationJob>(jobs.getJSONObject(jobIndex).toString())
                require(job.id == "worker-fixture-$runId")
                val provider = job.settings.provider
                require(provider.credentialId == "worker-fixture-$runId" && provider.model == "mihon-fixture")
                require(provider.baseUrl == "http://127.0.0.1:8765/v1")
                require(!job.settings.logs.captureRaw)
            }
            results += json.decodeFromString<List<TranslationPageResult>>(record.getJSONArray("results").toString())
        }
        val first = results.firstOrNull { it.size == 1 }?.single() ?: error("Missing first committed result evidence")
        val image = images.single { it.id == first.imageId }
        require(first.imageHash == image.contentHash && first.width == image.width && first.height == image.height)
        if (report.getString("status") ==
            "passed"
        ) {
            require(results.last().single { it.imageId == first.imageId } == first)
        }
        return Inspection(
            files,
            report,
            JSONObject().put("image_id", first.imageId).put("source_index", image.index)
                .put("image_sha256", image.contentHash).put("result_revision", first.revision),
        )
    }

    private suspend fun telemetry(context: Context, jobId: String): ByteArray {
        val repository = context.appGraph.translationRepository
        val operations = boundedPages { offset ->
            repository.operationPage(jobId, PAGE_SIZE, offset)
        }.sortedBy { it.id }
        val events = boundedPages { offset ->
            repository.eventPage(TranslationLogQuery(jobId = jobId, limit = PAGE_SIZE, offset = offset))
        }.sortedBy { it.id }
        val usage = boundedPages { offset ->
            repository.usagePage(0, Long.MAX_VALUE, limit = PAGE_SIZE, offset = offset, jobId = jobId)
        }.sortedBy { it.id }
        require(
            operations.all { it.jobId == jobId } && events.all { it.jobId == jobId && it.capturePath == null } &&
                usage.all { it.jobId == jobId },
        )
        require(
            operations.map { it.id }.distinct().size == operations.size &&
                events.map { it.id }.distinct().size == events.size &&
                usage.map { it.id }.distinct().size == usage.size,
        )
        val json = Json { encodeDefaults = true }
        return JSONObject().put("schema", 1).put("job_id", jobId).put("synthetic", true)
            .put("operations", JSONArray(json.encodeToString(operations)))
            .put("events", JSONArray(json.encodeToString(events)))
            .put("usage", JSONArray(json.encodeToString(usage))).toString(2).toByteArray(Charsets.UTF_8)
            .also {
                require(it.size <= MAX_JSON_BYTES) {
                    "Owned telemetry exceeds the bounded collector; nothing was deleted"
                }
            }
    }

    private suspend fun <T> boundedPages(fetch: suspend (Long) -> List<T>): List<T> {
        val result = mutableListOf<T>()
        var offset = 0L
        while (true) {
            val page = fetch(offset)
            require(page.size <= PAGE_SIZE)
            if (page.isEmpty()) return result
            require(result.size + page.size <= MAX_ROWS) {
                "Owned telemetry needs a larger separately reviewed collector"
            }
            result += page
            if (page.size < PAGE_SIZE) return result
            offset += page.size
        }
    }

    private fun status(value: JSONObject) = InstrumentationRegistry.getInstrumentation().sendStatus(
        0,
        Bundle().apply {
            putString("translation_worker_evidence_json", value.toString())
        },
    )
    private fun hash(file: File): String = MessageDigest.getInstance("SHA-256").let { digest ->
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count <
                    0
                ) {
                    break
                }
                digest.update(buffer, 0, count)
            }
        }
        hex(digest.digest())
    }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private data class Entry(
        val name: String,
        val size: Long,
        val sha256: String,
        val file: File? = null,
        val bytes: ByteArray? = null,
    )
    private data class Inspection(val files: List<File>, val report: JSONObject, val firstCommitted: JSONObject)

    companion object {
        private const val PAGE_SIZE = 100L
        private const val MAX_ROWS = 2_000
        private const val MAX_JSON_BYTES = 4 * 1024 * 1024
        private const val MAX_FILE_BYTES = 8L * 1024 * 1024
        private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024
        private const val CHUNK_BYTES = 24 * 1024
    }
}
