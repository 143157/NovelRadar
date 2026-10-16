package com.novelradar.app.ui.paper

import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.novelradar.app.R

@Composable
fun PaperNavigation(selectedIndex: Int, onSelected: (Int) -> Unit) {
    val entries = listOf(
        "书架" to R.drawable.paper_nav_books,
        "搜书" to R.drawable.paper_nav_search,
        "下载" to R.drawable.paper_nav_download,
        "书源" to R.drawable.paper_nav_sources,
    )
    NavigationBar(containerColor = PaperPanel, tonalElevation = 0.dp) {
        entries.forEachIndexed { index, entry ->
            NavigationBarItem(
                selected = index == selectedIndex,
                onClick = { onSelected(index) },
                icon = { Icon(painterResource(entry.second), contentDescription = null) },
                label = { Text(entry.first) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = PaperAccent,
                    selectedTextColor = PaperAccent,
                    unselectedIconColor = PaperMuted,
                    unselectedTextColor = PaperMuted,
                    indicatorColor = PaperAccent.copy(alpha = .10f),
                ),
            )
        }
    }
}
