package com.novelradar.app.feature.downloads

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.novelradar.app.ui.paper.PaperAccent
import com.novelradar.app.ui.paper.PaperError
import com.novelradar.app.ui.paper.PaperInk
import com.novelradar.app.ui.paper.PaperMuted
import com.novelradar.app.ui.paper.PaperPanel

@Composable
fun DownloadActionDialog(confirmation: DownloadConfirmation, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val deleting = confirmation.action == DownloadAction.DELETE_FILES
    AlertDialog(onDismissRequest = onDismiss, containerColor = PaperPanel,
        title = { Text(if (deleting) "删除本书本地正文？" else "取消下载任务？", color = PaperInk) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(confirmation.row.title, color = PaperInk)
                if (deleting) Text("将停止本书下载，并删除本书所有书源的本地正文，包括下载和阅读缓存；书架、目录和阅读历史会保留。",
                    color = PaperMuted, modifier = Modifier.testTag("download_confirmation_scope"))
                else Text("停止此任务，已下载和已缓存的正文仍保留。", color = PaperMuted)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (deleting) "删除正文" else "确认取消", color = if (deleting) PaperError else PaperAccent) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回", color = PaperAccent) } },
    )
}

@Composable
fun DownloadStartDialog(request: DownloadStartRequest, onNetwork: (Boolean) -> Unit,
    onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, containerColor = PaperPanel,
        title = { Text("整本下载", color = PaperInk) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${request.title} · ${request.sourceName}", color = PaperInk)
                Text("选择本次任务的网络条件", color = PaperInk)
                NetworkChoice(!request.allowMetered, "仅 Wi-Fi 等不计费网络", "download_unmetered", { onNetwork(false) })
                NetworkChoice(request.allowMetered, "允许计费网络（含移动数据）", "download_metered_opt_in", { onNetwork(true) })
                Text("网络是否计费由系统判断，并非所有 Wi-Fi 都是不计费网络。此选择仅用于本次新建任务。已有任务保留原设置。", color = PaperMuted)
                Text("下载需等待系统调度；系统限制可能中断任务，可在下载页手动继续。", color = PaperMuted)
            }
        },
        confirmButton = { TextButton(onClick = onConfirm, modifier = Modifier.heightIn(min = 48.dp)) { Text("开始下载", color = PaperAccent) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) { Text("返回", color = PaperAccent) } },
    )
}

@Composable
private fun NetworkChoice(selected: Boolean, label: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag(tag)
        .selectable(selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = PaperAccent, unselectedColor = PaperMuted))
        Text(label, color = PaperInk, modifier = Modifier.padding(start = 6.dp))
    }
}
