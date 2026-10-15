package com.novelradar.app.feature.reader

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.em

internal data class ReaderParagraphFragment(val startOffset: Int, val endOffset: Int, val text: AnnotatedString) {
    fun originalOffset(localOffset: Int): Int {
        require(localOffset in 0..text.length)
        return startOffset + localOffset
    }
}

internal data class ReaderParagraphSlice(val text: String, val fragments: List<ReaderParagraphFragment>) {
    val length: Int get() = text.length
    fun fragmentForOffset(offset: Int): ReaderParagraphFragment =
        fragments.lastOrNull { it.startOffset <= offset } ?: fragments.first()
}

/**
 * Native TextIndent already applies to every newline-delimited paragraph in one Text.
 * A continued first paragraph needs its own zero-indent Text; its separator is represented
 * once by the boundary between the two Texts, with both fragments retaining original offsets.
 */
internal fun readerParagraphSlice(text: String, start: Int, end: Int): ReaderParagraphSlice {
    require(start >= 0 && start <= end && end <= text.length)
    val slice = text.substring(start, end)
    val continued = start > 0 && text[start - 1] != '\n'
    val newline = if (continued) slice.indexOf('\n') else -1
    val ranges = if (newline < 0) listOf(start to end)
        else listOf(start to start + newline, start + newline + 1 to end)
    return ReaderParagraphSlice(slice, ranges.map { (from, to) ->
        ReaderParagraphFragment(from, to, readerParagraphText(text, from, to))
    })
}

private fun readerParagraphText(text: String, start: Int, end: Int): AnnotatedString = buildAnnotatedString {
    append(text.substring(start, end))
    if (start < end) {
        val firstLine = if (start == 0 || text[start - 1] == '\n') 2.em else 0.em
        addStyle(ParagraphStyle(textIndent = TextIndent(firstLine = firstLine, restLine = 0.em)), 0, length)
    }
}

internal class ReaderParagraphLayout(val slice: ReaderParagraphSlice, val layouts: List<TextLayoutResult>) {
    val height: Int get() = layouts.sumOf { it.size.height }

    fun getLineTopForOffset(offset: Int): Float {
        val index = slice.fragments.indexOf(slice.fragmentForOffset(offset))
        val fragment = slice.fragments[index]
        val layout = layouts[index]
        val local = (offset - fragment.startOffset).coerceIn(0, fragment.text.length)
        return layouts.take(index).sumOf { it.size.height }.toFloat() +
            layout.getLineTop(layout.getLineForOffset(local))
    }

    fun getLineStartForVerticalPosition(vertical: Float): Int {
        var top = 0
        layouts.forEachIndexed { index, layout ->
            if (vertical < top + layout.size.height || index == layouts.lastIndex) {
                return slice.fragments[index].originalOffset(
                    layout.getLineStart(layout.getLineForVerticalPosition(vertical - top)))
            }
            top += layout.size.height
        }
        return slice.fragments.first().startOffset
    }

    fun fittingEndOffset(maxHeight: Int): Int {
        var top = 0
        var end = slice.fragments.first().startOffset
        layouts.forEachIndexed { index, layout ->
            val line = (0 until layout.lineCount).lastOrNull { top + layout.getLineBottom(it) <= maxHeight }
                ?: return end
            end = slice.fragments[index].originalOffset(layout.getLineEnd(line))
            if (line != layout.lineCount - 1) return end
            top += layout.size.height
        }
        return end
    }
}

internal fun ReaderParagraphSlice.measure(measurer: TextMeasurer, style: TextStyle, width: Int): ReaderParagraphLayout =
    ReaderParagraphLayout(this, fragments.map {
        measurer.measure(it.text, style = style, constraints = Constraints(maxWidth = width))
    })

@Composable
internal fun ReaderParagraphBody(
    slice: ReaderParagraphSlice,
    textStyle: TextStyle,
    ink: Color,
    modifier: Modifier = Modifier,
    onTextLayout: ((ReaderParagraphLayout) -> Unit)? = null,
) {
    val measured = remember(slice, textStyle, LocalDensity.current, LocalLayoutDirection.current) {
        mutableStateMapOf<Int, TextLayoutResult>()
    }
    val layoutChanged by rememberUpdatedState(onTextLayout)
    Column(modifier) {
        slice.fragments.forEach { fragment ->
            Text(fragment.text, Modifier.fillMaxWidth(), color = ink, style = textStyle,
                onTextLayout = onLayout@ { result ->
                    val listener = layoutChanged ?: return@onLayout
                    measured[fragment.startOffset] = result
                    val results = slice.fragments.mapNotNull { measured[it.startOffset] }
                    if (results.size == slice.fragments.size &&
                        results.all { it.layoutInput.constraints.maxWidth == result.layoutInput.constraints.maxWidth }) {
                        listener(ReaderParagraphLayout(slice, results))
                    }
                })
        }
    }
}
