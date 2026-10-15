# Test APK artifacts

## Current paragraph-indentation repair test build

Packaged 2026-10-15 (Asia/Shanghai) from application commit `b15b995da1cdb00683462b0950b71e4e92ad2974`. Separately named debug test APK; not Release signing or phone acceptance.

- Stable path: `D:\NovelReader\build\deliverables\NovelRadar-0.1.0-test-indent-b15b995.apk`.
- Size: 34,229,500 bytes; SHA-256 `8C0487F1D0903D8B566B15F95D1B765EF3AB50870A28CC72FF203C76CD82154C`.
- Package `com.novelradar.app`, version `0.1.0`, code `1`; minimum API 26, target API 36; all four existing native ABIs retained.
- Original Android Debug RSA-2048 certificate; v2 signature verified on output and saved copy. Certificate SHA-256 `4AF3DFFD643A15889D42B27D56A747DA7C9124CEDD6D71FF75B54030D78A2289`.

Both reader modes indent genuine paragraph first lines by two em units, with zero indent for wrapped lines and initial continuation paragraphs. Ordinary slices use one native text layout; a continuation uses at most two original-range fragments, consuming the first separator once at their layout boundary. Real blank lines and empty tails remain. Scroll maps composite layout positions to original UTF-16 offsets; paging measures and draws the same model and rechecks final-cut height. Stored source text, cache content, character counts, persisted bookmarks, titles, icon and palette are unchanged.

Quality review rejected the initial `8986c37` candidate for possible extra blank lines from independent newline-containing style ranges; that candidate was not copied or delivered. The corrected `b15b995` passed fresh specification review and subsequent quality re-review with no outstanding findings. Saved correction evidence has RED 15 tests / 10 explicit assertion failures / zero errors or skips, then GREEN 15 / zero failures, errors or skips.

Root independently repeated Full4, forced `:app:testDebugUnitTest --rerun-tasks` (exit 0, one minute six seconds, all 26 tasks executed), then repeated Full4 successfully in seven seconds. Fresh XML: **593 tests / 52 suites / zero failures, errors or skips**. Actual SARIF retains seven existing warnings and zero errors, with no suppressions. Four added native-layout regressions cover newline line counts, continuation/blank lines, original composite offsets and final page height; compiled, not device-executed. Final ADB inventory had no attached target.

DEX inspection confirms corrected fragment/layout code. The selected icon remains 1,401,871 bytes, SHA-256 `EAE824602E571E34E47D5FB6B7290B26422E0061AB5E48B68DCDBA95785E1907`. The stable file was copied with overwrite disabled and its saved hash/signature verified. All three earlier APK hashes remain unchanged. No install, uninstall, deletion of user data, merge, push or worktree cleanup was performed.

Phone checks: cover-install without uninstalling; test both modes' two-character paragraph starts, wrapped/continued lines, real blank lines, final page lines, chapter titles, and position/progress after font changes, mode changes and reopening. Actual phone layout remains user acceptance work. This display-only fix makes no new promise about live-source speed or universal search coverage. Frontend optimization is a separate discussion requested after delivery, not part of this APK.

## Previous continuous-reading repair test build

Packaged 2026-10-14 (Asia/Shanghai) from application commit `adabb7c3903d5eba35c683b24c9d81daeefb4042`. This is the repaired debug test APK requested after the user's second-package feedback, not a Release or phone-acceptance claim.

### Artifact

- Stable path: `D:\NovelReader\build\deliverables\NovelRadar-0.1.0-test-repair-adabb7c.apk`.
- Size: 34,229,500 bytes.
- SHA-256: `804262763683933C3252EB8DC384D2C632E091717867779904DA9553E908FC91`.
- Package `com.novelradar.app`; version `0.1.0`, version code `1`; minimum API 26 (Android 8.0), target API 36.
- Native ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- Existing Android Debug RSA-2048 certificate; APK v2 signature verified on both build output and saved copy.
- Certificate SHA-256: `4AF3DFFD643A15889D42B27D56A747DA7C9124CEDD6D71FF75B54030D78A2289`.

The saved file was copied with overwrite disabled only after cumulative specification and quality reviews passed. Its SHA-256 matches the verified output. The saved APK's selected icon PNG remains exactly 1,401,871 bytes with SHA-256 `EAE824602E571E34E47D5FB6B7290B26422E0061AB5E48B68DCDBA95785E1907`. The canonical artwork, drawable copy, two earlier stable APKs, package/version configuration and palette remain unchanged. APK DEX inspection confirms the new continuous-reader, real catalog-position and legacy-preserving-write code is present. No install, uninstall, user-data deletion, merge, push or worktree cleanup was performed.

