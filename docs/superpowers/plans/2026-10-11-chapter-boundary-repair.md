# Chapter Boundary Repair Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop a chapter request from silently joining later catalog chapters while preserving legitimate same-chapter pages.

**Architecture:** A focused boundary helper distinguishes known catalog URLs, next-chapter link semantics, and normalized page titles. The real chapter fetcher passes its book/source catalog context to the existing engine. Reader continuation, old-cache repair, character-progress UI and prefetch lifecycle changes follow after this independently reviewed stage; this stage alone is not the complete user fix.

**Tech Stack:** Kotlin, existing SourceEngine/Jsoup/OkHttp, MockWebServer, JUnit, Gradle.

---

## Overall repair checklist

- [x] Correct network chapter boundaries (this plan; long-title audit fix independently verified and reviewed).
- [ ] Distinguish legacy mixed content and preserve old positions/files during repair.
- [ ] Implement bounded continuous reading in both modes, actual-visible-chapter progress and Unicode character-count display.
- [ ] Keep upcoming two-chapter caching automatic, reuse in-flight requests and validate request-count performance.
- [ ] Independent full verification/reviews and a separate stable test APK; no claim of device verification without running it.

The downstream stages have separate focused implementation plans once their interfaces are established. Do not bundle them into the boundary commit. The approved design is `docs/superpowers/specs/2026-10-11-continuous-reading-repair-design.md`.

## Task 1: Guard chapter pagination and connect actual catalog context

**Files:**

- Create: `app/src/main/java/com/novelradar/app/source/parse/ChapterPageBoundary.kt` (URL/title/link boundary only).
- Modify: `app/src/main/java/com/novelradar/app/source/parse/ChapterPageParser.kt` (`ChapterPage` carries optional next-link text).
- Modify: `app/src/main/java/com/novelradar/app/source/SourceEngine.kt` (guard before fetching or appending another page).
- Modify: `app/src/main/java/com/novelradar/app/data/repository/DefaultChapterRepository.kt` (only `ChapterPageFetcher` and `RuleBasedChapterContentFetcher` catalog-context seam).
- Modify: `app/src/main/java/com/novelradar/app/app/AppContainer.kt` (wire that seam to actual `ChapterDao`).
- Test: `app/src/test/java/com/novelradar/app/source/parse/ChapterPageBoundaryTest.kt`.
- Test: `app/src/test/java/com/novelradar/app/source/parse/ChapterPageParserTest.kt`.
- Test: `app/src/test/java/com/novelradar/app/source/SourceEngineTest.kt`.
- Test: `app/src/test/java/com/novelradar/app/data/repository/DefaultChapterRepositoryTest.kt` and focused HTTP/fetcher integration fixture.
- Update only existing test lambdas affected by the fetch signature; no unrelated app/test refactor.

- [x] **Step 1: Write and observe the behavioral RED**

Add the following regression inside the existing `SourceEngineTest` (its `withServer`, `routing`, `html`, `rule` and `engine` helpers already exist):

```kotlin
@Test fun nextChapterIsNotAppendedAsAnotherPage() = withServer { server ->
    server.dispatcher = routing { request ->
        val number = request.requestUrl!!.encodedPath.substringAfterLast('/').toInt()
        val next = if (number < 3) "<a class=next href='/chapter/${number + 1}'>下一页</a>" else ""
        html("<h1>第${number}章</h1><div id=content><p>正文$number</p></div>$next")
    }
    val selected = rule(server).copy(chapter = ChapterRuleDto(
        title = "h1", content = "#content", nextPage = "a.next", pagination = true,
    ))
    val result = runBlocking { withTimeout(10_000) {
        engine().chapter(selected, "source-1", server.url("/chapter/1").toString(), allowCleartext = true)
    } }
    val page = (result as SourceResult.Success).value
    assertThat(page.title).isEqualTo("第1章")
    assertThat(page.content).isEqualTo("正文1")
    assertThat(server.requestCount).isEqualTo(2)
}
```

Run this existing-API test first; expect an assertion failure because it currently includes `正文2`/`正文3`, not a compiler error. Archive the actual RED XML under ignored `app/build/reports/continuous-reading-boundary-red/`. Only afterward add pure-helper, parser and catalog-context tests. Cover:

