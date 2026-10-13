package com.novelradar.app.feature.reader

import com.novelradar.app.domain.model.Chapter

fun readingWindowIds(catalog: List<Chapter>, activeChapterId: String): Set<String> {
    val ordered = catalog.sortedWith(compareBy(Chapter::chapterIndex, Chapter::chapterId))
    val active = ordered.indexOfFirst { it.chapterId == activeChapterId }
    if (active < 0) return emptySet()
    return ordered.subList((active - 1).coerceAtLeast(0), (active + 3).coerceAtMost(ordered.size))
        .mapTo(linkedSetOf()) { it.chapterId }
}
