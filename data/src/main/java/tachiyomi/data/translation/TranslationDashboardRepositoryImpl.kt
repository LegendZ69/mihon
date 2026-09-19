package tachiyomi.data.translation

import app.cash.sqldelight.async.coroutines.awaitAsList
import app.cash.sqldelight.async.coroutines.awaitAsOne
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesBinding
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import tachiyomi.data.Database
import tachiyomi.domain.translation.model.TranslationDashboardModel
import tachiyomi.domain.translation.model.TranslationDashboardQuery
import tachiyomi.domain.translation.model.TranslationDashboardReport
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationUsageRecord
import tachiyomi.domain.translation.service.TranslationDashboardAccumulator
import tachiyomi.domain.translation.service.TranslationDashboardRepository

/** A consistent SQL snapshot is read in fixed pages; app memory never contains a full operation/usage history. */
@Inject
@SingleIn(AppScope::class)
@ContributesBinding(AppScope::class)
class TranslationDashboardRepositoryImpl(private val database: Database) : TranslationDashboardRepository {
    private val json = Json { ignoreUnknownKeys = true }
    override suspend fun aggregate(
        query: TranslationDashboardQuery,
    ): TranslationDashboardReport = withContext(Dispatchers.IO) {
        query.validate()
        database.transactionWithResult {
            val queries = database.translationDashboardQueries
            val models = queries.dashboardModels(
                query.since,
                query.until,
                query.jobId,
                query.provider,
                query.model,
                query.breakdownLimit.toLong() + 1,
                query.breakdownOffset,
            ).awaitAsList()
            val collector = TranslationDashboardAccumulator(
                query,
                models.take(query.breakdownLimit).map { TranslationDashboardModel(it.provider, it.model) },
            )
            var afterId: String? = null
            do {
                currentCoroutineContext().ensureActive()
                val rows = queries.dashboardOperations(
                    query.since,
                    query.until,
                    query.jobId,
                    afterId,
                    PAGE_SIZE,
                ).awaitAsList()
                rows.forEach { collector.operation(json.decodeFromString<TranslationOperation>(it.payload)) }
                afterId = rows.lastOrNull()?.id
            } while (afterId != null)
            afterId = null
            do {
                currentCoroutineContext().ensureActive()
                val rows = queries.dashboardUsage(
                    query.since,
                    query.until,
                    query.jobId,
                    query.provider,
                    query.model,
                    afterId,
                    PAGE_SIZE,
                ).awaitAsList()
                rows.forEach { collector.usage(json.decodeFromString<TranslationUsageRecord>(it.payload)) }
                afterId = rows.lastOrNull()?.id
            } while (afterId != null)
            val uniquePages = queries.dashboardSavedPages(
                query.since,
                query.until,
                query.jobId,
                query.provider,
                query.model,
            ).awaitAsOne()
            collector.finish(uniquePages, models.size > query.breakdownLimit, System.currentTimeMillis())
        }
    }

    companion object {
        private const val PAGE_SIZE = 256L
    }
}
