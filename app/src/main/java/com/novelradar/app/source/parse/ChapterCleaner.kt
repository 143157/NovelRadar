package com.novelradar.app.source.parse

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.rule.ChapterRuleDto
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.select.Selector

/** Turns a bounded HTML fragment into readable paragraphs without executing regex backtracking. */
class ChapterCleaner {
    fun clean(contentHtml: String, rule: ChapterRuleDto): SourceResult<String> {
        if (contentHtml.toByteArray(Charsets.UTF_8).size > MAX_FRAGMENT_BYTES) {
            return unsafe("chapter content exceeds 2 MiB")
        }
        val filtered = try {
            val withoutAds = rule.filterTxt?.takeIf(String::isNotBlank)?.let {
                Pattern.compile(it).matcher(contentHtml).replaceAll("")
            } ?: contentHtml
            rule.paragraphTag?.takeIf(String::isNotBlank)?.let {
                Pattern.compile(it).matcher(withoutAds).replaceAll("<br>")
            } ?: withoutAds
        } catch (_: PatternSyntaxException) {
            return unsafe("unsupported chapter filter regex")
        }

        val document = Jsoup.parseBodyFragment(filtered)
        try {
            rule.filterTag?.takeIf(String::isNotBlank)?.let { document.select(it).remove() }
        } catch (_: Selector.SelectorParseException) {
            return unsafe("invalid chapter filter selector")
        }
        document.select("script, style, noscript, iframe").remove()
        val out = StringBuilder()
        document.body().childNodes().forEach { appendText(it, out) }
        val normalized = out.toString()
            .replace('\u00a0', ' ')
            .replace(Regex("[ \\t]*\\n[ \\t]*"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return SourceResult.Success(normalized)
    }

    private fun appendText(node: Node, out: StringBuilder) {
        when (node) {
            is TextNode -> {
                val text = node.wholeText
                    .replace('\u00a0', ' ')
                    .replace(Regex("\\s+"), " ")
                    .trim()
                if (text.isNotEmpty()) out.append(text)
            }
            is Element -> {
                if (node.normalName() == "br") {
                    out.append('\n')
                    return
                }
                val paragraph = node.normalName() in PARAGRAPH_TAGS
                if (paragraph && out.isNotEmpty() && out.last() != '\n') out.append('\n')
                val beforeChildren = out.length
                node.childNodes().forEach { appendText(it, out) }
                if (paragraph && (out.length == beforeChildren || out.last() != '\n')) out.append('\n')
            }
        }
    }

    private fun unsafe(reason: String): SourceResult.Failure =
        SourceResult.Failure(SourceFailure.UnsafeRule(reason))

    private companion object {
        const val MAX_FRAGMENT_BYTES = 2 * 1024 * 1024
        val PARAGRAPH_TAGS = setOf("p", "div", "section", "article", "li", "blockquote")
    }
}
