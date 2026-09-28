package com.novelradar.app.source.parse

import com.google.common.truth.Truth.assertThat
import org.jsoup.Jsoup
import org.junit.Test

class HtmlSelectorTest {
    private val baseUrl = "https://books.example.test/catalog/page.html"
    private val html = """
        <html><head><meta property="og:image" content="/cover/7.jpg"></head>
        <body>
          <a href="/outside">外部链接</a>
          <article class="book">
            <h2>  星河   旅人 </h2>
            <a class="detail" href="../books/7">详情</a>
            <img src="/covers/7.jpg">
            <div id="chapter">首段<br><p>第二段</p></div>
          </article>
        </body></html>
    """.trimIndent()
    private val document = Jsoup.parse(html, baseUrl)
    private val selector = HtmlSelector()

    @Test fun splitsOnlyFirstScriptMarker() {
        val expression = SelectorExpression.parse("h2@js:r=r.replace('星', '@js:辰');")

        assertThat(expression.selector).isEqualTo("h2")
        assertThat(expression.script).isEqualTo("r=r.replace('星', '@js:辰');")
    }

    @Test fun leavesScriptAbsentWhenThereIsNoMarker() {
        val expression = SelectorExpression.parse("h2")

        assertThat(expression.selector).isEqualTo("h2")
        assertThat(expression.script).isNull()
    }

    @Test fun cssScalarExtractionNormalizesText() {
        assertThat(selector.values(document, "h2")).containsExactly("星河 旅人")
    }

    @Test fun cssSelectionCanBeScopedToOneResult() {
        val book = selector.elements(document, "article.book").single()

        assertThat(selector.values(book, "h2")).containsExactly("星河 旅人")
    }

    @Test fun anchorHrefResolvesAgainstPageUrl() {
        assertThat(selector.values(document, "a.detail"))
            .containsExactly("https://books.example.test/books/7")
    }

    @Test fun imageSrcResolvesAgainstPageUrl() {
        assertThat(selector.values(document, "img"))
            .containsExactly("https://books.example.test/covers/7.jpg")
    }

    @Test fun metaReturnsContentAttribute() {
        assertThat(selector.values(document, "meta[property=og:image]"))
            .containsExactly("/cover/7.jpg")
    }

    @Test fun xpathSelectsElementsFromJsoupDocument() {
        assertThat(selector.values(document, "//article[@class='book']/h2"))
            .containsExactly("星河 旅人")
    }

    @Test fun xpathSelectionRemainsScopedToContextElement() {
        val book = selector.elements(document, "article.book").single()

        assertThat(selector.values(book, "//a"))
            .containsExactly("https://books.example.test/books/7")
    }

    @Test fun chapterContentUsesInnerHtmlInsteadOfFlattenedText() {
        assertThat(selector.values(document, "#chapter", chapterContent = true).single())
            .contains("<br>")
        assertThat(selector.values(document, "#chapter", chapterContent = true).single())
            .contains("<p>第二段</p>")
    }

    @Test fun missingSelectorReturnsNoValues() {
        assertThat(selector.values(document, ".missing")).isEmpty()
    }
}
