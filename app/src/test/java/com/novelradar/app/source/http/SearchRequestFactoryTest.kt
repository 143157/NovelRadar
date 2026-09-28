package com.novelradar.app.source.http

import com.google.common.truth.Truth.assertThat
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.source.rule.SearchRuleDto
import okio.Buffer
import org.junit.Test

class SearchRequestFactoryTest {
    private val factory = SearchRequestFactory()

    @Test fun getReplacesExistingTemplateWithUtf8PercentEncoding() {
        val result = factory.create(SearchRuleDto(url = "https://example.test/search?q=%s&kind=book", method = "GET"), "斗破 苍穹")
        val request = (result as SourceResult.Success).value
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.toString()).isEqualTo("https://example.test/search?q=%E6%96%97%E7%A0%B4%20%E8%8B%8D%E7%A9%B9&kind=book")
        assertThat(request.url.queryParameterNames).containsExactly("q", "kind")
    }

    @Test fun postParsesLooseFormBeforeSubstitutingKeyword() {
        val result = factory.create(SearchRuleDto(url = "https://example.test/search", method = "POST", data = "{searchkey: %s, searchtype: all}"), "斗破 苍穹")
        val request = (result as SourceResult.Success).value
        assertThat(request.method).isEqualTo("POST")
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertThat(body).isEqualTo("searchkey=%E6%96%97%E7%A0%B4%20%E8%8B%8D%E7%A9%B9&searchtype=all")
    }

    @Test fun quotedDelimitersAndKeywordDelimitersCannotCreateFields() {
        val result = factory.create(SearchRuleDto(url = "https://example.test/search", method = "POST", data = "{'q': '%s', 'fixed': 'a,b:c'}"), "x,admin:true:y")
        val request = (result as SourceResult.Success).value
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertThat(body).isEqualTo("q=x%2Cadmin%3Atrue%3Ay&fixed=a%2Cb%3Ac")
    }

    @Test fun postEscapesLiteralPlusAndPercentFromKeyword() {
        val result = factory.create(SearchRuleDto(url = "https://example.test/search", method = "POST", data = "{q: %s}"), "a+b% c")
        val request = (result as SourceResult.Success).value
        val body = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
        assertThat(body).isEqualTo("q=a%2Bb%25%20c")
    }

    @Test fun invalidTemplatesAndMethodsReturnTypedFailure() {
        val cases = listOf(
            SearchRuleDto(url = "https://example.test/search", method = "GET"),
            SearchRuleDto(url = "https://example.test/search", method = "POST", data = "{q: fixed}"),
            SearchRuleDto(url = "https://example.test/search", method = "POST", data = "{q: %s, broken}"),
            SearchRuleDto(url = "https://example.test/search", method = "PATCH", data = "{q: %s}"),
        )
        cases.forEach { assertThat(factory.create(it, "book")).isInstanceOf(SourceResult.Failure::class.java) }
        assertThat((factory.create(cases[2], "book") as SourceResult.Failure).error).isInstanceOf(SourceFailure.UnsafeRule::class.java)
    }

    @Test fun cleartextRequiresOptInAndOtherSchemesAreRejected() {
        val http = SearchRuleDto(url = "http://example.test/?q=%s", method = "GET")
        assertThat(factory.create(http, "book")).isInstanceOf(SourceResult.Failure::class.java)
        assertThat(factory.create(http, "book", allowCleartext = true)).isInstanceOf(SourceResult.Success::class.java)
        listOf("file:///tmp/%s", "ftp://example.test/%s", "data:text/plain,%s").forEach { url ->
            assertThat(factory.create(http.copy(url = url), "book", allowCleartext = true)).isInstanceOf(SourceResult.Failure::class.java)
        }
        assertThat(HttpPolicy.isAllowed("https://example.test/", false)).isTrue()
        assertThat(HttpPolicy.isAllowed("http://example.test/", false)).isFalse()
        assertThat(HttpPolicy.isAllowed("http://example.test/", true)).isTrue()
    }
}
