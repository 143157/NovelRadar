# Reader, Bookshelf, and Offline Download Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the user journey from an aggregated search result to book details, chapter reading, persistent bookshelf progress, whole-book background download, and offline reading.

**Architecture:** Details and chapter repositories persist per-source snapshots and use a file store for body text. Reader state is derived from Room progress, DataStore preferences, and a chapter loader that prefers downloaded files, then cache, then network. A unique WorkManager chain per book/source performs resumable downloads and checkpoints every chapter.

**Tech Stack:** Kotlin coroutines and Flow, Room, atomic files, Preferences DataStore, Jetpack Compose, Coil, WorkManager foreground workers, JUnit, Turbine, WorkManager TestDriver, Compose tests.

---

## Task 1: Load book details and per-source table of contents

Completion contract (2026-10-04): retain the existing catalog/reading/download integration and add the missing detail refresh, cover, and introduction. Separate actual page title/author evidence from `SearchHit` values borrowed for display; absent page fields remain unknown. Detail updates change only nonblank cover/introduction metadata, preserve canonical book ID/key/title/author, and atomically validate the expected book snapshot plus source ownership/detail URL before saving. A canceled or superseded request must not write back. Catalog and details refresh independently, keeping successful data when the other fails; cached chapters remain usable while details are pending or fail.

If actual nonblank page identity conflicts with known canonical identity, reject the metadata update and explicitly mark that source as mismatched. Preserve successfully parsed identity fields even when another detail field fails, so an unrelated parsing failure cannot conceal a known conflict; failed detail parsing must not save metadata or confirm a full match. Keep the legacy `parse`/`book` APIs' failure behavior. Before publishing any identity conclusion, validate that its expected book/source/detail URL snapshot is still current; expired evidence cannot mark the current mapping or clear an existing mismatch. In the current detail ViewModel session, a proven mismatch prevents reading/download targets and chapter actions; switching away and back or an unknown/failed refresh cannot bypass it. Only a successful, complete actual title/author match clears that source's session flag. Late old-source/request results cannot mark a newer source incorrectly. This evidence is ephemeral, not a new persisted author-verification claim; fallback must obtain fresh identity after restart. Unknown fields alone do not block explicit reading of a cached catalog. Reuse the existing policy-checked `NovelCover`/`SafeCoverFetcher` and actual application graph rather than supplying remote URLs directly to the image loader. No automatic source switch, reading-history rewrite, or reader-session closure is part of this task.

**Files:**
- Create: `app/src/main/java/com/novelradar/app/domain/repository/CatalogRepository.kt`
- Create: `app/src/main/java/com/novelradar/app/data/repository/DefaultCatalogRepository.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/book/BookDetailUiState.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/book/BookDetailViewModel.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/book/BookDetailScreen.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/book/ChapterList.kt`
- Test: `app/src/test/java/com/novelradar/app/data/repository/DefaultCatalogRepositoryTest.kt`
- Test: `app/src/test/java/com/novelradar/app/feature/book/BookDetailViewModelTest.kt`
- Test: `app/src/androidTest/java/com/novelradar/app/feature/book/BookDetailScreenTest.kt`

- [ ] **Step 1: Write failing repository tests**

Test detail parsing, catalog caching, force refresh, source switching without overwriting another source's catalog, duplicate URL removal, and stale-cache fallback when refresh fails.

```kotlin
@Test fun failedRefreshReturnsStaleCatalogWithWarning() = runTest {
    dao.insertChapters(chapters(sourceId = "a"))
    engine.failure = SourceFailure.Timeout
    val result = repository.catalog("book", "a", forceRefresh = true)
    assertThat(result.chapters).hasSize(3)
    assertThat(result.warning).isEqualTo(SourceFailure.Timeout)
}
```

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.DefaultCatalogRepositoryTest"`

Expected: FAIL.

- [ ] **Step 3: Implement repository and transactions**

`CatalogRepository` exposes `book(bookId)`, `sources(bookId)`, `catalog(bookId, sourceId, forceRefresh)`, and `refreshDetails`. Persist a complete catalog in one transaction only after parsing succeeds; keep previous snapshot on failure. Preserve the established `chapterId` representation: the first 20 bytes of SHA-256(resolved URL), encoded as 40 hexadecimal characters, not a new 20-character ID.

- [ ] **Step 4: Write ViewModel tests and implement state**

