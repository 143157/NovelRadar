package com.novelradar.app.feature.library

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelradar.app.data.repository.CoverFetcher
import com.novelradar.app.ui.components.NovelCover
import com.novelradar.app.ui.paper.*

@Composable
fun LibraryScreen(
    state: LibraryUiState,
    onOpen: (String) -> Unit,
    onRefresh: () -> Unit,
    onRequestRemoval: (String) -> Unit,
    onConfirmRemoval: (Boolean) -> Unit,
    onCancelRemoval: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    onDetails: (String) -> Unit = {},
    coverFetcher: CoverFetcher? = null,
) {
    LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = modifier.background(PaperCanvas).testTag("library_grid"),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(18.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PaperPageHeader("我的书架", actions = {
                TextButton(onClick = onRefresh, enabled = !state.refreshing && state.books.isNotEmpty(),
                    colors = ButtonDefaults.textButtonColors(contentColor = PaperAccent)) {
                    Text(if (state.refreshing) "刷新中…" else "刷新书架")
                }
            })
        }
        state.notice?.let { notice -> item(span = { GridItemSpan(maxLineSpan) }) { Text(notice, color = PaperMuted) } }
        if (state.refreshWarnings.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Text("${state.refreshWarnings.size} 本目录刷新失败，保留上次目录，可再次刷新", color = PaperWarning, fontSize = 13.sp)
        }
        if (state.loading) item(span = { GridItemSpan(maxLineSpan) }) { Text("正在读取书架…", color = PaperMuted) }
        else if (state.books.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(vertical = 70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("书架还没有书", color = PaperInk, style = MaterialTheme.typography.titleLarge)
                Text("找到喜欢的故事，在详情中加入书架", Modifier.padding(vertical = 12.dp), color = PaperMuted)
                PaperPrimaryButton("去搜书", onClick = onSearch)
            }
        }
        state.continueReading?.let { current -> item(span = { GridItemSpan(maxLineSpan) }) {
            Surface(color = PaperPanel, contentColor = PaperInk, shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, PaperLine),
                modifier = Modifier.fillMaxWidth().testTag("continue_reading").combinedClickable(
                    onClick = { onOpen(current.shelf.book.bookId) }, onLongClick = { onRequestRemoval(current.shelf.book.bookId) })) {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("继续阅读 →", color = PaperAccent, style = MaterialTheme.typography.titleLarge)
                        Text(current.shelf.book.bookName, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium)
                        Text(current.positionLabel, color = PaperMuted, fontSize = 12.sp)
                    }
                    NovelCover(current.shelf.book.bookName, current.shelf.book.coverUrl, current.shelf.preferredSourceId,
                        coverFetcher, Modifier.width(72.dp).height(104.dp).clip(RoundedCornerShape(2.dp)),
                        paperColor = PaperPanel, letterColor = PaperAccent)
                }
            }
        } }
        items(state.books, key = { it.shelf.book.bookId }) { item ->
            val shelf = item.shelf
            Column(Modifier.testTag("shelf_book_${shelf.book.bookId}").combinedClickable(
                onClick = { onOpen(shelf.book.bookId) }, onLongClick = { onRequestRemoval(shelf.book.bookId) }),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Box {
                    NovelCover(shelf.book.bookName, shelf.book.coverUrl, shelf.preferredSourceId, coverFetcher,
                        Modifier.fillMaxWidth().aspectRatio(0.72f).clip(RoundedCornerShape(2.dp)),
                        paperColor = PaperPanel, letterColor = PaperAccent)
                    if (shelf.unreadCount > 0) Surface(color = PaperAccent, contentColor = PaperPanel,
                        modifier = Modifier.align(Alignment.TopEnd), shape = RoundedCornerShape(bottomStart = 8.dp)) {
                        Text("未读 ${shelf.unreadCount} 章", Modifier.padding(horizontal = 8.dp, vertical = 5.dp), fontSize = 12.sp,
                            fontWeight = FontWeight.Bold)
                    }
                }
                Text(shelf.book.bookName, color = PaperInk, style = MaterialTheme.typography.titleMedium, fontSize = 17.sp,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("最新：${shelf.lastChapterTitle ?: "暂无目录"}", color = PaperMuted, fontSize = 12.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.positionLabel,
                    color = PaperMuted, fontSize = 12.sp)
                TextButton(onClick = { onDetails(shelf.book.bookId) }, contentPadding = PaddingValues(0.dp),
                    colors = ButtonDefaults.textButtonColors(contentColor = PaperAccent)) {
                    Text("书籍详情", fontSize = 12.sp)
                }
                if (shelf.book.bookId in state.refreshWarnings) Text("刷新失败，目录已保留", color = PaperWarning, fontSize = 12.sp)
            }
        }
    }
    state.pendingRemoval?.let { pending ->
        var deleteLocal by rememberSaveable(pending.shelf.book.bookId) { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { if (!state.removing) onCancelRemoval() },
            containerColor = PaperPanel, titleContentColor = PaperInk, textContentColor = PaperInk,
            title = { Text("移出「${pending.shelf.book.bookName}」？") },
            text = { Column {
                Text("阅读记录会保留。")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = deleteLocal, onCheckedChange = { deleteLocal = it }, enabled = !state.removing,
                        colors = CheckboxDefaults.colors(checkedColor = PaperAccent, uncheckedColor = PaperMuted,
                            checkmarkColor = PaperPanel))
                    Text("同时删除本地章节（缓存及下载）")
                }
            } },
            confirmButton = { TextButton(onClick = { onConfirmRemoval(deleteLocal) }, enabled = !state.removing,
                colors = ButtonDefaults.textButtonColors(contentColor = PaperError)) {
                Text(if (state.removing) "正在移除…" else "移出书架")
            } },
            dismissButton = { TextButton(onClick = onCancelRemoval, enabled = !state.removing,
                colors = ButtonDefaults.textButtonColors(contentColor = PaperAccent)) { Text("取消") } })
    }
}
