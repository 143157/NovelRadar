package com.novelradar.app.feature.reader

import com.google.common.truth.Truth.assertThat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.ReaderPreferences
import com.novelradar.app.domain.model.ReaderTheme
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun loadsCachedChapterAndPrefetchesAtMostTwo() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
        assertThat(vm.uiState.value.content?.origin).isEqualTo(ChapterOrigin.Cached)
        assertThat(vm.uiState.value.loading).isFalse()
        assertThat(fixture.chapters.prefetches).containsExactly(0 to 2)
    }

    @Test fun restoresProgressOnlyForRequestedSourceAndClampsOffset() = runTest {
        val fixture = Fixture()
        fixture.progress.initial = location("c1", 1, 999)
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(vm.uiState.value.offset).isEqualTo(100)
        val other = Fixture().also { it.progress.initial = location("c1", 1, 55).copy(sourceId = "b") }
        val otherVm = other.create()
        advanceUntilIdle()
        assertThat(otherVm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
        assertThat(otherVm.uiState.value.offset).isEqualTo(0)
    }

    @Test fun explicitCatalogSelectionStartsAtChapterBeginning() = runTest {
        val fixture = Fixture()
        fixture.progress.initial = location("c1", 1, 55)
        val vm = fixture.create("c1")
        advanceUntilIdle()
        assertThat(vm.uiState.value.offset).isEqualTo(0)
    }

    @Test fun explicitEntryIsConsumedOnlyAfterContentArrives() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.chapters.gate = gate
        var consumed = 0
        val vm = fixture.create("c1") { consumed++ }
        runCurrent()
        assertThat(consumed).isEqualTo(0)
        assertThat(vm.uiState.value.content).isNull()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(consumed).isEqualTo(1)
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(consumed).isEqualTo(1)
    }

    @Test fun failedInitialEntryRemainsPendingUntilRetrySucceeds() = runTest {
        val fixture = Fixture()
        fixture.chapters.failed = "c1"
        var pending: String? = "c1"
        val vm = fixture.create(pending) { pending = null }
        advanceUntilIdle()
        assertThat(pending).isEqualTo("c1")
        fixture.chapters.failed = null
        vm.retry()
        advanceUntilIdle()
        assertThat(pending).isNull()
    }

    @Test fun recreationAfterExplicitEntryRestoresCurrentChapterAndOffset() = runTest {
        val fixture = Fixture()
        var pending: String? = "c0"
        val vm = fixture.create(pending) { pending = null }
        advanceUntilIdle()
        vm.nextChapter()
        advanceUntilIdle()
        vm.onPositionChanged(47)
        vm.saveOnStop()
        advanceUntilIdle()
        val recreated = fixture.create(pending) { pending = null }
        advanceUntilIdle()
        assertThat(recreated.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(recreated.uiState.value.offset).isEqualTo(47)
    }

    @Test fun recreationWhileInitialLoadPendingKeepsRequestedChapter() = runTest {
        val fixture = Fixture()
        fixture.progress.initial = location("c2", 2, 55)
        val gate = CompletableDeferred<Unit>()
        fixture.chapters.gate = gate
        var pending: String? = "c1"
        val owner = ViewModelStore()
        owner.put("reader", fixture.create(pending) { pending = null })
        runCurrent()
        owner.clear()
        val recreated = fixture.create(pending) { pending = null }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(recreated.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(recreated.uiState.value.offset).isEqualTo(0)
        assertThat(pending).isNull()
    }

    @Test fun retainedSessionKeepsPositionWhileStorageWriteIsPending() = runTest {
        val fixture = Fixture()
        val activityStore = ViewModelStore()
        val activityOwner = object : ViewModelStoreOwner { override val viewModelStore = activityStore }
        val sessionFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ReaderSessionViewModel() as T
            }
        }
        val session = ViewModelProvider(activityOwner, sessionFactory)["reader:1", ReaderSessionViewModel::class.java]
        val vm = fixture.create("c0")
        session.viewModelStore.put("reader", vm)
        advanceUntilIdle()
        vm.nextChapter()
        advanceUntilIdle()
        vm.onPositionChanged(47)
        val gate = CompletableDeferred<Unit>()
        fixture.progress.saveGate = gate
        vm.saveOnStop()
        runCurrent()
        // A new Activity wrapper uses the retained Activity store before disk catches up.
        val recreatedOwner = object : ViewModelStoreOwner { override val viewModelStore = activityStore }
        val recreatedSession = ViewModelProvider(recreatedOwner, sessionFactory)["reader:1", ReaderSessionViewModel::class.java]
        val restored = recreatedSession.viewModelStore["reader"] as ReaderViewModel
        assertThat(restored).isSameInstanceAs(vm)
        assertThat(restored.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(restored.uiState.value.offset).isEqualTo(47)
        assertThat(fixture.progress.initial?.offset).isEqualTo(0)
        gate.complete(Unit)
        advanceUntilIdle()
        activityStore.clear()
    }

    @Test fun clearingSessionOwnerCancelsPendingReaderLoad() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.chapters.gate = gate
        val session = ReaderSessionViewModel()
        val activityStore = ViewModelStore()
        activityStore.put("session", session)
        val vm = fixture.create("c1")
        session.viewModelStore.put("reader", vm)
        runCurrent()
        activityStore.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.uiState.value.content).isNull()
        assertThat(fixture.progress.exact).isEmpty()
    }

    @Test fun nextAndPreviousRespectCatalogBoundariesAndSaveBeforeChanging() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        vm.previousChapter()
        advanceUntilIdle()
        assertThat(fixture.chapters.loads).containsExactly("c0")
        vm.onPositionChanged(47)
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(fixture.progress.exact.last { it.chapterId == "c0" }.offset).isEqualTo(47)
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        vm.nextChapter()
        advanceUntilIdle()
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(fixture.chapters.loads).containsExactly("c0", "c1", "c2").inOrder()
        vm.previousChapter()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
    }

    @Test fun failurePreservesLastContentAndRetryLoadsRequestedChapterFromNetwork() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        fixture.chapters.failed = "c1"
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
        assertThat(vm.uiState.value.error).isEqualTo(SourceFailure.Timeout)
        fixture.chapters.failed = null
        fixture.chapters.origin = ChapterOrigin.Network
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(vm.uiState.value.content?.origin).isEqualTo(ChapterOrigin.Network)
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test fun preferenceChangesPersistAndPrefetchHonorsZeroOrOne() = runTest {
        val fixture = Fixture()
        fixture.settings.value.value = ReaderPreferences(prefetchCount = 0)
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(fixture.chapters.prefetches).isEmpty()
        vm.updatePreferences { it.copy(theme = ReaderTheme.NIGHT, prefetchCount = 1) }
        advanceUntilIdle()
        assertThat(vm.uiState.value.preferences.theme).isEqualTo(ReaderTheme.NIGHT)
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(fixture.chapters.prefetches).containsExactly(1 to 1)
    }

    @Test fun positionUpdatesAndExactSaveUseMonotonicallyIncreasingEventTimes() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(8)
        advanceUntilIdle()
        assertThat(vm.saveExact()).isTrue()
        vm.onPositionChanged(9)
        advanceUntilIdle()
        assertThat(fixture.progress.changes.last().offset).isEqualTo(9)
        assertThat(fixture.progress.changes.last().updatedAtEpochMillis)
            .isGreaterThan(fixture.progress.exact.last().updatedAtEpochMillis)
    }

    @Test fun saveFailureKeepsReaderOpenAndMakesProblemVisible() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        fixture.progress.failSave = true
        assertThat(vm.saveExact()).isFalse()
        assertThat(vm.uiState.value.notice).isNotNull()
    }

    @Test fun waitsForPersistedPreferencesBeforeFirstChapterPrefetch() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<ReaderPreferences>()
        val delayed = object : ReaderSettingsRepository {
            override val preferences = flow { emit(gate.await()) }
            override suspend fun update(transform: (ReaderPreferences) -> ReaderPreferences) = Unit
        }
        ReaderViewModel("book", "a", null, fixture.catalog, fixture.chapters, delayed, fixture.progress)
        runCurrent()
        assertThat(fixture.chapters.prefetches).isEmpty()
        gate.complete(ReaderPreferences(prefetchCount = 0))
        advanceUntilIdle()
        assertThat(fixture.chapters.prefetches).isEmpty()
    }

    @Test fun preferencesReadFailureKeepsDefaultsAndDoesNotCrashReader() = runTest {
        val fixture = Fixture()
        val broken = object : ReaderSettingsRepository {
            override val preferences = flow<ReaderPreferences> { error("disk unavailable") }
            override suspend fun update(transform: (ReaderPreferences) -> ReaderPreferences) = Unit
        }
        val vm = ReaderViewModel("book", "a", null, fixture.catalog, fixture.chapters, broken, fixture.progress)
        advanceUntilIdle()
        assertThat(vm.uiState.value.content).isNotNull()
        assertThat(vm.uiState.value.notice).isNotNull()
        assertThat(fixture.chapters.prefetches).isEmpty()
    }

    @Test fun ignoresStalePositionEventsFromPreviousChapterOrLayout() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        val oldVersion = vm.uiState.value.positionVersion
        vm.nextChapter()
        advanceUntilIdle()
        vm.onPositionChanged("c0", oldVersion, 72)
        vm.onPositionChanged("c1", oldVersion, 73)
        assertThat(vm.uiState.value.offset).isEqualTo(0)
        vm.onPositionChanged("c1", vm.uiState.value.positionVersion, 74)
        assertThat(vm.uiState.value.offset).isEqualTo(74)
    }

    @Test fun lifecycleStopWritesExactCurrentPosition() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(39)
        vm.saveOnStop()
        advanceUntilIdle()
        assertThat(fixture.progress.exact.last().offset).isEqualTo(39)
    }

    @Test fun lifecycleStopSavesEvenWhenOwnerIsClearedImmediately() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        val owner = ViewModelStore()
        owner.put("reader", vm)
        advanceUntilIdle()
        vm.onPositionChanged(39)
        vm.saveOnStop()
        owner.clear()
        advanceUntilIdle()
        assertThat(fixture.progress.exact.last().offset).isEqualTo(39)
    }

    @Test fun layoutPreferenceChangeRejectsPreviousLayoutPositionEvents() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(29)
        val previousLayout = vm.uiState.value.positionVersion
        vm.updatePreferences { it.copy(fontSizeSp = 28f) }
        advanceUntilIdle()
        vm.onPositionChanged("c0", previousLayout, 80)
        assertThat(vm.uiState.value.offset).isEqualTo(29)
    }

    @Test fun chapterArrivalSavesPositionMovedWhileLoading() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        fixture.chapters.gate = gate
        vm.nextChapter()
        runCurrent()
        vm.onPositionChanged(42)
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(fixture.progress.exact.last { it.chapterId == "c0" }.offset).isEqualTo(42)
    }

    @Test fun closeCancelsPendingChapterAndSavesLastVisibleContent() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        fixture.chapters.gate = gate
        vm.onPositionChanged(27)
        vm.nextChapter()
        runCurrent()
        assertThat(vm.close()).isTrue()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
        assertThat(fixture.progress.exact.last().offset).isEqualTo(27)
    }

    private class Fixture {
        val catalog = FakeCatalog()
        val chapters = FakeChapters(catalog.list)
        val settings = FakeSettings()
        val progress = FakeProgress()
        fun create(chapterId: String? = null, onInitialChapterLoaded: () -> Unit = {}) = ReaderViewModel("book", "a", chapterId,
            catalog, chapters, settings, progress, clock = { 100L }, onInitialChapterLoaded = onInitialChapterLoaded)
    }

    private class FakeCatalog : CatalogRepository {
        val list = (0..2).map { Chapter("book", "a", "c$it", it, "第${it + 1}章") }
        override suspend fun book(bookId: String) = Book("book", "x", "星河旅人", "叶子")
        override suspend fun sourceNames() = mapOf("a" to "书源甲")
        override fun sources(bookId: String): Flow<List<BookSource>> = MutableStateFlow(emptyList())
        override fun observeChapters(bookId: String, sourceId: String): Flow<List<Chapter>> = MutableStateFlow(list)
        override suspend fun catalog(bookId: String, sourceId: String, forceRefresh: Boolean) = CatalogResult(list, true)
    }

    private class FakeChapters(val catalog: List<Chapter>) : ChapterRepository {
        val loads = mutableListOf<String>()
        val prefetches = mutableListOf<Pair<Int, Int>>()
        var failed: String? = null
        var origin = ChapterOrigin.Cached
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun loadChapter(bookId: String, sourceId: String, chapterId: String): SourceResult<LoadedChapter> {
            loads += chapterId
            gate?.await()
            return if (failed == chapterId) SourceResult.Failure(SourceFailure.Timeout)
            else SourceResult.Success(LoadedChapter(catalog.first { it.chapterId == chapterId }, "正文".repeat(50), origin))
        }
        override suspend fun prefetchNext(bookId: String, sourceId: String, chapterIndex: Int, count: Int): Int {
            prefetches += chapterIndex to count
            return count
        }
    }

    private class FakeSettings : ReaderSettingsRepository {
        val value = MutableStateFlow(ReaderPreferences())
        override val preferences: Flow<ReaderPreferences> = value
        override suspend fun update(transform: (ReaderPreferences) -> ReaderPreferences) { value.value = transform(value.value).normalized() }
    }

    private class FakeProgress : ReadingProgressRepository {
        var initial: ReadingLocation? = null
        var failSave = false
        var saveGate: CompletableDeferred<Unit>? = null
        val exact = mutableListOf<ReadingLocation>()
        val changes = mutableListOf<ReadingLocation>()
        override fun progress(bookId: String): Flow<ReadingLocation?> = MutableStateFlow(initial)
        override suspend fun saveExact(location: ReadingLocation) {
            saveGate?.await()
            check(!failSave)
            exact += location
            initial = location
        }
        override suspend fun collectPositionChanges(changes: Flow<ReadingLocation>) { changes.collect { this.changes += it } }
    }

    class MainDispatcherRule : TestWatcher() {
        override fun starting(description: Description) { Dispatchers.setMain(StandardTestDispatcher()) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }

    private companion object {
        fun location(id: String, index: Int, offset: Int) = ReadingLocation("book", "a", id, index, offset, 50L)
    }
}
