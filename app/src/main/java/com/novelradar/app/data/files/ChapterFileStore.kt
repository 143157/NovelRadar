package com.novelradar.app.data.files

data class StoredChapterFile(
    val relativePath: String,
    val sha256: String,
    val byteCount: Long,
)

interface ChapterFileStore {
    suspend fun write(bookId: String, sourceId: String, chapterId: String, content: String): StoredChapterFile
    suspend fun read(bookId: String, sourceId: String, chapterId: String, expectedSha256: String? = null): String?
    suspend fun deleteBook(bookId: String)
}
