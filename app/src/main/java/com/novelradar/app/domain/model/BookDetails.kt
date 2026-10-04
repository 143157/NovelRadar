package com.novelradar.app.domain.model

import java.util.Locale

/** Detail-page evidence never includes fields borrowed from a local book or search hit. */
data class BookIdentityEvidence(val title: String? = null, val author: String? = null) {
    fun conflictsWith(book: Book): Boolean = differs(title, book.bookName) || differs(author, book.author)

    fun fullyMatches(book: Book): Boolean = listOf(title, author, book.bookName, book.author)
        .all { !it.isNullOrBlank() && identityPart(it).isNotBlank() } && !conflictsWith(book)

    private fun differs(actual: String?, expected: String): Boolean {
        val pagePart = actual?.let(::identityPart).orEmpty()
        val savedPart = identityPart(expected)
        return pagePart.isNotBlank() && savedPart.isNotBlank() && pagePart != savedPart
    }

    private fun identityPart(value: String) = normalizeIdentityPart(value).lowercase(Locale.ROOT)
}

data class BookDetails(val book: Book, val identity: BookIdentityEvidence)
