package com.novelradar.app.feature.reader

import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.repository.ChapterOrigin
import com.novelradar.app.domain.repository.LoadedChapter
import org.junit.Assert.*
import org.junit.Test

class ReaderParagraphTextTest {
    @Test fun completeTextKeepsAllNativeNewlinesInOneStyledFragment() {
        val text = "甲乙丙丁\n第二段😀正文"
        val result = readerParagraphSlice(text, 0, text.length)

        assertOriginalSlice(text, 0, text.length, result)
        assertEquals(1, result.fragments.size)
        assertFragment(result.fragments.single(), 0, text.length, text, 2)
    }

    @Test fun continuationConsumesTheFirstSeparatorOnceBetweenTwoFragments() {
        val text = "甲乙丙丁\n第二段😀正文"
        val result = readerParagraphSlice(text, 2, text.length)

        assertOriginalSlice(text, 2, text.length, result)
        assertEquals(2, result.fragments.size)
        assertFragment(result.fragments[0], 2, 4, "丙丁", 0)
        assertFragment(result.fragments[1], 5, text.length, "第二段😀正文", 2)
    }

    @Test fun sliceBeginningAfterNewlineIndentsEachNativeParagraphInOneFragment() {
        val text = "前段\n新段正文\n还有一段"
        val result = readerParagraphSlice(text, 3, text.length)

        assertOriginalSlice(text, 3, text.length, result)
        assertEquals(1, result.fragments.size)
        assertFragment(result.fragments.single(), 3, text.length, text.substring(3), 2)
    }

    @Test fun sliceEndingMidParagraphStillIndentsItsOriginalFirstLine() {
        val result = readerParagraphSlice("第一段原文很长", 0, 3)

        assertEquals(1, result.fragments.size)
        assertFragment(result.fragments.single(), 0, 3, "第一段", 2)
    }

    @Test fun wrappedAndContinuedLinesUseZeroRestLineIndent() {
        val text = "这是足够长而需要自动换行的同一段正文。".repeat(30)
        assertFragment(readerParagraphSlice(text, 0, text.length).fragments.single(), 0, text.length, text, 2)
        assertFragment(readerParagraphSlice(text, 3, text.length).fragments.single(), 3, text.length, text.substring(3), 0)
    }

    @Test fun emptyTextAndEmptySlicesKeepOneEmptyNativeLayoutWithoutCharacters() {
        for ((text, offset) in listOf("" to 0, "甲\n乙" to 0, "甲\n乙" to 2, "甲\n乙" to 3)) {
            val result = readerParagraphSlice(text, offset, offset)
            assertOriginalSlice(text, offset, offset, result)
            assertEquals(1, result.fragments.size)
            assertEquals("", result.fragments.single().text.text)
            assertTrue(result.fragments.single().text.paragraphStyles.isEmpty())
        }
    }

    @Test fun blankParagraphsAndTrailingNewlineRemainInsideOneNativeLayout() {
        val text = "\n\n甲\n"
        val result = readerParagraphSlice(text, 0, text.length)

        assertOriginalSlice(text, 0, text.length, result)
        assertEquals(1, result.fragments.size)
        assertFragment(result.fragments.single(), 0, text.length, text, 2)
    }

    @Test fun continuationEndingAtNewlineKeepsTheActualEmptyLastLine() {
        val text = "甲乙\n下一段"
        val result = readerParagraphSlice(text, 1, 3)

        assertOriginalSlice(text, 1, 3, result)
        assertEquals(2, result.fragments.size)
        assertFragment(result.fragments[0], 1, 2, "乙", 0)
        assertEquals("", result.fragments[1].text.text)
        assertEquals(3, result.fragments[1].startOffset)
        assertEquals(3, result.fragments[1].endOffset)
    }

    @Test fun supplementaryCharactersKeepOriginalUtf16FragmentOffsets() {
        val text = "😀甲\n乙😀丙\n丁"
        val result = readerParagraphSlice(text, 2, text.length - 1)

        assertOriginalSlice(text, 2, text.length - 1, result)
        assertEquals(7, result.length)
        assertEquals(2, result.fragments.size)
        assertFragment(result.fragments[0], 2, 3, "甲", 0)
        assertFragment(result.fragments[1], 4, text.length - 1, "乙😀丙\n", 2)
    }

