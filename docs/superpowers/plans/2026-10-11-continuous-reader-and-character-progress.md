# Continuous Reader and Character Progress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Read continuously across separately verified chapters in both modes, showing their titles, actual catalog position and per-chapter Unicode character progress, while reusing permitted upcoming requests.

**Architecture:** A small bounded chapter window is separate from the active, actually visible chapter. Background preparation may populate that window but never writes reading progress. Existing Room progress, exact-save serialization, UTF-16 offsets and legacy recovery remain the single persistence path.

**Tech Stack:** Existing Kotlin coroutines/StateFlow, Compose LazyColumn/HorizontalPager, atomic chapter repository, JUnit and controlled MockWebServer integration, compiled Android UI tests.

---

## Dependency and execution boundary

This is the next phase of the user-approved repair design, not a new product-design checkpoint. The original source boundary repair and provenance Tasks 1 and 2 have passed independent specification and quality review. **Do not implement this plan until provenance Task 3 passes both reviews and the focused `2026-10-12-explicit-fraction-page-suffix.md` follow-up is verified and reviewed.** Re-read their final interfaces before dispatching these tasks and adjust this document if needed; never bypass `positionNeedsReview` to enable continuity.

Use a fresh implementer for each task, then specification review followed by quality review. Only one app/test writer and one Gradle owner at a time. Root writes documentation only. No stable APK is published between partial stages. No changes to source boundary rules, chapter IDs, book identities, migration schemas, download task generations, network protection, selected icon or palette.

The user requested a test APK first once this repair is complete. Complete and review this repair batch, then deliver the separate debug APK described in Task 4; do not wait for unrelated planned product features, and do not publish an intermediate build that lacks the approved repair behavior.

## File responsibilities

- `domain/repository/ChapterRepository.kt`: one safe preparation operation returning a verified individual chapter, not a foreground-network bypass.
- `data/repository/DefaultChapterRepository.kt`: reuse existing per-chapter locks and metered checks for that operation; preserve the reviewed legacy body logic.
- `feature/reader/ChapterReadingProgress.kt`: pure Unicode display conversion and explicit chapter/offset event type.
- `feature/reader/ReadingWindowPolicy.kt`: pure catalog ordering and bounded neighbor selection, independent of fetching or progress writes.
- `ReaderUiState.kt`: bounded prepared chapters and continuation status, with existing `content`/`offset` representing the active visible chapter.
- `ReaderViewModel.kt`: foreground entry, visible-position acceptance, continuation loading and at most two permitted preparation jobs.
- `ScrollReader.kt` / `PagedReader.kt`: stable per-chapter presentation, actual visible position and deliberate boundary navigation.
- `ReaderScreen.kt` / `ReaderControls.kt` / `app/NovelRadarApp.kt`: route the typed position/continuation events, show chapter progress and locate the current catalog row.
- `feature/book/BookDetailUiState.kt` / `BookDetailViewModel.kt` / `BookDetailScreen.kt`: observe the existing persisted location and mark the same-source current chapter.
- `feature/library/LibraryUiState.kt` / `LibraryScreen.kt`: retain existing subscription, label chapter position honestly instead of implying exact whole-book character completion.
- `data/db/BookDao.kt` / `ShelfProjection.kt`, `domain/repository/BookRepository.kt`, `data/repository/RoomBookRepository.kt` in Task 4 only: expose the nullable actual catalog ordinal of the saved same-source chapter as a read projection. This is not an entity/schema/migration change or a second progress store.

All paths above are under `app/src/main/java/com/novelradar/app/`. Tests live under their existing mirrored `app/src/test/java/` or `app/src/androidTest/java/` packages. A focused HTTP/reader integration test may be added under the reader test package; announce its exact path and responsibility first.

## Task 1: Safe preparation seam and pure progress/window contracts

**Tests:** create `app/src/test/java/com/novelradar/app/feature/reader/ChapterReadingProgressTest.kt` and `ReadingWindowPolicyTest.kt`; extend the existing `data/repository/DefaultChapterRepositoryTest.kt` for the real preparation operation.

