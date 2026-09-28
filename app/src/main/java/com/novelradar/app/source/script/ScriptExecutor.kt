package com.novelradar.app.source.script

import com.novelradar.app.domain.model.SourceResult

/** Applies one source-rule JavaScript transform to a scalar value. */
interface ScriptExecutor {
    suspend fun transform(input: String, script: String): SourceResult<String>
}
