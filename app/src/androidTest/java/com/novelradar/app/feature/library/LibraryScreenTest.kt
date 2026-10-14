package com.novelradar.app.feature.library

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.repository.ShelfBook
import com.novelradar.app.ui.theme.NovelRadarTheme
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun continueCardAndGridSharePositionLabelAndDoNotPromoteLegacy() {
        val shelf = ShelfBook(Book("b", "b", "星河旅人", "叶子"), 1, 50, false, "a",
            ReadingLocation("b", "a", "chapter", 99, 77, 50), chapterCount = 3, readingChapterPosition = 2)
        val display = androidx.compose.runtime.mutableStateOf(LibraryBook(shelf))
        compose.setContent { NovelRadarTheme { LibraryScreen(LibraryUiState(books = listOf(display.value), loading = false),
            {}, {}, {}, {}, {}, {}) } }
        fun assertBothLabels(label: String) {
            for (tag in listOf("continue_reading", "shelf_book_b")) {
                compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag(tag))
                compose.onNodeWithTag(tag).assertTextContains(label).assertIsDisplayed()
            }
        }
        assertBothLabels("读到第 2 / 3 章")
        compose.runOnIdle { display.value = LibraryBook(shelf.copy(location = shelf.location!!.copy(
            contentFormatVersion = ChapterContentFormat.LEGACY))) }
        assertBothLabels("原阅读位置 第 100 章（待核对）")
        compose.onAllNodesWithText("读到第 2 / 3 章").assertCountEquals(0)
        compose.runOnIdle { display.value = LibraryBook(shelf.copy(preferredSourceId = "b")) }
        compose.onNodeWithTag("continue_reading").assertDoesNotExist()
        compose.onNodeWithTag("library_grid").performScrollToNode(hasTestTag("shelf_book_b"))
        compose.onNodeWithText("尚未开始阅读").assertIsDisplayed()
    }
    @Test fun emptyShelfOffersSearch() {
        var searched = false
        compose.setContent { NovelRadarTheme { LibraryScreen(LibraryUiState(loading = false), {}, {}, {}, {}, {}, { searched = true }) } }
        compose.onNodeWithText("去搜书").performClick()
        assertThat(searched).isTrue()
    }
    @Test fun removalRequiresIndependentLocalDeletionChoice() {
        var deleteLocal: Boolean? = null
        val book = LibraryBook(ShelfBook(Book("b", "b", "星河旅人", "叶子"), 1, null, false))
        compose.setContent { NovelRadarTheme { LibraryScreen(LibraryUiState(books = listOf(book), loading = false, pendingRemoval = book), {}, {}, {}, { deleteLocal = it }, {}, {}) } }
        compose.onNodeWithText("同时删除本地章节（缓存及下载）").assertIsDisplayed()
        compose.onNodeWithText("移出书架").performClick()
        assertThat(deleteLocal).isFalse()
    }
    @Test fun gridExposesUnreadBadgeAndLongPress() {
        var removal: String? = null
        val book = LibraryBook(ShelfBook(Book("b", "b", "星河旅人", "叶子"), 1, null, false, "a", chapterCount = 10, unreadCount = 10, lastChapterTitle = "第十章"))
        compose.setContent { NovelRadarTheme { LibraryScreen(LibraryUiState(books = listOf(book), loading = false), {}, {}, { removal = it }, {}, {}, {}) } }
        compose.onNodeWithText("未读 10 章").assertIsDisplayed()
        compose.onNodeWithTag("shelf_book_b").performTouchInput { longClick() }
        assertThat(removal).isEqualTo("b")
    }
}
