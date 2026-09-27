package com.novelradar.app.source.rule

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleJsonParserTest {
    private val parser = RuleJsonParser()

    @Test
    fun parsesSinglePostRuleAndPreservesItsFormTemplate() {
        val rules = success(ruleFixture("valid-search-rule.json"))
        val rule = rules.single()

        assertThat(rule.name).isEqualTo("示例")
        assertThat(rule.url).isEqualTo("https://example.test/")
        assertThat(rule.comment).isEqualTo("保留书源说明")
        assertThat(rule.search!!.method).isEqualTo("post")
        assertThat(rule.search.data).isEqualTo("{searchkey: %s, searchtype: all}")
        assertThat(rule.chapter!!.paragraphTag).isEqualTo("<br>+")
        assertThat(rule.chapter.paragraphTagClosed).isFalse()
        assertThat(rule.book).isEqualTo(BookRuleDto())
    }

    @Test
    fun acceptsAnArrayAndRetainsDisabledRulesInInputOrder() {
        val rules = success("[${ruleFixture("valid-search-rule.json")},${ruleFixture("invalid-script-rule.json")}]")

        assertThat(rules.map { it.name }).containsExactly("示例", "危险脚本示例").inOrder()
        assertThat(rules[1].disabled).isTrue()
        assertThat(rules[1].search!!.bookName).contains("import")
        assertThat(success("[]")).isEmpty()
    }

    @Test
    fun absentOptionalFieldsAndLegacyIdsHaveExplicitDefaults() {
        val rule = success("""{"url":"https://example.test/","name":"旧规则"}""").single()

        assertThat(rule.id).isNull()
        assertThat(rule.comment).isNull()
        assertThat(rule.language).isNull()
        assertThat(rule.disabled).isFalse()
        assertThat(rule.search).isNull()
        assertThat(rule.book).isNull()
        assertThat(rule.toc).isNull()
        assertThat(rule.chapter).isNull()
        assertThat(rule.crawl).isNull()
    }

    @Test
    fun retainsAllSupportedOptionalFieldsAndCrawlIntervalsInSeconds() {
        val rule = success(
            """{
              "id":"new-id", "url":"https://example.test/", "name":"完整", "language":"zh_TW",
              "search":{"disabled":true,"cookies":"key=value","category":".category",
                "latestChapter":".latest","lastUpdateTime":".time","wordCount":".words",
                "status":".status","pagination":true,"nextPage":".next"},
              "book":{"url":"https://example.test/book/(.*?)/","bookName":"h1","author":".author",
                "intro":"#intro","category":".category","coverUrl":"img","latestChapter":".latest",
                "lastUpdateTime":".time","status":".status"},
              "toc":{"baseUri":"https://example.test/%s/","url":"https://example.test/toc/%s/",
                "list":"/html","item":"//a","pagination":true,"isDesc":true,"nextPage":"#select option"},
              "chapter":{"title":"h1","content":"#content","paragraphTagClosed":true,
                "paragraphTag":"<br>+","filterTxt":"广告","filterTag":"script","pagination":true,"nextPage":".next"},
              "crawl":{"concurrency":2,"minInterval":1,"maxInterval":3,"maxAttempts":4,
                "retryMinInterval":5,"retryMaxInterval":8},
              "futureField":{"ignored":true}
            }""",
        ).single()

        assertThat(rule.id).isEqualTo("new-id")
        assertThat(rule.language).isEqualTo("zh_TW")
        assertThat(rule.search).isEqualTo(SearchRuleDto(
            disabled = true, cookies = "key=value", category = ".category", latestChapter = ".latest",
            lastUpdateTime = ".time", wordCount = ".words", status = ".status", pagination = true, nextPage = ".next",
        ))
        assertThat(rule.book).isEqualTo(BookRuleDto(
            url = "https://example.test/book/(.*?)/", bookName = "h1", author = ".author", intro = "#intro",
            category = ".category", coverUrl = "img", latestChapter = ".latest", lastUpdateTime = ".time", status = ".status",
        ))
        assertThat(rule.toc).isEqualTo(TocRuleDto(
            baseUri = "https://example.test/%s/", url = "https://example.test/toc/%s/", list = "/html", item = "//a",
            pagination = true, isDesc = true, nextPage = "#select option",
        ))
        assertThat(rule.chapter).isEqualTo(ChapterRuleDto(
            title = "h1", content = "#content", paragraphTagClosed = true, paragraphTag = "<br>+",
            filterTxt = "广告", filterTag = "script", pagination = true, nextPage = ".next",
        ))
        assertThat(rule.crawl).isEqualTo(CrawlRuleDto(
            concurrency = 2, minInterval = 1, maxInterval = 3, maxAttempts = 4, retryMinInterval = 5, retryMaxInterval = 8,
        ))
    }

    @Test
    fun invalidDocumentsReturnSafeFailuresWithoutThrowingParserDetails() {
        val inputs = listOf(
            "", "  ", "{", "[", "null", "true", "42", "\"text\"", "[null]", "[42]",
            "{} {}", "[] trailing-secret", "{name:'JSON5'}", "{/* comment */}", "{\"name\":\"secret\",}",
            "{\"name\":{\"secret\":true}}", "{\"search\":[]}", "{\"disabled\":\"true\"}",
        )

        for (input in inputs) {
            val result = parser.parse(input)
            assertThat(result).isInstanceOf(RuleParseResult.Failure::class.java)
            val message = (result as RuleParseResult.Failure).message
            assertThat(message).isNotEmpty()
            assertThat(message).doesNotContain("secret")
            assertThat(message).doesNotContain("JsonDataException")
            assertThat(message).doesNotContain("JsonEncodingException")
            assertThat(message).doesNotContain("at path")
        }
    }

    @Test
    fun rejectsCoercionOfKnownStringIntegerAndBooleanFields() {
        val inputs = listOf(
            """{"name":123}""", """{"search":{"data":123}}""",
            """{"search":{"pagination":"true"}}""", """{"chapter":{"paragraphTagClosed":1}}""",
            """{"crawl":{"concurrency":"2"}}""", """{"crawl":{"minInterval":1.5}}""",
            """{"crawl":{"maxAttempts":2147483648}}""", """{"disabled":null}""",
        )

        inputs.forEach { assertThat(parser.parse(it)).isInstanceOf(RuleParseResult.Failure::class.java) }
    }

    private fun success(json: String): List<RuleDto> {
        val result = parser.parse(json)
        assertThat(result).isInstanceOf(RuleParseResult.Success::class.java)
        return (result as RuleParseResult.Success).rules
    }
}

internal fun ruleFixture(name: String): String =
    requireNotNull(RuleJsonParserTest::class.java.getResource("/rules/$name")).readText()
