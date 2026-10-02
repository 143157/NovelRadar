package com.novelradar.app.feature.book

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.ui.theme.NovelRadarTheme
import org.junit.Rule
import org.junit.Test

class BookDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsBookCatalogAndSourceSwitchAction() {
        var selected: String? = null
        compose.setContent {
            NovelRadarTheme {
                BookDetailScreen(state(), onBack = {}, onSourceSelected = { selected = it },
                    onAddToShelf = {}, onRefresh = {}, onChapterQueryChanged = {}, onToggleOrder = {})
            }
        }

        compose.onNodeWithText("星河旅人").assertIsDisplayed()
        compose.onNodeWithTag("book_primary_actions").assertIsDisplayed()
        compose.onNodeWithTag("source_picker").assertIsDisplayed()
        compose.onNodeWithTag("chapter_list").assertIsDisplayed()
        compose.onNodeWithText("第一章").assertIsDisplayed()
        compose.onNodeWithText("书源乙").performClick()
        assertThat(selected).isEqualTo("b")
    }

    @Test fun shelfAndRefreshActionsCallBack() {
        var shelved = false
        var refreshed = false
        compose.setContent {
            NovelRadarTheme {
                BookDetailScreen(state(), onBack = {}, onSourceSelected = {},
                    onAddToShelf = { shelved = true }, onRefresh = { refreshed = true },
                    onChapterQueryChanged = {}, onToggleOrder = {})
            }
        }

        compose.onNodeWithText("加入书架").performClick()
        compose.onNodeWithText("刷新目录").performClick()
        assertThat(shelved).isTrue()
        assertThat(refreshed).isTrue()
    }

    @Test fun enabledReadingActionsOpenSavedPositionOrChosenChapter() {
        var started = false
        var selectedChapter: String? = null
        compose.setContent {
            NovelRadarTheme {
                BookDetailScreen(state(), onBack = {}, onSourceSelected = {}, onAddToShelf = {}, onRefresh = {},
                    onChapterQueryChanged = {}, onToggleOrder = {}, canRead = true,
                    onStartReading = { started = true }, onChapterSelected = { selectedChapter = it.chapterId })
            }
        }
        compose.onNodeWithText("开始阅读").performClick()
        compose.onNodeWithText("第一章").performClick()
        assertThat(started).isTrue()
        assertThat(selectedChapter).isEqualTo("c1")
    }

    @Test fun pendingSourceChoiceDisablesReadingAndDownloadActions() {
        compose.setContent {
            NovelRadarTheme {
                BookDetailScreen(state().copy(sourceActionPending = true), onBack = {}, onSourceSelected = {},
                    onAddToShelf = {}, onRefresh = {}, onChapterQueryChanged = {}, onToggleOrder = {},
                    canRead = true, canDownload = true)
            }
        }
        compose.onNodeWithText("开始阅读").assertIsNotEnabled()
        compose.onNodeWithText("← 返回").assertIsNotEnabled()
        compose.onNodeWithText("整本下载").assertIsNotEnabled()
        compose.onNodeWithText("第一章").assertIsNotEnabled()
        compose.onNodeWithText("正在保存选择…").assertIsDisplayed()
    }

    private fun state() = BookDetailUiState(
        book = Book("book-1", "星河旅人|叶子", "星河旅人", "叶子"),
        sources = listOf(BookSource("book-1", "a", "https://example.org/a"),
            BookSource("book-1", "b", "https://example.org/b")),
        selectedSourceId = "a",
        sourceNames = mapOf("a" to "书源甲", "b" to "书源乙"),
        chapters = listOf(Chapter("book-1", "a", "c1", 0, "第一章", "https://example.org/chapter/1")),
        loading = false,
    )
}
