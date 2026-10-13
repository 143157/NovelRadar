# Explicit Fraction Page Suffix Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve both pages of one named chapter when its titles end in an explicit fraction such as `（第1/2页）`, without joining another chapter.

**Architecture:** Extend only the existing trailing page-suffix branch in `ChapterPageBoundary`. The real engine/parser/HTTP regression proves the accepted content stays complete and a known next catalog chapter is still not requested. No new matching framework or content/progress format is introduced.

**Tech Stack:** Existing Kotlin boundary helper, SourceEngine, Jsoup, MockWebServer, JUnit, offline Gradle.

---

## Evidence and execution boundary

During the position-recovery review, root checked the actual source pattern with an ASCII-escaped Java diagnostic. `第一章(第1/2页)` and `第一章(第2/2页)` did not normalize to one identity, whereas the already-covered `(1/2)` suffix was removed. In the actual engine, two known different titles cause successful termination before appending the second page. This is a concrete input-based risk of incomplete chapter content, not evidence that a particular live website uses this format.

First finish the pending position-recovery specification/quality reviews and root verification. Then execute this single focused task before the existing continuous-reader plan. One app/test writer and one Gradle owner; root edits documentation only. Preserve all reviewed URL/query/fragment, catalog ownership, generic-title, subtitle, cancellation, cleartext, loop, twenty-page and 8 MiB byte-limit contracts. No schema, cache marker, chapter/book identity, reader, downloader, icon, signing or stable APK change.

## Task 1: Preserve explicit fraction pages with a real HTTP regression

**Files:**

- Modify: `app/src/main/java/com/novelradar/app/source/parse/ChapterPageBoundary.kt`, only the `PAGE_SUFFIX` expression.
- Test: `app/src/test/java/com/novelradar/app/source/parse/ChapterPageBoundaryTest.kt`.
- Test: `app/src/test/java/com/novelradar/app/source/SourceEngineTest.kt`.

- [ ] **Step 1: Add the actual engine regression before changing production code**

The existing `withServer`, `html`, `paginatedChapterRule` and `engine` fixtures exercise real parsing and local HTTP. Add this test; do not replace existing plain-fraction or next-chapter tests:

```kotlin
@Test fun explicitFractionPagesRetainContentWithoutRequestingNextChapter() = withServer { server ->
    server.enqueue(html("<h1>第一章（第1/2页）</h1><div id=content>首段</div>" +
        "<a class=next href='/page/2'>下一页</a>"))
    server.enqueue(html("<h1>第一章（第2/2页）</h1><div id=content>尾段</div>" +
        "<a class=next href='/chapter/2#body'>下一页</a>"))
    server.enqueue(html("<h1>第二章</h1><div id=content>不可合并</div>"))
    val result = runBlocking { withTimeout(10_000) {
        engine().chapter(paginatedChapterRule(server), "source-1",
            server.url("/chapter/1").toString(), allowCleartext = true,
            context = ChapterRequestContext(setOf(server.url("/chapter/1").toString(),
                server.url("/chapter/2#catalog").toString())))
    } }
    assertThat(result).isInstanceOf(SourceResult.Success::class.java)
    val page = (result as SourceResult.Success).value
    assertThat(page.content).isEqualTo("首段\n\n尾段")
    assertThat(page.title).isEqualTo("第一章（第1/2页）")
    assertThat(server.requestCount).isEqualTo(2)
    assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.path).isEqualTo("/chapter/1")
    assertThat(server.takeRequest(1, TimeUnit.SECONDS)!!.path).isEqualTo("/page/2")
}
```

- [ ] **Step 2: Observe behavioral RED and add focused identity assertions**

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.novelradar.app.source.SourceEngineTest.explicitFractionPagesRetainContentWithoutRequestingNextChapter' --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

Expected RED: an actual assertion, expected `首段\n\n尾段`, actual `首段`; zero errors/skips. Retain the XML under ignored `app/build/fraction-page-evidence/red/` before another test run overwrites it. A compiler/encoding failure is not the required evidence.

