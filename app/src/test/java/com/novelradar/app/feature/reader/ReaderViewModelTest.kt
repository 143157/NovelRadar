package com.novelradar.app.feature.reader

import com.google.common.truth.Truth.assertThat
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStoreOwner
import com.novelradar.app.domain.model.Book
import com.novelradar.app.domain.model.BookSource
import com.novelradar.app.domain.model.Chapter
import com.novelradar.app.domain.model.ChapterContentFormat
import com.novelradar.app.domain.model.ReaderPreferences
import com.novelradar.app.domain.model.ReaderTheme
import com.novelradar.app.domain.model.ReaderMode
import com.novelradar.app.domain.model.SourceFailure
import com.novelradar.app.domain.model.SourceResult
import com.novelradar.app.domain.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ReaderViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    @Test(timeout = 15000) fun preparedBodyDoesNotMoveActiveButVisibleCrossingSavesExactCurrentChapter() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        fixture.progress.initial = location("c0", 0, 18)
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            advanceUntilIdle()
            assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).containsExactly("c0", "c1", "c2").inOrder()
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
            assertThat(vm.uiState.value.offset).isEqualTo(18)
            assertThat(vm.uiState.value.catalogPosition).isEqualTo(0)
            assertThat(fixture.progress.exact.map { it.chapterId }.distinct()).containsExactly("c0")
            val restore = vm.uiState.value.restorationVersion
            val epoch = vm.uiState.value.positionVersion
            vm.onVisiblePosition(epoch, ChapterReadingPosition("c1", 17))
            advanceUntilIdle()
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
            assertThat(vm.uiState.value.offset).isEqualTo(17)
            assertThat(vm.uiState.value.catalogPosition).isEqualTo(1)
            assertThat(fixture.progress.exact.last()).isEqualTo(fixture.progress.initial)
            assertThat(fixture.progress.exact.last().chapterId).isEqualTo("c1")
            assertThat(fixture.progress.exact.last().offset).isEqualTo(17)
            assertThat(fixture.progress.exact.last().contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
            assertThat(vm.uiState.value.positionVersion).isGreaterThan(epoch)
            assertThat(vm.uiState.value.restorationVersion).isEqualTo(restore)
            vm.onVisiblePosition(epoch, ChapterReadingPosition("c0", 90))
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        } finally { owner.clear() }
    }

    @Test(timeout = 15000) fun scrollEdgeAppendsWithoutMovingAndBackwardEdgeRemainsUsable() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        fixture.settings.value.value = ReaderPreferences(prefetchCount = 0)
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            advanceUntilIdle()
            vm.continueBackward(vm.uiState.value.positionVersion)
            vm.continueForward(vm.uiState.value.positionVersion)
            advanceUntilIdle()
            assertThat(fixture.chapters.loads).containsExactly("c0", "c1").inOrder()
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
            assertThat(fixture.progress.initial?.chapterId).isEqualTo("c0")
            vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("c1", 36))
            advanceUntilIdle()
            vm.continueBackward(vm.uiState.value.positionVersion)
            advanceUntilIdle()
            vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("c0", 100))
            advanceUntilIdle()
            assertThat(vm.uiState.value.offset).isEqualTo(100)
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
        } finally { owner.clear() }
    }

    @Test(timeout = 15000) fun reviewHidesPreparedNeighborsAndAutomaticCallbacksCannotConfirmThem() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        val original = location("c0", 0, 20).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
        fixture.progress.initial = original
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            advanceUntilIdle()
            val epoch = vm.uiState.value.positionVersion
            val restore = vm.uiState.value.restorationVersion
            assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).contains("c1")
            assertThat(vm.uiState.value.presentationChapters.map { it.chapter.chapterId }).containsExactly("c0")
            assertThat(vm.uiState.value.characterProgress).isNull()
            vm.onVisiblePosition(epoch, ChapterReadingPosition("c1", 10))
            vm.continueForward(epoch)
            vm.continueBackward(epoch)
            advanceUntilIdle()
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
            assertThat(fixture.progress.initial).isEqualTo(original)
            assertThat(fixture.progress.exact).isEmpty()
            vm.onVisiblePosition(epoch, ChapterReadingPosition("c0", 32))
            assertThat(vm.uiState.value.offset).isEqualTo(32)
            assertThat(vm.confirmPosition()).isTrue()
            assertThat(vm.uiState.value.presentationChapters.map { it.chapter.chapterId }).contains("c1")
            assertThat(vm.uiState.value.restorationVersion).isEqualTo(restore)
            assertThat(vm.uiState.value.offset).isEqualTo(32)
            assertThat(fixture.progress.initial?.offset).isEqualTo(32)
        } finally { owner.clear() }
    }

    @Test(timeout = 15000) fun invalidPreparedIdentitiesAndUnloadedVisiblePositionsAreRejected() = runTest(timeout = 10.seconds) {
        for (wrong in listOf(Chapter("other", "a", "c1", 1, "other"), Chapter("book", "b", "c1", 1, "other"),
            Chapter("book", "a", "missing", 1, "other"), null)) {
            val fixture = Fixture()
            fixture.chapters.preparedChapter = wrong
            fixture.chapters.preparedVerified = wrong != null
            val vm = fixture.create()
            val owner = ViewModelStore().also { it.put("reader", vm) }
            try {
                advanceUntilIdle()
                val epoch = vm.uiState.value.positionVersion
                val saves = fixture.progress.exact.size
                vm.onVisiblePosition(epoch, ChapterReadingPosition("missing", 1))
                vm.onVisiblePosition(epoch, ChapterReadingPosition("c1", 20))
                advanceUntilIdle()
                assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).containsExactly("c0")
                assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
                assertThat(fixture.progress.exact).hasSize(saves)
            } finally { owner.clear() }
        }
    }

    @Test(timeout = 15000) fun boundedWindowPrunesDistantBodiesWithoutEvictingActiveAndUsesCatalogTieOrder() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        fixture.catalog.list = (0..6).reversed().map { Chapter("book", "a", "c$it", it / 2, "第$it 章") } +
            Chapter("foreign", "b", "wrong", 0, "错误")
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            advanceUntilIdle()
            assertThat(vm.uiState.value.chapters.map { it.chapterId }).containsExactly("c0", "c1", "c2", "c3", "c4", "c5", "c6").inOrder()
            for (id in listOf("c1", "c2", "c3", "c4", "c5", "c6")) {
                vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition(id, 3))
                advanceUntilIdle()
                val state = vm.uiState.value
                assertThat(state.content?.chapter?.chapterId).isEqualTo(id)
                assertThat(state.verifiedWindow.size).isAtMost(4)
                assertThat(state.verifiedWindow.map { it.chapter.chapterId }).contains(id)
                assertThat(state.verifiedWindow.map { it.chapter.chapterId }.toSet()).isEqualTo(
                    readingWindowIds(state.chapters, id).intersect(state.verifiedWindow.map { it.chapter.chapterId }.toSet()))
            }
            val loads = fixture.chapters.loads.toList()
            vm.continueForward(vm.uiState.value.positionVersion)
            advanceUntilIdle()
            assertThat(fixture.chapters.loads).isEqualTo(loads)
            assertThat(vm.uiState.value.characterProgress).isEqualTo(ChapterCharacterProgress(3, 100))
        } finally { owner.clear() }
    }

    @Test(timeout = 15000) fun pagedDeliberateEdgeTransitionsAndLayoutHasSeparateRestoreEpoch() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        fixture.settings.value.value = ReaderPreferences(mode = ReaderMode.PAGED, prefetchCount = 0)
        fixture.chapters.body = "甲😀乙"
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            advanceUntilIdle()
            val epoch = vm.uiState.value.positionVersion
            val restore = vm.uiState.value.restorationVersion
            vm.continueForward(epoch)
            advanceUntilIdle()
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
            assertThat(vm.uiState.value.offset).isEqualTo(0)
            assertThat(vm.uiState.value.restorationVersion).isGreaterThan(restore)
            vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("c1", 2))
            assertThat(vm.uiState.value.characterProgress).isEqualTo(ChapterCharacterProgress(1, 3))
            val currentRestore = vm.uiState.value.restorationVersion
            vm.updatePreferences { it.copy(fontSizeSp = 28f, mode = ReaderMode.SCROLL) }
            advanceUntilIdle()
            assertThat(vm.uiState.value.restorationVersion).isGreaterThan(currentRestore)
            vm.continueForward(epoch)
            advanceUntilIdle()
            assertThat(fixture.chapters.loads).containsExactly("c0", "c1").inOrder()
            assertThat(vm.uiState.value.offset).isEqualTo(2)
        } finally { owner.clear() }
    }

    @Test(timeout = 15000) fun failedContinuationRetriesSameEdgeWithoutMovingActiveOrResettingOffset() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        fixture.settings.value.value = ReaderPreferences(prefetchCount = 0)
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            advanceUntilIdle()
            vm.onPositionChanged(47)
            fixture.chapters.failed = "c1"
            vm.continueForward(vm.uiState.value.positionVersion)
            advanceUntilIdle()
            assertThat(vm.uiState.value.continuationTargetId).isEqualTo("c1")
            assertThat(vm.uiState.value.continuationError).isEqualTo(SourceFailure.Timeout)
            assertThat(vm.uiState.value.continuationLoading).isFalse()
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
            assertThat(vm.uiState.value.offset).isEqualTo(47)
            fixture.chapters.failed = null
            vm.retry()
            advanceUntilIdle()
            assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).contains("c1")
            assertThat(vm.uiState.value.continuationError).isNull()
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
            assertThat(vm.uiState.value.offset).isEqualTo(47)
            vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("c1", 11))
            advanceUntilIdle()
            assertThat(fixture.progress.initial?.chapterId).isEqualTo("c1")
        } finally { owner.clear() }
    }

    @Test(timeout = 15000) fun preparationsKeepRelevantJobsAndNeverRefetchReadyBodiesAfterPreferenceOrLayoutEvents() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        fixture.catalog.list = (0..5).map { Chapter("book", "a", "c$it", it, "第$it 章") }
        val gate = CompletableDeferred<Unit>()
        fixture.chapters.preparationGates["c2"] = gate
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            runCurrent()
            assertThat(fixture.chapters.preparations).containsExactly("c1", "c2").inOrder()
            vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("c1", 8))
            runCurrent()
            assertThat(fixture.chapters.cancelledPreparations).doesNotContain("c2")
            assertThat(fixture.chapters.preparations).containsExactly("c1", "c2", "c3").inOrder()
            gate.complete(Unit)
            advanceUntilIdle()
            val calls = fixture.chapters.preparations.toList()
            val restore = vm.uiState.value.restorationVersion
            vm.updatePreferences { it.copy(theme = ReaderTheme.NIGHT) }
            advanceUntilIdle()
            assertThat(fixture.chapters.preparations).isEqualTo(calls)
            assertThat(vm.uiState.value.restorationVersion).isEqualTo(restore)
            vm.selectChapter("c5")
            advanceUntilIdle()
            assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).containsExactly("c5")
        } finally { gate.complete(Unit); owner.clear() }
    }

    @Test(timeout = 15000) fun restrictingPreferencesCancelsOnlyNowIrrelevantPreparationAndCloseDropsLateResult() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        val first = CompletableDeferred<Unit>()
        val second = CompletableDeferred<Unit>()
        fixture.chapters.preparationGates["c1"] = first
        fixture.chapters.preparationGates["c2"] = second
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            runCurrent()
            vm.updatePreferences { it.copy(prefetchCount = 1) }
            runCurrent()
            assertThat(fixture.chapters.cancelledPreparations).containsExactly("c2")
            assertThat(vm.close()).isTrue()
            first.complete(Unit); second.complete(Unit)
            advanceUntilIdle()
            assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).containsExactly("c0")
            assertThat(fixture.chapters.cancelledPreparations).containsExactly("c1", "c2")
            val writes = fixture.progress.exact.size
            vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("c1", 2))
            vm.continueForward(vm.uiState.value.positionVersion)
            advanceUntilIdle()
            assertThat(fixture.progress.exact).hasSize(writes)
        } finally { first.complete(Unit); second.complete(Unit); owner.clear() }
    }

    @Test(timeout = 15000) fun capturedCrossingWritesDrainBeforeLatestStopAndFailedWriteStaysRetryable() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        val gate = CompletableDeferred<Unit>()
        try {
            advanceUntilIdle()
            fixture.progress.saveGate = gate
            vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("c1", 13))
            runCurrent()
            vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("c2", 27))
            vm.onPositionChanged(39)
            vm.saveOnStop()
            runCurrent()
            fixture.progress.saveGate = null
            gate.complete(Unit)
            advanceUntilIdle()
            assertThat(fixture.progress.exact.filter { it.chapterId == "c1" }.map { it.offset }).containsExactly(13)
            assertThat(fixture.progress.exact.last().chapterId).isEqualTo("c2")
            assertThat(fixture.progress.exact.last().offset).isEqualTo(39)
            fixture.progress.failSave = true
            vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("c1", 21))
            advanceUntilIdle()
            assertThat(vm.uiState.value.notice).isEqualTo("阅读位置保存失败，请重试返回")
            assertThat(fixture.progress.initial?.chapterId).isEqualTo("c2")
            fixture.progress.failSave = false
            assertThat(vm.saveExact()).isTrue()
            assertThat(fixture.progress.initial?.chapterId).isEqualTo("c1")
            assertThat(fixture.progress.initial?.offset).isEqualTo(21)
        } finally { gate.complete(Unit); owner.clear() }
    }

    @Test(timeout = 15000) fun retryAfterVisibleCrossingUsesLatestActiveIdentityAndOffset() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            advanceUntilIdle()
            vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("c1", 15))
            advanceUntilIdle()
            vm.onPositionChanged(32)
            vm.retry()
            advanceUntilIdle()
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
            assertThat(vm.uiState.value.offset).isEqualTo(32)
            assertThat(vm.uiState.value.positionNeedsReview).isFalse()
            assertThat(fixture.progress.initial?.chapterId).isEqualTo("c1")
        } finally { owner.clear() }
    }

    @Test(timeout = 15000) fun distantSelectionWaitsForActivationBeforePreparingTargetSuccessors() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        fixture.catalog.list = (0..6).map { Chapter("book", "a", "c$it", it, "第$it 章") }
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        val gate = CompletableDeferred<Unit>()
        try {
            advanceUntilIdle()
            fixture.chapters.gate = gate
            vm.selectChapter("c4")
            runCurrent()
            assertThat(fixture.chapters.preparations).containsExactly("c1", "c2").inOrder()
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
            gate.complete(Unit)
            advanceUntilIdle()
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c4")
            assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).containsExactly("c4", "c5", "c6").inOrder()
            assertThat(fixture.chapters.preparations).containsExactly("c1", "c2", "c5", "c6").inOrder()
        } finally { gate.complete(Unit); owner.clear() }
    }

    @Test(timeout = 15000) fun failedPreparationRetriesOnceOnActualAdvancementWithoutCompletionLoop() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        fixture.catalog.list = (0..4).map { Chapter("book", "a", "c$it", it, "第$it 章") }
        fixture.chapters.failed = "c2"
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            advanceUntilIdle()
            assertThat(fixture.chapters.preparations.count { it == "c2" }).isEqualTo(1)
            fixture.chapters.failed = null
            vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("c1", 8))
            advanceUntilIdle()
            assertThat(fixture.chapters.preparations.count { it == "c2" }).isEqualTo(2)
            assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).containsExactly("c0", "c1", "c2", "c3").inOrder()
            vm.updatePreferences { it.copy(theme = ReaderTheme.NIGHT) }
            advanceUntilIdle()
            assertThat(fixture.chapters.preparations.count { it == "c2" }).isEqualTo(2)
        } finally { owner.clear() }
    }

    @Test(timeout = 15000) fun allowingMeteredPreparationRetriesFailedAttemptWithoutForegroundLoad() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        fixture.chapters.failed = "c1"
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            advanceUntilIdle()
            fixture.chapters.failed = null
            vm.updatePreferences { it.copy(allowMeteredPrefetch = true) }
            advanceUntilIdle()
            assertThat(fixture.chapters.preparations.count { it == "c1" }).isEqualTo(2)
            assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).contains("c1")
            assertThat(fixture.chapters.loads).containsExactly("c0")
        } finally { owner.clear() }
    }

    @Test(timeout = 15000) fun failedCloseRestartsStillRelevantPreparationsAndKeepsReaderUsable() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        val one = CompletableDeferred<Unit>()
        val two = CompletableDeferred<Unit>()
        fixture.chapters.preparationGates["c1"] = one
        fixture.chapters.preparationGates["c2"] = two
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            runCurrent()
            fixture.progress.failSave = true
            assertThat(vm.close()).isFalse()
            runCurrent()
            assertThat(fixture.chapters.preparations).containsExactly("c1", "c2", "c1", "c2").inOrder()
            fixture.progress.failSave = false
            one.complete(Unit); two.complete(Unit)
            advanceUntilIdle()
            assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).containsExactly("c0", "c1", "c2").inOrder()
            vm.onVisiblePosition(vm.uiState.value.positionVersion, ChapterReadingPosition("c1", 12))
            advanceUntilIdle()
            assertThat(fixture.progress.initial?.chapterId).isEqualTo("c1")
        } finally { one.complete(Unit); two.complete(Unit); owner.clear() }
    }

    @Test(timeout = 15000) fun cancelledOldPreparationCannotReplaceNewJobResultForReusedChapterId() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        fixture.settings.value.value = ReaderPreferences(prefetchCount = 1)
        fixture.chapters.ignorePreparationCancellation = true
        fixture.chapters.preparationBody = "old body"
        val old = CompletableDeferred<Unit>()
        val fresh = CompletableDeferred<Unit>()
        fixture.chapters.preparationGates["c1"] = old
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            runCurrent()
            vm.updatePreferences { it.copy(prefetchCount = 0) }
            runCurrent()
            fixture.chapters.preparationGates["c1"] = fresh
            fixture.chapters.preparationBody = "fresh body"
            vm.updatePreferences { it.copy(prefetchCount = 1) }
            runCurrent()
            fresh.complete(Unit)
            runCurrent()
            old.complete(Unit)
            advanceUntilIdle()
            assertThat(fixture.chapters.preparations).containsExactly("c1", "c1").inOrder()
            assertThat(vm.uiState.value.verifiedWindow.first { it.chapter.chapterId == "c1" }.content).isEqualTo("fresh body")
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
        } finally { old.complete(Unit); fresh.complete(Unit); owner.clear(); runCurrent() }
    }

    @Test(timeout = 15000) fun continuationRejectsForeignOrUnverifiedBodiesWithoutSourceSwitchOrPositionWrites() = runTest(timeout = 10.seconds) {
        for (wrong in listOf(Chapter("other", "a", "c1", 1, "wrong"), Chapter("book", "b", "c1", 1, "wrong"),
            Chapter("book", "a", "c2", 2, "wrong"), null)) {
            val fixture = Fixture()
            fixture.settings.value.value = ReaderPreferences(prefetchCount = 0)
            val vm = fixture.create()
            val owner = ViewModelStore().also { it.put("reader", vm) }
            try {
                advanceUntilIdle()
                vm.onPositionChanged(17)
                val writes = fixture.progress.exact.toList()
                fixture.chapters.returnedChapter = wrong
                fixture.chapters.verified = wrong != null
                vm.continueForward(vm.uiState.value.positionVersion)
                advanceUntilIdle()
                assertThat(vm.uiState.value.continuationError).isNotNull()
                assertThat(vm.uiState.value.continuationTargetId).isEqualTo("c1")
                assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
                assertThat(vm.uiState.value.content?.chapter?.sourceId).isEqualTo("a")
                assertThat(vm.uiState.value.offset).isEqualTo(17)
                assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).containsExactly("c0")
                assertThat(fixture.progress.exact).isEqualTo(writes)
            } finally { owner.clear() }
        }
    }

    @Test(timeout = 15000) fun backwardPagedRetryKeepsCorrectNeighborAndArrivesAtEnd() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        fixture.settings.value.value = ReaderPreferences(prefetchCount = 0, mode = ReaderMode.PAGED)
        val vm = fixture.create("c2")
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            advanceUntilIdle()
            fixture.chapters.failed = "c1"
            vm.continueBackward(vm.uiState.value.positionVersion)
            advanceUntilIdle()
            assertThat(vm.uiState.value.continuationTargetId).isEqualTo("c1")
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c2")
            fixture.chapters.failed = null
            vm.retry()
            advanceUntilIdle()
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
            assertThat(vm.uiState.value.offset).isEqualTo(100)
            assertThat(fixture.progress.initial?.offset).isEqualTo(100)
        } finally { owner.clear() }
    }

    @Test(timeout = 15000) fun recoveredPreparedEdgeClearsOldContinuationErrorOnRetryWithoutMovingVisibleChapter() = runTest(timeout = 10.seconds) {
        val fixture = Fixture()
        fixture.settings.value.value = ReaderPreferences(prefetchCount = 1)
        fixture.chapters.failed = "c1"
        val vm = fixture.create()
        val owner = ViewModelStore().also { it.put("reader", vm) }
        try {
            advanceUntilIdle()
            vm.continueForward(vm.uiState.value.positionVersion)
            advanceUntilIdle()
            assertThat(vm.uiState.value.continuationError).isEqualTo(SourceFailure.Timeout)
            fixture.chapters.failed = null
            vm.updatePreferences { it.copy(allowMeteredPrefetch = true) }
            advanceUntilIdle()
            assertThat(vm.uiState.value.verifiedWindow.map { it.chapter.chapterId }).contains("c1")
            val calls = fixture.chapters.loads.toList()
            vm.retry()
            advanceUntilIdle()
            assertThat(vm.uiState.value.continuationError).isNull()
            assertThat(vm.uiState.value.continuationTargetId).isNull()
            assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
            assertThat(fixture.chapters.loads).isEqualTo(calls)
        } finally { owner.clear() }
    }

    @Test fun loadsCachedChapterAndPrefetchesAtMostTwo() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
        assertThat(vm.uiState.value.content?.origin).isEqualTo(ChapterOrigin.Cached)
        assertThat(vm.uiState.value.loading).isFalse()
        assertThat(fixture.chapters.preparations).containsExactly("c1", "c2").inOrder()
        assertThat(fixture.chapters.prefetches).isEmpty()
    }

    @Test fun outOfRangeCurrentPositionIsNotClampedOrOverwritten() = runTest {
        val fixture = Fixture()
        val original = location("c1", 1, 999)
        fixture.progress.initial = original
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(vm.uiState.value.offset).isEqualTo(0)
        assertThat(fixture.progress.initial).isEqualTo(original)
        assertThat(fixture.progress.exact).isEmpty()
    }

    @Test fun validCurrentPositionRestoresAndOtherSourceIsIgnored() = runTest {
        val fixture = Fixture()
        fixture.progress.initial = location("c1", 1, 55)
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(vm.uiState.value.offset).isEqualTo(55)
        val other = Fixture().also { it.progress.initial = location("c1", 1, 55).copy(sourceId = "b") }
        val otherVm = other.create()
        advanceUntilIdle()
        assertThat(otherVm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
        assertThat(otherVm.uiState.value.offset).isEqualTo(0)
        assertThat(otherVm.uiState.value.positionNeedsReview).isTrue()
        assertThat(otherVm.uiState.value.originalReadingLocation?.sourceId).isEqualTo("b")
        assertThat(other.progress.initial?.sourceId).isEqualTo("b")
    }

    @Test fun repairedLegacyPositionSurvivesScrollLayoutStopRetryPrefetchAndClose() = runTest {
        val fixture = Fixture()
        val original = location("c1", 1, 88).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
        fixture.progress.initial = original
        fixture.chapters.body = "修复后的短正文"
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(vm.uiState.value.offset).isEqualTo(0)
        vm.onPositionChanged(4)
        vm.updatePreferences { it.copy(fontSizeSp = 28f) }
        vm.onPositionChanged("c1", vm.uiState.value.positionVersion, 6)
        vm.saveOnStop()
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.close()).isTrue()
        assertThat(fixture.progress.initial).isEqualTo(original)
        assertThat(fixture.progress.exact).isEmpty()
        assertThat(fixture.progress.changes).isEmpty()
        assertThat(fixture.chapters.preparations).containsExactly("c2")
    }

    @Test fun temporaryLegacyBodyIsViewableWithoutAutomaticWrites() = runTest {
        val fixture = Fixture()
        val original = location("c1", 1, 55).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
        fixture.progress.initial = original
        fixture.chapters.verified = false
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(vm.uiState.value.offset).isEqualTo(55)
        vm.onPositionChanged(65)
        vm.saveOnStop()
        advanceUntilIdle()
        assertThat(vm.close()).isTrue()
        assertThat(fixture.progress.initial).isEqualTo(original)
        assertThat(fixture.progress.exact).isEmpty()
        assertThat(fixture.progress.changes).isEmpty()
    }

    @Test fun missingSavedChapterFallbackCannotReplaceOriginalPosition() = runTest {
        val fixture = Fixture()
        val original = location("removed", 8, 42)
        fixture.progress.initial = original
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
        vm.onPositionChanged(18)
        assertThat(vm.close()).isTrue()
        assertThat(fixture.progress.initial).isEqualTo(original)
    }

    @Test fun missingExplicitEntryIsNotConsumedByFallbackChapter() = runTest {
        val fixture = Fixture()
        var consumed = 0
        val vm = fixture.create("missing") { consumed++ }
        advanceUntilIdle()
        assertThat(vm.uiState.value.content).isNull()
        assertThat(consumed).isEqualTo(0)
        assertThat(fixture.progress.exact).isEmpty()
        vm.retry()
        advanceUntilIdle()
        assertThat(consumed).isEqualTo(0)
    }

    @Test fun legacyAndUnknownMarkersNeedReviewEvenWhenOffsetFitsVerifiedBody() = runTest {
        for (marker in listOf(ChapterContentFormat.LEGACY, 99)) {
            val fixture = Fixture()
            val original = location("c1", 1, 55).copy(contentFormatVersion = marker)
            fixture.progress.initial = original
            val vm = fixture.create()
            advanceUntilIdle()
            assertThat(vm.uiState.value.positionNeedsReview).isTrue()
            assertThat(vm.uiState.value.offset).isEqualTo(0)
            assertThat(vm.uiState.value.originalReadingLocation).isEqualTo(original)
            assertThat(vm.uiState.value.originalChapterTitle).isEqualTo("第2章")
            assertThat(fixture.progress.initial).isEqualTo(original)
        }
    }

    @Test fun failedChoiceKeepsGateAndRetryConfirmsActualRequestedChapter() = runTest {
        val fixture = Fixture()
        val original = location("c1", 1, 80).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
        fixture.progress.initial = original
        val vm = fixture.create()
        advanceUntilIdle()
        fixture.chapters.failed = "c2"
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(vm.uiState.value.positionNeedsReview).isTrue()
        assertThat(fixture.progress.initial).isEqualTo(original)
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        fixture.chapters.failed = null
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c2")
        assertThat(vm.uiState.value.offset).isEqualTo(0)
        assertThat(vm.uiState.value.positionNeedsReview).isFalse()
        assertThat(fixture.progress.initial?.chapterId).isEqualTo("c2")
    }

    @Test fun explicitUnverifiedEntryStaysPendingUntilTrustedRetryHasSaved() = runTest {
        val fixture = Fixture()
        fixture.chapters.verified = false
        val original = location("c2", 2, 55)
        fixture.progress.initial = original
        var consumed = 0
        val vm = fixture.create("c1") { consumed++ }
        advanceUntilIdle()
        assertThat(consumed).isEqualTo(0)
        assertThat(vm.uiState.value.initialEntryCompleted).isFalse()
        assertThat(vm.confirmPosition()).isFalse()
        assertThat(fixture.progress.initial).isEqualTo(original)
        fixture.chapters.verified = true
        fixture.progress.failSave = true
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.uiState.value.positionNeedsReview).isTrue()
        assertThat(consumed).isEqualTo(0)
        fixture.progress.failSave = false
        vm.retry()
        advanceUntilIdle()
        assertThat(consumed).isEqualTo(1)
        assertThat(vm.uiState.value.initialEntryCompleted).isTrue()
        assertThat(vm.uiState.value.positionNeedsReview).isFalse()
        assertThat(fixture.progress.initial?.chapterId).isEqualTo("c1")
        assertThat(fixture.progress.initial?.offset).isEqualTo(0)
    }

    @Test fun confirmFailureKeepsOriginalAndLaterConfirmationSavesBoundedVisibleOffset() = runTest {
        val fixture = Fixture()
        val original = location("c1", 1, 88).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
        fixture.progress.initial = original
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(44)
        fixture.progress.failSave = true
        assertThat(vm.confirmPosition()).isFalse()
        assertThat(vm.uiState.value.positionNeedsReview).isTrue()
        assertThat(vm.uiState.value.originalReadingLocation).isEqualTo(original)
        assertThat(vm.uiState.value.notice).isEqualTo("阅读位置确认失败，请重试确认")
        assertThat(fixture.progress.initial).isEqualTo(original)
        vm.saveOnStop()
        advanceUntilIdle()
        fixture.progress.failSave = false
        vm.onPositionChanged(999)
        assertThat(vm.confirmPosition()).isTrue()
        assertThat(vm.uiState.value.positionNeedsReview).isFalse()
        assertThat(vm.uiState.value.originalReadingLocation).isNull()
        assertThat(fixture.progress.initial?.offset).isEqualTo(100)
        assertThat(fixture.progress.initial?.contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
        assertThat(vm.uiState.value.notice).isNull()
    }

    @Test fun pendingConfirmationKeepsGateUntilSerializedWriteSucceeds() = runTest {
        val fixture = Fixture()
        val original = location("c1", 1, 88).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
        fixture.progress.initial = original
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(44)
        val gate = CompletableDeferred<Unit>()
        fixture.progress.saveGate = gate
        var confirmed = false
        backgroundScope.launch { confirmed = vm.confirmPosition() }
        runCurrent()
        assertThat(vm.uiState.value.positionNeedsReview).isTrue()
        assertThat(fixture.progress.initial).isEqualTo(original)
        vm.onPositionChanged(77)
        assertThat(fixture.progress.changes).isEmpty()
        gate.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        assertThat(confirmed).isTrue()
        assertThat(vm.uiState.value.positionNeedsReview).isFalse()
        assertThat(fixture.progress.initial?.offset).isEqualTo(44)
        assertThat(fixture.progress.changes.map { it.offset }).contains(77)
        vm.saveOnStop()
        advanceUntilIdle()
        assertThat(fixture.progress.initial?.offset).isEqualTo(77)
    }

    @Test fun unverifiedLaterChoiceProtectsLatestPersistedPosition() = runTest {
        val fixture = Fixture()
        fixture.progress.initial = location("c0", 0, 5)
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(42)
        fixture.chapters.verified = false
        vm.nextChapter()
        advanceUntilIdle()
        val latest = fixture.progress.initial
        assertThat(latest?.chapterId).isEqualTo("c0")
        assertThat(latest?.offset).isEqualTo(42)
        assertThat(vm.uiState.value.originalReadingLocation).isEqualTo(latest)
        assertThat(vm.uiState.value.positionNeedsReview).isTrue()
        vm.onPositionChanged(70)
        vm.saveOnStop()
        advanceUntilIdle()
        assertThat(vm.close()).isTrue()
        assertThat(fixture.progress.initial).isEqualTo(latest)
    }

    @Test fun reviewSurvivesRetainedStoreLayoutAndStaleCallbacks() = runTest {
        val fixture = Fixture()
        val original = location("c1", 1, 88).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
        fixture.progress.initial = original
        val session = ReaderSessionViewModel()
        val vm = fixture.create()
        session.viewModelStore.put("reader", vm)
        advanceUntilIdle()
        val oldVersion = vm.uiState.value.positionVersion
        vm.updatePreferences { it.copy(fontSizeSp = 28f) }
        advanceUntilIdle()
        val retained = session.viewModelStore["reader"] as ReaderViewModel
        assertThat(retained).isSameInstanceAs(vm)
        retained.onPositionChanged("c1", oldVersion, 80)
        assertThat(retained.uiState.value.offset).isEqualTo(0)
        retained.onPositionChanged("c1", retained.uiState.value.positionVersion, 60)
        retained.saveOnStop()
        advanceUntilIdle()
        assertThat(retained.uiState.value.positionNeedsReview).isTrue()
        assertThat(retained.uiState.value.originalReadingLocation).isEqualTo(original)
        assertThat(fixture.progress.initial).isEqualTo(original)
        session.viewModelStore.clear()
    }

    @Test fun invalidExplicitEntryCanRetryRefreshedCatalogWithoutLosingIntent() = runTest {
        val fixture = Fixture()
        fixture.catalog.list = fixture.catalog.list.filterNot { it.chapterId == "c1" }
        var consumed = 0
        val vm = fixture.create("c1") { consumed++ }
        advanceUntilIdle()
        assertThat(vm.uiState.value.content).isNull()
        fixture.catalog.list = fixture.catalog.list + Chapter("book", "a", "c1", 1, "第2章")
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(consumed).isEqualTo(1)
        assertThat(vm.uiState.value.offset).isEqualTo(0)
    }

    @Test fun loadedIdentityMustMatchBookSourceAndActualRequestedChapter() = runTest {
        for (wrong in listOf(Chapter("other", "a", "c1", 1, "错书"),
            Chapter("book", "b", "c1", 1, "错源"), Chapter("book", "a", "c0", 0, "错章"))) {
            val fixture = Fixture()
            val original = location("c2", 2, 55)
            fixture.progress.initial = original
            fixture.chapters.returnedChapter = wrong
            var consumed = 0
            val vm = fixture.create("c1") { consumed++ }
            advanceUntilIdle()
            assertThat(vm.uiState.value.content).isNull()
            assertThat(vm.uiState.value.error).isNotNull()
            assertThat(consumed).isEqualTo(0)
            assertThat(fixture.progress.initial).isEqualTo(original)
            assertThat(fixture.progress.exact).isEmpty()
        }
    }

    @Test fun recoveryWarningSurvivesSettingsNoticeAndCloseDoesNotRequireSave() = runTest {
        val fixture = Fixture()
        val original = location("c1", 1, 55).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
        fixture.progress.initial = original
        val vm = fixture.create()
        advanceUntilIdle()
        fixture.settings.failUpdate = true
        fixture.progress.failSave = true
        vm.updatePreferences { it.copy(fontSizeSp = 28f) }
        advanceUntilIdle()
        assertThat(vm.uiState.value.notice).isEqualTo("设置保存失败，请重试")
        assertThat(vm.uiState.value.positionNeedsReview).isTrue()
        assertThat(vm.close()).isTrue()
        assertThat(fixture.progress.initial).isEqualTo(original)
    }

    @Test fun passiveRetryAfterLegacyConfirmationKeepsConfirmedPosition() = runTest {
        val fixture = Fixture()
        fixture.progress.initial = location("c1", 1, 88).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
        fixture.chapters.body = "修复后的短正文"
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(4)
        assertThat(vm.confirmPosition()).isTrue()
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.uiState.value.offset).isEqualTo(4)
        assertThat(fixture.progress.initial?.offset).isEqualTo(4)
    }

    @Test fun passiveCurrentRetryKeepsLatestVisiblePosition() = runTest {
        val fixture = Fixture()
        fixture.progress.initial = location("c1", 1, 8)
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(42)
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.uiState.value.offset).isEqualTo(42)
        assertThat(fixture.progress.initial?.offset).isEqualTo(42)
    }

    @Test fun layoutChangeDuringConfirmationDoesNotKeepObsoleteRecoveryAfterSuccessfulWrite() = runTest {
        val fixture = Fixture()
        fixture.progress.initial = location("c1", 1, 88).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(44)
        val gate = CompletableDeferred<Unit>()
        fixture.progress.saveGate = gate
        backgroundScope.launch { vm.confirmPosition() }
        runCurrent()
        vm.updatePreferences { it.copy(fontSizeSp = 28f) }
        runCurrent()
        assertThat(vm.uiState.value.positionNeedsReview).isTrue()
        gate.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        assertThat(vm.uiState.value.positionNeedsReview).isFalse()
        assertThat(vm.uiState.value.originalReadingLocation).isNull()
        assertThat(fixture.progress.initial?.offset).isEqualTo(44)
    }

    @Test fun onlyMatchingLegacyMarkerCanUseTemporaryOldBodyOffset() = runTest {
        for (marker in listOf(ChapterContentFormat.CURRENT, 99)) {
            val fixture = Fixture()
            val original = location("c1", 1, 55).copy(contentFormatVersion = marker)
            fixture.progress.initial = original
            fixture.chapters.verified = false
            val vm = fixture.create()
            advanceUntilIdle()
            assertThat(vm.uiState.value.offset).isEqualTo(0)
            assertThat(vm.uiState.value.positionNeedsReview).isTrue()
            assertThat(fixture.progress.initial).isEqualTo(original)
        }
    }

    @Test fun invalidLegacyOffsetsUseSafeViewAnchorAndPreserveRawOffset() = runTest {
        for (offset in listOf(-1, 999)) {
            val fixture = Fixture()
            val original = location("c1", 1, offset).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
            fixture.progress.initial = original
            fixture.chapters.verified = false
            val vm = fixture.create()
            advanceUntilIdle()
            assertThat(vm.uiState.value.offset).isEqualTo(0)
            assertThat(vm.uiState.value.originalReadingLocation).isEqualTo(original)
            assertThat(vm.close()).isTrue()
            assertThat(fixture.progress.initial).isEqualTo(original)
        }
    }

    @Test fun currentOffsetsAtBothUtf16BoundsRestoreWithoutReview() = runTest {
        for (offset in listOf(0, 100)) {
            val fixture = Fixture()
            fixture.progress.initial = location("c1", 1, offset)
            val vm = fixture.create()
            advanceUntilIdle()
            assertThat(vm.uiState.value.offset).isEqualTo(offset)
            assertThat(vm.uiState.value.positionNeedsReview).isFalse()
        }
    }

    @Test fun untrustedExplicitEntryRecreationRetainsPendingRequestedChapter() = runTest {
        val fixture = Fixture()
        fixture.progress.initial = location("c2", 2, 55)
        fixture.chapters.verified = false
        var pending: String? = "c1"
        val owner = ViewModelStore()
        val first = fixture.create(pending) { pending = null }
        owner.put("reader", first)
        advanceUntilIdle()
        assertThat(pending).isEqualTo("c1")
        assertThat(fixture.progress.initial?.chapterId).isEqualTo("c2")
        owner.clear()
        fixture.chapters.verified = true
        val recreated = fixture.create(pending) { pending = null }
        advanceUntilIdle()
        assertThat(recreated.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(recreated.uiState.value.offset).isEqualTo(0)
        assertThat(pending).isNull()
    }

    @Test fun savedBookMismatchIsReviewedAndCannotForgeAProgressWrite() = runTest {
        val fixture = Fixture()
        val original = location("c1", 1, 55).copy(bookId = "other")
        fixture.progress.initial = original
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(vm.uiState.value.positionNeedsReview).isTrue()
        assertThat(vm.uiState.value.offset).isEqualTo(0)
        vm.onPositionChanged(42)
        assertThat(vm.close()).isTrue()
        assertThat(fixture.progress.initial).isEqualTo(original)
    }

    @Test fun retainedConfirmationFinishesWriteWhenRotationCancelsItsUiCaller() = runTest {
        val fixture = Fixture()
        val original = location("c1", 1, 88).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
        fixture.progress.initial = original
        val vm = fixture.create()
        val retained = ReaderSessionViewModel()
        retained.viewModelStore.put("reader", vm)
        advanceUntilIdle()
        vm.onPositionChanged(44)
        val gate = CompletableDeferred<Unit>()
        fixture.progress.saveGate = gate
        val caller = backgroundScope.launch { vm.confirmPosition() }
        runCurrent()
        assertThat(vm.uiState.value.positionNeedsReview).isTrue()
        caller.cancel()
        gate.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        val restored = retained.viewModelStore["reader"] as ReaderViewModel
        assertThat(restored).isSameInstanceAs(vm)
        assertThat(restored.uiState.value.positionNeedsReview).isFalse()
        assertThat(restored.uiState.value.originalReadingLocation).isNull()
        assertThat(fixture.progress.initial?.offset).isEqualTo(44)
        assertThat(fixture.progress.initial?.contentFormatVersion).isEqualTo(ChapterContentFormat.CURRENT)
        retained.viewModelStore.clear()
    }

    @Test fun legacyInitialLoadFailureStillExposesProtectedOriginalPosition() = runTest {
        val fixture = Fixture()
        val original = location("c1", 1, 88).copy(contentFormatVersion = ChapterContentFormat.LEGACY)
        fixture.progress.initial = original
        fixture.chapters.failed = "c1"
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content).isNull()
        assertThat(vm.uiState.value.positionNeedsReview).isTrue()
        assertThat(vm.uiState.value.originalReadingLocation).isEqualTo(original)
        assertThat(vm.uiState.value.originalChapterTitle).isEqualTo("第2章")
        assertThat(vm.close()).isTrue()
        assertThat(fixture.progress.initial).isEqualTo(original)
    }

    @Test fun anotherSourceRecordRemainsProtectedAndVisibleForUnverifiedCurrentBody() = runTest {
        val fixture = Fixture()
        val original = location("c2", 2, 55).copy(sourceId = "b", updatedAtEpochMillis = 200L)
        fixture.progress.initial = original
        fixture.progress.preferredSource = "b"
        fixture.chapters.verified = false
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.sourceId).isEqualTo("a")
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
        assertThat(vm.uiState.value.offset).isEqualTo(0)
        assertThat(vm.uiState.value.positionNeedsReview).isTrue()
        assertThat(vm.uiState.value.originalReadingLocation).isEqualTo(original)
        assertThat(vm.uiState.value.originalChapterTitle).isNull()
        vm.onPositionChanged(42)
        vm.saveOnStop()
        advanceUntilIdle()
        assertThat(vm.close()).isTrue()
        assertThat(fixture.progress.initial).isEqualTo(original)
        assertThat(fixture.progress.preferredSource).isEqualTo("b")
    }

    @Test fun explicitCatalogSelectionStartsAtChapterBeginning() = runTest {
        val fixture = Fixture()
        fixture.progress.initial = location("c1", 1, 55)
        val vm = fixture.create("c1")
        advanceUntilIdle()
        assertThat(vm.uiState.value.offset).isEqualTo(0)
    }

    @Test fun explicitEntryIsConsumedOnlyAfterContentArrives() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.chapters.gate = gate
        var consumed = 0
        val vm = fixture.create("c1") { consumed++ }
        runCurrent()
        assertThat(consumed).isEqualTo(0)
        assertThat(vm.uiState.value.content).isNull()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(consumed).isEqualTo(1)
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(consumed).isEqualTo(1)
    }

    @Test fun failedInitialEntryRemainsPendingUntilRetrySucceeds() = runTest {
        val fixture = Fixture()
        fixture.chapters.failed = "c1"
        var pending: String? = "c1"
        val vm = fixture.create(pending) { pending = null }
        advanceUntilIdle()
        assertThat(pending).isEqualTo("c1")
        fixture.chapters.failed = null
        vm.retry()
        advanceUntilIdle()
        assertThat(pending).isNull()
    }

    @Test fun recreationAfterExplicitEntryRestoresCurrentChapterAndOffset() = runTest {
        val fixture = Fixture()
        var pending: String? = "c0"
        val vm = fixture.create(pending) { pending = null }
        advanceUntilIdle()
        vm.nextChapter()
        advanceUntilIdle()
        vm.onPositionChanged(47)
        vm.saveOnStop()
        advanceUntilIdle()
        val recreated = fixture.create(pending) { pending = null }
        advanceUntilIdle()
        assertThat(recreated.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(recreated.uiState.value.offset).isEqualTo(47)
    }

    @Test fun recreationWhileInitialLoadPendingKeepsRequestedChapter() = runTest {
        val fixture = Fixture()
        fixture.progress.initial = location("c2", 2, 55)
        val gate = CompletableDeferred<Unit>()
        fixture.chapters.gate = gate
        var pending: String? = "c1"
        val owner = ViewModelStore()
        owner.put("reader", fixture.create(pending) { pending = null })
        runCurrent()
        owner.clear()
        val recreated = fixture.create(pending) { pending = null }
        runCurrent()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(recreated.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(recreated.uiState.value.offset).isEqualTo(0)
        assertThat(pending).isNull()
    }

    @Test fun retainedSessionKeepsPositionWhileStorageWriteIsPending() = runTest {
        val fixture = Fixture()
        val activityStore = ViewModelStore()
        val activityOwner = object : ViewModelStoreOwner { override val viewModelStore = activityStore }
        val sessionFactory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return ReaderSessionViewModel() as T
            }
        }
        val session = ViewModelProvider(activityOwner, sessionFactory)["reader:1", ReaderSessionViewModel::class.java]
        val vm = fixture.create("c0")
        session.viewModelStore.put("reader", vm)
        advanceUntilIdle()
        vm.nextChapter()
        advanceUntilIdle()
        vm.onPositionChanged(47)
        val gate = CompletableDeferred<Unit>()
        fixture.progress.saveGate = gate
        vm.saveOnStop()
        runCurrent()
        // A new Activity wrapper uses the retained Activity store before disk catches up.
        val recreatedOwner = object : ViewModelStoreOwner { override val viewModelStore = activityStore }
        val recreatedSession = ViewModelProvider(recreatedOwner, sessionFactory)["reader:1", ReaderSessionViewModel::class.java]
        val restored = recreatedSession.viewModelStore["reader"] as ReaderViewModel
        assertThat(restored).isSameInstanceAs(vm)
        assertThat(restored.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(restored.uiState.value.offset).isEqualTo(47)
        assertThat(fixture.progress.initial?.offset).isEqualTo(0)
        gate.complete(Unit)
        advanceUntilIdle()
        activityStore.clear()
    }

    @Test fun clearingSessionOwnerCancelsPendingReaderLoad() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<Unit>()
        fixture.chapters.gate = gate
        val session = ReaderSessionViewModel()
        val activityStore = ViewModelStore()
        activityStore.put("session", session)
        val vm = fixture.create("c1")
        session.viewModelStore.put("reader", vm)
        runCurrent()
        activityStore.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.uiState.value.content).isNull()
        assertThat(fixture.progress.exact).isEmpty()
    }

    @Test fun nextAndPreviousRespectCatalogBoundariesAndSaveBeforeChanging() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        vm.previousChapter()
        advanceUntilIdle()
        assertThat(fixture.chapters.loads).containsExactly("c0")
        vm.onPositionChanged(47)
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(fixture.progress.exact.last { it.chapterId == "c0" }.offset).isEqualTo(47)
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        vm.nextChapter()
        advanceUntilIdle()
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(fixture.chapters.loads).containsExactly("c0", "c1", "c2").inOrder()
        vm.previousChapter()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
    }

    @Test fun failurePreservesLastContentAndRetryLoadsRequestedChapterFromNetwork() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        fixture.chapters.failed = "c1"
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
        assertThat(vm.uiState.value.error).isEqualTo(SourceFailure.Timeout)
        fixture.chapters.failed = null
        fixture.chapters.origin = ChapterOrigin.Network
        vm.retry()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(vm.uiState.value.content?.origin).isEqualTo(ChapterOrigin.Network)
        assertThat(vm.uiState.value.error).isNull()
    }

    @Test fun preferenceChangesPersistAndPrefetchHonorsZeroOrOne() = runTest {
        val fixture = Fixture()
        fixture.settings.value.value = ReaderPreferences(prefetchCount = 0)
        val vm = fixture.create()
        advanceUntilIdle()
        assertThat(fixture.chapters.preparations).isEmpty()
        vm.updatePreferences { it.copy(theme = ReaderTheme.NIGHT, prefetchCount = 1) }
        advanceUntilIdle()
        assertThat(vm.uiState.value.preferences.theme).isEqualTo(ReaderTheme.NIGHT)
        assertThat(fixture.chapters.preparations).containsExactly("c1")
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(fixture.chapters.preparations).containsExactly("c1", "c2").inOrder()
    }

    @Test fun positionUpdatesAndExactSaveUseMonotonicallyIncreasingEventTimes() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(8)
        advanceUntilIdle()
        assertThat(vm.saveExact()).isTrue()
        vm.onPositionChanged(9)
        advanceUntilIdle()
        assertThat(fixture.progress.changes.last().offset).isEqualTo(9)
        assertThat(fixture.progress.changes.last().updatedAtEpochMillis)
            .isGreaterThan(fixture.progress.exact.last().updatedAtEpochMillis)
    }

    @Test fun saveFailureKeepsReaderOpenAndMakesProblemVisible() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        fixture.progress.failSave = true
        assertThat(vm.saveExact()).isFalse()
        assertThat(vm.uiState.value.notice).isNotNull()
    }

    @Test fun recoveredExactSaveClearsItsFailureNotice() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        fixture.progress.failSave = true
        assertThat(vm.saveExact()).isFalse()
        assertThat(vm.uiState.value.notice).isEqualTo("阅读位置保存失败，请重试返回")
        fixture.progress.failSave = false
        assertThat(vm.saveExact()).isTrue()
        assertThat(vm.uiState.value.notice).isNull()
    }

    @Test fun successfulExactSavePreservesOtherFailureNotices() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        fixture.settings.failUpdate = true
        vm.updatePreferences { it.copy(fontSizeSp = 28f) }
        advanceUntilIdle()
        assertThat(vm.uiState.value.notice).isEqualTo("设置保存失败，请重试")
        assertThat(vm.saveExact()).isTrue()
        assertThat(vm.uiState.value.notice).isEqualTo("设置保存失败，请重试")
    }

    @Test fun waitsForPersistedPreferencesBeforeFirstChapterPrefetch() = runTest {
        val fixture = Fixture()
        val gate = CompletableDeferred<ReaderPreferences>()
        val delayed = object : ReaderSettingsRepository {
            override val preferences = flow { emit(gate.await()) }
            override suspend fun update(transform: (ReaderPreferences) -> ReaderPreferences) = Unit
        }
        ReaderViewModel("book", "a", null, fixture.catalog, fixture.chapters, delayed, fixture.progress)
        runCurrent()
        assertThat(fixture.chapters.preparations).isEmpty()
        gate.complete(ReaderPreferences(prefetchCount = 0))
        advanceUntilIdle()
        assertThat(fixture.chapters.preparations).isEmpty()
    }

    @Test fun preferencesReadFailureKeepsDefaultsAndDoesNotCrashReader() = runTest {
        val fixture = Fixture()
        val broken = object : ReaderSettingsRepository {
            override val preferences = flow<ReaderPreferences> { error("disk unavailable") }
            override suspend fun update(transform: (ReaderPreferences) -> ReaderPreferences) = Unit
        }
        val vm = ReaderViewModel("book", "a", null, fixture.catalog, fixture.chapters, broken, fixture.progress)
        advanceUntilIdle()
        assertThat(vm.uiState.value.content).isNotNull()
        assertThat(vm.uiState.value.notice).isNotNull()
        assertThat(fixture.chapters.preparations).isEmpty()
    }

    @Test fun ignoresStalePositionEventsFromPreviousChapterOrLayout() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        val oldVersion = vm.uiState.value.positionVersion
        vm.nextChapter()
        advanceUntilIdle()
        vm.onPositionChanged("c0", oldVersion, 72)
        vm.onPositionChanged("c1", oldVersion, 73)
        assertThat(vm.uiState.value.offset).isEqualTo(0)
        vm.onPositionChanged("c1", vm.uiState.value.positionVersion, 74)
        assertThat(vm.uiState.value.offset).isEqualTo(74)
    }

    @Test fun lifecycleStopWritesExactCurrentPosition() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(39)
        vm.saveOnStop()
        advanceUntilIdle()
        assertThat(fixture.progress.exact.last().offset).isEqualTo(39)
    }

    @Test fun lifecycleStopSavesEvenWhenOwnerIsClearedImmediately() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        val owner = ViewModelStore()
        owner.put("reader", vm)
        advanceUntilIdle()
        vm.onPositionChanged(39)
        vm.saveOnStop()
        owner.clear()
        advanceUntilIdle()
        assertThat(fixture.progress.exact.last().offset).isEqualTo(39)
    }

    @Test fun layoutPreferenceChangeRejectsPreviousLayoutPositionEvents() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(29)
        val previousLayout = vm.uiState.value.positionVersion
        vm.updatePreferences { it.copy(fontSizeSp = 28f) }
        advanceUntilIdle()
        vm.onPositionChanged("c0", previousLayout, 80)
        assertThat(vm.uiState.value.offset).isEqualTo(29)
    }

    @Test fun chapterArrivalSavesPositionMovedWhileLoading() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        fixture.chapters.gate = gate
        vm.nextChapter()
        runCurrent()
        vm.onPositionChanged(42)
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(fixture.progress.exact.last { it.chapterId == "c0" }.offset).isEqualTo(42)
    }

    @Test fun closeCancelsPendingChapterAndSavesLastVisibleContent() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        fixture.chapters.gate = gate
        vm.onPositionChanged(27)
        vm.nextChapter()
        runCurrent()
        assertThat(vm.close()).isTrue()
        gate.complete(Unit)
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c0")
        assertThat(fixture.progress.exact.last().offset).isEqualTo(27)
    }

    @Test fun successfulClosePreventsDisposalAndLatePositionFromRevertingNewSourceChoice() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(27)
        assertThat(vm.close()).isTrue()
        val writes = fixture.progress.exact.size
        fixture.progress.preferredSource = "b"
        vm.onPositionChanged(80)
        vm.saveOnStop()
        advanceUntilIdle()
        assertThat(fixture.progress.exact).hasSize(writes)
        assertThat(fixture.progress.preferredSource).isEqualTo("b")
        assertThat(vm.uiState.value.offset).isEqualTo(27)
    }

    @Test fun successfulCloseWaitsForAlreadyPendingProtectedLifecycleSave() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(27)
        val gate = CompletableDeferred<Unit>()
        fixture.progress.saveGate = gate
        vm.saveOnStop()
        fixture.progress.saveGate = null
        var closed = false
        backgroundScope.launch { closed = vm.close() }
        runCurrent()
        assertThat(closed).isFalse()
        vm.onPositionChanged(80)
        gate.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        assertThat(closed).isTrue()
        assertThat(fixture.progress.exact.last().offset).isEqualTo(27)
        fixture.progress.preferredSource = "b"
        vm.saveOnStop()
        advanceUntilIdle()
        assertThat(fixture.progress.preferredSource).isEqualTo("b")
    }

    @Test fun cancelledCloseDuringCollectorCleanupKeepsRetainedReaderUsableAndSaving() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        vm.onPositionChanged(27)
        val cleanup = CompletableDeferred<Unit>()
        fixture.progress.collectorCleanup = cleanup
        val closing = backgroundScope.launch { vm.close() }
        runCurrent()
        closing.cancel()
        fixture.progress.collectorCleanup = null
        cleanup.complete(Unit)
        advanceUntilIdle()
        runCurrent()
        vm.onPositionChanged(70)
        vm.saveOnStop()
        advanceUntilIdle()
        assertThat(vm.uiState.value.offset).isEqualTo(70)
        assertThat(fixture.progress.exact.last().offset).isEqualTo(70)
        assertThat(fixture.progress.changes.last().offset).isEqualTo(70)
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(vm.close()).isTrue()
        val count = fixture.progress.exact.size
        fixture.progress.preferredSource = "b"
        vm.saveOnStop()
        advanceUntilIdle()
        assertThat(fixture.progress.exact).hasSize(count)
        assertThat(fixture.progress.preferredSource).isEqualTo("b")
    }

    @Test fun failedCloseAfterCancellingLoadKeepsChapterNavigationAvailable() = runTest {
        val fixture = Fixture()
        val vm = fixture.create()
        advanceUntilIdle()
        vm.nextChapter()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        fixture.chapters.gate = gate
        vm.nextChapter()
        runCurrent()
        assertThat(vm.uiState.value.loading).isTrue()
        fixture.progress.failSave = true
        assertThat(vm.close()).isFalse()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(vm.uiState.value.loading).isFalse()
        assertThat(vm.uiState.value.canPrevious).isTrue()
        assertThat(vm.uiState.value.canNext).isTrue()
        fixture.progress.failSave = false
        gate.complete(Unit)
        vm.nextChapter()
        advanceUntilIdle()
        assertThat(vm.uiState.value.content?.chapter?.chapterId).isEqualTo("c2")
    }

    @Test fun reusedActivitySessionOwnerCreatesFreshReaderAfterNavigation() = runTest {
        val fixture = Fixture()
        val activityStore = ViewModelStore()
        val activityOwner = object : ViewModelStoreOwner { override val viewModelStore = activityStore }
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return if (modelClass == ReaderSessionViewModel::class.java) ReaderSessionViewModel() as T
                else fixture.create("c1") as T
            }
        }
        val provider = ViewModelProvider(activityOwner, factory)
        val owner = provider["reader-session", ReaderSessionViewModel::class.java]
        val first = ViewModelProvider(owner, factory)["reader:book:a:1", ReaderViewModel::class.java]
        advanceUntilIdle()
        first.onPositionChanged(48)
        assertThat(first.close()).isTrue()
        owner.viewModelStore.clear()
        val nextOwner = provider["reader-session", ReaderSessionViewModel::class.java]
        val second = ViewModelProvider(nextOwner, factory)["reader:book:a:2", ReaderViewModel::class.java]
        advanceUntilIdle()
        assertThat(nextOwner).isSameInstanceAs(owner)
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.uiState.value.content?.chapter?.chapterId).isEqualTo("c1")
        assertThat(second.uiState.value.offset).isEqualTo(0)
        assertThat(activityStore.keys()).containsExactly("reader-session")
        val recreatedOwner = object : ViewModelStoreOwner { override val viewModelStore = activityStore }
        val restoredOwner = ViewModelProvider(recreatedOwner, factory)["reader-session", ReaderSessionViewModel::class.java]
        val restored = ViewModelProvider(restoredOwner, factory)["reader:book:a:2", ReaderViewModel::class.java]
        assertThat(restored).isSameInstanceAs(second)
        activityStore.clear()
    }

    private class Fixture {
        val catalog = FakeCatalog()
        val chapters = FakeChapters { catalog.list }
        val settings = FakeSettings()
        val progress = FakeProgress()
        fun create(chapterId: String? = null, onInitialChapterLoaded: () -> Unit = {}) = ReaderViewModel("book", "a", chapterId,
            catalog, chapters, settings, progress, clock = { 100L }, onInitialChapterLoaded = onInitialChapterLoaded)
    }

    private class FakeCatalog : CatalogRepository {
        var list = (0..2).map { Chapter("book", "a", "c$it", it, "第${it + 1}章") }
        override suspend fun book(bookId: String) = Book("book", "x", "星河旅人", "叶子")
        override suspend fun sourceNames() = mapOf("a" to "书源甲")
        override fun sources(bookId: String): Flow<List<BookSource>> = MutableStateFlow(emptyList())
        override fun observeChapters(bookId: String, sourceId: String): Flow<List<Chapter>> = MutableStateFlow(list)
        override suspend fun catalog(bookId: String, sourceId: String, forceRefresh: Boolean) = CatalogResult(list, true)
    }

    private class FakeChapters(val catalog: () -> List<Chapter>) : ChapterRepository {
        val loads = mutableListOf<String>()
        val prefetches = mutableListOf<Pair<Int, Int>>()
        val preparations = mutableListOf<String>()
        val cancelledPreparations = mutableListOf<String>()
        val preparationGates = mutableMapOf<String, CompletableDeferred<Unit>>()
        var preparedChapter: Chapter? = null
        var preparedVerified = true
        var preparationBody: String? = null
        var ignorePreparationCancellation = false
        var failed: String? = null
        var origin = ChapterOrigin.Cached
        var body = "正文".repeat(50)
        var verified = true
        var returnedChapter: Chapter? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun loadChapter(bookId: String, sourceId: String, chapterId: String): SourceResult<LoadedChapter> {
            loads += chapterId
            gate?.await()
            return if (failed == chapterId) SourceResult.Failure(SourceFailure.Timeout)
            else SourceResult.Success(LoadedChapter(returnedChapter ?: catalog().first { it.chapterId == chapterId }, body, origin, verified))
        }
        override suspend fun prefetchNext(bookId: String, sourceId: String, chapterIndex: Int, count: Int): Int {
            prefetches += chapterIndex to count
            return count
        }
        override suspend fun prepareChapter(bookId: String, sourceId: String, chapterId: String): SourceResult<LoadedChapter> {
            preparations += chapterId
            val preparedBody = preparationBody ?: body
            val preparationGate = preparationGates[chapterId]
            try {
                if (ignorePreparationCancellation) withContext(NonCancellable) { preparationGate?.await() }
                else preparationGate?.await()
            }
            catch (cancelled: kotlinx.coroutines.CancellationException) { cancelledPreparations += chapterId; throw cancelled }
            return if (failed == chapterId) SourceResult.Failure(SourceFailure.Timeout)
            else SourceResult.Success(LoadedChapter(preparedChapter ?: catalog().first { it.chapterId == chapterId }, preparedBody, origin, verified && preparedVerified))
        }
    }

    private class FakeSettings : ReaderSettingsRepository {
        var failUpdate = false
        val value = MutableStateFlow(ReaderPreferences())
        override val preferences: Flow<ReaderPreferences> = value
        override suspend fun update(transform: (ReaderPreferences) -> ReaderPreferences) {
            check(!failUpdate)
            value.value = transform(value.value).normalized()
        }
    }

    private class FakeProgress : ReadingProgressRepository {
        var initial: ReadingLocation? = null
        var failSave = false
        var saveGate: CompletableDeferred<Unit>? = null
        var preferredSource = "a"
        var collectorCleanup: CompletableDeferred<Unit>? = null
        val exact = mutableListOf<ReadingLocation>()
        val changes = mutableListOf<ReadingLocation>()
        override fun progress(bookId: String): Flow<ReadingLocation?> = MutableStateFlow(initial)
        override suspend fun saveExact(location: ReadingLocation) {
            saveGate?.await()
            check(!failSave)
            exact += location
            initial = location
            preferredSource = location.sourceId
        }
        override suspend fun collectPositionChanges(changes: Flow<ReadingLocation>) {
            try { changes.collect { this.changes += it } }
            finally { withContext(NonCancellable) { collectorCleanup?.await() } }
        }
    }

    class MainDispatcherRule : TestWatcher() {
        override fun starting(description: Description) { Dispatchers.setMain(StandardTestDispatcher()) }
        override fun finished(description: Description) { Dispatchers.resetMain() }
    }

    private companion object {
        fun location(id: String, index: Int, offset: Int) = ReadingLocation("book", "a", id, index, offset, 50L)
    }
}
