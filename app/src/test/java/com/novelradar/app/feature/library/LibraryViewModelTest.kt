package com.novelradar.app.feature.library

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.*
import com.novelradar.app.domain.repository.*
import com.novelradar.app.feature.book.BookDetailViewModelTest.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun staleIndexAndLegacyPositionNeverClaimFreshWholeBookProgress() {
        val stale = LibraryBook(item("book", 1).copy(location = ReadingLocation("book", "a", "removed", 99, 77, 1),
            readingChapterPosition = null))
        assertThat(stale.percent).isEqualTo(0)
        assertThat(stale.positionLabel).isEqualTo("原阅读位置 第 100 章（待核对）")
        val legacy = LibraryBook(item("book", 1).copy(location = ReadingLocation("book", "a", "2", 2, 77, 1,
            ChapterContentFormat.LEGACY)))
        assertThat(legacy.percent).isEqualTo(0)
        assertThat(legacy.positionLabel).isEqualTo("原阅读位置 第 3 章（待核对）")
    }

    @Test fun sharedLabelUsesActualOrdinalOnlyForMatchingCurrentPosition() {
        val shelf = item("book", 1).copy(location = ReadingLocation("book", "a", "2", 999, 77, 1))
        assertThat(LibraryBook(shelf).positionLabel).isEqualTo("读到第 3 / 10 章")
        assertThat(LibraryBook(shelf).percent).isEqualTo(30)
        for (format in listOf(ChapterContentFormat.LEGACY, 999)) {
            val book = LibraryBook(shelf.copy(location = shelf.location!!.copy(contentFormatVersion = format)))
            assertThat(book.positionLabel).isEqualTo("原阅读位置 第 1000 章（待核对）")
            assertThat(book.percent).isEqualTo(0)
        }
        for (position in listOf(null, 0, 11)) {
            assertThat(LibraryBook(shelf.copy(readingChapterPosition = position)).positionLabel).contains("待核对")
            assertThat(LibraryBook(shelf.copy(readingChapterPosition = position)).percent).isEqualTo(0)
        }
        assertThat(LibraryBook(shelf.copy(location = null)).positionLabel).isEqualTo("尚未开始阅读")
        assertThat(LibraryBook(shelf.copy(preferredSourceId = "b")).positionLabel).isEqualTo("尚未开始阅读")
        val foreign = LibraryBook(shelf.copy(location = shelf.location!!.copy(bookId = "foreign")))
        assertThat(foreign.positionLabel).isEqualTo("尚未开始阅读")
        assertThat(LibraryUiState(books = listOf(foreign)).continueReading).isNull()
    }

    @Test fun lastReadBookIsContinueTargetAndPercentUsesSelectedSourceOnly() = runTest {
        val books = MemoryShelf()
        books.shelf.value = listOf(item("old", 20), item("new", 40))
        val vm = LibraryViewModel(books, refresh = { emptyMap() }, deleteLocal = {})
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertThat(vm.uiState.value.continueReading?.shelf?.book?.bookId).isEqualTo("new")
        assertThat(vm.uiState.value.books.first().percent).isEqualTo(30)
        assertThat(vm.uiState.value.books.first().shelf.unreadCount).isEqualTo(7)
    }

    @Test fun openUsesSelectedSourceAndLeavesChapterUnspecifiedToRestoreOffset() = runTest {
        val books = MemoryShelf()
        books.shelf.value = listOf(item("book", 1))
        val vm = LibraryViewModel(books, refresh = { emptyMap() }, deleteLocal = {})
        val events = mutableListOf<LibraryOpen>()
        backgroundScope.launch { vm.navigation.collect { events += it } }
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        books.location = ReadingLocation("book", "b", "latest", 5, 177, 99)
        vm.open("book")
        advanceUntilIdle()
        runCurrent()
        assertThat(events.single()).isEqualTo(LibraryOpen("book", "a"))
    }

    @Test fun preferredSourceWithOldForeignProgressDoesNotClaimContinuePosition() = runTest {
        val books = MemoryShelf()
        books.shelf.value = listOf(item("book", 1).copy(preferredSourceId = "b"))
        val vm = LibraryViewModel(books, refresh = { emptyMap() }, deleteLocal = {})
        advanceUntilIdle()
        assertThat(vm.uiState.value.continueReading).isNull()
        assertThat(vm.uiState.value.books.single().percent).isEqualTo(0)
    }

    @Test fun removalKeepsLocalContentUnlessSeparatelyChecked() = runTest {
        val books = MemoryShelf()
        books.shelf.value = listOf(item("a", 1), item("b", 2))
        val deleted = mutableListOf<String>()
        val vm = LibraryViewModel(books, refresh = { emptyMap() }, deleteLocal = { deleted += it })
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.requestRemoval("a")
        vm.confirmRemoval(deleteLocal = false)
        advanceUntilIdle()
        assertThat(deleted).isEmpty()
        assertThat(books.shelf.value.map { it.book.bookId }).containsExactly("b")
        vm.requestRemoval("b")
        vm.confirmRemoval(deleteLocal = true)
        advanceUntilIdle()
        assertThat(deleted).containsExactly("b")
        assertThat(vm.uiState.value.books).isEmpty()
    }

    @Test fun partialRefreshWarningKeepsShelfCounts() = runTest {
        val books = MemoryShelf()
        books.shelf.value = listOf(item("a", 1))
        val vm = LibraryViewModel(books, refresh = { mapOf("a" to SourceFailure.Timeout) }, deleteLocal = {})
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.refresh()
        advanceUntilIdle()
        assertThat(vm.uiState.value.books.single().shelf.unreadCount).isEqualTo(7)
        assertThat(vm.uiState.value.refreshWarnings["a"]).isEqualTo(SourceFailure.Timeout)
        assertThat(vm.uiState.value.refreshing).isFalse()
    }

    private fun item(id: String, lastRead: Long) = ShelfBook(Book(id, id, id, "作者"), 1, lastRead,
        false, "a", ReadingLocation(id, "a", "2", 2, 7, lastRead), 10, 7, "第十章", readingChapterPosition = 3)

    private class MemoryShelf : BookRepository {
        val shelf = MutableStateFlow<List<ShelfBook>>(emptyList())
        var location: ReadingLocation? = null
        override fun observeShelf() = shelf
        override fun progress(bookId: String) = flowOf(location)
        override suspend fun getBook(bookId: String) = shelf.value.firstOrNull { it.book.bookId == bookId }?.book
        override fun observeSources(bookId: String) = flowOf(listOf(BookSource(bookId, "a", "https://example.org/a"), BookSource(bookId, "b", "https://example.org/b")))
        override suspend fun removeFromShelf(bookId: String) { shelf.value = shelf.value.filterNot { it.book.bookId == bookId } }
        override suspend fun addToShelf(bookId: String, atEpochMillis: Long) = Unit
        override suspend fun saveProgress(location: ReadingLocation) = Unit
        override suspend fun saveSearchHit(hit: SearchHit): Book = error("unused")
    }
}