- [ ] **Step 1: Add and observe failing contracts before implementing them**

Use independent assertions for Unicode conversion, valid UTF-16 bounds and bounded catalog neighbors. For example, `"甲😀乙\n"` has five UTF-16 units but four displayed characters. At offset three, two displayed characters have been read. An offset inside the surrogate pair must not count half an emoji; normalize the display prefix to before that pair without changing stored offsets. Empty content has zero read/total and zero percent, not a division error. Include punctuation, blank lines, supplementary characters, negative/oversized input, reordered/non-contiguous catalog indices and first/last chapter.

```kotlin
data class ChapterReadingPosition(val chapterId: String, val offset: Int)

data class ChapterCharacterProgress(val read: Int, val total: Int) {
    val percent: Int get() = if (total == 0) 0 else
        (read.toLong() * 100 / total).toInt().coerceIn(0, 100)
}

fun chapterCharacterProgress(text: String, utf16Offset: Int): ChapterCharacterProgress {
    var prefixEnd = utf16Offset.coerceIn(0, text.length)
    if (prefixEnd in 1 until text.length && text[prefixEnd].isLowSurrogate() &&
        text[prefixEnd - 1].isHighSurrogate()) prefixEnd--
    return ChapterCharacterProgress(text.codePointCount(0, prefixEnd), text.codePointCount(0, text.length))
}
```

The code above defines the display contract, not a new persistence format. It may serve as a small-input/reference function; UI must not call its full count repeatedly. Add a pure sparse `ChapterCharacterIndex` in the same focused file, memoized by immutable body identity. Its total is computed once, and each position lookup scans at most one 256-unit block (257 when its boundary crosses a surrogate pair). This adds roughly one Int per 256 UTF-16 units, not a whole-book word index or an array per character.

```kotlin
class ChapterCharacterIndex(private val text: String) {
    private val checkpoints = IntArray(text.length / 256 + 1)
    val total: Int

    init {
        var previousEnd = 0
        var count = 0
        for (block in checkpoints.indices) {
            val end = boundedPrefix(block * 256)
            count += text.codePointCount(previousEnd, end)
            checkpoints[block] = count
            previousEnd = end
        }
        total = count + text.codePointCount(previousEnd, text.length)
    }

    fun progress(utf16Offset: Int): ChapterCharacterProgress {
        val end = boundedPrefix(utf16Offset)
        val block = end / 256
        val start = boundedPrefix(block * 256)
        return ChapterCharacterProgress(checkpoints[block] + text.codePointCount(start, end), total)
    }

    private fun boundedPrefix(offset: Int): Int {
        var end = offset.coerceIn(0, text.length)
        if (end in 1 until text.length && text[end].isLowSurrogate() && text[end - 1].isHighSurrogate()) end--
        return end
    }
}
```

Test the sparse index against the direct reference function at every offset of mixed Unicode samples, particularly a pair straddling unit 256, empty text and long bodies. Tests precede implementation; the snippets define the required interface/algorithm. Do not add production test-only counters.

```kotlin
@Test fun displayedCharactersUseCodePointsWhileOffsetsRemainUtf16() {
    val text = "甲😀乙\n"
    assertThat(text.length).isEqualTo(5)
    assertThat(chapterCharacterProgress(text, 3)).isEqualTo(ChapterCharacterProgress(2, 4))
    assertThat(chapterCharacterProgress(text, 2)).isEqualTo(ChapterCharacterProgress(1, 4))
    assertThat(chapterCharacterProgress(text, text.length).percent).isEqualTo(100)
}
```

Run focused tests with the established flags. Expected RED is an assertion exposing absent behavior; minimal type scaffolding/reflection is allowed when necessary to get past compilation, but it must not pre-implement the behavior being tested.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.novelradar.app.feature.reader.ChapterReadingProgressTest' --tests 'com.novelradar.app.feature.reader.ReadingWindowPolicyTest' --tests 'com.novelradar.app.data.repository.DefaultChapterRepositoryTest' --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

- [ ] **Step 2: Add a fail-closed verified preparation operation**

