package com.novelradar.app.domain.repository

import com.novelradar.app.domain.model.DownloadPreferences
import kotlinx.coroutines.flow.Flow

interface DownloadSettingsRepository {
    val preferences: Flow<DownloadPreferences>
    suspend fun setAllowMetered(allowMetered: Boolean)
}
