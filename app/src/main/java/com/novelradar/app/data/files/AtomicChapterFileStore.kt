package com.novelradar.app.data.files

import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Stores chapter text inside the app's private files directory. */
class AtomicChapterFileStore(
    private val filesDir: Path,
    private val replace: (Path, Path) -> Unit = ::replaceFile,
) : ChapterFileStore {
    private val lock = Mutex()

    override suspend fun write(bookId: String, sourceId: String, chapterId: String, content: String): StoredChapterFile =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val target = chapterPath(bookId, sourceId, chapterId)
                val temporary = target.resolveSibling("$chapterId.tmp")
                val bytes = content.toByteArray(Charsets.UTF_8)
                Files.createDirectories(target.parent)
                try {
                    FileOutputStream(temporary.toFile()).use { stream ->
                        stream.write(bytes)
                        stream.flush()
                        stream.fd.sync()
                    }
                    replace(temporary, target)
                } finally {
                    Files.deleteIfExists(temporary)
                }
                StoredChapterFile(relativePath(bookId, sourceId, chapterId), sha256(bytes), bytes.size.toLong())
            }
        }

    override suspend fun read(bookId: String, sourceId: String, chapterId: String, expectedSha256: String?): String? =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val target = chapterPath(bookId, sourceId, chapterId)
                if (!Files.isRegularFile(target)) return@withContext null
                val bytes = Files.readAllBytes(target)
                if (expectedSha256 != null && !sha256(bytes).equals(expectedSha256, ignoreCase = true)) {
                    return@withContext null
                }
                bytes.toString(Charsets.UTF_8)
            }
        }

    override suspend fun deleteBook(bookId: String) = lock.withLock {
        withContext(Dispatchers.IO) {
            val book = filesDir.resolve("books").resolve(validId(bookId))
            if (Files.exists(book)) Files.walkFileTree(book, object : SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    Files.delete(file)
                    return FileVisitResult.CONTINUE
                }

                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
                    if (error != null) throw error
                    Files.delete(dir)
                    return FileVisitResult.CONTINUE
                }
            })
        }
    }

    private fun chapterPath(bookId: String, sourceId: String, chapterId: String): Path = filesDir
        .resolve("books")
        .resolve(validId(bookId))
        .resolve(validId(sourceId))
        .resolve("${validId(chapterId)}.txt")

    private fun relativePath(bookId: String, sourceId: String, chapterId: String): String =
        "books/${validId(bookId)}/${validId(sourceId)}/${validId(chapterId)}.txt"

    private fun validId(id: String): String {
        require(id.length in 1..128 && id != "." && ".." !in id && ID_PATTERN.matches(id)) {
            "Invalid local chapter path ID"
        }
        return id
    }

    private companion object {
        val ID_PATTERN = Regex("[A-Za-z0-9._-]+")
    }
}

private fun replaceFile(source: Path, target: Path) {
    try {
        Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source, target, REPLACE_EXISTING)
    } catch (_: UnsupportedOperationException) {
        Files.move(source, target, REPLACE_EXISTING)
    }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
