package com.novelradar.app.domain.model

sealed interface SourceFailure {
    data object Timeout : SourceFailure
    data class Http(val code: Int) : SourceFailure
    data class Decode(val charset: String?) : SourceFailure
    data class Parse(val field: String, val selector: String) : SourceFailure
    data object VerificationRequired : SourceFailure
    data class UnsafeRule(val reason: String) : SourceFailure
    data class Unexpected(val message: String) : SourceFailure
}

sealed interface SourceResult<out T> {
    data class Success<T>(val value: T) : SourceResult<T>
    data class Failure(val error: SourceFailure) : SourceResult<Nothing>
}
