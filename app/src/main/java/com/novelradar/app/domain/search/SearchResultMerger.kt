package com.novelradar.app.domain.search

import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceHealthStatus
import com.novelradar.app.domain.model.normalizeIdentityPart
import java.util.Locale

/** Deterministic matching and ranking, independent of network completion order. */
class SearchResultMerger {
    fun merge(
        query: String,
        hits: List<SearchHit>,
        health: Map<String, SourceHealth> = emptyMap(),
    ): List<AggregatedBookResult> {
        val normalizedQuery = key(query)
        val groups = mutableListOf<Group>()
        val groupsByName = mutableMapOf<String, MutableList<Group>>()
        val groupsByExactKey = mutableMapOf<Pair<String, String>, Group>()
        hits.map { KeyedHit(it, key(it.bookName), key(it.author)) }
            .sortedWith(compareBy<KeyedHit>({ it.name }, { it.author }, { it.hit.sourceId }, { it.hit.detailUrl }))
            .forEach { item ->
                val hit = item.hit
                val name = item.name
                if (name.isBlank()) return@forEach
                val author = item.author
                val sameName = groupsByName.getOrPut(name) { mutableListOf() }
                val exact = groupsByExactKey[name to author]
                val group = exact ?: when {
                    author.isNotBlank() && sameName.size == 1 -> groupsByExactKey[name to ""]
                    sameName.size == 1 -> sameName.single()
                    else -> null
                } ?: Group(name, author).also { groups += it; sameName += it }
                if (group.author.isBlank() && author.isNotBlank()) {
                    groupsByExactKey.remove(name to "")
                    group.author = author
                }
                groupsByExactKey[name to group.author] = group
                if (group.hits.none { it.sourceId == hit.sourceId && it.detailUrl == hit.detailUrl }) {
                    group.hits += hit
                }
            }
        return groups.map { group ->
            val sortedSources = group.hits.sortedWith(
                compareBy<SearchHit> { titleRank(normalizedQuery, key(it.bookName)) }
                    .thenBy { healthRank(health[it.sourceId]?.status) }
                    .thenByDescending { health[it.sourceId]?.lastSuccessAtEpochMillis ?: Long.MIN_VALUE }
                    .thenBy { it.sourceId }
                    .thenBy { it.detailUrl },
            )
            val best = sortedSources.first()
            val result = AggregatedBookResult(
                bookKey = "${group.name}|${group.author}",
                displayName = best.bookName,
                author = sortedSources.firstOrNull { it.author.isNotBlank() }?.author.orEmpty(),
                sources = sortedSources,
                bestHit = best,
            )
            val title = key(result.displayName)
            RankedResult(
                result = result,
                titleRank = titleRank(normalizedQuery, title),
                distance = editDistance(normalizedQuery, title),
                healthRank = healthRank(health[best.sourceId]?.status),
                lastSuccess = health[best.sourceId]?.lastSuccessAtEpochMillis ?: Long.MIN_VALUE,
                distinctSources = result.sources.map(SearchHit::sourceId).distinct().size,
            )
        }.sortedWith(
            compareBy<RankedResult> { it.titleRank }
                .thenBy { it.distance }
                .thenBy { it.healthRank }
                .thenByDescending { it.lastSuccess }
                .thenByDescending { it.distinctSources }
                .thenBy { it.result.bookKey },
        ).map { it.result }
    }

    private fun key(text: String): String = normalizeIdentityPart(text).lowercase(Locale.ROOT)

    private fun titleRank(query: String, title: String): Int = when {
        query.isNotEmpty() && title == query -> 0
        query.isNotEmpty() && title.startsWith(query) -> 1
        else -> 2
    }

    private fun healthRank(status: SourceHealthStatus?): Int = when (status) {
        SourceHealthStatus.HEALTHY -> 0
        SourceHealthStatus.SLOW -> 1
        null, SourceHealthStatus.UNKNOWN -> 2
        SourceHealthStatus.FAILED -> 3
        SourceHealthStatus.VERIFICATION_REQUIRED -> 4
    }

    /** Bounded Levenshtein score; distant names share one tier and use stable tie-breakers. */
    private fun editDistance(left: String, right: String): Int {
        val a = left.take(MAX_RANK_CHARS)
        val b = right.take(MAX_RANK_CHARS)
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > MAX_DISTANCE) return MAX_DISTANCE + 1
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in a.indices) {
            current[0] = i + 1
            for (j in b.indices) {
                current[j + 1] = minOf(
                    previous[j + 1] + 1,
                    current[j] + 1,
                    previous[j] + if (a[i] == b[j]) 0 else 1,
                )
            }
            if (current.min() > MAX_DISTANCE) return MAX_DISTANCE + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length].coerceAtMost(MAX_DISTANCE + 1)
    }

    private class Group(val name: String, var author: String, val hits: MutableList<SearchHit> = mutableListOf())
    private data class KeyedHit(val hit: SearchHit, val name: String, val author: String)
    private data class RankedResult(
        val result: AggregatedBookResult,
        val titleRank: Int,
        val distance: Int,
        val healthRank: Int,
        val lastSuccess: Long,
        val distinctSources: Int,
    )

    private companion object {
        const val MAX_RANK_CHARS = 48
        const val MAX_DISTANCE = 4
    }
}
