package com.novelradar.app.feature.book

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.repository.DefaultCatalogRepository
import com.novelradar.app.data.repository.RoomBookRepository
import com.novelradar.app.data.repository.RoomBookRepositoryTest
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookIdentityEvidence
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.CatalogFetcher
import com.novelradar.app.domain.repository.CatalogRepository
import com.novelradar.app.domain.repository.ChapterCatalogStore
import com.novelradar.app.domain.repository.DetailsFetcher
import com.novelradar.app.domain.repository.DetailsResult
import com.novelradar.app.domain.repository.ImportSummary
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.source.SourceEngine
import com.novelradar.app.source.http.SearchRequestFactory
import com.novelradar.app.source.http.SourceHttpClient
import com.novelradar.app.source.parse.BookPageParser
import com.novelradar.app.source.parse.ChapterPageParser
import com.novelradar.app.source.parse.HtmlSelector
import com.novelradar.app.source.parse.SearchPageParser
import com.novelradar.app.source.parse.TocPageParser
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.script.QuickJsScriptExecutor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test

/** Real HTTP, QuickJS, parsers, repository and ViewModel; only storage and the remote site are fixtures. */
@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailIdentityIntegrationTest {
    @get:Rule val main = BookDetailViewModelTest.MainDispatcherRule()

    @Test fun introScriptFailureCannotHideActualWrongTitleOrEnableReading() = runTest {
        MockWebServer().use { server ->
            server.start()
            val fixture = fixture(server, INTRO_SCRIPT_RULE)
            server.enqueue(page("<h1>另一部作品</h1><div id=author>叶子</div><div id=intro>简介</div>"))
            val model = fixture.model()
            backgroundScope.launch { model.uiState.collect {} }
            val state = loaded(model)

            assertThat(state.sourceIdentityMismatch).isTrue()
            assertThat(fixture.results.single().identity).isEqualTo(BookIdentityEvidence("另一部作品", "叶子"))
            assertThat(model.readingTarget()).isNull()
            assertThat(state.chapters).hasSize(1)
            assertThat(fixture.books.getBook(fixture.book.bookId)).isEqualTo(fixture.book)
        }
    }

    @Test fun failedRequiredAuthorCannotHideActualWrongTitle() = runTest {
        MockWebServer().use { server ->
            server.start()
            val fixture = fixture(server, """{"bookName":"h1","author":"#absent"}""")
            server.enqueue(page("<h1>另一部作品</h1>"))
            val model = fixture.model()
            backgroundScope.launch { model.uiState.collect {} }
            val state = loaded(model)

            assertThat(state.sourceIdentityMismatch).isTrue()
            assertThat(fixture.results.single().identity).isEqualTo(BookIdentityEvidence(title = "另一部作品"))
            assertThat(model.readingTarget()).isNull()
        }
    }

    @Test fun failedRequiredTitleStillChecksActualWrongAuthor() = runTest {
        MockWebServer().use { server ->
            server.start()
            val fixture = fixture(server, """{"bookName":"#absent","author":"#author"}""")
            server.enqueue(page("<div id=author>其他作者</div>"))
            val model = fixture.model()
            backgroundScope.launch { model.uiState.collect {} }
            val state = loaded(model)

            assertThat(state.sourceIdentityMismatch).isTrue()
            assertThat(fixture.results.single().identity).isEqualTo(BookIdentityEvidence(author = "其他作者"))
            assertThat(model.readingTarget()).isNull()
        }
    }

    @Test fun entirelyUnknownParseFailureKeepsCachedCatalogReadableWithoutSavingMetadata() = runTest {
        MockWebServer().use { server ->
            server.start()
            val fixture = fixture(server, """{"bookName":"#absent","author":"#absent"}""")
            server.enqueue(page("<div id=intro>不可用简介</div>"))
            val model = fixture.model()
            backgroundScope.launch { model.uiState.collect {} }
            val state = loaded(model)

            assertThat(state.sourceIdentityMismatch).isFalse()
            assertThat(state.detailsWarning).isEqualTo(SourceFailure.Parse("book.bookName", "#absent"))
            assertThat(fixture.results.single().identity).isEqualTo(BookIdentityEvidence())
            assertThat(model.readingTarget()).isEqualTo(BookReadTarget("a", null))
            assertThat(fixture.books.getBook(fixture.book.bookId)).isEqualTo(fixture.book)
        }
    }

    @Test fun matchingPartialDetailsCannotClearPreviouslyConfirmedMismatch() = runTest {
        MockWebServer().use { server ->
            server.start()
            val fixture = fixture(server, INTRO_SCRIPT_RULE)
            server.enqueue(page("<h1>另一部作品</h1><div id=author>叶子</div><div id=intro>简介</div>"))
            val model = fixture.model()
            backgroundScope.launch { model.uiState.collect {} }
            loaded(model)
            server.enqueue(page("<h1>星河</h1><div id=author>叶子</div><div id=intro>较新简介</div>"))
            model.refresh()
            runCurrent()
            val state = loaded(model)

            assertThat(fixture.results.last().identity).isEqualTo(BookIdentityEvidence("星河", "叶子"))
            assertThat(fixture.results.last().warning).isNotNull()
            assertThat(state.sourceIdentityMismatch).isTrue()
            assertThat(model.readingTarget()).isNull()
            assertThat(fixture.books.getBook(fixture.book.bookId)).isEqualTo(fixture.book)
        }
    }

    @Test fun changedMappingDuringConflictingRequestDiscardsExpiredIdentity() = runTest {
        MockWebServer().use { server ->
            server.start()
            val fixture = fixture(server, IDENTITY_RULE)
            server.enqueue(page("<h1>另一部作品</h1><div id=author>叶子</div>"))
            fixture.afterFetch = { fixture.books.saveSearchHit(fixture.hit.copy(detailUrl = server.url("/replacement").toString())) }
            val result = fixture.catalog.refreshDetails(fixture.book.bookId, "a")

            assertThat(result.identityMismatch).isFalse()
            assertThat(result.identity).isNull()
            assertThat(result.warning).isEqualTo(SourceFailure.Unexpected("Details changed during refresh"))
            assertThat(result.book).isEqualTo(fixture.book)
        }
    }

    @Test fun changedBookDuringConflictingRequestReturnsCurrentBookWithoutExpiredEvidence() = runTest {
        MockWebServer().use { server ->
            server.start()
            val fixture = fixture(server, IDENTITY_RULE)
            server.enqueue(page("<h1>另一部作品</h1><div id=author>叶子</div>"))
            fixture.afterFetch = { fixture.books.saveSearchHit(fixture.hit.copy(introduction = "较新的本地简介")) }
            val result = fixture.catalog.refreshDetails(fixture.book.bookId, "a")

            assertThat(result.identityMismatch).isFalse()
            assertThat(result.identity).isNull()
            assertThat(result.book?.introduction).isEqualTo("较新的本地简介")
            assertThat(fixture.books.getBook(fixture.book.bookId)?.introduction).isEqualTo("较新的本地简介")
        }
    }

    @Test fun expiredMatchingPageCannotClearConfirmedSessionMismatch() = runTest {
        MockWebServer().use { server ->
            server.start()
            val fixture = fixture(server, IDENTITY_RULE)
            server.enqueue(page("<h1>另一部作品</h1><div id=author>叶子</div>"))
            val model = fixture.model()
            backgroundScope.launch { model.uiState.collect {} }
            assertThat(loaded(model).sourceIdentityMismatch).isTrue()
            server.enqueue(page("<h1>星河</h1><div id=author>叶子</div>"))
            fixture.afterFetch = { fixture.books.saveSearchHit(fixture.hit.copy(detailUrl = server.url("/replacement").toString())) }
            model.refresh()
            runCurrent()
            val state = loaded(model)

            assertThat(fixture.results.last().identity).isNull()
            assertThat(fixture.results.last().identityMismatch).isFalse()
            assertThat(state.sourceIdentityMismatch).isTrue()
            assertThat(model.readingTarget()).isNull()
        }
    }

    @Test fun expiredPartialMatchCannotPublishEvidenceOrClearConfirmedMismatch() = runTest {
        MockWebServer().use { server ->
            server.start()
            val fixture = fixture(server, INTRO_SCRIPT_RULE)
            server.enqueue(page("<h1>另一部作品</h1><div id=author>叶子</div><div id=intro>简介</div>"))
            val model = fixture.model()
            backgroundScope.launch { model.uiState.collect {} }
            assertThat(loaded(model).sourceIdentityMismatch).isTrue()
            server.enqueue(page("<h1>星河</h1><div id=author>叶子</div><div id=intro>简介</div>"))
            fixture.afterFetch = { fixture.books.saveSearchHit(fixture.hit.copy(detailUrl = server.url("/replacement").toString())) }
            model.refresh()
            runCurrent()
            val state = loaded(model)

            assertThat(fixture.results.last().identity).isNull()
            assertThat(fixture.results.last().warning).isEqualTo(SourceFailure.Unexpected("Details changed during refresh"))
            assertThat(state.sourceIdentityMismatch).isTrue()
            assertThat(model.readingTarget()).isNull()
        }
    }

    private suspend fun loaded(model: BookDetailViewModel) = model.uiState.first {
        it.selectedSourceId == "a" && !it.loading && !it.detailsLoading
    }

    private suspend fun fixture(server: MockWebServer, bookRule: String): Fixture {
        val books = RoomBookRepository(RoomBookRepositoryTest.FakeBookDao())
        val hit = SearchHit("a", "星河", "叶子", server.url("/book/1").toString(),
            coverUrl = "https://example.org/old.jpg", introduction = "已保存简介")
        return Fixture(server, bookRule, books, hit, books.saveSearchHit(hit))
    }

    private class Fixture(server: MockWebServer, bookRule: String, val books: RoomBookRepository, val hit: SearchHit, val book: Book) {
        var afterFetch: suspend () -> Unit = {}
        val results = mutableListOf<DetailsResult>()
        private val selector = HtmlSelector()
        private val scripts = QuickJsScriptExecutor()
        private val engine = SourceEngine(SearchRequestFactory(), SourceHttpClient(),
            SearchPageParser(selector, scripts), BookPageParser(selector, scripts), TocPageParser(selector, scripts),
            ChapterPageParser(selector, scripts), selector, scripts)
        private val chapters = MutableStateFlow(listOf(Chapter(book.bookId, "a", "saved-chapter", 0, "已保存章节", "https://example.org/chapter/1")))
        private val stored = StoredSourceRule("a", """{"id":"a","url":"${server.url("/")}","name":"测试书源",
            "book":$bookRule,"toc":{"item":"#chapters a"},"chapter":{"title":"h1","content":"#content"}}""", allowCleartext = true)
        private val rules = object : SourceRuleRepository {
            override fun observeAll(): Flow<List<StoredSourceRule>> = MutableStateFlow(listOf(stored))
            override suspend fun enabled() = listOf(stored)
            override suspend fun upsertImported(rules: List<RuleDto>) = ImportSummary(0, 0, 0)
            override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
            override suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean) = Unit
            override suspend fun updateHealth(sourceId: String, health: SourceHealth) = Unit
            override suspend fun deleteUserRule(sourceId: String) = Unit
        }
        private val store = object : ChapterCatalogStore {
            override fun observe(bookId: String, sourceId: String): Flow<List<Chapter>> = chapters
            override suspend fun replace(bookId: String, sourceId: String, chapters: List<Chapter>) { this@Fixture.chapters.value = chapters }
        }
        private val repository = DefaultCatalogRepository(books, rules, store,
            CatalogFetcher { _, _, _, _ -> SourceResult.Success(chapters.value) },
            detailsFetcher = DetailsFetcher { rule, source, hit, id ->
                val parsed = engine.bookDetails(rule, source.sourceId, hit, id, source.allowCleartext)
                afterFetch()
                parsed
            })
        val catalog = object : CatalogRepository by repository {
            override suspend fun refreshDetails(bookId: String, sourceId: String) = repository.refreshDetails(bookId, sourceId).also { results += it }
        }
        fun model() = BookDetailViewModel(book.bookId, catalog, books)
    }

    private fun page(body: String) = MockResponse().addHeader("Content-Type", "text/html; charset=utf-8").setBody(body)

    private companion object {
        const val IDENTITY_RULE = """{"bookName":"h1","author":"#author"}"""
        const val INTRO_SCRIPT_RULE = """{"bookName":"h1","author":"#author","intro":"#intro@js:r=missingFunction(r)"}"""
    }
}
