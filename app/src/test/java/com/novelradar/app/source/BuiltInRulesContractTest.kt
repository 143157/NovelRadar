package com.novelradar.app.source

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.source.rule.RuleJsonParser
import com.novelradar.app.source.rule.RuleParseResult
import com.novelradar.app.source.rule.RuleValidator
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import org.junit.Test

/** Offline contract for the rules bundled with the APK; never contacts source websites. */
class BuiltInRulesContractTest {
    private val rules by lazy {
        val json = File("src/main/assets/sources/main.json").readText(Charsets.UTF_8)
        val parsed = RuleJsonParser().parse(json)
        assertThat(parsed).isInstanceOf(RuleParseResult.Success::class.java)
        (parsed as RuleParseResult.Success).rules
    }

    @Test fun bundlesAllTwelveReferenceSourcesWithUniqueStableIds() {
        assertThat(rules).hasSize(12)
        assertThat(rules.map { it.name }).containsExactly(
            "香书小说", "书海阁小说网", "梦书中文", "鸟书网", "笔趣阁22", "笔尖中文",
            "书林文学", "悠久小说网", "阅读库", "顶点小说", "笔趣阁365", "燃文小说网",
        ).inOrder()
        val ids = rules.map { rule ->
            val host = requireNotNull(URI(rule.url).host).lowercase(Locale.ROOT)
            val seed = "$host|${rule.name}"
            val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
            val expected = digest.take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
            assertThat(rule.id).isEqualTo(expected)
            rule.id
        }
        assertThat(ids.toSet()).hasSize(12)
    }

    @Test fun everyBundledRulePassesTheStrictImporter() {
        assertThat(RuleValidator().validateAll(rules)).isEmpty()
    }

    @Test fun legacyHttpRulesStartDisabledAndHttpsRulesRemainAvailable() {
        val httpRules = rules.filter { URI(it.url).scheme.equals("http", ignoreCase = true) }
        assertThat(httpRules).hasSize(7)
        assertThat(httpRules.all { it.disabled }).isTrue()
        val httpsRules = rules.filter { URI(it.url).scheme.equals("https", ignoreCase = true) }
        assertThat(httpsRules).hasSize(5)
        assertThat(httpsRules.any { !it.disabled }).isTrue()
    }

    @Test fun shuhaigeFilterIsCompatibleWithLinearTimeRegexEngine() {
        val filter = rules.single { it.name == "书海阁小说网" }.chapter?.filterTxt
        assertThat(filter).isNotNull()
        assertThat(filter).doesNotContain("\\1")
    }
}
