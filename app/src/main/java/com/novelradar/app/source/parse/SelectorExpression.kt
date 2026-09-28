package com.novelradar.app.source.parse

/** A SoNovel selector and its optional post-selection string transform. */
data class SelectorExpression(
    val selector: String,
    val script: String? = null,
) {
    companion object {
        fun parse(raw: String): SelectorExpression {
            val marker = raw.indexOf("@js:")
            return if (marker < 0) {
                SelectorExpression(raw.trim())
            } else {
                SelectorExpression(
                    selector = raw.substring(0, marker).trim(),
                    script = raw.substring(marker + "@js:".length),
                )
            }
        }
    }
}
