package com.novelradar.app.data.download

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.entity.DownloadTaskEntity
import com.novelradar.app.domain.repository.DownloadStart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DownloadCoordinatorTest {
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
}

internal fun task() = DownloadTaskEntity("task", "book", "source", "QUEUED", 0, 0, 0, 1, 1,
    workId = "work", generation = 1)

internal class MemoryDownloadStore : DownloadStore {
    val rows = linkedMapOf<String, DownloadTaskEntity>()
    val checkpoints = mutableListOf<DownloadTaskEntity>()
    override suspend fun all() = rows.values.toList()
    var beforeReadReturn: ((DownloadTaskEntity) -> Unit)? = null
    var afterPersist: (suspend () -> Unit)? = null
    override suspend fun get(id: String): DownloadTaskEntity? {
        val old = rows[id] ?: return null
        val hook = beforeReadReturn; beforeReadReturn = null
        hook?.invoke(old)
        return old
    }
    override suspend fun save(task: DownloadTaskEntity) { rows[task.taskId] = task; afterPersist?.invoke() }
    override suspend fun mutate(id: String, transform: (DownloadTaskEntity) -> DownloadTaskEntity): DownloadTaskEntity? {
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
    var beforeEnqueue: (suspend () -> Unit)? = null
    var afterWorkCreated: (suspend () -> Unit)? = null
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
    override suspend fun awaitTerminal(workId: String) = Unit
    override suspend fun status(workId: String) = status
}
