package com.novelradar.app.domain.repository

import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.source.rule.RuleDto
import kotlinx.coroutines.flow.Flow

data class CatalogResult(
    val chapters: List<Chapter>,
    val fromCache: Boolean,
    val warning: SourceFailure? = null,
)

interface CatalogRepository {
    suspend fun book(bookId: String): Book?
    suspend fun sourceNames(): Map<String, String>
    fun sources(bookId: String): Flow<List<BookSource>>
    fun observeChapters(bookId: String, sourceId: String): Flow<List<Chapter>>
    suspend fun catalog(bookId: String, sourceId: String, forceRefresh: Boolean = false): CatalogResult
}

interface ChapterCatalogStore {
    fun observe(bookId: String, sourceId: String): Flow<List<Chapter>>
    suspend fun replace(bookId: String, sourceId: String, chapters: List<Chapter>)
}

fun interface CatalogFetcher {
    suspend fun fetch(
        rule: RuleDto,
        source: StoredSourceRule,
        hit: SearchHit,
        bookId: String,
    ): SourceResult<List<Chapter>>
}
