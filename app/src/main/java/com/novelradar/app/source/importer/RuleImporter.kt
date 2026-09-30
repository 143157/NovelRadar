package com.novelradar.app.source.importer

import com.novelradar.app.domain.repository.ImportSummary
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.rule.RuleIssue
import com.novelradar.app.source.rule.RuleJsonParser
import com.novelradar.app.source.rule.RuleParseResult
import com.novelradar.app.source.rule.RuleValidator
import com.novelradar.app.source.rule.deriveSourceId
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.flow.first

sealed interface ImportResult {
    data class Imported(val added: Int, val updated: Int, val disabledHttp: Int) : ImportResult {
        constructor(summary: ImportSummary) : this(summary.added, summary.updated, summary.disabledHttp)
    }
    data class Rejected(val issues: List<RuleIssue>) : ImportResult
}

/** Validates the entire bounded file before making one repository write. Does not own the input stream. */
class RuleImporter(
    private val repository: SourceRuleRepository,
    private val parser: RuleJsonParser = RuleJsonParser(),
    private val validator: RuleValidator = RuleValidator(),
) {
    suspend fun import(input: InputStream): ImportResult {
        val bytes = try { readBounded(input) } catch (_: IOException) {
            return reject("$", "Could not read rule file")
        } ?: return reject("$", "Rule file exceeds 1 MiB")
        val json = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return reject("$", "Rule file is not valid UTF-8")
        }
        val parsed = parser.parse(json)
        if (parsed is RuleParseResult.Failure) return reject("$", parsed.message)
        val rules = (parsed as RuleParseResult.Success).rules
        if (rules.isEmpty()) return reject("$", "Rule file contains no sources")

        val issues = validator.validateAll(rules).toMutableList()
        val identified = rules.mapIndexed { index, rule ->
            val explicitId = rule.id
            if (explicitId != null && !validId(explicitId)) {
                issues += RuleIssue("[$index].id", "Invalid source ID")
            }
            val id = if (explicitId.isNullOrBlank()) {
                try { deriveSourceId(rule) } catch (_: IllegalArgumentException) { null }
            } else explicitId
            id?.let { rule.copy(id = it) }
        }
        val ids = mutableSetOf<String>()
        identified.forEachIndexed { index, rule ->
            if (rule != null && !ids.add(requireNotNull(rule.id))) {
                issues += RuleIssue("[$index].id", "Duplicate source ID")
            }
        }
        if (issues.isNotEmpty()) return ImportResult.Rejected(issues)
        val ready = identified.filterNotNull()
        val existing = repository.observeAll().first()
        val existingById = existing.associateBy { it.sourceId }
        val names = existing.mapNotNull { stored ->
            val result = parser.parse(stored.rawJson) as? RuleParseResult.Success
            result?.rules?.singleOrNull()?.name?.trim()?.let { name -> name to stored.sourceId }
        }
        ready.forEachIndexed { index, rule ->
            val id = requireNotNull(rule.id)
            if (existingById[id]?.builtIn == true) {
                issues += RuleIssue("[$index].id", "Built-in source cannot be overwritten")
            }
            if (names.any { (name, existingId) -> name == rule.name.trim() && existingId != id }) {
                issues += RuleIssue("[$index].name", "Source name already exists")
            }
        }
        if (issues.isNotEmpty()) return ImportResult.Rejected(issues)
        return try {
            ImportResult.Imported(repository.upsertImported(ready))
        } catch (_: IllegalArgumentException) {
            reject("$", "Rule file conflicts with existing sources")
        }
    }

    private fun readBounded(input: InputStream): ByteArray? {
        val output = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        while (true) {
            val read = input.read(chunk)
            if (read < 0) return output.toByteArray()
            if (output.size() + read > MAX_FILE_BYTES) return null
            output.write(chunk, 0, read)
        }
    }

    private fun validId(id: String): Boolean = id.length in 1..128 && id != "." && ".." !in id && ID_PATTERN.matches(id)

    private fun reject(path: String, message: String) = ImportResult.Rejected(listOf(RuleIssue(path, message)))

    private companion object {
        const val MAX_FILE_BYTES = 1024 * 1024
        val ID_PATTERN = Regex("[A-Za-z0-9._-]+")
    }
}
