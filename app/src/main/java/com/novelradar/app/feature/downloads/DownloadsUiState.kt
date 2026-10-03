package com.novelradar.app.feature.downloads

import com.novelradar.app.domain.model.DownloadState

enum class DownloadAction(val label: String) {
    PAUSE("暂停"), CONTINUE("继续下载"), RETRY_FAILED("重试失败章节"),
    CANCEL("取消任务"), DELETE_FILES("删除本书本地正文"), OPEN("打开阅读"),
}

data class DownloadRow(
    val taskId: String, val bookId: String, val sourceId: String,
    val title: String, val sourceName: String, val state: DownloadState,
    val completed: Int, val total: Int, val percentage: Int, val failed: Int,
    val bytes: Long, val chapterTitle: String?, val bytesPerSecond: Long,
    val allowMetered: Boolean, val actions: Set<DownloadAction>,
)

data class DownloadConfirmation(val row: DownloadRow, val action: DownloadAction)
data class DownloadStartRequest(val bookId: String, val sourceId: String,
    val title: String, val sourceName: String, val allowMetered: Boolean)
data class DownloadReadTarget(val bookId: String, val sourceId: String)

data class DownloadsUiState(
    val rows: List<DownloadRow> = emptyList(), val loading: Boolean = true,
    val listError: String? = null, val notice: String? = null,
    val defaultAllowMetered: Boolean = false, val settingsReady: Boolean = false,
    val settingsError: String? = null,
    val busyTaskIds: Set<String> = emptySet(),
    val confirmation: DownloadConfirmation? = null,
    val startRequest: DownloadStartRequest? = null, val startBusy: Boolean = false,
    val notificationPermissionRequestPending: Boolean = false,
)

sealed interface DownloadsEvent {
    data object RequestNotificationPermission : DownloadsEvent
    data class OpenReader(val target: DownloadReadTarget) : DownloadsEvent
}
