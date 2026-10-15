package com.novelradar.app.ui.paper

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaperStudyColorsTest {
    @Test
    fun approvedValuesAreStable() {
        assertEquals(Color(0xFFF5F0E7), PaperCanvas)
        assertEquals(Color(0xFFFBF8F1), PaperPanel)
        assertEquals(Color(0xFF373A36), PaperInk)
        assertEquals(Color(0xFF696F65), PaperMuted)
        assertEquals(Color(0xFF566457), PaperAccent)
        assertEquals(Color(0xFFDCDCCF), PaperLine)
        assertEquals(Color(0xFF8A4F2B), PaperWarning)
        assertEquals(Color(0xFFA33B32), PaperError)
    }

    @Test
    fun enabledTextAndPrimaryButtonMeetContrastThreshold() {
        assertContrastAtLeast45(PaperInk, PaperCanvas)
        assertContrastAtLeast45(PaperMuted, PaperCanvas)
        assertContrastAtLeast45(PaperAccent, PaperCanvas)
        assertContrastAtLeast45(PaperPanel, PaperAccent)
        assertContrastAtLeast45(PaperWarning, PaperCanvas)
        assertContrastAtLeast45(PaperError, PaperCanvas)
    }

    private fun assertContrastAtLeast45(foreground: Color, background: Color) {
        val a = foreground.luminance().toDouble()
        val b = background.luminance().toDouble()
        val ratio = (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
        assertTrue("Text contrast: " + ratio, ratio >= 4.5)
    }
}