Append this operation to `ChapterRepository`, with the default returning a typed failure so older simple doubles cannot silently issue ordinary network loads:

```kotlin
suspend fun prepareChapter(bookId: String, sourceId: String, chapterId: String): SourceResult<LoadedChapter> =
    SourceResult.Failure(SourceFailure.UnsafeRule("Chapter preparation is unavailable"))
```

The real repository delegates to its existing protected `prefetch = true` path and refuses an unverified legacy fallback. `prefetchNext` uses this operation without changing its count limit or network checks. A current, hash-valid cached chapter may be returned without network even when metered prefetch is disabled. A missing/legacy chapter cannot trigger a preparation fetch on a disallowed network, including after waiting for its lock. Foreground `loadChapter` remains the deliberate reading path.

Tests must use the actual repository and atomic store for cache/policy behavior, including preparation racing a foreground request for the same chapter: one fetch, both see verified data, no progress writes. Preserve cancellation and book deletion barriers. Do not add repository-global network locks or change downloader limits.

- [ ] **Step 3: Implement the bounded neighbor policy and verify GREEN**

Order actual catalog entries by index then ID, not by assuming chapter indices are dense. The retained window contains at most the previous chapter, active chapter, and next two chapters: four bodies maximum, each still subject to the existing body bounds. Only already-loaded/verified candidates are retained; a missing neighbor is not a fake loaded chapter. The active chapter must never be evicted. User prefetch setting limits speculative forward preparation to zero, one or two; it does not forbid a deliberate read into the next chapter.

```kotlin
fun readingWindowIds(catalog: List<Chapter>, activeChapterId: String): Set<String> {
    val ordered = catalog.sortedWith(compareBy(Chapter::chapterIndex, Chapter::chapterId))
    val active = ordered.indexOfFirst { it.chapterId == activeChapterId }
    if (active < 0) return emptySet()
    return ordered.subList((active - 1).coerceAtLeast(0), (active + 3).coerceAtMost(ordered.size))
        .mapTo(linkedSetOf()) { it.chapterId }
}
```

- [ ] **Step 4: Full verification, explicit commit and independent reviews**

Run all four milestone tasks, read XML/SARIF and self-review; commit exact scoped production/test files. Release all writer/build ownership only after processes exit. Root independently verifies and requests both reviews before Task 2.

## Task 2: Visible chapter state, bounded continuation and request reuse

- [ ] **Step 1: Add and observe ViewModel failures for actual visibility and lifecycle**

Extend existing tests rather than replacing them. Cover preparation of chapter two without changing `content`, `offset`, catalog or persisted progress; an accepted visible event for verified chapter two changes all of those together. Test first/last chapter, forward/backward events, unknown/unloaded chapter IDs, stale layout/selection callbacks, restoration/font/mode changes, loading/saving failures, close/stop races, legacy recovery gate, retry target and no automatic source switch.

Also retain a controlled integration case in which the old trusted exact save is suspended and the retained old chapter scrolls before an unverified replacement arrives. The Task 3 quality reviewer found no production defect in the actual Main.immediate ordering, but recommended documenting that generation/collector ordering as the continuous coordinator evolves. Use the real progress repository and low-level storage synchronization, not a mocked debounce result or an artificial high-level collector claimed to prove phone behavior.

Typed events carry the real chapter ID and UTF-16 offset:

```kotlin
fun onVisiblePosition(positionVersion: Int, position: ChapterReadingPosition)
fun continueForward(positionVersion: Int)
fun continueBackward(positionVersion: Int)
```

The implementation checks the retained session, version, book/source ownership and actual verified window membership. An active recovery gate cannot be cleared or written by any automatic visible/continuation event. Manual selection and explicit confirmation keep the separately reviewed recovery semantics.

- [ ] **Step 2: Keep loading state separate from active reading state**

Append bounded prepared bodies and continuation status to `ReaderUiState`. Existing `content` remains the active visible chapter, not the most recently fetched chapter. Foreground manual selection preserves the existing exact save-before-change and initial-entry behavior. Scroll continuation appends a verified neighbor without moving the active chapter until it is visible. Deliberate paged boundary navigation may transition after successful loading because the user has requested that page; failed loading retains the original body/title/offset and exposes retry.

