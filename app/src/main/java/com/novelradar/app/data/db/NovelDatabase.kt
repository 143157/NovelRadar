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
    version = 5,
    exportSchema = true,
)
abstract class NovelDatabase : RoomDatabase() {
    abstract fun sourceRuleDao(): SourceRuleDao
    abstract fun bookDao(): BookDao
    abstract fun chapterDao(): ChapterDao
    abstract fun downloadTaskDao(): DownloadTaskDao

    companion object {
        const val NAME = "novel-radar.db"
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE chapters ADD COLUMN contentFormatVersion INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE reading_progress ADD COLUMN contentFormatVersion INTEGER NOT NULL DEFAULT 0")
            }
        }
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN attemptedNonNetworkChapterIds TEXT NOT NULL DEFAULT ''")
                // Version 3 cannot distinguish this run's attempts from historical failures safely.
                db.execSQL("UPDATE download_tasks SET state = 'PAUSED', bytesPerSecond = 0, lastError = '下载升级后请继续下载' WHERE state IN ('QUEUED', 'RUNNING')")
            }
        }
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN generation INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN allowMetered INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN retryFailedOnly INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN networkRetryOnly INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN networkAttempts INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN failedChapterIds TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN networkFailedChapterIds TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN bytesDownloaded INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN currentChapterId TEXT")
                db.execSQL("ALTER TABLE download_tasks ADD COLUMN bytesPerSecond INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE download_tasks SET state = 'PAUSED', lastError = '下载升级后请继续下载' WHERE state IN ('QUEUED', 'RUNNING')")
            }
        }
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
