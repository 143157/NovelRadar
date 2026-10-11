package com.novelradar.app.source

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.BookIdentityEvidence
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.http.SearchRequestFactory
import com.novelradar.app.source.http.SourceHttpClient
import com.novelradar.app.source.parse.BookPageParser
import com.novelradar.app.source.parse.ChapterPageParser
import com.novelradar.app.source.parse.ChapterRequestContext
import com.novelradar.app.source.parse.HtmlSelector
import com.novelradar.app.source.parse.SearchPageParser
import com.novelradar.app.source.parse.TocPageParser
import com.novelradar.app.source.rule.BookRuleDto
import com.novelradar.app.source.rule.ChapterRuleDto
import com.novelradar.app.source.rule.RuleDto
import com.novelradar.app.source.rule.SearchRuleDto
import com.novelradar.app.source.rule.TocRuleDto
import com.novelradar.app.source.script.ScriptExecutor
import com.novelradar.app.source.script.QuickJsScriptExecutor
import java.nio.charset.Charset
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.Test
import org.junit.Assert.assertThrows

class SourceEngineTest {
    @Test fun fragmentOnlyContinuationDoesNotFetchAndAppendTheSameNetworkPage() = withServer { server ->
        server.dispatcher = routing {
            html("<h1>第一章</h1><div id=content>正文1</div><a class=next href='#page'>下一页</a>")
        }
        val result = runBlocking { withTimeout(10_000) {
            engine().chapter(paginatedChapterRule(server), "source-1",
                server.url("/chapter/1").toString(), allowCleartext = true)
        } }

        assertThat((result as SourceResult.Success).value.content).isEqualTo("正文1")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun knownNextCatalogChapterStopsBeforeAnotherRequest() = withServer { server ->
        server.enqueue(html("<h1>第一章</h1><div id=content>正文1</div><a class=next href='/chapter/2#body'>下一页</a>"))
        server.enqueue(html("<h1>第一章</h1><div id=content>另一章正文</div>"))
        val result = runBlocking { engine().chapter(paginatedChapterRule(server), "source-1",
            server.url("/chapter/1").toString(), allowCleartext = true,
            context = ChapterRequestContext(setOf(server.url("/chapter/1").toString(),
                server.url("/chapter/2#catalog").toString()))) }

        assertThat((result as SourceResult.Success).value.content).isEqualTo("正文1")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun explicitNextChapterLabelsStopBeforeAnotherRequest() {
        listOf("下一章", "Next chapter").forEach { label -> withServer { server ->
            server.enqueue(html("<h1>第一章</h1><div id=content>正文1</div><a class=next href='/chapter/2'>$label</a>"))
            server.enqueue(html("<h1>第一章</h1><div id=content>另一章正文</div>"))
            val result = runBlocking { engine().chapter(paginatedChapterRule(server), "source-1",
                server.url("/chapter/1").toString(), allowCleartext = true) }

            assertThat((result as SourceResult.Success).value.content).isEqualTo("正文1")
            assertThat(server.requestCount).isEqualTo(1)
        } }
    }

    @Test fun sameChapterWithExplicitPageSuffixesRetainsAllContent() = withServer { server ->
        val titles = listOf("第一章 (1/3)", "第一章 (2/3)", "第一章（第3页）")
        server.dispatcher = routing { request ->
            val number = request.requestUrl!!.encodedPath.substringAfterLast('/').toInt()
            val next = if (number < 3) "<a class=next href='/page/${number + 1}'>下一页</a>" else ""
            html("<h1>${titles[number - 1]}</h1><div id=content>正文$number</div>$next")
        }
        val result = runBlocking { engine().chapter(paginatedChapterRule(server), "source-1",
            server.url("/page/1").toString(), allowCleartext = true) }

        val page = (result as SourceResult.Success).value
        assertThat(page.title).isEqualTo("第一章 (1/3)")
        assertThat(page.content).isEqualTo("正文1\n\n正文2\n\n正文3")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun uniqueUnnumberedChapterTitleCanContinueAcrossPages() = withServer { server ->
        server.enqueue(html("<h1>序章</h1><div id=content>首段</div><a class=next href='/page/2'>下一页</a>"))
        server.enqueue(html("<h1>序章 (2/2)</h1><div id=content>尾段</div>"))
        val result = runBlocking { engine().chapter(paginatedChapterRule(server), "source-1",
            server.url("/page/1").toString(), allowCleartext = true) }

        assertThat((result as SourceResult.Success).value.content).isEqualTo("首段\n\n尾段")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun genericFirstTitleCannotProveUnknownContinuation() = withServer { server ->
        server.enqueue(html("<h1>正文</h1><div id=content>首段</div><a class=next href='/page/2'>下一页</a>"))
        server.enqueue(html("<h1>第一章</h1><div id=content>尾段</div>"))
        val result = runBlocking { engine().chapter(paginatedChapterRule(server), "source-1",
            server.url("/page/1").toString(), allowCleartext = true) }

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.Parse("chapter.boundary", "a.next")))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun genericLaterTitleCannotProveUnknownContinuation() = withServer { server ->
        server.enqueue(html("<h1>第一章</h1><div id=content>首段</div><a class=next href='/page/2'>下一页</a>"))
        server.enqueue(html("<h1>章节</h1><div id=content>尾段</div>"))
        val result = runBlocking { engine().chapter(paginatedChapterRule(server), "source-1",
            server.url("/page/1").toString(), allowCleartext = true) }

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.Parse("chapter.boundary", "a.next")))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun blankLaterTitlePreservesTheOriginalParserFailure() = withServer { server ->
        server.enqueue(html("<h1>第一章</h1><div id=content>首段</div><a class=next href='/page/2'>下一页</a>"))
        server.enqueue(html("<h1> </h1><div id=content>尾段</div>"))
        val result = runBlocking { engine().chapter(paginatedChapterRule(server), "source-1",
            server.url("/page/1").toString(), allowCleartext = true) }

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.Parse("chapter.title", "h1")))
    }

