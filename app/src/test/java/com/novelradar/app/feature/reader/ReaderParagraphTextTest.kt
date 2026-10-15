package com.novelradar.app.feature.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.repository.ChapterOrigin
import com.novelradar.app.domain.repository.LoadedChapter
import org.junit.Assert.*
import org.junit.Test

class ReaderParagraphTextTest {
    @Test fun completeTextIndentsEachRealParagraphWithoutChangingCharacters() {
        val text = "甲乙丙丁\n第二段😀正文"
        val result = readerParagraphText(text, 0, text.length)

        assertEquals(text, result.text)
        assertEquals(text.length, result.length)
        assertEquals(2, result.paragraphStyles.size)
        assertParagraph(result, 0, 0, 5, 2)
        assertParagraph(result, 1, 5, text.length, 2)
    }

    @Test fun continuationSliceOnlyIndentsTheFollowingRealParagraph() {
        val text = "甲乙丙丁\n第二段😀正文"
        val result = readerParagraphText(text, 2, text.length)

        assertEquals(text.substring(2), result.text)
        assertEquals(2, result.paragraphStyles.size)
        assertParagraph(result, 0, 0, 3, 0)
        assertParagraph(result, 1, 3, result.length, 2)
    }

    @Test fun sliceBeginningImmediatelyAfterNewlineIsARealParagraphStart() {
        val text = "前段\n新段正文"
        val result = readerParagraphText(text, 3, text.length)

        assertEquals("新段正文", result.text)
        assertEquals(1, result.paragraphStyles.size)
        assertParagraph(result, 0, 0, result.length, 2)
    }

    @Test fun sliceEndingMidParagraphStillIndentsItsOriginalFirstLine() {
        val result = readerParagraphText("第一段原文很长", 0, 3)

        assertEquals("第一段", result.text)
        assertEquals(1, result.paragraphStyles.size)
        assertParagraph(result, 0, 0, 3, 2)
    }

    @Test fun wrappedLinesUseZeroRestLineIndent() {
        val text = "这是足够长而需要自动换行的同一段正文。".repeat(30)
        val result = readerParagraphText(text, 0, text.length)

        assertEquals(text, result.text)
        assertEquals(1, result.paragraphStyles.size)
        assertParagraph(result, 0, 0, result.length, 2)
    }

    @Test fun emptyTextAndEmptySlicesStayEmptyWithoutParagraphRanges() {
        for ((text, offset) in listOf("" to 0, "甲\n乙" to 0, "甲\n乙" to 2, "甲\n乙" to 3)) {
            val result = readerParagraphText(text, offset, offset)
            assertEquals("", result.text)
            assertTrue(result.paragraphStyles.isEmpty())
        }
    }

    @Test fun blankParagraphsAndTrailingNewlinePreserveExactRangesAndText() {
        val text = "\n\n甲\n"
        val result = readerParagraphText(text, 0, text.length)

        assertEquals(text, result.text)
        assertEquals(3, result.paragraphStyles.size)
        assertParagraph(result, 0, 0, 1, 2)
        assertParagraph(result, 1, 1, 2, 2)
        assertParagraph(result, 2, 2, 4, 2)
    }

    @Test fun sliceEndingAtNewlineDoesNotCreateAnExtraParagraph() {
        val text = "甲乙\n下一段"
        val result = readerParagraphText(text, 1, 3)

        assertEquals("乙\n", result.text)
        assertEquals(1, result.paragraphStyles.size)
        assertParagraph(result, 0, 0, 2, 0)
    }

    @Test fun supplementaryCharactersKeepOriginalUtf16RangeOffsets() {
        val text = "😀甲\n乙😀丙\n丁"
        val result = readerParagraphText(text, 2, text.length - 1)

        assertEquals("甲\n乙😀丙\n", result.text)
        assertEquals(7, result.length)
        assertEquals(2, result.paragraphStyles.size)
        assertParagraph(result, 0, 0, 2, 0)
        assertParagraph(result, 1, 2, 7, 2)
    }

    @Test fun paginatorSlicesReassembleExactOriginalWithoutReindentingContinuation() {
        val text = "甲😀乙丙丁戊己庚\n另一段😀正文"
        val pages = TextPaginator.paginate(text, 5)
        val formatted = pages.map { readerParagraphText(text, it.startOffset, it.endOffset) }

        assertEquals(text, formatted.joinToString("") { it.text })
        pages.zip(formatted).forEach { (page, result) ->
            assertEquals(text.substring(page.startOffset, page.endOffset), result.text)
            assertEquals(page.endOffset - page.startOffset, result.length)
            assertFalse(result.paragraphStyles.isEmpty())
            val firstLine = if (page.startOffset == 0 || text[page.startOffset - 1] == '\n') 2.em else 0.em
            assertEquals(TextIndent(firstLine = firstLine, restLine = 0.em), result.paragraphStyles.first().item.textIndent)
        }
    }

    @Test fun formattingKeepsReaderItemPositionAndOriginalCharacterProgress() {
        val text = "甲😀乙\n丙丁"
        val chapter = LoadedChapter(Chapter("book", "source", "chapter", 0, "章节"), text, ChapterOrigin.Cached)
        val item = ReaderItem(chapter, TextPage(3, text.length), "chapter:chapter:text:3")
        val result = readerParagraphText(item.chapter.content, item.page!!.startOffset, item.page.endOffset)
        val position = item.position(1)

        assertEquals("乙\n丙丁", result.text)
        assertEquals(ChapterReadingPosition("chapter", 4), position)
        assertEquals(ChapterCharacterProgress(3, 6), chapterCharacterProgress(chapter.content, position.offset))
        assertEquals(ChapterCharacterProgress(3, 6), ChapterCharacterIndex(chapter.content).progress(position.offset))
        assertEquals(text, chapter.content)
        assertEquals(7, chapter.content.length)
        assertEquals(2, result.paragraphStyles.size)
        assertEquals(TextIndent(firstLine = 0.em, restLine = 0.em), result.paragraphStyles.first().item.textIndent)
    }

    @Test fun invalidSlicesAreRejectedInsteadOfClamped() {
        for ((start, end) in listOf(-1 to 1, 0 to 4, 2 to 1, 4 to 4)) {
            assertThrows(IllegalArgumentException::class.java) { readerParagraphText("甲乙丙", start, end) }
        }
    }

    private fun assertParagraph(text: AnnotatedString, index: Int, start: Int, end: Int, firstLine: Int) {
        val paragraph = text.paragraphStyles[index]
        assertEquals(start, paragraph.start)
        assertEquals(end, paragraph.end)
        assertEquals(TextIndent(firstLine = firstLine.em, restLine = 0.em), paragraph.item.textIndent)
    }
}
