package com.novelradar.app.source.http

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Applied to initial URLs and every redirect. */
object HttpPolicy {
    fun isAllowed(url: String, allowCleartext: Boolean = false): Boolean =
        url.toHttpUrlOrNull()?.let { isAllowed(it, allowCleartext) } ?: false

    internal fun isAllowed(url: HttpUrl, allowCleartext: Boolean): Boolean =
        (url.isHttps || allowCleartext && url.scheme == "http") &&
            url.username.isEmpty() && url.password.isEmpty()
}
