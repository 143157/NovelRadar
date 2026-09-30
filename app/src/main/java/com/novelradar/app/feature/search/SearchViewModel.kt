package com.novelradar.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.search.AggregatedBookResult
import com.novelradar.app.domain.search.SearchSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SearchViewModel(
    private val search: (String) -> Flow<SearchSnapshot>,
    private val books: BookRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = mutableState.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SearchUiState(),
    )
    private val navigationChannel = Channel<String>(Channel.BUFFERED)
    val navigation: Flow<String> = navigationChannel.receiveAsFlow()
    private var searchJob: Job? = null
    private var generation = 0L

    fun onQueryChanged(query: String) {
        if (query == mutableState.value.query) return
        generation++
        searchJob?.cancel()
        mutableState.value = SearchUiState(query = query)
    }

    fun submit() {
        val keyword = mutableState.value.query.trim()
        if (keyword.isEmpty()) return
        generation++
        val currentGeneration = generation
        searchJob?.cancel()
        mutableState.value = SearchUiState(query = mutableState.value.query, hasSearched = true, searching = true)
        searchJob = viewModelScope.launch {
            try {
                search(keyword).collect { snapshot ->
                    if (currentGeneration == generation) {
                        mutableState.update { current ->
                            current.copy(
                                searching = true,
                                completedSources = snapshot.completedSources,
                                totalSources = snapshot.totalSources,
                                results = snapshot.results,
                                failures = snapshot.failures,
                                sourceNames = snapshot.sourceNames,
                                errorMessage = null,
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (currentGeneration == generation) {
                    mutableState.update { it.copy(errorMessage = "搜索暂时无法开始，请重试") }
                }
            } finally {
                if (currentGeneration == generation) mutableState.update { it.copy(searching = false) }
            }
        }
    }

    fun retryFailed() {
        if (mutableState.value.failures.isNotEmpty() || mutableState.value.errorMessage != null) submit()
    }

    fun selectResult(result: AggregatedBookResult) {
        if (result.sources.isEmpty()) return
        viewModelScope.launch {
            try {
                var bookId: String? = null
                for (hit in result.sources) {
                    // All source mappings share one canonical book identity, even when one site omits the author.
                    val canonical = hit.copy(bookName = result.displayName, author = result.author)
                    val saved = books.saveSearchHit(canonical)
                    if (bookId == null) bookId = saved.bookId
                }
                bookId?.let { navigationChannel.send(it) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(errorMessage = "保存书籍失败，请重试") }
            }
        }
    }
}
