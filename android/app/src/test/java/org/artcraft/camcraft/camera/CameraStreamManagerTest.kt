package org.artcraft.camcraft.camera

import androidx.camera.core.SurfaceRequest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.artcraft.camcraft.protocol.PacketType
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
            val mainFuture = AtomicReference<Future<*>?>(null)

            CameraStreamManager.runOnMainBlocking(
                isMainThread = { false },
                post = { runnable ->
                    mainFuture.set(fakeMainExecutor.submit(runnable))
                    true
                },
                removeCallbacks = { runnable -> removedRunnables.add(runnable) },
                timeoutMs = 1_500L,
                onWarn = { warnings.add(it) },
            ) {
                executions.incrementAndGet()
                executedThread.set(Thread.currentThread().name)
            }

            mainFuture.get()?.get(2, TimeUnit.SECONDS)
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
            val mainFuture = AtomicReference<Future<*>?>(null)

            val result = CameraStreamManager.runOnMainBlocking(
                isMainThread = { false },
                post = { runnable ->
                    mainFuture.set(fakeMain.submit(runnable))
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
            mainFuture.get()?.get(2, TimeUnit.SECONDS)
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
            val mainFuture = AtomicReference<Future<*>?>(null)
            val callerThread = Thread.currentThread()

            val result = try {
                CameraStreamManager.runOnMainBlocking(
                    isMainThread = { false },
                    post = { runnable ->
                        mainFuture.set(fakeMainInterrupt.submit(runnable))
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
            mainFuture.get()?.get(2, TimeUnit.SECONDS)
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

    @Test
    fun lifecycleActivationRequiresCurrentSessionAndGeneration() {
        // Replacement rebind generation activates lifecycle even if initial startStream was superseded.
        assertTrue(
            CameraStreamManager.shouldActivateLifecycleOnBind(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 4L,
                snapshotSessionId = 4L,
                currentGeneration = 2L,
                snapshotGeneration = 2L,
            ),
        )
        // Superseded initial generation cannot activate lifecycle.
        assertFalse(
            CameraStreamManager.shouldActivateLifecycleOnBind(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 4L,
                snapshotSessionId = 4L,
                currentGeneration = 2L,
                snapshotGeneration = 1L,
            ),
        )
        // Stopped or released stream cannot activate lifecycle.
        assertFalse(
            CameraStreamManager.shouldActivateLifecycleOnBind(
                isReleased = false,
                isStreaming = false,
                activeSessionId = 4L,
                snapshotSessionId = 4L,
                currentGeneration = 2L,
                snapshotGeneration = 2L,
            ),
        )
        assertFalse(
            CameraStreamManager.shouldActivateLifecycleOnBind(
                isReleased = true,
                isStreaming = true,
                activeSessionId = 4L,
                snapshotSessionId = 4L,
                currentGeneration = 2L,
                snapshotGeneration = 2L,
            ),
        )
        // Stale session cannot activate lifecycle.
        assertFalse(
            CameraStreamManager.shouldActivateLifecycleOnBind(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 5L,
                snapshotSessionId = 4L,
                currentGeneration = 2L,
                snapshotGeneration = 2L,
            ),
        )
    }

    @Test
    fun activeEncoderSlotReleaseLatchAndRetryHookCoordinateConcurrentRelease() {
        val retiringSlot = ActiveEncoderSlot(
            sessionId = 3L,
            generation = 1L,
            encoder = null,
            width = 1920,
            height = 1080,
            fps = 60,
            cameraId = "0",
            surfaceTracker = EncoderSurfaceRetirementTracker(),
            isRetiring = true,
        )

        assertFalse(retiringSlot.isReleased)
        assertFalse(retiringSlot.awaitReleased(15L))

        val releaseExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-encoder-release").apply { isDaemon = true }
        }
        val encoderExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-encoder-setup").apply { isDaemon = true }
        }
        try {
            val hookTriggered = CountDownLatch(1)
            val hookCalls = AtomicInteger(0)
            val setupThreadName = AtomicReference<String?>()

            // Simulate failed encoder creation while retiringSlot is still releasing, then attach retry hook.
            assertTrue(
                CameraStreamManager.shouldRetryEncoderSetupAfterFailure(
                    isReleased = false,
                    isStreaming = true,
                    activeSessionId = 3L,
                    requestSessionId = 3L,
                    currentGeneration = 2L,
                    expectedGeneration = 2L,
                    hasRetriedAfterRelease = false,
                    priorSlotAlreadyReleased = false,
                ),
            )
            retiringSlot.onReleased {
                encoderExecutor.execute {
                    setupThreadName.set(Thread.currentThread().name)
                    hookCalls.incrementAndGet()
                    hookTriggered.countDown()
                }
            }
            assertEquals(0, hookCalls.get())

            // Simulate dedicated encoderReleaseExecutor completing release & calling markReleased().
            releaseExecutor.execute {
                retiringSlot.markReleased()
            }

            assertTrue(retiringSlot.awaitReleased(2_000L))
            assertTrue(hookTriggered.await(2_000L, TimeUnit.MILLISECONDS))
            assertEquals(1, hookCalls.get())
            assertEquals("test-encoder-setup", setupThreadName.get())
            assertTrue(retiringSlot.isReleased)

            // Idempotent markReleased() must not re-fire already invoked hook.
            retiringSlot.markReleased()
            assertEquals(1, hookCalls.get())

            // Attaching a late listener after markReleased() already ran invokes immediately once.
            val lateCalls = AtomicInteger(0)
            retiringSlot.onReleased { lateCalls.incrementAndGet() }
            assertEquals(1, lateCalls.get())

            // No retry when prior slot had already finished releasing, already retried, or generation changed.
            assertFalse(
                CameraStreamManager.shouldRetryEncoderSetupAfterFailure(
                    isReleased = false,
                    isStreaming = true,
                    activeSessionId = 3L,
                    requestSessionId = 3L,
                    currentGeneration = 2L,
                    expectedGeneration = 2L,
                    hasRetriedAfterRelease = false,
                    priorSlotAlreadyReleased = true,
                ),
            )
            assertFalse(
                CameraStreamManager.shouldRetryEncoderSetupAfterFailure(
                    isReleased = false,
                    isStreaming = true,
                    activeSessionId = 3L,
                    requestSessionId = 3L,
                    currentGeneration = 2L,
                    expectedGeneration = 2L,
                    hasRetriedAfterRelease = true,
                    priorSlotAlreadyReleased = false,
                ),
            )
            assertFalse(
                CameraStreamManager.shouldRetryEncoderSetupAfterFailure(
                    isReleased = false,
                    isStreaming = true,
                    activeSessionId = 3L,
                    requestSessionId = 3L,
                    currentGeneration = 3L,
                    expectedGeneration = 2L,
                    hasRetriedAfterRelease = false,
                    priorSlotAlreadyReleased = false,
                ),
            )
        } finally {
            releaseExecutor.shutdownNow()
            encoderExecutor.shutdownNow()
        }
    }

    @Test
    fun evaluateSurfaceRequestResolutionHandlesExactMatchReconfigureOnceAndStaleReject() {
        // 1. Exact size match -> PROVIDE_SURFACE
        assertEquals(
            CameraStreamManager.SurfaceResolutionDecision.PROVIDE_SURFACE,
            CameraStreamManager.evaluateSurfaceRequestResolution(
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
                resolutionAlreadyAdjusted = false,
            ),
        )

        // 2. CameraX fell back to valid even size -> RECONFIGURE_ENCODER once
        assertEquals(
            CameraStreamManager.SurfaceResolutionDecision.RECONFIGURE_ENCODER,
            CameraStreamManager.evaluateSurfaceRequestResolution(
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
                resolutionAlreadyAdjusted = false,
            ),
        )

        // 3. Subsequent mismatch on already-adjusted generation -> REJECT_STALE (no infinite rebind loop)
        assertEquals(
            CameraStreamManager.SurfaceResolutionDecision.REJECT_STALE,
            CameraStreamManager.evaluateSurfaceRequestResolution(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 5L,
                snapshotSessionId = 5L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                surfaceValid = true,
                snapshotWidth = 1280,
                snapshotHeight = 720,
                requestWidth = 640,
                requestHeight = 480,
                resolutionAlreadyAdjusted = true,
            ),
        )

        // 4. Odd or non-positive resolution -> REJECT_STALE
        assertEquals(
            CameraStreamManager.SurfaceResolutionDecision.REJECT_STALE,
            CameraStreamManager.evaluateSurfaceRequestResolution(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 5L,
                snapshotSessionId = 5L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                surfaceValid = true,
                snapshotWidth = 1920,
                snapshotHeight = 1080,
                requestWidth = 1279,
                requestHeight = 720,
                resolutionAlreadyAdjusted = false,
            ),
        )
        assertEquals(
            CameraStreamManager.SurfaceResolutionDecision.REJECT_STALE,
            CameraStreamManager.evaluateSurfaceRequestResolution(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 5L,
                snapshotSessionId = 5L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                surfaceValid = true,
                snapshotWidth = 1920,
                snapshotHeight = 1080,
                requestWidth = 0,
                requestHeight = 720,
                resolutionAlreadyAdjusted = false,
            ),
        )

        // 5. Stale / retiring / invalid surface -> REJECT_STALE even if size differs
        assertEquals(
            CameraStreamManager.SurfaceResolutionDecision.REJECT_STALE,
            CameraStreamManager.evaluateSurfaceRequestResolution(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 6L,
                snapshotSessionId = 5L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                surfaceValid = true,
                snapshotWidth = 1920,
                snapshotHeight = 1080,
                requestWidth = 1280,
                requestHeight = 720,
                resolutionAlreadyAdjusted = false,
            ),
        )
    }

    @Test
    fun resolutionAdjustmentPublishAndFailureHelpersGateUntilSurfaceAcceptance() {
        val normalSlot = ActiveEncoderSlot(
            sessionId = 7L,
            generation = 1L,
            encoder = null,
            width = 1920,
            height = 1080,
            fps = 60,
            cameraId = "0",
            surfaceTracker = EncoderSurfaceRetirementTracker(),
            cameraConfigId = 4L,
            pendingResolutionAdjustment = false,
        )
        assertFalse(normalSlot.markResolutionAdjustmentReported())
        assertFalse(
            CameraStreamManager.shouldPublishResolutionAdjustment(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 7L,
                snapshotSessionId = 7L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                pendingResolutionAdjustment = normalSlot.pendingResolutionAdjustment,
            ),
        )

        val adjustedSlot = ActiveEncoderSlot(
            sessionId = 7L,
            generation = 2L,
            encoder = null,
            width = 1280,
            height = 720,
            fps = 60,
            cameraId = "0",
            surfaceTracker = EncoderSurfaceRetirementTracker(),
            cameraConfigId = 4L,
            pendingResolutionAdjustment = true,
            fallbackWidth = 1920,
            fallbackHeight = 1080,
        )
        assertTrue(
            CameraStreamManager.shouldPublishResolutionAdjustment(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 7L,
                snapshotSessionId = 7L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                pendingResolutionAdjustment = adjustedSlot.pendingResolutionAdjustment,
            ),
        )
        // Outcome reporting is strictly one-shot per adjusted slot.
        assertTrue(adjustedSlot.markResolutionAdjustmentReported())
        assertFalse(adjustedSlot.markResolutionAdjustmentReported())

        // Unaccepted mismatched size on an active pending slot reports negotiation failure instead of success.
        assertTrue(
            CameraStreamManager.shouldReportResolutionNegotiationFailure(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 7L,
                snapshotSessionId = 7L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                surfaceValid = true,
                pendingResolutionAdjustment = true,
                snapshotWidth = 1280,
                snapshotHeight = 720,
                requestWidth = 640,
                requestHeight = 480,
            ),
        )
        // Matching size or retired slot never triggers unaccepted-negotiation failure.
        assertFalse(
            CameraStreamManager.shouldReportResolutionNegotiationFailure(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 7L,
                snapshotSessionId = 7L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                surfaceValid = true,
                pendingResolutionAdjustment = true,
                snapshotWidth = 1280,
                snapshotHeight = 720,
                requestWidth = 1280,
                requestHeight = 720,
            ),
        )
        assertFalse(
            CameraStreamManager.shouldReportResolutionNegotiationFailure(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 7L,
                snapshotSessionId = 7L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = true,
                surfaceValid = true,
                pendingResolutionAdjustment = true,
                snapshotWidth = 1280,
                snapshotHeight = 720,
                requestWidth = 640,
                requestHeight = 480,
            ),
        )
    }

    @Test
    fun resolutionAdjustmentSuccessPublishesOnlyOnFirstVideoFrameAndRejectsInvalidSurfaceResult() {
        // VIDEO_CONFIG must NOT prematurely publish adjusted resolution before first VIDEO_FRAME arrives.
        assertFalse(
            CameraStreamManager.shouldPublishResolutionOnVideoPacket(
                packetType = PacketType.VIDEO_CONFIG,
                isReleased = false,
                isStreaming = true,
                activeSessionId = 8L,
                snapshotSessionId = 8L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                pendingResolutionAdjustment = true,
            ),
        )
        // First VIDEO_FRAME for still-current replacement slot IS eligible to publish.
        assertTrue(
            CameraStreamManager.shouldPublishResolutionOnVideoPacket(
                packetType = PacketType.VIDEO_FRAME,
                isReleased = false,
                isStreaming = true,
                activeSessionId = 8L,
                snapshotSessionId = 8L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                pendingResolutionAdjustment = true,
            ),
        )
        // Retiring or stale slot must NOT publish even on VIDEO_FRAME.
        assertFalse(
            CameraStreamManager.shouldPublishResolutionOnVideoPacket(
                packetType = PacketType.VIDEO_FRAME,
                isReleased = false,
                isStreaming = true,
                activeSessionId = 9L,
                snapshotSessionId = 8L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                pendingResolutionAdjustment = true,
            ),
        )

        // Surface provider result listener distinguishes normal teardown vs CameraX rejection codes.
        assertFalse(
            CameraStreamManager.isSurfaceResultRejection(
                SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY,
            ),
        )
        assertTrue(
            CameraStreamManager.isSurfaceResultRejection(
                SurfaceRequest.Result.RESULT_INVALID_SURFACE,
            ),
        )
        assertTrue(
            CameraStreamManager.isSurfaceResultRejection(
                SurfaceRequest.Result.RESULT_REQUEST_CANCELLED,
            ),
        )
    }

    @Test
    fun failedNativeCodecReleaseDoesNotMarkSlotReleasedOrTriggerRetry() {
        val retiringSlot = ActiveEncoderSlot(
            sessionId = 4L,
            generation = 1L,
            encoder = null,
            width = 1920,
            height = 1080,
            fps = 60,
            cameraId = "0",
            surfaceTracker = EncoderSurfaceRetirementTracker(),
            isRetiring = true,
        )
        val retryCalls = AtomicInteger(0)
        retiringSlot.onReleased { retryCalls.incrementAndGet() }

        retiringSlot.markReleaseFinished(codecReleased = false)

        // awaitReleased returns immediately with false when native release failed.
        assertFalse(retiringSlot.awaitReleased(2_000L))
        assertTrue(retiringSlot.isTeardownFinished)
        assertTrue(retiringSlot.isReleaseFailed)
        assertFalse(retiringSlot.isReleased)
        assertEquals(0, retryCalls.get())

        // Late onReleased attachment after release failure must also stay silent.
        retiringSlot.onReleased { retryCalls.incrementAndGet() }
        assertEquals(0, retryCalls.get())

        assertFalse(
            CameraStreamManager.shouldRetryEncoderSetupAfterFailure(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 4L,
                requestSessionId = 4L,
                currentGeneration = 2L,
                expectedGeneration = 2L,
                hasRetriedAfterRelease = false,
                priorSlotAlreadyReleased = false,
                priorSlotReleaseFailed = retiringSlot.isReleaseFailed,
            ),
        )
    }

    @Test
    fun shouldAdvanceCameraConfigForRebindDistinguishesSupersedingRetriesFromBitrateOnly() {
        // Same camera/geometry while pipeline is healthy and no adjustment in flight -> preserve token.
        assertFalse(
            CameraStreamManager.shouldAdvanceCameraConfigForRebind(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 5L,
                requestSessionId = 5L,
                pipelineHealthyOrStarting = true,
                resolutionAdjustmentInFlight = false,
                requestedCameraId = "0",
                requestedWidth = 1920,
                requestedHeight = 1080,
                requestedFps = 60,
                newCameraId = "0",
                newWidth = 1920,
                newHeight = 1080,
                newFps = 60,
            ),
        )
        // Superseding retry during in-flight negotiation OR after pipeline failure -> advance token.
        assertTrue(
            CameraStreamManager.shouldAdvanceCameraConfigForRebind(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 5L,
                requestSessionId = 5L,
                pipelineHealthyOrStarting = true,
                resolutionAdjustmentInFlight = true,
                requestedCameraId = "0",
                requestedWidth = 1920,
                requestedHeight = 1080,
                requestedFps = 60,
                newCameraId = "0",
                newWidth = 1920,
                newHeight = 1080,
                newFps = 60,
            ),
        )
        assertTrue(
            CameraStreamManager.shouldAdvanceCameraConfigForRebind(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 5L,
                requestSessionId = 5L,
                pipelineHealthyOrStarting = false,
                resolutionAdjustmentInFlight = false,
                requestedCameraId = "0",
                requestedWidth = 1920,
                requestedHeight = 1080,
                requestedFps = 60,
                newCameraId = "0",
                newWidth = 1920,
                newHeight = 1080,
                newFps = 60,
            ),
        )
    }

    @Test
    fun onReleaseFinishedObservesBothOutcomesAndPublishesResultBeforeTeardownFinished() {
        val successSlot = ActiveEncoderSlot(
            sessionId = 10L,
            generation = 1L,
            encoder = null,
            width = 1920,
            height = 1080,
            fps = 60,
            cameraId = "0",
            surfaceTracker = EncoderSurfaceRetirementTracker(),
        )
        val successEvents = mutableListOf<Boolean>()
        successSlot.onReleaseFinished { successEvents.add(it) }
        successSlot.markReleaseFinished(codecReleased = true)
        assertTrue(successSlot.isTeardownFinished)
        assertTrue(successSlot.isReleased)
        assertFalse(successSlot.isReleaseFailed)
        assertTrue(successSlot.awaitReleased(10L))
        assertEquals(listOf(true), successEvents)

        val lateSuccessEvents = mutableListOf<Boolean>()
        successSlot.onReleaseFinished { lateSuccessEvents.add(it) }
        assertEquals(listOf(true), lateSuccessEvents)

        val failSlot = ActiveEncoderSlot(
            sessionId = 11L,
            generation = 1L,
            encoder = null,
            width = 1920,
            height = 1080,
            fps = 60,
            cameraId = "0",
            surfaceTracker = EncoderSurfaceRetirementTracker(),
        )
        val failEvents = mutableListOf<Boolean>()
        failSlot.onReleaseFinished { failEvents.add(it) }
        failSlot.markReleaseFinished(codecReleased = false)
        assertTrue(failSlot.isTeardownFinished)
        assertFalse(failSlot.isReleased)
        assertTrue(failSlot.isReleaseFailed)
        assertFalse(failSlot.awaitReleased(10L))
        assertEquals(listOf(false), failEvents)

        val lateFailEvents = mutableListOf<Boolean>()
        failSlot.onReleaseFinished { lateFailEvents.add(it) }
        assertEquals(listOf(false), lateFailEvents)
    }

    @Test
    fun terminalSetupFailureNegotiatedDimsControlTokensAndSurfaceRequestIdentity() {
        // Terminal failure reports for current generation regardless of adjustment, but defers when retry pending.
        assertTrue(
            CameraStreamManager.shouldReportTerminalEncoderSetupFailure(
                hasPendingRetry = false,
                isCurrentGeneration = true,
            ),
        )
        assertFalse(
            CameraStreamManager.shouldReportTerminalEncoderSetupFailure(
                hasPendingRetry = true,
                isCurrentGeneration = true,
            ),
        )
        assertFalse(
            CameraStreamManager.shouldReportTerminalEncoderSetupFailure(
                hasPendingRetry = false,
                isCurrentGeneration = false,
            ),
        )

        // Older active slot cannot overwrite dimensions recorded by a newer accepted geometry request.
        assertTrue(CameraStreamManager.shouldApplyNegotiatedDimensionsToRequest(2L, 2L))
        assertFalse(CameraStreamManager.shouldApplyNegotiatedDimensionsToRequest(1L, 2L))
        assertFalse(CameraStreamManager.shouldApplyNegotiatedDimensionsToRequest(0L, 0L))

        // Stale captured control tokens do not overwrite newer accepted controls.
        assertTrue(CameraStreamManager.shouldApplyControlToken(3L, 3L))
        assertFalse(CameraStreamManager.shouldApplyControlToken(2L, 3L))
        assertFalse(CameraStreamManager.shouldApplyControlToken(0L, 0L))

        // SurfaceRequest identity ignores stale RESULT_REQUEST_CANCELLED while keeping current & other rejections.
        val slot = ActiveEncoderSlot(
            sessionId = 12L,
            generation = 1L,
            encoder = null,
            width = 1920,
            height = 1080,
            fps = 60,
            cameraId = "0",
            surfaceTracker = EncoderSurfaceRetirementTracker(),
        )
        val req1 = Any()
        val req2 = Any()
        slot.setCurrentSurfaceRequest(req1)
        slot.setCurrentSurfaceRequest(req2)
        assertFalse(slot.clearCurrentSurfaceRequestIfMatches(req1))
        assertTrue(slot.isCurrentSurfaceRequest(req2))
        assertFalse(
            CameraStreamManager.isSurfaceResultRejection(
                SurfaceRequest.Result.RESULT_REQUEST_CANCELLED,
                isCurrentRequest = false,
            ),
        )
        assertTrue(
            CameraStreamManager.isSurfaceResultRejection(
                SurfaceRequest.Result.RESULT_INVALID_SURFACE,
                isCurrentRequest = false,
            ),
        )
        assertTrue(slot.clearCurrentSurfaceRequestIfMatches(req2))
        assertTrue(
            CameraStreamManager.isSurfaceResultRejection(
                SurfaceRequest.Result.RESULT_REQUEST_CANCELLED,
                isCurrentRequest = true,
            ),
        )

        // Intentional unbind before fallback rebind clears active request so subsequent cancel is ignored.
        val req3 = Any()
        slot.setCurrentSurfaceRequest(req3)
        slot.clearCurrentSurfaceRequest()
        assertFalse(slot.isCurrentSurfaceRequest(req3))
        assertFalse(slot.clearCurrentSurfaceRequestIfMatches(req3))
        assertFalse(
            CameraStreamManager.isSurfaceResultRejection(
                SurfaceRequest.Result.RESULT_REQUEST_CANCELLED,
                isCurrentRequest = false,
            ),
        )
    }

    @Test
    fun bindFailureHelperHandlesAllCurrentSlotsIncludingLensSwitchAndDeduplicates() {
        // Ordinary start / lens switch (pendingResolutionAdjustment = false) and resolution
        // adjustments alike must report failure when the slot is still current.
        assertTrue(
            CameraStreamManager.shouldReportCurrentSlotPipelineFailure(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 3L,
                snapshotSessionId = 3L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                slotGeometryRequestSeq = 2L,
                latestGeometryRequestSeq = 2L,
            ),
        )
        assertFalse(
            CameraStreamManager.shouldReportCurrentSlotPipelineFailure(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 3L,
                snapshotSessionId = 3L,
                activeSlotMatchesSnapshot = false,
                snapshotRetiring = false,
                slotGeometryRequestSeq = 2L,
                latestGeometryRequestSeq = 2L,
            ),
        )
        assertFalse(
            CameraStreamManager.shouldReportCurrentSlotPipelineFailure(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 3L,
                snapshotSessionId = 3L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = true,
                slotGeometryRequestSeq = 2L,
                latestGeometryRequestSeq = 2L,
            ),
        )
        assertFalse(
            CameraStreamManager.shouldReportCurrentSlotPipelineFailure(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 3L,
                snapshotSessionId = 3L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = false,
                slotGeometryRequestSeq = 1L,
                latestGeometryRequestSeq = 2L,
            ),
        )

        val ordinarySlot = ActiveEncoderSlot(
            sessionId = 3L,
            generation = 2L,
            encoder = null,
            width = 1920,
            height = 1080,
            fps = 60,
            cameraId = "1",
            surfaceTracker = EncoderSurfaceRetirementTracker(),
            pendingResolutionAdjustment = false,
        )
        // Healthy slot permits in-place bitrate updates until terminal failure is claimed.
        assertTrue(CameraStreamManager.isSlotUsableForRebind(ordinarySlot))
        val healthyDecision = CameraStreamManager.evaluateRebindDecision(
            cameraChanged = false,
            geometryOrFpsChanged = false,
            bitrateChanged = true,
            hasEncoder = CameraStreamManager.isSlotUsableForRebind(ordinarySlot),
        )
        assertFalse(healthyDecision.needsEncoderRestart)
        assertTrue(healthyDecision.needsBitrateUpdate)

        assertTrue(ordinarySlot.markTerminalFailureReported())
        assertTrue(ordinarySlot.isTerminalFailureReported)
        assertTrue(ordinarySlot.isRetiring)
        assertFalse(CameraStreamManager.isSlotUsableForRebind(ordinarySlot))
        val failedDecision = CameraStreamManager.evaluateRebindDecision(
            cameraChanged = false,
            geometryOrFpsChanged = false,
            bitrateChanged = true,
            hasEncoder = CameraStreamManager.isSlotUsableForRebind(ordinarySlot),
        )
        assertTrue(failedDecision.needsEncoderRestart)
        assertFalse(failedDecision.needsBitrateUpdate)
        assertFalse(ordinarySlot.markTerminalFailureReported())

        val adjustmentSlot = ActiveEncoderSlot(
            sessionId = 3L,
            generation = 3L,
            encoder = null,
            width = 1280,
            height = 720,
            fps = 60,
            cameraId = "1",
            surfaceTracker = EncoderSurfaceRetirementTracker(),
            pendingResolutionAdjustment = true,
        )
        assertTrue(CameraStreamManager.isSlotUsableForRebind(adjustmentSlot))
        assertTrue(adjustmentSlot.markTerminalFailureReported())
        assertTrue(adjustmentSlot.isTerminalFailureReported)
        assertTrue(adjustmentSlot.isRetiring)
        assertFalse(CameraStreamManager.isSlotUsableForRebind(adjustmentSlot))
        assertFalse(adjustmentSlot.hasPendingResolutionAdjustmentUnreported())
        assertFalse(adjustmentSlot.markResolutionAdjustmentReported())
        assertFalse(adjustmentSlot.markTerminalFailureReported())
    }

    @Test
    fun startEncoderHandlingDrainThreadOomRoutesOnlyStartupOomToTerminalFailure() {
        var normalStarted = false
        CameraStreamManager.startEncoderHandlingDrainThreadOom {
            normalStarted = true
        }
        assertTrue(normalStarted)

        val oom = OutOfMemoryError("pthread_create failed")
        val wrapped = try {
            CameraStreamManager.startEncoderHandlingDrainThreadOom {
                throw oom
            }
            null
        } catch (ex: EncoderDrainThreadStartupException) {
            ex
        }
        assertNotNull(wrapped)
        assertSame(oom, wrapped!!.cause)
        assertFalse(CameraStreamManager.isRetryableEncoderSetupFailure(wrapped))
        assertTrue(CameraStreamManager.isRetryableEncoderSetupFailure(IllegalStateException("codec busy")))

        val unrelatedAssertion = AssertionError("unrelated fatal error")
        val propagatedAssertion = try {
            CameraStreamManager.startEncoderHandlingDrainThreadOom {
                throw unrelatedAssertion
            }
            null
        } catch (err: AssertionError) {
            err
        }
        assertSame(unrelatedAssertion, propagatedAssertion)
    }

    @Test
    fun shouldApplyAutoFocusOnMainSkipsSupersededTapAndAllowsUnsupersededCommand() {
        assertTrue(
            CameraStreamManager.shouldApplyAutoFocusOnMain(
                isReleased = false,
                token = 4L,
                latestAutoFocusToken = 4L,
            ),
        )
        // Newer tap-to-focus advances latestAutoFocusToken, so queued Main runnable must skip.
        assertFalse(
            CameraStreamManager.shouldApplyAutoFocusOnMain(
                isReleased = false,
                token = 4L,
                latestAutoFocusToken = 5L,
            ),
        )
        assertFalse(
            CameraStreamManager.shouldApplyAutoFocusOnMain(
                isReleased = true,
                token = 5L,
                latestAutoFocusToken = 5L,
            ),
        )
        assertFalse(
            CameraStreamManager.shouldApplyAutoFocusOnMain(
                isReleased = false,
                token = 0L,
                latestAutoFocusToken = 0L,
            ),
        )
    }

    @Test
    fun isVideoPacketEligibleForWriteValidatesEncoderGenerationEvenWithSameSession() {
        // Active/starting slot accepts VIDEO_CONFIG before first frame and subsequent frames.
        assertTrue(
            CameraStreamManager.isVideoPacketEligibleForWrite(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 10L,
                packetSessionId = 10L,
                currentGeneration = 3L,
                packetGeneration = 3L,
                currentSlotSessionId = 10L,
                currentSlotGeneration = 3L,
                currentSlotRetiring = false,
            ),
        )

        // Retired encoder from earlier generation within the SAME session is rejected.
        assertFalse(
            CameraStreamManager.isVideoPacketEligibleForWrite(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 10L,
                packetSessionId = 10L,
                currentGeneration = 4L,
                packetGeneration = 3L,
                currentSlotSessionId = 10L,
                currentSlotGeneration = 4L,
                currentSlotRetiring = false,
            ),
        )

        // Retiring slot or cleared slot rejects packets even if pipelineGeneration hasn't bumped yet.
        assertFalse(
            CameraStreamManager.isVideoPacketEligibleForWrite(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 10L,
                packetSessionId = 10L,
                currentGeneration = 3L,
                packetGeneration = 3L,
                currentSlotSessionId = 10L,
                currentSlotGeneration = 3L,
                currentSlotRetiring = true,
            ),
        )
        assertFalse(
            CameraStreamManager.isVideoPacketEligibleForWrite(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 10L,
                packetSessionId = 10L,
                currentGeneration = 3L,
                packetGeneration = 3L,
                currentSlotSessionId = null,
                currentSlotGeneration = null,
                currentSlotRetiring = true,
            ),
        )

        // Session mismatch or stopped/released manager is rejected.
        assertFalse(
            CameraStreamManager.isVideoPacketEligibleForWrite(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 11L,
                packetSessionId = 10L,
                currentGeneration = 3L,
                packetGeneration = 3L,
                currentSlotSessionId = 11L,
                currentSlotGeneration = 3L,
                currentSlotRetiring = false,
            ),
        )
        assertFalse(
            CameraStreamManager.isVideoPacketEligibleForWrite(
                isReleased = false,
                isStreaming = false,
                activeSessionId = 10L,
                packetSessionId = 10L,
                currentGeneration = 3L,
                packetGeneration = 3L,
                currentSlotSessionId = 10L,
                currentSlotGeneration = 3L,
                currentSlotRetiring = false,
            ),
        )
    }

    @Test
    fun adjustedSlotRejectStaleReportsTerminalFailureEvenAfterNegotiatedResolutionReported() {
        val adjustedSlot = ActiveEncoderSlot(
            sessionId = 7L,
            generation = 2L,
            encoder = null,
            width = 1280,
            height = 720,
            fps = 60,
            cameraId = "0",
            surfaceTracker = EncoderSurfaceRetirementTracker(),
            cameraConfigId = 4L,
            geometryRequestSeq = 2L,
            pendingResolutionAdjustment = true,
            fallbackWidth = 1920,
            fallbackHeight = 1080,
            resolutionAdjusted = true,
        )

        // First frame committed negotiated dimensions, claiming markResolutionAdjustmentReported().
        assertTrue(adjustedSlot.markResolutionAdjustmentReported())
        adjustedSlot.negotiatedDimensionsCommitted = true
        assertFalse(adjustedSlot.markResolutionAdjustmentReported())

        // Later mismatched replacement request still rejects without rebinding, AND triggers failure path.
        assertEquals(
            CameraStreamManager.SurfaceResolutionDecision.REJECT_STALE,
            CameraStreamManager.evaluateSurfaceRequestResolution(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 7L,
                snapshotSessionId = 7L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = adjustedSlot.isRetiring,
                surfaceValid = true,
                snapshotWidth = adjustedSlot.width,
                snapshotHeight = adjustedSlot.height,
                requestWidth = 640,
                requestHeight = 480,
                resolutionAlreadyAdjusted = adjustedSlot.resolutionAdjusted,
            ),
        )
        assertTrue(
            CameraStreamManager.shouldReportResolutionNegotiationFailure(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 7L,
                snapshotSessionId = 7L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = adjustedSlot.isRetiring,
                surfaceValid = true,
                pendingResolutionAdjustment = adjustedSlot.pendingResolutionAdjustment,
                snapshotWidth = adjustedSlot.width,
                snapshotHeight = adjustedSlot.height,
                requestWidth = 640,
                requestHeight = 480,
            ),
        )
        assertTrue(
            CameraStreamManager.shouldReportCurrentSlotPipelineFailure(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 7L,
                snapshotSessionId = 7L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = adjustedSlot.isRetiring,
                slotGeometryRequestSeq = adjustedSlot.geometryRequestSeq,
                latestGeometryRequestSeq = 2L,
            ),
        )
        assertTrue(adjustedSlot.markTerminalFailureReported())
        assertTrue(adjustedSlot.isRetiring)
        assertTrue(adjustedSlot.isTerminalFailureReported)
        assertTrue(adjustedSlot.negotiatedDimensionsCommitted)
        assertFalse(adjustedSlot.markTerminalFailureReported())

        // If adjustment outcome was NOT yet reported when REJECT_STALE fires, terminal failure also claims it.
        val unconfirmedAdjustedSlot = ActiveEncoderSlot(
            sessionId = 7L,
            generation = 3L,
            encoder = null,
            width = 1280,
            height = 720,
            fps = 60,
            cameraId = "0",
            surfaceTracker = EncoderSurfaceRetirementTracker(),
            cameraConfigId = 4L,
            geometryRequestSeq = 3L,
            pendingResolutionAdjustment = true,
            fallbackWidth = 1920,
            fallbackHeight = 1080,
            resolutionAdjusted = true,
        )
        assertTrue(unconfirmedAdjustedSlot.markTerminalFailureReported())
        assertTrue(unconfirmedAdjustedSlot.isResolutionAdjustmentReported())
        assertFalse(unconfirmedAdjustedSlot.hasPendingResolutionAdjustmentUnreported())
        assertFalse(unconfirmedAdjustedSlot.negotiatedDimensionsCommitted)
    }

    @Test
    fun provideSurfaceTerminalRejectionReportsPipelineFailureEvenAfterAdjustmentClaimed() {
        val adjustedSlot = ActiveEncoderSlot(
            sessionId = 12L,
            generation = 5L,
            encoder = null,
            width = 1280,
            height = 720,
            fps = 60,
            cameraId = "0",
            surfaceTracker = EncoderSurfaceRetirementTracker(),
            cameraConfigId = 8L,
            geometryRequestSeq = 4L,
            pendingResolutionAdjustment = true,
            fallbackWidth = 1920,
            fallbackHeight = 1080,
            resolutionAdjusted = true,
        )

        // First frame already claimed negotiated-size adjustment outcome and committed dims.
        assertTrue(adjustedSlot.markResolutionAdjustmentReported())
        adjustedSlot.negotiatedDimensionsCommitted = true
        assertFalse(adjustedSlot.markResolutionAdjustmentReported())

        // Normal surface return and superseded request cancellation stay on retirement-accounting path only.
        assertFalse(
            CameraStreamManager.isSurfaceResultRejection(
                SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY,
                isCurrentRequest = true,
            ),
        )
        assertFalse(
            CameraStreamManager.isSurfaceResultRejection(
                SurfaceRequest.Result.RESULT_REQUEST_CANCELLED,
                isCurrentRequest = false,
            ),
        )
        assertFalse(adjustedSlot.isTerminalFailureReported)
        assertFalse(adjustedSlot.isRetiring)

        // Terminal rejection of current request routes through reportSlotPipelineFailure even after adjustment claim.
        assertTrue(
            CameraStreamManager.isSurfaceResultRejection(
                SurfaceRequest.Result.RESULT_INVALID_SURFACE,
                isCurrentRequest = true,
            ),
        )
        assertTrue(
            CameraStreamManager.shouldReportCurrentSlotPipelineFailure(
                isReleased = false,
                isStreaming = true,
                activeSessionId = 12L,
                snapshotSessionId = 12L,
                activeSlotMatchesSnapshot = true,
                snapshotRetiring = adjustedSlot.isRetiring,
                slotGeometryRequestSeq = adjustedSlot.geometryRequestSeq,
                latestGeometryRequestSeq = 4L,
            ),
        )
        assertTrue(adjustedSlot.markTerminalFailureReported())
        assertTrue(adjustedSlot.isTerminalFailureReported)
        assertTrue(adjustedSlot.isRetiring)
        assertTrue(adjustedSlot.negotiatedDimensionsCommitted)
        assertFalse(adjustedSlot.markTerminalFailureReported())
    }
}
