package com.novelradar.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "books", indices = [Index("normalizedBookKey")])
data class BookEntity(
    @PrimaryKey val bookId: String,
    val normalizedBookKey: String,
    val bookName: String,
    val author: String,
    val coverUrl: String? = null,
    val introduction: String? = null,
)
