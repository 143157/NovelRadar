package com.novelradar.app.feature.reader

import androidx.lifecycle.ViewModelStore
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.ChapterDao
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.data.files.AtomicChapterFileStore
import com.novelradar.app.data.repository.*
import com.novelradar.app.domain.model.*
import com.novelradar.app.domain.repository.*
import com.novelradar.app.source.SourceEngine
import com.novelradar.app.source.http.SearchRequestFactory
import com.novelradar.app.source.http.SourceHttpClient
import com.novelradar.app.source.parse.*
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.script.ScriptExecutor
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real stored-rule execution, HTTP boundary parsing, atomic cache and exact/debounced progress. */
@OptIn(ExperimentalCoroutinesApi::class)
class ReaderContinuationIntegrationTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var mainDispatcher: ExecutorCoroutineDispatcher
    @Before fun mainDispatcher() {
        mainDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "reader-continuation-main").apply { isDaemon = true }
        }.asCoroutineDispatcher()
        Dispatchers.setMain(mainDispatcher)
    }
    @After fun resetDispatcher() { Dispatchers.resetMain(); mainDispatcher.close() }

    @Test(timeout = 15000) fun preparedNeighborStaysPassiveAndCrossingWritesWhileAnotherRequestIsStalled() = runBlocking(Dispatchers.Main) {
        fixture().use { f ->
            withTimeout(10_000) {
                f.hold("two"); f.hold("three")
                val vm = f.start()
                f.awaitActive("one")
                f.awaitRequested("two"); f.awaitRequested("three")
                assertThat(vm.saveExact()).isTrue() // Quiescent trusted initial baseline.
                val baseline = f.books.writes.toList()
                val anchor = vm.uiState.value.restorationVersion
                f.release("two")
                vm.uiState.first { it.verifiedWindow.any { body -> body.chapter.chapterId == "two" } }
                assertThat(f.books.writes).isEqualTo(baseline)
                assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("one")
                assertThat(vm.uiState.value.catalogPosition).isEqualTo(0)
                assertThat(vm.uiState.value.offset).isEqualTo(8)
                assertThat(vm.uiState.value.restorationVersion).isEqualTo(anchor)
                assertThat(vm.uiState.value.content?.content).isEqualTo("第一章正文内容😀")
                vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("two", 4))
                f.books.value.first { it?.chapterId == "two" && it.offset == 4 }
                assertThat(vm.uiState.value.catalogPosition).isEqualTo(1)
                assertThat(vm.uiState.value.offset).isEqualTo(4)
                assertThat(vm.uiState.value.restorationVersion).isEqualTo(anchor)
                assertThat(f.count("one")).isEqualTo(1)
                assertThat(f.count("two")).isEqualTo(1)
                assertThat(f.count("three")).isEqualTo(1)
                assertThat(f.count("wrong-page")).isEqualTo(0)
                assertThat(f.books.value.value?.contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
                // The stalled unrelated chapter never delays a retained cached body.
                val cached = f.repository.loadChapter("book", "a", "two") as SourceResult.Success
                assertThat(cached.value.content).isEqualTo("第二章正文内容😀")
                assertThat(f.count("two")).isEqualTo(1)
            }
        }
    }

    @Test(timeout = 15000) fun selectingInflightUpcomingTargetSharesOneRealFetchWithoutRestart() = runBlocking(Dispatchers.Main) {
        fixture().use { f ->
            withTimeout(10_000) {
                f.hold("two"); f.hold("three")
                val vm = f.start()
                f.awaitActive("one")
                f.awaitRequested("two"); f.awaitRequested("three")
                vm.onPositionChanged(9)
                vm.selectChapter("two")
                vm.uiState.first { it.loading }
                f.release("two")
                f.awaitActive("two")
                assertThat(vm.uiState.value.offset).isEqualTo(0)
                assertThat(vm.uiState.value.positionNeedsReview).isFalse()
                assertThat(f.books.writes.last { it.chapterId == "one" }.offset).isEqualTo(9)
                assertThat(f.books.value.value?.chapterId).isEqualTo("two")
                assertThat(f.count("two")).isEqualTo(1)
                assertThat(f.count("three")).isEqualTo(1)
                assertThat(vm.uiState.value.content?.content).isEqualTo("第二章正文内容😀")
            }
        }
    }

    @Test(timeout = 15000) fun sameChapterVisibleMovementUsesReal750MillisecondDebounce() = runBlocking(Dispatchers.Main) {
        fixture(prefetchCount = 0).use { f ->
            withTimeout(10_000) {
                val vm = f.start()
                f.awaitActive("one")
                assertThat(vm.saveExact()).isTrue()
                val baseline = f.books.writes.size
                vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("one", 4))
                assertThat(f.books.writes).hasSize(baseline)
                f.books.value.first { it?.offset == 4 }
                assertThat(f.books.writes).hasSize(baseline + 1)
                assertThat(f.count("two")).isEqualTo(0)
                assertThat(f.count("three")).isEqualTo(0)
                assertThat(vm.close()).isTrue()
            }
        }
    }

    @Test(timeout = 15000) fun zeroAndMeteredPolicyNeverBecomeAutomaticForegroundRequestsButCachedPreparationWorks() = runBlocking(Dispatchers.Main) {
        for ((count, metered) in listOf(0 to false, 2 to true)) {
            fixture(prefetchCount = count, metered = metered).use { f ->
                withTimeout(10_000) {
                    f.cache("two", "第二章缓存😀")
                    val vm = f.start()
                    f.awaitActive("one")
                    if (count > 0) vm.uiState.first { it.verifiedWindow.any { body -> body.chapter.chapterId == "two" } }
                    vm.updatePreferences { it.copy(fontSizeSp = 28f, mode = ReaderMode.PAGED) }
                    vm.uiState.first { it.preferences.mode == ReaderMode.PAGED }
                    assertThat(f.count("two")).isEqualTo(0)
                    assertThat(f.count("three")).isEqualTo(0)
                    assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("one")
                    assertThat(f.books.value.value?.chapterId).isEqualTo("one")
                    assertThat(vm.close()).isTrue()
                }
            }
        }
    }

    @Test(timeout = 15000) fun disabledStoredRulePreparationKeepsTrustedCachedActiveUsableWithoutSourceSwitch() = runBlocking(Dispatchers.Main) {
        fixture(enabled = false).use { f ->
            withTimeout(10_000) {
                f.cache("one", "第一章缓存正文😀")
                val vm = f.start()
                f.awaitActive("one")
                assertThat(vm.uiState.value.content?.content).isEqualTo("第一章缓存正文😀")
                assertThat(vm.uiState.value.content?.chapter?.sourceId).isEqualTo("a")
                assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).containsExactly("one")
                assertThat(f.server.requestCount).isEqualTo(0)
                vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("two", 4))
                assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("one")
                assertThat(vm.close()).isTrue()
                assertThat(f.server.requestCount).isEqualTo(0)
            }
        }
    }

    @Test(timeout = 15000) fun closingCancelsPreparationAndLateHttpResponseCannotAppendOrWrite() = runBlocking(Dispatchers.Main) {
        fixture().use { f ->
            withTimeout(10_000) {
                f.hold("two"); f.hold("three")
                val vm = f.start()
                f.awaitActive("one")
                f.awaitRequested("two"); f.awaitRequested("three")
                assertThat(vm.close()).isTrue()
                val writes = f.books.writes.toList()
                f.release("two"); f.release("three")
                assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).containsExactly("one")
                vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("two", 7))
                assertThat(f.books.writes).isEqualTo(writes)
                assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("one")
            }
        }
    }

    @Test(timeout = 15000) fun movementDuringSuspendedCrossingSaveStillReachesRealDebouncedProgress() = runBlocking(Dispatchers.Main) {
        fixture().use { f ->
            withTimeout(10_000) {
                val vm = f.start()
                f.awaitActive("one")
                vm.uiState.first { it.verifiedWindow.any { body -> body.chapter.chapterId == "two" } }
                assertThat(vm.saveExact()).isTrue()
                val gate = CompletableDeferred<Unit>()
                f.books.saveGate = gate
                try {
                    vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("two", 2))
                    f.books.saveStarted.await()
                    vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("two", 6))
                    f.books.saveGate = null
                    gate.complete(Unit)
                    f.books.value.first { it?.chapterId == "two" && it.offset == 2 }
                    delay(900)
                    assertThat(f.books.value.value?.offset).isEqualTo(6)
                } finally { f.books.saveGate = null; gate.complete(Unit) }
            }
        }
    }

    private fun fixture(prefetchCount: Int = 2, metered: Boolean = false, enabled: Boolean = true) =
        Fixture(AtomicChapterFileStore(temporary.newFolder().toPath()), prefetchCount, metered, enabled)

    private suspend fun Fixture.use(block: suspend (Fixture) -> Unit) {
        try { block(this) } finally { close() }
    }

    private class Fixture(val files: AtomicChapterFileStore, count: Int, metered: Boolean, enabled: Boolean) {
        val server = MockWebServer()
        private val gates = ConcurrentHashMap<String, CountDownLatch>()
        private val requests = ConcurrentHashMap<String, Int>()
        private val requested = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
        val books = MemoryBooks()
        private val preferenceState = MutableStateFlow(ReaderPreferences(prefetchCount = count))
        val settings = object : ReaderSettingsRepository {
            override val preferences = preferenceState
            override suspend fun update(transform: (ReaderPreferences) -> ReaderPreferences) {
                preferences.value = transform(preferences.value).normalized()
            }
        }
        val dao: MemoryChapterDao
        val repository: DefaultChapterRepository
        private val catalog: CatalogRepository
        private var vm: ReaderViewModel? = null
        private val owner = ViewModelStore()

        init {
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val id = request.path?.removePrefix("/") ?: ""
                    requests.merge(id, 1, Int::plus)
                    requested.computeIfAbsent(id) { CompletableDeferred() }.complete(Unit)
                    if (gates[id]?.await(10, TimeUnit.SECONDS) == false) return MockResponse().setResponseCode(504)
                    val index = listOf("one", "two", "three").indexOf(id)
                    if (index < 0) return MockResponse().setResponseCode(404)
                    val label = listOf("第一章", "第二章", "第三章")[index]
                    val next = listOf("two", "three", null)[index]?.let { "<a class=next href='/$it'>下一页</a>" } ?: ""
                    return MockResponse().addHeader("Content-Type", "text/html; charset=utf-8")
                        .setBody("<h1>$label</h1><div id=content>${label}正文内容😀</div>$next")
                }
            }
            server.start()
            dao = MemoryChapterDao(*listOf("one", "two", "three").mapIndexed { index, id ->
                ChapterEntity("book", "a", id, index, "第${index + 1}章", server.url("/$id").toString()) }.toTypedArray())
            val raw = """{"id":"a","url":"${server.url("/")}","name":"Source",
                "search":{"url":"${server.url("/search?q=%s")}","method":"GET","result":".item","bookName":"a"},
                "toc":{"item":"#toc a"},"chapter":{"title":"h1","content":"#content","pagination":true,"nextPage":"a.next"}}"""
            val rules = MemoryRules(StoredSourceRule("a", raw, enabled = enabled, allowCleartext = true))
            val selector = HtmlSelector()
            val scripts = object : ScriptExecutor {
                override suspend fun transform(input: String, script: String): SourceResult<String> = SourceResult.Success(input)
            }
            val engine = SourceEngine(SearchRequestFactory(), SourceHttpClient(), SearchPageParser(selector, scripts),
                BookPageParser(selector, scripts), TocPageParser(selector, scripts), ChapterPageParser(selector, scripts), selector, scripts)
            val fetcher = RuleBasedChapterContentFetcher(rules,
                ChapterPageFetcher { rule, sourceId, url, allowed, context -> engine.chapter(rule, sourceId, url, allowed, context) },
                chapterUrls = { bookId, sourceId -> dao.getChapters(bookId, sourceId).mapNotNull { it.url } })
            repository = DefaultChapterRepository(dao, files, fetcher, isMeteredNetwork = { metered },
                allowMeteredPrefetch = { preferenceState.value.allowMeteredPrefetch })
            catalog = object : CatalogRepository {
                override suspend fun book(bookId: String) = books.getBook(bookId)
                override suspend fun sourceNames() = mapOf("a" to "书源甲")
                override fun sources(bookId: String): Flow<List<BookSource>> = flowOf(emptyList())
                override fun observeChapters(bookId: String, sourceId: String) = flowOf(dao.rows.values.map(::domain))
                override suspend fun catalog(bookId: String, sourceId: String, forceRefresh: Boolean) =
                    CatalogResult(dao.getChapters(bookId, sourceId).map(::domain), true)
            }
        }

        fun start(): ReaderViewModel = ReaderViewModel("book", "a", null, catalog, repository, settings,
            RoomReadingProgressRepository(books), clock = { 100L }).also { vm = it; owner.put("reader", it) }
        suspend fun awaitActive(id: String) = requireNotNull(vm).uiState.first {
            !it.loading && it.content?.chapter?.chapterId == id && !it.positionNeedsReview }
        suspend fun awaitRequested(id: String) = requested.computeIfAbsent(id) { CompletableDeferred() }.await()
        fun hold(id: String) { gates[id] = CountDownLatch(1) }
        fun release(id: String) { gates[id]?.countDown() }
        fun count(id: String) = requests[id] ?: 0
        suspend fun cache(id: String, text: String) {
            val stored = files.write("book", "a", id, text)
            val row = dao.getChapter("book", "a", id)!!
            dao.upsert(row.copy(localContentState = "CACHED", localFilePath = stored.relativePath,
                contentSha256 = stored.sha256, contentBytes = stored.byteCount, contentFormatVersion = ChapterContentFormat.CURRENT))
        }
        suspend fun close() = withContext(NonCancellable) {
            gates.values.forEach { it.countDown() }
            owner.clear()
            try { withTimeout(2_000) { vm?.close() } }
            finally { withContext(Dispatchers.IO) { server.close() } }
        }
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

    private class MemoryChapterDao(vararg initial: ChapterEntity) : ChapterDao {
        val rows = ConcurrentHashMap(initial.associateBy { it.chapterId })
        override suspend fun upsertAll(chapters: List<ChapterEntity>) { chapters.forEach { upsert(it) } }
        override suspend fun upsert(chapter: ChapterEntity) { rows[chapter.chapterId] = chapter }
        override fun observeChapters(bookId: String, sourceId: String): Flow<List<ChapterEntity>> = flowOf(rows.values.toList())
        override suspend fun getChapters(bookId: String, sourceId: String) =
            rows.values.filter { it.bookId == bookId && it.sourceId == sourceId }.sortedBy { it.chapterIndex }
        override suspend fun getChapter(bookId: String, sourceId: String, chapterId: String) =
            rows[chapterId]?.takeIf { it.bookId == bookId && it.sourceId == sourceId }
        override suspend fun updateStoredContent(bookId: String, sourceId: String, chapterId: String, expectedUrl: String,
            state: String, path: String, sha256: String, byteCount: Long, contentFormatVersion: Int): Int {
            val old = getChapter(bookId, sourceId, chapterId)?.takeIf { it.url == expectedUrl } ?: return 0
            rows[chapterId] = old.copy(localContentState = state, localFilePath = path, contentSha256 = sha256,
                contentBytes = byteCount, contentFormatVersion = contentFormatVersion)
            return 1
        }
        override suspend fun deleteForSource(bookId: String, sourceId: String) {
            rows.entries.removeAll { it.value.bookId == bookId && it.value.sourceId == sourceId }
        }
        override suspend fun clearStoredMetadata(bookId: String) = Unit
    }

    private class MemoryBooks : BookRepository {
        val value = MutableStateFlow<ReadingLocation?>(ReadingLocation("book", "a", "one", 0, 8, 50L))
        val writes = mutableListOf<ReadingLocation>()
        var saveGate: CompletableDeferred<Unit>? = null
        val saveStarted = CompletableDeferred<Unit>()
        override suspend fun saveSearchHit(hit: SearchHit): Book = error("Not used")
        override suspend fun getBook(bookId: String) = Book("book", "key", "星河旅人", "叶子")
        override fun observeSources(bookId: String): Flow<List<BookSource>> = flowOf(emptyList())
        override fun observeShelf(): Flow<List<ShelfBook>> = flowOf(emptyList())
        override suspend fun addToShelf(bookId: String, atEpochMillis: Long) = Unit
        override suspend fun removeFromShelf(bookId: String) = Unit
        override fun progress(bookId: String): Flow<ReadingLocation?> = value
        override suspend fun saveProgress(location: ReadingLocation) {
            saveGate?.let { saveStarted.complete(Unit); it.await() }
            writes += location; value.value = location
        }
    }

    private companion object {
        fun domain(row: ChapterEntity) = Chapter(row.bookId, row.sourceId, row.chapterId, row.chapterIndex, row.title, row.url)
    }
}
