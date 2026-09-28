package com.novelradar.app.source.parse

import org.jsoup.helper.W3CDom
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/** Selects source-page elements; any [SelectorExpression.script] is run by the caller. */
class HtmlSelector {
    fun elements(context: Element, selector: String): List<Element> {
        val query = SelectorExpression.parse(selector).selector
        if (query.isEmpty()) return emptyList()
        return if (query.startsWith('/')) {
            // Source rules use unprefixed HTML names; W3CDom otherwise creates XHTML namespaces.
            val converter = W3CDom().namespaceAware(false)
            val w3cDocument = converter.fromJsoup(context)
            val scopedQuery = if (context !is Document && query.startsWith("//")) ".$query" else query
            val startNode = if (context is Document) w3cDocument else converter.contextNode(w3cDocument)
            converter.sourceNodes(converter.selectXpath(scopedQuery, startNode), Element::class.java)
        } else {
            context.select(query)
        }
    }

    fun values(context: Element, selector: String, chapterContent: Boolean = false): List<String> =
        elements(context, selector).map { element ->
            when (element.normalName()) {
                "a" -> element.absUrl("href")
                "img" -> element.absUrl("src")
                "meta" -> element.attr("content")
                else -> if (chapterContent) element.html() else element.text()
            }
        }
}
