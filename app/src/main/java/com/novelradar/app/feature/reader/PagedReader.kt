package com.novelradar.app.feature.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlin.math.floor

@Composable
fun PagedReader(
    state: ReaderUiState,
    textStyle: TextStyle,
    ink: Color,
    onPositionChanged: (Int) -> Unit,
    onToggleControls: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = state.content ?: return
    val text = loaded.content
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().padding(horizontal = state.preferences.horizontalMarginDp.dp)) {
        Text(loaded.chapter.title, Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp),
            color = ink.copy(alpha = 0.7f), fontSize = 14.sp, maxLines = 1)
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
            val height = with(density) { (maxHeight - 40.dp).roundToPx() }.coerceAtLeast(1)
            val pages = remember(text, width, height, textStyle, measurer) {
                val glyph = measurer.measure("汉", style = textStyle)
                val columns = floor(width.toDouble() / glyph.size.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                val rows = floor(height.toDouble() / glyph.size.height.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                val capacity = (columns * rows).coerceAtLeast(1)
                val measuredPages = mutableListOf<TextPage>()
                var start = 0
                while (start < text.length) {
                    var hardEnd = minOf(text.length, start + capacity)
                    if (hardEnd < text.length && text[hardEnd].isLowSurrogate() && text[hardEnd - 1].isHighSurrogate()) {
                        hardEnd = if (hardEnd - start > 1) hardEnd - 1 else hardEnd + 1
                    }
                    val candidate = text.substring(start, hardEnd)
                    val layout = measurer.measure(candidate, style = textStyle, constraints = Constraints(maxWidth = width))
                    val lastFittingLine = (0 until layout.lineCount).lastOrNull { layout.getLineBottom(it) <= height } ?: 0
                    val fitting = layout.getLineEnd(lastFittingLine).coerceAtLeast(1)
                    val boundary = TextPaginator.paginate(candidate, fitting).first().endOffset
                    measuredPages += TextPage(start, start + boundary)
                    start += boundary
                }
                measuredPages.ifEmpty { listOf(TextPage(0, 0)) }
            }
            val anchorKey = listOf(loaded.chapter.chapterId, state.positionVersion, pages)
            val anchor = remember(anchorKey) { state.offset }
            val anchorPage = TextPaginator.pageIndexForOffset(pages, anchor)
            val pager = rememberPagerState(initialPage = anchorPage) { pages.size }
            var restored by remember(anchorKey) { mutableStateOf(false) }
            LaunchedEffect(anchorKey) { pager.scrollToPage(anchorPage); restored = true }
            LaunchedEffect(anchorKey, restored) {
                if (!restored) return@LaunchedEffect
                snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { page ->
                    // Repagination locates the same character without replacing it by the page's start.
                    onPositionChanged(if (page == anchorPage) anchor else pages[page].startOffset)
                }
            }
            HorizontalPager(pager, Modifier.fillMaxSize().pointerInput(pager, pages, onToggleControls) {
                detectTapGestures { position ->
                    when {
                        position.x < size.width * 0.25f -> {
                            if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                            else if (state.canPrevious) onPrevious()
                        }
                        position.x > size.width * 0.75f -> {
                            if (pager.currentPage < pages.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                            else if (state.canNext) onNext()
                        }
                        else -> onToggleControls()
                    }
                }
            }) { pageIndex ->
                val page = pages[pageIndex]
                Text(text.substring(page.startOffset, page.endOffset), Modifier.fillMaxSize().padding(bottom = 40.dp),
                    color = ink, style = textStyle)
            }
            Text("${pager.currentPage + 1} / ${pages.size}", Modifier.align(Alignment.BottomEnd).padding(bottom = 12.dp),
                color = ink.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}
