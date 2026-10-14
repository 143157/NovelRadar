package com.novelradar.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.BookDao
import com.novelradar.app.data.db.ShelfProjection
import com.novelradar.app.data.db.entity.BookEntity
import com.novelradar.app.data.db.entity.BookSourceEntity
import com.novelradar.app.data.db.entity.ReadingProgressEntity
import com.novelradar.app.data.db.entity.ShelfEntryEntity
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.BookDetails
import com.novelradar.app.domain.model.BookIdentityEvidence
import com.novelradar.app.domain.model.SourceHealthStatus
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.repository.ReadingLocation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RoomBookRepositoryTest {
    private val dao = FakeBookDao()
    private val repository = RoomBookRepository(dao)

    @Test fun shelfMapsActualCatalogOrdinalWithoutChangingStoredLocation() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        repository.addToShelf(book.bookId, 10, "source-a")
        val location = ReadingLocation(book.bookId, "source-a", "tie-b", 999, 77, 50,
            contentFormatVersion = ChapterContentFormat.LEGACY)
        repository.saveProgress(location)
        dao.readingChapterPosition = 3
        val shelf = repository.observeShelf().first().single()
        assertThat(shelf.readingChapterPosition).isEqualTo(3)
        assertThat(shelf.location).isEqualTo(location)
        assertThat(repository.progress(book.bookId).first()).isEqualTo(location)
        assertThat(shelf.lastReadAtEpochMillis).isEqualTo(50)
        dao.readingChapterPosition = null
        assertThat(repository.observeShelf().first().single().readingChapterPosition).isNull()
    }

    @Test fun legacyProgressRemainsLegacyInBothProgressAndShelfWithoutChangingHistory() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        repository.saveSearchHit(hit("source-b"))
        repository.addToShelf(book.bookId, 10, "source-b")
        val legacy = ReadingProgressEntity(book.bookId, "source-a", "chapter-2", 2, 77, 50,
            contentFormatVersion = ChapterContentFormat.LEGACY)
        dao.upsertProgress(legacy)

        val expected = ReadingLocation(book.bookId, "source-a", "chapter-2", 2, 77, 50,
            contentFormatVersion = ChapterContentFormat.LEGACY)
        val shelf = repository.observeShelf().first().single()
        assertThat(listOf(repository.progress(book.bookId).first(), shelf.location))
            .containsExactly(expected, expected)
        assertThat(shelf.preferredSourceId).isEqualTo("source-b")
        assertThat(shelf.addedAtEpochMillis).isEqualTo(10)
        assertThat(dao.getProgress(book.bookId)).isEqualTo(legacy)
        assertThat(repository.observeSources(book.bookId).first().map { it.sourceId })
            .containsExactly("source-a", "source-b")
    }

    @Test fun savingCurrentPositionPersistsTheProvidedCurrentMarker() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        val location = ReadingLocation(book.bookId, "source-a", "chapter-2", 2, 77, 50,
            contentFormatVersion = ChapterContentFormat.CURRENT)

        repository.saveProgress(location)

        assertThat(dao.getProgress(book.bookId)).isEqualTo(ReadingProgressEntity(
            book.bookId, "source-a", "chapter-2", 2, 77, 50,
            contentFormatVersion = ChapterContentFormat.CURRENT))
        assertThat(repository.progress(book.bookId).first()).isEqualTo(location)
    }

    @Test fun savingExplicitLegacyPositionDoesNotPromoteItsFormat() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        val location = ReadingLocation(book.bookId, "source-a", "chapter-2", 2, 77, 50,
            contentFormatVersion = ChapterContentFormat.LEGACY)

        repository.saveProgress(location)

        assertThat(dao.getProgress(book.bookId)?.contentFormatVersion).isEqualTo(ChapterContentFormat.LEGACY)
        assertThat(repository.progress(book.bookId).first()).isEqualTo(location)
    }

    @Test fun currentDetailsCheckIsReadOnlyAndRejectsChangedBookMappingOrOwnership() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        val source = repository.observeSources(book.bookId).first().single()
        val location = ReadingLocation(book.bookId, "source-a", "chapter-1", 0, 5, 200L)
        repository.addToShelf(book.bookId, 100L, "source-a")
        repository.saveProgress(location)
        val shelf = repository.observeShelf().first().single()

        assertThat(repository.detailsAreCurrent(book, source)).isTrue()
        assertThat(repository.detailsAreCurrent(book, source.copy(bookId = "foreign-book"))).isFalse()
        assertThat(repository.detailsAreCurrent(book.copy(introduction = "旧快照"), source)).isFalse()
        repository.saveSearchHit(hit("source-a").copy(detailUrl = "https://example.org/replacement"))
        assertThat(repository.detailsAreCurrent(book, source)).isFalse()
        assertThat(repository.getBook(book.bookId)).isEqualTo(book)
        assertThat(repository.progress(book.bookId).first()).isEqualTo(location)
        assertThat(repository.observeShelf().first().single()).isEqualTo(shelf)
    }

    @Test fun directDetailsSaveRejectsMetadataWhenPartialParsingFailed() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        val source = repository.observeSources(book.bookId).first().single()
        val result = repository.saveDetails(book, source, BookDetails(book.copy(introduction = "不应保存"),
            BookIdentityEvidence(book.bookName, book.author), SourceFailure.Parse("book.coverUrl", "[")))

        assertThat(result).isNull()
        assertThat(repository.getBook(book.bookId)).isEqualTo(book)
    }

    @Test fun detailsUpdateOnlyOptionalMetadataAndPreserveShelfProgressAndSources() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        repository.saveSearchHit(hit("source-b"))
        repository.addToShelf(book.bookId, 100L, "source-a")
        val location = ReadingLocation(book.bookId, "source-a", "chapter-2", 1, 9, 200L)
        repository.saveProgress(location)
        val sources = repository.observeSources(book.bookId).first()
        val shelf = repository.observeShelf().first().single()

        val saved = repository.saveDetails(book, sources.first { it.sourceId == "source-a" },
            BookDetails(book.copy(bookName = "测试 小说", author = "测试 作者",
                normalizedBookKey = "untrusted-key", coverUrl = "https://example.org/new.jpg", introduction = "新简介"),
                BookIdentityEvidence("测试 小说", "测试 作者")))

        assertThat(saved).isEqualTo(book.copy(coverUrl = "https://example.org/new.jpg", introduction = "新简介"))
        assertThat(repository.observeSources(book.bookId).first()).isEqualTo(sources)
        assertThat(repository.progress(book.bookId).first()).isEqualTo(location)
        assertThat(repository.observeShelf().first().single().copy(book = book)).isEqualTo(shelf)
    }

    @Test fun detailsDoNotOverwriteBookChangedSinceRequestBegan() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        val source = repository.observeSources(book.bookId).first().single()
        dao.upsertBook(BookEntity(book.bookId, book.normalizedBookKey, book.bookName, book.author, introduction = "较新的简介"))

        val saved = repository.saveDetails(book, source,
            BookDetails(book.copy(introduction = "过时简介"), BookIdentityEvidence()))

        assertThat(saved).isNull()
        assertThat(repository.getBook(book.bookId)?.introduction).isEqualTo("较新的简介")
    }

    @Test fun detailsDoNotOverwriteMappingChangedSinceRequestBegan() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        val source = repository.observeSources(book.bookId).first().single()
        repository.saveSearchHit(hit("source-a").copy(detailUrl = "https://example.org/replacement"))

        val saved = repository.saveDetails(book, source,
            BookDetails(book.copy(introduction = "过时简介"), BookIdentityEvidence()))

        assertThat(saved).isNull()
        assertThat(repository.getBook(book.bookId)).isEqualTo(book)
        assertThat(repository.observeSources(book.bookId).first().single().detailUrl).isEqualTo("https://example.org/replacement")
    }

    @Test fun detailsRejectIdentityMismatchEvenThroughDirectRepositoryCall() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        val source = repository.observeSources(book.bookId).first().single()

        val saved = repository.saveDetails(book, source, BookDetails(book.copy(introduction = "错误简介"),
            BookIdentityEvidence(book.bookName, "别的作者")))

        assertThat(saved).isNull()
        assertThat(repository.getBook(book.bookId)).isEqualTo(book)
    }

    @Test fun detailsWithUnknownCanonicalAuthorNeverChangeItsIdentity() = runTest {
        val book = repository.saveSearchHit(hit("source-a").copy(author = ""))
        val source = repository.observeSources(book.bookId).first().single()

        val saved = repository.saveDetails(book, source, BookDetails(book.copy(author = "页面作者", introduction = "简介"),
            BookIdentityEvidence(book.bookName, "页面作者")))

        assertThat(saved?.author).isEmpty()
        assertThat(saved?.normalizedBookKey).isEqualTo(book.normalizedBookKey)
        assertThat(saved?.bookId).isEqualTo(book.bookId)
        assertThat(saved?.introduction).isEqualTo("简介")
    }

    @Test fun detailsPreserveNonblankOptionalFieldsWhenPageOmitsThem() = runTest {
        val book = repository.saveSearchHit(hit("source-a").copy(coverUrl = "https://example.org/old.jpg", introduction = "旧简介"))
        val source = repository.observeSources(book.bookId).first().single()

        val saved = repository.saveDetails(book, source,
            BookDetails(book.copy(coverUrl = " ", introduction = null), BookIdentityEvidence()))

        assertThat(saved).isEqualTo(book)
    }

    @Test fun detailsComparePageIdentityCaseInsensitivelyWithoutChangingStoredKey() = runTest {
        val book = repository.saveSearchHit(hit("source-a").copy(bookName = "Star River", author = "Alice"))
        val source = repository.observeSources(book.bookId).first().single()

        val saved = repository.saveDetails(book, source,
            BookDetails(book.copy(introduction = "新简介"), BookIdentityEvidence("STAR RIVER", "alice")))

        assertThat(saved).isEqualTo(book.copy(introduction = "新简介"))
    }

    @Test fun searchHitCreatesOneBookWithMultipleSourceMappings() = runTest {
        val first = repository.saveSearchHit(hit("source-a"))
        val second = repository.saveSearchHit(hit("source-b"))

        assertThat(second.bookId).isEqualTo(first.bookId)
        assertThat(repository.observeSources(first.bookId).first().map { it.sourceId })
            .containsExactly("source-a", "source-b")
    }

    @Test fun repeatedSearchRefreshesUrlWithoutErasingSourceHealth() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        dao.upsertSource(BookSourceEntity(book.bookId, "source-a", "https://old.example/book", "HEALTHY", 123L))

        repository.saveSearchHit(hit("source-a").copy(detailUrl = "https://new.example/book"))

        val source = repository.observeSources(book.bookId).first().single()
        assertThat(source.detailUrl).isEqualTo("https://new.example/book")
        assertThat(source.status).isEqualTo(SourceHealthStatus.HEALTHY)
        assertThat(source.updatedAtEpochMillis).isEqualTo(123L)
    }

    @Test fun shelfAndProgressAreExposedWithoutRoomEntities() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        repository.addToShelf(book.bookId, 100L)
        repository.saveProgress(ReadingLocation(book.bookId, "source-a", "chapter-2", 2, 12, 200L))

        assertThat(repository.observeShelf().first().single().book).isEqualTo(book)
        assertThat(repository.progress(book.bookId).first()!!.chapterIndex).isEqualTo(2)
        repository.removeFromShelf(book.bookId)
        assertThat(repository.observeShelf().first()).isEmpty()
    }

    @Test fun savingNewLocationReplacesOnlyThatBooksProgressRow() = runTest {
        val first = repository.saveSearchHit(hit("source-a"))
        val second = repository.saveSearchHit(hit("source-a").copy(bookName = "另一部小说"))
        repository.saveProgress(ReadingLocation(first.bookId, "source-a", "chapter-1", 0, 1, 100L))
        repository.saveProgress(ReadingLocation(second.bookId, "source-a", "chapter-3", 2, 3, 101L))

        val updated = ReadingLocation(first.bookId, "source-a", "chapter-2", 1, 4, 102L)
        repository.saveProgress(updated)

        assertThat(repository.progress(first.bookId).first()).isEqualTo(updated)
        assertThat(repository.progress(second.bookId).first()!!.chapterId).isEqualTo("chapter-3")
    }

    @Test fun repeatedShelfAddPreservesAddedAndLastReadTimes() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        repository.addToShelf(book.bookId, 100L)
        repository.saveProgress(ReadingLocation(book.bookId, "source-a", "chapter-2", 1, 5, 200L))
        repository.addToShelf(book.bookId, 300L)
        val shelf = repository.observeShelf().first().single()
        assertThat(shelf.addedAtEpochMillis).isEqualTo(100L)
        assertThat(shelf.lastReadAtEpochMillis).isEqualTo(200L)
    }

    @Test fun progressRejectsSourceFromAnotherBook() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        val failure = runCatching { repository.saveProgress(ReadingLocation(book.bookId, "other", "1", 0, 0, 1L)) }
        assertThat(failure.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun firstShelfAddAndReAddRecoverExistingOwnedReadingHistory() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        val location = ReadingLocation(book.bookId, "source-a", "chapter-2", 1, 55, 200L)
        repository.saveProgress(location)
        repository.addToShelf(book.bookId, 300L)
        var shelf = repository.observeShelf().first().single()
        assertThat(shelf.lastReadAtEpochMillis).isEqualTo(200L)
        assertThat(shelf.preferredSourceId).isEqualTo("source-a")
        repository.removeFromShelf(book.bookId)
        repository.addToShelf(book.bookId, 500L)
        shelf = repository.observeShelf().first().single()
        assertThat(shelf.lastReadAtEpochMillis).isEqualTo(200L)
        assertThat(repository.progress(book.bookId).first()).isEqualTo(location)
    }

    @Test fun freshShelfAddIgnoresForeignLegacySourceAndKeepsExplicitChoice() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        dao.upsertProgress(ReadingProgressEntity(book.bookId, "foreign", "chapter-2", 1, 55, 200L))
        repository.addToShelf(book.bookId, 300L, "source-a")
        val shelf = repository.observeShelf().first().single()
        assertThat(shelf.lastReadAtEpochMillis).isNull()
        assertThat(shelf.preferredSourceId).isEqualTo("source-a")
    }

    @Test fun pickerSelectionRetainsOldSourceLocationAndUpdatesShelfChoice() = runTest {
        val book = repository.saveSearchHit(hit("source-a"))
        repository.saveSearchHit(hit("source-b"))
        repository.addToShelf(book.bookId, 100L, "source-a")
        val location = ReadingLocation(book.bookId, "source-a", "chapter-2", 1, 55, 200L)
        repository.saveProgress(location)
        repository.selectPreferredSource(book.bookId, "source-b")
        assertThat(repository.observeShelf().first().single().preferredSourceId).isEqualTo("source-b")
        assertThat(repository.progress(book.bookId).first()).isEqualTo(location)
    }

    private fun hit(sourceId: String) = SearchHit(
        sourceId = sourceId,
        bookName = "测试小说",
        author = "测试作者",
        detailUrl = "https://example.org/$sourceId",
    )

    internal class FakeBookDao : BookDao {
        var readingChapterPosition: Int? = null
        private val books = linkedMapOf<String, BookEntity>()
        private val sources = MutableStateFlow<List<BookSourceEntity>>(emptyList())
        private val shelf = MutableStateFlow<List<ShelfEntryEntity>>(emptyList())
        private val progresses = MutableStateFlow<List<ReadingProgressEntity>>(emptyList())

        override suspend fun upsertBook(book: BookEntity) { books[book.bookId] = book }
        override suspend fun upsertSource(source: BookSourceEntity) {
            sources.value = sources.value.filterNot { it.bookId == source.bookId && it.sourceId == source.sourceId } + source
        }
        override suspend fun getBook(bookId: String): BookEntity? = books[bookId]
        override suspend fun getByNormalizedKey(normalizedBookKey: String): BookEntity? =
            books.values.firstOrNull { it.normalizedBookKey == normalizedBookKey }
        override fun observeSources(bookId: String): Flow<List<BookSourceEntity>> =
            sources.map { rows -> rows.filter { it.bookId == bookId } }
        override suspend fun getSource(bookId: String, sourceId: String): BookSourceEntity? =
            sources.value.firstOrNull { it.bookId == bookId && it.sourceId == sourceId }
        override suspend fun upsertShelfEntry(entry: ShelfEntryEntity) {
            shelf.value = shelf.value.filterNot { it.bookId == entry.bookId } + entry
        }
        override fun observeShelf(): Flow<List<ShelfEntryEntity>> = shelf
        override suspend fun getShelfEntry(bookId: String) = shelf.value.firstOrNull { it.bookId == bookId }
        override fun observeShelfDetails(): Flow<List<ShelfProjection>> = kotlinx.coroutines.flow.combine(shelf, progresses) { rows, locations ->
            rows.sortedByDescending { it.lastReadAtEpochMillis ?: it.addedAtEpochMillis }.map { row ->
                val progress = locations.firstOrNull { it.bookId == row.bookId }
                ShelfProjection(books.getValue(row.bookId), row, progress, row.preferredSourceId ?: progress?.sourceId,
                    0, 0, null, readingChapterPosition)
            }
        }
        override suspend fun removeFromShelf(bookId: String) {
            shelf.value = shelf.value.filterNot { it.bookId == bookId }
        }
        override suspend fun upsertProgress(progress: ReadingProgressEntity) {
            progresses.value = progresses.value.filterNot { it.bookId == progress.bookId } + progress
        }
        override fun progress(bookId: String): Flow<ReadingProgressEntity?> =
            progresses.map { rows -> rows.firstOrNull { it.bookId == bookId } }
        override suspend fun getProgress(bookId: String) = progresses.value.firstOrNull { it.bookId == bookId }
        override suspend fun deleteBook(bookId: String) { books.remove(bookId) }
    }
}
