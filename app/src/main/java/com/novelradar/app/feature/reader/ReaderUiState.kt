package com.novelradar.app.feature.reader

import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.model.ReaderPreferences
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.repository.LoadedChapter
import com.novelradar.app.domain.repository.ReadingLocation

data class ReaderUiState(
    val book: Book? = null,
    val sourceName: String = "",
    val chapters: List<Chapter> = emptyList(),
    val content: LoadedChapter? = null,
    val loading: Boolean = true,
    val error: SourceFailure? = null,
    val notice: String? = null,
    val preferences: ReaderPreferences = ReaderPreferences(),
    val offset: Int = 0,
    val positionVersion: Int = 0,
    val positionNeedsReview: Boolean = false,
    val originalReadingLocation: ReadingLocation? = null,
    val originalChapterTitle: String? = null,
    val initialEntryCompleted: Boolean = false,
) {
    val catalogPosition: Int get() = chapters.indexOfFirst { it.chapterId == content?.chapter?.chapterId }
    val canPrevious: Boolean get() = !loading && catalogPosition > 0
    val canNext: Boolean get() = !loading && catalogPosition >= 0 && catalogPosition < chapters.lastIndex
}

fun needsPositionReview(saved: ReadingLocation?, loaded: LoadedChapter, explicitlySelected: Boolean): Boolean =
    !loaded.boundaryVerified || (!explicitlySelected && saved != null &&
        (saved.bookId != loaded.chapter.bookId || saved.sourceId != loaded.chapter.sourceId ||
            saved.chapterId != loaded.chapter.chapterId || saved.contentFormatVersion != ChapterContentFormat.CURRENT ||
            saved.offset !in 0..loaded.content.length))

internal const val POSITION_CONFIRMATION_FAILURE_NOTICE = "阅读位置确认失败，请重试确认"
