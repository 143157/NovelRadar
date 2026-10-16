package com.novelradar.app.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelradar.app.domain.search.AggregatedBookResult
import com.novelradar.app.ui.paper.PaperAccent
import com.novelradar.app.ui.paper.PaperCanvas
import com.novelradar.app.ui.paper.PaperInk
import com.novelradar.app.ui.paper.PaperLine
import com.novelradar.app.ui.paper.PaperMuted
import com.novelradar.app.ui.paper.PaperPanel

@Composable
fun SearchResultCard(
    result: AggregatedBookResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .testTag("search_result_${result.bookKey}")
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(4.dp),
        color = PaperPanel,
        border = BorderStroke(1.dp, PaperLine),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier.size(width = 60.dp, height = 82.dp)
                    .clip(RoundedCornerShape(2.dp)).background(PaperCanvas),
                contentAlignment = Alignment.Center,
            ) {
                Text(result.displayName.take(1), color = PaperAccent, fontSize = 29.sp, fontFamily = FontFamily.Serif)
            }
            Column(
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    result.displayName,
                    color = PaperInk,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    if (result.author.isBlank()) "作者未标注" else result.author,
                    color = PaperMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    result.bestHit.latestChapter ?: "暂无章节信息",
                    color = PaperMuted,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                horizontalAlignment = Alignment.End,
            ) {
                Text("${result.sources.map { it.sourceId }.distinct().size}", color = PaperAccent,
                    fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text("个书源", color = PaperMuted, fontSize = 12.sp)
            }
        }
    }
}
