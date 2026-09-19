# Release Hardening and APK Verification Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden security and failure reporting, finish the selected visual/accessibility treatment, produce a locally signed Release APK, verify installation and core flows, and publish build/test documentation.

**Architecture:** All user-visible failures pass through a redacted diagnostic model. Network policy is enforced inside the HTTP layer even though Android must permit cleartext at the manifest level for explicitly opted-in legacy sources. Release signing is local-only and injected through untracked Gradle properties; no secret or private key enters Git.

**Tech Stack:** Android Lint, Compose accessibility tests, OkHttp policy tests, R8, Gradle signing configuration, ADB, an API 26 emulator or physical Android 8 device plus one current Android target, Markdown docs.

---

## Task 1: Add redacted diagnostics and user-facing recovery

**Files:**
- Create: `app/src/main/java/com/novelradar/app/domain/diagnostics/AppDiagnostic.kt`
- Create: `app/src/main/java/com/novelradar/app/domain/diagnostics/DiagnosticRedactor.kt`
- Create: `app/src/main/java/com/novelradar/app/data/diagnostics/DiagnosticLogger.kt`
- Create: `app/src/main/java/com/novelradar/app/ui/components/ErrorPanel.kt`
- Modify: search, source, book, reader, and download UI state mappers
- Test: `app/src/test/java/com/novelradar/app/domain/diagnostics/DiagnosticRedactorTest.kt`

- [ ] **Step 1: Write failing redaction tests**

Cover cookies, authorization headers, query values, filesystem paths, and chapter body fragments. Preserve source ID, host, HTTP code, failure kind, timestamp, and a generated event ID.

```kotlin
@Test fun removesCookiesAndQueryValues() {
    val raw = "Cookie: sid=secret GET https://a.test/search?q=斗破苍穹"
    assertThat(redactor.redact(raw)).isEqualTo("Cookie: [REDACTED] GET https://a.test/search?q=[REDACTED]")
}
```

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.DiagnosticRedactorTest"`

Expected: FAIL.

- [ ] **Step 3: Implement diagnostic contracts**

`AppDiagnostic` contains event ID, source ID, host, operation, failure kind, safe message, timestamp, and retryable flag. `DiagnosticLogger` writes a bounded 1 MiB rotating local log in app-private cache and never stores response bodies or rule scripts.

- [ ] **Step 4: Add consistent recovery UI**

`ErrorPanel` accepts title, concise explanation, optional event ID, retry action, and secondary action. Map timeout to retry, verification to “在浏览器中查看来源” only after explicit tap, unsafe rule to “查看书源问题”, and storage-full to “管理下载”. Do not expose stack traces in release UI.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.DiagnosticRedactorTest"`

Expected: PASS.

```powershell
git add app/src/main/java/com/novelradar/app/domain/diagnostics app/src/main/java/com/novelradar/app/data/diagnostics app/src/main/java/com/novelradar/app/ui/components app/src/main/java/com/novelradar/app/feature app/src/test
git commit -m "feat: add redacted diagnostics and recovery UI"
```

## Task 2: Enforce cleartext, script, and storage security policy

**Files:**
- Create: `app/src/main/res/xml/network_security_config.xml`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/novelradar/app/source/http/HttpPolicy.kt`
- Modify: `app/src/main/java/com/novelradar/app/source/script/QuickJsScriptExecutor.kt`
- Create: `app/src/test/java/com/novelradar/app/SecurityContractTest.kt`

- [ ] **Step 1: Write the security contract test**

Assert non-HTTP schemes are rejected, HTTP is rejected by default and allowed only for the opted-in source, redirects from HTTPS to HTTP are rechecked, scripts have no `fetch`/Java/module bindings, IDs cannot traverse paths, and release logs redact secrets.

- [ ] **Step 2: Verify failure**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.SecurityContractTest"`

Expected: at least the redirect-policy assertion fails before hardening.

- [ ] **Step 3: Configure Android transport policy**

Set `android:networkSecurityConfig="@xml/network_security_config"`. The config allows system/user certificates and cleartext because Android cannot dynamically allow arbitrary imported HTTP domains; `HttpPolicy` remains the authoritative per-source gate. Add an OkHttp network interceptor that checks every redirect request against the same source-specific policy before transmission.

```xml
<network-security-config>
    <base-config cleartextTrafficPermitted="true">
        <trust-anchors>
            <certificates src="system" />
        </trust-anchors>
    </base-config>
</network-security-config>
```

- [ ] **Step 4: Harden scripts and files**

Verify the QuickJS global object does not expose host bindings; reject dynamic import syntax and `Function` constructor in validation; run every evaluation with a fresh context and timeout. Canonicalize every file-store path and assert it starts with the canonical books directory before open/write/delete.

