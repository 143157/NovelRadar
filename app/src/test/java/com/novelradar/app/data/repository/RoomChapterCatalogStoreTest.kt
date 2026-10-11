package com.novelradar.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.ChapterDao
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.ChapterContentFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomChapterCatalogStoreTest {
    @Test fun refreshOfSameUrlPreservesCurrentMarkerAndAllDownloadedMetadata() = runTest {
        val old = stored("DOWNLOADED", ChapterContentFormat.CURRENT)
        val dao = MemoryChapterDao(old)

        RoomChapterCatalogStore(dao).replace("book", "source", listOf(catalog(old.url!!)))

        assertThat(dao.getChapter("book", "source", "chapter"))
            .isEqualTo(old.copy(chapterIndex = 4, title = "Corrected title"))
    }

    @Test fun refreshOfSameUrlPreservesLegacyMarkerAndAllCachedMetadata() = runTest {
        val old = stored("CACHED", ChapterContentFormat.LEGACY)
        val dao = MemoryChapterDao(old)

        RoomChapterCatalogStore(dao).replace("book", "source", listOf(catalog(old.url!!)))

        assertThat(dao.getChapter("book", "source", "chapter"))
            .isEqualTo(old.copy(chapterIndex = 4, title = "Corrected title"))
    }

    @Test fun changedUrlDoesNotInheritOldCurrentMarkerOrStoredMetadata() = runTest {
        val dao = MemoryChapterDao(stored("DOWNLOADED", ChapterContentFormat.CURRENT))

        RoomChapterCatalogStore(dao).replace("book", "source", listOf(catalog("https://example.test/new")))

        assertThat(dao.getChapter("book", "source", "chapter"))
            .isEqualTo(ChapterEntity("book", "source", "chapter", 4, "Corrected title", "https://example.test/new",
                contentFormatVersion = ChapterContentFormat.LEGACY))
    }

    private fun stored(state: String, format: Int) = ChapterEntity(
        "book", "source", "chapter", 2, "Old title", "https://example.test/chapter", state,
        "books/book/source/chapter.txt", "stored-hash", 123, contentFormatVersion = format,
    )

    private fun catalog(url: String) = Chapter("book", "source", "chapter", 4, "Corrected title", url)

    /** Low-level storage only: replaceSnapshot remains the real DAO default transaction behavior. */
    private class MemoryChapterDao(vararg initial: ChapterEntity) : ChapterDao {
        private val rows = initial.associateBy { Triple(it.bookId, it.sourceId, it.chapterId) }.toMutableMap()
        override suspend fun upsertAll(chapters: List<ChapterEntity>) { chapters.forEach { upsert(it) } }
        override suspend fun upsert(chapter: ChapterEntity) {
            rows[Triple(chapter.bookId, chapter.sourceId, chapter.chapterId)] = chapter
        }
        override fun observeChapters(bookId: String, sourceId: String): Flow<List<ChapterEntity>> =
            flowOf(rows.values.filter { it.bookId == bookId && it.sourceId == sourceId })
        override suspend fun getChapters(bookId: String, sourceId: String): List<ChapterEntity> =
            rows.values.filter { it.bookId == bookId && it.sourceId == sourceId }
        override suspend fun getChapter(bookId: String, sourceId: String, chapterId: String): ChapterEntity? =
            rows[Triple(bookId, sourceId, chapterId)]
        override suspend fun updateStoredContent(bookId: String, sourceId: String, chapterId: String,
            expectedUrl: String, state: String, path: String, sha256: String, byteCount: Long,
            contentFormatVersion: Int): Int = error("Unused in catalog tests")
        override suspend fun deleteForSource(bookId: String, sourceId: String) {
            rows.keys.removeAll { it.first == bookId && it.second == sourceId }
        }
        override suspend fun clearStoredMetadata(bookId: String) = error("Unused in catalog tests")
    }
}
