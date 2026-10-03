package com.novelradar.app.feature.downloads

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.*
import com.novelradar.app.domain.repository.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadsViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun mapsAllStatesToMeaningfulCachedRowsAndActionsWithoutRefreshingCatalog() = runTest {
        val f = Fixture()
        f.downloads.tasks.value = DownloadState.entries.map { task(it, it.name) }
        val vm = f.create(); advanceUntilIdle()
        assertThat(vm.uiState.value.rows).hasSize(6)
        val running = vm.uiState.value.rows.first { it.state == DownloadState.RUNNING }
        assertThat(running.title).isEqualTo("星河旅人")
        assertThat(running.sourceName).isEqualTo("书源甲")
        assertThat(running.completed).isEqualTo(3)
        assertThat(running.total).isEqualTo(10)
        assertThat(running.percentage).isEqualTo(30)
        assertThat(running.failed).isEqualTo(2)
        assertThat(running.bytes).isEqualTo(4096L)
        assertThat(running.chapterTitle).isEqualTo("第四章")
        assertThat(running.bytesPerSecond).isEqualTo(512L)
        assertThat(running.actions).containsAtLeast(DownloadAction.PAUSE, DownloadAction.CANCEL)
        val rows = vm.uiState.value.rows.associateBy { it.state }
        assertThat(rows.getValue(DownloadState.QUEUED).actions).contains(DownloadAction.PAUSE)
        assertThat(rows.getValue(DownloadState.PAUSED).actions).contains(DownloadAction.CONTINUE)
        assertThat(rows.getValue(DownloadState.PARTIAL).actions).contains(DownloadAction.RETRY_FAILED)
        assertThat(rows.getValue(DownloadState.CANCELED).actions).contains(DownloadAction.CONTINUE)
        assertThat(rows.getValue(DownloadState.COMPLETED).actions).contains(DownloadAction.OPEN)
        assertThat(rows.getValue(DownloadState.COMPLETED).actions).doesNotContain(DownloadAction.CANCEL)
        assertThat(f.catalog.refreshes).isEqualTo(0)
    }

    @Test fun cachedMetadataUpdatesWithoutNewTaskEmission() = runTest {
        val f = Fixture(); val vm = f.create(); advanceUntilIdle()
        f.catalog.chapters.value = listOf(Chapter("book", "a", "c4", 3, "更新的章节名"))
        advanceUntilIdle()
        assertThat(vm.uiState.value.rows.single().chapterTitle).isEqualTo("更新的章节名")
    }

    @Test fun pauseContinueAndFailedOnlyRetryDelegateWithStoredPolicyUntouched() = runTest {
        val f = Fixture(); val vm = f.create(); advanceUntilIdle()
        vm.action("task", DownloadAction.PAUSE); advanceUntilIdle()
        assertThat(f.downloads.actions).contains("pause:task")
        f.downloads.tasks.value = listOf(task(DownloadState.PARTIAL)); advanceUntilIdle()
        vm.action("task", DownloadAction.RETRY_FAILED); advanceUntilIdle()
        vm.action("task", DownloadAction.CONTINUE); advanceUntilIdle()
        assertThat(f.downloads.actions).containsAtLeast("resume:task:true", "resume:task:false")
        assertThat(vm.uiState.value.rows.single().allowMetered).isFalse()
    }

    @Test fun canceledTaskCanBeExplicitlyContinued() = runTest {
        val f = Fixture(); f.downloads.tasks.value = listOf(task(DownloadState.CANCELED))
        val vm = f.create(); advanceUntilIdle(); vm.action("task", DownloadAction.CONTINUE); advanceUntilIdle()
        assertThat(f.downloads.actions).containsExactly("resume:task:false")
    }

    @Test fun cancelAndBookWideDeleteUseSeparateConfirmationsAndDelegateOnlyAfterConfirm() = runTest {
        val f = Fixture(); val vm = f.create(); advanceUntilIdle()
        vm.action("task", DownloadAction.CANCEL)
        assertThat(vm.uiState.value.confirmation?.action).isEqualTo(DownloadAction.CANCEL)
        assertThat(f.downloads.actions).isEmpty()
        vm.dismissConfirmation(); vm.confirmAction(); advanceUntilIdle()
        assertThat(f.downloads.actions).isEmpty()
        vm.action("task", DownloadAction.CANCEL); vm.confirmAction(); advanceUntilIdle()
        vm.action("task", DownloadAction.DELETE_FILES)
        assertThat(vm.uiState.value.confirmation?.action).isEqualTo(DownloadAction.DELETE_FILES)
        vm.confirmAction(); advanceUntilIdle()
        assertThat(f.downloads.actions).containsExactly("cancel:task", "delete:book").inOrder()
    }

    @Test fun staleCompletedRowCannotCancelAndMissingRowCannotDelete() = runTest {
        val f = Fixture(); val vm = f.create(); advanceUntilIdle()
        vm.action("task", DownloadAction.CANCEL)
        f.downloads.tasks.value = listOf(task(DownloadState.COMPLETED)); advanceUntilIdle()
        vm.confirmAction(); advanceUntilIdle()
        vm.action("task", DownloadAction.DELETE_FILES)
        f.downloads.tasks.value = emptyList(); advanceUntilIdle()
        vm.confirmAction(); advanceUntilIdle()
        assertThat(f.downloads.actions).isEmpty()
        assertThat(vm.uiState.value.notice).isNotNull()
    }

    @Test fun confirmationRevalidatesRepositoryStateBeforeItsListCollectorCatchesUp() = runTest {
        val f = Fixture(); val vm = f.create(); advanceUntilIdle()
        vm.action("task", DownloadAction.CANCEL)
        f.downloads.tasks.value = listOf(task(DownloadState.COMPLETED))
        // No scheduler advance: the on-screen row still reports RUNNING.
        vm.confirmAction(); advanceUntilIdle()
        assertThat(f.downloads.actions).isEmpty()
        assertThat(vm.uiState.value.notice).contains("任务状态已变化")
    }

    @Test fun slowActionDeduplicatesTapsUntilTheRepositoryFinishes() = runTest {
        val f = Fixture(); val vm = f.create(); advanceUntilIdle()
        val gate = CompletableDeferred<Unit>(); f.downloads.actionGate = gate
        vm.action("task", DownloadAction.PAUSE); vm.action("task", DownloadAction.PAUSE); runCurrent()
        assertThat(vm.uiState.value.busyTaskIds).contains("task")
        vm.action("task", DownloadAction.CANCEL)
        assertThat(vm.uiState.value.confirmation).isNull()
        gate.complete(Unit); advanceUntilIdle()
        assertThat(f.downloads.actions).containsExactly("pause:task")
        assertThat(vm.uiState.value.busyTaskIds).isEmpty()
    }

    @Test fun failedActionShowsSafeRecoverableNoticeAndCanRetry() = runTest {
        val f = Fixture(); val vm = f.create(); advanceUntilIdle()
        f.downloads.failAction = true
        vm.action("task", DownloadAction.PAUSE); advanceUntilIdle()
        assertThat(vm.uiState.value.notice).isEqualTo("暂停失败，请重试")
        assertThat(vm.uiState.value.busyTaskIds).isEmpty()
        f.downloads.failAction = false
        vm.action("task", DownloadAction.PAUSE); advanceUntilIdle()
        assertThat(f.downloads.actions).containsExactly("pause:task")
    }

    @Test fun listFailureCanRecoverWithoutShowingRawError() = runTest {
        val f = Fixture(); f.downloads.failList = true
        val vm = f.create(); advanceUntilIdle()
        assertThat(vm.uiState.value.listError).isEqualTo("下载列表读取失败，请重试")
        f.downloads.failList = false; vm.retryList(); advanceUntilIdle()
        assertThat(vm.uiState.value.listError).isNull()
        assertThat(vm.uiState.value.rows).hasSize(1)
    }

    @Test fun completedTaskOpensItsOwnSourceEvenWhenPreferredSourceIsDifferent() = runTest {
        val f = Fixture(); f.downloads.tasks.value = listOf(task(DownloadState.COMPLETED))
        f.books.preferred = "b"
        val vm = f.create(); val events = mutableListOf<DownloadsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }; advanceUntilIdle()
        vm.action("task", DownloadAction.OPEN); advanceUntilIdle()
        assertThat(events).containsExactly(DownloadsEvent.OpenReader(DownloadReadTarget("book", "a")))
        assertThat(f.books.preferred).isEqualTo("b")
    }

    @Test fun completedOpenRejectsRemovedOrForeignSource() = runTest {
        val f = Fixture(); f.downloads.tasks.value = listOf(task(DownloadState.COMPLETED))
        val vm = f.create(); val events = mutableListOf<DownloadsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }; advanceUntilIdle()
        f.books.sources.value = listOf(BookSource("other-book", "a", "https://example.org")); advanceUntilIdle()
        vm.action("task", DownloadAction.OPEN); advanceUntilIdle()
        assertThat(events).isEmpty()
        assertThat(vm.uiState.value.notice).isNotNull()
    }

    @Test fun explicitStartUsesConservativeDefaultAndChosenTitleSourceInConfirmation() = runTest {
        val f = Fixture(); val vm = f.create(); advanceUntilIdle()
        assertThat(vm.uiState.value.startRequest).isNull()
        vm.requestStart("book", "a"); advanceUntilIdle()
        assertThat(vm.uiState.value.startRequest).isEqualTo(DownloadStartRequest("book", "a", "星河旅人", "书源甲", false))
        assertThat(f.downloads.starts).isEmpty()
        vm.chooseStartNetwork(true); vm.confirmStart("book", "a", false); advanceUntilIdle()
        assertThat(f.downloads.starts).containsExactly(Triple("book", "a", true))
        assertThat(f.settings.state.value.allowMetered).isFalse()
        assertThat(vm.uiState.value.notice).contains("等待系统调度")
    }

    @Test fun changedOrPendingLiveSourceRejectsConfirmedStart() = runTest {
        val f = Fixture(); val vm = f.create(); advanceUntilIdle()
        vm.requestStart("book", "a"); advanceUntilIdle()
        vm.confirmStart("book", "b", false); advanceUntilIdle()
        vm.requestStart("book", "a"); advanceUntilIdle()
        vm.confirmStart(null, null, false); advanceUntilIdle()
        assertThat(f.downloads.starts).isEmpty()
    }

    @Test fun notificationPromptOccursOnlyAfterExplicitConfirmAndDeniedStillStartsExactlyOnce() = runTest {
        val f = Fixture(); val vm = f.create(); val events = mutableListOf<DownloadsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }; advanceUntilIdle()
        assertThat(events).isEmpty()
        vm.requestStart("book", "a"); vm.requestStart("book", "b"); advanceUntilIdle()
        vm.confirmStart("book", "a", true); vm.confirmStart("book", "a", true); advanceUntilIdle()
        assertThat(events).containsExactly(DownloadsEvent.RequestNotificationPermission)
        assertThat(f.downloads.starts).isEmpty()
        vm.requestStart("book", "b"); vm.notificationPermissionResult(false)
        vm.notificationPermissionResult(false); advanceUntilIdle()
        assertThat(f.downloads.starts).containsExactly(Triple("book", "a", false))
        assertThat(vm.uiState.value.notice).contains("通知可见性受限")
        assertThat(vm.uiState.value.startBusy).isFalse()
    }

    @Test fun permissionEventIsNotReplayedToRecreatedCollectorAndGrantedStartsChosenTarget() = runTest {
        val f = Fixture(); val vm = f.create(); val events = mutableListOf<DownloadsEvent>()
        var collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }; advanceUntilIdle()
        vm.requestStart("book", "a"); advanceUntilIdle(); vm.confirmStart("book", "a", true); advanceUntilIdle()
        assertThat(vm.claimNotificationPermissionRequest()).isTrue()
        collector.cancel(); collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }; advanceUntilIdle()
        assertThat(events).hasSize(1)
        vm.notificationPermissionResult(true); vm.notificationPermissionResult(true); advanceUntilIdle()
        assertThat(f.downloads.starts).containsExactly(Triple("book", "a", false))
        collector.cancel()
    }

    @Test fun permissionRequestSurvivesAbsentCollectorAndCanBeClaimedOnlyOnce() = runTest {
        val f = Fixture(); val vm = f.create(); advanceUntilIdle()
        vm.requestStart("book", "a"); advanceUntilIdle(); vm.confirmStart("book", "a", true); advanceUntilIdle()
        assertThat(vm.uiState.value.notificationPermissionRequestPending).isTrue()
        val events = mutableListOf<DownloadsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        runCurrent()
        assertThat(events).containsExactly(DownloadsEvent.RequestNotificationPermission)
        assertThat(vm.claimNotificationPermissionRequest()).isTrue()
        assertThat(vm.claimNotificationPermissionRequest()).isFalse()
        vm.requestStart("book", "b"); vm.notificationPermissionResult(false); advanceUntilIdle()
        assertThat(f.downloads.starts).containsExactly(Triple("book", "a", false))
        assertThat(vm.uiState.value.notificationPermissionRequestPending).isFalse()
    }

    @Test fun existingPausedTaskFeedbackDoesNotRescheduleOrChangeNetwork() = runTest {
        val f = Fixture(); f.downloads.result = DownloadStart.Existing("task")
        f.downloads.tasks.value = listOf(task(DownloadState.PAUSED))
        val vm = f.create(); advanceUntilIdle(); vm.requestStart("book", "a"); advanceUntilIdle()
        vm.chooseStartNetwork(true); vm.confirmStart("book", "a", false); advanceUntilIdle()
        assertThat(vm.uiState.value.notice).contains("已有任务")
        assertThat(vm.uiState.value.notice).contains("保留原网络设置")
        assertThat(f.downloads.actions).isEmpty()
        assertThat(vm.uiState.value.rows.single().allowMetered).isFalse()
    }

    @Test fun conflictIdentifiesExistingSourceAndDoesNotCancelOrSwitchIt() = runTest {
        val f = Fixture(); f.downloads.result = DownloadStart.Conflict("other", "b")
        val vm = f.create(); advanceUntilIdle(); vm.requestStart("book", "a"); advanceUntilIdle()
        vm.confirmStart("book", "a", false); advanceUntilIdle()
        assertThat(vm.uiState.value.notice).contains("书源乙")
        assertThat(vm.uiState.value.notice).contains("下载页")
        assertThat(f.downloads.actions).isEmpty()
    }

    @Test fun conflictFeedbackUsesKnownSourceNameEvenIfMetadataReadBecomesUnavailable() = runTest {
        val f = Fixture(); f.downloads.result = DownloadStart.Conflict("other", "b")
        val vm = f.create(); advanceUntilIdle(); vm.requestStart("book", "a"); advanceUntilIdle()
        f.downloads.beforeResult = { f.catalog.failNames = true }
        vm.confirmStart("book", "a", false); advanceUntilIdle()
        assertThat(vm.uiState.value.notice).contains("书源乙")
        assertThat(vm.uiState.value.notice).doesNotContain("创建下载失败")
        assertThat(f.downloads.actions).isEmpty()
    }

    @Test fun globalDefaultAppliesToNewConfirmationAndDoesNotMutateExistingTaskPolicy() = runTest {
        val f = Fixture(); val vm = f.create(); advanceUntilIdle()
        vm.setDefaultNetwork(true); advanceUntilIdle()
        vm.requestStart("book", "a"); advanceUntilIdle()
        assertThat(vm.uiState.value.startRequest?.allowMetered).isTrue()
        assertThat(vm.uiState.value.rows.single().allowMetered).isFalse()
        assertThat(f.downloads.actions).isEmpty()
    }

    @Test fun startWaitsForPersistedDefaultInsteadOfUsingTransientFirstUseValue() = runTest {
        val f = Fixture(); val gate = CompletableDeferred<Unit>()
        f.settings.state.value = DownloadPreferences(allowMetered = true)
        f.settings.beforeRead = gate
        val vm = f.create(); runCurrent()
        vm.requestStart("book", "a"); runCurrent()
        assertThat(vm.uiState.value.startRequest).isNull()
        assertThat(vm.uiState.value.startBusy).isTrue()
        gate.complete(Unit); advanceUntilIdle()
        assertThat(vm.uiState.value.startRequest?.allowMetered).isTrue()
    }

    @Test fun startValidatesBookSourceOwnershipAndReportsSafeFailure() = runTest {
        val f = Fixture(); val vm = f.create(); advanceUntilIdle()
        vm.requestStart("other-book", "a"); advanceUntilIdle()
        assertThat(vm.uiState.value.startRequest).isNull()
        vm.requestStart("book", "a"); advanceUntilIdle()
        f.downloads.failAction = true; vm.confirmStart("book", "a", false); advanceUntilIdle()
        assertThat(vm.uiState.value.notice).isEqualTo("创建下载失败，请重试")
        assertThat(vm.uiState.value.startBusy).isFalse()
    }

    @Test fun sourceRemovedAfterStartDialogIsRejectedBeforeNotificationPermissionPrompt() = runTest {
        val f = Fixture(); val vm = f.create(); val events = mutableListOf<DownloadsEvent>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.events.collect { events += it } }
        advanceUntilIdle(); vm.requestStart("book", "a"); advanceUntilIdle()
        f.books.sources.value = emptyList()
        vm.confirmStart("book", "a", true); advanceUntilIdle()
        assertThat(events).isEmpty()
        assertThat(f.downloads.starts).isEmpty()
        assertThat(vm.uiState.value.startBusy).isFalse()
        assertThat(vm.uiState.value.notice).isNotNull()
    }

    @Test fun networkDefaultReadFailureCanRetryAndSaveFailureKeepsStoredDefault() = runTest {
        val f = Fixture(); f.settings.failRead = true
        val vm = f.create(); advanceUntilIdle()
        assertThat(vm.uiState.value.settingsReady).isFalse()
        assertThat(vm.uiState.value.settingsError).isNotNull()
        f.settings.failRead = false; vm.retryList(); advanceUntilIdle()
        assertThat(vm.uiState.value.settingsReady).isTrue()
        assertThat(vm.uiState.value.settingsError).isNull()
        f.settings.failSave = true; vm.setDefaultNetwork(true); advanceUntilIdle()
        assertThat(vm.uiState.value.defaultAllowMetered).isFalse()
        assertThat(vm.uiState.value.notice).isEqualTo("下载网络设置保存失败，请重试")
    }

    private class Fixture {
        val downloads = FakeDownloads(); val books = FakeBooks(); val catalog = FakeCatalog(); val settings = FakeSettings()
        fun create() = DownloadsViewModel(downloads, books, catalog, settings)
    }
    private class FakeDownloads : DownloadRepository {
        val tasks = MutableStateFlow(listOf(task(DownloadState.RUNNING)))
        val actions = mutableListOf<String>(); val starts = mutableListOf<Triple<String, String, Boolean>>()
        var result: DownloadStart = DownloadStart.Started("new"); var failAction = false; var failList = false
        var actionGate: CompletableDeferred<Unit>? = null
        var beforeResult: () -> Unit = {}
        override fun observeAll(): Flow<List<DownloadTask>> = if (failList) flow { error("https://bad/script.js raw body") } else tasks
        override suspend fun start(bookId: String, sourceId: String, allowMetered: Boolean): DownloadStart {
            checkFailure(); starts += Triple(bookId, sourceId, allowMetered); beforeResult(); return result
        }
        private fun checkFailure() { if (failAction) error("https://bad/script.js raw body") }
        override suspend fun pause(taskId: String) { actionGate?.await(); checkFailure(); actions += "pause:$taskId" }
        override suspend fun resume(taskId: String, failedOnly: Boolean) { checkFailure(); actions += "resume:$taskId:$failedOnly" }
        override suspend fun cancel(taskId: String) { checkFailure(); actions += "cancel:$taskId" }
        override suspend fun deleteLocal(bookId: String) { checkFailure(); actions += "delete:$bookId" }
        override suspend fun reconcile() = Unit
    }
    private class FakeBooks : BookRepository {
        var preferred = "a"
        val sources = MutableStateFlow(listOf(BookSource("book", "a", "https://example.org/a"), BookSource("book", "b", "https://example.org/b")))
        override suspend fun getBook(bookId: String) = if (bookId == "book") Book("book", "star", "星河旅人", "叶子") else null
        override suspend fun saveSearchHit(hit: SearchHit) = error("unused")
        override fun observeSources(bookId: String) = sources
        override fun observeShelf(): Flow<List<ShelfBook>> = flowOf(emptyList())
        override suspend fun addToShelf(bookId: String, atEpochMillis: Long) = Unit
        override suspend fun removeFromShelf(bookId: String) = Unit
        override fun progress(bookId: String): Flow<ReadingLocation?> = flowOf(null)
        override suspend fun saveProgress(location: ReadingLocation) = Unit
        override suspend fun preferredSource(bookId: String) = preferred
    }
    private class FakeCatalog : CatalogRepository {
        val chapters = MutableStateFlow(listOf(Chapter("book", "a", "c4", 3, "第四章")))
        var refreshes = 0
        var failNames = false
        override suspend fun book(bookId: String): Book? = null
        override suspend fun sourceNames(): Map<String, String> {
            if (failNames) error("raw metadata error")
            return mapOf("a" to "书源甲", "b" to "书源乙")
        }
        override fun sources(bookId: String): Flow<List<BookSource>> = flowOf(emptyList())
        override fun observeChapters(bookId: String, sourceId: String) = chapters
        override suspend fun catalog(bookId: String, sourceId: String, forceRefresh: Boolean): CatalogResult { refreshes++; error("must not refresh") }
    }
    private class FakeSettings : DownloadSettingsRepository {
        val state = MutableStateFlow(DownloadPreferences())
        var failRead = false; var failSave = false
        var beforeRead: CompletableDeferred<Unit>? = null
        override val preferences: Flow<DownloadPreferences> get() = flow {
            beforeRead?.await()
            if (failRead) error("raw preference error")
            emitAll(state)
        }
        override suspend fun setAllowMetered(allowMetered: Boolean) {
            if (failSave) error("raw preference error")
            state.value = DownloadPreferences(allowMetered)
        }
    }
    class MainDispatcherRule : TestWatcher() {
        private val dispatcher = StandardTestDispatcher()
        override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }
    companion object {
        private fun task(state: DownloadState, id: String = "task") = DownloadTask(id, "book", "a", state, 10, 3, 2, false, 0, setOf("c2", "c3"), 4096, "c4", 512, null)
    }
}
