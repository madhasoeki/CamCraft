package org.artcraft.camcraft.encoder

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import org.artcraft.camcraft.protocol.AudioConfigPayload
import org.artcraft.camcraft.protocol.CcftJson
import org.artcraft.camcraft.protocol.PacketType

/**
 * Hardware-backed PCM microphone capture engine emitting 20 ms Mono frames at 48 kHz.
 *
 * Reads fixed 1920-byte (960 sample) frames from [AudioRecord], emits
 * [PacketType.AUDIO_CONFIG] (`0x03`) once on startup, and streams
 * [PacketType.AUDIO_FRAME] (`0x04`) packets while respecting mute state without
 * allocating per-frame arrays in the hot capture loop.
 */
class AudioStreamEngine(
    private val context: Context,
    private val audioSource: Int = MediaRecorder.AudioSource.MIC,
    private val onAudioPacket: (packetType: PacketType, ptsUs: Long, payload: ByteArray, length: Int) -> Unit,
) {

    private val lifecycleCoordinator = AudioLifecycleCoordinator<AudioRecord>(
        stopResource = { it.stop() },
        releaseResource = { it.release() },
    )
    private val isMuted = AtomicBoolean(false)

    val isActive: Boolean
        get() = lifecycleCoordinator.isActive

    /**
     * Updates mute state in real time without stopping [AudioRecord].
     * When muted, pre-allocated zero bytes are transmitted instead of microphone samples.
     */
    fun setMuted(muted: Boolean) {
        isMuted.set(muted)
    }

    fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Initializes [AudioRecord] and starts the background capture thread.
     * Serialized against [stop] so a new [AudioRecord] is never opened until the previous
     * instance and capture thread have completely stopped and released.
     *
     * Returns `false` if `RECORD_AUDIO` permission is missing or the audio device fails to initialize.
     */
    fun start(initiallyMuted: Boolean = false): Boolean {
        isMuted.set(initiallyMuted)
        val started = lifecycleCoordinator.start(
            createAndStart = {
                if (!hasRecordAudioPermission()) {
                    Log.w(TAG, "RECORD_AUDIO permission not granted; skipping audio capture start")
                    return@start null
                }

                val minBuf = AudioRecord.getMinBufferSize(
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                )
                if (minBuf <= 0) {
                    Log.e(TAG, "AudioRecord.getMinBufferSize failed with code $minBuf")
                    return@start null
                }

                val bufferSizeBytes = calculateBufferSizeBytes(minBuf)
                val record = buildAudioRecord(bufferSizeBytes)
                if (record == null) {
                    Log.e(TAG, "Failed to initialize AudioRecord (bufferSize=$bufferSizeBytes)")
                    return@start null
                }

                try {
                    record.startRecording()
                } catch (e: Exception) {
                    Log.e(TAG, "AudioRecord.startRecording failed: ${e.message}", e)
                    try {
                        record.release()
                    } catch (_: Exception) {}
                    return@start null
                }
                record
            },
            spawnWorker = { record ->
                Thread({
                    captureLoop(record)
                }, "CamCraft-Audio-Capture").apply {
                    isDaemon = true
                    start()
                }
            },
        )
        if (started) {
            Log.i(
                TAG,
                "AudioStreamEngine started (${SAMPLE_RATE_HZ}Hz Mono PCM_16BIT, ${FRAME_SIZE_BYTES}B/20ms, muted=$initiallyMuted)",
            )
        }
        return started
    }

    @SuppressLint("MissingPermission")
    private fun buildAudioRecord(bufferSizeBytes: Int): AudioRecord? {
        if (!hasRecordAudioPermission()) return null

        return try {
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE_HZ)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val record = AudioRecord.Builder()
                .setAudioSource(audioSource)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSizeBytes)
                .build()

            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                null
            } else {
                record
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception creating AudioRecord: ${e.message}", e)
            null
        }
    }

    private fun captureLoop(record: AudioRecord) {
        try {
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {}

            val configBytes = buildAudioConfigBytes()
            onAudioPacket(
                PacketType.AUDIO_CONFIG,
                System.nanoTime() / 1_000L,
                configBytes,
                configBytes.size,
            )

            val pcmBuffer = ByteArray(FRAME_SIZE_BYTES)
            val silentBuffer = ByteArray(FRAME_SIZE_BYTES)
            var abortCapture = false

            while (lifecycleCoordinator.isActive && !abortCapture) {
                var filled = 0
                while (filled < FRAME_SIZE_BYTES && lifecycleCoordinator.isActive) {
                    val read = try {
                        record.read(
                            pcmBuffer,
                            filled,
                            FRAME_SIZE_BYTES - filled,
                            AudioRecord.READ_BLOCKING,
                        )
                    } catch (e: Exception) {
                        if (lifecycleCoordinator.isActive) {
                            Log.w(TAG, "AudioRecord.read exception: ${e.message}")
                        }
                        -1
                    }

                    when (evaluateReadStep(read, filled)) {
                        ReadStepDecision.ADVANCE -> filled += read
                        ReadStepDecision.RETRY_FRAME -> break
                        ReadStepDecision.ABORT_CAPTURE -> {
                            if (read < 0 && lifecycleCoordinator.isActive) {
                                Log.w(TAG, "AudioRecord.read returned error code $read (filled=$filled)")
                            }
                            abortCapture = true
                            break
                        }
                    }
                }

                if (abortCapture || !lifecycleCoordinator.isActive) {
                    break
                }

                if (filled == FRAME_SIZE_BYTES) {
                    val ptsUs = System.nanoTime() / 1_000L
                    val payload = selectFramePayload(pcmBuffer, silentBuffer, isMuted.get())
                    onAudioPacket(PacketType.AUDIO_FRAME, ptsUs, payload, FRAME_SIZE_BYTES)
                }
            }
        } finally {
            val releasedOrphan = lifecycleCoordinator.onWorkerExit(record)
            if (releasedOrphan) {
                Log.w(TAG, "AudioStreamEngine captureLoop exited unexpectedly; AudioRecord released")
            }
        }
    }

    /**
     * Stops audio recording, joins the capture thread, and releases [AudioRecord].
     */
    fun stop() {
        if (lifecycleCoordinator.stop()) {
            Log.i(TAG, "AudioStreamEngine stopped")
        }
    }

    fun release() {
        stop()
    }

    companion object {
        private const val TAG = "AudioStreamEngine"

        const val SAMPLE_RATE_HZ = 48_000
        const val CHANNEL_COUNT = 1
        const val FRAME_DURATION_MS = 20
        const val SAMPLES_PER_FRAME = 960
        const val BYTES_PER_SAMPLE = 2
        const val FRAME_SIZE_BYTES = SAMPLES_PER_FRAME * BYTES_PER_SAMPLE * CHANNEL_COUNT // 1920 bytes
        const val CODEC_PCM_S16LE = "pcm_s16le"

        /**
         * Builds the canonical [AudioConfigPayload] for 48 kHz Mono 16-bit PCM.
         */
        fun buildAudioConfig(): AudioConfigPayload = AudioConfigPayload(
            sampleRate = SAMPLE_RATE_HZ,
            channels = CHANNEL_COUNT,
            codec = CODEC_PCM_S16LE,
        )

        /**
         * Serializes [buildAudioConfig] to UTF-8 JSON bytes for `AUDIO_CONFIG (0x03)`.
         */
        fun buildAudioConfigBytes(): ByteArray =
            CcftJson.encodeToString(buildAudioConfig()).toByteArray(Charsets.UTF_8)

        /**
         * Selects the silence buffer when [muted] is `true`, otherwise returns [capturedPcm]
         * without allocating a new byte array.
         */
        fun selectFramePayload(
            capturedPcm: ByteArray,
            silenceBuffer: ByteArray,
            muted: Boolean,
        ): ByteArray = if (muted) silenceBuffer else capturedPcm

        /**
         * Computes an [AudioRecord] internal buffer size at least 4 frames (80 ms) long
         * and aligned to an exact multiple of [FRAME_SIZE_BYTES].
         */
        fun calculateBufferSizeBytes(minBufferBytes: Int): Int {
            val minTarget = FRAME_SIZE_BYTES * 4
            if (minBufferBytes <= 0) return minTarget
            val candidate = maxOf(minBufferBytes, minTarget)
            val remainder = candidate % FRAME_SIZE_BYTES
            return if (remainder == 0) {
                candidate
            } else {
                candidate + (FRAME_SIZE_BYTES - remainder)
            }
        }

        internal enum class ReadStepDecision {
            ADVANCE,
            RETRY_FRAME,
            ABORT_CAPTURE,
        }

        /**
         * Classifies an `AudioRecord.read` return value given how many bytes [filledBefore]
         * have already been accumulated into the current 20 ms frame.
         * Any negative error code aborts capture immediately (discarding partial bytes).
         */
        internal fun evaluateReadStep(read: Int, filledBefore: Int): ReadStepDecision = when {
            read < 0 -> ReadStepDecision.ABORT_CAPTURE
            read == 0 && filledBefore == 0 -> ReadStepDecision.ABORT_CAPTURE
            read == 0 -> ReadStepDecision.RETRY_FRAME
            else -> ReadStepDecision.ADVANCE
        }
    }
}

