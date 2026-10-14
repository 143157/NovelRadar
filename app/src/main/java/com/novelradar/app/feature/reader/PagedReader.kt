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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
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
    onPositionChanged: (ChapterReadingPosition) -> Unit,
    onToggleControls: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = state.content ?: return
    val currentState by rememberUpdatedState(state)
    val positionChanged by rememberUpdatedState(onPositionChanged)
    val previous by rememberUpdatedState(onPrevious)
    val next by rememberUpdatedState(onNext)
    var navigationIntent by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    Column(modifier.fillMaxSize().padding(horizontal = state.preferences.horizontalMarginDp.dp)) {
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val width = with(density) { maxWidth.roundToPx() }.coerceAtLeast(1)
            val height = with(density) { maxHeight.roundToPx() }.coerceAtLeast(1)
            val pagePadding = with(density) { 76.dp.roundToPx() }
            val pages = remember(state.presentationChapters, width, height, textStyle, measurer) {
                val glyph = measurer.measure("汉", style = textStyle)
                val columns = floor(width.toDouble() / glyph.size.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                state.presentationChapters.flatMap { chapter ->
                    val text = chapter.content
                    val measuredPages = mutableListOf<TextPage>()
                    var start = 0
                    while (start < text.length) {
                        val first = start == 0
                        val titleStyle = TextStyle(fontSize = if (first) 23.sp else 14.sp,
                            lineHeight = if (first) 32.sp else 20.sp,
                            fontWeight = if (first) FontWeight.SemiBold else FontWeight.Normal)
                        val title = measurer.measure(chapter.chapter.title, style = titleStyle,
                            maxLines = if (first) 2 else 1, constraints = Constraints(maxWidth = width))
                        val bodyHeight = (height - pagePadding - title.size.height).coerceAtLeast(1)
                        val rows = floor(bodyHeight.toDouble() / glyph.size.height.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                        val capacity = (columns * rows).coerceAtLeast(1)
                        var hardEnd = minOf(text.length, start + capacity)
                        if (hardEnd < text.length && text[hardEnd].isLowSurrogate() && text[hardEnd - 1].isHighSurrogate()) {
                            hardEnd = if (hardEnd - start > 1) hardEnd - 1 else hardEnd + 1
                        }
                        val candidate = text.substring(start, hardEnd)
                        val layout = measurer.measure(candidate, style = textStyle, constraints = Constraints(maxWidth = width))
                        val lastFittingLine = (0 until layout.lineCount).lastOrNull { layout.getLineBottom(it) <= bodyHeight } ?: 0
                        val fitting = layout.getLineEnd(lastFittingLine).coerceAtLeast(1)
                        val boundary = TextPaginator.paginate(candidate, fitting).first().endOffset
                        measuredPages += TextPage(start, start + boundary)
                        start += boundary
                    }
                    measuredPages.ifEmpty { listOf(TextPage(0, 0)) }.map {
                        ReaderItem(chapter, it, "chapter:${chapter.chapter.chapterId}:text:${it.startOffset}")
                    }
                }
            }
            if (pages.isEmpty()) return@BoxWithConstraints
            val currentPages by rememberUpdatedState(pages)
            val anchorKey = listOf(state.restorationVersion, width, height, textStyle)
            val anchor = remember(anchorKey) { ChapterReadingPosition(loaded.chapter.chapterId, state.offset) }
            val anchorPage = readerAnchor(pages, anchor)
            val anchorItemKey = remember(anchorKey) { pages[anchorPage].key }
            var retainedPageKey by remember(anchorKey) { mutableStateOf(anchorItemKey) }
            var retainedPosition by remember(anchorKey) { mutableStateOf(anchor) }
            val pager = rememberPagerState(initialPage = anchorPage) { pages.size }
            var restored by remember(anchorKey) { mutableStateOf(false) }
            LaunchedEffect(anchorKey) { navigationIntent = false; pager.scrollToPage(anchorPage); restored = true }
            LaunchedEffect(anchorKey, restored) {
                if (!restored) return@LaunchedEffect
                snapshotFlow {
                    val key = pager.layoutInfo.visiblePagesInfo.firstOrNull { it.index == pager.settledPage }?.key
                    pager.isScrollInProgress to key
                }.distinctUntilChanged().collect { (scrolling, key) ->
                    if (scrolling) return@collect
                    val item = currentPages.firstOrNull { it.key == key }
                    if (item == null) return@collect
                    if (item.chapter.chapter.chapterId != currentState.content?.chapter?.chapterId) {
                        if (navigationIntent && !currentState.positionNeedsReview) {
                            val target = currentState.chapters.indexOfFirst { it.chapterId == item.chapter.chapter.chapterId }
                            if (target > currentState.catalogPosition) next() else previous()
                        }
                        return@collect
                    }
                    // Repagination locates the same character without replacing it by the page's start.
                    val position = readerSettledPosition(item, retainedPageKey, retainedPosition, navigationIntent) ?: return@collect
                    retainedPageKey = item.key
                    retainedPosition = position
                    positionChanged(position)
                    navigationIntent = false
                }
            }
            val edges = remember(pager) { object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && available.x != 0f) navigationIntent = true
                    return Offset.Zero
                }
                override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                    if (source == NestedScrollSource.UserInput && !currentState.positionNeedsReview && !currentState.continuationLoading) {
                        if (available.x < -1f && !pager.canScrollForward && currentState.canNext) next()
                        if (available.x > 1f && !pager.canScrollBackward && currentState.canPrevious) previous()
                    }
                    return Offset.Zero
                }
            } }
            HorizontalPager(pager, Modifier.fillMaxSize().nestedScroll(edges).pointerInput(pager, pages, onToggleControls) {
                detectTapGestures { position ->
                    navigationIntent = true
                    when {
                        position.x < size.width * 0.25f -> {
                            if (pager.currentPage > 0) scope.launch { pager.animateScrollToPage(pager.currentPage - 1) }
                            else if (state.canPrevious && !state.positionNeedsReview) onPrevious()
                        }
                        position.x > size.width * 0.75f -> {
                            if (pager.currentPage < pages.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                            else if (state.canNext && !state.positionNeedsReview) onNext()
                        }
                        else -> { navigationIntent = false; onToggleControls() }
                    }
                }
            }, key = { pages[it].key }) { pageIndex ->
                val item = pages[pageIndex]
                val page = item.page!!
                Column(Modifier.fillMaxSize().padding(bottom = 40.dp)) {
                    Text(item.chapter.chapter.title, Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 18.dp),
                        color = ink.copy(alpha = 0.7f), fontSize = if (page.startOffset == 0) 23.sp else 14.sp,
                        lineHeight = if (page.startOffset == 0) 32.sp else 20.sp,
                        fontWeight = if (page.startOffset == 0) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = if (page.startOffset == 0) 2 else 1)
                    Text(item.chapter.content.substring(page.startOffset, page.endOffset), Modifier.fillMaxWidth(), color = ink, style = textStyle)
                }
            }
            val chapterPages = pages.filter { it.chapter.chapter.chapterId == loaded.chapter.chapterId }
            val currentPageKey = pages.getOrNull(pager.currentPage)?.key
            val localPage = chapterPages.indexOfFirst { it.key == currentPageKey }.coerceAtLeast(0)
            Text("${localPage + 1} / ${chapterPages.size} 页", Modifier.align(Alignment.BottomEnd).padding(bottom = 12.dp),
                color = ink.copy(alpha = 0.6f), fontSize = 12.sp)
        }
    }
}
