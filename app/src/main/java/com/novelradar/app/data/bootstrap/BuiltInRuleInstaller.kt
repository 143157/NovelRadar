package com.novelradar.app.data.bootstrap

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.novelradar.app.data.db.SourceRuleDao
import com.novelradar.app.data.db.entity.SourceRuleEntity
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.rule.RuleJsonParser
import com.novelradar.app.source.rule.RuleParseResult
import com.novelradar.app.source.rule.RuleValidator
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

interface BundledRuleVersionStore {
    suspend fun read(): String?
    suspend fun write(version: String)
}

class DataStoreBundledRuleVersionStore(private val dataStore: DataStore<Preferences>) : BundledRuleVersionStore {
    override suspend fun read(): String? = dataStore.data.first()[VERSION_KEY]
    override suspend fun write(version: String) { dataStore.edit { it[VERSION_KEY] = version } }

    private companion object {
        val VERSION_KEY = stringPreferencesKey("bundled_rules_sha256")
    }
}

/** Reconciles packaged rules with local choices. The DAO performs the reconciliation in one transaction. */
class BuiltInRuleInstaller(
    private val dao: SourceRuleDao,
    private val readAsset: () -> String,
    private val versions: BundledRuleVersionStore,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val parser: RuleJsonParser = RuleJsonParser(),
    private val validator: RuleValidator = RuleValidator(),
) {
    private val adapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build().adapter(RuleDto::class.java)

    suspend fun run() = withContext(dispatcher) {
        val json = readAsset()
        val parsed = parser.parse(json) as? RuleParseResult.Success
            ?: throw IllegalArgumentException("Bundled rules are invalid")
        val rules = parsed.rules
        require(rules.isNotEmpty() && validator.validateAll(rules).isEmpty()) { "Bundled rules are invalid" }
        require(rules.all { !it.id.isNullOrBlank() } && rules.map { it.id }.distinct().size == rules.size) {
            "Bundled rules need unique IDs"
        }
        val entities = rules.map { rule ->
            val scheme = URI(rule.url).scheme.uppercase()
            SourceRuleEntity(
                sourceId = requireNotNull(rule.id),
                rawJson = adapter.toJson(rule),
                enabled = !rule.disabled && scheme == "HTTPS",
                builtIn = true,
                allowCleartext = false,
                scheme = scheme,
            )
        }
        dao.installBuiltIns(entities)
        versions.write(json.sha256())
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
