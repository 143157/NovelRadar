package com.novelradar.app.source.parse

import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.rule.SearchRuleDto
import com.novelradar.app.source.script.ScriptExecutor
import java.net.URI
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parses one search response; page iteration and cross-source merging belong to SourceEngine. */
class SearchPageParser(
    private val selector: HtmlSelector,
    private val scripts: ScriptExecutor,
) {
    suspend fun parse(
        html: String,
        baseUrl: String,
        sourceId: String,
        rule: SearchRuleDto,
    ): SourceResult<List<SearchHit>> {
        val resultExpression = rule.result?.takeIf { it.isNotBlank() }
            ?: return SourceResult.Failure(SourceFailure.Parse("search.result", rule.result.orEmpty()))
        val nameExpression = rule.bookName?.takeIf { it.isNotBlank() }
            ?: return SourceResult.Failure(SourceFailure.Parse("search.bookName", rule.bookName.orEmpty()))
        val document = Jsoup.parse(html, baseUrl)
        val rows = when (val selected = selectRuleNodes(document, resultExpression, "search.result", selector)) {
            is SourceResult.Success -> selected.value
            is SourceResult.Failure -> return selected
        }
        if (rows.isEmpty()) return SourceResult.Success(emptyList())

        val hits = mutableListOf<SearchHit>()
        var firstRowFailure: SourceFailure.Parse? = null
        for (row in rows) {
            val name = when (val read = extractRuleText(row, nameExpression, "search.bookName", selector, scripts, required = true)) {
                is SourceResult.Success -> read.value.orEmpty()
                is SourceResult.Failure -> {
                    if (read.error is SourceFailure.Parse) {
                        if (firstRowFailure == null) firstRowFailure = read.error
                        continue
                    }
                    return read
                }
            }
            val nameNodes = when (val selected = selectRuleNodes(row, nameExpression, "search.bookName", selector)) {
                is SourceResult.Success -> selected.value
                is SourceResult.Failure -> return selected
            }
            val detailUrl = nameNodes.firstOrNull()?.let { ruleNodeUrl(it, baseUrl) }
            if (detailUrl == null) {
                if (firstRowFailure == null) firstRowFailure = SourceFailure.Parse("search.bookName", nameExpression)
                continue
            }
            val author = when (val read = extractRuleText(row, rule.author, "search.author", selector, scripts)) {
                is SourceResult.Success -> read.value.orEmpty()
                is SourceResult.Failure -> return read
            }
            val latest = when (val read = extractRuleText(row, rule.latestChapter, "search.latestChapter", selector, scripts)) {
                is SourceResult.Success -> read.value
                is SourceResult.Failure -> return read
            }
            val updated = when (val read = extractRuleText(row, rule.lastUpdateTime, "search.lastUpdateTime", selector, scripts)) {
                is SourceResult.Success -> read.value
                is SourceResult.Failure -> return read
            }
            hits += SearchHit(
                sourceId = sourceId,
                bookName = name,
                author = author,
                detailUrl = detailUrl,
                latestChapter = latest,
                updatedAtText = updated,
            )
        }
        return if (hits.isEmpty()) {
            SourceResult.Failure(firstRowFailure ?: SourceFailure.Parse("search.bookName", nameExpression))
        } else {
            SourceResult.Success(hits)
        }
    }
}

/** Internal field primitives shared by the page parsers in this package. */
internal fun selectRuleNodes(
    context: Element,
    rawExpression: String,
    field: String,
    selector: HtmlSelector,
): SourceResult<List<Element>> = try {
    SourceResult.Success(selector.elements(context, SelectorExpression.parse(rawExpression).selector))
} catch (error: RuntimeException) {
    SourceResult.Failure(SourceFailure.Parse(field, rawExpression))
}

internal suspend fun extractRuleText(
    context: Element,
    rawExpression: String?,
    field: String,
    selector: HtmlSelector,
    scripts: ScriptExecutor,
    required: Boolean = false,
): SourceResult<String?> {
    if (rawExpression.isNullOrBlank()) {
        return if (required) SourceResult.Failure(SourceFailure.Parse(field, rawExpression.orEmpty()))
        else SourceResult.Success(null)
    }
    val expression = SelectorExpression.parse(rawExpression)
    val nodes = when (val selected = selectRuleNodes(context, rawExpression, field, selector)) {
        is SourceResult.Success -> selected.value
        is SourceResult.Failure -> return selected
    }
    val raw = nodes.firstOrNull()?.let { node ->
        if (node.normalName() == "meta") node.attr("content") else node.text()
    }?.trim().orEmpty()
    if (raw.isEmpty()) {
        return if (required) SourceResult.Failure(SourceFailure.Parse(field, rawExpression))
        else SourceResult.Success(null)
    }
    val transformed = when (val result = applyRuleScript(raw, expression, scripts)) {
        is SourceResult.Success -> result.value.trim()
        is SourceResult.Failure -> return result
    }
    return if (required && transformed.isEmpty()) {
        SourceResult.Failure(SourceFailure.Parse(field, rawExpression))
    } else {
        SourceResult.Success(transformed.takeIf { it.isNotEmpty() })
    }
}

internal suspend fun applyRuleScript(
    input: String,
    expression: SelectorExpression,
    scripts: ScriptExecutor,
): SourceResult<String> = expression.script?.let { scripts.transform(input, it) }
    ?: SourceResult.Success(input)

/** Resolve only HTTP(S) links; never let a page turn a chapter URL into a local or script URI. */
internal fun resolveRuleUrl(baseUrl: String, rawUrl: String): String? = try {
    val resolved = URI(baseUrl).resolve(rawUrl.trim()).normalize()
    if (resolved.scheme?.lowercase() in setOf("http", "https") && !resolved.host.isNullOrBlank()) {
        resolved.toString()
    } else null
} catch (error: IllegalArgumentException) {
    null
}

internal fun ruleNodeUrl(node: Element, baseUrl: String): String? {
    val linked = when (node.normalName()) {
        "a" -> node
        "img" -> node
        "meta" -> node
        "option" -> node
        else -> node.selectFirst("a[href], img[src]") ?: node
    }
    val raw = when (linked.normalName()) {
        "a" -> linked.attr("href")
        "img" -> linked.attr("src")
        "meta" -> linked.attr("content")
        "option" -> linked.attr("value")
        else -> linked.attr("href").ifEmpty { linked.attr("src") }
    }
    return raw.takeIf { it.isNotBlank() }?.let { resolveRuleUrl(baseUrl, it) }
}