    @Test fun paginatorSlicesKeepOriginalTextAndContinuationIndentWithoutRewritingOffsets() {
        val text = "甲😀乙丙丁戊己庚\n另一段😀正文"
        val pages = TextPaginator.paginate(text, 5)
        val formatted = pages.map { readerParagraphSlice(text, it.startOffset, it.endOffset) }

        assertEquals(text, formatted.joinToString("") { it.text })
        pages.zip(formatted).forEach { (page, result) ->
            assertOriginalSlice(text, page.startOffset, page.endOffset, result)
            assertTrue(result.fragments.isNotEmpty())
            val firstLine = if (page.startOffset == 0 || text[page.startOffset - 1] == '\n') 2.em else 0.em
            val first = result.fragments.first()
            if (first.text.isNotEmpty()) {
                assertEquals(TextIndent(firstLine = firstLine, restLine = 0.em), first.text.paragraphStyles.single().item.textIndent)
            }
        }
    }

    @Test fun formattingKeepsReaderItemPositionAndOriginalCharacterProgress() {
        val text = "甲😀乙\n丙丁"
        val chapter = LoadedChapter(Chapter("book", "source", "chapter", 0, "章节"), text, ChapterOrigin.Cached)
        val item = ReaderItem(chapter, TextPage(3, text.length), "chapter:chapter:text:3")
        val result = readerParagraphSlice(item.chapter.content, item.page!!.startOffset, item.page.endOffset)
        val position = item.position(1)

        assertOriginalSlice(text, 3, text.length, result)
        assertEquals(ChapterReadingPosition("chapter", 4), position)
        assertEquals(ChapterCharacterProgress(3, 6), chapterCharacterProgress(chapter.content, position.offset))
        assertEquals(ChapterCharacterProgress(3, 6), ChapterCharacterIndex(chapter.content).progress(position.offset))
        assertEquals(text, chapter.content)
        assertEquals(7, chapter.content.length)
    }

    @Test fun invalidSlicesAreRejectedInsteadOfClamped() {
        for ((start, end) in listOf(-1 to 1, 0 to 4, 2 to 1, 4 to 4)) {
            assertThrows(IllegalArgumentException::class.java) { readerParagraphSlice("甲乙丙", start, end) }
        }
    }

    @Test fun aSingleSeparatorDoesNotProduceAnIndependentTerminalNewlineParagraph() {
        for (text in listOf("甲\n乙", "甲\n\n乙")) {
            val result = readerParagraphSlice(text, 0, text.length)
            assertEquals(1, result.fragments.size)
            assertEquals(text, result.fragments.single().text.text)
            assertEquals(1, result.fragments.single().text.paragraphStyles.size)
            assertOriginalSlice(text, 0, text.length, result)
        }
    }

    @Test fun continuationKeepsBlankLineInTheRemainingNativeText() {
        val text = "前乙\n\n丙"
        val result = readerParagraphSlice(text, 1, text.length)

        assertOriginalSlice(text, 1, text.length, result)
        assertEquals(2, result.fragments.size)
        assertFragment(result.fragments[0], 1, 2, "乙", 0)
        assertFragment(result.fragments[1], 3, text.length, "\n丙", 2)
    }

    @Test fun separatorAndSupplementaryOffsetsMapBackToOriginalChapterPositions() {
        val text = "甲😀乙\n丙丁"
        val result = readerParagraphSlice(text, 3, text.length)

        assertEquals(2, result.fragments.size)
        val continued = result.fragments[0]
        val next = result.fragments[1]
        assertEquals(4, continued.originalOffset(1))
        assertEquals(6, next.originalOffset(1))
        assertEquals(continued, result.fragmentForOffset(4))
        assertEquals(next, result.fragmentForOffset(5))
        assertEquals(next, result.fragmentForOffset(7))
    }

    private fun assertOriginalSlice(original: String, start: Int, end: Int, result: ReaderParagraphSlice) {
        assertEquals(original.substring(start, end), result.text)
        assertEquals(end - start, result.length)
        assertEquals(result.text, result.fragments.joinToString("\n") { it.text.text })
        result.fragments.forEach { fragment ->
            assertTrue(fragment.startOffset in start..end)
            assertTrue(fragment.endOffset in fragment.startOffset..end)
            assertEquals(original.substring(fragment.startOffset, fragment.endOffset), fragment.text.text)
        }
        result.fragments.zipWithNext().forEach { (before, after) ->
            assertEquals(before.endOffset + 1, after.startOffset)
            assertEquals('\n', original[before.endOffset])
        }
    }

    private fun assertFragment(fragment: ReaderParagraphFragment, start: Int, end: Int, text: String, firstLine: Int) {
        assertEquals(start, fragment.startOffset)
        assertEquals(end, fragment.endOffset)
        assertEquals(text, fragment.text.text)
        assertEquals(1, fragment.text.paragraphStyles.size)
        val style = fragment.text.paragraphStyles.single()
        assertEquals(0, style.start)
        assertEquals(text.length, style.end)
        assertEquals(TextIndent(firstLine = firstLine.em, restLine = 0.em), style.item.textIndent)
    }
}
