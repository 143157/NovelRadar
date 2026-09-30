package com.novelradar.app.feature.book

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.ui.theme.CanvasWhite
import com.novelradar.app.ui.theme.CardPaper
import com.novelradar.app.ui.theme.Hairline
import com.novelradar.app.ui.theme.InkBlack
import com.novelradar.app.ui.theme.QuietInk
import com.novelradar.app.ui.theme.SignalOrange

@Composable
fun BookDetailScreen(
    state: BookDetailUiState,
    onBack: () -> Unit,
    onSourceSelected: (String) -> Unit,
    onAddToShelf: () -> Unit,
    onRefresh: () -> Unit,
    onChapterQueryChanged: (String) -> Unit,
    onToggleOrder: () -> Unit,
    onChapterSelected: (Chapter) -> Unit = {},
    canRead: Boolean = false,
    canDownload: Boolean = false,
    onStartReading: () -> Unit = {},
    onDownload: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier.fillMaxSize().testTag("chapter_list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Surface(color = SignalOrange, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 17.dp)) {
                    TextButton(onClick = onBack) { Text("← 返回搜书", color = InkBlack) }
                    Text(state.book?.bookName ?: "正在打开书籍…", color = CanvasWhite,
                        fontSize = 30.sp, fontWeight = FontWeight.Black)
                    state.book?.author?.takeIf(String::isNotBlank)?.let {
                        Text(it, color = InkBlack, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Text("${state.sources.size} 个可选书源", color = InkBlack, fontSize = 12.sp)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(Modifier.fillMaxWidth().testTag("book_primary_actions"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAddToShelf, modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = InkBlack, contentColor = CanvasWhite),
                        shape = RoundedCornerShape(8.dp)) {
                        Text(if (state.addedToShelf) "已加入书架" else "加入书架")
                    }
                    OutlinedButton(onClick = onStartReading, enabled = canRead && state.chapters.isNotEmpty(),
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Text("开始阅读")
                    }
                    OutlinedButton(onClick = onDownload, enabled = canDownload && state.chapters.isNotEmpty(),
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Text("整本下载")
                    }
                }
                if (!canDownload) Text("整本下载功能正在接入", color = QuietInk, fontSize = 11.sp)
                state.notice?.let { Text(it, color = InkBlack, fontSize = 12.sp) }
                state.warning?.let { Text(
                    (if (state.chapters.isEmpty()) "目录加载失败：" else "目录更新失败，当前显示已保存内容：") + it.label(),
                    color = SignalOrange, fontSize = 12.sp) }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择书源", color = InkBlack, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("source_picker"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.sources.forEach { source ->
                        val selected = source.sourceId == state.selectedSourceId
                        Surface(
                            color = if (selected) InkBlack else CardPaper,
                            contentColor = if (selected) CanvasWhite else InkBlack,
                            shape = RoundedCornerShape(7.dp),
                            border = BorderStroke(1.dp, if (selected) InkBlack else Hairline),
                            modifier = Modifier.clickable { onSourceSelected(source.sourceId) },
                        ) { Text(state.sourceNames[source.sourceId] ?: source.sourceId,
                            Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                            fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("目录 · ${state.chapters.size} 章", color = InkBlack,
                        fontSize = 20.sp, fontWeight = FontWeight.Black)
                    TextButton(onClick = onRefresh, enabled = state.selectedSourceId != null && !state.refreshing) {
                        Text(if (state.refreshing) "刷新中" else "刷新目录", color = SignalOrange)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = state.chapterQuery, onValueChange = onChapterQueryChanged,
                        label = { Text("查找章节") }, singleLine = true, modifier = Modifier.weight(1f))
                    TextButton(onClick = onToggleOrder) { Text(if (state.reversed) "倒序" else "正序", color = InkBlack) }
                }
                if (state.loading) Text("正在加载目录…", color = QuietInk, fontSize = 12.sp)
                else if (state.chapters.isEmpty()) Text("当前书源还没有可显示的章节", color = QuietInk, fontSize = 12.sp)
            }
        }
        items(state.visibleChapters, key = { "${it.sourceId}:${it.chapterId}" }) { chapter ->
            Surface(
                color = CardPaper, shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, Hairline),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
                    .clickable(enabled = canRead) { onChapterSelected(chapter) },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${chapter.chapterIndex + 1}", color = SignalOrange, fontSize = 12.sp,
                        fontWeight = FontWeight.Black)
                    Text(chapter.title, color = InkBlack, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

private fun SourceFailure.label(): String = when (this) {
    SourceFailure.Timeout -> "连接超时"
    is SourceFailure.Http -> "网站返回 $code"
    is SourceFailure.Parse -> "目录格式不匹配"
    SourceFailure.VerificationRequired -> "网站需要验证"
    is SourceFailure.Decode -> "文字解码失败"
    is SourceFailure.UnsafeRule -> "书源规则不可用"
    is SourceFailure.Unexpected -> "连接失败"
}
