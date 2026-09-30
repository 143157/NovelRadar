package com.novelradar.app.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelradar.app.domain.model.ReaderPreferences
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.repository.CatalogRepository
import com.novelradar.app.domain.repository.ChapterRepository
import com.novelradar.app.domain.repository.ReaderSettingsRepository
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ReaderViewModel(
    private val bookId: String,
    private val sourceId: String,
    private val initialChapterId: String?,
    private val catalog: CatalogRepository,
    private val chapters: ChapterRepository,
    private val settings: ReaderSettingsRepository,
    private val progress: ReadingProgressRepository,
    private val clock: () -> Long = System::currentTimeMillis,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = mutableState.asStateFlow()
    private val positions = MutableSharedFlow<ReadingLocation>(extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var retryChapterId: String? = null
    private var retryOffset = 0
    private var lastEventTime = Long.MIN_VALUE

    init {
        viewModelScope.launch {
            try { progress.collectPositionChanges(positions) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(notice = "阅读位置暂时无法自动保存，请返回前重试") } }
        }
        viewModelScope.launch {
            try { settings.preferences.collect(::applyPreferences) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { settingsUnavailable() }
        }
        loadJob = viewModelScope.launch { initialize() }
    }

    private suspend fun initialize() {
        try {
            try { applyPreferences(settings.preferences.first()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { settingsUnavailable() }
            val book = catalog.book(bookId)
            val names = catalog.sourceNames()
            val snapshot = catalog.catalog(bookId, sourceId)
            val saved = progress.progress(bookId).first()?.takeIf { it.sourceId == sourceId }
            lastEventTime = maxOf(lastEventTime, saved?.updatedAtEpochMillis ?: Long.MIN_VALUE)
            val ordered = snapshot.chapters.sortedBy { it.chapterIndex }
            val target = ordered.firstOrNull { it.chapterId == initialChapterId }
                ?: if (initialChapterId == null) ordered.firstOrNull { it.chapterId == saved?.chapterId } else null
            val chapter = target ?: ordered.firstOrNull()
            mutableState.update { it.copy(book = book, sourceName = names[sourceId] ?: sourceId, chapters = ordered) }
            if (chapter == null) {
                mutableState.update { it.copy(loading = false, error = snapshot.warning ?: SourceFailure.Unexpected("目录为空")) }
                return
            }
            val offset = if (initialChapterId == null && chapter.chapterId == saved?.chapterId) saved.offset else 0
            load(chapter.chapterId, offset)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(loading = false, error = SourceFailure.Unexpected("无法打开书籍")) } }
    }

    fun selectChapter(chapterId: String) {
        if (mutableState.value.chapters.none { it.chapterId == chapterId }) return
        loadJob?.cancel()
        prefetchJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!saveExact()) return@launch
            load(chapterId, 0)
        }
    }

    fun nextChapter() { mutableState.value.takeIf { it.canNext }?.let { selectChapter(it.chapters[it.catalogPosition + 1].chapterId) } }
    fun previousChapter() { mutableState.value.takeIf { it.canPrevious }?.let { selectChapter(it.chapters[it.catalogPosition - 1].chapterId) } }

    fun retry() {
        val target = retryChapterId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (target == null) initialize()
            else if (saveExact()) load(target, retryOffset)
        }
    }

    private suspend fun load(chapterId: String, offset: Int) {
        retryChapterId = chapterId
        retryOffset = offset
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            when (val result = chapters.loadChapter(bookId, sourceId, chapterId)) {
                is SourceResult.Failure -> mutableState.update { it.copy(loading = false, error = result.error) }
                is SourceResult.Success -> {
                    val loaded = result.value
                    // The retained chapter can still scroll while the next chapter is requested.
                    if (!saveExact()) {
                        mutableState.update { it.copy(loading = false) }
                        return
                    }
                    mutableState.update { it.copy(content = loaded, loading = false, error = null,
                        offset = offset.coerceIn(0, loaded.content.length), positionVersion = it.positionVersion + 1) }
                    saveExact()
                    val count = mutableState.value.preferences.prefetchCount.coerceIn(0, 2)
                    prefetchJob?.cancel()
                    if (count > 0) prefetchJob = viewModelScope.launch {
                        try { chapters.prefetchNext(bookId, sourceId, loaded.chapter.chapterIndex, count) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { /* Prefetch failure must not interrupt the current chapter. */ }
                    }
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(loading = false, error = SourceFailure.Unexpected("正文加载失败")) } }
    }

    fun onPositionChanged(offset: Int) {
        val current = mutableState.value
        val content = current.content ?: return
        val bounded = offset.coerceIn(0, content.content.length)
        if (bounded == current.offset) return
        mutableState.update { it.copy(offset = bounded) }
        positions.tryEmit(location(bounded))
    }

    fun onPositionChanged(chapterId: String, positionVersion: Int, offset: Int) {
        val current = mutableState.value
        if (current.content?.chapter?.chapterId != chapterId || current.positionVersion != positionVersion) return
        onPositionChanged(offset)
    }

    /** Callers await this before leaving; cancellation of the debounced collector does not flush it. */
    suspend fun saveExact(): Boolean {
        if (mutableState.value.content == null) return true
        return try {
            progress.saveExact(location(mutableState.value.offset))
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(notice = "阅读位置保存失败，请重试返回") }
            false
        }
    }

    fun saveOnStop() { viewModelScope.launch { withContext(NonCancellable) { saveExact() } } }

    suspend fun close(): Boolean {
        loadJob?.cancelAndJoin()
        prefetchJob?.cancelAndJoin()
        return saveExact()
    }

    fun updatePreferences(transform: (ReaderPreferences) -> ReaderPreferences) {
        viewModelScope.launch {
            try { settings.update(transform) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(notice = "设置保存失败，请重试") } }
        }
    }

    private fun location(offset: Int): ReadingLocation {
        val chapter = requireNotNull(mutableState.value.content).chapter
        lastEventTime = maxOf(clock(), if (lastEventTime == Long.MAX_VALUE) Long.MAX_VALUE else lastEventTime + 1)
        return ReadingLocation(bookId, sourceId, chapter.chapterId, chapter.chapterIndex, offset, lastEventTime)
    }

    private fun settingsUnavailable() {
        mutableState.update { it.copy(preferences = it.preferences.copy(prefetchCount = 0),
            notice = "阅读设置暂时无法读取，预读已关闭") }
    }

    private fun applyPreferences(preferences: ReaderPreferences) {
        val next = preferences.normalized()
        val previous = mutableState.value.preferences
        if (next.prefetchCount < previous.prefetchCount || (!next.allowMeteredPrefetch && previous.allowMeteredPrefetch)) {
            prefetchJob?.cancel()
        }
        val layoutChanged = next.fontSizeSp != previous.fontSizeSp || next.lineHeight != previous.lineHeight ||
            next.horizontalMarginDp != previous.horizontalMarginDp || next.mode != previous.mode
        mutableState.update { it.copy(preferences = next,
            positionVersion = if (layoutChanged) it.positionVersion + 1 else it.positionVersion) }
    }
}
