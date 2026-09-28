# NovelRadar QuickJS-KT native security revision

This directory records the one-file JNI patch behind the independently named Maven
artifacts in `vendor/maven/`. The unmodified upstream 1.0.15 JNI bridge treats a
JavaScript `Error.name` as a Java class name, calls `FindClass`, and invokes a
`String` constructor **before** checking whether the constructed object is a
`Throwable`. This is reachable through returned errors, thrown errors, and
Promise rejection tracking, even when no host bindings are defined. The patch
deletes that dynamic class/constructor path. Every JavaScript `Error` now maps
to the library's fixed `com.dokar.quickjs.QuickJsException`, retaining the JS
name/message and stack. Non-error values and ordinary JNI bindings are unchanged.

## Fixed inputs

| Input | Revision / SHA-256 |
| --- | --- |
| [dokar3/quickjs-kt](https://github.com/dokar3/quickjs-kt/releases/tag/v1.0.15) | tag `v1.0.15`, commit `a081755bcdb18ee6afa7ffee25b8fa976f06d31f` |
| [bellard/quickjs](https://github.com/bellard/quickjs) submodule | `04be246001599f5995fa2f2d8c91a0f198d3f34c` |
| [eteran/c-vector](https://github.com/eteran/c-vector) submodule | `774773d4cb1e66dd736e90e7f482d4f22af464c6` |
| Google NDK r28c Windows archive | `6bec98ac2354d8a919760889a1a41d020132e5e8cfa1b1fe51610a72c36a466b` |
| Google CMake 3.22.1 Windows archive (includes Ninja) | `c9a9d568452a20cf27d703cb21ef9529dd67bda6be048c4d4b884acb3ac3a2b8` |
| Zig 0.12.0 Windows x64 archive | `2199eb4c2000ddb1fba85ba78f1fcf9c1fb8b3e57658f6a627a8e513131893f5` |
| Upstream `quickjs-kt-android-1.0.15.aar` | `406c3efb80d5fa3495df4a7f2663be8cc30c974fe5c2368effa929de77cf0ed8` |
| Upstream `quickjs-kt-jvm-1.0.15.jar` | `c8a8d0b47e2c3cd6fb21ea618204ba0a04027e09e41dc036c0f60518823f3d6d` |

The URLs and checksums of every downloaded build/test input are in
[`Build-QuickJs.ps1`](../../tools/quickjs/Build-QuickJs.ps1). The upstream AAR
and JAR keep their original Kotlin classes/resources, Android metadata, and
POM dependencies. The published Gradle metadata marker is omitted because this
local repository provides POMs rather than `.module` files. Only native archive
entries change: the Android AAR contains freshly built `arm64-v8a`,
`armeabi-v7a`, `x86`, and `x86_64` libraries; the JVM JAR contains only the
freshly built `windows_x64/libquickjs.dll`. Unpatched Linux/macOS libraries
from the upstream JVM JAR are intentionally absent. The Windows JAR must not be
used for JVM tests on other hosts.

## Maven coordinates

Add `vendor/maven` as a local Maven repository for the consuming build, then
use the platform-specific coordinate:

```text
com.novelradar.vendor:quickjs-kt-android:1.0.15-nr1
com.novelradar.vendor:quickjs-kt-jvm:1.0.15-nr1
```

Do not combine these with the original `io.github.dokar3` artifact or use a
native-library `pickFirst`: that could select an unpatched `libquickjs`.
The SHA-256s of the four published AAR/JAR/POM files are in
[`artifacts.sha256`](artifacts.sha256).

## Rebuild and test (Windows PowerShell)

With Git, PowerShell 7, `curl.exe`, `tar.exe`, and a Windows JDK 17 installed:

```powershell
$jdk = 'C:\Users\26530\.gradle\jdks\eclipse_adoptium-17-amd64-windows.2'
& .\tools\quickjs\Build-QuickJs.ps1 -JdkHome $jdk
& .\tools\quickjs\Verify-QuickJs.ps1
& .\tools\quickjs\Test-QuickJs.ps1 -JdkHome $jdk
```

The build script downloads fixed official inputs into the ignored
`.tools/quickjs-native/` cache, checks hashes and submodule commits, applies
[`0001-fix-js-error-mapping.patch`](patches/0001-fix-js-error-mapping.patch),
and builds five shared libraries from the upstream CMake target. It does not
run the root Gradle build, mutate Gradle caches, or install tools into the
user's Android SDK. Re-running it with the same source and tools produced the
same artifact SHA-256s.

The standalone JVM test first proves the original library calls an in-memory
sentinel constructor from JS-controlled Error names (throw, return, unhandled
and subsequently handled Promise rejection). It then checks zero constructor
calls with the patched JAR, fixed exception mapping, Unicode/emoji/embedded-NUL
round trips, error stack, absent default host I/O bindings, a 300 ms infinite
loop deadline, a 2 MiB native memory cap, and close behavior. The archive
verifier confirms unchanged non-native content/dependencies, exact native
entry sets, ABI/JNI exports, and 16 KiB ELF load alignment. The Android
libraries were cross-compiled and statically inspected here; Android runtime
behavior still requires an emulator or device test. The current upstream
low-memory exception may have a null message, so that test checks rejection
type rather than exact text.

Licenses are retained verbatim in [`licenses/`](licenses/): QuickJS-KT
Apache-2.0, QuickJS and c-vector MIT, and winpthreads' COPYING notice.