1. Known next catalog URL stops before request; generic “下一页” text cannot bypass a known boundary.
2. Explicit “下一章”/“下章”/“Next chapter” stops before request.
3. Same named chapter with explicit page suffixes `(1/3)`, `(2/3)` or `（第2页）` joins completely.
4. Nonempty different titles are not joined even with a misleading “下一页” link.
5. Generic/blank identity cannot serve as proof for an unknown continuation; fail with a typed boundary parse failure rather than silently join or claim a complete chapter.
6. Redirect to a different known catalog chapter is rejected before appending; loops and bounded legitimate pagination still work.
7. HTTP policy, byte bounds, cancellation and source/catalog ownership remain intact.

- [x] **Step 2: Add the minimal focused types and parser link data**

The context and guard API are deliberately small:

```kotlin
import java.text.Normalizer
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class ChapterRequestContext(val knownChapterUrls: Set<String> = emptySet())

class ChapterPageBoundary(initialUrl: String, context: ChapterRequestContext) {
    private val initial = canonical(initialUrl)
    private val known = context.knownChapterUrls.mapNotNull(::canonical).toSet()
    fun isOtherCatalogChapter(url: String): Boolean {
        val candidate = canonical(url) ?: return false
        return candidate != initial && candidate in known
    }
    fun isExplicitNextChapter(label: String?): Boolean {
        val text = normalized(label.orEmpty())
        return listOf("下一章", "下章", "下一回", "nextchapter").any(text::contains)
    }
    fun isKnownTitle(title: String): Boolean {
        val text = normalized(title)
        return text.isNotBlank() && text !in GENERIC && !PAGE_ONLY.matches(text)
    }
    fun sameChapterTitle(first: String, later: String): Boolean =
        isKnownTitle(first) && isKnownTitle(later) && normalized(first) == normalized(later)
    private fun canonical(url: String): String? =
        url.toHttpUrlOrNull()?.newBuilder()?.fragment(null)?.build()?.toString()
    private fun normalized(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKC)
        .filterNot(Char::isWhitespace).replace(PAGE_SUFFIX, "").lowercase(Locale.ROOT)
    private companion object {
        val GENERIC = setOf("正文", "章节", "内容", "小说", "chapter", "content")
        val PAGE_SUFFIX = Regex("""\s*[\(\[【]\s*(?:\d+\s*/\s*\d+|第?\s*[0-9零〇一二三四五六七八九十百千万]+\s*页)\s*[\)\]】]\s*$""")
        val PAGE_ONLY = Regex("""(?:第?[0-9零〇一二三四五六七八九十百千万]+页|page[0-9]+)""")
    }
}
```

Implement these functions, not a matching framework. Compare canonical HTTP URLs with only the fragment removed; preserve query parameters because they may identify chapters or pages. Normalize titles with Unicode NFKC, whitespace and Locale.ROOT case handling, removing only explicit trailing page-number syntax. Do not discard chapter numbers or arbitrary subtitle parentheses. Empty titles and generic boilerplate such as “正文”/“章节” cannot prove identity. A known unique identical title without a chapter number remains legitimate (e.g. “序章”).

`ChapterPage` retains its existing three fields and appends a defaulted field:

```kotlin
data class ChapterPage(
    val title: String,
    val content: String,
    val nextPageUrl: String? = null,
    val nextPageLabel: String? = null,
)
```

The parser captures `node.text()` from the same selected pagination node while preserving existing selector/script/URL behavior. This is not permission to parse another link or bypass unsafe-rule errors.

- [x] **Step 3: Apply guards to the actual engine and dependency graph**

Add a defaulted context to the existing engine API, preserving its four positional arguments:

