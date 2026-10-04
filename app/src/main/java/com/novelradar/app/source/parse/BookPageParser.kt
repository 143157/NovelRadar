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
        is SourceResult.Success -> SourceResult.Success(parsed.value.book)
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
        val parsedName = when (val read = extractRuleText(
            document, rule.bookName, "book.bookName", selector, scripts,
            required = !rule.bookName.isNullOrBlank(),
        )) {
            is SourceResult.Success -> read.value
            is SourceResult.Failure -> return read
        }
        val parsedAuthor = when (val read = extractRuleText(
            document, rule.author, "book.author", selector, scripts,
            required = !rule.author.isNullOrBlank(),
        )) {
            is SourceResult.Success -> read.value
            is SourceResult.Failure -> return read
        }
        val bookName = parsedName ?: hit.bookName
        val author = parsedAuthor ?: hit.author
        if (bookName.isBlank()) return SourceResult.Failure(SourceFailure.Parse("book.bookName", rule.bookName.orEmpty()))
        val intro = when (val read = extractRuleText(document, rule.intro, "book.intro", selector, scripts)) {
            is SourceResult.Success -> read.value
            is SourceResult.Failure -> return read
        }
        val coverUrl = when (val read = extractCoverUrl(document, baseUrl, rule.coverUrl)) {
            is SourceResult.Success -> read.value
            is SourceResult.Failure -> return read
        }
        return SourceResult.Success(BookDetails(Book(
            bookId = bookId,
            normalizedBookKey = "${normalizeIdentityPart(bookName)}|${normalizeIdentityPart(author)}",
            bookName = bookName,
            author = author,
            coverUrl = coverUrl,
            introduction = intro,
        ), BookIdentityEvidence(parsedName, parsedAuthor)))
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
