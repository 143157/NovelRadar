package com.novelradar.app.feature.library

import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.repository.ShelfBook

data class LibraryBook(val shelf: ShelfBook) {
    private val matchingLocation get() = shelf.location?.takeIf {
        it.bookId == shelf.book.bookId && it.sourceId == shelf.preferredSourceId
    }
    private val currentPosition: Int? get() = shelf.readingChapterPosition?.takeIf {
        matchingLocation?.contentFormatVersion == ChapterContentFormat.CURRENT && it > 0 && it <= shelf.chapterCount
    }
    val positionLabel: String get() {
        val location = matchingLocation ?: return "尚未开始阅读"
        val position = currentPosition
        return if (position != null) "读到第 $position / ${shelf.chapterCount} 章"
        else "原阅读位置 第 ${location.chapterIndex.toLong() + 1} 章（待核对）"
    }
    val percent: Int get() {
        val position = currentPosition ?: return 0
        return (position.toLong() * 100 / shelf.chapterCount).toInt()
    }
}

data class LibraryUiState(
    val books: List<LibraryBook> = emptyList(),
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val refreshWarnings: Map<String, SourceFailure> = emptyMap(),
    val pendingRemoval: LibraryBook? = null,
    val removing: Boolean = false,
    val notice: String? = null,
) {
    val continueReading: LibraryBook? get() = books.firstOrNull {
        it.shelf.location != null && it.shelf.location.bookId == it.shelf.book.bookId &&
            it.shelf.location.sourceId == it.shelf.preferredSourceId
    }
}

/** A null initial chapter in the reader restores its persisted chapter and text offset. */
data class LibraryOpen(val bookId: String, val sourceId: String)
