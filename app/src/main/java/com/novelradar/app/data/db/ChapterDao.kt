package com.novelradar.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.novelradar.app.data.db.entity.ChapterEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChapterDao {
    @Upsert
    suspend fun upsertAll(chapters: List<ChapterEntity>)

    @Upsert
    suspend fun upsert(chapter: ChapterEntity)

    @Query(
        "SELECT * FROM chapters WHERE bookId = :bookId AND sourceId = :sourceId " +
            "ORDER BY chapterIndex ASC, chapterId ASC",
    )
    fun observeChapters(bookId: String, sourceId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId AND sourceId = :sourceId ORDER BY chapterIndex ASC")
    suspend fun getChapters(bookId: String, sourceId: String): List<ChapterEntity>

    @Transaction
    suspend fun replaceSnapshot(bookId: String, sourceId: String, chapters: List<ChapterEntity>) {
        require(chapters.all { it.bookId == bookId && it.sourceId == sourceId })
        val oldById = getChapters(bookId, sourceId).associateBy { it.chapterId }
        val preserved = chapters.map { chapter ->
            oldById[chapter.chapterId]?.let { old ->
                chapter.copy(localContentState = old.localContentState, localFilePath = old.localFilePath,
                    contentSha256 = old.contentSha256, contentBytes = old.contentBytes)
            } ?: chapter
        }
        deleteForSource(bookId, sourceId)
        upsertAll(preserved)
    }

    @Query(
        "SELECT * FROM chapters WHERE bookId = :bookId AND sourceId = :sourceId " +
            "AND chapterId = :chapterId LIMIT 1",
    )
    suspend fun getChapter(bookId: String, sourceId: String, chapterId: String): ChapterEntity?

    @Query("DELETE FROM chapters WHERE bookId = :bookId AND sourceId = :sourceId")
    suspend fun deleteForSource(bookId: String, sourceId: String)
}
