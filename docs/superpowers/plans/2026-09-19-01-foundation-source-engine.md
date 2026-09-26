# Foundation and Source Engine Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a buildable Android app shell and a fixture-tested SoNovel-compatible engine for rule parsing, HTTP request construction, HTML selection, restricted JavaScript transforms, search, table-of-contents, and chapter extraction.

**Architecture:** The app is a single Android module with strict package boundaries. Source-engine classes depend on domain models but not Compose, Room, or WorkManager. A `SourceHttpClient` and `ScriptExecutor` interface makes all parsing behavior deterministic in local JVM tests.

**Tech Stack:** PowerShell bootstrap, compileSdk 37.2, AGP 9.4.0, Gradle 9.6.0, built-in Kotlin, Compose, Kotlin coroutines, OkHttp, MockWebServer, jsoup, Moshi, quickjs-kt, JUnit 4, Truth.

---

## Task 1: Bootstrap the local Android toolchain

**Files:**
- Create: `tools/bootstrap-android.ps1`
- Modify: `.gitignore`

- [ ] **Step 1: Record the failing prerequisite check**

Run:

```powershell
Get-Command adb, sdkmanager, gradle -ErrorAction SilentlyContinue
```

Expected before bootstrap: no entries for Android SDK or Gradle on this machine.

- [ ] **Step 2: Create the reproducible bootstrap script**

Create `tools/bootstrap-android.ps1` with this behavior and constants:

```powershell
$ErrorActionPreference = 'Stop'
$androidSdkDir = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
$toolCacheDir = Join-Path $PSScriptRoot '..\.tools'
$cliZip = Join-Path $toolCacheDir 'commandlinetools-win-15859902_latest.zip'
$cliUrl = 'https://dl.google.com/android/repository/commandlinetools-win-15859902_latest.zip'
$gradleVersion = '9.6.0'
$gradleZip = Join-Path $toolCacheDir "gradle-$gradleVersion-bin.zip"
$gradleUrl = "https://services.gradle.org/distributions/gradle-$gradleVersion-bin.zip"

New-Item -ItemType Directory -Force -Path $toolCacheDir, $androidSdkDir | Out-Null

if (-not (Test-Path -LiteralPath $cliZip)) {
    Invoke-WebRequest -Uri $cliUrl -OutFile $cliZip
}
$cliStage = Join-Path $toolCacheDir 'android-cli-stage'
$resolvedToolCache = [IO.Path]::GetFullPath($toolCacheDir)
$resolvedStage = [IO.Path]::GetFullPath($cliStage)
if (-not $resolvedStage.StartsWith($resolvedToolCache + [IO.Path]::DirectorySeparatorChar)) {
    throw "Refusing to clear stage outside tool cache: $resolvedStage"
}
if (Test-Path -LiteralPath $resolvedStage) {
    Remove-Item -LiteralPath $resolvedStage -Recurse -Force
}
Expand-Archive -LiteralPath $cliZip -DestinationPath $cliStage
$cliLatest = Join-Path $androidSdkDir 'cmdline-tools\latest'
New-Item -ItemType Directory -Force -Path $cliLatest | Out-Null
Copy-Item -Path (Join-Path $cliStage 'cmdline-tools\*') -Destination $cliLatest -Recurse -Force

$sdkManager = Join-Path $cliLatest 'bin\sdkmanager.bat'
$licenseInput = (1..100 | ForEach-Object { 'y' }) -join [Environment]::NewLine
$licenseInput | & $sdkManager --sdk_root=$androidSdkDir --licenses | Out-Host
& $sdkManager --sdk_root=$androidSdkDir 'platform-tools' 'platforms;android-37.2' 'build-tools;36.0.0'

if (-not (Test-Path -LiteralPath $gradleZip)) {
    Invoke-WebRequest -Uri $gradleUrl -OutFile $gradleZip
}
$gradleDir = Join-Path $toolCacheDir "gradle-$gradleVersion"
if (-not (Test-Path -LiteralPath $gradleDir)) {
    Expand-Archive -LiteralPath $gradleZip -DestinationPath $toolCacheDir
}

Set-Content -LiteralPath (Join-Path $PSScriptRoot '..\local.properties') -Value (
    'sdk.dir=' + ($androidSdkDir -replace '\\', '\\')
)
Write-Output "ANDROID_SDK=$androidSdkDir"
Write-Output "GRADLE=$gradleDir\bin\gradle.bat"
```

