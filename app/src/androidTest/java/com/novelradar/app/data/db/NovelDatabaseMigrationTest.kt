package com.novelradar.app.data.db

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test

class NovelDatabaseMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), NovelDatabase::class.java)

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