### Included repair and automated evidence

Chapter fetching stops at real next-chapter boundaries while retaining legitimate same-chapter pagination. Existing mixed-body files and raw bookmarks remain recoverable rather than being silently erased or clamped. Scrolling and paging render individual chapter titles and actual chapter-local positions; the catalog follows that position. Unicode per-chapter read/total characters and percentage appear with catalog position in one naturally sized footer. Detail and shelf displays observe the same-source persisted location and use actual catalog ordinals rather than stale raw indices.

Upcoming verified requests are bounded, reused and replenished according to the existing zero/one/two-chapter and metered-network settings. Preparation alone does not move progress. This removes known repeated work; it does not promise a fixed speed for every live source.

Root independently repeated Full4 and the actual whole unit task, then performed final `:app:testDebugUnitTest --rerun-tasks`: exit 0, one minute 29 seconds, all 26 tasks executed. Fresh XML contains **578 tests / 51 suites / zero failures, errors or skips**. Post-force Full4 also exited 0 in seven seconds. Lint retains seven existing warnings and zero errors, with no new warnings or suppression. Cumulative independent specification review and subsequent quality review both passed with no outstanding required findings.

Actual source SQL plus generated schemas pass the in-memory shelf-position and v4-to-v5 migration checks, including raw offsets, hashes, source choices and download history. Historical schemas 1–4 are unchanged. Android-test sources compile, including both reading modes and enlarged footer layout, but were not device-executed. Root's final ADB inventory was empty.

### Phone checklist and limitations

1. Use an in-place update with the same package/signing identity; do not uninstall first if you want to preserve the bookshelf, bookmarks and local chapters.
2. In both scrolling and paging, cross several chapters and check each title, catalog highlight and chapter-local character progress. Include short/empty chapters, backward navigation and failure/retry.
3. Reopen, rotate and change font size/mode; confirm the same character position remains. Check both hidden and opened controls with larger text.
4. Test upcoming-cache settings and offline reading of newly verified cached chapters. Check the detail directory and shelf after leaving the reader.
5. An old mixed-body bookmark may show “阅读位置需要确认”. Its original record is retained: choose the real chapter or explicitly confirm a suitable verified position. Do not treat an offline legacy fallback as corrected chapter progress.

Actual phone layout, gesture timing, Android persistence/scheduling, installation and real-source latency still need user testing. Multi-source search queries enabled accessible sources; this package is not evidence of universal internet coverage. Unrelated first-release features and Release signing remain outside this repair delivery.

## Previous selected-icon build

Packaged 2026-10-05 (Asia/Shanghai) from application commit `eace59ba2b8f482822b93da0946c3f07f82313df`. This debug-signed intermediate test package includes the reviewed detail refresh/identity fixes and the user's selected launcher icon. It is not the planned locally signed Release APK or final first-release acceptance.

### Artifact

- Stable path: `D:\NovelReader\build\deliverables\NovelRadar-0.1.0-test-icon-eace59b.apk`.
- Size: 34,229,500 bytes.
- SHA-256: `FF8B77BF36BC15832598C1EC2DB4FB08D5FE7DF22C9636DAF8E7D4366EB30B9B`.
- Package: `com.novelradar.app`; version name `0.1.0`, version code `1`.
- Minimum Android: API 26 (Android 8.0); target API 36.
- Native ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- Signature: APK Signature Scheme v2 verified with the existing Android Debug RSA-2048 certificate.
- Certificate SHA-256: `4AF3DFFD643A15889D42B27D56A747DA7C9124CEDD6D71FF75B54030D78A2289`.

The stable copy was made with overwrite disabled after validating the build output, and its hash and signature were independently checked. The earlier `44d534b` artifact below remains unchanged. No installation, uninstall, or deletion of user data was performed.

### Automated evidence and selected artwork

Root independently ran the four milestone tasks shown below successfully. A separate forced `:app:testDebugUnitTest --rerun-tasks` run executed all 26 tasks in 50 seconds and produced 394 tests across 42 suites with zero failures/errors/skips; a subsequent four-task verification also exited 0. Android-test sources compiled but did not run. Separate icon specification and code-quality reviews passed with no outstanding issues.

Lint has zero errors and seven warnings: four existing (`OldTargetApi`, `ModifierParameter`, `UseKtx`, `UseTomlInstead`), the planned v26-resource redundancy warning (`ObsoleteSdkInt`), and two `MonochromeLauncherIcon` warnings. The latter reflect the explicit choice not to redraw a monochrome variant. No warnings were suppressed.

