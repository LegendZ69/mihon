package tachiyomi.domain.translation.service

import tachiyomi.domain.translation.model.TranslationDashboardQuery
import tachiyomi.domain.translation.model.TranslationDashboardReport

interface TranslationDashboardRepository {
    /** Bounded-memory, consistent database snapshot; no network calls or accounting estimates are generated here. */
    suspend fun aggregate(query: TranslationDashboardQuery): TranslationDashboardReport
}
