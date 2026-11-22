package org.artcraft.camcraft.encoder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import org.artcraft.camcraft.protocol.CcftFlags
import org.artcraft.camcraft.protocol.PacketType

/**
 * Hardware-accelerated H.264/AVC video encoder fed directly by GPU [Surface].
 *
 * Configures [MediaCodec] in low-latency surface-input mode, drains Annex-B
 * NAL units on a dedicated worker thread using a reusable scratch buffer,
 * extracts SPS/PPS parameter sets, and emits CCFT [PacketType.VIDEO_CONFIG]
 * and [PacketType.VIDEO_FRAME] slices without per-frame heap allocations.
 */
class H264SurfaceEncoder(
    val width: Int,
    val height: Int,
    val fps: Int,
    bitrateKbps: Int,
    private val onVideoPacketSlice: (
        packetType: PacketType,
        flags: Int,
        ptsUs: Long,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) -> Unit,
) {
    constructor(
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
        onVideoPacket: (packetType: PacketType, flags: Int, ptsUs: Long, payload: ByteArray) -> Unit,
    ) : this(
        width = width,
        height = height,
        fps = fps,
        bitrateKbps = bitrateKbps,
        onVideoPacketSlice = { packetType, flags, ptsUs, payload, offset, length ->
            val standalone = if (offset == 0 && length == payload.size) {
                payload
            } else {
                payload.copyOfRange(offset, offset + length)
            }
            onVideoPacket(packetType, flags, ptsUs, standalone)
        },
    )

    @Volatile
    var bitrateKbps: Int = bitrateKbps
        private set

    private val lifecycleLock = Any()
    private var codec: MediaCodec? = null
    var inputSurface: Surface? = null
        private set

    @Volatile
    private var hasStarted = false

    @Volatile
    var cachedConfig: ByteArray? = null
        private set

    private val isRunning = AtomicBoolean(false)
    private val releaseCoordinator = DrainReleaseCoordinator()
    private var drainThread: Thread? = null

    init {
        initCodec()
    }

    private fun buildMediaFormat(includeLowLatencyAndProfile: Boolean): MediaFormat {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        return MediaFormat.createVideoFormat(mime, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

            // Low-latency and chipset-tolerant optimizations
            try {
                setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            } catch (_: Exception) {}

            try {
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            } catch (_: Exception) {}

            try {
                setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            } catch (_: Exception) {}

            if (includeLowLatencyAndProfile) {
                try {
                    setInteger(MediaFormat.KEY_LATENCY, 1)
                } catch (_: Exception) {}

                try {
                    setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
                } catch (_: Exception) {}

                try {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, selectAvcLevel(width, height, fps))
                } catch (_: Exception) {}
            }
        }
    }

    private fun initCodec() {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        var mediaCodec = MediaCodec.createEncoderByType(mime)
        var createdSurface: Surface? = null

        try {
            val primaryFormat = buildMediaFormat(includeLowLatencyAndProfile = true)
            try {
                mediaCodec.configure(primaryFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            } catch (primaryError: Exception) {
                Log.w(
                    TAG,
                    "Primary H.264 MediaCodec configure failed (${primaryError.message}); retrying without profile/level/latency",
                )
                try {
                    mediaCodec.release()
                } catch (_: Exception) {}
                mediaCodec = MediaCodec.createEncoderByType(mime)
                val fallbackFormat = buildMediaFormat(includeLowLatencyAndProfile = false)
                mediaCodec.configure(fallbackFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            }

            createdSurface = mediaCodec.createInputSurface()
            synchronized(lifecycleLock) {
                inputSurface = createdSurface
                codec = mediaCodec
                hasStarted = false
            }
        } catch (e: Exception) {
            try {
                createdSurface?.release()
            } catch (_: Exception) {}
            try {
                mediaCodec.release()
            } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Starts the encoder and launches the background drain loop.
     */
    fun start() {
        val activeCodec = synchronized(lifecycleLock) {
            if (isRunning.get()) return
            val existing = codec ?: return
            try {
                existing.start()
            } catch (e: Exception) {
                isRunning.set(false)
                throw e
            }
            hasStarted = true
            isRunning.set(true)
            releaseCoordinator.markDrainStarted()

            val thread = Thread({
                drainEncoderLoop(existing)
            }, "CamCraft-H264-Drain").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
            }
            drainThread = thread
            try {
                thread.start()
            } catch (e: Throwable) {
                isRunning.set(false)
                drainThread = null
                releaseCoordinator.markDrainExited()
                throw e
            }
            existing
        }
        Log.i(TAG, "H264SurfaceEncoder started ($width x $height @ $fps fps, $bitrateKbps kbps, codec=$activeCodec)")
    }

    /**
     * Polling drain loop processing encoder output buffers with a reusable scratch array.
     */
    private fun drainEncoderLoop(activeCodec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()
        var scratchBuffer = ByteArray(INITIAL_SCRATCH_BUFFER_BYTES)

        try {
            while (isRunning.get() && !releaseCoordinator.isReleaseRequested()) {
                try {
                    val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 10_000L)
                    when (outputIndex) {
                        MediaCodec.INFO_TRY_AGAIN_LATER -> {
                            // Polling timeout - continue loop
                        }
                        MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            if (!isRunning.get() || releaseCoordinator.isReleaseRequested()) break
                            val newFormat = activeCodec.outputFormat
                            Log.i(TAG, "Encoder output format changed: $newFormat")
                            val csd0 = try { newFormat.getByteBuffer("csd-0") } catch (_: Exception) { null }
                            val csd1 = try { newFormat.getByteBuffer("csd-1") } catch (_: Exception) { null }
                            val config = combineSpsPps(csd0, csd1)
                            if (config.isNotEmpty()) {
                                cachedConfig = config
                                val ptsUs = System.nanoTime() / 1_000L
                                onVideoPacketSlice(PacketType.VIDEO_CONFIG, CcftFlags.NONE, ptsUs, config, 0, config.size)
                                Log.i(TAG, "Dispatched VIDEO_CONFIG SPS/PPS (${config.size} bytes)")
                            }
                        }
                        else -> {
                            if (outputIndex >= 0) {
                                var shouldDispatch = false
                                var sliceOffset = 0
                                var sliceLength = 0
                                var ptsUs = 0L
                                var isConfig = false
                                var isKeyframe = false

                                try {
                                    if (isRunning.get() && !releaseCoordinator.isReleaseRequested()) {
                                        val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                                        val size = bufferInfo.size
                                        if (outputBuffer != null && size > 0) {
                                            val requiredCapacity = size + ANNEX_B_PREFIX_HEADROOM
                                            if (requiredCapacity > scratchBuffer.size) {
                                                scratchBuffer = ByteArray(maxOf(scratchBuffer.size * 2, requiredCapacity))
                                            }

                                            outputBuffer.position(bufferInfo.offset)
                                            outputBuffer.limit(bufferInfo.offset + size)
                                            outputBuffer.get(scratchBuffer, ANNEX_B_PREFIX_HEADROOM, size)

                                            val slice = formatAnnexBInPlace(scratchBuffer, size)
                                            sliceOffset = slice.offset
                                            sliceLength = slice.length
                                            ptsUs = if (bufferInfo.presentationTimeUs > 0) {
                                                bufferInfo.presentationTimeUs
                                            } else {
                                                System.nanoTime() / 1_000L
                                            }
                                            isConfig = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
                                            isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                            shouldDispatch = true
                                        }
                                    }
                                } finally {
                                    // Return buffer to MediaCodec before dispatching over I/O so we never
                                    // touch activeCodec after a potentially stalled callback returns or if copy throws.
                                    if (isRunning.get() && !releaseCoordinator.isReleaseRequested()) {
                                        activeCodec.releaseOutputBuffer(outputIndex, false)
                                    }
                                }

                                if (shouldDispatch && isRunning.get() && !releaseCoordinator.isReleaseRequested()) {
                                    if (isConfig) {
                                        val configCopy = scratchBuffer.copyOfRange(sliceOffset, sliceOffset + sliceLength)
                                        cachedConfig = configCopy
                                        onVideoPacketSlice(
                                            PacketType.VIDEO_CONFIG,
                                            CcftFlags.NONE,
                                            ptsUs,
                                            configCopy,
                                            0,
                                            configCopy.size,
                                        )
                                    } else {
                                        val flags = if (isKeyframe) CcftFlags.KEYFRAME else CcftFlags.NONE
                                        onVideoPacketSlice(
                                            PacketType.VIDEO_FRAME,
                                            flags,
                                            ptsUs,
                                            scratchBuffer,
                                            sliceOffset,
                                            sliceLength,
                                        )
                                    }
                                }
                            }
                        }
                    }
                } catch (_: IllegalStateException) {
                    // Codec was stopped or released
                    break
                } catch (e: Exception) {
                    if (isRunning.get()) {
                        Log.e(TAG, "Exception during drainEncoderLoop: ${e.message}", e)
                    }
                }
            }
        } finally {
            if (releaseCoordinator.markDrainExited()) {
                releaseCodecAndSurfaceLocked()
            }
        }
    }

    /**
     * Dynamically adjusts target video bitrate on the active [MediaCodec] without restarting the pipeline.
     */
    fun updateBitrate(bitrateKbps: Int) {
        val clampedKbps = bitrateKbps.coerceAtLeast(100)
        val activeCodec = synchronized(lifecycleLock) {
            this.bitrateKbps = clampedKbps
            if (!isRunning.get() || !hasStarted) return
            codec
        } ?: return

        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, clampedKbps * 1_000)
            }
            activeCodec.setParameters(params)
            Log.i(TAG, "Updated H.264 bitrate dynamically to $clampedKbps kbps")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update bitrate dynamically: ${e.message}")
        }
    }

    /**
     * Requests an immediate IDR keyframe from hardware encoder.
     */
    fun requestKeyFrame() {
        val activeCodec = synchronized(lifecycleLock) {
            if (!isRunning.get() || !hasStarted) return
            codec
        } ?: return

        try {
            val params = Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            }
            activeCodec.setParameters(params)
            Log.d(TAG, "Requested IDR sync keyframe from MediaCodec")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request IDR keyframe: ${e.message}")
        }
    }

    /**
     * Safely stops the background worker thread, signals EOS, and releases MediaCodec and Surface.
     *
     * Optional [onReleased] runs strictly after the underlying codec and input [Surface] have been
     * torn down, either synchronously before returning or on drain-thread exit when deferred.
     */
    @JvmOverloads
    fun release(onReleased: (() -> Unit)? = null) {
        if (onReleased != null) {
            releaseCoordinator.onTeardownCompleted(onReleased)
        }
        isRunning.set(false)

        val threadToJoin = synchronized(lifecycleLock) {
            drainThread.also { drainThread = null }
        }
        if (threadToJoin != null && threadToJoin !== Thread.currentThread()) {
            try {
                threadToJoin.join(500)
                if (threadToJoin.isAlive) {
                    threadToJoin.interrupt()
                    threadToJoin.join(100)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        if (releaseCoordinator.requestRelease()) {
            releaseCodecAndSurfaceLocked()
        } else {
            Log.w(TAG, "H264 drain thread still exiting stalled callback; deferring MediaCodec release to drain exit")
        }
    }

    private fun releaseCodecAndSurfaceLocked() {
        try {
            synchronized(lifecycleLock) {
                val activeCodec = codec
                val wasStarted = hasStarted
                codec = null
                hasStarted = false

                if (activeCodec != null && wasStarted) {
                    try {
                        activeCodec.stop()
                    } catch (e: Exception) {
                        Log.w(TAG, "Codec stop exception: ${e.message}")
                    }
                }

                if (activeCodec != null) {
                    try {
                        activeCodec.release()
                    } catch (e: Exception) {
                        Log.w(TAG, "Codec release exception: ${e.message}")
                    }
                }

                try {
                    inputSurface?.release()
                } catch (e: Exception) {
                    Log.w(TAG, "Input surface release exception: ${e.message}")
                }
                inputSurface = null
                cachedConfig = null
            }

            Log.i(TAG, "H264SurfaceEncoder released")
        } finally {
            releaseCoordinator.markTeardownCompleted()
        }
    }

    /**
     * Zero-allocation slice pointer packed into a single primitive 64-bit integer.
     */
    @JvmInline
    value class AnnexBSlice(private val packed: Long) {
        constructor(offset: Int, length: Int) : this(
            (offset.toLong() shl 32) or (length.toLong() and 0xFFFFFFFFL),
        )

        val offset: Int
            get() = (packed ushr 32).toInt()

        val length: Int
            get() = packed.toInt()
    }

    companion object {
        private const val TAG = "H264SurfaceEncoder"
        private const val INITIAL_SCRATCH_BUFFER_BYTES = 256 * 1024
        const val ANNEX_B_PREFIX_HEADROOM = 4

        /**
         * Selects an appropriate H.264 AVC level constant for the configured resolution and FPS.
         */
        fun selectAvcLevel(width: Int, height: Int, fps: Int): Int {
            val pixels = width.toLong() * height.toLong()
            val effectiveFps = fps.coerceAtLeast(1).toLong()
            val pixelRate = pixels * effectiveFps

            return when {
                pixels <= 1280L * 720L && effectiveFps <= 30L ->
                    MediaCodecInfo.CodecProfileLevel.AVCLevel31
                pixels <= 1920L * 1080L && pixelRate <= 1920L * 1080L * 30L ->
                    MediaCodecInfo.CodecProfileLevel.AVCLevel4
                pixels <= 1920L * 1080L && pixelRate <= 1920L * 1080L * 60L ->
                    MediaCodecInfo.CodecProfileLevel.AVCLevel42
                pixels <= 3840L * 2160L && pixelRate <= 3840L * 2160L * 30L ->
                    MediaCodecInfo.CodecProfileLevel.AVCLevel51
                else ->
                    MediaCodecInfo.CodecProfileLevel.AVCLevel52
            }
        }

        /**
         * Formats NAL unit bytes previously copied into [buffer] starting at index 4
         * (`[4 .. 4 + payloadSize)`) into a 4-byte Annex-B slice without heap allocations.
         */
        fun formatAnnexBInPlace(buffer: ByteArray, payloadSize: Int): AnnexBSlice {
            require(payloadSize >= 0 && buffer.size >= payloadSize + ANNEX_B_PREFIX_HEADROOM) {
                "Buffer too small: size=${buffer.size}, required=${payloadSize + ANNEX_B_PREFIX_HEADROOM}"
            }

            if (payloadSize >= 4 &&
                buffer[4] == 0.toByte() &&
                buffer[5] == 0.toByte() &&
                buffer[6] == 0.toByte() &&
                buffer[7] == 1.toByte()
            ) {
                return AnnexBSlice(offset = 4, length = payloadSize)
            }

            if (payloadSize >= 3 &&
                buffer[4] == 0.toByte() &&
                buffer[5] == 0.toByte() &&
                buffer[6] == 1.toByte()
            ) {
                buffer[3] = 0
                return AnnexBSlice(offset = 3, length = payloadSize + 1)
            }

            buffer[0] = 0
            buffer[1] = 0
            buffer[2] = 0
            buffer[3] = 1
            return AnnexBSlice(offset = 0, length = payloadSize + ANNEX_B_PREFIX_HEADROOM)
        }

        /**
         * Ensures a byte array has a 4-byte Annex-B start code `00 00 00 01`.
         * Returns original array if already prefixed to prevent extra heap allocation.
         */
        fun ensureAnnexB(bytes: ByteArray): ByteArray {
            if (bytes.size >= 4 &&
                bytes[0] == 0.toByte() &&
                bytes[1] == 0.toByte() &&
                bytes[2] == 0.toByte() &&
                bytes[3] == 1.toByte()
            ) {
                return bytes
            }

            if (bytes.size >= 3 &&
                bytes[0] == 0.toByte() &&
                bytes[1] == 0.toByte() &&
                bytes[2] == 1.toByte()
            ) {
                val result = ByteArray(bytes.size + 1)
                result[0] = 0
                System.arraycopy(bytes, 0, result, 1, bytes.size)
                return result
            }

            val result = ByteArray(bytes.size + 4)
            result[0] = 0
            result[1] = 0
            result[2] = 0
            result[3] = 1
            System.arraycopy(bytes, 0, result, 4, bytes.size)
            return result
        }

        /**
         * Converts a ByteBuffer to a 4-byte Annex-B byte array.
         */
        fun bufferToAnnexB(buffer: ByteBuffer): ByteArray {
            val duplicate = buffer.duplicate()
            val bytes = ByteArray(duplicate.remaining())
            duplicate.get(bytes)
            return ensureAnnexB(bytes)
        }

        /**
         * Combines `csd-0` (SPS) and `csd-1` (PPS) buffers into a unified Annex-B byte array.
         */
        fun combineSpsPps(csd0: ByteBuffer?, csd1: ByteBuffer?): ByteArray {
            val sps = if (csd0 != null) bufferToAnnexB(csd0) else ByteArray(0)
            val pps = if (csd1 != null) bufferToAnnexB(csd1) else ByteArray(0)

            if (sps.isEmpty() && pps.isEmpty()) return ByteArray(0)

            val combined = ByteArray(sps.size + pps.size)
            System.arraycopy(sps, 0, combined, 0, sps.size)
            System.arraycopy(pps, 0, combined, sps.size, pps.size)
            return combined
        }
    }
}

/**
 * Coordinates safe one-time MediaCodec & Surface teardown between [H264SurfaceEncoder.release]
 * and the background drain thread so resources are never released while the drain loop can
 * still touch [MediaCodec], without blocking `release()` indefinitely on stalled callbacks.
 */
internal class DrainReleaseCoordinator {
    private val lock = Any()
    private var started = false
    private var drainActive = false
    private var releaseRequested = false
    private var finalized = false
    private var teardownCompleted = false
    private val completionListeners = mutableListOf<() -> Unit>()

    fun markDrainStarted() {
        synchronized(lock) {
            started = true
            drainActive = true
        }
    }

    fun markDrainExited(): Boolean {
        synchronized(lock) {
            drainActive = false
            if (releaseRequested && !finalized) {
                finalized = true
                return true
            }
            return false
        }
    }

    fun requestRelease(): Boolean {
        synchronized(lock) {
            releaseRequested = true
            if ((!started || !drainActive) && !finalized) {
                finalized = true
                return true
            }
            return false
        }
    }

    fun onTeardownCompleted(listener: () -> Unit) {
        val runImmediately = synchronized(lock) {
            if (teardownCompleted) {
                true
            } else {
                completionListeners.add(listener)
                false
            }
        }
        if (runImmediately) {
            try {
                listener()
            } catch (_: Throwable) {
            }
        }
    }

    fun markTeardownCompleted() {
        val listeners = synchronized(lock) {
            if (teardownCompleted) {
                return
            }
            teardownCompleted = true
            val snapshot = completionListeners.toList()
            completionListeners.clear()
            snapshot
        }
        for (listener in listeners) {
            try {
                listener()
            } catch (_: Throwable) {
            }
        }
    }

    fun isReleaseRequested(): Boolean = synchronized(lock) { releaseRequested }
    fun isFinalized(): Boolean = synchronized(lock) { finalized }
    fun isTeardownCompleted(): Boolean = synchronized(lock) { teardownCompleted }
}
