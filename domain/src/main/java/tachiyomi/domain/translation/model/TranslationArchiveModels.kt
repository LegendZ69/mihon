package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

/** A chapter snapshot contains durable results/history, never a credential or a queued transport operation. */
@Serializable
data class TranslationArchiveChapter(
    val job: TranslationJob,
    val images: List<TranslationImage>,
    val results: List<TranslationPageResult>,
    val reviews: List<QualityReviewCheckpoint> = emptyList(),
    val effectiveSettings: TranslationSettings? = null,
    val replacesSourceJobId: String? = null,
)

@Serializable
data class TranslationArchiveEntry(
    val name: String,
    val kind: String,
    val bytes: Long,
    val sha256: String,
)

@Serializable
data class TranslationArchiveManifest(
    val version: Int = 1,
    val createdAt: Long,
    val chapters: Int,
    val pages: Int,
    val entries: List<TranslationArchiveEntry>,
    val credentialPolicy: String = "excluded",
    val capturePolicy: String = "sanitized-v1",
    val warnings: List<String> = emptyList(),
)

enum class TranslationArchiveConflictPolicy { KEEP_LOCAL, REPLACE }

data class TranslationArchiveLink(
    val mangaId: Long,
    val chapterId: Long,
    val originals: List<TranslationImage> = emptyList(),
    val mangaTitle: String? = null,
    val chapterTitle: String? = null,
    val targetJobId: String? = null,
    /** Null means legacy restore; entries assert the previewed revision or absence at commit. */
    val expectedRevisions: Map<String, Long?>? = null,
    val replaceImageIds: Set<String> = emptySet(),
)

data class TranslationRestoreReport(
    val importedPages: Int,
    val identicalPages: Int,
    val preservedConflicts: Int,
    val linkedJobs: Int,
    val unlinkedJobs: Int,
    val jobIds: List<String>,
    val warnings: List<String> = emptyList(),
)

data class TranslationBackupOptions(val includeLogs: Boolean = false, val includeCaptures: Boolean = false)

enum class TranslationRenderedFormat { CBZ, PDF }

enum class TranslationPdfPageSize(val widthPoints: Int, val heightPoints: Int) {
    A4(595, 842),
    LETTER(612, 792),
}

data class TranslationTransferReport(
    val complete: Boolean,
    val pagesWritten: Int,
    val pagesExpected: Int,
    val bytesWritten: Long,
    val missingOriginals: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/** Import provenance survives ordinary job updates; presentation settings are offered for explicit application only. */
@Serializable
data class TranslationArchiveProvenance(
    val sourceJobId: String,
    val sourceRevisions: Map<String, Long>,
    val importedAt: Long,
    val effectiveSettings: TranslationSettings? = null,
    val sourceReplacesJobId: String? = null,
    val structuredFiles: Boolean = false,
    val structuredPages: Map<String, StructuredImportProvenance> = emptyMap(),
)

data class TranslationTransferProgress(
    val stage: TranslationStage,
    val completed: Long,
    val total: Long?,
    val unit: TranslationProgressUnit,
    val message: String? = null,
)

data class TranslationArchiveChapterSummary(
    val sourceJobId: String,
    val mangaTitle: String,
    val chapterTitle: String,
    val savedPages: Int,
    val expectedPages: Int?,
    val hasEffectiveSettings: Boolean,
)
