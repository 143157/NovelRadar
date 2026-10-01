package com.novelradar.app.feature.book

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.CatalogRepository
import com.novelradar.app.domain.repository.CatalogResult
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.repository.ShelfBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun loadsFirstSourceThenSwitchesAndFiltersIndependentCatalog() = runTest {
        val catalog = FakeCatalog()
        val vm = BookDetailViewModel("book-1", catalog, FakeBooks())
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.book?.bookName).isEqualTo("星河旅人")
        assertThat(vm.uiState.value.selectedSourceId).isEqualTo("a")
        assertThat(vm.uiState.value.sourceNames["b"]).isEqualTo("书源乙")
        assertThat(vm.uiState.value.chapters.map { it.title }).containsExactly("第一章", "第二章")

        vm.selectSource("b")
        advanceUntilIdle()
        assertThat(vm.uiState.value.selectedSourceId).isEqualTo("b")
        assertThat(vm.uiState.value.chapters.map { it.title }).containsExactly("番外")
        vm.onChapterQueryChanged("番")
        assertThat(vm.uiState.value.visibleChapters.map { it.title }).containsExactly("番外")
        collector.cancel()
    }

    @Test fun refreshWarningRetainsCachedChaptersAndShelfActionPersists() = runTest {
        val catalog = FakeCatalog()
        val books = FakeBooks()
        val vm = BookDetailViewModel("book-1", catalog, books)
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        catalog.failRefresh = true
        vm.refresh()
        vm.addToShelf()
        advanceUntilIdle()

        assertThat(vm.uiState.value.chapters).hasSize(2)
        assertThat(vm.uiState.value.warning).isEqualTo(SourceFailure.Timeout)
        assertThat(vm.uiState.value.addedToShelf).isTrue()
        assertThat(books.added).isEqualTo("book-1")
        collector.cancel()
    }

    @Test fun preferredSourceRestoresAndSelectedSourceIsStoredWhenAdded() = runTest {
        val books = FakeBooks().apply { preferred = "b" }
        val vm = BookDetailViewModel("book-1", FakeCatalog(), books)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertThat(vm.uiState.value.selectedSourceId).isEqualTo("b")
        vm.selectSource("a")
        vm.addToShelf()
        advanceUntilIdle()
        assertThat(books.addedSource).isEqualTo("a")
        assertThat(books.preferred).isEqualTo("a")
    }

    private class FakeCatalog : CatalogRepository {
        var failRefresh = false
        override suspend fun book(bookId: String) = Book("book-1", "星河旅人|叶子", "星河旅人", "叶子")
        override suspend fun sourceNames() = mapOf("a" to "书源甲", "b" to "书源乙")
        override fun sources(bookId: String): Flow<List<BookSource>> = MutableStateFlow(listOf(
            BookSource("book-1", "a", "https://example.org/a"),
            BookSource("book-1", "b", "https://example.org/b"),
        ))
        override fun observeChapters(bookId: String, sourceId: String): Flow<List<Chapter>> = MutableStateFlow(emptyList())
        override suspend fun catalog(bookId: String, sourceId: String, forceRefresh: Boolean): CatalogResult {
            val chapters = if (sourceId == "a") listOf(
                Chapter(bookId, sourceId, "1", 0, "第一章", "https://example.org/1"),
                Chapter(bookId, sourceId, "2", 1, "第二章", "https://example.org/2"),
            ) else listOf(Chapter(bookId, sourceId, "3", 0, "番外", "https://example.org/3"))
            return CatalogResult(chapters, fromCache = failRefresh,
                warning = if (failRefresh && forceRefresh) SourceFailure.Timeout else null)
        }
    }

    private class FakeBooks : BookRepository {
        var added: String? = null
        var addedSource: String? = null
        var preferred: String? = null
        override suspend fun preferredSource(bookId: String) = preferred
        override suspend fun selectPreferredSource(bookId: String, sourceId: String) { preferred = sourceId }
        override suspend fun addToShelf(bookId: String, atEpochMillis: Long, sourceId: String) {
            added = bookId; addedSource = sourceId; preferred = sourceId
        }
        override suspend fun saveSearchHit(hit: SearchHit) = Book("book-1", "x", "星河旅人", "叶子")
        override suspend fun getBook(bookId: String) = Book("book-1", "x", "星河旅人", "叶子")
        override fun observeSources(bookId: String): Flow<List<BookSource>> = MutableStateFlow(emptyList())
        override fun observeShelf(): Flow<List<ShelfBook>> = MutableStateFlow(emptyList())
        override suspend fun addToShelf(bookId: String, atEpochMillis: Long) { added = bookId }
        override suspend fun removeFromShelf(bookId: String) = Unit
        override fun progress(bookId: String): Flow<ReadingLocation?> = MutableStateFlow(null)
        override suspend fun saveProgress(location: ReadingLocation) = Unit
    }

    class MainDispatcherRule : TestWatcher() {
        private val dispatcher = StandardTestDispatcher()
        override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }
}
