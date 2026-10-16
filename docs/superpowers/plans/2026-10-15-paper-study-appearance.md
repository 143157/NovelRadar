# Paper Study Appearance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** 按用户确认的 B「纸页书房」统一五个非阅读页面及其导航/弹窗，保留全部真实行为、阅读器和旧测试包。

**Architecture:** 新建独立 PaperStudyTheme 与少量纯呈现组件，在现有 Scaffold 的非阅读分支启用，旧 NovelRadarTheme 和 Reader 子树不变。先稳定主题/导航契约，再逐页迁移；所有 Screen 仍接收原 UiState 和原回调，不增加数据层或网络调用。

**Tech Stack:** 现有 Kotlin、Compose Material3、Android VectorDrawable、JUnit4/Truth、Compose Android tests、Gradle offline；不增加字体、图标或其他依赖。

---

## 执行上下文与门禁

工程：D:\NovelReader\.worktrees\android-novel-reader，现有 feature/android-novel-reader 分支。已确认设计：docs/superpowers/specs/2026-10-15-paper-study-appearance-design.md，用户于 2026-10-15 回复「确认」。应用基线 b15b995，设计提交 98fe297；开始执行时再次读取状态，不覆盖用户新改动。

这是一个共享主题的单一 UI 子项目，不新建前后端目录、不开发 API。执行前按 using-git-worktrees 检查现有隔离工作树并优先复用；不创建无用的新工作树。未获请求不安装/卸载应用、不合并、不推送、不删除工作树。

适配 dev-team：主 agent 负责调度与集成；实现者负责前端；业务契约审查只读检查没有业务层变更；测试/质量审查复核真实报告。后端不需要实现任务。若选择子 agent，按任务顺序交接，同一时刻一个 app/test 写入者和一个 Gradle 所有者；其他角色只读，不同时构建。PM 负责文档和最终产物。角色之间通过 PM 交接，不互相改共享文件。

每项实现都先写可编译的失败断言；新接口允许暂用旧视觉值作为 scaffold。保存明确断言 RED 与 GREEN 原始 XML，不能把编译失败/无测试/异常当成功的 RED。无设备时原生测试只能编译，明确记录未执行，不启动新模拟器或下载新运行环境。

历史证据：b15b995 曾通过 593 项 JVM 测试、52 套件，lint 七个已知 warning/零 error。此为历史基线，不代表本次改版已通过验证。

## 文件责任

| 单元 | 创建/修改的实际路径（相对工程根） | 职责 |
| --- | --- | --- |
| 主题 | 创建 app/src/main/java/com/novelradar/app/ui/paper/PaperStudyColors.kt、PaperStudyTheme.kt | 语义颜色、字体与形状；不改旧主题 |
| 公共 UI | 创建 app/src/main/java/com/novelradar/app/ui/paper/PaperComponents.kt、PaperNavigation.kt | 页头/按钮/纸面与四项导航 |
| 边界 | 创建 app/src/main/java/com/novelradar/app/app/AppAppearance.kt；修改 app/src/main/java/com/novelradar/app/app/NovelRadarApp.kt | 非阅读主题和内容宽度隔离；既有路由不变 |
| 图标 | 创建 app/src/main/res/drawable/paper_nav_books.xml、paper_nav_search.xml、paper_nav_download.xml、paper_nav_sources.xml | 四个本地描边矢量 |
| 书架 | 修改 app/src/main/java/com/novelradar/app/feature/library/LibraryScreen.kt | 两列大封面、纸页式继续阅读 |
| 搜书 | 修改 app/src/main/java/com/novelradar/app/feature/search/SearchScreen.kt、SearchResultCard.kt | 纸色页头、表单和真实结果层级 |
| 详情 | 修改 app/src/main/java/com/novelradar/app/feature/book/BookDetailScreen.kt | 书籍元信息、源选择、目录 |
| 下载 | 修改 app/src/main/java/com/novelradar/app/feature/downloads/DownloadsScreen.kt、DownloadDialogs.kt | 任务纸面与原确认流程 |
| 书源 | 修改 app/src/main/java/com/novelradar/app/feature/sources/SourceManagerScreen.kt | 原来源状态、开关与安全提示 |
| 封面 | 修改 app/src/main/java/com/novelradar/app/ui/components/NovelCover.kt | 仅增加可选呈现颜色，不动 produceState/fetcher/Coil 数据行为 |
| JVM 测试 | 创建 app/src/test/java/com/novelradar/app/ui/paper/PaperStudyColorsTest.kt、app/src/test/java/com/novelradar/app/app/AppAppearancePolicyTest.kt | 精确 tokens/对比度/边界 |
| 原生测试 | 创建 app/src/androidTest/java/com/novelradar/app/ui/paper/PaperThemeBoundaryTest.kt、PaperTestViewport.kt；修改五页既有 ScreenTest.kt 与 app/src/androidTest/java/com/novelradar/app/AppLaunchTest.kt | 阅读隔离、导航、回调、安全和窄屏 |
| 交付 | 修改 docs/test-apk-notes.md 和本计划 | 真实报告、最终提交、独立 APK |

