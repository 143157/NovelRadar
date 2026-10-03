# Download-management test APK

Packaged 2026-10-04 (Asia/Shanghai) from application commit `44d534b509356135caefff8e15f1b9e939a3bc38`. This is an intermediate debug-signed test artifact, not the planned locally signed Release APK and not final first-release acceptance.

## Artifact

- Stable path: `D:\NovelReader\build\deliverables\NovelRadar-0.1.0-test-44d534b.apk`
- Size: 32,350,605 bytes.
- SHA-256: `306D187C186FCEE713C5F7E8530D348A9A05700B96E425CBF88F254B4C8D8DEB`.
- Package: `com.novelradar.app`; version name `0.1.0`, version code `1`.
- Minimum Android: API 26 (Android 8.0); target API 36.
- Native ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- Signature: APK Signature Scheme v2 verified with the Android Debug RSA-2048 certificate.
- Certificate SHA-256: `4AF3DFFD643A15889D42B27D56A747DA7C9124CEDD6D71FF75B54030D78A2289`.

The stable copy was created without overwriting different existing contents; its hash matched the verified build output. Later builds may change `app/build/outputs/apk/debug/app-debug.apk`, but do not change this versioned copy.

## Automated evidence

Independent verification ran:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestSources --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

The command exited 0. Actual final reports contain 348 unit tests across 40 suites, zero failures/errors, and six existing lint warnings with zero errors. Unit tests cover download task mapping, deliberate network choice, notification handoff, fixed pending targets, stale task actions, source-specific opening, durable coordination, retry, and deletion barriers. Root directly inspected the regression RED reports before the completion/stop fix and the focused GREEN reports afterward. Separate specification and code-quality reviews passed, including re-review of that fix.

`apksigner verify --verbose --print-certs` passed and `aapt dump badging` confirmed the metadata above on 2026-10-03 at approximately 23:59 (Asia/Shanghai). The six newly added download Compose tests compiled; they did not run.

## Device and feature limits

A fresh `adb devices -l` check found no target. Installation, first launch, real notification permission/recreation, foreground scheduling, Android Room/DataStore persistence, and phone layout are not verified. Compiling instrumented tests is not a substitute for running them. Live book sources can fail or change; this APK is not evidence that every built-in source is currently reachable. Multi-source search means querying enabled, accessible sources, not scanning the entire internet or bypassing site protection.

The remaining approved first-release work includes detail refresh/cover/introduction completion, safe cross-source fallback, cumulative cache limits, end-to-end offline tests, release security/accessibility hardening, local Release signing, and device acceptance. See the existing implementation plans and `device-verification-notes.md`.

To try it, transfer this APK to an intended Android 8.0+ device and install it there. It has not been installed automatically. Android may ask you to allow installation from the selected file/browser app. The planned Release uses a separate local signing key and may not update this debug build in place; do not uninstall without preserving needed data. Uninstalling can remove the bookshelf, progress, and local chapters. No uninstall or user-data deletion was performed during packaging.
