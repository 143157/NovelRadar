package com.novelradar.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class NovelDatabaseMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NovelDatabase::class.java)

    @Test fun versionTwoPreservesTaskShelfAndProgressAndAddsConservativeDownloadDefaults() {
        val name = "download-migration-test"
        helper.createDatabase(name, 2).apply {
            execSQL("INSERT INTO books (bookId, normalizedBookKey, bookName, author) VALUES ('book', 'key', 'Book', 'Author')")
            execSQL("INSERT INTO book_sources (bookId, sourceId, detailUrl, status) VALUES ('book', 'source', 'https://example.test/book', 'UNKNOWN')")
            execSQL("INSERT INTO shelf_entries (bookId, addedAtEpochMillis, lastReadAtEpochMillis, hasUpdate, preferredSourceId) VALUES ('book', 1, 50, 0, 'source')")
            execSQL("INSERT INTO reading_progress (bookId, sourceId, chapterId, chapterIndex, offset, updatedAtEpochMillis) VALUES ('book', 'source', 'c1', 1, 77, 50)")
            execSQL("INSERT INTO download_tasks (taskId, bookId, sourceId, state, totalCount, completedCount, failedCount, createdAtEpochMillis, updatedAtEpochMillis, workId) VALUES ('task', 'book', 'source', 'RUNNING', 100, 20, 2, 1, 50, 'old-work')")
            close()
        }
        helper.runMigrationsAndValidate(name, 3, true, NovelDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT state, completedCount, failedCount, workId, generation, allowMetered, networkAttempts, failedChapterIds FROM download_tasks").use {
                it.moveToFirst(); assertThat(it.getString(0)).isEqualTo("PAUSED")
                assertThat(it.getInt(1)).isEqualTo(20); assertThat(it.getInt(2)).isEqualTo(2)
                assertThat(it.getString(3)).isEqualTo("old-work"); assertThat(it.getLong(4)).isEqualTo(0)
                assertThat(it.getInt(5)).isEqualTo(0); assertThat(it.getInt(6)).isEqualTo(0); assertThat(it.getString(7)).isEmpty()
            }
            db.query("SELECT offset FROM reading_progress").use { it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(77) }
            db.query("SELECT preferredSourceId FROM shelf_entries").use { it.moveToFirst(); assertThat(it.getString(0)).isEqualTo("source") }
        }
    }

    @Test fun versionOneRestoresOwnedSourceAndReadTimeWithoutLosingNewerTime() {
        val name = "shelf-migration-test"
        helper.createDatabase(name, 1).apply {
            for (id in listOf("old", "newer", "foreign")) {
                execSQL("INSERT INTO books (bookId, normalizedBookKey, bookName, author) VALUES ('$id', '$id', '$id', '作者')")
                execSQL("INSERT INTO book_sources (bookId, sourceId, detailUrl, status) VALUES ('$id', 'a', 'https://example.org/$id', 'UNKNOWN')")
                execSQL("INSERT INTO shelf_entries (bookId, addedAtEpochMillis, lastReadAtEpochMillis, hasUpdate) VALUES ('$id', 1, ${if (id == "newer") "500" else "NULL"}, 0)")
                execSQL("INSERT INTO reading_progress (bookId, sourceId, chapterId, chapterIndex, offset, updatedAtEpochMillis) VALUES ('$id', '${if (id == "foreign") "invalid" else "a"}', 'chapter', 2, 77, 200)")
            }
            close()
        }
        helper.runMigrationsAndValidate(name, 2, true, NovelDatabase.MIGRATION_1_2).use { db ->
            db.query("SELECT bookId, preferredSourceId, lastReadAtEpochMillis FROM shelf_entries ORDER BY bookId").use { cursor ->
                cursor.moveToNext()
                assertThat(cursor.getString(0)).isEqualTo("foreign")
                assertThat(cursor.isNull(1)).isTrue()
                assertThat(cursor.isNull(2)).isTrue()
                cursor.moveToNext()
                assertThat(cursor.getString(1)).isEqualTo("a")
                assertThat(cursor.getLong(2)).isEqualTo(500)
                cursor.moveToNext()
                assertThat(cursor.getLong(2)).isEqualTo(200)
            }
            db.query("SELECT offset FROM reading_progress WHERE bookId = 'old'").use {
                it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(77)
            }
        }
    }
}
