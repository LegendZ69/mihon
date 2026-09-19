package tachiyomi.domain.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationDashboardModel
import tachiyomi.domain.translation.model.TranslationDashboardQuery
import tachiyomi.domain.translation.model.TranslationMeasuredTotal
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.model.TranslationUsage
import tachiyomi.domain.translation.model.TranslationUsageRecord
import tachiyomi.domain.translation.service.TranslationDashboardAccumulator

class TranslationDashboardAccumulatorTest {
    @Test
    fun `dashboard totals pass one thousand records while preserving missing usage and separate reservations`() {
        val query = TranslationDashboardQuery(0, 30_000, buckets = 3)
        val key = TranslationDashboardModel("GROQ", "openai/gpt-oss-120b")
        val dashboard = TranslationDashboardAccumulator(query, listOf(key))
        repeat(1200) { index ->
            val time = 10_000L + index * 10
            dashboard.operation(
                TranslationOperation(
                    "request-$index", "job", TranslationStage.REQUEST,
                    state = if (index % 10 ==
                        0
                    ) {
                        TranslationOperationState.FAILED
                    } else {
                        TranslationOperationState.COMPLETED
                    },
                    attempt = if (index % 3 == 0) 2 else 1, startedAt = time - 750, endedAt = time, updatedAt = time,
                    provider = key.provider, model = key.model,
                ),
            )
            dashboard.usage(
                TranslationUsageRecord(
                    "request-$index", "job", provider = key.provider, model = key.model, time = time,
                    usage = if (index == 1199) TranslationUsage() else TranslationUsage(100, 50, 0),
                    estimatedUsd = if (index == 1199) null else "0.0001", outcomeUncertain = index == 1199,
                    reservedCurrency = if (index ==
                        1199
                    ) {
                        "SGD"
                    } else {
                        null
                    },
                    reservedAmount = if (index == 1199) "0.5" else null,
                ),
            )
        }
        repeat(4) { index ->
            dashboard.operation(
                TranslationOperation(
                    "save-$index", "job", TranslationStage.SAVE,
                    imageId = if (index ==
                        3
                    ) {
                        null
                    } else {
                        "page-${index % 2}"
                    },
                    state = TranslationOperationState.COMPLETED,
                    startedAt = 20_000,
                    endedAt = 20_010,
                    updatedAt = 20_010,
                    completed = 1,
                    total = 1,
                    unit = TranslationProgressUnit.PAGES,
                ),
            )
        }
        val result = dashboard.finish(uniqueSavedPages = 2, moreBreakdowns = true, generatedAt = 30_001)
        result.requests shouldBe 1200
        result.successfulRequests shouldBe 1080
        result.failedRequests shouldBe 120
        result.retryAttempts shouldBe 400
        result.pageSaves shouldBe 4
        result.uniqueSavedPages shouldBe 2
        result.savesWithoutPageIdentity shouldBe 1
        result.usage.attempts shouldBe 1200
        result.usage.input shouldBe TranslationMeasuredTotal("119900", 1199, 1)
        result.usage.reasoning shouldBe TranslationMeasuredTotal(null, 0, 1200)
        result.usage.estimatedUsd shouldBe TranslationMeasuredTotal("0.1199", 1199, 1)
        result.usage.reservations["SGD"] shouldBe TranslationMeasuredTotal("0.5", 1, 0)
        result.latency.observations shouldBe 1200
        result.latency.meanMillis shouldBe 750.0
        result.latency.percentileInterval(0.95) shouldBe (500L to 1000L)
        result.bins.sumOf { it.requests } shouldBe 1200
        result.bins.sumOf { it.generationAttempts } shouldBe 1200
        result.breakdowns.single().usage.attempts shouldBe 1200
        result.moreBreakdowns shouldBe true
    }

    @Test
    fun `scope and time filters exclude unrelated records while historical attribution stays unavailable`() {
        val query = TranslationDashboardQuery(10, 30, provider = "GROQ", model = "model", jobId = "job", buckets = 2)
        val dashboard = TranslationDashboardAccumulator(query)
        val operation = TranslationOperation(
            "included",
            "job",
            TranslationStage.REQUEST,
            provider = "GROQ",
            model = "model",
            updatedAt = 20,
            state = TranslationOperationState.COMPLETED,
        )
        dashboard.operation(operation)
        dashboard.operation(operation.copy(id = "old", updatedAt = 9))
        dashboard.operation(operation.copy(id = "boundary", updatedAt = 30))
        dashboard.operation(operation.copy(id = "other-model", model = "another"))
        dashboard.operation(operation.copy(id = "other-job", jobId = "another"))
        dashboard.operation(operation.copy(id = "legacy", provider = null, model = null))
        val usage = TranslationUsageRecord(
            "included",
            "job",
            provider = "GROQ",
            model = "model",
            time = 20,
            usage = TranslationUsage(inputTokens = 0, outputTokens = -1),
            estimatedUsd = "NaN",
        )
        dashboard.usage(usage)
        dashboard.usage(usage.copy(id = "other", provider = "OPENAI"))
        val result = dashboard.finish(0, false, 30)
        result.requests shouldBe 1
        result.operationsWithoutProvider shouldBe 1
        result.latency.observations shouldBe 0
        result.latency.unavailable shouldBe 1
        result.usage.attempts shouldBe 1
        result.usage.input shouldBe TranslationMeasuredTotal("0", 1, 0)
        result.usage.output shouldBe TranslationMeasuredTotal(null, 0, 1)
        result.usage.estimatedUsd shouldBe TranslationMeasuredTotal(null, 0, 1)
    }
}
