package com.novelradar.app.feature.reader

import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.ReaderPreferences
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.repository.LoadedChapter

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
) {
    val catalogPosition: Int get() = chapters.indexOfFirst { it.chapterId == content?.chapter?.chapterId }
    val canPrevious: Boolean get() = !loading && catalogPosition > 0
    val canNext: Boolean get() = !loading && catalogPosition >= 0 && catalogPosition < chapters.lastIndex
}
