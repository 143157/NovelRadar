# Selected App Icon Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Use the user's selected purple-background, cream-book image as Novel Radar's launcher icon without redrawing the image or changing the in-app palette.

**Architecture:** Preserve the supplied PNG byte-for-byte and reference it from Android drawable/mipmap resources. A color background and inset bitmap foreground provide the adaptive icon; a plain bitmap resource supplies the non-adaptive fallback. Both launcher attributes resolve to the same selected artwork. No image-generation call or raster alteration is necessary.

**Tech Stack:** Android resource XML, PNG, manifest, JVM resource-contract tests, AAPT, Gradle.

---

## Approved asset and boundary

The user explicitly selected this image on 2026-10-04 and then requested continuation. Its canonical project copy is `docs/assets/user-app-icon.png`, 1254 × 1254 pixels, 1,401,871 bytes, SHA-256 `EAE824602E571E34E47D5FB6B7290B26422E0061AB5E48B68DCDBA95785E1907`. It has no requested caption or branding text. Copy, do not overwrite or regenerate it.

The adaptive foreground uses an 18 dp inset on each side, with a full-color background sampled from the original corner (`#4E529E`). This is a resource-level layout adaptation, not a pixel edit. The original's book shape remains inside the central logo area. Android supports bitmap foreground/background layers and OEM masks; the reserved outer area and icon attributes are documented in [Android adaptive icon guidance](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive). Device appearance still requires a launcher check; resource tests do not prove runtime rendering. Do not add a monochrome redesign in this task.

## Task 1: Connect and verify the chosen launcher icon

**Files:**

- Create: `app/src/main/res/drawable-nodpi/user_app_icon.png` (exact binary copy)
- Create: `app/src/main/res/drawable/launcher_icon_foreground.xml`
- Create: `app/src/main/res/values/launcher_icon_colors.xml`
- Create: `app/src/main/res/mipmap-anydpi/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi/ic_launcher_round.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`
- Modify: `app/src/main/AndroidManifest.xml` (icon attributes only)
- Test: `app/src/test/java/com/novelradar/app/LauncherIconContractTest.kt`

- [x] **Step 1: Write and observe the failing resource-contract test**

Use the existing unit-test working-directory convention (`app`). The following test exercises the actual manifest, XML resources and supplied PNG rather than mocking resource ownership:

```kotlin
package com.novelradar.app

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test

class LauncherIconContractTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun xml(path: String) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
    }.newDocumentBuilder().parse(File("src/main/$path"))

    @Test fun manifestAndMasksUseSelectedArtwork() {
        val application = xml("AndroidManifest.xml").getElementsByTagName("application").item(0) as org.w3c.dom.Element
        assertThat(application.getAttributeNS(ns, "icon")).isEqualTo("@mipmap/ic_launcher")
        assertThat(application.getAttributeNS(ns, "roundIcon")).isEqualTo("@mipmap/ic_launcher_round")
        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val fallback = xml("res/mipmap-anydpi/$name.xml").documentElement
            assertThat(fallback.tagName).isEqualTo("bitmap")
            assertThat(fallback.getAttributeNS(ns, "src")).isEqualTo("@drawable/user_app_icon")
            val adaptive = xml("res/mipmap-anydpi-v26/$name.xml")
            assertThat(adaptive.documentElement.tagName).isEqualTo("adaptive-icon")
            val foreground = adaptive.getElementsByTagName("foreground").item(0) as org.w3c.dom.Element
            val background = adaptive.getElementsByTagName("background").item(0) as org.w3c.dom.Element
            assertThat(foreground.getAttributeNS(ns, "drawable")).isEqualTo("@drawable/launcher_icon_foreground")
            assertThat(background.getAttributeNS(ns, "drawable")).isEqualTo("@color/launcher_background")
        }
        val inset = xml("res/drawable/launcher_icon_foreground.xml")
        assertThat(inset.documentElement.tagName).isEqualTo("inset")
        assertThat(inset.documentElement.getAttributeNS(ns, "inset")).isEqualTo("18dp")
        val bitmap = inset.getElementsByTagName("bitmap").item(0) as org.w3c.dom.Element
        assertThat(bitmap.getAttributeNS(ns, "src")).isEqualTo("@drawable/user_app_icon")
        assertThat(bitmap.getAttributeNS(ns, "gravity")).isEqualTo("fill")
        val color = xml("res/values/launcher_icon_colors.xml").getElementsByTagName("color").item(0)
        assertThat(color.textContent.trim()).isEqualTo("#4E529E")
    }

    @Test fun pngPreservesUserSelectedBytes() {
        val file = File("src/main/res/drawable-nodpi/user_app_icon.png")
        assertThat(file.exists()).isTrue()
        val bytes = file.readBytes()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
        assertThat(hash).isEqualTo("EAE824602E571E34E47D5FB6B7290B26422E0061AB5E48B68DCDBA95785E1907")
    }
}
```

