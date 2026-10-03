package com.novelradar.app.feature.downloads

import com.novelradar.app.domain.model.DownloadState
import com.novelradar.app.domain.model.DownloadTask

internal fun DownloadTask.toRow(title: String, sourceName: String, chapterTitle: String?): DownloadRow {
    val actions = buildSet {
        when (state) {
            DownloadState.QUEUED, DownloadState.RUNNING -> { add(DownloadAction.PAUSE); add(DownloadAction.CANCEL) }
            DownloadState.PAUSED -> { add(DownloadAction.CONTINUE); add(DownloadAction.CANCEL) }
            DownloadState.PARTIAL -> {
                add(DownloadAction.CONTINUE)
                if (failedCount > 0) add(DownloadAction.RETRY_FAILED)
                add(DownloadAction.CANCEL)
            }
            DownloadState.CANCELED -> add(DownloadAction.CONTINUE)
            DownloadState.COMPLETED -> add(DownloadAction.OPEN)
        }
        add(DownloadAction.DELETE_FILES)
    }
    return DownloadRow(taskId, bookId, sourceId, title, sourceName, state,
        completedCount, totalCount, if (totalCount > 0) (completedCount.toLong() * 100 / totalCount).toInt().coerceIn(0, 100) else 0,
        failedCount, bytesDownloaded, chapterTitle, bytesPerSecond, allowMetered, actions)
}
