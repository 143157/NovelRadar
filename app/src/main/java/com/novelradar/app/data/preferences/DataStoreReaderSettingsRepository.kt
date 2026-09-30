package com.novelradar.app.data.preferences

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.novelradar.app.domain.model.ReaderMode
import com.novelradar.app.domain.model.ReaderPreferences
import com.novelradar.app.domain.model.ReaderTheme
import com.novelradar.app.domain.repository.ReaderSettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** All preference fields are committed in a single DataStore transaction. */
class DataStoreReaderSettingsRepository(private val dataStore: DataStore<Preferences>) : ReaderSettingsRepository {
    override val preferences: Flow<ReaderPreferences> = dataStore.data.map { it.toReaderPreferences() }

    override suspend fun update(transform: (ReaderPreferences) -> ReaderPreferences) {
        dataStore.edit { stored ->
            val updated = transform(stored.toReaderPreferences()).normalized()
            stored[FONT_SIZE] = updated.fontSizeSp
            stored[LINE_HEIGHT] = updated.lineHeight
            stored[HORIZONTAL_MARGIN] = updated.horizontalMarginDp
            if (updated.brightness == null) stored.remove(BRIGHTNESS)
            else stored[BRIGHTNESS] = updated.brightness
            stored[THEME] = updated.theme.name
            stored[MODE] = updated.mode.name
            stored[PREFETCH_COUNT] = updated.prefetchCount
            stored[ALLOW_METERED_PREFETCH] = updated.allowMeteredPrefetch
        }
    }

    private fun Preferences.toReaderPreferences() = ReaderPreferences(
        fontSizeSp = this[FONT_SIZE] ?: 20f,
        lineHeight = this[LINE_HEIGHT] ?: 1.65f,
        horizontalMarginDp = this[HORIZONTAL_MARGIN] ?: 20,
        brightness = this[BRIGHTNESS],
        theme = ReaderTheme.entries.firstOrNull { it.name == this[THEME] } ?: ReaderTheme.PAPER,
        mode = ReaderMode.entries.firstOrNull { it.name == this[MODE] } ?: ReaderMode.SCROLL,
        prefetchCount = this[PREFETCH_COUNT] ?: 2,
        allowMeteredPrefetch = this[ALLOW_METERED_PREFETCH] ?: false,
    ).normalized()

    private companion object {
        val FONT_SIZE = floatPreferencesKey("reader_font_size_sp")
        val LINE_HEIGHT = floatPreferencesKey("reader_line_height")
        val HORIZONTAL_MARGIN = intPreferencesKey("reader_horizontal_margin_dp")
        val BRIGHTNESS = floatPreferencesKey("reader_brightness")
        val THEME = stringPreferencesKey("reader_theme")
        val MODE = stringPreferencesKey("reader_mode")
        val PREFETCH_COUNT = intPreferencesKey("reader_prefetch_count")
        val ALLOW_METERED_PREFETCH = booleanPreferencesKey("reader_allow_metered_prefetch")
    }
}
