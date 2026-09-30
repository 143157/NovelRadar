package com.novelradar.app.source

import com.google.re2j.Pattern
import com.google.re2j.PatternSyntaxException
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.http.HttpPolicy
import com.novelradar.app.source.http.SearchRequestFactory
import com.novelradar.app.source.http.SourceHttpClient
import com.novelradar.app.source.http.SourceHttpPage
import com.novelradar.app.source.parse.BookPageParser
import com.novelradar.app.source.parse.ChapterPage
import com.novelradar.app.source.parse.ChapterPageParser
import com.novelradar.app.source.parse.HtmlSelector
import com.novelradar.app.source.parse.SearchPageParser
import com.novelradar.app.source.parse.SelectorExpression
import com.novelradar.app.source.parse.SourceLimits
import com.novelradar.app.source.parse.TocPageParser
import com.novelradar.app.source.parse.resolveRuleUrl
import com.novelradar.app.source.rule.BookRuleDto
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.script.ScriptExecutor
import java.net.URLEncoder
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import okhttp3.Request
import org.jsoup.Jsoup

/** Bounded network flow for one imported source; it never owns app state or downloaded files. */
class SourceEngine(
    private val requests: SearchRequestFactory,
    private val http: SourceHttpClient,
    private val searchParser: SearchPageParser,
    private val bookParser: BookPageParser,
    private val tocParser: TocPageParser,
    private val chapterParser: ChapterPageParser,
    private val selector: HtmlSelector,
    private val scripts: ScriptExecutor,
) {
    suspend fun search(
        rule: RuleDto,
        sourceId: String,
        keyword: String,
        allowCleartext: Boolean = false,
    ): SourceResult<List<SearchHit>> {
        if (!safeId(sourceId)) return unsafe("invalid source ID")
        if (rule.disabled || rule.search?.disabled == true) return unsafe("source search is disabled")
        val searchRule = rule.search ?: return unsafe("source has no search rule")
        var request = when (val built = requests.create(searchRule, keyword, allowCleartext)) {
            is SourceResult.Success -> built.value
            is SourceResult.Failure -> return built
        }
        val visited = hashSetOf<String>()
        val parsedFinalUrls = hashSetOf<String>()
        val hits = linkedMapOf<String, SearchHit>()
        var savedFieldBytes = 0L
        repeat(MAX_SEARCH_PAGES) { pageIndex ->
            if (!visited.add(request.url.toString())) return SourceResult.Success(hits.values.toList())
            val page = when (val fetched = http.execute(
                sourceId, request, allowCleartext,
                initialCookies = if (pageIndex == 0) searchRule.cookies else null,
            )) {
                is SourceResult.Success -> fetched.value
                is SourceResult.Failure -> return fetched
            }
            if (!parsedFinalUrls.add(page.finalUrl)) return SourceResult.Success(hits.values.toList())
            visited.add(page.finalUrl)
            val html = when (val decoded = decode(page)) {
                is SourceResult.Success -> decoded.value
                is SourceResult.Failure -> return decoded
            }
            if (isVerificationPage(html)) return SourceResult.Failure(SourceFailure.VerificationRequired)
            val parsedHits = when (val parsed = searchParser.parse(html, page.finalUrl, sourceId, searchRule)) {
                is SourceResult.Success -> parsed.value
                is SourceResult.Failure -> return parsed
            }
            for (hit in parsedHits) {
                if (hit.detailUrl !in hits) {
                    if (hits.size >= SourceLimits.MAX_SEARCH_HITS) return unsafe("search exceeds ${SourceLimits.MAX_SEARCH_HITS} distinct hits")
                    val addedBytes = SourceLimits.searchFieldBytes(hit)
                    if (savedFieldBytes + addedBytes > SourceLimits.MAX_SEARCH_FIELDS_BYTES) {
                        return unsafe("search fields exceed ${SourceLimits.MAX_SEARCH_FIELDS_BYTES} UTF-8 bytes")
                    }
                    savedFieldBytes += addedBytes
                    hits[hit.detailUrl] = hit
                }
            }
            if (!searchRule.pagination || searchRule.nextPage.isNullOrBlank()) return SourceResult.Success(hits.values.toList())
            val next = when (val extracted = nextPage(html, page.finalUrl, searchRule.nextPage, "search.nextPage", visited)) {
                is SourceResult.Success -> extracted.value
                is SourceResult.Failure -> return extracted
            } ?: return SourceResult.Success(hits.values.toList())
            request = getRequest(next, allowCleartext)
                ?: return unsafe("search next page is not permitted")
        }
        return SourceResult.Success(hits.values.toList())
    }

    suspend fun book(
        rule: RuleDto,
        sourceId: String,
        hit: SearchHit,
        bookId: String,
        allowCleartext: Boolean = false,
    ): SourceResult<Book> {
        validateBookRequest(rule, sourceId, hit, bookId)?.let { return it }
        val page = when (val fetched = getPage(sourceId, hit.detailUrl, allowCleartext)) {
            is SourceResult.Success -> fetched.value
            is SourceResult.Failure -> return fetched
        }
        val html = when (val decoded = decode(page)) {
            is SourceResult.Success -> decoded.value
            is SourceResult.Failure -> return decoded
        }
        return bookParser.parse(html, page.finalUrl, hit, bookId, rule.book ?: BookRuleDto())
    }

    suspend fun toc(
        rule: RuleDto,
        sourceId: String,
        hit: SearchHit,
        bookId: String,
        allowCleartext: Boolean = false,
    ): SourceResult<List<Chapter>> {
        validateBookRequest(rule, sourceId, hit, bookId)?.let { return it }
        val tocRule = rule.toc ?: return unsafe("source has no TOC rule")
        val remoteId = if (listOf(tocRule.url, tocRule.baseUri).any { it?.contains("%s") == true }) {
            when (val extracted = remoteBookId(rule.book?.url, hit.detailUrl)) {
                is SourceResult.Success -> extracted.value
                is SourceResult.Failure -> return extracted
            }
        } else null
        val initialUrl = expandTemplate(tocRule.url, remoteId) ?: hit.detailUrl
        val linkBase = expandTemplate(tocRule.baseUri, remoteId)
        if (linkBase != null && !HttpPolicy.isAllowed(linkBase, allowCleartext)) return unsafe("TOC link base is not permitted")

        var url = initialUrl
        val visitedPages = hashSetOf<String>()
        val parsedFinalUrls = hashSetOf<String>()
        val chapters = linkedMapOf<String, Chapter>()
        var savedFieldBytes = 0L
        repeat(MAX_TOC_PAGES) {
            if (!visitedPages.add(url)) return SourceResult.Success(orderedChapters(chapters.values.toList(), tocRule.isDesc))
            val page = when (val fetched = getPage(sourceId, url, allowCleartext)) {
                is SourceResult.Success -> fetched.value
                is SourceResult.Failure -> return fetched
            }
            if (!parsedFinalUrls.add(page.finalUrl)) {
                return SourceResult.Success(orderedChapters(chapters.values.toList(), tocRule.isDesc))
            }
            visitedPages.add(page.finalUrl)
            val html = when (val decoded = decode(page)) {
                is SourceResult.Success -> decoded.value
                is SourceResult.Failure -> return decoded
            }
            val parsedChapters = when (val parsed = tocParser.parse(html, linkBase ?: page.finalUrl, sourceId, bookId, tocRule)) {
                is SourceResult.Success -> parsed.value
                is SourceResult.Failure -> return parsed
            }
            for (chapter in parsedChapters) {
                val chapterUrl = chapter.url ?: continue
                if (chapterUrl !in chapters) {
                    if (chapters.size >= SourceLimits.MAX_TOC_CHAPTERS) return unsafe("TOC exceeds ${SourceLimits.MAX_TOC_CHAPTERS} distinct chapters")
                    val addedBytes = SourceLimits.utf8Bytes(chapter.title) + SourceLimits.utf8Bytes(chapterUrl)
                    if (savedFieldBytes + addedBytes > SourceLimits.MAX_TOC_FIELDS_BYTES) {
                        return unsafe("TOC fields exceed ${SourceLimits.MAX_TOC_FIELDS_BYTES} UTF-8 bytes")
                    }
                    savedFieldBytes += addedBytes
                    chapters[chapterUrl] = chapter
                }
            }
            if (!tocRule.pagination || tocRule.nextPage.isNullOrBlank()) {
                return SourceResult.Success(orderedChapters(chapters.values.toList(), tocRule.isDesc))
            }
            url = when (val extracted = nextPage(html, page.finalUrl, tocRule.nextPage, "toc.nextPage", visitedPages)) {
                is SourceResult.Success -> extracted.value
                is SourceResult.Failure -> return extracted
            } ?: return SourceResult.Success(orderedChapters(chapters.values.toList(), tocRule.isDesc))
        }
        return SourceResult.Success(orderedChapters(chapters.values.toList(), tocRule.isDesc))
    }

    suspend fun chapter(
        rule: RuleDto,
        sourceId: String,
        chapterUrl: String,
        allowCleartext: Boolean = false,
    ): SourceResult<ChapterPage> {
        if (!safeId(sourceId)) return unsafe("invalid source ID")
        if (rule.disabled) return unsafe("source is disabled")
        val chapterRule = rule.chapter ?: return unsafe("source has no chapter rule")
        val visited = hashSetOf<String>()
        val parsedFinalUrls = hashSetOf<String>()
        val parts = mutableListOf<String>()
        var contentBytes = 0L
        var url = chapterUrl
        var title: String? = null
        repeat(MAX_CHAPTER_PAGES) {
            if (!visited.add(url)) return SourceResult.Success(ChapterPage(title.orEmpty(), parts.joinToString("\n\n")))
            val page = when (val fetched = getPage(sourceId, url, allowCleartext)) {
                is SourceResult.Success -> fetched.value
                is SourceResult.Failure -> return fetched
            }
            if (!parsedFinalUrls.add(page.finalUrl)) {
                return SourceResult.Success(ChapterPage(title.orEmpty(), parts.joinToString("\n\n")))
            }
            visited.add(page.finalUrl)
            val html = when (val decoded = decode(page)) {
                is SourceResult.Success -> decoded.value
                is SourceResult.Failure -> return decoded
            }
            val parsed = when (val result = chapterParser.parse(html, page.finalUrl, chapterRule)) {
                is SourceResult.Success -> result.value
                is SourceResult.Failure -> return result
            }
            if (title == null) title = parsed.title
            val additionalBytes = parsed.content.toByteArray(Charsets.UTF_8).size.toLong() + if (parts.isEmpty()) 0 else 2
            if (contentBytes + additionalBytes > MAX_CHAPTER_CONTENT_BYTES) {
                return unsafe("chapter content exceeds $MAX_CHAPTER_CONTENT_BYTES UTF-8 bytes")
            }
            contentBytes += additionalBytes
            parts += parsed.content
            if (!chapterRule.pagination || parsed.nextPageUrl == null || parsed.nextPageUrl in visited) {
                return SourceResult.Success(ChapterPage(title.orEmpty(), parts.joinToString("\n\n")))
            }
            url = parsed.nextPageUrl
        }
        return SourceResult.Success(ChapterPage(title.orEmpty(), parts.joinToString("\n\n")))
    }

    private fun validateBookRequest(rule: RuleDto, sourceId: String, hit: SearchHit, bookId: String): SourceResult.Failure? {
        if (!safeId(sourceId) || !safeId(bookId)) return unsafe("invalid local source or book ID")
        if (rule.disabled) return unsafe("source is disabled")
        if (hit.sourceId != sourceId) return unsafe("search hit belongs to a different source")
        return null
    }

    private suspend fun getPage(sourceId: String, url: String, allowCleartext: Boolean): SourceResult<SourceHttpPage> {
        val request = getRequest(url, allowCleartext) ?: return unsafe("page URL is not permitted")
        return http.execute(sourceId, request, allowCleartext)
    }

    private fun getRequest(url: String, allowCleartext: Boolean): Request? {
        if (url.length > SourceLimits.MAX_URL_CHARS || !HttpPolicy.isAllowed(url, allowCleartext)) return null
        return try { Request.Builder().url(url).get().build() } catch (_: IllegalArgumentException) { null }
    }

    private suspend fun nextPage(
        html: String,
        finalUrl: String,
        rawSelector: String,
        field: String,
        visited: Set<String>,
    ): SourceResult<String?> {
        val expression = SelectorExpression.parse(rawSelector)
        val nodes = try { selector.elements(Jsoup.parse(html, finalUrl), expression.selector) }
        catch (_: RuntimeException) { return SourceResult.Failure(SourceFailure.Parse(field, rawSelector)) }
        for (node in nodes) {
            val raw = when (node.normalName()) {
                "a" -> node.attr("href")
                "option" -> node.attr("value")
                else -> node.attr("href").ifBlank { node.attr("value") }
            }
            if (raw.isBlank()) continue
            val transformed = if (expression.script == null) raw else when (val result = scripts.transform(raw, expression.script)) {
                is SourceResult.Success -> result.value
                is SourceResult.Failure -> return result
            }
            val resolved = resolveRuleUrl(finalUrl, transformed) ?: continue
            if (resolved !in visited) return SourceResult.Success(resolved)
        }
        return SourceResult.Success(null)
    }

    private fun remoteBookId(pattern: String?, detailUrl: String): SourceResult<String> {
        if (pattern.isNullOrBlank()) return unsafe("book URL ID regex is required for TOC template")
        val match = try { Pattern.compile(pattern).matcher(detailUrl) }
        catch (_: PatternSyntaxException) { return unsafe("unsupported book URL ID regex") }
        if (!match.find() || match.groupCount() < 1) return SourceResult.Failure(SourceFailure.Parse("book.url", pattern))
        val id = match.group(1)?.takeIf { it.isNotEmpty() }
            ?: return SourceResult.Failure(SourceFailure.Parse("book.url", pattern))
        return SourceResult.Success(URLEncoder.encode(id, Charsets.UTF_8.name()).replace("+", "%20"))
    }

    private fun expandTemplate(template: String?, encodedId: String?): String? = template?.takeIf { it.isNotBlank() }?.let {
        if ("%s" in it) encodedId?.let { id -> it.replace("%s", id) } else it
    }

    private fun orderedChapters(chapters: List<Chapter>, descending: Boolean): List<Chapter> =
        (if (descending) chapters.asReversed() else chapters).mapIndexed { index, chapter -> chapter.copy(chapterIndex = index) }

    private fun decode(page: SourceHttpPage): SourceResult<String> {
        val bytes = page.body
        val bom = when {
            bytes.size >= 3 && bytes[0] == 0xef.toByte() && bytes[1] == 0xbb.toByte() && bytes[2] == 0xbf.toByte() -> "UTF-8" to 3
            bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> "UTF-16LE" to 2
            bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> "UTF-16BE" to 2
            else -> null
        }
        val header = CHARSET_PATTERN.find(page.contentType.orEmpty().take(512))?.groupValues?.get(1)
        val head = bytes.copyOfRange(0, minOf(bytes.size, 4096)).toString(Charsets.ISO_8859_1)
        val meta = CHARSET_PATTERN.find(head)?.groupValues?.get(1)
        val name = bom?.first ?: header ?: meta ?: "UTF-8"
        val decoded = decodeAs(bytes, bom?.second ?: 0, name)
        if (decoded != null) return SourceResult.Success(decoded)
        if (bom == null && header == null && meta == null) {
            decodeAs(bytes, 0, "GB18030")?.let { return SourceResult.Success(it) }
        }
        return SourceResult.Failure(SourceFailure.Decode(name))
    }

    private fun decodeAs(bytes: ByteArray, offset: Int, charset: String): String? = try {
        Charset.forName(charset).newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, offset, bytes.size - offset)).toString()
    } catch (_: CharacterCodingException) { null }
      catch (_: IllegalArgumentException) { null }

    private fun safeId(id: String): Boolean =
        id.length in 1..128 && id != "." && ".." !in id && LOCAL_ID.matches(id)

    private fun isVerificationPage(html: String): Boolean {
        val document = Jsoup.parse(html)
        val title = document.title().trim().lowercase(Locale.ROOT)
        return title.contains("just a moment") || title.contains("人机验证") || title.contains("安全验证") ||
            document.select("#cf-challenge, #captcha, .g-recaptcha, form[action*=captcha]").isNotEmpty()
    }

    private fun unsafe(reason: String): SourceResult.Failure = SourceResult.Failure(SourceFailure.UnsafeRule(reason))

    private companion object {
        const val MAX_SEARCH_PAGES = 10
        const val MAX_TOC_PAGES = 100
        const val MAX_CHAPTER_PAGES = 20
        const val MAX_CHAPTER_CONTENT_BYTES = 8L * 1024 * 1024
        val LOCAL_ID = Regex("[A-Za-z0-9._-]+")
        val CHARSET_PATTERN = Regex("charset\\s*=\\s*['\\\"]?([A-Za-z0-9._-]+)", RegexOption.IGNORE_CASE)
    }
}
