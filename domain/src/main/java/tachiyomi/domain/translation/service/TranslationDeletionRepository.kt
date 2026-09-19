package tachiyomi.domain.translation.service

import tachiyomi.domain.translation.model.TranslationDeletionScope
import tachiyomi.domain.translation.model.TranslationDeletionSnapshot

/** Exact snapshots and compare-before-delete in one database transaction. No source chapter tables are touched. */
interface TranslationDeletionRepository {
    suspend fun deletionSnapshots(jobIds: Set<String>): List<TranslationDeletionSnapshot>
    suspend fun deleteRecords(
        expected: List<TranslationDeletionSnapshot>,
        scopes: Set<TranslationDeletionScope>,
    ): Boolean
}
