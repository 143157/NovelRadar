package com.novelradar.app.data.db

import androidx.room.Embedded
import com.novelradar.app.data.db.entity.BookEntity
import com.novelradar.app.data.db.entity.ShelfEntryEntity
import com.novelradar.app.data.db.entity.ReadingProgressEntity

data class ShelfProjection(
    @Embedded(prefix = "book_") val book: BookEntity,
    @Embedded(prefix = "shelf_") val shelf: ShelfEntryEntity,
    @Embedded(prefix = "progress_") val progress: ReadingProgressEntity?,
    val selectedSourceId: String?,
    val chapterCount: Int,
    val unreadCount: Int,
    val lastChapterTitle: String?,
)
