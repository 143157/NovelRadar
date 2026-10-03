package com.novelradar.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Test

class DataStoreDownloadSettingsRepositoryTest {
    @Test fun firstUseIsUnmeteredAndExplicitOptInSurvivesRepositoryRecreation() = runBlocking {
        val store = MemoryStore()
        val repository = DataStoreDownloadSettingsRepository(store)
        assertThat(repository.preferences.first().allowMetered).isFalse()
        repository.setAllowMetered(true)
        assertThat(DataStoreDownloadSettingsRepository(store).preferences.first().allowMetered).isTrue()
        repository.setAllowMetered(false)
        assertThat(DataStoreDownloadSettingsRepository(store).preferences.first().allowMetered).isFalse()
    }

    private class MemoryStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }
}
