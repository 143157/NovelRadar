package com.novelradar.app.data.repository

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.data.db.ChapterDao
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.data.files.AtomicChapterFileStore
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceHealth
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.model.StoredSourceRule
import com.novelradar.app.domain.repository.ChapterOrigin
import com.novelradar.app.domain.repository.ImportSummary
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.source.parse.ChapterPage
import com.novelradar.app.source.rule.RuleDto
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DefaultChapterRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun validDownloadedFileLoadsWithoutNetwork() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val saved = files.write("book", "source", "one", "下载正文")
        val dao = FakeChapterDao(chapter("one", 0).copy(
            localContentState = "DOWNLOADED", localFilePath = saved.relativePath,
            contentSha256 = saved.sha256, contentBytes = saved.byteCount,
        ))
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files, { fetches++; SourceResult.Success("网络正文") })

        val loaded = repository.loadChapter("book", "source", "one") as SourceResult.Success

        assertThat(loaded.value.content).isEqualTo("下载正文")
        assertThat(loaded.value.origin).isEqualTo(ChapterOrigin.Downloaded)
        assertThat(fetches).isEqualTo(0)
    }

    @Test fun validCachedFileReportsCachedOrigin() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val saved = files.write("book", "source", "one", "缓存正文")
        val dao = FakeChapterDao(chapter("one", 0).copy(
            localContentState = "CACHED", localFilePath = saved.relativePath,
            contentSha256 = saved.sha256, contentBytes = saved.byteCount,
        ))
        val repository = DefaultChapterRepository(dao, files, { error("Network should not be used") })

        val loaded = repository.loadChapter("book", "source", "one") as SourceResult.Success

        assertThat(loaded.value.content).isEqualTo("缓存正文")
        assertThat(loaded.value.origin).isEqualTo(ChapterOrigin.Cached)
    }

    @Test fun corruptFileRefetchesThenStoresVerifiedContentAndRoomMetadata() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val old = files.write("book", "source", "one", "旧正文")
        Files.writeString(temporary.root.toPath().resolve(old.relativePath), "损坏")
        val dao = FakeChapterDao(chapter("one", 0).copy(
            localContentState = "DOWNLOADED", localFilePath = old.relativePath,
            contentSha256 = old.sha256, contentBytes = old.byteCount,
        ))
        var fetches = 0
        val repository = DefaultChapterRepository(dao, files, { fetches++; SourceResult.Success("新正文") })

        val first = repository.loadChapter("book", "source", "one") as SourceResult.Success
        val row = dao.getChapter("book", "source", "one")!!
        val second = repository.loadChapter("book", "source", "one") as SourceResult.Success

        assertThat(first.value.origin).isEqualTo(ChapterOrigin.Network)
        assertThat(first.value.content).isEqualTo("新正文")
        assertThat(row.localContentState).isEqualTo("CACHED")
        assertThat(row.localFilePath).isEqualTo("books/book/source/one.txt")
        assertThat(row.contentSha256).hasLength(64)
        assertThat(row.contentBytes).isEqualTo("新正文".toByteArray(Charsets.UTF_8).size.toLong())
        assertThat(files.read("book", "source", "one", row.contentSha256)).isEqualTo("新正文")
        assertThat(second.value.origin).isEqualTo(ChapterOrigin.Cached)
        assertThat(fetches).isEqualTo(1)
    }

    @Test fun networkFailureDoesNotReplaceExistingChapterMetadata() = runBlocking {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val original = chapter("one", 0)
        val dao = FakeChapterDao(original)
        val repository = DefaultChapterRepository(dao, files, {
            SourceResult.Failure(SourceFailure.Timeout)
        })

        val result = repository.loadChapter("book", "source", "one")

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.Timeout))
        assertThat(dao.getChapter("book", "source", "one")).isEqualTo(original)
        assertThat(files.read("book", "source", "one")).isNull()
    }

    @Test fun prefetchIsBoundedAndSkipsMeteredNetworkWhenNotAllowed() = runBlocking {
        val dao = FakeChapterDao(*(0..4).map { chapter("c$it", it) }.toTypedArray())
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val fetched = mutableListOf<String>()
        var allowed = false
        val repository = DefaultChapterRepository(dao, files,
            { chapter -> fetched += chapter.chapterId; SourceResult.Success(chapter.chapterId) },
            isMeteredNetwork = { true }, allowMeteredPrefetch = { allowed })

        assertThat(repository.prefetchNext("book", "source", 0, count = 99)).isEqualTo(0)
        assertThat(fetched).isEmpty()
        allowed = true
        assertThat(repository.prefetchNext("book", "source", 0, count = 99)).isEqualTo(2)
        assertThat(fetched).containsExactly("c1", "c2").inOrder()
        assertThat(repository.prefetchNext("book", "source", 3)).isEqualTo(1)
    }

    @Test fun ruleBasedFetcherUsesSavedCleartextChoiceAndParsedChapter() = runBlocking {
        val rules = FakeRules(StoredSourceRule("source", RULE_JSON, allowCleartext = true))
        var called = false
        val fetcher = RuleBasedChapterContentFetcher(rules, { rule, sourceId, url, allowCleartext ->
            called = true
            assertThat(rule.name).isEqualTo("Source")
            assertThat(sourceId).isEqualTo("source")
            assertThat(url).isEqualTo("https://example.test/one")
            assertThat(allowCleartext).isTrue()
            SourceResult.Success(ChapterPage("Chapter", "正文"))
        })

        assertThat(fetcher.fetch(chapter("one", 0))).isEqualTo(SourceResult.Success("正文"))
        assertThat(called).isTrue()
    }

    @Test fun ruleBasedFetcherRejectsDisabledSourceBeforeNetwork() = runBlocking {
        val rules = FakeRules(StoredSourceRule("source", RULE_JSON, enabled = false))
        var called = false
        val fetcher = RuleBasedChapterContentFetcher(rules, { _, _, _, _ ->
            called = true
            SourceResult.Success(ChapterPage("", "unexpected"))
        })

        assertThat(fetcher.fetch(chapter("one", 0))).isInstanceOf(SourceResult.Failure::class.java)
        assertThat(called).isFalse()
    }

    private fun chapter(id: String, index: Int) = ChapterEntity(
        bookId = "book", sourceId = "source", chapterId = id, chapterIndex = index,
        title = "Chapter $index", url = "https://example.test/$id",
    )

    private class FakeRules(private val rule: StoredSourceRule) : SourceRuleRepository {
        override fun observeAll(): Flow<List<StoredSourceRule>> = flowOf(listOf(rule))
        override suspend fun enabled(): List<StoredSourceRule> = listOf(rule).filter { it.enabled }
        override suspend fun upsertImported(rules: List<RuleDto>) = ImportSummary(0, 0, 0)
        override suspend fun setEnabled(sourceId: String, enabled: Boolean) = Unit
        override suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean) = Unit
        override suspend fun updateHealth(sourceId: String, health: SourceHealth) = Unit
        override suspend fun deleteUserRule(sourceId: String) = Unit
    }

    private companion object {
        const val RULE_JSON = """{"id":"source","url":"https://example.test/","name":"Source",
            "search":{"url":"https://example.test/search?q=%s","method":"GET","result":".item","bookName":"a"},
            "toc":{"item":"#toc a"},"chapter":{"title":"h1","content":"#content"}}"""
    }

    private class FakeChapterDao(vararg chapters: ChapterEntity) : ChapterDao {
        private val rows = chapters.associateBy { Triple(it.bookId, it.sourceId, it.chapterId) }.toMutableMap()

        override suspend fun upsertAll(chapters: List<ChapterEntity>) { chapters.forEach { upsert(it) } }
        override suspend fun upsert(chapter: ChapterEntity) {
            rows[Triple(chapter.bookId, chapter.sourceId, chapter.chapterId)] = chapter
        }
        override fun observeChapters(bookId: String, sourceId: String): Flow<List<ChapterEntity>> =
            flowOf(rows.values.filter { it.bookId == bookId && it.sourceId == sourceId }.sortedBy { it.chapterIndex })
        override suspend fun getChapters(bookId: String, sourceId: String): List<ChapterEntity> =
            rows.values.filter { it.bookId == bookId && it.sourceId == sourceId }.sortedBy { it.chapterIndex }
        override suspend fun getChapter(bookId: String, sourceId: String, chapterId: String): ChapterEntity? =
            rows[Triple(bookId, sourceId, chapterId)]
        override suspend fun deleteForSource(bookId: String, sourceId: String) {
            rows.keys.removeAll { it.first == bookId && it.second == sourceId }
        }
    }
}
