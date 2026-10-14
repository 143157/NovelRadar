package com.novelradar.app.feature.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
fun ScrollReader(state: ReaderUiState, textStyle: TextStyle, ink: Color,
    onPositionChanged: (ChapterReadingPosition) -> Unit, onToggleControls: () -> Unit,
    onForward: () -> Unit, onBackward: () -> Unit, modifier: Modifier = Modifier) {
    val loaded = state.content ?: return
    BoxWithConstraints(modifier.fillMaxSize()) {
        val items = remember(state.presentationChapters) { readerItems(state.presentationChapters, 600) }
        if (items.isEmpty()) return@BoxWithConstraints
        val currentItems by rememberUpdatedState(items)
        val currentState by rememberUpdatedState(state)
        val positionChanged by rememberUpdatedState(onPositionChanged)
        val forward by rememberUpdatedState(onForward)
        val backward by rememberUpdatedState(onBackward)
        val restoreKey = listOf(state.restorationVersion, textStyle, state.preferences.horizontalMarginDp, maxWidth)
        val anchor = remember(restoreKey) { ChapterReadingPosition(loaded.chapter.chapterId, state.offset) }
        val list = rememberLazyListState()
        val layouts = remember(textStyle, state.preferences.horizontalMarginDp, maxWidth) { mutableStateMapOf<String, TextLayoutResult>() }
        var pendingTarget by remember(restoreKey) { mutableStateOf<ReaderScrollTarget?>(null) }
        val requestBoundary: (Boolean) -> Unit = { towardNext ->
            val live = currentState
            if (!live.positionNeedsReview && !live.continuationLoading &&
                (if (towardNext) live.canNext else live.canPrevious)) {
                val chapter = live.chapters.getOrNull(live.catalogPosition + if (towardNext) 1 else -1)
                if (chapter != null) {
                    pendingTarget = ReaderScrollTarget(chapter.chapterId, live.content!!.chapter.chapterId, towardNext)
                    if (towardNext) forward() else backward()
                }
            }
        }
        val requestCurrentBoundary by rememberUpdatedState(requestBoundary)
        LaunchedEffect(pendingTarget, items, state.positionNeedsReview, loaded.chapter.chapterId) {
            val target = pendingTarget ?: return@LaunchedEffect
            val activeId = currentState.content?.chapter?.chapterId
            if (currentState.positionNeedsReview || (activeId != target.originChapterId && activeId != target.chapterId)) {
                pendingTarget = null
                return@LaunchedEffect
            }
            val position = readerReadyScrollPosition(items, target) ?: return@LaunchedEffect
            val index = readerAnchor(items, position)
            val item = items[index]
            list.scrollToItem(index)
            if (item.page != null && position.offset > 0) {
                val layout = snapshotFlow { layouts[item.key] }.filterNotNull().first()
                val local = (position.offset - item.page.startOffset).coerceIn(0, layout.layoutInput.text.length)
                list.scrollToItem(index, layout.getLineTop(layout.getLineForOffset(local)).toInt())
            }
            // The viewport collector reports the real heading/line after this deliberate movement.
            pendingTarget = null
        }
        var restored by remember(restoreKey) { mutableStateOf(false) }
        var restoredViewport by remember { mutableStateOf("" to 0) }
        LaunchedEffect(items) {
            val keys = items.map { it.key }.toSet()
            layouts.keys.toList().filter { it !in keys }.forEach { layouts.remove(it) }
        }
        LaunchedEffect(restoreKey) {
            val index = readerAnchor(items, anchor)
            list.scrollToItem(index)
            val item = items[index]
            if (item.page != null && anchor.offset > 0) {
                val layout = snapshotFlow { layouts[item.key] }.filterNotNull().first()
                val local = (anchor.offset - item.page.startOffset).coerceIn(0, layout.layoutInput.text.length)
                list.scrollToItem(index, layout.getLineTop(layout.getLineForOffset(local)).toInt())
            }
            restoredViewport = (list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String ?: item.key) to list.firstVisibleItemScrollOffset
            restored = true
        }
        LaunchedEffect(restoreKey, restored) {
            if (!restored) return@LaunchedEffect
            snapshotFlow {
                val visibleKey = list.layoutInfo.visibleItemsInfo.firstOrNull()?.key ?: return@snapshotFlow null
                val item = currentItems.firstOrNull { it.key == visibleKey } ?: return@snapshotFlow null
                if ((item.key to list.firstVisibleItemScrollOffset) == restoredViewport) anchor
                else if (item.page == null) item.position()
                else layouts[item.key]?.let { layout ->
                    item.position(layout.getLineStart(layout.getLineForVerticalPosition(list.firstVisibleItemScrollOffset.toFloat())))
                }
            }.filterNotNull().distinctUntilChanged().collect { positionChanged(it) }
        }
        val edges = remember(list) { object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.UserInput && !currentState.positionNeedsReview && !currentState.continuationLoading) {
                    if (available.y < -1f && !list.canScrollForward && currentState.canNext) requestCurrentBoundary(true)
                    if (available.y > 1f && !list.canScrollBackward && currentState.canPrevious) requestCurrentBoundary(false)
                }
                return Offset.Zero
            }
        } }
        LazyColumn(Modifier.fillMaxSize().nestedScroll(edges).pointerInput(onToggleControls) {
            detectTapGestures { if (it.x in size.width * 0.25f..size.width * 0.75f) onToggleControls() }
        }, state = list, contentPadding = PaddingValues(start = state.preferences.horizontalMarginDp.dp,
            end = state.preferences.horizontalMarginDp.dp, top = 24.dp,
            bottom = (maxHeight - 108.dp).coerceAtLeast(24.dp))) {
            items(items, key = { it.key }) { item ->
                if (item.page == null) Text(item.chapter.chapter.title,
                    Modifier.fillMaxWidth().testTag("reader_heading:${item.chapter.chapter.chapterId}").padding(top = 12.dp, bottom = 24.dp), color = ink,
                    fontSize = 23.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
                else Text(item.chapter.content.substring(item.page.startOffset, item.page.endOffset), Modifier.fillMaxWidth(),
                    color = ink, style = textStyle, onTextLayout = { layouts[item.key] = it })
            }
            item(key = "reader:end") {
                TextButton(onClick = { requestBoundary(true) }, enabled = state.canNext && !state.positionNeedsReview && !state.continuationLoading,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp)) {
                    Text(if (state.continuationLoading) "正在打开下一章…" else if (state.canNext) "继续阅读 →" else "已到最后一章", color = ink)
                }
            }
        }
    }
}
