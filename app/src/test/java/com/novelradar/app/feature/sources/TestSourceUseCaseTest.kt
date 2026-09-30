package com.novelradar.app.feature.sources

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceHealthStatus
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.search.SourceSearch
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Test

class TestSourceUseCaseTest {
    @Test fun probesOnlyOneSearchPageWithNeutralKeyword() = runTest {
        var keyword = ""
        var pagination = true
        val probe = TestSourceUseCase(
            searcher = SourceSearch { _, rule, value ->
                keyword = value
                pagination = rule.search?.pagination == true
                SourceResult.Success(emptyList())
            },
            clock = sequenceOf(100L, 260L).iterator()::next,
        )

        val health = probe.test(stored())

        assertThat(keyword).isEqualTo("测试")
        assertThat(pagination).isFalse()
        assertThat(health.status).isEqualTo(SourceHealthStatus.HEALTHY)
        assertThat(health.lastLatencyMillis).isEqualTo(160L)
        assertThat(health.lastSuccessAtEpochMillis).isEqualTo(260L)
    }

    @Test fun verificationHttpResponseHasItsOwnStatus() = runTest {
        val probe = TestSourceUseCase(SourceSearch { _, _, _ -> SourceResult.Failure(SourceFailure.Http(403)) })

        val health = probe.test(stored())

        assertThat(health.status).isEqualTo(SourceHealthStatus.VERIFICATION_REQUIRED)
        assertThat(health.lastFailure).isEqualTo(SourceFailure.VerificationRequired)
    }

    @Test fun probeStopsAfterEightSeconds() = runTest {
        val probe = TestSourceUseCase(SourceSearch { _, _, _ ->
            delay(9_000)
            SourceResult.Success(emptyList())
        })

        val health = probe.test(stored())

        assertThat(health.status).isEqualTo(SourceHealthStatus.FAILED)
        assertThat(health.lastFailure).isEqualTo(SourceFailure.Timeout)
    }

    private fun stored() = StoredSourceRule(
        sourceId = "test-source",
        rawJson = """{"id":"test-source","url":"https://example.org/","name":"测试源",
            "search":{"url":"https://example.org/search?q=%s","method":"GET","result":".book","bookName":"a",
              "pagination":true,"nextPage":"a.next"},"toc":{"item":"#toc a"},
            "chapter":{"title":"h1","content":"#content"}}""",
    )
}
