package com.novelradar.app.source.http

import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.rule.SearchRuleDto
import java.net.URLEncoder
import java.util.Locale
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request

/** Builds search requests without interpreting keyword text as form syntax. */
class SearchRequestFactory {
    fun create(rule: SearchRuleDto, keyword: String, allowCleartext: Boolean = false): SourceResult<Request> {
        val template = rule.url?.trim().orEmpty()
        val method = rule.method?.trim()?.uppercase(Locale.ROOT)
        if (method != "GET" && method != "POST") return unsafe("search.method must be GET or POST")
        val rawUrl = if (method == "GET") {
            if (!template.contains("%s")) return unsafe("search.url must contain %s")
            template.replace("%s", percentEncode(keyword))
        } else template
        val url = rawUrl.toHttpUrlOrNull() ?: return unsafe("search.url must be an absolute HTTP or HTTPS URL")
        if (!HttpPolicy.isAllowed(url, allowCleartext)) return unsafe("search.url is not permitted by HTTP policy")
        val builder = Request.Builder().url(url)
        if (method == "POST") {
            val fields = parseForm(rule.data ?: return unsafe("search.data is required"))
                ?: return unsafe("search.data has an invalid form template")
            if (fields.none { "%s" in it.second }) return unsafe("search.data must contain %s")
            builder.post(FormBody.Builder().apply {
                fields.forEach { (key, value) -> addEncoded(percentEncode(key), percentEncode(value.replace("%s", keyword))) }
            }.build())
        }
        return SourceResult.Success(builder.build())
    }

    private fun parseForm(template: String): List<Pair<String, String>>? {
        val trimmed = template.trim()
        if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return null
        val text = trimmed.substring(1, trimmed.length - 1)
        if (text.isBlank()) return null
        val items = splitOutsideQuotes(text, ',') ?: return null
        return items.map { item ->
            val separator = firstOutsideQuotes(item, ':')
            if (separator <= 0) return null
            val key = unquote(item.substring(0, separator).trim()) ?: return null
            val value = unquote(item.substring(separator + 1).trim()) ?: return null
            if (key.isBlank() || "%s" in key) return null
            key to value
        }
    }

    private fun splitOutsideQuotes(text: String, delimiter: Char): List<String>? {
        val parts = mutableListOf<String>()
        var quote: Char? = null
        var escaped = false
        var start = 0
        text.forEachIndexed { index, char ->
            if (escaped) escaped = false
            else if (char == '\\' && quote != null) escaped = true
            else if (char == quote) quote = null
            else if (quote == null && (char == '\'' || char == '"')) quote = char
            else if (quote == null && char == delimiter) {
                parts += text.substring(start, index)
                start = index + 1
            }
        }
        if (quote != null || escaped) return null
        parts += text.substring(start)
        return parts.takeIf { it.none(String::isBlank) }
    }

    private fun firstOutsideQuotes(text: String, delimiter: Char): Int {
        var quote: Char? = null
        var escaped = false
        text.forEachIndexed { index, char ->
            if (escaped) escaped = false
            else if (char == '\\' && quote != null) escaped = true
            else if (char == quote) quote = null
            else if (quote == null && char == delimiter) return index
        }
        return -1
    }

    private fun unquote(value: String): String? {
        if (value.isEmpty()) return ""
        val first = value.first()
        val last = value.last()
        if (first == '\'' || first == '"') {
            if (last != first || value.length < 2) return null
            return value.substring(1, value.length - 1).replace("\\$first", "$first").replace("\\\\", "\\")
        }
        if (last == '\'' || last == '"' || value.any { it == '\'' || it == '"' }) return null
        return value
    }

    private fun percentEncode(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun unsafe(reason: String): SourceResult.Failure = SourceResult.Failure(SourceFailure.UnsafeRule(reason))
}
