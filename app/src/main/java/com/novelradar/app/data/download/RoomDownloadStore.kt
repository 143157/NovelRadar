package com.novelradar.app.data.download

import androidx.room.withTransaction
import com.novelradar.app.data.db.NovelDatabase
import com.novelradar.app.data.db.entity.DownloadTaskEntity

class RoomDownloadStore(private val database: NovelDatabase) : DownloadStore {
    private val dao = database.downloadTaskDao()
    override suspend fun all() = dao.getAll()
    override suspend fun get(id: String) = dao.getById(id)
    override suspend fun save(task: DownloadTaskEntity) = dao.upsert(task)
    override suspend fun mutate(id: String, transform: (DownloadTaskEntity) -> DownloadTaskEntity): DownloadTaskEntity? = database.withTransaction {
        val current = dao.getById(id) ?: return@withTransaction null
        transform(current).also { require(it.taskId == id); dao.upsert(it) }
    }
    override suspend fun checkpoint(task: DownloadTaskEntity): Boolean = database.withTransaction {
        val current = dao.getById(task.taskId) ?: return@withTransaction false
        if (current.workId != task.workId || current.generation != task.generation || current.state !in setOf("QUEUED", "RUNNING")) return@withTransaction false
        dao.upsert(task)
        true
    }
}
