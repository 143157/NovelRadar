package com.novelradar.app.domain.model

/** [chapterId] is scoped to [bookId] and [sourceId], not shared across different sources. */
data class Chapter(
    val bookId: String,
    val sourceId: String,
    val chapterId: String,
    val chapterIndex: Int,
    val title: String,
    val url: String? = null,
    val localContentState: LocalContentState = LocalContentState.NOT_STORED,
)

enum class LocalContentState {
    NOT_STORED,
    CACHED,
    DOWNLOADED,
}
