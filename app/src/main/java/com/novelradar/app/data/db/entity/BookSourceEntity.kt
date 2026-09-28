package com.novelradar.app.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "book_sources",
    primaryKeys = ["bookId", "sourceId"],
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
data class BookSourceEntity(
    val bookId: String,
    val sourceId: String,
    val detailUrl: String,
    val status: String = "UNKNOWN",
    val updatedAtEpochMillis: Long? = null,
)
