package com.novelradar.app.domain.search

import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.domain.repository.asExecutableRule
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.rule.RuleJsonParser
import com.novelradar.app.source.rule.RuleParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun interface SourceSearch {
    suspend fun search(source: StoredSourceRule, rule: RuleDto, keyword: String): SourceResult<List<SearchHit>>
}

class AggregateSearchUseCase(
    private val repository: SourceRuleRepository,
    private val searcher: SourceSearch,
    private val parser: RuleJsonParser = RuleJsonParser(),
    private val merger: SearchResultMerger = SearchResultMerger(),
) {
    fun search(query: String): Flow<SearchSnapshot> = channelFlow {
        val keyword = query.trim()
        if (keyword.isBlank()) {
            send(SearchSnapshot(query = keyword))
            return@channelFlow
        }
        val sources = repository.enabled()
        val health = sources.associate { it.sourceId to it.health }
        val semaphore = Semaphore(MAX_ACTIVE_SOURCES)
        val lock = Mutex()
        val hits = mutableListOf<SearchHit>()
        val active = linkedSetOf<String>()
        val failures = linkedMapOf<String, SourceFailure>()
        var completed = 0
        send(SearchSnapshot(query = keyword, totalSources = sources.size))

        suspend fun publish(event: SearchEvent, newHits: List<SearchHit> = emptyList()) {
            lock.lock()
            try {
                when (event) {
                    is SearchEvent.SourceStarted -> active += event.sourceId
                    is SearchEvent.SourceSucceeded -> {
                        active -= event.sourceId
                        hits += newHits
                        completed++
                    }
                    is SearchEvent.SourceFailed -> {
                        active -= event.sourceId
                        failures[event.sourceId] = event.failure
                        completed++
                    }
                }
                send(SearchSnapshot(
                    query = keyword,
                    results = merger.merge(keyword, hits.toList(), health),
                    completedSources = completed,
                    totalSources = sources.size,
                    activeSources = active.toSet(),
                    failures = failures.toMap(),
                    lastEvent = event,
                ))
            } finally {
                lock.unlock()
            }
        }

        sources.forEach { stored ->
            launch {
                semaphore.withPermit {
                    publish(SearchEvent.SourceStarted(stored.sourceId))
                    val executableRule = try {
                        val parsed = parser.parse(stored.rawJson) as? RuleParseResult.Success
                        val rule = parsed?.rules?.singleOrNull()
                            ?: throw IllegalArgumentException("Invalid stored source rule")
                        stored.asExecutableRule(rule)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        null
                    }
                    val result = if (executableRule == null) {
                        SourceResult.Failure(SourceFailure.UnsafeRule("Stored source rule is invalid"))
                    } else try {
                        searcher.search(stored, executableRule, keyword)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        SourceResult.Failure(SourceFailure.Unexpected("Source search failed"))
                    }
                    when (result) {
                        is SourceResult.Success -> publish(SearchEvent.SourceSucceeded(stored.sourceId, result.value.size), result.value)
                        is SourceResult.Failure -> publish(SearchEvent.SourceFailed(stored.sourceId, result.error))
                    }
                }
            }
        }
    }

    private companion object {
        const val MAX_ACTIVE_SOURCES = 6
    }
}