- [ ] **Step 5: Verify and commit**

Run: `.\gradlew.bat :app:testDebugUnitTest --tests "*.SecurityContractTest" --tests "*.QuickJsScriptExecutorTest" --tests "*.AtomicChapterFileStoreTest"`

Expected: PASS.

```powershell
git add app/src/main/res/xml app/src/main/AndroidManifest.xml app/src/main/java/com/novelradar/app/source app/src/main/java/com/novelradar/app/data/files app/src/test
git commit -m "security: enforce source and script boundaries"
```

## Task 3: Finish visual polish and accessibility

**Files:**
- Modify: `app/src/main/java/com/novelradar/app/ui/theme/Color.kt`
- Modify: `app/src/main/java/com/novelradar/app/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/novelradar/app/ui/theme/Shapes.kt`
- Create: `app/src/main/java/com/novelradar/app/ui/components/NovelCover.kt`
- Create: `app/src/androidTest/java/com/novelradar/app/AccessibilitySmokeTest.kt`
- Create: `app/src/androidTest/java/com/novelradar/app/NavigationSmokeTest.kt`

- [ ] **Step 1: Add failing semantics/navigation tests**

Assert every main tab has selected semantics, icon-only controls have content descriptions, touch targets are at least 48 dp, the reader controls are reachable by keyboard/accessibility actions, text scales at 1.5× without clipping, and back navigation returns reader → book → search.

- [ ] **Step 2: Compile tests to expose missing semantics**

Run: `.\gradlew.bat :app:compileDebugAndroidTestSources`

Expected: compile succeeds; executing tests should fail until labels/semantics are complete.

- [ ] **Step 3: Apply the selected design system**

Use primary `#FF5A2A`, ink `#151515`, paper `#FFF8EB`, white surfaces, two-pixel dark borders, 8 dp component corners, 16 dp card corners, and offset dark shadows only on prominent cards/actions. Keep body text calm; use the high-contrast treatment for hierarchy rather than decorating every element.

- [ ] **Step 4: Add accessibility and responsive behavior**

Provide content descriptions, state descriptions, focus order, minimum target size, large-font layouts, contrast-safe night theme, reduced-motion behavior from system animation scale, landscape reader layout, and window-inset handling. Cover images use title-based descriptions outside the reader and decorative semantics where the adjacent title already identifies them.

- [ ] **Step 5: Run lint and commit**

Run:

```powershell
.\gradlew.bat :app:lintDebug
.\gradlew.bat :app:compileDebugAndroidTestSources
```

Expected: no lint errors; instrumentation sources compile.

```powershell
git add app/src/main/java/com/novelradar/app/ui app/src/main/java/com/novelradar/app/feature app/src/androidTest
git commit -m "style: finish accessible Novel Radar UI"
```

