# Non-destructive Chapter and Position Provenance Upgrade

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Keep old files and bookmarks recoverable, while preventing legacy mixed-chapter content or an uncertain old offset from being treated as verified single-chapter progress.

**Architecture:** Explicit content-format markers distinguish old content/positions from new chapter-boundary-aware ones. Version 5 adds two default-zero columns without deleting data. Repairs preserve the previous hash-matching body before replacing it; reader recovery suppresses automatic bookmark writes until a verified explicit position is chosen.

**Tech Stack:** Room migration, existing atomic file store and content leases, Kotlin coroutines, existing Compose reader, JUnit/MockWebServer, compiled migration/UI instrumentation tests.

---

## Dependency and boundary

The dependency is complete: independent cumulative specification and quality reviews of source-boundary commits `1275491` and `b9771c5` passed, and root's forced final unit run produced 423 tests with zero failures/errors/skips. The source-only stage is not a new user deliverable. Do not alter its title/URL boundary rules, chapter IDs, book keys, source mapping or download task generations. This phase establishes the legacy recovery seam before the continuous reader and character display phase. Execute Tasks 1, 2 and 3 sequentially with a fresh implementer and independent reviews after each; no stable APK is published between these partial stages.

## Task 1: Add content and position format markers with an explicit migration

**Files:**

- Create: `app/src/main/java/com/novelradar/app/domain/model/ChapterContentFormat.kt`.
- Modify: `app/src/main/java/com/novelradar/app/data/db/entity/ChapterEntity.kt` and `ReadingProgressEntity.kt`.
- Modify: `app/src/main/java/com/novelradar/app/data/db/NovelDatabase.kt`, `ChapterDao.kt`, `BookDao.kt`.
- Modify: `app/src/main/java/com/novelradar/app/domain/repository/BookRepository.kt`, `ChapterRepository.kt`.
- Modify: `app/src/main/java/com/novelradar/app/data/repository/RoomBookRepository.kt`, `RoomChapterCatalogStore.kt`, and affected mapping/projection code.
- Modify: `app/src/main/java/com/novelradar/app/app/AppContainer.kt` (migration registration only in this task).
- Generated schema: `app/schemas/com.novelradar.app.data.db.NovelDatabase/5.json` (confirm actual existing schema directory before recording).
- Test: existing book/catalog repository and Room migration tests plus focused format-marker tests.

- [x] **Step 1: Add failing marker and migration-preservation tests**

Seed version-4 data with a book, two source mappings, chapters marked CACHED and DOWNLOADED, shelf, a nonzero saved offset and a paused download task. Verify the migration keeps their IDs, paths, hashes, counts, source selection and offset, and initializes only the new markers to zero. Exercise actual migration SQL on a host SQLite database where available, and compile an Android migration test; do not label compilation as runtime proof. Also test that an updated same-URL catalog preserves the marker alongside the existing file metadata, while a replaced URL cannot inherit it.

- [x] **Step 2: Add only the declared fields and migration**

```kotlin
object ChapterContentFormat {
    const val LEGACY = 0
    const val CURRENT = 1
}
```

Append to both persisted entities, keeping all existing constructor parameters and keys:

```kotlin
@androidx.room.ColumnInfo(defaultValue = "0")
val contentFormatVersion: Int = ChapterContentFormat.LEGACY
```

Append `val contentFormatVersion: Int = ChapterContentFormat.CURRENT` to the domain `ReadingLocation`: new producer-created locations use the current format, while `RoomBookRepository` must explicitly map the persisted value, including zero. Add `val boundaryVerified: Boolean = true` to `LoadedChapter` for existing verified producer fixtures; the real repository must explicitly set false for legacy content. This flag describes chapter boundary processing, not author identity or source access permission.

Use this actual upgrade, register it after the existing 1→2→3→4 chain, and set database version 5:

```kotlin
val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
    override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE chapters ADD COLUMN contentFormatVersion INTEGER NOT NULL DEFAULT 0")
        db.execSQL("ALTER TABLE reading_progress ADD COLUMN contentFormatVersion INTEGER NOT NULL DEFAULT 0")
    }
}
```

