package com.novelradar.app.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novelradar.app.domain.search.AggregatedBookResult
import com.novelradar.app.ui.theme.CardPaper
import com.novelradar.app.ui.theme.Hairline
import com.novelradar.app.ui.theme.InkBlack
import com.novelradar.app.ui.theme.QuietInk
import com.novelradar.app.ui.theme.SignalOrange

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
        shape = RoundedCornerShape(10.dp),
        color = CardPaper,
        border = BorderStroke(1.5.dp, InkBlack),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(120.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.width(5.dp).fillMaxHeight().background(SignalOrange))
            Box(
                modifier = Modifier.padding(start = 13.dp).size(width = 60.dp, height = 82.dp)
                    .background(InkBlack, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Text(result.displayName.take(1), color = CardPaper, fontSize = 29.sp, fontFamily = FontFamily.Serif)
            }
            Column(
                modifier = Modifier.weight(1f).padding(start = 13.dp, end = 14.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    result.displayName,
                    color = InkBlack,
                    fontSize = 21.sp,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(5.dp))
                Text(
                    if (result.author.isBlank()) "作者未标注" else result.author,
                    color = QuietInk,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(9.dp))
                Text(
                    result.bestHit.latestChapter ?: "暂无章节信息",
                    color = QuietInk,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Column(
                modifier = Modifier.padding(end = 13.dp),
                horizontalAlignment = Alignment.End,
            ) {
                Text("${result.sources.map { it.sourceId }.distinct().size}", color = SignalOrange,
                    fontSize = 23.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                Text("个书源", color = InkBlack, fontSize = 10.sp)
                Spacer(Modifier.height(7.dp))
                Box(Modifier.width(22.dp).height(2.dp).background(Hairline))
            }
        }
    }
}
