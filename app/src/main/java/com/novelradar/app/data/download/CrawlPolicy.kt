package com.novelradar.app.data.download

import com.novelradar.app.source.rule.CrawlRuleDto
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class CrawlPolicy(val intervalMillis: Long, val retryIntervalMillis: Long, val maxAttempts: Int) {
    companion object {
        fun from(crawl: CrawlRuleDto?): CrawlPolicy = CrawlPolicy(
            intervalMillis = interval(crawl?.minInterval, crawl?.maxInterval),
            retryIntervalMillis = interval(crawl?.retryMinInterval, crawl?.retryMaxInterval),
            maxAttempts = (crawl?.maxAttempts ?: 3).coerceIn(1, 3))
        private fun interval(min: Int?, max: Int?): Long = (listOfNotNull(min, max)
            .filter { it > 0 }.maxOrNull()?.toLong() ?: 1000L).coerceIn(1L, 60_000L)
    }
}

class SourceDownloadLimiter(private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    private val wait: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }) {
    private class SourceGate { val lock = Mutex(); var lastFinished: Long? = null }
    private val sources = ConcurrentHashMap<String, SourceGate>()
    suspend fun <T> fetch(sourceId: String, policy: CrawlPolicy, block: suspend () -> T): T {
        val source = sources.getOrPut(sourceId) { SourceGate() }
        return source.lock.withLock {
            source.lastFinished?.let { last ->
                val remaining = policy.intervalMillis - (now() - last).coerceAtLeast(0)
                if (remaining > 0) wait(remaining)
            }
            try { block() } finally { source.lastFinished = now() }
        }
    }
}
