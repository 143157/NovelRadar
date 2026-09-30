package com.novelradar.app.domain.repository

import com.novelradar.app.domain.model.ReaderPreferences
import kotlinx.coroutines.flow.Flow

interface ReaderSettingsRepository {
    val preferences: Flow<ReaderPreferences>
    suspend fun update(transform: (ReaderPreferences) -> ReaderPreferences)
}
