package tachiyomi.domain.translation

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import tachiyomi.domain.translation.model.TranslationOperation
import tachiyomi.domain.translation.model.TranslationOperationState
import tachiyomi.domain.translation.model.TranslationProgressUnit
import tachiyomi.domain.translation.model.TranslationStage
import tachiyomi.domain.translation.service.TranslationOperationRecorder
import tachiyomi.domain.translation.service.TranslationRepository

class TranslationOperationRecorderTest {
    @Test
    fun `acquisition starts with unknown total and records the acquired page count on completion`() = runTest {
        val records = mutableListOf<TranslationOperation>()
        val repository = mockk<TranslationRepository>(relaxed = true)
        coEvery { repository.saveOperation(any()) } coAnswers { records += firstArg<TranslationOperation>() }
        TranslationOperationRecorder(repository).run(
            TranslationOperation(
                "acquire",
                "chapter",
                TranslationStage.ACQUISITION,
                unit = TranslationProgressUnit.PAGES,
            ),
            completedUnits = { pages: List<String> -> pages.size.toLong() },
        ) { listOf("page1", "page2", "page3") }
        records.first().total shouldBe null
        records.last().completed shouldBe 3L
        records.last().total shouldBe 3L
    }

    @Test
    fun `local OCR completes as OCR and does not claim a translation was saved`() = runTest {
        val records = mutableListOf<TranslationOperation>()
        val repository = mockk<TranslationRepository>(relaxed = true)
        coEvery { repository.saveOperation(any()) } coAnswers { records += firstArg<TranslationOperation>() }
        val output = TranslationOperationRecorder(repository).run(
            TranslationOperation(
                "ocr-1",
                "chapter",
                TranslationStage.LOCAL_OCR,
                imageId = "page-1",
                total = 1,
                unit = TranslationProgressUnit.PAGES,
            ),
        ) { "recognized source" }
        output shouldBe "recognized source"
        records.map { it.state } shouldBe listOf(TranslationOperationState.ACTIVE, TranslationOperationState.COMPLETED)
        records.last().stage shouldBe TranslationStage.LOCAL_OCR
        records.last().completed shouldBe 1
    }
}
