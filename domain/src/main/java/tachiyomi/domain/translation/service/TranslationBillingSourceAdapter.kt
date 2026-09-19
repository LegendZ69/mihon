package tachiyomi.domain.translation.service

import tachiyomi.domain.translation.model.TranslationBillingConnection
import tachiyomi.domain.translation.model.TranslationBillingRange
import tachiyomi.domain.translation.model.TranslationBillingReport

/** Reads an explicitly configured account; implementations never provision billing resources. */
interface TranslationBillingSourceAdapter {
    suspend fun fetch(
        connection: TranslationBillingConnection,
        range: TranslationBillingRange,
    ): TranslationBillingReport
}
