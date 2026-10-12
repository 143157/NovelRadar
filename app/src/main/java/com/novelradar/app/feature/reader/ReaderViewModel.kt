package com.novelradar.app.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelradar.app.domain.model.ReaderPreferences
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.repository.CatalogRepository
import com.novelradar.app.domain.repository.ChapterRepository
import com.novelradar.app.domain.repository.ReaderSettingsRepository
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.repository.ReadingProgressRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val onInitialChapterLoaded: () -> Unit = {},
) : ViewModel() {
    private val mutableState = MutableStateFlow(ReaderUiState())
    val uiState: StateFlow<ReaderUiState> = mutableState.asStateFlow()
    private val positions = MutableSharedFlow<ReadingLocation>(extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var positionJob: Job? = null
    private val saveMutex = Mutex()
    private val closeMutex = Mutex()
    private var closing = false
    private var closed = false
    private var retryChapterId: String? = null
    private var retryOffset = 0
    private var retryExplicitlySelected = false
    // Session-only protection of the existing progress row; never a second persistent bookmark.
    private var persistedLocation: ReadingLocation? = null
    private var pendingChoice = initialChapterId
    private var lastEventTime = Long.MIN_VALUE
    private var initialEntryPending = initialChapterId != null

    init {
        positionJob = startPositionCollection()
        viewModelScope.launch {
            try { settings.preferences.collect(::applyPreferences) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { settingsUnavailable() }
        }
        loadJob = viewModelScope.launch { initialize() }
    }

    private fun startPositionCollection(): Job = viewModelScope.launch {
        try { progress.collectPositionChanges(positions) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(notice = "阅读位置暂时无法自动保存，请返回前重试") } }
    }

    private suspend fun initialize() {
        try {
            try { applyPreferences(settings.preferences.first()) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { settingsUnavailable() }
            val book = catalog.book(bookId)
            val names = catalog.sourceNames()
            val snapshot = catalog.catalog(bookId, sourceId)
            val saved = progress.progress(bookId).first()
            val restore = saved?.takeIf { it.bookId == bookId && it.sourceId == sourceId }
            persistedLocation = saved
            lastEventTime = maxOf(lastEventTime, saved?.updatedAtEpochMillis ?: Long.MIN_VALUE)
            val ordered = snapshot.chapters.filter { it.bookId == bookId && it.sourceId == sourceId }.sortedBy { it.chapterIndex }
            val requested = pendingChoice
            val target = ordered.firstOrNull { it.chapterId == requested }
                ?: if (requested == null) ordered.firstOrNull { it.chapterId == restore?.chapterId } else null
            val chapter = target ?: ordered.firstOrNull()
            val recovery = mutableState.value.positionNeedsReview || saved?.let {
                it.bookId != bookId || it.sourceId != sourceId || it.contentFormatVersion != ChapterContentFormat.CURRENT ||
                    ordered.none { chapter -> chapter.chapterId == it.chapterId }
            } == true
            mutableState.update { it.copy(book = book, sourceName = names[sourceId] ?: sourceId, chapters = ordered,
                positionNeedsReview = recovery, originalReadingLocation = if (recovery) saved else null,
                originalChapterTitle = if (recovery && saved?.bookId == bookId && saved.sourceId == sourceId)
                    ordered.firstOrNull { chapter -> chapter.chapterId == saved.chapterId }?.title else null) }
            if (requested != null && target == null) {
                retryChapterId = requested
                retryOffset = 0
                retryExplicitlySelected = true
                mutableState.update { it.copy(loading = false, positionNeedsReview = true,
                    originalReadingLocation = saved, originalChapterTitle = originalTitle(),
                    error = SourceFailure.Unexpected("所选章节不在当前目录中，请选择章节或重试")) }
                return
            }
            if (chapter == null) {
                mutableState.update { it.copy(loading = false, error = snapshot.warning ?: SourceFailure.Unexpected("目录为空")) }
                return
            }
            val offset = if (requested == null && chapter.chapterId == restore?.chapterId) restore.offset else 0
            load(chapter.chapterId, offset, explicitlySelected = requested != null)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(loading = false, error = SourceFailure.Unexpected("无法打开书籍")) } }
    }

    fun selectChapter(chapterId: String) {
        if (closing || closed) return
        if (mutableState.value.chapters.none { it.chapterId == chapterId }) return
        pendingChoice = chapterId
        retryChapterId = chapterId
        retryOffset = 0
        retryExplicitlySelected = true
        loadJob?.cancel()
        prefetchJob?.cancel()
        loadJob = viewModelScope.launch {
            if (!saveExact()) return@launch
            load(chapterId, 0, explicitlySelected = true)
        }
    }

    fun nextChapter() { mutableState.value.takeIf { it.canNext }?.let { selectChapter(it.chapters[it.catalogPosition + 1].chapterId) } }
    fun previousChapter() { mutableState.value.takeIf { it.canPrevious }?.let { selectChapter(it.chapters[it.catalogPosition - 1].chapterId) } }

    fun retry() {
        if (closing || closed) return
        val target = retryChapterId
        val explicit = retryExplicitlySelected
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (target == null || mutableState.value.chapters.none { it.chapterId == target }) initialize()
            else if (saveExact()) {
                val offset = if (!explicit) persistedLocation?.takeIf {
                    it.bookId == bookId && it.sourceId == sourceId && it.chapterId == target
                }?.offset ?: retryOffset
                    else retryOffset
                load(target, offset, explicit)
            }
        }
    }

    suspend fun confirmPosition(): Boolean = withContext(NonCancellable) {
        saveMutex.withLock {
            if (closing || closed || mutableState.value.loading) return@withLock false
            persistExact(confirm = true)
        }
    }

    private suspend fun load(chapterId: String, offset: Int, explicitlySelected: Boolean) {
        retryChapterId = chapterId
        retryOffset = offset
        retryExplicitlySelected = explicitlySelected
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            when (val result = chapters.loadChapter(bookId, sourceId, chapterId)) {
                is SourceResult.Failure -> mutableState.update { it.copy(loading = false, error = result.error) }
                is SourceResult.Success -> {
                    val loaded = result.value
                    if (loaded.chapter.bookId != bookId || loaded.chapter.sourceId != sourceId ||
                        loaded.chapter.chapterId != chapterId) {
                        mutableState.update { it.copy(loading = false, error = SourceFailure.Unexpected("正文与所选章节不符，请重试")) }
                        return
                    }
                    // The retained chapter can still scroll while the next chapter is requested.
                    if (!saveExact()) {
                        mutableState.update { it.copy(loading = false) }
                        return
                    }
                    val review = needsPositionReview(persistedLocation, loaded, explicitlySelected)
                    val oldBodyOffset = !explicitlySelected && !loaded.boundaryVerified &&
                        persistedLocation?.let { it.bookId == bookId && it.sourceId == sourceId &&
                            it.chapterId == chapterId && it.contentFormatVersion == ChapterContentFormat.LEGACY &&
                            it.offset in 0..loaded.content.length } == true
                    val viewOffset = if (explicitlySelected || (review && !oldBodyOffset)) 0
                        else offset.coerceIn(0, loaded.content.length)
                    mutableState.update { it.copy(content = loaded, loading = false, error = null,
                        offset = viewOffset, positionVersion = it.positionVersion + 1,
                        positionNeedsReview = review || explicitlySelected,
                        originalReadingLocation = if (review || explicitlySelected) persistedLocation else null,
                        originalChapterTitle = if (review || explicitlySelected) originalTitle() else null) }
                    if (explicitlySelected && loaded.boundaryVerified) {
                        confirmPosition()
                    } else saveExact()
                    currentCoroutineContext().ensureActive()
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
        if (closing || closed) return
        val current = mutableState.value
        val content = current.content ?: return
        val bounded = offset.coerceIn(0, content.content.length)
        if (bounded == current.offset) return
        mutableState.update { it.copy(offset = bounded) }
        if (!current.positionNeedsReview) positions.tryEmit(location(bounded))
    }

    fun onPositionChanged(chapterId: String, positionVersion: Int, offset: Int) {
        val current = mutableState.value
        if (current.content?.chapter?.chapterId != chapterId || current.positionVersion != positionVersion) return
        onPositionChanged(offset)
    }

    /** Callers await this before leaving; cancellation of the debounced collector does not flush it. */
    suspend fun saveExact(): Boolean = saveMutex.withLock {
        if (closed) true else persistExact()
    }

    private suspend fun persistExact(confirm: Boolean = false): Boolean {
        val current = mutableState.value
        val content = current.content ?: return !confirm
        if (confirm && !content.boundaryVerified) return false
        if (!confirm && current.positionNeedsReview) return true
        return try {
            val saved = location(current.offset.coerceIn(0, content.content.length))
            progress.saveExact(saved)
            persistedLocation = saved
            if (confirm && mutableState.value.content === content) {
                mutableState.update { it.copy(positionNeedsReview = false, originalReadingLocation = null, originalChapterTitle = null) }
                // Movement while the exact confirmation was pending becomes eligible only after the gate opens.
                val visibleOffset = mutableState.value.offset
                if (!closing && !closed && visibleOffset != saved.offset) positions.tryEmit(location(visibleOffset))
                if (pendingChoice == content.chapter.chapterId) {
                    pendingChoice = null
                    retryExplicitlySelected = false
                    retryOffset = saved.offset
                    if (initialEntryPending) {
                        initialEntryPending = false
                        mutableState.update { it.copy(initialEntryCompleted = true) }
                        onInitialChapterLoaded()
                    }
                }
            }
            mutableState.update { if (it.notice == SAVE_FAILURE_NOTICE || it.notice == POSITION_CONFIRMATION_FAILURE_NOTICE)
                it.copy(notice = null) else it }
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            mutableState.update { it.copy(notice = if (confirm) POSITION_CONFIRMATION_FAILURE_NOTICE else SAVE_FAILURE_NOTICE) }
            false
        }
    }

    fun saveOnStop() {
        if (closing || closed) return
        // Enter the protected save before route disposal can clear the ViewModelStore.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { saveExact() }
        }
    }

    suspend fun close(): Boolean = closeMutex.withLock {
        if (closed) return@withLock true
        closing = true
        try {
            loadJob?.cancelAndJoin()
            prefetchJob?.cancelAndJoin()
            mutableState.update { it.copy(loading = false) }
            val saved = saveMutex.withLock {
                val result = persistExact()
                if (result) {
                    // Own cleanup even if rotation cancels the caller; do not seal the retained reader then.
                    withContext(NonCancellable) { positionJob?.cancelAndJoin() }
                    currentCoroutineContext().ensureActive()
                    closed = true
                }
                result
            }
            saved
        } finally {
            if (!closed) {
                closing = false
                if (positionJob?.isActive != true) positionJob = startPositionCollection()
            }
        }
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

    private fun originalTitle(): String? = mutableState.value.chapters
        .firstOrNull { persistedLocation?.bookId == bookId && persistedLocation?.sourceId == sourceId &&
            it.chapterId == persistedLocation?.chapterId }?.title

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

    private companion object {
        const val SAVE_FAILURE_NOTICE = "阅读位置保存失败，请重试返回"
    }
}
