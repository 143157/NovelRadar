# Reader Paragraph Indentation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the approved two-character first-line indentation in both reader modes without altering text or progress.

**Architecture:** One shared formatter annotates exact original UTF-16 slices with paragraph styles. Scroll draws it; paging measures and draws it, so page boundaries account for indentation and continuation pages do not indent mid-paragraph.

**Tech Stack:** Existing Kotlin/Compose AnnotatedString, ParagraphStyle, TextIndent, em units, JUnit and compiled Compose tests; no new dependencies.

## Task 1: Implement and verify one atomic presentation repair

**Files:** create `app/src/main/java/com/novelradar/app/feature/reader/ReaderParagraphText.kt` and mirrored `app/src/test/java/com/novelradar/app/feature/reader/ReaderParagraphTextTest.kt`; modify `ScrollReader.kt`, `PagedReader.kt`, and existing Android `ReaderScreenTest.kt`. Root owns docs and final artifact copy. No source/parser/cache/VM/offset/schema changes.

- [ ] Add focused tests before implementation and observe assertion RED; a compiling plain-substring scaffold is allowed, not a completed formatter. Test exact slice text, firstLine 2.em/restLine 0.em, per-newline boundaries, continuation firstLine zero, a page beginning after newline, empty/blank paragraphs and emoji offsets. Test that formatting doesn't change original character progress or ReaderItem.position.

```kotlin
val text = "甲乙丙丁\n第二段😀正文"
val part = readerParagraphText(text, 2, text.length)
assertEquals(text.substring(2), part.text)
assertEquals(TextIndent(firstLine = 0.em, restLine = 0.em), part.paragraphStyles.first().item.textIndent)
assertEquals(TextIndent(firstLine = 2.em, restLine = 0.em), part.paragraphStyles.last().item.textIndent)
```

- [ ] Implement `internal fun readerParagraphText(text: String, start: Int, end: Int): AnnotatedString`. Require a valid 0..length slice; append precisely substring(start,end). For each local newline-delimited range, derive its original start; firstLine is 2.em iff originalStart == 0 or text[originalStart-1] == '\n', otherwise 0.em. All restLine values are 0.em. Never add/remove literal spaces, mutate cache or normalize stored offsets.
- [ ] In ScrollReader, replace only body Text's substring argument with `readerParagraphText(...)`; existing TextLayoutResult offsets still index the unchanged slice. In PagedReader, measure the annotated candidate at original start/hardEnd and draw the annotated final page using the identical helper. Keep the unindented glyph capacity estimate and title styling separate; final measured body height remains authoritative. Do not use a global firstLine style that reindents continuation slices.
- [ ] Add actual Compose regressions to existing ReaderScreenTest for both modes' rendered layout: genuine paragraph first-line horizontal offset, wrapped/continued lines without repeated indent, and exact position after font/mode changes. Without a device these are compiled only; no invented runtime RED/GREEN or new emulator/dependencies.
- [ ] Run focused JUnit RED/GREEN and full milestone tasks, read real XML/SARIF, self-review, exact five-file commit, release all writer/Gradle sessions to root. Preserve unrelated existing docs.

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.novelradar.app.feature.reader.ReaderParagraphTextTest' --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestSources --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

## Task 2: Independent review and separate test APK

- [ ] Root independently repeats full verification and actual unit rerun, inspects source/offset/pagination compatibility, then obtains independent specification and quality review in that order. Fix and re-review any required issues.
- [ ] Verify actual package `com.novelradar.app` / version 0.1.0 code 1 / min26 target36, original debug certificate and v2 signature, exact user-icon bytes, SHA-256 and size. Copy verified output to a unique `NovelRadar-0.1.0-test-indent-<application-commit>.apk` with overwrite disabled; keep the repair-adabb7c and both earlier APKs.
- [ ] Update docs/test-apk-notes.md, commit only owned docs, and hand off the clickable new APK with a short phone checklist. Do not install/uninstall, merge/push or claim device acceptance. Existing palette/system fonts stay unchanged; only paragraph first-line layout changes.
