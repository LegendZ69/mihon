package mihon.feature.translation.transfer

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.translation.model.OcrPageResult
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.QualityReviewAttempt
import tachiyomi.domain.translation.model.QualityReviewCheckpoint
import tachiyomi.domain.translation.model.QualityReviewSettings
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TextRegion
import tachiyomi.domain.translation.model.TranslationArchiveChapter
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationPoint
import tachiyomi.domain.translation.model.TranslationPromptPair
import tachiyomi.domain.translation.model.TranslationPrompts
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationUsage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class TranslationBackupCodecTest {
    @TempDir lateinit var directory: File

    @Test
    fun `review snapshots preserve prompts and exclude credentials and executable work`() = runBlocking {
        val image = TranslationImage("page", 0, "", "image/png", 100, 80, "a".repeat(64), 42)
        val result = TranslationPageResult("page", image.contentHash, 100, 80, emptyList(), revision = 2)
        val prompts = TranslationPrompts(
            qualityReview = TranslationPromptPair("My review", "My user {{target_language}}"),
        )
        val execution = TranslationSettings(
            provider = ProviderSettings(
                credentialId = "private-credential-ref",
                extraHeaders = mapOf("X-Private" to "private-header"),
            ),
            prompts = prompts,
            autoTranslate = true,
            chaptersAhead = 5,
        )
        val job = TranslationJob(
            "job",
            1,
            2,
            "Series",
            "Chapter",
            TranslationSettings(prompts = prompts),
            imageCount = 1,
        )
        val review = QualityReviewCheckpoint(
            "review",
            "job",
            "page",
            2,
            result,
            QualityReviewSettings(),
            prompts = prompts.qualityReview,
            executionSettings = execution,
        )
        val output = ByteArrayOutputStream()
        val codec = TranslationBackupCodec(directory)
        codec.write(flowOf(TranslationArchiveChapter(job, listOf(image), listOf(result), listOf(review))), output)
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (zip.nextEntry != null) {
                val text = zip.readBytes().decodeToString()
                assertFalse(text.contains("private-credential-ref"))
                assertFalse(text.contains("private-header"))
            }
        }
        codec.read(ByteArrayInputStream(output.toByteArray())).use { archive ->
            val saved = archive.chapters().single().reviews.single()
            assertEquals(QualityReviewState.INCOMPLETE, saved.state)
            assertEquals(prompts.qualityReview, saved.prompts)
            assertEquals(prompts, saved.executionSettings!!.prompts)
            assertEquals("", saved.executionSettings!!.provider.credentialId)
            assertEquals(emptyMap<String, String>(), saved.executionSettings!!.provider.extraHeaders)
            assertFalse(saved.executionSettings!!.autoTranslate)
            assertEquals(0, saved.executionSettings!!.chaptersAhead)
        }
    }

    @Test
    fun `backup keeps replacement lineage historical without creating an operational successor`() = runBlocking {
        val job =
            TranslationJob("successor", 1, 2, "Series", "Chapter", TranslationSettings(), replacesJobId = "live-source")
        val output = ByteArrayOutputStream()
        val codec = TranslationBackupCodec(directory)
        codec.write(flowOf(TranslationArchiveChapter(job, emptyList(), emptyList())), output)
        codec.read(ByteArrayInputStream(output.toByteArray())).use { archive ->
            val restored = archive.chapters().single()
            assertNull(restored.job.replacesJobId)
            assertEquals("live-source", restored.replacesSourceJobId)
        }
    }

    @Test
    fun `font restoration uses verified private paths and reuses existing content`() = runBlocking {
        val font = File(directory, "source.otf").apply { writeBytes("OTTO".toByteArray() + ByteArray(32)) }
        val job =
            TranslationJob(
                "font-restore",
                1,
                2,
                "Series",
                "Chapter",
                TranslationSettings(style = tachiyomi.domain.translation.model.OverlayStyle(fontPath = font.path)),
            )
        val codec = TranslationBackupCodec(File(directory, "staging"))
        val output = ByteArrayOutputStream()
        codec.write(flowOf(TranslationArchiveChapter(job, emptyList(), emptyList())), output)
        codec.read(ByteArrayInputStream(output.toByteArray())).use { archive ->
            val store = TranslationArchiveFontStore(File(directory, "owned-fonts"))
            val mapping = store.restore(archive)
            val relative = archive.manifest.entries.single { it.kind == "font" }.name
            org.junit.jupiter.api.Assertions.assertTrue(mapping.containsKey(relative))
            val restored = File(mapping.getValue(relative))
            assertEquals(font.readBytes().toList(), restored.readBytes().toList())
            assertEquals(File(directory, "owned-fonts").canonicalFile, restored.parentFile.canonicalFile)
            assertEquals(mapping, store.restore(archive))
            assertEquals(1, File(directory, "owned-fonts").listFiles()!!.size)
        }
    }

    @Test
    fun `unresolved portable font references are reported instead of promising an absent attachment`() = runBlocking {
        val path = "fonts/${"b".repeat(64)}.font"
        val job =
            TranslationJob(
                "missing-font",
                1,
                2,
                "Series",
                "Chapter",
                TranslationSettings(style = tachiyomi.domain.translation.model.OverlayStyle(fontPath = path)),
            )
        val output = ByteArrayOutputStream()
        val codec = TranslationBackupCodec(directory)
        val manifest = codec.write(flowOf(TranslationArchiveChapter(job, emptyList(), emptyList())), output)
        org.junit.jupiter.api.Assertions.assertTrue(manifest.warnings.isNotEmpty())
        codec.read(ByteArrayInputStream(output.toByteArray())).use {
            assertNull(it.chapters().single().job.settings.style.fontPath)
        }
    }

    @Test
    fun `cancelled archive extraction removes staged files before returning`() = runBlocking {
        val codec = TranslationBackupCodec(directory)
        val job = TranslationJob("cancelled", 1, 2, "Series", "Chapter", TranslationSettings())
        val output = ByteArrayOutputStream()
        codec.write(flowOf(TranslationArchiveChapter(job, emptyList(), emptyList())), output)
        val cancelled = kotlinx.coroutines.Job()
        val source = object : java.io.FilterInputStream(ByteArrayInputStream(output.toByteArray())) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                val count = super.read(bytes, offset, length)
                cancelled.cancel()
                return count
            }
        }
        val failure = runCatching { kotlinx.coroutines.withContext(cancelled) { codec.read(source) } }.exceptionOrNull()
        org.junit.jupiter.api.Assertions.assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertEquals(
            emptyList<String>(),
            directory.listFiles().orEmpty().map {
                it.name
            },
            "Cancelled extraction leaked its verified staging directory",
        )
    }

    @Test
    fun `font attachments are content addressed once and retain portable historical styles`() = runBlocking {
        val font = File(directory, "private-font.otf").apply { writeBytes("OTTO".toByteArray() + ByteArray(32)) }
        val style = tachiyomi.domain.translation.model.OverlayStyle(fontPath = font.absolutePath, fontSize = 25f)
        val job = TranslationJob("font-job", 1, 2, "Series", "Chapter", TranslationSettings(style = style))
        val output = ByteArrayOutputStream()
        val codec = TranslationBackupCodec(File(directory, "staging"))
        val manifest = codec.write(
            flowOf(TranslationArchiveChapter(job, emptyList(), emptyList(), effectiveSettings = job.settings)),
            output,
        )
        val expected = "fonts/${TranslationBackupCodec.sha256(font)}.font"
        assertEquals(listOf(expected), manifest.entries.filter { it.kind == "font" }.map { it.name })
        codec.read(ByteArrayInputStream(output.toByteArray())).use { archive ->
            val chapter = archive.chapters().single()
            assertEquals(expected, chapter.job.settings.style.fontPath)
            assertEquals(expected, chapter.effectiveSettings!!.style.fontPath)
            assertEquals(font.readBytes().toList(), File(archive.directory, expected).readBytes().toList())
            assertFalse(
                File(
                    archive.directory,
                    manifest.entries.single {
                        it.kind == "chapter"
                    }.name,
                ).readText().contains(font.absolutePath),
            )
        }
    }

    @Test
    fun `current and prior settings retain styles and glossary but exclude credentials and automation`() =
        runBlocking {
            val effective = TranslationSettings(
                provider = ProviderSettings(
                    credentialId = "effective-secret-reference",
                    baseUrl = "https://user:secret-password@example.com/v1?key=secret-query",
                    advancedJson = """{"api_key":"advanced-secret","temperature":0.2}""",
                ),
                glossary = "Mina is a name",
                style = tachiyomi.domain.translation.model.OverlayStyle(
                    fontSize = 31f,
                    fontPath = "/private/font-file.font",
                ),
                autoTranslate = true,
                chaptersAhead = 2,
            )
            val job = TranslationJob(
                "portable-settings",
                1,
                2,
                "Series",
                "Chapter",
                TranslationSettings(),
                archiveMetadata = tachiyomi.domain.translation.model.TranslationArchiveProvenance(
                    "prior",
                    emptyMap(),
                    1,
                    effective,
                ),
            )
            val codec = TranslationBackupCodec(directory)
            val output = ByteArrayOutputStream()
            codec.write(
                flowOf(TranslationArchiveChapter(job, emptyList(), emptyList(), effectiveSettings = effective)),
                output,
            )
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                while (zip.nextEntry != null) {
                    val text = zip.readBytes().decodeToString()
                    listOf(
                        "effective-secret-reference",
                        "secret-password",
                        "secret-query",
                        "advanced-secret",
                        "/private/font-file.font",
                    ).forEach { secret ->
                        assertFalse(text.contains(secret), "Backup exported private settings: $secret")
                    }
                }
            }
            codec.read(ByteArrayInputStream(output.toByteArray())).use { staged ->
                val chapter = staged.chapters().single()
                listOf(
                    chapter.effectiveSettings!!,
                    chapter.job.archiveMetadata!!.effectiveSettings!!,
                ).forEach { settings ->
                    assertEquals("", settings.provider.credentialId)
                    assertEquals("Mina is a name", settings.glossary)
                    assertEquals(31f, settings.style.fontSize)
                    assertFalse(settings.autoTranslate)
                    assertEquals(0, settings.chaptersAhead)
                }
            }
        }

    @Test
    fun `backup rejects an Undo baseline belonging to a different original`() {
        val image = TranslationImage("page", 0, "", "image/png", 100, 80, "a".repeat(64), 42)
        val result = TranslationPageResult("page", image.contentHash, 100, 80, emptyList(), revision = 2)
        val job = TranslationJob("job", 1, 2, "Series", "Chapter", TranslationSettings(), imageCount = 1)
        val review = QualityReviewCheckpoint(
            "review",
            "job",
            "page",
            1,
            result.copy(imageHash = "b".repeat(64), revision = 1),
            QualityReviewSettings(),
            state = QualityReviewState.REPAIRED,
            repairedRevision = 2,
        )
        val output = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                TranslationBackupCodec(
                    directory,
                ).write(flowOf(TranslationArchiveChapter(job, listOf(image), listOf(result), listOf(review))), output)
            }
        }
        assertEquals(0, output.size())
    }

    @Test
    fun `structured ZIP preserves OCR corrections revisions and Undo while excluding credentials and queued work`() =
        runBlocking {
            val region = TextRegion(
                "region-1",
                listOf(
                    TranslationPoint(0f, 0f),
                    TranslationPoint(100f, 0f),
                    TranslationPoint(100f, 100f),
                    TranslationPoint(0f, 100f),
                ),
                "raw source",
                "Keep the door closed",
                correctedText = "corrected source",
                recognitionConfidence = 0.9f,
            )
            val image =
                TranslationImage("page-1", 0, "/private/source.png", "image/png", 800, 1200, "a".repeat(64), 123)
            val result = TranslationPageResult(
                "page-1",
                image.contentHash,
                800,
                1200,
                listOf(region),
                rawOcr = OcrPageResult("page-1", listOf(region.copy(correctedText = null, translatedText = ""))),
                revision = 42,
            )
            val before = result.copy(
                regions = listOf(region.copy(translatedText = "Leave the door open")),
                revision = 41,
            )
            val job = TranslationJob(
                "job-1", 3, 20, "Series", "Chapter",
                TranslationSettings(
                    provider = ProviderSettings(
                        credentialId = "private-key-reference",
                        extraHeaders = mapOf("Authorization" to "Bearer secret"),
                        baseUrl = "https://user:pass@example.com/v1?api_key=secret",
                    ),
                    autoTranslate = true,
                    chaptersAhead = 5,
                ),
                state = TranslationJobState.TRANSLATING, imageCount = 2, completedImages = 1,
                reviewReturnState = TranslationJobState.TRANSLATING, reviewImageIds = listOf("page-1"),
            )
            val review = QualityReviewCheckpoint(
                "review-1", job.id, image.id, 41, before, QualityReviewSettings(),
                state = QualityReviewState.REPAIRED, repairedRevision = 42,
                attempts = listOf(QualityReviewAttempt(1, 10, 20, usage = TranslationUsage(inputTokens = 73))),
            )
            val codec = TranslationBackupCodec(directory)
            val output = ByteArrayOutputStream()
            codec.write(flowOf(TranslationArchiveChapter(job, listOf(image), listOf(result), listOf(review))), output)
            ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
                assertEquals("manifest.json", zip.nextEntry?.name)
                val manifest = zip.readBytes().decodeToString()
                assertFalse(manifest.contains("private-key-reference"))
            }
            codec.read(ByteArrayInputStream(output.toByteArray())).use { archive ->
                val restored = archive.chapters().single()
                assertEquals(result, restored.results.single())
                assertEquals(before, restored.reviews.single().beforeResult)
                assertEquals(review.attempts, restored.reviews.single().attempts)
                assertEquals(42L, restored.reviews.single().repairedRevision)
                assertEquals("", restored.job.settings.provider.credentialId)
                assertEquals(emptyMap<String, String>(), restored.job.settings.provider.extraHeaders)
                assertEquals("https://example.com/v1", restored.job.settings.provider.baseUrl)
                assertEquals("", restored.images.single().filePath)
                assertEquals(TranslationJobState.PAUSED, restored.job.state)
                assertFalse(restored.job.settings.autoTranslate)
                assertEquals(0, restored.job.settings.chaptersAhead)
                assertNull(restored.job.reviewReturnState)
                assertNull(restored.job.reviewImageIds)
            }
        }
}