State contains book, sources, selected source ID, chapters, reversed flag, chapter query, loading, refreshing, and warning. Filter chapters locally by normalized title. Switching source cancels the previous load and reads that source's independent snapshot.

- [ ] **Step 5: Implement the details UI**

Render the orange hero, cover, metadata, source picker, `加入书架`, `开始阅读`, `整本下载`, chapter search, ascending/descending toggle, and lazy chapter list. Add test tags `book_primary_actions`, `source_picker`, and `chapter_list`.

- [ ] **Step 6: Verify and commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*.DefaultCatalogRepositoryTest" --tests "*.BookDetailViewModelTest"
.\gradlew.bat :app:compileDebugAndroidTestSources
```

Expected: PASS/compile success.

```powershell
git add app/src/main/java/com/novelradar/app/domain/repository/CatalogRepository.kt app/src/main/java/com/novelradar/app/data/repository/DefaultCatalogRepository.kt app/src/main/java/com/novelradar/app/feature/book app/src/test app/src/androidTest
git commit -m "feat: add book details and chapter catalog"
```

## Task 2: Add atomic chapter storage and cache policy

**Files:**
- Create: `app/src/main/java/com/novelradar/app/data/files/ChapterFileStore.kt`
- Create: `app/src/main/java/com/novelradar/app/data/files/AtomicChapterFileStore.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/repository/ChapterRepository.kt`
- Create: `app/src/main/java/com/novelradar/app/data/repository/DefaultChapterRepository.kt`
- Test: `app/src/test/java/com/novelradar/app/data/files/AtomicChapterFileStoreTest.kt`
- Test: `app/src/test/java/com/novelradar/app/data/repository/DefaultChapterRepositoryTest.kt`

- [x] **Step 1: Write failing storage tests**

Test UTF-8 round-trip, deterministic path, temporary-file cleanup, interrupted write preserving the previous file, SHA-256 validation, delete-one-book, and path traversal rejection.

- [x] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.AtomicChapterFileStoreTest"`

Expected: FAIL.

- [x] **Step 3: Implement atomic storage**

Store under `filesDir/books/<bookId>/<sourceId>/<chapterId>.txt`. Validate IDs against `[A-Za-z0-9_-]{1,80}`. Write `<chapterId>.tmp`, flush and close, calculate SHA-256, then use `Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)` when available and a same-directory rename fallback otherwise.

- [x] **Step 4: Implement cache-first repository behavior**

`loadChapter` returns `Downloaded`, `Cached`, or `Network` origin. Read and verify local content first. On missing/corrupt files, request and parse network content, atomically store it, then update Room. Provide bounded `prefetchNext(bookId, sourceId, chapterIndex, count = 2)` and never prefetch on metered network when the setting disallows it.

- [x] **Step 5: Verify and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.data.files.*" --tests "*.DefaultChapterRepositoryTest"`

Expected: PASS.

```powershell
git add app/src/main/java/com/novelradar/app/data/files app/src/main/java/com/novelradar/app/domain/repository/ChapterRepository.kt app/src/main/java/com/novelradar/app/data/repository/DefaultChapterRepository.kt app/src/test
git commit -m "feat: cache chapter text atomically"
```

## Task 3: Persist reader preferences and reading progress

**Files:**
- Create: `app/src/main/java/com/novelradar/app/domain/model/ReaderPreferences.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/repository/ReaderSettingsRepository.kt`
- Create: `app/src/main/java/com/novelradar/app/data/preferences/DataStoreReaderSettingsRepository.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/repository/ReadingProgressRepository.kt`
- Create: `app/src/main/java/com/novelradar/app/data/repository/RoomReadingProgressRepository.kt`
- Test: `app/src/test/java/com/novelradar/app/data/preferences/DataStoreReaderSettingsRepositoryTest.kt`
- Test: `app/src/test/java/com/novelradar/app/data/repository/RoomReadingProgressRepositoryTest.kt`

- [x] **Step 1: Write failing bounds and progress tests**

Assert font size clamps to 14–36 sp, line height 1.2–2.2, horizontal margin 8–48 dp, brightness null or 0.05–1.0, theme enum persistence, mode persistence, and progress replacement by book ID.

- [x] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.ReaderSettingsRepositoryTest" --tests "*.ReadingProgressRepositoryTest"`

Expected: FAIL.

- [x] **Step 3: Implement reader preferences**

Use:

