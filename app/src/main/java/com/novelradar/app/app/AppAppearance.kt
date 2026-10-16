package com.novelradar.app.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.novelradar.app.ui.paper.PaperStudyTheme

internal fun usesPaperStudyAppearance(bookId: String?, sourceId: String?): Boolean =
    bookId == null || sourceId == null

@Composable
internal fun PaperAppAppearance(usePaper: Boolean, content: @Composable () -> Unit) {
    if (usePaper) PaperStudyTheme(content) else content()
}

@Composable
internal fun PaperContentFrame(
    usePaper: Boolean,
    modifier: Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    if (!usePaper) content(modifier)
    else Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        content(Modifier.widthIn(max = 600.dp).fillMaxSize())
    }
}
