package com.novelradar.app.source.rule

/** Import shape only; required values are checked by [RuleValidator]. */
data class RuleDto(
    val url: String = "",
    val name: String = "",
    val comment: String? = null,
    val language: String? = null,
    val disabled: Boolean = false,
    val id: String? = null,
    val search: SearchRuleDto? = null,
    val book: BookRuleDto? = null,
    val toc: TocRuleDto? = null,
    val chapter: ChapterRuleDto? = null,
    val crawl: CrawlRuleDto? = null,
)

data class SearchRuleDto(
    val url: String? = null,
    val method: String? = null,
    val data: String? = null,
    val cookies: String? = null,
    val result: String? = null,
    val bookName: String? = null,
    val author: String? = null,
    val category: String? = null,
    val latestChapter: String? = null,
    val lastUpdateTime: String? = null,
    val wordCount: String? = null,
    val status: String? = null,
    val nextPage: String? = null,
    val pagination: Boolean = false,
    val disabled: Boolean = false,
)

data class BookRuleDto(
    /** Regular expression extracting a book ID, not a request URL. */
    val url: String? = null,
    val bookName: String? = null,
    val author: String? = null,
    val intro: String? = null,
    val category: String? = null,
    val coverUrl: String? = null,
    val latestChapter: String? = null,
    val lastUpdateTime: String? = null,
    val status: String? = null,
)

data class TocRuleDto(
    val baseUri: String? = null,
    val url: String? = null,
    val list: String? = null,
    val item: String? = null,
    val nextPage: String? = null,
    val pagination: Boolean = false,
    val isDesc: Boolean = false,
)

data class ChapterRuleDto(
    val title: String? = null,
    val content: String? = null,
    val paragraphTag: String? = null,
    val filterTxt: String? = null,
    val filterTag: String? = null,
    val nextPage: String? = null,
    val paragraphTagClosed: Boolean = false,
    val pagination: Boolean = false,
)

/** Interval values in the SoNovel format are milliseconds (passed directly to Thread.sleep). */
data class CrawlRuleDto(
    val concurrency: Int? = null,
    val minInterval: Int? = null,
    val maxInterval: Int? = null,
    val maxAttempts: Int? = null,
    val retryMinInterval: Int? = null,
    val retryMaxInterval: Int? = null,
)
