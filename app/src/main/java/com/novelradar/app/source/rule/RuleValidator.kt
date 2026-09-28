package com.novelradar.app.source.rule

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale
import javax.xml.xpath.XPathExpressionException
import javax.xml.xpath.XPathFactory
import org.jsoup.select.QueryParser
import org.jsoup.select.Selector

/** Static import diagnostics only; JavaScript execution still requires an isolated runtime. */
class RuleValidator {
    fun validate(rule: RuleDto): List<RuleIssue> {
        val issues = mutableListOf<RuleIssue>()
        issues.required("name", rule.name)
        issues.required("url", rule.url)
        issues.url("url", rule.url)
        issues.required("toc.item", rule.toc?.item)
        issues.required("chapter.title", rule.chapter?.title)
        issues.required("chapter.content", rule.chapter?.content)

        rule.search?.let { search ->
            // Source-level disabled does not bypass validation. A source may separately
            // disable only searching, in which case its search fields may be absent.
            if (!search.disabled) {
                issues.required("search.url", search.url)
                issues.required("search.method", search.method)
                issues.required("search.result", search.result)
                issues.required("search.bookName", search.bookName)
                when (search.method?.uppercase(Locale.ROOT)) {
                    "GET" -> if (!search.url.isNullOrBlank()) issues.template("search.url", search.url)
                    "POST" -> issues.template("search.data", search.data)
                    null, "" -> Unit
                    else -> if (!search.method.isBlank()) {
                        issues.add(RuleIssue("search.method", "method must be GET or POST"))
                    }
                }
            }
            issues.url("search.url", search.url, template = true)
        }
        issues.url("toc.url", rule.toc?.url, template = true)
        if (!rule.toc?.url.isNullOrBlank()) issues.template("toc.url", rule.toc.url)
        issues.url("toc.baseUri", rule.toc?.baseUri, template = true)

        selectorFields(rule).forEach { (path, expression) -> issues.selector(path, expression) }
        listOf(
            "book.url" to rule.book?.url,
            "chapter.paragraphTag" to rule.chapter?.paragraphTag,
            "chapter.filterTxt" to rule.chapter?.filterTxt,
        ).forEach { (path, expression) -> issues.safeRegex(path, expression) }
        // These are regexes or templates, so CSS/XPath grammar does not apply.
        listOf(
            "book.url" to rule.book?.url,
            "chapter.paragraphTag" to rule.chapter?.paragraphTag,
            "chapter.filterTxt" to rule.chapter?.filterTxt,
            "search.data" to rule.search?.data,
            "search.cookies" to rule.search?.cookies,
        ).forEach { (path, expression) ->
            expression?.substringAfter(SCRIPT_MARKER, "")?.let { issues.script(path, it) }
        }
        return issues.sortedWith(issueOrder)
    }

