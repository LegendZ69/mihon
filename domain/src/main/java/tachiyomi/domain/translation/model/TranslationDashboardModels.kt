package tachiyomi.domain.translation.model

/** Time bounds are inclusive/exclusive; report totals cover every matching durable row. */
data class TranslationDashboardQuery(
    val since: Long,
    val until: Long,
    val provider: String? = null,
    val model: String? = null,
    val jobId: String? = null,
    val buckets: Int = 24,
    val breakdownOffset: Long = 0,
    val breakdownLimit: Int = 25,
) {
    fun validate() {
        require(since >= 0 && until > since)
        require(buckets in 1..96 && breakdownOffset >= 0 && breakdownLimit in 1..100)
        require(provider == null || provider.length <= 256)
        require(model == null || model.length <= 512)
    }
}

/** Decimal strings preserve amounts and large token sums without floating-point rounding. */
data class TranslationMeasuredTotal(val value: String? = null, val observed: Long = 0, val unavailable: Long = 0)

data class TranslationLatencyBucket(val upperMillis: Long?, val observations: Long)

data class TranslationLatencySummary(
    val observations: Long = 0,
    val unavailable: Long = 0,
    val sumMillis: String? = null,
    val meanMillis: Double? = null,
    val histogram: List<TranslationLatencyBucket> = emptyList(),
) {
    /** A histogram interval, not an exact percentile. null upper means greater than the last finite bound. */
    fun percentileInterval(percentile: Double): Pair<Long, Long?>? {
        require(percentile > 0 && percentile <= 1)
        if (observations == 0L) return null
        val target = kotlin.math.ceil(observations * percentile).toLong().coerceAtLeast(1)
        var count = 0L
        var lower = 0L
        histogram.forEach { bucket ->
            count += bucket.observations
            if (count >= target) return lower to bucket.upperMillis
            lower = bucket.upperMillis ?: lower
        }
        return null
    }
}

data class TranslationDashboardUsage(
    val attempts: Long = 0,
    val uncertain: Long = 0,
    val input: TranslationMeasuredTotal = TranslationMeasuredTotal(),
    val output: TranslationMeasuredTotal = TranslationMeasuredTotal(),
    val cached: TranslationMeasuredTotal = TranslationMeasuredTotal(),
    val reasoning: TranslationMeasuredTotal = TranslationMeasuredTotal(),
    val estimatedUsd: TranslationMeasuredTotal = TranslationMeasuredTotal(),
    val reservations: Map<String, TranslationMeasuredTotal> = emptyMap(),
    val unknownReservationCurrency: Long = 0,
)

data class TranslationDashboardStage(
    val stage: TranslationStage,
    val states: Map<TranslationOperationState, Long>,
    val durations: TranslationLatencySummary,
    val completedUnits: Map<TranslationProgressUnit, String>,
)

data class TranslationDashboardBin(
    val since: Long,
    val until: Long,
    val requests: Long = 0,
    val successfulRequests: Long = 0,
    val failedRequests: Long = 0,
    val retryAttempts: Long = 0,
    val pageSaves: Long = 0,
    val generationAttempts: Long = 0,
    val estimatedUsd: TranslationMeasuredTotal = TranslationMeasuredTotal(),
)

data class TranslationDashboardModel(val provider: String, val model: String)
data class TranslationDashboardBreakdown(val key: TranslationDashboardModel, val usage: TranslationDashboardUsage)

data class TranslationDashboardReport(
    val query: TranslationDashboardQuery,
    val generatedAt: Long,
    val operations: Long = 0,
    val requests: Long = 0,
    val successfulRequests: Long = 0,
    val failedRequests: Long = 0,
    val retryAttempts: Long = 0,
    val pageSaves: Long = 0,
    val uniqueSavedPages: Long = 0,
    val savesWithoutPageIdentity: Long = 0,
    val partialOutputs: Long = 0,
    val operationsWithoutProvider: Long = 0,
    val stages: List<TranslationDashboardStage> = emptyList(),
    val latency: TranslationLatencySummary = TranslationLatencySummary(),
    val usage: TranslationDashboardUsage = TranslationDashboardUsage(),
    val bins: List<TranslationDashboardBin> = emptyList(),
    val breakdowns: List<TranslationDashboardBreakdown> = emptyList(),
    val moreBreakdowns: Boolean = false,
)

/** One canonical accounting label shared by operations and request usage. */
fun ProviderSettings.accountingProvider(): String =
    if (kind == TranslationProviderKind.OPENAI &&
        baseUrl.trimEnd('/') == "https://api.groq.com/openai/v1"
    ) {
        "GROQ"
    } else {
        kind.name
    }
