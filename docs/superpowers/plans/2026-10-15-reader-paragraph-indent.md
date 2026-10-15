# Reader Paragraph Indentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved two-character first-line indentation in both reader modes without altering text or progress.

**Architecture:** A shared display model retains original UTF-16 ranges and uses at most two styled fragments per slice. Genuine-boundary slices stay one Text with a single style covering native newlines; only an initial continuation may split before its first newline into 0.em prefix and 2.em tail, with one separator consumed by the layout boundary. Scroll combines fragment layouts for original-offset mapping; paging measures and draws the same model. See the quality follow-up below: the initial per-newline-style implementation was rejected, not delivered.

**Tech Stack:** Existing Kotlin/Compose AnnotatedString, ParagraphStyle, TextIndent, em units, JUnit and compiled Compose tests; no new dependencies.

## Task 1: Implement and verify one atomic presentation repair

**Files:** create `app/src/main/java/com/novelradar/app/feature/reader/ReaderParagraphText.kt` and mirrored `app/src/test/java/com/novelradar/app/feature/reader/ReaderParagraphTextTest.kt`; modify `ScrollReader.kt`, `PagedReader.kt`, and existing Android `ReaderScreenTest.kt`. Root owns docs and final artifact copy. No source/parser/cache/VM/offset/schema changes.

- [x] Add focused tests before implementation and observe assertion RED; a compiling plain-substring scaffold is allowed, not a completed formatter. Test exact slice text, firstLine 2.em/restLine 0.em, per-newline boundaries, continuation firstLine zero, a page beginning after newline, empty/blank paragraphs and emoji offsets. Test that formatting doesn't change original character progress or ReaderItem.position.

```kotlin
val text = "甲乙丙丁\n第二段😀正文"
val part = readerParagraphText(text, 2, text.length)
assertEquals(text.substring(2), part.text)
assertEquals(TextIndent(firstLine = 0.em, restLine = 0.em), part.paragraphStyles.first().item.textIndent)
assertEquals(TextIndent(firstLine = 2.em, restLine = 0.em), part.paragraphStyles.last().item.textIndent)
```

- [x] Implement `internal fun readerParagraphText(text: String, start: Int, end: Int): AnnotatedString`. Require a valid 0..length slice; append precisely substring(start,end). For each local newline-delimited range, derive its original start; firstLine is 2.em iff originalStart == 0 or text[originalStart-1] == '\n', otherwise 0.em. All restLine values are 0.em. Never add/remove literal spaces, mutate cache or normalize stored offsets.
- [x] In ScrollReader, replace only body Text's substring argument with `readerParagraphText(...)`; existing TextLayoutResult offsets still index the unchanged slice. In PagedReader, measure the annotated candidate at original start/hardEnd and draw the annotated final page using the identical helper. Keep the unindented glyph capacity estimate and title styling separate; final measured body height remains authoritative. Do not use a global firstLine style that reindents continuation slices.
- [x] Add actual Compose regressions to existing ReaderScreenTest for both modes' rendered layout: genuine paragraph first-line horizontal offset, wrapped/continued lines without repeated indent, and exact position after font/mode changes. Without a device these are compiled only; no invented runtime RED/GREEN or new emulator/dependencies.
- [x] Run focused JUnit RED/GREEN and full milestone tasks, read real XML/SARIF, self-review, exact five-file commit, release all writer/Gradle sessions to root. Preserve unrelated existing docs.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.novelradar.app.feature.reader.ReaderParagraphTextTest' --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestSources --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

## Task 2: Independent review and separate test APK

- [x] Root independently repeats full verification and actual unit rerun, inspects source/offset/pagination compatibility, then obtains independent specification and quality review in that order. Fix and re-review any required issues.
- [x] Verify actual package `com.novelradar.app` / version 0.1.0 code 1 / min26 target36, original debug certificate and v2 signature, exact user-icon bytes, SHA-256 and size. Copy verified output to a unique `NovelRadar-0.1.0-test-indent-<application-commit>.apk` with overwrite disabled; keep the repair-adabb7c and both earlier APKs.
- [x] Update docs/test-apk-notes.md, commit only owned docs, and hand off the clickable new APK with a short phone checklist. Do not install/uninstall, merge/push or claim device acceptance. Existing palette/system fonts stay unchanged; only paragraph first-line layout changes.

## Verification checkpoint

Application commit `8986c37bf7d28d53ea97e8f4109034067c40d5cc` changes exactly the planned five files. The focused assertion RED report has 12 tests / 11 failures, all `AssertionError`, zero errors/skips; the GREEN report has 12 tests / zero failures/errors/skips. Both remain under `app/build/evidence/paragraph-indent/`. The earlier exploratory RED report with two empty-list exceptions is retained but is not the clean assertion-RED evidence.

Root independently repeated Full4, a whole-unit rerun, and finally `:app:testDebugUnitTest --rerun-tasks`: exit 0, one minute six seconds, all 26 tasks executed. Fresh reports contain 590 tests across 52 suites with zero failures, errors or skips. Post-force Full4 exited 0 in eight seconds. Lint retains the same seven warnings, zero errors and no suppressions. The three new native-layout tests compiled but did not run; final ADB inventory had no attached target.

Specification review passed for `8986c37`. Quality review found one required P2: separate paragraph-style ranges containing terminal newlines can each add a native empty final line, creating extra blank lines. Root checked the helper and Android's terminal-newline logic and returned the issue to the same implementation owner. This candidate must not be delivered. Its DEX/icon/signature checks do not establish correctness after the pending fix.

### Required review follow-up

- [x] Verify Compose style normalization/native paragraph handling, add focused regression assertion RED, then fix newline separation once without altering raw text or UTF-16 positions. Use at most two original-range fragments, not a separate Text/style for every paragraph. A continuation prefix excludes only its first separator from the drawn fragment; the boundary consumes it once and the tail preserves all remaining original text. Composite scroll layout must map back to original positions, and paging must measure/draw identical fragments, including final candidate cuts and newline edges.
- [x] Add native layout assertions for two lines from `甲\n乙`, three from `甲\n\n乙`, and continuation slices; compile only unless a real attached device is available.
- [x] Repeat final verification and specification/quality review on the corrected application commit, then package the corrected output rather than the rejected candidate.

The user's additional request is to discuss frontend optimization after this APK is delivered. No frontend redesign belongs in this repair package.

## Final corrected delivery

Final application commit `b15b995da1cdb00683462b0950b71e4e92ad2974` passed fresh Spec and Quality reviews, with no outstanding findings. Root read correction RED 15/10 explicit assertion failures/zero errors or skips and GREEN 15/0. Independent Full4, all-26-task forced unit run (one minute six seconds), and post-force Full4 (seven seconds) all exited 0. Fresh XML is 593 tests / 52 suites / zero failures/errors/skips; effective lint levels remain seven warnings, zero errors. Native layout cases compiled only; no ADB target or installation.

After actual metadata, original signature, exact icon and corrected DEX checks, saved with overwrite disabled as `D:\NovelReader\build\deliverables\NovelRadar-0.1.0-test-indent-b15b995.apk`, 34,229,500 bytes, SHA-256 `8C0487F1D0903D8B566B15F95D1B765EF3AB50870A28CC72FF203C76CD82154C`. Saved-copy signature and hash match; all three older stable APK hashes remain unchanged. Clickable artifact and cover-install/no-uninstall advice were handed off. Only owned docs are committed; branch, worktree and user data are preserved.
