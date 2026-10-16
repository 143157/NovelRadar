package com.novelradar.app.feature.book

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.data.repository.CoverFetcher
import com.novelradar.app.ui.components.NovelCover
import com.novelradar.app.ui.paper.PaperAccent
import com.novelradar.app.ui.paper.PaperCanvas
import com.novelradar.app.ui.paper.PaperError
import com.novelradar.app.ui.paper.PaperInk
import com.novelradar.app.ui.paper.PaperLine
import com.novelradar.app.ui.paper.PaperMuted
import com.novelradar.app.ui.paper.PaperPanel
import com.novelradar.app.ui.paper.PaperSheet
import com.novelradar.app.ui.paper.PaperWarning

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun BookDetailScreen(
    state: BookDetailUiState,
    onBack: () -> Unit,
    onSourceSelected: (String) -> Unit,
    onAddToShelf: () -> Unit,
    onRefresh: () -> Unit,
    onChapterQueryChanged: (String) -> Unit,
    onToggleOrder: () -> Unit,
    modifier: Modifier = Modifier,
    onChapterSelected: (Chapter) -> Unit = {},
    canRead: Boolean = false,
    canDownload: Boolean = false,
    onStartReading: () -> Unit = {},
    onDownload: () -> Unit = {},
    coverFetcher: CoverFetcher? = null,
) {
    val canUseSource = !state.sourceActionPending && !state.sourceIdentityMismatch && !state.loading && state.chapters.isNotEmpty()
    val currentChapterId = state.currentChapterId
    LazyColumn(modifier.fillMaxSize().testTag("chapter_list"),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 30.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Surface(color = PaperCanvas, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 17.dp)) {
                    TextButton(onClick = onBack, enabled = !state.sourceActionPending) { Text("← 返回") }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                        NovelCover(state.book?.bookName.orEmpty(), state.book?.coverUrl, state.selectedSourceId,
                            coverFetcher, Modifier.width(78.dp).height(112.dp).clip(RoundedCornerShape(2.dp)),
                            paperColor = PaperPanel, letterColor = PaperAccent)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(state.book?.bookName ?: "正在打开书籍…", color = PaperInk,
                                style = MaterialTheme.typography.headlineLarge)
                            Text(state.book?.author?.takeIf(String::isNotBlank) ?: "作者暂未提供",
                                color = PaperInk, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Text("${state.sources.size} 个可选书源", color = PaperInk,
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    state.selectedSourceId?.let { source ->
                        Text("当前书源：${state.sourceNames[source] ?: source}",
                            Modifier.padding(top = 12.dp), color = PaperInk, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)) {
                FlowRow(Modifier.fillMaxWidth().testTag("book_primary_actions"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = onAddToShelf, enabled = !state.sourceActionPending,
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 100.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = PaperAccent, contentColor = PaperPanel),
                        shape = RoundedCornerShape(6.dp)) {
                        Text(if (state.addedToShelf) "已加入书架" else "加入书架")
                    }
                    OutlinedButton(onClick = onStartReading, enabled = canRead && canUseSource,
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 100.dp), shape = RoundedCornerShape(6.dp)) {
                        Text("开始阅读")
                    }
                    OutlinedButton(onClick = onDownload, enabled = canDownload && canUseSource,
                        modifier = Modifier.heightIn(min = 48.dp).widthIn(min = 100.dp), shape = RoundedCornerShape(6.dp)) {
                        Text("整本下载")
                    }
                }
                state.notice?.let { Text(it, color = PaperInk, style = MaterialTheme.typography.bodySmall) }
                state.warning?.let { Text(
                    (if (state.chapters.isEmpty()) "目录加载失败：" else "目录更新失败，当前显示已保存内容：") + it.label(),
                    color = PaperWarning, style = MaterialTheme.typography.bodySmall) }
                state.detailsWarning?.let { Text(
                    "书籍信息更新失败，保留已保存信息：" + it.label(details = true),
                    color = PaperWarning, style = MaterialTheme.typography.bodySmall) }
                if (state.sourceIdentityMismatch) Text(
                    "当前书源的作品信息与已保存书籍不一致，请选择其他书源或重新刷新核验",
                    color = PaperError, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        item {
            PaperSheet(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("简介", color = PaperInk, style = MaterialTheme.typography.titleLarge)
                    Text(state.book?.introduction?.takeIf(String::isNotBlank) ?: "书源暂未提供简介",
                        color = PaperMuted, style = MaterialTheme.typography.bodyMedium)
                    if (state.detailsLoading) Text("正在更新书籍信息…", color = PaperMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("选择书源", color = PaperInk, style = MaterialTheme.typography.titleLarge)
                if (state.sourceActionPending) Text("正在保存选择…", color = PaperMuted, style = MaterialTheme.typography.bodySmall)
                FlowRow(Modifier.fillMaxWidth().testTag("source_picker"),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.sources.forEach { source ->
                        val selected = source.sourceId == state.selectedSourceId
                        Surface(
                            color = if (selected) PaperAccent else PaperPanel,
                            contentColor = if (selected) PaperPanel else PaperInk,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, if (selected) PaperAccent else PaperMuted),
                            modifier = Modifier.heightIn(min = 48.dp).clickable { onSourceSelected(source.sourceId) },
                        ) { Text(state.sourceNames[source.sourceId] ?: source.sourceId,
                            Modifier.padding(horizontal = 13.dp, vertical = 9.dp),
                            style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)) {
                FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("目录 · ${state.chapters.size} 章", color = PaperInk,
                        style = MaterialTheme.typography.titleLarge)
                    TextButton(onClick = onRefresh, enabled = state.selectedSourceId != null && !state.refreshing && !state.sourceActionPending) {
                        Text(if (state.refreshing) "刷新中" else "刷新书籍与目录")
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = state.chapterQuery, onValueChange = onChapterQueryChanged,
                        label = { Text("查找章节") }, singleLine = true, modifier = Modifier.weight(1f),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PaperAccent, unfocusedBorderColor = PaperMuted,
                            focusedLabelColor = PaperAccent, unfocusedLabelColor = PaperMuted))
                    TextButton(onClick = onToggleOrder) { Text(if (state.reversed) "倒序" else "正序") }
                }
                if (state.loading) Text("正在加载目录…", color = PaperMuted, style = MaterialTheme.typography.bodySmall)
                else if (state.chapters.isEmpty()) Text("当前书源还没有可显示的章节", color = PaperMuted, style = MaterialTheme.typography.bodySmall)
                state.positionLabel?.let { Text(it, color = PaperMuted, style = MaterialTheme.typography.bodySmall) }
            }
        }
        items(state.visibleChapters, key = { "${it.sourceId}:${it.chapterId}" }) { chapter ->
            val current = chapter.bookId == state.book?.bookId && chapter.sourceId == state.selectedSourceId &&
                chapter.chapterId == currentChapterId
            Surface(
                color = PaperPanel, shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, if (current) PaperAccent else PaperLine),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).heightIn(min = 48.dp)
                    .clickable(enabled = canRead && canUseSource) { onChapterSelected(chapter) },
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${chapter.chapterIndex + 1}", color = PaperMuted, style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Black)
                    Text(chapter.title, modifier = Modifier.weight(1f), color = PaperInk,
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    if (current) Text("当前阅读", color = PaperAccent, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun SourceFailure.label(details: Boolean = false): String = when (this) {
    SourceFailure.Timeout -> "连接超时"
    is SourceFailure.Http -> "网站返回 $code"
    is SourceFailure.Parse -> when {
        field == "book.identity" -> "作品信息不一致"
        details -> "书籍信息格式不匹配"
        else -> "目录格式不匹配"
    }
    SourceFailure.VerificationRequired -> "网站需要验证"
    is SourceFailure.Decode -> "文字解码失败"
    is SourceFailure.UnsafeRule -> "书源规则不可用"
    is SourceFailure.Unexpected -> "连接失败"
    is SourceFailure.Network -> "网络连接失败"
    is SourceFailure.Storage -> "本地存储不可用"
}
