package com.novelradar.app.feature.reader

data class ChapterReadingPosition(val chapterId: String, val offset: Int)

data class ChapterCharacterProgress(val read: Int, val total: Int) {
    val percent: Int get() = if (total == 0) 0 else
        (read.toLong() * 100 / total).coerceIn(0L, 100L).toInt()
}

/** Converts a UTF-16 position for display without changing the persisted offset. */
fun chapterCharacterProgress(text: String, utf16Offset: Int): ChapterCharacterProgress {
    var prefixEnd = utf16Offset.coerceIn(0, text.length)
    if (prefixEnd in 1 until text.length && text[prefixEnd].isLowSurrogate() && text[prefixEnd - 1].isHighSurrogate()) {
        prefixEnd--
    }
    return ChapterCharacterProgress(text.codePointCount(0, prefixEnd), text.codePointCount(0, text.length))
}

/** One count per 256 UTF-16 units; each position lookup scans at most 257 units. */
class ChapterCharacterIndex(private val text: String) {
    private val checkpoints = IntArray(text.length / 256 + 1)
    val total: Int

    init {
        var previousEnd = 0
        var count = 0
        for (block in checkpoints.indices) {
            val end = boundedPrefix(block * 256)
            count += text.codePointCount(previousEnd, end)
            checkpoints[block] = count
            previousEnd = end
        }
        total = count + text.codePointCount(previousEnd, text.length)
    }

    fun progress(utf16Offset: Int): ChapterCharacterProgress {
        val end = boundedPrefix(utf16Offset)
        val block = end / 256
        val start = boundedPrefix(block * 256)
        return ChapterCharacterProgress(checkpoints[block] + text.codePointCount(start, end), total)
    }

    private fun boundedPrefix(offset: Int): Int {
        var end = offset.coerceIn(0, text.length)
        if (end in 1 until text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
        return end
    }
}
