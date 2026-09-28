package com.novelradar.app.source.script

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.system.measureTimeMillis

class QuickJsScriptExecutorTest {
    private val executor: ScriptExecutor = QuickJsScriptExecutor()

    @Test
    fun replacesInputAndPreservesUnicodeQuotesAndNewlines() = runBlocking {
        val input = "第一章 \"你好\"\n猫😀"

        assertThat(executor.transform(input, "r = r.replace('猫', '狗')"))
            .isEqualTo(SourceResult.Success("第一章 \"你好\"\n狗😀"))
    }

    @Test
    fun scriptHasNoHostIoBindings() = runBlocking {
        val result = executor.transform("ignored", "r = [typeof Java, typeof require, typeof fetch, typeof std, typeof os].join(',')")

        assertThat(result).isEqualTo(SourceResult.Success("undefined,undefined,undefined,undefined,undefined"))
    }

    @Test
    fun infiniteLoopIsInterruptedWithinTwoSeconds() = runBlocking {
        var result: SourceResult<String>? = null
        val elapsed = measureTimeMillis {
            result = executor.transform("input", "while (true) {}")
        }

        assertThat(elapsed).isLessThan(2_000L)
        assertThat(result).isInstanceOf(SourceResult.Failure::class.java)
        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
    }

    @Test
    fun rejectsOversizedInputBeforeCreatingRuntime() = runBlocking {
        val result = executor.transform("x".repeat(2 * 1024 * 1024 + 1), "r = 'ok'")

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.UnsafeRule("input exceeds 2 MiB UTF-8")))
    }

    @Test
    fun rejectsOversizedScriptBeforeCreatingRuntime() = runBlocking {
        val result = executor.transform("input", " ".repeat(16 * 1024 + 1))

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.UnsafeRule("script exceeds 16 KiB UTF-8")))
    }

    @Test
    fun rejectsOversizedOutput() = runBlocking {
        val result = executor.transform("input", "r = 'x'.repeat(4 * 1024 * 1024 + 1)")

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.UnsafeRule("output exceeds 4 MiB UTF-8")))
    }

    @Test
    fun jsErrorNameCannotConstructHostClass() = runBlocking {
        HostConstructor.constructions.set(0)
        val hostileName = HostConstructor::class.java.name

        val result = executor.transform("input", "throw Object.assign(new Error('probe'), {name:'$hostileName'})")

        assertThat(result).isInstanceOf(SourceResult.Failure::class.java)
        assertThat(HostConstructor.constructions.get()).isEqualTo(0)
    }

    class HostConstructor(@Suppress("UNUSED_PARAMETER") ignored: String) {
        companion object {
            val constructions = AtomicInteger()
        }

        init {
            constructions.incrementAndGet()
        }
    }
}
