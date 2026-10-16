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
        PaperStudyTheme {
            Box(Modifier.width(width).height(700.dp)) {
                content()
            }
        }
    }
}
