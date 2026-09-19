package tachiyomi.domain.translation.service

import tachiyomi.domain.translation.model.TranslationDashboardBin
import tachiyomi.domain.translation.model.TranslationDashboardBreakdown
import tachiyomi.domain.translation.model.TranslationDashboardModel
import tachiyomi.domain.translation.model.TranslationDashboardQuery
import tachiyomi.domain.translation.model.TranslationDashboardReport
import tachiyomi.domain.translation.model.TranslationDashboardStage
import tachiyomi.domain.translation.model.TranslationDashboardUsage
import tachiyomi.domain.translation.model.TranslationLatencyBucket
import tachiyomi.domain.translation.model.TranslationLatencySummary
import tachiyomi.domain.translation.model.TranslationMeasuredTotal
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.model.TranslationUsageRecord
import java.math.BigDecimal
import java.math.BigInteger

/** Accepts each durable primary key once from a database snapshot; storage owns replay deduplication. */
class TranslationDashboardAccumulator(
    private val query: TranslationDashboardQuery,
    models: List<TranslationDashboardModel> = emptyList(),
) {
    init {
        query.validate()
        require(models.size <= query.breakdownLimit && models.distinct().size == models.size)
    }
    private val stages = linkedMapOf<TranslationStage, StageCounter>()
    private val totalUsage = UsageCounter()
    private val modelUsage = models.associateWith { UsageCounter() }
    private val bins = List(query.buckets) { BinCounter(boundary(it), boundary(it + 1)) }
    private val latency = LatencyCounter()
    private var operations = 0L
    private var unattributed = 0L
    private var requests = 0L
    private var successes = 0L
    private var failures = 0L
    private var retries = 0L
    private var pageSaves = 0L
    private var missingPageIds = 0L
    private var partials = 0L

    fun operation(value: TranslationOperation) {
        if (value.updatedAt !in query.since until query.until ||
            (query.jobId != null && value.jobId != query.jobId)
        ) {
            return
        }
        if (value.provider == null || value.model == null) unattributed++
        if ((query.provider != null && value.provider != query.provider) ||
            (query.model != null && value.model != query.model)
        ) {
            return
        }
        operations++
        stages.getOrPut(value.stage) { StageCounter(value.stage) }.add(value)
        val bin = bin(value.updatedAt)
        if (value.stage == TranslationStage.REQUEST) {
            requests++
            bin.requests++
            if (value.state == TranslationOperationState.COMPLETED) {
                successes++
                bin.successes++
            }
            if (value.state == TranslationOperationState.FAILED) {
                failures++
                bin.failures++
            }
            if ((value.attempt ?: 1) > 1) {
                retries++
                bin.retries++
            }
            latency.add(value)
        }
        if (value.stage == TranslationStage.SAVE && value.state == TranslationOperationState.COMPLETED) {
            pageSaves++
            bin.saves++
            if (value.imageId == null) missingPageIds++
        }
        if (value.stage == TranslationStage.VALIDATION && value.state == TranslationOperationState.PARTIAL) partials++
    }

    fun usage(value: TranslationUsageRecord) {
        if (value.time !in query.since until query.until || (query.jobId != null && value.jobId != query.jobId) ||
            (query.provider != null && value.provider != query.provider) ||
            (query.model != null && value.model != query.model)
        ) {
            return
        }
        totalUsage.add(value)
        modelUsage[TranslationDashboardModel(value.provider, value.model)]?.add(value)
        bin(value.time).also {
            it.attempts++
            it.estimates.add(decimal(value.estimatedUsd))
        }
    }

    fun finish(uniqueSavedPages: Long, moreBreakdowns: Boolean, generatedAt: Long): TranslationDashboardReport {
        require(uniqueSavedPages >= 0 && uniqueSavedPages <= pageSaves)
        return TranslationDashboardReport(
            query, generatedAt, operations, requests, successes, failures, retries,
            pageSaves, uniqueSavedPages, missingPageIds, partials, unattributed,
            stages.values.map { it.result() }, latency.result(), totalUsage.result(), bins.map { it.result() },
            modelUsage.map { (key, value) -> TranslationDashboardBreakdown(key, value.result()) }, moreBreakdowns,
        )
    }

    private fun boundary(index: Int): Long {
        val span = query.until - query.since
        return query.since + (span / query.buckets) * index + (span % query.buckets) * index / query.buckets
    }

    private fun bin(time: Long): BinCounter {
        var index = (((time - query.since).toDouble() / (query.until - query.since)) * query.buckets).toInt().coerceIn(
            bins.indices,
        )
        while (index > 0 && time < bins[index].since) index--
        while (index < bins.lastIndex && time >= bins[index].until) index++
        return bins[index]
    }

    private class Total {
        private var sum = BigDecimal.ZERO
        private var observed = 0L
        private var unavailable = 0L
        fun add(value: BigDecimal?) {
            if (value == null) {
                unavailable++
            } else {
                sum += value
                observed++
            }
        }
        fun result() = TranslationMeasuredTotal(
            if (observed ==
                0L
            ) {
                null
            } else {
                sum.stripTrailingZeros().toPlainString()
            },
            observed,
            unavailable,
        )
    }

    private class UsageCounter {
        private var attempts = 0L
        private var uncertain = 0L
        private val input = Total()
        private val output = Total()
        private val cached = Total()
        private val reasoning = Total()
        private val estimates = Total()

        // Exactly three uppercase ASCII letters bounds this map independently of record count.
        private val reservations = sortedMapOf<String, Total>()
        private var unknownCurrency = 0L
        fun add(value: TranslationUsageRecord) {
            attempts++
            fun token(value: Long?) = value?.takeIf { it >= 0 }?.toBigDecimal()
            input.add(token(value.usage.inputTokens))
            output.add(token(value.usage.outputTokens))
            cached.add(token(value.usage.cachedTokens))
            reasoning.add(token(value.usage.reasoningTokens))
            estimates.add(decimal(value.estimatedUsd))
            if (value.outcomeUncertain) {
                uncertain++
                val currency = value.reservedCurrency?.takeIf { it.matches(Regex("[A-Z]{3}")) }
                if (currency == null) {
                    unknownCurrency++
                } else {
                    reservations.getOrPut(currency) { Total() }.add(
                        decimal(value.reservedAmount)?.takeIf {
                            it >=
                                BigDecimal.ZERO
                        },
                    )
                }
            }
        }
        fun result() = TranslationDashboardUsage(
            attempts, uncertain, input.result(), output.result(), cached.result(),
            reasoning.result(), estimates.result(), reservations.mapValues { it.value.result() }, unknownCurrency,
        )
    }

    private class LatencyCounter {
        private var sum = BigInteger.ZERO
        private var count = 0L
        private var unavailable = 0L
        private val histogram = LongArray(LATENCY_BOUNDS.size + 1)
        fun add(operation: TranslationOperation) {
            val start = operation.startedAt
            val end = operation.endedAt
            if (start == null || end == null || start < 0 || end < start) {
                unavailable++
                return
            }
            val duration = end - start
            sum += duration.toBigInteger()
            count++
            val bucket = LATENCY_BOUNDS.indexOfFirst { duration <= it }.takeIf { it >= 0 } ?: LATENCY_BOUNDS.size
            histogram[bucket]++
        }
        fun result() = TranslationLatencySummary(
            count,
            unavailable,
            sum.takeIf { count > 0 }?.toString(),
            if (count == 0L) null else sum.toDouble() / count,
            histogram.mapIndexed { index, value -> TranslationLatencyBucket(LATENCY_BOUNDS.getOrNull(index), value) },
        )
    }

    private class StageCounter(private val stage: TranslationStage) {
        private val states = linkedMapOf<TranslationOperationState, Long>()
        private val durations = LatencyCounter()
        private val units = linkedMapOf<TranslationProgressUnit, BigInteger>()
        fun add(value: TranslationOperation) {
            states[value.state] = (states[value.state] ?: 0) + 1
            durations.add(value)
            if (value.completed >=
                0
            ) {
                units[value.unit] = (units[value.unit] ?: BigInteger.ZERO) + value.completed.toBigInteger()
            }
        }
        fun result() = TranslationDashboardStage(
            stage,
            states.toMap(),
            durations.result(),
            units.mapValues {
                it.value.toString()
            },
        )
    }

    private class BinCounter(val since: Long, val until: Long) {
        var requests = 0L
        var successes = 0L
        var failures = 0L
        var retries = 0L
        var saves = 0L
        var attempts = 0L
        val estimates = Total()
        fun result() = TranslationDashboardBin(
            since,
            until,
            requests,
            successes,
            failures,
            retries,
            saves,
            attempts,
            estimates.result(),
        )
    }

    companion object {
        private val LATENCY_BOUNDS =
            longArrayOf(16, 33, 50, 100, 250, 500, 1000, 2000, 5000, 10_000, 30_000, 60_000, 120_000, 300_000)
        private fun decimal(value: String?): BigDecimal? = value?.takeIf { it.length <= 128 }?.toBigDecimalOrNull()
            ?.takeIf { it.precision() <= 38 && it.scale() in -18..18 }
    }
}
