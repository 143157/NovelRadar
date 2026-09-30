package com.novelradar.app.feature.sources

import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceHealthStatus
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.asExecutableRule
import com.novelradar.app.domain.search.SourceSearch
import com.novelradar.app.source.rule.RuleJsonParser
import com.novelradar.app.source.rule.RuleParseResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/** A fixed neutral keyword and disabled pagination keep health probes to one search page. */
class TestSourceUseCase(
    private val searcher: SourceSearch,
    private val parser: RuleJsonParser = RuleJsonParser(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun test(stored: StoredSourceRule): SourceHealth {
        val started = clock()
        val result = try {
            val parsed = parser.parse(stored.rawJson) as? RuleParseResult.Success
            val raw = parsed?.rules?.singleOrNull() ?: throw IllegalArgumentException("Invalid source rule")
            val search = raw.search ?: throw IllegalArgumentException("No search rule")
            val executable = stored.asExecutableRule(raw).copy(search = search.copy(pagination = false, nextPage = null))
            withTimeoutOrNull(PROBE_TIMEOUT_MILLIS) {
                searcher.search(stored, executable, PROBE_KEYWORD)
            } ?: SourceResult.Failure(SourceFailure.Timeout)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            SourceResult.Failure(SourceFailure.UnsafeRule("Source rule cannot be tested"))
        } catch (_: Exception) {
            SourceResult.Failure(SourceFailure.Unexpected("Source probe failed"))
        }
        val finished = clock()
        val elapsed = (finished - started).coerceAtLeast(0)
        return when (result) {
            is SourceResult.Success -> SourceHealth(
                status = if (elapsed > SLOW_THRESHOLD_MILLIS) SourceHealthStatus.SLOW else SourceHealthStatus.HEALTHY,
                lastLatencyMillis = elapsed,
                lastSuccessAtEpochMillis = finished,
            )
            is SourceResult.Failure -> {
                val failure = when (val error = result.error) {
                    is SourceFailure.Http -> if (error.code == 403 || error.code == 429) SourceFailure.VerificationRequired else error
                    else -> error
                }
                SourceHealth(
                    status = if (failure == SourceFailure.VerificationRequired) SourceHealthStatus.VERIFICATION_REQUIRED
                        else SourceHealthStatus.FAILED,
                    lastLatencyMillis = elapsed,
                    lastSuccessAtEpochMillis = stored.health.lastSuccessAtEpochMillis,
                    lastFailure = failure,
                )
            }
        }
    }

    private companion object {
        const val PROBE_KEYWORD = "测试"
        const val PROBE_TIMEOUT_MILLIS = 8_000L
        const val SLOW_THRESHOLD_MILLIS = 4_000L
    }
}
