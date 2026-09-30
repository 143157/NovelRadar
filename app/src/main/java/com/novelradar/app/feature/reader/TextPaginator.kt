package com.novelradar.app.feature.reader

data class TextPage(val startOffset: Int, val endOffset: Int)

/** Boundaries refer to the original UTF-16 text; no whitespace or punctuation is discarded. */
object TextPaginator {
    fun paginate(text: String, maxCharsPerPage: Int): List<TextPage> {
        val capacity = maxCharsPerPage.coerceAtLeast(1)
        val pages = mutableListOf<TextPage>()
        var start = 0
        while (start < text.length) {
            var end = minOf(text.length.toLong(), start.toLong() + capacity).toInt()
            if (end < text.length) {
                val preferredFrom = start + (capacity * 0.8).toInt()
                val boundary = (end - 1 downTo preferredFrom).firstOrNull {
                    text[it] == '\n' || text[it] in "。！？；"
                }
                if (boundary != null) end = boundary + 1
                if (end < text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) {
                    end = if (end - start > 1) end - 1 else end + 1
                }
            }
            pages += TextPage(start, end)
            start = end
        }
        return pages
    }

    fun pageIndexForOffset(pages: List<TextPage>, offset: Int): Int =
        pages.indexOfFirst { offset < it.endOffset }.let { if (it < 0) (pages.size - 1).coerceAtLeast(0) else it }
}
