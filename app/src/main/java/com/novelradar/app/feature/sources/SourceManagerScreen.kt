package com.novelradar.app.feature.sources

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
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
import com.novelradar.app.ui.paper.PaperAccent
import com.novelradar.app.ui.paper.PaperError
import com.novelradar.app.ui.paper.PaperInk
import com.novelradar.app.ui.paper.PaperLine
import com.novelradar.app.ui.paper.PaperMuted
import com.novelradar.app.ui.paper.PaperPageHeader
import com.novelradar.app.ui.paper.PaperPanel
import com.novelradar.app.ui.paper.PaperPrimaryButton
import com.novelradar.app.ui.paper.PaperWarning

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
        PaperPageHeader("书源", subtitle = "只使用你启用的来源")
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PaperPrimaryButton(
                label = "＋ 导入书源",
                onClick = onImport,
                modifier = Modifier.weight(1f).testTag("source_import"),
            )
            OutlinedButton(
                onClick = onTestAll,
                enabled = state.rows.any { it.enabled } && state.testingSourceIds.isEmpty(),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).testTag("source_test_all"),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = PaperInk, disabledContentColor = PaperMuted),
                border = BorderStroke(1.dp, PaperAccent),
            ) { Text("测试全部", fontWeight = FontWeight.SemiBold) }
        }
        state.notice?.let {
            Text(it, Modifier.padding(horizontal = 20.dp, vertical = 4.dp), color = PaperInk, fontSize = 12.sp)
        }
        if (state.importIssues.isNotEmpty()) {
            Surface(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 7.dp),
                shape = RoundedCornerShape(8.dp),
                color = PaperPanel,
                border = BorderStroke(1.dp, PaperWarning),
            ) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("导入未完成", color = PaperWarning, style = MaterialTheme.typography.titleMedium)
                    state.importIssues.take(8).forEach { issue ->
                        Text("${issue.path}：${issue.message}", color = PaperMuted, fontSize = 12.sp)
                    }
                    if (state.importIssues.size > 8) Text("还有 ${state.importIssues.size - 8} 项问题", color = PaperMuted, fontSize = 12.sp)
                }
            }
        }
        if (state.rows.isEmpty()) {
            Column(Modifier.fillMaxWidth().weight(1f).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text("目前没有书源", style = MaterialTheme.typography.titleLarge, color = PaperInk)
                Spacer(Modifier.height(7.dp))
                Text("导入 SoNovel JSON 书源后即可搜书", fontSize = 12.sp, color = PaperMuted)
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
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
            containerColor = PaperPanel,
            title = { Text("HTTP 连接不加密", color = PaperWarning) },
            text = { Text("${pending.name} 使用明文连接，阅读请求可能被沿途网络看见或修改。Android 系统也可能阻止连接。",
                color = PaperInk, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = onConfirmHttp, modifier = Modifier.heightIn(min = 48.dp)) { Text("仍要启用", color = PaperWarning) } },
            dismissButton = { TextButton(onClick = onCancelHttp, modifier = Modifier.heightIn(min = 48.dp)) { Text("保持关闭", color = PaperAccent) } },
        )
    }
    val deleting = deleteCandidate?.let { id -> state.rows.firstOrNull { it.sourceId == id && !it.builtIn } }
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deleteCandidate = null },
            containerColor = PaperPanel,
            title = { Text("删除自定义书源？", color = PaperInk) },
            text = { Text("将从本机删除“${deleting.name}”的规则，已加入书架的书籍不会被删除。",
                color = PaperMuted, modifier = Modifier.verticalScroll(rememberScrollState())) },
            confirmButton = { TextButton(onClick = { onDelete(deleting.sourceId); deleteCandidate = null },
                modifier = Modifier.heightIn(min = 48.dp)) { Text("确认删除", color = PaperError) } },
            dismissButton = { TextButton(onClick = { deleteCandidate = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("取消", color = PaperAccent) } },
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
        color = PaperPanel,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PaperLine),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(row.name, color = PaperInk, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f))
                Text(row.scheme, color = if (row.scheme == "HTTP") PaperWarning else PaperAccent,
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (row.builtIn) "内置" else "导入", color = PaperMuted, fontSize = 12.sp)
                Text(row.host, color = PaperMuted, fontSize = 12.sp, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            Text(row.health.label(), color = when (row.health.status) {
                SourceHealthStatus.HEALTHY -> PaperAccent
                SourceHealthStatus.SLOW, SourceHealthStatus.VERIFICATION_REQUIRED -> PaperWarning
                SourceHealthStatus.FAILED -> PaperError
                else -> PaperMuted
            }, fontSize = 12.sp)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(
                        checked = row.enabled,
                        onCheckedChange = onToggle,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("source_toggle_${row.sourceId}"),
                        colors = SwitchDefaults.colors(checkedThumbColor = PaperPanel, checkedTrackColor = PaperAccent),
                    )
                    Text(if (row.enabled) "已启用" else "已关闭", color = PaperMuted, fontSize = 12.sp,
                        modifier = Modifier.padding(start = 7.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onTest, enabled = row.enabled && !testing,
                        modifier = Modifier.heightIn(min = 48.dp),
                        colors = ButtonDefaults.textButtonColors(contentColor = PaperInk, disabledContentColor = PaperMuted)) {
                        Text(if (testing) "检测中" else "测试")
                    }
                    if (!row.builtIn) TextButton(onClick = onDelete,
                        modifier = Modifier.heightIn(min = 48.dp).testTag("source_delete_${row.sourceId}")) {
                        Text("删除", color = PaperError)
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
