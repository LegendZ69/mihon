package tachiyomi.domain.translation.service

import tachiyomi.domain.translation.model.TranslationJob
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.accountingProvider

/** Pin attribution when an operation is created; later job edits cannot rewrite measured history. */
object TranslationOperationAttribution {
    fun capture(
        operation: TranslationOperation,
        existing: TranslationOperation?,
        job: TranslationJob?,
    ): TranslationOperation = when {
        existing != null -> operation.copy(provider = existing.provider, model = existing.model)
        operation.provider != null || operation.model != null || job == null -> operation
        else -> operation.copy(
            provider = job.settings.provider.accountingProvider(),
            model = job.settings.provider.model,
        )
    }
}
