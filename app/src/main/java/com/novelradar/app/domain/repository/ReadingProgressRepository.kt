package com.novelradar.app.domain.repository

import kotlinx.coroutines.flow.Flow

interface ReadingProgressRepository {
    fun progress(bookId: String): Flow<ReadingLocation?>
    suspend fun saveExact(location: ReadingLocation)
    suspend fun collectPositionChanges(changes: Flow<ReadingLocation>)
}
