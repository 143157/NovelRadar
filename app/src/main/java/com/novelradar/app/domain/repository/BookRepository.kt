package com.novelradar.app.domain.repository

import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookDetails
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.model.SearchHit
import kotlinx.coroutines.flow.Flow

data class ShelfBook(
    val book: Book,
    val addedAtEpochMillis: Long,
    val lastReadAtEpochMillis: Long?,
    val hasUpdate: Boolean,
    val preferredSourceId: String? = null,
    val location: ReadingLocation? = null,
    val chapterCount: Int = 0,
    val unreadCount: Int = 0,
    val lastChapterTitle: String? = null,
    val readingChapterPosition: Int? = null,
)

data class ReadingLocation(
    val bookId: String,
    val sourceId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val offset: Int,
    val updatedAtEpochMillis: Long,
    val contentFormatVersion: Int = ChapterContentFormat.CURRENT,
)

interface BookRepository {
    suspend fun saveSearchHit(hit: SearchHit): Book
    suspend fun getBook(bookId: String): Book?
    /** Returns the existing book with optional metadata enriched, or null if the snapshots are no longer current. */
    suspend fun saveDetails(expectedBook: Book, expectedSource: BookSource, details: BookDetails): Book? = null
    /** Read-only snapshot check before publishing page identity; false also means verification is unavailable. */
    suspend fun detailsAreCurrent(expectedBook: Book, expectedSource: BookSource): Boolean = false
    fun observeSources(bookId: String): Flow<List<BookSource>>
    fun observeShelf(): Flow<List<ShelfBook>>
    suspend fun addToShelf(bookId: String, atEpochMillis: Long)
    suspend fun addToShelf(bookId: String, atEpochMillis: Long, sourceId: String) {
        addToShelf(bookId, atEpochMillis)
    }
    suspend fun preferredSource(bookId: String): String? = null
    suspend fun selectPreferredSource(bookId: String, sourceId: String) = Unit
    suspend fun removeFromShelf(bookId: String)
    fun progress(bookId: String): Flow<ReadingLocation?>
    suspend fun saveProgress(location: ReadingLocation)
}
