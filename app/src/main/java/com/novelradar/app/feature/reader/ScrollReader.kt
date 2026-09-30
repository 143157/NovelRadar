package com.novelradar.app.feature.reader

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

@Composable
fun ScrollReader(
    state: ReaderUiState,
    textStyle: TextStyle,
    ink: Color,
    onPositionChanged: (Int) -> Unit,
    onToggleControls: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val loaded = state.content ?: return
    val text = loaded.content
    val chunks = remember(text) { TextPaginator.paginate(text, 600) }
    val positionKey = listOf(loaded.chapter.chapterId, state.positionVersion, textStyle, state.preferences.horizontalMarginDp)
    val anchor = remember(positionKey) { state.offset.coerceIn(0, text.length) }
    val anchorChunk = TextPaginator.pageIndexForOffset(chunks, anchor)
    val list = rememberLazyListState()
    // Retrying the same content keeps valid measurements even when only the restore request changes.
    val layouts = remember(text, textStyle, state.preferences.horizontalMarginDp) { mutableStateMapOf<Int, TextLayoutResult>() }
    var restored by remember(positionKey) { mutableStateOf(false) }

    LaunchedEffect(positionKey) {
        if (anchor == 0 || chunks.isEmpty()) {
            list.scrollToItem(0)
        } else {
            list.scrollToItem(anchorChunk + 1)
            val layout = snapshotFlow { layouts[anchorChunk] }.filterNotNull().first()
            val localOffset = (anchor - chunks[anchorChunk].startOffset).coerceIn(0, layout.layoutInput.text.length)
            val line = layout.getLineForOffset(localOffset)
            list.scrollToItem(anchorChunk + 1, layout.getLineTop(line).toInt())
        }
        restored = true
    }
    LaunchedEffect(positionKey, restored) {
        if (!restored) return@LaunchedEffect
        val restoredPosition = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        snapshotFlow {
            if ((list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset) == restoredPosition) return@snapshotFlow anchor
            val index = list.firstVisibleItemIndex - 1
            when {
                index < 0 -> 0
                index >= chunks.size -> text.length
                else -> layouts[index]?.let { layout ->
                    val line = layout.getLineForVerticalPosition(list.firstVisibleItemScrollOffset.toFloat())
                    chunks[index].startOffset + layout.getLineStart(line)
                }
            }
        }.filterNotNull().distinctUntilChanged().collect(onPositionChanged)
    }

    LazyColumn(
        modifier.fillMaxSize().pointerInput(onToggleControls) {
            detectTapGestures { position -> if (position.x in size.width * 0.25f..size.width * 0.75f) onToggleControls() }
        },
        state = list,
        contentPadding = PaddingValues(horizontal = state.preferences.horizontalMarginDp.dp, vertical = 24.dp),
    ) {
        item(key = "title") {
            Text(loaded.chapter.title, Modifier.fillMaxWidth().padding(bottom = 24.dp), color = ink,
                fontSize = 23.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold)
        }
        itemsIndexed(chunks, key = { _, chunk -> chunk.startOffset }) { index, chunk ->
            Text(text.substring(chunk.startOffset, chunk.endOffset), Modifier.fillMaxWidth(),
                color = ink, style = textStyle, onTextLayout = { layouts[index] = it })
        }
        item(key = "end") {
            TextButton(onClick = onNext, enabled = state.canNext, modifier = Modifier.fillMaxWidth().padding(vertical = 30.dp)) {
                Text(if (state.canNext) "下一章 →" else "已到最后一章", color = ink)
            }
        }
    }
}
