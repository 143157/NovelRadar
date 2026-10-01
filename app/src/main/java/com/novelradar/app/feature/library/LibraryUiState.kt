package com.novelradar.app.feature.library

import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.repository.ShelfBook

data class LibraryBook(val shelf: ShelfBook) {
    val percent: Int get() {
        val progress = shelf.location?.takeIf { it.sourceId == shelf.preferredSourceId } ?: return 0
        if (shelf.chapterCount == 0) return 0
        return ((progress.chapterIndex.toLong() + 1) * 100 / shelf.chapterCount).toInt().coerceIn(0, 100)
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
        it.shelf.location != null && it.shelf.location.sourceId == it.shelf.preferredSourceId
    }
}

/** A null initial chapter in the reader restores its persisted chapter and text offset. */
data class LibraryOpen(val bookId: String, val sourceId: String)
