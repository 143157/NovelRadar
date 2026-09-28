package com.novelradar.app.source.parse

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SearchHit
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.rule.BookRuleDto
import com.novelradar.app.source.script.ScriptExecutor
import kotlinx.coroutines.runBlocking
import org.junit.Test

class BookPageParserTest {
    private val html = javaClass.getResource("/html/book.html")!!.readText()
    private val hit = SearchHit(
        sourceId = "source-1", bookName = "星河旅人", author = "叶子",
        detailUrl = "https://books.example.test/book/7",
    )
    private val parser = BookPageParser(HtmlSelector(), object : ScriptExecutor {
        override suspend fun transform(input: String, script: String): SourceResult<String> =
            SourceResult.Success(input.replace("·修订版", ""))
    })

    @Test fun parsesMetadataAndResolvesRelativeCoverUrl() = runBlocking {
        val rule = BookRuleDto(
            bookName = "meta[property=og:title]@js:r=r.replace('·修订版', '')",
            author = "//div[@class='author']",
            intro = "#intro",
            coverUrl = "meta[property=og:image]",
        )

        val result = parser.parse(html, "https://books.example.test/book/7", hit, "local-id-7", rule)
            as SourceResult.Success

        assertThat(result.value.bookId).isEqualTo("local-id-7")
        assertThat(result.value.bookName).isEqualTo("星河旅人")
        assertThat(result.value.author).isEqualTo("叶子")
        assertThat(result.value.coverUrl).isEqualTo("https://books.example.test/covers/7.jpg")
        assertThat(result.value.introduction).isEqualTo("第一段简介。 第二段简介。")
        assertThat(result.value.normalizedBookKey).isEqualTo("星河旅人|叶子")
    }

    @Test fun fallsBackToSearchHitWhenBookFieldsAbsent() = runBlocking {
        val result = parser.parse(html, hit.detailUrl, hit, "local-id-7", BookRuleDto())
            as SourceResult.Success

        assertThat(result.value.bookId).isEqualTo("local-id-7")
        assertThat(result.value.bookName).isEqualTo("星河旅人")
        assertThat(result.value.author).isEqualTo("叶子")
        assertThat(result.value.coverUrl).isNull()
    }

    @Test fun configuredMissingRequiredNameIsParseFailure() = runBlocking {
        val result = parser.parse(html, hit.detailUrl, hit, "local-id-7", BookRuleDto(bookName = ".absent"))

        assertThat(result).isEqualTo(SourceResult.Failure(SourceFailure.Parse("book.bookName", ".absent")))
    }
}
