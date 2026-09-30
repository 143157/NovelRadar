package com.novelradar.app.feature.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class BookDetailViewModel(
    private val bookId: String,
    private val catalog: CatalogRepository,
    private val books: BookRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(BookDetailUiState())
    val uiState: StateFlow<BookDetailUiState> = mutableState.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), BookDetailUiState(),
    )
    private var catalogJob: Job? = null

    init {
        viewModelScope.launch {
            try {
                val book = catalog.book(bookId)
                val sources = catalog.sources(bookId).first()
                val sourceNames = catalog.sourceNames()
                mutableState.update { it.copy(book = book, sources = sources,
                    sourceNames = sourceNames, loading = false) }
                sources.firstOrNull()?.let { selectSource(it.sourceId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, notice = "无法读取书籍信息") }
            }
        }
    }

    fun selectSource(sourceId: String) {
        if (mutableState.value.sources.none { it.sourceId == sourceId }) return
        catalogJob?.cancel()
        mutableState.update { it.copy(selectedSourceId = sourceId, chapters = emptyList(), loading = true,
            refreshing = false, warning = null) }
        load(sourceId, force = false)
    }

    fun refresh() {
        val sourceId = mutableState.value.selectedSourceId ?: return
        catalogJob?.cancel()
        mutableState.update { it.copy(refreshing = true, warning = null) }
        load(sourceId, force = true)
    }

    private fun load(sourceId: String, force: Boolean) {
        catalogJob = viewModelScope.launch {
            try {
                val result = catalog.catalog(bookId, sourceId, force)
                if (mutableState.value.selectedSourceId == sourceId) mutableState.update {
                    it.copy(chapters = result.chapters, loading = false, refreshing = false, warning = result.warning)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (mutableState.value.selectedSourceId == sourceId) mutableState.update {
                    it.copy(loading = false, refreshing = false, notice = "目录加载失败，请重试")
                }
            }
        }
    }

    fun onChapterQueryChanged(query: String) { mutableState.update { it.copy(chapterQuery = query) } }
    fun toggleChapterOrder() { mutableState.update { it.copy(reversed = !it.reversed) } }

    fun addToShelf() {
        viewModelScope.launch {
            try {
                books.addToShelf(bookId, clock())
                mutableState.update { it.copy(addedToShelf = true, notice = "已加入书架") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = "加入书架失败，请重试") }
            }
        }
    }
}
