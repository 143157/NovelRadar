# Search, Persistence, and Source Management Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist books and rules locally, stream fault-isolated multi-source search into the Compose UI, and let users import, enable, disable, and health-test SoNovel JSON sources.

**Architecture:** Room owns normalized metadata and rule state; repositories expose Flows and hide entity mapping. `AggregateSearchUseCase` fans out to enabled rules with a semaphore and emits immutable snapshots after every source event. Feature ViewModels convert repository/use-case output into UI state, and composables remain stateless.

**Tech Stack:** Room 2.8.5 with KSP, Kotlin Flow/coroutines, Android Storage Access Framework, Compose Material 3, Navigation Compose, MockWebServer, Turbine, Room migration tests.

---

## Task 1: Create the Room schema and migrations

**Files:**
- Create: `app/src/main/java/com/novelradar/app/data/db/entity/SourceRuleEntity.kt`
- Create: `app/src/main/java/com/novelradar/app/data/db/entity/BookEntity.kt`
- Create: `app/src/main/java/com/novelradar/app/data/db/entity/BookSourceEntity.kt`
- Create: `app/src/main/java/com/novelradar/app/data/db/entity/ChapterEntity.kt`
- Create: `app/src/main/java/com/novelradar/app/data/db/entity/ShelfEntryEntity.kt`
- Create: `app/src/main/java/com/novelradar/app/data/db/entity/ReadingProgressEntity.kt`
- Create: `app/src/main/java/com/novelradar/app/data/db/entity/DownloadTaskEntity.kt`
- Create: `app/src/main/java/com/novelradar/app/data/db/SourceRuleDao.kt`
- Create: `app/src/main/java/com/novelradar/app/data/db/BookDao.kt`
- Create: `app/src/main/java/com/novelradar/app/data/db/ChapterDao.kt`
- Create: `app/src/main/java/com/novelradar/app/data/db/DownloadTaskDao.kt`
- Create: `app/src/main/java/com/novelradar/app/data/db/NovelDatabase.kt`
- Test: `app/src/androidTest/java/com/novelradar/app/data/db/NovelDatabaseTest.kt`

- [ ] **Step 1: Add Room test dependency and write failing DAO tests**

Add `androidx.room:room-testing:2.8.5`, AndroidX test runner, and AndroidX test core. In an in-memory database, test source upsert, book/source transaction, ordered chapters, one shelf row per book, progress replacement, and download-task observation.

```kotlin
@Test fun replacingProgressKeepsOnlyLatestLocation() = runTest {
    database.bookDao().upsertProgress(progress(chapterIndex = 4, offset = 20))
    database.bookDao().upsertProgress(progress(chapterIndex = 7, offset = 3))
    assertThat(database.bookDao().progress("book-1").first().chapterIndex).isEqualTo(7)
}
```

- [ ] **Step 2: Run the instrumented test to verify failure**

Run: `.\gradlew.bat :app:compileDebugAndroidTestSources`

Expected: FAIL because the database types are undefined.

- [ ] **Step 3: Implement normalized entities**

Use these keys:

```text
source_rules: sourceId
books: bookId
book_sources: (bookId, sourceId)
chapters: (bookId, sourceId, chapterId)
shelf_entries: bookId
reading_progress: bookId
download_tasks: taskId, unique active index enforced in repository
```

Store rule JSON verbatim plus enabled/builtIn/allowCleartext/lastHealth fields. Use `Long` epoch milliseconds. Add foreign keys from book sources/chapters/shelf/progress to books with cascade only for explicit book deletion.

- [ ] **Step 4: Implement DAOs and version-1 database**

Every observe method returns `Flow`. Multi-table book insertion uses `@Transaction`. Chapter ordering is `ORDER BY chapterIndex ASC`. Room schema export goes to `app/schemas`; commit exported JSON for every version.

- [ ] **Step 5: Compile and run database tests**

Run:

```powershell
.\gradlew.bat :app:kspDebugKotlin
.\gradlew.bat :app:compileDebugAndroidTestSources
```

When a device is available run `connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.novelradar.app.data.db.NovelDatabaseTest`; expected PASS.

- [ ] **Step 6: Commit**

```powershell
git add gradle/libs.versions.toml app/build.gradle.kts app/src/main/java/com/novelradar/app/data/db app/src/androidTest app/schemas
git commit -m "feat: add local novel database"
```

## Task 2: Install built-in rules and expose repository contracts

**Files:**
- Create: `app/src/main/java/com/novelradar/app/domain/repository/SourceRuleRepository.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/repository/BookRepository.kt`
- Create: `app/src/main/java/com/novelradar/app/data/repository/RoomSourceRuleRepository.kt`
- Create: `app/src/main/java/com/novelradar/app/data/repository/RoomBookRepository.kt`
- Create: `app/src/main/java/com/novelradar/app/data/bootstrap/BuiltInRuleInstaller.kt`
- Create: `app/src/main/java/com/novelradar/app/app/AppContainer.kt`
- Create: `app/src/main/java/com/novelradar/app/NovelRadarApplication.kt`
- Test: `app/src/test/java/com/novelradar/app/data/bootstrap/BuiltInRuleInstallerTest.kt`