不得修改 app/src/main/java/com/novelradar/app/feature/reader/、ViewModel/UiState、source/、data/、domain/、app/schemas/、旧 Color.kt/Theme.kt、构建版本和图标资源。确需越界先向 PM 说明，不偷偷扩大范围。

## 统一命令

以下在工程根用 PowerShell 执行；Windows 命令逐个调用，不并发跑 Gradle。

Focused JVM:

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.novelradar.app.ui.paper.PaperStudyColorsTest' --tests 'com.novelradar.app.app.AppAppearancePolicyTest' --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
~~~

Full4:

~~~powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestSources --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
~~~

每次保存完整 exit code，读取 app/build/test-results/testDebugUnitTest/TEST-*.xml 和 app/build/reports/lint-results-debug.sarif；SARIF 未写 result.level 时读取对应 rule.defaultConfiguration.level。不抑制 warning 来造通过。

## Task 1：建立颜色契约与非阅读判定（先 RED/GREEN）

Files: 创建 PaperStudyColors.kt、AppAppearance.kt、PaperStudyColorsTest.kt、AppAppearancePolicyTest.kt。

- [x] 增加编译 scaffold：PaperCanvas/Panel/Ink/Muted/Accent/Line 暂引用旧 CanvasWhite/CardPaper/InkBlack/QuietInk/SignalOrange/Hairline；PaperWarning 和 PaperError 暂用旧 SignalOrange。先不把 scaffold 接入 UI。
- [x] 写精确颜色与对比度回归：

~~~kotlin
package com.novelradar.app.ui.paper

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperStudyColorsTest {
    @Test fun approvedValuesAreStable() {
        assertEquals(Color(0xFFF5F0E7), PaperCanvas)
        assertEquals(Color(0xFFFBF8F1), PaperPanel)
        assertEquals(Color(0xFF373A36), PaperInk)
        assertEquals(Color(0xFF696F65), PaperMuted)
        assertEquals(Color(0xFF566457), PaperAccent)
        assertEquals(Color(0xFFDCDCCF), PaperLine)
        assertEquals(Color(0xFF8A4F2B), PaperWarning)
        assertEquals(Color(0xFFA33B32), PaperError)
    }

    @Test fun enabledTextAndPrimaryButtonMeetContrastThreshold() {
        val pairs = listOf(
            PaperInk to PaperCanvas, PaperMuted to PaperCanvas,
            PaperAccent to PaperCanvas, PaperPanel to PaperAccent,
            PaperWarning to PaperCanvas, PaperError to PaperCanvas
        )
        pairs.forEach { (foreground, background) ->
            val a = foreground.luminance().toDouble()
            val b = background.luminance().toDouble()
            val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
            assertTrue("Text contrast: " + ratio, ratio >= 4.5)
        }
    }
}
~~~

- [x] 建立纯判定函数 scaffold，初始恒 true，并加入完整四组合测试后运行 Focused，预期是颜色/判定断言失败，保存 RED XML：

~~~kotlin
package com.novelradar.app.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAppearancePolicyTest {
    @Test fun onlyTheActualReaderKeepsLegacyTheme() {
        assertTrue(usesPaperStudyAppearance(null, null))
        assertTrue(usesPaperStudyAppearance("book", null))
        assertTrue(usesPaperStudyAppearance(null, "source"))
        assertFalse(usesPaperStudyAppearance("book", "source"))
    }
}
~~~

- [x] 将 scaffold 换为最终完整定义，运行 Focused 预期零失败并保存 GREEN：

~~~kotlin
// PaperStudyColors.kt
package com.novelradar.app.ui.paper

import androidx.compose.ui.graphics.Color

val PaperCanvas = Color(0xFFF5F0E7)
val PaperPanel = Color(0xFFFBF8F1)
val PaperInk = Color(0xFF373A36)
val PaperMuted = Color(0xFF696F65)
val PaperAccent = Color(0xFF566457)
val PaperLine = Color(0xFFDCDCCF)
val PaperWarning = Color(0xFF8A4F2B)
val PaperError = Color(0xFFA33B32)
~~~

~~~kotlin
// AppAppearance.kt: pure policy; composable boundary added in Task 2.
package com.novelradar.app.app

internal fun usesPaperStudyAppearance(bookId: String?, sourceId: String?): Boolean =
    bookId == null || sourceId == null
~~~

- [x] 只提交本项四文件，commit message: feat: define paper study palette and appearance policy。

## Task 2：主题、共享组件和导航隔离

Depends on Task 1. Files: PaperStudyTheme.kt、PaperComponents.kt、PaperNavigation.kt、四个 vector、AppAppearance.kt、NovelRadarApp.kt；测试 PaperThemeBoundaryTest.kt、PaperTestViewport.kt、AppLaunchTest.kt。

