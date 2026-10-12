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
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.model.SourceFailure
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

    @Test fun reviewNoticeKeepsOriginalPositionAndOpensCatalogWhileToolsAreHidden() {
        var selected: String? = null
        val review = state().copy(positionNeedsReview = true, originalChapterTitle = "原第二章",
            originalReadingLocation = ReadingLocation("book", "b", "c1", 1, 888, 50L, ChapterContentFormat.LEGACY),
            notice = "设置保存失败，请重试", error = SourceFailure.Timeout)
        compose.setContent {
            NovelRadarTheme {
                ReaderScreen(review, onBack = {}, onPositionChanged = {}, onPrevious = {}, onNext = {},
                    onRetry = {}, onChapterSelected = { selected = it }, onPreferencesChanged = {})
            }
        }
        compose.onNodeWithTag("reader_position_review").assertIsDisplayed()
        compose.onNodeWithText("原阅读位置：原第二章 · UTF-16 偏移 888").assertIsDisplayed()
        compose.onNodeWithText("原记录来自另一书源").assertIsDisplayed()
        compose.onNodeWithText("设置保存失败，请重试").assertIsDisplayed()
        compose.onNodeWithText("连接超时，请检查网络后重试").assertIsDisplayed()
        compose.onNodeWithText("选择章节").performClick()
        compose.onNodeWithText("第二章").performClick()
        assertThat(selected).isEqualTo("c1")
    }

    @Test fun verifiedReviewOffersConfirmationButTemporaryLegacyBodyDoesNot() {
        val review = mutableStateOf(state().copy(positionNeedsReview = true,
            originalReadingLocation = ReadingLocation("book", "a", "missing", 8, 888, 50L)))
        var confirmations = 0
        compose.setContent {
            NovelRadarTheme {
                ReaderScreen(review.value, onBack = {}, onPositionChanged = {}, onPrevious = {}, onNext = {},
                    onRetry = {}, onChapterSelected = {}, onPreferencesChanged = {}, onConfirmPosition = { confirmations++ })
            }
        }
        compose.onNodeWithText("原阅读位置：第9章 · UTF-16 偏移 888").assertIsDisplayed()
        compose.onNodeWithText("确认当前位置").performClick()
        assertThat(confirmations).isEqualTo(1)
        compose.runOnIdle { review.value = review.value.copy(notice = POSITION_CONFIRMATION_FAILURE_NOTICE) }
        compose.onNodeWithText("阅读位置确认失败，请重试确认").assertIsDisplayed()
        compose.onNodeWithText("重试返回").assertDoesNotExist()
        compose.onNodeWithText("重试确认").performClick()
        assertThat(confirmations).isEqualTo(2)
        compose.runOnIdle { review.value = review.value.copy(content = review.value.content!!.copy(boundaryVerified = false)) }
        compose.onNodeWithText("确认当前位置").assertDoesNotExist()
        compose.onNodeWithText("当前显示临时旧正文，章节范围尚未确认。原阅读位置会继续保留，请选择章节或重试加载。").assertIsDisplayed()
        compose.onNodeWithText("重试加载").assertIsDisplayed()
    }

    private fun state(): ReaderUiState {
        val chapters = listOf(Chapter("book", "a", "c0", 0, "第一章"), Chapter("book", "a", "c1", 1, "第二章"))
        return ReaderUiState(book = Book("book", "x", "星河旅人", "叶子"), sourceName = "书源甲",
            chapters = chapters, content = LoadedChapter(chapters.first(), "夜色渐深，星河仍明亮。\n旅人从山间走来。", ChapterOrigin.Cached), loading = false)
    }
}
