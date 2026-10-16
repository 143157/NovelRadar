package com.novelradar.app.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.search.AggregatedBookResult
import com.novelradar.app.ui.paper.PaperAccent
import com.novelradar.app.ui.paper.PaperCanvas
import com.novelradar.app.ui.paper.PaperError
import com.novelradar.app.ui.paper.PaperInk
import com.novelradar.app.ui.paper.PaperLine
import com.novelradar.app.ui.paper.PaperMuted
import com.novelradar.app.ui.paper.PaperPageHeader
import com.novelradar.app.ui.paper.PaperPanel
import com.novelradar.app.ui.paper.PaperWarning

@Composable
fun SearchScreen(
    state: SearchUiState,
    onQueryChanged: (String) -> Unit,
    onSubmit: () -> Unit,
    onRetry: () -> Unit = {},
    onResultSelected: (AggregatedBookResult) -> Unit = {},
    sourcesReady: Boolean = true,
    bootstrapFailed: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var failuresExpanded by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxSize().background(PaperCanvas)) {
        PaperPageHeader("搜书", subtitle = "一次查找，汇集已启用书源")
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 17.dp),
            horizontalArrangement = Arrangement.spacedBy(9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.query,
                onValueChange = onQueryChanged,
                modifier = Modifier.weight(1f).testTag("search_input"),
                placeholder = { Text("书名或作者", color = PaperMuted) },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { if (sourcesReady) onSubmit() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PaperAccent,
                    unfocusedBorderColor = PaperMuted,
                    focusedTextColor = PaperInk,
                    unfocusedTextColor = PaperInk,
                ),
            )
            Button(
                onClick = onSubmit,
                enabled = sourcesReady && state.query.isNotBlank(),
                modifier = Modifier.heightIn(min = 56.dp).testTag("search_submit"),
                shape = RoundedCornerShape(4.dp),
                colors = ButtonDefaults.buttonColors(containerColor = PaperAccent, contentColor = PaperPanel),
            ) { Text("搜索", fontWeight = FontWeight.Bold) }
        }
        if (!sourcesReady) {
            Text(
                if (bootstrapFailed) "书源准备失败，请重新打开应用" else "正在准备书源…",
                Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                color = if (bootstrapFailed) PaperError else PaperMuted,
                fontSize = 12.sp,
            )
        }
        if (state.hasSearched) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)
                    .testTag("search_progress"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Surface(shape = RoundedCornerShape(4.dp), border = BorderStroke(1.dp, PaperLine), color = PaperPanel) {
                    Text(
                        "已完成 ${state.completedSources} / ${state.totalSources} 个书源",
                        Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                        color = if (state.searching) PaperInk else PaperAccent,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                if (state.searching) Text("搜索中…", color = PaperMuted, fontSize = 12.sp)
            }
        } else Spacer(Modifier.height(14.dp))
        if (state.failures.isNotEmpty()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Surface(
                    modifier = Modifier.fillMaxWidth().clickable { failuresExpanded = !failuresExpanded },
                    shape = RoundedCornerShape(4.dp),
                    color = PaperPanel,
                    border = BorderStroke(1.dp, PaperLine),
                ) {
                    Row(Modifier.padding(11.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("${state.failures.size} 个书源未完成", color = PaperWarning, fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp)
                        Text(if (failuresExpanded) "收起" else "查看原因", color = PaperAccent, fontSize = 12.sp)
                    }
                }
                if (failuresExpanded) {
                    state.failures.toSortedMap().forEach { (sourceId, failure) ->
                        Text("${state.sourceNames[sourceId] ?: sourceId} · ${failure.label()}", Modifier.padding(start = 10.dp, top = 7.dp),
                            color = PaperError, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    Text("重新搜索", Modifier.clickable(onClick = onRetry).padding(10.dp), color = PaperAccent,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        state.errorMessage?.let { message ->
            Text(message, Modifier.padding(horizontal = 20.dp, vertical = 5.dp), color = PaperError, fontSize = 12.sp)
        }
        if (state.results.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f).padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when {
                            !state.hasSearched -> "输入书名或作者开始搜书"
                            state.searching -> "正在查找，结果会逐条出现"
                            state.totalSources == 0 -> "还没有启用可搜索的书源"
                            else -> "没有找到匹配的书"
                        },
                        color = PaperInk,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (!state.hasSearched) "可以试试完整书名，或输入作者名" else "可检查书名，或到书源中查看书源状态",
                        color = PaperMuted,
                        fontSize = 12.sp,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f).testTag("search_results"),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                items(state.results, key = { it.bookKey }) { result ->
                    SearchResultCard(result, onClick = { onResultSelected(result) })
                }
            }
        }
    }
}

private fun SourceFailure.label(): String = when (this) {
    SourceFailure.Timeout -> "连接超时"
    is SourceFailure.Http -> "网站返回 $code"
    is SourceFailure.Decode -> "网页编码错误"
    is SourceFailure.Parse -> "网页结构已变化"
    SourceFailure.VerificationRequired -> "网站需要验证"
    is SourceFailure.UnsafeRule -> "书源规则不可用"
    is SourceFailure.Unexpected -> "连接或解析失败"
    is SourceFailure.Network -> "网络连接失败"
    is SourceFailure.Storage -> "本地存储不可用"
}
