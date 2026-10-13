package com.novelradar.app.source.parse

import java.text.Normalizer
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ChapterRequestContext(val knownChapterUrls: Set<String> = emptySet())

class ChapterPageBoundary(initialUrl: String, context: ChapterRequestContext = ChapterRequestContext()) {
    private val initial = canonicalUrl(initialUrl)
    private val catalogUrls = context.knownChapterUrls.mapNotNull(::canonicalUrl).toSet()

    fun isOtherCatalogChapter(url: String): Boolean {
        val canonical = canonicalUrl(url) ?: return false
        return canonical != initial && canonical in catalogUrls
    }

    fun isExplicitNextChapter(label: String?): Boolean {
        val normalized = normalize(label.orEmpty())
        return NEXT_CHAPTER_LABELS.any(normalized::contains)
    }

    fun isKnownTitle(title: String): Boolean = normalize(title).let {
        it.isNotEmpty() && it !in GENERIC_TITLES && !PAGE_ONLY.matches(it)
    }

    fun sameChapterTitle(first: String, later: String): Boolean =
        isKnownTitle(first) && isKnownTitle(later) && normalize(first) == normalize(later)

    private fun canonicalUrl(url: String): String? =
        url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()

    private fun normalize(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFKC)
            .filterNot(Char::isWhitespace).replace(PAGE_SUFFIX, "").lowercase(Locale.ROOT)

    private companion object {
        val PAGE_SUFFIX = Regex("""\s*[\(\[【]\s*(?:第?\s*\d+\s*/\s*\d+\s*页?|第?\s*[0-9零〇一二三四五六七八九十百千万]+\s*页)\s*[\)\]】]\s*$""")
        val PAGE_ONLY = Regex("(?:第?[0-9零〇一二三四五六七八九十百千万]+页|page[0-9]+)")
        val GENERIC_TITLES = setOf("正文", "章节", "内容", "小说", "chapter", "content")
        val NEXT_CHAPTER_LABELS = listOf("下一章", "下章", "下一回", "nextchapter")
    }
}
