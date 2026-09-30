package com.novelradar.app.feature.sources

import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.source.rule.RuleIssue

data class SourceRowState(
    val sourceId: String,
    val name: String,
    val scheme: String,
    val host: String,
    val enabled: Boolean,
    val builtIn: Boolean,
    val allowCleartext: Boolean,
    val health: SourceHealth,
)

data class SourceManagerUiState(
    val rows: List<SourceRowState> = emptyList(),
    val pendingHttpSourceId: String? = null,
    val testingSourceIds: Set<String> = emptySet(),
    val importIssues: List<RuleIssue> = emptyList(),
    val notice: String? = null,
)

sealed interface SourceManagerEvent {
    data object OpenDocument : SourceManagerEvent
}
