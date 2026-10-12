package com.novelradar.app.data.files

data class StoredChapterFile(
    val relativePath: String,
    val sha256: String,
    val byteCount: Long,
)

interface ChapterFileStore {
    suspend fun write(bookId: String, sourceId: String, chapterId: String, content: String): StoredChapterFile
    suspend fun writePreservingPrevious(bookId: String, sourceId: String, chapterId: String,
        content: String, previousSha256: String): StoredChapterFile =
        throw java.io.IOException("Legacy-preserving write is unavailable")
    suspend fun read(bookId: String, sourceId: String, chapterId: String, expectedSha256: String? = null): String?
    suspend fun deleteBook(bookId: String)
}