```kotlin
data class ReaderPreferences(
    val fontSizeSp: Float = 20f,
    val lineHeight: Float = 1.65f,
    val horizontalMarginDp: Int = 20,
    val brightness: Float? = null,
    val theme: ReaderTheme = ReaderTheme.PAPER,
    val mode: ReaderMode = ReaderMode.SCROLL,
    val prefetchCount: Int = 2,
)
```

Write each update atomically through DataStore and map invalid stored values back into bounds.

- [x] **Step 4: Implement debounced progress writes**

Keep exact writes available for chapter changes and lifecycle stop. For scroll/page position changes, use a 750 ms debounce and `distinctUntilChanged` before Room upsert. Store book/source/chapter IDs, chapter index, character offset, and updated time.

- [x] **Step 5: Verify and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.ReaderSettingsRepositoryTest" --tests "*.ReadingProgressRepositoryTest"`

Expected: PASS.

```powershell
git add app/src/main/java/com/novelradar/app/domain/model/ReaderPreferences.kt app/src/main/java/com/novelradar/app/domain/repository app/src/main/java/com/novelradar/app/data/preferences app/src/main/java/com/novelradar/app/data/repository app/src/test
git commit -m "feat: persist reader settings and progress"
```

Implementation evidence (2026-10-01): Task 2 is committed in `e9bdd42` and `28b7c02`; Task 3 in `9ad217a` and `04e41fc`. Both passed separate specification and quality reviews. The combined verification produced 203 unit tests with zero failures/errors, successful lint, debug APK build, and Android-test source compilation. No Android device tests have run. Settings unit tests use an in-memory DataStore after repeat file replacement failed in the Windows/JVM test environment; actual Android file persistence remains unverified. Task 1 still has follow-up work for detail refresh and cover rendering; reading/download actions are connected in subsequent tasks.

## Task 4: Build scroll and paged reading experiences

**Files:**
- Create: `app/src/main/java/com/novelradar/app/feature/reader/ReaderUiState.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/reader/ReaderViewModel.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/reader/ReaderScreen.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/reader/ScrollReader.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/reader/PagedReader.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/reader/ReaderControls.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/reader/TextPaginator.kt`
- Test: `app/src/test/java/com/novelradar/app/feature/reader/TextPaginatorTest.kt`
- Test: `app/src/test/java/com/novelradar/app/feature/reader/ReaderViewModelTest.kt`
- Test: `app/src/androidTest/java/com/novelradar/app/feature/reader/ReaderScreenTest.kt`

- [x] **Step 1: Write failing paginator tests**

Test empty text, paragraphs, Chinese punctuation line breaks, narrow width, font change invalidation, no dropped characters, and page offsets joining back to the original text.

- [x] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.TextPaginatorTest"`

Expected: FAIL.

- [x] **Step 3: Implement pagination as a pure boundary calculator**

`TextPaginator.paginate(text, maxCharsPerPage)` returns `List<TextPage(startOffset, endOffset)>`; it prefers newline or Chinese sentence punctuation within the last 20% of a page but falls back to the hard boundary. Compose computes `maxCharsPerPage` from measured width/height and text style, then the pure paginator guarantees stable offsets.

- [x] **Step 4: Write ViewModel tests and implement reading state**

Cover load, cached/network origin, next/previous chapter, exact end/start behavior, retry, settings update, progress restore, progress save, and two-chapter prefetch. `ReaderUiState` must distinguish loading, content, and error without erasing the last successful chapter during refresh.

- [x] **Step 5: Implement the reader UI**

Tap the middle zone to toggle controls; side zones turn pages only in paged mode. Controls provide catalog, brightness, font, theme, mode, and more settings. Apply paper/day/night colors, system-bar colors, keep-screen-on while visible, and the selected brightness without changing system settings. Scroll mode uses a lazy text container; paged mode uses `HorizontalPager` and stable character offsets.

