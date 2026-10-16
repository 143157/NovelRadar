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
        headlineLarge = base.headlineLarge.copy(
            fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 36.sp,
            fontWeight = FontWeight.Normal,
        ),
        titleLarge = base.titleLarge.copy(
            fontFamily = FontFamily.Serif, fontSize = 20.sp, lineHeight = 28.sp,
            fontWeight = FontWeight.Medium,
        ),
        titleMedium = base.titleMedium.copy(
            fontFamily = FontFamily.Serif, fontSize = 17.sp, lineHeight = 24.sp,
        ),
        bodyLarge = base.bodyLarge.copy(fontFamily = FontFamily.SansSerif, fontSize = 16.sp),
        bodyMedium = base.bodyMedium.copy(fontFamily = FontFamily.SansSerif, fontSize = 14.sp),
        bodySmall = base.bodySmall.copy(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
        labelSmall = base.labelSmall.copy(fontFamily = FontFamily.SansSerif, fontSize = 12.sp),
    )
}

@Composable
fun PaperStudyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = PaperAccent,
            onPrimary = PaperPanel,
            primaryContainer = PaperAccent.copy(alpha = .10f),
            onPrimaryContainer = PaperInk,
            secondary = PaperAccent,
            onSecondary = PaperPanel,
            secondaryContainer = PaperCanvas,
            onSecondaryContainer = PaperInk,
            tertiary = PaperAccent,
            onTertiary = PaperPanel,
            tertiaryContainer = PaperCanvas,
            onTertiaryContainer = PaperInk,
            background = PaperCanvas,
            onBackground = PaperInk,
            surface = PaperPanel,
            onSurface = PaperInk,
            surfaceVariant = PaperCanvas,
            onSurfaceVariant = PaperMuted,
            surfaceTint = PaperPanel,
            surfaceDim = PaperCanvas,
            surfaceBright = PaperPanel,
            surfaceContainerLowest = PaperPanel,
            surfaceContainerLow = PaperPanel,
            surfaceContainer = PaperPanel,
            surfaceContainerHigh = PaperPanel,
            surfaceContainerHighest = PaperPanel,
            inverseSurface = PaperInk,
            inverseOnSurface = PaperPanel,
            inversePrimary = PaperPanel,
            outline = PaperMuted,
            outlineVariant = PaperLine,
            error = PaperError,
            onError = PaperPanel,
            errorContainer = PaperPanel,
            onErrorContainer = PaperError,
        ),
        typography = paperTypography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(2.dp),
            small = RoundedCornerShape(4.dp),
            medium = RoundedCornerShape(8.dp),
            large = RoundedCornerShape(8.dp),
            extraLarge = RoundedCornerShape(8.dp),
        ),
        content = content,
    )
}
