package com.novelradar.app.feature.reader

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.ChapterDao
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.data.files.AtomicChapterFileStore
import com.novelradar.app.data.repository.ChapterContentFetcher
import com.novelradar.app.data.repository.ChapterPageFetcher
import com.novelradar.app.data.repository.DefaultChapterRepository
import com.novelradar.app.data.repository.RuleBasedChapterContentFetcher
import com.novelradar.app.data.repository.RoomReadingProgressRepository
import com.novelradar.app.domain.model.*
import com.novelradar.app.domain.repository.*
import com.novelradar.app.source.SourceEngine
import com.novelradar.app.source.http.SearchRequestFactory
import com.novelradar.app.source.http.SourceHttpClient
import com.novelradar.app.source.parse.*
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.script.ScriptExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Only persistence/DAO boundaries are faked; HTTP parsing, file repair and progress generations are real. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderPositionRepairIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    @Before fun mainDispatcher() { Dispatchers.setMain(Dispatchers.Unconfined) }
    @After fun resetDispatcher() { Dispatchers.resetMain() }

    @Test fun realHttpRepairToShortSingleChapterCannotReinterpretLegacyBookmark() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<h1>第一章</h1><div id=content>修复单章正文😀</div><a class=next href='/two'>下一页</a>"))
            val files = AtomicChapterFileStore(temporary.root.toPath())
            val oldText = "第一章旧正文😀\n\n第二章旧正文\n\n第三章旧正文"
            val old = files.write("book", "a", "one", oldText)
            val row = chapter("one", 0, server).copy(localContentState = "DOWNLOADED", localFilePath = old.relativePath,
                contentSha256 = old.sha256, contentBytes = old.byteCount)
            val dao = MemoryChapterDao(row, chapter("two", 1, server))
            val original = ReadingLocation("book", "a", "one", 0, oldText.length - 2, 50L, ChapterContentFormat.LEGACY)
            val storage = MemoryBooks(original)
            val vm = reader(dao, files, httpFetcher(dao, server), storage)
            val owner = ViewModelStore().also { it.put("reader", vm) }
            try {
                val state = withTimeout(10_000) { vm.uiState.first { !it.loading && it.content != null } }
                assertThat(state.content!!.boundaryVerified).isTrue()
                assertThat(state.content.content).isEqualTo("修复单章正文😀")
                assertThat(state.offset).isEqualTo(0)
                assertThat(state.positionNeedsReview).isTrue()
                assertThat(state.originalReadingLocation).isEqualTo(original)
                assertThat(storage.value.value).isEqualTo(original)
                assertThat(dao.getChapter("book", "a", "one")!!.contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
                assertThat(files.read("book", "a", "one", old.sha256)).isEqualTo(oldText)
                vm.onPositionChanged(4)
                vm.saveOnStop()
                vm.retry()
                withTimeout(10_000) { vm.uiState.first { !it.loading } }
                assertThat(vm.uiState.value.positionNeedsReview).isTrue()
                assertThat(vm.uiState.value.offset).isEqualTo(0)
                assertThat(vm.close()).isTrue()
                assertThat(storage.value.value).isEqualTo(original)
                assertThat(storage.writes).isEmpty()
                assertThat(server.requestCount).isEqualTo(1)
            } finally { owner.clear() }
        }
    }

    @Test fun realHttpFailureKeepsTemporaryLegacyOffsetViewableWithoutWritesOrConfirmation() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            val files = AtomicChapterFileStore(temporary.root.toPath())
            val text = "第一章旧正文\n第二章旧正文\n第三章旧正文"
            val old = files.write("book", "a", "one", text)
            val row = chapter("one", 0, server).copy(localContentState = "CACHED", localFilePath = old.relativePath,
                contentSha256 = old.sha256, contentBytes = old.byteCount)
            val dao = MemoryChapterDao(row)
            val original = ReadingLocation("book", "a", "one", 0, 12, 50L, ChapterContentFormat.LEGACY)
            val storage = MemoryBooks(original)
            val vm = reader(dao, files, httpFetcher(dao, server), storage)
            val owner = ViewModelStore().also { it.put("reader", vm) }
            try {
                val state = withTimeout(10_000) { vm.uiState.first { !it.loading && it.content != null } }
                assertThat(state.content!!.boundaryVerified).isFalse()
                assertThat(state.offset).isEqualTo(12)
                assertThat(state.positionNeedsReview).isTrue()
                assertThat(vm.confirmPosition()).isFalse()
                vm.onPositionChanged(16)
                vm.saveOnStop()
                assertThat(vm.close()).isTrue()
                assertThat(storage.value.value).isEqualTo(original)
                assertThat(storage.writes).isEmpty()
                assertThat(dao.getChapter("book", "a", "one")).isEqualTo(row)
            } finally { owner.clear() }
        }
    }

    @Test fun queuedTrustedDebounceCannotOverwriteLatestProtectedRecordAfterUnverifiedChapterArrives() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setResponseCode(503))
            val files = AtomicChapterFileStore(temporary.root.toPath())
            val current = files.write("book", "a", "one", "可信正文".repeat(20))
            val legacy = files.write("book", "a", "two", "旧混章正文".repeat(20))
            val dao = MemoryChapterDao(
                chapter("one", 0, server).copy(localContentState = "CACHED", localFilePath = current.relativePath,
                    contentSha256 = current.sha256, contentBytes = current.byteCount, contentFormatVersion = ChapterContentFormat.CURRENT),
                chapter("two", 1, server).copy(localContentState = "CACHED", localFilePath = legacy.relativePath,
                    contentSha256 = legacy.sha256, contentBytes = legacy.byteCount))
            val storage = MemoryBooks(ReadingLocation("book", "a", "one", 0, 8, 50L))
            val vm = reader(dao, files, httpFetcher(dao, server), storage)
            val owner = ViewModelStore().also { it.put("reader", vm) }
            try {
                withTimeout(10_000) { vm.uiState.first { !it.loading && it.content != null } }
                vm.onPositionChanged(32)
                vm.nextChapter()
                val state = withTimeout(10_000) { vm.uiState.first { !it.loading && it.content?.chapter?.chapterId == "two" } }
                val protected = storage.value.value
                val writes = storage.writes.size
                assertThat(protected!!.chapterId).isEqualTo("one")
                assertThat(protected.offset).isEqualTo(32)
                assertThat(state.originalReadingLocation).isEqualTo(protected)
                vm.onPositionChanged(70)
                vm.saveOnStop()
                delay(900) // Exercise the real 750 ms collector across the exact-generation boundary.
                assertThat(storage.value.value).isEqualTo(protected)
                assertThat(storage.writes).hasSize(writes)
                assertThat(vm.close()).isTrue()
            } finally { owner.clear() }
        }
    }

    @Test fun explicitlyChosenSourceScrollEventsRemainNewerThanOtherSourceExactGeneration() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            val files = AtomicChapterFileStore(temporary.root.toPath())
            val current = files.write("book", "a", "one", "可信正文".repeat(20))
            val dao = MemoryChapterDao(chapter("one", 0, server).copy(localContentState = "CACHED",
                localFilePath = current.relativePath, contentSha256 = current.sha256, contentBytes = current.byteCount,
                contentFormatVersion = ChapterContentFormat.CURRENT))
            val otherSource = ReadingLocation("book", "b", "other", 8, 88, 200L)
            val storage = MemoryBooks(otherSource)
            val progress = RoomReadingProgressRepository(storage)
            progress.saveExact(otherSource)
            val vm = reader(dao, files, httpFetcher(dao, server), storage, progress, initialChapterId = "one")
            val owner = ViewModelStore().also { it.put("reader", vm) }
            try {
                withTimeout(10_000) { vm.uiState.first { it.initialEntryCompleted } }
                assertThat(vm.uiState.value.positionNeedsReview).isFalse()
                assertThat(vm.uiState.value.offset).isEqualTo(0)
                val selected = storage.value.value!!
                assertThat(selected.sourceId).isEqualTo("a")
                vm.onPositionChanged(32)
                delay(900)
                assertThat(storage.value.value?.sourceId).isEqualTo("a")
                assertThat(storage.value.value?.offset).isEqualTo(32)
                assertThat(selected.updatedAtEpochMillis).isGreaterThan(200L)
                assertThat(storage.value.value!!.updatedAtEpochMillis).isGreaterThan(selected.updatedAtEpochMillis)
                assertThat(vm.close()).isTrue()
                assertThat(server.requestCount).isEqualTo(0)
            } finally { owner.clear() }
        }
    }

    @Test fun disabledStoredRuleCannotFetchOrConfirmLegacyPosition() = runBlocking {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<h1>第一章</h1><div id=content>修复单章正文</div>"))
            val files = AtomicChapterFileStore(temporary.root.toPath())
            val oldText = "旧混章正文😀".repeat(20)
            val old = files.write("book", "a", "one", oldText)
            val row = chapter("one", 0, server).copy(localContentState = "CACHED", localFilePath = old.relativePath,
                contentSha256 = old.sha256, contentBytes = old.byteCount)
            val dao = MemoryChapterDao(row)
            val original = ReadingLocation("book", "a", "one", 0, 55, 50L, ChapterContentFormat.LEGACY)
            val storage = MemoryBooks(original)
            val vm = reader(dao, files, httpFetcher(dao, server, enabled = false), storage, initialChapterId = "one")
            val owner = ViewModelStore().also { it.put("reader", vm) }
            try {
                val state = withTimeout(10_000) { vm.uiState.first { !it.loading && it.content != null } }
                assertThat(server.requestCount).isEqualTo(0)
                assertThat(state.content!!.boundaryVerified).isFalse()
                assertThat(state.content.content).isEqualTo(oldText)
                assertThat(state.positionNeedsReview).isTrue()
                assertThat(state.originalReadingLocation).isEqualTo(original)
                assertThat(state.initialEntryCompleted).isFalse()
                assertThat(vm.confirmPosition()).isFalse()
                vm.onPositionChanged(16)
                vm.saveOnStop()
                vm.retry()
                withTimeout(10_000) { vm.uiState.first { !it.loading } }
                assertThat(vm.uiState.value.initialEntryCompleted).isFalse()
                assertThat(vm.close()).isTrue()
                assertThat(server.requestCount).isEqualTo(0)
                assertThat(storage.value.value).isEqualTo(original)
                assertThat(storage.writes).isEmpty()
                assertThat(dao.getChapter("book", "a", "one")).isEqualTo(row)
                assertThat(files.read("book", "a", "one", old.sha256)).isEqualTo(oldText)
            } finally { owner.clear() }
        }
    }

    private fun reader(dao: MemoryChapterDao, files: AtomicChapterFileStore, fetcher: ChapterContentFetcher,
        storage: MemoryBooks, progress: ReadingProgressRepository = RoomReadingProgressRepository(storage),
        initialChapterId: String? = null): ReaderViewModel {
        val catalog = object : CatalogRepository {
            override suspend fun book(bookId: String) = storage.getBook(bookId)
            override suspend fun sourceNames() = mapOf("a" to "书源甲")
            override fun sources(bookId: String): Flow<List<BookSource>> = flowOf(emptyList())
            override fun observeChapters(bookId: String, sourceId: String) = flowOf(dao.rows.values.map(::domain))
            override suspend fun catalog(bookId: String, sourceId: String, forceRefresh: Boolean) =
                CatalogResult(dao.getChapters(bookId, sourceId).map(::domain), true)
        }
        val settings = object : ReaderSettingsRepository {
            override val preferences = flowOf(ReaderPreferences(prefetchCount = 0))
            override suspend fun update(transform: (ReaderPreferences) -> ReaderPreferences) = Unit
        }
        return ReaderViewModel("book", "a", initialChapterId, catalog, DefaultChapterRepository(dao, files, fetcher),
            settings, progress, clock = { 100L })
    }

    private fun httpFetcher(dao: ChapterDao, server: MockWebServer, enabled: Boolean = true): ChapterContentFetcher {
        val raw = """{"id":"a","url":"${server.url("/")}","name":"Source",
            "search":{"url":"${server.url("/search?q=%s")}","method":"GET","result":".item","bookName":"a"},
            "toc":{"item":"#toc a"},"chapter":{"title":"h1","content":"#content","pagination":true,"nextPage":"a.next"}}"""
        val stored = StoredSourceRule("a", raw, enabled = enabled, allowCleartext = true)
        val selector = HtmlSelector()
        val scripts = object : ScriptExecutor {
            override suspend fun transform(input: String, script: String): SourceResult<String> = SourceResult.Success(input)
        }
        val engine = SourceEngine(SearchRequestFactory(), SourceHttpClient(), SearchPageParser(selector, scripts),
            BookPageParser(selector, scripts), TocPageParser(selector, scripts), ChapterPageParser(selector, scripts), selector, scripts)
        return RuleBasedChapterContentFetcher(MemoryRules(stored),
            ChapterPageFetcher { rule, sourceId, url, allowed, context -> engine.chapter(rule, sourceId, url, allowed, context) },
            chapterUrls = { bookId, sourceId -> dao.getChapters(bookId, sourceId).mapNotNull { it.url } })
    }

    private class MemoryRules(private val stored: StoredSourceRule) : SourceRuleRepository {
        override fun observeAll(): Flow<List<StoredSourceRule>> = flowOf(listOf(stored))
        override suspend fun enabled() = listOf(stored).filter { it.enabled }
        override suspend fun upsertImported(rules: List<RuleDto>) = ImportSummary(0, 0, 0)
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean) = Unit
        override suspend fun updateHealth(sourceId: String, health: SourceHealth) = Unit
        override suspend fun deleteUserRule(sourceId: String) = Unit
    }

    private fun chapter(id: String, index: Int, server: MockWebServer) =
        ChapterEntity("book", "a", id, index, "第${index + 1}章", server.url("/$id").toString())
    private fun domain(row: ChapterEntity) = Chapter(row.bookId, row.sourceId, row.chapterId, row.chapterIndex, row.title, row.url)

    private class MemoryChapterDao(vararg initial: ChapterEntity) : ChapterDao {
        val rows = initial.associateBy { it.chapterId }.toMutableMap()
        override suspend fun upsertAll(chapters: List<ChapterEntity>) { chapters.forEach { upsert(it) } }
        override suspend fun upsert(chapter: ChapterEntity) { rows[chapter.chapterId] = chapter }
        override fun observeChapters(bookId: String, sourceId: String): Flow<List<ChapterEntity>> =
            flowOf(rows.values.filter { it.bookId == bookId && it.sourceId == sourceId })
        override suspend fun getChapters(bookId: String, sourceId: String) =
            rows.values.filter { it.bookId == bookId && it.sourceId == sourceId }.sortedBy { it.chapterIndex }
        override suspend fun getChapter(bookId: String, sourceId: String, chapterId: String) =
            rows[chapterId]?.takeIf { it.bookId == bookId && it.sourceId == sourceId }
        override suspend fun updateStoredContent(bookId: String, sourceId: String, chapterId: String, expectedUrl: String,
            state: String, path: String, sha256: String, byteCount: Long, contentFormatVersion: Int): Int {
            val old = getChapter(bookId, sourceId, chapterId)?.takeIf { it.url == expectedUrl } ?: return 0
            rows[chapterId] = old.copy(localContentState = if (old.localContentState == "DOWNLOADED") "DOWNLOADED" else state,
                localFilePath = path, contentSha256 = sha256, contentBytes = byteCount, contentFormatVersion = contentFormatVersion)
            return 1
        }
        override suspend fun deleteForSource(bookId: String, sourceId: String) {
            rows.entries.removeAll { it.value.bookId == bookId && it.value.sourceId == sourceId }
        }
        override suspend fun clearStoredMetadata(bookId: String) = Unit
    }

    private class MemoryBooks(original: ReadingLocation) : BookRepository {
        val value = MutableStateFlow<ReadingLocation?>(original)
        val writes = mutableListOf<ReadingLocation>()
        override suspend fun saveSearchHit(hit: SearchHit): Book = error("Not used by the reader")
        override suspend fun getBook(bookId: String) = Book("book", "key", "星河旅人", "叶子")
        override fun observeSources(bookId: String): Flow<List<BookSource>> = flowOf(emptyList())
        override fun observeShelf(): Flow<List<ShelfBook>> = flowOf(emptyList())
        override suspend fun addToShelf(bookId: String, atEpochMillis: Long) = Unit
        override suspend fun removeFromShelf(bookId: String) = Unit
        override fun progress(bookId: String): Flow<ReadingLocation?> = value
        override suspend fun saveProgress(location: ReadingLocation) { writes += location; value.value = location }
    }
}
