package com.novelradar.app.source.importer

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.ImportSummary
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.source.rule.ChapterRuleDto
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.rule.SearchRuleDto
import com.novelradar.app.source.rule.TocRuleDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.ByteArrayInputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RuleImporterTest {
    private val repository = FakeSourceRuleRepository()
    private val importer = RuleImporter(repository)
    private val adapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build().adapter(RuleDto::class.java)

    @Test fun importsOneObjectAndDerivesStableId() = runTest {
        val result = importer.import(streamOf(rule("A")))

        assertThat(result).isInstanceOf(ImportResult.Imported::class.java)
        assertThat(repository.savedRules).hasSize(1)
        assertThat(repository.savedRules.single().id).matches("[0-9a-f]{16}")
    }

    @Test fun invalidMemberRejectsEntireFile() = runTest {
        val invalid = rule("B").copy(chapter = ChapterRuleDto(title = "h1"))
        val result = importer.import(streamOf(rule("A"), invalid))

        assertThat(result).isInstanceOf(ImportResult.Rejected::class.java)
        assertThat((result as ImportResult.Rejected).issues.map { it.path }).contains("[1].chapter.content")
        assertThat(repository.savedRules).isEmpty()
    }

    @Test fun rejectsFileBeyondOneMiB() = runTest {
        val result = importer.import(ByteArrayInputStream(ByteArray(1024 * 1024 + 1) { 'x'.code.toByte() }))

        assertThat(result).isInstanceOf(ImportResult.Rejected::class.java)
        assertThat(repository.savedRules).isEmpty()
    }

    @Test fun rejectsInvalidUtf8() = runTest {
        val result = importer.import(ByteArrayInputStream(byteArrayOf(0xc3.toByte(), 0x28)))

        assertThat(result).isInstanceOf(ImportResult.Rejected::class.java)
        assertThat(repository.savedRules).isEmpty()
    }

    @Test fun duplicateIdsRejectEntireFile() = runTest {
        val result = importer.import(streamOf(rule("A").copy(id = "same"), rule("B").copy(id = "same")))

        assertThat((result as ImportResult.Rejected).issues.map { it.path }).contains("[1].id")
        assertThat(repository.savedRules).isEmpty()
    }

    @Test fun existingBuiltInNameConflictRequiresResolution() = runTest {
        repository.rows.value = listOf(StoredSourceRule("built-in", adapter.toJson(rule("A")), builtIn = true))
        val result = importer.import(streamOf(rule("A")))

        assertThat((result as ImportResult.Rejected).issues.map { it.path }).contains("[0].name")
        assertThat(repository.savedRules).isEmpty()
    }

    @Test fun forbiddenScriptRejectsEntireFile() = runTest {
        val unsafe = rule("A").copy(search = rule("A").search!!.copy(author = ".author@js:r=fetch('x')"))
        val result = importer.import(streamOf(unsafe))

        assertThat((result as ImportResult.Rejected).issues.map { it.path }).contains("[0].search.author")
        assertThat(repository.savedRules).isEmpty()
    }

    @Test fun unsupportedSchemeRejectsEntireFile() = runTest {
        val result = importer.import(streamOf(rule("A").copy(url = "file:///etc/passwd")))

        assertThat((result as ImportResult.Rejected).issues.map { it.path }).contains("[0].url")
        assertThat(repository.savedRules).isEmpty()
    }

    @Test fun importedHttpIsReportedAsDisabled() = runTest {
        val http = rule("A").copy(url = "http://example.org/")
        val result = importer.import(streamOf(http))

        assertThat((result as ImportResult.Imported).disabledHttp).isEqualTo(1)
    }

    private fun rule(name: String) = RuleDto(
        url = "https://example.org/",
        name = name,
        search = SearchRuleDto(url = "https://example.org/search?q=%s", method = "GET", result = ".book", bookName = "a"),
        toc = TocRuleDto(item = "#toc a"),
        chapter = ChapterRuleDto(title = "h1", content = "#content"),
    )

    private fun streamOf(vararg rules: RuleDto): ByteArrayInputStream {
        val json = if (rules.size == 1) adapter.toJson(rules.single())
            else rules.joinToString(prefix = "[", postfix = "]") { adapter.toJson(it) }
        return ByteArrayInputStream(json.toByteArray(Charsets.UTF_8))
    }

    private class FakeSourceRuleRepository : SourceRuleRepository {
        val rows = MutableStateFlow<List<StoredSourceRule>>(emptyList())
        val savedRules = mutableListOf<RuleDto>()

        override fun observeAll(): Flow<List<StoredSourceRule>> = rows
        override suspend fun enabled(): List<StoredSourceRule> = rows.value.filter { it.enabled }
        override suspend fun upsertImported(rules: List<RuleDto>): ImportSummary {
            savedRules += rules
            val http = rules.count { it.url.startsWith("http:") }
            return ImportSummary(rules.size, 0, http)
        }
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean) = Unit
        override suspend fun updateHealth(sourceId: String, health: SourceHealth) = Unit
        override suspend fun deleteUserRule(sourceId: String) = Unit
    }
}
