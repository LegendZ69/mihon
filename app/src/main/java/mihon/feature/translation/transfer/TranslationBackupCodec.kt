package mihon.feature.translation.transfer

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromStream
import kotlinx.serialization.json.encodeToStream
import mihon.feature.translation.provider.SanitizedCaptureOutputStream
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import tachiyomi.domain.translation.model.OverlayStyle
import tachiyomi.domain.translation.model.QualityReviewState
import tachiyomi.domain.translation.model.TranslationArchiveChapter
import tachiyomi.domain.translation.model.TranslationArchiveChapterSummary
import tachiyomi.domain.translation.model.TranslationArchiveEntry
import tachiyomi.domain.translation.model.TranslationArchiveManifest
import tachiyomi.domain.translation.model.TranslationJobState
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.QualityReviewValidation
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.FilterOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Attachments are already sanitized diagnostics or portable fonts, never original images or credentials. */
data class TranslationArchiveAttachment(val name: String, val kind: String, val file: File)

class StagedTranslationArchive internal constructor(
    val manifest: TranslationArchiveManifest,
    val directory: File,
    private val readChapter: (File, () -> Unit) -> TranslationArchiveChapter,
) : Closeable {
    var chapterSummaries: List<TranslationArchiveChapterSummary> = emptyList()
        internal set
    internal var localPreview: tachiyomi.domain.translation.model.TranslationArchivePreview? = null
    fun chapters(checkActive: () -> Unit = {}): Sequence<TranslationArchiveChapter> = manifest.entries.asSequence()
        .filter { it.kind == "chapter" }.map {
            checkActive()
            readChapter(File(directory, it.name), checkActive)
        }

    fun chapter(sourceJobId: String, checkActive: () -> Unit = {}): TranslationArchiveChapter? {
        val index = chapterSummaries.indexOfFirst { it.sourceJobId == sourceJobId }
        if (index < 0) return null
        checkActive()
        return readChapter(File(directory, manifest.entries.filter { it.kind == "chapter" }[index].name), checkActive)
    }

    override fun close() {
        directory.deleteRecursively()
    }
}