- [x] 先创建透明主题 scaffold 并写原生颜色捕获断言。外层始终用旧 NovelRadarTheme；非阅读应捕获 PaperAccent，切入阅读捕获旧 SignalOrange。无设备只编译记录，不声称运行 RED。
- [x] 实现独立主题，旧 Theme.kt/Color.kt 不变：

~~~kotlin
package com.novelradar.app.ui.paper

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val paperTypography = Typography().let { base ->
    base.copy(
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif,
            fontSize = 28.sp, lineHeight = 36.sp, fontWeight = FontWeight.Normal),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif,
            fontSize = 20.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium),
        titleMedium = base.titleMedium.copy(fontFamily = FontFamily.Serif,
            fontSize = 17.sp, lineHeight = 24.sp),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.SansSerif, fontSize = 12.sp)
    )
}

@Composable
fun PaperStudyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PaperAccent, onPrimary = PaperPanel,
            primaryContainer = PaperAccent.copy(alpha = 0.10f), onPrimaryContainer = PaperInk,
            secondary = PaperAccent, onSecondary = PaperPanel,
            secondaryContainer = PaperCanvas, onSecondaryContainer = PaperInk,
            tertiary = PaperAccent, onTertiary = PaperPanel,
            tertiaryContainer = PaperCanvas, onTertiaryContainer = PaperInk,
            background = PaperCanvas, onBackground = PaperInk,
            surface = PaperPanel, onSurface = PaperInk,
            surfaceVariant = PaperCanvas, onSurfaceVariant = PaperMuted,
            surfaceTint = PaperPanel, surfaceDim = PaperCanvas, surfaceBright = PaperPanel,
            surfaceContainerLowest = PaperPanel, surfaceContainerLow = PaperPanel,
            surfaceContainer = PaperPanel, surfaceContainerHigh = PaperPanel,
            surfaceContainerHighest = PaperPanel,
            inverseSurface = PaperInk, inverseOnSurface = PaperPanel, inversePrimary = PaperPanel,
            outline = PaperMuted, outlineVariant = PaperLine,
            error = PaperError, onError = PaperPanel,
            errorContainer = PaperPanel, onErrorContainer = PaperError
        ),
        typography = paperTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(2.dp),
            small = RoundedCornerShape(4.dp), medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(8.dp)),
        content = content
    )
}
~~~

- [x] 在 AppAppearance.kt 增加以下边界；只包现有 Scaffold 呈现，ViewModel、状态与 side effects 保持原位置。Scaffold 内容区域以 PaperContentFrame 包原 when，不复制路由：

~~~kotlin
@androidx.compose.runtime.Composable
internal fun PaperAppAppearance(usePaper: Boolean, content: @androidx.compose.runtime.Composable () -> Unit) {
    if (usePaper) com.novelradar.app.ui.paper.PaperStudyTheme(content) else content()
}

@androidx.compose.runtime.Composable
internal fun PaperContentFrame(
    usePaper: Boolean,
    modifier: androidx.compose.ui.Modifier,
    content: @androidx.compose.runtime.Composable (androidx.compose.ui.Modifier) -> Unit
) {
    if (!usePaper) {
        content(modifier)
    } else {
        androidx.compose.foundation.layout.Box(
            modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.TopCenter
        ) {
            content(androidx.compose.ui.Modifier.widthIn(max = 600.dp).fillMaxSize())
        }
    }
}
~~~

AppAppearance.kt 同时导入 fillMaxSize、widthIn、dp。NovelRadarApp 中使用 usesPaperStudyAppearance(selectedBookId, readerSourceId)；只有渲染 when 的 Modifier 经 frame 传递。所有 onRead/onBack/selectedIndex/readerSession 的赋值与条件原样保留。

- [x] PaperComponents.kt 的公共契约和实现：

~~~kotlin
package com.novelradar.app.ui.paper

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp

@Composable
fun PaperPageHeader(title: String, modifier: Modifier = Modifier,
    subtitle: String? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Column(modifier.fillMaxWidth().testTag("paper_header")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, color = PaperInk, style = MaterialTheme.typography.headlineLarge)
                subtitle?.let { Text(it, color = PaperMuted, style = MaterialTheme.typography.bodySmall) }
            }
            actions()
        }
        HorizontalDivider(color = PaperLine)
    }
}

@Composable
fun PaperPrimaryButton(label: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    Button(onClick = onClick, enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp), shape = RoundedCornerShape(4.dp),
        colors = ButtonDefaults.buttonColors(containerColor = PaperAccent, contentColor = PaperPanel)) {
        Text(label)
    }
}

@Composable
fun PaperSheet(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(modifier = modifier, color = PaperPanel, shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, PaperLine), content = content)
}
~~~

- [x] 四个 vector 都使用以下完整公共外壳，分别把 pathData 设置为表中的实际值，每个文件恰好一个 path。不得替换 launcher 图标：

~~~xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp" android:height="24dp"
    android:viewportWidth="24" android:viewportHeight="24">
    <path android:fillColor="@android:color/transparent"
        android:strokeColor="#FF000000" android:strokeWidth="1.7"
        android:strokeLineCap="round" android:strokeLineJoin="round"
        android:pathData="M4,4H9V20H4Z M11,4H16V20H11Z M18,5L20,4L23,18L21,19Z"/>
