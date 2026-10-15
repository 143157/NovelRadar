package com.novelradar.app.feature.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.ReaderMode
import com.novelradar.app.domain.model.ReaderTheme
import com.novelradar.app.domain.repository.ChapterOrigin
import com.novelradar.app.domain.repository.LoadedChapter
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.ui.theme.NovelRadarTheme
import org.junit.Rule
import org.junit.Test

class ReaderScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test fun bothModesIndentRealParagraphFirstLinesAndKeepWrappedLinesAtTheMargin() {
        val initial = state()
        val first = "天地玄黄宇宙洪荒日月盈昃辰宿列张".repeat(2)
        val text = "$first\n" + "春夏秋冬山川日月星辰江河湖海风雨".repeat(2)
        val body = initial.content!!.copy(content = text)
        val live = mutableStateOf(initial.copy(content = body, verifiedWindow = listOf(body)))
        compose.setContent { NovelRadarTheme {
            Box(Modifier.width(280.dp).height(520.dp)) {
                ReaderScreen(live.value, {}, {}, {}, {}, {}, {}, {})
            }
        } }

        for (mode in listOf(ReaderMode.SCROLL, ReaderMode.PAGED)) {
            compose.runOnIdle { live.value = live.value.copy(preferences = live.value.preferences.copy(mode = mode),
                restorationVersion = live.value.restorationVersion + 1) }
            val layout = textLayout(text)
            assertThat(layout.layoutInput.text.text).isEqualTo(text)
            assertFirstLineIndent(layout, 0)
            assertFirstLineIndent(layout, first.length + 1)
        }
    }

    @Test fun scrollContinuationChunkKeepsOriginalTextAndDoesNotRepeatParagraphIndent() {
        val initial = state()
        val text = (0 until 150).joinToString("") { "第${it}组原文甲乙丙丁" }
        val body = initial.content!!.copy(content = text)
        val live = initial.copy(content = body, verifiedWindow = listOf(body))
        compose.setContent { NovelRadarTheme {
            Box(Modifier.width(280.dp).height(520.dp)) { ReaderScreen(live, {}, {}, {}, {}, {}, {}, {}) }
        } }

        assertFirstLineIndent(textLayout(text.substring(0, 600)), 0)
        val continued = text.substring(600, 1200)
        compose.onNodeWithTag("reader_scroll").performScrollToNode(hasText(continued))
        val layout = textLayout(continued)
        assertThat(layout.layoutInput.text.text).isEqualTo(continued)
        assertThat(layout.layoutInput.text.paragraphStyles.single().item.textIndent)
            .isEqualTo(TextIndent(firstLine = 0.em, restLine = 0.em))
        assertThat(layout.lineCount).isGreaterThan(1)
        assertThat(layout.getLineLeft(0)).isWithin(0.5f).of(0f)
        assertThat(layout.getLineLeft(1)).isWithin(0.5f).of(0f)
    }

    @Test fun pagedContinuationAndFontModeChangesKeepTheOriginalUtf16Anchor() {
        val initial = state()
        val text = (0 until 150).joinToString("") { "第${it}组原文😀甲乙丙丁" }
        val body = initial.content!!.copy(content = text)
        val live = mutableStateOf(initial.copy(content = body, verifiedWindow = listOf(body), offset = 17,
            characterProgress = chapterCharacterProgress(text, 17)))
        var visible: ChapterReadingPosition? = null
        compose.setContent { NovelRadarTheme {
            Box(Modifier.width(280.dp).height(520.dp)) {
                ReaderScreen(live.value, {}, { visible = it }, {}, {}, {}, {}, {})
            }
        } }

        for ((mode, fontSize) in listOf(ReaderMode.SCROLL to 20f, ReaderMode.PAGED to 20f,
            ReaderMode.PAGED to 24f, ReaderMode.SCROLL to 24f, ReaderMode.PAGED to 24f)) {
            compose.runOnIdle { live.value = live.value.copy(preferences = live.value.preferences.copy(mode = mode,
                fontSizeSp = fontSize), restorationVersion = live.value.restorationVersion + 1) }
            compose.waitForIdle()
            compose.runOnIdle { assertThat(visible).isEqualTo(ChapterReadingPosition("c0", 17)) }
            compose.onNodeWithText(readerProgressLabel(live.value)).assertIsDisplayed()
        }

        compose.onNodeWithTag("reader_paged").performTouchInput { swipeLeft() }
        val viewport = compose.onNodeWithTag("reader_paged").fetchSemanticsNode().boundsInRoot
        val bodyMatcher = SemanticsMatcher("visible original body slice") { node ->
            val value = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text
            value != null && value.isNotEmpty() && text.contains(value) &&
                node.boundsInRoot.center.x in viewport.left..viewport.right
        }
        val bodyNodes = compose.onAllNodes(bodyMatcher, useUnmergedTree = true)
        assertThat(bodyNodes.fetchSemanticsNodes()).hasSize(1)
        val layouts = mutableListOf<TextLayoutResult>()
        bodyNodes[0].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
            assertThat(action(layouts)).isTrue()
        }
        val layout = layouts.single()
        val slice = layout.layoutInput.text.text
        compose.runOnIdle {
            val position = checkNotNull(visible)
            assertThat(position.chapterId).isEqualTo("c0")
            assertThat(position.offset).isGreaterThan(17)
            assertThat(text.substring(position.offset, position.offset + slice.length)).isEqualTo(slice)
            assertThat(text[position.offset - 1]).isNotEqualTo('\n')
            assertThat(body.content).isEqualTo(text)
        }
        assertThat(layout.layoutInput.text.paragraphStyles.single().item.textIndent)
            .isEqualTo(TextIndent(firstLine = 0.em, restLine = 0.em))
        assertThat(layout.lineCount).isGreaterThan(1)
        assertThat(layout.getLineLeft(0)).isWithin(0.5f).of(0f)
        assertThat(layout.getLineLeft(1)).isWithin(0.5f).of(0f)
        val bounds = bodyNodes[0].fetchSemanticsNode().boundsInRoot
        assertThat(bounds.top + layout.getLineBottom(layout.lineCount - 1)).isAtMost(viewport.bottom + 0.5f)
    }

    @Test fun nativeNewlinesProduceExactlyTwoOrThreeLinesInBothReaderModes() {
        val initial = state()
        val live = mutableStateOf(initial)
        compose.setContent { NovelRadarTheme {
            Box(Modifier.width(280.dp).height(520.dp)) { ReaderScreen(live.value, {}, {}, {}, {}, {}, {}, {}) }
        } }

        for (mode in listOf(ReaderMode.SCROLL, ReaderMode.PAGED)) {
            for ((text, expectedLines) in listOf("甲\n乙" to 2, "甲\n\n乙" to 3,
                "甲\n" to 2, "\n甲" to 2, "\n\n" to 3)) {
                compose.runOnIdle {
                    val body = initial.content!!.copy(content = text)
                    live.value = live.value.copy(content = body, verifiedWindow = listOf(body), offset = 0,
                        preferences = live.value.preferences.copy(mode = mode),
                        restorationVersion = live.value.restorationVersion + 1)
                }
                val layout = textLayout(text)
                assertThat(layout.layoutInput.text.text).isEqualTo(text)
                assertThat(layout.lineCount).isEqualTo(expectedLines)
                val starts = listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }
                assertThat((0 until layout.lineCount).map { layout.getLineStart(it) }).containsExactlyElementsIn(starts).inOrder()
            }
        }
    }

    @Test fun nativeContinuationConsumesOneSeparatorAndKeepsBlankLinesAndOriginalOffsets() {
        val initial = state()
        val prefix = "甲😀".repeat(200)
        val first = initial.content!!.copy(content = prefix + "乙\n丙")
        val live = mutableStateOf(initial.copy(content = first, verifiedWindow = listOf(first), offset = 600))
        var visible: ChapterReadingPosition? = null
        compose.setContent { NovelRadarTheme {
            Box(Modifier.width(280.dp).height(520.dp)) {
                ReaderScreen(live.value, {}, { visible = it }, {}, {}, {}, {}, {})
            }
        } }

        for ((continued, expectedLines) in listOf("乙\n丙" to 2, "乙\n\n丙" to 3, "乙\n" to 2)) {
            compose.runOnIdle {
                val body = first.copy(content = prefix + continued)
                live.value = live.value.copy(content = body, verifiedWindow = listOf(body),
                    characterProgress = chapterCharacterProgress(body.content, 600),
                    restorationVersion = live.value.restorationVersion + 1)
            }
            compose.waitForIdle()
            val head = textLayout("乙")
            val tailText = continued.substring(2)
            val tail = textLayout(tailText)
            assertThat(head.lineCount + tail.lineCount).isEqualTo(expectedLines)
            assertThat(head.getLineLeft(0)).isWithin(0.5f).of(0f)
            val headBounds = compose.onNodeWithText("乙", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val tailBounds = compose.onNodeWithText(tailText, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertThat(tailBounds.top).isWithin(0.5f).of(headBounds.bottom)
            if (tailText.isNotEmpty()) {
                val offset = tailText.lastIndex
                val line = tail.getLineForOffset(offset)
                assertThat(tail.getLineLeft(line)).isWithin(1f).of(tail.getBoundingBox(offset).width * 2f)
            }
            val slice = readerParagraphSlice(live.value.content!!.content, 600, live.value.content!!.content.length)
            assertThat(slice.fragments[0].originalOffset(head.getLineStart(0))).isEqualTo(600)
            assertThat(slice.fragments[1].originalOffset(tail.getLineStart(tail.lineCount - 1)))
                .isEqualTo(602 + if (tailText.startsWith('\n')) 1 else 0)
            compose.runOnIdle { assertThat(visible).isEqualTo(ChapterReadingPosition("c0", 600)) }
            compose.onNodeWithText(readerProgressLabel(live.value)).assertIsDisplayed()
        }
    }

    @Test fun nativeCompositeLayoutMapsContinuationAndBlankLinesBackToOriginalUtf16() {
        val text = "甲😀乙\n\n丙丁"
        val slice = readerParagraphSlice(text, 3, text.length)
        var rendered: ReaderParagraphLayout? = null
        compose.setContent { NovelRadarTheme {
            Box(Modifier.width(280.dp)) {
                ReaderParagraphBody(slice, TextStyle(fontSize = 20.sp, lineHeight = 33.sp), Color.Black,
                    onTextLayout = { rendered = it })
            }
        } }
        compose.waitForIdle()
        compose.runOnIdle {
            val actual = checkNotNull(rendered)
            assertThat(actual.layouts.sumOf { it.lineCount }).isEqualTo(3)
            assertThat(actual.layouts.map { it.layoutInput.text.text }).containsExactly("乙", "\n丙丁").inOrder()
            val headHeight = actual.layouts[0].size.height
            val nextParagraphTop = headHeight + actual.layouts[1].getLineTop(1)
            assertThat(actual.getLineTopForOffset(4)).isWithin(0.5f).of(0f)
            assertThat(actual.getLineTopForOffset(5)).isWithin(0.5f).of(headHeight.toFloat())
            assertThat(actual.getLineTopForOffset(6)).isWithin(0.5f).of(nextParagraphTop)
            assertThat(actual.getLineStartForVerticalPosition(nextParagraphTop + 0.5f)).isEqualTo(6)
            assertThat(actual.getLineStartForVerticalPosition(headHeight + 0.5f)).isEqualTo(5)
            assertThat(actual.fittingEndOffset(headHeight)).isEqualTo(4)
            assertThat(actual.fittingEndOffset(actual.height)).isEqualTo(text.length)
            assertThat(chapterCharacterProgress(text, actual.getLineStartForVerticalPosition(nextParagraphTop + 0.5f)))
                .isEqualTo(ChapterCharacterProgress(5, 7))
            assertThat(slice.text).isEqualTo(text.substring(3))
        }
    }

    @Test fun pagedFinalSliceAccountsForTheEmptyLastLineExposedByANewlineCut() {
        val initial = state()
        val text = "甲\n".repeat(100)
        val body = initial.content!!.copy(content = text)
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            NovelRadarTheme {
                Box(Modifier.width(280.dp).height(300.dp)) {
                    ReaderScreen(initial.copy(content = body, verifiedWindow = listOf(body),
                        preferences = initial.preferences.copy(mode = ReaderMode.PAGED)), {}, {}, {}, {}, {}, {}, {})
                }
            }
        }
        val viewport = compose.onNodeWithTag("reader_paged").fetchSemanticsNode().boundsInRoot
        val bodies = compose.onAllNodes(SemanticsMatcher("visible original newline body") { node ->
            val value = node.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text
            value != null && value.isNotEmpty() && text.contains(value) &&
                node.boundsInRoot.center.x in viewport.left..viewport.right
        }, useUnmergedTree = true)
        assertThat(bodies.fetchSemanticsNodes()).hasSize(1)
        val results = mutableListOf<TextLayoutResult>()
        bodies[0].performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action -> assertThat(action(results)).isTrue() }
        val layout = results.single()
        assertThat(text.startsWith(layout.layoutInput.text.text)).isTrue()
        assertThat(layout.lineCount).isGreaterThan(1)
        val bounds = bodies[0].fetchSemanticsNode().boundsInRoot
        assertThat(bounds.top + layout.getLineBottom(layout.lineCount - 1)).isAtMost(viewport.bottom - 40f * density + 0.5f)
    }

    @Test fun enlargedFontControlsReserveActualFooterHeightInBothModesWithoutLosingAnchor() {
        val initial = state()
        val body = initial.content!!.copy(content = ("真实正文末行😀，不能被阅读工具栏覆盖。\n").repeat(120))
        val live = mutableStateOf(initial.copy(content = body, verifiedWindow = listOf(body), offset = 17,
            characterProgress = chapterCharacterProgress(body.content, 17),
            preferences = initial.preferences.copy(fontSizeSp = 36f, prefetchCount = 0)))
        var visible: ChapterReadingPosition? = null
        var requests = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale = 1.6f)) {
                NovelRadarTheme {
                    ReaderScreen(live.value, {}, { visible = it }, {}, {}, {}, {}, {},
                        onContinueForward = { requests++ }, onContinueBackward = { requests++ })
                }
            }
        }
        for ((mode, tag) in listOf(ReaderMode.SCROLL to "reader_scroll", ReaderMode.PAGED to "reader_paged")) {
            compose.runOnIdle { live.value = live.value.copy(preferences = live.value.preferences.copy(mode = mode),
                restorationVersion = live.value.restorationVersion + 1) }
            compose.waitForIdle()
            compose.onNodeWithTag("reader_center_toggle").performClick()
            val reader = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val controls = compose.onNodeWithTag("reader_controls").fetchSemanticsNode().boundsInRoot
            assertThat(reader.bottom).isAtMost(controls.top + 0.5f)
            assertThat(compose.onAllNodesWithText(readerProgressLabel(live.value)).fetchSemanticsNodes()).hasSize(1)
            compose.runOnIdle { assertThat(visible).isEqualTo(ChapterReadingPosition("c0", 17)); assertThat(requests).isEqualTo(0) }
            compose.onNodeWithTag("reader_center_toggle").performClick()
            val hiddenReader = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            val progress = compose.onNodeWithText(readerProgressLabel(live.value)).fetchSemanticsNode().boundsInRoot
            assertThat(hiddenReader.bottom).isAtMost(progress.top + 0.5f)
            compose.runOnIdle { assertThat(visible).isEqualTo(ChapterReadingPosition("c0", 17)); assertThat(requests).isEqualTo(0) }
        }
    }

    @Test fun centralTapOpensControlsAndSettingsChangeThemeAndMode() {
        val state = mutableStateOf(state())
        compose.setContent {
            NovelRadarTheme {
                ReaderScreen(state.value, onBack = {}, onPositionChanged = {}, onPrevious = {}, onNext = {},
                    onRetry = {}, onChapterSelected = {},
                    onPreferencesChanged = { transform -> state.value = state.value.copy(preferences = transform(state.value.preferences)) })
            }
        }
        compose.onNodeWithTag("reader_center_toggle").performClick()
        compose.onNodeWithTag("reader_controls").assertIsDisplayed()
        compose.onNodeWithText("阅读设置").performClick()
        compose.onNodeWithText("夜间").performScrollTo().performClick()
        compose.onNodeWithText("分页").performScrollTo().performClick()
        assertThat(state.value.preferences.theme).isEqualTo(ReaderTheme.NIGHT)
        assertThat(state.value.preferences.mode).isEqualTo(ReaderMode.PAGED)
    }

    @Test fun catalogSelectionCallsReaderChapterAction() {
        var selected: String? = null
        compose.setContent {
            NovelRadarTheme {
                ReaderScreen(state(), onBack = {}, onPositionChanged = {}, onPrevious = {}, onNext = {},
                    onRetry = {}, onChapterSelected = { selected = it }, onPreferencesChanged = {})
            }
        }
        compose.onNodeWithTag("reader_center_toggle").performClick()
        compose.onNodeWithText("目录").performClick()
        compose.onNodeWithText("第二章").performClick()
        assertThat(selected).isEqualTo("c1")
    }

    @Test fun reviewNoticeKeepsOriginalPositionAndOpensCatalogWhileToolsAreHidden() {
        var selected: String? = null
        val review = state().copy(positionNeedsReview = true, originalChapterTitle = "原第二章",
            originalReadingLocation = ReadingLocation("book", "b", "c1", 1, 888, 50L, ChapterContentFormat.LEGACY),
            notice = "设置保存失败，请重试", error = SourceFailure.Timeout)
        compose.setContent {
            NovelRadarTheme {
                ReaderScreen(review, onBack = {}, onPositionChanged = {}, onPrevious = {}, onNext = {},
                    onRetry = {}, onChapterSelected = { selected = it }, onPreferencesChanged = {})
            }
        }
        compose.onNodeWithTag("reader_position_review").assertIsDisplayed()
        compose.onNodeWithText("原阅读位置：原第二章 · 原字符位置 888（待确认）").assertIsDisplayed()
        compose.onNodeWithText("原记录来自另一书源").assertIsDisplayed()
        compose.onNodeWithText("设置保存失败，请重试").assertIsDisplayed()
        compose.onNodeWithText("连接超时，请检查网络后重试").assertIsDisplayed()
        compose.onNodeWithText("选择章节").performClick()
        compose.onNodeWithText("第二章").performClick()
        assertThat(selected).isEqualTo("c1")
    }

    @Test fun verifiedReviewOffersConfirmationButTemporaryLegacyBodyDoesNot() {
        val review = mutableStateOf(state().copy(positionNeedsReview = true,
            originalReadingLocation = ReadingLocation("book", "a", "missing", 8, 888, 50L)))
        var confirmations = 0
        compose.setContent {
            NovelRadarTheme {
                ReaderScreen(review.value, onBack = {}, onPositionChanged = {}, onPrevious = {}, onNext = {},
                    onRetry = {}, onChapterSelected = {}, onPreferencesChanged = {}, onConfirmPosition = { confirmations++ })
            }
        }
        compose.onNodeWithText("原阅读位置：第9章 · 原字符位置 888（待确认）").assertIsDisplayed()
        compose.onNodeWithText("确认当前位置").performClick()
        assertThat(confirmations).isEqualTo(1)
        compose.runOnIdle { review.value = review.value.copy(notice = POSITION_CONFIRMATION_FAILURE_NOTICE) }
        compose.onNodeWithText("阅读位置确认失败，请重试确认").assertIsDisplayed()
        compose.onNodeWithText("重试返回").assertDoesNotExist()
        compose.onNodeWithText("重试确认").performClick()
        assertThat(confirmations).isEqualTo(2)
        compose.runOnIdle { review.value = review.value.copy(content = review.value.content!!.copy(boundaryVerified = false)) }
        compose.onNodeWithText("确认当前位置").assertDoesNotExist()
        compose.onNodeWithText("当前显示临时旧正文，章节范围尚未确认。原阅读位置会继续保留，请选择章节或重试加载。").assertIsDisplayed()
        compose.onNodeWithText("重试加载").assertIsDisplayed()
    }

    @Test fun scrollGestureReportsNeighborChapterWithItsOwnLocalOffset() {
        val initial = state()
        val bodies = initial.chapters.map { LoadedChapter(it, ("相同正文😀，仍属于自己的章节。\n").repeat(22), ChapterOrigin.Cached) }
        val live = mutableStateOf(initial.copy(content = bodies[0], verifiedWindow = bodies))
        var visible = ChapterReadingPosition("c0", 0)
        compose.setContent {
            NovelRadarTheme {
                ReaderScreen(live.value, {}, onPositionChanged = {
                    visible = it
                    val body = bodies.first { chapter -> chapter.chapter.chapterId == it.chapterId }
                    live.value = live.value.copy(content = body, offset = it.offset,
                        positionVersion = live.value.positionVersion + if (body != live.value.content) 1 else 0)
                }, onPrevious = {}, onNext = {}, onRetry = {}, onChapterSelected = {}, onPreferencesChanged = {})
            }
        }
        repeat(5) { compose.onNodeWithTag("reader_scroll").performTouchInput { swipeUp() } }
        compose.runOnIdle {
            assertThat(visible.chapterId).isEqualTo("c1")
            assertThat(visible.offset).isAtMost(bodies[1].content.length)
        }
    }

    @Test fun scrollAppendAndTrimKeepExactRestoredCharacterWithoutFetch() {
        val initial = state()
        val body = LoadedChapter(initial.chapters[1], ("保存精确字符😀位置，继续阅读。\n").repeat(120), ChapterOrigin.Cached)
        val live = mutableStateOf(initial.copy(content = body, offset = 17, verifiedWindow = listOf(body)))
        var visible: ChapterReadingPosition? = null
        var forwards = 0
        compose.setContent { NovelRadarTheme {
            ReaderScreen(live.value, {}, { visible = it }, {}, {}, {}, {}, {}, onContinueForward = { forwards++ })
        } }
        compose.waitForIdle()
        compose.runOnIdle {
            assertThat(visible).isEqualTo(ChapterReadingPosition("c1", 17))
            live.value = live.value.copy(verifiedWindow = listOf(initial.content!!, body))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertThat(visible).isEqualTo(ChapterReadingPosition("c1", 17))
            live.value = live.value.copy(verifiedWindow = listOf(body))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertThat(visible).isEqualTo(ChapterReadingPosition("c1", 17))
            assertThat(forwards).isEqualTo(0)
        }
    }

    @Test fun pagedSaturatedSwipesRequestBothTypedEdgesAndHoldCurrentBody() {
        val initial = state()
        val bodies = initial.chapters.map { LoadedChapter(it, "短正文😀", ChapterOrigin.Cached) }
        val live = mutableStateOf(initial.copy(content = bodies[0], verifiedWindow = listOf(bodies[0]),
            preferences = initial.preferences.copy(mode = ReaderMode.PAGED, prefetchCount = 0)))
        var forwards = 0
        var backwards = 0
        compose.setContent { NovelRadarTheme {
            ReaderScreen(live.value, {}, {}, {}, {}, {}, {}, {},
                onContinueForward = { forwards++; live.value = live.value.copy(continuationLoading = true) },
                onContinueBackward = { backwards++; live.value = live.value.copy(continuationLoading = true) })
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertThat(forwards).isEqualTo(0); assertThat(backwards).isEqualTo(0) }
        compose.onNodeWithTag("reader_paged").performTouchInput { swipeLeft() }
        compose.runOnIdle {
            assertThat(forwards).isGreaterThan(0)
            assertThat(live.value.content).isEqualTo(bodies[0])
            live.value = live.value.copy(content = bodies[1], verifiedWindow = listOf(bodies[1]),
                continuationLoading = false, restorationVersion = 1, positionVersion = 1)
        }
        compose.onNodeWithTag("reader_paged").performTouchInput { swipeRight() }
        compose.runOnIdle { assertThat(backwards).isGreaterThan(0); assertThat(live.value.content).isEqualTo(bodies[1]) }
    }

    @Test fun pagedReadyNeighborWithTiedCatalogIndicesAdvancesByCatalogOrder() {
        val initial = state()
        val chapters = initial.chapters.map { it.copy(chapterIndex = 7) }
        val bodies = chapters.map { LoadedChapter(it, "各章短正文😀", ChapterOrigin.Cached) }
        val live = mutableStateOf(initial.copy(chapters = chapters, content = bodies[0], verifiedWindow = bodies,
            preferences = initial.preferences.copy(mode = ReaderMode.PAGED)))
        var forwards = 0
        var backwards = 0
        compose.setContent { NovelRadarTheme {
            ReaderScreen(live.value, {}, {}, {}, {}, {}, {}, {},
                onContinueForward = { forwards++; live.value = live.value.copy(content = bodies[1], offset = 0,
                    positionVersion = 1, restorationVersion = 1) }, onContinueBackward = { backwards++ })
        } }
        compose.onNodeWithTag("reader_paged").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertThat(forwards).isEqualTo(1); assertThat(backwards).isEqualTo(0) }
        compose.onNodeWithText("第二章").assertIsDisplayed()
    }

    @Test fun progressVisibleWithHiddenControlsAndContinuationErrorCanRetry() {
        var retries = 0
        val initial = state().copy(characterProgress = ChapterCharacterProgress(2, 4), continuationError = SourceFailure.Timeout)
        compose.setContent { NovelRadarTheme { ReaderScreen(initial, {}, {}, {}, {}, { retries++ }, {}, {}) } }
        compose.onNodeWithText("2 / 4 字 · 本章 50% · 第 1 / 2 章").assertIsDisplayed()
        compose.onNodeWithText("相邻章节暂时无法打开，当前章节仍可阅读").assertIsDisplayed()
        compose.onNodeWithText("重试").performClick()
        assertThat(retries).isEqualTo(1)
    }

    @Test fun pagedCancelledDragAppendTrimAndFontRepaginationPreserveExactAnchorWithoutNavigation() {
        val initial = state()
        val body = LoadedChapter(initial.chapters[1], ("分页精确字符😀位置，继续阅读。\n").repeat(120), ChapterOrigin.Cached)
        val live = mutableStateOf(initial.copy(content = body, verifiedWindow = listOf(body), offset = 17,
            preferences = initial.preferences.copy(mode = ReaderMode.PAGED, prefetchCount = 0)))
        var visible: ChapterReadingPosition? = null
        var boundaries = 0
        compose.setContent { NovelRadarTheme {
            ReaderScreen(live.value, {}, { visible = it }, {}, {}, {}, {}, {},
                onContinueForward = { boundaries++ }, onContinueBackward = { boundaries++ })
        } }
        compose.waitForIdle()
        compose.onNodeWithTag("reader_paged").performTouchInput {
            down(center)
            advanceEventTime(500)
            moveTo(center + Offset(-center.x * 0.2f, 0f))
            advanceEventTime(500)
            up()
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertThat(visible).isEqualTo(ChapterReadingPosition("c1", 17))
            val prepared = body.copy(origin = ChapterOrigin.Downloaded)
            live.value = live.value.copy(content = prepared, verifiedWindow = listOf(initial.content!!, prepared))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertThat(visible).isEqualTo(ChapterReadingPosition("c1", 17))
            live.value = live.value.copy(verifiedWindow = listOf(body), restorationVersion = 1,
                preferences = live.value.preferences.copy(fontSizeSp = 24f))
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertThat(visible).isEqualTo(ChapterReadingPosition("c1", 17))
            assertThat(boundaries).isEqualTo(0)
        }
    }

    @Test fun reviewGateHidesPreparedNeighborAndBlocksUserEdgeGesture() {
        val initial = state()
        val neighbor = LoadedChapter(initial.chapters[1], "未确认不能显示的邻章正文", ChapterOrigin.Cached)
        val live = initial.copy(positionNeedsReview = true, verifiedWindow = listOf(initial.content!!, neighbor))
        var requests = 0
        compose.setContent { NovelRadarTheme {
            ReaderScreen(live, {}, {}, {}, {}, {}, {}, {}, onContinueForward = { requests++ })
        } }
        compose.onNodeWithText(neighbor.content).assertDoesNotExist()
        compose.onNodeWithTag("reader_scroll").performTouchInput { swipeUp() }
        compose.runOnIdle { assertThat(requests).isEqualTo(0) }
    }

    @Test fun finalChapterGestureNeverRequestsPhantomForwardInEitherMode() {
        val initial = state()
        val live = mutableStateOf(initial.copy(chapters = listOf(initial.chapters[0])))
        var requests = 0
        compose.setContent { NovelRadarTheme {
            ReaderScreen(live.value, {}, {}, {}, {}, {}, {}, {}, onContinueForward = { requests++ })
        } }
        compose.onNodeWithTag("reader_scroll").performTouchInput { swipeUp() }
        compose.runOnIdle {
            assertThat(requests).isEqualTo(0)
            live.value = live.value.copy(preferences = live.value.preferences.copy(mode = ReaderMode.PAGED), restorationVersion = 1)
        }
        compose.onNodeWithTag("reader_paged").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertThat(requests).isEqualTo(0) }
    }

    @Test fun continueClickPositionsActualHeadingOfReadyEmptyAndShortNeighbor() {
        val initial = state()
        val bodies = listOf(initial.content!!.copy(content = "短章"), LoadedChapter(initial.chapters[1], "", ChapterOrigin.Cached))
        val live = mutableStateOf(initial.copy(content = bodies[0], verifiedWindow = bodies, preferences = initial.preferences.copy(prefetchCount = 0)))
        var visible: ChapterReadingPosition? = null
        var requests = 0
        compose.setContent { NovelRadarTheme {
            ReaderScreen(live.value, {}, {
                visible = it
                val actual = live.value.verifiedWindow.first { body -> body.chapter.chapterId == it.chapterId }
                live.value = live.value.copy(content = actual, offset = it.offset,
                    positionVersion = live.value.positionVersion + if (actual != live.value.content) 1 else 0)
            }, {}, {}, {}, {}, {}, onContinueForward = { requests++ })
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertThat(requests).isEqualTo(0); assertThat(visible?.chapterId).isEqualTo("c0") }
        compose.onNodeWithText("继续阅读 →").performClick()
        compose.onNodeWithTag("reader_heading:c1").assertIsDisplayed()
        compose.onNodeWithTag("reader_heading:c0").assertIsNotDisplayed()
        compose.runOnIdle { assertThat(visible).isEqualTo(ChapterReadingPosition("c1", 0)); assertThat(requests).isEqualTo(1) }
        compose.runOnIdle {
            live.value = live.value.copy(content = bodies[0], verifiedWindow = listOf(bodies[0], bodies[1].copy(content = "短章正文")),
                offset = 0, positionVersion = live.value.positionVersion + 1, restorationVersion = 1)
        }
        compose.onNodeWithText("继续阅读 →").performClick()
        compose.onNodeWithTag("reader_heading:c1").assertIsDisplayed()
        compose.onNodeWithTag("reader_heading:c0").assertIsNotDisplayed()
        compose.runOnIdle { assertThat(visible).isEqualTo(ChapterReadingPosition("c1", 0)); assertThat(requests).isEqualTo(2) }
    }

    @Test fun userOverscrollWaitsForMissingShortAndEmptyNeighborThenPositionsItsRealHeading() {
        val initial = state()
        val first = initial.content!!.copy(content = "短章")
        val next = LoadedChapter(initial.chapters[1], "新短章", ChapterOrigin.Cached)
        val live = mutableStateOf(initial.copy(content = first, verifiedWindow = listOf(first), preferences = initial.preferences.copy(prefetchCount = 0)))
        var visible: ChapterReadingPosition? = null
        var requests = 0
        compose.setContent { NovelRadarTheme {
            ReaderScreen(live.value, {}, {
                visible = it
                val actual = live.value.verifiedWindow.first { body -> body.chapter.chapterId == it.chapterId }
                live.value = live.value.copy(content = actual, offset = it.offset,
                    positionVersion = live.value.positionVersion + if (actual != live.value.content) 1 else 0)
            }, {}, {}, {}, {}, {}, onContinueForward = {
                requests++
                live.value = live.value.copy(continuationTargetId = "c1", continuationLoading = true)
            })
        } }
        compose.waitForIdle()
        compose.runOnIdle { assertThat(requests).isEqualTo(0) }
        repeat(2) { compose.onNodeWithTag("reader_scroll").performTouchInput { swipeUp() } }
        compose.runOnIdle {
            assertThat(requests).isEqualTo(1)
            assertThat(visible?.chapterId).isEqualTo("c0")
            live.value = live.value.copy(verifiedWindow = listOf(first, next), continuationLoading = false)
        }
        compose.onNodeWithTag("reader_heading:c1").assertIsDisplayed()
        compose.onNodeWithTag("reader_heading:c0").assertIsNotDisplayed()
        compose.runOnIdle { assertThat(visible).isEqualTo(ChapterReadingPosition("c1", 0)); assertThat(requests).isEqualTo(1) }
        compose.runOnIdle {
            live.value = live.value.copy(content = first, verifiedWindow = listOf(first), continuationTargetId = null,
                continuationLoading = false, offset = 0, positionVersion = live.value.positionVersion + 1, restorationVersion = 1)
        }
        repeat(2) { compose.onNodeWithTag("reader_scroll").performTouchInput { swipeUp() } }
        compose.runOnIdle {
            assertThat(requests).isEqualTo(2)
            assertThat(visible?.chapterId).isEqualTo("c0")
            live.value = live.value.copy(verifiedWindow = listOf(first, next.copy(content = "")), continuationLoading = false)
        }
        compose.onNodeWithTag("reader_heading:c1").assertIsDisplayed()
        compose.onNodeWithTag("reader_heading:c0").assertIsNotDisplayed()
        compose.runOnIdle { assertThat(visible).isEqualTo(ChapterReadingPosition("c1", 0)); assertThat(requests).isEqualTo(2) }
    }

    @Test fun preparingShortNeighborWithoutUserIntentKeepsOriginalHeadingAndPosition() {
        val initial = state()
        val first = initial.content!!.copy(content = "短章")
        val live = mutableStateOf(initial.copy(content = first, verifiedWindow = listOf(first), preferences = initial.preferences.copy(prefetchCount = 0)))
        var visible: ChapterReadingPosition? = null
        var requests = 0
        compose.setContent { NovelRadarTheme {
            ReaderScreen(live.value, {}, { visible = it }, {}, {}, {}, {}, {}, onContinueForward = { requests++ })
        } }
        compose.waitForIdle()
        compose.runOnIdle { live.value = live.value.copy(verifiedWindow = listOf(first, LoadedChapter(initial.chapters[1], "", ChapterOrigin.Cached))) }
        compose.onNodeWithTag("reader_heading:c0").assertIsDisplayed()
        compose.runOnIdle { assertThat(visible).isEqualTo(ChapterReadingPosition("c0", 0)); assertThat(requests).isEqualTo(0) }
    }

    private fun textLayout(text: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        compose.onNodeWithText(text, useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                assertThat(action(layouts)).isTrue()
            }
        assertThat(layouts).hasSize(1)
        return layouts.single()
    }

    private fun assertFirstLineIndent(layout: TextLayoutResult, paragraphStart: Int) {
        val line = layout.getLineForOffset(paragraphStart)
        assertThat(layout.lineCount).isGreaterThan(line + 1)
        val glyphWidth = layout.getBoundingBox(paragraphStart).width
        assertThat(layout.getLineLeft(line) - layout.getLineLeft(line + 1))
            .isWithin(1f).of(glyphWidth * 2f)
        assertThat(layout.getLineLeft(line + 1)).isWithin(0.5f).of(0f)
    }

    private fun state(): ReaderUiState {
        val chapters = listOf(Chapter("book", "a", "c0", 0, "第一章"), Chapter("book", "a", "c1", 1, "第二章"))
        val body = LoadedChapter(chapters.first(), "夜色渐深，星河仍明亮。\n旅人从山间走来。", ChapterOrigin.Cached)
        return ReaderUiState(book = Book("book", "x", "星河旅人", "叶子"), sourceName = "书源甲",
            chapters = chapters, content = body, verifiedWindow = listOf(body), characterProgress = chapterCharacterProgress(body.content, 0), loading = false)
    }
}
