package tachiyomi.domain.translation

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationImage
import tachiyomi.domain.translation.model.TranslationPageResult
import tachiyomi.domain.translation.service.TranslationCoverage
import tachiyomi.domain.translation.service.TranslationCoverageState

class TranslationCoverageTest {
    private fun image(
        id: String,
        hash: String = id,
    ) = TranslationImage(id, id.toInt(), "/unused", "image/png", 100, 200, hash, 12)
    private fun saved(id: String, hash: String = id) = TranslationPageResult(id, hash, 100, 200, emptyList())

    @Test
    fun `partial restored identities cannot shrink the known chapter total into completed`() {
        val coverage = TranslationCoverage.calculate(
            listOf(image("0"), image("1")),
            listOf(saved("0"), saved("1")),
            knownTotal = 5,
        )
        coverage shouldBe TranslationCoverage(TranslationCoverageState.PARTIAL, 2, 5)
    }

    @Test
    fun `blank and intentionally ignored pages are complete without included regions`() {
        TranslationCoverage.calculate(listOf(image("0"), image("1")), listOf(saved("0"), saved("1"))).state shouldBe
            TranslationCoverageState.COMPLETED
    }

    @Test
    fun `old hash and duplicate saved records cannot inflate coverage`() {
        val coverage = TranslationCoverage.calculate(
            listOf(image("0"), image("1", "new")),
            listOf(saved("0"), saved("0"), saved("1", "old")),
        )
        coverage shouldBe TranslationCoverage(TranslationCoverageState.PARTIAL, 1, 2)
    }

    @Test
    fun `unavailable original list does not manufacture a complete total`() {
        TranslationCoverage.calculate(emptyList(), listOf(saved("0"))) shouldBe
            TranslationCoverage(TranslationCoverageState.UNKNOWN, 1, null)
    }
}
