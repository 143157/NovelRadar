package com.novelradar.app.data.repository

import com.novelradar.app.data.db.ChapterDao
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.LocalContentState
import com.novelradar.app.domain.repository.ChapterCatalogStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomChapterCatalogStore(private val dao: ChapterDao) : ChapterCatalogStore {
    override fun observe(bookId: String, sourceId: String): Flow<List<Chapter>> =
        dao.observeChapters(bookId, sourceId).map { rows -> rows.map(ChapterEntity::toDomain) }

    override suspend fun replace(bookId: String, sourceId: String, chapters: List<Chapter>) {
        dao.replaceSnapshot(bookId, sourceId, chapters.map { chapter ->
            require(chapter.bookId == bookId && chapter.sourceId == sourceId)
            ChapterEntity(chapter.bookId, chapter.sourceId, chapter.chapterId,
                chapter.chapterIndex, chapter.title, chapter.url, chapter.localContentState.name)
        })
    }
}

private fun ChapterEntity.toDomain() = Chapter(bookId, sourceId, chapterId, chapterIndex, title, url,
    LocalContentState.entries.firstOrNull { it.name == localContentState } ?: LocalContentState.NOT_STORED)
