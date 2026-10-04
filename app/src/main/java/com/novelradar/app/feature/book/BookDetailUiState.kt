package com.novelradar.app.feature.book

import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.Chapter
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
) {
    val visibleChapters: List<Chapter> get() {
        val keyword = normalizeIdentityPart(chapterQuery).lowercase(Locale.ROOT)
        val matching = if (keyword.isBlank()) chapters else chapters.filter {
            normalizeIdentityPart(it.title).lowercase(Locale.ROOT).contains(keyword)
        }
        return if (reversed) matching.asReversed() else matching
    }
}
