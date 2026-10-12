package com.novelradar.app.data.files

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicChapterFileStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun preservingWriteKeepsOldAndNewHashesSeparatelyReadableAtCanonicalPath() = runBlocking {
        val root = temporary.root.toPath()
        val store = AtomicChapterFileStore(root)
        val old = store.write("book", "source", "chapter", "旧混章正文")

        val attempt = runCatching { store.writePreservingPrevious("book", "source", "chapter", "单章正文", old.sha256.uppercase()) }
        assertThat(attempt.exceptionOrNull()).isNull()
        val repaired = attempt.getOrThrow()

        assertThat(repaired.relativePath).isEqualTo(old.relativePath)
        assertThat(store.read("book", "source", "chapter", old.sha256)).isEqualTo("旧混章正文")
        assertThat(store.read("book", "source", "chapter", repaired.sha256)).isEqualTo("单章正文")
        assertThat(store.read("book", "source", "chapter")).isEqualTo("单章正文")
        assertThat(Files.readString(root.resolve("books/book/source/chapter.legacy-${old.sha256}.txt")))
            .isEqualTo("旧混章正文")
    }

    @Test fun retryReusesExactBackupWhenPrimaryAlreadyContainsNewBody() = runBlocking {
        val root = temporary.root.toPath()
        val store = AtomicChapterFileStore(root)
        val old = store.write("book", "source", "chapter", "原始旧正文")
        assertThat(runCatching { store.writePreservingPrevious("book", "source", "chapter", "第一次修复", old.sha256) }.exceptionOrNull()).isNull()
        val backup = root.resolve("books/book/source/chapter.legacy-${old.sha256}.txt")
        val retainedTime = Files.getLastModifiedTime(backup)

        val attempt = runCatching { store.writePreservingPrevious("book", "source", "chapter", "重试修复正文", old.sha256) }
        assertThat(attempt.exceptionOrNull()).isNull()
        val retried = attempt.getOrThrow()

        assertThat(store.read("book", "source", "chapter", old.sha256)).isEqualTo("原始旧正文")
        assertThat(store.read("book", "source", "chapter", retried.sha256)).isEqualTo("重试修复正文")
        assertThat(Files.getLastModifiedTime(backup)).isEqualTo(retainedTime)
        Files.list(backup.parent).use { paths -> assertThat(paths.count()).isEqualTo(2L) }
    }

    @Test fun malformedHashesAndTraversalCannotSelectBackupPaths() = runBlocking {
        val store = AtomicChapterFileStore(temporary.root.toPath())
        val old = store.write("book", "source", "chapter", "旧正文")
        for (hash in listOf("", "a".repeat(63), "g".repeat(64), "../outside", "a".repeat(64) + "/../outside")) {
            assertThat(runCatching { store.writePreservingPrevious("book", "source", "chapter", "新正文", hash) }.exceptionOrNull())
                .isInstanceOf(IllegalArgumentException::class.java)
            assertThat(store.read("book", "source", "chapter", hash)).isNull()
        }
        assertThat(runCatching { store.writePreservingPrevious("../escape", "source", "chapter", "text", old.sha256) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { store.writePreservingPrevious("book", "..", "chapter", "text", old.sha256) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { store.writePreservingPrevious("book", "source", "../chapter", "text", old.sha256) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(store.read("book", "source", "chapter", old.sha256)).isEqualTo("旧正文")
    }

    @Test fun existingBackupWithDifferentHashIsNeverOverwritten() = runBlocking {
        val root = temporary.root.toPath()
        val store = AtomicChapterFileStore(root)
        val old = store.write("book", "source", "chapter", "旧正文")
        val backup = root.resolve("books/book/source/chapter.legacy-${old.sha256}.txt")
        Files.writeString(backup, "冲突备份")

        assertThat(runCatching { store.writePreservingPrevious("book", "source", "chapter", "新正文", old.sha256) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(Files.readString(backup)).isEqualTo("冲突备份")
        assertThat(store.read("book", "source", "chapter", old.sha256)).isEqualTo("旧正文")
        assertThat(Files.exists(root.resolve("books/book/source/chapter.tmp"))).isFalse()
    }

    @Test fun missingOrMismatchedPreviousBodyCannotBeSilentlyReplaced() = runBlocking {
        val store = AtomicChapterFileStore(temporary.root.toPath())
        assertThat(runCatching { store.writePreservingPrevious("book", "source", "chapter", "新正文", "0".repeat(64)) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        val old = store.write("book", "source", "chapter", "旧正文")
        assertThat(runCatching { store.writePreservingPrevious("book", "source", "chapter", "新正文", "0".repeat(64)) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(store.read("book", "source", "chapter", old.sha256)).isEqualTo("旧正文")
    }

    @Test fun failedReplacementAfterPrimaryRemovalStillRetainsReadableOriginalBackup() = runBlocking {
        val root = temporary.root.toPath()
        val initial = AtomicChapterFileStore(root)
        val old = initial.write("book", "source", "chapter", "旧正文")
        val failing = AtomicChapterFileStore(root, replace = { _, target -> Files.delete(target); throw IOException("replace failed") })

        assertThat(runCatching { failing.writePreservingPrevious("book", "source", "chapter", "新正文", old.sha256) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(initial.read("book", "source", "chapter", old.sha256)).isEqualTo("旧正文")
        assertThat(initial.read("book", "source", "chapter")).isNull()
        assertThat(Files.exists(root.resolve("books/book/source/chapter.tmp"))).isFalse()
    }

    @Test fun failedBackupWriteLeavesPrimaryReadableWithoutReplacingIt() = runBlocking {
        val root = temporary.root.toPath()
        val store = AtomicChapterFileStore(root)
        val old = store.write("book", "source", "chapter", "旧正文")
        Files.createDirectory(root.resolve("books/book/source/chapter.legacy-${old.sha256}.tmp"))

        assertThat(runCatching { store.writePreservingPrevious("book", "source", "chapter", "新正文", old.sha256) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(store.read("book", "source", "chapter", old.sha256)).isEqualTo("旧正文")
        assertThat(Files.exists(root.resolve("books/book/source/chapter.tmp"))).isFalse()
    }

    @Test fun backupReadOnlyUsesExactHashNamedCandidateAndRejectsMismatchOrMissing() = runBlocking {
        val root = temporary.root.toPath()
        val store = AtomicChapterFileStore(root)
        val old = store.write("book", "source", "chapter", "旧正文")
        val primary = root.resolve(old.relativePath)
        val backup = primary.resolveSibling("chapter.legacy-${old.sha256}.txt")
        Files.move(primary, backup)
        assertThat(store.read("book", "source", "chapter", old.sha256)).isEqualTo("旧正文")
        assertThat(store.read("book", "source", "chapter")).isNull()
        assertThat(store.read("book", "source", "chapter", "0".repeat(64))).isNull()
        Files.writeString(backup, "不匹配")
        assertThat(store.read("book", "source", "chapter", old.sha256)).isNull()
    }

    @Test fun oversizedHashMatchingBackupIsRejectedAndCannotBeReusedForPreservation() = runBlocking {
        val root = temporary.root.toPath()
        val store = AtomicChapterFileStore(root)
        val saved = store.write("book", "source", "chapter", "new")
        val oversized = ByteArray(8 * 1024 * 1024 + 1) { 'a'.code.toByte() }
        val hash = hash(oversized)
        Files.write(root.resolve(saved.relativePath).resolveSibling("chapter.legacy-$hash.txt"), oversized)

        assertThat(store.read("book", "source", "chapter", hash)).isNull()
        assertThat(runCatching { store.writePreservingPrevious("book", "source", "chapter", "repair", hash) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
        assertThat(store.read("book", "source", "chapter", saved.sha256)).isEqualTo("new")
    }

    @Test fun malformedUtf8HashMatchingBackupIsRejected() = runBlocking {
        val root = temporary.root.toPath()
        val store = AtomicChapterFileStore(root)
        val saved = store.write("book", "source", "chapter", "new")
        val malformed = byteArrayOf(0xc3.toByte(), 0x28)
        val hash = hash(malformed)
        Files.write(root.resolve(saved.relativePath).resolveSibling("chapter.legacy-$hash.txt"), malformed)

        assertThat(store.read("book", "source", "chapter", hash)).isNull()
        assertThat(runCatching { store.writePreservingPrevious("book", "source", "chapter", "repair", hash) }.exceptionOrNull())
            .isInstanceOf(IOException::class.java)
    }

    @Test fun explicitBookDeletionRemovesItsBackupsAndPreservesOtherBooksBackups() = runBlocking {
        val store = AtomicChapterFileStore(temporary.root.toPath())
        val oldA = store.write("book-a", "source", "chapter", "A-old")
        val oldB = store.write("book-b", "source", "chapter", "B-old")
        assertThat(runCatching { store.writePreservingPrevious("book-a", "source", "chapter", "A-new", oldA.sha256) }.exceptionOrNull()).isNull()
        val attempt = runCatching { store.writePreservingPrevious("book-b", "source", "chapter", "B-new", oldB.sha256) }
        assertThat(attempt.exceptionOrNull()).isNull()
        val newB = attempt.getOrThrow()

        store.deleteBook("book-a")

        assertThat(store.read("book-a", "source", "chapter", oldA.sha256)).isNull()
        assertThat(store.read("book-b", "source", "chapter", oldB.sha256)).isEqualTo("B-old")
        assertThat(store.read("book-b", "source", "chapter", newB.sha256)).isEqualTo("B-new")
        assertThat(Files.exists(temporary.root.toPath().resolve("books/book-a"))).isFalse()
    }

    @Test fun unsupportedStoreFailsClosedInsteadOfCallingDestructiveWrite() = runBlocking {
        var writes = 0
        val store = object : ChapterFileStore {
            override suspend fun write(bookId: String, sourceId: String, chapterId: String, content: String): StoredChapterFile {
                writes++; error("Legacy write must not overwrite")
            }
            override suspend fun read(bookId: String, sourceId: String, chapterId: String, expectedSha256: String?) = "old"
            override suspend fun deleteBook(bookId: String) = Unit
        }

        val failure = runCatching { store.writePreservingPrevious("book", "source", "chapter", "new", "a".repeat(64)) }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(failure!!.message).isEqualTo("Legacy-preserving write is unavailable")
        assertThat(writes).isEqualTo(0)
    }

    @Test fun hashMatchingMalformedUtf8IsNotReadableChapterText() = runBlocking {
        val root = temporary.root.toPath()
        val store = AtomicChapterFileStore(root)
        val saved = store.write("book", "source", "chapter", "text")
        val malformed = byteArrayOf(0xc3.toByte(), 0x28)
        Files.write(root.resolve(saved.relativePath), malformed)
        val hash = java.security.MessageDigest.getInstance("SHA-256").digest(malformed)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

        assertThat(store.read("book", "source", "chapter", hash)).isNull()
    }
    @Test fun oversizedCorruptFileIsRejectedBeforeReadingAllBytes() = runBlocking {
        val root = temporary.root.toPath()
        val store = AtomicChapterFileStore(root)
        val saved = store.write("book", "source", "chapter", "text")
        java.io.RandomAccessFile(root.resolve(saved.relativePath).toFile(), "rw").use { it.setLength(8L * 1024 * 1024 + 1) }
        assertThat(store.read("book", "source", "chapter")).isNull()
    }

    @Test fun utf8RoundTripAndDeterministicPathWithHashValidation() = runBlocking {
        val store = AtomicChapterFileStore(temporary.root.toPath())

        val saved = store.write("book-1", "source_1", "chapter-1", "第一段\n第二段")

        assertThat(saved.relativePath).isEqualTo("books/book-1/source_1/chapter-1.txt")
        assertThat(saved.byteCount).isEqualTo("第一段\n第二段".toByteArray(Charsets.UTF_8).size.toLong())
        assertThat(saved.sha256).hasLength(64)
        assertThat(store.read("book-1", "source_1", "chapter-1", saved.sha256)).isEqualTo("第一段\n第二段")
        assertThat(store.read("book-1", "source_1", "chapter-1", "0".repeat(64))).isNull()
    }

    @Test fun failedAtomicReplacementLeavesPreviousContentAndRemovesTemporaryFile() = runBlocking {
        val root = temporary.root.toPath()
        val initial = AtomicChapterFileStore(root)
        val old = initial.write("book-1", "source-1", "chapter-1", "旧内容")
        val failing = AtomicChapterFileStore(root, replace = { _, _ -> throw IOException("interrupted") })

        val failure = runCatching { failing.write("book-1", "source-1", "chapter-1", "新内容") }.exceptionOrNull()

        assertThat(failure).isInstanceOf(IOException::class.java)
        assertThat(initial.read("book-1", "source-1", "chapter-1", old.sha256)).isEqualTo("旧内容")
        assertThat(Files.exists(root.resolve("books/book-1/source-1/chapter-1.tmp"))).isFalse()
    }

    @Test fun corruptedContentFailsValidation() = runBlocking {
        val root = temporary.root.toPath()
        val store = AtomicChapterFileStore(root)
        val saved = store.write("book-1", "source-1", "chapter-1", "原文")
        Files.writeString(root.resolve(saved.relativePath), "篡改后的正文")

        assertThat(store.read("book-1", "source-1", "chapter-1", saved.sha256)).isNull()
    }

    @Test fun invalidIdsCannotEscapeBookDirectory() = runBlocking {
        val store = AtomicChapterFileStore(temporary.root.toPath())

        assertThat(runCatching { store.write("../other", "source", "chapter", "text") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { store.write("book", "..", "chapter", "text") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { store.read("book", "source", "../chapter") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun importerCompatibleDottedIdsAreAllowedButLengthIsBounded() = runBlocking {
        val store = AtomicChapterFileStore(temporary.root.toPath())

        val saved = store.write("book.v2", "source.v2", "chapter.v2", "正文")

        assertThat(saved.relativePath).isEqualTo("books/book.v2/source.v2/chapter.v2.txt")
        assertThat(runCatching { store.write("a".repeat(129), "source", "chapter", "text") }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test fun deletingOneBookDoesNotTouchAnother() = runBlocking {
        val store = AtomicChapterFileStore(temporary.root.toPath())
        store.write("book-a", "source", "chapter", "A")
        store.write("book-b", "source", "chapter", "B")

        store.deleteBook("book-a")

        assertThat(store.read("book-a", "source", "chapter")).isNull()
        assertThat(store.read("book-b", "source", "chapter")).isEqualTo("B")
    }

    private fun hash(bytes: ByteArray): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
