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
