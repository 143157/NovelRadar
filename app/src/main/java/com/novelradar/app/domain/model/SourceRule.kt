package com.novelradar.app.domain.model

data class StoredSourceRule(
    val sourceId: String,
    val rawJson: String,
    val enabled: Boolean = true,
    val builtIn: Boolean = false,
    val allowCleartext: Boolean = false,
    val health: SourceHealth = SourceHealth(),
)

data class SourceHealth(
    val status: SourceHealthStatus = SourceHealthStatus.UNKNOWN,
    val lastLatencyMillis: Long? = null,
    val lastSuccessAtEpochMillis: Long? = null,
    val lastFailure: SourceFailure? = null,
)

enum class SourceHealthStatus {
    UNKNOWN,
    HEALTHY,
    SLOW,
    FAILED,
    VERIFICATION_REQUIRED,
}
