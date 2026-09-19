package mihon.feature.translation.transfer

import android.content.Context
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import mihon.feature.translation.TranslationImageSource
import mihon.feature.translation.TranslationManager
import mihon.feature.translation.TranslationPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.chapter.model.Chapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.domain.translation.model.StructuredImportPlan
import tachiyomi.domain.translation.model.TranslationArchiveChapter
import tachiyomi.domain.translation.model.TranslationArchiveLink
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationRestoreReport
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.TranslationArchiveRepository
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.util.concurrent.CancellationException

class TranslationStructuredImportServiceTest {
    @TempDir lateinit var directory: File

    @Test
    fun `importing a valid subset with automatic settings enabled schedules no provider work`() = runBlocking {
        val fixture = Fixture()
        fixture.service.prepareTarget(3, 4).use { target ->
            val plan = fixture.plan(target, includeInvalid = true)
            assertEquals(2, plan.pages.size)
            val report = fixture.service.commit(plan, target, plan.readyPages.map { it.sourceKey }.toSet())
            assertEquals(1, report.importedPages)
            val archived = fixture.restored.single()
            assertEquals(TranslationMode.STRUCTURED_FILES, archived.job.settings.mode)
            assertFalse(archived.job.settings.autoTranslate)
            assertEquals(0, archived.job.settings.chaptersAhead)
            assertEquals("", archived.job.settings.provider.credentialId)
            assertTrue(archived.job.settings.provider.extraHeaders.isEmpty())
            assertEquals(listOf("0"), archived.results.map { it.imageId })
            assertTrue(archived.reviews.isEmpty())
            assertEquals(mapOf("0" to null), fixture.links.single().expectedRevisions)
            coVerify(exactly = 0) { fixture.manager.enqueue(any(), any(), any()) }
            coVerify(exactly = 0) { fixture.manager.enqueueSelected(any(), any()) }
            coVerify(exactly = 0) { fixture.manager.reviewPages(any(), any()) }
            coVerify(exactly = 0) { fixture.manager.checkpointForManagement(any()) }
        }
    }

    @Test
    fun `closing one preview removes only its own acquired originals`() = runBlocking {
        val fixture = Fixture()
        val first = fixture.service.prepareTarget(3, 4)
        val second = fixture.service.prepareTarget(3, 4)
        val firstFile = File(first.images.single().filePath)
        val secondFile = File(second.images.single().filePath)
        assertTrue(firstFile != secondFile)
        assertEquals(first.job.id, second.job.id, "Repeated chapter imports target one stable saved chapter")
        first.close()
        assertFalse(firstFile.exists())
        assertTrue(secondFile.exists())
        second.close()
        assertFalse(secondFile.exists())
        assertTrue(fixture.original.exists())
    }

