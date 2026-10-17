package com.novelradar.app.feature.sources

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceHealthStatus
import com.novelradar.app.ui.paper.PaperStudyTheme
import com.novelradar.app.ui.paper.PaperTestViewport
import org.junit.Rule
import org.junit.Test

class SourceManagerScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun sourceRowsShowProtocolOriginAndHealth() {
        compose.setContent {
            PaperStudyTheme {
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
            PaperStudyTheme {
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
            PaperTestViewport {
                SourceManagerScreen(state, {}, {}, { _, _ -> }, {}, {},
                    onConfirmHttp = { confirmed = true }, onCancelHttp = {})
            }
        }

        compose.onNodeWithText("HTTP 连接不加密").assertIsDisplayed()
        compose.onNodeWithText("旧站 使用明文连接，阅读请求可能被沿途网络看见或修改。Android 系统也可能阻止连接。")
            .assertIsDisplayed()
        compose.onNodeWithText("保持关闭").assertIsDisplayed()
        assertThat(confirmed).isFalse()
        compose.onNodeWithText("仍要启用").performClick()
        assertThat(confirmed).isTrue()
    }

    @Test fun importedRuleDeletionRequiresDialog() {
        val state = state().copy(rows = listOf(SourceRowState("custom", "自定义源", "HTTPS", "custom.example",
            true, false, false, SourceHealth())))
        var deleted = false
        compose.setContent {
            PaperTestViewport {
                SourceManagerScreen(state, {}, {}, { _, _ -> }, {}, { deleted = true }, {}, {})
            }
        }

        compose.onNodeWithTag("source_delete_custom").performClick()
        assertThat(deleted).isFalse()
        compose.onNodeWithText("将从本机删除“自定义源”的规则，已加入书架的书籍不会被删除。")
            .assertIsDisplayed()
        compose.onNodeWithText("确认删除").performClick()
        assertThat(deleted).isTrue()
    }

    @Test fun paperLongHostRetainsSourceIdAndTestAction() {
        val host = "very-long-library-host-with-many-sections.example.org/catalogue/archive"
        var tested: String? = null
        var toggled: String? = null
        val state = state().copy(rows = listOf(state().rows.single().copy(host = host)))
        compose.setContent { PaperTestViewport {
            SourceManagerScreen(state, {}, {}, { id, _ -> toggled = id },
                { tested = it }, {}, {}, {})
        } }
        compose.onNodeWithText(host).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("可用 · 120 ms").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("测试", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("source_toggle_secure").performScrollTo().performClick()
        compose.runOnIdle {
            assertThat(tested).isEqualTo("secure")
            assertThat(toggled).isEqualTo("secure")
        }
    }

    @Test fun paperTestingDisablesTestActionsAndPreservesToggle() {
        var tests = 0
        var toggles = 0
        compose.setContent { PaperTestViewport {
            SourceManagerScreen(state().copy(testingSourceIds = setOf("secure")), {}, { tests++ },
                { _, _ -> toggles++ }, { tests++ }, {}, {}, {})
        } }
        compose.onNodeWithTag("source_test_all").assertIsNotEnabled().performClick()
        compose.onNodeWithText("检测中").performScrollTo().assertIsNotEnabled().performClick()
        compose.onNodeWithTag("source_toggle_secure").performScrollTo().performClick()
        compose.runOnIdle {
            assertThat(tests).isEqualTo(0)
            assertThat(toggles).isEqualTo(1)
        }
    }

    @Test fun paperHttpCancelKeepsConfirmationUnaccepted() {
        var confirmed = false
        var canceled = false
        val state = state().copy(
            rows = listOf(state().rows.single().copy(scheme = "HTTP", enabled = false)),
            pendingHttpSourceId = "secure",
        )
        compose.setContent { PaperTestViewport {
            SourceManagerScreen(state, {}, {}, { _, _ -> }, {}, {},
                { confirmed = true }, { canceled = true })
        } }
        compose.onNodeWithText("保持关闭").performClick()
        compose.runOnIdle {
            assertThat(confirmed).isFalse()
            assertThat(canceled).isTrue()
        }
    }

    private fun state() = SourceManagerUiState(rows = listOf(
        SourceRowState("secure", "测试源", "HTTPS", "example.org", true, true, false,
            SourceHealth(SourceHealthStatus.HEALTHY, lastLatencyMillis = 120L)),
    ))
}
