# Paragraph first-line indentation repair

The user approved on 2026-10-15: both reading modes indent each real paragraph's first line by two Chinese-character widths; wrapped lines and a paragraph continued on another page/chunk do not indent again. Only presentation changes: original chapter bytes, UTF-16 positions, character counts and saved bookmarks stay unchanged. Rebuild and deliver a separately named debug test APK after verification.

Diagnosis: ChapterCleaner removes line-start HTML whitespace, while ReaderScreen supplies only font size/line height and both renderers display raw substrings. Writing spaces into cached content would change hashes and offsets, so it is rejected. A global first-line style alone would incorrectly indent a paragraph continued at a chunk/page boundary.

Use a shared AnnotatedString formatter for an original UTF-16 slice. Preserve its text exactly, add paragraph TextIndent(firstLine = 2.em, restLine = 0.em) only where the original paragraph starts, and use zero first-line indent for a continued paragraph. Use the same formatted candidate for paging measurement and final drawing. Keep titles, palette, existing gestures, bounded window, gate and settings unchanged. No settings/schema/source/cache migration or network behavior changes.

Verification covers beginning-of-chapter, multiple paragraphs, empty/blank lines, continuation slices, supplementary Unicode, exact original text and ranges; compile actual Compose layout regressions for both modes. Run full unit tests, lint, APK build and Android-test source compilation, independently review specification then quality, verify signature/icon/hash, and preserve every previous APK. No device-runtime claim without an attached target.
