package com.novelradar.app.source.parse

import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookDetails
import com.novelradar.app.domain.model.BookIdentityEvidence
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.model.normalizeIdentityPart
import com.novelradar.app.source.rule.BookRuleDto
import com.novelradar.app.source.script.ScriptExecutor
import org.jsoup.Jsoup

/** Enriches a search hit using its detail page while preserving the caller's local book ID. */
class BookPageParser(
    private val selector: HtmlSelector,
    private val scripts: ScriptExecutor,
) {
    suspend fun parse(
        html: String,
        baseUrl: String,
        hit: SearchHit,
        bookId: String,
        rule: BookRuleDto,
    ): SourceResult<Book> = when (val parsed = parseDetails(html, baseUrl, hit, bookId, rule)) {
        is SourceResult.Success -> parsed.value.warning?.let { SourceResult.Failure(it) }
            ?: SourceResult.Success(parsed.value.book)
        is SourceResult.Failure -> parsed
    }

    suspend fun parseDetails(
        html: String,
        baseUrl: String,
        hit: SearchHit,
        bookId: String,
        rule: BookRuleDto,
    ): SourceResult<BookDetails> {
        val document = Jsoup.parse(html, baseUrl)
        var warning: SourceFailure? = null
        fun parsedValue(result: SourceResult<String?>): String? = when (result) {
            is SourceResult.Success -> result.value
            is SourceResult.Failure -> { if (warning == null) warning = result.error; null }
        }
        val parsedName = parsedValue(extractRuleText(
            document, rule.bookName, "book.bookName", selector, scripts,
            required = !rule.bookName.isNullOrBlank(),
        ))
        // The author remains independent evidence even if the title's rule failed.
        val parsedAuthor = parsedValue(extractRuleText(
            document, rule.author, "book.author", selector, scripts,
            required = !rule.author.isNullOrBlank(),
        ))
        val bookName = parsedName ?: hit.bookName
        val author = parsedAuthor ?: hit.author
        if (bookName.isBlank() && warning == null) warning = SourceFailure.Parse("book.bookName", rule.bookName.orEmpty())
        val intro = if (warning == null) parsedValue(extractRuleText(document, rule.intro, "book.intro", selector, scripts)) else null
        val coverUrl = if (warning == null) parsedValue(extractCoverUrl(document, baseUrl, rule.coverUrl)) else null
        return SourceResult.Success(BookDetails(Book(
            bookId = bookId,
            normalizedBookKey = "${normalizeIdentityPart(bookName)}|${normalizeIdentityPart(author)}",
            bookName = bookName,
            author = author,
            coverUrl = coverUrl,
            introduction = intro,
        ), BookIdentityEvidence(parsedName, parsedAuthor), warning))
    }

    private suspend fun extractCoverUrl(
        document: org.jsoup.nodes.Document,
        baseUrl: String,
        rawExpression: String?,
    ): SourceResult<String?> {
        if (rawExpression.isNullOrBlank()) return SourceResult.Success(null)
        val expression = SelectorExpression.parse(rawExpression)
        val nodes = when (val selected = selectRuleNodes(document, rawExpression, "book.coverUrl", selector)) {
            is SourceResult.Success -> selected.value
            is SourceResult.Failure -> return selected
        }
        val node = nodes.firstOrNull() ?: return SourceResult.Success(null)
        val rawUrl = when (node.normalName()) {
            "img" -> node.attr("src")
            "meta" -> node.attr("content")
            "a" -> node.attr("href")
            else -> node.selectFirst("img[src]")?.attr("src") ?: node.text()
        }
        if (rawUrl.isBlank()) return SourceResult.Success(null)
        val transformed = when (val result = applyRuleScript(rawUrl, expression, scripts)) {
            is SourceResult.Success -> result.value
            is SourceResult.Failure -> return result
        }
        return SourceResult.Success(resolveRuleUrl(baseUrl, transformed))
    }
}
