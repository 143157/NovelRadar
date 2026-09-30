package com.novelradar.app.domain.repository

import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.SourceResult

enum class ChapterOrigin { Downloaded, Cached, Network }

data class LoadedChapter(
    val chapter: Chapter,
    val content: String,
    val origin: ChapterOrigin,
)

interface ChapterRepository {
    suspend fun loadChapter(bookId: String, sourceId: String, chapterId: String): SourceResult<LoadedChapter>
    suspend fun prefetchNext(bookId: String, sourceId: String, chapterIndex: Int, count: Int = 2): Int
}
