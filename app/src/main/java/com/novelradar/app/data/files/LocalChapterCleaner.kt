package com.novelradar.app.data.files

/** Keeps catalogs and reading history while deleting the book's cached/downloaded text. */
class LocalChapterCleaner(
    private val files: ChapterFileStore,
    private val clearMetadata: suspend (String) -> Unit,
) {
    suspend fun delete(bookId: String) {
        files.deleteBook(bookId)
        clearMetadata(bookId)
    }
}
