package com.novelradar.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.novelradar.app.data.db.entity.BookEntity
import com.novelradar.app.data.db.entity.BookSourceEntity
import com.novelradar.app.data.db.entity.ReadingProgressEntity
import com.novelradar.app.data.db.entity.ShelfEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Upsert
    suspend fun upsertBook(book: BookEntity)

    @Upsert
    suspend fun upsertSource(source: BookSourceEntity)

    @Transaction
    suspend fun upsertBookAndSource(book: BookEntity, source: BookSourceEntity) {
        require(book.bookId == source.bookId) { "Book source belongs to another book" }
        upsertBook(book)
        upsertSource(source)
    }

    @Query("SELECT * FROM books WHERE bookId = :bookId LIMIT 1")
    suspend fun getBook(bookId: String): BookEntity?

    @Query("SELECT * FROM books WHERE normalizedBookKey = :normalizedBookKey ORDER BY bookId LIMIT 1")
    suspend fun getByNormalizedKey(normalizedBookKey: String): BookEntity?

    @Query("SELECT * FROM book_sources WHERE bookId = :bookId ORDER BY sourceId")
    fun observeSources(bookId: String): Flow<List<BookSourceEntity>>

    @Query("SELECT * FROM book_sources WHERE bookId = :bookId AND sourceId = :sourceId LIMIT 1")
    suspend fun getSource(bookId: String, sourceId: String): BookSourceEntity?

    @Upsert
    suspend fun upsertShelfEntry(entry: ShelfEntryEntity)

    @Query(
        "SELECT shelf_entries.* FROM shelf_entries " +
            "INNER JOIN books ON books.bookId = shelf_entries.bookId " +
            "ORDER BY shelf_entries.lastReadAtEpochMillis DESC, shelf_entries.addedAtEpochMillis DESC",
    )
    fun observeShelf(): Flow<List<ShelfEntryEntity>>

    @Query("DELETE FROM shelf_entries WHERE bookId = :bookId")
    suspend fun removeFromShelf(bookId: String)

    @Upsert
    suspend fun upsertProgress(progress: ReadingProgressEntity)

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId LIMIT 1")
    fun progress(bookId: String): Flow<ReadingProgressEntity?>

    @Query("DELETE FROM books WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: String)
}