- [x] **Step 6: Verify and commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*.feature.reader.*"
.\gradlew.bat :app:compileDebugAndroidTestSources
```

Expected: PASS/compile success.

```powershell
git add app/src/main/java/com/novelradar/app/feature/reader app/src/test app/src/androidTest
git commit -m "feat: add persistent novel reader"
```

Task 4 implementation evidence (2026-10-01): committed in `9bb86f8`, `ff26e27`, and `cac3d5a`; specification and code-quality reviews passed after the lifecycle/recreation fixes. Full verification ran 236 unit tests with zero failures/errors, plus successful `lintDebug`, `assembleDebug`, and `compileDebugAndroidTestSources`. Explicit catalog entry opens at offset zero only for a fresh navigation; the Activity retains the active reader across configuration changes, including pending loads and delayed progress writes. Exact stop saves enter their non-cancellable section before store cleanup. A capacity-one paginator page may contain two UTF-16 units to preserve a supplementary code point intact. Android runtime tests remain unexecuted; see `docs/device-verification-notes.md`.

## Task 5: Build the bookshelf and update indicators

**Files:**
- Create: `app/src/main/java/com/novelradar/app/feature/library/LibraryUiState.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/library/LibraryViewModel.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/library/LibraryScreen.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/library/RefreshShelfUseCase.kt`
- Test: `app/src/test/java/com/novelradar/app/feature/library/LibraryViewModelTest.kt`
- Test: `app/src/test/java/com/novelradar/app/domain/library/RefreshShelfUseCaseTest.kt`

- [x] **Step 1: Write failing shelf tests**

Test add/remove, continue-reading order, progress percentage, unread chapter count from the selected source, refresh concurrency three, partial refresh failures, and opening the last position.

- [x] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.library.*"`

Expected: FAIL.

- [x] **Step 3: Implement shelf observation and refresh**

Join shelf, book, selected source, progress, and chapter counts in a repository projection. `RefreshShelfUseCase` refreshes only the selected source per book with semaphore three and preserves old update counts on failure while recording a warning.

- [x] **Step 4: Implement shelf UI**

Show `继续阅读` first, then a two-column cover grid with title, last chapter, percentage, and orange update badge. Long press opens remove/delete-download options; removal never deletes downloaded files without a separate checked action.

- [x] **Step 5: Verify and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.library.*"`

Expected: PASS.

```powershell
git add app/src/main/java/com/novelradar/app/feature/library app/src/main/java/com/novelradar/app/domain/library app/src/test
git commit -m "feat: add bookshelf and update checks"
```

Task 5 implementation evidence (2026-10-02): committed in `62551d5`, `0477117`, `dad9405`, `9f5fe1d`, and `f3610bd`; separate specification and code-quality reviews passed. The current reports contain 262 unit tests across 35 suites with zero failures/errors. Independent verification of `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `compileDebugAndroidTestSources` exited successfully; lint has zero errors and seven existing warnings. Shelf selection and last-read time are persisted, source changes block stale navigation while pending, and terminal reader closure drains old saves without freezing a retained reader when rotation cancels closure. Room migration and Compose runtime tests remain unexecuted on Android. Download-worker cancellation must be connected to the separate file-deletion action when workers are added.

## Task 6: Implement resumable whole-book downloads

**Files:**
- Create: `app/src/main/java/com/novelradar/app/domain/repository/DownloadRepository.kt`
- Create: `app/src/main/java/com/novelradar/app/data/download/DownloadCoordinator.kt`
- Create: `app/src/main/java/com/novelradar/app/data/download/BookDownloadWorker.kt`
- Create: `app/src/main/java/com/novelradar/app/data/download/DownloadNotificationFactory.kt`
- Create: `app/src/main/java/com/novelradar/app/data/repository/WorkManagerDownloadRepository.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Test: `app/src/test/java/com/novelradar/app/data/download/DownloadCoordinatorTest.kt`
- Test: `app/src/androidTest/java/com/novelradar/app/data/download/BookDownloadWorkerTest.kt`

- [x] **Step 1: Write failing coordinator tests**

Test one active task per book/source, deterministic unique-work name, resume skipping verified files, retrying only failed chapters, pause state, cancellation preserving completed files, disk-full pause, and progress checkpoint after every chapter.

- [x] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.DownloadCoordinatorTest"`

Expected: FAIL.

- [x] **Step 3: Implement the coordinator**

Use unique work name `book-download:<bookId>:<sourceId>` with `ExistingWorkPolicy.KEEP`. Persist task state before enqueuing. Enforce `UNMETERED` by default, and `NetworkType.CONNECTED` only after explicit user opt-in for mobile data. Pause cancels work but keeps task as `PAUSED`; resume enqueues the same unique name after prior work reaches terminal state.

- [x] **Step 4: Implement the foreground worker**

