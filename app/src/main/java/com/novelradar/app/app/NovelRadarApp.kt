package com.novelradar.app.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelradar.app.SourceBootstrapState
import com.novelradar.app.feature.search.SearchScreen
import com.novelradar.app.feature.search.SearchViewModel
import com.novelradar.app.ui.theme.InkBlack
import com.novelradar.app.ui.theme.SignalOrange
import kotlinx.coroutines.flow.StateFlow

private val destinations = listOf(
    "书架" to "▤",
    "搜书" to "⌕",
    "下载" to "↓",
    "设置" to "⚙",
)

@Composable
fun NovelRadarApp(container: AppContainer, bootstrapState: StateFlow<SourceBootstrapState>) {
    // Search is the first useful destination while bookshelf and reader pages are being built.
    var selectedIndex by rememberSaveable { mutableIntStateOf(1) }
    var selectedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    val searchFactory = remember(container) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == SearchViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return SearchViewModel(container.aggregateSearch::search, container.books) as T
            }
        }
    }
    val searchViewModel: SearchViewModel = viewModel(factory = searchFactory)
    val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()
    val preparation by bootstrapState.collectAsStateWithLifecycle()

    LaunchedEffect(searchViewModel) {
        searchViewModel.navigation.collect { bookId ->
            selectedBookId = bookId
            selectedIndex = 1
        }
    }
    BackHandler(enabled = selectedBookId != null) { selectedBookId = null }

    Scaffold(
        bottomBar = {
            if (selectedBookId == null) {
                NavigationBar {
                    destinations.forEachIndexed { index, (label, symbol) ->
                        NavigationBarItem(
                            selected = selectedIndex == index,
                            onClick = { selectedIndex = index },
                            icon = { Text(symbol, fontSize = 22.sp) },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = InkBlack,
                                selectedTextColor = SignalOrange,
                                indicatorColor = SignalOrange,
                            ),
                        )
                    }
                }
            }
        },
    ) { contentPadding ->
        val modifier = Modifier.fillMaxSize().padding(contentPadding)
        when {
            selectedBookId != null -> BookDetailPreview(requireNotNull(selectedBookId), container, { selectedBookId = null }, modifier)
            selectedIndex == 1 -> SearchScreen(
                state = searchState,
                onQueryChanged = searchViewModel::onQueryChanged,
                onSubmit = searchViewModel::submit,
                onRetry = searchViewModel::retryFailed,
                onResultSelected = searchViewModel::selectResult,
                sourcesReady = preparation == SourceBootstrapState.Ready,
                bootstrapFailed = preparation == SourceBootstrapState.Failed,
                modifier = modifier,
            )
            else -> Column(modifier, horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                Text(destinations[selectedIndex].first, fontSize = 30.sp, fontWeight = FontWeight.Bold)
                Text("即将加入", Modifier.padding(top = 8.dp))
            }
        }
    }
}

@Composable
private fun BookDetailPreview(bookId: String, container: AppContainer, onBack: () -> Unit, modifier: Modifier) {
    val book by produceState<com.novelradar.app.domain.model.Book?>(initialValue = null, bookId) {
        value = container.books.getBook(bookId)
    }
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = onBack) { Text("返回搜书") }
        Text(book?.bookName ?: "正在打开书籍…", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = InkBlack)
        book?.author?.takeIf(String::isNotBlank)?.let { Text(it, color = InkBlack) }
        Text("书籍编号：$bookId", fontSize = 11.sp, color = InkBlack)
        Text("书籍详情和章节阅读正在接入。", fontSize = 14.sp, color = InkBlack)
    }
}
