package com.novelradar.app.domain.model

data class BookSource(
    val bookId: String,
    val sourceId: String,
    val detailUrl: String,
    val status: SourceHealthStatus = SourceHealthStatus.UNKNOWN,
    val updatedAtEpochMillis: Long? = null,
)
