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

    /** Parsed identity is valid only for the book and source URL that began this request. */
    @Transaction
    suspend fun detailsAreCurrent(expectedBook: BookEntity, expectedSource: BookSourceEntity): Boolean {
        if (expectedSource.bookId != expectedBook.bookId || getBook(expectedBook.bookId) != expectedBook) return false
        return getSource(expectedBook.bookId, expectedSource.sourceId)?.detailUrl == expectedSource.detailUrl
    }

    /** Compare the request snapshots and enrich only this existing book, in one transaction. */
    @Transaction
    suspend fun saveDetailsIfCurrent(
        expectedBook: BookEntity,
        expectedSource: BookSourceEntity,
        coverUrl: String?,
        introduction: String?,
    ): BookEntity? {
        if (expectedSource.bookId != expectedBook.bookId || getBook(expectedBook.bookId) != expectedBook) return null
        val source = getSource(expectedBook.bookId, expectedSource.sourceId) ?: return null
        if (source.detailUrl != expectedSource.detailUrl) return null
        val updated = expectedBook.copy(
            coverUrl = coverUrl?.takeIf(String::isNotBlank) ?: expectedBook.coverUrl,
            introduction = introduction?.takeIf(String::isNotBlank) ?: expectedBook.introduction,
        )
        upsertBook(updated)
        return updated
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

    @Query("SELECT * FROM shelf_entries WHERE bookId = :bookId")
    suspend fun getShelfEntry(bookId: String): ShelfEntryEntity?

    @Transaction
    suspend fun addShelfPreservingHistory(bookId: String, at: Long, sourceId: String?) {
        requireNotNull(getBook(bookId)) { "Unknown book" }
        if (sourceId != null) requireNotNull(getSource(bookId, sourceId)) { "Source does not belong to book" }
        val old = getShelfEntry(bookId)
        val location = if (old == null) getProgress(bookId)?.takeIf {
            getSource(bookId, it.sourceId) != null
        } else null
        upsertShelfEntry(old?.copy(preferredSourceId = sourceId ?: old.preferredSourceId)
            ?: ShelfEntryEntity(bookId, at, lastReadAtEpochMillis = location?.updatedAtEpochMillis,
                preferredSourceId = sourceId ?: location?.sourceId))
    }

    @Transaction
    suspend fun saveReadingLocation(progress: ReadingProgressEntity) {
        requireNotNull(getSource(progress.bookId, progress.sourceId)) { "Source does not belong to book" }
        upsertProgress(progress)
        getShelfEntry(progress.bookId)?.let {
            upsertShelfEntry(it.copy(lastReadAtEpochMillis = progress.updatedAtEpochMillis,
                preferredSourceId = progress.sourceId))
        }
    }

    @Transaction
    suspend fun selectShelfSource(bookId: String, sourceId: String) {
        requireNotNull(getSource(bookId, sourceId)) { "Source does not belong to book" }
        getShelfEntry(bookId)?.let { upsertShelfEntry(it.copy(preferredSourceId = sourceId)) }
    }

    @Query("""
        SELECT b.bookId AS book_bookId, b.normalizedBookKey AS book_normalizedBookKey,
        b.bookName AS book_bookName, b.author AS book_author, b.coverUrl AS book_coverUrl,
        b.introduction AS book_introduction,
        s.bookId AS shelf_bookId, s.addedAtEpochMillis AS shelf_addedAtEpochMillis,
        s.lastReadAtEpochMillis AS shelf_lastReadAtEpochMillis, s.hasUpdate AS shelf_hasUpdate,
        s.preferredSourceId AS shelf_preferredSourceId,
        p.bookId AS progress_bookId, p.sourceId AS progress_sourceId, p.chapterId AS progress_chapterId,
        p.chapterIndex AS progress_chapterIndex, p.offset AS progress_offset,
        p.updatedAtEpochMillis AS progress_updatedAtEpochMillis,
        p.contentFormatVersion AS progress_contentFormatVersion,
        bs.sourceId AS selectedSourceId, COUNT(c.chapterId) AS chapterCount,
        SUM(CASE WHEN c.chapterId IS NOT NULL AND (p.sourceId IS NULL OR p.sourceId != bs.sourceId OR c.chapterIndex > p.chapterIndex) THEN 1 ELSE 0 END) AS unreadCount,
        (SELECT title FROM chapters WHERE bookId = b.bookId AND sourceId = bs.sourceId ORDER BY chapterIndex DESC LIMIT 1) AS lastChapterTitle
        FROM shelf_entries s JOIN books b ON b.bookId = s.bookId
        LEFT JOIN reading_progress p ON p.bookId = b.bookId
        LEFT JOIN book_sources bs ON bs.bookId = b.bookId AND bs.sourceId = COALESCE(
            (SELECT sourceId FROM book_sources WHERE bookId = b.bookId AND sourceId = s.preferredSourceId),
            (SELECT sourceId FROM book_sources WHERE bookId = b.bookId AND sourceId = p.sourceId),
            (SELECT sourceId FROM book_sources WHERE bookId = b.bookId ORDER BY sourceId LIMIT 1))
        LEFT JOIN chapters c ON c.bookId = b.bookId AND c.sourceId = bs.sourceId
        GROUP BY b.bookId ORDER BY s.lastReadAtEpochMillis DESC, s.addedAtEpochMillis DESC, b.bookId
    """)
    fun observeShelfDetails(): Flow<List<ShelfProjection>>

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

    @Query("SELECT * FROM reading_progress WHERE bookId = :bookId LIMIT 1")
    suspend fun getProgress(bookId: String): ReadingProgressEntity?

    @Query("DELETE FROM books WHERE bookId = :bookId")
    suspend fun deleteBook(bookId: String)
}
