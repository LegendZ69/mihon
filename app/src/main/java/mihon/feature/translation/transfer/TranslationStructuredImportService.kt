package mihon.feature.translation.transfer

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import mihon.feature.translation.TranslationImageSource
import mihon.feature.translation.TranslationManager
import mihon.feature.translation.TranslationPreferences
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.translation.model.StructuredImportDisposition
import tachiyomi.domain.translation.model.StructuredImportDocument
import tachiyomi.domain.translation.model.StructuredImportFormat
import tachiyomi.domain.translation.model.StructuredImportPlan
import tachiyomi.domain.translation.model.StructuredImportProvenance
import tachiyomi.domain.translation.model.TranslationArchiveChapter
import tachiyomi.domain.translation.model.TranslationArchiveLink
import tachiyomi.domain.translation.model.TranslationArchiveProvenance
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationRestoreReport
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.TranslationArchiveRepository
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/** Originals may be acquired from the source; this service has no provider or OCR dependency. */
@Inject
@SingleIn(AppScope::class)
class TranslationStructuredImportService(
    private val context: Context,
    private val preferences: TranslationPreferences,
    private val repository: TranslationRepository,
    private val archives: TranslationArchiveRepository,
    private val manager: TranslationManager,
    private val imageSource: TranslationImageSource,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
) {
    private val codec = StructuredTranslationCodec()
    private val commitMutex = Mutex()

    suspend fun stage(input: InputStream, name: String): StructuredImportDocument = withContext(Dispatchers.IO) {
        val call = currentCoroutineContext()
        codec.decode(input, name) { call.ensureActive() }
    }

    suspend fun prepareTarget(mangaId: Long, chapterId: Long, jobId: String? = null): StructuredImportTarget {
        var prepared: StructuredImportTarget? = null
        try {
            return withContext(Dispatchers.IO) {
                val manga = requireNotNull(getManga.await(mangaId)) { "Series no longer exists" }
                val chapter = requireNotNull(getChapter.await(chapterId)) { "Chapter no longer exists" }
                require(chapter.mangaId == mangaId) { "Chapter belongs to another series" }
                val candidates = repository.jobs().filter { it.mangaId == mangaId && it.chapterId == chapterId }
                val existing = if (jobId != null) {
                    requireNotNull(candidates.singleOrNull { it.id == jobId }) { "Selected saved chapter changed" }
                } else {
                    candidates.maxByOrNull { it.updatedAt }
                }
                val sourceId = "structured-source-${UUID.randomUUID()}"
                val owned = File(context.noBackupFilesDir, "translation/images/$sourceId")
                try {
                    val settings = preferences.effectiveSettings(mangaId)
                    val provisional = TranslationJob(
                        sourceId,
                        mangaId,
                        chapterId,
                        manga.title,
                        chapter.name,
                        settings.copy(
                            mode = TranslationMode.STRUCTURED_FILES,
                            autoTranslate = false,
                            chaptersAhead = 0,
                        ),
                        state = TranslationJobState.PAUSED,
                    )
                    val cached = existing?.let { repository.images(it.id) }.orEmpty()
                    val cacheComplete = existing?.imageCount == cached.size
                    val usableCache = cached.isNotEmpty() && cacheComplete && cached.all(::verify)
                    val originals = if (usableCache) cached else imageSource.acquire(provisional)
                    require(originals.isNotEmpty()) { "Chapter has no original pages to match" }
                    require(originals.all { verify(it) }) { "An original image changed during acquisition" }
                    fun TranslationImage.identity() = Triple(contentHash, width, height) to index
                    val identities = originals.sortedBy { it.index }.map { it.identity() }
                    require(originals.map { it.index }.distinct().size == originals.size) {
                        "Original pages have ambiguous ordering"
                    }
                    var reusable: TranslationJob? = null
                    var reusableImages = emptyList<TranslationImage>()
                    var reusableResults = emptyList<TranslationPageResult>()
                    val preferred = listOfNotNull(existing) + candidates.filter { it.id != existing?.id }
                        .sortedByDescending { it.updatedAt }
                    for (candidate in preferred) {
                        val storedImages = if (candidate.id == existing?.id) cached else repository.images(candidate.id)
                        val sameOriginals = storedImages.sortedBy { it.index }.map { it.identity() } == identities
                        val unacquired = candidate.id == existing?.id && candidate.imageCount == 0 &&
                            storedImages.isEmpty()
                        if (!sameOriginals && !unacquired) continue
                        val storedResults = repository.results(candidate.id)
                        val coherentResults = storedResults.all { result ->
                            storedImages.any { image ->
                                image.id == result.imageId && image.contentHash == result.imageHash &&
                                    image.width == result.width && image.height == result.height
                            }
                        }
                        if (!coherentResults || (unacquired && storedResults.isNotEmpty())) continue
                        reusable = candidate
                        reusableImages = storedImages
                        reusableResults = storedResults
                        break
                    }
                    val originalByIdentity = reusableImages.associateBy { it.identity() }
                    val images = originals.map { original ->
                        originalByIdentity[original.identity()]?.let { original.copy(id = it.id) } ?: original
                    }
                    val job = reusable ?: provisional.copy(
                        id = "structured-" + digest(
                            "$mangaId:$chapterId:" + images.sortedBy { it.index }.joinToString(";") {
                                "${it.index}:${it.contentHash}:${it.width}:${it.height}"
                            },
                        ),
                        imageCount = images.size,
                        message = "Awaiting imported pages",
                    )
                    val results = reusableResults
                    StructuredImportTarget(job, images, results, owned.takeIf { it.isDirectory }).also { prepared = it }
                } catch (failure: Throwable) {
                    owned.deleteRecursively()
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            // withContext can reject a successfully prepared return when the caller is cancelled.
            prepared?.close()
            throw failure
        }
    }

    fun preview(
        document: StructuredImportDocument,
        target: StructuredImportTarget,
        mapping: Map<String, String> = emptyMap(),
        replacePageKeys: Set<String> = emptySet(),
    ): StructuredImportPlan {
        target.requireOpen()
        return codec.plan(document, target.images, target.results, mapping, replacePageKeys)
    }

    suspend fun commit(
        plan: StructuredImportPlan,
        target: StructuredImportTarget,
        selectedPageKeys: Set<String>,
    ): TranslationRestoreReport = withContext(Dispatchers.IO) {
        commitMutex.withLock {
            target.beginCommit()
            try {
                require(plan.document.source.format != StructuredImportFormat.MIHON_ZIP) { "Use the ZIP restore flow" }
                require(selectedPageKeys.isNotEmpty()) { "Select valid pages to import" }
                val pages = plan.pages.filter { it.sourceKey in selectedPageKeys }
                val importable = setOf(StructuredImportDisposition.READY, StructuredImportDisposition.IDENTICAL)
                require(
                    pages.size == selectedPageKeys.size && pages.all {
                        it.disposition in importable &&
                            it.image != null && it.result != null && it.issues.isEmpty()
                    },
                ) { "Selection contains invalid, unmapped or unresolved pages" }
                require(pages.map { it.image!!.id }.distinct().size == pages.size) {
                    "Multiple pages map to one original"
                }
                val revalidated = codec.plan(
                    plan.document,
                    target.images,
                    target.results,
                    pages.associate { it.sourceKey to it.image!!.id },
                    pages.filter { it.expectedRevision != null }.map { it.sourceKey }.toSet(),
                ).pages.associateBy { it.sourceKey }
                require(
                    pages.all { page ->
                        val checked = revalidated[page.sourceKey]
                        checked?.result == page.result && checked?.expectedRevision == page.expectedRevision &&
                            checked?.disposition in importable
                    },
                ) { "Import preview changed; reopen validation before importing" }
                val originals = pages.map { requireNotNull(it.image) }
                require(originals.all { it in target.images && verify(it) }) {
                    "Original identity changed since preview"
                }
                val now = System.currentTimeMillis()
                val source = plan.document.source
                val provenance = pages.associate { page ->
                    val input = plan.document.pages.single { it.key == page.sourceKey }
                    page.image!!.id to StructuredImportProvenance(
                        source,
                        input.key,
                        input.imageId,
                        now,
                        plan.document.externalUsageJson,
                    )
                }
                val config = target.job.settings
                val archiveJob = target.job.copy(
                    settings = config.copy(
                        mode = TranslationMode.STRUCTURED_FILES,
                        provider = config.provider.copy(credentialId = "", extraHeaders = emptyMap()),
                        autoTranslate = false,
                        chaptersAhead = 0,
                    ),
                    imageCount = target.images.size,
                    state = TranslationJobState.PAUSED,
                    reviewReturnState = null,
                    reviewImageIds = null,
                    replacesJobId = null,
                    archiveMetadata = TranslationArchiveProvenance(
                        target.job.id,
                        pages.associate { it.image!!.id to (it.expectedRevision ?: 0) },
                        now,
                        structuredFiles = true,
                        structuredPages = provenance,
                    ),
                )
                val chapter = TranslationArchiveChapter(
                    archiveJob,
                    originals.map { it.copy(filePath = "") },
                    pages.map { requireNotNull(it.result) },
                )
                val link = TranslationArchiveLink(
                    target.job.mangaId,
                    target.job.chapterId,
                    target.images,
                    target.job.mangaTitle,
                    target.job.chapterTitle,
                    target.job.id,
                    expectedRevisions = pages.associate { it.image!!.id to it.expectedRevision },
                    replaceImageIds = pages.filter { it.expectedRevision != null }.map { it.image!!.id }.toSet(),
                )
                val checkpoint = repository.jobs().firstOrNull { it.id == target.job.id }?.let {
                    manager.checkpointForManagement(setOf(it.id))
                }
                suspend fun restore() = archives.restore(sequenceOf(chapter), links = mapOf(archiveJob.id to link))
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    val report = if (checkpoint != null) {
                        manager.withManagementCheckpoint(checkpoint) { restore() }
                    } else {
                        restore()
                    }
                    // Retain original files before cancellation can return a committed transaction to its caller.
                    target.markCommitted()
                    val operationId = UUID.randomUUID().toString()
                    try {
                        repository.saveOperation(
                            TranslationOperation(
                                operationId, target.job.id, TranslationStage.RESTORE,
                                state = TranslationOperationState.COMPLETED, completed = pages.size.toLong(),
                                total = pages.size.toLong(), unit = TranslationProgressUnit.PAGES,
                                startedAt = now, updatedAt = now, endedAt = now,
                                message = "Imported ${report.importedPages} pages; " +
                                    "${report.identicalPages} identical; " +
                                    "${report.preservedConflicts} local conflicts preserved. No provider requests.",
                            ),
                        )
                        repository.addEvent(
                            TranslationEvent(
                                "$operationId:complete",
                                target.job.id,
                                operationId = operationId,
                                stage = TranslationStage.RESTORE.name,
                                message = "Structured import complete: ${pages.size} selected pages",
                                details = mapOf(
                                    "sourceSha256" to source.sha256,
                                    "sourceFormat" to source.format.name,
                                    "providerUsage" to "Historical provenance only; no new application spending",
                                ),
                            ),
                        )
                        report
                    } catch (_: Exception) {
                        report.copy(
                            warnings = report.warnings +
                                "Translations were saved, but import diagnostics could not be saved.",
                        )
                    }
                }
            } finally {
                target.finishCommit()
            }
        }
    }

    private fun verify(image: TranslationImage): Boolean {
        val file = File(image.filePath)
        if (!file.isFile || file.length() != image.byteSize) return false
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) } == image.contentHash
    }
    private fun digest(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}

class StructuredImportTarget internal constructor(
    val job: TranslationJob,
    val images: List<TranslationImage>,
    val results: List<TranslationPageResult>,
    private val owned: File?,
) : AutoCloseable {
    private var committed = false
    private var closed = false
    private var committing = false

    @Synchronized
    internal fun requireOpen() {
        check(!closed) { "Import preview is closed; reopen the files" }
    }

    @Synchronized
    internal fun beginCommit() {
        requireOpen()
        check(!committing) { "Import is already committing" }
        committing = true
    }

    @Synchronized
    internal fun markCommitted() {
        committed = true
    }

    @Synchronized
    internal fun finishCommit() {
        committing = false
        if (closed && !committed) owned?.deleteRecursively()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        // A concurrent screen disposal must not remove originals during an atomic database commit.
        if (!committing && !committed) owned?.deleteRecursively()
    }
}
