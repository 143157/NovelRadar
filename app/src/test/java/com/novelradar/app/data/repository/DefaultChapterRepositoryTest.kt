package com.novelradar.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.ChapterDao
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.data.files.AtomicChapterFileStore
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.ChapterOrigin
import com.novelradar.app.domain.repository.ImportSummary
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.source.parse.ChapterPage
import com.novelradar.app.source.rule.RuleDto
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultChapterRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun deletionWaitsNonCancellableOldWriteAndStaleWorkerCannotResurrectFiles() = runBlocking {
        val leases = com.novelradar.app.data.download.BookDownloadLeases()
        val realFiles = AtomicChapterFileStore(temporary.root.toPath())
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val files = object : com.novelradar.app.data.files.ChapterFileStore by realFiles {
            override suspend fun write(bookId: String, sourceId: String, chapterId: String, content: String) =
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    entered.complete(Unit); release.await(); realFiles.write(bookId, sourceId, chapterId, content)
                }
        }
        val dao = FakeChapterDao(chapter("one", 0))
        val repository = DefaultChapterRepository(dao, files, { SourceResult.Success("正文") }, contentLeases = leases)
        val store = com.novelradar.app.data.download.MemoryDownloadStore().apply {
            rows["task"] = com.novelradar.app.data.download.task()
        }
        val runner = com.novelradar.app.data.download.BookDownloadRunner(store, repository, leases,
            com.novelradar.app.data.download.SourceDownloadLimiter(wait = {}), { com.novelradar.app.data.download.CrawlPolicy.from(null) })
        val running = async { runner.run("task", "work", 1) }
        val scheduler = object : com.novelradar.app.data.download.DownloadScheduler {
            override fun newWorkId() = "new"
            override suspend fun enqueue(task: com.novelradar.app.data.db.entity.DownloadTaskEntity, name: String) = Unit
            override suspend fun cancel(workId: String) { running.cancel() }
            override suspend fun awaitTerminal(workId: String) = Unit // WM terminal is deliberately ahead of the physical write.
            override suspend fun status(workId: String) = com.novelradar.app.data.download.ScheduledStatus.TERMINAL
        }
        val coordinator = com.novelradar.app.data.download.DownloadCoordinator(store, scheduler, leases)
        val cleaner = com.novelradar.app.data.files.LocalChapterCleaner(files, dao::clearStoredMetadata)
        entered.await()
        val deletion = async { coordinator.deleteLocal("book") { cleaner.delete("book") } }
        kotlinx.coroutines.yield()
        assertThat(deletion.isCompleted).isFalse()
        release.complete(Unit)
        running.join(); deletion.await()
        assertThat(realFiles.read("book", "source", "one")).isNull()
        assertThat(dao.getChapter("book", "source", "one")!!.localContentState).isEqualTo("NOT_STORED")
        assertThat(store.get("task")!!.completedCount).isEqualTo(0)
        assertThat(runner.run("task", "work", 1)).isEqualTo(com.novelradar.app.data.download.DownloadRunResult.OBSOLETE)
        assertThat(realFiles.read("book", "source", "one")).isNull()
    }

    @Test fun readerAndDownloadShareLockAndFetchOnceWithDownloadedMetadata() = runBlocking {
        val dao = FakeChapterDao(chapter("one", 0))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var fetches = 0
        val repository = DefaultChapterRepository(dao, AtomicChapterFileStore(temporary.root.toPath()), {
            fetches++; entered.complete(Unit); release.await(); SourceResult.Success("正文")
        })
        val reading = async { repository.loadChapter("book", "source", "one") }
        entered.await()
        val download = async { repository.download(chapter("one", 0)) }
        release.complete(Unit); reading.await(); download.await()
        assertThat(fetches).isEqualTo(1)
        assertThat(dao.getChapter("book", "source", "one")!!.localContentState).isEqualTo("DOWNLOADED")
    }

    @Test fun verifiedCachedFilePromotesToDownloadedWithoutFetching() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val saved = files.write("book", "source", "one", "正文")
        val row = chapter("one", 0).copy(localContentState = "CACHED", localFilePath = saved.relativePath,
            contentSha256 = saved.sha256, contentBytes = saved.byteCount)
        val dao = FakeChapterDao(row)
        val repository = DefaultChapterRepository(dao, files, { error("Network should not be used") })
        assertThat(repository.verifiedBytes(row)).isEqualTo(saved.byteCount)
        assertThat(dao.getChapter("book", "source", "one")!!.localContentState).isEqualTo("DOWNLOADED")
    }

    @Test fun realDownloadWritesVerifiedDownloadedMetadataAndReaderCannotDowngrade() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val dao = FakeChapterDao(chapter("one", 0))
        val repository = DefaultChapterRepository(dao, files, { SourceResult.Success("正文") })
        assertThat(repository.download(chapter("one", 0))).isEqualTo(SourceResult.Success("正文".toByteArray().size.toLong()))
        assertThat(dao.getChapter("book", "source", "one")!!.localContentState).isEqualTo("DOWNLOADED")
        repository.loadChapter("book", "source", "one")
        assertThat(dao.getChapter("book", "source", "one")!!.localContentState).isEqualTo("DOWNLOADED")
    }

    @Test fun downloadStorageFailureDoesNotCountAsNetworkFailure() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath(), replace = { _, _ -> throw java.io.IOException("No space") })
        val dao = FakeChapterDao(chapter("one", 0))
        val repository = DefaultChapterRepository(dao, files, { SourceResult.Success("正文") })
        val failed = repository.download(chapter("one", 0)) as SourceResult.Failure
        assertThat(failed.error).isInstanceOf(SourceFailure.Storage::class.java)
        assertThat(dao.getChapter("book", "source", "one")!!.localContentState).isEqualTo("NOT_STORED")
    }

    @Test fun validDownloadedFileLoadsWithoutNetwork() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val saved = files.write("book", "source", "one", "下载正文")
        val dao = FakeChapterDao(chapter("one", 0).copy(
            localContentState = "DOWNLOADED", localFilePath = saved.relativePath,
            contentSha256 = saved.sha256, contentBytes = saved.byteCount,
        ))
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files, { fetches++; SourceResult.Success("网络正文") })

        val loaded = repository.loadChapter("book", "source", "one") as SourceResult.Success

        assertThat(loaded.value.content).isEqualTo("下载正文")
        assertThat(loaded.value.origin).isEqualTo(ChapterOrigin.Downloaded)
        assertThat(fetches).isEqualTo(0)
    }

    @Test fun validCachedFileReportsCachedOrigin() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val saved = files.write("book", "source", "one", "缓存正文")
        val dao = FakeChapterDao(chapter("one", 0).copy(
            localContentState = "CACHED", localFilePath = saved.relativePath,
            contentSha256 = saved.sha256, contentBytes = saved.byteCount,
        ))
        val repository = DefaultChapterRepository(dao, files, { error("Network should not be used") })

        val loaded = repository.loadChapter("book", "source", "one") as SourceResult.Success

        assertThat(loaded.value.content).isEqualTo("缓存正文")
        assertThat(loaded.value.origin).isEqualTo(ChapterOrigin.Cached)
    }

    @Test fun corruptFileRefetchesThenStoresVerifiedContentAndRoomMetadata() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val old = files.write("book", "source", "one", "旧正文")
        Files.writeString(temporary.root.toPath().resolve(old.relativePath), "损坏")
        val dao = FakeChapterDao(chapter("one", 0).copy(
            localContentState = "DOWNLOADED", localFilePath = old.relativePath,
            contentSha256 = old.sha256, contentBytes = old.byteCount,
        ))
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files, { fetches++; SourceResult.Success("新正文") })

        val first = repository.loadChapter("book", "source", "one") as SourceResult.Success
        val row = dao.getChapter("book", "source", "one")!!
        val second = repository.loadChapter("book", "source", "one") as SourceResult.Success

        assertThat(first.value.origin).isEqualTo(ChapterOrigin.Network)
        assertThat(first.value.content).isEqualTo("新正文")
        assertThat(row.localContentState).isEqualTo("DOWNLOADED")
        assertThat(row.localFilePath).isEqualTo("books/book/source/one.txt")
        assertThat(row.contentSha256).hasLength(64)
        assertThat(row.contentBytes).isEqualTo("新正文".toByteArray(Charsets.UTF_8).size.toLong())
        assertThat(files.read("book", "source", "one", row.contentSha256)).isEqualTo("新正文")
        assertThat(second.value.origin).isEqualTo(ChapterOrigin.Downloaded)
        assertThat(fetches).isEqualTo(1)
    }

    @Test fun networkFailureDoesNotReplaceExistingChapterMetadata() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val original = chapter("one", 0)
        val dao = FakeChapterDao(original)
        val repository = DefaultChapterRepository(dao, files, {
            SourceResult.Failure(SourceFailure.Timeout)
        })

        val result = repository.loadChapter("book", "source", "one")

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.Timeout))
        assertThat(dao.getChapter("book", "source", "one")).isEqualTo(original)
        assertThat(files.read("book", "source", "one")).isNull()
    }

    @Test fun catalogRefreshDuringFetchKeepsUpdatedTitleAndIndex() = runBlocking {
        val dao = FakeChapterDao(chapter("one", 0))
        val repository = DefaultChapterRepository(dao, AtomicChapterFileStore(temporary.root.toPath()), {
            dao.upsert(chapter("one", 7).copy(title = "Refreshed title"))
            SourceResult.Success("正文")
        })

        val loaded = repository.loadChapter("book", "source", "one") as SourceResult.Success
        val current = dao.getChapter("book", "source", "one")!!

        assertThat(current.title).isEqualTo("Refreshed title")
        assertThat(current.chapterIndex).isEqualTo(7)
        assertThat(current.localContentState).isEqualTo("CACHED")
        assertThat(loaded.value.chapter.title).isEqualTo("Refreshed title")
    }

    @Test fun catalogRemovalDuringFetchDoesNotResurrectChapter() = runBlocking {
        val dao = FakeChapterDao(chapter("one", 0))
        val repository = DefaultChapterRepository(dao, AtomicChapterFileStore(temporary.root.toPath()), {
            dao.deleteForSource("book", "source")
            SourceResult.Success("正文")
        })

        val result = repository.loadChapter("book", "source", "one")

        assertThat(result).isInstanceOf(SourceResult.Failure::class.java)
        assertThat(dao.getChapter("book", "source", "one")).isNull()
    }

    @Test fun concurrentLoadsKeepFileAndMetadataForTheSameVersion() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val dao = FakeChapterDao(chapter("one", 0))
        val firstAtMetadata = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var updates = 0
        dao.beforeMetadataUpdate = {
            if (updates++ == 0) {
                firstAtMetadata.complete(Unit)
                releaseFirst.await()
            }
        }
        val fetches = AtomicInteger()
        val repository = DefaultChapterRepository(dao, files, {
            SourceResult.Success("version-${fetches.incrementAndGet()}")
        })

        val first = async { repository.loadChapter("book", "source", "one") }
        firstAtMetadata.await()
        val second = async { repository.loadChapter("book", "source", "one") }
        withTimeoutOrNull(500) { second.await() }
        releaseFirst.complete(Unit)
        first.await()
        second.await()

        val row = dao.getChapter("book", "source", "one")!!
        assertThat(fetches.get()).isEqualTo(1)
        assertThat(files.read("book", "source", "one", row.contentSha256)).isEqualTo("version-1")
    }

    @Test fun prefetchIsBoundedAndSkipsMeteredNetworkWhenNotAllowed() = runBlocking {
        val dao = FakeChapterDao(*(0..4).map { chapter("c$it", it) }.toTypedArray())
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val fetched = mutableListOf<String>()
        var allowed = false
        val repository = DefaultChapterRepository(dao, files,
            { chapter -> fetched += chapter.chapterId; SourceResult.Success(chapter.chapterId) },
            isMeteredNetwork = { true }, allowMeteredPrefetch = { allowed })

        assertThat(repository.prefetchNext("book", "source", 0, count = 99)).isEqualTo(0)
        assertThat(fetched).isEmpty()
        allowed = true
        assertThat(repository.prefetchNext("book", "source", 0, count = 99)).isEqualTo(2)
        assertThat(fetched).containsExactly("c1", "c2").inOrder()
        assertThat(repository.prefetchNext("book", "source", 3)).isEqualTo(1)
    }

    @Test fun prefetchStopsWhenNetworkBecomesMeteredBetweenChapters() = runBlocking {
        val dao = FakeChapterDao(*(0..2).map { chapter("c$it", it) }.toTypedArray())
        val fetched = mutableListOf<String>()
        var metered = false
        val repository = DefaultChapterRepository(dao, AtomicChapterFileStore(temporary.root.toPath()),
            { chapter ->
                fetched += chapter.chapterId
                metered = true
                SourceResult.Success(chapter.chapterId)
            }, isMeteredNetwork = { metered }, allowMeteredPrefetch = { false })

        assertThat(repository.prefetchNext("book", "source", 0, count = 2)).isEqualTo(1)
        assertThat(fetched).containsExactly("c1")
        Unit
    }

    @Test fun prefetchRechecksMeteringAfterWaitingForChapterLock() = runBlocking {
        val dao = FakeChapterDao(chapter("c1", 1))
        val firstAtFetch = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val prefetchCheckedNetwork = CompletableDeferred<Unit>()
        var metered = false
        val fetches = AtomicInteger()
        val repository = DefaultChapterRepository(dao, AtomicChapterFileStore(temporary.root.toPath()),
            { _ ->
                if (fetches.incrementAndGet() == 1) {
                    firstAtFetch.complete(Unit)
                    releaseFirst.await()
                    SourceResult.Failure(SourceFailure.Timeout)
                } else SourceResult.Success("unexpected prefetch")
            }, isMeteredNetwork = {
                prefetchCheckedNetwork.complete(Unit)
                metered
            }, allowMeteredPrefetch = { false })

        val foreground = async { repository.loadChapter("book", "source", "c1") }
        firstAtFetch.await()
        val prefetch = async { repository.prefetchNext("book", "source", 0, count = 1) }
        prefetchCheckedNetwork.await()
        metered = true
        releaseFirst.complete(Unit)

        assertThat(foreground.await()).isEqualTo(SourceResult.Failure(SourceFailure.Timeout))
        assertThat(prefetch.await()).isEqualTo(0)
        assertThat(fetches.get()).isEqualTo(1)
        assertThat(dao.getChapter("book", "source", "c1")!!.localContentState).isEqualTo("NOT_STORED")
        Unit
    }

    @Test fun ruleBasedFetcherUsesSavedCleartextChoiceAndParsedChapter() = runBlocking {
        val rules = FakeRules(StoredSourceRule("source", RULE_JSON, allowCleartext = true))
        var called = false
        val fetcher = RuleBasedChapterContentFetcher(rules, { rule, sourceId, url, allowCleartext ->
            called = true
            assertThat(rule.name).isEqualTo("Source")
            assertThat(sourceId).isEqualTo("source")
            assertThat(url).isEqualTo("https://example.test/one")
            assertThat(allowCleartext).isTrue()
            SourceResult.Success(ChapterPage("Chapter", "正文"))
        })

        assertThat(fetcher.fetch(chapter("one", 0))).isEqualTo(SourceResult.Success("正文"))
        assertThat(called).isTrue()
    }

    @Test fun ruleBasedFetcherRejectsDisabledSourceBeforeNetwork() = runBlocking {
        val rules = FakeRules(StoredSourceRule("source", RULE_JSON, enabled = false))
        var called = false
        val fetcher = RuleBasedChapterContentFetcher(rules, { _, _, _, _ ->
            called = true
            SourceResult.Success(ChapterPage("", "unexpected"))
        })

        assertThat(fetcher.fetch(chapter("one", 0))).isInstanceOf(SourceResult.Failure::class.java)
        assertThat(called).isFalse()
    }

    private fun chapter(id: String, index: Int) = ChapterEntity(
        bookId = "book", sourceId = "source", chapterId = id, chapterIndex = index,
        title = "Chapter $index", url = "https://example.test/$id",
    )

    private class FakeRules(private val rule: StoredSourceRule) : SourceRuleRepository {
        override fun observeAll(): Flow<List<StoredSourceRule>> = flowOf(listOf(rule))
        override suspend fun enabled(): List<StoredSourceRule> = listOf(rule).filter { it.enabled }
        override suspend fun upsertImported(rules: List<RuleDto>) = ImportSummary(0, 0, 0)
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean) = Unit
        override suspend fun updateHealth(sourceId: String, health: SourceHealth) = Unit
        override suspend fun deleteUserRule(sourceId: String) = Unit
    }

    private companion object {
        const val RULE_JSON = """{"id":"source","url":"https://example.test/","name":"Source",
            "search":{"url":"https://example.test/search?q=%s","method":"GET","result":".item","bookName":"a"},
            "toc":{"item":"#toc a"},"chapter":{"title":"h1","content":"#content"}}"""
    }

    private class FakeChapterDao(vararg chapters: ChapterEntity) : ChapterDao {
        private val rows = chapters.associateBy { Triple(it.bookId, it.sourceId, it.chapterId) }.toMutableMap()
        var beforeMetadataUpdate: (suspend () -> Unit)? = null

        override suspend fun upsertAll(chapters: List<ChapterEntity>) { chapters.forEach { upsert(it) } }
        override suspend fun upsert(chapter: ChapterEntity) {
            rows[Triple(chapter.bookId, chapter.sourceId, chapter.chapterId)] = chapter
        }
        override fun observeChapters(bookId: String, sourceId: String): Flow<List<ChapterEntity>> =
            flowOf(rows.values.filter { it.bookId == bookId && it.sourceId == sourceId }.sortedBy { it.chapterIndex })
        override suspend fun getChapters(bookId: String, sourceId: String): List<ChapterEntity> =
            rows.values.filter { it.bookId == bookId && it.sourceId == sourceId }.sortedBy { it.chapterIndex }
        override suspend fun getChapter(bookId: String, sourceId: String, chapterId: String): ChapterEntity? =
            rows[Triple(bookId, sourceId, chapterId)]
        override suspend fun updateStoredContent(bookId: String, sourceId: String, chapterId: String,
            expectedUrl: String, state: String, path: String, sha256: String, byteCount: Long): Int {
            beforeMetadataUpdate?.invoke()
            val key = Triple(bookId, sourceId, chapterId)
            val row = rows[key]?.takeIf { it.url == expectedUrl } ?: return 0
            rows[key] = row.copy(localContentState = state, localFilePath = path,
                contentSha256 = sha256, contentBytes = byteCount)
            return 1
        }
        override suspend fun deleteForSource(bookId: String, sourceId: String) {
            rows.keys.removeAll { it.first == bookId && it.second == sourceId }
        }
        override suspend fun clearStoredMetadata(bookId: String) {
            rows.entries.filter { it.key.first == bookId }.forEach { (key, row) ->
                rows[key] = row.copy(localContentState = "NOT_STORED", localFilePath = null,
                    contentSha256 = null, contentBytes = null)
            }
        }
    }
}