</vector>
~~~

| 文件 | pathData |
| --- | --- |
| paper_nav_books.xml | M4,4H9V20H4Z M11,4H16V20H11Z M18,5L20,4L23,18L21,19Z |
| paper_nav_search.xml | M16,10A6,6 0,1 1,4,10A6,6 0,1 1,16,10 M15,15L21,21 |
| paper_nav_download.xml | M12,3V15 M8,11L12,15L16,11 M4,16V21H20V16 |
| paper_nav_sources.xml | M4,5C4,1 20,1 20,5C20,9 4,9 4,5Z M4,5V12C4,16 20,16 20,12V5 M4,12V19C4,23 20,23 20,19V12 |

四个文件的描边和路径边缘均留在 viewport 内；每个矢量由调用端统一 tint。

- [x] 实现 PaperNavigation.kt；Root 保留四标签顺序，只替换其 NavigationBar 呈现为此组件，详情/阅读仍不显示导航：

~~~kotlin
package com.novelradar.app.ui.paper

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.novelradar.app.R

@Composable
fun PaperNavigation(selectedIndex: Int, onSelected: (Int) -> Unit) {
    val entries = listOf("书架" to R.drawable.paper_nav_books,
        "搜书" to R.drawable.paper_nav_search, "下载" to R.drawable.paper_nav_download,
        "书源" to R.drawable.paper_nav_sources)
    NavigationBar(containerColor = PaperPanel, tonalElevation = 0.dp) {
        entries.forEachIndexed { index, entry ->
            NavigationBarItem(selected = index == selectedIndex, onClick = { onSelected(index) },
                icon = { Icon(painterResource(entry.second), contentDescription = null) },
                label = { Text(entry.first) },
                colors = NavigationBarItemDefaults.colors(selectedIconColor = PaperAccent,
                    selectedTextColor = PaperAccent, unselectedIconColor = PaperMuted,
                    unselectedTextColor = PaperMuted, indicatorColor = PaperAccent.copy(alpha = 0.10f)))
        }
    }
}
~~~

- [x] 修正 AppLaunchTest 的 labels 为 listOf("书架", "搜书", "下载", "书源")；保留 Selected、可点击与逐项选中/其他项未选中的全部断言，不改成仅检查文本存在。
- [x] PaperThemeBoundaryTest.kt 捕获真实 composition，而非读取源码字符串：

~~~kotlin
package com.novelradar.app.ui.paper

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.v2.createComposeRule
import com.novelradar.app.app.PaperAppAppearance
import com.novelradar.app.ui.theme.NovelRadarTheme
import com.novelradar.app.ui.theme.SignalOrange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PaperThemeBoundaryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun switchingToReaderRestoresLegacyPaletteAndTypography() {
        val paper = mutableStateOf(true)
        var primary = Color.Unspecified
        var size = androidx.compose.ui.unit.TextUnit.Unspecified
        var originalSize = androidx.compose.ui.unit.TextUnit.Unspecified
        compose.setContent {
            NovelRadarTheme {
                originalSize = MaterialTheme.typography.headlineLarge.fontSize
                PaperAppAppearance(paper.value) {
                    primary = MaterialTheme.colorScheme.primary
                    size = MaterialTheme.typography.headlineLarge.fontSize
                    Text("主题边界")
                }
            }
        }
        compose.runOnIdle {
            assertEquals(PaperAccent, primary)
            paper.value = false
        }
        compose.runOnIdle {
            assertEquals(SignalOrange, primary)
            assertEquals(originalSize, size)
        }
    }
}
~~~

- [x] 加入共用窄屏测试容器（PaperTestViewport.kt），后续各页新增布局回归使用此实际 Composable：

~~~kotlin
package com.novelradar.app.ui.paper

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
internal fun PaperTestViewport(width: Dp = 320.dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current.density
    CompositionLocalProvider(LocalDensity provides Density(density, 1.5f)) {
        PaperStudyTheme { Box(Modifier.width(width).height(700.dp)) { content() } }
    }
}
~~~

- [x] 增加 frame 的 800dp 宽输入/600dp 内容上限、320dp 输入内容不超边界两项原生 bounds 断言；阅读分支不得被 600dp 限制。运行 Focused + Full4，读真实结果，提交本任务精确文件，message: feat: isolate paper study shell from reader。

## 五页公共迁移规则（仅下面明确列出的 UI 文件）

用 apply_patch 将旧颜色名和对应 imports 同时替换为下表；LibraryScreen 的旧主题 wildcard 改为 ui.paper wildcard。不能改旧 Color.kt，也不能全仓替换。每页再按自己的 Task 替换页头/布局和风险状态色；不能把所有失败提示都机械换成成功灰绿。

