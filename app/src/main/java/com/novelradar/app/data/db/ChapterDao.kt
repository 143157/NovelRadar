package com.novelradar.app.data.db

import androidx.room.Dao
import androidx.room.Query
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

    @Query(
        "SELECT * FROM chapters WHERE bookId = :bookId AND sourceId = :sourceId " +
            "AND chapterId = :chapterId LIMIT 1",
    )
    suspend fun getChapter(bookId: String, sourceId: String, chapterId: String): ChapterEntity?

    @Query("DELETE FROM chapters WHERE bookId = :bookId AND sourceId = :sourceId")
    suspend fun deleteForSource(bookId: String, sourceId: String)
}
