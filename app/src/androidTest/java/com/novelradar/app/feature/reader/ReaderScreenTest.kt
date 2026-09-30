package com.novelradar.app.feature.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.ReaderMode
import com.novelradar.app.domain.model.ReaderTheme
import com.novelradar.app.domain.repository.ChapterOrigin
import com.novelradar.app.domain.repository.LoadedChapter
import com.novelradar.app.ui.theme.NovelRadarTheme
import org.junit.Rule
import org.junit.Test

class ReaderScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun centralTapOpensControlsAndSettingsChangeThemeAndMode() {
        val state = mutableStateOf(state())
        compose.setContent {
            NovelRadarTheme {
                ReaderScreen(state.value, onBack = {}, onPositionChanged = {}, onPrevious = {}, onNext = {},
                    onRetry = {}, onChapterSelected = {},
                    onPreferencesChanged = { transform -> state.value = state.value.copy(preferences = transform(state.value.preferences)) })
            }
        }
        compose.onNodeWithTag("reader_center_toggle").performClick()
        compose.onNodeWithTag("reader_controls").assertIsDisplayed()
        compose.onNodeWithText("阅读设置").performClick()
        compose.onNodeWithText("夜间").performScrollTo().performClick()
        compose.onNodeWithText("分页").performScrollTo().performClick()
        assertThat(state.value.preferences.theme).isEqualTo(ReaderTheme.NIGHT)
        assertThat(state.value.preferences.mode).isEqualTo(ReaderMode.PAGED)
    }

    @Test fun catalogSelectionCallsReaderChapterAction() {
        var selected: String? = null
        compose.setContent {
            NovelRadarTheme {
                ReaderScreen(state(), onBack = {}, onPositionChanged = {}, onPrevious = {}, onNext = {},
                    onRetry = {}, onChapterSelected = { selected = it }, onPreferencesChanged = {})
            }
        }
        compose.onNodeWithTag("reader_center_toggle").performClick()
        compose.onNodeWithText("目录").performClick()
        compose.onNodeWithText("第二章").performClick()
        assertThat(selected).isEqualTo("c1")
    }

    private fun state(): ReaderUiState {
        val chapters = listOf(Chapter("book", "a", "c0", 0, "第一章"), Chapter("book", "a", "c1", 1, "第二章"))
        return ReaderUiState(book = Book("book", "x", "星河旅人", "叶子"), sourceName = "书源甲",
            chapters = chapters, content = LoadedChapter(chapters.first(), "夜色渐深，星河仍明亮。\n旅人从山间走来。", ChapterOrigin.Cached), loading = false)
    }
}
