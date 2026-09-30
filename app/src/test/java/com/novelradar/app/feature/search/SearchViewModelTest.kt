package com.novelradar.app.feature.search

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.repository.ShelfBook
import com.novelradar.app.domain.search.AggregatedBookResult
import com.novelradar.app.domain.search.SearchSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val books = FakeBooks()

    @Test fun blankQueryDoesNotStartSearch() = runTest {
        var calls = 0
        val viewModel = SearchViewModel({ calls++; emptyFlow() }, books)

        viewModel.submit()
        advanceUntilIdle()

        assertThat(calls).isEqualTo(0)
        assertThat(viewModel.uiState.value.results).isEmpty()
    }

    @Test fun progressiveResultsAndFailuresRemainVisible() = runTest {
        val result = result()
        val viewModel = SearchViewModel({ query -> flow {
            emit(SearchSnapshot(query, totalSources = 2))
            delay(10)
            emit(SearchSnapshot(query, results = listOf(result), completedSources = 1, totalSources = 2))
            delay(10)
            emit(SearchSnapshot(query, results = listOf(result), completedSources = 2, totalSources = 2,
                failures = mapOf("bad" to SourceFailure.Timeout), sourceNames = mapOf("bad" to "失效书源")))
        } }, books)

        viewModel.uiState.test {
            assertThat(awaitItem().results).isEmpty()
            viewModel.onQueryChanged("星河")
            viewModel.submit()
            advanceTimeBy(11)
            runCurrent()
            assertThat(viewModel.uiState.value.results).hasSize(1)
            assertThat(viewModel.uiState.value.completedSources).isEqualTo(1)
            advanceUntilIdle()
            assertThat(viewModel.uiState.value.searching).isFalse()
            assertThat(viewModel.uiState.value.failures.keys).containsExactly("bad")
            assertThat(viewModel.uiState.value.sourceNames["bad"]).isEqualTo("失效书源")
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test fun newQueryCancelsPreviousSearch() = runTest {
        var cancelled = false
        val viewModel = SearchViewModel({ query -> flow {
            emit(SearchSnapshot(query, totalSources = 1))
            if (query == "旧") try { awaitCancellation() } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        } }, books)
        val keepStateActive = backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.onQueryChanged("旧")
        viewModel.submit()
        runCurrent()
        viewModel.onQueryChanged("新")
        viewModel.submit()
        advanceUntilIdle()

        assertThat(cancelled).isTrue()
        assertThat(viewModel.uiState.value.query).isEqualTo("新")
        keepStateActive.cancel()
    }

    @Test fun retryRunsSearchAgainWhenSourcesFailed() = runTest {
        var calls = 0
        val viewModel = SearchViewModel({ query -> flow {
            calls++
            emit(SearchSnapshot(query, totalSources = 1, completedSources = 1,
                failures = if (calls == 1) mapOf("bad" to SourceFailure.Timeout) else emptyMap()))
        } }, books)
        val keepStateActive = backgroundScope.launch { viewModel.uiState.collect {} }

        viewModel.onQueryChanged("星河")
        viewModel.submit()
        advanceUntilIdle()
        viewModel.retryFailed()
        advanceUntilIdle()

        assertThat(calls).isEqualTo(2)
        assertThat(viewModel.uiState.value.failures).isEmpty()
        keepStateActive.cancel()
    }

    @Test fun selectingResultPersistsSourceMappingAndEmitsBookId() = runTest {
        val viewModel = SearchViewModel({ emptyFlow() }, books)
        val selected = result()
        val navigation = backgroundScope.async { viewModel.navigation.first() }

        viewModel.selectResult(selected)
        advanceUntilIdle()

        assertThat(books.savedHits).hasSize(1)
        assertThat(books.savedHits.single().author).isEqualTo("作者")
        assertThat(navigation.await()).isEqualTo("book-1")
    }

    private fun result(): AggregatedBookResult {
        val hit = SearchHit("good", "星河", "作者", "https://example.org/book")
        return AggregatedBookResult("星河|作者", "星河", "作者", listOf(hit), hit)
    }

    private class FakeBooks : BookRepository {
        val savedHits = mutableListOf<SearchHit>()
        override suspend fun saveSearchHit(hit: SearchHit): Book {
            savedHits += hit
            return Book("book-1", hit.normalizedBookKey, hit.bookName, hit.author)
        }
        override suspend fun getBook(bookId: String): Book? = null
        override fun observeSources(bookId: String): Flow<List<BookSource>> = emptyFlow()
        override fun observeShelf(): Flow<List<ShelfBook>> = emptyFlow()
        override suspend fun addToShelf(bookId: String, atEpochMillis: Long) = Unit
        override suspend fun removeFromShelf(bookId: String) = Unit
        override fun progress(bookId: String): Flow<ReadingLocation?> = emptyFlow()
        override suspend fun saveProgress(location: ReadingLocation) = Unit
    }

    class MainDispatcherRule : TestWatcher() {
        private val dispatcher = StandardTestDispatcher()
        override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }
}
