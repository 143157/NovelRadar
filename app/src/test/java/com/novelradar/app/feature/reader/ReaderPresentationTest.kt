package com.novelradar.app.feature.reader

import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.repository.ChapterOrigin
import com.novelradar.app.domain.repository.LoadedChapter
import org.junit.Assert.*
import org.junit.Test

class ReaderPresentationTest {
    private fun body(id: String, text: String) = LoadedChapter(Chapter("book", "source", id, 0, id), text, ChapterOrigin.Cached)

    @Test fun identicalBodiesHaveChapterScopedHeadingAndTextKeys() {
        val items = readerItems(listOf(body("a", "same"), body("b", "same")), 2)
        assertEquals(items.size, items.map { it.key }.distinct().size)
        assertEquals(listOf("chapter:a:title", "chapter:a:text:0", "chapter:a:text:2", "chapter:b:title", "chapter:b:text:0", "chapter:b:text:2"), items.map { it.key })
    }
    @Test fun visiblePositionUsesActualChapterAndOriginalLocalUtf16() {
        val items = readerItems(listOf(body("a", "abc"), body("b", "x😀yz")), 3)
        val item = items.first { it.chapter.chapter.chapterId == "b" && it.page?.startOffset == 3 }
        assertEquals(ChapterReadingPosition("b", 4), item.position(1))
    }
    @Test fun exactAnchorEmptyChapterAndSurrogatesStayWithinBoundedWindow() {
        val chapters = listOf(body("a", ""), body("b", "x😀yz"), body("c", "same"), body("d", "same"))
        val items = readerItems(chapters, 2)
        assertEquals(4, items.map { it.chapter.chapter.chapterId }.distinct().size)
        assertNull(items[readerAnchor(items, ChapterReadingPosition("a", 0))].page)
        val index = readerAnchor(items, ChapterReadingPosition("b", 3))
        assertEquals(3, items[index].page!!.startOffset)
        assertEquals("x😀yz", items.filter { it.chapter.chapter.chapterId == "b" && it.page != null }.joinToString("") { it.chapter.content.substring(it.page!!.startOffset, it.page.endOffset) })
    }
    @Test fun appendAndTrimLocateSameChapterKeyAndExactCharacter() {
        val active = body("b", "x😀yz")
        val position = ChapterReadingPosition("b", 4)
        val before = readerItems(listOf(body("a", "same"), active), 2)
        val after = readerItems(listOf(active, body("c", "same"), body("d", "same")), 2)
        assertEquals(before[readerAnchor(before, position)].key, after[readerAnchor(after, position)].key)
        val item = after[readerAnchor(after, position)]
        assertEquals(position, item.position(position.offset - item.page!!.startOffset))
    }
    @Test fun pagedFirstAnchorAndEmptyBodyAreExact() {
        val chapter = body("a", "abcdef")
        val pages = readerItems(listOf(chapter), 2).filter { it.page != null }
        assertEquals(0, readerAnchor(pages, ChapterReadingPosition("a", 0)))
        assertEquals(2, readerAnchor(pages, ChapterReadingPosition("a", 6)))
        val empty = ReaderItem(body("empty", ""), TextPage(0, 0), "chapter:empty:text:0")
        assertEquals(ChapterReadingPosition("empty", 0), empty.position(5))
    }
    @Test fun progressOnlyUsesTrustedCachedChapterCount() {
        val chapter = body("a", "x😀yz")
        val state = ReaderUiState(content = chapter, chapters = listOf(chapter.chapter), characterProgress = ChapterCharacterProgress(2, 4))
        assertEquals("2 / 4 字 · 本章 50% · 第 1 / 1 章", readerProgressLabel(state))
        assertEquals("第 1 / 1 章", readerProgressLabel(state.copy(positionNeedsReview = true)))
        assertEquals("第 1 / 1 章", readerProgressLabel(state.copy(content = chapter.copy(boundaryVerified = false))))
        assertEquals("章节位置待确认", readerProgressLabel(state.copy(chapters = emptyList(), characterProgress = null)))
    }
    @Test fun cancelledDragAndPreparationKeepExactAnchorUntilDeliberatePageChange() {
        val chapter = body("a", "abcdefghijklmnopqrstuvwxyz")
        val items = readerItems(listOf(chapter), 10).filter { it.page != null }
        val exact = ChapterReadingPosition("a", 7)
        assertEquals(exact, readerSettledPosition(items[0], items[0].key, exact, true))
        assertEquals(exact, readerSettledPosition(items[0], items[0].key, exact, false))
        assertNull(readerSettledPosition(items[1], items[0].key, exact, false))
        assertEquals(ChapterReadingPosition("a", 10), readerSettledPosition(items[1], items[0].key, exact, true))
    }
    @Test fun explicitScrollIntentFindsShortOrEmptyReadyNeighborAndWaitsForMissingBody() {
        val first = body("a", "short")
        val empty = body("b", "")
        val forward = ReaderScrollTarget("b", "a", true)
        assertEquals(ChapterReadingPosition("b", 0), readerReadyScrollPosition(readerItems(listOf(first, empty), 600), forward))
        assertNull(readerReadyScrollPosition(readerItems(listOf(first), 600), forward))
        assertEquals(ChapterReadingPosition("b", 0), readerReadyScrollPosition(readerItems(listOf(first, body("b", "new")), 600), forward))
        assertNull(readerReadyScrollPosition(readerItems(listOf(first, empty), 600), null))
        assertEquals(ChapterReadingPosition("a", 5), readerReadyScrollPosition(readerItems(listOf(first, empty), 600), ReaderScrollTarget("a", "b", false)))
    }
}
