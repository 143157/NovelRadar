package com.novelradar.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.source.http.SourceHttpClient
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test

class SafeCoverFetcherTest {
    @Test fun rejectsHttpAndEmbeddedCredentialsBeforeNetwork() = runTest {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("image".toResponseBody()).build()
        }.build()
        val fetcher = SafeCoverFetcher(SourceHttpClient(client))
        assertThat(fetcher.fetch("a", "http://example.org/image.png")).isNull()
        assertThat(fetcher.fetch("a", "https://user:secret@example.org/image.png")).isNull()
        assertThat(calls).isEqualTo(0)
    }
    @Test fun rejectsHttpsToHttpRedirectAndLoadsBoundedHttpsBytes() = runTest {
        var calls = 0
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val builder = Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
            if (chain.request().url.encodedPath == "/redirect") builder.code(302).message("Found").header("Location", "http://example.org/unsafe").body("".toResponseBody()).build()
            else builder.code(200).message("OK").header("Content-Type", "image/png").body(byteArrayOf(1, 2, 3).toResponseBody()).build()
        }.build()
        val fetcher = SafeCoverFetcher(SourceHttpClient(client))
        assertThat(fetcher.fetch("a", "https://example.org/redirect")).isNull()
        assertThat(calls).isEqualTo(1)
        assertThat(fetcher.fetch("a", "https://example.org/image")).isEqualTo(byteArrayOf(1, 2, 3))
    }
}
