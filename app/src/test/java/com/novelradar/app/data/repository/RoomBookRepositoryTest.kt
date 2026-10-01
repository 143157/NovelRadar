package com.novelradar.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.BookDao
import com.novelradar.app.data.db.ShelfProjection
import com.novelradar.app.data.db.entity.BookEntity
import com.novelradar.app.data.db.entity.BookSourceEntity
import com.novelradar.app.data.db.entity.ReadingProgressEntity
import com.novelradar.app.data.db.entity.ShelfEntryEntity
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceHealthStatus
import com.novelradar.app.domain.repository.ReadingLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomBookRepositoryTest {
    private val dao = FakeBookDao()
    private val repository = RoomBookRepository(dao)

    @Test fun searchHitCreatesOneBookWithMultipleSourceMappings() = runTest {
        val first = repository.saveSearchHit(hit("source-a"))
        val second = repository.saveSearchHit(hit("source-b"))

        assertThat(second.bookId).isEqualTo(first.bookId)
        assertThat(repository.observeSources(first.bookId).first().map { it.sourceId })
            .containsExactly("source-a", "source-b")
    }

    @Test fun repeatedSearchRefreshesUrlWithoutErasingSourceHealth() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        dao.upsertSource(BookSourceEntity(book.bookId, "source-a", "https://old.example/book", "HEALTHY", 123L))

        repository.saveSearchHit(hit("source-a").copy(detailUrl = "https://new.example/book"))

        val source = repository.observeSources(book.bookId).first().single()
        assertThat(source.detailUrl).isEqualTo("https://new.example/book")
        assertThat(source.status).isEqualTo(SourceHealthStatus.HEALTHY)
        assertThat(source.updatedAtEpochMillis).isEqualTo(123L)
    }

    @Test fun shelfAndProgressAreExposedWithoutRoomEntities() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        repository.addToShelf(book.bookId, 100L)
        repository.saveProgress(ReadingLocation(book.bookId, "source-a", "chapter-2", 2, 12, 200L))

        assertThat(repository.observeShelf().first().single().book).isEqualTo(book)
        assertThat(repository.progress(book.bookId).first()!!.chapterIndex).isEqualTo(2)
        repository.removeFromShelf(book.bookId)
        assertThat(repository.observeShelf().first()).isEmpty()
    }

    @Test fun savingNewLocationReplacesOnlyThatBooksProgressRow() = runTest {
        val first = repository.saveSearchHit(hit("source-a"))
        val second = repository.saveSearchHit(hit("source-a").copy(bookName = "另一部小说"))
        repository.saveProgress(ReadingLocation(first.bookId, "source-a", "chapter-1", 0, 1, 100L))
        repository.saveProgress(ReadingLocation(second.bookId, "source-a", "chapter-3", 2, 3, 101L))

        val updated = ReadingLocation(first.bookId, "source-a", "chapter-2", 1, 4, 102L)
        repository.saveProgress(updated)

        assertThat(repository.progress(first.bookId).first()).isEqualTo(updated)
        assertThat(repository.progress(second.bookId).first()!!.chapterId).isEqualTo("chapter-3")
    }

    @Test fun repeatedShelfAddPreservesAddedAndLastReadTimes() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        repository.addToShelf(book.bookId, 100L)
        repository.saveProgress(ReadingLocation(book.bookId, "source-a", "chapter-2", 1, 5, 200L))
        repository.addToShelf(book.bookId, 300L)
        val shelf = repository.observeShelf().first().single()
        assertThat(shelf.addedAtEpochMillis).isEqualTo(100L)
        assertThat(shelf.lastReadAtEpochMillis).isEqualTo(200L)
    }

    @Test fun progressRejectsSourceFromAnotherBook() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        val failure = runCatching { repository.saveProgress(ReadingLocation(book.bookId, "other", "1", 0, 0, 1L)) }
        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    private fun hit(sourceId: String) = SearchHit(
        sourceId = sourceId,
        bookName = "测试小说",
        author = "测试作者",
        detailUrl = "https://example.org/$sourceId",
    )

    private class FakeBookDao : BookDao {
        private val books = linkedMapOf<String, BookEntity>()
        private val sources = MutableStateFlow<List<BookSourceEntity>>(emptyList())
        private val shelf = MutableStateFlow<List<ShelfEntryEntity>>(emptyList())
        private val progresses = MutableStateFlow<List<ReadingProgressEntity>>(emptyList())

        override suspend fun upsertBook(book: BookEntity) { books[book.bookId] = book }
        override suspend fun upsertSource(source: BookSourceEntity) {
            sources.value = sources.value.filterNot { it.bookId == source.bookId && it.sourceId == source.sourceId } + source
        }
        override suspend fun getBook(bookId: String): BookEntity? = books[bookId]
        override suspend fun getByNormalizedKey(normalizedBookKey: String): BookEntity? =
            books.values.firstOrNull { it.normalizedBookKey == normalizedBookKey }
        override fun observeSources(bookId: String): Flow<List<BookSourceEntity>> =
            sources.map { rows -> rows.filter { it.bookId == bookId } }
        override suspend fun getSource(bookId: String, sourceId: String): BookSourceEntity? =
            sources.value.firstOrNull { it.bookId == bookId && it.sourceId == sourceId }
        override suspend fun upsertShelfEntry(entry: ShelfEntryEntity) {
            shelf.value = shelf.value.filterNot { it.bookId == entry.bookId } + entry
        }
        override fun observeShelf(): Flow<List<ShelfEntryEntity>> = shelf
        override suspend fun getShelfEntry(bookId: String) = shelf.value.firstOrNull { it.bookId == bookId }
        override fun observeShelfDetails(): Flow<List<ShelfProjection>> = kotlinx.coroutines.flow.combine(shelf, progresses) { rows, locations ->
            rows.sortedByDescending { it.lastReadAtEpochMillis ?: it.addedAtEpochMillis }.map { row ->
                val progress = locations.firstOrNull { it.bookId == row.bookId }
                ShelfProjection(books.getValue(row.bookId), row, progress, row.preferredSourceId ?: progress?.sourceId, 0, 0, null)
            }
        }
        override suspend fun removeFromShelf(bookId: String) {
            shelf.value = shelf.value.filterNot { it.bookId == bookId }
        }
        override suspend fun upsertProgress(progress: ReadingProgressEntity) {
            progresses.value = progresses.value.filterNot { it.bookId == progress.bookId } + progress
        }
        override fun progress(bookId: String): Flow<ReadingProgressEntity?> =
            progresses.map { rows -> rows.firstOrNull { it.bookId == bookId } }
        override suspend fun deleteBook(bookId: String) { books.remove(bookId) }
    }
}
