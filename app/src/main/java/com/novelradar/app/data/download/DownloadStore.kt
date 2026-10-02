package com.novelradar.app.data.download

import com.novelradar.app.data.db.entity.DownloadTaskEntity

interface DownloadStore {
    suspend fun all(): List<DownloadTaskEntity>
    suspend fun get(id: String): DownloadTaskEntity?
    suspend fun save(task: DownloadTaskEntity)
    /** Transactional field transition over the latest row, preserving newer progress. */
    suspend fun mutate(id: String, transform: (DownloadTaskEntity) -> DownloadTaskEntity): DownloadTaskEntity?
    /** Atomic compare-and-set of the current active work owner. Never inserts a missing task. */
    suspend fun checkpoint(task: DownloadTaskEntity): Boolean
}

internal suspend fun DownloadStore.transitionOwned(task: DownloadTaskEntity,
    transform: (DownloadTaskEntity) -> DownloadTaskEntity): DownloadTaskEntity? = mutate(task.taskId) { latest ->
    if (latest.workId == task.workId && latest.generation == task.generation && latest.state in setOf("QUEUED", "RUNNING")) transform(latest)
    else latest
}

enum class ScheduledStatus { MISSING, ACTIVE, TERMINAL }

interface DownloadScheduler {
    fun newWorkId(): String
    suspend fun enqueue(task: DownloadTaskEntity, name: String)
    suspend fun cancel(workId: String)
    suspend fun awaitTerminal(workId: String)
    suspend fun status(workId: String): ScheduledStatus
}
