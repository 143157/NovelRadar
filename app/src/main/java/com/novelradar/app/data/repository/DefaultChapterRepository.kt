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
import com.novelradar.app.data.download.BookDownloadLeases
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
    private val contentLeases: BookDownloadLeases = BookDownloadLeases(),
) : ChapterRepository, com.novelradar.app.data.download.DownloadChapters {
    override suspend fun catalog(bookId: String, sourceId: String) = chapters.getChapters(bookId, sourceId)
    override suspend fun verifiedBytes(chapter: ChapterEntity): Long? = contentLeases.withContent(chapter.bookId) {
        loadLocks.getOrPut(ChapterKey(chapter.bookId, chapter.sourceId, chapter.chapterId)) { Mutex() }.withLock {
            ensureCurrent()
            val current = chapters.getChapter(chapter.bookId, chapter.sourceId, chapter.chapterId)
                ?.takeIf { it.url == chapter.url } ?: return@withLock null
            val content = verifiedContent(current) ?: return@withLock null
            val url = current.url ?: return@withLock null
            val bytes = content.toByteArray(Charsets.UTF_8).size.toLong()
            if (chapters.updateStoredContent(current.bookId, current.sourceId, current.chapterId, url,
                "DOWNLOADED", current.localFilePath!!, current.contentSha256!!, bytes) == 0) null else bytes
        }
    }

    override suspend fun download(chapter: ChapterEntity): SourceResult<Long> = try {
        contentLeases.withContent(chapter.bookId) {
            loadLocks.getOrPut(ChapterKey(chapter.bookId, chapter.sourceId, chapter.chapterId)) { Mutex() }.withLock {
                ensureCurrent()
                val current = chapters.getChapter(chapter.bookId, chapter.sourceId, chapter.chapterId)
                if (current == null || current.url != chapter.url) return@withLock SourceResult.Failure(SourceFailure.UnsafeRule("Chapter changed while downloading"))
                when (val loaded = loadChapterLocked(chapter.bookId, chapter.sourceId, chapter.chapterId, prefetch = false, download = true)) {
                    is SourceResult.Success -> SourceResult.Success(loaded.value.content.toByteArray(Charsets.UTF_8).size.toLong())
                    is SourceResult.Failure -> loaded
                }
            }
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: java.io.IOException) { SourceResult.Failure(SourceFailure.Storage("Could not store chapter")) }
    catch (_: IllegalStateException) { SourceResult.Failure(SourceFailure.UnsafeRule("Book content is being deleted")) }
    private val loadLocks = ConcurrentHashMap<ChapterKey, Mutex>()

    override suspend fun loadChapter(bookId: String, sourceId: String, chapterId: String): SourceResult<LoadedChapter> =
        loadChapter(bookId, sourceId, chapterId, prefetch = false)

    private suspend fun loadChapter(bookId: String, sourceId: String, chapterId: String,
        prefetch: Boolean): SourceResult<LoadedChapter> =
        try {
            contentLeases.withContent(bookId) {
                loadLocks.getOrPut(ChapterKey(bookId, sourceId, chapterId)) { Mutex() }.withLock {
                    ensureCurrent()
                    loadChapterLocked(bookId, sourceId, chapterId, prefetch)
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IllegalStateException) { SourceResult.Failure(SourceFailure.UnsafeRule("Book content is being deleted")) }

    private suspend fun loadChapterLocked(bookId: String, sourceId: String, chapterId: String,
        prefetch: Boolean, download: Boolean = false): SourceResult<LoadedChapter> {
        val row = chapters.getChapter(bookId, sourceId, chapterId)
            ?: return SourceResult.Failure(SourceFailure.UnsafeRule("Chapter is not saved"))
        val cached = try { verifiedContent(row) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: java.io.IOException) { return SourceResult.Failure(SourceFailure.Storage("Could not read chapter storage")) }
        if (cached != null) {
            if (download && row.localContentState != "DOWNLOADED") {
                val url = row.url ?: return SourceResult.Failure(SourceFailure.UnsafeRule("Chapter has no URL"))
                if (chapters.updateStoredContent(bookId, sourceId, chapterId, url, "DOWNLOADED", row.localFilePath!!,
                    row.contentSha256!!, row.contentBytes!!) == 0) return SourceResult.Failure(SourceFailure.UnsafeRule("Chapter changed while downloading"))
            }
            val origin = if (download || row.localContentState == "DOWNLOADED") ChapterOrigin.Downloaded else ChapterOrigin.Cached
            return SourceResult.Success(LoadedChapter(row.toDomain(), cached, origin))
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
                    if (download || row.localContentState == "DOWNLOADED") "DOWNLOADED" else "CACHED",
                    saved.relativePath, saved.sha256, saved.byteCount)
                if (changed == 0) SourceResult.Failure(SourceFailure.UnsafeRule("Chapter changed while loading"))
                else {
                    val updated = chapters.getChapter(bookId, sourceId, chapterId)
                    if (updated == null) SourceResult.Failure(SourceFailure.UnsafeRule("Chapter changed while loading"))
                    else SourceResult.Success(LoadedChapter(updated.toDomain(), fetched.value, ChapterOrigin.Network))
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                SourceResult.Failure(SourceFailure.Storage("Could not cache chapter"))
            }
        }
    }

    private suspend fun verifiedContent(row: ChapterEntity): String? {
        if (row.localContentState !in setOf("CACHED", "DOWNLOADED") || row.contentSha256 == null ||
            row.localFilePath != "books/${row.bookId}/${row.sourceId}/${row.chapterId}.txt") return null
        val content = files.read(row.bookId, row.sourceId, row.chapterId, row.contentSha256) ?: return null
        return content.takeIf { row.contentBytes == it.toByteArray(Charsets.UTF_8).size.toLong() }
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
