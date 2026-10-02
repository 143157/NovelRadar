package com.novelradar.app.data.download

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** State guards never span suspension; deletion waits for the runner, then all content operations. */
class BookDownloadLeases {
    class ContentPermit internal constructor(private val verify: () -> Unit) {
        /** Call after waiting for a chapter lock, before reading or writing any content. */
        fun ensureCurrent() = verify()
    }

    private class Lease {
        val runner = Mutex()
        val guard = Any()
        var deleting = false
        var epoch = 0L
        var activeContent = 0
        var drained = CompletableDeferred<Unit>().apply { complete(Unit) }
    }
    private val leases = ConcurrentHashMap<String, Lease>()
    private fun lease(bookId: String) = leases.getOrPut(bookId) { Lease() }

    suspend fun <T> withRunner(bookId: String, block: suspend () -> T): T = lease(bookId).runner.withLock { block() }
    suspend fun awaitRunner(bookId: String) = withRunner(bookId) {}
    fun isDeleting(bookId: String): Boolean {
        val lease = lease(bookId)
        return synchronized(lease.guard) { lease.deleting }
    }

    suspend fun <T> withContent(bookId: String, block: suspend ContentPermit.() -> T): T {
        val lease = lease(bookId)
        var released = false
        val permit = synchronized(lease.guard) {
            check(!lease.deleting) { "Book content is being deleted" }
            val epoch = lease.epoch
            if (lease.activeContent == 0) lease.drained = CompletableDeferred()
            lease.activeContent++
            ContentPermit { synchronized(lease.guard) {
                check(!released && !lease.deleting && epoch == lease.epoch) { "Book content was deleted" }
            } }
        }
        try {
            currentCoroutineContext().ensureActive()
            permit.ensureCurrent()
            return permit.block()
        } finally {
            val drained = synchronized(lease.guard) {
                released = true
                lease.activeContent--
                if (lease.activeContent == 0) lease.drained else null
            }
            drained?.complete(Unit)
        }
    }

    suspend fun <T> deleting(bookId: String, stop: suspend () -> Unit, clean: suspend () -> T): T {
        val lease = lease(bookId)
        val drained = synchronized(lease.guard) {
            check(!lease.deleting)
            lease.deleting = true
            lease.epoch++
            lease.drained
        }
        try {
            stop()
            return lease.runner.withLock { drained.await(); clean() }
        } finally { synchronized(lease.guard) { lease.deleting = false } }
    }
}
