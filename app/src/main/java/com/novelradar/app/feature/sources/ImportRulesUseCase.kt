package com.novelradar.app.feature.sources

import android.content.ContentResolver
import android.net.Uri
import com.novelradar.app.source.importer.ImportResult
import com.novelradar.app.source.importer.RuleImporter
import com.novelradar.app.source.rule.RuleIssue
import java.io.IOException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The UI supplies an OpenDocument URI; only this boundary touches Android's content resolver. */
class ImportRulesUseCase(
    private val resolver: ContentResolver,
    private val importer: RuleImporter,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun import(uri: Uri): ImportResult = withContext(ioDispatcher) {
        try {
            val input = resolver.openInputStream(uri)
                ?: return@withContext ImportResult.Rejected(listOf(RuleIssue("$", "Could not open rule file")))
            input.use { importer.import(it) }
        } catch (_: IOException) {
            ImportResult.Rejected(listOf(RuleIssue("$", "Could not read rule file")))
        } catch (_: SecurityException) {
            ImportResult.Rejected(listOf(RuleIssue("$", "No permission to read rule file")))
        }
    }
}
