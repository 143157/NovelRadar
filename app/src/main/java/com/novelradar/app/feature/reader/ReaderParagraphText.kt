package com.novelradar.app.feature.reader

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.em

/** Adds display-only indentation while keeping every offset relative to the original slice. */
internal fun readerParagraphText(text: String, start: Int, end: Int): AnnotatedString {
    require(start >= 0 && start <= end && end <= text.length)
    val slice = text.substring(start, end)
    return buildAnnotatedString {
        append(slice)
        var localStart = 0
        while (localStart < slice.length) {
            val newline = slice.indexOf('\n', localStart)
            val localEnd = if (newline < 0) slice.length else newline + 1
            val originalStart = start + localStart
            val firstLine = if (originalStart == 0 || text[originalStart - 1] == '\n') 2.em else 0.em
            addStyle(ParagraphStyle(textIndent = TextIndent(firstLine = firstLine, restLine = 0.em)),
                localStart, localEnd)
            localStart = localEnd
        }
    }
}
