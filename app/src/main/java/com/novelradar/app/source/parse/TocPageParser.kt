package com.novelradar.app.source.parse

import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.rule.TocRuleDto
import com.novelradar.app.source.script.ScriptExecutor
import java.security.MessageDigest
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

/** Parses one TOC page in document order; cross-page reversal belongs to SourceEngine. */
class TocPageParser(
    private val selector: HtmlSelector,
    private val scripts: ScriptExecutor,
) {
    suspend fun parse(
        html: String,
        baseUrl: String,
        sourceId: String,
        bookId: String,
        rule: TocRuleDto,
    ): SourceResult<List<Chapter>> {
        val itemExpression = rule.item?.takeIf { it.isNotBlank() }
            ?: return SourceResult.Failure(SourceFailure.Parse("toc.item", rule.item.orEmpty()))
        val document = Jsoup.parse(html, baseUrl)
        val scope = when (val selected = selectListScope(document, baseUrl, rule.list)) {
            is SourceResult.Success -> selected.value
            is SourceResult.Failure -> return selected
        }
        val nodes = when (val selected = selectRuleNodes(scope, itemExpression, "toc.item", selector)) {
            is SourceResult.Success -> selected.value
            is SourceResult.Failure -> return selected
        }
        if (nodes.isEmpty()) return SourceResult.Failure(SourceFailure.Parse("toc.item", itemExpression))

        val expression = SelectorExpression.parse(itemExpression)
        val seenUrls = hashSetOf<String>()
        val chapters = mutableListOf<Chapter>()
        for (node in nodes) {
            val title = node.text().trim()
            val rawUrl = when (node.normalName()) {
                "a" -> node.attr("href")
                "option" -> node.attr("value")
                else -> node.selectFirst("a[href]")?.attr("href") ?: node.attr("href")
            }
            if (title.isEmpty() || rawUrl.isBlank()) continue
            val transformedUrl = when (val result = applyRuleScript(rawUrl, expression, scripts)) {
                is SourceResult.Success -> result.value
                is SourceResult.Failure -> return result
            }
            val url = resolveRuleUrl(baseUrl, transformedUrl) ?: continue
            if (url.length > SourceLimits.MAX_URL_CHARS) {
                return SourceResult.Failure(SourceFailure.UnsafeRule("TOC chapter URL exceeds ${SourceLimits.MAX_URL_CHARS} characters"))
            }
            if (url in seenUrls) continue
            if (chapters.size >= SourceLimits.MAX_TOC_CHAPTERS) {
                return SourceResult.Failure(SourceFailure.UnsafeRule("TOC page exceeds ${SourceLimits.MAX_TOC_CHAPTERS} chapters"))
            }
            seenUrls.add(url)
            chapters += Chapter(
                bookId = bookId,
                sourceId = sourceId,
                chapterId = chapterIdFor(url),
                chapterIndex = chapters.size,
                title = title,
                url = url,
            )
        }
        return if (chapters.isEmpty()) SourceResult.Failure(SourceFailure.Parse("toc.item", itemExpression))
        else SourceResult.Success(chapters)
    }

    private suspend fun selectListScope(
        document: org.jsoup.nodes.Document,
        baseUrl: String,
        rawExpression: String?,
    ): SourceResult<Element> {
        if (rawExpression.isNullOrBlank()) return SourceResult.Success(document)
        val expression = SelectorExpression.parse(rawExpression)
        val nodes = when (val selected = selectRuleNodes(document, rawExpression, "toc.list", selector)) {
            is SourceResult.Success -> selected.value
            is SourceResult.Failure -> return selected
        }
        val node = nodes.firstOrNull()
            ?: return SourceResult.Failure(SourceFailure.Parse("toc.list", rawExpression))
        val script = expression.script ?: return SourceResult.Success(node)
        val transformed = when (val result = scripts.transform(node.outerHtml(), script)) {
            is SourceResult.Success -> result.value
            is SourceResult.Failure -> return result
        }
        return SourceResult.Success(Jsoup.parse(transformed, baseUrl))
    }

    private fun chapterIdFor(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
