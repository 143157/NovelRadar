package com.novelradar.app.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAppearancePolicyTest {
    @Test
    fun onlyTheActualReaderKeepsLegacyTheme() {
        assertTrue(usesPaperStudyAppearance(null, null))
        assertTrue(usesPaperStudyAppearance("book", null))
        assertTrue(usesPaperStudyAppearance(null, "source"))
        assertFalse(usesPaperStudyAppearance("book", "source"))
    }
}
