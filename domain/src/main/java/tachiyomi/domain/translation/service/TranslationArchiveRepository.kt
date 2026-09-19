package tachiyomi.domain.translation.service

import tachiyomi.domain.translation.model.TranslationArchiveChapter
import tachiyomi.domain.translation.model.TranslationArchiveConflictPolicy
import tachiyomi.domain.translation.model.TranslationArchiveLink
import tachiyomi.domain.translation.model.TranslationArchivePreview
import tachiyomi.domain.translation.model.TranslationRestoreReport

/** Database boundary for consistent chapter snapshots and one atomic, revision-preserving restoration. */
interface TranslationArchiveRepository {
    suspend fun snapshot(jobId: String): TranslationArchiveChapter?

    /** Compact identities and revision fingerprints, captured atomically before a restore is confirmed. */
    suspend fun preview(): TranslationArchivePreview = error("Archive preview checks are unavailable")

    /** The verified archive is iterated within the transaction, so later invalid input rolls back earlier pages. */
    suspend fun restore(
        chapters: Sequence<TranslationArchiveChapter>,
        policy: TranslationArchiveConflictPolicy = TranslationArchiveConflictPolicy.KEEP_LOCAL,
        links: Map<String, TranslationArchiveLink> = emptyMap(),
        preview: TranslationArchivePreview? = null,
    ): TranslationRestoreReport

    /** Insert inert historical rows atomically, preserving any already existing identities. */
    suspend fun importHistory(
        events: List<tachiyomi.domain.translation.model.TranslationEvent>,
        operations: List<tachiyomi.domain.translation.model.TranslationOperation>,
    ): Unit = error("Historical archive import is unavailable")
}
