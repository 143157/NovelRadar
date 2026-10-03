package com.novelradar.app.data.download

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.entity.DownloadTaskEntity
import com.novelradar.app.data.files.AtomicChapterFileStore
import com.novelradar.app.data.files.LocalChapterCleaner
import com.novelradar.app.domain.repository.DownloadStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DownloadCoordinatorTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun pauseDoesNotDowngradeWorkerCompletionAtTheTransactionalCommitBoundary() = runTest {
        val f = Fixture(); val running = progressTask("RUNNING")
        f.store.save(running)
        val completed = completedTask(running)
        f.store.beforeMutateLatest = { assertThat(f.store.checkpoint(completed)).isTrue() }

        f.coordinator.pause(running.taskId)

        assertThat(f.store.get(running.taskId)).isEqualTo(completed)
        assertThat(f.scheduler.canceledIds).isEmpty()
        assertThat(f.coordinator.start("book", "another-source")).isInstanceOf(DownloadStart.Started::class.java)
    }

    @Test fun cancelDoesNotDowngradeWorkerCompletionAtTheTransactionalCommitBoundary() = runTest {
        val f = Fixture(); val running = progressTask("RUNNING")
        f.store.save(running)
        val completed = completedTask(running)
        f.store.beforeMutateLatest = { assertThat(f.store.checkpoint(completed)).isTrue() }

        f.coordinator.cancel(running.taskId)

        assertThat(f.store.get(running.taskId)).isEqualTo(completed)
        assertThat(f.scheduler.canceledIds).isEmpty()
        assertThat(f.coordinator.start("book", "another-source")).isInstanceOf(DownloadStart.Started::class.java)
    }

    @Test fun pauseIsAnExactNoOpForPausedPartialCompletedCanceledAndUnknownRows() = runTest {
        for (state in listOf("PAUSED", "PARTIAL", "COMPLETED", "CANCELED", "UNKNOWN")) {
            val f = Fixture(); val saved = progressTask(state)
            f.store.save(saved)
            f.coordinator.pause(saved.taskId)
            // Equality checks every field, including generation, work owner, counters and metadata.
            assertThat(f.store.get(saved.taskId)).isEqualTo(saved)
            assertThat(f.scheduler.canceledIds).isEmpty()
        }
    }

    @Test fun cancelIsAnExactNoOpForCompletedCanceledAndUnknownRows() = runTest {
        for (state in listOf("COMPLETED", "CANCELED", "UNKNOWN")) {
            val f = Fixture(); val saved = progressTask(state)
            f.store.save(saved)
            f.coordinator.cancel(saved.taskId)
            assertThat(f.store.get(saved.taskId)).isEqualTo(saved)
            assertThat(f.scheduler.canceledIds).isEmpty()
        }
    }

    @Test fun allowedUserStopsInvalidateTheOldGenerationAndPreserveAllProgressFields() = runTest {
        for ((pause, states) in listOf(true to listOf("QUEUED", "RUNNING"),
            false to listOf("QUEUED", "RUNNING", "PAUSED", "PARTIAL"))) {
            for (state in states) {
                val f = Fixture(); val old = progressTask(state)
                f.store.save(old)
                if (pause) f.coordinator.pause(old.taskId) else f.coordinator.cancel(old.taskId)
                assertThat(f.store.get(old.taskId)).isEqualTo(old.copy(
                    state = if (pause) "PAUSED" else "CANCELED", generation = old.generation + 1,
                    bytesPerSecond = 0, updatedAtEpochMillis = 100))
                assertThat(f.scheduler.canceledIds).containsExactly(old.workId)
                assertThat(f.store.checkpoint(completedTask(old))).isFalse()
            }
        }
    }

    @Test fun explicitCompletedBookDeletionCancelsOldWorkAndDrainsRunnerAndContentEvenIfCallerCancels() = runTest {
        val f = Fixture(); val first = completedTask(progressTask("RUNNING"))
        val second = first.copy(taskId = "other-source-task", sourceId = "b", workId = "other-source-work")
        val unrelated = first.copy(taskId = "other-book-task", bookId = "other-book", workId = "other-book-work")
        f.store.save(first); f.store.save(second); f.store.save(unrelated)
        val files = AtomicChapterFileStore(temporary.root.toPath())
        files.write("book", "source", "downloaded", "已下载正文")
        files.write("book", "b", "cached", "阅读缓存正文")
        files.write("other-book", "source", "keep", "保留正文")
        val cleared = mutableListOf<String>()
        val cleaner = LocalChapterCleaner(files) { cleared += it }
        val runnerEntered = CompletableDeferred<Unit>(); val runnerRelease = CompletableDeferred<Unit>()
        val contentEntered = CompletableDeferred<Unit>(); val contentRelease = CompletableDeferred<Unit>()
        val terminalEntered = CompletableDeferred<Unit>(); val terminalRelease = CompletableDeferred<Unit>()
        val runner = async { f.leases.withRunner("book") { runnerEntered.complete(Unit); runnerRelease.await() } }
        val content = async { f.leases.withContent("book") { contentEntered.complete(Unit); contentRelease.await() } }
        runnerEntered.await(); contentEntered.await()
        f.scheduler.beforeAwaitTerminal = { terminalEntered.complete(Unit); terminalRelease.await() }
        var cleaned = false
        val deleting = async { f.coordinator.deleteLocal("book") {
            currentCoroutineContext().ensureActive()
            cleaner.delete("book"); cleaned = true
        } }
        terminalEntered.await(); deleting.cancel(); testScheduler.runCurrent()
        assertThat(cleaned).isFalse()
        assertThat(files.read("book", "source", "downloaded")).isEqualTo("已下载正文")
        terminalRelease.complete(Unit); testScheduler.runCurrent()
        assertThat(f.scheduler.canceledIds).containsExactly(first.workId, second.workId).inOrder()
        assertThat(f.scheduler.awaitedIds).containsExactly(first.workId, second.workId).inOrder()
        assertThat(f.store.get(first.taskId)).isEqualTo(first.copy(state = "CANCELED",
            generation = first.generation + 1, bytesPerSecond = 0, updatedAtEpochMillis = 100))
        assertThat(f.store.get(second.taskId)!!.state).isEqualTo("CANCELED")
        assertThat(f.leases.isDeleting("book")).isTrue()
        assertThat(runCatching { f.leases.withContent("book") {} }.isFailure).isTrue()
        runnerRelease.complete(Unit); runner.await(); testScheduler.runCurrent()
        assertThat(cleaned).isFalse()
        contentRelease.complete(Unit); content.await(); deleting.join()
        assertThat(cleaned).isTrue()
        assertThat(f.leases.isDeleting("book")).isFalse()
        for (old in listOf(first, second)) assertThat(f.store.get(old.taskId)).isEqualTo(old.copy(
            state = "CANCELED", generation = old.generation + 1, completedCount = 0, failedCount = 0,
            bytesDownloaded = 0, failedChapterIds = "", networkFailedChapterIds = "",
            attemptedNonNetworkChapterIds = "", currentChapterId = null, bytesPerSecond = 0,
            updatedAtEpochMillis = 100))
        assertThat(f.store.get(unrelated.taskId)).isEqualTo(unrelated)
        assertThat(files.read("book", "source", "downloaded")).isNull()
        assertThat(files.read("book", "b", "cached")).isNull()
        assertThat(files.read("other-book", "source", "keep")).isEqualTo("保留正文")
        assertThat(cleared).containsExactly("book")
    }

    @Test fun cancellationDuringStartEnqueueCompletesThePersistedOwnershipHandoff() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.scheduler.beforeEnqueue = { entered.complete(Unit); release.await() }
        val starting = async { f.coordinator.start("book", "source") }
        entered.await(); starting.cancel(); release.complete(Unit); starting.join()
        val saved = f.store.all().single()
        assertThat(saved.state).isEqualTo("QUEUED")
        assertThat(f.scheduler.createdIds).containsExactly(saved.workId)
    }

    @Test fun cancellationDuringResumeEnqueueCompletesTheNewOwnershipHandoff() = runTest {
        val f = Fixture(); f.store.save(task().copy(state = "PAUSED"))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.scheduler.beforeEnqueue = { entered.complete(Unit); release.await() }
        val resuming = async { f.coordinator.resume("task") }
        entered.await(); resuming.cancel(); release.complete(Unit); resuming.join()
        val saved = f.store.get("task")!!
        assertThat(saved.generation).isEqualTo(2)
        assertThat(saved.state).isEqualTo("QUEUED")
        assertThat(f.scheduler.createdIds).containsExactly(saved.workId)
    }

    @Test fun cancellationImmediatelyAfterStartPersistsStillEnqueuesThePreparedRequest() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.store.afterPersist = { entered.complete(Unit); release.await() }
        val starting = async { f.coordinator.start("book", "source") }
        entered.await(); starting.cancel(); release.complete(Unit); starting.join()
        assertThat(f.scheduler.createdIds).containsExactly(f.store.all().single().workId)
    }

    @Test fun cancellationImmediatelyAfterResumePersistsStillEnqueuesThePreparedRequest() = runTest {
        val f = Fixture(); f.store.save(task().copy(state = "PAUSED"))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        f.store.afterPersist = { entered.complete(Unit); release.await() }
        val resuming = async { f.coordinator.resume("task") }
        entered.await(); resuming.cancel(); release.complete(Unit); resuming.join()
        assertThat(f.scheduler.createdIds).containsExactly(f.store.get("task")!!.workId)
    }

    @Test fun schedulerCancellationAfterWorkCreationPausesAndCancelsThatWorkBeforePropagating() = runTest {
        for (resume in listOf(false, true)) {
            val f = Fixture()
            if (resume) f.store.save(task().copy(state = "PAUSED"))
            f.scheduler.afterWorkCreated = { throw CancellationException("scheduler canceled after creation") }
            val failure = runCatching {
                if (resume) f.coordinator.resume("task") else f.coordinator.start("book", "source")
            }.exceptionOrNull()
            assertThat(failure).isInstanceOf(CancellationException::class.java)
            val saved = f.store.all().single()
            assertThat(saved.state).isEqualTo("PAUSED")
            assertThat(saved.lastError).isNotEmpty()
            assertThat(f.scheduler.canceledIds).contains(saved.workId)
            assertThat(f.scheduler.createdIds).containsExactly(saved.workId)
            f.scheduler.afterWorkCreated = null
            f.coordinator.resume(saved.taskId)
            assertThat(f.store.get(saved.taskId)!!.state).isEqualTo("QUEUED")
            assertThat(f.scheduler.createdIds).hasSize(2)
        }
    }

    @Test fun canceledEnqueueCompensationNeverOverridesOrCancelsANewerOwner() = runTest {
        val f = Fixture()
        f.scheduler.afterWorkCreated = {
            val saved = f.store.all().single()
            f.store.rows[saved.taskId] = saved.copy(workId = "new-owner", generation = saved.generation + 1)
            throw CancellationException("old scheduler canceled")
        }
        assertThat(runCatching { f.coordinator.start("book", "source") }.exceptionOrNull()).isInstanceOf(CancellationException::class.java)
        assertThat(f.store.all().single().workId).isEqualTo("new-owner")
        assertThat(f.store.all().single().state).isEqualTo("QUEUED")
        assertThat(f.scheduler.canceledIds).containsExactly("work-1")
    }

    @Test fun startedDeletionFinishesCleanupEvenIfCallerIsCanceled() = runTest {
        val f = Fixture()
        f.coordinator.start("book", "source")
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val reader = async { f.leases.withContent("book") { entered.complete(Unit); release.await() } }
        entered.await()
        var cleaned = false
        val deleting = async { f.coordinator.deleteLocal("book") { cleaned = true } }
        testScheduler.runCurrent(); deleting.cancel()
        release.complete(Unit); reader.await(); deleting.join()
        assertThat(cleaned).isTrue()
    }
    @Test fun pauseKeepsCheckpointThatLandsWhileCoordinatorReadsTask() = runTest {
        val f = Fixture()
        val id = (f.coordinator.start("book", "source") as DownloadStart.Started).taskId
        f.store.beforeReadReturn = { old ->
            f.store.rows[id] = old.copy(completedCount = 1, bytesDownloaded = 10, failedChapterIds = "failed", networkAttempts = 1)
        }
        f.coordinator.pause(id)
        val paused = f.store.get(id)!!
        assertThat(paused.completedCount).isEqualTo(1)
        assertThat(paused.bytesDownloaded).isEqualTo(10)
        assertThat(paused.failedChapterIds).isEqualTo("failed")
        assertThat(paused.networkAttempts).isEqualTo(1)
    }
    @Test fun uniqueNameAndDurableOwnershipPrecedeEnqueueWithUnmeteredDefault() = runTest {
        val fixture = Fixture()
        val start = fixture.coordinator.start("book", "source") as DownloadStart.Started
        assertThat(fixture.scheduler.names).containsExactly("book-download:book:source")
        assertThat(fixture.store.get(start.taskId)!!.workId).isEqualTo("work-1")
        assertThat(fixture.store.get(start.taskId)!!.generation).isEqualTo(1)
        assertThat(fixture.scheduler.metered).containsExactly(false)
        assertThat(fixture.scheduler.persistedBeforeEnqueue).isTrue()
    }

    @Test fun oneUnfinishedTaskPerBookAcrossSourcesIncludingPausedAndPartial() = runTest {
        val f = Fixture()
        val first = f.coordinator.start("book", "a") as DownloadStart.Started
        assertThat(f.coordinator.start("book", "a")).isEqualTo(DownloadStart.Existing(first.taskId))
        assertThat(f.coordinator.start("book", "b")).isEqualTo(DownloadStart.Conflict(first.taskId, "a"))
        f.coordinator.pause(first.taskId)
        assertThat(f.store.get(first.taskId)!!.state).isEqualTo("PAUSED")
        assertThat(f.coordinator.start("book", "b")).isInstanceOf(DownloadStart.Conflict::class.java)
        f.store.rows[first.taskId] = f.store.get(first.taskId)!!.copy(state = "PARTIAL")
        assertThat(f.coordinator.start("book", "b")).isInstanceOf(DownloadStart.Conflict::class.java)
    }

    @Test fun pausePersistsBeforeCancellationAndResumeWaitsActualRunner() = runTest {
        val f = Fixture()
        val id = (f.coordinator.start("book", "source") as DownloadStart.Started).taskId
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val old = async { f.leases.withRunner("book") { entered.complete(Unit); release.await() } }
        entered.await()
        f.coordinator.pause(id)
        assertThat(f.scheduler.stateWhenCanceled).isEqualTo("PAUSED")
        val resume = async { f.coordinator.resume(id) }
        testScheduler.runCurrent()
        assertThat(resume.isCompleted).isFalse()
        assertThat(f.scheduler.names).hasSize(1)
        release.complete(Unit)
        old.await(); resume.await()
        assertThat(f.store.get(id)!!.generation).isEqualTo(3)
        assertThat(f.scheduler.names).containsExactly("book-download:book:source", "book-download:book:source")
    }

    @Test fun cancellationKeepsFilesAndOldCheckpointCannotOverrideIt() = runTest {
        val f = Fixture()
        val id = (f.coordinator.start("book", "source") as DownloadStart.Started).taskId
        val old = f.store.get(id)!!
        f.coordinator.cancel(id)
        assertThat(f.store.get(id)!!.state).isEqualTo("CANCELED")
        assertThat(f.store.checkpoint(old.copy(state = "COMPLETED"))).isFalse()
        assertThat(f.store.get(id)!!.completedCount).isEqualTo(0)
    }

    @Test fun enqueueFailureAndTerminalOrphanBecomeRecoverablePausedTasks() = runTest {
        val f = Fixture()
        f.scheduler.failEnqueue = true
        val id = (f.coordinator.start("book", "source") as DownloadStart.Started).taskId
        assertThat(f.store.get(id)!!.state).isEqualTo("PAUSED")
        assertThat(f.store.get(id)!!.lastError).isNotEmpty()
        f.store.rows[id] = f.store.get(id)!!.copy(state = "RUNNING")
        f.scheduler.status = ScheduledStatus.TERMINAL
        f.coordinator.reconcile()
        assertThat(f.store.get(id)!!.state).isEqualTo("PAUSED")
    }

    @Test fun crashBetweenPersistAndEnqueueReusesExactPreparedRequest() = runTest {
        val f = Fixture()
        f.store.rows["task"] = task().copy(workId = "durable-id", generation = 8)
        f.scheduler.status = ScheduledStatus.MISSING
        f.coordinator.reconcile()
        assertThat(f.scheduler.ids).containsExactly("durable-id")
        assertThat(f.store.get("task")!!.generation).isEqualTo(8)
    }

    @Test fun explicitMobileOptInAndFailedOnlyResumeArePersisted() = runTest {
        val f = Fixture()
        val id = (f.coordinator.start("book", "source", allowMetered = true) as DownloadStart.Started).taskId
        f.coordinator.pause(id)
        f.coordinator.resume(id, failedOnly = true)
        assertThat(f.store.get(id)!!.allowMetered).isTrue()
        assertThat(f.store.get(id)!!.retryFailedOnly).isTrue()
        assertThat(f.scheduler.metered).containsExactly(true, true)
    }

    @Test fun checkedDeletionRejectsNewContentAndWaitsRunningContentBeforeCleaning() = runTest {
        val f = Fixture()
        val id = (f.coordinator.start("book", "source") as DownloadStart.Started).taskId
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var cleaned = false
        val reader = async { f.leases.withContent("book") { entered.complete(Unit); release.await() } }
        entered.await()
        val deleting = async { f.coordinator.deleteLocal("book") { cleaned = true } }
        testScheduler.runCurrent()
        assertThat(cleaned).isFalse()
        assertThat(f.store.get(id)!!.state).isEqualTo("CANCELED")
        assertThat(runCatching { f.leases.withContent("book") {} }.isFailure).isTrue()
        release.complete(Unit); reader.await(); deleting.await()
        assertThat(cleaned).isTrue()
    }

    private class Fixture {
        val store = MemoryDownloadStore()
        val scheduler = FakeScheduler(store)
        val leases = BookDownloadLeases()
        val coordinator = DownloadCoordinator(store, scheduler, leases, now = { 100L }, newTaskId = { "task-${store.rows.size}" })
    }

    private fun progressTask(state: String) = task().copy(state = state, totalCount = 10,
        completedCount = 9, failedCount = 1, generation = 7, allowMetered = true,
        retryFailedOnly = true, networkRetryOnly = true, networkAttempts = 2,
        failedChapterIds = "last", networkFailedChapterIds = "last", attemptedNonNetworkChapterIds = "previous",
        bytesDownloaded = 4096, currentChapterId = "penultimate", bytesPerSecond = 512,
        lastError = "已保存失败原因", updatedAtEpochMillis = 50)

    private fun completedTask(old: DownloadTaskEntity) = old.copy(state = "COMPLETED", completedCount = 10,
        failedCount = 0, bytesDownloaded = 8192, currentChapterId = "last", bytesPerSecond = 0,
        failedChapterIds = "", networkFailedChapterIds = "", attemptedNonNetworkChapterIds = "",
        lastError = null, updatedAtEpochMillis = 90)
}