- [ ] **Step 3: Ignore local tool and SDK artifacts**

Append exactly:

```gitignore
.tools/
*.jks
*.keystore
app/release/
```

- [ ] **Step 4: Run bootstrap and verify installed packages**

Run:

```powershell
& .\tools\bootstrap-android.ps1
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" version
```

Expected: bootstrap exits `0`; ADB prints a version; `platforms\android-37.2` and `build-tools\36.0.0` exist.

- [ ] **Step 5: Commit**

```powershell
git add .gitignore tools/bootstrap-android.ps1
git commit -m "build: add Android toolchain bootstrap"
```

## Task 2: Create the Gradle project and app shell

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Create: `app/build.gradle.kts`
- Create: `app/proguard-rules.pro`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/java/com/novelradar/app/MainActivity.kt`
- Create: `app/src/main/java/com/novelradar/app/app/NovelRadarApp.kt`
- Create: `app/src/main/java/com/novelradar/app/ui/theme/Color.kt`
- Create: `app/src/main/java/com/novelradar/app/ui/theme/Theme.kt`
- Test: `app/src/androidTest/java/com/novelradar/app/AppLaunchTest.kt`

- [ ] **Step 1: Generate and pin the wrapper**

Run:

```powershell
& '.\.tools\gradle-9.6.0\bin\gradle.bat' wrapper --gradle-version 9.6.0
```

Expected: wrapper files exist and `gradle-wrapper.properties` uses `gradle-9.6.0-bin.zip`.

- [ ] **Step 2: Create settings and version catalog**

Use this dependency contract in `gradle/libs.versions.toml`:

```toml
[versions]
agp = "9.4.0"
kotlin = "2.4.10"
ksp = "2.3.6"
composeBom = "2026.09.00"
activityCompose = "1.13.0"
lifecycle = "2.11.0"
navigation = "2.10.1"
room = "2.8.5"
work = "2.11.2"
datastore = "1.2.0"
coroutines = "1.11.0"
okhttp = "5.3.0"
jsoup = "1.23.2"
moshi = "1.15.2"
quickjs = "1.0.14"
coil = "3.3.0"
truth = "1.4.5"
turbine = "1.2.1"

