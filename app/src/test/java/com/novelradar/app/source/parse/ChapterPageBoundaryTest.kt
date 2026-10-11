package com.novelradar.app.source.parse

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChapterPageBoundaryTest {
    @Test fun catalogUrlsIgnoreFragmentsButKeepChapterQueries() {
        val boundary = ChapterPageBoundary("https://BOOKS.example.test/read?id=1#top",
            ChapterRequestContext(setOf("https://books.example.test/read?id=1",
                "https://books.example.test/read?id=2#catalog")))

        assertThat(boundary.isOtherCatalogChapter("https://books.example.test/read?id=1#body")).isFalse()
        assertThat(boundary.isOtherCatalogChapter("https://books.example.test/read?id=2#body")).isTrue()
        assertThat(boundary.isOtherCatalogChapter("https://books.example.test/read?id=3")).isFalse()
    }

    @Test fun explicitNextChapterLabelsAreDistinctFromNextPage() {
        val boundary = ChapterPageBoundary("https://books.example.test/1")

        listOf("下一章", "下章", "下一回", " Next chapter ", "ＮＥＸＴ　ＣＨＡＰＴＥＲ")
            .forEach { assertThat(boundary.isExplicitNextChapter(it)).isTrue() }
        listOf(null, "下一页", "Next page").forEach {
            assertThat(boundary.isExplicitNextChapter(it)).isFalse()
        }
    }

    @Test fun explicitPageSuffixesAndWhitespaceDoNotChangeChapterIdentity() {
        val boundary = ChapterPageBoundary("https://books.example.test/1")

        listOf("第一章 (1/3)", "第一章 (2/3)", "第一章（第2页）", "第 一 章【二页】")
            .forEach { assertThat(boundary.sameChapterTitle("第一章", it)).isTrue() }
    }

    @Test fun uniqueTitlesWithoutChapterNumbersCanProveContinuation() {
        val boundary = ChapterPageBoundary("https://books.example.test/1")

        assertThat(boundary.isKnownTitle("序章")).isTrue()
        assertThat(boundary.sameChapterTitle("序章", "序章 (2/3)")).isTrue()
        assertThat(boundary.sameChapterTitle("Prologue", "ＰＲＯＬＯＧＵＥ")).isTrue()
    }

    @Test fun genericAndPageOnlyTitlesCannotProveContinuation() {
        val boundary = ChapterPageBoundary("https://books.example.test/1")

        listOf("", "  ", "正文", "章节", "内容", "小说", "CHAPTER", "content", "第2页", "二页", "page 2")
            .forEach {
                assertThat(boundary.isKnownTitle(it)).isFalse()
                assertThat(boundary.sameChapterTitle(it, it)).isFalse()
            }
    }

    @Test fun chapterNumbersAndNonPageSubtitlesRemainPartOfIdentity() {
        val boundary = ChapterPageBoundary("https://books.example.test/1")

        assertThat(boundary.sameChapterTitle("第一章", "第二章")).isFalse()
        assertThat(boundary.sameChapterTitle("第一章（相遇）", "第一章（重逢）")).isFalse()
        assertThat(boundary.sameChapterTitle("第一章（一）", "第一章（二）")).isFalse()
        assertThat(boundary.sameChapterTitle("第一章", "第一章（完）")).isFalse()
    }
}
