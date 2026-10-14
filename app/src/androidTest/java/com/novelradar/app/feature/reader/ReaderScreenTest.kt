package com.novelradar.app.feature.reader

import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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

    private fun state(): ReaderUiState {
        val chapters = listOf(Chapter("book", "a", "c0", 0, "第一章"), Chapter("book", "a", "c1", 1, "第二章"))
        val body = LoadedChapter(chapters.first(), "夜色渐深，星河仍明亮。\n旅人从山间走来。", ChapterOrigin.Cached)
        return ReaderUiState(book = Book("book", "x", "星河旅人", "叶子"), sourceName = "书源甲",
            chapters = chapters, content = body, verifiedWindow = listOf(body), characterProgress = chapterCharacterProgress(body.content, 0), loading = false)
    }
}
