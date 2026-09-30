package com.novelradar.app.feature.sources

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.source.importer.ImportResult
import com.novelradar.app.source.rule.RuleJsonParser
import com.novelradar.app.source.rule.RuleParseResult
import java.net.URI
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class SourceManagerViewModel(
    private val repository: SourceRuleRepository,
    private val testSource: suspend (StoredSourceRule) -> SourceHealth,
    private val importAction: suspend (String) -> ImportResult,
    private val cleartextAvailable: (String) -> Boolean,
    private val parser: RuleJsonParser = RuleJsonParser(),
) : ViewModel() {
    private val mutableState = MutableStateFlow(SourceManagerUiState())
    val uiState: StateFlow<SourceManagerUiState> = mutableState.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceManagerUiState(),
    )
    private val eventChannel = Channel<SourceManagerEvent>(Channel.BUFFERED)
    val events: Flow<SourceManagerEvent> = eventChannel.receiveAsFlow()
    private var currentRules: Map<String, StoredSourceRule> = emptyMap()

    init {
        viewModelScope.launch {
            repository.observeAll().collect { rules ->
                currentRules = rules.associateBy { it.sourceId }
                mutableState.update { it.copy(rows = rules.map(::rowState)) }
            }
        }
    }

    fun requestImport() { eventChannel.trySend(SourceManagerEvent.OpenDocument) }

    fun onImportSelected(uri: String) {
        viewModelScope.launch {
            try {
                when (val result = importAction(uri)) {
                    is ImportResult.Imported -> mutableState.update { it.copy(
                        importIssues = emptyList(),
                        notice = "已导入 ${result.added} 条，更新 ${result.updated} 条" +
                            if (result.disabledHttp > 0) "；${result.disabledHttp} 条 HTTP 书源保持关闭" else "",
                    ) }
                    is ImportResult.Rejected -> mutableState.update { it.copy(
                        importIssues = result.issues, notice = "导入失败，请检查规则字段",
                    ) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = "导入失败，请重试") }
            }
        }
    }

    fun requestToggle(sourceId: String, enabled: Boolean) {
        val rule = currentRules[sourceId] ?: return
        if (enabled && rowState(rule).scheme == "HTTP") {
            mutableState.update { it.copy(pendingHttpSourceId = sourceId) }
            return
        }
        viewModelScope.launch {
            try {
                repository.setEnabled(sourceId, enabled)
                if (!enabled && rowState(rule).scheme == "HTTP") repository.setCleartextAllowed(sourceId, false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = "无法更新书源开关") }
            }
        }
    }

    fun cancelHttpEnable() { mutableState.update { it.copy(pendingHttpSourceId = null) } }

    fun confirmHttpEnable() {
        val sourceId = mutableState.value.pendingHttpSourceId ?: return
        mutableState.update { it.copy(pendingHttpSourceId = null) }
        val rule = currentRules[sourceId] ?: return
        val host = rowState(rule).host
        val allowed = try { host.isNotBlank() && cleartextAvailable(host) } catch (_: Exception) { false }
        if (!allowed) {
            mutableState.update { it.copy(notice = "当前 Android 网络策略不允许 HTTP，此书源仍保持关闭") }
            return
        }
        viewModelScope.launch {
            try {
                repository.setCleartextAllowed(sourceId, true)
                repository.setEnabled(sourceId, true)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutableState.update { it.copy(notice = "无法启用此 HTTP 书源") }
            }
        }
    }

    fun deleteUserRule(sourceId: String) {
        if (currentRules[sourceId]?.builtIn != false) {
            mutableState.update { it.copy(notice = "内置书源不能删除") }
            return
        }
        viewModelScope.launch {
            try { repository.deleteUserRule(sourceId) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { mutableState.update { it.copy(notice = "删除书源失败") } }
        }
    }

    fun testOne(sourceId: String) {
        val rule = currentRules[sourceId] ?: return
        viewModelScope.launch { runProbe(rule) }
    }

    fun testAll() {
        viewModelScope.launch {
            val semaphore = Semaphore(3)
            repository.enabled().map { rule ->
                async { semaphore.withPermit { runProbe(rule) } }
            }.awaitAll()
        }
    }

    private suspend fun runProbe(rule: StoredSourceRule) {
        mutableState.update { it.copy(testingSourceIds = it.testingSourceIds + rule.sourceId) }
        try {
            repository.updateHealth(rule.sourceId, testSource(rule))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableState.update { it.copy(notice = "${rowState(rule).name} 检测失败") }
        } finally {
            mutableState.update { it.copy(testingSourceIds = it.testingSourceIds - rule.sourceId) }
        }
    }

    private fun rowState(rule: StoredSourceRule): SourceRowState {
        val parsed = (parser.parse(rule.rawJson) as? RuleParseResult.Success)?.rules?.singleOrNull()
        val uri = runCatching { URI(parsed?.url) }.getOrNull()
        return SourceRowState(
            sourceId = rule.sourceId,
            name = parsed?.name?.takeIf(String::isNotBlank) ?: rule.sourceId,
            scheme = uri?.scheme?.uppercase(Locale.ROOT) ?: "UNKNOWN",
            host = uri?.host.orEmpty(),
            enabled = rule.enabled,
            builtIn = rule.builtIn,
            allowCleartext = rule.allowCleartext,
            health = rule.health,
        )
    }
}
