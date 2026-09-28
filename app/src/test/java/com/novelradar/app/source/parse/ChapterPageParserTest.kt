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
                ChapterPage("新标题", "新正文\n第二段", "https://books.example.test/2.html")
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
}
