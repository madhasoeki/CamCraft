package org.artcraft.camcraft.camera

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraStreamManagerTest {

    @Test
    fun needsEncoderRestartCoversCameraSwitchGeometryFpsAndMissingEncoder() {
        // Switching camera at identical resolution and FPS must restart encoder for fresh VIDEO_CONFIG + IDR.
        assertTrue(
            CameraStreamManager.needsEncoderRestart(
                cameraChanged = true,
                geometryOrFpsChanged = false,
                hasEncoder = true,
            ),
        )
        assertTrue(
            CameraStreamManager.needsEncoderRestart(
                cameraChanged = false,
                geometryOrFpsChanged = true,
                hasEncoder = true,
            ),
        )
        assertTrue(
            CameraStreamManager.needsEncoderRestart(
                cameraChanged = false,
                geometryOrFpsChanged = false,
                hasEncoder = false,
            ),
        )
        assertFalse(
            CameraStreamManager.needsEncoderRestart(
                cameraChanged = false,
                geometryOrFpsChanged = false,
                hasEncoder = true,
            ),
        )
    }

    @Test
    fun evaluateRebindDecisionUsesDynamicBitrateOnlyWhenCameraAndGeometryUnchanged() {
        val bitrateOnly = CameraStreamManager.evaluateRebindDecision(
            cameraChanged = false,
            geometryOrFpsChanged = false,
            bitrateChanged = true,
            hasEncoder = true,
        )
        assertFalse(bitrateOnly.needsEncoderRestart)
        assertTrue(bitrateOnly.needsBitrateUpdate)

        val cameraAndBitrate = CameraStreamManager.evaluateRebindDecision(
            cameraChanged = true,
            geometryOrFpsChanged = false,
            bitrateChanged = true,
            hasEncoder = true,
        )
        assertTrue(cameraAndBitrate.needsEncoderRestart)
        assertFalse(cameraAndBitrate.needsBitrateUpdate)
    }

    @Test
    fun previewAttachAndDetachTransitionsAreIdempotentAndPreserveEncoder() {
        // Not streaming / released -> NO_OP
        assertEquals(
            CameraStreamManager.PreviewSyncAction.NO_OP,
            CameraStreamManager.decideAttachPreviewAction(
                isStreaming = false,
                isReleased = false,
                sameViewAlreadyAttached = false,
                uiPreviewBound = false,
                encoderPreviewBound = false,
            ),
        )
        assertEquals(
            CameraStreamManager.PreviewSyncAction.NO_OP,
            CameraStreamManager.decideAttachPreviewAction(
                isStreaming = true,
                isReleased = true,
                sameViewAlreadyAttached = false,
                uiPreviewBound = false,
                encoderPreviewBound = true,
            ),
        )

        // Same view already attached and uiPreview bound -> deduplicated NO_OP
        assertEquals(
            CameraStreamManager.PreviewSyncAction.NO_OP,
            CameraStreamManager.decideAttachPreviewAction(
                isStreaming = true,
                isReleased = false,
                sameViewAlreadyAttached = true,
                uiPreviewBound = true,
                encoderPreviewBound = true,
            ),
        )

        // Attaching when encoderPreview is healthy binds ONLY uiPreview
        assertEquals(
            CameraStreamManager.PreviewSyncAction.REBIND_UI_ONLY,
            CameraStreamManager.decideAttachPreviewAction(
                isStreaming = true,
                isReleased = false,
                sameViewAlreadyAttached = false,
                uiPreviewBound = false,
                encoderPreviewBound = true,
            ),
        )

        // Attaching before encoderPreview is bound performs FULL_REBIND
        assertEquals(
            CameraStreamManager.PreviewSyncAction.FULL_REBIND,
            CameraStreamManager.decideAttachPreviewAction(
                isStreaming = true,
                isReleased = false,
                sameViewAlreadyAttached = false,
                uiPreviewBound = false,
                encoderPreviewBound = false,
            ),
        )

        // Detach when already detached -> NO_OP (prevents double-detach on backgrounding)
        assertEquals(
            CameraStreamManager.PreviewSyncAction.NO_OP,
            CameraStreamManager.decideDetachPreviewAction(
                isStreaming = true,
                isReleased = false,
                hasAttachedOrUiPreview = false,
            ),
        )

        // Detach while attached & streaming -> UNBIND_UI_ONLY (encoderPreview stays bound)
        assertEquals(
            CameraStreamManager.PreviewSyncAction.UNBIND_UI_ONLY,
            CameraStreamManager.decideDetachPreviewAction(
                isStreaming = true,
                isReleased = false,
                hasAttachedOrUiPreview = true,
            ),
        )
    }

    @Test
    fun runOnMainBlockingExecutesInlineWhenAlreadyOnMainThread() {
        val executions = AtomicInteger(0)
        var postCalled = false
        var removeCalled = false

        CameraStreamManager.runOnMainBlocking(
            isMainThread = { true },
            post = {
                postCalled = true
                true
            },
            removeCallbacks = { removeCalled = true },
            timeoutMs = 1_500L,
        ) {
            executions.incrementAndGet()
        }

        assertEquals(1, executions.get())
        assertFalse(postCalled)
        assertFalse(removeCalled)
    }

    @Test
    fun runOnMainBlockingRunsBlockOnceAndUnblocksCleanly() {
        val fakeMainExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "fake-main-thread").apply { isDaemon = true }
        }
        try {
            val executions = AtomicInteger(0)
            val executedThread = AtomicReference<String?>()
            val removedRunnables = mutableListOf<Runnable>()
            val warnings = mutableListOf<String>()

            CameraStreamManager.runOnMainBlocking(
                isMainThread = { false },
                post = { runnable ->
                    fakeMainExecutor.execute(runnable)
                    true
                },
                removeCallbacks = { runnable -> removedRunnables.add(runnable) },
                timeoutMs = 1_500L,
                onWarn = { warnings.add(it) },
            ) {
                executions.incrementAndGet()
                executedThread.set(Thread.currentThread().name)
            }

            assertEquals(1, executions.get())
            assertEquals("fake-main-thread", executedThread.get())
            assertTrue(removedRunnables.isEmpty())
            assertTrue(warnings.isEmpty())
        } finally {
            fakeMainExecutor.shutdownNow()
        }
    }

    @Test
    fun runOnMainBlockingRemovesAndGuardsRunnableAfterTimeout() {
        val postedRunnable = AtomicReference<Runnable?>()
        val removedRunnables = mutableListOf<Runnable>()
        val warnings = mutableListOf<String>()
        val executions = AtomicInteger(0)

        CameraStreamManager.runOnMainBlocking(
            isMainThread = { false },
            post = { runnable ->
                postedRunnable.set(runnable)
                true
            },
            removeCallbacks = { runnable -> removedRunnables.add(runnable) },
            timeoutMs = 20L,
            onWarn = { warnings.add(it) },
        ) {
            executions.incrementAndGet()
        }

        val queued = postedRunnable.get()
        assertNotNull(queued)
        assertEquals(1, removedRunnables.size)
        assertSame(queued, removedRunnables.first())
        assertEquals(1, warnings.size)
        assertEquals(0, executions.get())

        // Simulate the queued Runnable firing after removeCallbacks raced with Looper dispatch.
        queued!!.run()
        assertEquals(0, executions.get())
    }

    @Test
    fun runOnMainBlockingReturnsImmediatelyWhenPostFails() {
        val postedRunnable = AtomicReference<Runnable?>()
        val removedRunnables = mutableListOf<Runnable>()
        val warnings = mutableListOf<String>()
        val executions = AtomicInteger(0)

        val startNs = System.nanoTime()
        CameraStreamManager.runOnMainBlocking(
            isMainThread = { false },
            post = { runnable ->
                postedRunnable.set(runnable)
                false
            },
            removeCallbacks = { runnable -> removedRunnables.add(runnable) },
            timeoutMs = 5_000L,
            onWarn = { warnings.add(it) },
        ) {
            executions.incrementAndGet()
        }
        val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)

        assertTrue("Expected immediate return, took ${elapsedMs}ms", elapsedMs < 1_000L)
        assertEquals(0, executions.get())
        assertTrue(removedRunnables.isEmpty())
        assertEquals(1, warnings.size)

        // Even if a misbehaving post implementation ran the runnable anyway, the guard blocks it.
        postedRunnable.get()?.run()
        assertEquals(0, executions.get())

        // Also verify default logger path is safe in unmocked JVM unit tests.
        CameraStreamManager.runOnMainBlocking(
            isMainThread = { false },
            post = { false },
            removeCallbacks = {},
            timeoutMs = 5_000L,
        ) {
            executions.incrementAndGet()
        }
        assertEquals(0, executions.get())
    }

    @Test
    fun runOnMainBlockingCancelsRemovesAndRestoresInterruptOnInterruption() {
        val postedRunnable = AtomicReference<Runnable?>()
        val removedRunnables = mutableListOf<Runnable>()
        val warnings = mutableListOf<String>()
        val executions = AtomicInteger(0)

        try {
            Thread.currentThread().interrupt()
            CameraStreamManager.runOnMainBlocking(
                isMainThread = { false },
                post = { runnable ->
                    postedRunnable.set(runnable)
                    true
                },
                removeCallbacks = { runnable -> removedRunnables.add(runnable) },
                timeoutMs = 5_000L,
                onWarn = { warnings.add(it) },
            ) {
                executions.incrementAndGet()
            }

            assertTrue("Interrupt flag must be preserved", Thread.currentThread().isInterrupted)
        } finally {
            // Clear interrupt flag so subsequent tests on this runner thread are unaffected.
            Thread.interrupted()
        }

        val queued = postedRunnable.get()
        assertNotNull(queued)
        assertEquals(1, removedRunnables.size)
        assertSame(queued, removedRunnables.first())
        assertEquals(1, warnings.size)
        assertEquals(0, executions.get())

        queued!!.run()
        assertEquals(0, executions.get())
    }

    @Test
    fun encoderSurfaceRetirementTrackerImmediateAndDeferredRelease() {
        // 1. Immediate release when no surfaces are in flight.
        val immediate = EncoderSurfaceRetirementTracker()
        assertTrue(immediate.requestRetire())
        assertTrue(immediate.isRetirementPending())
        assertTrue(immediate.isReleased())
        assertFalse(immediate.requestRetire())
        assertFalse(immediate.onSurfaceProvided())

        // 2. Deferred release while surfaces are in flight.
        val deferred = EncoderSurfaceRetirementTracker()
        assertTrue(deferred.onSurfaceProvided())
        assertTrue(deferred.onSurfaceProvided())
        assertEquals(2, deferred.inFlightCount())

        // Retire requested while 2 surfaces are held by CameraX: defer release & reject new surfaces.
        assertFalse(deferred.requestRetire())
        assertTrue(deferred.isRetirementPending())
        assertFalse(deferred.isReleased())
        assertFalse(deferred.onSurfaceProvided())

        // First surface returns -> still 1 in flight -> not released yet.
        assertFalse(deferred.onSurfaceReturned())
        assertEquals(1, deferred.inFlightCount())
        assertFalse(deferred.isReleased())

        // Last surface returns -> triggers release exactly once.
        assertTrue(deferred.onSurfaceReturned())
        assertEquals(0, deferred.inFlightCount())
        assertTrue(deferred.isReleased())

        // Extra surface return clamps at 0 and never triggers duplicate release.
        assertFalse(deferred.onSurfaceReturned())
        assertEquals(0, deferred.inFlightCount())
    }

    @Test
    fun runOnMainBlockingReturnsExplicitResultsAndDefersOnlyWhenRunningOnMain() {
        // Inline and post-rejected paths
        val deferredCalls = AtomicInteger(0)
        assertEquals(
            CameraStreamManager.MainWaitResult.COMPLETED,
            CameraStreamManager.runOnMainBlocking(
                isMainThread = { true },
                post = { false },
                removeCallbacks = {},
                onDeferredComplete = { deferredCalls.incrementAndGet() },
            ) {},
        )
        assertEquals(0, deferredCalls.get())

        val rejectedRunnable = AtomicReference<Runnable?>()
        assertEquals(
            CameraStreamManager.MainWaitResult.POST_REJECTED,
            CameraStreamManager.runOnMainBlocking(
                isMainThread = { false },
                post = { runnable ->
                    rejectedRunnable.set(runnable)
                    false
                },
                removeCallbacks = {},
                onWarn = {},
                onDeferredComplete = { deferredCalls.incrementAndGet() },
            ) {},
        )
        rejectedRunnable.get()?.run()
        assertEquals(0, deferredCalls.get())

        // Timeout while block() is ALREADY mid-execution on fake Main invokes onDeferredComplete once done.
        val fakeMain = Executors.newSingleThreadExecutor { r ->
            Thread(r, "fake-main-stalled").apply { isDaemon = true }
        }
        try {
            val enteredBlock = CountDownLatch(1)
            val allowBlockFinish = CountDownLatch(1)
            val deferredLatch = CountDownLatch(1)
            val blockFinished = AtomicBoolean(false)

            val result = CameraStreamManager.runOnMainBlocking(
                isMainThread = { false },
                post = { runnable ->
                    fakeMain.execute(runnable)
                    assertTrue(enteredBlock.await(1, TimeUnit.SECONDS))
                    true
                },
                removeCallbacks = {},
                timeoutMs = 20L,
                onWarn = {},
                onDeferredComplete = {
                    assertTrue("onDeferredComplete must run after block finishes", blockFinished.get())
                    deferredCalls.incrementAndGet()
                    deferredLatch.countDown()
                },
            ) {
                enteredBlock.countDown()
                assertTrue(allowBlockFinish.await(2, TimeUnit.SECONDS))
                blockFinished.set(true)
            }

            assertEquals(CameraStreamManager.MainWaitResult.TIMED_OUT, result)
            assertEquals(0, deferredCalls.get())
            allowBlockFinish.countDown()
            assertTrue(deferredLatch.await(2, TimeUnit.SECONDS))
            assertEquals(1, deferredCalls.get())
        } finally {
            fakeMain.shutdownNow()
        }

        // Interrupt while block() is ALREADY mid-execution restores interrupt and invokes onDeferredComplete.
        val fakeMainInterrupt = Executors.newSingleThreadExecutor { r ->
            Thread(r, "fake-main-interrupt").apply { isDaemon = true }
        }
        try {
            val enteredBlock = CountDownLatch(1)
            val allowBlockFinish = CountDownLatch(1)
            val deferredLatch = CountDownLatch(1)
            val callerThread = Thread.currentThread()

            val result = try {
                CameraStreamManager.runOnMainBlocking(
                    isMainThread = { false },
                    post = { runnable ->
                        fakeMainInterrupt.execute(runnable)
                        assertTrue(enteredBlock.await(1, TimeUnit.SECONDS))
                        callerThread.interrupt()
                        true
                    },
                    removeCallbacks = {},
                    timeoutMs = 2_000L,
                    onWarn = {},
                    onDeferredComplete = {
                        deferredCalls.incrementAndGet()
                        deferredLatch.countDown()
                    },
                ) {
                    enteredBlock.countDown()
                    assertTrue(allowBlockFinish.await(2, TimeUnit.SECONDS))
                }
            } finally {
                Thread.interrupted()
            }

            assertEquals(CameraStreamManager.MainWaitResult.INTERRUPTED, result)
            assertEquals(1, deferredCalls.get())
            allowBlockFinish.countDown()
            assertTrue(deferredLatch.await(2, TimeUnit.SECONDS))
            assertEquals(2, deferredCalls.get())
        } finally {
            fakeMainInterrupt.shutdownNow()
        }
    }

    @Test
    fun retirePipelineOnUnbindOutcomeDefersReleaseAcrossTimeoutInterruptAndPostReject() {
        // 1. COMPLETED -> retires immediately
        val completedRetire = AtomicInteger(0)
        val completedRetry = AtomicInteger(0)
        CameraStreamManager.retirePipelineOnUnbindOutcome(
            runBlockingWait = { _, block ->
                block()
                CameraStreamManager.MainWaitOutcome(
                    CameraStreamManager.MainWaitResult.COMPLETED,
                    wasRunningOnMain = false,
                )
            },
            unbindOnMain = {},
            scheduleMainRetry = {
                completedRetry.incrementAndGet()
                true
            },
            retire = { completedRetire.incrementAndGet() },
        )
        assertEquals(1, completedRetire.get())
        assertEquals(0, completedRetry.get())

        // 2. TIMED_OUT canceled before start -> defers retire until async retry unbinds
        val canceledRetire = AtomicInteger(0)
        val canceledUnbind = AtomicInteger(0)
        val retryHolder = AtomicReference<Runnable?>()
        CameraStreamManager.retirePipelineOnUnbindOutcome(
            runBlockingWait = { _, _ ->
                CameraStreamManager.MainWaitOutcome(
                    CameraStreamManager.MainWaitResult.TIMED_OUT,
                    wasRunningOnMain = false,
                )
            },
            unbindOnMain = { canceledUnbind.incrementAndGet() },
            scheduleMainRetry = { runnable ->
                retryHolder.set(runnable)
                true
            },
            retire = {
                assertEquals("Unbind must run before retire", 1, canceledUnbind.get())
                canceledRetire.incrementAndGet()
            },
        )
        assertEquals(0, canceledRetire.get())
        assertNotNull(retryHolder.get())
        retryHolder.get()!!.run()
        assertEquals(1, canceledUnbind.get())
        assertEquals(1, canceledRetire.get())

        // 3. INTERRUPTED while mid-execution on Main -> no retry scheduled; onDeferredComplete retires once
        val midRunRetire = AtomicInteger(0)
        val midRunRetry = AtomicInteger(0)
        val deferredCallback = AtomicReference<(() -> Unit)?>(null)
        CameraStreamManager.retirePipelineOnUnbindOutcome(
            runBlockingWait = { onDeferred, _ ->
                deferredCallback.set(onDeferred)
                CameraStreamManager.MainWaitOutcome(
                    CameraStreamManager.MainWaitResult.INTERRUPTED,
                    wasRunningOnMain = true,
                )
            },
            unbindOnMain = {},
            scheduleMainRetry = {
                midRunRetry.incrementAndGet()
                true
            },
            retire = { midRunRetire.incrementAndGet() },
        )
        assertEquals(0, midRunRetry.get())
        assertEquals(0, midRunRetire.get())
        deferredCallback.get()!!.invoke()
        assertEquals(1, midRunRetire.get())

        // 4. POST_REJECTED when retry scheduling itself fails -> falls back to retiring once
        val postRejectRetire = AtomicInteger(0)
        CameraStreamManager.retirePipelineOnUnbindOutcome(
            runBlockingWait = { _, _ ->
                CameraStreamManager.MainWaitOutcome(
                    CameraStreamManager.MainWaitResult.POST_REJECTED,
                    wasRunningOnMain = false,
                )
            },
            unbindOnMain = {},
            scheduleMainRetry = { false },
            retire = { postRejectRetire.incrementAndGet() },
        )
        assertEquals(1, postRejectRetire.get())
    }

    @Test
    fun shouldFallbackFullRebindAfterUiFailureOnlyWhenEncoderLost() {
        assertFalse(CameraStreamManager.shouldFallbackFullRebindAfterUiFailure(encoderStillBound = true))
        assertTrue(CameraStreamManager.shouldFallbackFullRebindAfterUiFailure(encoderStillBound = false))
    }

    @Test
    fun rebindEligibilityChecksSessionOwnershipAndMonotonicConfigSeq() {
        assertTrue(
            CameraStreamManager.shouldApplyRebind(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 7L,
                requestSessionId = 7L,
                configSeq = 3L,
                lastAppliedConfigSeq = 2L,
            ),
        )
        assertTrue(
            CameraStreamManager.shouldApplyRebind(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 7L,
                requestSessionId = 7L,
                configSeq = 3L,
                lastAppliedConfigSeq = 3L,
            ),
        )
        // Stale configSeq rejected
        assertFalse(
            CameraStreamManager.shouldApplyRebind(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 7L,
                requestSessionId = 7L,
                configSeq = 2L,
                lastAppliedConfigSeq = 3L,
            ),
        )
        // Stale sessionId rejected
        assertFalse(
            CameraStreamManager.shouldApplyRebind(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 8L,
                requestSessionId = 7L,
                configSeq = 10L,
                lastAppliedConfigSeq = 0L,
            ),
        )
        // Released or not streaming rejected
        assertFalse(
            CameraStreamManager.shouldApplyRebind(
                isReleased = true,
                isStreaming = true,
                activeSessionId = 7L,
                requestSessionId = 7L,
                configSeq = 10L,
                lastAppliedConfigSeq = 0L,
            ),
        )
        assertFalse(
            CameraStreamManager.shouldApplyRebind(
                isReleased = false,
                isStreaming = false,
                activeSessionId = 7L,
                requestSessionId = 7L,
                configSeq = 10L,
                lastAppliedConfigSeq = 0L,
            ),
        )
    }

    @Test
    fun sessionSlotValidationAndSurfaceProviderResolutionCheck() {
        assertTrue(
            CameraStreamManager.isSessionSlotValid(
                activeSessionId = 5L,
                requestSessionId = 5L,
                slotSessionId = 5L,
                slotRetiring = false,
            ),
        )
        assertFalse(
            CameraStreamManager.isSessionSlotValid(
                activeSessionId = 5L,
                requestSessionId = 5L,
                slotSessionId = 5L,
                slotRetiring = true,
            ),
        )
        assertFalse(
            CameraStreamManager.isSessionSlotValid(
                activeSessionId = 6L,
                requestSessionId = 5L,
                slotSessionId = 5L,
                slotRetiring = false,
            ),
        )
        assertFalse(
            CameraStreamManager.isSessionSlotValid(
                activeSessionId = 0L,
                requestSessionId = 0L,
                slotSessionId = 0L,
                slotRetiring = false,
            ),
        )

        assertTrue(
            CameraStreamManager.isSurfaceProvideEligible(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 5L,
                snapshotSessionId = 5L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                surfaceValid = true,
                snapshotWidth = 1920,
                snapshotHeight = 1080,
                requestWidth = 1920,
                requestHeight = 1080,
            ),
        )
        // Mismatched resolution rejected
        assertFalse(
            CameraStreamManager.isSurfaceProvideEligible(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 5L,
                snapshotSessionId = 5L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                surfaceValid = true,
                snapshotWidth = 1920,
                snapshotHeight = 1080,
                requestWidth = 1280,
                requestHeight = 720,
            ),
        )
        // Retiring slot or mismatched slot rejected
        assertFalse(
            CameraStreamManager.isSurfaceProvideEligible(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 5L,
                snapshotSessionId = 5L,
                activeSlotMatchesSnapshot = false,
                snapshotRetiring = false,
                surfaceValid = true,
                snapshotWidth = 1920,
                snapshotHeight = 1080,
                requestWidth = 1920,
                requestHeight = 1080,
            ),
        )
        assertFalse(
            CameraStreamManager.isSurfaceProvideEligible(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 5L,
                snapshotSessionId = 5L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = true,
                surfaceValid = true,
                snapshotWidth = 1920,
                snapshotHeight = 1080,
                requestWidth = 1920,
                requestHeight = 1080,
            ),
        )
    }

    @Test
    fun distinctOwnedUseCasesFiltersNullsAndDuplicates() {
        val owned = CameraStreamManager.distinctOwnedUseCases("enc", null, "ui", "enc")
        assertEquals(listOf("enc", "ui"), owned)
        assertTrue(CameraStreamManager.distinctOwnedUseCases<String>(null, null).isEmpty())
    }
}
