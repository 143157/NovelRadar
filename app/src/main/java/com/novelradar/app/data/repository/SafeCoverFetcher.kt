package com.novelradar.app.data.repository

import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.http.HttpPolicy
import com.novelradar.app.source.http.SourceHttpClient
import okhttp3.Request

fun interface CoverFetcher {
    suspend fun fetch(sourceId: String, url: String): ByteArray?
}

/** Covers use HTTPS only. SourceHttpClient enforces that policy on every redirect and bounds bytes. */
class SafeCoverFetcher(private val http: SourceHttpClient) : CoverFetcher {
    override suspend fun fetch(sourceId: String, url: String): ByteArray? {
        if (!HttpPolicy.isAllowed(url)) return null
        val request = Request.Builder().url(url).get().build()
        return when (val result = http.execute("cover:$sourceId", request, allowCleartext = false)) {
            is SourceResult.Failure -> null
            is SourceResult.Success -> result.value.body.takeIf {
                result.value.contentType?.substringBefore(';')?.startsWith("image/", ignoreCase = true) == true
            }
        }
    }
}
