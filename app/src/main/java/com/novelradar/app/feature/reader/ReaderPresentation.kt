package com.novelradar.app.feature.reader

import com.novelradar.app.domain.repository.LoadedChapter

internal data class ReaderItem(val chapter: LoadedChapter, val page: TextPage?, val key: String) {
    fun position(localOffset: Int = 0) = ChapterReadingPosition(chapter.chapter.chapterId,
        ((page?.startOffset ?: 0) + localOffset).coerceIn(0, chapter.content.length))
}

internal fun readerItems(chapters: List<LoadedChapter>, capacity: Int): List<ReaderItem> =
    chapters.flatMap { chapter -> listOf(ReaderItem(chapter, null, "chapter:${chapter.chapter.chapterId}:title")) +
        TextPaginator.paginate(chapter.content, capacity).map { ReaderItem(chapter, it, "chapter:${chapter.chapter.chapterId}:text:${it.startOffset}") } }

internal fun readerProgressLabel(state: ReaderUiState): String {
    val chapter = if (state.catalogPosition < 0) "章节位置待确认" else "第 ${state.catalogPosition + 1} / ${state.chapters.size} 章"
    val progress = state.characterProgress.takeIf { state.content?.boundaryVerified == true && !state.positionNeedsReview }
    return if (progress == null) chapter else "${progress.read} / ${progress.total} 字 · 本章 ${progress.percent}% · $chapter"
}

internal fun readerAnchor(items: List<ReaderItem>, position: ChapterReadingPosition): Int =
    items.indexOfFirst { it.chapter.chapter.chapterId == position.chapterId &&
        (if (position.offset == 0) it.page == null || it.page.startOffset == 0
        else it.page?.let { page -> position.offset < page.endOffset } == true) }
        .let { if (it < 0) items.indexOfLast { item -> item.chapter.chapter.chapterId == position.chapterId }.coerceAtLeast(0) else it }

internal fun readerSettledPosition(item: ReaderItem, retainedKey: String,
    retainedPosition: ChapterReadingPosition, navigationIntent: Boolean): ChapterReadingPosition? = when {
    item.key == retainedKey -> retainedPosition
    navigationIntent -> item.position()
    else -> null
}

internal data class ReaderScrollTarget(val chapterId: String, val originChapterId: String, val forward: Boolean)

internal fun readerReadyScrollPosition(items: List<ReaderItem>, target: ReaderScrollTarget?): ChapterReadingPosition? {
    target ?: return null
    val body = items.firstOrNull { it.chapter.chapter.chapterId == target.chapterId }?.chapter ?: return null
    return ChapterReadingPosition(target.chapterId, if (target.forward) 0 else body.content.length)
}