Window append/removal must not increment the restoration epoch or move the scroll/pager anchor. Explicit chapter selection, layout change and session reset still invalidate stale callbacks. If a separate restoration token is needed, keep it in the reader state and test both token responsibilities; do not use the active chapter change itself as a request to restore to its beginning.

When the actual visible chapter changes, serialize its exact CURRENT position through the existing save path, rejecting late prior events; within one chapter keep the existing 750 ms changes. Never emit a saved location from preparation completion or speculative window insertion. Save failures keep the reader usable and visibly retryable, and do not silently label an unwritten location as persisted.

- [ ] **Step 3: Reuse at most two relevant preparation jobs**

Track jobs by actual upcoming chapter ID, retaining still-relevant jobs when the user advances into their target. A foreground request must share the repository's per-chapter lock/fetch with that in-flight preparation rather than cancel/restart it. After actual advancement, reconcile to the next zero/one/two IDs. Cancel jobs made irrelevant by a distant manual jump, leaving, deletion or disabling/restricting preferences. Do not launch unbounded generations of jobs or wait for chapter three before displaying already-ready chapter two.

Use `prepareChapter` for speculative work, never ordinary `loadChapter` to evade metered preferences. With prefetch off or disallowed, crossing the visible boundary is a deliberate foreground continuation and may show a loading edge; merely restoring, repaginating or appending layout may not fetch the following chapter. An unverified body cannot enter the trusted continuous window.

- [ ] **Step 4: Add fixed-delay real HTTP integration and verify GREEN**

Use a controlled delayed server, actual boundary engine/parser, rule-based fetcher, atomic store and real repository with low-level storage doubles. Assert exact request counts, no known-next-page misfetch, shared in-flight next chapter, cache reads independent from another stalled chapter, no progress changes on cache completion, actual position changes when crossing, cancellation and disabled/metered preparation. Prefer synchronization gates and bounded waits over fragile absolute speed claims. This proves reduced repeated work, not every real source loading in a fixed number of seconds.

- [ ] **Step 5: Full verification, explicit commit and independent reviews**

Run the full four-task command and inspect reports. Commit only scoped reader/seam/integration test files. Both reviews must pass before UI consumes the new event model.

## Task 3: Scroll/paged continuity, titles, catalog and character display

- [ ] **Step 1: Add and observe presentation helper failures and compile real UI regressions**

Each presented item/page must retain its chapter ID, title and original offset. Tests cover two distinct chapters with identical body text and overlapping local offsets, visible chapter mapping, title rows, stable keys, restored offsets, empty chapters, supplementary characters and bounded presentation. Compose device tests exercise actual user scroll/swipe/tap, not a high-level fake screen. Compile these tests even when no device is available, and state that runtime remains unverified.

- [ ] **Step 2: Render a stable chapter window in scrolling mode**

Use stable composite keys, for example `"chapter:$chapterId:title"` and `"chapter:$chapterId:text:$startOffset"`; never use only local offsets across chapters. Each chapter displays its own heading. Map the first relevant visible item and line to `ChapterReadingPosition`. Appending prepared chapters or removing a far previous chapter preserves the anchored item and its line offset. Do not restart restoration simply because the active chapter updates.

Approaching the end may use already permitted prepared content. Reaching the reading edge can request deliberate continuation; setting prefetch to zero must not cause background fetch merely because an edge is laid out. Loading/error/retry appears at the edge, current content stays readable, and the final chapter ends without a phantom next request. Keep the central toolbar gesture and readable theme/fonts.

- [ ] **Step 3: Render paging across individual chapter pages**

Build measured pages per chapter and keep their chapter/offset identity. At the last page, continuing a forward swipe or right-edge tap enters the next verified chapter and shows its title. Backward boundary navigation remains usable. If a neighbor is not ready, a deliberate reading-edge page/action requests it and keeps the current chapter/progress until success; failure permits retry. Restoration, page-count changes, font/margin/mode changes and rotation must not be mistaken for user boundary navigation. Preserve the exact restored character anchor rather than overwriting it with a page start.