[libraries]
androidx-activity-compose = { module = "androidx.activity:activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-runtime-compose = { module = "androidx.lifecycle:lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { module = "androidx.lifecycle:lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-navigation-compose = { module = "androidx.navigation:navigation-compose", version.ref = "navigation" }
androidx-compose-bom = { module = "androidx.compose:compose-bom", version.ref = "composeBom" }
androidx-compose-ui = { module = "androidx.compose.ui:ui" }
androidx-compose-foundation = { module = "androidx.compose.foundation:foundation" }
androidx-compose-material3 = { module = "androidx.compose.material3:material3" }
androidx-compose-ui-tooling = { module = "androidx.compose.ui:ui-tooling" }
androidx-compose-ui-tooling-preview = { module = "androidx.compose.ui:ui-tooling-preview" }
androidx-compose-ui-test-junit4 = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
androidx-room-runtime = { module = "androidx.room:room-runtime", version.ref = "room" }
androidx-room-ktx = { module = "androidx.room:room-ktx", version.ref = "room" }
androidx-room-compiler = { module = "androidx.room:room-compiler", version.ref = "room" }
androidx-work-runtime = { module = "androidx.work:work-runtime-ktx", version.ref = "work" }
androidx-work-testing = { module = "androidx.work:work-testing", version.ref = "work" }
androidx-datastore = { module = "androidx.datastore:datastore-preferences", version.ref = "datastore" }
kotlinx-coroutines-android = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-android", version.ref = "coroutines" }
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutines" }
okhttp-bom = { module = "com.squareup.okhttp3:okhttp-bom", version.ref = "okhttp" }
okhttp = { module = "com.squareup.okhttp3:okhttp" }
okhttp-mockwebserver = { module = "com.squareup.okhttp3:mockwebserver", version.ref = "okhttp" }
jsoup = { module = "org.jsoup:jsoup", version.ref = "jsoup" }
moshi = { module = "com.squareup.moshi:moshi", version.ref = "moshi" }
moshi-kotlin = { module = "com.squareup.moshi:moshi-kotlin", version.ref = "moshi" }
quickjs = { module = "io.github.dokar3:quickjs-kt", version.ref = "quickjs" }
coil-compose = { module = "io.coil-kt.coil3:coil-compose", version.ref = "coil" }
junit4 = { module = "junit:junit", version = "4.13.2" }
truth = { module = "com.google.truth:truth", version.ref = "truth" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
compose-compiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

Compatibility adjustment verified during Task 2: QuickJS 1.0.14 ships Kotlin metadata 2.4.0 and depends on Kotlin stdlib 2.4.10; Kotlin 2.2.10 cannot compile against it. Pin Kotlin and the Compose compiler to 2.4.10, and supply `org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10` in the root buildscript classpath to upgrade AGP's built-in Kotlin compiler.

`settings.gradle.kts` must declare `google()`, `mavenCentral()`, `gradlePluginPortal()`, set `rootProject.name = "NovelRadar"`, and include `:app`. Apply `org.gradle.toolchains.foojay-resolver-convention` 1.0.0 so Gradle can provision the required Java 17 toolchain when it is not installed locally; do not pin machine-specific JDK or proxy paths in the project.

- [ ] **Step 3: Configure the Android application**

`app/build.gradle.kts` must use package `com.novelradar.app`, `minSdk = 26`, `compileSdk = 37` with `minorApiLevel = 2`, `targetSdk = 36`, Java toolchain 17, Compose enabled, and the version-catalog dependencies above. Add this JVM unit-test substitution so QuickJS tests work on Windows JVM:

```kotlin
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:1.0.14"))
    }
}
```

- [ ] **Step 4: Add the launch smoke test before the UI implementation**

Create `AppLaunchTest.kt`:

```kotlin
@RunWith(AndroidJUnit4::class)
class AppLaunchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun showsPrimaryNavigation() {
        val labels = listOf("书架", "搜书", "下载", "设置")
        val selectable = SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected)
        labels.forEach { label ->
            compose.onNode(hasText(label) and selectable)
                .assertExists()
                .assertHasClickAction()
                .performClick()
                .assertIsSelected()
            labels.filterNot { it == label }.forEach { otherLabel ->
                compose.onNode(hasText(otherLabel) and selectable).assertIsNotSelected()
            }
        }
    }
}
```

Use the Compose test `junit4.v2.createAndroidComposeRule` API. Match selectable navigation nodes so the centered destination heading does not create an ambiguous text match.

- [ ] **Step 5: Implement the minimal themed shell**

`NovelRadarApp.kt` must render a `Scaffold` with four `NavigationBarItem`s and an orange `#FF5A2A` primary color. Each destination initially renders only its Chinese label in a centered `Text`. `MainActivity` calls `setContent { NovelRadarTheme { NovelRadarApp() } }`.

- [ ] **Step 6: Verify the scaffold**

Run:

```powershell
.\gradlew.bat help
.\gradlew.bat :app:assembleDebug
```

Expected: both exit `0`; `app\build\outputs\apk\debug\app-debug.apk` exists. Run the instrumented smoke test when an emulator/device is available; do not block this milestone on the device before Task 7.

- [ ] **Step 7: Commit**

```powershell
git add settings.gradle.kts build.gradle.kts gradle.properties gradle app
git commit -m "build: scaffold Novel Radar Android app"
```

## Task 3: Define stable domain models and error contracts

**Files:**
- Create: `app/src/main/java/com/novelradar/app/domain/model/Book.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/model/BookSource.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/model/SearchHit.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/model/Chapter.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/model/SourceRule.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/model/SourceFailure.kt`
- Test: `app/src/test/java/com/novelradar/app/domain/model/DomainModelTest.kt`

- [ ] **Step 1: Write the failing identity test**

```kotlin
class DomainModelTest {
    @Test fun bookIdentityNormalizesChinesePunctuationAndWhitespace() {
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
}
```

- [ ] **Step 2: Run the focused test**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.DomainModelTest"`

Expected: FAIL because `SearchHit` is undefined.

- [ ] **Step 3: Implement immutable models**

Define `Book`, `BookSource`, `SearchHit`, and `Chapter` as immutable data classes with string IDs. `SearchHit.normalizedBookKey` must use a pure `normalizeIdentityPart` function that applies Unicode NFKC, removes whitespace, converts Chinese punctuation to empty text, and strips terminal markers `全集`, `完本`, and `全本` only when enclosed in brackets.

Define failures exactly as:

```kotlin
sealed interface SourceFailure {
    data object Timeout : SourceFailure
    data class Http(val code: Int) : SourceFailure
    data class Decode(val charset: String?) : SourceFailure
    data class Parse(val field: String, val selector: String) : SourceFailure
    data object VerificationRequired : SourceFailure
    data class UnsafeRule(val reason: String) : SourceFailure
    data class Unexpected(val message: String) : SourceFailure
}
```

Also define the result wrapper used by every source-engine API:

```kotlin
sealed interface SourceResult<out T> {
    data class Success<T>(val value: T) : SourceResult<T>
    data class Failure(val error: SourceFailure) : SourceResult<Nothing>
}
```

`SourceRule.kt` also defines `StoredSourceRule` (ID, raw JSON, enabled, built-in, cleartext permission, and health) plus `SourceHealth` with status `UNKNOWN`, `HEALTHY`, `SLOW`, `FAILED`, or `VERIFICATION_REQUIRED`.

- [ ] **Step 4: Verify domain behavior**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.DomainModelTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/java/com/novelradar/app/domain app/src/test/java/com/novelradar/app/domain
git commit -m "feat: add novel domain contracts"
```

## Task 4: Parse and validate SoNovel JSON rules

**Files:**
- Create: `app/src/main/java/com/novelradar/app/source/rule/RuleDto.kt`
- Create: `app/src/main/java/com/novelradar/app/source/rule/RuleJsonParser.kt`
- Create: `app/src/main/java/com/novelradar/app/source/rule/RuleValidator.kt`
- Create: `app/src/main/java/com/novelradar/app/source/rule/RuleIssue.kt`
- Create: `app/src/test/resources/rules/valid-search-rule.json`
- Create: `app/src/test/resources/rules/invalid-script-rule.json`
- Test: `app/src/test/java/com/novelradar/app/source/rule/RuleJsonParserTest.kt`
- Test: `app/src/test/java/com/novelradar/app/source/rule/RuleValidatorTest.kt`

- [ ] **Step 1: Write parser and validator tests first**

Cover a valid POST rule, absent optional fields, duplicate names, unsupported schemes, a script longer than 16 KiB, and selectors longer than 2 KiB. The key assertion is:

```kotlin
@Test fun rejectsRuleThatCanImportModules() {
    val rule = parser.parseOne(invalidScriptJson)
    assertThat(validator.validate(rule)).contains(
        RuleIssue("search.bookName", "script contains forbidden token: import")
    )
}
```

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.source.rule.*"`

Expected: FAIL because parser and validator are undefined.

- [ ] **Step 3: Implement DTOs and parser**

Model top-level fields plus nested `SearchRuleDto`, `BookRuleDto`, `TocRuleDto`, `ChapterRuleDto`, and `CrawlRuleDto`. Configure Moshi with `KotlinJsonAdapterFactory`, accept either one object or an array, and never throw raw Moshi exceptions across the public API:

```kotlin
sealed interface RuleParseResult {
    data class Success(val rules: List<RuleDto>) : RuleParseResult
    data class Failure(val message: String) : RuleParseResult
}
```

Define `data class RuleIssue(val path: String, val message: String)` and return issues in stable path/message order so the importer and UI can show deterministic diagnostics.

- [ ] **Step 4: Implement deterministic validation**

Require HTTPS or HTTP base URL, a nonblank name, search URL/method/result/bookName for searchable sources, TOC item, chapter title/content, and `%s` for any derived URL template. Reject scripts containing `import`, `require`, `fetch`, `XMLHttpRequest`, `WebSocket`, `Java`, `Packages`, `process`, or `eval`. Preserve disabled rules but return issues rather than silently deleting them.

- [ ] **Step 5: Run tests and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.source.rule.*"`

Expected: PASS.

```powershell
git add app/src/main/java/com/novelradar/app/source/rule app/src/test
git commit -m "feat: parse and validate SoNovel rules"
```

## Task 5: Build safe GET and form-POST requests

**Files:**
- Create: `app/src/main/java/com/novelradar/app/source/http/HttpPolicy.kt`
- Create: `app/src/main/java/com/novelradar/app/source/http/SearchRequestFactory.kt`
- Create: `app/src/main/java/com/novelradar/app/source/http/SourceHttpClient.kt`
- Test: `app/src/test/java/com/novelradar/app/source/http/SearchRequestFactoryTest.kt`
- Test: `app/src/test/java/com/novelradar/app/source/http/SourceHttpClientTest.kt`

- [ ] **Step 1: Write failing request tests**

Test URL substitution, UTF-8 encoding, JSON-like form syntax, cookies, a three-hop redirect, timeout mapping, cancellation, and HTTP policy. Include:

```kotlin
@Test fun parsesSoNovelFormTemplateWithoutStringConcatenation() {
    val request = factory.create(rule(method = "post", data = "{searchkey: %s, searchtype: all}"), "斗破 苍穹")
    assertThat(request.body!!.readUtf8()).isEqualTo("searchkey=%E6%96%97%E7%A0%B4%20%E8%8B%8D%E7%A9%B9&searchtype=all")
}
```

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.source.http.*"`

Expected: FAIL.

- [ ] **Step 3: Implement policy and request factory**

`HttpPolicy.isAllowed(url, allowCleartext)` returns true for HTTPS, true for HTTP only when the setting is true, and false for every other scheme. Parse POST `data` by removing the outer braces, splitting only commas outside quoted segments, splitting each pair at its first colon, trimming quotes, replacing `%s`, and adding fields through OkHttp `FormBody.Builder`.

- [ ] **Step 4: Implement the cancellable client**

Wrap `Call.enqueue` with `suspendCancellableCoroutine`; cancel the OkHttp call when the coroutine is cancelled. Limit redirects to five, set a default 10-second per-source timeout, use a cookie jar scoped by source ID, and map exceptions to `SourceFailure`.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.source.http.*"`

Expected: PASS.

```powershell
git add app/src/main/java/com/novelradar/app/source/http app/src/test/java/com/novelradar/app/source/http
git commit -m "feat: build safe source HTTP requests"
```

## Task 6: Implement CSS/XPath selection and restricted `@js`

**Files:**
- Create: `app/src/main/java/com/novelradar/app/source/parse/SelectorExpression.kt`
- Create: `app/src/main/java/com/novelradar/app/source/parse/HtmlSelector.kt`
- Create: `app/src/main/java/com/novelradar/app/source/script/ScriptExecutor.kt`
- Create: `app/src/main/java/com/novelradar/app/source/script/QuickJsScriptExecutor.kt`
- Test: `app/src/test/java/com/novelradar/app/source/parse/HtmlSelectorTest.kt`
- Test: `app/src/test/java/com/novelradar/app/source/script/QuickJsScriptExecutorTest.kt`

- [ ] **Step 1: Write failing selector tests**

Use fixture HTML to assert text extraction, `href` absolute resolution, `src`, `meta[property=...]` content, XPath beginning with `//`, and an `@js:` transform. The script test must include successful replacement and `while(true){}` timeout.

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.source.parse.*" --tests "*.source.script.*"`

Expected: FAIL.

- [ ] **Step 3: Implement selector expressions**

Split only the first `@js:` occurrence into selector and script. CSS selection uses jsoup. For XPath, parse with jsoup first, convert through jsoup `W3CDom`, then evaluate with `XPathFactory`; configure any XML factory used by tests with external entities and DTD disabled. Attribute selection rules are fixed: anchors return absolute `href`, images return absolute `src`, meta elements return `content`, and other elements return inner HTML for chapter content or normalized text for scalar fields.

- [ ] **Step 4: Implement the script sandbox**

`QuickJsScriptExecutor.transform(input, script)` must create a fresh QuickJS context, set `evaluationTimeoutMillis = 300`, define no bindings, JSON-quote the input, and evaluate:

```javascript
(() => { let r = INPUT; SCRIPT; return String(r); })()
```

Reject inputs over 2 MiB, scripts over 16 KiB, and output over 4 MiB. Always close the runtime in `finally`. Map timeout, output limit, and JS errors to `SourceFailure.UnsafeRule` or `SourceFailure.Parse` without crashing the process.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.source.parse.*" --tests "*.source.script.*"`

Expected: PASS, including the infinite-loop test in under two seconds.

```powershell
git add app/src/main/java/com/novelradar/app/source/parse app/src/main/java/com/novelradar/app/source/script app/src/test
git commit -m "feat: add selector and sandboxed script engine"
```

## Task 7: Parse search, book, TOC, and chapter responses

**Files:**
- Create: `app/src/main/java/com/novelradar/app/source/parse/SearchPageParser.kt`
- Create: `app/src/main/java/com/novelradar/app/source/parse/BookPageParser.kt`
- Create: `app/src/main/java/com/novelradar/app/source/parse/TocPageParser.kt`
- Create: `app/src/main/java/com/novelradar/app/source/parse/ChapterPageParser.kt`
- Create: `app/src/main/java/com/novelradar/app/source/parse/ChapterCleaner.kt`
- Create: `app/src/main/java/com/novelradar/app/source/SourceEngine.kt`
- Create: `app/src/test/resources/html/search.html`
- Create: `app/src/test/resources/html/book.html`
- Create: `app/src/test/resources/html/toc.html`
- Create: `app/src/test/resources/html/chapter.html`
- Test: `app/src/test/java/com/novelradar/app/source/SourceEngineTest.kt`

- [ ] **Step 1: Write the end-to-end fixture test**

Mock four endpoints and assert that searching yields a `SearchHit`, book parsing yields cover/intro, TOC parsing returns ordered unique chapters with absolute URLs, and chapter parsing removes configured tags/text while preserving paragraphs.

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.SourceEngineTest"`

Expected: FAIL.

- [ ] **Step 3: Implement focused page parsers**

Each parser accepts HTML, base URL, and only its corresponding nested rule. It returns `SourceResult<T>`; empty required values are parse failures. `TocPageParser` de-duplicates by resolved URL while preserving document order. `ChapterCleaner` removes `filterTag`, applies compiled `filterTxt`, converts configured paragraph separators to newline, normalizes nonbreaking spaces, and collapses more than two blank lines.

- [ ] **Step 4: Implement the source facade and pagination caps**

`SourceEngine` composes the request factory, HTTP client, and parsers. Search pagination stops after 10 pages or when the next URL repeats; TOC pagination stops after 100 pages; chapter pagination stops after 20 pages. These hard caps prevent hostile or broken rules from looping forever.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.SourceEngineTest"`

Expected: PASS.

```powershell
git add app/src/main/java/com/novelradar/app/source app/src/test
git commit -m "feat: parse complete novel source flow"
```

## Task 8: Port safe built-in rules and close the milestone

**Files:**
- Create: `app/src/main/assets/sources/main.json`
- Create: `docs/source-rule-format.md`
- Test: `app/src/test/java/com/novelradar/app/source/BuiltInRulesContractTest.kt`

- [ ] **Step 1: Add the built-in rule contract test**

The test loads `assets/sources/main.json`, parses every rule, asserts stable nonblank IDs, asserts no validation errors, and asserts HTTP rules are imported disabled. It must not make live network requests.

- [ ] **Step 2: Copy and normalize reference rules**

Use `D:\Download\sonovel小说下载工具\SoNovel\SoNovel\rules\main.json` as the reference. Add stable IDs derived from `SHA-256(lowercase-host + "|" + name)` truncated to 16 hex characters. Preserve source authorship/comments. Disable rules using HTTP, forbidden scripts, unsupported Cloudflare bypass, or currently invalid schema instead of weakening validation.

- [ ] **Step 3: Document the accepted rule subset**

`docs/source-rule-format.md` must include complete examples for GET and form POST; supported scalar selectors; absolute URL behavior; pagination; chapter cleaning; `@js:` input/output and limits; HTTP opt-in; import conflict rules; and explicit unsupported capabilities.

- [ ] **Step 4: Run milestone verification**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:assembleDebug
git status --short
```

Expected: all commands exit `0`; only intended rule/doc changes remain before commit.

- [ ] **Step 5: Commit**

```powershell
git add app/src/main/assets docs/source-rule-format.md app/src/test
git commit -m "feat: ship validated SoNovel source rules"
```
