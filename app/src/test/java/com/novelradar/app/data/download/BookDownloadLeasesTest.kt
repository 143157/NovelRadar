package com.novelradar.app.data.download

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import org.junit.Test

class BookDownloadLeasesTest {
    @Test fun independentContentOperationsOverlapAndDeletionDrainsEveryOne() = runTest {
        val leases = BookDownloadLeases()
        val firstEntered = CompletableDeferred<Unit>(); val firstRelease = CompletableDeferred<Unit>()
        val secondEntered = CompletableDeferred<Unit>(); val secondRelease = CompletableDeferred<Unit>()
        val first = async { leases.withContent("book") { firstEntered.complete(Unit); firstRelease.await() } }
        val second = async { leases.withContent("book") { secondEntered.complete(Unit); secondRelease.await() } }
        testScheduler.runCurrent()
        assertThat(firstEntered.isCompleted).isTrue()
        assertThat(secondEntered.isCompleted).isTrue()
        var cleaned = false
        val deletion = async { leases.deleting("book", stop = {}, clean = { cleaned = true }) }
        testScheduler.runCurrent()
        assertThat(cleaned).isFalse()
        assertThat(runCatching { leases.withContent("book") {} }.isFailure).isTrue()
        firstRelease.complete(Unit); first.await(); testScheduler.runCurrent()
        assertThat(cleaned).isFalse()
        secondRelease.complete(Unit); second.await(); deletion.await()
        assertThat(cleaned).isTrue()
    }

    @Test fun cancellationReleasesOneLeaseButDeletionStillWaitsForAnotherOperation() = runTest {
        val leases = BookDownloadLeases()
        val release = CompletableDeferred<Unit>(); val secondEntered = CompletableDeferred<Unit>()
        val canceled = async { leases.withContent("book") { CompletableDeferred<Unit>().await() } }
        val other = async { leases.withContent("book") { secondEntered.complete(Unit); release.await() } }
        testScheduler.runCurrent()
        assertThat(secondEntered.isCompleted).isTrue()
        var cleaned = false
        val deletion = async { leases.deleting("book", stop = {}, clean = { cleaned = true }) }
        testScheduler.runCurrent(); canceled.cancelAndJoin(); testScheduler.runCurrent()
        assertThat(cleaned).isFalse()
        release.complete(Unit); other.await(); deletion.await()
        assertThat(cleaned).isTrue()
        assertThat(leases.withContent("book") { "fresh" }).isEqualTo("fresh")
    }

    @Test fun canceledContentOperationDoesNotLeakTheDeletionDrain() = runTest {
        val leases = BookDownloadLeases()
        val entered = CompletableDeferred<Unit>()
        val operation = async { leases.withContent("book") { entered.complete(Unit); CompletableDeferred<Unit>().await() } }
        entered.await(); operation.cancelAndJoin()
        var cleaned = false
        leases.deleting("book", stop = {}, clean = { cleaned = true })
        assertThat(cleaned).isTrue()
    }
}
