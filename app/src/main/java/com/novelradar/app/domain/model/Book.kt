package com.novelradar.app.domain.model

data class Book(
    val bookId: String,
    val normalizedBookKey: String,
    val bookName: String,
    val author: String,
    val coverUrl: String? = null,
    val introduction: String? = null,
)