    /** Paths are prefixed with the zero-based input index, e.g. `[0].search.url`. */
    fun validateAll(rules: List<RuleDto>): List<RuleIssue> {
        val nameCounts = rules.map { it.name.trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount()
        return rules.flatMapIndexed { index, rule ->
            val issues = validate(rule).map { it.copy(path = "[$index].${it.path}") }.toMutableList()
            val name = rule.name.trim()
            if ((nameCounts[name] ?: 0) > 1) issues.add(RuleIssue("[$index].name", "duplicate rule name: $name"))
            issues
        }.sortedWith(issueOrder)
    }

    private fun MutableList<RuleIssue>.required(path: String, value: String?) {
        if (value.isNullOrBlank()) add(RuleIssue(path, "required field is blank"))
    }

    private fun MutableList<RuleIssue>.template(path: String, value: String?) {
        if (value == null || "%s" !in value) add(RuleIssue(path, "template must contain %s"))
    }

    private fun MutableList<RuleIssue>.safeRegex(path: String, expression: String?) {
        if (expression.isNullOrBlank()) return
        if (expression.toByteArray(Charsets.UTF_8).size > MAX_REGEX_BYTES) {
            add(RuleIssue(path, "regex exceeds $MAX_REGEX_BYTES UTF-8 bytes"))
            return
        }
        try {
            Pattern.compile(expression)
        } catch (_: PatternSyntaxException) {
            add(RuleIssue(path, "unsupported regex syntax"))
        }
    }

    private fun MutableList<RuleIssue>.url(path: String, value: String?, template: Boolean = false) {
        if (value.isNullOrBlank()) return
        val candidate = if (template) value.replace("%s", "rule-value") else value
        val valid = try {
            val uri = URI(candidate)
            (uri.scheme.equals("http", ignoreCase = true) || uri.scheme.equals("https", ignoreCase = true)) &&
                !uri.host.isNullOrBlank() && (uri.port == -1 || uri.port in 1..65535)
        } catch (_: URISyntaxException) {
            false
        }
        if (!valid) add(RuleIssue(path, "URL must be an absolute HTTP or HTTPS URL"))
    }

    private fun MutableList<RuleIssue>.selector(path: String, expression: String?) {
        if (expression.isNullOrBlank()) return
        val marker = expression.indexOf(SCRIPT_MARKER)
        val selector = if (marker < 0) expression else expression.substring(0, marker)
        if (marker >= 0) script(path, expression.substring(marker + SCRIPT_MARKER.length))
        if (selector.toByteArray(Charsets.UTF_8).size > MAX_SELECTOR_BYTES) {
            add(RuleIssue(path, "selector exceeds $MAX_SELECTOR_BYTES UTF-8 bytes"))
            return
        }
        if (selector.isBlank()) {
            add(RuleIssue(path, "selector is blank"))
            return
        }
        val trimmed = selector.trim()
        if (trimmed.startsWith("/")) {
            try {
                XPathFactory.newInstance().newXPath().compile(trimmed)
            } catch (_: XPathExpressionException) {
                add(RuleIssue(path, "invalid XPath selector"))
            }
        } else {
            try {
                QueryParser.parse(trimmed)
            } catch (_: Selector.SelectorParseException) {
                add(RuleIssue(path, "invalid CSS selector"))
            }
        }
    }

    private fun MutableList<RuleIssue>.script(path: String, script: String) {
        if (script.toByteArray(Charsets.UTF_8).size > MAX_SCRIPT_BYTES) {
            add(RuleIssue(path, "script exceeds $MAX_SCRIPT_BYTES UTF-8 bytes"))
        }
        forbiddenTokens.findAll(script).map { it.value }.distinct().forEach { token ->
            add(RuleIssue(path, "script contains forbidden token: $token"))
        }
    }

    private fun selectorFields(rule: RuleDto): List<Pair<String, String?>> = listOf(
        "search.result" to rule.search?.result,
        "search.bookName" to rule.search?.bookName,
        "search.author" to rule.search?.author,
        "search.category" to rule.search?.category,
        "search.latestChapter" to rule.search?.latestChapter,
        "search.lastUpdateTime" to rule.search?.lastUpdateTime,
        "search.wordCount" to rule.search?.wordCount,
        "search.status" to rule.search?.status,
        "search.nextPage" to rule.search?.nextPage,
        "book.bookName" to rule.book?.bookName,
        "book.author" to rule.book?.author,
        "book.intro" to rule.book?.intro,
        "book.category" to rule.book?.category,
        "book.coverUrl" to rule.book?.coverUrl,
        "book.latestChapter" to rule.book?.latestChapter,
        "book.lastUpdateTime" to rule.book?.lastUpdateTime,
        "book.status" to rule.book?.status,
        "toc.list" to rule.toc?.list,
        "toc.item" to rule.toc?.item,
        "toc.nextPage" to rule.toc?.nextPage,
        "chapter.title" to rule.chapter?.title,
        "chapter.content" to rule.chapter?.content,
        "chapter.filterTag" to rule.chapter?.filterTag,
        "chapter.nextPage" to rule.chapter?.nextPage,
    )

    private companion object {
        const val SCRIPT_MARKER = "@js:"
        const val MAX_SELECTOR_BYTES = 2 * 1024
        const val MAX_REGEX_BYTES = 2 * 1024
        const val MAX_SCRIPT_BYTES = 16 * 1024
        val issueOrder = compareBy(RuleIssue::path, RuleIssue::message)
        val forbiddenTokens = Regex(
            "(?<![\\p{L}\\p{N}_$])(?:import|require|fetch|XMLHttpRequest|WebSocket|Java|Packages|process|eval)(?![\\p{L}\\p{N}_$])",
        )
    }
}