`ChapterDao.updateStoredContent` accepts a defaulted `contentFormatVersion: Int = CURRENT` last parameter and sets the column in the same guarded update. Its DOWNLOADED-preservation expression and expected URL clause remain. Copy the old format marker with the old hash/path/byte metadata in `replaceSnapshot` only when the URL is unchanged. Update the progress projection's SELECT alias to read the new persisted field; don't accidentally map all positions back to zero. Every new trusted save writes the passed domain marker. Do not add a destructive fallback, remove records, reset existing offsets or force all completed downloads into a different state.

### Task 1 verification and review

Implementation: `ca2027f792a8a2385867d8cad02114e62b8ba032`, 18 scoped app/test/schema files. Independent specification review and subsequent quality review both passed without outstanding issues. Historical schemas 1–4 are unchanged; schema 5 was generated by KSP, and the actual generated shelf projection explicitly passes the stored marker into the progress entity.

Observed RED evidence is retained under ignored `app/build/task1-evidence/`: nine contract tests failed by assertion (zero errors/skips), the actual repository/catalog run had 24 tests and four expected failures, and a separate combined progress/shelf check failed once. Focused GREEN contained 58 tests in four suites with zero failures/errors/skips. Root independently read these reports and the implementation diff.

Root independently ran the four milestone tasks with the established offline/no-daemon/in-process flags: exit 0, 24 seconds, 61 tasks (one executed, 60 up-to-date). A separate forced `:app:testDebugUnitTest --rerun-tasks` run then executed all 26 tasks: exit 0, 2 minutes 46 seconds, fresh XML containing **438 tests / 45 suites / zero failures, errors or skips**. SARIF contains the same seven existing warnings and zero errors. Android migration and actual Room marker tests compiled; they have not run on a device.

Root twice executed `app/src/test/resources/migrations/verify-v4-v5.ps1`, which reads the actual source SQL and schemas 4/5 and creates only in-memory SQLite databases. The two ALTER statements preserve all seven tables and eleven seeded rows, old columns, primary/foreign keys and indices, source selection, offset 77, paths/hashes/bytes, timestamps and PAUSED/COMPLETED download checkpoints. Only the three new row marker values become zero. Working-tree/whitespace checks passed after the implementation commit. Both previously delivered APKs retain their recorded SHA-256 values; no new stable APK was copied.

This closes Task 1 only. Legacy body handling, reader recovery and continuous reading remain incomplete and must not be described as repaired by the marker migration alone.

## Task 2: Preserve old body files during repair and expose legacy status honestly

**Files:**

- Modify: `app/src/main/java/com/novelradar/app/data/files/ChapterFileStore.kt`, `AtomicChapterFileStore.kt`.
- Modify: `app/src/main/java/com/novelradar/app/data/repository/DefaultChapterRepository.kt` (body load/verified/download logic; keep the reviewed fetcher seam).
- Test: `app/src/test/java/com/novelradar/app/data/files/AtomicChapterFileStoreTest.kt`, `DefaultChapterRepositoryTest.kt` and focused real-HTTP legacy-repair integration.

- [x] **Step 1: Observe failing storage and repository tests**

Verify a legacy hash-valid file does not count as newly boundary-verified or complete in the download runner's `verifiedBytes` path. A normal explicit load may attempt a policy-checked fresh single-chapter fetch. Failed network/parse/write/metadata repair keeps the old bytes readable with the original hash and does not erase its bookmark. A successful repair publishes the new single-chapter body and marker only after guarded storage succeeds; retain a hash-named previous file for old-offset recovery. Current-format cache hits require no network, stay independent of another chapter's network request, and preserve downloaded status.

- [x] **Step 2: Add the minimum recovery-aware file method**

Add a default fail-closed operation rather than silently overwriting a legacy file in an unsupported store:

```kotlin
suspend fun writePreservingPrevious(
    bookId: String, sourceId: String, chapterId: String,
    content: String, previousSha256: String,
): StoredChapterFile = throw java.io.IOException("Legacy-preserving write is unavailable")
```

