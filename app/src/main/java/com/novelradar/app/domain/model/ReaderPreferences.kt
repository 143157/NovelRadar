package com.novelradar.app.domain.model

enum class ReaderTheme { DAY, PAPER, NIGHT }

enum class ReaderMode { SCROLL, PAGED }

data class ReaderPreferences(
    val fontSizeSp: Float = 20f,
    val lineHeight: Float = 1.65f,
    val horizontalMarginDp: Int = 20,
    val brightness: Float? = null,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val mode: ReaderMode = ReaderMode.SCROLL,
    val prefetchCount: Int = 2,
    val allowMeteredPrefetch: Boolean = false,
) {
    fun normalized(): ReaderPreferences = copy(
        fontSizeSp = fontSizeSp.takeIf(Float::isFinite)?.coerceIn(14f, 36f) ?: 20f,
        lineHeight = lineHeight.takeIf(Float::isFinite)?.coerceIn(1.2f, 2.2f) ?: 1.65f,
        horizontalMarginDp = horizontalMarginDp.coerceIn(8, 48),
        brightness = brightness?.takeIf(Float::isFinite)?.coerceIn(0.05f, 1f),
        prefetchCount = prefetchCount.coerceIn(0, 2),
    )
}