internal fun task() = DownloadTaskEntity("task", "book", "source", "QUEUED", 0, 0, 0, 1, 1,
    workId = "work", generation = 1)

internal class MemoryDownloadStore : DownloadStore {
    val rows = linkedMapOf<String, DownloadTaskEntity>()
    val checkpoints = mutableListOf<DownloadTaskEntity>()
    override suspend fun all() = rows.values.toList()
    var beforeReadReturn: ((DownloadTaskEntity) -> Unit)? = null
    var afterPersist: (suspend () -> Unit)? = null
    var beforeMutateLatest: (suspend (DownloadTaskEntity) -> Unit)? = null
    override suspend fun get(id: String): DownloadTaskEntity? {
        val old = rows[id] ?: return null
        val hook = beforeReadReturn; beforeReadReturn = null
        hook?.invoke(old)
        return old
    }
    override suspend fun save(task: DownloadTaskEntity) { rows[task.taskId] = task; afterPersist?.invoke() }
    override suspend fun mutate(id: String, transform: (DownloadTaskEntity) -> DownloadTaskEntity): DownloadTaskEntity? {
        val observed = rows[id] ?: return null
        val checkpointHook = beforeMutateLatest; beforeMutateLatest = null
        checkpointHook?.invoke(observed)
        val old = rows[id] ?: return null
        val hook = beforeReadReturn; beforeReadReturn = null
        hook?.invoke(old)
        val updated = transform(rows[id]!!)
        rows[id] = updated; afterPersist?.invoke()
        return updated
    }
    override suspend fun checkpoint(task: DownloadTaskEntity): Boolean {
        val current = rows[task.taskId] ?: return false
        if (current.workId != task.workId || current.generation != task.generation || current.state !in setOf("QUEUED", "RUNNING")) return false
        rows[task.taskId] = task; checkpoints += task
        return true
    }
}