| 旧名字 | 新名字 |
| --- | --- |
| CanvasWhite / CardPaper | PaperPanel |
| InkBlack | PaperInk |
| QuietInk | PaperMuted |
| SignalOrange / SourceGreen | PaperAccent |
| Hairline | PaperLine |

删除重复 import。普通文字不小于 12sp；页面 title 使用 MaterialTheme.typography.headlineLarge，分区使用 titleLarge，书名使用 titleMedium。输入框 unfocusedBorderColor=PaperMuted，focusedBorderColor=PaperAccent。主要 Button 的 containerColor=PaperAccent、contentColor=PaperPanel。不得用 fixed height 裁剪文字：卡片正文自然高度，封面独立固定比例，按钮 heightIn(min=48.dp)，可换行操作使用原 FlowRow。

## Task 3：书架与搜书的完整 B 视觉

Depends on Task 2. Files: LibraryScreen.kt、SearchScreen.kt、SearchResultCard.kt、NovelCover.kt、LibraryScreenTest.kt、SearchScreenTest.kt。

- [ ] 两个既有 ScreenTest 的非阅读主题从 NovelRadarTheme 改为 PaperStudyTheme，保留每条原断言。Library 新增窄屏长书名 case；Search 新增长结果名和忙碌/错误 case。先在代码保持旧布局时编译测试；有设备时运行指定 UI 测试捕获布局失败，没有设备如实标记编译-only。
- [ ] NovelCover 仅添加有旧值默认参数的颜色样式；保留 fetcher 的 coroutine keys、异常处理和 AsyncImage 不变。代码改动必须是：

~~~kotlin
fun NovelCover(title: String, url: String?, sourceId: String?, fetcher: CoverFetcher?,
    modifier: Modifier = Modifier,
    paperColor: androidx.compose.ui.graphics.Color = CardPaper,
    letterColor: androidx.compose.ui.graphics.Color = SignalOrange)
~~~

将其 Surface color 改为 paperColor，原 fallback Text color 改为 letterColor；不改变 produceState、ContentScale、clearAndSetSemantics、真实图片字节。书架/详情调用使用 paperColor=PaperPanel、letterColor=PaperAccent，封面 Modifier clip(RoundedCornerShape(2.dp))。没传样式的原调用保持兼容。

- [ ] Library 应用公共迁移；原 header item 使用 PaperPageHeader("我的书架")，actions 中的刷新按钮保留 !state.refreshing && state.books.isNotEmpty() 条件与原 label。列数仍 GridCells.Fixed(2)，horizontalArrangement 18.dp，横向 padding20.dp。继续阅读保留 continue_reading 标签和全部 combinedClickable 回调，Row 的 Column 文本在左、72dp×104dp 封面在右，表面 PaperPanel/PaperInk；标题与真实 positionLabel 原样。网格条目保留 shelf_book_ 标签、两行书名、省略、未读和最新章节、详情按钮；长按删除和本地正文独立选项不动。
- [ ] Search 页头替换为 PaperPageHeader("搜书", subtitle = "一次查找，汇集已启用书源")。搜索提交按钮由 height(56.dp) 改为 heightIn(min=56.dp)，允许放大字体时自然增高。保留 search_input/search_submit/search_progress、IME Search、enabled 条件和失败详情展开行为。失败框标题和错误信息使用 PaperWarning/PaperError，不增加查询状态。
- [ ] SearchResultCard 去掉 5dp 橙条和 1.5dp 黑边；Surface 使用 PaperPanel、1dp PaperLine、4dp 圆角，Row 改 heightIn(min=120.dp) 并 padding12.dp。左侧保留基于 displayName 的文字封面（不传入网络 fetcher），封面底 PaperCanvas/字 PaperAccent。中间书名最多两行，作者/最新章节各至少12sp；右侧仅展示真实去重 source 数及“个书源”，数字20sp；不加入演示简介。tag、clickable 回调与 bookKey 不动。
- [ ] 在 LibraryScreenTest 中增加可直接执行的两列/长名/详情回调测试：

~~~kotlin
@Test fun paperShelfLongNamesKeepSeparateDetailAndOpenActions() {
    val longName = "这是一部用于测试超长中文书名在两列纸色书架中显示的小说"
    val book = LibraryBook(ShelfBook(Book("b", "b", longName, "作者"), 1, null, false))
    var opened: String? = null
    var detailed: String? = null
    compose.setContent {
        com.novelradar.app.ui.paper.PaperTestViewport {
            LibraryScreen(LibraryUiState(books = listOf(book), loading = false),
                { opened = it }, {}, {}, {}, {}, {}, onDetails = { detailed = it })
        }
    }
    compose.onNodeWithTag("library_grid").performScrollToNode(hasText("书籍详情"))
    compose.onNodeWithText("书籍详情").performClick()
    compose.runOnIdle { assertThat(detailed).isEqualTo("b"); assertThat(opened).isNull() }
    compose.onNodeWithTag("shelf_book_b").performClick()
    compose.runOnIdle { assertThat(opened).isEqualTo("b") }
}
~~~