`BookDownloadWorker` loads the persisted catalog, skips chapters whose file hash verifies, downloads sequentially with source-configured delay, updates Room after each chapter, and calls `setForeground` with completed/total. Return `Result.retry()` only for retryable network failures and only while persisted attempt count is below three; parse errors are recorded per chapter and the worker continues.

- [x] **Step 5: Add notification/channel and service declarations**

Create the download notification channel on application startup. Declare foreground service type `dataSync` as required by the WorkManager foreground-service integration and expose the API-33+ notification-permission requirement. Connect the actual `POST_NOTIFICATIONS` prompt and the reduced-visibility explanation in Task 7's UI; permission denial must not gate task creation or delete completed files. Downloads remain subject to platform scheduling limits.

- [x] **Step 6: Verify and commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*.DownloadCoordinatorTest"
.\gradlew.bat :app:compileDebugAndroidTestSources
```

Expected: PASS/compile success.

```powershell
git add app/src/main/java/com/novelradar/app/domain/repository/DownloadRepository.kt app/src/main/java/com/novelradar/app/data/download app/src/main/java/com/novelradar/app/data/repository/WorkManagerDownloadRepository.kt app/src/main/AndroidManifest.xml app/src/test app/src/androidTest
git commit -m "feat: add resumable whole-book downloads"
```

Task 6 implementation evidence (2026-10-03): committed in `0d1e016`, `2ed79d0`, and `21f0025`; separate specification and quality reviews passed. Independent verification of `testDebugUnitTest`, `lintDebug`, `assembleDebug`, and `compileDebugAndroidTestSources` exited successfully. An additional forced unit-test rerun executed 26 Gradle tasks and produced 316 tests across 38 suites with zero failures/errors; lint has zero errors and seven existing warnings. Shared content permits allow cached reading during a different chapter's network fetch, while deletion still drains actual writes. Current-generation failure markers preserve manual retry without repeating recorded non-network failures automatically. Durable task scheduling is cancellation-safe. Room version 4 is registered with explicit migrations and leaves old active download tasks recoverably paused after upgrade. Host in-memory SQLite checks of the actual 2→3→4 and 3→4 SQL matched seven tables and six task states, preserving shelf/progress and download metadata; these are not Android Room runtime tests. Android Worker, notification, migration, and device-installation tests remain unexecuted. The actual download tab, start dialog, persistent network default, and runtime notification prompt remain Task 7 work.

## Task 7: Build download management UI

**Files:**
- Create: `app/src/main/java/com/novelradar/app/feature/downloads/DownloadsUiState.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/downloads/DownloadsViewModel.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/downloads/DownloadsScreen.kt`
- Test: `app/src/test/java/com/novelradar/app/feature/downloads/DownloadsViewModelTest.kt`
- Test: `app/src/androidTest/java/com/novelradar/app/feature/downloads/DownloadsScreenTest.kt`

- [x] **Step 1: Write failing task-action tests**

Cover queued, running, paused, completed, partial failure, retry, cancel confirmation, delete-files confirmation, and notification-permission prompt events.

- [x] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.DownloadsViewModelTest"`

Expected: FAIL.

- [x] **Step 3: Implement state and actions**

Observe all download tasks and map them to display rows with completed/total, percentage, failed count, bytes, current chapter, speed, and allowed actions. Actions delegate to `DownloadRepository`; ViewModel never calls WorkManager directly.

- [x] **Step 4: Implement download cards**

Use thick bordered cards, orange progress for running, green complete, and visible pause/resume/retry buttons. A completed item opens the book; a partial-failure item exposes failed-chapter retry. Destructive cancellation/deletion uses confirmation dialogs.

