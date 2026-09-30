package com.novelradar.app.domain.search

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceHealthStatus
import org.junit.Test

class SearchResultMergerTest {
    private val merger = SearchResultMerger()

    @Test fun punctuationVariantsOfSameBookAndAuthorMerge() {
        val results = merger.merge("斗破苍穹", listOf(hit("a", "斗破·苍穹", "天蚕土豆"), hit("b", "斗破苍穹", "天蚕土豆")))

        assertThat(results).hasSize(1)
        assertThat(results.single().sources.map { it.sourceId }).containsExactly("a", "b")
    }

    @Test fun sameTitleWithDifferentNonblankAuthorsNeverMerges() {
        val results = merger.merge("斗破苍穹", listOf(hit("a", "斗破苍穹", "作者甲"), hit("b", "斗破苍穹", "作者乙")))

        assertThat(results).hasSize(2)
    }

    @Test fun exactTitleRanksBeforePrefixAndFuzzyMatches() {
        val results = merger.merge("斗破苍穹", listOf(
            hit("c", "斗破苍穹传", "丙"),
            hit("b", "斗破苍穹", "乙"),
            hit("a", "斗破苍穹前传", "甲"),
        ))

        assertThat(results.first().displayName).isEqualTo("斗破苍穹")
        assertThat(results.drop(1).map { it.displayName }).containsExactly("斗破苍穹传", "斗破苍穹前传")
    }

    @Test fun inputOrderDoesNotChangeResultOrderOrBestSource() {
        val hits = listOf(hit("z", "星河", "同作者"), hit("a", "星河", "同作者"), hit("m", "星河二", "同作者"))

        assertThat(merger.merge("星河", hits)).isEqualTo(merger.merge("星河", hits.reversed()))
    }

    @Test fun healthierAndMoreRecentlySuccessfulSourceIsPreferredWithinGroup() {
        val hits = listOf(hit("slow", "星河", "作者"), hit("healthy", "星河", "作者"))
        val health = mapOf(
            "slow" to SourceHealth(SourceHealthStatus.FAILED, lastSuccessAtEpochMillis = 100L),
            "healthy" to SourceHealth(SourceHealthStatus.HEALTHY, lastSuccessAtEpochMillis = 200L),
        )

        assertThat(merger.merge("星河", hits, health).single().bestHit.sourceId).isEqualTo("healthy")
    }

    private fun hit(source: String, title: String, author: String) = SearchHit(
        sourceId = source,
        bookName = title,
        author = author,
        detailUrl = "https://example.org/$source/$title",
    )
}