    @Test fun redirectToKnownOtherCatalogChapterDoesNotAppendItsBody() = withServer { server ->
        server.dispatcher = routing { request -> when (request.requestUrl!!.encodedPath) {
            "/chapter/1" -> html("<h1>第一章</h1><div id=content>首段</div><a class=next href='/bounce'>下一页</a>")
            "/bounce" -> MockResponse().setResponseCode(302).addHeader("Location", "/chapter/2")
            "/chapter/2" -> html("<h1>第一章</h1><div id=content>另一章正文</div>")
            else -> MockResponse().setResponseCode(404)
        } }
        val result = runBlocking { engine().chapter(paginatedChapterRule(server), "source-1",
            server.url("/chapter/1").toString(), allowCleartext = true,
            context = ChapterRequestContext(setOf(server.url("/chapter/2").toString()))) }

        assertThat((result as SourceResult.Success).value.content).isEqualTo("首段")
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test fun initialRedirectToOtherCatalogChapterIsABoundaryFailure() = withServer { server ->
        server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", "/chapter/2"))
        server.enqueue(html("<h1>第二章</h1><div id=content>另一章正文</div>"))
        val result = runBlocking { engine().chapter(paginatedChapterRule(server), "source-1",
            server.url("/chapter/1").toString(), allowCleartext = true,
            context = ChapterRequestContext(setOf(server.url("/chapter/2").toString()))) }

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.Parse("chapter.boundary", "a.next")))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun queryChapterIdsStayDistinctWhileCatalogFragmentsAreIgnored() = withServer { server ->
        server.enqueue(html("<h1>第一章</h1><div id=content>首段</div><a class=next href='/read?id=1&amp;page=2'>下一页</a>"))
        server.enqueue(html("<h1>第一章（第2页）</h1><div id=content>尾段</div><a class=next href='/read?id=2#body'>下一页</a>"))
        server.enqueue(html("<h1>第一章</h1><div id=content>另一章正文</div>"))
        val result = runBlocking { engine().chapter(paginatedChapterRule(server), "source-1",
            server.url("/read?id=1").toString(), allowCleartext = true,
            context = ChapterRequestContext(setOf(server.url("/read?id=1#catalog").toString(),
                server.url("/read?id=2#catalog").toString()))) }

        assertThat((result as SourceResult.Success).value.content).isEqualTo("首段\n\n尾段")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun chapterPoliciesRejectInvalidSourceDisabledRuleAndCleartextBeforeNetwork() = withServer { server ->
        val rule = paginatedChapterRule(server)
        val engine = engine()
        val url = server.url("/chapter/1").toString()
        val results = runBlocking { listOf(
            engine.chapter(rule, "../source", url, allowCleartext = true),
            engine.chapter(rule.copy(disabled = true), "source-1", url, allowCleartext = true),
            engine.chapter(rule, "source-1", url),
        ) }

        results.forEach { assertThat((it as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java) }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test fun chapterParsingCancellationIsRethrown() = withServer { server ->
        server.enqueue(html("<h1>第一章</h1><div id=content>正文</div>"))
        val scripts = object : ScriptExecutor {
            override suspend fun transform(input: String, script: String): SourceResult<String> =
                throw CancellationException("parse cancelled")
        }
        val rule = paginatedChapterRule(server).let { it.copy(chapter = it.chapter!!.copy(title = "h1@js:cancel")) }

        val cancelled = assertThrows(CancellationException::class.java) { runBlocking {
            engine(scripts).chapter(rule, "source-1", server.url("/chapter/1").toString(), allowCleartext = true)
        } }
        assertThat(cancelled.message).isEqualTo("parse cancelled")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun nextChapterIsNotAppendedAsAnotherPage() = withServer { server ->
        server.dispatcher = routing { request ->
            val number = request.requestUrl!!.encodedPath.substringAfterLast('/').toInt()
            val next = if (number < 3) "<a class=next href='/chapter/${number + 1}'>下一页</a>" else ""
            html("<h1>第${number}章</h1><div id=content><p>正文$number</p></div>$next")
        }
        val rule = rule(server).copy(chapter = ChapterRuleDto(
            title = "h1", content = "#content", pagination = true, nextPage = "a.next",
        ))

        val result = runBlocking { withTimeout(10_000) {
            engine().chapter(rule, "source-1", server.url("/chapter/1").toString(), allowCleartext = true)
        } }

        assertThat(result).isInstanceOf(SourceResult.Success::class.java)
        val page = (result as SourceResult.Success).value
        assertThat(page.title).isEqualTo("第1章")
        assertThat(page.content).isEqualTo("正文1")
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun detailHttpFlowCarriesPartialIdentityButLegacyBookKeepsOriginalScriptFailure() = withServer { server ->
        val page = "<h1>另一部作品</h1><div id=author>叶子</div><div id=intro>简介</div>"
        server.enqueue(html(page))
        server.enqueue(html(page))
        val rule = rule(server).copy(book = BookRuleDto(bookName = "h1", author = "#author",
            intro = "#intro@js:r=missingFunction(r)"))
        val engine = engine(QuickJsScriptExecutor())
        val result = runBlocking { engine.bookDetails(rule, "source-1", hit(server), "book-7", allowCleartext = true) }

        assertThat(result).isInstanceOf(SourceResult.Success::class.java)
        val details = (result as SourceResult.Success).value
        assertThat(details.identity).isEqualTo(BookIdentityEvidence("另一部作品", "叶子"))
        assertThat(details.warning).isEqualTo(SourceFailure.UnsafeRule("script execution failed"))
        val legacy = runBlocking { engine.book(rule, "source-1", hit(server), "book-7", allowCleartext = true) }
        assertThat(legacy).isEqualTo(SourceResult.Failure(details.warning!!))
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun freshDetailsReturnPageIdentityWithoutBorrowingSearchAuthor() = withServer { server ->
        server.enqueue(html("<h1>页面书名</h1><div id=intro>页面简介</div>"))
        val result = runBlocking { engine().bookDetails(rule(server).copy(
            book = BookRuleDto(bookName = "h1", intro = "#intro")), "source-1", hit(server),
            "book-7", allowCleartext = true) }

        assertThat(result).isInstanceOf(SourceResult.Success::class.java)
        val details = (result as SourceResult.Success).value
        assertThat(details.identity).isEqualTo(BookIdentityEvidence(title = "页面书名"))
        assertThat(details.book.introduction).isEqualTo("页面简介")
        assertThat(details.book.bookId).isEqualTo("book-7")
    }

    @Test fun searchRecognizesHtmlVerificationPageBeforeSelectorParsing() = withServer { server ->
        server.enqueue(html("<html><head><title>Just a moment...</title></head>" +
            "<body><div id='cf-challenge'>Checking your browser</div></body></html>"))

        val result = runBlocking { engine().search(rule(server), "source-1", "星河", allowCleartext = true) }

        assertThat((result as SourceResult.Failure).error).isEqualTo(SourceFailure.VerificationRequired)
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test fun fourEndpointFlowSearchesEnrichesListsAndReads() = withServer { server ->
        server.dispatcher = routing { request ->
            when (request.requestUrl?.encodedPath) {
                "/search" -> html("<div class=result><a class=name href='/book/7'>星河旅人</a><span class=author>叶子</span></div>")
                "/book/7" -> html("<meta property='og:image' content='/cover/7.jpg'><h1 id=title>星河旅人</h1><div id=intro>穿越群星的故事</div>")
                "/toc/7" -> html("<div id=chapters><a href='/chapter/1'>第一章</a><a href='/chapter/1'>重复章节</a></div>")
                "/chapter/1" -> html("<h1>第一章</h1><div id=content><p>第一段【过滤】</p><div class=ad>广告</div><p>第二段</p></div>")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val rule = rule(server).copy(
            book = BookRuleDto(url = ".*/book/(\\d+)", bookName = "#title", intro = "#intro", coverUrl = "meta[property=og:image]"),
            toc = TocRuleDto(url = server.url("/toc/%s").toString(), item = "#chapters a"),
            chapter = ChapterRuleDto(title = "h1", content = "#content", filterTag = ".ad", filterTxt = "【过滤】"),
        )
        val engine = engine()

        runBlocking { withTimeout(10_000) {
            val hit = (engine.search(rule, "source-1", "星河", allowCleartext = true) as SourceResult.Success).value.single()
            assertThat(hit.detailUrl).isEqualTo(server.url("/book/7").toString())
            val book = (engine.book(rule, "source-1", hit, "book-7", allowCleartext = true) as SourceResult.Success).value
            assertThat(book.bookId).isEqualTo("book-7")
            assertThat(book.introduction).isEqualTo("穿越群星的故事")
            assertThat(book.coverUrl).isEqualTo(server.url("/cover/7.jpg").toString())
            val chapters = (engine.toc(rule, "source-1", hit, "book-7", allowCleartext = true) as SourceResult.Success).value
            assertThat(chapters).hasSize(1)
            val chapter = chapters.single()
            assertThat(chapter.title).isEqualTo("第一章")
            val page = (engine.chapter(rule, "source-1", chapter.url!!, allowCleartext = true) as SourceResult.Success).value
            assertThat(page.title).isEqualTo("第一章")
            assertThat(page.content).isEqualTo("第一段\n第二段")
            assertThat(page.content).doesNotContain("广告")
            assertThat(page.content).doesNotContain("【过滤】")
        } }
        assertThat(server.requestCount).isEqualTo(4)
    }

    @Test fun searchPaginationStopsAtTenPages() = withServer { server ->
        server.dispatcher = routing { request ->
            val number = request.requestUrl!!.queryParameter("p")!!.toInt()
            html("<div class=result><a class=name href='/book/$number'>书$number</a></div><a class=next href='/search?q=x&p=${number + 1}'>下一页</a>")
        }
        val rule = rule(server).copy(search = rule(server).search!!.copy(
            url = server.url("/search?q=%s&p=1").toString(),
            pagination = true,
            nextPage = "a.next",
        ))

        val result = runBlocking { withTimeout(10_000) { engine().search(rule, "source-1", "x", allowCleartext = true) } }

        assertThat((result as SourceResult.Success).value).hasSize(10)
        assertThat(server.requestCount).isEqualTo(10)
    }

    @Test fun searchDoesNotParseAnAlreadyParsedRedirectDestination() = withServer { server ->
        var searchResponses = 0
        server.dispatcher = routing { request -> when (request.requestUrl?.encodedPath) {
            "/search" -> {
                searchResponses++
                html("<div class=result><a class=name href='/book/$searchResponses'>书$searchResponses</a></div><a class=next href='/bounce'>下一页</a>")
            }
            "/bounce" -> MockResponse().setResponseCode(302).addHeader("Location", "/search?q=x")
            else -> MockResponse().setResponseCode(404)
        } }
        val rule = rule(server).copy(search = rule(server).search!!.copy(pagination = true, nextPage = "a.next"))

        val result = runBlocking { withTimeout(10_000) { engine().search(rule, "source-1", "x", allowCleartext = true) } }

        assertThat((result as SourceResult.Success).value.map { it.bookName }).containsExactly("书1")
        assertThat(searchResponses).isEqualTo(2)
    }

    @Test fun tocDoesNotParseAnAlreadyParsedRedirectDestination() = withServer { server ->
        var tocResponses = 0
        server.dispatcher = routing { request -> when (request.requestUrl?.encodedPath) {
            "/toc" -> {
                tocResponses++
                html("<div id=chapters><a href='/chapter/$tocResponses'>章$tocResponses</a></div><a class=next href='/bounce'>下一页</a>")
            }
            "/bounce" -> MockResponse().setResponseCode(302).addHeader("Location", "/toc")
            else -> MockResponse().setResponseCode(404)
        } }
        val rule = rule(server).copy(toc = TocRuleDto(
            url = server.url("/toc").toString(), item = "#chapters a", pagination = true, nextPage = "a.next",
        ))

        val result = runBlocking { withTimeout(10_000) { engine().toc(rule, "source-1", hit(server), "book-7", allowCleartext = true) } }

        assertThat((result as SourceResult.Success).value.map { it.title }).containsExactly("章1")
        assertThat(tocResponses).isEqualTo(2)
    }

    @Test fun chapterDoesNotAppendAnAlreadyParsedRedirectDestination() = withServer { server ->
        var chapterResponses = 0
        server.dispatcher = routing { request -> when (request.requestUrl?.encodedPath) {
            "/chapter" -> {
                chapterResponses++
                html("<h1>章节</h1><div id=content><p>正文$chapterResponses</p></div><a class=next href='/bounce'>下一页</a>")
            }
            "/bounce" -> MockResponse().setResponseCode(302).addHeader("Location", "/chapter")
            else -> MockResponse().setResponseCode(404)
        } }
        val rule = rule(server).copy(chapter = ChapterRuleDto(
            title = "h1", content = "#content", pagination = true, nextPage = "a.next",
        ))

        val result = runBlocking { withTimeout(10_000) {
            engine().chapter(rule, "source-1", server.url("/chapter").toString(), allowCleartext = true)
        } }

        assertThat((result as SourceResult.Success).value.content).isEqualTo("正文1")
        assertThat(chapterResponses).isEqualTo(2)
    }

    @Test fun searchRejectsMoreThanTenThousandDistinctHits() = withServer { server ->
        val rows = buildString {
            repeat(10_000) { index -> append("<div class=result><a class=name href='/b/$index'>书</a></div>") }
            append("<div class=result><a class=name href='/b/0'>重复</a></div>")
            append("<div class=result><a class=name href='/b/10000'>超额</a></div>")
        }
        server.enqueue(html(rows))

        val result = runBlocking { withTimeout(60_000) {
            engine().search(rule(server), "source-1", "x", allowCleartext = true)
        } }

        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
    }

    @Test fun searchAllowsTenThousandDistinctHitsAndARepeatedUrl() = withServer { server ->
        val rows = buildString {
            repeat(10_000) { index -> append("<div class=result><a class=name href='/b/$index'>书</a></div>") }
            append("<div class=result><a class=name href='/b/0'>重复</a></div>")
        }
        server.enqueue(html(rows))

        val result = runBlocking { withTimeout(60_000) {
            engine().search(rule(server), "source-1", "x", allowCleartext = true)
        } }

        assertThat((result as SourceResult.Success).value).hasSize(10_000)
    }

    @Test fun searchRejectsMoreThanEightMiBOfSavedFieldsAcrossPages() = withServer { server ->
        val longName = "中".repeat(580_000)
        val scripts = object : ScriptExecutor {
            override suspend fun transform(input: String, script: String): SourceResult<String> = SourceResult.Success(longName)
        }
        server.dispatcher = routing { request ->
            val page = request.requestUrl!!.queryParameter("p")!!.toInt()
            val next = if (page < 5) "<a class=next href='/search?q=x&p=${page + 1}'>下一页</a>" else ""
            html("<div class=result><a class=name href='/book/$page'>书</a></div>$next")
        }
        val search = rule(server).search!!.copy(
            url = server.url("/search?q=%s&p=1").toString(), bookName = ".name@js:inflate",
            pagination = true, nextPage = "a.next",
        )

        val result = runBlocking { withTimeout(60_000) {
            engine(scripts).search(rule(server).copy(search = search), "source-1", "x", allowCleartext = true)
        } }

        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
    }

    @Test fun searchAllowsExactlyEightMiBOfSavedFieldsPlusCrossPageDuplicate() = withServer { server ->
        val urlBytes = server.url("/book/1").toString().toByteArray(Charsets.UTF_8).size
        val longName = "a".repeat(1_048_576 - urlBytes)
        val scripts = object : ScriptExecutor {
            override suspend fun transform(input: String, script: String): SourceResult<String> = SourceResult.Success(longName)
        }
        server.dispatcher = routing { request ->
            val page = request.requestUrl!!.queryParameter("p")!!.toInt()
            val book = if (page == 9) 1 else page
            val next = if (page < 9) "<a class=next href='/search?q=x&p=${page + 1}'>下一页</a>" else ""
            html("<div class=result><a class=name href='/book/$book'>书</a></div>$next")
        }
        val search = rule(server).search!!.copy(
            url = server.url("/search?q=%s&p=1").toString(), bookName = ".name@js:inflate",
            pagination = true, nextPage = "a.next",
        )

        val result = runBlocking { withTimeout(60_000) {
            engine(scripts).search(rule(server).copy(search = search), "source-1", "x", allowCleartext = true)
        } }

        assertThat((result as SourceResult.Success).value).hasSize(8)
        assertThat(server.requestCount).isEqualTo(9)
    }

    @Test fun tocRejectsMoreThanTwentyThousandDistinctChapters() = withServer { server ->
        val links = buildString {
            append("<div id=chapters>")
            repeat(20_000) { index -> append("<a href='/c/$index'>章</a>") }
            append("<a href='/c/0'>重复</a><a href='/c/20000'>超额</a></div>")
        }
        server.enqueue(html(links))

        val result = runBlocking { withTimeout(60_000) {
            engine().toc(rule(server), "source-1", hit(server), "book-7", allowCleartext = true)
        } }

        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
    }

    @Test fun tocAllowsTwentyThousandDistinctChaptersAndARepeatedUrl() = withServer { server ->
        val links = buildString {
            append("<div id=chapters>")
            repeat(20_000) { index -> append("<a href='/c/$index'>章</a>") }
            append("<a href='/c/0'>重复</a></div>")
        }
        server.enqueue(html(links))

        val result = runBlocking { withTimeout(60_000) {
            engine().toc(rule(server), "source-1", hit(server), "book-7", allowCleartext = true)
        } }

        assertThat((result as SourceResult.Success).value).hasSize(20_000)
    }

    @Test fun tocRejectsMoreThanSixteenMiBOfSavedTitlesAndUrlsAcrossPages() = withServer { server ->
        val longTitle = "中".repeat(580_000)
        server.dispatcher = routing { request ->
            val page = request.requestUrl!!.queryParameter("p")!!.toInt()
            val next = if (page < 10) "<a class=next href='/toc?p=${page + 1}'>下一页</a>" else ""
            html("<div id=chapters><a href='/chapter/$page'>$longTitle</a></div>$next")
        }
        val toc = TocRuleDto(
            url = server.url("/toc?p=1").toString(), item = "#chapters a",
            pagination = true, nextPage = "a.next",
        )

        val result = runBlocking { withTimeout(120_000) {
            engine().toc(rule(server).copy(toc = toc), "source-1", hit(server), "book-7", allowCleartext = true)
        } }

        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
    }

    @Test fun tocAllowsExactlySixteenMiBOfSavedFieldsPlusCrossPageDuplicate() = withServer { server ->
        server.dispatcher = routing { request ->
            val page = request.requestUrl!!.queryParameter("p")!!.toInt()
            val chapter = if (page == 17) 1 else page
            val urlBytes = server.url("/chapter/$chapter").toString().toByteArray(Charsets.UTF_8).size
            val title = "a".repeat(1_048_576 - urlBytes)
            val next = if (page < 17) "<a class=next href='/toc?p=${page + 1}'>下一页</a>" else ""
            html("<div id=chapters><a href='/chapter/$chapter'>$title</a></div>$next")
        }
        val toc = TocRuleDto(
            url = server.url("/toc?p=1").toString(), item = "#chapters a",
            pagination = true, nextPage = "a.next",
        )

        val result = runBlocking { withTimeout(120_000) {
            engine().toc(rule(server).copy(toc = toc), "source-1", hit(server), "book-7", allowCleartext = true)
        } }

        assertThat((result as SourceResult.Success).value).hasSize(16)
        assertThat(server.requestCount).isEqualTo(17)
    }

    @Test fun chapterRejectsAggregatedUtf8ContentOverEightMiB() = withServer { server ->
        val paragraph = "中".repeat(500_000)
        server.dispatcher = routing { request ->
            val number = request.requestUrl!!.encodedPath.substringAfterLast('/').toInt()
            val next = if (number < 6) "<a class=next href='/chapter/${number + 1}'>下一页</a>" else ""
            html("<h1>第一章</h1><div id=content><p>$paragraph</p></div>$next")
        }
        val rule = rule(server).copy(chapter = ChapterRuleDto(
            title = "h1", content = "#content", pagination = true, nextPage = "a.next",
        ))

        val result = runBlocking { withTimeout(60_000) {
            engine().chapter(rule, "source-1", server.url("/chapter/1").toString(), allowCleartext = true)
        } }

        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
    }

    @Test fun chapterAllowsExactlyEightMiBUtf8IncludingPageSeparators() = withServer { server ->
        val paragraph = "a".repeat(1_677_720)
        server.dispatcher = routing { request ->
            val number = request.requestUrl!!.encodedPath.substringAfterLast('/').toInt()
            val next = if (number < 5) "<a class=next href='/chapter/${number + 1}'>下一页</a>" else ""
            html("<h1>第一章</h1><div id=content><p>$paragraph</p></div>$next")
        }
        val rule = rule(server).copy(chapter = ChapterRuleDto(
            title = "h1", content = "#content", pagination = true, nextPage = "a.next",
        ))

        val result = runBlocking { withTimeout(60_000) {
            engine().chapter(rule, "source-1", server.url("/chapter/1").toString(), allowCleartext = true)
        } }

        assertThat((result as SourceResult.Success).value.content.toByteArray(Charsets.UTF_8)).hasLength(8 * 1024 * 1024)
    }

    @Test fun tocPaginationDeduplicatesThenGloballyReversesAndRenumbers() = withServer { server ->
        server.dispatcher = routing { request ->
            when (request.requestUrl?.queryParameter("p")) {
                "1" -> html("<div id=chapters><a href='/chapter/3'>第三章</a><a href='/chapter/2'>第二章</a></div><a class=next href='/toc?p=2'>下一页</a>")
                "2" -> html("<div id=chapters><a href='/chapter/2'>第二章重发</a><a href='/chapter/1'>第一章</a></div><a class=next href='/toc?p=1'>循环</a>")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val hit = hit(server)
        val rule = rule(server).copy(toc = TocRuleDto(
            url = server.url("/toc?p=1").toString(), item = "#chapters a",
            nextPage = "a.next", pagination = true, isDesc = true,
        ))

        val result = runBlocking { withTimeout(10_000) { engine().toc(rule, "source-1", hit, "book-7", allowCleartext = true) } }

        val chapters = (result as SourceResult.Success).value
        assertThat(chapters.map { it.title }).containsExactly("第一章", "第二章", "第三章").inOrder()
        assertThat(chapters.map { it.chapterIndex }).containsExactly(0, 1, 2).inOrder()
        assertThat(server.requestCount).isEqualTo(2)
    }

    @Test fun tocPaginationStopsAtOneHundredPages() = withServer { server ->
        server.dispatcher = routing { request ->
            val number = request.requestUrl!!.queryParameter("p")!!.toInt()
            html("<div id=chapters><a href='/chapter/$number'>章$number</a></div><a class=next href='/toc?p=${number + 1}'>下一页</a>")
        }
        val rule = rule(server).copy(toc = TocRuleDto(
            url = server.url("/toc?p=1").toString(), item = "#chapters a",
            nextPage = "a.next", pagination = true,
        ))

        val result = runBlocking { withTimeout(20_000) { engine().toc(rule, "source-1", hit(server), "book-7", allowCleartext = true) } }

        assertThat((result as SourceResult.Success).value).hasSize(100)
        assertThat(server.requestCount).isEqualTo(100)
    }

    @Test fun chapterPaginationStopsAtTwentyPages() = withServer { server ->
        server.dispatcher = routing { request ->
            val number = request.requestUrl!!.encodedPath.substringAfterLast('/').toInt()
            val title = if (number == 1) "第1章" else "第1章 ($number/20)"
            html("<h1>$title</h1><div id=content><p>页$number</p></div><a class=next href='/chapter/${number + 1}'>下一页</a>")
        }
        val rule = rule(server).copy(chapter = ChapterRuleDto(
            title = "h1", content = "#content", nextPage = "a.next", pagination = true,
        ))

        val result = runBlocking { withTimeout(10_000) {
            engine().chapter(rule, "source-1", server.url("/chapter/1").toString(), allowCleartext = true)
        } }

        val page = (result as SourceResult.Success).value
        assertThat(page.title).isEqualTo("第1章")
        assertThat(page.content).contains("页20")
        assertThat(page.content).doesNotContain("页21")
        assertThat(server.requestCount).isEqualTo(20)
    }

    @Test fun declaredGbkCharsetDecodesChineseAndUnsupportedCharsetIsTyped() = withServer { server ->
        val body = "<div class=result><a class=name href='/book/7'>中文书</a></div>"
        server.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=gbk")
            .setBody(Buffer().writeString(body, Charset.forName("GBK"))))
        server.enqueue(MockResponse().addHeader("Content-Type", "text/html; charset=x-not-real")
            .setBody(body))
        val rule = rule(server)
        val engine = engine()

        val decoded = runBlocking { engine.search(rule, "source-1", "x", allowCleartext = true) }
        val unsupported = runBlocking { engine.search(rule, "source-1", "y", allowCleartext = true) }

        assertThat((decoded as SourceResult.Success).value.single().bookName).isEqualTo("中文书")
        assertThat(unsupported).isEqualTo(SourceResult.Failure(SourceFailure.Decode("x-not-real")))
    }

    @Test fun remoteBookIdIsPathEncodedAndLocalIdsAreValidatedBeforeNetwork() = withServer { server ->
        server.dispatcher = routing { request ->
            when (request.requestUrl?.encodedPath) {
                "/toc/part%2Ftwo" -> html("<div id=chapters><a href='/chapter/1'>第一章</a></div>")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val detailUrl = server.url("/book/part/two").toString()
        val hit = hit(server).copy(detailUrl = detailUrl)
        val rule = rule(server).copy(
            book = BookRuleDto(url = ".*/book/(.*)"),
            toc = TocRuleDto(url = server.url("/toc/%s").toString(), item = "#chapters a"),
        )
        val engine = engine()

        val bad = runBlocking { engine.toc(rule, "source-1", hit, "../escape", allowCleartext = true) }
        assertThat((bad as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
        assertThat(server.requestCount).isEqualTo(0)
        val good = runBlocking { engine.toc(rule, "source-1", hit, "book-7", allowCleartext = true) }
        assertThat((good as SourceResult.Success).value).hasSize(1)
        assertThat(server.takeRequest(2, TimeUnit.SECONDS)!!.requestUrl!!.encodedPath).isEqualTo("/toc/part%2Ftwo")
    }

    @Test fun cleartextRemainsOptInAndSourceMismatchFailsBeforeNetwork() = withServer { server ->
        val rule = rule(server)
        val engine = engine()
        val denied = runBlocking { engine.search(rule, "source-1", "x") }
        val mismatch = runBlocking { engine.book(rule, "other-source", hit(server), "book-7", allowCleartext = true) }

        assertThat((denied as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
        assertThat((mismatch as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
        assertThat(server.requestCount).isEqualTo(0)
    }

    private fun engine(scripts: ScriptExecutor = object : ScriptExecutor {
        override suspend fun transform(input: String, script: String): SourceResult<String> = SourceResult.Success(input)
    }): SourceEngine {
        val selector = HtmlSelector()
        return SourceEngine(
            SearchRequestFactory(), SourceHttpClient(),
            SearchPageParser(selector, scripts), BookPageParser(selector, scripts),
            TocPageParser(selector, scripts), ChapterPageParser(selector, scripts),
            selector, scripts,
        )
    }

    private fun rule(server: MockWebServer) = RuleDto(
        url = server.url("/").toString(), name = "fixture",
        search = SearchRuleDto(
            url = server.url("/search?q=%s").toString(), method = "GET",
            result = ".result", bookName = ".name", author = ".author",
        ),
        book = BookRuleDto(),
        toc = TocRuleDto(item = "#chapters a"),
        chapter = ChapterRuleDto(title = "h1", content = "#content"),
    )

    private fun paginatedChapterRule(server: MockWebServer) = rule(server).copy(chapter = ChapterRuleDto(
        title = "h1", content = "#content", nextPage = "a.next", pagination = true,
    ))

    private fun hit(server: MockWebServer) = SearchHit(
        sourceId = "source-1", bookName = "星河旅人", author = "叶子",
        detailUrl = server.url("/book/7").toString(),
    )

    private fun html(body: String) = MockResponse().addHeader("Content-Type", "text/html; charset=utf-8").setBody(body)

    private fun routing(block: (RecordedRequest) -> MockResponse) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse = block(request)
    }

    private inline fun withServer(block: (MockWebServer) -> Unit) {
        MockWebServer().use { server -> server.start(); block(server) }
    }
}
