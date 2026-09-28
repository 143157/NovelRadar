package com.novelradar.app.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import com.novelradar.app.data.db.entity.DownloadTaskEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface DownloadTaskDao {
    @Upsert
    suspend fun upsert(task: DownloadTaskEntity)

    @Query("SELECT * FROM download_tasks ORDER BY updatedAtEpochMillis DESC, taskId")
    fun observeAll(): Flow<List<DownloadTaskEntity>>

    @Query(
        "SELECT * FROM download_tasks WHERE bookId = :bookId " +
            "ORDER BY updatedAtEpochMillis DESC, taskId",
    )
    fun observeForBook(bookId: String): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_tasks WHERE taskId = :taskId LIMIT 1")
    suspend fun getById(taskId: String): DownloadTaskEntity?

    @Query("DELETE FROM download_tasks WHERE taskId = :taskId")
    suspend fun delete(taskId: String)
}
