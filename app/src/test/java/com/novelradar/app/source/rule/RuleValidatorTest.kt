package com.novelradar.app.source.rule

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RuleValidatorTest {
    private val parser = RuleJsonParser()
    private val validator = RuleValidator()

    @Test
    fun acceptsRepresentativePostRule() {
        assertThat(validator.validate(validRule())).isEmpty()
    }

    @Test
    fun requiresNameBaseUrlAndReadingSelectors() {
        assertThat(validator.validate(RuleDto())).containsExactly(
            RuleIssue("chapter.content", "required field is blank"),
            RuleIssue("chapter.title", "required field is blank"),
            RuleIssue("name", "required field is blank"),
            RuleIssue("toc.item", "required field is blank"),
            RuleIssue("url", "required field is blank"),
        ).inOrder()
    }

    @Test
    fun searchIsOptionalButEnabledSearchRequiresItsCoreFields() {
        assertThat(validator.validate(validRule().copy(search = null))).isEmpty()
        assertThat(validator.validate(validRule().copy(search = SearchRuleDto()))).containsExactly(
            RuleIssue("search.bookName", "required field is blank"),
            RuleIssue("search.method", "required field is blank"),
            RuleIssue("search.result", "required field is blank"),
            RuleIssue("search.url", "required field is blank"),
        ).inOrder()
        assertThat(validator.validate(validRule().copy(search = SearchRuleDto(disabled = true)))).isEmpty()
    }

    @Test
    fun searchMethodsAreCaseInsensitiveAndRequireTheirKeywordTemplate() {
        val rule = validRule()
        val search = requireNotNull(rule.search)
        for (method in listOf("GET", "get", "GeT")) {
            assertThat(validator.validate(rule.copy(search = search.copy(method = method, url = "https://example.test/?q=%s")))).isEmpty()
        }
        assertThat(validator.validate(rule.copy(search = search.copy(method = "GET")))).contains(
            RuleIssue("search.url", "template must contain %s"),
        )
        assertThat(validator.validate(rule.copy(search = search.copy(method = "POST", data = "{q: fixed}")))).contains(
            RuleIssue("search.data", "template must contain %s"),
        )
        assertThat(validator.validate(rule.copy(search = search.copy(method = "PATCH")))).contains(
            RuleIssue("search.method", "method must be GET or POST"),
        )
    }

    @Test
    fun rejectsUnsupportedSchemesAndMalformedAbsoluteUrls() {
        val invalidUrls = listOf("file:///etc/passwd", "javascript:alert(1)", "ftp://example.test/", "example.test", "https:///", "https://exa mple.test/", "https://example.test:99999/")
        for (url in invalidUrls) {
            assertThat(validator.validate(validRule().copy(url = url))).contains(RuleIssue("url", "URL must be an absolute HTTP or HTTPS URL"))
        }
        assertThat(validator.validate(validRule().copy(url = "http://example.test/"))).isEmpty()
    }

    @Test
    fun validatesRequestUrlsAndDerivedTocTemplateWithoutTreatingBookIdRegexAsAUrl() {
        val rule = validRule().copy(
            book = BookRuleDto(url = "https://example.test/book/(.*?)/"),
            toc = TocRuleDto(item = "#list a", url = "https://example.test/toc/%s/", baseUri = "https://example.test/%s/"),
        )
        assertThat(validator.validate(rule)).isEmpty()
        assertThat(validator.validate(rule.copy(toc = rule.toc!!.copy(baseUri = "https://example.test/static/")))).isEmpty()
        assertThat(validator.validate(rule.copy(toc = rule.toc.copy(url = "https://example.test/static/")))).contains(
            RuleIssue("toc.url", "template must contain %s"),
        )
        assertThat(validator.validate(rule.copy(toc = rule.toc.copy(baseUri = "file:///tmp/%s")))).contains(
            RuleIssue("toc.baseUri", "URL must be an absolute HTTP or HTTPS URL"),
        )
        assertThat(validator.validate(rule.copy(search = rule.search!!.copy(url = "file:///tmp")))).contains(
            RuleIssue("search.url", "URL must be an absolute HTTP or HTTPS URL"),
        )
    }

    @Test
    fun acceptsCssXPathAndScriptsSplitOnlyAtTheFirstMarker() {
        val rule = validRule().copy(
            book = BookRuleDto(coverUrl = "meta[property=\"og:image\"]@js:r='https://example.test'+r"),
            toc = TocRuleDto(item = "//a[@href]", list = "/html@js:const marker='@js:';r=r.replace(marker,'');", nextPage = "#select option"),
            chapter = ChapterRuleDto(title = "#chapterTitle@js:r=r.replace(/\\(\\d+\\/\\d+\\)/,'');", content = "#content", paragraphTag = "<br>+", filterTxt = "\\(本章完\\)|<!--.*?-->"),
        )
        assertThat(validator.validate(rule)).isEmpty()
    }

    @Test
    fun reportsCssAndXPathSyntaxErrorsAtTheirFields() {
        val rule = validRule().copy(
            book = BookRuleDto(coverUrl = "div["),
            toc = TocRuleDto(item = "//a[", list = "/html["),
            chapter = ChapterRuleDto(title = "h1", content = "div:unknown-pseudo"),
        )
        assertThat(validator.validate(rule)).containsExactly(
            RuleIssue("book.coverUrl", "invalid CSS selector"),
            RuleIssue("chapter.content", "invalid CSS selector"),
            RuleIssue("toc.item", "invalid XPath selector"),
            RuleIssue("toc.list", "invalid XPath selector"),
        ).inOrder()
    }

    @Test
    fun rejectsRuleThatCanImportModulesEvenWhenDisabled() {
        val result = parser.parse(ruleFixture("invalid-script-rule.json")) as RuleParseResult.Success
        val rule = result.rules.single()

        assertThat(rule.disabled).isTrue()
        assertThat(validator.validate(rule)).containsExactly(
            RuleIssue("search.bookName", "script contains forbidden token: import"),
        )
        assertThat(validator.validate(rule.copy(search = rule.search!!.copy(disabled = true)))).contains(
            RuleIssue("search.bookName", "script contains forbidden token: import"),
        )
    }

    @Test
    fun rejectsEachForbiddenStandaloneTokenWithoutRejectingLongerIdentifiers() {
        val rule = validRule()
        val search = requireNotNull(rule.search)
        for (token in listOf("import", "require", "fetch", "XMLHttpRequest", "WebSocket", "Java", "Packages", "process", "eval")) {
            assertThat(validator.validate(rule.copy(search = search.copy(bookName = "a@js:$token('x');")))).contains(
                RuleIssue("search.bookName", "script contains forbidden token: $token"),
            )
        }
        assertThat(validator.validate(rule.copy(search = search.copy(bookName = "a@js:const important=1;const fetcher=2;const evaluation=3;r=r+important+fetcher+evaluation;")))).isEmpty()
    }

    @Test
    fun enforcesUtf8ByteLimitsOnSelectorAndScriptIndependently() {
        val rule = validRule()
        val search = requireNotNull(rule.search)
        assertThat(validator.validate(rule.copy(search = search.copy(bookName = "a".repeat(2048) + "@js:" + " ".repeat(16384))))).isEmpty()
        assertThat(validator.validate(rule.copy(search = search.copy(bookName = "a".repeat(2049))))).contains(
            RuleIssue("search.bookName", "selector exceeds 2048 UTF-8 bytes"),
        )
        assertThat(validator.validate(rule.copy(search = search.copy(bookName = "a@js:" + " ".repeat(16385))))).contains(
            RuleIssue("search.bookName", "script exceeds 16384 UTF-8 bytes"),
        )
        assertThat(validator.validate(rule.copy(search = search.copy(bookName = "书".repeat(683))))).contains(
            RuleIssue("search.bookName", "selector exceeds 2048 UTF-8 bytes"),
        )
        assertThat(validator.validate(rule.copy(search = search.copy(bookName = "a@js:/*" + "书".repeat(5462) + "*/")))).contains(
            RuleIssue("search.bookName", "script exceeds 16384 UTF-8 bytes"),
        )
    }

    @Test
    fun rejectsUnsupportedAndOversizedRegularExpressionsAtImport() {
        val rule = validRule().copy(
            disabled = true,
            book = BookRuleDto(url = "x".repeat(2049)),
            chapter = ChapterRuleDto(
                title = "h1",
                content = "#content",
                filterTxt = "(.)\\1",
                paragraphTag = "(",
            ),
        )

        assertThat(validator.validate(rule)).containsExactly(
            RuleIssue("book.url", "regex exceeds 2048 UTF-8 bytes"),
            RuleIssue("chapter.filterTxt", "unsupported regex syntax"),
            RuleIssue("chapter.paragraphTag", "unsupported regex syntax"),
        ).inOrder()
    }

    @Test
    fun checksOptionalExpressionsAndDisabledSearchForSafety() {
        val rule = validRule().copy(
            disabled = true,
            search = SearchRuleDto(disabled = true, nextPage = "a@js:fetch('x')"),
            book = BookRuleDto(intro = "div@js:eval(r)"),
            toc = TocRuleDto(item = "a", list = "/html@js:require('x')"),
            chapter = ChapterRuleDto(title = "h1", content = "#content", nextPage = "a@js:process.exit()"),
        )
        assertThat(validator.validate(rule)).containsExactly(
            RuleIssue("book.intro", "script contains forbidden token: eval"),
            RuleIssue("chapter.nextPage", "script contains forbidden token: process"),
            RuleIssue("search.nextPage", "script contains forbidden token: fetch"),
            RuleIssue("toc.list", "script contains forbidden token: require"),
        ).inOrder()
    }

    @Test
    fun batchValidationReportsEveryDuplicateNameAndSortsAllDiagnostics() {
        val rules = listOf(validRule(), validRule().copy(name = " 示例 ", disabled = true), validRule().copy(name = "other", url = "file:///tmp"))
        val issues = validator.validateAll(rules)
        assertThat(issues).containsExactly(
            RuleIssue("[0].name", "duplicate rule name: 示例"),
            RuleIssue("[1].name", "duplicate rule name: 示例"),
            RuleIssue("[2].url", "URL must be an absolute HTTP or HTTPS URL"),
        ).inOrder()
        assertThat(validator.validateAll(rules)).isEqualTo(issues)
    }

    @Test
    fun diagnosticsUsePathThenMessageOrderAndDeduplicateRepeatedTokens() {
        val rule = validRule().copy(search = validRule().search!!.copy(bookName = "a@js:import('x');eval(r);import('y');"))
        assertThat(validator.validate(rule)).containsExactly(
            RuleIssue("search.bookName", "script contains forbidden token: eval"),
            RuleIssue("search.bookName", "script contains forbidden token: import"),
        ).inOrder()
    }

    private fun validRule(): RuleDto = (parser.parse(ruleFixture("valid-search-rule.json")) as RuleParseResult.Success).rules.single()
}
