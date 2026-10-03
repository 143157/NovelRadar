package com.novelradar.app.feature.downloads

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelradar.app.domain.model.DownloadState
import com.novelradar.app.ui.theme.CardPaper
import com.novelradar.app.ui.theme.InkBlack
import com.novelradar.app.ui.theme.QuietInk
import com.novelradar.app.ui.theme.SignalOrange
import com.novelradar.app.ui.theme.SourceGreen
import java.util.Locale

@Composable
fun DownloadsScreen(state: DownloadsUiState, onAction: (String, DownloadAction) -> Unit,
    onConfirm: () -> Unit, onDismiss: () -> Unit, onDefaultNetwork: (Boolean) -> Unit,
    onRetry: () -> Unit, onSearch: () -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxSize().testTag("download_list"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("下载", color = InkBlack, fontSize = 30.sp, fontWeight = FontWeight.Black)
                Text("按书籍管理本地正文", color = QuietInk)
            }
        }
        item {
            Surface(color = CardPaper, shape = RoundedCornerShape(16.dp), border = BorderStroke(2.dp, InkBlack)) {
                Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("新任务网络默认", color = InkBlack, fontWeight = FontWeight.Bold)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("允许计费网络（含移动数据）", color = InkBlack, modifier = Modifier.weight(1f))
                        Switch(checked = state.defaultAllowMetered, onCheckedChange = onDefaultNetwork,
                            enabled = state.settingsReady, modifier = Modifier.heightIn(min = 48.dp).testTag("download_network_default"))
                    }
                    Text(if (state.defaultAllowMetered) "新任务默认可使用移动数据等计费网络。" else "新任务默认仅使用 Wi-Fi 等不计费网络，以系统判断为准。", color = QuietInk)
                    Text("仅用于新建任务，不改变已有任务的网络限制。", color = QuietInk)
                    state.settingsError?.let {
                        Text(it, color = InkBlack)
                        ActionButton("重读网络设置", onRetry)
                    }
                }
            }
        }
        state.listError?.let { error -> item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(error, color = InkBlack)
                ActionButton("重新读取", onRetry)
            }
        } }
        if (state.loading) item { Text("正在读取下载任务…", color = QuietInk) }
        else if (state.rows.isEmpty() && state.listError == null) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("还没有下载任务", color = InkBlack, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text("到搜书找到小说，打开详情后选择「整本下载」。", color = QuietInk)
                ActionButton("去搜书", onSearch)
            }
        }
        items(state.rows, key = { it.taskId }) { row ->
            DownloadCard(row, enabled = row.taskId !in state.busyTaskIds && state.listError == null && !state.loading,
                onAction = { onAction(row.taskId, it) })
        }
    }
    state.confirmation?.let { DownloadActionDialog(it, onConfirm, onDismiss) }
}

@Composable
private fun DownloadCard(row: DownloadRow, enabled: Boolean, onAction: (DownloadAction) -> Unit) {
    val accent = when (row.state) { DownloadState.COMPLETED -> SourceGreen; DownloadState.RUNNING -> SignalOrange; else -> InkBlack }
    Surface(color = CardPaper, shape = RoundedCornerShape(16.dp), border = BorderStroke(2.dp, InkBlack),
        modifier = Modifier.fillMaxWidth().testTag("download_task_${row.taskId}")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(row.title, color = InkBlack, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(row.sourceName, color = QuietInk)
            Text(row.state.label(), color = if (row.state == DownloadState.RUNNING) InkBlack else accent,
                fontWeight = FontWeight.Bold)
            Text("${row.completed} / ${row.total} 章", color = accent, fontFamily = FontFamily.Monospace,
                fontSize = 26.sp, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(progress = { row.percentage / 100f }, color = accent,
                trackColor = InkBlack.copy(alpha = 0.12f), modifier = Modifier.fillMaxWidth())
            Text("${row.percentage}% · 已保存 ${formatBytes(row.bytes)}", color = QuietInk, fontFamily = FontFamily.Monospace)
            if (row.failed > 0) Text("失败 ${row.failed} 章", color = InkBlack)
            row.chapterTitle?.let { Text("当前章节：$it", color = QuietInk) }
            if (row.state == DownloadState.RUNNING) Text("速度 ${formatBytes(row.bytesPerSecond)}/s", color = QuietInk, fontFamily = FontFamily.Monospace)
            Text(if (row.allowMetered) "本任务：允许计费网络（含移动数据）" else "本任务：仅不计费网络", color = QuietInk)
            if (row.state == DownloadState.QUEUED) Text("等待网络条件及系统调度", color = QuietInk)
            if (row.state == DownloadState.PARTIAL) Text("可重试失败章节，或继续处理剩余章节。", color = QuietInk)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                row.actions.forEach { action ->
                    if (action == DownloadAction.OPEN) ActionButton(action.label, { onAction(action) }, enabled)
                    else OutlinedButton(onClick = { onAction(action) }, enabled = enabled,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = InkBlack, disabledContentColor = QuietInk),
                        border = BorderStroke(1.dp, if (enabled) InkBlack else InkBlack.copy(alpha = 0.3f)),
                        modifier = Modifier.heightIn(min = 48.dp)) { Text(action.label) }
                }
            }
        }
    }
}

@Composable
private fun ActionButton(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled, shape = RoundedCornerShape(8.dp),
        colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = InkBlack),
        modifier = Modifier.heightIn(min = 48.dp)) { Text(label, fontWeight = FontWeight.Bold) }
}

private fun DownloadState.label() = when (this) {
    DownloadState.QUEUED -> "等待下载"
    DownloadState.RUNNING -> "正在下载"
    DownloadState.PAUSED -> "已暂停"
    DownloadState.PARTIAL -> "部分完成"
    DownloadState.COMPLETED -> "下载完成"
    DownloadState.CANCELED -> "已取消，可手动继续"
}

private fun formatBytes(bytes: Long): String {
    val safe = bytes.coerceAtLeast(0)
    return when {
        safe >= 1024 * 1024 -> String.format(Locale.ROOT, "%.1f MB", safe / (1024f * 1024f))
        safe >= 1024 -> String.format(Locale.ROOT, "%.1f KB", safe / 1024f)
        else -> "$safe B"
    }
}
