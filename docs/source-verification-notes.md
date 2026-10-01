# Source compatibility and live probes

Notes recorded on 2026-10-01. These are read-only workstation HTTP probes, **not** Android `SourceEngine` end-to-end results. Website reachability can differ by network and can change.

## Live observations

- `https://www.biquge365.net/` returned HTTP 200. A POST to `/s.php` using the configured fields `type=articlename` and `s=大梦主` returned HTTP 200 and five linked result rows matching the rule's `span.name > a` shape. A different title returned an empty list; an empty result is not by itself evidence of a broken rule. Detail, table-of-contents, and body parsing remain to be verified through the actual engine.
- `https://www.shuhaige.net/` produced a request-send error in the workstation probe. This does not prove the source is unavailable on a phone.
- `https://www.wxsy.net/` returned HTTP 403 in the workstation probe. No verification or access protection was bypassed.
- `https://www.ranwen8.cc/` failed TLS establishment in the workstation probe. Certificate verification was not disabled.
- The former `https://www.22biqu.com/` source displayed a registrar/auction landing page in the web probe. It should not be counted as a verified novel source or shipped enabled without a successful fresh rule test.

The upstream [source overview](https://github.com/freeok/so-novel/blob/main/BOOK_SOURCES.md) also documents site-specific search limits, including a 15-second search interval for the Biquge365 source. Repeated probes should respect that interval.

## Crawl interval unit

The reference installation is `D:\Download\sonovel小说下载工具\SoNovel\SoNovel`. Its `rules/rule-template.json5` labels crawl intervals as seconds, but the same installation's `config.ini` labels global intervals as milliseconds and its rule sets contain values such as 1000 and 2000.

The actual reference `app.jar` was checked read-only with `javap -c -p`:

- `com.pcdd.sonovel.core.Source` copies `Rule.Crawl` interval integers directly into `AppConfig`, without a factor of 1000.
- `com.pcdd.sonovel.util.CrawlUtils.randomInterval` returns those integers as a long.
- `com.pcdd.sonovel.parse.ChapterParser.fetchContent` passes that value directly to `Thread.sleep(long)` and labels it `ms` in its log.

Therefore compatibility with this reference binary means **milliseconds**, despite the template comment. The Android DTO currently preserves raw integers and has no active interval consumer, but its seconds KDoc must be corrected when the download policy is implemented. Add a regression asserting that an imported interval of 1000 means a one-second delay, not 1000 seconds. Do not silently reinterpret existing imported raw values.

## Remaining verification

Use the actual engine for search → details → catalog → body, separately report empty results, parse failures, TLS/network failures, and verification pages, and never count an HTTP 200 landing page as a working source. Android runtime checks and the final source health results belong in the release test report.
