package com.novelradar.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class NovelDatabaseMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NovelDatabase::class.java)

    @Test fun versionThreeKeepsDownloadCheckpointsButPausesUncertainActiveRounds() {
        val name = "download-attempt-migration-test"
        helper.createDatabase(name, 3).apply {
            execSQL("INSERT INTO books (bookId, normalizedBookKey, bookName, author) VALUES ('book', 'key', 'Book', 'Author')")
            execSQL("INSERT INTO book_sources (bookId, sourceId, detailUrl, status) VALUES ('book', 'source', 'https://example.test/book', 'UNKNOWN')")
            execSQL("INSERT INTO shelf_entries (bookId, addedAtEpochMillis, lastReadAtEpochMillis, hasUpdate, preferredSourceId) VALUES ('book', 1, 50, 0, 'source')")
            execSQL("INSERT INTO reading_progress (bookId, sourceId, chapterId, chapterIndex, offset, updatedAtEpochMillis) VALUES ('book', 'source', 'c1', 1, 77, 50)")
            for (state in listOf("QUEUED", "RUNNING", "PARTIAL", "COMPLETED")) {
                execSQL("INSERT INTO download_tasks (taskId, bookId, sourceId, state, totalCount, completedCount, failedCount, createdAtEpochMillis, updatedAtEpochMillis, workId, generation, failedChapterIds, networkFailedChapterIds, networkAttempts, bytesDownloaded, currentChapterId, bytesPerSecond) VALUES ('$state', 'book', 'source', '$state', 100, 20, 2, 1, 50, 'old-work', 8, 'c0\nc1', 'c1', 3, 200, 'c1', 50)")
            }
            close()
        }
        helper.runMigrationsAndValidate(name, 4, true, NovelDatabase.MIGRATION_3_4).use { db ->
            db.query("SELECT taskId, state, completedCount, failedCount, workId, generation, failedChapterIds, networkFailedChapterIds, networkAttempts, bytesDownloaded, currentChapterId, bytesPerSecond, attemptedNonNetworkChapterIds, lastError FROM download_tasks ORDER BY taskId").use {
                while (it.moveToNext()) {
                    val wasActive = it.getString(0) in setOf("QUEUED", "RUNNING")
                    assertThat(it.getString(1)).isEqualTo(if (wasActive) "PAUSED" else it.getString(0))
                    assertThat(it.getInt(2)).isEqualTo(20); assertThat(it.getInt(3)).isEqualTo(2)
                    assertThat(it.getString(4)).isEqualTo("old-work"); assertThat(it.getLong(5)).isEqualTo(8)
                    assertThat(it.getString(6)).isEqualTo("c0\nc1"); assertThat(it.getString(7)).isEqualTo("c1")
                    assertThat(it.getInt(8)).isEqualTo(3); assertThat(it.getLong(9)).isEqualTo(200)
                    assertThat(it.getString(10)).isEqualTo("c1"); assertThat(it.getLong(11)).isEqualTo(if (wasActive) 0L else 50L)
                    assertThat(it.getString(12)).isEmpty()
                    if (wasActive) assertThat(it.getString(13)).isEqualTo("下载升级后请继续下载")
                }
            }
            db.query("SELECT offset FROM reading_progress").use { it.moveToFirst(); assertThat(it.getInt(0)).isEqualTo(77) }
            db.query("SELECT preferredSourceId FROM shelf_entries").use { it.moveToFirst(); assertThat(it.getString(0)).isEqualTo("source") }
        }
    }

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
