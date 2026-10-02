package com.novelradar.app.source.http

import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class SourceHttpPage(val body: ByteArray, val finalUrl: String, val contentType: String?)

/** Fetches public pages for one source; decoding and HTML parsing belong to callers. */
class SourceHttpClient(
    private val baseClient: OkHttpClient = OkHttpClient(),
    val timeoutMillis: Long = 10_000,
    val maxRedirects: Int = 5,
    val maxBodyBytes: Long = 2L * 1024 * 1024,
) {
    init {
        require(timeoutMillis > 0)
        require(maxRedirects >= 0)
        require(maxBodyBytes > 0)
    }

    private val sessions = ConcurrentHashMap<String, SourceSession>()

    suspend fun execute(
        sourceId: String,
        request: Request,
        allowCleartext: Boolean = false,
        initialCookies: String? = null,
    ): SourceResult<SourceHttpPage> {
        if (sourceId.isBlank()) return unsafe("source ID is required")
        if (request.method != "GET" && request.method != "POST") return unsafe("request method must be GET or POST")
        if (!HttpPolicy.isAllowed(request.url, allowCleartext)) return unsafe("request URL is not permitted")
        val session = sessions.computeIfAbsent(sourceId) {
            val jar = OriginCookieJar()
            SourceSession(baseClient.newBuilder().followRedirects(false).followSslRedirects(false).cookieJar(jar).build(), jar)
        }
        initialCookies?.let { session.cookies.seed(request.url, it) }
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val seen = mutableSetOf<Pair<String, String>>()
        // Cookie headers come only from this source's origin-partitioned jar.
        var current = request.newBuilder().removeHeader("Cookie").build()
        var redirects = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            if (!seen.add(current.method to current.url.toString())) return unsafe("redirect loop")
            if (!HttpPolicy.isAllowed(current.url, allowCleartext)) return unsafe("redirect URL is not permitted")
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) return SourceResult.Failure(SourceFailure.Timeout)
            val hop = try {
                fetch(session.client, current, remaining)
            } catch (error: CancellationException) {
                throw error
            } catch (error: IOException) {
                currentCoroutineContext().ensureActive()
                return SourceResult.Failure(when (error) {
                    is javax.net.ssl.SSLException -> SourceFailure.UnsafeRule("TLS verification failed")
                    is SocketTimeoutException, is InterruptedIOException -> SourceFailure.Timeout
                    else -> SourceFailure.Network(error.message ?: "network error")
                })
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                return SourceResult.Failure(SourceFailure.Unexpected(error.message ?: "network error"))
            }
            when (hop) {
                is Hop.Page -> return SourceResult.Success(SourceHttpPage(hop.bytes, current.url.toString(), hop.contentType))
                is Hop.HttpError -> return SourceResult.Failure(SourceFailure.Http(hop.code))
                Hop.TooLarge -> return SourceResult.Failure(SourceFailure.Unexpected("response exceeds $maxBodyBytes bytes"))
                is Hop.Redirect -> {
                    if (redirects >= maxRedirects) return unsafe("redirect limit exceeded")
                    val nextUrl = current.url.resolve(hop.location) ?: return unsafe("invalid redirect URL")
                    if (!HttpPolicy.isAllowed(nextUrl, allowCleartext)) return unsafe("redirect URL is not permitted")
                    val crossOrigin = current.url.scheme != nextUrl.scheme || current.url.host != nextUrl.host || current.url.port != nextUrl.port
                    val builder = current.newBuilder().url(nextUrl)
                    if (crossOrigin) builder.removeHeader("Cookie").removeHeader("Authorization").removeHeader("Proxy-Authorization")
                    if (hop.code == 303 || (hop.code == 301 || hop.code == 302) && current.method == "POST") {
                        builder.method("GET", null).removeHeader("Content-Type").removeHeader("Content-Length").removeHeader("Transfer-Encoding")
                    }
                    current = builder.build()
                    redirects++
                }
            }
        }
    }

    private suspend fun fetch(client: OkHttpClient, request: Request, remainingNanos: Long): Hop = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        call.timeout().timeout(remainingNanos.coerceAtLeast(1), TimeUnit.NANOSECONDS)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val hop = response.use { received ->
                        val code = received.code
                        val location = received.header("Location")
                        when {
                            code in 300..399 && location != null -> Hop.Redirect(code, location)
                            code !in 200..299 -> Hop.HttpError(code)
                            else -> {
                                val body = received.body
                                val buffer = Buffer()
                                val source = body.source()
                                while (buffer.size <= maxBodyBytes) {
                                    val read = source.read(buffer, maxBodyBytes + 1 - buffer.size)
                                    if (read == -1L) break
                                }
                                if (buffer.size > maxBodyBytes) Hop.TooLarge
                                else Hop.Page(buffer.readByteArray(), received.header("Content-Type"))
                            }
                        }
                    }
                    if (continuation.isActive) continuation.resume(hop)
                } catch (error: Exception) {
                    if (continuation.isActive) continuation.resumeWithException(error)
                }
            }
        })
    }

    private fun unsafe(reason: String): SourceResult.Failure = SourceResult.Failure(SourceFailure.UnsafeRule(reason))

    private data class SourceSession(val client: OkHttpClient, val cookies: OriginCookieJar)

    private sealed interface Hop {
        data class Page(val bytes: ByteArray, val contentType: String?) : Hop
        data class HttpError(val code: Int) : Hop
        data class Redirect(val code: Int, val location: String) : Hop
        data object TooLarge : Hop
    }

    /** Exact-origin storage prevents cookie reuse across hosts, ports and schemes. */
    private class OriginCookieJar : CookieJar {
        private val stored = mutableMapOf<String, MutableList<Cookie>>()

        @Synchronized override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
            val bucket = stored.getOrPut(origin(url)) { mutableListOf() }
            cookies.forEach { cookie ->
                bucket.removeAll { it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path }
                if (cookie.matches(url) && cookie.expiresAt > System.currentTimeMillis()) bucket += cookie
            }
        }

        @Synchronized override fun loadForRequest(url: HttpUrl): List<Cookie> {
            val bucket = stored[origin(url)] ?: return emptyList()
            bucket.removeAll { it.expiresAt <= System.currentTimeMillis() }
            return bucket.filter { it.matches(url) }
        }

        fun seed(url: HttpUrl, header: String) {
            val cookies = header.split(';').mapNotNull { field ->
                val separator = field.indexOf('=')
                if (separator <= 0) return@mapNotNull null
                try {
                    Cookie.Builder().name(field.substring(0, separator).trim())
                        .value(field.substring(separator + 1).trim())
                        .hostOnlyDomain(url.host).path("/").build()
                } catch (_: IllegalArgumentException) { null }
            }
            saveFromResponse(url, cookies)
        }

        private fun origin(url: HttpUrl): String = "${url.scheme}://${url.host}:${url.port}"
    }
}
