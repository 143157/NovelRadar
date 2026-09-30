package com.novelradar.app.data.repository

import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.repository.ReadingProgressRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reuses the existing BookRepository/Room row instead of maintaining a second progress store. */
class RoomReadingProgressRepository(private val books: BookRepository) : ReadingProgressRepository {
    private val writeMutex = Mutex()
    private val exactGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val latestExactAt = ConcurrentHashMap<String, Long>()

    override fun progress(bookId: String): Flow<ReadingLocation?> = books.progress(bookId)

    override suspend fun saveExact(location: ReadingLocation) {
        writeMutex.withLock {
            books.saveProgress(location)
            latestExactAt.merge(location.bookId, location.updatedAtEpochMillis, ::maxOf)
            generation(location.bookId).incrementAndGet()
        }
    }

    override suspend fun collectPositionChanges(changes: Flow<ReadingLocation>) = coroutineScope {
        val pending = ConcurrentHashMap<String, Job>()
        val lastSaved = mutableMapOf<String, PositionKey>()
        changes.collect { location ->
            if (isOlderThanExact(location)) return@collect
            val bookId = location.bookId
            val capturedGeneration = generation(bookId).get()
            pending.remove(bookId)?.cancel()
            val job = launch {
                delay(POSITION_DEBOUNCE_MILLIS)
                writeMutex.withLock {
                    val key = PositionKey(location.sourceId, location.chapterId,
                        location.chapterIndex, location.offset, capturedGeneration)
                    if (generation(bookId).get() == capturedGeneration &&
                        !isOlderThanExact(location) && lastSaved[bookId] != key) {
                        books.saveProgress(location)
                        lastSaved[bookId] = key
                    }
                }
            }
            pending[bookId] = job
            job.invokeOnCompletion { pending.remove(bookId, job) }
        }
    }

    private fun generation(bookId: String) = exactGenerations.getOrPut(bookId) { AtomicLong() }

    // Exact chapter/lifecycle saves win ties within the same wall-clock millisecond.
    private fun isOlderThanExact(location: ReadingLocation): Boolean =
        latestExactAt[location.bookId]?.let { location.updatedAtEpochMillis <= it } ?: false

    private data class PositionKey(val sourceId: String, val chapterId: String,
        val chapterIndex: Int, val offset: Int, val exactGeneration: Long)

    private companion object { const val POSITION_DEBOUNCE_MILLIS = 750L }
}
