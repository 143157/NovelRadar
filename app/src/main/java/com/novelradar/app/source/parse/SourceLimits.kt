package com.novelradar.app.source.parse

import com.novelradar.app.domain.model.SearchHit

internal object SourceLimits {
    const val MAX_SEARCH_HITS = 10_000
    const val MAX_TOC_CHAPTERS = 20_000
    const val MAX_URL_CHARS = 4_096
    const val MAX_SEARCH_FIELDS_BYTES = 8L * 1024 * 1024
    const val MAX_TOC_FIELDS_BYTES = 16L * 1024 * 1024

    fun searchFieldBytes(hit: SearchHit): Long =
        utf8Bytes(hit.bookName) + utf8Bytes(hit.author) + utf8Bytes(hit.detailUrl) +
            utf8Bytes(hit.latestChapter) + utf8Bytes(hit.updatedAtText) +
            utf8Bytes(hit.coverUrl) + utf8Bytes(hit.introduction)

    fun utf8Bytes(value: String?): Long {
        if (value == null) return 0
        var bytes = 0L
        var index = 0
        while (index < value.length) {
            val char = value[index]
            bytes += when {
                char.code <= 0x7f -> 1
                char.code <= 0x7ff -> 2
                Character.isHighSurrogate(char) && index + 1 < value.length && Character.isLowSurrogate(value[index + 1]) -> {
                    index++
                    4
                }
                char in '\uD800'..'\uDFFF' -> 1 // Java UTF-8 replaces an unpaired surrogate with '?'.
                else -> 3
            }
            index++
        }
        return bytes
    }
}
