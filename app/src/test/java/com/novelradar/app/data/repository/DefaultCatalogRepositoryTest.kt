package com.novelradar.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookDetails
import com.novelradar.app.domain.model.BookIdentityEvidence
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.CatalogFetcher
import com.novelradar.app.domain.repository.DetailsFetcher
import com.novelradar.app.domain.repository.ChapterCatalogStore
import com.novelradar.app.domain.repository.ImportSummary
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.repository.ShelfBook
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.source.rule.RuleDto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runCurrent
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultCatalogRepositoryTest {
    @Test fun cancelledRequestCannotSaveDetailsEvenIfFetcherIgnoresCancellation() = runTest {
        val books = MemoryBooks()
        val saved = books.book
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = detailsRepository(books) { _, _, _, _ ->
            withContext(NonCancellable) { started.complete(Unit); release.await() }
            SourceResult.Success(BookDetails(saved.copy(introduction = "被取消的简介"), BookIdentityEvidence()))
        }
        val request = launch { repository.refreshDetails("book-1", "a") }
        runCurrent()
        request.cancel()
        release.complete(Unit)
        request.join()

        assertThat(started.isCompleted).isTrue()
        assertThat(books.book).isEqualTo(saved)
    }

    @Test fun refreshDetailsSavesMetadataWithoutChangingCanonicalIdentityOrCatalogs() = runTest {
        val books = MemoryBooks()
        val store = MemoryCatalogStore()
        store.replace("book-1", "b", listOf(chapter("b", 0, "乙目录", "https://example.org/b/1")))
        val repository = detailsRepository(books, store) { _, _, _, _ ->
            SourceResult.Success(BookDetails(books.book.copy(bookName = "星 河", author = "叶 子",
                coverUrl = "https://example.org/new.jpg", introduction = "新简介"), BookIdentityEvidence("星 河", "叶 子")))
        }

        val result = repository.refreshDetails("book-1", "a")

        assertThat(result.fromCache).isFalse()
        assertThat(result.warning).isNull()
        assertThat(result.book).isEqualTo(books.book.copy(coverUrl = "https://example.org/new.jpg", introduction = "新简介"))
        assertThat(result.book?.bookName).isEqualTo("星河")
        assertThat(result.book?.normalizedBookKey).isEqualTo("星河|叶子")
        assertThat(result.identity).isEqualTo(BookIdentityEvidence("星 河", "叶 子"))
        assertThat(store.observe("book-1", "b").first().single().title).isEqualTo("乙目录")
    }

    @Test fun detailFailureReturnsSavedBookAndDoesNotTouchCatalog() = runTest {
        val books = MemoryBooks()
        val saved = books.book
        val store = MemoryCatalogStore()
        store.replace("book-1", "a", listOf(chapter("a", 0, "旧目录", "https://example.org/old")))
        val result = detailsRepository(books, store) { _, _, _, _ -> SourceResult.Failure(SourceFailure.Timeout) }
            .refreshDetails("book-1", "a")

        assertThat(result.book).isEqualTo(saved)
        assertThat(result.fromCache).isTrue()
        assertThat(result.warning).isEqualTo(SourceFailure.Timeout)
        assertThat(result.identity).isNull()
        assertThat(books.book).isEqualTo(saved)
        assertThat(store.observe("book-1", "a").first().single().title).isEqualTo("旧目录")
    }

    @Test fun mismatchingActualAuthorRejectsAllRemoteMetadata() = runTest {
        val books = MemoryBooks()
        val saved = books.book
        val result = detailsRepository(books) { _, _, _, _ -> SourceResult.Success(
            BookDetails(saved.copy(author = "同名的其他作者", introduction = "错误简介"),
                BookIdentityEvidence(saved.bookName, "同名的其他作者"))) }
            .refreshDetails("book-1", "a")

        assertThat(result.warning).isNotNull()
        assertThat(result.book).isEqualTo(saved)
        assertThat(books.book).isEqualTo(saved)
        assertThat(result.fromCache).isTrue()
        assertThat(result.identityMismatch).isTrue()
        assertThat(result.identity?.author).isEqualTo("同名的其他作者")
    }

    @Test fun mismatchingActualTitleRejectsAllRemoteMetadata() = runTest {
        val books = MemoryBooks()
        val saved = books.book
        val result = detailsRepository(books) { _, _, _, _ -> SourceResult.Success(
            BookDetails(saved.copy(bookName = "另一部作品", coverUrl = "https://example.org/wrong.jpg"),
                BookIdentityEvidence("另一部作品", saved.author))) }
            .refreshDetails("book-1", "a")

        assertThat(result.warning).isNotNull()
        assertThat(result.book).isEqualTo(saved)
        assertThat(books.book).isEqualTo(saved)
        assertThat(result.identityMismatch).isTrue()
    }

    @Test fun unknownEvidenceCanEnrichMetadataWithoutConfirmingCanonicalAuthor() = runTest {
        val books = MemoryBooks()
        val result = detailsRepository(books) { _, _, _, _ -> SourceResult.Success(
            BookDetails(books.book.copy(introduction = "可用简介"), BookIdentityEvidence())) }
            .refreshDetails("book-1", "a")

        assertThat(result.warning).isNull()
        assertThat(result.book?.introduction).isEqualTo("可用简介")
        assertThat(result.identity).isEqualTo(BookIdentityEvidence())
        assertThat(result.book?.author).isEqualTo("叶子")
    }

    @Test fun missingOptionalMetadataDoesNotEraseSavedCoverOrIntroduction() = runTest {
        val books = MemoryBooks()
        val saved = books.book
        val result = detailsRepository(books) { _, _, _, _ -> SourceResult.Success(
            BookDetails(saved.copy(coverUrl = null, introduction = "  "), BookIdentityEvidence())) }
            .refreshDetails("book-1", "a")

        assertThat(result.warning).isNull()
        assertThat(result.book).isEqualTo(saved)
    }

    @Test fun changedMappingDuringRequestRejectsLateDetails() = runTest {
        val books = MemoryBooks()
        val saved = books.book
        val result = detailsRepository(books) { _, _, _, _ ->
            books.mappings.value = books.mappings.value.map { if (it.sourceId == "a") it.copy(detailUrl = "https://example.org/new-a") else it }
            SourceResult.Success(BookDetails(saved.copy(introduction = "过时简介"), BookIdentityEvidence()))
        }.refreshDetails("book-1", "a")

        assertThat(result.warning).isNotNull()
        assertThat(result.book).isEqualTo(saved)
        assertThat(books.book).isEqualTo(saved)
    }

    @Test fun refreshReplacesOnlySelectedSourceAndRemovesDuplicateUrls() = runTest {
        val store = MemoryCatalogStore()
        var calls = 0
        val repository = repository(store) { _, source, _, _ ->
            calls++
            SourceResult.Success(listOf(
                chapter(source.sourceId, 0, "第一章", "https://example.org/1"),
                chapter(source.sourceId, 1, "重复", "https://example.org/1"),
                chapter(source.sourceId, 2, "第二章", "https://example.org/2"),
            ))
        }

        val first = repository.catalog("book-1", "a")
        val cached = repository.catalog("book-1", "a")
        val other = repository.catalog("book-1", "b")
        val refreshed = repository.catalog("book-1", "a", forceRefresh = true)

        assertThat(first.chapters.map { it.title }).containsExactly("第一章", "第二章").inOrder()
        assertThat(first.chapters.map { it.chapterIndex }).containsExactly(0, 1).inOrder()
        assertThat(first.chapters.map { it.chapterId }.distinct()).hasSize(2)
        assertThat(cached.fromCache).isTrue()
        assertThat(other.chapters).hasSize(2)
        assertThat(store.observe("book-1", "b").first()).hasSize(2)
        assertThat(refreshed.fromCache).isFalse()
        assertThat(calls).isEqualTo(3)
    }

    @Test fun failedRefreshReturnsPreviousCatalogAndWarning() = runTest {
        val store = MemoryCatalogStore()
        store.replace("book-1", "a", listOf(chapter("a", 0, "旧章节", "https://example.org/old")))
        val repository = repository(store) { _, _, _, _ -> SourceResult.Failure(SourceFailure.Timeout) }

        val result = repository.catalog("book-1", "a", forceRefresh = true)

        assertThat(result.chapters.map { it.title }).containsExactly("旧章节")
        assertThat(result.warning).isEqualTo(SourceFailure.Timeout)
        assertThat(store.observe("book-1", "a").first().map { it.title }).containsExactly("旧章节")
    }

    private fun repository(store: ChapterCatalogStore, fetch: CatalogFetcher) = DefaultCatalogRepository(
        books = MemoryBooks(), rules = MemoryRules(), store = store, fetcher = fetch,
    )

    private fun detailsRepository(books: MemoryBooks, store: ChapterCatalogStore = MemoryCatalogStore(), fetch: DetailsFetcher) =
        DefaultCatalogRepository(books, MemoryRules(), store,
            CatalogFetcher { _, _, _, _ -> SourceResult.Success(emptyList()) }, detailsFetcher = fetch)

    private fun chapter(sourceId: String, index: Int, title: String, url: String) = Chapter(
        "book-1", sourceId, "temporary-$index", index, title, url,
    )

    private class MemoryCatalogStore : ChapterCatalogStore {
        private val rows = mutableMapOf<Pair<String, String>, MutableStateFlow<List<Chapter>>>()
        override fun observe(bookId: String, sourceId: String): Flow<List<Chapter>> =
            rows.getOrPut(bookId to sourceId) { MutableStateFlow(emptyList()) }
        override suspend fun replace(bookId: String, sourceId: String, chapters: List<Chapter>) {
            rows.getOrPut(bookId to sourceId) { MutableStateFlow(emptyList()) }.value = chapters
        }
    }

    private class MemoryBooks : BookRepository {
        var book = Book("book-1", "星河|叶子", "星河", "叶子", "https://example.org/old.jpg", "旧简介")
        val mappings = MutableStateFlow(listOf(
            BookSource("book-1", "a", "https://example.org/a"),
            BookSource("book-1", "b", "https://example.org/b"),
        ))
        override suspend fun saveDetails(expectedBook: Book, expectedSource: BookSource, details: BookDetails): Book? {
            if (book != expectedBook || mappings.value.none { it == expectedSource }) return null
            book = expectedBook.copy(coverUrl = details.book.coverUrl?.takeIf(String::isNotBlank) ?: expectedBook.coverUrl,
                introduction = details.book.introduction?.takeIf(String::isNotBlank) ?: expectedBook.introduction)
            return book
        }
        override suspend fun saveSearchHit(hit: SearchHit) = book
        override suspend fun getBook(bookId: String) = book.takeIf { bookId == it.bookId }
        override fun observeSources(bookId: String): Flow<List<BookSource>> = mappings
        override fun observeShelf(): Flow<List<ShelfBook>> = MutableStateFlow(emptyList())
        override suspend fun addToShelf(bookId: String, atEpochMillis: Long) = Unit
        override suspend fun removeFromShelf(bookId: String) = Unit
        override fun progress(bookId: String): Flow<ReadingLocation?> = MutableStateFlow(null)
        override suspend fun saveProgress(location: ReadingLocation) = Unit
    }

    private class MemoryRules : SourceRuleRepository {
        private val values = MutableStateFlow(listOf("a", "b").map { id ->
            StoredSourceRule(id, """{"id":"$id","url":"https://example.org/","name":"$id",
                "search":{"url":"https://example.org/search?q=%s","method":"GET","result":".item","bookName":"a"},
                "toc":{"item":"#toc a"},"chapter":{"title":"h1","content":"#content"}}""")
        })
        override fun observeAll(): Flow<List<StoredSourceRule>> = values
        override suspend fun enabled(): List<StoredSourceRule> = values.value
        override suspend fun upsertImported(rules: List<RuleDto>) = ImportSummary(0, 0, 0)
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean) = Unit
        override suspend fun updateHealth(sourceId: String, health: SourceHealth) = Unit
        override suspend fun deleteUserRule(sourceId: String) = Unit
    }
}