    @Test
    fun `cancelled return handoff releases prepared original files`() = runBlocking {
        val fixture = Fixture()
        val tasks = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        val dispatcher = object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                tasks.add(block)
            }
        }
        val work = launch(dispatcher) { fixture.service.prepareTarget(3, 4).close() }
        requireNotNull(tasks.poll(5, java.util.concurrent.TimeUnit.SECONDS)).run()
        val returnToCaller = requireNotNull(tasks.poll(5, java.util.concurrent.TimeUnit.SECONDS))
        val parent = File(directory, "translation/images")
        assertTrue(parent.listFiles().orEmpty().any { it.isDirectory })
        work.cancel()
        returnToCaller.run()
        while (!work.isCompleted) requireNotNull(tasks.poll(5, java.util.concurrent.TimeUnit.SECONDS)).run()
        assertTrue(parent.listFiles().orEmpty().isEmpty(), "Cancelled dispatcher handoff leaked acquired originals")
        assertTrue(fixture.original.exists())
    }

    @Test
    fun `cancelling and disposing during committed restore keeps referenced originals`() = runBlocking {
        val fixture = Fixture()
        val target = fixture.service.prepareTarget(3, 4)
        val plan = fixture.plan(target)
        val entered = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        coEvery { fixture.archives.restore(any(), any(), any()) } coAnswers {
            entered.complete(Unit)
            finish.await()
            TranslationRestoreReport(1, 0, 0, 1, 0, listOf(target.job.id))
        }
        val work = launch { fixture.service.commit(plan, target, plan.readyPages.map { it.sourceKey }.toSet()) }
        entered.await()
        target.close()
        work.cancel()
        assertTrue(File(target.images.single().filePath).exists(), "Screen disposal deleted an in-flight original")
        finish.complete(Unit)
        work.join()
        assertTrue(
            File(target.images.single().filePath).exists(),
            "Cancellation deleted an original after SQLite commit",
        )
        coVerify(exactly = 1) { fixture.repository.saveOperation(any()) }
    }

    @Test
    fun `failed atomic restore cleans disposable originals and never records completion`() = runBlocking {
        val fixture = Fixture()
        val target = fixture.service.prepareTarget(3, 4)
        val plan = fixture.plan(target)
        coEvery {
            fixture.archives.restore(
                any(),
                any(),
                any(),
            )
        } throws CancellationException("Interrupted before commit")
        val result = runCatching { fixture.service.commit(plan, target, plan.readyPages.map { it.sourceKey }.toSet()) }
        assertTrue(result.exceptionOrNull() is CancellationException)
        target.close()
        assertFalse(File(target.images.single().filePath).exists())
        coVerify(exactly = 0) { fixture.repository.saveOperation(any()) }
    }

    @Test
    fun `postcommit diagnostic failure is reported as a warning without losing saved images`() = runBlocking {
        val fixture = Fixture()
        val target = fixture.service.prepareTarget(3, 4)
        val plan = fixture.plan(target)
        coEvery { fixture.repository.saveOperation(any()) } throws
            IllegalStateException("Unavailable diagnostic storage")
        val report = fixture.service.commit(plan, target, plan.readyPages.map { it.sourceKey }.toSet())
        target.close()
        assertEquals(1, report.importedPages)
        assertTrue(report.warnings.single().contains("were saved"))
        assertTrue(File(target.images.single().filePath).exists())
    }

    @Test
    fun `modified preview or changed originals cannot reach the transaction`() = runBlocking {
        val fixture = Fixture()
        fixture.service.prepareTarget(3, 4).use { target ->
            val plan = fixture.plan(target)
            val page = plan.readyPages.single()
            val forged = plan.copy(pages = listOf(page.copy(result = page.result!!.copy(width = 999))))
            assertTrue(runCatching { fixture.service.commit(forged, target, setOf(page.sourceKey)) }.isFailure)
            File(target.images.single().filePath).appendText("changed")
            assertTrue(runCatching { fixture.service.commit(plan, target, setOf(page.sourceKey)) }.isFailure)
            coVerify(exactly = 0) { fixture.archives.restore(any(), any(), any()) }
        }
    }

    @Test
    fun `changed source keeps the old chapter isolated even when importing only an unchanged page`() = runBlocking {
        val fixture = Fixture()
        val oldImages = fixture.descriptors(listOf("old-A", "same-B"))
        val old = fixture.existing(oldImages)
        val originals = fixture.reacquire(listOf("new-C", "same-B"))
        fixture.service.prepareTarget(3, 4).use { target ->
            assertTrue(target.job.id != old.id)
            assertTrue(target.results.isEmpty())
            assertEquals(originals.map { it.contentHash }, target.images.map { it.contentHash })
            val kept = target.images[1]
            val document = fixture.service.stage(
                """{"format":"mihon-structured-translations","version":1,"pages":[
                    {"imageId":"unchanged-page","imageHash":"${kept.contentHash}",
                    "width":100,"height":200,"regions":[]}]}""".byteInputStream(),
                "partial.json",
            )
            val plan = fixture.service.preview(document, target)
            fixture.service.commit(plan, target, plan.readyPages.map { it.sourceKey }.toSet())
            assertEquals(target.job.id, fixture.restored.single().job.id)
            assertEquals(target.job.id, fixture.links.single().targetJobId)
            assertEquals(mapOf(kept.id to null), fixture.links.single().expectedRevisions)
            assertEquals(old, fixture.repository.jobs().single())
            assertEquals(oldImages, fixture.repository.images(old.id))
            assertEquals(fixture.savedResults(oldImages), fixture.repository.results(old.id))
            coVerify(exactly = 0) { fixture.manager.checkpointForManagement(any()) }
            coVerify(exactly = 0) { fixture.repository.saveJob(any()) }
        }
    }

    @Test
    fun `reordered source uses a different chapter and preserves saved region identities`() = runBlocking {
        val fixture = Fixture()
        val oldImages = fixture.descriptors(listOf("A", "B"))
        val old = fixture.existing(oldImages)
        val originals = fixture.reacquire(listOf("B", "A"))
        fixture.service.prepareTarget(3, 4).use { target ->
            assertTrue(target.job.id != old.id)
            assertTrue(target.results.isEmpty())
            assertEquals(originals.map { it.contentHash }, target.images.map { it.contentHash })
            assertEquals(fixture.savedResults(oldImages), fixture.repository.results(old.id))
        }
    }

    @Test
    fun `shrinking a reacquired chapter cannot inherit extra saved pages`() = runBlocking {
        val fixture = Fixture()
        val oldImages = fixture.descriptors(listOf("A", "B", "C"))
        val old = fixture.existing(oldImages)
        fixture.reacquire(listOf("A", "B"))
        fixture.service.prepareTarget(3, 4).use { target ->
            assertTrue(target.job.id != old.id)
            assertEquals(2, target.job.imageCount)
            assertEquals(2, target.images.size)
            assertTrue(target.results.isEmpty())
            assertEquals(3, fixture.repository.images(old.id).size)
            assertEquals(3, fixture.repository.results(old.id).size)
        }
    }

    @Test
    fun `missing files with unchanged identities reuse saved IDs and corrections`() = runBlocking {
        val fixture = Fixture()
        val oldImages = fixture.descriptors(listOf("A", "B")).map { it.copy(id = "saved-${it.index}") }
        val old = fixture.existing(oldImages)
        fixture.reacquire(listOf("A", "B"))
        fixture.service.prepareTarget(3, 4).use { target ->
            assertEquals(old, target.job)
            assertEquals(listOf("saved-0", "saved-1"), target.images.map { it.id })
            assertEquals(fixture.savedResults(oldImages), target.results)
            assertTrue(target.images.all { File(it.filePath).isFile })
        }
    }

    @Test
    fun `new import identity includes dimensions and is stable for identical ordered originals`() = runBlocking {
        val fixture = Fixture()
        fixture.reacquire(listOf("A"), width = 100)
        val first = fixture.service.prepareTarget(3, 4).use { it.job.id }
        val repeated = fixture.service.prepareTarget(3, 4).use { it.job.id }
        assertEquals(first, repeated)
        fixture.reacquire(listOf("A"), width = 200)
        val resized = fixture.service.prepareTarget(3, 4).use { it.job.id }
        assertTrue(first != resized)
    }

    @Test
    fun `matching prior import is reused instead of the latest incompatible source snapshot`() = runBlocking {
        val fixture = Fixture()
        val incompatible = fixture.descriptors(listOf("old"))
        val latest = fixture.existing(incompatible)
        val matching = fixture.descriptors(listOf("current"))
        val earlier = latest.copy(id = "matching-import", updatedAt = latest.updatedAt - 1)
        coEvery { fixture.repository.jobs() } returns listOf(latest, earlier)
        coEvery { fixture.repository.images(earlier.id) } returns matching
        coEvery { fixture.repository.results(earlier.id) } returns fixture.savedResults(matching)
        fixture.reacquire(listOf("current"))
        fixture.service.prepareTarget(3, 4).use { target ->
            assertEquals(earlier, target.job)
            assertEquals(fixture.savedResults(matching), target.results)
        }
    }

    @Test
    fun `only a genuinely empty unacquired job can adopt verified originals`() = runBlocking {
        val fixture = Fixture()
        val placeholder = fixture.existing(emptyList()).copy(imageCount = 0, completedImages = 0)
        coEvery { fixture.repository.jobs() } returns listOf(placeholder)
        fixture.reacquire(listOf("A"))
        fixture.service.prepareTarget(3, 4).use { assertEquals(placeholder.id, it.job.id) }
        val orphan = fixture.savedResults(fixture.descriptors(listOf("previous")))
        coEvery { fixture.repository.results(placeholder.id) } returns orphan
        fixture.service.prepareTarget(3, 4).use { target ->
            assertTrue(target.job.id != placeholder.id)
            assertTrue(target.results.isEmpty())
            assertEquals(orphan, fixture.repository.results(placeholder.id))
        }
    }

    private inner class Fixture {
        val archives = mockk<TranslationArchiveRepository>()
        val repository = mockk<TranslationRepository>(relaxed = true)
        val manager = mockk<TranslationManager>()
        val imageSource = mockk<TranslationImageSource>()
        val original = File(directory, "source.fixture").apply { writeText("fixed original bytes") }
        val restored = mutableListOf<TranslationArchiveChapter>()
        val links = mutableListOf<TranslationArchiveLink>()
        private val image = TranslationImage(
            "0",
            0,
            "",
            "image/png",
            100,
            200,
            TranslationBackupCodec.sha256(original),
            original.length(),
        )
        val service: TranslationStructuredImportService

        init {
            val context = mockk<Context>()
            val preferences = mockk<TranslationPreferences>()
            val getManga = mockk<GetManga>()
            val getChapter = mockk<GetChapter>()
            every { context.noBackupFilesDir } returns directory
            every { preferences.effectiveSettings(3) } returns TranslationSettings(
                autoTranslate = true,
                chaptersAhead = 5,
                provider = tachiyomi.domain.translation.model.ProviderSettings(
                    credentialId = "private-reference",
                    extraHeaders = mapOf("secret" to "private"),
                ),
            )
            coEvery { repository.jobs() } returns emptyList()
            coEvery { getManga.await(3) } returns Manga.create().copy(id = 3, title = "Selected series")
            coEvery { getChapter.await(4) } returns Chapter.create().copy(
                id = 4,
                mangaId = 3,
                name = "Selected chapter",
            )
            coEvery { imageSource.acquire(any()) } coAnswers {
                val job = firstArg<TranslationJob>()
                val folder = File(directory, "translation/images/${job.id}").apply { check(mkdirs()) }
                val file = original.copyTo(File(folder, "0.image"))
                listOf(image.copy(filePath = file.path))
            }
            coEvery { archives.restore(any(), any(), any()) } coAnswers {
                val chapter = firstArg<Sequence<TranslationArchiveChapter>>().single()
                restored += chapter
                links += thirdArg<Map<String, TranslationArchiveLink>>().getValue(chapter.job.id)
                TranslationRestoreReport(chapter.results.size, 0, 0, 1, 0, listOf(chapter.job.id))
            }
            service = TranslationStructuredImportService(
                context,
                preferences,
                repository,
                archives,
                manager,
                imageSource,
                getManga,
                getChapter,
            )
        }

        fun descriptors(contents: List<String>, width: Int = 100): List<TranslationImage> =
            contents.mapIndexed { index, text ->
                val bytes = text.toByteArray()
                val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                TranslationImage(
                    index.toString(),
                    index,
                    File(directory, "missing/$index.image").path,
                    "image/png",
                    width,
                    200,
                    hash,
                    bytes.size.toLong(),
                )
            }

        fun savedResults(images: List<TranslationImage>) = images.map { image ->
            val region = tachiyomi.domain.translation.model.TextRegion(
                "dialogue-${image.id}",
                listOf(
                    tachiyomi.domain.translation.model.TranslationPoint(1f, 1f),
                    tachiyomi.domain.translation.model.TranslationPoint(50f, 1f),
                    tachiyomi.domain.translation.model.TranslationPoint(50f, 50f),
                    tachiyomi.domain.translation.model.TranslationPoint(1f, 50f),
                ),
                "raw source ${image.index}",
                "saved translation ${image.index}",
                correctedText = "manual correction ${image.index}",
                recognitionConfidence = 0.95f,
                style = tachiyomi.domain.translation.model.OverlayStyle(fontSize = 19f),
            )
            TranslationPageResult(
                image.id,
                image.contentHash,
                image.width,
                image.height,
                listOf(region),
                rawOcr = tachiyomi.domain.translation.model.OcrPageResult(
                    image.id,
                    listOf(region.copy(correctedText = null)),
                    rawJson = "retained raw OCR",
                ),
                revision = 40L + image.index,
            )
        }

        fun existing(images: List<TranslationImage>): TranslationJob {
            val job = TranslationJob(
                "prior-chapter", 3, 4, "Series", "Chapter", TranslationSettings(),
                state = TranslationJobState.COMPLETED, imageCount = images.size, completedImages = images.size,
            )
            coEvery { repository.jobs() } returns listOf(job)
            coEvery { repository.images(job.id) } returns images
            coEvery { repository.results(job.id) } returns savedResults(images)
            return job
        }

        fun reacquire(contents: List<String>, width: Int = 100): List<TranslationImage> {
            val identities = descriptors(contents, width)
            coEvery { imageSource.acquire(any()) } coAnswers {
                val job = firstArg<TranslationJob>()
                val folder = File(directory, "translation/images/${job.id}").apply { check(mkdirs()) }
                identities.map { image ->
                    val file = File(folder, "${image.index}.image").apply { writeText(contents[image.index]) }
                    image.copy(filePath = file.path)
                }
            }
            return identities
        }

        suspend fun plan(target: StructuredImportTarget, includeInvalid: Boolean = false): StructuredImportPlan {
            val invalid = if (includeInvalid) {
                """,{"imageId":"invalid","regions":[{"id":"bad","translatedText":"x","points":"invalid"}]}"""
            } else {
                ""
            }
            val document = service.stage(
                """{"format":"mihon-structured-translations","version":1,"pages":[
                    {"imageId":"external","imageHash":"${image.contentHash}",
                    "width":100,"height":200,"regions":[]}$invalid]}""".byteInputStream(),
                "translation.json",
            )
            return service.preview(document, target)
        }
    }
}
