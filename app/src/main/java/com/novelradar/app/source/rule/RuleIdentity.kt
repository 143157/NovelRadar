package com.novelradar.app.source.rule

import java.net.URI
import java.security.MessageDigest
import java.util.Locale

/** SoNovel-compatible stable identity for rules without an explicit ID. */
fun deriveSourceId(rule: RuleDto): String {
    val host = requireNotNull(URI(rule.url).host).lowercase(Locale.ROOT)
    val identity = "$host|${rule.name}"
    return MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
