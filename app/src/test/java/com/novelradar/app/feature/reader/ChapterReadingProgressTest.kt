package com.novelradar.app.feature.reader

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ChapterReadingProgressTest {
    @Test fun supplementaryCharactersCountOnceWithoutChangingTheStoredUtf16Offset() {
        val text = "甲😀乙\n"
        val position = ChapterReadingPosition("chapter", 3)

        assertThat(text.length).isEqualTo(5)
        assertThat(text.codePointCount(0, text.length)).isEqualTo(4)
        assertThat(chapterCharacterProgress(text, position.offset)).isEqualTo(ChapterCharacterProgress(2, 4))
        assertThat(ChapterCharacterIndex(text).progress(position.offset)).isEqualTo(ChapterCharacterProgress(2, 4))
        assertThat(position).isEqualTo(ChapterReadingPosition("chapter", 3))
    }

    @Test fun offsetInsideSurrogatePairDisplaysOnlyTheCompletedPrefix() {
        val text = "甲😀乙\n"
        val position = ChapterReadingPosition("chapter", 2)

        assertThat(chapterCharacterProgress(text, position.offset)).isEqualTo(ChapterCharacterProgress(1, 4))
        assertThat(ChapterCharacterIndex(text).progress(position.offset)).isEqualTo(ChapterCharacterProgress(1, 4))
        assertThat(position.offset).isEqualTo(2)
    }

    @Test fun emptyChapterHasZeroReadTotalAndPercent() {
        assertThat(chapterCharacterProgress("", 99)).isEqualTo(ChapterCharacterProgress(0, 0))
        assertThat(ChapterCharacterIndex("").progress(-1)).isEqualTo(ChapterCharacterProgress(0, 0))
        assertThat(ChapterCharacterIndex("").total).isEqualTo(0)
        assertThat(ChapterCharacterProgress(0, 0).percent).isEqualTo(0)
    }

    @Test fun punctuationSpacesAndBlankLinesAreCharacters() {
        val text = "甲，！ \n\n乙"

        assertThat(chapterCharacterProgress(text, 6)).isEqualTo(ChapterCharacterProgress(6, 7))
        assertThat(ChapterCharacterIndex(text).progress(6)).isEqualTo(ChapterCharacterProgress(6, 7))
    }

    @Test fun outOfRangeOffsetsClampOnlyTheDisplay() {
        val text = "甲😀乙\n"
        val negative = ChapterReadingPosition("chapter", -4)
        val oversized = ChapterReadingPosition("chapter", 999)
        val index = ChapterCharacterIndex(text)

        assertThat(chapterCharacterProgress(text, negative.offset)).isEqualTo(ChapterCharacterProgress(0, 4))
        assertThat(index.progress(negative.offset)).isEqualTo(ChapterCharacterProgress(0, 4))
        assertThat(chapterCharacterProgress(text, oversized.offset)).isEqualTo(ChapterCharacterProgress(4, 4))
        assertThat(index.progress(oversized.offset)).isEqualTo(ChapterCharacterProgress(4, 4))
        assertThat(negative.offset).isEqualTo(-4)
        assertThat(oversized.offset).isEqualTo(999)
    }

    @Test fun surrogatePairCrossingSparseCheckpointCountsOnce() {
        val text = "甲".repeat(255) + "😀乙"
        val index = ChapterCharacterIndex(text)

        assertThat(index.total).isEqualTo(257)
        assertThat(index.progress(255)).isEqualTo(ChapterCharacterProgress(255, 257))
        assertThat(index.progress(256)).isEqualTo(ChapterCharacterProgress(255, 257))
        assertThat(index.progress(257)).isEqualTo(ChapterCharacterProgress(256, 257))
        assertThat(index.progress(258)).isEqualTo(ChapterCharacterProgress(257, 257))
    }

    @Test fun percentUsesLongArithmeticAndClampsTheDisplay() {
        assertThat(ChapterCharacterProgress(2, 4).percent).isEqualTo(50)
        assertThat(ChapterCharacterProgress(Int.MAX_VALUE - 1, Int.MAX_VALUE).percent).isEqualTo(99)
        assertThat(ChapterCharacterProgress(Int.MAX_VALUE, Int.MAX_VALUE).percent).isEqualTo(100)
        assertThat(ChapterCharacterProgress(Int.MAX_VALUE, 1).percent).isEqualTo(100)
        assertThat(ChapterCharacterProgress(-1, 4).percent).isEqualTo(0)
    }

    @Test fun sparseIndexMatchesDirectCodePointCountsAtEveryOffsetOfBoundedMixedSamples() {
        val samples = listOf("", "甲😀乙\n", "😀".repeat(260), "甲".repeat(255) + "😀" + "乙".repeat(257),
            "甲😀，\n\n \uD83D乙\uDE00".repeat(70))

        for (text in samples) {
            val index = ChapterCharacterIndex(text)
            assertThat(index.total).isEqualTo(text.codePointCount(0, text.length))
            for (offset in -1..text.length + 1) {
                val expected = referenceProgress(text, offset)
                assertThat(index.progress(offset)).isEqualTo(expected)
                assertThat(chapterCharacterProgress(text, offset)).isEqualTo(expected)
            }
        }
    }

    @Test fun longChapterIndexMatchesDirectCountsAtSelectedBoundariesMiddleAndEnd() {
        val text = ("甲".repeat(255) + "😀，\n\n").repeat(4_000)
        val index = ChapterCharacterIndex(text)
        val offsets = listOf(-1, 0, 1, 255, 256, 257, 511, 512, 513,
            text.length / 2 - 1, text.length / 2, text.length / 2 + 1,
            text.length - 2, text.length - 1, text.length, Int.MAX_VALUE)

        assertThat(index.total).isEqualTo(text.codePointCount(0, text.length))
        for (offset in offsets) assertThat(index.progress(offset)).isEqualTo(referenceProgress(text, offset))
    }

    private fun referenceProgress(text: String, offset: Int): ChapterCharacterProgress {
        var end = offset.coerceIn(0, text.length)
        if (end > 0 && end < text.length && Character.isSurrogatePair(text[end - 1], text[end])) end--
        return ChapterCharacterProgress(text.codePointCount(0, end), text.codePointCount(0, text.length))
    }
}
