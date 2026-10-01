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
    version = 2,
    exportSchema = true,
)
abstract class NovelDatabase : RoomDatabase() {
    abstract fun sourceRuleDao(): SourceRuleDao
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        const val NAME = "novel-radar.db"
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shelf_entries ADD COLUMN preferredSourceId TEXT")
                db.execSQL("""
                    UPDATE shelf_entries SET preferredSourceId = (
                        SELECT p.sourceId FROM reading_progress p JOIN book_sources bs
                        ON bs.bookId = p.bookId AND bs.sourceId = p.sourceId
                        WHERE p.bookId = shelf_entries.bookId),
                    lastReadAtEpochMillis = CASE WHEN EXISTS (
                        SELECT 1 FROM reading_progress p JOIN book_sources bs
                        ON bs.bookId = p.bookId AND bs.sourceId = p.sourceId
                        WHERE p.bookId = shelf_entries.bookId)
                    THEN MAX(COALESCE(lastReadAtEpochMillis, 0),
                        (SELECT updatedAtEpochMillis FROM reading_progress WHERE bookId = shelf_entries.bookId))
                    ELSE lastReadAtEpochMillis END
                """.trimIndent())
            }
        }
    }
}
