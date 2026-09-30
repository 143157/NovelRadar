package com.novelradar.app.domain.search

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.ImportSummary
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.source.rule.RuleDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AggregateSearchUseCaseTest {
    @Test fun oneSourceFailureDoesNotDiscardOtherResult() = runTest {
        val repository = FakeRules(listOf(stored("ok"), stored("bad")))
        val useCase = AggregateSearchUseCase(repository, SourceSearch { stored, _, _ ->
            if (stored.sourceId == "bad") SourceResult.Failure(SourceFailure.Timeout)
            else SourceResult.Success(listOf(hit("ok")))
        })

        val snapshots = useCase.search("星河").toList()
        val last = snapshots.last()
        assertThat(last.completedSources).isEqualTo(2)
        assertThat(last.totalSources).isEqualTo(2)
        assertThat(last.results).hasSize(1)
        assertThat(last.failures.keys).containsExactly("bad")
        assertThat(snapshots.any { it.completedSources == 1 }).isTrue()
    }

    @Test fun noMoreThanSixSourceCallsRunAtOnce() = runTest {
        val repository = FakeRules((1..12).map { stored("source-$it") })
        var active = 0
        var peak = 0
        val useCase = AggregateSearchUseCase(repository, SourceSearch { _, _, _ ->
            active++
            peak = maxOf(peak, active)
            delay(10)
            active--
            SourceResult.Success(emptyList())
        })

        val last = useCase.search("星河").toList().last()

        assertThat(peak).isEqualTo(6)
        assertThat(last.completedSources).isEqualTo(12)
    }

    @Test fun collectorCancellationCancelsActiveSourceCalls() = runTest {
        val repository = FakeRules(listOf(stored("one")))
        var cancelled = false
        val useCase = AggregateSearchUseCase(repository, SourceSearch { _, _, _ ->
            try {
                delay(100_000)
                SourceResult.Success(emptyList())
            } catch (error: CancellationException) {
                cancelled = true
                throw error
            }
        })

        useCase.search("星河").take(2).toList()

        assertThat(cancelled).isTrue()
    }

    @Test fun legacyDisabledJsonUsesPersistedEnabledChoice() = runTest {
        val rule = stored("legacy", disabledInJson = true)
        val repository = FakeRules(listOf(rule))
        var called = false
        val useCase = AggregateSearchUseCase(repository, SourceSearch { _, parsed, _ ->
            called = true
            assertThat(parsed.disabled).isFalse()
            SourceResult.Success(listOf(hit("legacy")))
        })

        val last = useCase.search("星河").toList().last()

        assertThat(called).isTrue()
        assertThat(last.results).hasSize(1)
    }

    @Test fun unexpectedSourceExceptionIsIsolatedAndClassified() = runTest {
        val repository = FakeRules(listOf(stored("throwing"), stored("ok")))
        val useCase = AggregateSearchUseCase(repository, SourceSearch { stored, _, _ ->
            if (stored.sourceId == "throwing") throw IllegalStateException("network callback failed")
            SourceResult.Success(listOf(hit("ok")))
        })

        val last = useCase.search("星河").toList().last()

        assertThat(last.results).hasSize(1)
        assertThat(last.failures["throwing"]).isInstanceOf(SourceFailure.Unexpected::class.java)
    }

    private fun stored(id: String, disabledInJson: Boolean = false): StoredSourceRule {
        val json = """{"id":"$id","url":"https://example.org/","name":"$id","disabled":$disabledInJson,
            "search":{"url":"https://example.org/search?q=%s","method":"GET","result":".book","bookName":"a"},
            "toc":{"item":"#toc a"},"chapter":{"title":"h1","content":"#content"}}"""
        return StoredSourceRule(id, json)
    }

    private fun hit(source: String) = SearchHit(source, "星河", "作者", "https://example.org/$source/book")

    private class FakeRules(rules: List<StoredSourceRule>) : SourceRuleRepository {
        private val rows = MutableStateFlow(rules)
        override fun observeAll(): Flow<List<StoredSourceRule>> = rows
        override suspend fun enabled(): List<StoredSourceRule> = rows.value.filter { it.enabled }
        override suspend fun upsertImported(rules: List<RuleDto>) = ImportSummary(0, 0, 0)
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean) = Unit
        override suspend fun updateHealth(sourceId: String, health: SourceHealth) = Unit
        override suspend fun deleteUserRule(sourceId: String) = Unit
    }
}
