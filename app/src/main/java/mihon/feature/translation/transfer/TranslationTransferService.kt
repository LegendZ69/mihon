package mihon.feature.translation.transfer

import android.content.Context
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement
import mihon.app.di.appGraph
import mihon.feature.translation.TranslationImageSource
import mihon.feature.translation.TranslationManager
import mihon.feature.translation.TranslationPreferences
import mihon.feature.translation.overlay.resolveReviewTheme
import mihon.feature.translation.provider.SanitizedCaptureOutputStream
import mihon.feature.translation.provider.TranslationDiagnosticsStore
import tachiyomi.domain.chapter.interactor.GetChapter
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.translation.model.TranslationArchiveChapter
import tachiyomi.domain.translation.model.TranslationArchiveConflictPolicy
import tachiyomi.domain.translation.model.TranslationArchiveLink
import tachiyomi.domain.translation.model.TranslationArchiveManifest
import tachiyomi.domain.translation.model.TranslationBackupOptions
import tachiyomi.domain.translation.model.TranslationEvent
import tachiyomi.domain.translation.model.TranslationLogQuery
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationPdfPageSize
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationRenderedFormat
import tachiyomi.domain.translation.model.TranslationRestoreReport
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.model.TranslationTransferProgress
import tachiyomi.domain.translation.model.TranslationTransferReport
import tachiyomi.domain.translation.service.TranslationArchiveRepository
import tachiyomi.domain.translation.service.TranslationRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** User initiated transfers never enqueue translation/review work or change live series preferences. */
@Inject
@SingleIn(AppScope::class)
class TranslationTransferService(
    private val context: Context,
    private val archiveRepository: TranslationArchiveRepository,
    private val repository: TranslationRepository,
    private val preferences: TranslationPreferences,
    private val diagnostics: TranslationDiagnosticsStore,
    private val exports: TranslationExportStore,
    private val manager: TranslationManager,
    private val imageSource: TranslationImageSource,
    private val getManga: GetManga,
    private val getChapter: GetChapter,
) {
    private val root get() = File(context.noBackupFilesDir, "translation/transfer")
    private val codec get() = TranslationBackupCodec(root)
    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val restoreMutex = Mutex()

    suspend fun backup(
        jobIds: Set<String>,
        destination: OutputStream,
        options: TranslationBackupOptions = TranslationBackupOptions(),
        progress: suspend (TranslationTransferProgress) -> Unit = {},
    ): TranslationArchiveManifest = withContext(Dispatchers.IO) {
        require(jobIds.isNotEmpty()) { "Select translations to back up" }
        operation(
            jobIds.first(),
            TranslationStage.EXPORT,
            TranslationProgressUnit.ARTIFACTS,
            jobIds.size.toLong(),
            progress,
        ) { update ->
            val staging = File(root, "attachments-${UUID.randomUUID()}").apply { check(mkdirs()) }
            try {
                val attachments = mutableListOf<TranslationArchiveAttachment>()
                if (options.includeLogs) {
                    jobIds.forEachIndexed { jobIndex, jobId ->
                        var offset = 0L
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val events = repository.eventPage(
                                TranslationLogQuery(jobId = jobId, limit = 200, offset = offset),
                            )
                            if (events.isEmpty()) break
                            val name = "diagnostics/log-$jobIndex-$offset.json"
                            val file = File(staging, "log-$jobIndex-$offset.json")
                            file.outputStream().use { out ->
                                SanitizedCaptureOutputStream(out).use { sanitizer ->
                                    sanitizer.write(
                                        json.encodeToString(
                                            TranslationArchiveHistory(
                                                events = events.map {
                                                    it.copy(
                                                        capturePath = it.capturePath?.let { path ->
                                                            File(path).name
                                                        },
                                                    )
                                                },
                                            ),
                                        ).toByteArray(),
                                    )
                                    sanitizer.finish(true)
                                }
                            }
                            attachments += TranslationArchiveAttachment(name, "logs", file)
                            offset += events.size
                        }
                        offset = 0
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val operations = repository.observeOperations(jobId, 200, offset).first()
                            if (operations.isEmpty()) break
                            val name = "diagnostics/operations-$jobIndex-$offset.json"
                            val file = File(staging, "operations-$jobIndex-$offset.json")
                            file.outputStream().use { out ->
                                SanitizedCaptureOutputStream(out).use { sanitizer ->
                                    sanitizer.write(
                                        TranslationArchiveHistory(
                                            operations = operations,
                                        ).exportJson(json).toByteArray(),
                                    )
                                    sanitizer.finish(true)
                                }
                            }
                            attachments += TranslationArchiveAttachment(name, "logs", file)
                            offset += operations.size
                        }
                    }
                }
                if (options.includeCaptures) {
                    val captures = diagnostics.list().filter { it.jobId in jobIds }.map { it.id }
                    if (captures.isNotEmpty()) {
                        captures.chunked(8).forEachIndexed { index, ids ->
                            val file = File(staging, "captures-$index.zip")
                            diagnostics.export(ids, file.outputStream())
                            attachments +=
                                TranslationArchiveAttachment("diagnostics/captures-$index.zip", "captures", file)
                        }
                    }
                }
                var count = 0L
                codec.write(
                    flow {
                        jobIds.forEach { jobId ->
                            val chapter =
                                requireNotNull(archiveRepository.snapshot(jobId)) { "Translation job is unavailable" }
                            val effective = if (chapter.job.chapterId < 0) {
                                chapter.effectiveSettings ?: chapter.job.settings
                            } else {
                                preferences.effectiveSettings(chapter.job.mangaId)
                            }
                            emit(chapter.copy(effectiveSettings = effective))
                            update(
                                ++count,
                                "Saved chapter snapshot: ${chapter.job.mangaTitle} · ${chapter.job.chapterTitle}",
                            )
                        }
                    },
                    destination,
                    attachments,
                )
            } finally {
                staging.deleteRecursively()
            }
        }
    }

    suspend fun inspectBackup(source: InputStream): StagedTranslationArchive = withContext(Dispatchers.IO) {
        val staged = codec.read(source)
        try {
            staged.localPreview = archiveRepository.preview()
            staged
        } catch (failure: Throwable) {
            staged.close()
            throw failure
        }
    }

    suspend fun restore(
        archive: StagedTranslationArchive,
        policy: TranslationArchiveConflictPolicy = TranslationArchiveConflictPolicy.KEEP_LOCAL,
        links: Map<String, TranslationArchiveLink> = emptyMap(),
        structuredFiles: Boolean = false,
        progress: suspend (TranslationTransferProgress) -> Unit = {},
    ): TranslationRestoreReport = restoreInternal(archive, policy, links, progress, structuredFiles = structuredFiles)

    private suspend fun restoreInternal(
        archive: StagedTranslationArchive,
        policy: TranslationArchiveConflictPolicy,
        links: Map<String, TranslationArchiveLink>,
        progress: suspend (TranslationTransferProgress) -> Unit,
        expectedSource: TranslationArchiveChapter? = null,
        structuredFiles: Boolean = false,
    ): TranslationRestoreReport = withContext(Dispatchers.IO) {
        restoreMutex.withLock {
            var restoreOperationId = ""
            operation(
                archive.chapterSummaries.firstOrNull()?.sourceJobId ?: "archive-restore",
                TranslationStage.RESTORE,
                TranslationProgressUnit.PAGES,
                archive.manifest.pages.toLong(),
                progress,
                onStarted = { restoreOperationId = it },
            ) { update ->
                val call = currentCoroutineContext()
                val ownedOriginalFolders = mutableListOf<File>()
                val fontsDirectory = File(root, "fonts")
                val priorFontPaths = fontsDirectory.listFiles().orEmpty().map { it.absolutePath }.toSet()
                var coreCommitted = false
                val warnings = archive.manifest.warnings.toMutableList()
                try {
                    val sourceIds = archive.chapterSummaries.map { it.sourceJobId }.toSet()
                    require(links.keys.all { it in sourceIds }) { "A selected archive chapter is unavailable" }
                    val preparedLinks = linkedMapOf<String, TranslationArchiveLink>()
                    links.forEach { (sourceId, target) ->
                        val source = requireNotNull(archive.chapter(sourceId) { call.ensureActive() })
                        preparedLinks[sourceId] =
                            prepareLink(source, target, ownedOriginalFolders, progress, restoreOperationId)
                    }
                    val affected = repository.jobs().filter { existing ->
                        existing.id in sourceIds || existing.archiveMetadata?.sourceJobId in sourceIds ||
                            preparedLinks.values.any { it.targetJobId == existing.id }
                    }.map { it.id }.toSet() + listOfNotNull(expectedSource?.job?.id)
                    val checkpoint = affected.takeIf { it.isNotEmpty() }?.let { manager.checkpointForManagement(it) }
                    val fontPaths = try {
                        TranslationArchiveFontStore(fontsDirectory).restore(archive).filterValues { path ->
                            runCatching { android.graphics.Typeface.createFromFile(path) }.isSuccess.also { valid ->
                                if (!valid) {
                                    warnings +=
                                        "An imported font cannot be loaded on this Android runtime; the " +
                                        "system fallback is used."
                                }
                            }
                        }
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure
                        warnings += "Optional fonts could not be imported: ${failure.message}"
                        emptyMap()
                    }
                    fun chapters() = archive.chapters { call.ensureActive() }.map { sourceChapter ->
                        val chapter = if (structuredFiles) {
                            sourceChapter.copy(
                                job = sourceChapter.job.copy(
                                    settings = sourceChapter.job.settings.copy(
                                        mode = tachiyomi.domain.translation.model.TranslationMode.STRUCTURED_FILES,
                                    ),
                                ),
                            )
                        } else {
                            sourceChapter
                        }
                        chapter.mapStyles({ style ->
                            style.copy(fontPath = style.fontPath?.let(fontPaths::get))
                        }) { key, _ ->
                            fontPaths[key]
                                ?: key
                        }
                    }
                    suspend fun commit(): TranslationRestoreReport {
                        expectedSource?.let { expected ->
                            val current = archiveRepository.snapshot(expected.job.id)
                            require(
                                current != null && current.job.mangaId == expected.job.mangaId &&
                                    current.job.chapterId == expected.job.chapterId &&
                                    current.job.settings == expected.job.settings &&
                                    current.effectiveSettings == expected.effectiveSettings &&
                                    current.images.associateBy { it.id } == expected.images.associateBy { it.id } &&
                                    current.results.associateBy { it.imageId } ==
                                    expected.results.associateBy { it.imageId } &&
                                    current.reviews.associateBy { it.id } == expected.reviews.associateBy { it.id },
                            ) {
                                "The saved archive changed while originals were acquired; relink " +
                                    "again to use its current results and history"
                            }
                        }
                        call.ensureActive()
                        return withContext(NonCancellable) {
                            // Check cancellation during the transaction through chapters().
                            // Once committed, keep its receipt durable.
                            archiveRepository.restore(
                                chapters(),
                                policy,
                                preparedLinks,
                                archive.localPreview,
                            ).also { report ->
                                coreCommitted = true
                                for (targetJobId in report.jobIds.distinct()) {
                                    val committedAt = System.currentTimeMillis()
                                    val receipt = TranslationOperation(
                                        UUID.randomUUID().toString(), targetJobId, TranslationStage.RESTORE,
                                        parentId = restoreOperationId, state = TranslationOperationState.COMPLETED,
                                        completed = 1, total = 1, unit = TranslationProgressUnit.STEPS,
                                        updatedAt = committedAt, endedAt = committedAt,
                                        message = "Translation restore committed; optional history import follows. " +
                                            "Source operation: $restoreOperationId",
                                    )
                                    repository.saveOperation(receipt)
                                    repository.addEvent(
                                        TranslationEvent(
                                            "${receipt.id}:committed",
                                            targetJobId,
                                            operationId = receipt.id,
                                            stage = TranslationStage.RESTORE.name,
                                            operationState = receipt.state,
                                            message = requireNotNull(receipt.message),
                                        ),
                                    )
                                }
                            }
                        }
                    }
                    val report = if (checkpoint != null) {
                        manager.withManagementCheckpoint(checkpoint) { commit() }
                    } else {
                        commit()
                    }
                    update(
                        archive.manifest.pages.toLong(),
                        "Translations are durably restored; importing optional history",
                    )
                    val jobIds = archive.chapterSummaries.map { it.sourceJobId }.zip(report.jobIds).toMap()
                    val captureIds = linkedMapOf<String, String>()
                    for (entry in archive.manifest.entries.filter { it.kind == "captures" }) {
                        try {
                            call.ensureActive()
                            val file = verifiedAttachment(archive, entry)
                            file.inputStream().use {
                                captureIds +=
                                    diagnostics.importArchive(it, jobIds, preferences.settings.value.logs) { warning ->
                                        warnings +=
                                            warning
                                    }
                            }
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            warnings +=
                                "Translations were restored, but optional captures could not be " +
                                "imported: ${failure.message}"
                        }
                    }
                    for (entry in archive.manifest.entries.filter { it.kind == "logs" }) {
                        try {
                            call.ensureActive()
                            val file = verifiedAttachment(archive, entry)
                            require(file.length() <= 16L * 1024 * 1024) { "Log attachment exceeds its record limit" }
                            val safe = java.io.ByteArrayOutputStream()
                            file.inputStream().use { input ->
                                SanitizedCaptureOutputStream(safe).use { sanitizer ->
                                    val buffer = ByteArray(8192)
                                    while (true) {
                                        call.ensureActive()
                                        val count = input.read(buffer)
                                        if (count <
                                            0
                                        ) {
                                            break
                                        }
                                        sanitizer.write(buffer, 0, count)
                                    }
                                    sanitizer.finish(true)
                                }
                            }
                            val element = json.parseToJsonElement(safe.toString(Charsets.UTF_8.name()))
                                .withoutGeometryCorrectionInputs()
                            val history = if (element is kotlinx.serialization.json.JsonArray) {
                                TranslationArchiveHistory(events = json.decodeFromJsonElement(element))
                            } else {
                                json.decodeFromJsonElement<TranslationArchiveHistory>(element)
                            }
                            for ((sourceId, targetId) in jobIds) {
                                val source = requireNotNull(archive.chapter(sourceId) { call.ensureActive() })
                                val target = requireNotNull(archiveRepository.snapshot(targetId))
                                val images = source.images.mapNotNull { original ->
                                    target.images.singleOrNull {
                                        it.index == original.index && it.contentHash == original.contentHash &&
                                            it.width == original.width &&
                                            it.height == original.height
                                    }?.let { original.id to it.id }
                                }.toMap()
                                val reviewIds = source.reviews.mapNotNull { review ->
                                    (
                                        target.reviews.firstOrNull { it.id == review.id }
                                            ?: target.reviews.firstOrNull {
                                                it.imageId == images[review.imageId] &&
                                                    it.sourceRevision == review.sourceRevision &&
                                                    it.attempts == review.attempts
                                            }
                                        )?.let { review.id to it.id }
                                }.toMap()
                                val remapped = history.remap(sourceId, targetId, images, captureIds, reviewIds)
                                archiveRepository.importHistory(
                                    remapped.events.map { event ->
                                        event.copy(
                                            capturePath = event.capturePath?.let {
                                                diagnostics.directoryFor(it).path
                                            },
                                        )
                                    },
                                    remapped.operations,
                                )
                            }
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            warnings +=
                                "Translations were restored, but an optional log attachment could " +
                                "not be imported: ${failure.message}"
                        }
                    }
                    update(
                        archive.manifest.pages.toLong(),
                        "${report.importedPages} imported, ${report.identicalPages} " +
                            "identical, ${report.preservedConflicts} local conflicts preserved",
                    )
                    report.copy(warnings = report.warnings + warnings.distinct())
                } catch (failure: CancellationException) {
                    if (coreCommitted) {
                        throw CancellationException(
                            "Translations were restored; optional attachment import was " +
                                "interrupted. Reimporting safely reuses completed records.",
                        ).also {
                            it.initCause(failure)
                        }
                    }
                    throw failure
                } catch (failure: Exception) {
                    if (coreCommitted) {
                        throw IllegalStateException(
                            "Translations were restored, but recording their receipt or " +
                                "importing optional history failed: ${failure.message}",
                            failure,
                        )
                    }
                    throw failure
                } finally {
                    if (!coreCommitted) {
                        withContext(NonCancellable) {
                            ownedOriginalFolders.forEach { it.deleteRecursively() }
                            fontsDirectory.listFiles().orEmpty().filter {
                                it.absolutePath !in priorFontPaths
                            }.forEach { it.delete() }
                        }
                    }
                }
            }
        }
    }

    suspend fun relinkSaved(
        jobId: String,
        mangaId: Long,
        chapterId: Long,
        progress: suspend (TranslationTransferProgress) -> Unit = {},
    ): TranslationRestoreReport = withContext(Dispatchers.IO) {
        manager.checkpointForManagement(setOf(jobId))
        val source = requireNotNull(archiveRepository.snapshot(jobId)) { "Saved archive is unavailable" }
        require(source.job.chapterId < 0 && source.job.mangaId < 0) { "Only an unlinked archive can be relinked here" }
        val file = File(root, "saved-relink-${UUID.randomUUID()}.zip")
        check(root.isDirectory || root.mkdirs()) { "Cannot prepare saved archive for relinking" }
        try {
            file.outputStream().use { output -> codec.write(kotlinx.coroutines.flow.flowOf(source), output) }
            file.inputStream().use { input -> codec.read(input) }.use { staged ->
                restoreInternal(
                    staged,
                    TranslationArchiveConflictPolicy.KEEP_LOCAL,
                    mapOf(source.job.id to TranslationArchiveLink(mangaId, chapterId)),
                    progress,
                    expectedSource = source,
                )
            }
        } finally {
            file.delete()
        }
    }

    /** A library/local chapter can be linked without constructing a provider job or importing credentials. */
    suspend fun relink(
        archive: StagedTranslationArchive,
        sourceJobId: String,
        mangaId: Long,
        chapterId: Long,
        policy: TranslationArchiveConflictPolicy = TranslationArchiveConflictPolicy.KEEP_LOCAL,
        progress: suspend (TranslationTransferProgress) -> Unit = {},
    ): TranslationRestoreReport = restore(
        archive,
        policy,
        mapOf(sourceJobId to TranslationArchiveLink(mangaId, chapterId)),
        progress = progress,
    )

    private suspend fun prepareLink(
        source: tachiyomi.domain.translation.model.TranslationArchiveChapter,
        target: TranslationArchiveLink,
        ownedFolders: MutableList<File>,
        progress: suspend (TranslationTransferProgress) -> Unit,
        parentId: String,
    ): TranslationArchiveLink {
        val manga = requireNotNull(getManga.await(target.mangaId)) { "Selected series is unavailable" }
        val chapter = requireNotNull(getChapter.await(target.chapterId)) { "Selected chapter is unavailable" }
        require(chapter.mangaId == manga.id) { "Selected chapter belongs to another series" }
        val provisional = source.job.copy(
            id = "archive-source-${UUID.randomUUID()}",
            mangaId = manga.id,
            chapterId = chapter.id,
            mangaTitle = manga.title,
            chapterTitle = chapter.name,
            state = tachiyomi.domain.translation.model.TranslationJobState.PAUSED,
            settings = source.job.settings.copy(autoTranslate = false, chaptersAhead = 0),
            replacesJobId = null,
        )
        val provisionalFolder = File(context.noBackupFilesDir, "translation/images/${provisional.id}")
        ownedFolders += provisionalFolder
        val originals =
            operation(
                provisional.id,
                TranslationStage.ACQUISITION,
                TranslationProgressUnit.PAGES,
                null,
                progress,
                parentId = parentId,
            ) { update ->
                update(0, "Acquire originals: ${manga.title} · ${chapter.name}; no provider request")
                imageSource.acquire(provisional).also {
                    update(
                        it.size.toLong(),
                        "Original pages acquired for content identity verification; no provider request",
                    )
                }
            }
        require(
            source.images.isNotEmpty() && source.images.all { original ->
                originals.count {
                    it.index == original.index &&
                        it.contentHash == original.contentHash &&
                        it.width == original.width &&
                        it.height == original.height
                } == 1
            },
        ) {
            "Selected chapter does not match the archived image hashes, " +
                "dimensions and page order; translations remain unlinked"
        }
        var targetJobId: String? = null
        for (candidate in repository.jobs().filter { it.mangaId == manga.id && it.chapterId == chapter.id }) {
            val images = repository.images(candidate.id)
            if (images.size == originals.size &&
                images.all { image ->
                    originals.any {
                        it.index == image.index && it.contentHash == image.contentHash &&
                            it.width == image.width &&
                            it.height == image.height
                    }
                }
            ) {
                targetJobId = candidate.id
                break
            }
        }
        val resolvedId = targetJobId ?: "archive-linked-" + java.security.MessageDigest.getInstance("SHA-256")
            .digest("${source.job.id}:${manga.id}:${chapter.id}".toByteArray()).joinToString("") { "%02x".format(it) }
        require(resolvedId.matches(Regex("[A-Za-z0-9_-]{1,150}"))) {
            "Selected job has an unsupported storage identity"
        }
        val targetRoot = File(context.noBackupFilesDir, "translation/images/$resolvedId").apply {
            check(isDirectory || mkdirs())
        }
        val destination = File(targetRoot, "archive-${UUID.randomUUID()}")
        check(provisionalFolder.renameTo(destination)) { "Cannot checkpoint verified originals for relinking" }
        ownedFolders += destination
        return target.copy(
            originals = originals.map {
                it.copy(filePath = File(destination, File(it.filePath).name).path)
            },
            mangaTitle = manga.title,
            chapterTitle = chapter.name,
            targetJobId = resolvedId,
        )
    }

    private suspend fun verifiedAttachment(
        archive: StagedTranslationArchive,
        entry: tachiyomi.domain.translation.model.TranslationArchiveEntry,
    ): File {
        val call = currentCoroutineContext()
        val file = File(archive.directory, entry.name)
        require(
            file.length() == entry.bytes && TranslationBackupCodec.sha256(file) {
                call.ensureActive()
            } == entry.sha256,
        ) { "Staged archive attachment changed after verification" }
        return file
    }

    suspend fun exportChapter(
        jobId: String,
        format: TranslationRenderedFormat,
        destination: OutputStream,
        paper: TranslationPdfPageSize = TranslationPdfPageSize.A4,
        progress: suspend (TranslationTransferProgress) -> Unit = {},
    ): TranslationTransferReport = withContext(Dispatchers.IO) {
        val chapter = requireNotNull(archiveRepository.snapshot(jobId)) { "Translation job is unavailable" }
        val settings = if (chapter.job.chapterId < 0) {
            chapter.effectiveSettings ?: chapter.job.settings
        } else {
            preferences.effectiveSettings(chapter.job.mangaId)
        }
        val expected = chapter.job.imageCount.takeIf { it > 0 } ?: chapter.images.size
        val ui = context.appGraph.uiPreferences
        val themedContext = resolveReviewTheme(context, ui.themeMode.get(), ui.appTheme.get(), ui.themeDarkAmoled.get())
        operation(
            jobId,
            TranslationStage.EXPORT,
            TranslationProgressUnit.PAGES,
            expected.toLong(),
            progress,
        ) { update ->
            val handle = exports.begin(jobId, format.name)
            var committed = false
            try {
                val results = chapter.results.associateBy { it.imageId }
                val report = handle.file.outputStream().use { output ->
                    TranslationRenderedWriter().write(
                        flow {
                            chapter.images.sortedBy { it.index }.forEach { image ->
                                emit(
                                    TranslationRenderedPage(
                                        image.id,
                                        image.index,
                                        contentComplete = results.containsKey(image.id),
                                    ) {
                                        val result =
                                            results[image.id]
                                                ?: tachiyomi.domain.translation.model.TranslationPageResult(
                                                    image.id,
                                                    image.contentHash,
                                                    image.width,
                                                    image.height,
                                                    emptyList(),
                                                    revision = 1,
                                                )
                                        AndroidTranslationPageRaster.open(
                                            themedContext,
                                            image,
                                            result,
                                            settings.style,
                                            settings.contentPolicy,
                                        )
                                    },
                                )
                            }
                        },
                        expected,
                        format,
                        output,
                        paper,
                        completeChapter = chapter.job.imageCount > 0,
                    ) { complete, _ ->
                        update(complete.toLong(), "Rendered source pages: $complete/$expected")
                    }
                }
                exports.commit(handle, report.complete, report.pagesWritten)
                committed = true
                destination.use { output ->
                    handle.file.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                }
                report
            } finally {
                if (!committed) withContext(NonCancellable) { exports.abandon(handle) }
            }
        }
    }

    private suspend fun <T> operation(
        jobId: String,
        stage: TranslationStage,
        unit: TranslationProgressUnit,
        total: Long?,
        progress: suspend (TranslationTransferProgress) -> Unit,
        parentId: String? = null,
        onStarted: (String) -> Unit = {},
        block: suspend (suspend (Long, String?) -> Unit) -> T,
    ): T {
        val now = System.currentTimeMillis()
        var operation = TranslationOperation(
            UUID.randomUUID().toString(), jobId, stage,
            parentId = parentId,
            state = TranslationOperationState.ACTIVE,
            total = total,
            unit = unit,
            startedAt = now,
            updatedAt = now,
        )
        onStarted(operation.id)
        suspend fun publish() {
            repository.saveOperation(operation)
            progress(TranslationTransferProgress(stage, operation.completed, total, unit, operation.message))
        }
        suspend fun event(
            transition: String,
        ) = repository.addEvent(
            TranslationEvent(
                "${operation.id}:$transition",
                jobId,
                operationId = operation.id,
                stage = stage.name,
                operationState = operation.state,
                message =
                operation.message ?: "${stage.label}: $transition",
                level = when (operation.state) {
                    TranslationOperationState.FAILED -> "ERROR"
                    TranslationOperationState.PARTIAL, TranslationOperationState.INTERRUPTED -> "WARN"
                    else -> "INFO"
                },
            ),
        )
        publish()
        event("started")
        try {
            val value = block { completed, message ->
                operation =
                    operation.copy(completed = completed, updatedAt = System.currentTimeMillis(), message = message)
                publish()
            }
            val complete = (value as? TranslationTransferReport)?.complete != false
            operation =
                operation.copy(
                    state = if (complete) TranslationOperationState.COMPLETED else TranslationOperationState.PARTIAL,
                    endedAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                )
            publish()
            event("finished")
            return value
        } catch (error: Exception) {
            withContext(NonCancellable) {
                operation =
                    operation.copy(
                        state = if (error is CancellationException) {
                            TranslationOperationState.INTERRUPTED
                        } else {
                            TranslationOperationState.FAILED
                        },
                        endedAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        message =
                        error.message ?: error.javaClass.simpleName,
                    )
                repository.saveOperation(operation)
                event("failed")
            }
            throw error
        }
    }
}
