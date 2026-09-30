package com.novelradar.app.data.bootstrap

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.SourceRuleDao
import com.novelradar.app.data.db.entity.SourceRuleEntity
import com.novelradar.app.data.repository.RoomSourceRuleRepository
import com.novelradar.app.domain.repository.asExecutableRule
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.rule.RuleJsonParser
import com.novelradar.app.source.rule.RuleParseResult
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BuiltInRuleInstallerTest {
    private val parser = RuleJsonParser()
    private val ruleAdapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        .adapter(RuleDto::class.java)
    private val originalRules = (parser.parse(File("src/main/assets/sources/main.json").readText()) as RuleParseResult.Success).rules
    private val dao = FakeSourceRuleDao()
    private val versions = FakeVersionStore()
    private var asset = rulesJson(originalRules)
    private val installer = BuiltInRuleInstaller(dao, { asset }, versions)

    @Test fun firstInstallPersistsAllTwelveAndDisablesLegacyHttp() = runTest {
        installer.run()

        assertThat(dao.rows.value).hasSize(12)
        assertThat(dao.rows.value.count { it.builtIn && it.scheme == "HTTP" && !it.enabled }).isEqualTo(7)
        assertThat(dao.rows.value.count { it.enabled }).isEqualTo(5)
        assertThat(versions.value).isNotEmpty()
    }

    @Test fun secondInstallRefreshesJsonButPreservesUserChoices() = runTest {
        installer.run()
        val https = dao.rows.value.first { it.scheme == "HTTPS" }
        val http = dao.rows.value.first { it.scheme == "HTTP" }
        dao.setEnabled(https.sourceId, false)
        dao.setEnabled(http.sourceId, true)
        dao.setCleartextAllowed(http.sourceId, true)
        val updated = originalRules.map { if (it.id == https.sourceId) it.copy(comment = "bundled update") else it }
        asset = rulesJson(updated)

        installer.run()

        assertThat(dao.rows.value).hasSize(12)
        assertThat(dao.getById(https.sourceId)!!.rawJson).contains("bundled update")
        assertThat(dao.getById(https.sourceId)!!.enabled).isFalse()
        assertThat(dao.getById(http.sourceId)!!.enabled).isTrue()
        assertThat(dao.getById(http.sourceId)!!.allowCleartext).isTrue()
    }

    @Test fun removedBundledRuleRemainsStoredButDisabled() = runTest {
        installer.run()
        val removed = dao.rows.value.first { it.enabled }
        asset = rulesJson(originalRules.filterNot { it.id == removed.sourceId })

        installer.run()

        assertThat(dao.rows.value).hasSize(12)
        assertThat(dao.getById(removed.sourceId)!!.builtIn).isTrue()
        assertThat(dao.getById(removed.sourceId)!!.enabled).isFalse()
    }

    @Test fun importedHttpStartsDisabledAndStorageStateCanOverrideLegacyDisabledFlag() = runTest {
        val http = originalRules.first { it.url.startsWith("http:") }
        val repository = RoomSourceRuleRepository(dao)

        val summary = repository.upsertImported(listOf(http.copy(id = null)))
        val stored = dao.rows.value.single()

        assertThat(summary.added).isEqualTo(1)
        assertThat(summary.disabledHttp).isEqualTo(1)
        assertThat(stored.enabled).isFalse()
        dao.setEnabled(stored.sourceId, true)
        val active = repository.enabled().single()
        assertThat(active.asExecutableRule(http).disabled).isFalse()
        assertThat(active.allowCleartext).isFalse()
    }

    private fun rulesJson(rules: List<RuleDto>) = rules.joinToString(prefix = "[", postfix = "]") { ruleAdapter.toJson(it) }

    private class FakeVersionStore : BundledRuleVersionStore {
        var value: String? = null
        override suspend fun read(): String? = value
        override suspend fun write(version: String) { value = version }
    }

    private class FakeSourceRuleDao : SourceRuleDao {
        val rows = MutableStateFlow<List<SourceRuleEntity>>(emptyList())

        override suspend fun upsert(rule: SourceRuleEntity) { upsertAll(listOf(rule)) }

        override suspend fun upsertAll(rules: List<SourceRuleEntity>) {
            rows.value = (rows.value.associateBy { it.sourceId } + rules.associateBy { it.sourceId }).values.toList()
        }

        override fun observeAll(): Flow<List<SourceRuleEntity>> = rows
        override suspend fun getById(sourceId: String): SourceRuleEntity? = rows.value.firstOrNull { it.sourceId == sourceId }
        override suspend fun builtIns(): List<SourceRuleEntity> = rows.value.filter { it.builtIn }
        override suspend fun disableMissingBuiltIns(sourceIds: List<String>) {
            rows.value = rows.value.map { if (it.builtIn && it.sourceId !in sourceIds) it.copy(enabled = false) else it }
        }
        override suspend fun enabled(): List<SourceRuleEntity> = rows.value.filter { it.enabled }
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) {
            rows.value = rows.value.map { if (it.sourceId == sourceId) it.copy(enabled = enabled) else it }
        }
        override suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean) {
            rows.value = rows.value.map { if (it.sourceId == sourceId) it.copy(allowCleartext = allowed) else it }
        }
        override suspend fun deleteImported(sourceId: String): Int {
            val previous = rows.value.size
            rows.value = rows.value.filterNot { it.sourceId == sourceId && !it.builtIn }
            return previous - rows.value.size
        }
    }
}
