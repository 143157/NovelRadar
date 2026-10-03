# Download implementation constraints

Recorded 2026-10-01 before the download implementation. These are implementation and acceptance constraints, not claims that downloads have been completed or tested on Android.

## Compatibility

- Preserve SoNovel crawl interval integers as milliseconds. The reference binary passes them directly to `Thread.sleep(long)`; see `source-verification-notes.md`. An interval of 1000 must produce a one-second coroutine delay. Correct the DTO's contradictory seconds comment when adding the consumer.
- Download sequentially and respect the selected source's delay. Cached, hash-verified chapters must not be fetched again. Only verified successful chapters become `DOWNLOADED`.
- Keep the established book/source/chapter identities. Do not replace chapter metadata with stale whole-row copies after a catalog refresh.

## Durable task ownership

- Persist the task before enqueuing `book-download:<bookId>:<sourceId>` with `KEEP`, and enforce one active whole-book task per book even across sources.
- Every checkpoint and final state transition must belong to the current work generation. A stopped old worker must not overwrite a newer resume or an explicit pause/cancel state.
- Resume must wait for previous unique work to become terminal. File deletion must additionally wait for actual download writes to stop; a WorkManager cancellation acknowledgement alone is not a file-write barrier. Use the shared download coordination boundary before deleting local chapters.
- Pause/cancel preserves completed files and progress. Deletion remains a separate, explicit user action. Persist failed chapter IDs and retry attempts; retrying failures must not fetch successful chapters again.
- Distinguish network failures from storage failures. Storage exhaustion pauses with a recoverable message and keeps checkpoints; it must not consume network retry attempts or cause automatic deletion.

## Android scheduling limits

Use the approved WorkManager foreground-worker design, with the `dataSync` service type and required manifest permissions. Android's [long-running worker documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running) notes that Android 16 job quotas can interrupt this approach. [Foreground-service timeouts](https://developer.android.com/develop/background-work/services/fgs/timeout) also constrain long data-sync work. Preserve checkpoints and allow later recovery; do not promise uninterrupted overnight downloading or bypass system limits.

The [work-management documentation](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/manage-work) distinguishes cancellation from cooperative worker cleanup. Cancellation, process death, changed network constraints, and platform stops all need recovery tests.

Network restrictions must be explicit at task creation. The conservative default is non-metered networking, with a deliberate opt-in for mobile data. Notification permission denial must not be treated as consent to delete or cancel a task; explain visibility limitations and follow platform-permitted foreground behavior.

## Verification boundaries

JVM tests can prove coordinator ordering, retry policy, file verification, and checkpoint logic. Compiling Android worker and UI tests does not prove scheduling, foreground-service startup, notification permissions, or process-death behavior. Record those separately as unverified until an actual Android target is available.

## Implemented core checkpoint (2026-10-03)

The download core is committed in `0d1e016`, `2ed79d0`, and `21f0025` and has passed separate specification and code-quality reviews. Pause, cancellation, generation-owned checkpoints, explicit manual retry, startup reconciliation, foreground declarations, and coordinated local-file deletion are implemented. Source crawl integers are consumed as milliseconds. Verified cached files can be promoted without refetching, and a slow download of one chapter no longer blocks another chapter's cached read in the same book.

Room version 4 records which non-network failures were already attempted in the current execution generation. A new explicit resume clears that marker; an automatic restart does not. The 3→4 migration preserves existing metadata and makes old active tasks recoverably paused rather than guessing their current-generation attempts.

Independent verification ran all four milestone commands successfully, followed by a forced unit-test rerun: 316 tests, 38 suites, zero failures/errors; lint reports seven existing warnings and zero errors. Host SQLite checks also covered the actual 2→3→4 and 3→4 migration SQL in memory. No actual Android Worker/Room/notification behavior or APK installation has been verified; a fresh `adb devices -l` check found no attached target. The download screen, details-page start flow, persistent network default, and runtime notification permission handling are still pending Task 7, so this checkpoint is not the final APK handoff.

## Implemented management checkpoint (2026-10-04)

Task 7 is committed in `d6c8e3a` and `44d534b` and passed separate specification and code-quality reviews. The actual download tab, detail-page start confirmation, persistent new-task network default, per-task network choice, and API-gated notification-permission handoff are connected. Permission denial does not gate creation. Completed tasks open their own source rather than borrowing another source's saved position.

User pause/cancel checks allowed state inside the latest-row transaction. If a worker completes first, a stale request leaves its completed state, ownership, and counts unchanged. Explicit book-wide deletion remains a separate authorized operation that revokes completed owners too, waits for actual content operations to drain, and clears all sources' downloaded and cached bodies while preserving catalog, shelf, and reading history. Cancellation feedback says it was requested rather than asserting it changed a terminal task.

Independent full verification exited successfully with 348 unit tests across 40 suites and zero failures/errors; lint reports six existing warnings and zero errors. The extra coordinator regressions were observed failing before the fix and passing afterward. Debug APK signing verification passed; the stable test artifact is described in [test-apk-notes.md](test-apk-notes.md). Android tests remain compile-only: no attached target was available for notification, foreground scheduling, Room/DataStore persistence, installation, or layout verification. Safe fallback, detail completion, total cache bounds, and release hardening remain pending; this is not final first-release acceptance.
