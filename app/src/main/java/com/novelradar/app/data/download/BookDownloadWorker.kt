package com.novelradar.app.data.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.novelradar.app.NovelRadarApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

class BookDownloadWorker(
    context: Context,
    parameters: WorkerParameters,
    private val runner: BookDownloadRunner,
    private val store: DownloadStore,
    private val notifications: DownloadNotificationFactory,
    private val foregroundUpdate: (suspend (ForegroundInfo) -> Unit)? = null,
) : CoroutineWorker(context, parameters) {
    constructor(context: Context, parameters: WorkerParameters) : this(context, parameters,
        (context.applicationContext as NovelRadarApplication).container.downloadRunner,
        (context.applicationContext as NovelRadarApplication).container.downloadStore,
        (context.applicationContext as NovelRadarApplication).container.downloadNotifications)

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(TASK_ID) ?: return Result.failure()
        val generation = inputData.getLong(GENERATION, -1)
        val workId = id.toString()
        val task = store.get(taskId)?.takeIf { it.workId == workId && it.generation == generation && it.state in setOf("QUEUED", "RUNNING") }
            ?: return Result.success()
        return try {
            foreground(taskId, task.completedCount, task.totalCount)
            when (runner.run(taskId, workId, generation) { completed, total -> foreground(taskId, completed, total) }) {
                DownloadRunResult.RETRY -> Result.retry()
                else -> Result.success()
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { runner.pauseForPlatform(taskId, workId, generation, "下载已中断，已保存进度，请继续下载") }
            throw cancelled
        } catch (_: Exception) {
            runner.pauseForPlatform(taskId, workId, generation, "系统暂时无法运行前台下载，已保存进度，请重试")
            Result.success()
        }
    }

    private suspend fun foreground(taskId: String, completed: Int, total: Int) {
        val info = notifications.foreground(taskId, completed, total)
        if (foregroundUpdate != null) foregroundUpdate.invoke(info) else setForeground(info)
    }

    companion object {
        const val TASK_ID = "download_task_id"
        const val GENERATION = "download_generation"
    }
}
