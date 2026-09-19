# Android Novel Reader Delivery Roadmap

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this roadmap plan-by-plan. Each child plan uses checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver an installable Android APK that searches enabled SoNovel-compatible sources, aggregates books, supports reading and bookshelf progress, and downloads whole books for offline use.

**Architecture:** A single Android application module keeps the first release simple while separating packages by domain, data/source engine, and feature. Compose observes immutable UI state from ViewModels; repositories isolate Room, files, WorkManager, and the source-rule engine. Network and script execution are cancellable and source-scoped so one failing source cannot fail the whole search.

**Tech Stack:** AGP 9.4.0, Gradle 9.6.0, built-in Kotlin 2.2.10, Compose BOM 2026.09.00, compileSdk 37, targetSdk 36, minSdk 26, Room 2.8.5, WorkManager 2.11.2, OkHttp 5.3.0, jsoup 1.23.2, quickjs-kt 1.0.14, Moshi 1.15.2, JUnit 4, MockWebServer, Compose UI tests.

---

## Plan order

1. [Foundation and source engine](2026-09-19-01-foundation-source-engine.md)
   - Installs a reproducible local toolchain, creates the app shell, defines domain contracts, and implements the SoNovel-compatible parsing engine with fixture tests.
   - Exit condition: `assembleDebug` and source-engine unit tests pass; the app opens to the four-tab shell.
2. [Search, persistence, and source management](2026-09-19-02-search-storage.md)
   - Adds Room, built-in/imported rules, streaming aggregate search, the search experience, and source health management.
   - Exit condition: a MockWebServer-backed search appears in the Compose UI, persists imported rules, and isolates failed sources.
3. [Reader, bookshelf, and offline downloads](2026-09-19-03-reader-download.md)
   - Adds book details, table of contents, chapter cache, progress, reader settings, bookshelf, resumable WorkManager downloads, and source fallback.
   - Exit condition: the complete search → read → download → offline-read flow passes automated tests.
4. [Release hardening and APK verification](2026-09-19-04-release-verification.md)
   - Adds diagnostics, security policy, accessibility/polish, release signing, R8 rules, documentation, device checks, and the final test report.
   - Exit condition: a signed Release APK installs through ADB on Android 8+ and all verification commands pass.

## Locked file map

```text
NovelReader/
├── tools/
│   └── bootstrap-android.ps1
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── app/
│   ├── build.gradle.kts
│   ├── proguard-rules.pro
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml
│       │   ├── assets/sources/main.json
│       │   └── java/com/novelradar/app/
│       │       ├── NovelRadarApplication.kt
│       │       ├── MainActivity.kt
│       │       ├── app/               # container, navigation, root UI
│       │       ├── domain/            # models and repository contracts
│       │       ├── data/              # Room, files, repository implementations
│       │       ├── source/            # SoNovel rules, HTTP, selectors, scripts
│       │       ├── feature/library/   # bookshelf
│       │       ├── feature/search/    # aggregate search
│       │       ├── feature/book/      # details and TOC
│       │       ├── feature/reader/    # reading UI and settings
│       │       ├── feature/downloads/ # WorkManager and download UI
│       │       ├── feature/sources/   # import, enable, health checks
│       │       └── ui/theme/          # orange high-contrast visual system
│       ├── test/                      # JVM fixture, repository, ViewModel tests
│       └── androidTest/               # Compose and device integration tests
├── docs/
│   ├── source-rule-format.md
│   ├── build-and-install.md
│   ├── test-report.md
│   └── superpowers/
└── README.md
```

## Cross-plan rules

- Production changes are test-first unless the step is pure Gradle/resource scaffolding.
- Live websites are smoke tests only. Stable regression tests use checked-in HTML fixtures and MockWebServer.
- Never commit SDKs, Gradle distributions, APK signing keys, downloaded chapters, or imported user rules.
- The app may permit user-approved HTTP sources, but repository code must reject them before the request unless the explicit setting is enabled.
- QuickJS receives only string `r`; no Java bindings, module loader, filesystem access, or network function is registered.
- Every task ends with focused tests and a commit. Every milestone ends with `testDebugUnitTest`, `lintDebug`, and `assembleDebug`.
- The final APK is suitable for ADB/development installation. Public consumer distribution may additionally require Android Developer Console package/key registration; that external account step is not automated by this repository.

## Spec coverage self-review

| Design requirement | Implemented and verified in |
|---|---|
| Android project, visual shell, Android 8+ | Plan 1 Tasks 1–2; Plan 4 Tasks 3–5 |
| SoNovel JSON GET/POST, selectors, pagination, cleaning | Plan 1 Tasks 4–8 |
| Restricted `@js` transforms | Plan 1 Task 6; Plan 4 Task 2 |
| Concurrent progressive search, merge, failure isolation | Plan 2 Tasks 4–5 and 7 |
| Room metadata and independent per-source catalogs | Plan 2 Tasks 1–2; Plan 3 Task 1 |
| JSON import, enable/disable, HTTP opt-in, health testing | Plan 2 Tasks 3 and 6 |
| Details, table of contents, source switching | Plan 3 Task 1 |
| Scroll/page reading, settings, progress, prefetch | Plan 3 Tasks 2–4 |
| Bookshelf and update counts | Plan 3 Task 5 |
| Whole-book background download, pause/resume/retry | Plan 3 Tasks 6–7 |
| Cross-source chapter fallback with confirmation | Plan 3 Task 8 |
| Offline reading after restart | Plan 3 Task 9 |
| Error taxonomy, redacted logs, recovery UI | Plan 1 Task 3; Plan 4 Task 1 |
| Script/network/file security boundaries | Plan 1 Tasks 4–6; Plan 4 Task 2 |
| Unit, fixture, integration, Compose, device testing | Every plan milestone; Plan 4 Tasks 5–6 |
| Locally signed installable Release APK and documentation | Plan 4 Tasks 4–6 |

Self-review result: every acceptance item in the approved design has an implementation task and a verification step. The approved non-goals remain absent from all plans.
