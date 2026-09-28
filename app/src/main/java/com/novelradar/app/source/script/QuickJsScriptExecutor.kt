package com.novelradar.app.source.script

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.QuickJsException
import com.dokar.quickjs.QuickJsInterruptedException
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.squareup.moshi.Moshi
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers

/** One fresh, binding-free native runtime per transform; imported scripts cannot retain state. */
class QuickJsScriptExecutor : ScriptExecutor {
    override suspend fun transform(input: String, script: String): SourceResult<String> {
        if (input.exceedsUtf8Limit(MAX_INPUT_BYTES)) {
            return SourceResult.Failure(SourceFailure.UnsafeRule("input exceeds 2 MiB UTF-8"))
        }
        if (script.exceedsUtf8Limit(MAX_SCRIPT_BYTES)) {
            return SourceResult.Failure(SourceFailure.UnsafeRule("script exceeds 16 KiB UTF-8"))
        }

        val code = "(() => { let r = ${stringAdapter.toJson(input)}; $script; return String(r); })()"
        val runtime = try {
            QuickJs.create(Dispatchers.Default)
        } catch (error: QuickJsException) {
            return SourceResult.Failure(SourceFailure.UnsafeRule("script runtime unavailable"))
        }
        return try {
            runtime.memoryLimit = RUNTIME_MEMORY_BYTES
            runtime.maxStackSize = MAX_STACK_BYTES
            runtime.evaluationTimeoutMillis = EVALUATION_TIMEOUT_MILLIS
            val output = runtime.evaluate<String>(code, filename = "source-rule.js")
            if (output.exceedsUtf8Limit(MAX_OUTPUT_BYTES)) {
                SourceResult.Failure(SourceFailure.UnsafeRule("output exceeds 4 MiB UTF-8"))
            } else {
                SourceResult.Success(output)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: QuickJsInterruptedException) {
            SourceResult.Failure(SourceFailure.UnsafeRule("script exceeded 300 ms"))
        } catch (error: QuickJsException) {
            SourceResult.Failure(SourceFailure.UnsafeRule("script execution failed"))
        } finally {
            runtime.close()
        }
    }

    private fun String.exceedsUtf8Limit(limit: Int): Boolean =
        length > limit || toByteArray(Charsets.UTF_8).size > limit

    private companion object {
        const val MAX_INPUT_BYTES = 2 * 1024 * 1024
        const val MAX_SCRIPT_BYTES = 16 * 1024
        const val MAX_OUTPUT_BYTES = 4 * 1024 * 1024
        const val RUNTIME_MEMORY_BYTES = 32L * 1024 * 1024
        const val MAX_STACK_BYTES = 512L * 1024
        const val EVALUATION_TIMEOUT_MILLIS = 300L

        val stringAdapter = Moshi.Builder().build().adapter(String::class.java)
    }
}
