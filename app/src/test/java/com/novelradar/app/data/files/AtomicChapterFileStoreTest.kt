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
}
