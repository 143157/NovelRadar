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
                old.takeIf { it.url == chapter.url }?.let { chapter.copy(localContentState = old.localContentState, localFilePath = old.localFilePath,
                    contentSha256 = old.contentSha256, contentBytes = old.contentBytes)
                } ?: chapter
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

    @Query(
        "UPDATE chapters SET localContentState = CASE WHEN localContentState = 'DOWNLOADED' THEN 'DOWNLOADED' ELSE :state END, localFilePath = :path, " +
            "contentSha256 = :sha256, contentBytes = :byteCount " +
            "WHERE bookId = :bookId AND sourceId = :sourceId AND chapterId = :chapterId " +
            "AND url = :expectedUrl",
    )
    suspend fun updateStoredContent(
        bookId: String,
        sourceId: String,
        chapterId: String,
        expectedUrl: String,
        state: String,
        path: String,
        sha256: String,
        byteCount: Long,
    ): Int

    @Query("DELETE FROM chapters WHERE bookId = :bookId AND sourceId = :sourceId")
    suspend fun deleteForSource(bookId: String, sourceId: String)

    @Query("UPDATE chapters SET localContentState = 'NOT_STORED', localFilePath = NULL, contentSha256 = NULL, contentBytes = NULL WHERE bookId = :bookId")
    suspend fun clearStoredMetadata(bookId: String)
}
