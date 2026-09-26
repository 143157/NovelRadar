package com.novelradar.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val novelRadarColors = lightColorScheme(
    primary = SignalOrange,
    onPrimary = CanvasWhite,
    background = CanvasWhite,
    onBackground = InkBlack,
    surface = CanvasWhite,
    onSurface = InkBlack,
    surfaceContainer = CanvasWhite,
)

@Composable
fun NovelRadarTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = novelRadarColors, content = content)
}
