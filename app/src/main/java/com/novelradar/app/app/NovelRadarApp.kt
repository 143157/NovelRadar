package com.novelradar.app.app

import android.net.Uri
import android.security.NetworkSecurityPolicy
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelradar.app.SourceBootstrapState
import com.novelradar.app.feature.book.BookDetailScreen
import com.novelradar.app.feature.book.BookDetailViewModel
import com.novelradar.app.domain.model.ReaderPreferences
import com.novelradar.app.feature.reader.ReaderScreen
import com.novelradar.app.feature.reader.ReaderViewModel
import com.novelradar.app.feature.reader.readerColors
import com.novelradar.app.feature.search.SearchScreen
import com.novelradar.app.feature.search.SearchViewModel
import com.novelradar.app.feature.sources.SourceManagerEvent
import com.novelradar.app.feature.sources.SourceManagerScreen
import com.novelradar.app.feature.sources.SourceManagerViewModel
import com.novelradar.app.ui.theme.InkBlack
import com.novelradar.app.ui.theme.SignalOrange
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

private val destinations = listOf(
    "书架" to "▤",
    "搜书" to "⌕",
    "下载" to "↓",
    "书源" to "⚙",
)

@Composable
fun NovelRadarApp(container: AppContainer, bootstrapState: StateFlow<SourceBootstrapState>) {
    // Search is the first useful destination while bookshelf and reader pages are being built.
    var selectedIndex by rememberSaveable { mutableIntStateOf(1) }
    var selectedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerChapterId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerSession by rememberSaveable { mutableIntStateOf(0) }
    val readerPreferenceFlow = remember(container) { container.readerSettings.preferences.catch { /* Reader shows the recoverable error. */ } }
    val readerPreferences by readerPreferenceFlow.collectAsStateWithLifecycle(initialValue = ReaderPreferences())
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
    val sourcesFactory = remember(container) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == SourceManagerViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return SourceManagerViewModel(
                    repository = container.sourceRules,
                    testSource = container.testSource::test,
                    importAction = { uri -> container.importRules.import(Uri.parse(uri)) },
                    cleartextAvailable = { host -> NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host) },
                    parser = container.ruleParser,
                ) as T
            }
        }
    }
    val sourceViewModel: SourceManagerViewModel = viewModel(factory = sourcesFactory)
    val sourceState by sourceViewModel.uiState.collectAsStateWithLifecycle()
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { sourceViewModel.onImportSelected(it.toString()) }
    }
    val preparation by bootstrapState.collectAsStateWithLifecycle()

    LaunchedEffect(searchViewModel) {
        searchViewModel.navigation.collect { bookId ->
            selectedBookId = bookId
            selectedIndex = 1
        }
    }
    LaunchedEffect(sourceViewModel) {
        sourceViewModel.events.collect { event ->
            when (event) {
                SourceManagerEvent.OpenDocument -> importLauncher.launch(arrayOf("application/json", "text/plain", "text/*"))
            }
        }
    }
    BackHandler(enabled = selectedBookId != null && readerSourceId == null) { selectedBookId = null }

    Scaffold(
        containerColor = if (readerSourceId != null) readerColors(readerPreferences.theme).first else MaterialTheme.colorScheme.background,
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
            selectedBookId != null && readerSourceId != null -> ReaderRoute(requireNotNull(selectedBookId), requireNotNull(readerSourceId),
                readerChapterId, readerSession, container, { readerSourceId = null; readerChapterId = null }, modifier)
            selectedBookId != null -> BookDetailRoute(requireNotNull(selectedBookId), container, { selectedBookId = null },
                { sourceId, chapterId -> readerSourceId = sourceId; readerChapterId = chapterId; readerSession++ }, modifier)
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
            selectedIndex == 3 -> SourceManagerScreen(
                state = sourceState,
                onImport = sourceViewModel::requestImport,
                onTestAll = sourceViewModel::testAll,
                onToggle = sourceViewModel::requestToggle,
                onTestOne = sourceViewModel::testOne,
                onDelete = sourceViewModel::deleteUserRule,
                onConfirmHttp = sourceViewModel::confirmHttpEnable,
                onCancelHttp = sourceViewModel::cancelHttpEnable,
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
private fun BookDetailRoute(bookId: String, container: AppContainer, onBack: () -> Unit,
    onRead: (String, String?) -> Unit, modifier: Modifier) {
    val factory = remember(bookId, container) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == BookDetailViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return BookDetailViewModel(bookId, container.catalog, container.books) as T
            }
        }
    }
    val model: BookDetailViewModel = viewModel(key = "book:$bookId", factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    BookDetailScreen(state, onBack = onBack, onSourceSelected = model::selectSource,
        onAddToShelf = model::addToShelf, onRefresh = model::refresh,
        onChapterQueryChanged = model::onChapterQueryChanged, onToggleOrder = model::toggleChapterOrder,
        canRead = true, onStartReading = { state.selectedSourceId?.let { onRead(it, null) } },
        onChapterSelected = { chapter -> onRead(chapter.sourceId, chapter.chapterId) },
        modifier = modifier)
}

@Composable
private fun ReaderRoute(bookId: String, sourceId: String, chapterId: String?, session: Int,
    container: AppContainer, onBack: () -> Unit, modifier: Modifier) {
    val owner = remember(bookId, sourceId, session) {
        object : ViewModelStoreOwner { override val viewModelStore = ViewModelStore() }
    }
    val factory = remember(bookId, sourceId, chapterId, container) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ReaderViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return ReaderViewModel(bookId, sourceId, chapterId, container.catalog,
                    container.chapters, container.readerSettings, container.readingProgress) as T
            }
        }
    }
    val model: ReaderViewModel = viewModel(viewModelStoreOwner = owner, factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var exiting by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!exiting) {
            exiting = true
            scope.launch { if (model.close()) onBack() else exiting = false }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(model, lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) model.saveOnStop() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); model.saveOnStop(); owner.viewModelStore.clear() }
    }
    BackHandler(onBack = leave)
    val chapterKey = state.content?.chapter?.chapterId
    val version = state.positionVersion
    ReaderScreen(state, onBack = leave, onPositionChanged = { offset ->
        chapterKey?.let { model.onPositionChanged(it, version, offset) }
    }, onPrevious = model::previousChapter, onNext = model::nextChapter,
        onRetry = model::retry, onChapterSelected = model::selectChapter,
        onPreferencesChanged = model::updatePreferences, modifier = modifier)
}
