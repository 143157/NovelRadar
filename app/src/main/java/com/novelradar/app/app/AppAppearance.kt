package com.novelradar.app.app

internal fun usesPaperStudyAppearance(bookId: String?, sourceId: String?): Boolean =
    bookId == null || sourceId == null