In the real atomic store, under the existing file mutex and IO dispatcher, verify the old primary file or an already retained recovery file matches the supplied 64-hex hash, then retain it as `<chapterId>.legacy-<lowercaseSha256>.txt` in the same validated chapter directory before the existing atomic replacement. Reuse a matching retained file when retrying after a metadata-update failure; the primary may already contain the new body in that case. Refuse to overwrite a different existing backup. A failed backup or replacement preserves the old hash-matching readable bytes. All target paths still use validated book/source/chapter IDs; hash validation precedes using the hash as part of a filename. No unsafe concatenated absolute paths.

When `read(..., expectedSha256)` cannot find a matching primary file, allow the matching hash-named retained file as a recovery candidate, under the same 8 MiB/UTF-8/hash bounds. Never return a different-hash backup or glob arbitrary directories. The canonical database path remains `books/<book>/<source>/<chapter>.txt`. Existing explicit book deletion removes its retained files too; no new automatic deletion in this phase.

- [x] **Step 3: Keep legacy data separate from verified cache/download results**

For current-format rows, retain the original cache-first behavior. For a readable legacy row, a fresh successful fetch uses `writePreservingPrevious`, then the existing URL-guarded metadata update with CURRENT. Failure of the final metadata update does not make the old hash unreadable. A normal reader load that cannot repair may return its legacy body with `boundaryVerified = false`; this is a temporary viewing fallback, never proof of correct chapter progress. Prefetch must not count that fallback as a successfully prepared next chapter, and must respect metered/disabled prefetch policy before attempting a repair. `download` must not return success for an unverified legacy fallback; `verifiedBytes` returns null for a legacy row rather than promoting it to DOWNLOADED.

Do not swallow CancellationException or rewrite current-format rows into legacy. Preserve existing per-chapter locks, shared book permits, deletion barriers and current URL validation. Use fake stores only at the storage boundary; include a real atomic-store/MockWebServer repair case proving a three-chapter legacy body becomes one verified chapter while the old bytes are recoverable.

### Task 2 verification and review

Implementation: `1879abe84d7f21d0f89f0831e2231d607f52c2fa`, exactly three declared production files and two existing test files. Its parent is `1d49ff5`; root's later documentation-only commits do not belong to the implementation diff. Independent specification review then quality review passed without required changes. The reviewed source/fetcher seam, entities, schemas, reader, downloader policies and delivered APKs are unchanged.

Observed RED is saved under ignored `app/build/task2-evidence/`: initial 37 tests/five assertions, expanded 61 tests/22 failures, then a corrected identity/stale-metadata run with two tests/two Truth assertions and zero errors/skips. The expanded URL case originally used a cast; the targeted follow-up replaced it with an explicit typed-result assertion. Focused GREEN contains 62 tests (20 atomic storage, 42 repository), zero failures/errors/skips. Root directly inspected those XML files, the three production diffs and the real HTTP/atomic-store repair and metadata-failure retry tests.

Root independently ran the full four-task milestone command: exit 0, seven seconds, 61 tasks (one executed, 60 up-to-date). A forced unit rerun executed all 26 tasks: exit 0, one minute ten seconds; fresh XML contains **468 tests / 45 suites / zero failures, errors or skips**. Actual SARIF severities remain seven existing warnings and zero errors. Whitespace/status checks are clean, and the complete SHA-256 values of both delivered APKs match their original records. Android-test sources compiled but did not run on a phone. No new stable APK was copied or installed.

The real HTTP regression fetches only the first chapter despite a known next-chapter link, publishes verified single-chapter content, and retains the old three-chapter bytes by exact SHA. Retry after metadata failure reuses that backup; unverified fallback does not count as prefetch/download/verified bytes. This closes Task 2 only: the existing reader still needs the recovery gate before a user can safely receive a repaired APK.

## Task 3: Prevent implicit reinterpretation or overwriting of an old reading position

**Files:**

