package com.novelradar.app.domain.repository

import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.SearchHit
import kotlinx.coroutines.flow.Flow

data class ShelfBook(
    val book: Book,
    val addedAtEpochMillis: Long,
    val lastReadAtEpochMillis: Long?,
    val hasUpdate: Boolean,
)

data class ReadingLocation(
    val bookId: String,
    val sourceId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val offset: Int,
    val updatedAtEpochMillis: Long,
)

interface BookRepository {
    suspend fun saveSearchHit(hit: SearchHit): Book
    suspend fun getBook(bookId: String): Book?
    fun observeSources(bookId: String): Flow<List<BookSource>>
    fun observeShelf(): Flow<List<ShelfBook>>
    suspend fun addToShelf(bookId: String, atEpochMillis: Long)
    suspend fun removeFromShelf(bookId: String)
    fun progress(bookId: String): Flow<ReadingLocation?>
    suspend fun saveProgress(location: ReadingLocation)
}
