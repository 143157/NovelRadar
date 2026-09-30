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
                contentSha256 = "abc", contentBytes = 42),
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
