package com.novelradar.app.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.entity.BookEntity
import com.novelradar.app.data.db.entity.BookSourceEntity
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.data.db.entity.DownloadTaskEntity
import com.novelradar.app.data.db.entity.ReadingProgressEntity
import com.novelradar.app.data.db.entity.ShelfEntryEntity
import com.novelradar.app.data.db.entity.SourceRuleEntity
import com.novelradar.app.domain.model.ChapterContentFormat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NovelDatabaseTest {
    private lateinit var database: NovelDatabase

    @Before fun openDatabase() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NovelDatabase::class.java,
        ).build()
    }

    @After fun closeDatabase() = database.close()

    @Test fun shelfOrdinalUsesExistingSourceCatalogWithGapsTiesAndStaleSavedIndex() = runBlocking {
        val books = database.bookDao()
        books.upsertBookAndSource(book(), bookSource())
        books.upsertSource(bookSource().copy(sourceId = "other"))
        books.addShelfPreservingHistory("book-1", 10, "source-1")
        database.chapterDao().upsertAll(listOf(chapter("first", 10), chapter("tie-a", 30), chapter("tie-b", 30),
            chapter("last", 90), chapter("tie-b", 2).copy(sourceId = "other")))
        val saved = progress(999, 77).copy(chapterId = "tie-b")
        books.upsertProgress(saved)
        var row = books.observeShelfDetails().first().single()
        assertThat(row.readingChapterPosition).isEqualTo(3)
        assertThat(row.chapterCount).isEqualTo(4)
        assertThat(row.unreadCount).isEqualTo(0)
        assertThat(books.getProgress("book-1")).isEqualTo(saved)
        books.upsertProgress(saved.copy(chapterId = "removed"))
        assertThat(books.observeShelfDetails().first().single().readingChapterPosition).isNull()
        books.upsertProgress(saved.copy(sourceId = "other"))
        row = books.observeShelfDetails().first().single()
        assertThat(row.readingChapterPosition).isNull()
        assertThat(row.unreadCount).isEqualTo(4)
        books.upsertShelfEntry(shelfEntry(10).copy(preferredSourceId = "missing"))
        row = books.observeShelfDetails().first().single()
        assertThat(row.selectedSourceId).isEqualTo("other")
        assertThat(row.readingChapterPosition).isEqualTo(1)
        assertThat(row.chapterCount).isEqualTo(1)
    }

    @Test fun downloadedMetadataCannotBeDowngradedOrUpdatedWithStaleUrl() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())
        val row = chapter("c-1", 0).copy(url = "https://example.test/one", localContentState = "DOWNLOADED")
        database.chapterDao().upsert(row)
        assertThat(database.chapterDao().updateStoredContent("book-1", "source-1", "c-1", "https://example.test/old",
            "CACHED", "wrong", "wrong", 1)).isEqualTo(0)
        assertThat(database.chapterDao().getChapter("book-1", "source-1", "c-1")!!.contentFormatVersion)
            .isEqualTo(ChapterContentFormat.LEGACY)
        assertThat(database.chapterDao().updateStoredContent("book-1", "source-1", "c-1", row.url!!,
            "CACHED", "books/book-1/source-1/c-1.txt", "abc", 20)).isEqualTo(1)
        assertThat(database.chapterDao().getChapter("book-1", "source-1", "c-1")!!.localContentState).isEqualTo("DOWNLOADED")
        assertThat(database.chapterDao().getChapter("book-1", "source-1", "c-1")!!.contentFormatVersion)
            .isEqualTo(ChapterContentFormat.CURRENT)
    }

    @Test fun changedCatalogUrlDoesNotPreserveContentFromTheOldUrl() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())
        database.chapterDao().upsert(chapter("c-1", 0).copy(url = "https://example.test/old", localContentState = "DOWNLOADED",
            localFilePath = "old", contentSha256 = "abc", contentBytes = 20, contentFormatVersion = ChapterContentFormat.CURRENT))
        database.chapterDao().replaceSnapshot("book-1", "source-1", listOf(chapter("c-1", 0).copy(url = "https://example.test/new")))
        val current = database.chapterDao().getChapter("book-1", "source-1", "c-1")!!
        assertThat(current.localContentState).isEqualTo("NOT_STORED")
        assertThat(current.contentSha256).isNull()
        assertThat(current.contentFormatVersion).isEqualTo(ChapterContentFormat.LEGACY)
    }

    @Test fun roomDownloadCheckpointRejectsNewGenerationAndNeverReinsertsDeletedTask() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())
        val store = com.novelradar.app.data.download.RoomDownloadStore(database)
        val old = downloadTask(2).copy(workId = "work", generation = 1)
        store.save(old)
        store.mutate(old.taskId) { it.copy(state = "PAUSED", generation = 2) }
        assertThat(store.checkpoint(old.copy(completedCount = 5))).isFalse()
        assertThat(store.get(old.taskId)!!.completedCount).isEqualTo(2)
        database.downloadTaskDao().delete(old.taskId)
        assertThat(store.checkpoint(old)).isFalse()
        assertThat(store.get(old.taskId)).isNull()
    }

    @Test fun sourceUpsertReplacesExistingRule() = runBlocking {
        database.sourceRuleDao().upsert(sourceRule(rawJson = "{\"version\":1}"))
        database.sourceRuleDao().upsert(sourceRule(rawJson = "{\"version\":2}"))

        assertThat(database.sourceRuleDao().observeAll().first()).hasSize(1)
        assertThat(database.sourceRuleDao().getById("source-1")?.rawJson)
            .isEqualTo("{\"version\":2}")
    }

    @Test fun bookAndSourceInsertIsObservable() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())

        assertThat(database.bookDao().getBook("book-1")?.bookName).isEqualTo("测试小说")
        assertThat(database.bookDao().observeSources("book-1").first())
            .containsExactly(bookSource())
    }

    @Test fun chaptersAreOrderedByIndex() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())
        database.chapterDao().upsertAll(listOf(chapter("c-3", 3), chapter("c-1", 1)))

        assertThat(database.chapterDao().observeChapters("book-1", "source-1").first()
            .map { it.chapterId }).containsExactly("c-1", "c-3").inOrder()
    }

    @Test fun catalogRefreshKeepsMatchingDownloadedChapterMetadata() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())
        database.chapterDao().upsertAll(listOf(
            chapter("c-1", 0).copy(localContentState = "DOWNLOADED", localFilePath = "books/book-1/c-1.txt",
                contentSha256 = "abc", contentBytes = 42, contentFormatVersion = ChapterContentFormat.CURRENT),
            chapter("c-2", 1),
        ))

        database.chapterDao().replaceSnapshot("book-1", "source-1", listOf(
            chapter("c-1", 1).copy(title = "更正标题"),
            chapter("c-3", 2),
        ))

        val rows = database.chapterDao().getChapters("book-1", "source-1")
        assertThat(rows.map { it.chapterId }).containsExactly("c-1", "c-3").inOrder()
        assertThat(rows.first().title).isEqualTo("更正标题")
        assertThat(rows.first().localContentState).isEqualTo("DOWNLOADED")
        assertThat(rows.first().localFilePath).isEqualTo("books/book-1/c-1.txt")
        assertThat(rows.first().contentSha256).isEqualTo("abc")
        assertThat(rows.first().contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
    }

    @Test fun shelfUpsertKeepsOneRowPerBook() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())
        database.bookDao().upsertShelfEntry(shelfEntry(addedAtEpochMillis = 1))
        database.bookDao().upsertShelfEntry(shelfEntry(addedAtEpochMillis = 2))

        assertThat(database.bookDao().observeShelf().first())
            .containsExactly(shelfEntry(addedAtEpochMillis = 2))
    }

    @Test fun replacingProgressKeepsOnlyLatestLocation() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())
        database.bookDao().upsertProgress(progress(chapterIndex = 4, offset = 20))
        database.bookDao().upsertProgress(progress(chapterIndex = 7, offset = 3))

        val stored = database.bookDao().progress("book-1").first()
        assertThat(stored?.chapterIndex).isEqualTo(7)
        assertThat(stored?.offset).isEqualTo(3)
    }

    @Test fun downloadTaskUpsertIsObservable() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())
        database.downloadTaskDao().upsert(downloadTask(completedCount = 2))
        database.downloadTaskDao().upsert(downloadTask(completedCount = 5))

        assertThat(database.downloadTaskDao().observeForBook("book-1").first())
            .containsExactly(downloadTask(completedCount = 5))
    }

    @Test fun shelfProjectionCountsOnlyPreferredSourceAndReactsToCatalogAndProgress() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())
        database.bookDao().upsertSource(bookSource().copy(sourceId = "other"))
        database.bookDao().addShelfPreservingHistory("book-1", 10, "source-1")
        database.chapterDao().upsertAll(listOf(chapter("c-0", 0), chapter("c-1", 1), chapter("c-2", 2),
            chapter("foreign", 99).copy(sourceId = "other")))
        var row = database.bookDao().observeShelfDetails().first().single()
        assertThat(row.chapterCount).isEqualTo(3)
        assertThat(row.unreadCount).isEqualTo(3)
        database.bookDao().saveReadingLocation(progress(1, 17))
        row = database.bookDao().observeShelfDetails().first().single()
        assertThat(row.unreadCount).isEqualTo(1)
        assertThat(row.progress?.offset).isEqualTo(17)
        assertThat(row.shelf.lastReadAtEpochMillis).isEqualTo(100)
        database.chapterDao().upsert(chapter("c-3", 3))
        row = database.bookDao().observeShelfDetails().first().single()
        assertThat(row.chapterCount).isEqualTo(4)
        assertThat(row.unreadCount).isEqualTo(2)
    }

    @Test fun invalidLegacyPreferredSourceFallsBackToKnownProgressSource() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())
        database.bookDao().upsertShelfEntry(ShelfEntryEntity("book-1", 1, preferredSourceId = "foreign"))
        database.bookDao().upsertProgress(progress(1, 0))
        assertThat(database.bookDao().observeShelfDetails().first().single().selectedSourceId).isEqualTo("source-1")
    }

    @Test fun shelfProjectionExposesActualCurrentAndLegacyMarkers() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())
        database.bookDao().addShelfPreservingHistory("book-1", 10, "source-1")
        for (format in listOf(ChapterContentFormat.CURRENT, ChapterContentFormat.LEGACY)) {
            database.bookDao().upsertProgress(progress(1, 77).copy(contentFormatVersion = format))
            val row = database.bookDao().observeShelfDetails().first().single()
            assertThat(row.progress?.contentFormatVersion).isEqualTo(format)
            assertThat(row.progress?.offset).isEqualTo(77)
            assertThat(row.selectedSourceId).isEqualTo("source-1")
        }
    }

    @Test fun clearingLocalMetadataKeepsCatalogAndProgress() = runBlocking {
        database.bookDao().upsertBookAndSource(book(), bookSource())
        database.chapterDao().upsert(chapter("c-1", 1).copy(localContentState = "DOWNLOADED",
            localFilePath = "books/book-1/chapter.txt", contentSha256 = "abc", contentBytes = 50))
        database.bookDao().upsertProgress(progress(1, 12))
        database.chapterDao().clearStoredMetadata("book-1")
        val stored = database.chapterDao().getChapters("book-1", "source-1").single()
        assertThat(stored.localContentState).isEqualTo("NOT_STORED")
        assertThat(stored.localFilePath).isNull()
        assertThat(stored.contentBytes).isNull()
        assertThat(database.bookDao().progress("book-1").first()?.offset).isEqualTo(12)
    }

    private fun sourceRule(rawJson: String) = SourceRuleEntity(
        sourceId = "source-1", rawJson = rawJson, enabled = true, builtIn = false,
        allowCleartext = false,
    )

    private fun book() = BookEntity(
        bookId = "book-1", normalizedBookKey = "测试小说|作者", bookName = "测试小说",
        author = "作者",
    )

    private fun bookSource() = BookSourceEntity(
        bookId = "book-1", sourceId = "source-1", detailUrl = "https://example.test/book/1",
    )

    private fun chapter(id: String, index: Int) = ChapterEntity(
        bookId = "book-1", sourceId = "source-1", chapterId = id,
        chapterIndex = index, title = "第 $index 章",
    )

    private fun shelfEntry(addedAtEpochMillis: Long) = ShelfEntryEntity(
        bookId = "book-1", addedAtEpochMillis = addedAtEpochMillis,
    )

    private fun progress(chapterIndex: Int, offset: Int) = ReadingProgressEntity(
        bookId = "book-1", sourceId = "source-1", chapterId = "c-$chapterIndex",
        chapterIndex = chapterIndex, offset = offset, updatedAtEpochMillis = 100,
    )

    private fun downloadTask(completedCount: Int) = DownloadTaskEntity(
        taskId = "task-1", bookId = "book-1", sourceId = "source-1", state = "RUNNING",
        totalCount = 10, completedCount = completedCount, failedCount = 0,
        createdAtEpochMillis = 100, updatedAtEpochMillis = 100,
    )
}
