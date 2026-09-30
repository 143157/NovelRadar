package com.novelradar.app.feature.search

import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.search.AggregatedBookResult

data class SearchUiState(
    val query: String = "",
    val hasSearched: Boolean = false,
    val searching: Boolean = false,
    val completedSources: Int = 0,
    val totalSources: Int = 0,
    val results: List<AggregatedBookResult> = emptyList(),
    val failures: Map<String, SourceFailure> = emptyMap(),
    val sourceNames: Map<String, String> = emptyMap(),
    val errorMessage: String? = null,
)
