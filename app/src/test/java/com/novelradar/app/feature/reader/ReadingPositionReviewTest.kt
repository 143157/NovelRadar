package com.novelradar.app.feature.reader

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.repository.ChapterOrigin
import com.novelradar.app.domain.repository.LoadedChapter
import com.novelradar.app.domain.repository.ReadingLocation
import org.junit.Test

class ReadingPositionReviewTest {
    private val loaded = LoadedChapter(Chapter("book", "a", "c1", 1, "第一章"), "正文😀", ChapterOrigin.Cached)
    private val saved = ReadingLocation("book", "a", "c1", 1, 2, 55L)

    @Test fun verifiedCurrentIdentityAndUtf16BoundsNeedNoReview() {
        for (offset in listOf(0, 2, loaded.content.length)) {
            assertThat(needsPositionReview(saved.copy(offset = offset), loaded, false)).isFalse()
        }
        assertThat(needsPositionReview(null, loaded, false)).isFalse()
    }

    @Test fun everyUnverifiedBodyNeedsReviewIncludingExplicitChoiceAndNoSavedPosition() {
        for (explicit in listOf(false, true)) for (position in listOf(saved, null)) {
            assertThat(needsPositionReview(position, loaded.copy(boundaryVerified = false), explicit)).isTrue()
        }
    }

    @Test fun passiveRestorationRequiresExactIdentityCurrentMarkerAndInRangeOffset() {
        val unsafe = listOf(saved.copy(bookId = "other"), saved.copy(sourceId = "b"), saved.copy(chapterId = "missing"),
            saved.copy(contentFormatVersion = ChapterContentFormat.LEGACY), saved.copy(contentFormatVersion = 99),
            saved.copy(offset = -1), saved.copy(offset = loaded.content.length + 1))
        for (position in unsafe) assertThat(needsPositionReview(position, loaded, false)).isTrue()
    }

    @Test fun verifiedExplicitChoiceCanReplaceOldPositionAfterCallerValidatesLoadedIdentity() {
        val unsafe = saved.copy(bookId = "other", sourceId = "b", chapterId = "missing", offset = -1,
            contentFormatVersion = ChapterContentFormat.LEGACY)
        assertThat(needsPositionReview(unsafe, loaded, true)).isFalse()
    }
}