- [ ] SearchScreenTest 使用既有 private state()/result()；新增窄屏搜索结果测试：PaperTestViewport 中显示 state(results=listOf(result().copy(displayName="长中文书名".repeat(8))))，点击 search_result_星河|作者，断言回调收到同一 AggregatedBookResult，作者、最新章节、源数仍存在。禁止以演示常量替换 state。
- [ ] 运行 Full4、检查回调/禁用语义、对照 B 预览并提交精确六文件，message: feat: apply paper study shelf and search layouts。

## Task 4：书籍详情，保留身份核验与章节位置

Depends on Task 3. Files: BookDetailScreen.kt、BookDetailScreenTest.kt。

- [ ] 先将既有原生测试主题切到 PaperStudyTheme。保留所有身份不一致、待保存禁用、保存进度与长标题测试；将已有放大字号 case 通过 PaperTestViewport 扩展至320dp，三操作须滚动可达且未重叠。
- [ ] 应用公共颜色迁移并替换详情页头：Surface PaperCanvas、元信息 PaperInk；返回按钮保留 enabled=!state.sourceActionPending。封面78dp×112dp、2dp圆角与新可选样式；书名用 titleLarge 或 headlineLarge 自然换行，不设会裁剪长标题的定高。
- [ ] 三操作仍为原加入书架/开始阅读/整本下载顺序及回调。主按钮 PaperAccent/PaperPanel，其余为 outline，heightIn48dp并保留原 FlowRow；canUseSource 表达式、canRead/canDownload 判定不改。
- [ ] 简介区 PaperSheet；选择书源的选中项以 PaperAccent 底、PaperPanel 字/未选中 PaperPanel+PaperMuted 边。目录行当前项边与“当前阅读”文字 PaperAccent，其他装饰边 PaperLine。来源/章节名不固定单行；不得替换当前 positionLabel 或改 chapterIndex 的业务计算。
- [ ] 新增 sourceIdentityMismatch case 在 PaperTestViewport 中继续验证开始阅读/整本下载/章节不可操作，sourceActionPending 同样如此；原 state() 夹具保留两个真实 source IDs。操作测试代码：

~~~kotlin
@Test fun paperThemeKeepsPendingSourceActionsDisabled() {
    var reads = 0
    var downloads = 0
    compose.setContent {
        com.novelradar.app.ui.paper.PaperTestViewport {
            BookDetailScreen(state().copy(sourceActionPending = true),
                {}, {}, {}, {}, {}, {}, canRead = true, canDownload = true,
                onStartReading = { reads++ }, onDownload = { downloads++ })
        }
    }
    compose.onNodeWithText("开始阅读").performScrollTo().assertIsNotEnabled()
    compose.onNodeWithText("整本下载").performScrollTo().assertIsNotEnabled()
    compose.runOnIdle { assertThat(reads).isEqualTo(0); assertThat(downloads).isEqualTo(0) }
}
~~~

- [ ] Full4，检查 UI-only diff 后只提交两文件，message: feat: restyle book details without changing source guards。

## Task 5：下载、书源与相关非阅读确认弹窗

Depends on Task 2（实际单写者顺序在 Task 4 后）。Files: DownloadsScreen.kt、DownloadDialogs.kt、SourceManagerScreen.kt、DownloadsScreenTest.kt、SourceManagerScreenTest.kt。

- [ ] 先在两个既有 ScreenTest 中使用 PaperStudyTheme，保持 HTTP、删除和计费网络所有断言。新增 PaperTestViewport 场景，确认长域名、长书名、忙碌禁用仍可达；无设备不声称 UI 实测。
- [ ] DownloadsScreen 用 PaperPageHeader("下载", subtitle = "按书籍管理本地正文")；网络默认区和任务 Surface 采用 PaperSheet、8dp圆角1dp边。移除两个2dp黑框，正文数字最多20sp，所有辅助文字>=12sp。状态映射：

~~~kotlin
val accent = when (row.state) {
    DownloadState.COMPLETED, DownloadState.RUNNING -> PaperAccent
    DownloadState.PARTIAL -> PaperWarning
    else -> PaperInk
}
~~~

继续展示原 row.state.label()/completed/total/percentage/bytes/chapterTitle/速度/网络策略；failed>0 的文字 PaperError。原 action 集合、FlowRow、busyTaskIds、loading/listError/settingsReady 启用条件不动。

- [ ] DownloadDialogs 只迁移纸面/文字颜色，删除确认按钮 PaperError、取消/返回/开始按钮 PaperAccent；完整保留 confirmation_scope、网络选项 tags、Role.RadioButton、48dp、可滚动说明与独立回调。不能预先勾选计费网络或缩短删除范围文案。
- [ ] Source 页头用 PaperPageHeader("书源", subtitle = "只使用你启用的来源")，导入灰绿主按钮、测试全部清晰次按钮。Column 中的 LazyColumn 改用 Modifier.fillMaxWidth().weight(1f)，保留页头与操作占用后的剩余高度，不以 fillMaxSize 挤出底部。SourceRowCard 纸面与细边，开关 PaperAccent/PaperPanel；名称至少17sp，域名和来源类型至少12sp，域名 Text 添加 weight(1f)、maxLines=2 与省略。HTTP、SLOW、VERIFICATION_REQUIRED 用 PaperWarning，FAILED/删除用 PaperError，HEALTHY 用 PaperAccent，UNKNOWN 用 PaperMuted；状态文字和延迟值保持原样。内置/导入、builtIn 排序与删除限制不改。
- [ ] 下载测试新增 busy/错误 case（使用现有 row()），真实验证暂停被禁用且没有新回调：

