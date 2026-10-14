package com.novelradar.app.feature.book

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookIdentityEvidence
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.repository.BookRepository
import com.novelradar.app.domain.repository.CatalogRepository
import com.novelradar.app.domain.repository.CatalogResult
import com.novelradar.app.domain.repository.DetailsResult
import com.novelradar.app.domain.repository.ReadingLocation
import com.novelradar.app.domain.repository.ShelfBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class BookDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test fun directoryObservesPersistedLocationWithoutWritingOrChangingReadTarget() = runTest {
        val books = FakeBooks()
        val vm = BookDetailViewModel("book-1", FakeCatalog(), books)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        books.locations.value = ReadingLocation("book-1", "a", "2", 99, 177, 100)
        advanceUntilIdle()
        assertThat(vm.uiState.value.currentChapterId).isEqualTo("2")
        assertThat(vm.uiState.value.positionLabel).isEqualTo("读到第 2 / 2 章")
        assertThat(vm.uiState.value.readingLocation?.offset).isEqualTo(177)
        vm.toggleChapterOrder()
        vm.onChapterQueryChanged("第二")
        advanceUntilIdle()
        assertThat(vm.uiState.value.currentChapterId).isEqualTo("2")
        assertThat(vm.uiState.value.positionLabel).isEqualTo("读到第 2 / 2 章")
        assertThat(vm.readingTarget()).isEqualTo(BookReadTarget("a", null))
        assertThat(vm.readingTarget(vm.uiState.value.visibleChapters.single())).isEqualTo(BookReadTarget("a", "2"))
        vm.selectSource("b")
        advanceUntilIdle()
        assertThat(vm.uiState.value.currentChapterId).isNull()
        assertThat(vm.uiState.value.positionLabel).isNull()
        books.locations.value = ReadingLocation("book-1", "b", "3", 0, 42, 101)
        advanceUntilIdle()
        assertThat(vm.uiState.value.currentChapterId).isEqualTo("3")
        assertThat(books.progressWrites).isEqualTo(0)
    }

    @Test fun directoryOnlyMarksExistingCurrentSameBookAndSourceAndKeepsLegacyHonest() {
        val state = BookDetailUiState(book = Book("book-1", "key", "书名", "作者"), selectedSourceId = "a",
            chapters = listOf(Chapter("book-1", "a", "first", 10, "第一章"),
                Chapter("book-1", "a", "tie-b", 30, "第三章"), Chapter("book-1", "a", "tie-a", 30, "第二章")),
            readingLocation = ReadingLocation("book-1", "a", "tie-b", 999, 77, 1))
        assertThat(state.currentChapterId).isEqualTo("tie-b")
        assertThat(state.positionLabel).isEqualTo("读到第 3 / 3 章")
        for (format in listOf(ChapterContentFormat.LEGACY, 999)) {
            val legacy = state.copy(readingLocation = state.readingLocation!!.copy(contentFormatVersion = format))
            assertThat(legacy.currentChapterId).isNull()
            assertThat(legacy.positionLabel).isEqualTo("原阅读位置 第 1000 章（待核对）")
        }
        assertThat(state.copy(chapters = emptyList()).currentChapterId).isNull()
        assertThat(state.copy(chapters = emptyList()).positionLabel).contains("待核对")
        assertThat(state.copy(readingLocation = state.readingLocation!!.copy(chapterId = "removed")).currentChapterId).isNull()
        assertThat(state.copy(readingLocation = state.readingLocation.copy(bookId = "foreign")).positionLabel).isNull()
        assertThat(state.copy(readingLocation = null).positionLabel).isNull()
    }

    @Test fun progressReadFailureDoesNotBlockCatalogOrOverwriteShelfNotice() = runTest {
        val release = CompletableDeferred<Unit>()
        val books = FakeBooks().apply { progressFailure = { release.await(); error("read failed") } }
        val vm = BookDetailViewModel("book-1", FakeCatalog(), books)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.addToShelf()
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.uiState.value.notice).isEqualTo("已加入书架")
        assertThat(vm.readingTarget()).isEqualTo(BookReadTarget("a", null))
        assertThat(books.progressWrites).isEqualTo(0)
    }

    @Test fun confirmedWrongBookRetainsCatalogButRejectsReadingAndDownloadTarget() = runTest {
        val catalog = FakeCatalog().apply { details = { _, book -> DetailsResult(book, true,
            identity = BookIdentityEvidence(book.bookName, "其他作者"),
            warning = SourceFailure.Parse("book.identity", ""), identityMismatch = true) } }
        val vm = BookDetailViewModel("book-1", catalog, FakeBooks())
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.sourceIdentityMismatch).isTrue()
        assertThat(vm.uiState.value.chapters).hasSize(2)
        assertThat(vm.readingTarget()).isNull()
        assertThat(vm.readingTarget(vm.uiState.value.chapters.first())).isNull()
    }

    @Test fun confirmedMismatchSurvivesUnknownFailureAndReselectionUntilFullPageMatch() = runTest {
        val catalog = FakeCatalog().apply { details = { _, book -> DetailsResult(book, true, identityMismatch = true) } }
        val vm = BookDetailViewModel("book-1", catalog, FakeBooks())
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        catalog.details = { _, book -> DetailsResult(book, false, identity = BookIdentityEvidence(title = book.bookName)) }
        vm.refresh()
        advanceUntilIdle()
        assertThat(vm.readingTarget()).isNull()
        catalog.details = { _, book -> DetailsResult(book, true, warning = SourceFailure.Timeout) }
        vm.refresh()
        advanceUntilIdle()
        assertThat(vm.readingTarget()).isNull()
        vm.selectSource("b")
        advanceUntilIdle()
        assertThat(vm.readingTarget()).isEqualTo(BookReadTarget("b", null))
        vm.selectSource("a")
        advanceUntilIdle()
        assertThat(vm.readingTarget()).isNull()
        catalog.details = { _, book -> DetailsResult(book, false,
            identity = BookIdentityEvidence("星 河旅人", "叶 子")) }
        vm.refresh()
        advanceUntilIdle()

        assertThat(vm.uiState.value.sourceIdentityMismatch).isFalse()
        assertThat(vm.readingTarget()).isEqualTo(BookReadTarget("a", null))
    }

    @Test fun lateMismatchFromOldSourceCannotBlockSelectedSource() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val catalog = FakeCatalog().apply { details = { source, book ->
            if (source == "a") {
                withContext(NonCancellable) { started.complete(Unit); release.await() }
                DetailsResult(book, true, identityMismatch = true)
            } else DetailsResult(book, false, identity = BookIdentityEvidence(book.bookName, book.author))
        } }
        val vm = BookDetailViewModel("book-1", catalog, FakeBooks())
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        vm.selectSource("b")
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        assertThat(started.isCompleted).isTrue()
        assertThat(vm.uiState.value.sourceIdentityMismatch).isFalse()
        assertThat(vm.readingTarget()).isEqualTo(BookReadTarget("b", null))
    }

    @Test fun lateMismatchFromOlderSameSourceRefreshCannotBlockNewMatch() = runTest {
        val catalog = FakeCatalog()
        val vm = BookDetailViewModel("book-1", catalog, FakeBooks())
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        var requests = 0
        catalog.details = { _, book ->
            requests++
            if (requests == 1) {
                withContext(NonCancellable) { started.complete(Unit); release.await() }
                DetailsResult(book, true, identityMismatch = true)
            } else DetailsResult(book, false, identity = BookIdentityEvidence(book.bookName, book.author))
        }
        vm.refresh()
        runCurrent()
        vm.refresh()
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        assertThat(started.isCompleted).isTrue()
        assertThat(vm.uiState.value.sourceIdentityMismatch).isFalse()
        assertThat(vm.readingTarget()).isEqualTo(BookReadTarget("a", null))
    }

    @Test fun cachedCatalogIsReadableWhileRemoteDetailsAreStillPending() = runTest {
        val release = CompletableDeferred<Unit>()
        val catalog = FakeCatalog().apply { details = { _, book -> release.await(); DetailsResult(book, false) } }
        val vm = BookDetailViewModel("book-1", catalog, FakeBooks())
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()

        assertThat(vm.uiState.value.detailsLoading).isTrue()
        assertThat(vm.uiState.value.loading).isFalse()
        assertThat(vm.readingTarget()).isEqualTo(BookReadTarget("a", null))
        assertThat(vm.uiState.value.book?.introduction).isEqualTo("已保存简介")
        release.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.uiState.value.detailsLoading).isFalse()
    }

    @Test fun explicitRefreshKeepsSuccessfulDetailsWhenCatalogFails() = runTest {
        val catalog = FakeCatalog()
        val vm = BookDetailViewModel("book-1", catalog, FakeBooks())
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        catalog.failRefresh = true
        catalog.details = { _, book -> DetailsResult(book.copy(introduction = "新简介"), false) }

        vm.refresh()
        advanceUntilIdle()

        assertThat(vm.uiState.value.book?.introduction).isEqualTo("新简介")
        assertThat(vm.uiState.value.chapters).hasSize(2)
        assertThat(vm.uiState.value.warning).isEqualTo(SourceFailure.Timeout)
        assertThat(vm.uiState.value.detailsWarning).isNull()
        assertThat(vm.uiState.value.refreshing).isFalse()
    }

    @Test fun explicitRefreshKeepsSuccessfulCatalogWhenDetailsFail() = runTest {
        val catalog = FakeCatalog()
        val vm = BookDetailViewModel("book-1", catalog, FakeBooks())
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        catalog.refreshedTitle = "刷新目录章节"
        catalog.details = { _, book -> DetailsResult(book, true, warning = SourceFailure.Timeout) }

        vm.refresh()
        advanceUntilIdle()

        assertThat(vm.uiState.value.book?.introduction).isEqualTo("已保存简介")
        assertThat(vm.uiState.value.chapters.first().title).isEqualTo("刷新目录章节")
        assertThat(vm.uiState.value.warning).isNull()
        assertThat(vm.uiState.value.detailsWarning).isEqualTo(SourceFailure.Timeout)
        assertThat(vm.readingTarget()).isEqualTo(BookReadTarget("a", null))
    }

    @Test fun lateOldSourceDetailsCannotOverwriteNewSourcePage() = runTest {
        val release = CompletableDeferred<Unit>()
        val started = CompletableDeferred<Unit>()
        val catalog = FakeCatalog().apply { details = { source, book ->
            if (source == "a") withContext(NonCancellable) { started.complete(Unit); release.await() }
            DetailsResult(book.copy(introduction = "$source 简介"), false)
        } }
        val vm = BookDetailViewModel("book-1", catalog, FakeBooks())
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertThat(started.isCompleted).isTrue()
        vm.selectSource("b")
        runCurrent()
        assertThat(vm.uiState.value.book?.introduction).isEqualTo("b 简介")
        release.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.uiState.value.selectedSourceId).isEqualTo("b")
        assertThat(vm.uiState.value.book?.introduction).isEqualTo("b 简介")
        assertThat(vm.uiState.value.chapters.single().sourceId).isEqualTo("b")
    }

    @Test fun lateOlderRefreshOfSameSourceCannotOverwriteNewerDetails() = runTest {
        val catalog = FakeCatalog()
        val vm = BookDetailViewModel("book-1", catalog, FakeBooks())
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        catalog.details = { _, book ->
            requests++
            val current = requests
            if (current == 1) withContext(NonCancellable) { release.await() }
            DetailsResult(book.copy(introduction = "第$current 次简介"), false)
        }
        vm.refresh()
        runCurrent()
        vm.refresh()
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()

        assertThat(vm.uiState.value.book?.introduction).isEqualTo("第2 次简介")
        assertThat(vm.uiState.value.refreshing).isFalse()
    }

    @Test fun loadsFirstSourceThenSwitchesAndFiltersIndependentCatalog() = runTest {
        val catalog = FakeCatalog()
        val vm = BookDetailViewModel("book-1", catalog, FakeBooks())
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        assertThat(vm.uiState.value.book?.bookName).isEqualTo("星河旅人")
        assertThat(vm.uiState.value.selectedSourceId).isEqualTo("a")
        assertThat(vm.uiState.value.sourceNames["b"]).isEqualTo("书源乙")
        assertThat(vm.uiState.value.chapters.map { it.title }).containsExactly("第一章", "第二章")

        vm.selectSource("b")
        advanceUntilIdle()
        assertThat(vm.uiState.value.selectedSourceId).isEqualTo("b")
        assertThat(vm.uiState.value.chapters.map { it.title }).containsExactly("番外")
        vm.onChapterQueryChanged("番")
        assertThat(vm.uiState.value.visibleChapters.map { it.title }).containsExactly("番外")
        collector.cancel()
    }

    @Test fun refreshWarningRetainsCachedChaptersAndShelfActionPersists() = runTest {
        val catalog = FakeCatalog()
        val books = FakeBooks()
        val vm = BookDetailViewModel("book-1", catalog, books)
        val collector = backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()

        catalog.failRefresh = true
        vm.refresh()
        vm.addToShelf()
        advanceUntilIdle()

        assertThat(vm.uiState.value.chapters).hasSize(2)
        assertThat(vm.uiState.value.warning).isEqualTo(SourceFailure.Timeout)
        assertThat(vm.uiState.value.addedToShelf).isTrue()
        assertThat(books.added).isEqualTo("book-1")
        collector.cancel()
    }

    @Test fun preferredSourceRestoresAndSelectedSourceIsStoredWhenAdded() = runTest {
        val books = FakeBooks().apply { preferred = "b" }
        val vm = BookDetailViewModel("book-1", FakeCatalog(), books)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        assertThat(vm.uiState.value.selectedSourceId).isEqualTo("b")
        vm.selectSource("a")
        vm.addToShelf()
        advanceUntilIdle()
        assertThat(books.addedSource).isEqualTo("a")
        assertThat(books.preferred).isEqualTo("a")
    }

    @Test fun choosingSourcePersistsBeforeReadingAndReopening() = runTest {
        val books = FakeBooks().apply { preferred = "a" }
        val vm = BookDetailViewModel("book-1", FakeCatalog(), books)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        vm.selectSource("b")
        advanceUntilIdle()
        assertThat(books.preferred).isEqualTo("b")
        val reopened = BookDetailViewModel("book-1", FakeCatalog(), books)
        backgroundScope.launch { reopened.uiState.collect {} }
        advanceUntilIdle()
        assertThat(reopened.uiState.value.selectedSourceId).isEqualTo("b")
    }

    @Test fun failedSourceSelectionKeepsCommittedSourceAndCatalogAndShowsNotice() = runTest {
        val books = FakeBooks().apply { preferred = "a" }
        val vm = BookDetailViewModel("book-1", FakeCatalog(), books)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        books.beforeSelection = { error("disk full") }
        vm.selectSource("b")
        advanceUntilIdle()
        assertThat(vm.uiState.value.selectedSourceId).isEqualTo("a")
        assertThat(vm.uiState.value.chapters).hasSize(2)
        assertThat(vm.uiState.value.notice).isEqualTo("书源选择保存失败，请重试")
        assertThat(books.preferred).isEqualTo("a")
    }

    @Test fun slowSelectionThenNewChoiceAndImmediateShelfAddRemainOrdered() = runTest {
        val books = FakeBooks().apply { preferred = "a" }
        val vm = BookDetailViewModel("book-1", FakeCatalog(), books)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        books.beforeSelection = { source -> if (source == "b") { started.complete(Unit); release.await() } }
        vm.selectSource("b")
        runCurrent()
        assertThat(started.isCompleted).isTrue()
        vm.selectSource("a")
        vm.addToShelf()
        runCurrent()
        assertThat(books.addedSource).isNull()
        release.complete(Unit)
        advanceUntilIdle()
        assertThat(books.preferred).isEqualTo("a")
        assertThat(vm.uiState.value.selectedSourceId).isEqualTo("a")
        assertThat(books.addedSource).isEqualTo("a")
    }

    @Test fun pickerWaitsForInitialPreferenceRestoreToAvoidOverwritingNewChoice() = runTest {
        val release = CompletableDeferred<Unit>()
        val books = FakeBooks().apply { preferred = "a"; beforePreferred = { release.await() } }
        val vm = BookDetailViewModel("book-1", FakeCatalog(), books)
        backgroundScope.launch { vm.uiState.collect {} }
        runCurrent()
        assertThat(vm.uiState.value.sources).isEmpty()
        release.complete(Unit)
        advanceUntilIdle()
        vm.selectSource("b")
        advanceUntilIdle()
        assertThat(books.preferred).isEqualTo("b")
        assertThat(vm.uiState.value.selectedSourceId).isEqualTo("b")
    }

    @Test fun pendingSelectionRejectsImmediateReadThenUsesCommittedSourceAndRejectsStaleChapter() = runTest {
        val books = FakeBooks().apply { preferred = "a" }
        val vm = BookDetailViewModel("book-1", FakeCatalog(), books)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        val staleChapter = vm.uiState.value.chapters.first()
        val release = CompletableDeferred<Unit>()
        books.beforeSelection = { release.await() }
        vm.selectSource("b")
        assertThat(vm.readingTarget()).isNull()
        assertThat(vm.canLeave()).isFalse()
        assertThat(vm.readingTarget(staleChapter)).isNull()
        runCurrent()
        release.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.readingTarget()).isEqualTo(BookReadTarget("b", null))
        assertThat(vm.canLeave()).isTrue()
        assertThat(vm.readingTarget(staleChapter)).isNull()
        assertThat(vm.readingTarget(vm.uiState.value.chapters.single())).isEqualTo(BookReadTarget("b", "3"))
    }

    @Test fun failedPendingSelectionReadResolvesPreviousCommittedSource() = runTest {
        val books = FakeBooks().apply { preferred = "a" }
        val vm = BookDetailViewModel("book-1", FakeCatalog(), books)
        backgroundScope.launch { vm.uiState.collect {} }
        advanceUntilIdle()
        books.beforeSelection = { error("disk full") }
        vm.selectSource("b")
        assertThat(vm.readingTarget()).isNull()
        assertThat(vm.canLeave()).isFalse()
        advanceUntilIdle()
        assertThat(vm.readingTarget()).isEqualTo(BookReadTarget("a", null))
        assertThat(vm.canLeave()).isTrue()
        assertThat(vm.readingTarget(Chapter("other-book", "a", "1", 0, "foreign"))).isNull()
        assertThat(vm.readingTarget(Chapter("book-1", "a", "absent", 9, "absent"))).isNull()
    }

    private class FakeCatalog : CatalogRepository {
        var failRefresh = false
        var refreshedTitle: String? = null
        var details: suspend (String, Book) -> DetailsResult = { _, book -> DetailsResult(book, true) }
        override suspend fun book(bookId: String) = Book("book-1", "星河旅人|叶子", "星河旅人", "叶子", introduction = "已保存简介")
        override suspend fun refreshDetails(bookId: String, sourceId: String) = details(sourceId, book(bookId))
        override suspend fun sourceNames() = mapOf("a" to "书源甲", "b" to "书源乙")
        override fun sources(bookId: String): Flow<List<BookSource>> = MutableStateFlow(listOf(
            BookSource("book-1", "a", "https://example.org/a"),
            BookSource("book-1", "b", "https://example.org/b"),
        ))
        override fun observeChapters(bookId: String, sourceId: String): Flow<List<Chapter>> = MutableStateFlow(emptyList())
        override suspend fun catalog(bookId: String, sourceId: String, forceRefresh: Boolean): CatalogResult {
            val chapters = if (sourceId == "a") listOf(
                Chapter(bookId, sourceId, "1", 0, if (forceRefresh) refreshedTitle ?: "第一章" else "第一章", "https://example.org/1"),
                Chapter(bookId, sourceId, "2", 1, "第二章", "https://example.org/2"),
            ) else listOf(Chapter(bookId, sourceId, "3", 0, "番外", "https://example.org/3"))
            return CatalogResult(chapters, fromCache = failRefresh,
                warning = if (failRefresh && forceRefresh) SourceFailure.Timeout else null)
        }
    }

    private class FakeBooks : BookRepository {
        val locations = MutableStateFlow<ReadingLocation?>(null)
        var progressFailure: (suspend () -> Unit)? = null
        var progressWrites = 0
        var added: String? = null
        var addedSource: String? = null
        var preferred: String? = null
        var beforeSelection: suspend (String) -> Unit = {}
        var beforePreferred: suspend () -> Unit = {}
        override suspend fun preferredSource(bookId: String): String? { beforePreferred(); return preferred }
        override suspend fun selectPreferredSource(bookId: String, sourceId: String) {
            beforeSelection(sourceId); preferred = sourceId
        }
        override suspend fun addToShelf(bookId: String, atEpochMillis: Long, sourceId: String) {
            added = bookId; addedSource = sourceId; preferred = sourceId
        }
        override suspend fun saveSearchHit(hit: SearchHit) = Book("book-1", "x", "星河旅人", "叶子")
        override suspend fun getBook(bookId: String) = Book("book-1", "x", "星河旅人", "叶子")
        override fun observeSources(bookId: String): Flow<List<BookSource>> = MutableStateFlow(emptyList())
        override fun observeShelf(): Flow<List<ShelfBook>> = MutableStateFlow(emptyList())
        override suspend fun addToShelf(bookId: String, atEpochMillis: Long) { added = bookId }
        override suspend fun removeFromShelf(bookId: String) = Unit
        override fun progress(bookId: String): Flow<ReadingLocation?> = kotlinx.coroutines.flow.flow {
            progressFailure?.invoke()
            locations.collect { emit(it) }
        }
        override suspend fun saveProgress(location: ReadingLocation) { progressWrites++ }
    }

    class MainDispatcherRule : TestWatcher() {
        private val dispatcher = StandardTestDispatcher()
        override fun starting(description: Description) { Dispatchers.setMain(dispatcher) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }
}
