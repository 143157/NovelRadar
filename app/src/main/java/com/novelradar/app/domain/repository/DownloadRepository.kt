package com.novelradar.app.domain.repository

import com.novelradar.app.domain.model.DownloadTask
import kotlinx.coroutines.flow.Flow

sealed interface DownloadStart {
    data class Started(val taskId: String) : DownloadStart
    data class Existing(val taskId: String) : DownloadStart
    data class Conflict(val taskId: String, val sourceId: String) : DownloadStart
}

interface DownloadRepository {
    fun observeAll(): Flow<List<DownloadTask>>
    suspend fun start(bookId: String, sourceId: String, allowMetered: Boolean = false): DownloadStart
    suspend fun pause(taskId: String)
    suspend fun resume(taskId: String, failedOnly: Boolean = false)
    suspend fun cancel(taskId: String)
    suspend fun reconcile()
    suspend fun deleteLocal(bookId: String)
}
