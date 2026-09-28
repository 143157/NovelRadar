package com.novelradar.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "chapters",
    primaryKeys = ["bookId", "sourceId", "chapterId"],
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("bookId"),
        Index(value = ["bookId", "sourceId", "chapterIndex"]),
    ],
)
data class ChapterEntity(
    val bookId: String,
    val sourceId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val title: String,
    val url: String? = null,
    val localContentState: String = "NOT_STORED",
    val localFilePath: String? = null,
    val contentSha256: String? = null,
    val contentBytes: Long? = null,
)