## Task 4: Configure R8 and local release signing

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`
- Create: `tools/create-dev-keystore.ps1`
- Create: `docs/build-and-install.md`
- Modify: `.gitignore`

- [ ] **Step 1: Prove an unsigned release is not the deliverable**

Run: `.\gradlew.bat :app:assembleRelease`

Expected before signing configuration: build may create an unsigned APK or fail signing; record the exact result in the task notes and do not call it installable.

- [ ] **Step 2: Create a local-only keystore generator**

`tools/create-dev-keystore.ps1` must generate `local-signing/novelradar-dev.jks` only when absent, create a 4096-bit RSA key valid for 10 years, prompt securely for the password, write the password and aliases to untracked `local-signing/signing.properties`, and print the SHA-256 certificate fingerprint. It must never overwrite an existing key.

The keytool invocation must use alias `novelradar-dev` and distinguished name `CN=Novel Radar Development, OU=Local Development, O=Novel Radar, C=CN`.

- [ ] **Step 3: Inject signing without secrets in Gradle**

Load `local-signing/signing.properties` only when present. Configure a `release` signing config and attach it to the release build type. If absent, fail `assembleRelease` with a concise message pointing to the generator. Add to `.gitignore`:

```gitignore
local-signing/
```

- [ ] **Step 4: Add R8 keep rules**

Keep Moshi-reflected rule DTO constructors/fields and QuickJS native classes required by the library. Enable `isMinifyEnabled = true` and `isShrinkResources = true`. Do not blanket-keep the whole app package. Verify mapping and usage reports are generated under `app/build/outputs/mapping/release`.

- [ ] **Step 5: Document build/install and developer verification**

Document bootstrap, debug build, local signing, release build, ADB install, log capture, and uninstall. State that ADB installations can be tested without Android developer verification. For broad consumer distribution, document the external requirement to register the package/signing key through Android Developer Console or Google Play as rollout rules apply; do not automate identity/account steps.

- [ ] **Step 6: Build, inspect, and commit**

Run:

```powershell
& .\tools\create-dev-keystore.ps1
.\gradlew.bat :app:assembleRelease
& "$env:LOCALAPPDATA\Android\Sdk\build-tools\36.0.0\apksigner.bat" verify --verbose --print-certs .\app\build\outputs\apk\release\app-release.apk
```

Expected: release build exits `0`; verifier reports signature schemes and the local development certificate. Do not add `local-signing` or the APK to Git.

```powershell
git add .gitignore app/build.gradle.kts app/proguard-rules.pro tools/create-dev-keystore.ps1 docs/build-and-install.md
git commit -m "build: configure signed release APK"
```

## Task 5: Run full automated and device verification

**Files:**
- Create: `docs/test-report.md`
- Modify: `README.md`

- [ ] **Step 1: Run clean-room build without using `clean`**

Remove only the explicit project build directories after validating their absolute paths are inside `D:\NovelReader`, or use a fresh worktree. Then run:

```powershell
.\gradlew.bat --stop
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleDebug :app:assembleRelease
```

Expected: `BUILD SUCCESSFUL`; unit test XML shows zero failures; release APK exists.

- [ ] **Step 2: Run instrumented tests**

Connect an API 26 emulator or physical Android 8 device to prove the minimum version. Also run the smoke flow on one current Android target when available. If no API 26 target is present, create it with:

```powershell
$sdkRoot = "$env:LOCALAPPDATA\Android\Sdk"
$sdkManager = "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat"
$avdManager = "$sdkRoot\cmdline-tools\latest\bin\avdmanager.bat"
& $sdkManager --sdk_root=$sdkRoot 'emulator' 'system-images;android-26;google_apis;x86_64'
'no' | & $avdManager create avd --force --name NovelRadarApi26 --package 'system-images;android-26;google_apis;x86_64'
Start-Process -FilePath "$sdkRoot\emulator\emulator.exe" -ArgumentList '-avd','NovelRadarApi26','-no-snapshot-save' -WindowStyle Hidden
& "$sdkRoot\platform-tools\adb.exe" wait-for-device
```

Then run:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
.\gradlew.bat :app:connectedDebugAndroidTest
```

Expected: exactly one intended target is selected; all database, Compose, worker, accessibility, and flow tests pass.

- [ ] **Step 3: Install and run the release APK**

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb install -r .\app\build\outputs\apk\release\app-release.apk
& $adb shell am start -n com.novelradar.app/.MainActivity
& $adb shell pidof com.novelradar.app
```

Expected: install reports `Success`, activity starts, and `pidof` returns a process ID.

- [ ] **Step 4: Execute manual acceptance checklist**

Record pass/fail for: install on Android 8+; first-run rule install; multi-source search; one-source timeout isolation; details/TOC; add shelf; progress restore; scroll/paged reader; reader settings; whole-book pause/resume; process-kill recovery; airplane-mode downloaded reading; JSON import rejection/acceptance; HTTP warning; storage-full behavior; and source fallback confirmation.

- [ ] **Step 5: Write evidence-backed test report and README**

`docs/test-report.md` must include command, date, environment/API level, counts, result, APK SHA-256, certificate SHA-256, manual checklist, and any known live-source failures. `README.md` must describe scope, screenshots or screen descriptions, build/install commands, rule import, security boundaries, and limitations.

- [ ] **Step 6: Final verification and commit**

Run:

```powershell
Get-FileHash .\app\build\outputs\apk\release\app-release.apk -Algorithm SHA256
git status --short
git diff --check
```

Expected: APK hash is recorded in the report; no secrets, signing files, APKs, or unrelated files are staged; `git diff --check` prints nothing.

```powershell
git add README.md docs/test-report.md
git commit -m "docs: record APK verification results"
```

## Task 6: Final acceptance review

**Files:**
- Review all source, test, build, and documentation files

- [ ] **Step 1: Map every design acceptance criterion to evidence**

Create a temporary checklist from design section 12 and point each item to a passing test, command output, or manual-device result. Any criterion without evidence remains incomplete.

- [ ] **Step 2: Run the complete verification command one last time**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
git log --oneline --decorate -20
git status --short
```

Expected: `BUILD SUCCESSFUL`, recent task commits are visible, and the working tree is clean except intentionally ignored build/signing artifacts.

- [ ] **Step 3: Hand off the artifact**

Open or link `app/build/outputs/apk/release/app-release.apk`, `README.md`, `docs/build-and-install.md`, and `docs/test-report.md`. Report the APK hash, minimum Android version, package name, signing status, verified device/API level, and any sources that failed live smoke tests.
