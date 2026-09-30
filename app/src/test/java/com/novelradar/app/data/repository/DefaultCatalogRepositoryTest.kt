package com.novelradar.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.CatalogFetcher
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
import org.junit.Test

class DefaultCatalogRepositoryTest {
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
        private val book = Book("book-1", "星河|叶子", "星河", "叶子")
        override suspend fun saveSearchHit(hit: SearchHit) = book
        override suspend fun getBook(bookId: String) = book.takeIf { bookId == it.bookId }
        override fun observeSources(bookId: String): Flow<List<BookSource>> = MutableStateFlow(listOf(
            BookSource("book-1", "a", "https://example.org/a"),
            BookSource("book-1", "b", "https://example.org/b"),
        ))
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
