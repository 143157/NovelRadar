package com.novelradar.app.domain.search

import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure

data class AggregatedBookResult(
    val bookKey: String,
    val displayName: String,
    val author: String,
    val sources: List<SearchHit>,
    val bestHit: SearchHit,
)

data class SearchSnapshot(
    val query: String,
    val results: List<AggregatedBookResult> = emptyList(),
    val completedSources: Int = 0,
    val totalSources: Int = 0,
    val activeSources: Set<String> = emptySet(),
    val failures: Map<String, SourceFailure> = emptyMap(),
    val lastEvent: SearchEvent? = null,
)
