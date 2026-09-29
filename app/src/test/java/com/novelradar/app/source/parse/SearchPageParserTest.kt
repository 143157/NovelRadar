package com.novelradar.app.source.parse

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.rule.SearchRuleDto
import com.novelradar.app.source.script.ScriptExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Test

class SearchPageParserTest {
    private val html = javaClass.getResource("/html/search.html")!!.readText()
    private val baseUrl = "https://books.example.test/search/page.html"
    private val script = PrefixRemovingScript()
    private val parser = SearchPageParser(HtmlSelector(), script)

    @Test fun parsesOrderedSearchHitsWithAbsoluteDetailUrls() = runBlocking {
        val result = parser.parse(html, baseUrl, "source-1", rule()) as SourceResult.Success

        assertThat(result.value.map { it.bookName }).containsExactly("星河旅人", "雪夜书").inOrder()
        assertThat(result.value.map { it.detailUrl }).containsExactly(
            "https://books.example.test/book/7", "https://books.example.test/book/8",
        ).inOrder()
        assertThat(result.value.map { it.author }).containsExactly("叶子", "青山").inOrder()
        assertThat(result.value.map { it.sourceId }).containsExactly("source-1", "source-1")
        assertThat(result.value.first().latestChapter).isEqualTo("第十九章")
        assertThat(result.value.first().updatedAtText).isEqualTo("今日")
    }

    @Test fun xpathResultAndScopedFieldSelectorsWork() = runBlocking {
        val result = parser.parse(
            html, baseUrl, "source-1",
            rule().copy(result = "//div[@class='result']", bookName = "//a[@class='name']"),
        ) as SourceResult.Success

        assertThat(result.value.map { it.bookName }).containsExactly("星河旅人", "雪夜书").inOrder()
    }

    @Test fun emptyResultIsSuccessfulEmptyList() = runBlocking {
        val result = parser.parse(html, baseUrl, "source-1", rule().copy(result = ".absent"))

        assertThat(result).isEqualTo(SourceResult.Success(emptyList<Any>()))
    }

    @Test fun missingRequiredBookNameReturnsParseFailure() = runBlocking {
        val result = parser.parse(html, baseUrl, "source-1", rule().copy(bookName = ".absent"))

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.Parse("search.bookName", ".absent")))
    }

    @Test fun propagatesScriptFailureWithoutDroppingTheError() = runBlocking {
        val failing = SearchPageParser(HtmlSelector(), object : ScriptExecutor {
            override suspend fun transform(input: String, script: String): SourceResult<String> =
                SourceResult.Failure(SourceFailure.UnsafeRule("bad script"))
        })

        val result = failing.parse(html, baseUrl, "source-1", rule())

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.UnsafeRule("bad script")))
    }

    @Test fun stopsMaterializingAfterTenThousandHitsWithoutScanningLaterRows() = runBlocking {
        val page = buildString {
            repeat(10_002) { index -> append("<div class=result><a class=name href='/b/$index'>书</a></div>") }
        }
        var transformed = 0
        val bounded = SearchPageParser(HtmlSelector(), object : ScriptExecutor {
            override suspend fun transform(input: String, script: String): SourceResult<String> {
                transformed++
                return SourceResult.Success(input)
            }
        })

        val result = bounded.parse(page, baseUrl, "source-1", rule().copy(bookName = ".name@js:r"))

        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
        assertThat(transformed).isEqualTo(10_001)
    }

    @Test fun stopsMaterializingWhenScriptedFieldsExceedEightMiB() = runBlocking {
        val page = buildString {
            repeat(4) { index -> append("<div class=result><a class=name href='/b/$index'>书</a></div>") }
        }
        val longName = "中".repeat(1_300_000)
        var transformed = 0
        val bounded = SearchPageParser(HtmlSelector(), object : ScriptExecutor {
            override suspend fun transform(input: String, script: String): SourceResult<String> {
                transformed++
                return SourceResult.Success(longName)
            }
        })

        val result = bounded.parse(page, baseUrl, "source-1", rule().copy(bookName = ".name@js:inflate"))

        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
        assertThat(transformed).isEqualTo(3)
    }

    @Test fun rejectsDetailUrlLongerThanRequestLimit() = runBlocking {
        val page = "<div class=result><a class=name href='/${"x".repeat(4_096)}'>书</a></div>"

        val result = parser.parse(page, baseUrl, "source-1", rule())

        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
    }

    @Test fun acceptsDetailUrlAtExactlyRequestLimit() = runBlocking {
        val href = "/" + "x".repeat(4_096 - "https://books.example.test/".length)
        val page = "<div class=result><a class=name href='$href'>书</a></div>"

        val result = parser.parse(page, baseUrl, "source-1", rule())

        assertThat((result as SourceResult.Success).value.single().detailUrl).hasLength(4_096)
    }

    private fun rule() = SearchRuleDto(
        result = ".result",
        bookName = ".name",
        author = ".author@js:r=r.replace('作者：', '')",
        latestChapter = ".latest",
        lastUpdateTime = "time",
    )

    private class PrefixRemovingScript : ScriptExecutor {
        override suspend fun transform(input: String, script: String): SourceResult<String> {
            assertThat(script).isEqualTo("r=r.replace('作者：', '')")
            return SourceResult.Success(input.removePrefix("作者："))
        }
    }
}
