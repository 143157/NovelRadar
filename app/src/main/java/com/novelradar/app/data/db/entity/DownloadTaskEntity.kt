package com.novelradar.app.data.db.entity

import androidx.room.Entity
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
)
