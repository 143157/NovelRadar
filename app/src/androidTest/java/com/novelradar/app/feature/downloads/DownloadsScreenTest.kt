package com.novelradar.app.feature.downloads

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.DownloadState
import com.novelradar.app.ui.paper.PaperStudyTheme
import com.novelradar.app.ui.paper.PaperTestViewport
import org.junit.Rule
import org.junit.Test

class DownloadsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun chapterTallySourceAndStoredNetworkAreVisibleAndPauseCallsBack() {
        var action: DownloadAction? = null
        compose.setContent { PaperStudyTheme { DownloadsScreen(
            DownloadsUiState(rows = listOf(row()), loading = false, settingsReady = true),
            onAction = { _, selected -> action = selected }, onConfirm = {}, onDismiss = {},
            onDefaultNetwork = {}, onRetry = {}, onSearch = {}) } }
        compose.onNodeWithText("3 / 10 章").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("书源甲").assertIsDisplayed()
        compose.onNodeWithText("本任务：仅不计费网络").assertIsDisplayed()
        compose.onNodeWithText("暂停").performScrollTo().performClick()
        assertThat(action).isEqualTo(DownloadAction.PAUSE)
    }

    @Test fun partialRowShowsFailedOnlyRetryAndNormalContinue() {
        var action: DownloadAction? = null
        val row = row().copy(state = DownloadState.PARTIAL,
            actions = setOf(DownloadAction.CONTINUE, DownloadAction.RETRY_FAILED, DownloadAction.DELETE_FILES))
        compose.setContent { PaperStudyTheme { DownloadsScreen(
            DownloadsUiState(rows = listOf(row), loading = false, settingsReady = true),
            onAction = { _, selected -> action = selected }, onConfirm = {}, onDismiss = {},
            onDefaultNetwork = {}, onRetry = {}, onSearch = {}) } }
        compose.onNodeWithText("失败 2 章").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("重试失败章节").performScrollTo().performClick()
        assertThat(action).isEqualTo(DownloadAction.RETRY_FAILED)
        compose.onNodeWithText("继续下载").performScrollTo().assertIsDisplayed()
    }

    @Test fun cancelDialogRetainsFilesAndDeleteDialogExplainsBookWideScope() {
        compose.setContent { PaperStudyTheme { DownloadActionDialog(
            DownloadConfirmation(row(), DownloadAction.DELETE_FILES), onConfirm = {}, onDismiss = {}) } }
        compose.onNodeWithText("删除本书本地正文？").assertIsDisplayed()
        compose.onNodeWithTag("download_confirmation_scope").assertIsDisplayed()
        compose.onNodeWithText("删除正文").assertIsDisplayed()
    }

    @Test fun cancelDialogHasSeparateActionAndRetainedFilesCopy() {
        compose.setContent { PaperStudyTheme { DownloadActionDialog(
            DownloadConfirmation(row(), DownloadAction.CANCEL), onConfirm = {}, onDismiss = {}) } }
        compose.onNodeWithText("取消下载任务？").assertIsDisplayed()
        compose.onNodeWithText("停止此任务，已下载和已缓存的正文仍保留。").assertIsDisplayed()
        compose.onNodeWithText("确认取消").assertIsDisplayed()
    }

    @Test fun startDialogDisplaysChosenSourceAndDeliberateMeteredChoice() {
        var metered = false
        compose.setContent { PaperStudyTheme { DownloadStartDialog(
            DownloadStartRequest("book", "a", "星河旅人", "书源甲", false),
            onNetwork = { metered = it }, onConfirm = {}, onDismiss = {}) } }
        compose.onNodeWithText("星河旅人 · 书源甲").assertIsDisplayed()
        compose.onNodeWithTag("download_unmetered").assertIsSelected()
        compose.onNodeWithTag("download_metered_opt_in").assertIsNotSelected()
        assertThat(metered).isFalse()
        compose.onNodeWithTag("download_metered_opt_in").performClick()
        assertThat(metered).isTrue()
        compose.onNodeWithText("开始下载").assertIsDisplayed()
    }

    @Test fun emptyStateDirectsToSearchAndDetailsAndNewDefaultOnly() {
        var search = false
        compose.setContent { PaperStudyTheme { DownloadsScreen(
            DownloadsUiState(loading = false, settingsReady = true), onAction = { _, _ -> },
            onConfirm = {}, onDismiss = {}, onDefaultNetwork = {}, onRetry = {}, onSearch = { search = true }) } }
        compose.onNodeWithText("到搜书找到小说，打开详情后选择「整本下载」。").assertIsDisplayed()
        compose.onNodeWithText("仅用于新建任务，不改变已有任务的网络限制。").assertIsDisplayed()
        compose.onNodeWithText("去搜书").performClick()
        assertThat(search).isTrue()
    }

    @Test fun paperDownloadBusyRowStillBlocksActions() {
        var actions = 0
        compose.setContent { PaperTestViewport { DownloadsScreen(
            DownloadsUiState(rows = listOf(row()), busyTaskIds = setOf("task"), loading = false, settingsReady = true),
            onAction = { _, _ -> actions++ }, onConfirm = {}, onDismiss = {},
            onDefaultNetwork = {}, onRetry = {}, onSearch = {}) } }
        compose.onNodeWithText("暂停").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertThat(actions).isEqualTo(0) }
    }

    @Test fun paperDownloadLongTitleRetainsDetailsAndTaskAction() {
        val title = "星河旅人：穿越漫长宇宙之后仍然想读完的故事与遥远书房的来信"
        var taskId: String? = null
        var action: DownloadAction? = null
        compose.setContent { PaperTestViewport { DownloadsScreen(
            DownloadsUiState(rows = listOf(row().copy(title = title)), loading = false, settingsReady = true),
            onAction = { id, selected -> taskId = id; action = selected }, onConfirm = {}, onDismiss = {},
            onDefaultNetwork = {}, onRetry = {}, onSearch = {}) } }
        compose.onNodeWithText(title).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("书源甲").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("3 / 10 章").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("当前章节：第四章").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("暂停").performScrollTo().performClick()
        compose.runOnIdle {
            assertThat(taskId).isEqualTo("task")
            assertThat(action).isEqualTo(DownloadAction.PAUSE)
        }
    }

    @Test fun paperDownloadReadErrorBlocksActionsButRetainsRetry() {
        var actions = 0
        var retries = 0
        compose.setContent { PaperTestViewport { DownloadsScreen(
            DownloadsUiState(rows = listOf(row()), listError = "读取任务失败", loading = false, settingsReady = true),
            onAction = { _, _ -> actions++ }, onConfirm = {}, onDismiss = {},
            onDefaultNetwork = {}, onRetry = { retries++ }, onSearch = {}) } }
        compose.onNodeWithText("暂停").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithText("重新读取").performScrollTo().performClick()
        compose.runOnIdle {
            assertThat(actions).isEqualTo(0)
            assertThat(retries).isEqualTo(1)
        }
    }

    private fun row() = DownloadRow("task", "book", "a", "星河旅人", "书源甲", DownloadState.RUNNING,
        3, 10, 30, 2, 4096, "第四章", 512, false,
        setOf(DownloadAction.PAUSE, DownloadAction.CANCEL, DownloadAction.DELETE_FILES))
}
