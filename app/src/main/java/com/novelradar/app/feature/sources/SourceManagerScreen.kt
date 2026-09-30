package com.novelradar.app.feature.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelradar.app.domain.model.SourceHealthStatus
import com.novelradar.app.ui.theme.CanvasWhite
import com.novelradar.app.ui.theme.CardPaper
import com.novelradar.app.ui.theme.Hairline
import com.novelradar.app.ui.theme.InkBlack
import com.novelradar.app.ui.theme.QuietInk
import com.novelradar.app.ui.theme.SignalOrange
import com.novelradar.app.ui.theme.SourceGreen

@Composable
fun SourceManagerScreen(
    state: SourceManagerUiState,
    onImport: () -> Unit,
    onTestAll: () -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onTestOne: (String) -> Unit,
    onDelete: (String) -> Unit,
    onConfirmHttp: () -> Unit,
    onCancelHttp: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleteCandidate by rememberSaveable { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxSize()) {
        Surface(color = InkBlack, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 17.dp)) {
                Text("书源", color = CanvasWhite, fontSize = 31.sp, fontWeight = FontWeight.Black)
                Text("只使用你启用的来源", color = SignalOrange, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Button(
                onClick = onImport,
                modifier = Modifier.weight(1f).testTag("source_import"),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = SignalOrange, contentColor = InkBlack),
            ) { Text("＋ 导入书源", fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = onTestAll,
                enabled = state.rows.any { it.enabled } && state.testingSourceIds.isEmpty(),
                modifier = Modifier.weight(1f).testTag("source_test_all"),
                shape = RoundedCornerShape(8.dp),
                border = BorderStroke(1.dp, InkBlack),
            ) { Text("测试全部", color = InkBlack, fontWeight = FontWeight.SemiBold) }
        }
        state.notice?.let {
            Text(it, Modifier.padding(horizontal = 18.dp, vertical = 4.dp), color = InkBlack, fontSize = 12.sp)
        }
        if (state.importIssues.isNotEmpty()) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 7.dp),
                shape = RoundedCornerShape(8.dp),
                color = CardPaper,
                border = BorderStroke(1.dp, SignalOrange),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("导入未完成", color = InkBlack, fontWeight = FontWeight.Bold)
                    state.importIssues.take(8).forEach { issue ->
                        Text("${issue.path}：${issue.message}", color = QuietInk, fontSize = 12.sp)
                    }
                    if (state.importIssues.size > 8) Text("还有 ${state.importIssues.size - 8} 项问题", color = QuietInk, fontSize = 12.sp)
                }
            }
        }
        if (state.rows.isEmpty()) {
            Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("目前没有书源", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = InkBlack)
                Spacer(Modifier.height(7.dp))
                Text("导入 SoNovel JSON 书源后即可搜书", fontSize = 12.sp, color = QuietInk)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.rows.sortedWith(compareBy<SourceRowState> { !it.builtIn }.thenBy { it.name }),
                    key = { it.sourceId }) { row ->
                    SourceRowCard(row, row.sourceId in state.testingSourceIds,
                        onToggle = { enabled -> onToggle(row.sourceId, enabled) },
                        onTest = { onTestOne(row.sourceId) },
                        onDelete = { deleteCandidate = row.sourceId })
                }
            }
        }
    }

    val pending = state.pendingHttpSourceId?.let { id -> state.rows.firstOrNull { it.sourceId == id } }
    if (pending != null) {
        AlertDialog(
            onDismissRequest = onCancelHttp,
            title = { Text("HTTP 连接不加密") },
            text = { Text("${pending.name} 使用明文连接，阅读请求可能被沿途网络看见或修改。Android 系统也可能阻止连接。") },
            confirmButton = { TextButton(onClick = onConfirmHttp) { Text("仍要启用") } },
            dismissButton = { TextButton(onClick = onCancelHttp) { Text("保持关闭") } },
        )
    }
    val deleting = deleteCandidate?.let { id -> state.rows.firstOrNull { it.sourceId == id && !it.builtIn } }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            title = { Text("删除自定义书源？") },
            text = { Text("将从本机删除“${deleting.name}”的规则，已加入书架的书籍不会被删除。") },
            confirmButton = { TextButton(onClick = { onDelete(deleting.sourceId); deleteCandidate = null }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun SourceRowCard(
    row: SourceRowState,
    testing: Boolean,
    onToggle: (Boolean) -> Unit,
    onTest: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = CardPaper,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Hairline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, color = InkBlack, fontSize = 17.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(row.scheme, color = if (row.scheme == "HTTP") SignalOrange else SourceGreen,
                    fontSize = 11.sp, fontWeight = FontWeight.Black, modifier = Modifier.padding(start = 8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (row.builtIn) "内置" else "导入", color = QuietInk, fontSize = 11.sp)
                Text(row.host, color = QuietInk, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(row.health.label(), color = when (row.health.status) {
                SourceHealthStatus.HEALTHY -> SourceGreen
                SourceHealthStatus.FAILED, SourceHealthStatus.VERIFICATION_REQUIRED -> SignalOrange
                else -> QuietInk
            }, fontSize = 12.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = row.enabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier.testTag("source_toggle_${row.sourceId}"),
                        colors = SwitchDefaults.colors(checkedThumbColor = CanvasWhite, checkedTrackColor = SignalOrange),
                    )
                    Text(if (row.enabled) "已启用" else "已关闭", color = QuietInk, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 7.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onTest, enabled = row.enabled && !testing) {
                        Text(if (testing) "检测中" else "测试", color = InkBlack)
                    }
                    if (!row.builtIn) TextButton(onClick = onDelete,
                        modifier = Modifier.testTag("source_delete_${row.sourceId}")) {
                        Text("删除", color = SignalOrange)
                    }
                }
            }
        }
    }
}

private fun com.novelradar.app.domain.model.SourceHealth.label(): String {
    val base = when (status) {
        SourceHealthStatus.UNKNOWN -> "未检测"
        SourceHealthStatus.HEALTHY -> "可用"
        SourceHealthStatus.SLOW -> "响应较慢"
        SourceHealthStatus.FAILED -> "连接失败"
        SourceHealthStatus.VERIFICATION_REQUIRED -> "需要网站验证"
    }
    return lastLatencyMillis?.let { "$base · $it ms" } ?: base
}