- [ ] **Step 1: Write failing idempotency tests**

Use fakes for asset reading and the DAO. Verify first install inserts all rules, a second install updates built-in JSON without changing user enablement, and removed bundled rules become disabled instead of deleted.

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.BuiltInRuleInstallerTest"`

Expected: FAIL.

- [ ] **Step 3: Define repository contracts**

`SourceRuleRepository` must expose:

```kotlin
interface SourceRuleRepository {
    fun observeAll(): Flow<List<StoredSourceRule>>
    suspend fun enabled(): List<StoredSourceRule>
    suspend fun upsertImported(rules: List<RuleDto>): ImportSummary
    suspend fun setEnabled(sourceId: String, enabled: Boolean)
    suspend fun setCleartextAllowed(sourceId: String, allowed: Boolean)
    suspend fun updateHealth(sourceId: String, health: SourceHealth)
    suspend fun deleteUserRule(sourceId: String)
}
```

`BookRepository` owns search-result persistence, source mappings, shelf, and progress interfaces; it must not expose Room entities.

- [ ] **Step 4: Implement repositories and application container**

`NovelRadarApplication` creates one `AppContainer` with the database, OkHttp client, parser, source engine, repositories, and dispatchers. No global mutable singleton other than the Android `Application` reference. `BuiltInRuleInstaller.run()` executes from application startup on `Dispatchers.IO` and records a bundled-rule version in DataStore.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.BuiltInRuleInstallerTest"`

Expected: PASS.

```powershell
git add app/src/main/java/com/novelradar/app/domain/repository app/src/main/java/com/novelradar/app/data app/src/main/java/com/novelradar/app/app app/src/main/java/com/novelradar/app/NovelRadarApplication.kt app/src/main/AndroidManifest.xml app/src/test
git commit -m "feat: persist and install source rules"
```

## Task 3: Import rules through the Storage Access Framework

**Files:**
- Create: `app/src/main/java/com/novelradar/app/source/importer/RuleImporter.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/sources/ImportRulesUseCase.kt`
- Test: `app/src/test/java/com/novelradar/app/source/importer/RuleImporterTest.kt`

- [ ] **Step 1: Write failing import tests**

Cover one object, an array, 1 MiB file limit, invalid UTF-8, duplicate IDs, built-in name conflicts, forbidden scripts, unsupported scheme, and a mixed file where any invalid rule rejects the entire import.

```kotlin
@Test fun invalidMemberMakesImportAtomic() = runTest {
    val result = importer.import(streamOf(validRule, invalidRule))
    assertThat(result).isInstanceOf(ImportResult.Rejected::class.java)
    assertThat(repository.savedRules).isEmpty()
}
```

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.RuleImporterTest"`

Expected: FAIL.

- [ ] **Step 3: Implement bounded atomic import**

Read at most 1 MiB from the content resolver, decode strict UTF-8, parse all rules, validate all rules, derive missing IDs, and write one Room transaction only after all validation succeeds. Return `Imported(added, updated, disabledHttp)` or `Rejected(List<RuleIssue>)`.

- [ ] **Step 4: Verify and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.RuleImporterTest"`

Expected: PASS.

```powershell
git add app/src/main/java/com/novelradar/app/source/importer app/src/main/java/com/novelradar/app/feature/sources app/src/test
git commit -m "feat: import SoNovel rule files"
```

## Task 4: Aggregate source searches progressively

**Files:**
- Create: `app/src/main/java/com/novelradar/app/domain/search/SearchEvent.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/search/SearchSnapshot.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/search/SearchResultMerger.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/search/AggregateSearchUseCase.kt`
- Test: `app/src/test/java/com/novelradar/app/domain/search/SearchResultMergerTest.kt`
- Test: `app/src/test/java/com/novelradar/app/domain/search/AggregateSearchUseCaseTest.kt`

- [ ] **Step 1: Write failing merge and concurrency tests**

Test exact book/author grouping, same title/different author separation, punctuation normalization, stable ranking, progressive snapshots, cancellation, one failed source with another successful source, and a maximum of six active source calls.

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.domain.search.*"`

Expected: FAIL.

- [ ] **Step 3: Implement deterministic merging**

Create `AggregatedBookResult(bookKey, displayName, author, sources, bestHit)`. Rank exact normalized title first, prefix match second, edit-distance similarity third, then source health and recency. Never merge when both nonblank normalized authors differ.

- [ ] **Step 4: Implement streaming search**

Return `Flow<SearchSnapshot>` from `channelFlow`. Use `Semaphore(6)` and one child coroutine per enabled source. Emit `SourceStarted`, `SourceSucceeded`, or `SourceFailed`, then recompute an immutable snapshot. A cancelled collector cancels all child calls through `SourceHttpClient`.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.domain.search.*"`

Expected: PASS.

