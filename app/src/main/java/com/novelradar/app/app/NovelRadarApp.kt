package com.novelradar.app.app

import android.Manifest
import android.net.Uri
import android.os.Build
import android.security.NetworkSecurityPolicy
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.novelradar.app.SourceBootstrapState
import com.novelradar.app.feature.book.BookDetailScreen
import com.novelradar.app.feature.book.BookDetailViewModel
import com.novelradar.app.feature.library.LibraryScreen
import com.novelradar.app.feature.library.LibraryViewModel
import com.novelradar.app.feature.downloads.DownloadsViewModel
import com.novelradar.app.feature.downloads.DownloadsScreen
import com.novelradar.app.feature.downloads.DownloadsEvent
import com.novelradar.app.feature.downloads.DownloadStartDialog
import com.novelradar.app.domain.model.ReaderPreferences
import com.novelradar.app.feature.reader.ReaderScreen
import com.novelradar.app.feature.reader.ReaderViewModel
import com.novelradar.app.feature.reader.ReaderSessionViewModel
import com.novelradar.app.feature.reader.readerColors
import com.novelradar.app.feature.search.SearchScreen
import com.novelradar.app.feature.search.SearchViewModel
import com.novelradar.app.feature.sources.SourceManagerEvent
import com.novelradar.app.feature.sources.SourceManagerScreen
import com.novelradar.app.feature.sources.SourceManagerViewModel
import com.novelradar.app.ui.paper.PaperNavigation
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
    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }
    var selectedBookId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerSourceId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerChapterId by rememberSaveable { mutableStateOf<String?>(null) }
    var readerSession by rememberSaveable { mutableIntStateOf(0) }
    var readerFromShelf by rememberSaveable { mutableStateOf(false) }
    val libraryFactory = remember(container) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == LibraryViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return LibraryViewModel(container.books, container.refreshShelf::refresh,
                    container.downloads::deleteLocal) as T
            }
        }
    }
    val libraryViewModel: LibraryViewModel = viewModel(factory = libraryFactory)
    val libraryState by libraryViewModel.uiState.collectAsStateWithLifecycle()
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
    val downloadsFactory = remember(container) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == DownloadsViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return DownloadsViewModel(container.downloads, container.books, container.catalog, container.downloadSettings) as T
            }
        }
    }
    val downloadsViewModel: DownloadsViewModel = viewModel(factory = downloadsFactory)
    val downloadsState by downloadsViewModel.uiState.collectAsStateWithLifecycle()
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission(),
        downloadsViewModel::notificationPermissionResult)
    val snackbarHost = remember { SnackbarHostState() }
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
    LaunchedEffect(libraryViewModel) {
        libraryViewModel.navigation.collect { target ->
            selectedBookId = target.bookId
            readerSourceId = target.sourceId
            readerChapterId = null
            readerFromShelf = true
            readerSession++
        }
    }
    LaunchedEffect(sourceViewModel) {
        sourceViewModel.events.collect { event ->
            when (event) {
                SourceManagerEvent.OpenDocument -> importLauncher.launch(arrayOf("application/json", "text/plain", "text/*"))
            }
        }
    }
    LaunchedEffect(downloadsViewModel) {
        downloadsViewModel.events.collect { event ->
            when (event) {
                DownloadsEvent.RequestNotificationPermission -> if (downloadsViewModel.claimNotificationPermissionRequest()) {
                    if (Build.VERSION.SDK_INT >= 33) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else downloadsViewModel.notificationPermissionResult(granted = true)
                }
                is DownloadsEvent.OpenReader -> {
                    selectedIndex = 2
                    selectedBookId = event.target.bookId
                    readerSourceId = event.target.sourceId
                    readerChapterId = null
                    readerFromShelf = true
                    readerSession++
                }
            }
        }
    }
    LaunchedEffect(downloadsState.notice) {
        downloadsState.notice?.let {
            snackbarHost.showSnackbar(it, duration = SnackbarDuration.Long)
            downloadsViewModel.clearNotice()
        }
    }
    BackHandler(enabled = selectedBookId != null && readerSourceId == null) { selectedBookId = null }

    val usePaper = usesPaperStudyAppearance(selectedBookId, readerSourceId)
    PaperAppAppearance(usePaper) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbarHost) },
            containerColor = if (!usePaper) readerColors(readerPreferences.theme).first else MaterialTheme.colorScheme.background,
            bottomBar = {
                if (selectedBookId == null) {
                    PaperNavigation(selectedIndex, onSelected = { selectedIndex = it })
                }
            },
        ) { contentPadding ->
            val pageModifier = Modifier.fillMaxSize().padding(contentPadding)
            PaperContentFrame(usePaper, pageModifier) { modifier ->
                when {
                    selectedBookId != null && readerSourceId != null -> ReaderRoute(requireNotNull(selectedBookId), requireNotNull(readerSourceId),
                        readerChapterId, readerSession, container, {
                            readerSourceId = null; readerChapterId = null
                            if (readerFromShelf) selectedBookId = null
                            readerFromShelf = false
                        },
                        { readerChapterId = null }, modifier)
                    selectedBookId != null -> BookDetailRoute(requireNotNull(selectedBookId), container, downloadsViewModel, { selectedBookId = null },
                        { sourceId, chapterId -> readerFromShelf = false; readerSourceId = sourceId; readerChapterId = chapterId; readerSession++ }, modifier)
                    selectedIndex == 0 -> LibraryScreen(libraryState, libraryViewModel::open, libraryViewModel::refresh,
                        libraryViewModel::requestRemoval, libraryViewModel::confirmRemoval, libraryViewModel::cancelRemoval,
                        onSearch = { selectedIndex = 1 }, onDetails = { selectedBookId = it },
                        coverFetcher = container.coverFetcher, modifier = modifier)
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
                    selectedIndex == 2 -> DownloadsScreen(downloadsState, downloadsViewModel::action,
                        downloadsViewModel::confirmAction, downloadsViewModel::dismissConfirmation,
                        downloadsViewModel::setDefaultNetwork, downloadsViewModel::retryList,
                        onSearch = { selectedIndex = 1 }, modifier = modifier)
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
    }
}

@Composable
private fun BookDetailRoute(bookId: String, container: AppContainer, downloads: DownloadsViewModel, onBack: () -> Unit,
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
    val downloadState by downloads.uiState.collectAsStateWithLifecycle()
    val leave: () -> Unit = {
        if (model.canLeave() && !downloads.uiState.value.startBusy) { downloads.dismissStart(); onBack() }
    }
    BackHandler(onBack = leave)
    BookDetailScreen(state, onBack = leave, onSourceSelected = model::selectSource,
        onAddToShelf = model::addToShelf, onRefresh = model::refresh,
        onChapterQueryChanged = model::onChapterQueryChanged, onToggleOrder = model::toggleChapterOrder,
        canRead = true, onStartReading = { model.readingTarget()?.let { onRead(it.sourceId, it.chapterId) } },
        canDownload = !downloadState.startBusy && downloadState.startRequest == null,
        onDownload = { model.readingTarget()?.let { downloads.requestStart(bookId, it.sourceId) } },
        onChapterSelected = { chapter -> model.readingTarget(chapter)?.let { onRead(it.sourceId, it.chapterId) } },
        modifier = modifier, coverFetcher = container.coverFetcher)
    downloadState.startRequest?.takeIf { it.bookId == bookId }?.let { request ->
        DownloadStartDialog(request, downloads::chooseStartNetwork, onConfirm = {
            val liveTarget = model.readingTarget()
            downloads.confirmStart(if (liveTarget != null) bookId else null, liveTarget?.sourceId,
                container.downloadNotifications.permissionRequestRequired())
        }, onDismiss = downloads::dismissStart)
    }
}

@Composable
private fun ReaderRoute(bookId: String, sourceId: String, chapterId: String?, session: Int,
    container: AppContainer, onBack: () -> Unit, onInitialChapterLoaded: () -> Unit, modifier: Modifier) {
    // One Activity owner is reused across entries; its nested store is cleared on departure.
    // Configuration changes retain the current reader even while its disk write is pending.
    val owner: ReaderSessionViewModel = viewModel(key = "reader-session")
    val activity = LocalActivity.current
    val factory = remember(bookId, sourceId, chapterId, container) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                require(modelClass == ReaderViewModel::class.java)
                @Suppress("UNCHECKED_CAST")
                return ReaderViewModel(bookId, sourceId, chapterId, container.catalog,
                    container.chapters, container.readerSettings, container.readingProgress,
                    onInitialChapterLoaded = onInitialChapterLoaded) as T
            }
        }
    }
    val model: ReaderViewModel = viewModel(key = "reader:$bookId:$sourceId:$session",
        viewModelStoreOwner = owner, factory = factory)
    val state by model.uiState.collectAsStateWithLifecycle()
    // A retained model may finish loading between the old and new compositions.
    LaunchedEffect(model, state.initialEntryCompleted) {
        if (state.initialEntryCompleted) onInitialChapterLoaded()
    }
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
        onDispose {
            lifecycle.removeObserver(observer)
            model.saveOnStop()
            if (activity?.isChangingConfigurations != true) owner.viewModelStore.clear()
        }
    }
    BackHandler(onBack = leave)
    val version by androidx.compose.runtime.rememberUpdatedState(state.positionVersion)
    ReaderScreen(state, onBack = leave, onPositionChanged = { position ->
        model.onVisiblePosition(version, position)
    }, onPrevious = model::previousChapter, onNext = model::nextChapter,
        onContinueForward = { model.continueForward(version) }, onContinueBackward = { model.continueBackward(version) },
        onRetry = model::retry, onChapterSelected = model::selectChapter,
        onConfirmPosition = { scope.launch { model.confirmPosition() } },
        onPreferencesChanged = model::updatePreferences, modifier = modifier)
}