~~~kotlin
@Test fun paperDownloadBusyRowStillBlocksActions() {
    var actions = 0
    compose.setContent {
        com.novelradar.app.ui.paper.PaperTestViewport {
            DownloadsScreen(DownloadsUiState(rows = listOf(row()),
                busyTaskIds = setOf("task"), loading = false, settingsReady = true),
                onAction = { _, _ -> actions++ }, onConfirm = {}, onDismiss = {},
                onDefaultNetwork = {}, onRetry = {}, onSearch = {})
        }
    }
    compose.onNodeWithText("暂停").performScrollTo().assertIsNotEnabled()
    compose.runOnIdle { assertThat(actions).isEqualTo(0) }
}
~~~

该测试增加 assertIsNotEnabled import。SourceManagerScreenTest 将现有 httpConfirmationIsVisibleBeforeEnable 与 importedRuleDeletionRequiresDialog 再以 PaperTestViewport 包装覆盖320dp/放大字号；安全文案应可滚动可读，确认前回调仍未调用。

- [ ] Full4，读真实报告；提交精确五文件，message: feat: apply paper study download and source surfaces。

## Task 6：累计验收、审查与独立测试包

Depends on Task 1–5. Root 取得唯一 Gradle/最终文档权限后执行；审查者只读。Files: 本计划、docs/test-apk-notes.md；实际修复必须返还对应实现任务并重审。

- [ ] 逐条对照已确认设计：五页/导航/非阅读弹窗完整，正常字色对比达标；无全局旧颜色替换、无 Reader/Data/Domain/Source/schema/偏好/签名/用户图标改动；无网络字体、伪造简介或新 cover 请求。运行以下只读命令，越界输出必须逐项解释：

~~~powershell
git diff --name-only 98fe297 HEAD
git diff 98fe297 HEAD -- app/src/main/java/com/novelradar/app/feature/reader app/src/main/java/com/novelradar/app/data app/src/main/java/com/novelradar/app/domain app/src/main/java/com/novelradar/app/source app/schemas app/src/main/java/com/novelradar/app/ui/theme app/build.gradle.kts
git diff --check
~~~

- [ ] 独立 specification review 先通过，再 quality review；任何 required finding 修复后由同一审查者复核。不能用 HTML mockup 成功代替原生代码审查。
- [ ] Root 独立 Full4、强制全 unit prerequisites/test，再 Full4，保存全输出：

~~~powershell
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
~~~

期待 exit0；真实总数应包含基线与新增测试，禁止预填通过数。解析 XML failures/errors/skips，读实际 lint findings。Android 测试源码编译成功不是设备执行。

- [ ] 检查 D:\NovelReader\.tools\android-test-sdk\platform-tools\adb.exe devices -l。有已授权可用测试设备才报告原生执行/截图；没有就明确未执行、不自行安装/卸载，不启动新模拟器。需要安装权限时由用户明确授权，不能为了截图动现有用户数据。
- [ ] 验证实际 APK metadata：com.novelradar.app / 0.1.0 code1 / min26 target36 / 原四 ABI；v2 签名证书 SHA256 为 4AF3DFFD643A15889D42B27D56A747DA7C9124CEDD6D71FF75B54030D78A2289；图标 ZIP entry res/drawable-nodpi-v4/user_app_icon.png 的 SHA256 为 EAE824602E571E34E47D5FB6B7290B26422E0061AB5E48B68DCDBA95785E1907。aapt/apksigner 位于 C:\Users\26530\AppData\Local\Android\Sdk\build-tools\36.0.0。
- [ ] 最终实现提交冻结后，以下完整命令读取实际提交/哈希并另存，不填预想提交或哈希。File.Copy 第三个参数 false；已有不同内容则拒绝覆盖。复制后再验证签名、DEX 中新 Paper 代码及图标原字节：

