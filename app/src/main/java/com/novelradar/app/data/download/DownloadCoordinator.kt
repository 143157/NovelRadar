package com.novelradar.app.data.download

import com.novelradar.app.domain.repository.DownloadStart
import com.novelradar.app.data.db.entity.DownloadTaskEntity
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class DownloadCoordinator(private val store: DownloadStore, private val scheduler: DownloadScheduler,
    private val leases: BookDownloadLeases, private val now: () -> Long = System::currentTimeMillis,
    private val newTaskId: () -> String = { UUID.randomUUID().toString() }) {
    private val operations = Mutex()

    suspend fun start(bookId: String, sourceId: String, allowMetered: Boolean = false): DownloadStart = operations.withLock {
        require(validId(bookId) && validId(sourceId)) { "Invalid download identity" }
        check(!leases.isDeleting(bookId)) { "Book content is being deleted" }
        store.all().firstOrNull { it.bookId == bookId && it.state in UNFINISHED }?.let {
            return@withLock if (it.sourceId == sourceId) DownloadStart.Existing(it.taskId)
            else DownloadStart.Conflict(it.taskId, it.sourceId)
        }
        // A canceled old runner may still be inside a file write after WorkManager marks it terminal.
        for (old in store.all().filter { it.bookId == bookId }) old.workId?.let {
            if (scheduler.status(it) == ScheduledStatus.ACTIVE) scheduler.cancel(it)
            scheduler.awaitTerminal(it)
        }
        leases.awaitRunner(bookId)
        val timestamp = now()
        val task = DownloadTaskEntity(newTaskId(), bookId, sourceId, "QUEUED", 0, 0, 0,
            timestamp, timestamp, workId = scheduler.newWorkId(), generation = 1, allowMetered = allowMetered)
        // Once ownership is durable, caller cancellation must not strand a missing request.
        withContext(NonCancellable) { store.save(task); enqueue(task) }
        DownloadStart.Started(task.taskId)
    }

    suspend fun pause(taskId: String) = operations.withLock { stop(taskId, "PAUSED") }
    suspend fun cancel(taskId: String) = operations.withLock { stop(taskId, "CANCELED") }

    private suspend fun stop(taskId: String, state: String) {
        val current = store.mutate(taskId) { latest -> latest.copy(state = state, generation = latest.generation + 1,
            bytesPerSecond = 0, updatedAtEpochMillis = now()) } ?: return
        current.workId?.let { scheduler.cancel(it) }
    }

    suspend fun resume(taskId: String, failedOnly: Boolean = false) = operations.withLock {
        val current = store.get(taskId) ?: return@withLock
        if (current.state in setOf("QUEUED", "RUNNING", "COMPLETED")) return@withLock
        check(!leases.isDeleting(current.bookId))
        check(store.all().none { it.bookId == current.bookId && it.taskId != taskId && it.state in UNFINISHED }) {
            "Another source already owns this book's download"
        }
        current.workId?.let { scheduler.cancel(it); scheduler.awaitTerminal(it) }
        leases.awaitRunner(current.bookId)
        withContext(NonCancellable) {
            val resumed = store.mutate(taskId) { latest -> latest.copy(state = "QUEUED", workId = scheduler.newWorkId(),
                generation = latest.generation + 1, retryFailedOnly = failedOnly,
                networkRetryOnly = false, networkFailedChapterIds = "", attemptedNonNetworkChapterIds = "",
                lastError = null, bytesPerSecond = 0, updatedAtEpochMillis = now()) } ?: return@withContext
            enqueue(resumed)
        }
    }

    private suspend fun enqueue(task: DownloadTaskEntity) {
        try { scheduler.enqueue(task, uniqueName(task.bookId, task.sourceId)) }
        catch (cancelled: CancellationException) {
            // A scheduler can create the work before reporting cancellation. Invalidate only this owner.
            withContext(NonCancellable) {
                store.transitionOwned(task) { it.copy(state = "PAUSED", bytesPerSecond = 0,
                    lastError = "下载调度已中断，请继续下载", updatedAtEpochMillis = now()) }
                task.workId?.let { scheduler.cancel(it) }
            }
            throw cancelled
        }
        catch (_: Exception) {
            store.transitionOwned(task) { it.copy(state = "PAUSED", lastError = "下载调度失败，请重试", updatedAtEpochMillis = now()) }
        }
    }

    suspend fun reconcile() = operations.withLock {
        for (task in store.all()) {
            if (task.state !in setOf("QUEUED", "RUNNING")) {
                if (task.state in setOf("PAUSED", "CANCELED")) task.workId?.let { scheduler.cancel(it) }
                continue
            }
            val status = task.workId?.let { scheduler.status(it) } ?: ScheduledStatus.TERMINAL
            when (status) {
                ScheduledStatus.ACTIVE -> Unit
                ScheduledStatus.MISSING -> if (task.state == "QUEUED" && task.workId != null) enqueue(task)
                    else orphan(task)
                ScheduledStatus.TERMINAL -> orphan(task)
            }
        }
    }

    private suspend fun orphan(task: DownloadTaskEntity) {
        store.transitionOwned(task) { it.copy(state = "PAUSED", bytesPerSecond = 0,
            lastError = "下载已中断，已保存进度，请继续下载", updatedAtEpochMillis = now()) }
    }

    suspend fun deleteLocal(bookId: String, clean: suspend () -> Unit) = operations.withLock {
        withContext(NonCancellable) { leases.deleting(bookId, stop = {
            for (task in store.all().filter { it.bookId == bookId }) {
                stop(task.taskId, "CANCELED")
                task.workId?.let { scheduler.awaitTerminal(it) }
            }
        }, clean = {
            for (task in store.all().filter { it.bookId == bookId }) store.mutate(task.taskId) { it.copy(
                completedCount = 0, failedCount = 0, bytesDownloaded = 0, failedChapterIds = "",
                networkFailedChapterIds = "", attemptedNonNetworkChapterIds = "", currentChapterId = null,
                bytesPerSecond = 0, updatedAtEpochMillis = now()) }
            clean()
        }) }
    }

    companion object {
        private val UNFINISHED = setOf("QUEUED", "RUNNING", "PAUSED", "PARTIAL")
        fun uniqueName(bookId: String, sourceId: String) = "book-download:$bookId:$sourceId"
        private fun validId(id: String) = id.length in 1..128 && ".." !in id && id != "." && Regex("[A-Za-z0-9._-]+").matches(id)
    }
}
