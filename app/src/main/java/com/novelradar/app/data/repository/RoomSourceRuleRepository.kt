package com.novelradar.app.data.repository

import com.novelradar.app.data.db.SourceRuleDao
import com.novelradar.app.data.db.entity.SourceRuleEntity
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceHealthStatus
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.ImportSummary
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.rule.RuleValidator
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomSourceRuleRepository(
    private val dao: SourceRuleDao,
    private val validator: RuleValidator = RuleValidator(),
) : SourceRuleRepository {
    private val adapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build().adapter(RuleDto::class.java)

    override fun observeAll(): Flow<List<StoredSourceRule>> = dao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun enabled(): List<StoredSourceRule> = dao.enabled().map { it.toDomain() }

    override suspend fun upsertImported(rules: List<RuleDto>): ImportSummary {
        require(rules.isNotEmpty()) { "No source rules provided" }
        require(validator.validateAll(rules).isEmpty()) { "Invalid source rules" }
        val identified = rules.map { rule -> rule to (rule.id?.takeIf(String::isNotBlank) ?: sourceId(rule)) }
        require(identified.map { it.second }.distinct().size == identified.size) { "Duplicate source IDs" }
        val existing = identified.map { (_, id) -> dao.getById(id) }
        require(existing.none { it?.builtIn == true }) { "Built-in sources cannot be overwritten" }
        val entities = identified.mapIndexed { index, (rule, id) ->
            val old = existing[index]
            val scheme = URI(rule.url).scheme.uppercase(Locale.ROOT)
            SourceRuleEntity(
                sourceId = id,
                rawJson = adapter.toJson(rule),
                enabled = old?.enabled ?: (!rule.disabled && scheme == "HTTPS"),
                builtIn = false,
                allowCleartext = old?.allowCleartext ?: false,
                scheme = scheme,
                healthStatus = old?.healthStatus ?: SourceHealthStatus.UNKNOWN.name,
                lastLatencyMillis = old?.lastLatencyMillis,
                lastHealthAtEpochMillis = old?.lastHealthAtEpochMillis,
                lastSuccessAtEpochMillis = old?.lastSuccessAtEpochMillis,
                lastFailureKind = old?.lastFailureKind,
                lastFailureDetail = old?.lastFailureDetail,
            )
        }
        dao.upsertAll(entities)
        return ImportSummary(
            added = existing.count { it == null },
            updated = existing.count { it != null },
            disabledHttp = entities.count { it.scheme == "HTTP" && !it.enabled },
        )
    }

    override suspend fun setEnabled(sourceId: String, enabled: Boolean) {
        requireNotNull(dao.getById(sourceId)) { "Unknown source" }
        dao.setEnabled(sourceId, enabled)
    }

    override suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean) {
        val existing = requireNotNull(dao.getById(sourceId)) { "Unknown source" }
        require(!allowed || existing.scheme == "HTTP") { "Cleartext permission applies only to HTTP sources" }
        dao.setCleartextAllowed(sourceId, allowed)
    }

    override suspend fun updateHealth(sourceId: String, health: SourceHealth) {
        val old = requireNotNull(dao.getById(sourceId)) { "Unknown source" }
        val (kind, detail) = health.lastFailure.toColumns()
        dao.upsert(old.copy(
            healthStatus = health.status.name,
            lastLatencyMillis = health.lastLatencyMillis,
            lastHealthAtEpochMillis = System.currentTimeMillis(),
            lastSuccessAtEpochMillis = health.lastSuccessAtEpochMillis,
            lastFailureKind = kind,
            lastFailureDetail = detail,
        ))
    }

    override suspend fun deleteUserRule(sourceId: String) {
        require(dao.deleteImported(sourceId) == 1) { "Source does not exist or is built in" }
    }
}

private fun SourceRuleEntity.toDomain(): StoredSourceRule = StoredSourceRule(
    sourceId = sourceId,
    rawJson = rawJson,
    enabled = enabled,
    builtIn = builtIn,
    allowCleartext = allowCleartext,
    health = SourceHealth(
        status = enumValueOrDefault(healthStatus, SourceHealthStatus.UNKNOWN),
        lastLatencyMillis = lastLatencyMillis,
        lastSuccessAtEpochMillis = lastSuccessAtEpochMillis,
        lastFailure = failureFromColumns(lastFailureKind, lastFailureDetail),
    ),
)

private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
    enumValues<T>().firstOrNull { it.name == value } ?: default

private fun SourceFailure?.toColumns(): Pair<String?, String?> = when (this) {
    null -> null to null
    SourceFailure.Timeout -> "Timeout" to null
    is SourceFailure.Http -> "Http" to code.toString()
    is SourceFailure.Decode -> "Decode" to charset
    is SourceFailure.Parse -> "Parse" to "$field\u0000$selector"
    SourceFailure.VerificationRequired -> "VerificationRequired" to null
    is SourceFailure.UnsafeRule -> "UnsafeRule" to reason
    is SourceFailure.Unexpected -> "Unexpected" to message
}

private fun failureFromColumns(kind: String?, detail: String?): SourceFailure? = when (kind) {
    "Timeout" -> SourceFailure.Timeout
    "Http" -> detail?.toIntOrNull()?.let(SourceFailure::Http)
    "Decode" -> SourceFailure.Decode(detail)
    "Parse" -> SourceFailure.Parse(detail?.substringBefore('\u0000').orEmpty(), detail?.substringAfter('\u0000', "").orEmpty())
    "VerificationRequired" -> SourceFailure.VerificationRequired
    "UnsafeRule" -> SourceFailure.UnsafeRule(detail.orEmpty())
    "Unexpected" -> SourceFailure.Unexpected(detail.orEmpty())
    else -> null
}

private fun sourceId(rule: RuleDto): String {
    val host = URI(rule.url).host.lowercase(Locale.ROOT)
    val identity = "$host|${rule.name}"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
