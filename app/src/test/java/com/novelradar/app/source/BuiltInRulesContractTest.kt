package com.novelradar.app.source

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.parse.ChapterCleaner
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.rule.RuleJsonParser
import com.novelradar.app.source.rule.RuleParseResult
import com.novelradar.app.source.rule.RuleValidator
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import org.junit.Test

/** Offline contract for the rules bundled with the APK; never contacts source websites. */
class BuiltInRulesContractTest {
    // Reviewed, literal per-source expectations. Never derive these from the current asset at runtime.
    private val expectedSemanticSha256 = linkedMapOf(
        "香书小说" to "5d8ced8e9e97cc99e426a1dfe42a64281f33f209a583c02c00dc4fdd4e18543c",
        "书海阁小说网" to "f4c20d8e8e02a366ef423141dbc261fbb2307b81b4b8df993914ac897d2277e5",
        "梦书中文" to "a96cb50650bb666bf3105499600053020945b0bbd03b7f630ce65b39dd63fec8",
        "鸟书网" to "9a27fe1aa4f2d41623542b18f98a19a3c5489164e1da7a8282e97b363d60431e",
        "笔趣阁22" to "5b29ca06fd4c88da0f7eb72ce29cba303961151291b2e8e4c8a27db493c0e0f7",
        "笔尖中文" to "93bacbc24990fc324c17d7851bb7e9cf897b011405e1e41f370db08665bd6a87",
        "书林文学" to "9e3ee0c97047c671320d501917c4812d2463c30f1890d11b03be224839853903",
        "悠久小说网" to "7671461d7d8aa14ed1e33f06efb54838f3e5b14c8695d274422a920411918f54",
        "阅读库" to "87a0f2388241e133e5db81ea9606bd414b4ecf3f45cf858715be23c74d750605",
        "顶点小说" to "08c2d88b0b1484227a5c392b2b2d05510a469dc48d6a9038163a1798902ac6ae",
        "笔趣阁365" to "ba61a7831a170ab522fd38cf13aa1584da89ddff8c6a17f1d354df3fb3c5f7ba",
        "燃文小说网" to "fcc0e68d76468ee02b50981d197104c304a6be01f89881e19aff66020d91dffd",
    )
    // Fixed DTO field order ignores JSON formatting/key order; serializeNulls retains absent
    // optional fields in the fingerprint, while Moshi retains explicit false booleans.
    private val semanticAdapter = Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build()
        .adapter(RuleDto::class.java).serializeNulls()

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

    @Test fun preservesReviewedMeaningOfEveryBundledSource() {
        val actual = rules.associate { it.name to semanticSha256(it) }
        assertThat(actual).containsExactlyEntriesIn(expectedSemanticSha256)
        val representative = semanticAdapter.toJson(rules.single { it.name == "书海阁小说网" })
        assertThat(representative).contains("\"language\":null")
        assertThat(representative).contains("\"disabled\":false")
        assertThat(representative).contains("\"pagination\":true")
    }

    @Test fun semanticSnapshotDetectsDeletedSearchAndChangedOptionalSelector() {
        val reference = rules.single { it.name == "书海阁小说网" }
        val withoutSearch = reference.copy(search = null)
        val alteredIntro = reference.copy(book = requireNotNull(reference.book).copy(intro = "#intro > p:nth-child(2)"))
        assertThat(RuleValidator().validate(withoutSearch)).isEmpty()
        assertThat(RuleValidator().validate(alteredIntro)).isEmpty()
        assertThat(semanticSha256(withoutSearch)).isNotEqualTo(expectedSemanticSha256.getValue(reference.name))
        assertThat(semanticSha256(alteredIntro)).isNotEqualTo(expectedSemanticSha256.getValue(reference.name))
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

    @Test fun shuhaigeFilterRemovesAdvertisementWithoutRemovingOrdinaryProse() {
        val chapter = requireNotNull(rules.single { it.name == "书海阁小说网" }.chapter)
        val content = "<p>第一段正文。</p>" +
            "<p>喜欢《剑行》请大家收藏：(阅读页)《剑行》书海阁小说网更新速度全网最快。</p>" +
            "<p>喜欢看雨，仍是正文。</p>"
        val result = ChapterCleaner().clean(content, chapter)
        assertThat(result).isInstanceOf(SourceResult.Success::class.java)
        val cleaned = (result as SourceResult.Success).value
        assertThat(cleaned).contains("第一段正文。")
        assertThat(cleaned).contains("喜欢看雨，仍是正文。")
        assertThat(cleaned).doesNotContain("请大家收藏")
        assertThat(cleaned).doesNotContain("书海阁小说网更新速度全网最快")
    }

    private fun semanticSha256(rule: RuleDto): String =
        MessageDigest.getInstance("SHA-256")
            .digest(semanticAdapter.toJson(rule).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