private class FakeScheduler(private val store: MemoryDownloadStore) : DownloadScheduler {
    val names = mutableListOf<String>(); val ids = mutableListOf<String>(); val metered = mutableListOf<Boolean>()
    var persistedBeforeEnqueue = false; var failEnqueue = false
    var stateWhenCanceled: String? = null
    var status = ScheduledStatus.TERMINAL
    val createdIds = mutableListOf<String>(); val canceledIds = mutableListOf<String>()
    val awaitedIds = mutableListOf<String>()
    var beforeEnqueue: (suspend () -> Unit)? = null
    var afterWorkCreated: (suspend () -> Unit)? = null
    var beforeAwaitTerminal: (suspend (String) -> Unit)? = null
    private var sequence = 0
    override fun newWorkId() = "work-${++sequence}"
    override suspend fun enqueue(task: DownloadTaskEntity, name: String) {
        names += name; ids += task.workId!!; metered += task.allowMetered
        persistedBeforeEnqueue = store.get(task.taskId) == task
        if (failEnqueue) error("enqueue failed")
        beforeEnqueue?.invoke()
        createdIds += task.workId
        afterWorkCreated?.invoke()
    }
    override suspend fun cancel(workId: String) { canceledIds += workId; stateWhenCanceled = store.all().firstOrNull { it.workId == workId }?.state }
    override suspend fun awaitTerminal(workId: String) { awaitedIds += workId; beforeAwaitTerminal?.invoke(workId) }
    override suspend fun status(workId: String) = status
}
