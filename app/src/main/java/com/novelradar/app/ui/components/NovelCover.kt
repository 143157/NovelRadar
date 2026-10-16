package com.novelradar.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.novelradar.app.data.repository.CoverFetcher
import com.novelradar.app.ui.theme.CardPaper
import com.novelradar.app.ui.theme.SignalOrange
import kotlinx.coroutines.CancellationException

/** Decorative beside a visible book title. Only policy-checked bytes reach Coil. */
@Composable
fun NovelCover(
    title: String,
    url: String?,
    sourceId: String?,
    fetcher: CoverFetcher?,
    modifier: Modifier = Modifier,
    paperColor: Color = CardPaper,
    letterColor: Color = SignalOrange,
) {
    val bytes by produceState<ByteArray?>(null, url, sourceId, fetcher) {
        value = null
        if (url != null && sourceId != null && fetcher != null) {
            value = try { fetcher.fetch(sourceId, url) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { null }
        }
    }
    Surface(color = paperColor, modifier = modifier.clearAndSetSemantics {}) {
        Box(contentAlignment = Alignment.Center) {
            Text(title.take(1).ifBlank { "书" }, color = letterColor, fontSize = 42.sp, fontWeight = FontWeight.Black)
            bytes?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()) }
        }
    }
}