/**
 * Serializes [start] and [stop] calls across threads without deadlocking against [onWorkerExit],
 * guaranteeing any prior audio resource and worker thread are stopped, joined, and released
 * before a subsequent resource can be constructed.
 */
internal class AudioLifecycleCoordinator<R : Any>(
    private val stopResource: (R) -> Unit,
    private val releaseResource: (R) -> Unit,
) {
    private val startStopLock = Any()
    private val stateLock = Any()
    private val running = AtomicBoolean(false)

    private var activeResource: R? = null
    private var activeThread: Thread? = null

    val isActive: Boolean
        get() = running.get()

    fun start(
        createAndStart: () -> R?,
        spawnWorker: (R) -> Thread,
    ): Boolean {
        synchronized(startStopLock) {
            val staleThread = synchronized(stateLock) {
                if (running.get()) return true
                activeThread.also { activeThread = null }
            }
            joinThread(staleThread)

            val resource = createAndStart() ?: return false
            synchronized(stateLock) {
                activeResource = resource
                running.set(true)
                try {
                    activeThread = spawnWorker(resource)
                } catch (e: Throwable) {
                    running.set(false)
                    activeResource = null
                    activeThread = null
                    try {
                        stopResource(resource)
                    } catch (_: Exception) {}
                    try {
                        releaseResource(resource)
                    } catch (_: Exception) {}
                    throw e
                }
            }
            return true
        }
    }

    fun stop(): Boolean = synchronized(startStopLock) {
        val (resource, thread) = synchronized(stateLock) {
            val wasRunning = running.getAndSet(false)
            val res = activeResource
            val thr = activeThread
            if (!wasRunning && res == null && thr == null) {
                return false
            }
            activeResource = null
            activeThread = null
            res to thr
        }

        if (resource != null) {
            try {
                stopResource(resource)
            } catch (_: Exception) {}
        }

        joinThread(thread)

        if (resource != null) {
            try {
                releaseResource(resource)
            } catch (_: Exception) {}
        }
        true
    }

    fun onWorkerExit(resource: R): Boolean {
        val orphan = synchronized(stateLock) {
            if (activeResource === resource) {
                running.set(false)
                activeResource = null
                resource
            } else {
                null
            }
        }
        if (orphan != null) {
            try {
                stopResource(orphan)
            } catch (_: Exception) {}
            try {
                releaseResource(orphan)
            } catch (_: Exception) {}
        }
        synchronized(stateLock) {
            if (activeThread === Thread.currentThread()) {
                activeThread = null
            }
        }
        return orphan != null
    }

    private fun joinThread(thread: Thread?) {
        if (thread != null && thread !== Thread.currentThread()) {
            try {
                thread.join(500)
                if (thread.isAlive) {
                    thread.interrupt()
                    thread.join(100)
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