The canonical selected PNG is `docs/assets/user-app-icon.png`, 1254 × 1254 pixels and 1,401,871 bytes, SHA-256 `EAE824602E571E34E47D5FB6B7290B26422E0061AB5E48B68DCDBA95785E1907`. Both the drawable copy and the APK ZIP entry `res/drawable-nodpi-v4/user_app_icon.png` retain those exact bytes. Independent AAPT inspection verified the compiled manifest, regular/round adaptive resources, purple background, inset foreground, and PNG reference. The application name, package, version, in-app palette, and reader/download behavior were not changed by the icon task.

### Limits and next work

A fresh ADB check found no target. Phone installation, first launch, actual launcher rendering, Android Room/DataStore persistence, notifications, and foreground scheduling remain unverified. The APK is not proof that every live source is reachable. Multi-source search queries enabled, accessible sources; it does not scan the entire internet or bypass access protection.

Remaining approved first-release work includes safe cross-source fallback, cumulative cache limits, end-to-end offline tests, release security/accessibility hardening, local Release signing, and device acceptance. Detail refresh, cover/introduction completion, and the selected launcher-icon task are now reviewed and complete. The planned Release may use a different local signing key and may not update this debug build in place; preserve needed bookshelf/progress/local chapters before any uninstall.

## Earlier download-management build

Packaged 2026-10-04 (Asia/Shanghai) from application commit `44d534b509356135caefff8e15f1b9e939a3bc38`. This is an intermediate debug-signed test artifact, not the planned locally signed Release APK and not final first-release acceptance.

### Artifact

- Stable path: `D:\NovelReader\build\deliverables\NovelRadar-0.1.0-test-44d534b.apk`
- Size: 32,350,605 bytes.
- SHA-256: `306D187C186FCEE713C5F7E8530D348A9A05700B96E425CBF88F254B4C8D8DEB`.
- Package: `com.novelradar.app`; version name `0.1.0`, version code `1`.
- Minimum Android: API 26 (Android 8.0); target API 36.
- Native ABIs: `arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`.
- Signature: APK Signature Scheme v2 verified with the Android Debug RSA-2048 certificate.
- Certificate SHA-256: `4AF3DFFD643A15889D42B27D56A747DA7C9124CEDD6D71FF75B54030D78A2289`.

The stable copy was created without overwriting different existing contents; its hash matched the verified build output. Later builds may change `app/build/outputs/apk/debug/app-debug.apk`, but do not change this versioned copy.

### Automated evidence

Independent verification ran:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:compileDebugAndroidTestSources --offline --no-daemon '-Pkotlin.incremental=false' '-Pkotlin.compiler.execution.strategy=in-process' --console=plain
```

The command exited 0. Actual final reports contain 348 unit tests across 40 suites, zero failures/errors, and six existing lint warnings with zero errors. Unit tests cover download task mapping, deliberate network choice, notification handoff, fixed pending targets, stale task actions, source-specific opening, durable coordination, retry, and deletion barriers. Root directly inspected the regression RED reports before the completion/stop fix and the focused GREEN reports afterward. Separate specification and code-quality reviews passed, including re-review of that fix.

`apksigner verify --verbose --print-certs` passed and `aapt dump badging` confirmed the metadata above on 2026-10-03 at approximately 23:59 (Asia/Shanghai). The six newly added download Compose tests compiled; they did not run.

### Device and feature limits

A fresh `adb devices -l` check found no target. Installation, first launch, real notification permission/recreation, foreground scheduling, Android Room/DataStore persistence, and phone layout are not verified. Compiling instrumented tests is not a substitute for running them. Live book sources can fail or change; this APK is not evidence that every built-in source is currently reachable. Multi-source search means querying enabled, accessible sources, not scanning the entire internet or bypassing site protection.

At this earlier packaging checkpoint, the remaining approved first-release work included detail refresh/cover/introduction completion, safe cross-source fallback, cumulative cache limits, end-to-end offline tests, release security/accessibility hardening, local Release signing, and device acceptance. Detail completion is now included in the newer selected-icon build above. See the existing implementation plans and `device-verification-notes.md` for the still-pending work.

To try it, transfer this APK to an intended Android 8.0+ device and install it there. It has not been installed automatically. Android may ask you to allow installation from the selected file/browser app. The planned Release uses a separate local signing key and may not update this debug build in place; do not uninstall without preserving needed data. Uninstalling can remove the bookshelf, progress, and local chapters. No uninstall or user-data deletion was performed during packaging.
