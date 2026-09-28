package com.novelradar.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import com.novelradar.app.data.db.entity.BookEntity
import com.novelradar.app.data.db.entity.BookSourceEntity
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.data.db.entity.DownloadTaskEntity
import com.novelradar.app.data.db.entity.ReadingProgressEntity
import com.novelradar.app.data.db.entity.ShelfEntryEntity
import com.novelradar.app.data.db.entity.SourceRuleEntity

@Database(
    entities = [
        SourceRuleEntity::class,
        BookEntity::class,
        BookSourceEntity::class,
        ChapterEntity::class,
        ShelfEntryEntity::class,
        ReadingProgressEntity::class,
        DownloadTaskEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class NovelDatabase : RoomDatabase() {
    abstract fun sourceRuleDao(): SourceRuleDao
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        const val NAME = "novel-radar.db"
    }
}
