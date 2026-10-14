package com.novelradar.app.feature.reader

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.novelradar.app.domain.model.ReaderMode
import com.novelradar.app.domain.model.ReaderPreferences
import com.novelradar.app.domain.model.ReaderTheme
import com.novelradar.app.domain.model.SourceFailure

@Composable
fun ReaderScreen(
    state: ReaderUiState,
    onBack: () -> Unit,
    onPositionChanged: (ChapterReadingPosition) -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onRetry: () -> Unit,
    onChapterSelected: (String) -> Unit,
    onPreferencesChanged: ((ReaderPreferences) -> ReaderPreferences) -> Unit,
    modifier: Modifier = Modifier,
    onConfirmPosition: () -> Unit = {},
    onContinueForward: () -> Unit = {},
    onContinueBackward: () -> Unit = {},
) {
    var controlsVisible by remember { mutableStateOf(false) }
    var catalogVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    val colors = readerColors(state.preferences.theme)
    val toggle = { controlsVisible = !controlsVisible }
    val style = TextStyle(fontSize = state.preferences.fontSizeSp.sp,
        lineHeight = (state.preferences.fontSizeSp * state.preferences.lineHeight).sp)
    ReaderWindow(state.preferences, colors.first)

    Box(modifier.fillMaxSize().background(colors.first).testTag("reader_center_toggle")
        .semantics { onClick("显示或隐藏阅读工具栏") { toggle(); true } }) {
        if (state.content != null) Column(Modifier.fillMaxSize()) {
            val readerModifier = Modifier.weight(1f).padding(bottom = if (controlsVisible) 32.dp else 0.dp)
            when (state.preferences.mode) {
                ReaderMode.SCROLL -> ScrollReader(state, style, colors.second, onPositionChanged, toggle,
                    onContinueForward, onContinueBackward, readerModifier.testTag("reader_scroll"))
                ReaderMode.PAGED -> PagedReader(state, style, colors.second, onPositionChanged, toggle,
                    onContinueBackward, onContinueForward, readerModifier.testTag("reader_paged"))
            }
            if (!controlsVisible) Surface(Modifier.fillMaxWidth(), color = colors.first) {
                Text(readerProgressLabel(state), Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    fontSize = 11.sp, color = colors.second.copy(alpha = 0.65f))
            }
        }
        if (state.loading && state.content == null) Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = colors.second)
            Text("正在打开正文…", Modifier.padding(top = 14.dp), color = colors.second)
        }
        if (controlsVisible || state.content == null) {
            Surface(Modifier.align(Alignment.TopCenter).fillMaxWidth(), color = colors.first, contentColor = colors.second, shadowElevation = 4.dp) {
                Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onBack) { Text("← 返回", color = colors.second) }
                    Text(state.book?.bookName ?: "阅读", fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 1,
                        modifier = Modifier.weight(1f))
                    if (state.loading) Text("加载中", color = colors.second.copy(alpha = 0.6f), fontSize = 11.sp)
                }
            }
        }
        if (controlsVisible) ReaderControls(state, colors.first, colors.second, onPrevious, onNext,
            { catalogVisible = true }, { settingsVisible = true }, Modifier.align(Alignment.BottomCenter))
        if (state.continuationLoading || state.continuationError != null) Surface(
            Modifier.align(Alignment.BottomCenter).padding(bottom = if (controlsVisible) 100.dp else 36.dp),
            color = colors.first, contentColor = colors.second) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (state.continuationLoading) "正在打开相邻章节…" else "相邻章节暂时无法打开，当前章节仍可阅读", fontSize = 12.sp)
                if (state.continuationError != null) TextButton(onClick = onRetry) { Text("重试", color = colors.second) }
            }
        }

        if (state.positionNeedsReview || state.error != null || state.notice != null) Surface(
            Modifier.align(if (state.content == null) Alignment.Center else Alignment.TopCenter)
                .padding(top = if (state.content == null) 0.dp else 66.dp, start = 16.dp, end = 16.dp),
            color = colors.first, contentColor = colors.second, shadowElevation = 4.dp,
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.positionNeedsReview) Column(Modifier.testTag("reader_position_review"),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("阅读位置需要确认", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    state.originalReadingLocation?.let { original ->
                        val currentSource = state.content?.chapter?.sourceId ?: state.chapters.firstOrNull()?.sourceId
                        if (currentSource != null && original.sourceId != currentSource) Text("原记录来自另一书源", fontSize = 12.sp)
                        val chapter = state.originalChapterTitle ?: if (original.chapterIndex >= 0) "第${original.chapterIndex + 1}章" else "原章节"
                        Text("原阅读位置：$chapter · 原字符位置 ${original.offset}（待确认）", fontSize = 12.sp)
                    }
                    val verified = state.content?.boundaryVerified == true
                    val preservation = if (state.originalReadingLocation != null) "原阅读位置会继续保留" else "确认前不会自动保存阅读位置"
                    Text(if (state.content == null) "$preservation，请选择章节或重试加载。"
                        else if (verified) "正文已更新。请选择章节，或阅读到合适位置后确认；$preservation。"
                        else "当前显示临时旧正文，章节范围尚未确认。$preservation，请选择章节或重试加载。",
                        fontSize = 12.sp, color = colors.second.copy(alpha = 0.8f))
                    Row {
                        TextButton(onClick = { catalogVisible = true }) { Text("选择章节", color = colors.second) }
                        if (verified) TextButton(onClick = onConfirmPosition, enabled = !state.loading) {
                            Text(if (state.notice == POSITION_CONFIRMATION_FAILURE_NOTICE) "重试确认" else "确认当前位置",
                                color = colors.second.copy(alpha = if (state.loading) 0.35f else 1f))
                        } else if (state.error == null) TextButton(onClick = onRetry, enabled = !state.loading) {
                            Text("重试加载", color = colors.second.copy(alpha = if (state.loading) 0.35f else 1f))
                        }
                    }
                }
                state.notice?.let { Text(it, fontSize = 14.sp) }
                state.error?.let { Text(it.readerMessage(), fontSize = 14.sp) }
                if (state.content != null && state.error != null) Text("当前章节仍可继续阅读", fontSize = 11.sp, color = colors.second.copy(alpha = 0.7f))
                if (state.error != null || (state.notice != null && state.notice != POSITION_CONFIRMATION_FAILURE_NOTICE))
                    TextButton(onClick = if (state.error != null) onRetry else onBack) {
                    Text(if (state.error != null) "重试" else "重试返回", color = colors.second)
                }
            }
        }
    }
    if (catalogVisible) AlertDialog(onDismissRequest = { catalogVisible = false }, title = { Text("目录") },
        confirmButton = { TextButton(onClick = { catalogVisible = false }) { Text("关闭") } },
        text = {
            LazyColumn(state = rememberLazyListState(initialFirstVisibleItemIndex = state.catalogPosition.coerceAtLeast(0))) {
                items(state.chapters, key = { it.chapterId }) { chapter ->
                    Text(chapter.title, Modifier.fillMaxWidth().clickable {
                        catalogVisible = false
                        onChapterSelected(chapter.chapterId)
                    }.padding(vertical = 14.dp), fontWeight = if (chapter.chapterId == state.content?.chapter?.chapterId) FontWeight.Bold else FontWeight.Normal)
                }
            }
        })
    if (settingsVisible) ReaderSettingsDialog(state.preferences, onPreferencesChanged) { settingsVisible = false }
}

