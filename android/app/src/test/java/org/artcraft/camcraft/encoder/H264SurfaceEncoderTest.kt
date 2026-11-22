package org.artcraft.camcraft.encoder

import android.media.MediaCodecInfo
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class H264SurfaceEncoderTest {

    @Test
    fun ensureAnnexBKeepsFourByteStartCodeWithoutNewAllocation() {
        val original = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00)
        val result = H264SurfaceEncoder.ensureAnnexB(original)
        assertArrayEquals(original, result)
        assertEquals(System.identityHashCode(original), System.identityHashCode(result))
    }

    @Test
    fun ensureAnnexBNormalizesThreeByteStartCodeToFourBytes() {
        val threeByte = byteArrayOf(0, 0, 1, 0x68, 0xCE.toByte())
        val result = H264SurfaceEncoder.ensureAnnexB(threeByte)
        val expected = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte())
        assertArrayEquals(expected, result)
    }

    @Test
    fun ensureAnnexBPrependsStartCodeWhenMissing() {
        val raw = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte())
        val result = H264SurfaceEncoder.ensureAnnexB(raw)
        val expected = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())
        assertArrayEquals(expected, result)
    }

    @Test
    fun combineSpsPpsProducesUnifiedAnnexBSequence() {
        val sps = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00))
        val pps = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte()))

        val combined = H264SurfaceEncoder.combineSpsPps(sps, pps)
        val expected = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0x00,
            0, 0, 0, 1, 0x68, 0xCE.toByte(),
        )
        assertArrayEquals(expected, combined)
    }

    @Test
    fun selectAvcLevelMatchesResolutionAndFpsBounds() {
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel31,
            H264SurfaceEncoder.selectAvcLevel(1280, 720, 30),
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel4,
            H264SurfaceEncoder.selectAvcLevel(1920, 1080, 30),
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel4,
            H264SurfaceEncoder.selectAvcLevel(1280, 720, 60),
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel42,
            H264SurfaceEncoder.selectAvcLevel(1920, 1080, 60),
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel42,
            H264SurfaceEncoder.selectAvcLevel(1080, 1920, 60),
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel51,
            H264SurfaceEncoder.selectAvcLevel(3840, 2160, 30),
        )
        assertEquals(
            MediaCodecInfo.CodecProfileLevel.AVCLevel52,
            H264SurfaceEncoder.selectAvcLevel(3840, 2160, 60),
        )
    }

    @Test
    fun formatAnnexBInPlaceKeepsFourByteStartCodeAtOffsetFour() {
        val scratch = ByteArray(16)
        val payload = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())
        System.arraycopy(payload, 0, scratch, 4, payload.size)

        val slice = H264SurfaceEncoder.formatAnnexBInPlace(scratch, payload.size)

        assertEquals(4, slice.offset)
        assertEquals(payload.size, slice.length)
        assertArrayEquals(payload, scratch.copyOfRange(slice.offset, slice.offset + slice.length))
    }

    @Test
    fun formatAnnexBInPlaceNormalizesThreeByteStartCodeAtOffsetThree() {
        val scratch = ByteArray(16)
        val payload = byteArrayOf(0, 0, 1, 0x68, 0xCE.toByte())
        System.arraycopy(payload, 0, scratch, 4, payload.size)

        val slice = H264SurfaceEncoder.formatAnnexBInPlace(scratch, payload.size)

        assertEquals(3, slice.offset)
        assertEquals(payload.size + 1, slice.length)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte()),
            scratch.copyOfRange(slice.offset, slice.offset + slice.length),
        )
    }

    @Test
    fun formatAnnexBInPlacePrependsFourByteStartCodeAtOffsetZero() {
        val scratch = ByteArray(16)
        val payload = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte())
        System.arraycopy(payload, 0, scratch, 4, payload.size)

        val slice = H264SurfaceEncoder.formatAnnexBInPlace(scratch, payload.size)

        assertEquals(0, slice.offset)
        assertEquals(payload.size + 4, slice.length)
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte()),
            scratch.copyOfRange(slice.offset, slice.offset + slice.length),
        )
    }

    @Test
    fun drainReleaseCoordinatorDefersCleanupWhileCallbackStalledAndFinalizesOnce() {
        // 1. Stalled drain thread still running when release() is requested:
        //    requestRelease() must return false so codec/surface are NOT released early,
        //    and markDrainExited() must trigger cleanup exactly once once drain finishes.
        val stalledCoord = DrainReleaseCoordinator()
        stalledCoord.markDrainStarted()
        assertFalse(stalledCoord.requestRelease())
        assertTrue(stalledCoord.isReleaseRequested())
        assertFalse(stalledCoord.isFinalized())

        // Repeated release request remains safe and returns false.
        assertFalse(stalledCoord.requestRelease())
        assertFalse(stalledCoord.isFinalized())

        // Stalled callback unblocks and drain exits -> finalizes once.
        assertTrue(stalledCoord.markDrainExited())
        assertTrue(stalledCoord.isFinalized())
        assertFalse(stalledCoord.markDrainExited())
        assertFalse(stalledCoord.requestRelease())

        // 2. Normal exit before release(): requestRelease() finalizes immediately and idempotently.
        val normalCoord = DrainReleaseCoordinator()
        normalCoord.markDrainStarted()
        assertFalse(normalCoord.markDrainExited())
        assertTrue(normalCoord.requestRelease())
        assertTrue(normalCoord.isFinalized())
        assertFalse(normalCoord.requestRelease())

        // 3. Never-started release(): requestRelease() finalizes immediately once.
        val unstartedCoord = DrainReleaseCoordinator()
        assertTrue(unstartedCoord.requestRelease())
        assertTrue(unstartedCoord.isFinalized())
        assertFalse(unstartedCoord.requestRelease())
    }

    @Test
    fun drainReleaseCoordinatorSignalsCompletionOnlyAfterTeardownIncludingDeferredExit() {
        // 1. Deferred teardown: callback must NOT fire at requestRelease() nor merely at markDrainExited()
        //    before releaseCodecAndSurfaceLocked() calls markTeardownCompleted().
        val deferredCoord = DrainReleaseCoordinator()
        val deferredCalls = AtomicInteger(0)
        deferredCoord.markDrainStarted()
        deferredCoord.onTeardownCompleted { deferredCalls.incrementAndGet() }

        assertFalse(deferredCoord.requestRelease())
        assertFalse(deferredCoord.isTeardownCompleted())
        assertEquals(0, deferredCalls.get())

        assertTrue(deferredCoord.markDrainExited())
        assertFalse(deferredCoord.isTeardownCompleted())
        assertEquals(0, deferredCalls.get())

        deferredCoord.markTeardownCompleted()
        assertTrue(deferredCoord.isTeardownCompleted())
        assertEquals(1, deferredCalls.get())

        // Idempotent markTeardownCompleted() must not re-fire, and late listener fires immediately once.
        deferredCoord.markTeardownCompleted()
        assertEquals(1, deferredCalls.get())
        val lateCalls = AtomicInteger(0)
        deferredCoord.onTeardownCompleted { lateCalls.incrementAndGet() }
        assertEquals(1, lateCalls.get())

        // 2. Synchronous teardown: signals once markTeardownCompleted() runs after requestRelease().
        val syncCoord = DrainReleaseCoordinator()
        val syncCalls = AtomicInteger(0)
        syncCoord.markDrainStarted()
        assertFalse(syncCoord.markDrainExited())
        syncCoord.onTeardownCompleted { syncCalls.incrementAndGet() }
        assertTrue(syncCoord.requestRelease())
        assertEquals(0, syncCalls.get())
        syncCoord.markTeardownCompleted()
        assertEquals(1, syncCalls.get())
    }

    @Test
    fun drainReleaseCoordinatorSeparatesCodecReleaseFailureFromConfirmedSuccess() {
        val failedCoord = DrainReleaseCoordinator()
        val successOnlyCalls = AtomicInteger(0)
        val resultEvents = mutableListOf<Boolean>()

        failedCoord.markDrainStarted()
        failedCoord.onTeardownCompleted { successOnlyCalls.incrementAndGet() }
        failedCoord.onTeardownResult { codecReleased -> resultEvents.add(codecReleased) }

        assertFalse(failedCoord.requestRelease())
        assertTrue(failedCoord.markDrainExited())
        failedCoord.markTeardownCompleted(codecReleased = false)

        // Teardown attempt completed, but codec release failed -> success-only listeners MUST NOT run.
        assertTrue(failedCoord.isTeardownCompleted())
        assertFalse(failedCoord.isTeardownSucceeded())
        assertEquals(0, successOnlyCalls.get())
        assertEquals(listOf(false), resultEvents)

        // Late listeners observe the recorded failure status accurately without firing success hooks.
        val lateResultEvents = mutableListOf<Boolean>()
        failedCoord.onTeardownCompleted { successOnlyCalls.incrementAndGet() }
        failedCoord.onTeardownResult { codecReleased -> lateResultEvents.add(codecReleased) }
        assertEquals(0, successOnlyCalls.get())
        assertEquals(listOf(false), lateResultEvents)
    }
}
