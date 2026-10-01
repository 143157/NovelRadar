package com.novelradar.app.feature.library

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.repository.ShelfBook
import com.novelradar.app.ui.theme.NovelRadarTheme
import org.junit.Rule
import org.junit.Test

class LibraryScreenTest {
    @get:Rule val compose = createComposeRule()
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
