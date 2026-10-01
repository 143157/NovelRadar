# Android device verification notes

Status recorded on 2026-10-01: Android instrumentation and APK installation have **not** been verified.

The build and unit-test SDK is separate from the device-test SDK. An API 26 Google APIs x86_64 image (revision 16), Emulator 37.1.11, and platform-tools 37.0.1 were installed under `D:\NovelReader\.tools\android-test-sdk`; the project-specific AVD is `NovelRadarApi26` under `D:\NovelReader\.tools\android-avd`.

`emulator -accel-check` reported that the Android Emulator hypervisor driver is not installed. No connected physical device was present. Software-only startup was attempted with `-accel off`, headless rendering, snapshots disabled, and kernel output enabled. The emulator stayed `offline` in `adb devices -l`; no kernel boot output or `sys.boot_completed` result was obtained. A second diagnostic configuration disabled Vulkan and used one virtual CPU; a controlled foreground launch also made no boot progress. These observations do not establish a definitive cause beyond the unavailable hardware accelerator.

Only this task's emulator processes were stopped. The AVD, image, and diagnostic logs were preserved. No hypervisor driver was installed, no Windows feature or boot setting was changed, and no reboot was requested. Logs are in `D:\NovelReader\.tools\android-avd\NovelRadarApi26.stdout*.log` and `NovelRadarApi26.stderr*.log`.

Remaining device checks require a bootable emulator or an explicitly connected Android device. They include real DataStore disk persistence, measured pagination and scroll restoration, Activity recreation, Room migrations, WorkManager downloads, offline restart, and release APK installation. Compiling Android test sources is not a substitute for executing them.
