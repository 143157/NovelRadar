package com.novelradar.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey

@Entity(
    tableName = "shelf_entries",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["bookId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class ShelfEntryEntity(
    @PrimaryKey val bookId: String,
    val addedAtEpochMillis: Long,
    val lastReadAtEpochMillis: Long? = null,
    val hasUpdate: Boolean = false,
    val preferredSourceId: String? = null,
)
