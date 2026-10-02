package com.novelradar.app.data.db.entity

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "download_tasks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId"), Index("sourceId")],
)
data class DownloadTaskEntity(
    @PrimaryKey val taskId: String,
    val bookId: String,
    val sourceId: String,
    val state: String,
    val totalCount: Int,
    val completedCount: Int,
    val failedCount: Int,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val workId: String? = null,
    val lastError: String? = null,
    @ColumnInfo(defaultValue = "0") val generation: Long = 0,
    @ColumnInfo(defaultValue = "0") val allowMetered: Boolean = false,
    @ColumnInfo(defaultValue = "0") val retryFailedOnly: Boolean = false,
    @ColumnInfo(defaultValue = "0") val networkRetryOnly: Boolean = false,
    @ColumnInfo(defaultValue = "0") val networkAttempts: Int = 0,
    @ColumnInfo(defaultValue = "''") val failedChapterIds: String = "",
    @ColumnInfo(defaultValue = "''") val networkFailedChapterIds: String = "",
    @ColumnInfo(defaultValue = "0") val bytesDownloaded: Long = 0,
    val currentChapterId: String? = null,
    @ColumnInfo(defaultValue = "0") val bytesPerSecond: Long = 0,
)
