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
 * NAL units on a dedicated worker thread, extracts SPS/PPS parameter sets,
 * and emits CCFT [PacketType.VIDEO_CONFIG] and [PacketType.VIDEO_FRAME] packets.
 */
class H264SurfaceEncoder(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    private val onVideoPacket: (packetType: PacketType, flags: Int, ptsUs: Long, payload: ByteArray) -> Unit,
) {

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
    private var drainThread: Thread? = null

    init {
        initCodec()
    }

    private fun initCodec() {
        val mime = MediaFormat.MIMETYPE_VIDEO_AVC
        val mediaCodec = MediaCodec.createEncoderByType(mime)
        var createdSurface: Surface? = null

        try {
            val format = MediaFormat.createVideoFormat(mime, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateKbps * 1_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)

                // Low-latency and chipset-tolerant optimizations
                try {
                    setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
                } catch (ignored: Exception) {}

                try {
                    setInteger(MediaFormat.KEY_LATENCY, 1)
                } catch (ignored: Exception) {}

                try {
                    setInteger(MediaFormat.KEY_PRIORITY, 0)
                } catch (ignored: Exception) {}

                try {
                    setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
                } catch (ignored: Exception) {}

                try {
                    setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                    setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel41)
                } catch (ignored: Exception) {}
            }

            mediaCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
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

            val thread = Thread({
                drainEncoderLoop(existing)
            }, "CamCraft-H264-Drain").apply {
                priority = Thread.MAX_PRIORITY
                isDaemon = true
            }
            drainThread = thread
            thread.start()
            existing
        }
        Log.i(TAG, "H264SurfaceEncoder started ($width x $height @ $fps fps, $bitrateKbps kbps, codec=$activeCodec)")
    }

    /**
     * Polling drain loop processing encoder output buffers.
     */
    private fun drainEncoderLoop(activeCodec: MediaCodec) {
        val bufferInfo = MediaCodec.BufferInfo()

        while (isRunning.get()) {
            try {
                val outputIndex = activeCodec.dequeueOutputBuffer(bufferInfo, 10_000L)
                when (outputIndex) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        // Polling timeout - continue loop
                    }
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val newFormat = activeCodec.outputFormat
                        Log.i(TAG, "Encoder output format changed: $newFormat")
                        val csd0 = try { newFormat.getByteBuffer("csd-0") } catch (e: Exception) { null }
                        val csd1 = try { newFormat.getByteBuffer("csd-1") } catch (e: Exception) { null }
                        val config = combineSpsPps(csd0, csd1)
                        if (config.isNotEmpty()) {
                            cachedConfig = config
                            val ptsUs = System.nanoTime() / 1_000L
                            onVideoPacket(PacketType.VIDEO_CONFIG, CcftFlags.NONE, ptsUs, config)
                            Log.i(TAG, "Dispatched VIDEO_CONFIG SPS/PPS (${config.size} bytes)")
                        }
                    }
                    else -> {
                        if (outputIndex >= 0) {
                            val outputBuffer = activeCodec.getOutputBuffer(outputIndex)
                            if (outputBuffer != null && bufferInfo.size > 0) {
                                outputBuffer.position(bufferInfo.offset)
                                outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                                val payload = ByteArray(bufferInfo.size)
                                outputBuffer.get(payload)

                                val ptsUs = if (bufferInfo.presentationTimeUs > 0) {
                                    bufferInfo.presentationTimeUs
                                } else {
                                    System.nanoTime() / 1_000L
                                }

                                if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                                    val annexB = ensureAnnexB(payload)
                                    cachedConfig = annexB
                                    onVideoPacket(PacketType.VIDEO_CONFIG, CcftFlags.NONE, ptsUs, annexB)
                                } else {
                                    val isKeyframe = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0
                                    val flags = if (isKeyframe) CcftFlags.KEYFRAME else CcftFlags.NONE
                                    val annexB = ensureAnnexB(payload)
                                    onVideoPacket(PacketType.VIDEO_FRAME, flags, ptsUs, annexB)
                                }
                            }
                            activeCodec.releaseOutputBuffer(outputIndex, false)
                        }
                    }
                }
            } catch (e: IllegalStateException) {
                // Codec was stopped or released
                break
            } catch (e: Exception) {
                if (isRunning.get()) {
                    Log.e(TAG, "Exception during drainEncoderLoop: ${e.message}", e)
                }
            }
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
     */
    fun release() {
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
    }

    companion object {
        private const val TAG = "H264SurfaceEncoder"

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
