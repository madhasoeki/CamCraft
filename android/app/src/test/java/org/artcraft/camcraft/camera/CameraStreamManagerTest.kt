package org.artcraft.camcraft.camera

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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
}
