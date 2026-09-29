package com.novelradar.app.source.parse

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.rule.ChapterRuleDto
import org.junit.Test
import kotlin.system.measureTimeMillis

class ChapterCleanerTest {
    private val cleaner = ChapterCleaner()

    @Test fun removesConfiguredElementsAndTextButPreservesParagraphs() {
        val html = """
            首段&nbsp;内容<br><span>第二段</span>
            <p>第三段</p><div class="ad">广告不可见</div>
            <script>恶意脚本</script><p>（本章完）</p>
        """.trimIndent()
        val rule = ChapterRuleDto(
            paragraphTagClosed = false,
            paragraphTag = "<br>+",
            filterTag = ".ad, script",
            filterTxt = "（本章完）",
        )

        assertThat(cleaner.clean(html, rule)).isEqualTo(
            SourceResult.Success("首段 内容\n第二段\n第三段")
        )
    }

    @Test fun closedParagraphTagsRemainSeparateLines() {
        val html = "<p>第一段</p><p>第二段&nbsp;内容</p>"
        val rule = ChapterRuleDto(paragraphTagClosed = true)

        assertThat(cleaner.clean(html, rule)).isEqualTo(
            SourceResult.Success("第一段\n第二段 内容")
        )
    }

    @Test fun preservesSpacesAroundInlineElements() {
        val html = "<p>foo <em>bar</em> baz</p><p><span>one</span> <span>two</span></p>"

        assertThat(cleaner.clean(html, ChapterRuleDto())).isEqualTo(
            SourceResult.Success("foo bar baz\none two")
        )
    }

    @Test fun invalidFilterSelectorIsTypedFailure() {
        val result = cleaner.clean("<p>正文</p>", ChapterRuleDto(filterTag = "div["))

        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
    }

    @Test fun invalidFilterRegexIsTypedFailure() {
        val result = cleaner.clean("<p>正文</p>", ChapterRuleDto(filterTxt = "("))

        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
    }

    @Test fun unsupportedBackreferenceIsRejectedRatherThanUsingBacktracking() {
        val result = cleaner.clean("<p>正文</p>", ChapterRuleDto(filterTxt = "(.)\\1"))

        assertThat((result as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
    }

    @Test fun nestedQuantifierCannotStallTheReader() {
        val html = "<p>" + "a".repeat(20_000) + "b</p>"
        var result: SourceResult<String>? = null
        val elapsed = measureTimeMillis {
            result = cleaner.clean(html, ChapterRuleDto(filterTxt = "(a+)+$"))
        }

        assertThat(elapsed).isLessThan(2_000L)
        assertThat(result).isInstanceOf(SourceResult.Success::class.java)
    }

    @Test fun excessiveBlankLinesCollapseToAtMostOneBlankLine() {
        val result = cleaner.clean("<p>首段</p><p></p><p></p><p>末段</p>", ChapterRuleDto())

        assertThat(result).isEqualTo(SourceResult.Success("首段\n\n末段"))
    }
}
