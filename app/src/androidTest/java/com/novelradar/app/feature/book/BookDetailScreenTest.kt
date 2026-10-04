package com.novelradar.app.feature.book

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.data.repository.CoverFetcher
import com.novelradar.app.ui.theme.NovelRadarTheme
import org.junit.Rule
import org.junit.Test

class BookDetailScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun showsSavedIntroductionAndUsesInjectedSafeCoverFetcher() {
        var requested: Pair<String, String>? = null
        compose.setContent {
            NovelRadarTheme {
                BookDetailScreen(state().copy(book = state().book!!.copy(
                    coverUrl = "https://example.org/cover.jpg", introduction = "穿越群星的故事")),
                    onBack = {}, onSourceSelected = {}, onAddToShelf = {}, onRefresh = {},
                    onChapterQueryChanged = {}, onToggleOrder = {},
                    coverFetcher = CoverFetcher { source, url -> requested = source to url; null })
            }
        }
        compose.waitUntil { requested != null }
        assertThat(requested).isEqualTo("a" to "https://example.org/cover.jpg")
        compose.onNodeWithText("穿越群星的故事").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("当前书源：书源甲").performScrollTo().assertIsDisplayed()
    }

    @Test fun missingIntroductionAndDetailFailureKeepReadingAvailable() {
        compose.setContent {
            NovelRadarTheme {
                BookDetailScreen(state().copy(detailsWarning = SourceFailure.Timeout), onBack = {},
                    onSourceSelected = {}, onAddToShelf = {}, onRefresh = {},
                    onChapterQueryChanged = {}, onToggleOrder = {}, canRead = true, canDownload = true)
            }
        }
        compose.onNodeWithText("书源暂未提供简介").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("书籍信息更新失败，保留已保存信息：连接超时").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("开始阅读").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("整本下载").performScrollTo().assertIsEnabled()
    }

    @Test fun confirmedIdentityMismatchDisablesReadingDownloadAndChapterClicks() {
        compose.setContent {
            NovelRadarTheme {
                BookDetailScreen(state().copy(sourceIdentityMismatch = true), onBack = {},
                    onSourceSelected = {}, onAddToShelf = {}, onRefresh = {},
                    onChapterQueryChanged = {}, onToggleOrder = {}, canRead = true, canDownload = true)
            }
        }
        compose.onNodeWithText("开始阅读").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("整本下载").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("第一章").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("当前书源的作品信息与已保存书籍不一致，请选择其他书源或重新刷新核验")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun longMetadataAndLargeTextKeepAllThreeActionsReachable() {
        val title = "跨越银河与时间的漫长旅程".repeat(4)
        val intro = "读者与旅人沿着群星寻找归途。".repeat(30)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
                NovelRadarTheme {
                    BookDetailScreen(state().copy(book = state().book!!.copy(bookName = title, introduction = intro),
                        sourceNames = mapOf("a" to "名称很长的合法小说书源".repeat(3))), onBack = {},
                        onSourceSelected = {}, onAddToShelf = {}, onRefresh = {},
                        onChapterQueryChanged = {}, onToggleOrder = {}, canRead = true, canDownload = true)
                }
            }
        }
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("加入书架").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("开始阅读").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("整本下载").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(intro).performScrollTo().assertIsDisplayed()
    }

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
        compose.onNodeWithTag("source_picker").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("chapter_list").assertIsDisplayed()
        compose.onNodeWithText("第一章").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("书源乙").performScrollTo().performClick()
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
        compose.onNodeWithText("刷新书籍与目录").performScrollTo().performClick()
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
        compose.onNodeWithText("第一章").performScrollTo().performClick()
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
        compose.onNodeWithText("第一章").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("正在保存选择…").performScrollTo().assertIsDisplayed()
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
