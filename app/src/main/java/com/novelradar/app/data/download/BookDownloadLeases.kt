package com.novelradar.app.data.download

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Lock order: coordinator -> runner -> content -> chapter -> file store. */
class BookDownloadLeases {
    private class Lease {
        val runner = Mutex()
        val content = Mutex()
        @Volatile var deleting = false
        @Volatile var epoch = 0L
    }
    private val leases = ConcurrentHashMap<String, Lease>()
    private fun lease(bookId: String) = leases.getOrPut(bookId) { Lease() }

    suspend fun <T> withRunner(bookId: String, block: suspend () -> T): T = lease(bookId).runner.withLock { block() }
    suspend fun awaitRunner(bookId: String) = withRunner(bookId) {}
    fun isDeleting(bookId: String) = lease(bookId).deleting

    suspend fun <T> withContent(bookId: String, block: suspend () -> T): T {
        val lease = lease(bookId)
        val epoch = lease.epoch
        check(!lease.deleting) { "Book content is being deleted" }
        return lease.content.withLock {
            check(!lease.deleting && epoch == lease.epoch) { "Book content was deleted" }
            block()
        }
    }

    suspend fun <T> deleting(bookId: String, stop: suspend () -> Unit, clean: suspend () -> T): T {
        val lease = lease(bookId)
        check(!lease.deleting)
        lease.deleting = true
        lease.epoch++
        try {
            stop()
            return lease.runner.withLock { lease.content.withLock { clean() } }
        } finally { lease.deleting = false }
    }
}