- [ ] **Step 4: Connect typed callbacks and show one consistent reading position**

`NovelRadarApp.ReaderRoute` routes `ChapterReadingPosition` with the applicable position version; layout collectors use the current callback without leaking a previous session. Reader catalog highlights the active chapter and initially scrolls to it when opened. Top title, catalog highlight and per-chapter display derive from the same active state.

```kotlin
val characterIndex = remember(loaded.content) { ChapterCharacterIndex(loaded.content) }
val shown = characterIndex.progress(state.offset)
val label = "${shown.read} / ${shown.total} 字 · 本章 ${shown.percent}% · " +
    "第 ${state.catalogPosition + 1} / ${state.chapters.size} 章"
```

Memoize character counts appropriately. Hide precise character percentages for unverified legacy content or a pending position review, retaining the existing recovery notice/actions. Page number may remain as supplementary layout information; it is not persisted character progress. No exact whole-book character percentage from partial caches.

- [ ] **Step 5: Full verification, explicit commit and independent reviews**

Run all unit tests, lint, debug assembly and Android-test source compilation. Review both-mode integration, restoration and stale callback tests. Do not claim compiled Compose tests ran on a phone. Both reviews must pass before Task 4.

## Task 4: Persisted directory/library display and repaired test APK

- [ ] **Step 1: Add and observe same-source persisted-position display failures**

Current `BookDetailViewModel` does not subscribe to reading progress and its directory rows have no current marker. Add a subscription to existing `books.progress(bookId)` and an appended `readingLocation: ReadingLocation?` state field. A progress read failure must not overwrite source-selection/detail/catalog notices or block ordinary source operations. No writes or second store here.

```kotlin
val currentChapterId: String? get() = readingLocation
    ?.takeIf { it.bookId == book?.bookId && it.sourceId == selectedSourceId }
    ?.chapterId
```

Tests cover reader-updated chapter reflected without pressing next again, same-source identity, source switch excluding another source's position, absent/legacy records, search/order changes retaining the actual marker and source write ordering unchanged. Library already observes the shelf projection; exercise real progress-to-shelf mapping and ensure preparation alone does not move its display.

Root checked the actual shelf query and model: they currently derive position from the saved raw `chapterIndex`, which can be stale or outside a refreshed catalog. Do not clamp that into a fake 100%, show “9 / 3”, or assume non-contiguous indices are ordinal positions. Append `val readingChapterPosition: Int? = null` to both `ShelfProjection` and `ShelfBook`, and explicitly map it in `RoomBookRepository.observeShelf`. The existing Room query keeps its counts, chosen-source fallback and all old aliases, and adds this read-only join/column:

```sql
CASE WHEN current_chapter.chapterId IS NOT NULL THEN (
    SELECT COUNT(*) FROM chapters position_chapter
    WHERE position_chapter.bookId = b.bookId AND position_chapter.sourceId = bs.sourceId
      AND (position_chapter.chapterIndex < current_chapter.chapterIndex
        OR (position_chapter.chapterIndex = current_chapter.chapterIndex
          AND position_chapter.chapterId <= current_chapter.chapterId))
) ELSE NULL END AS readingChapterPosition
```

```sql
LEFT JOIN chapters current_chapter ON current_chapter.bookId = b.bookId
    AND current_chapter.sourceId = bs.sourceId AND current_chapter.chapterId = p.chapterId
    AND p.sourceId = bs.sourceId
```

The join is to the existing composite primary key, so it must not multiply the existing `c` counts. Order ties by chapter ID, consistently with the reader window. A removed saved chapter, missing selected-source catalog or another source returns null rather than promoting the old index. No persisted row is edited and schemas 1–5 remain unchanged.