internal fun readerColors(theme: ReaderTheme): Pair<Color, Color> = when (theme) {
    ReaderTheme.DAY -> Color(0xFFFFFFFF) to Color(0xFF202124)
    ReaderTheme.PAPER -> Color(0xFFFFF8E8) to Color(0xFF302F2B)
    ReaderTheme.NIGHT -> Color(0xFF171A1D) to Color(0xFFD7DBDF)
}

@Suppress("DEPRECATION")
@Composable
private fun ReaderWindow(preferences: ReaderPreferences, paper: Color) {
    val view = LocalView.current
    val window = LocalContext.current.activity()?.window ?: return
    val controller = remember(window, view) { WindowCompat.getInsetsController(window, view) }
    DisposableEffect(window, controller) {
        val oldBrightness = window.attributes.screenBrightness
        val oldKeepOn = window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        val oldStatus = window.statusBarColor
        val oldNavigation = window.navigationBarColor
        val oldLightStatus = controller.isAppearanceLightStatusBars
        val oldLightNavigation = controller.isAppearanceLightNavigationBars
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window.attributes = window.attributes.apply { screenBrightness = oldBrightness }
            if (!oldKeepOn) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.statusBarColor = oldStatus
            window.navigationBarColor = oldNavigation
            controller.isAppearanceLightStatusBars = oldLightStatus
            controller.isAppearanceLightNavigationBars = oldLightNavigation
        }
    }
    SideEffect {
        window.attributes = window.attributes.apply { screenBrightness = preferences.brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
        window.statusBarColor = paper.toArgb()
        window.navigationBarColor = paper.toArgb()
        controller.isAppearanceLightStatusBars = preferences.theme != ReaderTheme.NIGHT
        controller.isAppearanceLightNavigationBars = preferences.theme != ReaderTheme.NIGHT
    }
}

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

private fun SourceFailure.readerMessage(): String = when (this) {
    SourceFailure.Timeout -> "连接超时，请检查网络后重试"
    is SourceFailure.Http -> "书源返回 $code，请稍后重试"
    is SourceFailure.Parse -> "此章节正文暂时无法解析"
    SourceFailure.VerificationRequired -> "书源需要验证，请返回详情选择其他书源"
    is SourceFailure.Decode -> "章节文字无法识别，请重试"
    is SourceFailure.UnsafeRule -> "书源不可用，请返回详情检查书源"
    is SourceFailure.Unexpected -> "章节暂时无法打开，请重试"
    is SourceFailure.Network -> "网络连接失败，请稍后重试"
    is SourceFailure.Storage -> "本地存储不可用，请检查可用空间"
}
