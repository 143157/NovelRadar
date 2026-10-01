package com.novelradar.app.feature.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.ShelfBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class LibraryViewModel(
    private val books: BookRepository,
    private val refresh: suspend (List<ShelfBook>) -> Map<String, SourceFailure>,
    private val deleteLocal: suspend (String) -> Unit,
) : ViewModel() {
    private val state = MutableStateFlow(LibraryUiState())
    val uiState = state.asStateFlow()
    private val opens = Channel<LibraryOpen>(Channel.BUFFERED)
    val navigation = opens.receiveAsFlow()

    init {
        viewModelScope.launch {
            try {
                books.observeShelf().collect { shelf ->
                    val ordered = shelf.sortedWith(compareByDescending<ShelfBook> { it.lastReadAtEpochMillis ?: Long.MIN_VALUE }
                        .thenByDescending { it.addedAtEpochMillis }.thenBy { it.book.bookId })
                    state.update { it.copy(books = ordered.map(::LibraryBook), loading = false) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(loading = false, notice = "书架读取失败，请重试") } }
        }
    }

    fun refresh() {
        if (state.value.refreshing) return
        val snapshot = state.value.books.map { it.shelf }
        state.update { it.copy(refreshing = true, refreshWarnings = emptyMap(), notice = null) }
        viewModelScope.launch {
            try {
                val warnings = refresh(snapshot)
                state.update { it.copy(refreshing = false, refreshWarnings = warnings) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(refreshing = false, notice = "刷新失败，保留上次目录") } }
        }
    }

    fun open(bookId: String) {
        viewModelScope.launch {
            try {
                val entry = state.value.books.firstOrNull { it.shelf.book.bookId == bookId }?.shelf ?: return@launch
                val sources = books.observeSources(bookId).first().map { it.sourceId }
                val latest = books.progress(bookId).first()
                val source = books.preferredSource(bookId)?.takeIf { it in sources }
                    ?: entry.preferredSourceId?.takeIf { it in sources }
                    ?: latest?.sourceId?.takeIf { it in sources }
                    ?: sources.firstOrNull()
                if (source == null) state.update { it.copy(notice = "这本书没有可用书源，请到详情选择书源") }
                else opens.send(LibraryOpen(bookId, source))
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(notice = "打开书籍失败，请重试") } }
        }
    }

    fun requestRemoval(bookId: String) {
        if (!state.value.removing) state.update { current -> current.copy(
            pendingRemoval = current.books.firstOrNull { it.shelf.book.bookId == bookId }, notice = null) }
    }
    fun cancelRemoval() { if (!state.value.removing) state.update { it.copy(pendingRemoval = null) } }
    fun confirmRemoval(deleteLocal: Boolean) {
        val entry = state.value.pendingRemoval ?: return
        if (state.value.removing) return
        state.update { it.copy(removing = true) }
        viewModelScope.launch {
            try {
                val bookId = entry.shelf.book.bookId
                if (deleteLocal) deleteLocal(bookId)
                books.removeFromShelf(bookId)
                state.update { it.copy(pendingRemoval = null, removing = false,
                    notice = if (deleteLocal) "已移出书架并删除本地章节" else "已移出书架，本地章节已保留") }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { state.update { it.copy(removing = false, notice = "移除失败，请重试") } }
        }
    }
}
