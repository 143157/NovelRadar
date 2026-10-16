package com.novelradar.app.ui.paper

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.novelradar.app.app.PaperAppAppearance
import com.novelradar.app.app.PaperContentFrame
import com.novelradar.app.ui.theme.NovelRadarTheme
import com.novelradar.app.ui.theme.SignalOrange
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class PaperThemeBoundaryTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun switchingToReaderRestoresLegacyPaletteAndTypography() {
        val paper = mutableStateOf(true)
        var primary = Color.Unspecified
        var size = TextUnit.Unspecified
        var originalSize = TextUnit.Unspecified
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

    @Test
    fun widePaperContentIsLimitedTo600DpAndCentered() {
        var frameWidth = 0
        var contentWidth = 0
        var contentX = Float.NaN
        var expectedFrameWidth = 0
        var expectedContentWidth = 0
        compose.setContent {
            with(LocalDensity.current) {
                expectedFrameWidth = 800.dp.roundToPx()
                expectedContentWidth = 600.dp.roundToPx()
            }
            Box(Modifier.requiredSize(800.dp, 100.dp)) {
                PaperContentFrame(true, Modifier.fillMaxSize().onGloballyPositioned {
                    frameWidth = it.size.width
                }) { modifier ->
                    Box(modifier.onGloballyPositioned {
                        contentWidth = it.size.width
                        contentX = it.positionInParent().x
                    })
                }
            }
        }
        compose.runOnIdle {
            assertEquals(expectedFrameWidth, frameWidth)
            assertEquals(expectedContentWidth, contentWidth)
            assertEquals((frameWidth - contentWidth) / 2f, contentX, 1f)
        }
    }

    @Test
    fun narrowPaperContentFits320DpFrame() {
        var frameWidth = 0
        var contentWidth = 0
        var contentX = Float.NaN
        var expectedWidth = 0
        compose.setContent {
            with(LocalDensity.current) { expectedWidth = 320.dp.roundToPx() }
            Box(Modifier.requiredSize(320.dp, 100.dp)) {
                PaperContentFrame(true, Modifier.fillMaxSize().onGloballyPositioned {
                    frameWidth = it.size.width
                }) { modifier ->
                    Box(modifier.onGloballyPositioned {
                        contentWidth = it.size.width
                        contentX = it.positionInParent().x
                    })
                }
            }
        }
        compose.runOnIdle {
            assertEquals(expectedWidth, frameWidth)
            assertEquals(frameWidth, contentWidth)
            assertEquals(0f, contentX, 0f)
        }
    }

    @Test
    fun readerContentKeepsOriginal800DpWidth() {
        var contentWidth = 0
        var contentX = Float.NaN
        var expectedWidth = 0
        compose.setContent {
            with(LocalDensity.current) { expectedWidth = 800.dp.roundToPx() }
            Box(Modifier.requiredSize(800.dp, 100.dp)) {
                PaperContentFrame(false, Modifier.fillMaxSize()) { modifier ->
                    Box(modifier.onGloballyPositioned {
                        contentWidth = it.size.width
                        contentX = it.positionInParent().x
                    })
                }
            }
        }
        compose.runOnIdle {
            assertEquals(expectedWidth, contentWidth)
            assertEquals(0f, contentX, 0f)
        }
    }
}
