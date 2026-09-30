package com.novelradar.app.domain.repository

import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.source.rule.RuleDto
import kotlinx.coroutines.flow.Flow

data class ImportSummary(val added: Int, val updated: Int, val disabledHttp: Int)

interface SourceRuleRepository {
    fun observeAll(): Flow<List<StoredSourceRule>>
    suspend fun enabled(): List<StoredSourceRule>
    suspend fun upsertImported(rules: List<RuleDto>): ImportSummary
    suspend fun setEnabled(sourceId: String, enabled: Boolean)
    suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean)
    suspend fun updateHealth(sourceId: String, health: SourceHealth)
    suspend fun deleteUserRule(sourceId: String)
}

/** The user's persisted choice is authoritative; the bundled JSON's disabled flag is an initial default. */
fun StoredSourceRule.asExecutableRule(parsed: RuleDto): RuleDto {
    require(enabled) { "Source is disabled" }
    require(parsed.id == null || parsed.id == sourceId) { "Source ID mismatch" }
    return parsed.copy(disabled = false)
}
