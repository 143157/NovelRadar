package com.novelradar.app.data.repository

import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.repository.ReadingProgressRepository
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Reuses the existing BookRepository/Room row instead of maintaining a second progress store. */
class RoomReadingProgressRepository(private val books: BookRepository) : ReadingProgressRepository {
    private val writeMutex = Mutex()
    private val exactGenerations = ConcurrentHashMap<String, AtomicLong>()

    override fun progress(bookId: String): Flow<ReadingLocation?> = books.progress(bookId)

    override suspend fun saveExact(location: ReadingLocation) {
        writeMutex.withLock {
            generation(location.bookId).incrementAndGet()
            books.saveProgress(location)
        }
    }

    @OptIn(FlowPreview::class)
    override suspend fun collectPositionChanges(changes: Flow<ReadingLocation>) {
        changes.map { location -> PositionChange(location, generation(location.bookId).get()) }
            .debounce(POSITION_DEBOUNCE_MILLIS)
            .distinctUntilChangedBy { change ->
                with(change.location) {
                    listOf(bookId, sourceId, chapterId, chapterIndex, offset, change.exactGeneration)
                }
            }
            .collect { change ->
                writeMutex.withLock {
                    if (generation(change.location.bookId).get() == change.exactGeneration) {
                        books.saveProgress(change.location)
                    }
                }
            }
    }

    private fun generation(bookId: String) = exactGenerations.getOrPut(bookId) { AtomicLong() }

    private data class PositionChange(val location: ReadingLocation, val exactGeneration: Long)

    private companion object { const val POSITION_DEBOUNCE_MILLIS = 750L }
}
