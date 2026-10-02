package com.novelradar.app.data.download

import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.data.db.entity.DownloadTaskEntity
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

interface DownloadChapters {
    suspend fun catalog(bookId: String, sourceId: String): List<ChapterEntity>
    suspend fun verifiedBytes(chapter: ChapterEntity): Long?
    suspend fun download(chapter: ChapterEntity): SourceResult<Long>
}

enum class DownloadRunResult { COMPLETED, PARTIAL, RETRY, PAUSED, OBSOLETE }

class BookDownloadRunner(private val store: DownloadStore, private val chapters: DownloadChapters,
    private val leases: BookDownloadLeases, private val limiter: SourceDownloadLimiter,
    private val policy: suspend (String) -> CrawlPolicy, private val now: () -> Long = System::currentTimeMillis) {
    suspend fun run(taskId: String, workId: String, generation: Long,
        progress: suspend (Int, Int) -> Unit = { _, _ -> }): DownloadRunResult {
        val saved = owned(taskId, workId, generation) ?: return DownloadRunResult.OBSOLETE
        return leases.withRunner(saved.bookId) {
            var task = owned(taskId, workId, generation) ?: return@withRunner DownloadRunResult.OBSOLETE
            if (leases.isDeleting(task.bookId)) return@withRunner DownloadRunResult.OBSOLETE
            try {
                val catalog = chapters.catalog(task.bookId, task.sourceId).sortedWith(compareBy(ChapterEntity::chapterIndex, ChapterEntity::chapterId))
                if (catalog.isEmpty()) {
                    pauseForPlatform(taskId, workId, generation, "没有已保存目录，请先刷新目录")
                    return@withRunner DownloadRunResult.PAUSED
                }
                val crawl = policy(task.sourceId)
                val catalogIds = catalog.map { it.chapterId }.toSet()
                val failures = ids(task.failedChapterIds).intersect(catalogIds).toMutableSet()
                val previousNetwork = ids(task.networkFailedChapterIds).intersect(catalogIds)
                val attemptedNonNetwork = ids(task.attemptedNonNetworkChapterIds).intersect(catalogIds).toMutableSet()
                // A failed-chapter checkpoint may survive process death before the final PARTIAL state.
                val exhaustedNetworkRetries = task.networkAttempts >= crawl.maxAttempts
                val selected = when {
                    task.networkRetryOnly -> previousNetwork
                    task.retryFailedOnly -> failures.toSet()
                    else -> catalogIds
                }
                val verified = linkedMapOf<String, Long>()
                val networkFailures = previousNetwork.toMutableSet()
                var countedNetworkRound = false
                val started = now()
                var newBytes = 0L
                for (chapter in catalog) {
                    currentCoroutineContext().ensureActive()
                    if (owned(taskId, workId, generation) == null || leases.isDeleting(task.bookId)) return@withRunner DownloadRunResult.OBSOLETE
                    val existing = chapters.verifiedBytes(chapter)
                    if (existing != null) { verified[chapter.chapterId] = existing; failures.remove(chapter.chapterId); networkFailures.remove(chapter.chapterId); attemptedNonNetwork.remove(chapter.chapterId) }
                }
                task = task.copy(state = "RUNNING", totalCount = catalog.size,
                    completedCount = verified.size, bytesDownloaded = verified.values.sum(), failedCount = failures.size,
                    failedChapterIds = encode(failures), networkFailedChapterIds = encode(networkFailures),
                    attemptedNonNetworkChapterIds = encode(attemptedNonNetwork))
                if (!store.checkpoint(task)) return@withRunner DownloadRunResult.OBSOLETE
                progress(verified.size, catalog.size)
                for (chapter in catalog) {
                    currentCoroutineContext().ensureActive()
                    if (owned(taskId, workId, generation) == null || leases.isDeleting(task.bookId)) return@withRunner DownloadRunResult.OBSOLETE
                    // Recheck immediately before each fetch: a reader may have cached this chapter since the scan.
                    val existing = chapters.verifiedBytes(chapter)
                    var pauseReason: String? = null
                    if (existing != null) { verified[chapter.chapterId] = existing; failures.remove(chapter.chapterId); networkFailures.remove(chapter.chapterId); attemptedNonNetwork.remove(chapter.chapterId) }
                    else if (chapter.chapterId in selected && chapter.chapterId !in attemptedNonNetwork &&
                        !(exhaustedNetworkRetries && chapter.chapterId in previousNetwork)) {
                        val requestPolicy = if (chapter.chapterId in previousNetwork) crawl.copy(
                            intervalMillis = maxOf(crawl.intervalMillis, crawl.retryIntervalMillis)) else crawl
                        val fetched = limiter.fetch(task.sourceId, requestPolicy) {
                            currentCoroutineContext().ensureActive()
                            if (owned(taskId, workId, generation) == null || leases.isDeleting(task.bookId)) return@fetch null
                            chapters.download(chapter)
                        } ?: return@withRunner DownloadRunResult.OBSOLETE
                        when (fetched) {
                            is SourceResult.Success -> { verified[chapter.chapterId] = fetched.value; newBytes += fetched.value; failures.remove(chapter.chapterId); networkFailures.remove(chapter.chapterId); attemptedNonNetwork.remove(chapter.chapterId) }
                            is SourceResult.Failure -> {
                                failures += chapter.chapterId
                                if (fetched.error.retryableNetwork()) {
                                    attemptedNonNetwork.remove(chapter.chapterId)
                                    networkFailures += chapter.chapterId
                                    if (!countedNetworkRound) {
                                        if (task.networkAttempts < crawl.maxAttempts) task = task.copy(networkAttempts = task.networkAttempts + 1)
                                        countedNetworkRound = true
                                    }
                                } else { networkFailures.remove(chapter.chapterId); attemptedNonNetwork += chapter.chapterId }
                                if (fetched.error is SourceFailure.Storage) pauseReason = "本地存储不可用，请检查空间后继续下载"
                                if (fetched.error.sourceBlocked()) pauseReason = "书源访问受限或规则不可用，请检查书源后继续下载"
                            }
                        }
                    }
                    task = task.copy(state = if (pauseReason == null) "RUNNING" else "PAUSED", completedCount = verified.size,
                        failedCount = failures.size, failedChapterIds = encode(failures),
                        networkFailedChapterIds = encode(networkFailures), attemptedNonNetworkChapterIds = encode(attemptedNonNetwork),
                        bytesDownloaded = verified.values.sum(),
                        currentChapterId = chapter.chapterId, bytesPerSecond = if (pauseReason != null) 0 else newBytes * 1000 / (now() - started).coerceAtLeast(1000),
                        lastError = pauseReason, updatedAtEpochMillis = now())
                    if (!store.checkpoint(task)) return@withRunner DownloadRunResult.OBSOLETE
                    progress(verified.size, catalog.size)
                    if (pauseReason != null) return@withRunner DownloadRunResult.PAUSED
                }
                val attempts = task.networkAttempts
                val retry = networkFailures.isNotEmpty() && attempts < crawl.maxAttempts
                val state = when { retry -> "QUEUED"; verified.size == catalog.size -> "COMPLETED"; else -> "PARTIAL" }
                task = task.copy(state = state, networkAttempts = attempts, networkRetryOnly = retry, bytesPerSecond = 0,
                    networkFailedChapterIds = encode(networkFailures), lastError = if (failures.isEmpty()) null else "部分章节下载失败，可单独重试失败章节",
                    updatedAtEpochMillis = now())
                if (!store.checkpoint(task)) return@withRunner DownloadRunResult.OBSOLETE
                when (state) { "QUEUED" -> DownloadRunResult.RETRY; "COMPLETED" -> DownloadRunResult.COMPLETED; else -> DownloadRunResult.PARTIAL }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { pauseForPlatform(taskId, workId, generation, "下载已中断，已保存进度，请继续下载") }
                throw cancelled
            } catch (error: IOException) {
                pauseForPlatform(taskId, workId, generation, "本地存储不可用，请检查空间后继续下载")
                DownloadRunResult.PAUSED
            } catch (_: Exception) {
                pauseForPlatform(taskId, workId, generation, "下载暂时无法继续，已保存进度，请重试")
                DownloadRunResult.PAUSED
            }
        }
    }

    suspend fun pauseForPlatform(taskId: String, workId: String, generation: Long, message: String) {
        val task = owned(taskId, workId, generation) ?: return
        store.transitionOwned(task) { it.copy(state = "PAUSED", bytesPerSecond = 0, lastError = message, updatedAtEpochMillis = now()) }
    }

    private suspend fun owned(taskId: String, workId: String, generation: Long): DownloadTaskEntity? = store.get(taskId)?.takeIf {
        it.workId == workId && it.generation == generation && it.state in setOf("QUEUED", "RUNNING")
    }

    private fun SourceFailure.retryableNetwork() = this is SourceFailure.Network || this == SourceFailure.Timeout ||
        this is SourceFailure.Http && (code == 408 || code in 500..599)
    private fun SourceFailure.sourceBlocked() = this == SourceFailure.VerificationRequired || this is SourceFailure.UnsafeRule ||
        this is SourceFailure.Http && code in setOf(401, 403, 429)
    private fun ids(value: String) = value.split('\n').filter(String::isNotBlank).toSet()
    private fun encode(values: Set<String>) = values.sorted().joinToString("\n")
}
