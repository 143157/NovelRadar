package com.novelradar.app.data.download

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.data.db.entity.DownloadTaskEntity
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BookDownloadWorkerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun downloadedProgressProducesForegroundDataSyncAndCompletion() = runBlocking {
        val f = Fixture()
        val worker = f.worker()
        assertThat(worker.doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(f.store.row.state).isEqualTo("COMPLETED")
        assertThat(f.foreground).isNotEmpty()
        if (android.os.Build.VERSION.SDK_INT >= 29) assertThat(f.foreground.first().foregroundServiceType)
            .isEqualTo(android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    @Test fun onlyRetryableNetworkFailuresReturnRetry() = runBlocking {
        val f = Fixture(SourceFailure.Timeout)
        assertThat(f.worker().doWork()).isEqualTo(ListenableWorker.Result.retry())
        assertThat(f.store.row.networkAttempts).isEqualTo(1)
        val parse = Fixture(SourceFailure.Parse("content", "gone"))
        assertThat(parse.worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(parse.store.row.state).isEqualTo("PARTIAL")
    }

    @Test fun foregroundFailurePersistsRecoverablePauseWithoutNetworkAttempt() = runBlocking {
        val f = Fixture(); f.failForeground = true
        assertThat(f.worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(f.store.row.state).isEqualTo("PAUSED")
        assertThat(f.store.row.networkAttempts).isEqualTo(0)
        assertThat(f.fetches).isEqualTo(0)
    }

    @Test fun oldWorkIdNeverFetchesAfterDurableCancellation() = runBlocking {
        val f = Fixture(); f.store.row = f.store.row.copy(state = "CANCELED", generation = 2)
        assertThat(f.worker().doWork()).isEqualTo(ListenableWorker.Result.success())
        assertThat(f.fetches).isEqualTo(0)
        assertThat(f.foreground).isEmpty()
    }

    private inner class Fixture(private val failure: SourceFailure? = null) {
        val store = Store()
        var fetches = 0; var failForeground = false
        val foreground = mutableListOf<ForegroundInfo>()
        private val access = object : DownloadChapters {
            override suspend fun catalog(bookId: String, sourceId: String) = listOf(ChapterEntity(bookId, sourceId, "c1", 0, "One", "https://example.test/one"))
            override suspend fun verifiedBytes(chapter: ChapterEntity): Long? = null
            override suspend fun download(chapter: ChapterEntity): SourceResult<Long> {
                fetches++
                return failure?.let { SourceResult.Failure(it) } ?: SourceResult.Success(20L)
            }
        }
        private val runner = BookDownloadRunner(store, access, BookDownloadLeases(),
            SourceDownloadLimiter(wait = {}), { CrawlPolicy.from(null) })

        fun worker(): BookDownloadWorker {
            val factory = object : WorkerFactory() {
                override fun createWorker(appContext: Context, workerClassName: String, workerParameters: WorkerParameters): ListenableWorker =
                    BookDownloadWorker(appContext, workerParameters, runner, store, DownloadNotificationFactory(context)) { info ->
                        if (failForeground) throw IllegalStateException("platform blocked foreground")
                        foreground += info
                    }
            }
            val worker = TestListenableWorkerBuilder<BookDownloadWorker>(context)
                .setInputData(workDataOf(BookDownloadWorker.TASK_ID to "task", BookDownloadWorker.GENERATION to 1L))
                .setWorkerFactory(factory).build()
            store.row = store.row.copy(workId = worker.id.toString())
            return worker
        }
    }

    private class Store : DownloadStore {
        var row = DownloadTaskEntity("task", "book", "source", "QUEUED", 0, 0, 0, 1, 1, generation = 1)
        override suspend fun all() = listOf(row)
        override suspend fun get(id: String) = row.takeIf { it.taskId == id }
        override suspend fun save(task: DownloadTaskEntity) { row = task }
        override suspend fun mutate(id: String, transform: (DownloadTaskEntity) -> DownloadTaskEntity) = get(id)?.let(transform)?.also { row = it }
        override suspend fun checkpoint(task: DownloadTaskEntity): Boolean {
            if (row.workId != task.workId || row.generation != task.generation || row.state !in setOf("QUEUED", "RUNNING")) return false
            row = task; return true
        }
    }
}
