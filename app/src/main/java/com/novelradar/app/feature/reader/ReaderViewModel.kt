package com.novelradar.app.feature.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelradar.app.domain.model.ReaderPreferences
import com.novelradar.app.domain.model.ReaderMode
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.repository.CatalogRepository
import com.novelradar.app.domain.repository.ChapterRepository
import com.novelradar.app.domain.repository.LoadedChapter
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
    private val preparations = mutableMapOf<String, Preparation>()
    private var continuationJob: Job? = null
    private var continuationGeneration = 0
    private var loadGeneration = 0
    private var deliberateTarget: String? = null
    private var continuationPaged = false
    private var continuationDirection = 1
    private var indexedBody: LoadedChapter? = null
    private var characterIndex: ChapterCharacterIndex? = null
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
    private var lastExactEventTime = Long.MIN_VALUE
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
            val ordered = snapshot.chapters.filter { it.bookId == bookId && it.sourceId == sourceId }
                .sortedWith(compareBy(Chapter::chapterIndex, Chapter::chapterId))
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
        cancelContinuation()
        loadJob?.cancel()
        val generation = ++loadGeneration
        deliberateTarget = chapterId
        reconcilePreparations()
        loadJob = viewModelScope.launch {
            if (!saveExact()) return@launch
            currentCoroutineContext().ensureActive()
            load(chapterId, 0, explicitlySelected = true, generation = generation)
        }
    }

    fun nextChapter() { mutableState.value.takeIf { it.canNext }?.let { selectChapter(it.chapters[it.catalogPosition + 1].chapterId) } }
    fun previousChapter() { mutableState.value.takeIf { it.canPrevious }?.let { selectChapter(it.chapters[it.catalogPosition - 1].chapterId) } }

    fun retry() {
        if (closing || closed) return
        val continuation = mutableState.value
        if (continuation.continuationError != null && continuation.continuationTargetId != null) {
            requestContinuation(continuation.continuationTargetId, continuationPaged, continuationDirection)
            return
        }
        val target = retryChapterId
        val explicit = retryExplicitlySelected
        loadJob?.cancel()
        val generation = ++loadGeneration
        deliberateTarget = target
        reconcilePreparations()
        loadJob = viewModelScope.launch {
            if (target == null || mutableState.value.chapters.none { it.chapterId == target }) initialize()
            else if (saveExact()) {
                val offset = if (!explicit) persistedLocation?.takeIf {
                    it.bookId == bookId && it.sourceId == sourceId && it.chapterId == target
                }?.offset ?: retryOffset
                    else retryOffset
                currentCoroutineContext().ensureActive()
                load(target, offset, explicit, generation)
            }
        }
    }

    suspend fun confirmPosition(): Boolean = withContext(NonCancellable) {
        saveMutex.withLock {
            if (closing || closed || mutableState.value.loading) return@withLock false
            persistExact(confirm = true)
        }
    }

    private suspend fun load(chapterId: String, offset: Int, explicitlySelected: Boolean, generation: Int = loadGeneration) {
        retryChapterId = chapterId
        retryOffset = offset
        retryExplicitlySelected = explicitlySelected
        mutableState.update { it.copy(loading = true, error = null) }
        try {
            val result = chapters.loadChapter(bookId, sourceId, chapterId)
            currentCoroutineContext().ensureActive()
            if (generation != loadGeneration || closing || closed) return
            when (result) {
                is SourceResult.Failure -> mutableState.update { it.copy(loading = false, error = result.error) }
                is SourceResult.Success -> {
                    currentCoroutineContext().ensureActive()
                    if (generation != loadGeneration || closing || closed) return
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
                    currentCoroutineContext().ensureActive()
                    if (generation != loadGeneration || closing || closed) return
                    val review = needsPositionReview(persistedLocation, loaded, explicitlySelected)
                    val oldBodyOffset = !explicitlySelected && !loaded.boundaryVerified &&
                        persistedLocation?.let { it.bookId == bookId && it.sourceId == sourceId &&
                            it.chapterId == chapterId && it.contentFormatVersion == ChapterContentFormat.LEGACY &&
                            it.offset in 0..loaded.content.length } == true
                    val viewOffset = if (explicitlySelected || (review && !oldBodyOffset)) 0
                        else offset.coerceIn(0, loaded.content.length)
                    mutableState.update { bodyState(it.copy(content = loaded, loading = false, error = null,
                        offset = viewOffset, positionVersion = it.positionVersion + 1,
                        restorationVersion = it.restorationVersion + 1,
                        positionNeedsReview = review || explicitlySelected,
                        originalReadingLocation = if (review || explicitlySelected) persistedLocation else null,
                        originalChapterTitle = if (review || explicitlySelected) originalTitle() else null), loaded) }
                    if (explicitlySelected && loaded.boundaryVerified) {
                        confirmPosition()
                    } else saveExact()
                    currentCoroutineContext().ensureActive()
                    deliberateTarget = null
                    reconcilePreparations(retryFailures = true)
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
        mutableState.update { it.copy(offset = bounded, characterProgress = characterProgress(content, bounded, it.positionNeedsReview)) }
        if (!current.positionNeedsReview) positions.tryEmit(location(content, bounded))
    }

    /** An epoch identifies callbacks; a visible crossing never requests viewport restoration. */
    fun onVisiblePosition(positionVersion: Int, position: ChapterReadingPosition) {
        if (closing || closed) return
        val current = mutableState.value
        if (current.positionVersion != positionVersion) return
        if (position.chapterId == current.content?.chapter?.chapterId) {
            onPositionChanged(position.offset)
            return
        }
        if (current.positionNeedsReview) return
        val body = current.verifiedWindow.firstOrNull { it.chapter.chapterId == position.chapterId } ?: return
        if (!trusted(body, position.chapterId)) return
        activateVisible(body, position.offset, restore = false)
    }

    fun continueForward(positionVersion: Int) = continueInDirection(positionVersion, 1)

    fun continueBackward(positionVersion: Int) = continueInDirection(positionVersion, -1)

    private fun continueInDirection(positionVersion: Int, direction: Int) {
        if (closing || closed) return
        val current = mutableState.value
        if (current.positionVersion != positionVersion || current.positionNeedsReview || current.loading) return
        if (current.catalogPosition < 0) return
        val target = current.chapters.getOrNull(current.catalogPosition + direction) ?: return
        requestContinuation(target.chapterId, current.preferences.mode == ReaderMode.PAGED, direction)
    }

    private fun requestContinuation(chapterId: String, paged: Boolean, direction: Int = 1) {
        val current = mutableState.value
        if (closing || closed || current.positionNeedsReview) return
        if (current.chapters.none { it.chapterId == chapterId }) return
        if (current.continuationLoading && current.continuationTargetId == chapterId) return
        val ready = current.verifiedWindow.firstOrNull { it.chapter.chapterId == chapterId }
        if (ready != null) {
            if (paged) activateVisible(ready, if (direction < 0) ready.content.length else 0, restore = true)
            else cancelContinuation()
            return
        }
        cancelContinuation()
        continuationPaged = paged
        continuationDirection = direction
        val generation = continuationGeneration
        val origin = current.content
        val epoch = current.positionVersion
        mutableState.update { it.copy(continuationTargetId = chapterId, continuationLoading = true, continuationError = null) }
        continuationJob = viewModelScope.launch {
            try {
                val result = chapters.loadChapter(bookId, sourceId, chapterId)
                currentCoroutineContext().ensureActive()
                if (generation != continuationGeneration || closing || closed || mutableState.value.content !== origin ||
                    mutableState.value.positionVersion != epoch || mutableState.value.positionNeedsReview) return@launch
                when (result) {
                    is SourceResult.Failure -> mutableState.update { it.copy(continuationLoading = false, continuationError = result.error) }
                    is SourceResult.Success -> {
                        if (!trusted(result.value, chapterId)) {
                            mutableState.update { it.copy(continuationLoading = false,
                                continuationError = SourceFailure.Unexpected("续读正文与当前书籍章节不符，请重试")) }
                        } else {
                            mutableState.update { it.copy(verifiedWindow = boundedWindow(it, result.value),
                                continuationLoading = false, continuationError = null) }
                            if (paged) activateVisible(result.value, if (direction < 0) result.value.content.length else 0, restore = true)
                        }
                    }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                if (generation == continuationGeneration && !closing && !closed) mutableState.update {
                    it.copy(continuationLoading = false, continuationError = SourceFailure.Unexpected("续读正文加载失败，请重试")) }
            }
        }
    }

    private fun activateVisible(body: LoadedChapter, offset: Int, restore: Boolean) {
        cancelContinuation()
        val bounded = offset.coerceIn(0, body.content.length)
        if (deliberateTarget == null) {
            retryChapterId = body.chapter.chapterId
            retryOffset = bounded
            retryExplicitlySelected = false
        }
        mutableState.update { bodyState(it.copy(content = body, offset = bounded,
            positionVersion = it.positionVersion + 1,
            restorationVersion = if (restore) it.restorationVersion + 1 else it.restorationVersion), body) }
        // Capture the chapter and offset before suspension; an older queued save cannot follow a newer exact save.
        val captured = location(body, bounded)
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) { saveMutex.withLock { persistCaptured(captured) } }
        }
        reconcilePreparations(retryFailures = true)
    }

    private fun cancelContinuation() {
        continuationGeneration++
        continuationJob?.cancel()
        continuationJob = null
        mutableState.update { it.copy(continuationTargetId = null, continuationLoading = false, continuationError = null) }
    }

    private fun trusted(body: LoadedChapter, chapterId: String = body.chapter.chapterId): Boolean =
        body.boundaryVerified && body.chapter.bookId == bookId && body.chapter.sourceId == sourceId &&
            body.chapter.chapterId == chapterId && mutableState.value.chapters.any { it.chapterId == chapterId }

    private fun boundedWindow(state: ReaderUiState, incoming: LoadedChapter? = null): List<LoadedChapter> {
        val active = state.content?.chapter?.chapterId ?: return emptyList()
        val allowed = readingWindowIds(state.chapters, active)
        val bodies = (state.verifiedWindow + listOfNotNull(incoming)).filter { trusted(it) && it.chapter.chapterId in allowed &&
            (it.chapter.chapterId != active || state.content.boundaryVerified) }
            .associateBy { it.chapter.chapterId }
        return state.chapters.mapNotNull { bodies[it.chapterId] }
    }

    private fun bodyState(state: ReaderUiState, incoming: LoadedChapter? = null): ReaderUiState = state.copy(
        verifiedWindow = boundedWindow(state, incoming),
        characterProgress = state.content?.let { characterProgress(it, state.offset, state.positionNeedsReview) })

    private fun characterProgress(body: LoadedChapter, offset: Int, review: Boolean): ChapterCharacterProgress? {
        if (review || !body.boundaryVerified) {
            indexedBody = null
            characterIndex = null
            return null
        }
        if (indexedBody !== body) {
            indexedBody = body
            characterIndex = ChapterCharacterIndex(body.content)
        }
        return characterIndex?.progress(offset)
    }

    /** Completed attempts remain keyed while relevant, so failure/completion cannot create a refetch loop. */
    private fun reconcilePreparations(retryFailures: Boolean = false) {
        if (closing || closed) return
        val state = mutableState.value
        val active = state.catalogPosition
        val count = state.preferences.prefetchCount.coerceIn(0, 2)
        val target = deliberateTarget?.let { id -> state.chapters.indexOfFirst { it.chapterId == id } }
            ?.takeIf { it >= 0 }
        val desired = linkedSetOf<String>()
        if (target != null && target != active) {
            // A distant target is not visible yet. Keep relevant existing jobs but defer new successors
            // until activation, when their bodies can actually fit inside the bounded window.
            val relevant = state.chapters.drop(target).take(count + 1).map { it.chapterId }.toSet()
            preparations.keys.filter { it in relevant }.take(count).forEach { desired += it }
        } else if (active >= 0) {
            state.chapters.drop(active + 1).take(count).forEach { desired += it.chapterId }
        }
        if (count == 0) desired.clear()
        preparations.keys.toList().filter { it !in desired }.forEach { preparations.remove(it)?.job?.cancel() }
        desired.forEach { id ->
            if (retryFailures && preparations[id]?.failed == true) preparations.remove(id)
            if (state.verifiedWindow.any { it.chapter.chapterId == id } || preparations.containsKey(id)) return@forEach
            val preparation = Preparation()
            preparations[id] = preparation
            preparation.job = viewModelScope.launch {
                try {
                    val result = chapters.prepareChapter(bookId, sourceId, id)
                    currentCoroutineContext().ensureActive()
                    if (preparations[id] !== preparation || closing || closed) return@launch
                    if (result is SourceResult.Success && trusted(result.value, id)) {
                        mutableState.update { it.copy(verifiedWindow = boundedWindow(it, result.value)) }
                    } else preparation.failed = true
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { preparation.failed = true }
            }
        }
    }

    private class Preparation { var job: Job? = null; var failed = false }

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
            val saved = location(content, current.offset.coerceIn(0, content.content.length))
            progress.saveExact(saved)
            persistedLocation = saved
            lastExactEventTime = maxOf(lastExactEventTime, saved.updatedAtEpochMillis)
            if (confirm && mutableState.value.content === content) {
                mutableState.update { bodyState(it.copy(positionNeedsReview = false, originalReadingLocation = null, originalChapterTitle = null)) }
                // Movement while the exact confirmation was pending becomes eligible only after the gate opens.
                val visibleOffset = mutableState.value.offset
                if (!closing && !closed && visibleOffset != saved.offset) positions.tryEmit(location(content, visibleOffset))
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

    private suspend fun persistCaptured(captured: ReadingLocation): Boolean {
        if (closed || captured.updatedAtEpochMillis <= lastExactEventTime) return true
        return try {
            progress.saveExact(captured)
            persistedLocation = captured
            lastExactEventTime = maxOf(lastExactEventTime, captured.updatedAtEpochMillis)
            mutableState.update { if (it.notice == SAVE_FAILURE_NOTICE) it.copy(notice = null) else it }
            val current = mutableState.value
            val body = current.content
            if (!closing && !closed && !current.positionNeedsReview && body?.chapter?.chapterId == captured.chapterId &&
                current.offset != captured.offset) positions.tryEmit(location(body, current.offset))
            true
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { mutableState.update { it.copy(notice = SAVE_FAILURE_NOTICE) }; false }
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
            loadGeneration++
            continuationGeneration++
            loadJob?.cancelAndJoin()
            deliberateTarget = null
            continuationJob?.cancelAndJoin()
            val pendingPreparations = preparations.values.mapNotNull { it.job }
            preparations.clear()
            pendingPreparations.forEach { it.cancel() }
            pendingPreparations.forEach { it.join() }
            cancelContinuation()
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
                reconcilePreparations(retryFailures = true)
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

    private fun location(content: LoadedChapter, offset: Int): ReadingLocation {
        val chapter = content.chapter
        lastEventTime = maxOf(clock(), if (lastEventTime == Long.MAX_VALUE) Long.MAX_VALUE else lastEventTime + 1)
        return ReadingLocation(bookId, sourceId, chapter.chapterId, chapter.chapterIndex, offset, lastEventTime)
    }

    private fun originalTitle(): String? = mutableState.value.chapters
        .firstOrNull { persistedLocation?.bookId == bookId && persistedLocation?.sourceId == sourceId &&
            it.chapterId == persistedLocation?.chapterId }?.title

    private fun settingsUnavailable() {
        mutableState.update { it.copy(preferences = it.preferences.copy(prefetchCount = 0),
            notice = "阅读设置暂时无法读取，预读已关闭") }
        reconcilePreparations()
    }

    private fun applyPreferences(preferences: ReaderPreferences) {
        val next = preferences.normalized()
        val previous = mutableState.value.preferences
        if (!next.allowMeteredPrefetch && previous.allowMeteredPrefetch) {
            preparations.values.forEach { it.job?.cancel() }
            preparations.clear()
        }
        val layoutChanged = next.fontSizeSp != previous.fontSizeSp || next.lineHeight != previous.lineHeight ||
            next.horizontalMarginDp != previous.horizontalMarginDp || next.mode != previous.mode
        mutableState.update { it.copy(preferences = next,
            positionVersion = if (layoutChanged) it.positionVersion + 1 else it.positionVersion,
            restorationVersion = if (layoutChanged) it.restorationVersion + 1 else it.restorationVersion) }
        if (layoutChanged) cancelContinuation()
        reconcilePreparations(retryFailures = next.prefetchCount > previous.prefetchCount ||
            (next.allowMeteredPrefetch && !previous.allowMeteredPrefetch))
    }

    private companion object {
        const val SAVE_FAILURE_NOTICE = "阅读位置保存失败，请重试返回"
    }
}
