package com.novelradar.app.data.files

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalChapterCleanerTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun deletesOnlySelectedBooksFilesThenClearsItsMetadata() = runTest {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        files.write("one", "a", "1", "cached")
        files.write("one", "b", "2", "downloaded")
        files.write("two", "a", "1", "keep")
        val cleared = mutableListOf<String>()
        val cleaner = LocalChapterCleaner(files) { cleared += it }
        cleaner.delete("one")
        assertThat(files.read("one", "a", "1")).isNull()
        assertThat(files.read("one", "b", "2")).isNull()
        assertThat(files.read("two", "a", "1")).isEqualTo("keep")
        assertThat(cleared).containsExactly("one")
    }
    @Test fun invalidTargetDoesNotClearMetadata() = runTest {
        val files = AtomicChapterFileStore(temporary.root.toPath())
        val cleared = mutableListOf<String>()
        val cleaner = LocalChapterCleaner(files) { cleared += it }
        assertThat(runCatching { cleaner.delete("../two") }.isFailure).isTrue()
        assertThat(cleared).isEmpty()
    }
}
