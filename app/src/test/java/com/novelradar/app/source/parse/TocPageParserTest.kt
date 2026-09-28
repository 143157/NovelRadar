package com.novelradar.app.source.parse

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.rule.TocRuleDto
import com.novelradar.app.source.script.ScriptExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Test

class TocPageParserTest {
    private val html = javaClass.getResource("/html/toc.html")!!.readText()
    private val baseUrl = "https://books.example.test/book/7/index.html"
    private val parser = TocPageParser(HtmlSelector(), object : ScriptExecutor {
        override suspend fun transform(input: String, script: String): SourceResult<String> =
            SourceResult.Success(input)
    })

    @Test fun preservesPageOrderAndDeduplicatesByResolvedUrl() = runBlocking {
        val result = parser.parse(html, baseUrl, "source-1", "book-7", TocRuleDto(item = "#chapters a"))
            as SourceResult.Success

        assertThat(result.value.map { it.title }).containsExactly(
            "第一章 出发", "第二章 归来", "第三章 新路",
        ).inOrder()
        assertThat(result.value.map { it.url }).containsExactly(
            "https://books.example.test/book/chapters/1.html",
            "https://books.example.test/chapters/2.html",
            "https://books.example.test/book/chapters/3.html",
        ).inOrder()
        assertThat(result.value.map { it.chapterIndex }).containsExactly(0, 1, 2).inOrder()
        assertThat(result.value.map { it.bookId }.toSet()).containsExactly("book-7")
        assertThat(result.value.map { it.sourceId }.toSet()).containsExactly("source-1")
        assertThat(result.value.map { it.chapterId }.toSet()).hasSize(3)
        result.value.forEach { chapter ->
            assertThat(chapter.chapterId).matches("[0-9a-f]{64}")
        }
    }

    @Test fun xpathAndIsDescDoNotReverseSinglePageOrder() = runBlocking {
        val result = parser.parse(html, baseUrl, "source-1", "book-7", TocRuleDto(
            item = "//div[@id='chapters']/a", isDesc = true,
        )) as SourceResult.Success

        assertThat(result.value.map { it.title }).containsExactly(
            "第一章 出发", "第二章 归来", "第三章 新路",
        ).inOrder()
    }

    @Test fun listScriptTransformsSelectedHtmlBeforeItemSelection() = runBlocking {
        val transforming = TocPageParser(HtmlSelector(), object : ScriptExecutor {
            override suspend fun transform(input: String, script: String): SourceResult<String> {
                assertThat(script).isEqualTo("r=r.replace('第一章', '序章')")
                return SourceResult.Success(input.replace("第一章", "序章"))
            }
        })

        val result = transforming.parse(html, baseUrl, "source-1", "book-7", TocRuleDto(
            list = "#chapters@js:r=r.replace('第一章', '序章')",
            item = "a",
        )) as SourceResult.Success

        assertThat(result.value.first().title).isEqualTo("序章 出发")
    }

    @Test fun itemScriptTransformsUrlBeforeResolution() = runBlocking {
        val transforming = TocPageParser(HtmlSelector(), object : ScriptExecutor {
            override suspend fun transform(input: String, script: String): SourceResult<String> {
                assertThat(script).isEqualTo("r=r.replace('chapters', 'special')")
                return SourceResult.Success(input.replace("chapters", "special"))
            }
        })

        val result = transforming.parse(html, baseUrl, "source-1", "book-7", TocRuleDto(
            item = "#chapters a@js:r=r.replace('chapters', 'special')",
        )) as SourceResult.Success

        assertThat(result.value.first().url).isEqualTo("https://books.example.test/book/special/1.html")
    }

    @Test fun missingRequiredItemReturnsParseFailure() = runBlocking {
        val result = parser.parse(html, baseUrl, "source-1", "book-7", TocRuleDto(item = ".absent"))

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.Parse("toc.item", ".absent")))
    }
}
