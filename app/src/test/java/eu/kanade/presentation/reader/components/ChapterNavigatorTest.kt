package eu.kanade.presentation.reader.components

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ChapterNavigatorTest {
    @Test
    fun `loading sentinel and empty or single page chapters create a valid hidden slider`() {
        for (totalPages in listOf(-1, 0, 1)) {
            val state = createChapterNavigatorSliderState(currentPage = 0, totalPages = totalPages)

            assertEquals(0, state.steps)
            assertEquals(1f..1f, state.valueRange)
            assertEquals(1f, state.value)
            // ChapterNavigator updates the remembered slider even when its visual track is hidden.
            state.value = 0f
            assertEquals(1f, state.value)
        }
    }

    @Test
    fun `normal chapters retain one step per interior page and their full range`() {
        for (totalPages in listOf(2, 3, 5, 200)) {
            val state = createChapterNavigatorSliderState(currentPage = totalPages, totalPages = totalPages)

            assertEquals(totalPages - 2, state.steps)
            assertEquals(1f..totalPages.toFloat(), state.valueRange)
            assertEquals(totalPages.toFloat(), state.value)
        }
    }

    @Test
    fun `stale page from a longer chapter is clamped during loading and the next chapter`() {
        for (totalPages in listOf(20, -1, 0, 1, 5)) {
            val state = createChapterNavigatorSliderState(currentPage = 20, totalPages = totalPages)
            val lastPage = totalPages.coerceAtLeast(1).toFloat()

            assertEquals(lastPage, state.value)
            state.value = 20f
            assertEquals(lastPage, state.value)
        }
    }

    @Test
    fun `transient page values below the first page do not leave the slider range`() {
        for (currentPage in listOf(-1, 0, Int.MIN_VALUE)) {
            val state = createChapterNavigatorSliderState(currentPage = currentPage, totalPages = 5)

            assertEquals(1f, state.value)
            state.value = currentPage.toFloat()
            assertEquals(1f, state.value)
        }
    }
}
