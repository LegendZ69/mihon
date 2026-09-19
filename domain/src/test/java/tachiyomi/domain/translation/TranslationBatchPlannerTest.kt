package tachiyomi.domain.translation

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.ProviderCapabilities
import tachiyomi.domain.translation.model.ProviderSettings
import tachiyomi.domain.translation.model.TranslationConcurrency
import tachiyomi.domain.translation.model.TranslationException
import tachiyomi.domain.translation.model.TranslationFailureKind
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationMode
import tachiyomi.domain.translation.model.TranslationRequest
import tachiyomi.domain.translation.model.TranslationResponse
import tachiyomi.domain.translation.model.TranslationSettings
import tachiyomi.domain.translation.service.TranslationBatchPlanner
import tachiyomi.domain.translation.service.TranslationProvider

class TranslationBatchPlannerTest {
    @Test
    fun `Max finds largest ordered batches that fit token limit`() = runTest {
        val planner = TranslationBatchPlanner(FakeProvider())
        planner.plan(request(8, TranslationMode.MAX)).map { it.map(TranslationImage::index) } shouldBe
            listOf(listOf(0, 1, 2), listOf(3, 4, 5), listOf(6, 7))
    }

    @Test
    fun `Max image allowance is independent of per-image concurrency`() = runTest {
        val planner = TranslationBatchPlanner(FakeProvider())
        val request = request(3, TranslationMode.MAX).let {
            it.copy(settings = it.settings.copy(concurrency = TranslationConcurrency(images = 1)))
        }
        planner.plan(request).single().size shouldBe 3
    }

    @Test
    fun `Halving begins with entire chapter and splits odd chapter in reading order`() = runTest {
        val planner = TranslationBatchPlanner(FakeProvider())
        val batch = planner.plan(request(7, TranslationMode.HALVING)).single()
        val halves = TranslationBatchPlanner.halves(batch)
        halves.first.map(TranslationImage::index) shouldBe listOf(0, 1, 2, 3)
        halves.second.map(TranslationImage::index) shouldBe listOf(4, 5, 6)
    }

    @Test
    fun `authentication throttling and cancellation do not cause halving`() {
        listOf(
            TranslationFailureKind.AUTHENTICATION,
            TranslationFailureKind.RATE_LIMIT,
            TranslationFailureKind.CANCELLED,
        ).forEach {
            TranslationBatchPlanner.canSplit(TranslationException(it, "test")) shouldBe false
        }
        TranslationBatchPlanner.canSplit(
            TranslationException(TranslationFailureKind.CONTENT, "missing output"),
        ) shouldBe
            true
    }

    @Test
    fun `unknown context refuses Max without pretending a model limit`() = runTest {
        val error = runCatching {
            TranslationBatchPlanner(FakeProvider(0)).plan(request(2, TranslationMode.MAX))
        }.exceptionOrNull()
        (error as TranslationException).kind shouldBe TranslationFailureKind.CONFIGURATION
    }

    @Test
    fun `documented preset remains one image per request`() = runTest {
        TranslationBatchPlanner(FakeProvider()).plan(request(7, TranslationMode.VERTEX)).map { it.size } shouldBe
            List(7) { 1 }
    }

    private fun request(count: Int, mode: TranslationMode) = TranslationRequest(
        "job",
        "batch",
        TranslationSettings(mode = mode),
        List(count) { index ->
            TranslationImage(index.toString(), index, "/tmp/$index", "image/png", 100, 100, "$index", 100)
        },
    )

    private class FakeProvider(private val tokenLimit: Long = 350) : TranslationProvider {
        override fun capabilities(
            settings: ProviderSettings,
        ) = ProviderCapabilities("test", "2026-09-05", "https://example.test", 100, 7000000, tokenLimit, 65536)
        override suspend fun testConnection(settings: ProviderSettings) = "ok"
        override suspend fun countTokens(request: TranslationRequest) = request.images.size * 100L
        override suspend fun translate(request: TranslationRequest) = TranslationResponse(emptyList())
    }
}