Run the focused test with the established offline/in-process Gradle flags. Before implementation, expect failure for missing manifest attributes/resources, not a Kotlin compilation error. Preserve the RED report.

- [x] **Step 2: Add the minimum resources and manifest attributes**

Copy the canonical PNG into the exact new drawable path after confirming it does not already exist. Use `apply_patch` for the XML and manifest edits.

`launcher_icon_foreground.xml`:

```xml
<inset xmlns:android="http://schemas.android.com/apk/res/android" android:inset="18dp">
    <bitmap android:src="@drawable/user_app_icon" android:gravity="fill" />
</inset>
```

`launcher_icon_colors.xml`:

```xml
<resources><color name="launcher_background">#4E529E</color></resources>
```

Both `mipmap-anydpi` files:

```xml
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
    android:src="@drawable/user_app_icon" android:gravity="fill" />
```

Both `mipmap-anydpi-v26` files:

```xml
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/launcher_background" />
    <foreground android:drawable="@drawable/launcher_icon_foreground" />
</adaptive-icon>
```

Add only `android:icon="@mipmap/ic_launcher"` and `android:roundIcon="@mipmap/ic_launcher_round"` to the existing application element. Preserve backup/network/service/permission configuration and all Kotlin application behavior.

- [x] **Step 3: Verify, self-review and commit explicit files**

Run:

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*.LauncherIconContractTest' --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestSources --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
& 'C:\Users\26530\AppData\Local\Android\Sdk\build-tools\36.0.0\aapt.exe' dump badging 'app\build\outputs\apk\debug\app-debug.apk'
git diff --check
```

Expect the selected-image contract to pass, full JVM tests to have zero failures/errors, AAPT/Gradle resource processing to succeed, and `MissingApplicationIcon` to disappear. Inspect APK resources/manifest to confirm the launcher resource is actually packaged. Record remaining warnings rather than claiming lint is entirely clean. No Android launcher runtime or installation claim without a connected device. Explicitly stage only this task's app/test files, commit, and release Gradle ownership for independent specification then quality review.

```powershell
git add -- app/src/main/AndroidManifest.xml app/src/main/res/drawable-nodpi/user_app_icon.png app/src/main/res/drawable/launcher_icon_foreground.xml app/src/main/res/values/launcher_icon_colors.xml app/src/main/res/mipmap-anydpi/ic_launcher.xml app/src/main/res/mipmap-anydpi/ic_launcher_round.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml app/src/test/java/com/novelradar/app/LauncherIconContractTest.kt
git commit -m "feat: use selected book launcher icon"
```

- [x] **Step 4: Independent reviews and artifact handoff**

After both reviews pass and root independently verifies the build/signature/hash, create a new stable test APK in `D:\NovelReader\build\deliverables` without overwriting the earlier `44d534b` package. Record the selected image hash and test-only/device-unverified status in `docs/test-apk-notes.md`. Mark this focused task complete only; the first-release and visual/accessibility plans remain incomplete.

Verification checkpoint (2026-10-05): application commit `eace59b` contains exactly the nine authorized files. Root directly read the RED report (two assertions failed, zero errors), the focused GREEN report (two passed), and the actual resource/manifest changes. Independent full milestone verification passed; an additional forced unit rerun executed all 26 tasks in 50 seconds and produced 394 tests across 42 suites with zero failures/errors/skips. A subsequent full milestone verification also exited 0. Lint has zero errors and seven warnings: four pre-existing (`OldTargetApi`, `ModifierParameter`, `UseKtx`, `UseTomlInstead`) and three explicitly retained design warnings (`ObsoleteSdkInt` for the planned v26 resources and two `MonochromeLauncherIcon` warnings). No warning was suppressed and no new monochrome artwork was added. AAPT and an independent ZIP-stream hash check confirmed the compiled manifest/adaptive/inset references and the byte-for-byte original PNG inside the APK. APK v2 signature verification passed with the existing Debug certificate. Specification review passed; quality review and stable artifact handoff remain pending at this checkpoint. A fresh ADB check found no device, so launcher rendering, installation, and Android runtime are not verified.

Completion evidence (2026-10-05): both independent specification and code-quality reviews passed with no outstanding issues. Root saved `D:\NovelReader\build\deliverables\NovelRadar-0.1.0-test-icon-eace59b.apk` without overwriting an existing different file. It is 34,229,500 bytes with SHA-256 `FF8B77BF36BC15832598C1EC2DB4FB08D5FE7DF22C9636DAF8E7D4366EB30B9B`; root reverified the stable copy's APK v2 signature and existing Debug certificate. The earlier `44d534b` artifact retains its original SHA-256. The new artifact, selected-image hash, and remaining device/release limits are recorded in `docs/test-apk-notes.md`. This closes only the selected launcher-icon task, not the full first-release or visual/accessibility plans.
