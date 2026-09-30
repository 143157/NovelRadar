package com.novelradar.app.feature.sources

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceHealthStatus
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.ImportSummary
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.source.importer.ImportResult
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.rule.RuleIssue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class SourceManagerViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun httpEnableRequiresConfirmationAndPlatformPermission() = runTest {
        val repository = FakeRules(listOf(stored("legacy", "http://legacy.example/", enabled = false)))
        val viewModel = SourceManagerViewModel(repository, { SourceHealth() }, { ImportResult.Imported(0, 0, 0) },
            cleartextAvailable = { false })
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.requestToggle("legacy", true)
        advanceUntilIdle()
        assertThat(repository.rows.value.single().enabled).isFalse()
        assertThat(viewModel.uiState.value.pendingHttpSourceId).isEqualTo("legacy")
        viewModel.confirmHttpEnable()
        advanceUntilIdle()
        assertThat(repository.rows.value.single().enabled).isFalse()
        assertThat(viewModel.uiState.value.notice).contains("Android")
        collector.cancel()
    }

    @Test fun confirmedHttpAndHttpsTogglesPersistWhenAllowed() = runTest {
        val repository = FakeRules(listOf(stored("legacy", "http://legacy.example/", enabled = false),
            stored("secure", "https://secure.example/", enabled = false)))
        val viewModel = SourceManagerViewModel(repository, { SourceHealth() }, { ImportResult.Imported(0, 0, 0) },
            cleartextAvailable = { true })
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.requestToggle("secure", true)
        viewModel.requestToggle("legacy", true)
        advanceUntilIdle()
        assertThat(repository.rows.value.first { it.sourceId == "secure" }.enabled).isTrue()
        assertThat(repository.rows.value.first { it.sourceId == "legacy" }.enabled).isFalse()
        viewModel.confirmHttpEnable()
        advanceUntilIdle()
        assertThat(repository.rows.value.first { it.sourceId == "legacy" }.enabled).isTrue()
        assertThat(repository.rows.value.first { it.sourceId == "legacy" }.allowCleartext).isTrue()
        collector.cancel()
    }

    @Test fun onlyImportedRulesCanBeDeleted() = runTest {
        val repository = FakeRules(listOf(stored("built", "https://a.example/", builtIn = true),
            stored("custom", "https://b.example/")))
        val viewModel = SourceManagerViewModel(repository, { SourceHealth() }, { ImportResult.Imported(0, 0, 0) }, { false })
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.deleteUserRule("built")
        viewModel.deleteUserRule("custom")
        advanceUntilIdle()

        assertThat(repository.rows.value.map { it.sourceId }).containsExactly("built")
        collector.cancel()
    }

    @Test fun testAllLimitsConcurrencyToThreeAndStoresHealth() = runTest {
        val repository = FakeRules((1..7).map { stored("source-$it", "https://example.org/") })
        var active = 0
        var peak = 0
        val viewModel = SourceManagerViewModel(repository, {
            active++
            peak = maxOf(peak, active)
            delay(10)
            active--
            SourceHealth(SourceHealthStatus.HEALTHY, lastLatencyMillis = 10L)
        }, { ImportResult.Imported(0, 0, 0) }, { false })
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        advanceUntilIdle()

        viewModel.testAll()
        advanceUntilIdle()

        assertThat(peak).isEqualTo(3)
        assertThat(repository.rows.value.map { it.health.status }.distinct()).containsExactly(SourceHealthStatus.HEALTHY)
        collector.cancel()
    }

    @Test fun importIssuesKeepFieldPathsAndLauncherEventIsEmitted() = runTest {
        val repository = FakeRules(emptyList())
        val viewModel = SourceManagerViewModel(repository, { SourceHealth() }, {
            ImportResult.Rejected(listOf(RuleIssue("[1].search.url", "invalid URL")))
        }, { false })
        val collector = backgroundScope.launch { viewModel.uiState.collect {} }
        val event = backgroundScope.async { viewModel.events.first() }

        viewModel.requestImport()
        advanceUntilIdle()
        assertThat(event.await()).isEqualTo(SourceManagerEvent.OpenDocument)
        viewModel.onImportSelected("content://rules.json")
        advanceUntilIdle()
        assertThat(viewModel.uiState.value.importIssues.single().path).isEqualTo("[1].search.url")
        collector.cancel()
    }

    private fun stored(id: String, url: String, enabled: Boolean = true, builtIn: Boolean = false) = StoredSourceRule(
        sourceId = id,
        rawJson = """{"id":"$id","url":"$url","name":"$id","search":{"url":"${url}search?q=%s","method":"GET","result":".book","bookName":"a"},"toc":{"item":"#toc a"},"chapter":{"title":"h1","content":"#content"}}""",
        enabled = enabled,
        builtIn = builtIn,
    )

    private class FakeRules(initial: List<StoredSourceRule>) : SourceRuleRepository {
        val rows = MutableStateFlow(initial)
        override fun observeAll(): Flow<List<StoredSourceRule>> = rows
        override suspend fun enabled(): List<StoredSourceRule> = rows.value.filter { it.enabled }
        override suspend fun upsertImported(rules: List<RuleDto>) = ImportSummary(0, 0, 0)
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) {
            rows.value = rows.value.map { if (it.sourceId == sourceId) it.copy(enabled = enabled) else it }
        }
        override suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean) {
            rows.value = rows.value.map { if (it.sourceId == sourceId) it.copy(allowCleartext = allowed) else it }
        }
        override suspend fun updateHealth(sourceId: String, health: SourceHealth) {
            rows.value = rows.value.map { if (it.sourceId == sourceId) it.copy(health = health) else it }
        }
        override suspend fun deleteUserRule(sourceId: String) {
            rows.value = rows.value.filterNot { it.sourceId == sourceId && !it.builtIn }
        }
    }

    class MainDispatcherRule : TestWatcher() {
        private val dispatcher = StandardTestDispatcher()
        override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }
}
