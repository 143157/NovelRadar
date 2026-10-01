package com.novelradar.app.domain.library

import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.repository.CatalogRepository
import com.novelradar.app.domain.repository.ShelfBook
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

class RefreshShelfUseCase(private val catalog: CatalogRepository) {
    suspend fun refresh(shelf: List<ShelfBook>): Map<String, SourceFailure> = coroutineScope {
        val permits = Semaphore(3)
        shelf.map { entry -> async {
            permits.withPermit {
                val sourceId = entry.preferredSourceId
                val warning = if (sourceId == null) SourceFailure.Unexpected("没有可用书源") else try {
                    catalog.catalog(entry.book.bookId, sourceId, forceRefresh = true).warning
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    SourceFailure.Unexpected("目录刷新失败")
                }
                warning?.let { entry.book.bookId to it }
            }
        } }.awaitAll().filterNotNull().toMap()
    }
}
