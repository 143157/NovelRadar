package com.novelradar.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.ChapterDao
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.data.files.AtomicChapterFileStore
import com.novelradar.app.data.files.ChapterFileStore
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.ChapterOrigin
import com.novelradar.app.domain.repository.ChapterRepository
import com.novelradar.app.domain.repository.ImportSummary
import com.novelradar.app.domain.repository.LoadedChapter
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.source.SourceEngine
import com.novelradar.app.source.http.SearchRequestFactory
import com.novelradar.app.source.http.SourceHttpClient
import com.novelradar.app.source.parse.BookPageParser
import com.novelradar.app.source.parse.ChapterPage
import com.novelradar.app.source.parse.ChapterPageParser
import com.novelradar.app.source.parse.ChapterRequestContext
import com.novelradar.app.source.parse.HtmlSelector
import com.novelradar.app.source.parse.SearchPageParser
import com.novelradar.app.source.parse.TocPageParser
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.script.ScriptExecutor
import java.nio.file.Files
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.assertThrows
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

class DefaultChapterRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun oldRepositoryImplementationsFailClosedWhenPreparationIsUnavailable() = runBlocking {
        val repository = object : ChapterRepository {
            override suspend fun loadChapter(bookId: String, sourceId: String, chapterId: String): SourceResult<LoadedChapter> =
                error("Preparation must not use an old foreground implementation")
            override suspend fun prefetchNext(bookId: String, sourceId: String, chapterIndex: Int, count: Int) = 0
        }

