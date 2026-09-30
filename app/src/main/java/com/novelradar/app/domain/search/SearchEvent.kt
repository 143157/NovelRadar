package com.novelradar.app.domain.search

import com.novelradar.app.domain.model.SourceFailure

sealed interface SearchEvent {
    data class SourceStarted(val sourceId: String) : SearchEvent
    data class SourceSucceeded(val sourceId: String, val hitCount: Int) : SearchEvent
    data class SourceFailed(val sourceId: String, val failure: SourceFailure) : SearchEvent
}
