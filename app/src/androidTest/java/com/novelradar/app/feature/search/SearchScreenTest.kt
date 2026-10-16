package com.novelradar.app.feature.search

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.search.AggregatedBookResult
import com.novelradar.app.ui.paper.PaperStudyTheme
import com.novelradar.app.ui.paper.PaperTestViewport
import org.junit.Rule
import org.junit.Test

class SearchScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyScreenInvitesSearchAndAcceptsQuery() {
        var query = ""
        compose.setContent {
            PaperStudyTheme {
                SearchScreen(SearchUiState(query = query), onQueryChanged = { query = it }, onSubmit = {})
            }
        }

        compose.onNodeWithText("输入书名或作者开始搜书").assertIsDisplayed()
        compose.onNodeWithTag("search_input").performTextInput("星河")
        assertThat(query).isEqualTo("星河")
    }

    @Test fun partialResultShowsProgressAndBookCard() {
        compose.setContent {
            PaperStudyTheme {
                SearchScreen(state(results = listOf(result()), completed = 1, total = 3, searching = true), {}, {})
            }
        }

        compose.onNodeWithTag("search_progress").assertIsDisplayed()
        compose.onNodeWithText("已完成 1 / 3 个书源").assertIsDisplayed()
        compose.onNodeWithTag("search_result_星河|作者").assertIsDisplayed()
    }

    @Test fun failedSourceDetailsExpand() {
        compose.setContent {
            PaperStudyTheme {
                SearchScreen(state(failures = mapOf("source-a" to SourceFailure.Timeout),
                    sourceNames = mapOf("source-a" to "失效书源")), {}, {})
            }
        }

        compose.onNodeWithText("1 个书源未完成").performClick()
        compose.onNodeWithText("失效书源 · 连接超时").assertIsDisplayed()
    }

    @Test fun clickingResultCallsSelection() {
        var selected: AggregatedBookResult? = null
        val book = result()
        compose.setContent {
            PaperStudyTheme {
                SearchScreen(state(results = listOf(book)), {}, {}, onResultSelected = { selected = it })
            }
        }

        compose.onNodeWithTag("search_result_${book.bookKey}").performClick()
        assertThat(selected).isEqualTo(book)
    }

    @Test fun paperSearchLongNameRetainsMetadataAndSelection() {
        val longResult = result().copy(displayName = "长中文书名".repeat(8))
        var selected: AggregatedBookResult? = null
        compose.setContent { PaperTestViewport {
            SearchScreen(state(results = listOf(longResult)), {}, {}, onResultSelected = { selected = it })
        } }
        compose.onNodeWithTag("search_results").performScrollToNode(hasTestTag("search_result_星河|作者"))
        compose.onNodeWithText("作者").assertIsDisplayed()
        compose.onNodeWithText("第十章").assertIsDisplayed()
        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("个书源").assertIsDisplayed()
        compose.onNodeWithTag("search_result_星河|作者").performClick()
        compose.runOnIdle { assertThat(selected).isEqualTo(longResult) }
    }

    @Test fun paperSearchBusyStateKeepsPartialResultsSelectable() {
        val book = result()
        var selected: AggregatedBookResult? = null
        compose.setContent { PaperTestViewport {
            SearchScreen(state(results = listOf(book), completed = 1, total = 3, searching = true),
                {}, {}, onResultSelected = { selected = it })
        } }
        compose.onNodeWithText("已完成 1 / 3 个书源").assertIsDisplayed()
        compose.onNodeWithText("搜索中…").assertIsDisplayed()
        compose.onNodeWithTag("search_results").performScrollToNode(hasTestTag("search_result_星河|作者"))
        compose.onNodeWithTag("search_result_星河|作者").performClick()
        compose.runOnIdle { assertThat(selected).isEqualTo(book) }
    }

    @Test fun paperSearchErrorsRetainFailureExpansionAndRetry() {
        var retried = false
        compose.setContent { PaperTestViewport {
            SearchScreen(state(failures = mapOf("source-a" to SourceFailure.Timeout),
                sourceNames = mapOf("source-a" to "失效书源")).copy(errorMessage = "搜索未完成"),
                {}, {}, onRetry = { retried = true })
        } }
        compose.onNodeWithText("搜索未完成").assertIsDisplayed()
        compose.onNodeWithText("1 个书源未完成").performClick()
        compose.onNodeWithText("失效书源 · 连接超时").assertIsDisplayed()
        compose.onNodeWithText("重新搜索").performClick()
        compose.runOnIdle { assertThat(retried).isTrue() }
    }

    @Test fun paperSearchPreparationFailureDisablesSubmit() {
        var submitted = false
        compose.setContent { PaperTestViewport {
            SearchScreen(SearchUiState(query = "星河"), {}, { submitted = true },
                sourcesReady = false, bootstrapFailed = true)
        } }
        compose.onNodeWithText("书源准备失败，请重新打开应用").assertIsDisplayed()
        compose.onNodeWithTag("search_submit").assertIsNotEnabled().performClick()
        compose.onNodeWithTag("search_input").performImeAction()
        compose.runOnIdle { assertThat(submitted).isFalse() }
    }

    private fun state(
        results: List<AggregatedBookResult> = emptyList(),
        completed: Int = 0,
        total: Int = 1,
        searching: Boolean = false,
        failures: Map<String, SourceFailure> = emptyMap(),
        sourceNames: Map<String, String> = emptyMap(),
    ) = SearchUiState(
        query = "星河",
        hasSearched = true,
        searching = searching,
        completedSources = completed,
        totalSources = total,
        results = results,
        failures = failures,
        sourceNames = sourceNames,
    )

    private fun result(): AggregatedBookResult {
        val hit = SearchHit("source-a", "星河", "作者", "https://example.org/book", latestChapter = "第十章")
        return AggregatedBookResult("星河|作者", "星河", "作者", listOf(hit), hit)
    }
}
