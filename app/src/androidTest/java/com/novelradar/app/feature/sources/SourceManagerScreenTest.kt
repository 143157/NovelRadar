package com.novelradar.app.feature.sources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceHealthStatus
import com.novelradar.app.ui.theme.NovelRadarTheme
import org.junit.Rule
import org.junit.Test

class SourceManagerScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceRowsShowProtocolOriginAndHealth() {
        compose.setContent {
            NovelRadarTheme {
                SourceManagerScreen(state(), {}, {}, { _, _ -> }, {}, {}, {}, {})
            }
        }

        compose.onNodeWithText("测试源").assertIsDisplayed()
        compose.onNodeWithText("HTTPS").assertIsDisplayed()
        compose.onNodeWithText("内置").assertIsDisplayed()
        compose.onNodeWithText("可用 · 120 ms").assertIsDisplayed()
    }

    @Test fun importAndTestAllActionsAreAvailable() {
        var imported = false
        var tested = false
        compose.setContent {
            NovelRadarTheme {
                SourceManagerScreen(state(), onImport = { imported = true }, onTestAll = { tested = true },
                    onToggle = { _, _ -> }, onTestOne = {}, onDelete = {}, onConfirmHttp = {}, onCancelHttp = {})
            }
        }

        compose.onNodeWithTag("source_import").performClick()
        compose.onNodeWithTag("source_test_all").performClick()
        assertThat(imported).isTrue()
        assertThat(tested).isTrue()
    }

    @Test fun httpConfirmationIsVisibleBeforeEnable() {
        val state = state().copy(
            rows = state().rows + SourceRowState("legacy", "旧站", "HTTP", "legacy.example", false,
                true, false, SourceHealth()),
            pendingHttpSourceId = "legacy",
        )
        var confirmed = false
        compose.setContent {
            NovelRadarTheme {
                SourceManagerScreen(state, {}, {}, { _, _ -> }, {}, {},
                    onConfirmHttp = { confirmed = true }, onCancelHttp = {})
            }
        }

        compose.onNodeWithText("HTTP 连接不加密").assertIsDisplayed()
        compose.onNodeWithText("仍要启用").performClick()
        assertThat(confirmed).isTrue()
    }

    @Test fun importedRuleDeletionRequiresDialog() {
        val state = state().copy(rows = listOf(SourceRowState("custom", "自定义源", "HTTPS", "custom.example",
            true, false, false, SourceHealth())))
        var deleted = false
        compose.setContent {
            NovelRadarTheme {
                SourceManagerScreen(state, {}, {}, { _, _ -> }, {}, { deleted = true }, {}, {})
            }
        }

        compose.onNodeWithTag("source_delete_custom").performClick()
        assertThat(deleted).isFalse()
        compose.onNodeWithText("确认删除").performClick()
        assertThat(deleted).isTrue()
    }

    private fun state() = SourceManagerUiState(rows = listOf(
        SourceRowState("secure", "测试源", "HTTPS", "example.org", true, true, false,
            SourceHealth(SourceHealthStatus.HEALTHY, lastLatencyMillis = 120L)),
    ))
}