Add these assertions to the existing helper suite, run them against the unchanged helper and save their RED XML too:

```kotlin
@Test fun explicitFractionSuffixesKeepTheNamedChapterIdentity() {
    val boundary = ChapterPageBoundary("https://books.example.test/1")
    listOf("第一章（第1/2页）", "第一章 (第 2 / 2 页)", "第一章【2/2页】",
        "第一章[第2/2]", "第一章（第２／２页）").forEach {
        assertThat(boundary.sameChapterTitle("第一章", it)).isTrue()
    }
    assertThat(boundary.sameChapterTitle("第一章（第1/2页）", "第一章（第2/2页）")).isTrue()
}

@Test fun fractionPagesDoNotEraseChapterNumbersSubtitlesOrGenericIdentity() {
    val boundary = ChapterPageBoundary("https://books.example.test/1")
    assertThat(boundary.sameChapterTitle("第一章（第1/2页）", "第二章（第2/2页）")).isFalse()
    assertThat(boundary.sameChapterTitle("第一章（一）", "第一章（二）")).isFalse()
    assertThat(boundary.sameChapterTitle("第一章（相遇）", "第一章（重逢）")).isFalse()
    listOf("正文（第1/2页）", "章节（第2/2页）", "（第1/2页）").forEach {
        assertThat(boundary.isKnownTitle(it)).isFalse()
        assertThat(boundary.sameChapterTitle(it, it)).isFalse()
    }
}
```

- [ ] **Step 3: Extend only the fraction suffix branch and verify GREEN**

Preserve whitespace filtering before matching, NFKC normalization, locale handling and all bracket/Chinese-page behavior. Change only the fraction alternative from `\d+\s*/\s*\d+` to `第?\s*\d+\s*/\s*\d+\s*页?`:

```kotlin
val PAGE_SUFFIX = Regex("""\s*[\(\[【]\s*(?:第?\s*\d+\s*/\s*\d+\s*页?|第?\s*[0-9零〇一二三四五六七八九十百千万]+\s*页)\s*[\)\]】]\s*$""")
```

Do not strip arbitrary parenthesized numbers, subtitles or non-trailing text, guess additional website formats, or alter `PAGE_ONLY`/engine policy without separately demonstrated need. Run both existing helper and full engine suites:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests 'com.novelradar.app.source.parse.ChapterPageBoundaryTest' --tests 'com.novelradar.app.source.SourceEngineTest' --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

Expected GREEN: all assertions pass, including the existing long-interior-whitespace bound, legitimate plain fractions, ambiguous titles and exact aggregate UTF-8 limits. Save fresh focused XML under `app/build/fraction-page-evidence/green-focused/`.

- [ ] **Step 4: Verify fully, self-review, commit and release ownership**

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestSources --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
git diff --check
git add -- app/src/main/java/com/novelradar/app/source/parse/ChapterPageBoundary.kt app/src/test/java/com/novelradar/app/source/parse/ChapterPageBoundaryTest.kt app/src/test/java/com/novelradar/app/source/SourceEngineTest.kt
git commit -m "fix: preserve explicit fraction chapter pages"
```

Record actual exit codes, XML test/suite/failure/error/skip totals, unchanged seven existing lint warnings and zero errors. Commit only the three declared files after verification. Release writer/build ownership after every process exits. No stable APK copy or device-runtime claim.

- [ ] **Step 5: Independent root verification, specification then quality review**

Root inspects the real RED/GREEN, cumulative three-file diff and reruns full milestone/forced units. Independent specification review verifies the exact input format, complete first-chapter content and zero next-chapter requests; subsequent quality review checks the minimal expression and retained fail-closed semantics. Fix and re-review required findings before returning to Task 1 of `2026-10-11-continuous-reader-and-character-progress.md`.

For this inter-task forced run, the actual Gradle task supports `:app:testDebugUnitTest --rerun` with the established flags: rerun all unit tests, not unchanged compilation prerequisites. Root checked its real task help on October 13. Final cumulative repair verification still forces all prerequisites with `--rerun-tasks` before delivery.
