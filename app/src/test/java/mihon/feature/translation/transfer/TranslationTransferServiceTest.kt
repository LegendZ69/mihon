package mihon.feature.translation.transfer

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mihon.feature.translation.TranslationImageSource
import mihon.feature.translation.TranslationManager
import mihon.feature.translation.TranslationPreferences
import mihon.feature.translation.provider.TranslationDiagnosticsStore
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.model.GeometryCorrectionCheckpoint
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.TranslationArchiveChapter
import tachiyomi.domain.translation.model.TranslationArchiveLink
import tachiyomi.domain.translation.model.TranslationBackupOptions
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationInputTransform
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationRestoreReport
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.TranslationArchiveRepository
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class TranslationTransferServiceTest {
    @TempDir lateinit var directory: File

    @Test
    fun `restoring history drops checkpoint before decode and keeps operation facts`() = runBlocking {
        val fixture = Fixture()
        val image = fixture.source.images.single()
        val checkpoint = GeometryCorrectionCheckpoint(
            "correction", fixture.source.job.id, "policy", image,
            TranslationInputTransform(
                image,
            ),
            TranslationSettings(),
            emptyList(), "private context", "private candidate", emptyList(),
        )
        val operation = TranslationOperation(
            "imported-geometry",
            fixture.source.job.id,
            TranslationStage.GEOMETRY_CORRECTION,
            imageId = image.id,
            completed = 1,
            total = 2,
            state = TranslationOperationState.ACTIVE,
            geometryCorrection = checkpoint,
        )
        val attachment = File(directory, "incoming-history.json").apply {
            writeText(
                Json {
                    encodeDefaults = true
                }.encodeToString(TranslationArchiveHistory(operations = listOf(operation))),
            )
        }
        val output = ByteArrayOutputStream()
        val codec = TranslationBackupCodec(File(directory, "incoming-stage"))
        codec.write(
            flowOf(fixture.source),
            output,
            listOf(TranslationArchiveAttachment("diagnostics/history.json", "logs", attachment)),
        )
        codec.read(ByteArrayInputStream(output.toByteArray())).use { archive ->
            val report = fixture.service.restore(archive)
            assertEquals(
                1,
                fixture.importedHistory.size,
                "Valid operation facts must survive checkpoint omission; warnings: ${report.warnings}",
            )
        }
        val restored = fixture.importedHistory.single()
        assertEquals("linked-target", restored.jobId)
        assertEquals(TranslationStage.GEOMETRY_CORRECTION, restored.stage)
        assertEquals(1L, restored.completed)
        assertEquals(2L, restored.total)
        assertEquals(TranslationOperationState.INTERRUPTED, restored.state)
        assertEquals(null, restored.geometryCorrection)
    }

    @Test
    fun `structured ZIP keeps operation facts and excludes resumable geometry inputs`() = runBlocking {
        val fixture = Fixture()
        val image = fixture.source.images.single()
        val checkpoint = GeometryCorrectionCheckpoint(
            "correction", fixture.source.job.id, "policy", image,
            TranslationInputTransform(
                image,
            ),
            TranslationSettings(),
            emptyList(), "private correction context", "private correction candidate", emptyList(),
        )
        val operation = TranslationOperation(
            "geometry-operation",
            fixture.source.job.id,
            TranslationStage.GEOMETRY_CORRECTION,
            imageId = image.id,
            completed = 1,
            total = 2,
            state = TranslationOperationState.ACTIVE,
            geometryCorrection = checkpoint,
        )
        coEvery { fixture.repository.eventPage(any()) } returns emptyList()
        every { fixture.repository.observeOperations(fixture.source.job.id, 200, 0) } returns flowOf(listOf(operation))
        every { fixture.repository.observeOperations(fixture.source.job.id, 200, 1) } returns flowOf(emptyList())
        val output = ByteArrayOutputStream()
        fixture.service.backup(setOf(fixture.source.job.id), output, TranslationBackupOptions(includeLogs = true))
        val entries = mutableMapOf<String, String>()
        ZipInputStream(ByteArrayInputStream(output.toByteArray())).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes().decodeToString()
            }
        }
        val exported = entries.filterKeys { it.startsWith("diagnostics/operations-") }.values.single()
        val facts = Json.parseToJsonElement(exported).jsonObject.getValue("operations").jsonArray.single().jsonObject
        assertFalse("geometryCorrection" in facts)
        assertFalse(
            entries.values.any {
                it.contains("private correction candidate") ||
                    it.contains("private correction context")
            },
        )
        assertEquals("geometry-operation", facts.getValue("id").jsonPrimitive.content)
        assertEquals("1", facts.getValue("completed").jsonPrimitive.content)
        assertEquals("2", facts.getValue("total").jsonPrimitive.content)
        assertEquals(checkpoint, operation.geometryCorrection)
    }

    @Test
    fun `backing up an unlinked archive retains its recorded appearance and glossary`() = runBlocking {
        val fixture = Fixture()
        val output = ByteArrayOutputStream()
        fixture.service.backup(setOf(fixture.source.job.id), output)
        TranslationBackupCodec(
            File(directory, "verify"),
        ).read(ByteArrayInputStream(output.toByteArray())).use { archive ->
            val settings = archive.chapters().single().effectiveSettings!!
            assertEquals("Archived glossary", settings.glossary)
            assertEquals(37f, settings.style.fontSize)
        }
    }

    @Test
    fun `committed restoration leaves a related receipt in target job logs`() = runBlocking {
        val fixture = Fixture()
        val output = ByteArrayOutputStream()
        val codec = TranslationBackupCodec(File(directory, "verify"))
        codec.write(kotlinx.coroutines.flow.flowOf(fixture.source), output)
        codec.read(ByteArrayInputStream(output.toByteArray())).use { archive ->
            val report = fixture.service.restore(archive)
            assertEquals(listOf("linked-target"), report.jobIds)
            assertTrue(
                fixture.operations.any {
                    it.jobId == "linked-target" && it.stage == TranslationStage.RESTORE &&
                        it.parentId != null
                },
            )
        }
    }

    @Test
    fun `saved relink only acquires originals and rejects a concurrent source revision`() = runBlocking {
        val fixture = Fixture()
        var snapshots = 0
        coEvery { fixture.archives.snapshot(fixture.source.job.id) } answers {
            snapshots++
            if (snapshots ==
                1
            ) {
                fixture.source
            } else {
                fixture.source.copy(
                    results = fixture.source.results.map {
                        it.copy(
                            revision =
                            it.revision + 1,
                        )
                    },
                )
            }
        }
        val failure = runCatching { fixture.service.relinkSaved(fixture.source.job.id, 3, 4) }.exceptionOrNull()
        assertTrue(
            failure is IllegalArgumentException && failure.message!!.contains("changed"),
            "Relink must reject a changed saved revision; got $failure",
        )
        coVerify(exactly = 1) { fixture.images.acquire(any()) }
        coVerify(exactly = 0) { fixture.archives.restore(any(), any(), any()) }
        assertTrue(File(directory, "translation/transfer").walkTopDown().none { it.isFile && it.extension == "zip" })
    }

    private inner class Fixture {
        val archives = mockk<TranslationArchiveRepository>()
        val repository = mockk<TranslationRepository>(relaxed = true)
        private val preferences = mockk<TranslationPreferences>()
        private val manager = mockk<TranslationManager>()
        val images = mockk<TranslationImageSource>()
        private val getManga = mockk<GetManga>()
        private val getChapter = mockk<GetChapter>()
        val operations = mutableListOf<TranslationOperation>()
        val importedHistory = mutableListOf<TranslationOperation>()
        private val original = File(directory, "source.fixture").apply { writeText("fixed original identity") }
        private val image =
            TranslationImage(
                "0",
                0,
                "",
                "image/png",
                100,
                80,
                TranslationBackupCodec.sha256(original),
                original.length(),
            )
        val source = TranslationArchiveChapter(
            TranslationJob(
                "unlinked", -1, -1, "Archived series", "Archived chapter", TranslationSettings(),
                state = TranslationJobState.COMPLETED, imageCount = 1, completedImages = 1,
            ),
            listOf(image),
            listOf(TranslationPageResult("0", image.contentHash, 100, 80, emptyList(), revision = 7)),
            effectiveSettings = TranslationSettings(
                glossary = "Archived glossary",
                style = OverlayStyle(fontSize = 37f),
            ),
        )
        val service: TranslationTransferService

        init {
            val context = mockk<Context>()
            every { context.noBackupFilesDir } returns directory
            val global =
                TranslationSettings(glossary = "Unrelated global glossary", style = OverlayStyle(fontSize = 14f))
            every { preferences.settings } returns MutableStateFlow(global)
            every { preferences.effectiveSettings(any()) } returns global
            coEvery { archives.snapshot(source.job.id) } returns source
            coEvery { archives.snapshot("linked-target") } returns
                source.copy(job = source.job.copy(id = "linked-target"))
            coEvery { archives.importHistory(any(), any()) } coAnswers
                { importedHistory += secondArg<List<TranslationOperation>>() }
            coEvery { archives.restore(any(), any(), any()) } returns
                TranslationRestoreReport(1, 0, 0, 1, 0, listOf("linked-target"))
            coEvery { repository.jobs() } returns listOf(source.job)
            coEvery { repository.saveOperation(any()) } coAnswers { operations += firstArg<TranslationOperation>() }
            coEvery { manager.checkpointForManagement(any()) } returns mockk(relaxed = true)
            coEvery { manager.withManagementCheckpoint<TranslationRestoreReport>(any(), any()) } coAnswers {
                secondArg<suspend () -> TranslationRestoreReport>().invoke()
            }
            coEvery { getManga.await(3) } returns Manga.create().copy(id = 3, title = "Selected series")
            coEvery { getChapter.await(4) } returns
                Chapter.create().copy(id = 4, mangaId = 3, name = "Selected chapter")
            coEvery { images.acquire(any()) } coAnswers {
                val job = firstArg<TranslationJob>()
                val folder = File(directory, "translation/images/${job.id}").apply { mkdirs() }
                val file = File(folder, "0.image")
                original.copyTo(file)
                listOf(image.copy(filePath = file.path))
            }
            service = TranslationTransferService(
                context, archives, repository, preferences,
                TranslationDiagnosticsStore(
                    File(directory, "diagnostics"),
                ),
                mockk(relaxed = true), manager, images, getManga, getChapter,
            )
        }
    }
}
