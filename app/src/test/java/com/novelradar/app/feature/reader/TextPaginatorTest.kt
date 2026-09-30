package com.novelradar.app.feature.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TextPaginatorTest {
    @Test fun emptyTextHasNoPages() {
        assertThat(TextPaginator.paginate("", 10)).isEmpty()
    }

    @Test fun prefersParagraphBoundaryNearEndOfPage() {
        val text = "一二三四五六七八\n甲乙丙丁戊己"
        assertThat(TextPaginator.paginate(text, 10).first()).isEqualTo(TextPage(0, 9))
    }

    @Test fun prefersChineseSentencePunctuation() {
        val text = "一二三四五六七八。甲乙丙丁"
        assertThat(TextPaginator.paginate(text, 10).first()).isEqualTo(TextPage(0, 9))
    }

    @Test fun narrowPagesPreserveEveryCharacterAndSurrogatePair() {
        val text = "星🌟河\n旅人，启程。"
        val pages = TextPaginator.paginate(text, 1)
        assertThat(pages.joinToString("") { text.substring(it.startOffset, it.endOffset) }).isEqualTo(text)
        assertThat(pages.none { it.endOffset < text.length && text[it.endOffset].isLowSurrogate() }).isTrue()
    }

    @Test fun differentFontCapacityRepaginatesAndKeepsCharacterLocation() {
        val text = "章节正文。".repeat(40)
        val smallFont = TextPaginator.paginate(text, 30)
        val largeFont = TextPaginator.paginate(text, 18)
        assertThat(largeFont.size).isGreaterThan(smallFont.size)
        val offset = 51
        val page = largeFont[TextPaginator.pageIndexForOffset(largeFont, offset)]
        assertThat(offset).isAtLeast(page.startOffset)
        assertThat(offset).isLessThan(page.endOffset)
    }

    @Test fun allPagesJoinToOriginalIncludingWhitespaceAndPunctuation() {
        val text = "\n  第一段  \n" + "甲乙丙丁，戊己庚辛。".repeat(13) + "\n结尾\n"
        val pages = TextPaginator.paginate(text, 17)
        assertThat(pages.first().startOffset).isEqualTo(0)
        assertThat(pages.last().endOffset).isEqualTo(text.length)
        assertThat(pages.zipWithNext().all { (left, right) -> left.endOffset == right.startOffset }).isTrue()
        assertThat(pages.joinToString("") { text.substring(it.startOffset, it.endOffset) }).isEqualTo(text)
    }

    @Test fun invalidCapacityStillMakesProgress() {
        assertThat(TextPaginator.paginate("甲乙", 0)).containsExactly(TextPage(0, 1), TextPage(1, 2)).inOrder()
    }
}
