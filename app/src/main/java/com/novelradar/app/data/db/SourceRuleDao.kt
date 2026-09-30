package com.novelradar.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.novelradar.app.data.db.entity.SourceRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceRuleDao {
    @Upsert
    suspend fun upsert(rule: SourceRuleEntity)

    @Upsert
    suspend fun upsertAll(rules: List<SourceRuleEntity>)

    @Query("SELECT * FROM source_rules ORDER BY sourceId")
    fun observeAll(): Flow<List<SourceRuleEntity>>

    @Query("SELECT * FROM source_rules WHERE sourceId = :sourceId LIMIT 1")
    suspend fun getById(sourceId: String): SourceRuleEntity?

    @Query("SELECT * FROM source_rules WHERE builtIn = 1")
    suspend fun builtIns(): List<SourceRuleEntity>

    @Query("UPDATE source_rules SET enabled = 0 WHERE builtIn = 1 AND sourceId NOT IN (:sourceIds)")
    suspend fun disableMissingBuiltIns(sourceIds: List<String>)

    @Transaction
    suspend fun installBuiltIns(rules: List<SourceRuleEntity>) {
        require(rules.isNotEmpty()) { "Bundled rule set must not be empty" }
        val merged = rules.mapNotNull { bundled ->
            val old = getById(bundled.sourceId)
            when {
                old == null -> bundled
                !old.builtIn -> null
                else -> bundled.copy(enabled = old.enabled, allowCleartext = old.allowCleartext,
                    healthStatus = old.healthStatus, lastLatencyMillis = old.lastLatencyMillis,
                    lastHealthAtEpochMillis = old.lastHealthAtEpochMillis,
                    lastSuccessAtEpochMillis = old.lastSuccessAtEpochMillis,
                    lastFailureKind = old.lastFailureKind, lastFailureDetail = old.lastFailureDetail)
            }
        }
        upsertAll(merged)
        disableMissingBuiltIns(rules.map { it.sourceId })
    }

    @Query("SELECT * FROM source_rules WHERE enabled = 1 ORDER BY sourceId")
    suspend fun enabled(): List<SourceRuleEntity>

    @Query("UPDATE source_rules SET enabled = :enabled WHERE sourceId = :sourceId")
    suspend fun setEnabled(sourceId: String, enabled: Boolean)

    @Query("UPDATE source_rules SET allowCleartext = :allowed WHERE sourceId = :sourceId")
    suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean)

    @Query("DELETE FROM source_rules WHERE sourceId = :sourceId AND builtIn = 0")
    suspend fun deleteImported(sourceId: String): Int
}
