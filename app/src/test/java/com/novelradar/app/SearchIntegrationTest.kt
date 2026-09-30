package com.novelradar.app

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.ImportSummary
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.repository.ShelfBook
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.domain.search.AggregateSearchUseCase
import com.novelradar.app.domain.search.SourceSearch
import com.novelradar.app.source.SourceEngine
import com.novelradar.app.source.http.SearchRequestFactory
import com.novelradar.app.source.http.SourceHttpClient
import com.novelradar.app.source.parse.BookPageParser
import com.novelradar.app.source.parse.ChapterPageParser
import com.novelradar.app.source.parse.HtmlSelector
import com.novelradar.app.source.parse.SearchPageParser
import com.novelradar.app.source.parse.TocPageParser
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.script.ScriptExecutor
import com.novelradar.app.domain.model.SourceResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Test

class SearchIntegrationTest {
    @Test fun oneLiveFixtureAndOneTimeoutStillPersistBookMapping() = runBlocking {
        MockWebServer().use { healthy -> MockWebServer().use { stalled ->
            healthy.start()
            stalled.start()
            healthy.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<div class=result><a class=name href='/book/7'>星河旅人</a><span class=author>叶子</span></div>"))
            stalled.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val rules = MemoryRules(listOf(rule("working", healthy), rule("stalled", stalled)))
            val books = MemoryBooks()
            val engine = engine()
            val search = AggregateSearchUseCase(rules, SourceSearch { stored, parsed, keyword ->
                engine.search(parsed, stored.sourceId, keyword, stored.allowCleartext)
            })
            val snapshots = withTimeout(10_000) { search.search("星河旅人").toList() }
            val completed = snapshots.last()
            assertThat(snapshots.any { it.completedSources == 1 }).isTrue()
            assertThat(completed.totalSources).isEqualTo(2)
            assertThat(completed.completedSources).isEqualTo(2)
            assertThat(completed.results).hasSize(1)
            assertThat(completed.failures["stalled"]).isEqualTo(SourceFailure.Timeout)
            assertThat(completed.results.single().sources.map { it.sourceId }).containsExactly("working")

            val saved = books.saveSearchHit(completed.results.single().bestHit)
            assertThat(books.getBook(saved.bookId)?.bookName).isEqualTo("星河旅人")
            assertThat(books.observeSources(saved.bookId).first().map { it.sourceId }).containsExactly("working")
            assertThat(healthy.requestCount).isEqualTo(1)
            assertThat(stalled.requestCount).isEqualTo(1)
        } }
    }

    private fun rule(id: String, server: MockWebServer): StoredSourceRule {
        val base = server.url("/")
        val search = server.url("/search?q=%s")
        val json = """{"id":"$id","name":"$id","url":"$base",
            "search":{"url":"$search","method":"GET","result":".result","bookName":".name","author":".author"},
            "toc":{"item":"#toc a"},"chapter":{"title":"h1","content":"#content"}}"""
        return StoredSourceRule(id, json, allowCleartext = true)
    }

    private fun engine(): SourceEngine {
        val selector = HtmlSelector()
        val scripts = object : ScriptExecutor {
            override suspend fun transform(input: String, script: String): SourceResult<String> = SourceResult.Success(input)
        }
        return SourceEngine(
            SearchRequestFactory(), SourceHttpClient(timeoutMillis = 300),
            SearchPageParser(selector, scripts), BookPageParser(selector, scripts),
            TocPageParser(selector, scripts), ChapterPageParser(selector, scripts), selector, scripts,
        )
    }

    private class MemoryRules(initial: List<StoredSourceRule>) : SourceRuleRepository {
        private val rows = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<StoredSourceRule>> = rows
        override suspend fun enabled() = rows.value.filter { it.enabled }
        override suspend fun upsertImported(rules: List<RuleDto>) = ImportSummary(0, 0, 0)
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean) = Unit
        override suspend fun updateHealth(sourceId: String, health: SourceHealth) = Unit
        override suspend fun deleteUserRule(sourceId: String) = Unit
    }

    private class MemoryBooks : BookRepository {
        private val stored = mutableMapOf<String, Book>()
        private val mappings = mutableMapOf<String, MutableList<BookSource>>()
        override suspend fun saveSearchHit(hit: SearchHit): Book {
            val book = Book(hit.normalizedBookKey, hit.normalizedBookKey, hit.bookName, hit.author)
            stored[book.bookId] = book
            val sources = mappings.getOrPut(book.bookId) { mutableListOf() }
            sources.removeAll { it.sourceId == hit.sourceId }
            sources += BookSource(book.bookId, hit.sourceId, hit.detailUrl)
            return book
        }
        override suspend fun getBook(bookId: String) = stored[bookId]
        override fun observeSources(bookId: String): Flow<List<BookSource>> = MutableStateFlow(mappings[bookId].orEmpty().toList())
        override fun observeShelf(): Flow<List<ShelfBook>> = MutableStateFlow(emptyList())
        override suspend fun addToShelf(bookId: String, atEpochMillis: Long) = Unit
        override suspend fun removeFromShelf(bookId: String) = Unit
        override fun progress(bookId: String): Flow<ReadingLocation?> = MutableStateFlow(null)
        override suspend fun saveProgress(location: ReadingLocation) = Unit
    }
}
