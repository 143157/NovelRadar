package com.novelradar.app.domain.library

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.*
import com.novelradar.app.domain.repository.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RefreshShelfUseCaseTest {
    @Test fun refreshesOnlySelectedSourceAtMostThreeAtOnceAndKeepsPartialWarnings() = runTest {
        val shelf = (1..7).map { id -> ShelfBook(Book("$id", "$id", "书$id", "作者"), 1, null, false, "chosen", chapterCount = 10, unreadCount = 10) }
        val calls = mutableListOf<Pair<String, String>>()
        var active = 0
        var maximum = 0
        val catalog = object : CatalogRepository {
            override suspend fun book(bookId: String): Book? = null
            override suspend fun sourceNames() = emptyMap<String, String>()
            override fun sources(bookId: String) = flowOf(emptyList<BookSource>())
            override fun observeChapters(bookId: String, sourceId: String) = flowOf(emptyList<Chapter>())
            override suspend fun catalog(bookId: String, sourceId: String, forceRefresh: Boolean): CatalogResult {
                assertThat(forceRefresh).isTrue()
                calls += bookId to sourceId
                active++
                maximum = maxOf(maximum, active)
                try {
                    delay(50)
                    if (bookId == "2") throw IllegalStateException("offline")
                    return CatalogResult(emptyList(), true, if (bookId == "4") SourceFailure.Timeout else null)
                } finally { active-- }
            }
        }
        val warnings = RefreshShelfUseCase(catalog).refresh(shelf)
        assertThat(calls).hasSize(7)
        assertThat(calls.map { it.second }.distinct()).containsExactly("chosen")
        assertThat(maximum).isEqualTo(3)
        assertThat(warnings.keys).containsExactly("2", "4")
        assertThat(shelf.map { it.unreadCount }.distinct()).containsExactly(10)
    }

    @Test fun missingSourceProducesWarningWithoutTryingAnotherSource() = runTest {
        val catalog = object : CatalogRepository {
            override suspend fun book(bookId: String): Book? = null
            override suspend fun sourceNames() = emptyMap<String, String>()
            override fun sources(bookId: String) = flowOf(emptyList<BookSource>())
            override fun observeChapters(bookId: String, sourceId: String) = flowOf(emptyList<Chapter>())
            override suspend fun catalog(bookId: String, sourceId: String, forceRefresh: Boolean): CatalogResult = error("must not call")
        }
        val warnings = RefreshShelfUseCase(catalog).refresh(listOf(ShelfBook(Book("b", "b", "书", "作者"), 1, null, false)))
        assertThat(warnings.keys).containsExactly("b")
    }
}
