package com.novelradar.app.feature.reader

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Chapter
import org.junit.Test

class ReadingWindowPolicyTest {
    @Test fun firstChapterKeepsActiveAndNextTwo() {
        assertThat(readingWindowIds(catalog(), "c0")).containsExactly("c0", "c1", "c2").inOrder()
    }

    @Test fun middleChapterKeepsPreviousActiveAndNextTwo() {
        assertThat(readingWindowIds(catalog(), "c2")).containsExactly("c1", "c2", "c3", "c4").inOrder()
    }

    @Test fun lastChapterKeepsPreviousAndActive() {
        assertThat(readingWindowIds(catalog(), "c5")).containsExactly("c4", "c5").inOrder()
    }

    @Test fun emptyCatalogHasNoWindow() {
        assertThat(readingWindowIds(emptyList(), "c0")).isEmpty()
    }

    @Test fun unknownChapterHasNoWindow() {
        assertThat(readingWindowIds(catalog(), "missing")).isEmpty()
    }

    @Test fun reorderedCatalogUsesChapterOrder() {
        assertThat(readingWindowIds(catalog().reversed(), "c2")).containsExactly("c1", "c2", "c3", "c4").inOrder()
    }

    @Test fun gappedIndicesUseActualCatalogNeighbors() {
        val chapters = listOf(chapter("first", 2), chapter("active", 20), chapter("next", 99),
            chapter("later", 120), chapter("outside", 800))

        assertThat(readingWindowIds(chapters, "active")).containsExactly("first", "active", "next", "later").inOrder()
    }

    @Test fun equalIndicesUseChapterIdOrder() {
        val chapters = listOf(chapter("d", 3), chapter("b", 3), chapter("a", 3), chapter("c", 3), chapter("e", 3))

        assertThat(readingWindowIds(chapters, "b")).containsExactly("a", "b", "c", "d").inOrder()
    }

    @Test fun everyKnownActiveChapterIsIncludedAndWindowNeverExceedsFour() {
        val chapters = catalog()
        for (active in chapters) {
            val window = readingWindowIds(chapters, active.chapterId)
            assertThat(window).contains(active.chapterId)
            assertThat(window.size).isAtMost(4)
            assertThat(chapters.map { it.chapterId }).containsAtLeastElementsIn(window)
        }
    }

    private fun catalog() = (0..5).map { chapter("c$it", it) }

    private fun chapter(id: String, index: Int) = Chapter("book", "source", id, index, "Chapter $index")
}
