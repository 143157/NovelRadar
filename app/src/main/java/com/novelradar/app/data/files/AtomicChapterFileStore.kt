package com.novelradar.app.data.files

import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
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
                writeLocked(bookId, sourceId, chapterId, target, content)
            }
        }

    override suspend fun writePreservingPrevious(bookId: String, sourceId: String, chapterId: String,
        content: String, previousSha256: String): StoredChapterFile = lock.withLock {
        withContext(Dispatchers.IO) {
            val target = chapterPath(bookId, sourceId, chapterId)
            require(HASH_PATTERN.matches(previousSha256)) { "Invalid previous chapter hash" }
            val hash = previousSha256.lowercase(java.util.Locale.ROOT)
            val backup = legacyPath(target, chapterId, hash)
            if (Files.exists(backup)) {
                if (readCandidate(backup, hash) == null) throw IOException("Retained legacy chapter is invalid")
            } else {
                val previous = readCandidate(target, hash) ?: throw IOException("Previous chapter is unavailable")
                val temporary = backup.resolveSibling("$chapterId.legacy-$hash.tmp")
                try {
                    writeSynced(temporary, previous.bytes)
                    // No replacement: a colliding backup must never be overwritten.
                    Files.move(temporary, backup)
                } finally {
                    Files.deleteIfExists(temporary)
                }
            }
            writeLocked(bookId, sourceId, chapterId, target, content)
        }
    }

    override suspend fun read(bookId: String, sourceId: String, chapterId: String, expectedSha256: String?): String? =
        lock.withLock {
            withContext(Dispatchers.IO) {
                val target = chapterPath(bookId, sourceId, chapterId)
                val hash = expectedSha256?.let {
                    if (!HASH_PATTERN.matches(it)) return@withContext null
                    it.lowercase(java.util.Locale.ROOT)
                }
                readCandidate(target, hash)?.content
                    ?: hash?.let { readCandidate(legacyPath(target, chapterId, it), it)?.content }
            }
        }

    /** Only called while holding the file mutex on the IO dispatcher. */
    private fun writeLocked(bookId: String, sourceId: String, chapterId: String, target: Path,
        content: String): StoredChapterFile {
        val temporary = target.resolveSibling("$chapterId.tmp")
        val bytes = content.toByteArray(Charsets.UTF_8)
        Files.createDirectories(target.parent)
        try {
            writeSynced(temporary, bytes)
            replace(temporary, target)
        } finally {
            Files.deleteIfExists(temporary)
        }
        return StoredChapterFile(relativePath(bookId, sourceId, chapterId), sha256(bytes), bytes.size.toLong())
    }

    private fun writeSynced(path: Path, bytes: ByteArray) {
        FileOutputStream(path.toFile()).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
    }

    private fun readCandidate(path: Path, expectedSha256: String?): ReadableChapter? {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_CHAPTER_BYTES) return null
        val bytes = Files.readAllBytes(path)
        if (bytes.size > MAX_CHAPTER_BYTES ||
            expectedSha256 != null && !sha256(bytes).equals(expectedSha256, ignoreCase = true)) return null
        val content = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        return ReadableChapter(bytes, content)
    }

    private fun legacyPath(target: Path, chapterId: String, hash: String): Path =
        target.resolveSibling("$chapterId.legacy-$hash.txt")

    private data class ReadableChapter(val bytes: ByteArray, val content: String)

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
        val HASH_PATTERN = Regex("[A-Fa-f0-9]{64}")
        const val MAX_CHAPTER_BYTES = 8L * 1024 * 1024
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
