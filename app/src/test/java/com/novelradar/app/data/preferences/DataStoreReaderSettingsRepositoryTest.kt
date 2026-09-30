package com.novelradar.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.ReaderMode
import com.novelradar.app.domain.model.ReaderTheme
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Test

class DataStoreReaderSettingsRepositoryTest {
    @Test fun updatesClampBoundsAndPersistEnumsAndMeteredOptIn() = runBlocking {
        val store = MemoryPreferencesStore()
        val repository = DataStoreReaderSettingsRepository(store)

        repository.update {
            it.copy(fontSizeSp = 99f, lineHeight = 0.5f, horizontalMarginDp = 99,
                brightness = 0f, theme = ReaderTheme.NIGHT, mode = ReaderMode.PAGED,
                prefetchCount = 99, allowMeteredPrefetch = true)
        }
        val saved = DataStoreReaderSettingsRepository(store).preferences.first()
        assertThat(saved.fontSizeSp).isEqualTo(36f)
        assertThat(saved.lineHeight).isEqualTo(1.2f)
        assertThat(saved.horizontalMarginDp).isEqualTo(48)
        assertThat(saved.brightness).isEqualTo(0.05f)
        assertThat(saved.theme).isEqualTo(ReaderTheme.NIGHT)
        assertThat(saved.mode).isEqualTo(ReaderMode.PAGED)
        assertThat(saved.prefetchCount).isEqualTo(2)
        assertThat(saved.allowMeteredPrefetch).isTrue()

        repository.update { it.copy(fontSizeSp = 1f, lineHeight = 99f,
            horizontalMarginDp = -1, brightness = null, prefetchCount = -9) }
        val lower = repository.preferences.first()
        assertThat(lower.fontSizeSp).isEqualTo(14f)
        assertThat(lower.lineHeight).isEqualTo(2.2f)
        assertThat(lower.horizontalMarginDp).isEqualTo(8)
        assertThat(lower.brightness).isNull()
        assertThat(lower.prefetchCount).isEqualTo(0)
    }

    @Test fun invalidStoredValuesAreNormalizedWhenRead() = runBlocking {
        val store = MemoryPreferencesStore()
        store.edit {
            it[floatPreferencesKey("reader_font_size_sp")] = Float.NaN
            it[floatPreferencesKey("reader_line_height")] = 9f
            it[intPreferencesKey("reader_horizontal_margin_dp")] = -4
            it[floatPreferencesKey("reader_brightness")] = Float.POSITIVE_INFINITY
            it[stringPreferencesKey("reader_theme")] = "UNKNOWN"
            it[stringPreferencesKey("reader_mode")] = "UNKNOWN"
            it[intPreferencesKey("reader_prefetch_count")] = 8
            it[booleanPreferencesKey("reader_allow_metered_prefetch")] = false
        }

        val loaded = DataStoreReaderSettingsRepository(store).preferences.first()
        assertThat(loaded.fontSizeSp).isEqualTo(20f)
        assertThat(loaded.lineHeight).isEqualTo(2.2f)
        assertThat(loaded.horizontalMarginDp).isEqualTo(8)
        assertThat(loaded.brightness).isNull()
        assertThat(loaded.theme).isEqualTo(ReaderTheme.PAPER)
        assertThat(loaded.mode).isEqualTo(ReaderMode.SCROLL)
        assertThat(loaded.prefetchCount).isEqualTo(2)
        assertThat(loaded.allowMeteredPrefetch).isFalse()
    }

    @Test fun concurrentUpdatesUseLatestStoredSnapshot() = runBlocking {
        val store = MemoryPreferencesStore()
        val repository = DataStoreReaderSettingsRepository(store)

        coroutineScope {
            repeat(8) { launch { repository.update { it.copy(fontSizeSp = it.fontSizeSp + 1f) } } }
        }

        assertThat(repository.preferences.first().fontSizeSp).isEqualTo(28f)
    }

    private class MemoryPreferencesStore : DataStore<Preferences> {
        private val state = MutableStateFlow<Preferences>(emptyPreferences())
        private val mutex = Mutex()
        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = mutex.withLock {
            transform(state.value).also { state.value = it }
        }
    }
}