```powershell
git add app/src/main/java/com/novelradar/app/domain/search app/src/test/java/com/novelradar/app/domain/search
git commit -m "feat: stream aggregate novel search"
```

## Task 5: Build the full search UI

**Files:**
- Create: `app/src/main/java/com/novelradar/app/feature/search/SearchUiState.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/search/SearchViewModel.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/search/SearchScreen.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/search/SearchResultCard.kt`
- Modify: `app/src/main/java/com/novelradar/app/app/NovelRadarApp.kt`
- Test: `app/src/test/java/com/novelradar/app/feature/search/SearchViewModelTest.kt`
- Test: `app/src/androidTest/java/com/novelradar/app/feature/search/SearchScreenTest.kt`

- [ ] **Step 1: Write ViewModel tests**

Use Turbine to assert blank query suppression, previous-search cancellation, loading progress, partial results, per-source failures, no-result state, retry, and result selection.

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.SearchViewModelTest"`

Expected: FAIL.

- [ ] **Step 3: Implement immutable UI state and ViewModel**

Use:

```kotlin
data class SearchUiState(
    val query: String = "",
    val searching: Boolean = false,
    val completedSources: Int = 0,
    val totalSources: Int = 0,
    val results: List<AggregatedBookResult> = emptyList(),
    val failures: Map<String, SourceFailure> = emptyMap(),
)
```

Expose `onQueryChanged`, `submit`, `retryFailed`, and `selectResult`. Use `stateIn(viewModelScope, WhileSubscribed(5_000), initial)`.

- [ ] **Step 4: Implement the C-style visual design**

Create the orange high-contrast app bar, bordered search field, source-progress chip, result cards with cover/title/author/latest chapter/source count, expandable source status, empty state, and failure summary. Add stable semantics tags `search_input`, `search_submit`, `search_progress`, and `search_results`.

- [ ] **Step 5: Add and compile Compose tests**

Test empty state, partial-results state, failed-source expansion, and click callback. Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*.SearchViewModelTest"
.\gradlew.bat :app:compileDebugAndroidTestSources
```

Expected: PASS/compile success.

- [ ] **Step 6: Commit**

```powershell
git add app/src/main/java/com/novelradar/app/feature/search app/src/main/java/com/novelradar/app/app app/src/test app/src/androidTest
git commit -m "feat: add progressive multi-source search UI"
```

## Task 6: Build source management and health checks

**Files:**
- Create: `app/src/main/java/com/novelradar/app/feature/sources/SourceManagerUiState.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/sources/SourceManagerViewModel.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/sources/SourceManagerScreen.kt`
- Create: `app/src/main/java/com/novelradar/app/feature/sources/TestSourceUseCase.kt`
- Test: `app/src/test/java/com/novelradar/app/feature/sources/SourceManagerViewModelTest.kt`
- Test: `app/src/androidTest/java/com/novelradar/app/feature/sources/SourceManagerScreenTest.kt`

- [ ] **Step 1: Write failing health and toggle tests**

Assert that testing uses a fixed neutral keyword, records latency and timestamp, classifies verification pages, prevents enabling HTTP without confirmation, permits deletion only for imported rules, and surfaces import issues with field paths.

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.SourceManagerViewModelTest"`

Expected: FAIL.

- [ ] **Step 3: Implement source management logic**

`TestSourceUseCase` performs one search-page request and parse with an eight-second timeout; it never downloads a chapter. `SourceManagerViewModel` exposes import launcher events, confirmation events for HTTP, toggles, test-one/test-all with concurrency three, and delete for user rules.

- [ ] **Step 4: Implement UI and file picker**

Use `OpenDocument` for `application/json` and `text/*`. Each row shows name, protocol, enable switch, built-in/imported badge, last latency, and health. An HTTP enable action opens a warning dialog before persisting. Add `+ 导入书源` and `测试全部` actions.

- [ ] **Step 5: Verify and commit**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests "*.SourceManagerViewModelTest"
.\gradlew.bat :app:compileDebugAndroidTestSources
```

Expected: PASS/compile success.

```powershell
git add app/src/main/java/com/novelradar/app/feature/sources app/src/test app/src/androidTest
git commit -m "feat: manage and test novel sources"
```

## Task 7: Close the search milestone with an integration flow

**Files:**
- Create: `app/src/androidTest/java/com/novelradar/app/SearchFlowTest.kt`
- Create: `app/src/test/java/com/novelradar/app/SearchIntegrationTest.kt`

- [ ] **Step 1: Add a deterministic MockWebServer integration test**

Install two in-memory rules: one returns a result, one times out. Assert the repository/use-case emits one aggregated result, one failure, and completed count two. Persist the successful book/source mapping and assert it can be reloaded.

- [ ] **Step 2: Add the Compose flow test**

Inject a fake container, enter `斗破苍穹`, submit, wait for progress, assert `5 个书源` is displayed for the fake data, and tap the result to navigate to the book-detail route. Until Plan 3 adds the complete page, that route must render the selected book ID and a back action so navigation is real and testable.

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
git commit -m "test: cover search and source management flow"
```