        assertThat(repository.prepareChapter("book", "source", "one")).isEqualTo(
            SourceResult.Failure(SourceFailure.UnsafeRule("Chapter preparation is unavailable")))
    }

    @Test fun preparationReturnsVerifiedCurrentCacheOnDisallowedMeteredNetwork() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        for (state in listOf("CACHED", "DOWNLOADED")) {
            val id = state.lowercase()
            val row = storedChapter(files, id, 0, "已验证的正文😀", state)
                .copy(contentFormatVersion = ChapterContentFormat.CURRENT)
            val dao = FakeChapterDao(row)
            var fetches = 0
            val repository = DefaultChapterRepository(dao, files,
                { fetches++; SourceResult.Failure(SourceFailure.Timeout) }, isMeteredNetwork = { true })

            val loaded = requireSuccess(repository.prepareChapter("book", "source", id))

            assertThat(loaded.content).isEqualTo("已验证的正文😀")
            assertThat(loaded.boundaryVerified).isTrue()
            assertThat(loaded.origin).isEqualTo(if (state == "DOWNLOADED") ChapterOrigin.Downloaded else ChapterOrigin.Cached)
            assertThat(fetches).isEqualTo(0)
            assertThat(dao.getChapter("book", "source", id)).isEqualTo(row)
            assertThat(files.read("book", "source", id, row.contentSha256)).isEqualTo(loaded.content)
        }
    }

    @Test fun preparationOfMissingLegacyOrUnknownContentCannotFetchOnDisallowedMeteredNetwork() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val missing = chapter("missing", 0)
        val legacy = storedChapter(files, "legacy", 1, "旧混章正文")
        val unknown = storedChapter(files, "unknown", 2, "未知格式正文").copy(contentFormatVersion = 99)
        val dao = FakeChapterDao(missing, legacy, unknown)
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files,
            { fetches++; SourceResult.Success("unexpected") }, isMeteredNetwork = { true })

        for (row in listOf(missing, legacy, unknown)) {
            assertThat(repository.prepareChapter("book", "source", row.chapterId)).isEqualTo(
                SourceResult.Failure(SourceFailure.UnsafeRule("Prefetch is disabled on this network")))
            assertThat(dao.getChapter("book", "source", row.chapterId)).isEqualTo(row)
        }
        assertThat(fetches).isEqualTo(0)
        assertThat(files.read("book", "source", "missing")).isNull()
        assertThat(files.read("book", "source", "legacy", legacy.contentSha256)).isEqualTo("旧混章正文")
        assertThat(files.read("book", "source", "unknown", unknown.contentSha256)).isEqualTo("未知格式正文")
    }

    @Test fun failedPreparationNeverReturnsLegacyOrUnknownBodyAsVerifiedOrComplete() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        for (marker in listOf(ChapterContentFormat.LEGACY, 99)) {
            val id = "marker-$marker"
            val row = storedChapter(files, id, 1, "旧混章正文", "DOWNLOADED").copy(contentFormatVersion = marker)
            val dao = FakeChapterDao(row)
            var fetches = 0
            val repository = DefaultChapterRepository(dao, files,
                { fetches++; SourceResult.Failure(SourceFailure.Timeout) }, isMeteredNetwork = { false })

            assertThat(repository.prepareChapter("book", "source", id)).isEqualTo(SourceResult.Failure(SourceFailure.Timeout))
            assertThat(fetches).isEqualTo(1)
            assertThat(dao.getChapter("book", "source", id)).isEqualTo(row)
            assertThat(files.read("book", "source", id, row.contentSha256)).isEqualTo("旧混章正文")
            assertThat(repository.prefetchNext("book", "source", 0, count = 1)).isEqualTo(0)
        }
    }

    @Test fun preparationWithNoUrlRejectsReadableLegacyFallback() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val row = storedChapter(files, "one", 0, "旧正文").copy(url = null)
        val dao = FakeChapterDao(row)
        val repository = DefaultChapterRepository(dao, files, { error("A chapter with no URL must not fetch") },
            isMeteredNetwork = { false })

        assertThat(repository.prepareChapter("book", "source", "one")).isEqualTo(
            SourceResult.Failure(SourceFailure.UnsafeRule("Chapter has no URL")))
        assertThat(dao.getChapter("book", "source", "one")).isEqualTo(row)
        assertThat(files.read("book", "source", "one", row.contentSha256)).isEqualTo("旧正文")
    }

    @Test fun realHttpPreparationRepairsOnlyOneChapterAndPreservesLegacyHashAndDownloadedState() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<h1>第一章</h1><div id=content>第一章单章正文</div><a class=next href='/chapter/2'>下一页</a>"))
            runBlocking {
                val files = AtomicChapterFileStore(temporary.root.toPath())
                val legacy = storedChapter(files, "one", 0, "第一章\n第二章\n第三章", "DOWNLOADED")
                    .copy(url = server.url("/chapter/1").toString())
                val dao = FakeChapterDao(legacy, chapter("two", 1).copy(url = server.url("/chapter/2").toString()))
                val repository = DefaultChapterRepository(dao, files, realFetcher(server, dao), isMeteredNetwork = { false })

                val loaded = requireSuccess(withTimeout(10_000) { repository.prepareChapter("book", "source", "one") })
                val current = dao.getChapter("book", "source", "one")!!

                assertThat(loaded.content).isEqualTo("第一章单章正文")
                assertThat(loaded.boundaryVerified).isTrue()
                assertThat(current.contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
                assertThat(current.localContentState).isEqualTo("DOWNLOADED")
                assertThat(files.read("book", "source", "one", current.contentSha256)).isEqualTo(loaded.content)
                assertThat(files.read("book", "source", "one", legacy.contentSha256)).isEqualTo("第一章\n第二章\n第三章")
                assertThat(server.requestCount).isEqualTo(1)
                assertThat(server.takeRequest().requestUrl!!.encodedPath).isEqualTo("/chapter/1")
            }
        }
    }

    @Test fun preparationRethrowsFetchAndMetadataCancellationWithoutLosingLegacyHash() {
        runBlocking {
            val files = AtomicChapterFileStore(temporary.root.toPath())
            for (duringMetadata in listOf(false, true)) {
                val id = if (duringMetadata) "metadata" else "fetch"
                val row = storedChapter(files, id, 0, "旧正文")
                val dao = FakeChapterDao(row)
                if (duringMetadata) dao.beforeMetadataUpdate = { throw CancellationException("preparation cancelled") }
                val repository = DefaultChapterRepository(dao, files, {
                    if (!duringMetadata) throw CancellationException("preparation cancelled")
                    SourceResult.Success("单章正文")
                }, isMeteredNetwork = { false })

                val cancelled = assertThrows(CancellationException::class.java) {
                    runBlocking { repository.prepareChapter("book", "source", id) }
                }

                assertThat(cancelled.message).isEqualTo("preparation cancelled")
                assertThat(dao.getChapter("book", "source", id)).isEqualTo(row)
                assertThat(files.read("book", "source", id, row.contentSha256)).isEqualTo("旧正文")
            }
        }
    }

    @Test fun preparationRechecksNetworkPolicyAfterWaitingForChapterLock() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        for (hasLegacy in listOf(false, true)) {
            val id = if (hasLegacy) "legacy" else "missing"
            val row = if (hasLegacy) storedChapter(files, id, 0, "旧正文") else chapter(id, 0)
            val dao = FakeChapterDao(row)
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var metered = false
            var fetches = 0
            val repository = DefaultChapterRepository(dao, files, {
                fetches++; entered.complete(Unit); release.await(); SourceResult.Failure(SourceFailure.Timeout)
            }, isMeteredNetwork = { metered })
            val foreground = async { repository.loadChapter("book", "source", id) }
            withTimeout(2_000) { entered.await() }
            val preparing = async(start = CoroutineStart.UNDISPATCHED) { repository.prepareChapter("book", "source", id) }
            try {
                assertThat(preparing.isCompleted).isFalse()
                metered = true
                release.complete(Unit)

                assertThat(preparing.await()).isEqualTo(
                    SourceResult.Failure(SourceFailure.UnsafeRule("Prefetch is disabled on this network")))
                assertThat(fetches).isEqualTo(1)
                assertThat(dao.getChapter("book", "source", id)).isEqualTo(row)
            } finally { release.complete(Unit); foreground.await(); preparing.await() }
        }
    }

    @Test fun preparationAndForegroundLoadShareOneFetchAndVerifiedHashInEitherOrder() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        for (prepareFirst in listOf(false, true)) {
            val id = if (prepareFirst) "prepare-first" else "foreground-first"
            val dao = FakeChapterDao(chapter(id, 0))
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var fetches = 0
            val repository = DefaultChapterRepository(dao, files, {
                fetches++; entered.complete(Unit); release.await(); SourceResult.Success("单章正文😀")
            }, isMeteredNetwork = { false })
            val first = async(start = CoroutineStart.UNDISPATCHED) {
                if (prepareFirst) repository.prepareChapter("book", "source", id)
                else repository.loadChapter("book", "source", id)
            }
            try {
                assertThat(withTimeoutOrNull(2_000) { entered.await() }).isNotNull()
                val second = async(start = CoroutineStart.UNDISPATCHED) {
                    if (prepareFirst) repository.loadChapter("book", "source", id)
                    else repository.prepareChapter("book", "source", id)
                }
                assertThat(second.isCompleted).isFalse()
                release.complete(Unit)
                val results = listOf(requireSuccess(first.await()), requireSuccess(second.await()))
                val current = dao.getChapter("book", "source", id)!!

                assertThat(fetches).isEqualTo(1)
                for (loaded in results) {
                    assertThat(loaded.content).isEqualTo("单章正文😀")
                    assertThat(loaded.boundaryVerified).isTrue()
                }
                assertThat(current.contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
                assertThat(files.read("book", "source", id, current.contentSha256)).isEqualTo("单章正文😀")
                assertThat(current.contentBytes).isEqualTo("单章正文😀".toByteArray(Charsets.UTF_8).size.toLong())
            } finally { release.complete(Unit); first.await() }
        }
    }

    @Test fun cachedPreparationDoesNotWaitForAnotherStalledChapterFetch() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val cached = storedChapter(files, "cached", 0, "缓存正文").copy(contentFormatVersion = ChapterContentFormat.CURRENT)
        val dao = FakeChapterDao(cached, chapter("stalled", 1))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val repository = DefaultChapterRepository(dao, files, { row ->
            assertThat(row.chapterId).isEqualTo("stalled")
            entered.complete(Unit); release.await(); SourceResult.Success("网络正文")
        }, isMeteredNetwork = { false })
        val stalled = async(start = CoroutineStart.UNDISPATCHED) { repository.prepareChapter("book", "source", "stalled") }
        try {
            assertThat(withTimeoutOrNull(2_000) { entered.await() }).isNotNull()
            val result = withTimeoutOrNull(2_000) { repository.prepareChapter("book", "source", "cached") }
            assertThat(result).isNotNull()
            val loaded = requireSuccess(result!!)

            assertThat(loaded.content).isEqualTo("缓存正文")
            assertThat(loaded.boundaryVerified).isTrue()
            assertThat(stalled.isCompleted).isFalse()
            assertThat(dao.getChapter("book", "source", "cached")).isEqualTo(cached)
        } finally { release.complete(Unit); stalled.await() }
    }

    @Test fun queuedPreparationIsRejectedAfterBookDeletionStartsWithoutResurrectingFiles() = runBlocking {
        val leases = com.novelradar.app.data.download.BookDownloadLeases()
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val dao = FakeChapterDao(chapter("one", 0))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files, {
            fetches++; entered.complete(Unit); release.await(); SourceResult.Success("正文")
        }, isMeteredNetwork = { false }, contentLeases = leases)
        val foreground = async { repository.loadChapter("book", "source", "one") }
        withTimeout(2_000) { entered.await() }
        val preparing = async(start = CoroutineStart.UNDISPATCHED) { repository.prepareChapter("book", "source", "one") }
        try {
            assertThat(preparing.isCompleted).isFalse()
            val cleaner = com.novelradar.app.data.files.LocalChapterCleaner(files, dao::clearStoredMetadata)
            val deletion = async(start = CoroutineStart.UNDISPATCHED) {
                leases.deleting("book", stop = {}, clean = { cleaner.delete("book") })
            }
            assertThat(leases.isDeleting("book")).isTrue()
            release.complete(Unit)

            assertThat(preparing.await()).isEqualTo(
                SourceResult.Failure(SourceFailure.UnsafeRule("Book content is being deleted")))
            foreground.await()
            deletion.await()
            assertThat(fetches).isEqualTo(1)
            assertThat(files.read("book", "source", "one")).isNull()
            assertThat(dao.getChapter("book", "source", "one")!!.localContentState).isEqualTo("NOT_STORED")
        } finally { release.complete(Unit); foreground.await(); preparing.await() }
    }

    @Test fun preparationRetainsExpectedUrlGuardWhenCatalogChangesDuringFetch() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        for (fetchSucceeds in listOf(false, true)) {
            val id = if (fetchSucceeds) "success" else "failure"
            val row = storedChapter(files, id, 0, "旧正文")
            val dao = FakeChapterDao(row)
            val changed = row.copy(url = "https://example.test/replacement")
            val repository = DefaultChapterRepository(dao, files, {
                dao.upsert(changed)
                if (fetchSucceeds) SourceResult.Success("新正文") else SourceResult.Failure(SourceFailure.Timeout)
            }, isMeteredNetwork = { false })

            assertThat(repository.prepareChapter("book", "source", id)).isEqualTo(
                SourceResult.Failure(SourceFailure.UnsafeRule("Chapter changed while loading")))
            assertThat(dao.getChapter("book", "source", id)).isEqualTo(changed)
            assertThat(files.read("book", "source", id, row.contentSha256)).isEqualTo("旧正文")
        }
    }

    @Test fun prefetchHonorsZeroOneAndTwoCountsInCatalogOrderAndWritesVerifiedBodies() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val dao = FakeChapterDao(chapter("d", 90), chapter("b", 20), chapter("a", 20), chapter("active", 5), chapter("c", 30))
        val fetched = mutableListOf<String>()
        val repository = DefaultChapterRepository(dao, files,
            { row -> fetched += row.chapterId; SourceResult.Success("正文-${row.chapterId}") }, isMeteredNetwork = { false })

        assertThat(repository.prefetchNext("book", "source", 5, count = 0)).isEqualTo(0)
        assertThat(repository.prefetchNext("book", "source", 5, count = -1)).isEqualTo(0)
        assertThat(fetched).isEmpty()
        assertThat(repository.prefetchNext("book", "source", 5, count = 1)).isEqualTo(1)
        assertThat(fetched).containsExactly("a")
        assertThat(repository.prefetchNext("book", "source", 5, count = 2)).isEqualTo(2)
        assertThat(fetched).containsExactly("a", "b").inOrder()
        for (id in listOf("a", "b")) {
            val row = dao.getChapter("book", "source", id)!!
            assertThat(row.contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
            assertThat(files.read("book", "source", id, row.contentSha256)).isEqualTo("正文-$id")
        }
        assertThat(dao.getChapter("book", "source", "c")!!.localContentState).isEqualTo("NOT_STORED")
        assertThat(dao.getChapter("book", "source", "d")!!.localContentState).isEqualTo("NOT_STORED")
    }

    private fun requireSuccess(result: SourceResult<LoadedChapter>): LoadedChapter {
        assertThat(result).isInstanceOf(SourceResult.Success::class.java)
        return (result as SourceResult.Success).value
    }

    @Test fun readableLegacyBodyIsTemporaryUnverifiedFallbackWhenOffline() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val old = files.write("book", "source", "one", "第一章\n第二章\n第三章")
        val row = chapter("one", 0).copy(localContentState = "DOWNLOADED", localFilePath = old.relativePath,
            contentSha256 = old.sha256, contentBytes = old.byteCount)
        val dao = FakeChapterDao(row)
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files, { fetches++; SourceResult.Failure(SourceFailure.Timeout) })

        val loaded = (repository.loadChapter("book", "source", "one") as SourceResult.Success).value

        assertThat(loaded.boundaryVerified).isFalse()
        assertThat(loaded.content).isEqualTo("第一章\n第二章\n第三章")
        assertThat(fetches).isEqualTo(1)
        assertThat(dao.getChapter("book", "source", "one")).isEqualTo(row)
        assertThat(files.read("book", "source", "one", old.sha256)).isEqualTo(loaded.content)
    }

    @Test fun legacyAndUnknownMarkersNeverProduceVerifiedBytesOrPromoteDownloadState() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val old = files.write("book", "source", "one", "旧混章正文")
        val legacy = chapter("one", 0).copy(localContentState = "CACHED", localFilePath = old.relativePath,
            contentSha256 = old.sha256, contentBytes = old.byteCount)
        val dao = FakeChapterDao(legacy)
        val repository = DefaultChapterRepository(dao, files, { error("Verified byte checks must not fetch") })

        assertThat(repository.verifiedBytes(legacy)).isNull()
        assertThat(dao.getChapter("book", "source", "one")).isEqualTo(legacy)
        val unknown = legacy.copy(contentFormatVersion = 99)
        dao.upsert(unknown)
        assertThat(repository.verifiedBytes(unknown)).isNull()
        assertThat(dao.getChapter("book", "source", "one")).isEqualTo(unknown)
    }

    @Test fun legacyDownloadFailureCannotCountTemporaryBodyAsCompleted() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val old = files.write("book", "source", "one", "旧正文")
        val legacy = chapter("one", 0).copy(localContentState = "CACHED", localFilePath = old.relativePath,
            contentSha256 = old.sha256, contentBytes = old.byteCount)
        val dao = FakeChapterDao(legacy)
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files, { fetches++; SourceResult.Failure(SourceFailure.Timeout) })

        assertThat(repository.download(legacy)).isEqualTo(SourceResult.Failure(SourceFailure.Timeout))
        assertThat(fetches).isEqualTo(1)
        assertThat(dao.getChapter("book", "source", "one")).isEqualTo(legacy)
        assertThat(files.read("book", "source", "one", old.sha256)).isEqualTo("旧正文")
    }

    @Test fun legacyRepairFetchesOnceAndPreservesOriginalHashReadableAfterCurrentMetadata() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val old = files.write("book", "source", "one", "旧混章正文")
        val dao = FakeChapterDao(chapter("one", 0).copy(localContentState = "DOWNLOADED", localFilePath = old.relativePath,
            contentSha256 = old.sha256, contentBytes = old.byteCount))
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files, { fetches++; SourceResult.Success("仅第一章正文") })

        val loaded = (repository.loadChapter("book", "source", "one") as SourceResult.Success).value
        val repaired = dao.getChapter("book", "source", "one")!!

        assertThat(fetches).isEqualTo(1)
        assertThat(loaded.content).isEqualTo("仅第一章正文")
        assertThat(loaded.boundaryVerified).isTrue()
        assertThat(repaired.contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
        assertThat(repaired.localContentState).isEqualTo("DOWNLOADED")
        assertThat(files.read("book", "source", "one", old.sha256)).isEqualTo("旧混章正文")
        assertThat(files.read("book", "source", "one", repaired.contentSha256)).isEqualTo("仅第一章正文")
    }

    @Test fun realHttpLegacyThreeChapterBodyRepairsToOneChapterWithoutRequestingKnownNextChapter() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<h1>第一章</h1><div id=content>第一章单章正文</div><a class=next href='/chapter/2'>下一页</a>"))
            runBlocking {
                val files = AtomicChapterFileStore(temporary.root.toPath())
                val oldText = "第一章旧正文😀\n\n第二章旧正文\n\n第三章旧正文"
                val legacy = storedChapter(files, "one", 0, oldText, "DOWNLOADED")
                    .copy(url = server.url("/chapter/1").toString())
                val dao = FakeChapterDao(legacy,
                    chapter("two", 1).copy(url = server.url("/chapter/2").toString()),
                    chapter("three", 2).copy(url = server.url("/chapter/3").toString()))
                val repository = DefaultChapterRepository(dao, files, realFetcher(server, dao))

                val loaded = (withTimeout(10_000) { repository.loadChapter("book", "source", "one") } as SourceResult.Success).value
                val repaired = dao.getChapter("book", "source", "one")!!

                assertThat(loaded.content).isEqualTo("第一章单章正文")
                assertThat(loaded.boundaryVerified).isTrue()
                assertThat(loaded.origin).isEqualTo(ChapterOrigin.Network)
                assertThat(server.requestCount).isEqualTo(1)
                assertThat(server.takeRequest().requestUrl!!.encodedPath).isEqualTo("/chapter/1")
                assertThat(repaired.contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
                assertThat(repaired.localContentState).isEqualTo("DOWNLOADED")
                assertThat(repaired.localFilePath).isEqualTo("books/book/source/one.txt")
                assertThat(repaired.contentBytes).isEqualTo(loaded.content.toByteArray(Charsets.UTF_8).size.toLong())
                assertThat(files.read("book", "source", "one", repaired.contentSha256)).isEqualTo(loaded.content)
                assertThat(files.read("book", "source", "one", legacy.contentSha256)).isEqualTo(oldText)
                assertThat(Files.readAllBytes(temporary.root.toPath().resolve("books/book/source/one.legacy-${legacy.contentSha256}.txt")))
                    .isEqualTo(oldText.toByteArray(Charsets.UTF_8))
                assertThat((repository.loadChapter("book", "source", "one") as SourceResult.Success).value.boundaryVerified).isTrue()
                assertThat(server.requestCount).isEqualTo(1)
            }
        }
    }

    @Test fun realHttpMetadataFailureKeepsLegacyReadableAndRetryReusesOriginalHashBackup() {
        MockWebServer().use { server ->
            server.start()
            repeat(2) { server.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<h1>第一章</h1><div id=content>修复单章正文</div>")) }
            runBlocking {
                val files = AtomicChapterFileStore(temporary.root.toPath())
                val legacy = storedChapter(files, "one", 0, "第一章\n第二章\n第三章", "DOWNLOADED")
                    .copy(url = server.url("/chapter/1").toString())
                val dao = FakeChapterDao(legacy)
                var metadataFails = true
                dao.beforeMetadataUpdate = { if (metadataFails) throw IOException("metadata failed") }
                val repository = DefaultChapterRepository(dao, files, realFetcher(server, dao))

                val first = (withTimeout(10_000) { repository.loadChapter("book", "source", "one") } as SourceResult.Success).value

                assertThat(first.content).isEqualTo("第一章\n第二章\n第三章")
                assertThat(first.boundaryVerified).isFalse()
                assertThat(dao.getChapter("book", "source", "one")).isEqualTo(legacy)
                assertThat(files.read("book", "source", "one")).isEqualTo("修复单章正文")
                assertThat(files.read("book", "source", "one", legacy.contentSha256)).isEqualTo(first.content)
                val backup = temporary.root.toPath().resolve("books/book/source/one.legacy-${legacy.contentSha256}.txt")
                val retainedTime = Files.getLastModifiedTime(backup)
                metadataFails = false

                val retry = (withTimeout(10_000) { repository.loadChapter("book", "source", "one") } as SourceResult.Success).value

                assertThat(retry.content).isEqualTo("修复单章正文")
                assertThat(retry.boundaryVerified).isTrue()
                assertThat(server.requestCount).isEqualTo(2)
                assertThat(Files.getLastModifiedTime(backup)).isEqualTo(retainedTime)
                assertThat(files.read("book", "source", "one", legacy.contentSha256)).isEqualTo(first.content)
                assertThat(dao.getChapter("book", "source", "one")!!.contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
                assertThat(dao.getChapter("book", "source", "one")!!.localContentState).isEqualTo("DOWNLOADED")
            }
        }
    }

    @Test fun failedLegacyReplacementKeepsOriginalReadableAndMetadataUnchanged() = runBlocking {
        val realFiles = AtomicChapterFileStore(temporary.root.toPath())
        val legacy = storedChapter(realFiles, "one", 0, "旧混章正文")
        val dao = FakeChapterDao(legacy)
        val files = AtomicChapterFileStore(temporary.root.toPath(), replace = { _, target ->
            Files.delete(target); throw IOException("interrupted replacement")
        })
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files, { fetches++; SourceResult.Success("单章正文") })

        val loaded = (repository.loadChapter("book", "source", "one") as SourceResult.Success).value

        assertThat(loaded.boundaryVerified).isFalse()
        assertThat(loaded.content).isEqualTo("旧混章正文")
        assertThat(fetches).isEqualTo(1)
        assertThat(dao.getChapter("book", "source", "one")).isEqualTo(legacy)
        assertThat(realFiles.read("book", "source", "one", legacy.contentSha256)).isEqualTo("旧混章正文")
        assertThat(Files.exists(temporary.root.toPath().resolve("books/book/source/one.tmp"))).isFalse()
    }

    @Test fun realParseFailureReturnsOnlyUnverifiedLegacyViewingFallback() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=utf-8").setBody("<h1>第一章</h1>"))
            runBlocking {
                val files = AtomicChapterFileStore(temporary.root.toPath())
                val legacy = storedChapter(files, "one", 0, "原始旧正文").copy(url = server.url("/chapter/1").toString())
                val dao = FakeChapterDao(legacy)
                val repository = DefaultChapterRepository(dao, files, realFetcher(server, dao))

                val loaded = (withTimeout(10_000) { repository.loadChapter("book", "source", "one") } as SourceResult.Success).value

                assertThat(loaded.boundaryVerified).isFalse()
                assertThat(loaded.content).isEqualTo("原始旧正文")
                assertThat(server.requestCount).isEqualTo(1)
                assertThat(dao.getChapter("book", "source", "one")).isEqualTo(legacy)
            }
        }
    }

    @Test fun disabledSourcePrefetchDoesNotFetchOrCountLegacyBody() {
        MockWebServer().use { server ->
            server.start()
            runBlocking {
                val files = AtomicChapterFileStore(temporary.root.toPath())
                val legacy = storedChapter(files, "one", 1, "旧正文").copy(url = server.url("/chapter/1").toString())
                val dao = FakeChapterDao(legacy)
                val repository = DefaultChapterRepository(dao, files, realFetcher(server, dao, enabled = false),
                    isMeteredNetwork = { false })

                assertThat(repository.prefetchNext("book", "source", 0, count = 2)).isEqualTo(0)
                assertThat(server.requestCount).isEqualTo(0)
                assertThat(dao.getChapter("book", "source", "one")).isEqualTo(legacy)
            }
        }
    }

    @Test fun meteredOrZeroCountPrefetchDoesNotAttemptLegacyRepairAndFailedAllowedRepairDoesNotCount() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val legacy = storedChapter(files, "one", 1, "旧正文")
        val dao = FakeChapterDao(legacy)
        var metered = true
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files, { fetches++; SourceResult.Failure(SourceFailure.Timeout) },
            isMeteredNetwork = { metered })

        assertThat(repository.prefetchNext("book", "source", 0, count = 2)).isEqualTo(0)
        metered = false
        assertThat(repository.prefetchNext("book", "source", 0, count = 0)).isEqualTo(0)
        assertThat(fetches).isEqualTo(0)
        assertThat(repository.prefetchNext("book", "source", 0, count = 1)).isEqualTo(0)
        assertThat(fetches).isEqualTo(1)
        assertThat(dao.getChapter("book", "source", "one")).isEqualTo(legacy)
    }

    @Test fun legacyPrefetchRechecksMeteringAfterWaitingForChapterLock() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val legacy = storedChapter(files, "one", 1, "旧正文")
        val dao = FakeChapterDao(legacy)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val checked = CompletableDeferred<Unit>()
        var metered = false
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files, {
            fetches++; entered.complete(Unit); release.await(); SourceResult.Failure(SourceFailure.Timeout)
        }, isMeteredNetwork = { checked.complete(Unit); metered })
        val foreground = async { repository.loadChapter("book", "source", "one") }
        assertThat(withTimeoutOrNull(2_000) { entered.await() }).isNotNull()
        val prefetch = async { repository.prefetchNext("book", "source", 0, count = 1) }
        withTimeout(2_000) { checked.await() }
        metered = true
        release.complete(Unit)

        assertThat((foreground.await() as SourceResult.Success).value.boundaryVerified).isFalse()
        assertThat(prefetch.await()).isEqualTo(0)
        assertThat(fetches).isEqualTo(1)
        assertThat(dao.getChapter("book", "source", "one")).isEqualTo(legacy)
    }

    @Test fun changedUrlDuringLegacyFetchNeverPresentsOldBodyAsTheNewCurrentChapter() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        for (fetchSucceeds in listOf(false, true)) {
            val id = if (fetchSucceeds) "success" else "failure"
            val legacy = storedChapter(files, id, 0, "旧正文")
            val dao = FakeChapterDao(legacy)
            val changed = legacy.copy(url = "https://example.test/replacement")
            val repository = DefaultChapterRepository(dao, files, {
                dao.upsert(changed)
                if (fetchSucceeds) SourceResult.Success("新正文") else SourceResult.Failure(SourceFailure.Timeout)
            })

            val result = repository.loadChapter("book", "source", id)
            assertThat(result).isInstanceOf(SourceResult.Failure::class.java)
            val failed = result as SourceResult.Failure

            assertThat(failed.error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
            assertThat(dao.getChapter("book", "source", id)).isEqualTo(changed)
            assertThat(files.read("book", "source", id, legacy.contentSha256)).isEqualTo("旧正文")
        }
    }

    @Test fun missingOrCorruptLegacyFilesRecoverFreshlyWithoutInventingAnOldBackup() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        for (missing in listOf(false, true)) {
            val id = if (missing) "missing" else "corrupt"
            val legacy = storedChapter(files, id, 0, "旧正文")
            val path = temporary.root.toPath().resolve(legacy.localFilePath!!)
            if (missing) Files.delete(path) else Files.writeString(path, "损坏")
            val dao = FakeChapterDao(legacy)
            var fetches = 0
            val repository = DefaultChapterRepository(dao, files, { fetches++; SourceResult.Success("单章正文") })

            val loaded = (repository.loadChapter("book", "source", id) as SourceResult.Success).value

            assertThat(loaded.boundaryVerified).isTrue()
            assertThat(loaded.content).isEqualTo("单章正文")
            assertThat(fetches).isEqualTo(1)
            assertThat(dao.getChapter("book", "source", id)!!.contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
            assertThat(Files.exists(path.resolveSibling("$id.legacy-${legacy.contentSha256}.txt"))).isFalse()
        }
    }

    @Test fun validLegacyHashIsPreservedEvenWhenPersistedByteCountIsStale() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val legacy = storedChapter(files, "one", 0, "旧正文").copy(contentBytes = 1)
        val dao = FakeChapterDao(legacy)
        val repository = DefaultChapterRepository(dao, files, { SourceResult.Success("单章正文") })

        val loaded = (repository.loadChapter("book", "source", "one") as SourceResult.Success).value

        assertThat(loaded.content).isEqualTo("单章正文")
        assertThat(loaded.boundaryVerified).isTrue()
        assertThat(files.read("book", "source", "one", legacy.contentSha256)).isEqualTo("旧正文")
    }

    @Test fun validLegacyBytesSurviveRepairEvenWhenPathOrLocalStateMetadataIsStale() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        for (stalePath in listOf(false, true)) {
            val id = if (stalePath) "path" else "state"
            val saved = storedChapter(files, id, 0, "原始旧正文")
            val legacy = if (stalePath) saved.copy(localFilePath = "obsolete/$id.txt")
                else saved.copy(localContentState = "NOT_STORED")
            val dao = FakeChapterDao(legacy)
            val repository = DefaultChapterRepository(dao, files, { SourceResult.Success("修复单章正文") })

            val loaded = (repository.loadChapter("book", "source", id) as SourceResult.Success).value

            assertThat(loaded.boundaryVerified).isTrue()
            assertThat(loaded.content).isEqualTo("修复单章正文")
            assertThat(files.read("book", "source", id, legacy.contentSha256)).isEqualTo("原始旧正文")
        }
    }

    @Test fun unsupportedLegacyPreservingStoreCannotOverwriteReadableOldBody() = runBlocking {
        val realFiles = AtomicChapterFileStore(temporary.root.toPath())
        val legacy = storedChapter(realFiles, "one", 0, "旧正文")
        var destructiveWrites = 0
        val files = object : ChapterFileStore {
            override suspend fun write(bookId: String, sourceId: String, chapterId: String, content: String) =
                realFiles.write(bookId, sourceId, chapterId, content).also { destructiveWrites++ }
            override suspend fun read(bookId: String, sourceId: String, chapterId: String, expectedSha256: String?) =
                realFiles.read(bookId, sourceId, chapterId, expectedSha256)
            override suspend fun deleteBook(bookId: String) = realFiles.deleteBook(bookId)
        }
        val dao = FakeChapterDao(legacy)
        val repository = DefaultChapterRepository(dao, files, { SourceResult.Success("单章正文") })

        val loaded = (repository.loadChapter("book", "source", "one") as SourceResult.Success).value

        assertThat(loaded.boundaryVerified).isFalse()
        assertThat(loaded.content).isEqualTo("旧正文")
        assertThat(destructiveWrites).isEqualTo(0)
        assertThat(dao.getChapter("book", "source", "one")).isEqualTo(legacy)
    }

    @Test fun legacyFetchAndMetadataCancellationAreRethrownWithoutLosingOriginalBody() {
        runBlocking {
            val files = AtomicChapterFileStore(temporary.root.toPath())
            for (duringMetadata in listOf(false, true)) {
                val id = if (duringMetadata) "metadata" else "fetch"
                val legacy = storedChapter(files, id, 0, "旧正文")
                val dao = FakeChapterDao(legacy)
                if (duringMetadata) dao.beforeMetadataUpdate = { throw CancellationException("repair cancelled") }
                val repository = DefaultChapterRepository(dao, files, {
                    if (!duringMetadata) throw CancellationException("repair cancelled")
                    SourceResult.Success("单章正文")
                })

                val cancelled = assertThrows(CancellationException::class.java) {
                    runBlocking { repository.loadChapter("book", "source", id) }
                }

                assertThat(cancelled.message).isEqualTo("repair cancelled")
                assertThat(dao.getChapter("book", "source", id)).isEqualTo(legacy)
                assertThat(files.read("book", "source", id, legacy.contentSha256)).isEqualTo("旧正文")
            }
        }
    }

    @Test fun verifiedCachedChapterReadsWhileAnotherChapterDownloadFetchIsStalled() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val saved = files.write("book", "source", "cached", "已验证的正文")
        val cached = chapter("cached", 0).copy(localContentState = "CACHED", localFilePath = saved.relativePath,
            contentSha256 = saved.sha256, contentBytes = saved.byteCount, contentFormatVersion = ChapterContentFormat.CURRENT)
        val downloading = chapter("downloading", 1)
        val dao = FakeChapterDao(cached, downloading)
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val repository = DefaultChapterRepository(dao, files, { row ->
            assertThat(row.chapterId).isEqualTo("downloading")
            entered.complete(Unit); release.await(); SourceResult.Success("下载正文")
        })
        val download = async { repository.download(downloading) }
        entered.await()
        try {
            val loaded = withTimeoutOrNull(2_000) { repository.loadChapter("book", "source", "cached") }
            assertThat(loaded).isNotNull()
            val local = (loaded as SourceResult.Success).value
            assertThat(local.content).isEqualTo("已验证的正文")
            assertThat(local.origin).isEqualTo(ChapterOrigin.Cached)
            assertThat(download.isCompleted).isFalse()
        } finally { release.complete(Unit); download.await() }
    }

    @Test fun queuedSameChapterReadIsRejectedAfterDeletionStartsAndCannotResurrectFiles() = runBlocking {
        val leases = com.novelradar.app.data.download.BookDownloadLeases()
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val dao = FakeChapterDao(chapter("one", 0))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files, {
            fetches++; entered.complete(Unit); release.await(); SourceResult.Success("正文")
        }, contentLeases = leases)
        val active = async { repository.download(chapter("one", 0)) }
        entered.await()
        val queued = async { repository.loadChapter("book", "source", "one") }
        kotlinx.coroutines.yield()
        val cleaner = com.novelradar.app.data.files.LocalChapterCleaner(files, dao::clearStoredMetadata)
        val deletion = async { leases.deleting("book", stop = {}, clean = { cleaner.delete("book") }) }
        kotlinx.coroutines.yield()
        assertThat(leases.isDeleting("book")).isTrue()
        release.complete(Unit)
        active.await()
        assertThat(queued.await()).isInstanceOf(SourceResult.Failure::class.java)
        deletion.await()
        assertThat(fetches).isEqualTo(1)
        assertThat(files.read("book", "source", "one")).isNull()
        assertThat(dao.getChapter("book", "source", "one")!!.localContentState).isEqualTo("NOT_STORED")
    }

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
            contentSha256 = saved.sha256, contentBytes = saved.byteCount, contentFormatVersion = ChapterContentFormat.CURRENT)
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
            contentSha256 = saved.sha256, contentBytes = saved.byteCount, contentFormatVersion = ChapterContentFormat.CURRENT,
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
            contentSha256 = saved.sha256, contentBytes = saved.byteCount, contentFormatVersion = ChapterContentFormat.CURRENT,
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
        val fetcher = RuleBasedChapterContentFetcher(rules, { rule, sourceId, url, allowCleartext, context ->
            called = true
            assertThat(rule.name).isEqualTo("Source")
            assertThat(sourceId).isEqualTo("source")
            assertThat(url).isEqualTo("https://example.test/one")
            assertThat(allowCleartext).isTrue()
            assertThat(context).isEqualTo(ChapterRequestContext())
            SourceResult.Success(ChapterPage("Chapter", "正文"))
        })

        assertThat(fetcher.fetch(chapter("one", 0))).isEqualTo(SourceResult.Success("正文"))
        assertThat(called).isTrue()
    }

    @Test fun ruleBasedFetcherRejectsDisabledSourceBeforeNetwork() = runBlocking {
        val rules = FakeRules(StoredSourceRule("source", RULE_JSON, enabled = false))
        var called = false
        val fetcher = RuleBasedChapterContentFetcher(rules, { _, _, _, _, _ ->
            called = true
            SourceResult.Success(ChapterPage("", "unexpected"))
        })

        assertThat(fetcher.fetch(chapter("one", 0))).isInstanceOf(SourceResult.Failure::class.java)
        assertThat(called).isFalse()
    }

    @Test fun ruleBasedFetcherPassesTheCurrentBookAndSourceCatalog() = runBlocking {
        val rules = FakeRules(StoredSourceRule("source", RULE_JSON))
        val dao = FakeChapterDao(chapter("one", 0), chapter("two", 1),
            chapter("other-book", 0).copy(bookId = "other-book"),
            chapter("other-source", 0).copy(sourceId = "other-source"))
        val fetcher = RuleBasedChapterContentFetcher(rules, { _, _, _, _, context ->
            assertThat(context.knownChapterUrls).containsExactly("https://example.test/one", "https://example.test/two")
            SourceResult.Success(ChapterPage("第一章", "正文"))
        }, chapterUrls = { bookId, sourceId -> dao.getChapters(bookId, sourceId).mapNotNull { it.url } })

        assertThat(fetcher.fetch(chapter("one", 0))).isEqualTo(SourceResult.Success("正文"))
    }

    @Test fun ruleBasedFetcherCatalogFailureStopsBeforeNetwork() = runBlocking {
        var called = false
        val fetcher = RuleBasedChapterContentFetcher(FakeRules(StoredSourceRule("source", RULE_JSON)),
            { _, _, _, _, _ -> called = true; SourceResult.Success(ChapterPage("第一章", "unexpected")) },
            chapterUrls = { _, _ -> throw java.io.IOException("database unavailable") })

        assertThat(fetcher.fetch(chapter("one", 0))).isEqualTo(
            SourceResult.Failure(SourceFailure.UnsafeRule("Chapter catalog is unavailable")))
        assertThat(called).isFalse()
    }

    @Test fun ruleBasedFetcherCatalogCancellationIsRethrownBeforeNetwork() {
        var called = false
        val fetcher = RuleBasedChapterContentFetcher(FakeRules(StoredSourceRule("source", RULE_JSON)),
            { _, _, _, _, _ -> called = true; SourceResult.Success(ChapterPage("第一章", "unexpected")) },
            chapterUrls = { _, _ -> throw CancellationException("catalog cancelled") })

        val cancelled = assertThrows(CancellationException::class.java) {
            runBlocking { fetcher.fetch(chapter("one", 0)) }
        }
        assertThat(cancelled.message).isEqualTo("catalog cancelled")
        assertThat(called).isFalse()
    }

    @Test fun ruleBasedFetcherPageCancellationIsRethrown() {
        val fetcher = RuleBasedChapterContentFetcher(FakeRules(StoredSourceRule("source", RULE_JSON)),
            { _, _, _, _, _ -> throw CancellationException("page cancelled") })

        val cancelled = assertThrows(CancellationException::class.java) {
            runBlocking { fetcher.fetch(chapter("one", 0)) }
        }
        assertThat(cancelled.message).isEqualTo("page cancelled")
    }

    @Test fun ruleBasedFetcherRejectsStoredRuleSourceMismatchBeforeNetwork() = runBlocking {
        val rules = FakeRules(StoredSourceRule("source", RULE_JSON.replace("\"id\":\"source\"", "\"id\":\"other\"")))
        var called = false
        val fetcher = RuleBasedChapterContentFetcher(rules, { _, _, _, _, _ ->
            called = true
            SourceResult.Success(ChapterPage("第一章", "unexpected"))
        })

        assertThat(fetcher.fetch(chapter("one", 0))).isInstanceOf(SourceResult.Failure::class.java)
        assertThat(called).isFalse()
    }

    @Test fun fetcherHttpFlowUsesCurrentScopedCatalogAndRetainsRealContinuationPages() {
        MockWebServer().use { server ->
            server.start()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val body = when (request.requestUrl!!.encodedPath) {
                        "/chapter/1" -> "<h1>第一章</h1><div id=content>首段</div><a class=next href='/chapter/1-page2'>下一页</a>"
                        "/chapter/1-page2" -> "<h1>第一章 (2/2)</h1><div id=content>尾段</div><a class=next href='/chapter/2'>下一页</a>"
                        "/chapter/2" -> "<h1>第一章</h1><div id=content>另一章正文</div>"
                        else -> return MockResponse().setResponseCode(404)
                    }
                    return MockResponse().addHeader("Content-Type", "text/html; charset=utf-8").setBody(body)
                }
            }
            val first = chapter("one", 0).copy(url = server.url("/chapter/1").toString())
            val continuation = server.url("/chapter/1-page2").toString()
            val dao = FakeChapterDao(first,
                chapter("other-book", 0).copy(bookId = "other-book", url = continuation),
                chapter("other-source", 0).copy(sourceId = "other-source", url = continuation))
            val raw = RULE_JSON.replace("https://example.test", server.url("/").toString().trimEnd('/'))
                .replace("\"content\":\"#content\"", "\"content\":\"#content\",\"pagination\":true,\"nextPage\":\"a.next\"")
            val engine = realSourceEngine()
            val fetcher = RuleBasedChapterContentFetcher(FakeRules(StoredSourceRule("source", raw, allowCleartext = true)),
                ChapterPageFetcher { rule, sourceId, url, allowed, context -> engine.chapter(rule, sourceId, url, allowed, context) },
                chapterUrls = { bookId, sourceId -> dao.getChapters(bookId, sourceId).mapNotNull { it.url } })

            val result = runBlocking { withTimeout(10_000) {
                dao.upsert(chapter("two", 1).copy(url = server.url("/chapter/2").toString()))
                fetcher.fetch(first)
            } }

            assertThat(result).isEqualTo(SourceResult.Success("首段\n\n尾段"))
            assertThat(server.requestCount).isEqualTo(2)
        }
    }

    private fun realSourceEngine(): SourceEngine {
        val selector = HtmlSelector()
        val scripts = object : ScriptExecutor {
            override suspend fun transform(input: String, script: String): SourceResult<String> = SourceResult.Success(input)
        }
        return SourceEngine(SearchRequestFactory(), SourceHttpClient(), SearchPageParser(selector, scripts),
            BookPageParser(selector, scripts), TocPageParser(selector, scripts), ChapterPageParser(selector, scripts),
            selector, scripts)
    }

    private fun realFetcher(server: MockWebServer, dao: ChapterDao, enabled: Boolean = true): ChapterContentFetcher {
        val raw = RULE_JSON.replace("https://example.test", server.url("/").toString().trimEnd('/'))
            .replace("\"content\":\"#content\"", "\"content\":\"#content\",\"pagination\":true,\"nextPage\":\"a.next\"")
        val engine = realSourceEngine()
        return RuleBasedChapterContentFetcher(FakeRules(StoredSourceRule("source", raw, enabled = enabled, allowCleartext = true)),
            ChapterPageFetcher { rule, sourceId, url, allowed, context -> engine.chapter(rule, sourceId, url, allowed, context) },
            chapterUrls = { bookId, sourceId -> dao.getChapters(bookId, sourceId).mapNotNull { it.url } })
    }

    private suspend fun storedChapter(files: ChapterFileStore, id: String, index: Int, content: String,
        state: String = "CACHED"): ChapterEntity {
        val saved = files.write("book", "source", id, content)
        return chapter(id, index).copy(localContentState = state, localFilePath = saved.relativePath,
            contentSha256 = saved.sha256, contentBytes = saved.byteCount)
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
            expectedUrl: String, state: String, path: String, sha256: String, byteCount: Long, contentFormatVersion: Int): Int {
            beforeMetadataUpdate?.invoke()
            val key = Triple(bookId, sourceId, chapterId)
            val row = rows[key]?.takeIf { it.url == expectedUrl } ?: return 0
            rows[key] = row.copy(localContentState = if (row.localContentState == "DOWNLOADED") "DOWNLOADED" else state,
                localFilePath = path, contentSha256 = sha256, contentBytes = byteCount,
                contentFormatVersion = contentFormatVersion)
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
