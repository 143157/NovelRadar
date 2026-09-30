package com.novelradar.app.feature.search

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.search.AggregatedBookResult
import com.novelradar.app.ui.theme.NovelRadarTheme
import org.junit.Rule
import org.junit.Test

class SearchScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun emptyScreenInvitesSearchAndAcceptsQuery() {
        var query = ""
        compose.setContent {
            NovelRadarTheme {
                SearchScreen(SearchUiState(query = query), onQueryChanged = { query = it }, onSubmit = {})
            }
        }

        compose.onNodeWithText("输入书名或作者开始搜书").assertIsDisplayed()
        compose.onNodeWithTag("search_input").performTextInput("星河")
        assertThat(query).isEqualTo("星河")
    }

    @Test fun partialResultShowsProgressAndBookCard() {
        compose.setContent {
            NovelRadarTheme {
                SearchScreen(state(results = listOf(result()), completed = 1, total = 3, searching = true), {}, {})
            }
        }

        compose.onNodeWithTag("search_progress").assertIsDisplayed()
        compose.onNodeWithText("已完成 1 / 3 个书源").assertIsDisplayed()
        compose.onNodeWithTag("search_result_星河|作者").assertIsDisplayed()
    }

    @Test fun failedSourceDetailsExpand() {
        compose.setContent {
            NovelRadarTheme {
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
            NovelRadarTheme {
                SearchScreen(state(results = listOf(book)), {}, {}, onResultSelected = { selected = it })
            }
        }

        compose.onNodeWithTag("search_result_${book.bookKey}").performClick()
        assertThat(selected).isEqualTo(book)
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
