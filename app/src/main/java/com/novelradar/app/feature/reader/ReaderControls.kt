package com.novelradar.app.feature.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelradar.app.domain.model.ReaderMode
import com.novelradar.app.domain.model.ReaderPreferences
import com.novelradar.app.domain.model.ReaderTheme
import com.novelradar.app.ui.theme.SignalOrange

@Composable
fun ReaderControls(state: ReaderUiState, paper: Color, ink: Color,
    onPrevious: () -> Unit, onNext: () -> Unit, onCatalog: () -> Unit, onSettings: () -> Unit,
    modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth().testTag("reader_controls"), color = paper, contentColor = ink, shadowElevation = 4.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            HorizontalDivider(color = SignalOrange, thickness = 1.dp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TextButton(onClick = onPrevious, enabled = state.canPrevious) { Text("上一章", color = if (state.canPrevious) ink else ink.copy(alpha = 0.35f)) }
                TextButton(onClick = onCatalog) { Text("目录", color = ink) }
                TextButton(onClick = onSettings) { Text("阅读设置", color = ink) }
                TextButton(onClick = onNext, enabled = state.canNext) { Text("下一章", color = if (state.canNext) ink else ink.copy(alpha = 0.35f)) }
            }
            Text(if (state.content == null) state.sourceName else readerProgressLabel(state),
                Modifier.align(Alignment.CenterHorizontally), fontSize = 11.sp, color = ink.copy(alpha = 0.65f))
        }
    }
}

@Composable
fun ReaderSettingsDialog(preferences: ReaderPreferences, onChange: ((ReaderPreferences) -> ReaderPreferences) -> Unit,
    onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("阅读设置") },
        confirmButton = { TextButton(onClick = onDismiss) { Text("完成") } },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingSlider("字号 · ${preferences.fontSizeSp.toInt()}", preferences.fontSizeSp, 14f..36f) { value -> onChange { it.copy(fontSizeSp = value) } }
                SettingSlider("行距 · ${"%.1f".format(preferences.lineHeight)}", preferences.lineHeight, 1.2f..2.2f) { value -> onChange { it.copy(lineHeight = value) } }
                SettingSlider("页边距 · ${preferences.horizontalMarginDp}", preferences.horizontalMarginDp.toFloat(), 8f..48f) { value -> onChange { it.copy(horizontalMarginDp = value.toInt()) } }
                Text("主题", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ReaderTheme.DAY to "日间", ReaderTheme.PAPER to "纸张", ReaderTheme.NIGHT to "夜间").forEach { (theme, label) ->
                        OutlinedButton(onClick = { onChange { it.copy(theme = theme) } }, modifier = Modifier.weight(1f)) {
                            Text(if (preferences.theme == theme) "✓ $label" else label, fontSize = 12.sp)
                        }
                    }
                }
                Text("阅读方式", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(ReaderMode.SCROLL to "滚动", ReaderMode.PAGED to "分页").forEach { (mode, label) ->
                        OutlinedButton(onClick = { onChange { it.copy(mode = mode) } }, modifier = Modifier.weight(1f)) {
                            Text(if (preferences.mode == mode) "✓ $label" else label)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("亮度跟随系统")
                    Switch(checked = preferences.brightness == null,
                        onCheckedChange = { follow -> onChange { it.copy(brightness = if (follow) null else 0.5f) } })
                }
                preferences.brightness?.let { brightness ->
                    SettingSlider("阅读亮度", brightness, 0.05f..1f) { value -> onChange { it.copy(brightness = value) } }
                }
                Text("预读下一章", fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    (0..2).forEach { count -> OutlinedButton(onClick = { onChange { it.copy(prefetchCount = count) } }, modifier = Modifier.weight(1f)) {
                        val label = if (count == 0) "关闭" else "$count 章"
                        Text(if (preferences.prefetchCount == count) "✓ $label" else label, fontSize = 12.sp)
                    } }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("允许流量预读", modifier = Modifier.widthIn(max = 180.dp))
                    Switch(checked = preferences.allowMeteredPrefetch,
                        onCheckedChange = { allowed -> onChange { it.copy(allowMeteredPrefetch = allowed) } })
                }
                Text("已缓存的章节可离线阅读；每次最多预读两章。", fontSize = 11.sp)
            }
        })
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
