package com.novelradar.app.domain.model

enum class DownloadState { QUEUED, RUNNING, PAUSED, PARTIAL, COMPLETED, CANCELED }

data class DownloadTask(
    val taskId: String,
    val bookId: String,
    val sourceId: String,
    val state: DownloadState,
    val totalCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val allowMetered: Boolean,
    val networkAttempts: Int,
    val failedChapterIds: Set<String>,
    val bytesDownloaded: Long,
    val currentChapterId: String?,
    val bytesPerSecond: Long,
    val lastError: String?,
)
