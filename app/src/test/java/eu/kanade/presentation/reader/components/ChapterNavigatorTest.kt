package eu.kanade.presentation.reader.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChapterNavigatorTest {
    @Test
    fun `loading sentinel and empty or single page chapters do not create slider inputs`() {
        for (totalPages in listOf(Int.MIN_VALUE, -1, 0, 1)) {
            assertNull(createChapterNavigatorSliderInput(currentPage = 0, totalPages = totalPages))
        }
    }

    @Test
    fun `normal chapters retain one step per interior page and their full range`() {
        for (totalPages in listOf(2, 3, 5, 200)) {
            val input = requireNotNull(
                createChapterNavigatorSliderInput(currentPage = totalPages, totalPages = totalPages),
            )

            assertEquals(totalPages - 2, input.steps)
            assertEquals(1f..totalPages.toFloat(), input.trackRange)
            assertEquals(totalPages.toFloat(), input.value)
        }
    }

    @Test
    fun `stale page from a longer chapter is clamped during loading and the next chapter`() {
        for (totalPages in listOf(20, -1, 0, 1, 5)) {
            val input = createChapterNavigatorSliderInput(currentPage = 20, totalPages = totalPages)
            if (totalPages <= 1) {
                assertNull(input)
            } else {
                assertEquals(totalPages.toFloat(), requireNotNull(input).value)
            }
        }
    }

    @Test
    fun `transient page values below the first page do not leave the slider range`() {
        for (currentPage in listOf(-1, 0, Int.MIN_VALUE)) {
            val input = requireNotNull(createChapterNavigatorSliderInput(currentPage = currentPage, totalPages = 5))

            assertEquals(1f, input.value)
        }
    }
}
