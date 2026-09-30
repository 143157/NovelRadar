package com.novelradar.app.data.repository

import com.novelradar.app.data.db.ChapterDao
import com.novelradar.app.data.db.entity.ChapterEntity
import com.novelradar.app.data.files.ChapterFileStore
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.LocalContentState
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.domain.repository.asExecutableRule
import com.novelradar.app.domain.repository.ChapterOrigin
import com.novelradar.app.domain.repository.ChapterRepository
import com.novelradar.app.domain.repository.LoadedChapter
import com.novelradar.app.source.parse.ChapterPage
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.rule.RuleJsonParser
import com.novelradar.app.source.rule.RuleParseResult
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Fetches and parses one chapter from its saved source rule. */
fun interface ChapterContentFetcher {
    suspend fun fetch(chapter: ChapterEntity): SourceResult<String>
}

fun interface ChapterPageFetcher {
    suspend fun fetch(rule: RuleDto, sourceId: String, url: String, allowCleartext: Boolean): SourceResult<ChapterPage>
}

class RuleBasedChapterContentFetcher(
    private val rules: SourceRuleRepository,
    private val pages: ChapterPageFetcher,
    private val parser: RuleJsonParser = RuleJsonParser(),
) : ChapterContentFetcher {
    override suspend fun fetch(chapter: ChapterEntity): SourceResult<String> {
        val url = chapter.url?.takeIf(String::isNotBlank)
            ?: return SourceResult.Failure(SourceFailure.UnsafeRule("Chapter has no URL"))
        val saved = rules.observeAll().first().firstOrNull { it.sourceId == chapter.sourceId }
            ?: return SourceResult.Failure(SourceFailure.UnsafeRule("Source rule is missing"))
        val rule = try {
            val parsed = parser.parse(saved.rawJson) as? RuleParseResult.Success
            saved.asExecutableRule(parsed?.rules?.singleOrNull()
                ?: throw IllegalArgumentException("Invalid stored source rule"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return SourceResult.Failure(SourceFailure.UnsafeRule("Source rule is unavailable"))
        }
        return when (val page = pages.fetch(rule, chapter.sourceId, url, saved.allowCleartext)) {
            is SourceResult.Success -> SourceResult.Success(page.value.content)
            is SourceResult.Failure -> page
        }
    }
}

class DefaultChapterRepository(
    private val chapters: ChapterDao,
    private val files: ChapterFileStore,
    private val fetcher: ChapterContentFetcher,
    private val isMeteredNetwork: () -> Boolean = { true },
    private val allowMeteredPrefetch: suspend () -> Boolean = { false },
) : ChapterRepository {
    private val loadLocks = ConcurrentHashMap<ChapterKey, Mutex>()

    override suspend fun loadChapter(bookId: String, sourceId: String, chapterId: String): SourceResult<LoadedChapter> =
        loadChapter(bookId, sourceId, chapterId, prefetch = false)

    private suspend fun loadChapter(bookId: String, sourceId: String, chapterId: String,
        prefetch: Boolean): SourceResult<LoadedChapter> =
        loadLocks.getOrPut(ChapterKey(bookId, sourceId, chapterId)) { Mutex() }.withLock {
            loadChapterLocked(bookId, sourceId, chapterId, prefetch)
        }

    private suspend fun loadChapterLocked(bookId: String, sourceId: String, chapterId: String,
        prefetch: Boolean): SourceResult<LoadedChapter> {
        val row = chapters.getChapter(bookId, sourceId, chapterId)
            ?: return SourceResult.Failure(SourceFailure.UnsafeRule("Chapter is not saved"))
        if (row.localContentState == LocalContentState.CACHED.name ||
            row.localContentState == LocalContentState.DOWNLOADED.name) {
            val expectedPath = "books/$bookId/$sourceId/$chapterId.txt"
            val hash = row.contentSha256
            if (row.localFilePath == expectedPath && hash != null) {
                val cached = try { files.read(bookId, sourceId, chapterId, hash) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
                if (cached != null && row.contentBytes == cached.toByteArray(Charsets.UTF_8).size.toLong()) {
                    val origin = if (row.localContentState == LocalContentState.DOWNLOADED.name) {
                        ChapterOrigin.Downloaded
                    } else ChapterOrigin.Cached
                    return SourceResult.Success(LoadedChapter(row.toDomain(), cached, origin))
                }
            }
        }
        val chapterUrl = row.url?.takeIf(String::isNotBlank)
            ?: return SourceResult.Failure(SourceFailure.UnsafeRule("Chapter has no URL"))
        if (prefetch && !prefetchPermitted()) {
            return SourceResult.Failure(SourceFailure.UnsafeRule("Prefetch is disabled on this network"))
        }
        val fetched = try { fetcher.fetch(row) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SourceResult.Failure(SourceFailure.Unexpected("Could not load chapter")) }
        return when (fetched) {
            is SourceResult.Failure -> fetched
            is SourceResult.Success -> try {
                val saved = files.write(bookId, sourceId, chapterId, fetched.value)
                val changed = chapters.updateStoredContent(bookId, sourceId, chapterId, chapterUrl,
                    LocalContentState.CACHED.name, saved.relativePath, saved.sha256, saved.byteCount)
                if (changed == 0) SourceResult.Failure(SourceFailure.UnsafeRule("Chapter changed while loading"))
                else {
                    val updated = chapters.getChapter(bookId, sourceId, chapterId)
                    if (updated == null) SourceResult.Failure(SourceFailure.UnsafeRule("Chapter changed while loading"))
                    else SourceResult.Success(LoadedChapter(updated.toDomain(), fetched.value, ChapterOrigin.Network))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SourceResult.Failure(SourceFailure.Unexpected("Could not cache chapter"))
            }
        }
    }

    override suspend fun prefetchNext(bookId: String, sourceId: String, chapterIndex: Int, count: Int): Int {
        var loaded = 0
        for (chapter in chapters.getChapters(bookId, sourceId)
            .filter { it.chapterIndex > chapterIndex }
            .sortedWith(compareBy(ChapterEntity::chapterIndex, ChapterEntity::chapterId))
            .take(count.coerceIn(0, MAX_PREFETCH_COUNT))) {
            if (!prefetchPermitted()) break
            if (loadChapter(bookId, sourceId, chapter.chapterId, prefetch = true) is SourceResult.Success) loaded++
        }
        return loaded
    }

    private suspend fun prefetchPermitted(): Boolean = try {
        !isMeteredNetwork() || allowMeteredPrefetch()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        false
    }

    private fun ChapterEntity.toDomain() = Chapter(bookId, sourceId, chapterId, chapterIndex, title, url,
        LocalContentState.entries.firstOrNull { it.name == localContentState } ?: LocalContentState.NOT_STORED)

    private companion object {
        const val MAX_PREFETCH_COUNT = 2
    }

    private data class ChapterKey(val bookId: String, val sourceId: String, val chapterId: String)
}