Extend `RoomBookRepositoryTest.kt`, `LibraryViewModelTest.kt` and the actual `app/src/androidTest/java/com/novelradar/app/data/db/NovelDatabaseTest.kt` with ordinal mapping, tied/gapped indices, source mismatch and removed saved IDs. Add an explicitly scoped host query contract helper at `app/src/test/resources/library/verify-shelf-position.ps1`, following the existing in-memory migration helper: shell reads the actual BookDao SELECT and generated schema 5, passes data by stdin to bundled Python SQLite, and executes only in-memory databases. Prove old selected-source/chapter/unread counts and labels survive, actual ordinal is correct despite a stale saved raw index, removed/foreign chapter yields null, and the stored original offset/index/hash/marker are unchanged. Observe assertion RED against the old query/projection before implementation. Do not use Python filesystem access or treat compiled Room tests as runtime evidence.

- [ ] **Step 2: Render current/read-position labels without implying whole-book words**

Mark the exact current detail-directory row and show its known chapter position. Preserve explicit selection start-at-zero and ordinary continue-reading restore behavior. For older chapters, label ordering-based position conservatively rather than claiming every skipped chapter was actually read. In the library use “读到第 x / N 章” from the actual ordinal for a matching CURRENT location; if retaining a percentage, explicitly label it as chapter position, never exact whole-book character completion. Legacy/unknown-format or absent-catalog locations show the known original chapter as “原阅读位置 …（待核对）” without a fresh catalog fraction or 100%. No matching saved position still means “尚未开始阅读”. Share one pure label property between the grid and continue card; legacy positions are not silently promoted.

- [ ] **Step 3: Run final cumulative verification and independent reviews**

Root rechecks the complete approved repair design against all source/provenance/continuous commits, actual unit reports, lint levels, icon contracts, migration preservation and stable artifact hashes. Request independent final cumulative specification review followed by quality review. Fix and re-review all required issues before packaging. Do not merge/push/create a PR without user authorization.

- [ ] **Step 4: Save a separately named repaired debug APK and verify identity**

After the cumulative review passes, choose a distinct filename containing the repair commit. Preserve the two earlier APKs. Read actual package/version/min/target/ABIs and verify the established debug certificate, v2 signature, SHA-256, file size and selected icon bytes. If updating version metadata for this test build, use a dedicated scoped change and verify it; do not silently switch signing identity or require an uninstall. Copy only the already verified assembled artifact with overwrite disabled. Update `docs/test-apk-notes.md` with current artifact and explicit limitations.

Deliver the clickable absolute APK path and a concise phone checklist: both modes crossing multiple chapters show their titles and matching catalog; current chapter read/total words plus chapter position; re-open/rotation/font changes retain the same position; next-two caching obeys settings; legacy notice preserves old records; offline cached reading. No ADB install, system feature changes, release readiness or universal speed promise.

## Standard commands and final checklist

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestSources --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
.\gradlew.bat :app:testDebugUnitTest --rerun-tasks --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

Expected: exit 0; read current XML counts with zero failures/errors/skips, and SARIF actual severities. The seven existing warnings are not errors or permission to introduce new ones. Forced unit verification is independent of cached task results. Only the named Gradle owner runs these commands.

For independent checks between tasks, after the implementer's full milestone build, root may force the test task itself with `:app:testDebugUnitTest --rerun` and the same established offline/no-daemon/in-process flags. Root verified this option in the actual current Gradle task help on October 13; it executes the entire unit suite again while retaining unchanged compiled prerequisites. This is not `--test-dry-run`. The final cumulative check still uses the `--rerun-tasks` command above to rebuild all prerequisites as well. Read the exit code and newly written XML in both cases; cached test output alone is not a new test execution.

- [ ] No legacy gate bypass, lost old hash/offset or destructive migration.
- [ ] No background-preparation progress writes; one actual chapter/offset for both modes, catalog and persisted record.
- [ ] Window/jobs bounded; settings and real network policy respected; one in-flight fetch reused.
- [ ] Both-mode headings, exact UTF-16 restoration and Unicode display agree; append does not jump the viewport.
- [ ] Detail and library show actual same-source reading position.
- [ ] Cumulative independent reviews and fresh verification completed before a new stable APK is saved.
- [ ] Previous deliverables/icon/signing identity preserved; device tests and Release limitations stated honestly.
