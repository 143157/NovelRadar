package com.novelradar.app.feature.book

import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.normalizeIdentityPart
import java.util.Locale

data class BookDetailUiState(
    val book: Book? = null,
    val sources: List<BookSource> = emptyList(),
    val sourceNames: Map<String, String> = emptyMap(),
    val selectedSourceId: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val chapterQuery: String = "",
    val reversed: Boolean = false,
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val warning: SourceFailure? = null,
    val detailsLoading: Boolean = false,
    val detailsWarning: SourceFailure? = null,
    val sourceIdentityMismatch: Boolean = false,
    val notice: String? = null,
    val addedToShelf: Boolean = false,
    val sourceActionPending: Boolean = false,
    val readingLocation: ReadingLocation? = null,
) {
    private val matchingLocation: ReadingLocation? get() = readingLocation?.takeIf {
        book != null && it.bookId == book.bookId && it.sourceId == selectedSourceId
    }
    val currentChapterId: String? get() = matchingLocation?.takeIf {
        it.contentFormatVersion == ChapterContentFormat.CURRENT
    }?.chapterId?.takeIf { id -> chapters.any {
        it.bookId == book?.bookId && it.sourceId == selectedSourceId && it.chapterId == id
    } }
    val positionLabel: String? get() {
        val location = matchingLocation ?: return null
        val ordered = chapters.filter { it.bookId == location.bookId && it.sourceId == location.sourceId }
            .sortedWith(compareBy<Chapter> { it.chapterIndex }.thenBy { it.chapterId })
        val currentId = currentChapterId
        val position = ordered.indexOfFirst { it.chapterId == currentId }
        return if (position >= 0) "读到第 ${position + 1} / ${ordered.size} 章"
        else "原阅读位置 第 ${location.chapterIndex.toLong() + 1} 章（待核对）"
    }
    val visibleChapters: List<Chapter> get() {
        val keyword = normalizeIdentityPart(chapterQuery).lowercase(Locale.ROOT)
        val matching = if (keyword.isBlank()) chapters else chapters.filter {
            normalizeIdentityPart(it.title).lowercase(Locale.ROOT).contains(keyword)
        }
        return if (reversed) matching.asReversed() else matching
    }
}
