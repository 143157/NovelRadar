package com.novelradar.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import com.novelradar.app.domain.model.ChapterContentFormat

@Entity(
    tableName = "reading_progress",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ReadingProgressEntity(
    @PrimaryKey val bookId: String,
    val sourceId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val offset: Int,
    val updatedAtEpochMillis: Long,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val contentFormatVersion: Int = ChapterContentFormat.LEGACY,
)