/** Versioned ZIP boundary. Limits bound individual retained records and total expanded disk storage. */
@OptIn(ExperimentalSerializationApi::class)
class TranslationBackupCodec(private val stagingRoot: File) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun write(
        chapters: Flow<TranslationArchiveChapter>,
        destination: OutputStream,
        attachments: List<TranslationArchiveAttachment> = emptyList(),
    ): TranslationArchiveManifest {
        val staging = stageDirectory()
        val coroutine = currentCoroutineContext()
        try {
            val entries = mutableListOf<TranslationArchiveEntry>()
            val ids = mutableSetOf<String>()
            var pages = 0
            val fontPaths = mutableMapOf<String, String?>()
            val warnings = mutableListOf<String>()
            fun portableFont(style: OverlayStyle): OverlayStyle {
                val path = style.fontPath ?: return style
                if (path.matches(Regex("fonts/[0-9a-f]{64}\\.font"))) {
                    if (entries.any { it.name == path } ||
                        attachments.any { it.name == path && it.kind == "font" }
                    ) {
                        return style
                    }
                    warnings +=
                        "An unresolved portable font uses the system fallback because its " +
                        "font attachment is unavailable."
                    return style.copy(fontPath = null)
                }
                val mapped = fontPaths.getOrPut(path) {
                    val font = File(path)
                    if (!font.isFile || font.length() !in 4..32L * 1024 * 1024 || !validFont(font)) {
                        warnings +=
                            "An unavailable or unsupported imported font uses the system fallback in this backup."
                        null
                    } else {
                        val hash = sha256(font) { coroutine.ensureActive() }
                        val name = "fonts/$hash.font"
                        if (entries.none { it.name == name }) {
                            val file = File(staging, name).apply { parentFile!!.mkdirs() }
                            font.inputStream().use { input ->
                                file.outputStream().use {
                                    input.copyTo(
                                        LimitedOutputStream(
                                            it,
                                            32L * 1024 * 1024,
                                        ) { coroutine.ensureActive() },
                                    )
                                }
                            }
                            require(
                                sha256(file) {
                                    coroutine.ensureActive()
                                } == hash,
                            ) { "Imported font changed while being backed up" }
                            entries += TranslationArchiveEntry(name, "font", file.length(), hash)
                        }
                        name
                    }
                }
                return style.copy(fontPath = mapped)
            }
            chapters.collect { source ->
                currentCoroutineContext().ensureActive()
                require(ids.add(source.job.id)) { "Duplicate chapter identity in backup" }
                val mapped = source.mapStyles(::portableFont) { key, identity ->
                    if (File(key).isAbsolute) {
                        fontPaths[key]
                            ?: identity.removePrefix("sha256:").takeIf { hash ->
                                entries.any {
                                    it.name ==
                                        "fonts/$hash.font"
                                }
                            }
                                ?.let { "fonts/$it.font" } ?: "unavailable-imported-font:$identity"
                    } else {
                        key
                    }
                }
                val chapter = portable(mapped)
                validate(chapter)
                val name = "chapters/${entries.size.toString().padStart(6, '0')}.json"
                val file = File(staging, name).apply { parentFile!!.mkdirs() }
                file.outputStream().use {
                    json.encodeToStream(chapter, LimitedOutputStream(it, RECORD_LIMIT) { coroutine.ensureActive() })
                }
                entries += entry(name, "chapter", file) { coroutine.ensureActive() }
                pages += chapter.results.size
                checkLimits(entries)
            }
            attachments.forEach { attachment ->
                currentCoroutineContext().ensureActive()
                validateName(attachment.name)
                require(attachment.kind in setOf("font", "logs", "captures")) { "Unsupported archive attachment" }
                require(entries.none { it.name == attachment.name }) { "Duplicate archive filename" }
                val file = File(staging, attachment.name).apply { parentFile!!.mkdirs() }
                attachment.file.inputStream().use { input ->
                    file.outputStream().use {
                        input.copyTo(LimitedOutputStream(it, ATTACHMENT_LIMIT) { coroutine.ensureActive() })
                    }
                }
                entries += entry(attachment.name, attachment.kind, file) { coroutine.ensureActive() }
                checkLimits(entries)
            }
            val manifest =
                TranslationArchiveManifest(
                    createdAt = System.currentTimeMillis(),
                    chapters = ids.size,
                    pages = pages,
                    entries = entries,
                    warnings = warnings.distinct(),
                )
            ZipOutputStream(destination).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                json.encodeToStream(manifest, LimitedOutputStream(zip, MANIFEST_LIMIT))
                zip.closeEntry()
                entries.forEach { entry ->
                    currentCoroutineContext().ensureActive()
                    zip.putNextEntry(ZipEntry(entry.name))
                    File(staging, entry.name).inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            zip.write(buffer, 0, count)
                        }
                    }
                    zip.closeEntry()
                }
            }
            return manifest
        } finally {
            staging.deleteRecursively()
        }
    }

    suspend fun read(source: InputStream): StagedTranslationArchive {
        val coroutine = currentCoroutineContext()
        val staging = stageDirectory()
        try {
            val manifest = ZipInputStream(source).use { zip ->
                require(zip.nextEntry?.name == "manifest.json") { "Translation ZIP must start with manifest.json" }
                val manifestFile = File(staging, "manifest.json")
                manifestFile.outputStream().use {
                    zip.copyTo(LimitedOutputStream(it, MANIFEST_LIMIT) { coroutine.ensureActive() })
                }
                val value = manifestFile.inputStream().use { json.decodeFromStream<TranslationArchiveManifest>(it) }
                require(
                    value.version == 1 && value.credentialPolicy == "excluded" && value.capturePolicy == "sanitized-v1",
                ) {
                    "Unsupported translation backup version or policy"
                }
                require(
                    value.entries.map {
                        it.name
                    }.distinct().size == value.entries.size,
                ) { "Duplicate archive paths" }
                checkLimits(value.entries)
                value.entries.forEach { entry ->
                    coroutine.ensureActive()
                    validateName(entry.name)
                    require(entry.kind in setOf("chapter", "font", "logs", "captures")) { "Unsupported archive entry" }
                    require(entry.sha256.matches(Regex("[0-9a-f]{64}"))) { "Invalid archive checksum" }
                    require(
                        entry.bytes <= if (entry.kind ==
                            "chapter"
                        ) {
                            RECORD_LIMIT
                        } else {
                            ATTACHMENT_LIMIT
                        },
                    ) { "Archive record is too large" }
                    require(
                        zip.nextEntry?.let {
                            it.name == entry.name && !it.isDirectory
                        } == true,
                    ) { "Missing or reordered archive entry: ${entry.name}" }
                    val file = File(staging, entry.name).apply { parentFile!!.mkdirs() }
                    file.outputStream().use {
                        zip.copyTo(LimitedOutputStream(it, entry.bytes) { coroutine.ensureActive() })
                    }
                    require(
                        file.length() == entry.bytes && sha256(file) {
                            coroutine.ensureActive()
                        } == entry.sha256,
                    ) { "Backup checksum mismatch: ${entry.name}" }
                }
                require(zip.nextEntry == null) { "Unlisted archive entry" }
                value
            }
            coroutine.ensureActive()
            val archive = StagedTranslationArchive(manifest, staging) { file, checkActive ->
                val entry = manifest.entries.single { File(staging, it.name) == file }
                require(file.length() == entry.bytes && sha256(file, checkActive) == entry.sha256) {
                    "Staged backup changed after verification"
                }
                file.inputStream().use { input ->
                    json.decodeFromStream<TranslationArchiveChapter>(object : java.io.FilterInputStream(input) {
                        override fun read(): Int {
                            checkActive()
                            return super.read()
                        }
                        override fun read(
                            bytes: ByteArray,
                            offset: Int,
                            length: Int,
                        ): Int {
                            checkActive()
                            return super.read(bytes, offset, length)
                        }
                    })
                }.also {
                    checkActive()
                    validate(it)
                }
            }
            val ids = mutableSetOf<String>()
            var pages = 0
            val summaries = mutableListOf<TranslationArchiveChapterSummary>()
            archive.chapters { coroutine.ensureActive() }.forEach { chapter ->
                require(ids.add(chapter.job.id)) { "Duplicate chapter identity" }
                pages += chapter.results.size
                summaries +=
                    TranslationArchiveChapterSummary(
                        chapter.job.id,
                        chapter.job.mangaTitle,
                        chapter.job.chapterTitle,
                        chapter.results.size,
                        chapter.job.imageCount.takeIf {
                            it >
                                0
                        },
                        chapter.effectiveSettings != null,
                    )
            }
            archive.chapterSummaries = summaries
            require(manifest.chapters == ids.size && manifest.pages == pages) { "Backup counts do not match records" }
            return archive
        } catch (failure: Throwable) {
            staging.deleteRecursively()
            throw failure
        }
    }

    private fun stageDirectory() = File(stagingRoot, "archive-${UUID.randomUUID()}").apply {
        check(mkdirs()) { "Cannot create private archive staging" }
    }

    private fun entry(name: String, kind: String, file: File, checkActive: () -> Unit) = TranslationArchiveEntry(
        name,
        kind,
        file.length(),
        sha256(file, checkActive),
    )

    private fun checkLimits(entries: List<TranslationArchiveEntry>) {
        require(
            entries.size <= 10_000 && entries.all { it.bytes in 0..ATTACHMENT_LIMIT } &&
                entries.sumOf { it.bytes } <= ARCHIVE_LIMIT,
        ) { "Translation backup exceeds expanded storage limits" }
    }

    private fun validateName(name: String) {
        require(
            name.length in 1..200 && name.matches(Regex("(?:chapters|fonts|diagnostics)/[A-Za-z0-9._-]+")) &&
                ".." !in name,
        ) { "Unsafe translation archive path" }
    }

    private fun validate(chapter: TranslationArchiveChapter) {
        require(
            chapter.job.id.isNotBlank() && chapter.images.map {
                it.id
            }.distinct().size == chapter.images.size,
        ) { "Invalid backup image identities" }
        require(chapter.results.map { it.imageId }.distinct().size == chapter.results.size) { "Duplicate saved page" }
        listOfNotNull(
            chapter.job.settings,
            chapter.effectiveSettings,
            chapter.job.archiveMetadata?.effectiveSettings,
        ).plus(chapter.reviews.mapNotNull { it.executionSettings }).forEach { settings ->
            require(settings == portableSettings(settings)) { "Backup contains private or resumable settings" }
        }
        require(chapter.job.replacesJobId == null) { "Backup contains an operational replacement relationship" }
        require(
            chapter.job.state in setOf(TranslationJobState.PAUSED, TranslationJobState.COMPLETED) &&
                !chapter.job.settings.autoTranslate &&
                chapter.job.settings.chaptersAhead == 0 &&
                chapter.job.reviewReturnState == null &&
                chapter.job.reviewImageIds == null,
        ) { "Backup contains resumable paid work" }
        val images = chapter.images.associateBy { it.id }
        require(images.size == chapter.results.size) { "Backup contains unfinished images" }
        chapter.results.forEach { result ->
            val image = requireNotNull(images[result.imageId]) { "Saved page has no image identity" }
            require(
                image.filePath.isEmpty() && image.contentHash == result.imageHash && image.width == result.width &&
                    image.height == result.height &&
                    result.revision >= 1,
            ) { "Invalid backup image identity or source path" }
            QualityReviewValidation.validatePage(result)
        }
        require(chapter.reviews.map { it.id }.distinct().size == chapter.reviews.size) { "Duplicate review identity" }
        chapter.reviews.forEach { review ->
            val image = requireNotNull(images[review.imageId]) { "Review has no saved original" }
            require(
                !review.state.pending &&
                    review.jobId == chapter.job.id &&
                    review.beforeResult.imageId == review.imageId,
            ) {
                "Invalid backup review checkpoint"
            }
            require(
                review.beforeResult.imageHash == image.contentHash && review.beforeResult.width == image.width &&
                    review.beforeResult.height == image.height &&
                    review.sourceRevision == review.beforeResult.revision,
            ) {
                "Review Undo baseline belongs to a different original or revision"
            }
            require(
                review.renderEvidence?.let { evidence ->
                    evidence.original.filePath.isEmpty() && evidence.preview.filePath.isEmpty() &&
                        evidence.original.id == image.id && evidence.original.contentHash == image.contentHash &&
                        evidence.original.width == image.width && evidence.original.height == image.height &&
                        evidence.sourceRevision == review.sourceRevision
                } != false,
            ) { "Backup contains invalid original evidence or private paths" }
            QualityReviewValidation.validatePage(review.beforeResult)
        }
    }

    private fun portable(source: TranslationArchiveChapter): TranslationArchiveChapter {
        val images = source.images.filter { image ->
            source.results.any { it.imageId == image.id }
        }.map { it.copy(filePath = "") }
        val complete = source.job.imageCount > 0 && source.results.size == source.job.imageCount
        return source.copy(
            job = source.job.copy(
                settings = portableSettings(source.job.settings),
                replacesJobId = null,
                archiveMetadata = source.job.archiveMetadata?.let {
                    it.copy(effectiveSettings = it.effectiveSettings?.let(::portableSettings))
                },
                state = if (complete) TranslationJobState.COMPLETED else TranslationJobState.PAUSED,
                completedImages = source.results.size,
                reviewReturnState = null,
                reviewImageIds = null,
                message = "Restored saved translations; unfinished work is not scheduled.",
            ),
            images = images,
            replacesSourceJobId =
            source.replacesSourceJobId ?: source.job.replacesJobId
                ?: source.job.archiveMetadata?.sourceReplacesJobId,
            effectiveSettings = source.effectiveSettings?.let(::portableSettings),
            results = source.results.map(::portableResult),
            reviews = source.reviews.filter { it.imageId in images.map { image -> image.id } }.map { review ->
                review.copy(
                    beforeResult = portableResult(review.beforeResult),
                    executionSettings = review.executionSettings?.let(::portableSettings),
                    state = if (review.state.pending) QualityReviewState.INCOMPLETE else review.state,
                    message = if (review.state.pending) {
                        "Pending review was not resumed from backup; retained attempts are historical."
                    } else {
                        review.message
                    },
                    renderStyle = review.renderStyle?.let(::portableStyle),
                    renderEvidence = review.renderEvidence?.let { evidence ->
                        evidence.copy(
                            original = evidence.original.copy(filePath = ""),
                            preview = evidence.preview.copy(filePath = ""),
                            presentation = evidence.presentation.copy(
                                requestedStyle = portableStyle(evidence.presentation.requestedStyle),
                                resolvedStyle = portableStyle(evidence.presentation.resolvedStyle),
                                requestedRegionStyles = evidence.presentation.requestedRegionStyles.mapValues {
                                    it.value?.let(::portableStyle)
                                },
                                resolvedRegionStyles = evidence.presentation.resolvedRegionStyles.mapValues {
                                    portableStyle(it.value)
                                },
                            ),
                        )
                    },
                )
            },
        )
    }

    private fun portableSettings(settings: TranslationSettings): TranslationSettings {
        val provider = settings.provider
        val advanced = ByteArrayOutputStream().also { output ->
            SanitizedCaptureOutputStream(output).use { sanitizer ->
                sanitizer.write(provider.advancedJson.toByteArray())
                sanitizer.finish(true)
            }
        }.toString(Charsets.UTF_8.name())
        return settings.copy(
            provider = provider.copy(
                credentialId = "",
                extraHeaders = emptyMap(),
                baseUrl =
                provider.baseUrl.toHttpUrlOrNull()?.newBuilder()?.username(
                    "",
                )?.password("")?.query(null)?.fragment(null)?.build()?.toString()?.removeSuffix("/")
                    ?: "",
                advancedJson = advanced,
            ),
            autoTranslate = false,
            chaptersAhead = 0,
            style = portableStyle(settings.style),
        )
    }

    private fun portableResult(result: TranslationPageResult) = result.copy(
        regions = result.regions.map {
            it.copy(style = it.style?.let(::portableStyle))
        },
    )
    private fun portableStyle(
        style: OverlayStyle,
    ) = style.copy(fontPath = style.fontPath?.takeIf { it.matches(Regex("fonts/[0-9a-f]{64}\\.font")) })

    companion object {
        private const val RECORD_LIMIT = 16L * 1024 * 1024
        private const val ATTACHMENT_LIMIT = 128L * 1024 * 1024
        private const val MANIFEST_LIMIT = 4L * 1024 * 1024
        private const val ARCHIVE_LIMIT = 512L * 1024 * 1024

        internal fun validFont(file: File): Boolean = runCatching {
            val prefix = ByteArray(4)
            file.inputStream().use { java.io.DataInputStream(it).readFully(prefix) }
            prefix.contentEquals(byteArrayOf(0, 1, 0, 0)) || prefix.decodeToString() in setOf("OTTO", "ttcf", "true")
        }.getOrDefault(false)

        fun sha256(file: File, checkActive: () -> Unit = {}): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    checkActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

private class LimitedOutputStream(
    output: OutputStream,
    private val maximum: Long,
    private val checkActive: () -> Unit = {
    },
) : FilterOutputStream(output) {
    private var count = 0L
    override fun write(
        value: Int,
    ) {
        checkActive()
        require(count < maximum) { "Archive entry exceeds its bounded size" }
        out.write(value)
        count++
    }
    override fun write(
        value: ByteArray,
        offset: Int,
        length: Int,
    ) {
        checkActive()
        require(length <= maximum - count) { "Archive entry exceeds its bounded size" }
        out.write(value, offset, length)
        count +=
            length
    }
    override fun close() = flush()
}
