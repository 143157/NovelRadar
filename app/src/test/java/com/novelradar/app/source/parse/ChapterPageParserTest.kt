package com.novelradar.app.source.parse

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.rule.ChapterRuleDto
import com.novelradar.app.source.script.ScriptExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ChapterPageParserTest {
    private val fakeScripts = object : ScriptExecutor {
        override suspend fun transform(input: String, script: String): SourceResult<String> =
            SourceResult.Success(input.replace("旧", "新"))
    }
    private val parser = ChapterPageParser(HtmlSelector(), fakeScripts)
    private val page = """
        <html><body><h1 class="title">旧标题</h1>
        <div id="content"><p>旧正文</p><p>第二段</p><div class="ad">广告</div></div>
        <a class="next" href="../2.html">下一页</a></body></html>
    """.trimIndent()

    @Test fun nextPageLabelComesFromTheSameSelectedLinkAsItsUrl() = runBlocking {
        val html = "<h1>第一章</h1><div id=content>正文</div>" +
            "<a href='/wrong'>下一章</a><a class=next href='/page/2'><span>下一页</span></a>" +
            "<a class=next href='/chapter/2'>下一章</a>"

        val result = parser.parse(html, "https://books.example.test/chapter/1",
            ChapterRuleDto(title = "h1", content = "#content", nextPage = "a.next", pagination = true))

        val parsed = (result as SourceResult.Success).value
        assertThat(parsed.nextPageUrl).isEqualTo("https://books.example.test/page/2")
        assertThat(parsed.nextPageLabel).isEqualTo("下一页")
    }

    @Test fun parsesTitleCleanedContentAndAbsoluteNextPage() = runBlocking {
        val rule = ChapterRuleDto(
            title = ".title@js:r=r.replace('旧','新')",
            content = "#content@js:r=r.replace('旧','新')",
            filterTag = ".ad",
            nextPage = "a.next",
            pagination = true,
        )

        assertThat(parser.parse(page, "https://books.example.test/c/1.html", rule)).isEqualTo(
            SourceResult.Success(
                ChapterPage("新标题", "新正文\n第二段", "https://books.example.test/2.html", "下一页")
            )
        )
    }

    @Test fun missingRequiredContentIsParseFailure() = runBlocking {
        val result = parser.parse(
            "<h1 class=title>章节</h1>",
            "https://books.example.test/c/1.html",
            ChapterRuleDto(title = ".title", content = "#content"),
        )

        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.Parse::class.java)
    }

    @Test fun nextPageIsIgnoredWhenPaginationDisabled() = runBlocking {
        val result = parser.parse(
            page,
            "https://books.example.test/c/1.html",
            ChapterRuleDto(title = ".title", content = "#content", nextPage = "a.next"),
        )

        assertThat((result as SourceResult.Success).value.nextPageUrl).isNull()
    }

    @Test fun nextPageScriptTransformsRawHrefBeforeResolution() = runBlocking {
        val parser = ChapterPageParser(HtmlSelector(), object : ScriptExecutor {
            override suspend fun transform(input: String, script: String): SourceResult<String> {
                assertThat(input).isEqualTo("../2.html")
                assertThat(script).isEqualTo("r=r.replace('2.html','3.html')")
                return SourceResult.Success(input.replace("2.html", "3.html"))
            }
        })
        val rule = ChapterRuleDto(
            title = ".title", content = "#content",
            nextPage = "a.next@js:r=r.replace('2.html','3.html')", pagination = true,
        )

        val result = parser.parse(page, "https://books.example.test/c/1.html", rule)

        assertThat((result as SourceResult.Success).value.nextPageUrl)
            .isEqualTo("https://books.example.test/3.html")
    }

    @Test fun contentSelectorCombinesMultipleParagraphsInDocumentOrder() = runBlocking {
        val html = "<h1>第一章</h1><div id=content><p>首段</p><p>次段</p></div>"

        val result = parser.parse(
            html, "https://books.example.test/chapter/1", ChapterRuleDto(title = "h1", content = "#content p"),
        )

        assertThat((result as SourceResult.Success).value.content).isEqualTo("首段\n次段")
    }
}
