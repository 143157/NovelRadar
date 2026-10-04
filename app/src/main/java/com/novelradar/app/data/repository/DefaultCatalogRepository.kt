package com.novelradar.app.data.repository

import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.CatalogFetcher
import com.novelradar.app.domain.repository.DetailsFetcher
import com.novelradar.app.domain.repository.DetailsResult
import com.novelradar.app.domain.repository.CatalogRepository
import com.novelradar.app.domain.repository.CatalogResult
import com.novelradar.app.domain.repository.ChapterCatalogStore
import com.novelradar.app.domain.repository.SourceRuleRepository
import com.novelradar.app.domain.repository.asExecutableRule
import com.novelradar.app.source.rule.RuleJsonParser
import com.novelradar.app.source.rule.RuleParseResult
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first

/** Refreshes one source's chapter snapshot without disturbing cached chapters from other sources. */
class DefaultCatalogRepository(
    private val books: BookRepository,
    private val rules: SourceRuleRepository,
    private val store: ChapterCatalogStore,
    private val fetcher: CatalogFetcher,
    private val parser: RuleJsonParser = RuleJsonParser(),
    private val detailsFetcher: DetailsFetcher? = null,
) : CatalogRepository {
    override suspend fun book(bookId: String) = books.getBook(bookId)
    override suspend fun sourceNames(): Map<String, String> = rules.observeAll().first().associate { stored ->
        val parsed = parser.parse(stored.rawJson) as? RuleParseResult.Success
        stored.sourceId to (parsed?.rules?.singleOrNull()?.name?.takeIf(String::isNotBlank) ?: stored.sourceId)
    }
    override fun sources(bookId: String) = books.observeSources(bookId)
    override fun observeChapters(bookId: String, sourceId: String): Flow<List<Chapter>> = store.observe(bookId, sourceId)

    override suspend fun refreshDetails(bookId: String, sourceId: String): DetailsResult {
        val book = books.getBook(bookId)
            ?: return staleDetails(null, SourceFailure.UnsafeRule("Book is not saved"))
        val mapping = books.observeSources(bookId).first().firstOrNull { it.bookId == bookId && it.sourceId == sourceId }
            ?: return staleDetails(book, SourceFailure.UnsafeRule("Book has no matching source"))
        val source = rules.observeAll().first().firstOrNull { it.sourceId == sourceId }
            ?: return staleDetails(book, SourceFailure.UnsafeRule("Source rule is missing"))
        val rule = try {
            val parsed = parser.parse(source.rawJson) as? RuleParseResult.Success
            source.asExecutableRule(parsed?.rules?.singleOrNull()
                ?: throw IllegalArgumentException("Invalid stored source rule"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return staleDetails(book, SourceFailure.UnsafeRule("Source rule is unavailable"))
        }
        val hit = SearchHit(sourceId, book.bookName, book.author, mapping.detailUrl,
            coverUrl = book.coverUrl, introduction = book.introduction)
        val fetched = try {
            detailsFetcher?.fetch(rule, source, hit, bookId)
                ?: SourceResult.Failure(SourceFailure.Unexpected("Details are unavailable"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            SourceResult.Failure(SourceFailure.Unexpected("Could not load details"))
        }
        // An uncooperative fetcher must not write after its caller has switched source or refreshed again.
        currentCoroutineContext().ensureActive()
        return when (fetched) {
            is SourceResult.Failure -> staleDetails(book, fetched.error)
            is SourceResult.Success -> try {
                val details = fetched.value
                val current = books.detailsAreCurrent(book, mapping)
                currentCoroutineContext().ensureActive()
                if (!current) {
                    staleDetails(books.getBook(bookId), SourceFailure.Unexpected("Details changed during refresh"))
                } else if (details.book.bookId != bookId) {
                    staleDetails(book, SourceFailure.Unexpected("Details belong to another local book"))
                } else if (details.identity.conflictsWith(book)) {
                    DetailsResult(book, fromCache = true, identity = details.identity,
                        warning = SourceFailure.Parse("book.identity", ""), identityMismatch = true)
                } else if (details.warning != null) {
                    DetailsResult(book, fromCache = true, identity = details.identity, warning = details.warning)
                } else {
                    val saved = books.saveDetails(book, mapping, details)
                    if (saved == null) staleDetails(books.getBook(bookId), SourceFailure.Unexpected("Details changed during refresh"))
                    else DetailsResult(saved, fromCache = false, identity = details.identity)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                staleDetails(book, SourceFailure.Storage("Could not verify or save details"))
            }
        }
    }

    override suspend fun catalog(bookId: String, sourceId: String, forceRefresh: Boolean): CatalogResult {
        val cached = store.observe(bookId, sourceId).first()
        if (cached.isNotEmpty() && !forceRefresh) return CatalogResult(cached, fromCache = true)
        val book = books.getBook(bookId)
            ?: return stale(cached, SourceFailure.UnsafeRule("Book is not saved"))
        val mapping = books.observeSources(bookId).first().firstOrNull { it.sourceId == sourceId }
            ?: return stale(cached, SourceFailure.UnsafeRule("Book has no matching source"))
        val source = rules.observeAll().first().firstOrNull { it.sourceId == sourceId }
            ?: return stale(cached, SourceFailure.UnsafeRule("Source rule is missing"))
        val rule = try {
            val parsed = parser.parse(source.rawJson) as? RuleParseResult.Success
            source.asExecutableRule(parsed?.rules?.singleOrNull()
                ?: throw IllegalArgumentException("Invalid stored source rule"))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return stale(cached, SourceFailure.UnsafeRule("Source rule is unavailable"))
        }
        val hit = SearchHit(sourceId, book.bookName, book.author, mapping.detailUrl,
            coverUrl = book.coverUrl, introduction = book.introduction)
        val fetched = try { fetcher.fetch(rule, source, hit, bookId) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { SourceResult.Failure(SourceFailure.Unexpected("Could not load catalog")) }
        return when (fetched) {
            is SourceResult.Failure -> stale(cached, fetched.error)
            is SourceResult.Success -> {
                val normalized = normalize(bookId, sourceId, fetched.value)
                if (normalized.isEmpty()) stale(cached, SourceFailure.Parse("toc.item", ""))
                else try {
                    store.replace(bookId, sourceId, normalized)
                    CatalogResult(normalized, fromCache = false)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    stale(cached, SourceFailure.Unexpected("Could not save catalog"))
                }
            }
        }
    }

    private fun normalize(bookId: String, sourceId: String, chapters: List<Chapter>): List<Chapter> = chapters
        .filter { !it.url.isNullOrBlank() && it.title.isNotBlank() }
        .distinctBy { it.url }
        .mapIndexed { index, chapter ->
            chapter.copy(bookId = bookId, sourceId = sourceId, chapterId = stableChapterId(requireNotNull(chapter.url)), chapterIndex = index)
        }

    private fun stableChapterId(url: String): String = MessageDigest.getInstance("SHA-256")
        .digest(url.toByteArray(Charsets.UTF_8)).take(20)
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun stale(chapters: List<Chapter>, warning: SourceFailure) =
        CatalogResult(chapters, fromCache = chapters.isNotEmpty(), warning = warning)

    private fun staleDetails(book: Book?, warning: SourceFailure) =
        DetailsResult(book, fromCache = book != null, warning = warning)
}