- Modify: `app/src/main/java/com/novelradar/app/feature/reader/ReaderUiState.kt`, `ReaderViewModel.kt`, `ReaderScreen.kt`.
- Modify: `app/src/main/java/com/novelradar/app/app/NovelRadarApp.kt` only as needed to preserve the explicit entry/recovery intent across recreation.
- Test: existing ReaderViewModel/ReaderScreen tests plus legacy-position integration tests.

- [ ] **Step 1: Observe the false-clamping/write regression**

Start from an old-format nonzero bookmark whose offset points into a mixed body; repair returns a smaller verified body. Assert the original persisted location is still intact after load, scroll callbacks, stop, close, rotation and failed new-chapter loading. A verified explicit chapter selection or explicit position confirmation may replace it; background caching and retry alone may not. Test legacy content with no network remains temporarily viewable with a warning, never silently marked correct. An already current-format saved position within the body restores unchanged.

The existing `ReaderViewModelTest.restoresProgressOnlyForRequestedSourceAndClampsOffset` accepts automatic clamping of an out-of-range saved location. Replace that obsolete expectation with a recovery-gate assertion retaining the original persisted offset; keep separate coverage for valid CURRENT positions and other-source records. This is an intentional correction of a test that currently codifies unsafe behavior, not permission to weaken lifecycle or source ownership checks. Also cover a CURRENT saved chapter absent from the refreshed catalog: falling back to the first body cannot silently replace that persisted chapter. An explicitly requested entry absent from the catalog must remain pending with a selection/retry action, not be consumed as a successful first-chapter entry.

- [ ] **Step 2: Add a retained recovery gate, not a second progress store**

Append a `positionNeedsReview: Boolean = false` state flag. Keep the original persisted location separately in the ViewModel only for this retained session; no metadata migration rewrites it. The restore decision is explicit:

```kotlin
fun needsPositionReview(
    saved: ReadingLocation?, loaded: LoadedChapter, explicitlySelected: Boolean,
): Boolean = !loaded.boundaryVerified ||
    (!explicitlySelected && saved != null && (
        saved.bookId != loaded.chapter.bookId ||
        saved.sourceId != loaded.chapter.sourceId ||
        saved.chapterId != loaded.chapter.chapterId ||
        saved.contentFormatVersion != ChapterContentFormat.CURRENT ||
        saved.offset !in 0..loaded.content.length
    ))
```

Use a small pure helper in an allowed reader file and test it. A confirmed verified chapter selection starts at zero; an explicitly confirmed currently visible position uses its real bounded offset. Do not reinterpret an old offset as a new chapter or silently clamp it into a shorter repaired body. While the recovery gate is active, do not enqueue/save a new location in response to restore/layout/stop/close. Closing still leaves the retained original bookmark untouched. Successful trusted confirmation goes through the existing serialized exact write; failure keeps recovery active and the old bookmark usable.

Show a clear existing-style recovery notice with the known old chapter/offset and an action to choose the actual chapter from the catalog; for verified new content, also allow an explicit position confirmation. Do not display a precise corrected character percentage for an unverified legacy body. Selecting a chapter must clear the gate only after trusted content actually loads, not before its request, so failure cannot overwrite the old record with an unintended zero. Retain pending recovery and explicit-entry intent across configuration changes. No automatic cross-source switch.

This is the minimal legacy bridge; bounded multi-chapter reading and Unicode character UI remain the next phase. Add only focused helper/UI files if the existing file becomes too large, after reporting their exact responsibility to root.

## Final verification and handoff

- [ ] Run all focused RED/GREEN cycles, then the full four-task milestone command with the established offline/no-daemon/in-process flags; read actual XML and SARIF counts. Verify generated v5 schema and non-destructive migration data independently; compiled Android migration/UI tests are not runtime proof.
- [ ] Self-review, `git diff --check`, stage exact app/test/schema files only, and commit. Release writer/Gradle ownership after every process has exited. Root independently verifies and requests specification then quality review; fix/re-review any issues before the continuous-reading stage.
- [ ] Do not copy or advertise a new stable APK until all current repair stages are integrated. Preserve both existing delivered APK files, icon, app palette and signing identity.
