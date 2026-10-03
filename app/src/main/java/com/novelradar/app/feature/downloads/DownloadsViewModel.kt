package com.novelradar.app.feature.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelradar.app.domain.model.DownloadTask
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.CatalogRepository
import com.novelradar.app.domain.repository.DownloadRepository
import com.novelradar.app.domain.repository.DownloadSettingsRepository
import com.novelradar.app.domain.repository.DownloadStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class DownloadsViewModel(private val downloads: DownloadRepository,
    private val books: BookRepository, private val catalog: CatalogRepository,
    private val settings: DownloadSettingsRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(DownloadsUiState())
    val uiState = mutableState.asStateFlow()
    private val eventChannel = Channel<DownloadsEvent>(Channel.BUFFERED)
    // Unlike a consumable channel receive, an unclaimed permission request survives
    // collector cancellation. The Android launcher claims it synchronously before launch.
    val events: Flow<DownloadsEvent> = merge(eventChannel.receiveAsFlow(), mutableState
        .map { if (it.notificationPermissionRequestPending) DownloadsEvent.RequestNotificationPermission else null }
        .distinctUntilChanged().filterNotNull())
    private var listJob: Job? = null
    private var settingsJob: Job? = null
    private var pendingPermission: DownloadStartRequest? = null
    private var knownSourceNames: Map<String, String> = emptyMap()

    init { retryList() }

    private fun observeSettings() {
        if (settingsJob?.isActive == true) return
        settingsJob = viewModelScope.launch {
            try {
                settings.preferences.collect { preferences ->
                    mutableState.update { it.copy(defaultAllowMetered = preferences.allowMetered,
                        settingsReady = true, settingsError = null) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(settingsReady = false,
                settingsError = "下载网络设置读取失败，请重试") } }
        }
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun retryList() {
        observeSettings()
        listJob?.cancel()
        mutableState.update { it.copy(loading = true, listError = null) }
        listJob = viewModelScope.launch {
            try {
                downloads.observeAll().flatMapLatest { tasks ->
                    if (tasks.isEmpty()) flowOf(emptyList())
                    else combine(tasks.map(::observeRow)) { it.toList() }
                }.collect { rows -> mutableState.update { it.copy(rows = rows, loading = false, listError = null) } }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(loading = false, listError = "下载列表读取失败，请重试") } }
        }
    }

    private fun observeRow(task: DownloadTask): Flow<DownloadRow> = flow {
        val book = books.getBook(task.bookId)?.takeIf { it.bookId == task.bookId }
        val names = catalog.sourceNames().also { knownSourceNames = it }
        catalog.observeChapters(task.bookId, task.sourceId).collect { chapters ->
            emit(task.toRow(book?.bookName ?: "书籍信息暂不可用", names[task.sourceId] ?: "书源信息暂不可用",
                chapters.firstOrNull { it.bookId == task.bookId && it.sourceId == task.sourceId && it.chapterId == task.currentChapterId }?.title))
        }
    }

    fun action(taskId: String, action: DownloadAction) {
        val row = currentRow(taskId, action) ?: return
        if (action == DownloadAction.CANCEL || action == DownloadAction.DELETE_FILES) {
            mutableState.update { it.copy(confirmation = DownloadConfirmation(row, action)) }
        } else perform(row, action)
    }

    fun dismissConfirmation() { mutableState.update { it.copy(confirmation = null) } }

    fun confirmAction() {
        val confirmation = mutableState.value.confirmation ?: return
        dismissConfirmation()
        val row = currentRow(confirmation.row.taskId, confirmation.action)
        if (row == null || row.bookId != confirmation.row.bookId || row.sourceId != confirmation.row.sourceId) {
            notice("任务状态已变化，请查看最新列表")
            return
        }
        perform(row, confirmation.action)
    }

    private fun currentRow(taskId: String, action: DownloadAction): DownloadRow? = mutableState.value.let { state ->
        if (taskId in state.busyTaskIds || state.listError != null) null
        else state.rows.firstOrNull { it.taskId == taskId && action in it.actions }
    }

    private fun perform(row: DownloadRow, action: DownloadAction) {
        mutableState.update { it.copy(busyTaskIds = it.busyTaskIds + row.taskId) }
        viewModelScope.launch {
            try {
                val latest = downloads.observeAll().first().firstOrNull { it.taskId == row.taskId }
                if (latest == null || latest.bookId != row.bookId || latest.sourceId != row.sourceId ||
                    action !in latest.toRow(row.title, row.sourceName, row.chapterTitle).actions) {
                    notice("任务状态已变化，请查看最新列表")
                    return@launch
                }
                when (action) {
                    DownloadAction.PAUSE -> downloads.pause(row.taskId)
                    DownloadAction.CONTINUE -> downloads.resume(row.taskId)
                    DownloadAction.RETRY_FAILED -> downloads.resume(row.taskId, failedOnly = true)
                    DownloadAction.CANCEL -> downloads.cancel(row.taskId)
                    DownloadAction.DELETE_FILES -> downloads.deleteLocal(row.bookId)
                    DownloadAction.OPEN -> {
                        if (!validTarget(row.bookId, row.sourceId)) { notice("本书或下载书源已不可用，请查看书籍详情"); return@launch }
                        eventChannel.send(DownloadsEvent.OpenReader(DownloadReadTarget(row.bookId, row.sourceId)))
                    }
                }
                if (action != DownloadAction.OPEN) notice(when (action) {
                    DownloadAction.PAUSE -> "已请求暂停"
                    DownloadAction.CONTINUE -> "已请求继续，等待系统调度"
                    DownloadAction.RETRY_FAILED -> "已请求重试失败章节，等待系统调度"
                    DownloadAction.CANCEL -> "已请求取消，已保存正文仍保留"
                    DownloadAction.DELETE_FILES -> "本书所有书源的本地正文已删除"
                    DownloadAction.OPEN -> ""
                })
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("${action.label.removeSuffix("任务").removeSuffix("本书本地正文")}失败，请重试") }
            finally { mutableState.update { it.copy(busyTaskIds = it.busyTaskIds - row.taskId) } }
        }
    }

    fun requestStart(bookId: String, sourceId: String) {
        if (mutableState.value.startBusy || mutableState.value.startRequest != null) return
        mutableState.update { it.copy(startBusy = true) }
        viewModelScope.launch {
            try {
                if (!validTarget(bookId, sourceId)) { notice("本书或所选书源已不可用，请重新选择"); return@launch }
                val book = books.getBook(bookId) ?: return@launch
                val names = catalog.sourceNames().also { knownSourceNames = it }
                // DataStore may still be loading while the detail screen is already usable.
                // A saved default must win over the transient UI initial value.
                val default = settings.preferences.first()
                mutableState.update { it.copy(startRequest = DownloadStartRequest(bookId, sourceId,
                    book.bookName, names[sourceId] ?: "书源信息暂不可用", default.allowMetered)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("下载准备失败，请重试") }
            finally { mutableState.update { it.copy(startBusy = false) } }
        }
    }

    fun chooseStartNetwork(allowMetered: Boolean) {
        if (mutableState.value.startBusy) return
        mutableState.update { it.copy(startRequest = it.startRequest?.copy(allowMetered = allowMetered)) }
    }

    fun dismissStart() {
        if (!mutableState.value.startBusy) mutableState.update { it.copy(startRequest = null) }
    }

    /** The detail route supplies its live reading target here, never a captured source selection. */
    fun confirmStart(bookId: String?, sourceId: String?, permissionRequired: Boolean) {
        val request = mutableState.value.startRequest ?: return
        if (mutableState.value.startBusy) return
        if (request.bookId != bookId || request.sourceId != sourceId) {
            mutableState.update { it.copy(startRequest = null) }
            notice("所选书源正在变化，请重新确认下载")
            return
        }
        mutableState.update { it.copy(startBusy = true, startRequest = null) }
        viewModelScope.launch {
            try {
                if (!validTarget(request.bookId, request.sourceId)) {
                    notice("本书或所选书源已不可用，请重新选择")
                    mutableState.update { it.copy(startBusy = false) }
                    return@launch
                }
                if (permissionRequired) {
                    pendingPermission = request
                    mutableState.update { it.copy(notificationPermissionRequestPending = true) }
                } else start(request, notificationDenied = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) {
                notice("下载准备失败，请重试")
                mutableState.update { it.copy(startBusy = false) }
            }
        }
    }

    fun notificationPermissionResult(granted: Boolean) {
        val request = pendingPermission ?: return
        pendingPermission = null
        mutableState.update { it.copy(notificationPermissionRequestPending = false) }
        start(request, notificationDenied = !granted)
    }

    /** Called on the UI thread immediately before ActivityResultLauncher.launch. */
    fun claimNotificationPermissionRequest(): Boolean {
        if (pendingPermission == null || !mutableState.value.notificationPermissionRequestPending) return false
        mutableState.update { it.copy(notificationPermissionRequestPending = false) }
        return true
    }

    private fun start(request: DownloadStartRequest, notificationDenied: Boolean) {
        viewModelScope.launch {
            try {
                if (!validTarget(request.bookId, request.sourceId)) { notice("本书或所选书源已不可用，请重新选择"); return@launch }
                val result = downloads.start(request.bookId, request.sourceId, request.allowMetered)
                val message = when (result) {
                    is DownloadStart.Started -> "已创建下载，等待系统调度"
                    is DownloadStart.Existing -> "本书此书源已有任务，保留原网络设置；请到下载页查看或继续"
                    is DownloadStart.Conflict -> {
                        val sourceName = knownSourceNames[result.sourceId] ?: "其他书源"
                        "本书已有「$sourceName」的未完成任务，请到下载页管理后再选择书源"
                    }
                }
                notice(message + if (notificationDenied) "。通知可见性受限，可在系统设置中允许通知" else "")
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("创建下载失败，请重试") }
            finally { mutableState.update { it.copy(startBusy = false) } }
        }
    }

    private suspend fun validTarget(bookId: String, sourceId: String): Boolean =
        books.getBook(bookId)?.bookId == bookId && books.observeSources(bookId).first().any { it.bookId == bookId && it.sourceId == sourceId }

    fun setDefaultNetwork(allowMetered: Boolean) {
        viewModelScope.launch {
            try { settings.setAllowMetered(allowMetered) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { notice("下载网络设置保存失败，请重试") }
        }
    }

    fun clearNotice() { mutableState.update { it.copy(notice = null) } }
    private fun notice(message: String) { mutableState.update { it.copy(notice = message) } }
}
