package tachiyomi.domain.translation.model

/** In-memory optimistic-lock receipt for the local records seen when a ZIP preview opens. */
data class TranslationArchivePreview(val jobs: Map<String, TranslationArchivePreviewJob>)

data class TranslationArchivePreviewJob(val mangaId: Long, val chapterId: Long, val fingerprint: String)
