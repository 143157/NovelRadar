package com.novelradar.app.data.repository

import com.novelradar.app.data.db.DownloadTaskDao
import com.novelradar.app.data.download.DownloadCoordinator
import com.novelradar.app.data.files.LocalChapterCleaner
import com.novelradar.app.domain.model.DownloadState
import com.novelradar.app.domain.model.DownloadTask
import com.novelradar.app.domain.repository.DownloadRepository
import kotlinx.coroutines.flow.map

class WorkManagerDownloadRepository(private val dao: DownloadTaskDao, private val coordinator: DownloadCoordinator,
    private val cleaner: LocalChapterCleaner) : DownloadRepository {
    override fun observeAll() = dao.observeAll().map { tasks -> tasks.map { row -> DownloadTask(
        row.taskId, row.bookId, row.sourceId, DownloadState.entries.firstOrNull { it.name == row.state } ?: DownloadState.PAUSED,
        row.totalCount, row.completedCount, row.failedCount, row.allowMetered, row.networkAttempts,
        row.failedChapterIds.split('\n').filter(String::isNotBlank).toSet(), row.bytesDownloaded,
        row.currentChapterId, row.bytesPerSecond, row.lastError) } }
    override suspend fun start(bookId: String, sourceId: String, allowMetered: Boolean) = coordinator.start(bookId, sourceId, allowMetered)
    override suspend fun pause(taskId: String) = coordinator.pause(taskId)
    override suspend fun resume(taskId: String, failedOnly: Boolean) = coordinator.resume(taskId, failedOnly)
    override suspend fun cancel(taskId: String) = coordinator.cancel(taskId)
    override suspend fun reconcile() = coordinator.reconcile()
    override suspend fun deleteLocal(bookId: String) = coordinator.deleteLocal(bookId) { cleaner.delete(bookId) }
}
