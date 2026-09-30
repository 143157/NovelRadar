package com.novelradar.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.repository.ShelfBook
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RoomReadingProgressRepositoryTest {
    private val books = MemoryBooks()
    private val repository = RoomReadingProgressRepository(books)

    @Test fun exactSaveReplacesProgressByBookId() = runTest {
        repository.saveExact(location("book-a", 0, 4))
        repository.saveExact(location("book-b", 1, 2))
        repository.saveExact(location("book-a", 2, 13))

        assertThat(repository.progress("book-a").first()).isEqualTo(location("book-a", 2, 13))
        assertThat(repository.progress("book-b").first()).isEqualTo(location("book-b", 1, 2))
        assertThat(books.saved).hasSize(3)
    }

    @Test fun positionChangesAreDistinctAndDebouncedFor750Millis() = runTest {
        val updates = MutableSharedFlow<ReadingLocation>(extraBufferCapacity = 10)
        backgroundScope.launch { repository.collectPositionChanges(updates) }
        runCurrent()

        updates.emit(location("book-a", 1, 10, 100))
        advanceTimeBy(500)
        updates.emit(location("book-a", 1, 20, 200))
        advanceTimeBy(749)
        runCurrent()
        assertThat(books.saved).isEmpty()
        advanceTimeBy(1)
        runCurrent()
        assertThat(books.saved).containsExactly(location("book-a", 1, 20, 200))

        updates.emit(location("book-a", 1, 20, 300))
        advanceTimeBy(750)
        runCurrent()
        assertThat(books.saved).hasSize(1)
    }

    @Test fun exactChapterChangeIsNotOverwrittenByOlderPendingScroll() = runTest {
        val updates = MutableSharedFlow<ReadingLocation>(extraBufferCapacity = 10)
        backgroundScope.launch { repository.collectPositionChanges(updates) }
        runCurrent()

        updates.emit(location("book-a", 1, 40, 100))
        runCurrent()
        repository.saveExact(location("book-a", 2, 0, 101))
        advanceTimeBy(750)
        runCurrent()

        assertThat(repository.progress("book-a").first()).isEqualTo(location("book-a", 2, 0, 101))
        assertThat(books.saved).hasSize(1)
    }

    private fun location(bookId: String, chapter: Int, offset: Int, updated: Long = 100L) =
        ReadingLocation(bookId, "source", "chapter-$chapter", chapter, offset, updated)

    private class MemoryBooks : BookRepository {
        private val locations = MutableStateFlow<Map<String, ReadingLocation>>(emptyMap())
        val saved = mutableListOf<ReadingLocation>()

        override fun progress(bookId: String): Flow<ReadingLocation?> = locations.map { it[bookId] }
        override suspend fun saveProgress(location: ReadingLocation) {
            saved += location
            locations.value = locations.value + (location.bookId to location)
        }
        override suspend fun saveSearchHit(hit: SearchHit): Book = error("unused")
        override suspend fun getBook(bookId: String): Book? = null
        override fun observeSources(bookId: String): Flow<List<BookSource>> = error("unused")
        override fun observeShelf(): Flow<List<ShelfBook>> = error("unused")
        override suspend fun addToShelf(bookId: String, atEpochMillis: Long) = Unit
        override suspend fun removeFromShelf(bookId: String) = Unit
    }
}
