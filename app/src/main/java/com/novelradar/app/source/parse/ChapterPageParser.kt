package com.novelradar.app.source.parse

import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.rule.ChapterRuleDto
import com.novelradar.app.source.script.ScriptExecutor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.select.Selector

data class ChapterPage(
    val title: String,
    val content: String,
    val nextPageUrl: String? = null,
)

class ChapterPageParser(
    private val selector: HtmlSelector,
    private val scripts: ScriptExecutor,
    private val cleaner: ChapterCleaner = ChapterCleaner(),
) {
    suspend fun parse(html: String, baseUrl: String, rule: ChapterRuleDto): SourceResult<ChapterPage> {
        val document = Jsoup.parse(html, baseUrl)
        val titleSelector = rule.title.orEmpty()
        val contentSelector = rule.content.orEmpty()
        val title = when (val value = field(document, titleSelector, "chapter.title", false)) {
            is SourceResult.Success -> value.value
            is SourceResult.Failure -> return value
        }
        val contentHtml = when (val value = field(document, contentSelector, "chapter.content", true)) {
            is SourceResult.Success -> value.value
            is SourceResult.Failure -> return value
        }
        val content = when (val cleaned = cleaner.clean(contentHtml, rule)) {
            is SourceResult.Success -> cleaned.value
            is SourceResult.Failure -> return cleaned
        }
        if (content.isBlank()) return SourceResult.Failure(SourceFailure.Parse("chapter.content", contentSelector))

        val nextPage = if (rule.pagination && !rule.nextPage.isNullOrBlank()) {
            val expression = SelectorExpression.parse(rule.nextPage)
            val node = try {
                selector.elements(document, expression.selector).firstOrNull()
            } catch (_: Selector.SelectorParseException) {
                return SourceResult.Failure(SourceFailure.Parse("chapter.nextPage", rule.nextPage))
            }
            val raw = node?.attr("href")?.ifBlank { node.attr("value") }?.takeIf(String::isNotBlank)
            if (raw == null) null else {
                val transformed = if (expression.script == null) raw else when (val result = scripts.transform(raw, expression.script)) {
                    is SourceResult.Success -> result.value
                    is SourceResult.Failure -> return result
                }
                resolveRuleUrl(baseUrl, transformed)
            }
        } else null
        return SourceResult.Success(ChapterPage(title, content, nextPage))
    }

    private suspend fun field(
        document: Element,
        rawSelector: String,
        field: String,
        content: Boolean,
    ): SourceResult<String> {
        val expression = SelectorExpression.parse(rawSelector)
        val node = try {
            selector.elements(document, expression.selector).firstOrNull()
        } catch (_: Selector.SelectorParseException) {
            return SourceResult.Failure(SourceFailure.Parse(field, rawSelector))
        } ?: return SourceResult.Failure(SourceFailure.Parse(field, rawSelector))
        val initial = if (content) node.html() else node.text()
        val value = if (expression.script == null) SourceResult.Success(initial)
            else scripts.transform(initial, expression.script)
        return when (value) {
            is SourceResult.Success -> {
                if (value.value.isBlank()) SourceResult.Failure(SourceFailure.Parse(field, rawSelector))
                else SourceResult.Success(value.value.trim())
            }
            is SourceResult.Failure -> value
        }
    }
}
