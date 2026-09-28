package com.novelradar.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "source_rules", indices = [Index("enabled")])
data class SourceRuleEntity(
    @PrimaryKey val sourceId: String,
    val rawJson: String,
    val enabled: Boolean,
    val builtIn: Boolean,
    val allowCleartext: Boolean,
    val scheme: String = "HTTPS",
    val healthStatus: String = "UNKNOWN",
    val lastLatencyMillis: Long? = null,
    val lastHealthAtEpochMillis: Long? = null,
    val lastSuccessAtEpochMillis: Long? = null,
    val lastFailureKind: String? = null,
    val lastFailureDetail: String? = null,
)