```kotlin
suspend fun chapter(
    rule: RuleDto, sourceId: String, chapterUrl: String,
    allowCleartext: Boolean = false,
    context: ChapterRequestContext = ChapterRequestContext(),
): SourceResult<ChapterPage> {
    if (!safeId(sourceId)) return unsafe("invalid source ID")
    if (rule.disabled) return unsafe("source is disabled")
    val chapterRule = rule.chapter ?: return unsafe("source has no chapter rule")
    val boundary = ChapterPageBoundary(chapterUrl, context)
    val visited = hashSetOf<String>()
    val parsedFinalUrls = hashSetOf<String>()
    val parts = mutableListOf<String>()
    var contentBytes = 0L
    var url = chapterUrl
    var title: String? = null
    fun accepted() = SourceResult.Success(ChapterPage(title.orEmpty(), parts.joinToString("\n\n")))
    fun ambiguous() = SourceResult.Failure(SourceFailure.Parse("chapter.boundary", chapterRule.nextPage.orEmpty()))
    repeat(MAX_CHAPTER_PAGES) {
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (boundary.isOtherCatalogChapter(url)) return if (parts.isEmpty()) ambiguous() else accepted()
        if (!visited.add(url)) return accepted()
        val page = when (val fetched = getPage(sourceId, url, allowCleartext)) {
            is SourceResult.Success -> fetched.value
            is SourceResult.Failure -> return fetched
        }
        if (boundary.isOtherCatalogChapter(page.finalUrl)) return if (parts.isEmpty()) ambiguous() else accepted()
        if (!parsedFinalUrls.add(page.finalUrl)) return accepted()
        visited.add(page.finalUrl)
        val html = when (val decoded = decode(page)) {
            is SourceResult.Success -> decoded.value
            is SourceResult.Failure -> return decoded
        }
        val parsed = when (val result = chapterParser.parse(html, page.finalUrl, chapterRule)) {
            is SourceResult.Success -> result.value
            is SourceResult.Failure -> return result
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        if (title != null) {
            if (!boundary.isKnownTitle(title!!) || !boundary.isKnownTitle(parsed.title)) return ambiguous()
            if (!boundary.sameChapterTitle(title!!, parsed.title)) return accepted()
        } else title = parsed.title
        val additionalBytes = parsed.content.toByteArray(Charsets.UTF_8).size.toLong() + if (parts.isEmpty()) 0 else 2
        if (contentBytes + additionalBytes > MAX_CHAPTER_CONTENT_BYTES) {
            return unsafe("chapter content exceeds $MAX_CHAPTER_CONTENT_BYTES UTF-8 bytes")
        }
        contentBytes += additionalBytes
        parts += parsed.content
        val next = parsed.nextPageUrl
        if (!chapterRule.pagination || next == null || next in visited ||
            boundary.isOtherCatalogChapter(next) || boundary.isExplicitNextChapter(parsed.nextPageLabel)) return accepted()
        url = next
    }
    return accepted()
}
```

Use the existing engine private helpers/constants shown above; import `kotlinx.coroutines.ensureActive`. Add the boundary types to the existing imports. The two local result functions are request-scoped, not public/test-only API. Minor implementation improvements are permitted only if they preserve all tests and this exact fail-closed behavior.

Within the existing loop, keep all policy/decode/cancellation/body-bound checks. Before a continuation request, stop successfully if its URL is another known catalog chapter or its link explicitly says next chapter. After fetching, a redirect to another known chapter must not be attributed to the requested chapter. Before appending a second/subsequent parsed page, require a known normalized title equal to the first page's chapter identity. A proven different chapter ends the current successful result without appending it; an ambiguous continuation returns `SourceFailure.Parse("chapter.boundary", chapterRule.nextPage.orEmpty())`. Apply the body-size accounting only to accepted current-chapter pages. The first-page title is still the returned display title. Preserve `MAX_CHAPTER_PAGES = 20` for genuinely same-chapter pages.

The fetch seam carries context rather than querying UI state:

```kotlin
fun interface ChapterPageFetcher {
    suspend fun fetch(rule: RuleDto, sourceId: String, url: String,
        allowCleartext: Boolean, context: ChapterRequestContext): SourceResult<ChapterPage>
}
```

Append `chapterUrls: suspend (String, String) -> List<String> = { _, _ -> emptyList() }` to `RuleBasedChapterContentFetcher` after its existing parser parameter. Construct context from `chapterUrls(chapter.bookId, chapter.sourceId)`; failures and cancellation cannot fall back to an unsafe context in the production path. In the real graph use:

```kotlin
RuleBasedChapterContentFetcher(
    sourceRules,
    ChapterPageFetcher { rule, sourceId, url, allowed, context ->
        sourceEngine.chapter(rule, sourceId, url, allowed, context)
    },
    ruleParser,
    chapterUrls = { bookId, sourceId ->
        database.chapterDao().getChapters(bookId, sourceId).mapNotNull { it.url }
    },
)
```

Test this actual rule-fetcher/catalog-context path, not merely a mock accepting the new parameter. The empty default preserves standalone fixture convenience but cannot be mistaken for evidence that the production graph supplies the catalog. Do not change repository cache/storage/leases, database schema, worker, identities, icon, or reader UI in this stage.

- [x] **Step 4: Verify, self-review and commit explicit files**

Replace the old twenty-*different*-chapter sample with twenty pages of one genuinely named chapter, then preserve its exact twenty-page cap assertion. Add the separate cross-chapter regression above; do not simply relax old tests to pass. Check legitimate multi-page contents remain complete, including page separators and exact aggregate byte boundaries.

Run focused parser/engine/fetcher tests, then:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestSources --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
git diff --check
```

Record actual XML test/suite/failure/error/skip counts and all lint warnings. Explicitly stage only this stage's app/test files, commit `fix: keep chapter pagination inside catalog boundaries`, and release writer/Gradle ownership. Root independently verifies, then separate specification and quality reviewers inspect the actual cumulative stage diff. Do not start the next implementation stage with unresolved review issues. Do not copy a new stable APK yet: continuous reader/progress and legacy-cache stages remain pending.

Completion evidence (2026-10-11): application commit `1275491595a75500a622f428a230a9becbb00a82` contains exactly the nine authorized files. Root directly inspected the original cross-chapter RED (1 test/1 failure), engine RED (37/8), fragment-only duplicate RED (1/1), actual production graph/seam and the real fetcher→HTTP regression. The recorded parser/helper GREEN was 12 tests/2 suites; engine GREEN 50/3; final focused GREEN 75/4, all zero failures/errors/skips. Independent full milestone verification exited 0. Root additionally forced a unit rerun: all 26 tasks executed in 1m 7s, producing 421 tests/43 suites with zero failures/errors/skips. SARIF has seven existing warnings and zero errors. Independent specification and code-quality reviews both passed without outstanding issues. The fragment-only regression led to request-scoped network URL canonicalization in the chapter loop; stored chapter URLs/IDs are unchanged. No reader/cache/schema/download/resource logic or stable deliverable APK changed in this stage. The next non-destructive content/progress upgrade is planned in `2026-10-11-content-provenance-upgrade.md`; continuous reading, character display and prefetch lifecycle remain incomplete.

Additional performance audit (2026-10-11): before entering the next stage, root ran the actual Java page-suffix pattern against interior-whitespace titles: 8,000 spaces took 62 ms and 16,000 spaces 249 ms on the local JDK. The unanchored leading whitespace scan happens before whitespace normalization, so a permitted script-produced long title can produce disproportionate work. Reopen this stage for a focused TDD regression and move whitespace removal before suffix matching; retain all semantic boundary tests. A generous bounded-time regression must fail on the old ordering, then pass along with a new internally spaced Chinese page-suffix case. This is not a promise of real-phone speed and is not an additional product feature. Re-run independent verification and both reviews before closing the checklist again.

Audit-fix completion (2026-10-11): `b9771c532b474681d27c7ae72bd2a2234dd1662a` changes only one normalization ordering line and adds two tests. Root directly read the RED (8 tests/2 failures/0 errors/skips; the long case timed out at 3.006 seconds) and focused GREEN (8 passed, long case 0.017 seconds). Independent full milestone verification passed; root forced all 26 unit tasks again in 52 seconds, producing 423 tests/43 suites with zero failures/errors/skips, and the long case took 0.006 seconds in that run. Independent cumulative specification then quality re-reviews of `f3591fa → b9771c5` both passed with no outstanding issues. Existing chapter identity/URL/cancellation/limits contracts and all seven existing lint warnings remain. Both previously delivered APK hashes are unchanged. Only this source stage is closed; no new stable user APK has been delivered and reader/provenance/character-progress/prefetch work is still pending.
