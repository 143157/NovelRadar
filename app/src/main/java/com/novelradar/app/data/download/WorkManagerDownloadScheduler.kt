package com.novelradar.app.data.download

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.novelradar.app.data.db.entity.DownloadTaskEntity
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

class WorkManagerDownloadScheduler(context: Context, private val policy: suspend (String) -> CrawlPolicy) : DownloadScheduler {
    private val work by lazy { WorkManager.getInstance(context.applicationContext) }
    override fun newWorkId() = UUID.randomUUID().toString()
    override suspend fun enqueue(task: DownloadTaskEntity, name: String) {
        val id = UUID.fromString(requireNotNull(task.workId))
        val crawl = policy(task.sourceId)
        val request = OneTimeWorkRequestBuilder<BookDownloadWorker>()
            .setId(id)
            .setInputData(workDataOf(BookDownloadWorker.TASK_ID to task.taskId, BookDownloadWorker.GENERATION to task.generation))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if (task.allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, crawl.retryIntervalMillis.coerceAtLeast(10_000), TimeUnit.MILLISECONDS)
            .build()
        withContext(Dispatchers.IO) {
            work.enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, request).result.get()
            check(work.getWorkInfoById(id).get() != null) { "Another request owns this unique work" }
        }
    }
    override suspend fun cancel(workId: String) {
        val id = runCatching { UUID.fromString(workId) }.getOrNull() ?: return
        withContext(Dispatchers.IO) { work.cancelWorkById(id).result.get() }
    }
    override suspend fun awaitTerminal(workId: String) {
        val id = runCatching { UUID.fromString(workId) }.getOrNull() ?: return
        work.getWorkInfoByIdFlow(id).first { it == null || it.state.isFinished }
    }
    override suspend fun status(workId: String): ScheduledStatus {
        val id = runCatching { UUID.fromString(workId) }.getOrNull() ?: return ScheduledStatus.TERMINAL
        val info = withContext(Dispatchers.IO) { work.getWorkInfoById(id).get() }
        return when { info == null -> ScheduledStatus.MISSING; info.state.isFinished -> ScheduledStatus.TERMINAL; else -> ScheduledStatus.ACTIVE }
    }
}
