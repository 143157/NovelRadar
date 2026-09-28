package com.novelradar.app.source.http

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Request
import okhttp3.Call
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.FormBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test

class SourceHttpClientTest {
    @Test fun followsThreeRedirectsAndExposesFinalUrlBytesAndContentType() = withServer { server ->
        repeat(3) { index -> server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/step${index + 1}")) }
        server.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=utf-8").setBody("书"))
        val result = runBlocking { withTimeout(3_000) { SourceHttpClient().execute("source-a", request(server.url("/start").toString()), allowCleartext = true) } }
        val page = (result as SourceResult.Success).value
        assertThat(page.finalUrl).isEqualTo(server.url("/step3").toString())
        assertThat(page.body.toString(Charsets.UTF_8)).isEqualTo("书")
        assertThat(page.contentType).isEqualTo("text/html; charset=utf-8")
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test fun redirectLimitAndLoopAreBounded() = withServer { server ->
        repeat(7) { index -> server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/step${index + 1}")) }
        val client = SourceHttpClient()
        val limited = runBlocking { withTimeout(3_000) { client.execute("source-a", request(server.url("/start").toString()), allowCleartext = true) } }
        assertThat(limited).isInstanceOf(SourceResult.Failure::class.java)
        assertThat(server.requestCount).isEqualTo(6)
    }

    @Test fun redirectLoopStopsBeforeRepeatingRequest() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/start"))
        val result = runBlocking { withTimeout(3_000) { SourceHttpClient().execute("source-a", request(server.url("/start").toString()), allowCleartext = true) } }
        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun postMayRedirectToSameUrlAsGetWithoutLoop() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(303).addHeader("Location", "/search"))
        server.enqueue(MockResponse().setBody("ok"))
        val initial = Request.Builder().url(server.url("/search")).post(FormBody.Builder().add("q", "book").build()).build()
        val result = runBlocking { withTimeout(3_000) { SourceHttpClient().execute("source-a", initial, allowCleartext = true) } }
        assertThat(result).isInstanceOf(SourceResult.Success::class.java)
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)!!.method).isEqualTo("POST")
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)!!.method).isEqualTo("GET")
    }

    @Test fun directClientAlsoRejectsNonGetPostMethods() = withServer { server ->
        val request = Request.Builder().url(server.url("/patch")).method("PATCH", FormBody.Builder().build()).build()
        val result = runBlocking { withTimeout(3_000) { SourceHttpClient().execute("source-a", request, allowCleartext = true) } }
        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun httpsToHttpRedirectIsRejectedWithCleartextDisabled() {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", "http://example.test/landing").build()
        }.build()
        val result = runBlocking { withTimeout(3_000) { SourceHttpClient(client).execute("source-a", request("https://example.test/start")) } }
        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
        assertThat(calls).isEqualTo(1)
    }

    @Test fun cookieJarIsSourceAndOriginScopedAndHonorsPath() = withServer { first ->
        MockWebServer().use { second ->
            second.start()
            val client = SourceHttpClient()
            first.enqueue(MockResponse().addHeader("Set-Cookie", "sid=one; Path=/private").setBody("ok"))
            runBlocking { withTimeout(3_000) { client.execute("source-a", request(first.url("/private/login").toString()), allowCleartext = true, initialCookies = "waf_sc=''; HMACCOUNT=''") } }
            first.enqueue(MockResponse().setBody("ok"))
            runBlocking { withTimeout(3_000) { client.execute("source-a", request(first.url("/private/read").toString()), allowCleartext = true) } }
            val sameSource = first.takeRequest(2, TimeUnit.SECONDS)!!
            val matchingPath = first.takeRequest(2, TimeUnit.SECONDS)!!
            assertThat(sameSource.getHeader("Cookie")).contains("HMACCOUNT")
            assertThat(matchingPath.getHeader("Cookie")).contains("sid=one")

            first.enqueue(MockResponse().setBody("ok"))
            runBlocking { withTimeout(3_000) { client.execute("source-b", request(first.url("/private/read").toString()), allowCleartext = true) } }
            assertThat(first.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie")).isNull()

            first.enqueue(MockResponse().setBody("ok"))
            runBlocking { withTimeout(3_000) { client.execute("source-a", request(first.url("/public").toString()), allowCleartext = true) } }
            assertThat(first.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie")).doesNotContain("sid=one")

            second.enqueue(MockResponse().setBody("ok"))
            runBlocking { withTimeout(3_000) { client.execute("source-a", request(second.url("/private/read").toString()), allowCleartext = true) } }
            assertThat(second.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie")).isNull()
        }
    }

    @Test fun redirectDoesNotForwardCookiesToOtherOrigin() = withServer { first ->
        MockWebServer().use { second ->
            second.start()
            first.enqueue(MockResponse().setResponseCode(302).addHeader("Location", second.url("/end")))
            second.enqueue(MockResponse().setBody("ok"))
            val result = runBlocking { withTimeout(3_000) { SourceHttpClient().execute("source-a", request(first.url("/start").toString()), allowCleartext = true, initialCookies = "sid=secret") } }
            assertThat(result).isInstanceOf(SourceResult.Success::class.java)
            assertThat(first.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie")).contains("sid=secret")
            assertThat(second.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie")).isNull()
        }
    }

    @Test fun rawCookieHeaderCannotBypassSourceCookiePartition() = withServer { server ->
        server.enqueue(MockResponse().setBody("ok"))
        val request = Request.Builder().url(server.url("/read")).header("Cookie", "stolen=other-source").build()
        val result = runBlocking { withTimeout(3_000) { SourceHttpClient().execute("source-b", request, allowCleartext = true) } }
        assertThat(result).isInstanceOf(SourceResult.Success::class.java)
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("Cookie")).isNull()
    }

    @Test fun timeoutAndHttpErrorsAreTyped() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(503).setBody("unavailable"))
        val client = SourceHttpClient(timeoutMillis = 100)
        val error = runBlocking { withTimeout(3_000) { client.execute("source-a", request(server.url("/error").toString()), allowCleartext = true) } }
        assertThat((error as SourceResult.Failure).error).isEqualTo(SourceFailure.Http(503))
        server.enqueue(MockResponse().setBodyDelay(500, TimeUnit.MILLISECONDS).setBody("late"))
        val timeout = runBlocking { withTimeout(3_000) { client.execute("source-a", request(server.url("/slow").toString()), allowCleartext = true) } }
        assertThat((timeout as SourceResult.Failure).error).isEqualTo(SourceFailure.Timeout)
    }

    @Test fun coroutineCancellationCancelsCallPromptly() = withServer { server ->
        server.enqueue(MockResponse().setBodyDelay(10, TimeUnit.SECONDS).setBody("late"))
        val failed = CountDownLatch(1)
        val cancelled = AtomicReference<Boolean>()
        val observed = OkHttpClient.Builder().eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: java.io.IOException) {
                cancelled.set(call.isCanceled())
                failed.countDown()
            }
        }).build()
        val client = SourceHttpClient(observed)
        runBlocking {
            val job = async(start = CoroutineStart.UNDISPATCHED) { client.execute("source-a", request(server.url("/slow").toString()), allowCleartext = true) }
            assertThat(server.takeRequest(2, TimeUnit.SECONDS)).isNotNull()
            job.cancel()
            try {
                withTimeout(2_000) { job.await() }
                throw AssertionError("CancellationException expected")
            } catch (_: CancellationException) {
                // Cancellation must remain cancellation, not a SourceFailure.
            }
            assertThat(failed.await(2, TimeUnit.SECONDS)).isTrue()
            assertThat(cancelled.get()).isTrue()
        }
    }

    @Test fun responseBodyOverLimitIsRejected() = withServer { server ->
        server.enqueue(MockResponse().setBody("123456"))
        val result = runBlocking { withTimeout(3_000) {
            SourceHttpClient(maxBodyBytes = 5).execute("source-a", request(server.url("/large").toString()), allowCleartext = true)
        } }
        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.Unexpected::class.java)
    }

    private fun request(url: String): Request = Request.Builder().url(url).build()

    private inline fun withServer(block: (MockWebServer) -> Unit) {
        MockWebServer().use { server -> server.start(); block(server) }
    }
}
