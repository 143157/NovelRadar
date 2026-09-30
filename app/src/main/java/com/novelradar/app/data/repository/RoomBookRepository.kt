package com.novelradar.app.data.repository

import com.novelradar.app.data.db.BookDao
import com.novelradar.app.data.db.entity.BookEntity
import com.novelradar.app.data.db.entity.BookSourceEntity
import com.novelradar.app.data.db.entity.ReadingProgressEntity
import com.novelradar.app.data.db.entity.ShelfEntryEntity
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceHealthStatus
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.repository.ShelfBook
import java.security.MessageDigest
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomBookRepository(private val dao: BookDao) : BookRepository {
    override suspend fun saveSearchHit(hit: SearchHit): Book {
        require(hit.bookName.isNotBlank() && hit.detailUrl.isNotBlank() && hit.sourceId.isNotBlank())
        val old = dao.getByNormalizedKey(hit.normalizedBookKey)
        val book = BookEntity(
            bookId = old?.bookId ?: stableBookId(hit.normalizedBookKey),
            normalizedBookKey = hit.normalizedBookKey,
            bookName = old?.bookName ?: hit.bookName,
            author = old?.author ?: hit.author,
            coverUrl = hit.coverUrl ?: old?.coverUrl,
            introduction = hit.introduction ?: old?.introduction,
        )
        val source = dao.getSource(book.bookId, hit.sourceId)
            ?.copy(detailUrl = hit.detailUrl)
            ?: BookSourceEntity(book.bookId, hit.sourceId, hit.detailUrl)
        dao.upsertBookAndSource(book, source)
        return book.toDomain()
    }

    override suspend fun getBook(bookId: String): Book? = dao.getBook(bookId)?.toDomain()

    override fun observeSources(bookId: String): Flow<List<BookSource>> = dao.observeSources(bookId).map { rows ->
        rows.map { it.toDomain() }
    }

    override fun observeShelf(): Flow<List<ShelfBook>> = dao.observeShelf().map { rows ->
        rows.mapNotNull { row ->
            dao.getBook(row.bookId)?.let { book ->
                ShelfBook(book.toDomain(), row.addedAtEpochMillis, row.lastReadAtEpochMillis, row.hasUpdate)
            }
        }
    }

    override suspend fun addToShelf(bookId: String, atEpochMillis: Long) {
        requireNotNull(dao.getBook(bookId)) { "Unknown book" }
        dao.upsertShelfEntry(ShelfEntryEntity(bookId, atEpochMillis))
    }

    override suspend fun removeFromShelf(bookId: String) = dao.removeFromShelf(bookId)

    override fun progress(bookId: String): Flow<ReadingLocation?> = dao.progress(bookId).map { it?.toDomain() }

    override suspend fun saveProgress(location: ReadingLocation) {
        require(location.chapterIndex >= 0 && location.offset >= 0) { "Invalid reading position" }
        requireNotNull(dao.getBook(location.bookId)) { "Unknown book" }
        dao.upsertProgress(ReadingProgressEntity(
            location.bookId, location.sourceId, location.chapterId,
            location.chapterIndex, location.offset, location.updatedAtEpochMillis,
        ))
    }
}

private fun stableBookId(key: String): String = MessageDigest.getInstance("SHA-256")
    .digest(key.toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }

private fun BookEntity.toDomain() = Book(bookId, normalizedBookKey, bookName, author, coverUrl, introduction)

private fun BookSourceEntity.toDomain() = BookSource(
    bookId, sourceId, detailUrl,
    SourceHealthStatus.entries.firstOrNull { it.name == status } ?: SourceHealthStatus.UNKNOWN,
    updatedAtEpochMillis,
)

private fun ReadingProgressEntity.toDomain() = ReadingLocation(
    bookId, sourceId, chapterId, chapterIndex, offset, updatedAtEpochMillis,
)
