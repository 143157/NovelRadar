package com.novelradar.app.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DomainModelTest {
    @Test
    fun searchHitUsesNormalizedBookAndAuthorIdentity() {
        val hit = SearchHit(
            sourceId = "s1",
            bookName = " 斗破苍穹（全集） ",
            author = "天蚕 土豆",
            detailUrl = "https://example.test/book/1",
            latestChapter = null,
            updatedAtText = null,
        )

        assertThat(hit.normalizedBookKey).isEqualTo("斗破苍穹|天蚕土豆")
    }

    @Test
    fun widthWhitespaceAndCanonicalUnicodeVariantsShareAnIdentity() {
        val hit = hit(bookName = "　ＡＢＣ１２３\t", author = "e\u0301\u00A0张\n三")

        assertThat(hit.normalizedBookKey).isEqualTo("ABC123|é张三")
    }

    @Test
    fun sameBookFromDifferentSourcesHasTheSameIdentity() {
        val first = hit(bookName = "剑来", author = "烽火戏诸侯")
        val second = first.copy(sourceId = "s2", detailUrl = "https://other.test/book/2")

        assertThat(second.normalizedBookKey).isEqualTo(first.normalizedBookKey)
    }

    @Test
    fun matchingTitlesByDifferentAuthorsRemainDistinct() {
        val first = hit(bookName = "同名小说", author = "作者甲")
        val second = first.copy(author = "作者乙")

        assertThat(second.normalizedBookKey).isNotEqualTo(first.normalizedBookKey)
    }

    @Test
    fun terminalCompletionMarkersAreRemovedOnlyAsPairedBracketSuffixes() {
        val suffixes = listOf("（全集）", "(完本)", "【全本】", "[全集]", "〔完本〕", "［全本］")

        for (suffix in suffixes) {
            assertThat(normalizeIdentityPart("剑来$suffix　")).isEqualTo("剑来")
        }
    }

    @Test
    fun completionWordsInsideTitlesAndWithoutBracketsAreRetained() {
        val titles = mapOf(
            "全集故事" to "全集故事",
            "修仙完本" to "修仙完本",
            "全本" to "全本",
            "剑来（全集）续篇" to "剑来全集续篇",
            "（完本）修仙录" to "完本修仙录",
        )

        for ((title, expected) in titles) {
            assertThat(normalizeIdentityPart(title)).isEqualTo(expected)
        }
    }

    @Test
    fun mismatchedOrMissingBracketsDoNotRemoveCompletionWords() {
        val titles = mapOf(
            "剑来(全集]" to "剑来全集",
            "剑来【全本)" to "剑来全本",
            "剑来（完本" to "剑来完本",
            "剑来全集）" to "剑来全集",
        )

        for ((title, expected) in titles) {
            assertThat(normalizeIdentityPart(title)).isEqualTo(expected)
        }
    }

    @Test
    fun bracketedTextOtherThanExactCompletionMarkersIsRetained() {
        assertThat(normalizeIdentityPart("剑来（全集修订版）")).isEqualTo("剑来全集修订版")
        assertThat(normalizeIdentityPart("剑来（第一部）")).isEqualTo("剑来第一部")
    }

    @Test
    fun chinesePunctuationAndItsWidthEquivalentsAreRemovedPredictably() {
        assertThat(normalizeIdentityPart("《Ｃ＋＋》：入门，第一卷！")).isEqualTo("C++入门第一卷")
        assertThat(normalizeIdentityPart("C++:入门,第一卷!")).isEqualTo("C++入门第一卷")
        assertThat(normalizeIdentityPart("「甲」『乙』、【丙】；丁……")).isEqualTo("甲乙丙丁")
        assertThat(normalizeIdentityPart("C++/CLI-v2")).isEqualTo("C++/CLI-v2")
    }

    @Test
    fun identityNormalizationPreservesTheOriginalDisplayText() {
        val hit = hit(bookName = " 《剑来》（全集） ", author = "烽火 戏诸侯")

        assertThat(hit.normalizedBookKey).isEqualTo("剑来|烽火戏诸侯")
        assertThat(hit.bookName).isEqualTo(" 《剑来》（全集） ")
        assertThat(hit.author).isEqualTo("烽火 戏诸侯")
    }

    private fun hit(bookName: String, author: String) = SearchHit(
        sourceId = "s1",
        bookName = bookName,
        author = author,
        detailUrl = "https://example.test/book/1",
    )
}
