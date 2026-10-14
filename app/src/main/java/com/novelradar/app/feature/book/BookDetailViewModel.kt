package com.novelradar.app.feature.book

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.repository.CatalogRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
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
    private var loadVersion = 0L
    private val mismatchedSources = mutableSetOf<String>()
    // Source writes and shelf additions retain click order, even when storage is slow.
    private var sourceActionJob: Job? = null
    private var pendingSourceActions = 0

    init {
        viewModelScope.launch {
            try {
                books.progress(bookId).collect { location ->
                    mutableState.update { it.copy(readingLocation = location) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // Progress is optional display data; source/catalog actions remain independent.
            }
        }
        viewModelScope.launch {
            try {
                val book = catalog.book(bookId)
                val sources = catalog.sources(bookId).first()
                val sourceNames = catalog.sourceNames()
                val preferred = books.preferredSource(bookId)
                mutableState.update { it.copy(book = book, sources = sources,
                    sourceNames = sourceNames, loading = false) }
                (sources.firstOrNull { it.sourceId == preferred } ?: sources.firstOrNull())
                    ?.let { showSource(it.sourceId) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(loading = false, notice = "无法读取书籍信息") }
            }
        }
    }

    fun selectSource(sourceId: String) {
        if (mutableState.value.sources.none { it.sourceId == sourceId }) return
        beginSourceAction()
        val previous = sourceActionJob
        sourceActionJob = viewModelScope.launch {
            try {
                previous?.join()
                books.selectPreferredSource(bookId, sourceId)
                showSource(sourceId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = "书源选择保存失败，请重试") }
            } finally {
                finishSourceAction()
            }
        }
    }

    private fun showSource(sourceId: String) {
        catalogJob?.cancel()
        mutableState.update { it.copy(selectedSourceId = sourceId, chapters = emptyList(), loading = true,
            refreshing = false, warning = null, detailsWarning = null, notice = null,
            sourceIdentityMismatch = sourceId in mismatchedSources) }
        load(sourceId, force = false)
    }

    fun refresh() {
        val sourceId = mutableState.value.selectedSourceId ?: return
        catalogJob?.cancel()
        mutableState.update { it.copy(refreshing = true, warning = null, detailsWarning = null) }
        load(sourceId, force = true)
    }

    private fun load(sourceId: String, force: Boolean) {
        val version = ++loadVersion
        mutableState.update { it.copy(detailsLoading = true) }
        catalogJob = viewModelScope.launch {
            try {
                coroutineScope {
                    launch { loadCatalog(sourceId, force, version) }
                    launch { loadDetails(sourceId, version) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } finally {
                if (isCurrent(sourceId, version)) mutableState.update { it.copy(refreshing = false) }
            }
        }
    }

    private fun isCurrent(sourceId: String, version: Long): Boolean =
        loadVersion == version && mutableState.value.selectedSourceId == sourceId

    private suspend fun loadCatalog(sourceId: String, force: Boolean, version: Long) {
        try {
            val result = catalog.catalog(bookId, sourceId, force)
            if (isCurrent(sourceId, version)) mutableState.update {
                it.copy(chapters = result.chapters, loading = false, warning = result.warning)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (isCurrent(sourceId, version)) mutableState.update {
                it.copy(loading = false, notice = "目录加载失败，请重试")
            }
        }
    }

    private suspend fun loadDetails(sourceId: String, version: Long) {
        try {
            val result = catalog.refreshDetails(bookId, sourceId)
            if (isCurrent(sourceId, version)) {
                if (result.identityMismatch) mismatchedSources += sourceId
                else if (result.warning == null && mutableState.value.book?.let {
                    result.identity?.fullyMatches(it)
                } == true) mismatchedSources -= sourceId
                mutableState.update {
                    it.copy(book = result.book?.takeIf { book -> book.bookId == bookId } ?: it.book,
                        detailsLoading = false, detailsWarning = result.warning,
                        sourceIdentityMismatch = sourceId in mismatchedSources)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            if (isCurrent(sourceId, version)) mutableState.update {
                it.copy(detailsLoading = false, detailsWarning = SourceFailure.Unexpected("Could not load details"))
            }
        }
    }

    fun onChapterQueryChanged(query: String) { mutableState.update { it.copy(chapterQuery = query) } }
    fun toggleChapterOrder() { mutableState.update { it.copy(reversed = !it.reversed) } }

    fun readingTarget(chapter: Chapter? = null): BookReadTarget? {
        val current = mutableState.value
        if (current.sourceActionPending || current.sourceIdentityMismatch || current.loading || current.chapters.isEmpty()) return null
        val sourceId = current.selectedSourceId ?: return null
        if (current.sources.none { it.bookId == bookId && it.sourceId == sourceId }) return null
        if (chapter == null) return BookReadTarget(sourceId, null)
        if (chapter.bookId != bookId || chapter.sourceId != sourceId) return null
        val selected = current.chapters.firstOrNull {
            it.bookId == bookId && it.sourceId == sourceId && it.chapterId == chapter.chapterId
        } ?: return null
        return BookReadTarget(sourceId, selected.chapterId)
    }

    fun canLeave(): Boolean = !mutableState.value.sourceActionPending

    private fun beginSourceAction() {
        pendingSourceActions++
        mutableState.update { it.copy(sourceActionPending = true) }
    }

    private fun finishSourceAction() {
        pendingSourceActions--
        mutableState.update { it.copy(sourceActionPending = pendingSourceActions > 0) }
    }

    fun addToShelf() {
        beginSourceAction()
        val previous = sourceActionJob
        sourceActionJob = viewModelScope.launch {
            try {
                previous?.join()
                val sourceId = mutableState.value.selectedSourceId ?: return@launch
                books.addToShelf(bookId, clock(), sourceId)
                mutableState.update { it.copy(addedToShelf = true, notice = "已加入书架") }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = "加入书架失败，请重试") }
            } finally {
                finishSourceAction()
            }
        }
    }
}

data class BookReadTarget(val sourceId: String, val chapterId: String?)
