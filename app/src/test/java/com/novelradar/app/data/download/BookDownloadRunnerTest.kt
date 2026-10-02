package com.novelradar.app.data.download

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.rule.CrawlRuleDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BookDownloadRunnerTest {
    @Test fun exhaustedNetworkRetryCheckpointNeverFetchesTheFailedChapterAgain() = runTest {
        val f = Fixture()
        f.chapters.verified.putAll(mapOf("c0" to 10L, "c2" to 10L))
        f.store.save(exhaustedCheckpoint(networkRetryOnly = true))
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.PARTIAL)
        assertThat(f.chapters.fetched).isEmpty()
        val recovered = f.store.get("task")!!
        assertThat(recovered.networkAttempts).isEqualTo(3)
        assertThat(recovered.failedChapterIds).isEqualTo("c1")
        assertThat(recovered.completedCount).isEqualTo(2)
    }

    @Test fun exhaustedInitialPassStillFetchesNeverAttemptedChapterAndKeepsVerifiedFiles() = runTest {
        val f = Fixture(); f.chapters.verified["c0"] = 10
        f.store.save(exhaustedCheckpoint(networkRetryOnly = false))
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.PARTIAL)
        assertThat(f.chapters.fetched).containsExactly("c2")
        assertThat(f.chapters.verified.keys).containsExactly("c0", "c2")
        assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(3)
        assertThat(f.store.get("task")!!.failedChapterIds).isEqualTo("c1")
    }

    @Test fun verifiedFileCanCompleteExhaustedRetryCheckpointWithoutNetwork() = runTest {
        val f = Fixture(); f.chapters.verified.putAll(mapOf("c0" to 10L, "c1" to 10L, "c2" to 10L))
        f.store.save(exhaustedCheckpoint(networkRetryOnly = true))
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.COMPLETED)
        assertThat(f.chapters.fetched).isEmpty()
        assertThat(f.store.get("task")!!.failedChapterIds).isEmpty()
        assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(3)
    }

    @Test fun lowerSourceAttemptLimitAlsoStopsAutomaticProcessRestartRetry() = runTest {
        val f = Fixture(CrawlPolicy.from(CrawlRuleDto(maxAttempts = 1)))
        f.chapters.verified.putAll(mapOf("c0" to 10L, "c2" to 10L))
        f.store.save(exhaustedCheckpoint(networkRetryOnly = true).copy(networkAttempts = 1))
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.PARTIAL)
        assertThat(f.chapters.fetched).isEmpty()
        assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(1)
    }

    @Test fun initialPassNewChapterFailureDoesNotIncreaseAnExhaustedLowerSourceCap() = runTest {
        val f = Fixture(CrawlPolicy.from(CrawlRuleDto(maxAttempts = 1)))
        f.chapters.verified["c0"] = 10
        f.chapters.failures["c2"] = SourceFailure.Timeout
        f.store.save(exhaustedCheckpoint(networkRetryOnly = false).copy(networkAttempts = 1))
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.PARTIAL)
        assertThat(f.chapters.fetched).containsExactly("c2")
        assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(1)
    }

    @Test fun explicitNewWorkMayRetryFailedChapterAfterAutomaticCapWasReached() = runTest {
        val f = Fixture(); f.chapters.verified.putAll(mapOf("c0" to 10L, "c2" to 10L))
        // Mirrors the coordinator's explicit resume transition: new work/generation, cleared automatic retry IDs.
        f.store.save(exhaustedCheckpoint(networkRetryOnly = false).copy(state = "QUEUED", retryFailedOnly = true,
            networkFailedChapterIds = "", generation = 2, workId = "manual-work"))
        assertThat(f.runner.run("task", "manual-work", 2)).isEqualTo(DownloadRunResult.COMPLETED)
        assertThat(f.chapters.fetched).containsExactly("c1")
        assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(3)
    }

    @Test fun thirdLiveRoundMayAttemptAllPreviouslyFailedChaptersBeforeItEnds() = runTest {
        val f = Fixture(); f.chapters.verified["c0"] = 10
        f.chapters.failures.putAll(mapOf("c1" to SourceFailure.Timeout, "c2" to SourceFailure.Timeout))
        f.store.save(exhaustedCheckpoint(networkRetryOnly = true).copy(state = "QUEUED", networkAttempts = 2,
            failedChapterIds = "c1\nc2", networkFailedChapterIds = "c1\nc2"))
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.PARTIAL)
        assertThat(f.chapters.fetched).containsExactly("c1", "c2").inOrder()
        assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(3)
    }

    private fun exhaustedCheckpoint(networkRetryOnly: Boolean) = task().copy(state = "RUNNING", totalCount = 3,
        completedCount = 1, failedCount = 1, failedChapterIds = "c1", networkFailedChapterIds = "c1",
        currentChapterId = "c1", networkAttempts = 3, networkRetryOnly = networkRetryOnly)
    @Test fun sourceProtectionAndInvalidRulesPauseWithoutProbingLaterChapters() = runTest {
        for (error in listOf(SourceFailure.VerificationRequired, SourceFailure.UnsafeRule("TLS or source rejected"),
            SourceFailure.Http(401), SourceFailure.Http(403), SourceFailure.Http(429))) {
            val f = Fixture(); f.chapters.failures["c1"] = error
            assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.PAUSED)
            assertThat(f.chapters.fetched).containsExactly("c0", "c1").inOrder()
            assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(0)
            assertThat(f.store.get("task")!!.state).isEqualTo("PAUSED")
            assertThat(f.store.get("task")!!.lastError).isNotEmpty()
        }
    }
    @Test fun processDeathDuringInitialPassStillDownloadsNeverAttemptedChapters() = runTest {
        val f = Fixture(); f.chapters.verified["c0"] = 10
        f.store.save(task().copy(state = "RUNNING", totalCount = 3, completedCount = 1, failedCount = 1,
            failedChapterIds = "c1", networkFailedChapterIds = "c1", currentChapterId = "c1", networkAttempts = 1))
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.COMPLETED)
        assertThat(f.chapters.fetched).containsExactly("c1", "c2").inOrder()
    }
    @Test fun networkAttemptIsDurableAtTheFailedChapterCheckpoint() = runTest {
        val f = Fixture(); f.chapters.failures["c1"] = SourceFailure.Timeout
        f.runner.run("task", "work", 1)
        val atFailure = f.store.checkpoints.first { it.currentChapterId == "c1" }
        assertThat(atFailure.networkAttempts).isEqualTo(1)
        assertThat(atFailure.networkFailedChapterIds).isEqualTo("c1")
    }

    @Test fun parseAndUnexpectedFailuresAreNotRetriedWithNetworkFailures() = runTest {
        val f = Fixture()
        f.chapters.failures["c0"] = SourceFailure.Parse("content", "gone")
        f.chapters.failures["c1"] = SourceFailure.Timeout
        f.chapters.failures["c2"] = SourceFailure.Unexpected("oversized parse output")
        f.runner.run("task", "work", 1); f.chapters.fetched.clear()
        f.runner.run("task", "work", 1)
        assertThat(f.chapters.fetched).containsExactly("c1")
        assertThat(f.store.get("task")!!.failedChapterIds).isEqualTo("c0\nc1\nc2")
    }
    @Test fun resumesVerifiedFilesAndCheckpointsEveryChapter() = runTest {
        val f = Fixture()
        f.chapters.verified["c0"] = 12
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.COMPLETED)
        assertThat(f.chapters.fetched).containsExactly("c1", "c2").inOrder()
        val final = f.store.get("task")!!
        assertThat(final.completedCount).isEqualTo(3)
        assertThat(final.bytesDownloaded).isEqualTo(32)
        assertThat(final.state).isEqualTo("COMPLETED")
        assertThat(f.store.checkpoints.mapNotNull { it.currentChapterId }).containsAtLeast("c0", "c1", "c2")
    }

    @Test fun networkRetriesOnlyFailuresAndStopsAtPersistedCap() = runTest {
        val f = Fixture()
        f.chapters.failures["c1"] = SourceFailure.Timeout
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.RETRY)
        assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(1)
        f.chapters.fetched.clear()
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.RETRY)
        assertThat(f.chapters.fetched).containsExactly("c1")
        f.chapters.fetched.clear()
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.PARTIAL)
        assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(3)
        assertThat(f.chapters.fetched).containsExactly("c1")
    }

    @Test fun parseFailureContinuesAndManualFailedOnlyDoesNotFetchSuccesses() = runTest {
        val f = Fixture()
        f.chapters.failures["c1"] = SourceFailure.Parse("content", ".gone")
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.PARTIAL)
        assertThat(f.store.get("task")!!.failedChapterIds).isEqualTo("c1")
        assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(0)
        f.store.save(f.store.get("task")!!.copy(state = "QUEUED", retryFailedOnly = true, generation = 2, workId = "new"))
        f.chapters.failures.clear(); f.chapters.fetched.clear()
        assertThat(f.runner.run("task", "new", 2)).isEqualTo(DownloadRunResult.COMPLETED)
        assertThat(f.chapters.fetched).containsExactly("c1")
    }

    @Test fun diskFullPausesWithoutNetworkAttemptOrDeletingCompletedFiles() = runTest {
        val f = Fixture()
        f.chapters.failures["c1"] = SourceFailure.Storage("No space left on device")
        assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.PAUSED)
        assertThat(f.store.get("task")!!.state).isEqualTo("PAUSED")
        assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(0)
        assertThat(f.store.get("task")!!.completedCount).isEqualTo(1)
        assertThat(f.chapters.verified.keys).containsExactly("c0")
    }

    @Test fun staleCanceledOrNewGenerationWorkerNeverStartsFetching() = runTest {
        for (row in listOf(task().copy(state = "CANCELED"), task().copy(state = "PAUSED"), task().copy(generation = 2))) {
            val f = Fixture(); f.store.save(row)
            assertThat(f.runner.run("task", "work", 1)).isEqualTo(DownloadRunResult.OBSOLETE)
            assertThat(f.chapters.fetched).isEmpty()
        }
    }

    @Test fun cancellationIsRecoverableAndDoesNotCountAsNetworkFailure() = runTest {
        val f = Fixture(); val entered = CompletableDeferred<Unit>()
        f.chapters.onFetch = { entered.complete(Unit); CompletableDeferred<Unit>().await() }
        val running = async { f.runner.run("task", "work", 1) }
        entered.await(); running.cancelAndJoin()
        assertThat(f.store.get("task")!!.state).isEqualTo("PAUSED")
        assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(0)
    }

    @Test fun foregroundFailurePausesRecoverablyAndDoesNotConsumeNetworkAttempts() = runTest {
        val f = Fixture()
        f.runner.pauseForPlatform("task", "work", 1, "Foreground start unavailable")
        assertThat(f.store.get("task")!!.state).isEqualTo("PAUSED")
        assertThat(f.store.get("task")!!.networkAttempts).isEqualTo(0)
    }

    @Test fun rawOneThousandMeansOneSecondAndInvalidIntervalsAreSafe() {
        assertThat(CrawlPolicy.from(CrawlRuleDto(minInterval = 1000, maxInterval = 1000)).intervalMillis).isEqualTo(1000)
        assertThat(CrawlPolicy.from(null).intervalMillis).isEqualTo(1000)
        assertThat(CrawlPolicy.from(CrawlRuleDto(minInterval = -1, maxInterval = Int.MAX_VALUE)).intervalMillis).isAtMost(60_000)
        assertThat(CrawlPolicy.from(CrawlRuleDto(maxAttempts = 100)).maxAttempts).isEqualTo(3)
    }

    @Test fun sameSourceLimiterSerializesBooksAndEnforcesMilliseconds() = runTest {
        val intervals = mutableListOf<Long>(); var clock = 0L
        val limiter = SourceDownloadLimiter(now = { clock }, wait = { intervals += it; clock += it })
        val policy = CrawlPolicy.from(CrawlRuleDto(minInterval = 1000, maxInterval = 1000))
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val first = async { limiter.fetch("source", policy) { entered.complete(Unit); release.await() } }
        entered.await()
        var secondEntered = false
        val second = async { limiter.fetch("source", policy) { secondEntered = true } }
        testScheduler.runCurrent(); assertThat(secondEntered).isFalse()
        release.complete(Unit); first.await(); second.await()
        assertThat(intervals).containsExactly(1000L)
    }

    private class Fixture(crawlPolicy: CrawlPolicy = CrawlPolicy.from(null)) {
        val store = MemoryDownloadStore().apply { rows["task"] = task() }
        val chapters = FakeChapters()
        val runner = BookDownloadRunner(store, chapters, BookDownloadLeases(),
            SourceDownloadLimiter(now = { 100 }, wait = {}), policy = { crawlPolicy }, now = { 100 })
    }

    private class FakeChapters : DownloadChapters {
        val fetched = mutableListOf<String>(); val verified = mutableMapOf<String, Long>()
        val failures = mutableMapOf<String, SourceFailure>(); var onFetch: (suspend () -> Unit)? = null
        override suspend fun catalog(bookId: String, sourceId: String) = (0..2).map {
            ChapterEntity(bookId, sourceId, "c$it", it, "Chapter $it", "https://example.test/c$it")
        }
        override suspend fun verifiedBytes(chapter: ChapterEntity) = verified[chapter.chapterId]
        override suspend fun download(chapter: ChapterEntity): SourceResult<Long> {
            fetched += chapter.chapterId; onFetch?.invoke()
            return failures[chapter.chapterId]?.let { SourceResult.Failure(it) }
                ?: SourceResult.Success(10L).also { verified[chapter.chapterId] = 10 }
        }
    }
}