~~~powershell
$paperCommit = git rev-parse --short HEAD
if ($LASTEXITCODE -ne 0) { throw 'Cannot resolve verified application commit' }
$paperSource = 'D:\NovelReader\.worktrees\android-novel-reader\app\build\outputs\apk\debug\app-debug.apk'
$paperTarget = Join-Path 'D:\NovelReader\build\deliverables' ('NovelRadar-0.1.0-test-paper-' + $paperCommit + '.apk')
$paperHash = (Get-FileHash -LiteralPath $paperSource -Algorithm SHA256).Hash
if (Test-Path -LiteralPath $paperTarget) {
    if ((Get-FileHash -LiteralPath $paperTarget -Algorithm SHA256).Hash -ne $paperHash) {
        throw 'Refusing to overwrite a different stable APK'
    }
} else {
    [System.IO.File]::Copy($paperSource, $paperTarget, $false)
}
if ((Get-FileHash -LiteralPath $paperTarget -Algorithm SHA256).Hash -ne $paperHash) {
    throw 'Saved APK hash mismatch'
}
Get-Item -LiteralPath $paperTarget | Select-Object FullName, Length
Get-FileHash -LiteralPath $paperTarget -Algorithm SHA256
~~~
- [ ] 旧44d534b、icon-eace59b、repair-adabb7c、indent-b15b995四包均保留；既有 indent 包 SHA256 为 8C0487F1D0903D8B566B15F95D1B765EF3AB50870A28CC72FF203C76CD82154C。更新 docs/test-apk-notes.md 记录真实值/报告/设备限制，仅提交拥有的文档。
- [ ] 提供独立 APK 链接和复测要点：五页风格、长字/大字、导航、原按钮/确认、阅读器未变；建议同包覆盖安装，不先卸载。不得宣称新增搜书覆盖或书源加速。保留分支/工作树，不执行合并/推送/清理。

## 计划自审与交接状态

- [x] 覆盖设计的六个主题色、状态色、字体回退、两列封面、公共页头、四个矢量、五页、弹窗与阅读隔离。
- [x] 核对现有 Screen/UiState/fixture 名称；AppLaunchTest 的旧“设置”期望已列为相关测试修正，产品路由保持“书源”。
- [x] 定义新组件签名、路径、依赖顺序、单写者/单 Gradle 所有权及 RED/GREEN/设备证据边界。
- [x] 不增加后端/API/字体依赖；未给不存在的数据编造 UI；所有业务条件与安全确认须保留。
- [x] 用户选择方式 1：分任务开发、逐项独立审查。2026-10-15 开始执行 Task 1。

## 执行记录

2026-10-15：复用 D:\NovelReader\.worktrees\android-novel-reader 的既有 linked worktree，feature/android-novel-reader 分支，非 submodule，基线 dc4fe3726dc196f96279ac89d58d49921c26bd80 工作树干净。未发现适用 AGENTS.md。Root 在变更前运行 Full4，exit 0、七秒；实际 XML 为 593 tests / 52 suites / zero failures/errors/skips。此时没有运行的 Gradle 进程。Task 1 已交给独立实现者，独占四个新增 app/test 文件和 Gradle；Root 只维护文档。规格与质量审查均须在任务完成后按顺序通过，尚未标记任何实现任务完成。

2026-10-16：Task 1 实现提交 0ec8913a9539ec37a44bdf014821793b8a6cccd3；精确四文件，无现有 UI/业务改动。原始 RED 为三个断言失败（非编译错误），GREEN 三项通过，证据位于 app/build/evidence/paper-study。Root 已独立重跑 testDebugUnitTest --rerun，exit 0；实际全量 XML 为 596 tests / 54 suites，failure/error/skipped 均为零。独立规格审查 PASS，质量审查在额度恢复后完成，Critical/Important/Minor 均无，ReadyToProceed=Yes。Task 1 完成；Task 2–6 尚未完成，没有交付新版外观 APK。

2026-10-16 Task 2 技术澄清：首轮 Full4 exit 0，但新增 PaperPageHeader 的原参数顺序引入 ModifierParameter warning。Root 独立核对代码与实际 SARIF 后，批准将 modifier 移为第一个可选参数，subtitle 的类型/默认值/行为不变；尚无现有调用者，后续使用 subtitle 命名参数。本计划示例已同步修正。此为 Compose 内部 API 规范调整，不改变已确认设计或业务范围，不抑制诊断；实现者须重跑 Full4 再提交。

2026-10-16 环境核对：adb devices -l 返回空设备列表，未安装/卸载应用或启动模拟器。现阶段新增 Compose 回归只可编译，不能声称真机/布局断言运行通过。Root 读取四个历史 APK 的长度与 SHA256，均与既有交付值一致；最终新版必须独立命名保存，不能覆盖旧包。

2026-10-16 Task 2 实现提交 ed357a3f8dd349280574486ad1e7019d0c06388c，精确十二文件；Root 独立 Full4 会话 27178，exit 0、10s（61 tasks，1 executed/60 up-to-date），实际 XML 54 suites/596 tests、零 failure/error/skipped，实际 SARIF 为七个基线 warning/零 error。独立规格审查 PASS，无 Missing/Extra/Misinterpreted；质量审查进行中，尚未标记 Task 2 完成。新增四个 Compose 主题/尺寸回归仅编译，未设备执行。

2026-10-16 Task 2 质量审查完成，Critical/Important/Minor 均无，ReadyToProceed=Yes；Task 2 清单已完成。Task 3 交给新的实现者，单写者/单 Gradle 所有权继续沿用。
