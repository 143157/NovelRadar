package com.novelradar.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.novelradar.app.domain.model.DownloadPreferences
import com.novelradar.app.domain.repository.DownloadSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class DataStoreDownloadSettingsRepository(private val store: DataStore<Preferences>) : DownloadSettingsRepository {
    override val preferences: Flow<DownloadPreferences> = store.data.map { DownloadPreferences(it[ALLOW_METERED] ?: false) }
    override suspend fun setAllowMetered(allowMetered: Boolean) { store.edit { it[ALLOW_METERED] = allowMetered } }

    private companion object { val ALLOW_METERED = booleanPreferencesKey("download_default_allow_metered") }
}
