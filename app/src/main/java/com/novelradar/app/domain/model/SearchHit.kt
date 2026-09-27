package com.novelradar.app.domain.model

import java.text.Normalizer

data class SearchHit(
    val sourceId: String,
    val bookName: String,
    val author: String,
    val detailUrl: String,
    val latestChapter: String? = null,
    val updatedAtText: String? = null,
    val coverUrl: String? = null,
    val introduction: String? = null,
) {
    val normalizedBookKey: String
        get() = "${normalizeIdentityPart(bookName)}|${normalizeIdentityPart(author)}"
}

private val completionMarkerSuffixes = listOf("全集", "完本", "全本").flatMap { marker ->
    listOf("($marker)", "[$marker]", "{$marker}", "【$marker】", "〔$marker〕")
}

private val identityPunctuation = Normalizer.normalize(
    "，。！？；：、（）【】〔〕［］｛｝《》〈〉「」『』“”‘’…—·",
    Normalizer.Form.NFKC,
).toSet()

/**
 * Normalizes width and Unicode composition, whitespace, and a terminal bracketed completion marker.
 * Removes the explicit Chinese punctuation above and its NFKC equivalents (including ASCII
 * comma, period, colon, semicolon, question/exclamation marks and brackets); retains +, / and -.
 * Completion markers are checked before punctuation removal so bare or internal words survive.
 */
fun normalizeIdentityPart(value: String): String {
    val compact = Normalizer.normalize(value, Normalizer.Form.NFKC).filterNot { it.isWhitespace() }
    val suffix = completionMarkerSuffixes.firstOrNull { compact.endsWith(it) }
    val withoutMarker = if (suffix == null) compact else compact.removeSuffix(suffix)
    return withoutMarker.filterNot { it in identityPunctuation }
}
