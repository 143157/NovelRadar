package com.novelradar.app.data.db

import androidx.room.Dao
import androidx.room.Query
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

    @Query("SELECT * FROM source_rules WHERE enabled = 1 ORDER BY sourceId")
    suspend fun enabled(): List<SourceRuleEntity>

    @Query("UPDATE source_rules SET enabled = :enabled WHERE sourceId = :sourceId")
    suspend fun setEnabled(sourceId: String, enabled: Boolean)

    @Query("UPDATE source_rules SET allowCleartext = :allowed WHERE sourceId = :sourceId")
    suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean)

    @Query("DELETE FROM source_rules WHERE sourceId = :sourceId AND builtIn = 0")
    suspend fun deleteImported(sourceId: String): Int
}