- [x] **Step 5: Verify and commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*.DownloadsViewModelTest"
.\gradlew.bat :app:compileDebugAndroidTestSources
```

Expected: PASS/compile success.

```powershell
git add app/src/main/java/com/novelradar/app/feature/downloads app/src/test app/src/androidTest
git commit -m "feat: add offline download management UI"
```

Task 7 implementation evidence (2026-10-04): committed in `d6c8e3a` and `44d534b`; independent specification and code-quality reviews passed, including re-review of a completion/stop race exposed by the new UI. The stop transaction now rejects stale user pause/cancel requests after a worker commits completion, while explicit book-wide deletion retains its separate stop/drain/cleanup path. Root directly observed the regression RED reports (four coordinator failures and one request-feedback failure), then the focused GREEN reports. Independent verification of all four milestone commands exited successfully; the final XML contains 348 tests across 40 suites with zero failures/errors. Lint reports six existing warnings and zero errors. The persistent new-task network default, per-task confirmation, notification-permission handoff, and actual download tab/details entry are connected. Six new Compose tests compiled but did not run on Android. APK signature verification passed with the Android Debug certificate, minSdk 26 and targetSdk 36; a stable test-only copy and its hash are documented in `docs/test-apk-notes.md`. This is not the locally signed Release deliverable or a verified device installation.

## Task 8: Add safe cross-source chapter fallback

Dependency reconciliation (2026-10-04): finish Task 1's remaining detail refresh, cover, and introduction work before integrating fallback. Source identity evidence must distinguish the selected source's actual parsed title/author from values borrowed from the canonical `Book`; the latter cannot prove a candidate author's identity. Unknown or mismatched authors must not be silently treated as matching. Preserve existing book keys and chapter IDs. This changes execution order within the already-approved scope, not the acceptance criteria.

**Files:**
- Create: `app/src/main/java/com/novelradar/app/domain/reader/ChapterMatcher.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/reader/FindFallbackChapterUseCase.kt`
- Modify: `app/src/main/java/com/novelradar/app/feature/reader/ReaderViewModel.kt`
- Modify: `app/src/main/java/com/novelradar/app/feature/reader/ReaderScreen.kt`
- Test: `app/src/test/java/com/novelradar/app/domain/reader/ChapterMatcherTest.kt`

- [ ] **Step 1: Write failing confidence tests**

Test exact normalized title, same index with matching title, shifted index, extra volume headings, numerals in Chinese/Arabic, no-author mismatch, and low-confidence rejection.

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.ChapterMatcherTest"`

Expected: FAIL.

- [ ] **Step 3: Implement explicit confidence scoring**

Score 1.0 for exact normalized chapter title; 0.85 for same extracted chapter number and title similarity ≥ 0.75; 0.70 for index distance ≤ 1 and title similarity ≥ 0.85; otherwise below 0.70. Suggestions ≥ 0.85 may be preselected but still show the source-change dialog; 0.70–0.84 require manual selection; lower scores are not offered.

- [ ] **Step 4: Integrate fallback prompt**

On network/parse failure, load other known sources, match candidates, and emit a one-shot dialog event listing source and confidence. Do not silently change selected source or progress. On confirmation, persist the selected source and matched chapter together in one transaction.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.ChapterMatcherTest" --tests "*.ReaderViewModelTest"`

Expected: PASS.

```powershell
git add app/src/main/java/com/novelradar/app/domain/reader app/src/main/java/com/novelradar/app/feature/reader app/src/test
git commit -m "feat: offer safe source fallback while reading"
```

## Task 9: Close the reader/download milestone

Scope reconciliation (2026-10-02, updated 2026-10-04): before closing this milestone, finish Task 1's detail refresh, cover, and introduction rendering. Also cover the approved design's cumulative cache limit (design sections 5.4 and 6.6); limiting each prefetch to two chapters alone does not bound total cached storage. The persistent new-task download-network default is now implemented and reviewed in Task 7. Cache eviction must never delete `DOWNLOADED` chapters or reading history, and must coordinate with chapter writes. Preserve the already-persisted chapter IDs when finishing detail work. Detail completion and the cumulative cache limit remain requirements, not implemented features.

**Files:**
- Create: `app/src/test/java/com/novelradar/app/OfflineReadingIntegrationTest.kt`
- Create: `app/src/androidTest/java/com/novelradar/app/ReaderDownloadFlowTest.kt`

- [ ] **Step 1: Add offline integration coverage**

With MockWebServer online, fetch catalog and three chapters, enqueue/download all, shut down the server, then assert all three chapters load with `Downloaded` origin and saved progress restores chapter two.

- [ ] **Step 2: Add the UI flow test**

With a fake container: open a search result, add to shelf, open first chapter, change font/theme, return to shelf, enqueue whole-book download, pause, resume, complete through TestDriver, then open the downloaded chapter offline.

- [ ] **Step 3: Run milestone verification**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :app:compileDebugAndroidTestSources
```

Expected: all commands exit `0`.

- [ ] **Step 4: Commit**

```powershell
git add app/src/test app/src/androidTest
git commit -m "test: cover offline reading and download resume"
```

