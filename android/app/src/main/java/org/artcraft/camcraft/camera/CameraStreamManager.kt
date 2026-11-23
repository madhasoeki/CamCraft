package org.artcraft.camcraft.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.MeteringPoint
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt
import org.artcraft.camcraft.encoder.H264SurfaceEncoder
import org.artcraft.camcraft.protocol.PacketType

/**
 * Custom [LifecycleOwner] enabling CameraX to stream continuously inside
 * an Android [android.app.Service] independent of Activity lifecycle.
 */
class ServiceLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        runOnMain {
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    fun start() {
        runOnMain {
            if (registry.currentState == Lifecycle.State.DESTROYED) return@runOnMain
            registry.currentState = Lifecycle.State.STARTED
            registry.currentState = Lifecycle.State.RESUMED
        }
    }

    fun stop() {
        runOnMain {
            if (registry.currentState == Lifecycle.State.DESTROYED) return@runOnMain
            if (registry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                registry.currentState = Lifecycle.State.STARTED
            }
            registry.currentState = Lifecycle.State.CREATED
        }
    }

    fun destroy() {
        runOnMain {
            if (registry.currentState != Lifecycle.State.DESTROYED) {
                registry.currentState = Lifecycle.State.DESTROYED
            }
        }
    }

    override val lifecycle: Lifecycle
        get() = registry
}

/**
 * Coordinates safe retirement of an encoder surface so [H264SurfaceEncoder.release]
 * is never invoked while CameraX still holds a provided surface.
 */
internal class EncoderSurfaceRetirementTracker {
    private val lock = Any()
    private var inFlightSurfaces: Int = 0
    private var retirementPending: Boolean = false
    private var released: Boolean = false

    fun onSurfaceProvided(): Boolean = synchronized(lock) {
        if (retirementPending || released) {
            return@synchronized false
        }
        inFlightSurfaces++
        true
    }

    fun onSurfaceReturned(): Boolean = synchronized(lock) {
        if (inFlightSurfaces > 0) {
            inFlightSurfaces--
        }
        if (retirementPending && inFlightSurfaces == 0 && !released) {
            released = true
            true
        } else {
            false
        }
    }

    fun requestRetire(): Boolean = synchronized(lock) {
        retirementPending = true
        if (inFlightSurfaces == 0 && !released) {
            released = true
            true
        } else {
            false
        }
    }

    internal fun inFlightCount(): Int = synchronized(lock) { inFlightSurfaces }
    internal fun isRetirementPending(): Boolean = synchronized(lock) { retirementPending }
    internal fun isReleased(): Boolean = synchronized(lock) { released }
}

internal class ActiveEncoderSlot(
    val sessionId: Long,
    val generation: Long,
    val encoder: H264SurfaceEncoder?,
    val width: Int,
    val height: Int,
    val fps: Int,
    val cameraId: String,
    val surfaceTracker: EncoderSurfaceRetirementTracker,
    val cameraConfigId: Long = 0L,
    val geometryRequestSeq: Long = 0L,
    val pendingResolutionAdjustment: Boolean = false,
    val fallbackWidth: Int = width,
    val fallbackHeight: Int = height,
    @Volatile var isRetiring: Boolean = false,
    @Volatile var resolutionAdjusted: Boolean = false,
    @Volatile var negotiatedDimensionsCommitted: Boolean = false,
) {
    private val releaseLock = Any()
    private val resolutionAdjustmentOutcomeReported = AtomicBoolean(false)
    private val terminalFailureReported = AtomicBoolean(false)
    private val currentSurfaceRequestRef = AtomicReference<Any?>(null)
    val releasedLatch = CountDownLatch(1)

    @Volatile
    private var released = false
    @Volatile
    private var teardownFinished = false
    @Volatile
    private var releaseFailed = false
    private val releaseListeners = mutableListOf<(Boolean) -> Unit>()

    val isReleased: Boolean
        get() = released

    val isTeardownFinished: Boolean
        get() = teardownFinished

    val isReleaseFailed: Boolean
        get() = releaseFailed

    val isTerminalFailureReported: Boolean
        get() = terminalFailureReported.get()

    fun setCurrentSurfaceRequest(request: Any) {
        currentSurfaceRequestRef.set(request)
    }

    fun isCurrentSurfaceRequest(request: Any): Boolean =
        currentSurfaceRequestRef.get() === request

    fun clearCurrentSurfaceRequestIfMatches(request: Any): Boolean =
        currentSurfaceRequestRef.compareAndSet(request, null)

    fun clearCurrentSurfaceRequest() {
        currentSurfaceRequestRef.set(null)
    }

    fun hasPendingResolutionAdjustmentUnreported(): Boolean =
        pendingResolutionAdjustment && !resolutionAdjustmentOutcomeReported.get()

    fun isResolutionAdjustmentReported(): Boolean = resolutionAdjustmentOutcomeReported.get()

    fun markResolutionAdjustmentReported(): Boolean =
        pendingResolutionAdjustment && resolutionAdjustmentOutcomeReported.compareAndSet(false, true)

    fun markTerminalFailureReported(): Boolean {
        if (!terminalFailureReported.compareAndSet(false, true)) {
            return false
        }
        if (pendingResolutionAdjustment) {
            resolutionAdjustmentOutcomeReported.set(true)
        }
        isRetiring = true
        return true
    }

    fun awaitReleased(timeoutMs: Long): Boolean {
        if (teardownFinished) return released
        return try {
            releasedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)
            released
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            released
        }
    }

    /**
     * Registers a one-shot completion hook invoked only when codec release succeeds.
     * If already released successfully, invokes [listener] immediately; if teardown
     * completed with a native release failure, does not invoke [listener].
     */
    fun onReleased(listener: () -> Unit) {
        onReleaseFinished { codecReleased ->
            if (codecReleased) {
                listener()
            }
        }
    }

    /**
     * Registers a one-shot completion hook invoked when teardown finishes with the
     * terminal codec release result (`true` on success, `false` on failure).
     */
    fun onReleaseFinished(listener: (codecReleased: Boolean) -> Unit) {
        val (runImmediately, releasedSnapshot) = synchronized(releaseLock) {
            if (teardownFinished) {
                true to released
            } else {
                releaseListeners.add(listener)
                false to false
            }
        }
        if (runImmediately) {
            listener(releasedSnapshot)
        }
    }

    fun markReleased() {
        markReleaseFinished(codecReleased = true)
    }

    fun markReleaseFinished(codecReleased: Boolean) {
        val listeners = synchronized(releaseLock) {
            if (teardownFinished) {
                return
            }
            if (codecReleased) {
                released = true
            } else {
                releaseFailed = true
            }
            teardownFinished = true
            releasedLatch.countDown()
            val copy = releaseListeners.toList()
            releaseListeners.clear()
            copy
        }
        for (listener in listeners) {
            try {
                listener(codecReleased)
            } catch (_: Throwable) {
            }
        }
    }
}

internal data class BindSnapshot(
    val sessionId: Long,
    val generation: Long,
    val slot: ActiveEncoderSlot,
    val surface: android.view.Surface,
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val cameraConfigId: Long = 0L,
    val geometryRequestSeq: Long = 0L,
    val fallbackWidth: Int = width,
    val fallbackHeight: Int = height,
)

internal class EncoderDrainThreadStartupException(
    cause: OutOfMemoryError,
) : RuntimeException(
    cause.message?.takeIf { it.isNotBlank() }
        ?: "Failed to start encoder drain thread (OutOfMemoryError)",
    cause,
)

/**
 * Manages the CameraX lifecycle, GPU surface routing to [H264SurfaceEncoder],
 * real-time camera controls (Zoom, EV, Torch, AF, Tap-to-Focus), and stream reconfiguration.
 */
class CameraStreamManager(
    private val context: Context,
    private val onVideoPacketSlice: (
        sessionId: Long,
        cameraConfigId: Long,
        encoderGeneration: Long,
        packetType: PacketType,
        flags: Int,
        ptsUs: Long,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) -> Unit,
    private val onResolutionAdjusted: ((
        sessionId: Long,
        cameraConfigId: Long,
        cameraId: String,
        width: Int,
        height: Int,
        commitNegotiatedDimensions: () -> Boolean,
    ) -> Unit)? = null,
    private val onResolutionAdjustmentFailed: ((sessionId: Long, cameraConfigId: Long, cameraId: String, reason: String) -> Unit)? = null,
) {
    constructor(
        context: Context,
        onVideoPacketSlice: (
            packetType: PacketType,
            flags: Int,
            ptsUs: Long,
            payload: ByteArray,
            offset: Int,
            length: Int,
        ) -> Unit,
    ) : this(
        context = context,
        onVideoPacketSlice = { _, _, _, packetType, flags, ptsUs, payload, offset, length ->
            onVideoPacketSlice(packetType, flags, ptsUs, payload, offset, length)
        },
        onResolutionAdjusted = null,
    )

    constructor(
        context: Context,
        onVideoPacket: (packetType: PacketType, flags: Int, ptsUs: Long, payload: ByteArray) -> Unit,
    ) : this(
        context = context,
        onVideoPacketSlice = { _, _, _, packetType, flags, ptsUs, payload, offset, length ->
            val standalone = if (offset == 0 && length == payload.size) {
                payload
            } else {
                payload.copyOfRange(offset, offset + length)
            }
            onVideoPacket(packetType, flags, ptsUs, standalone)
        },
        onResolutionAdjusted = null,
    )

    private val lifecycleOwner = ServiceLifecycleOwner()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CamCraft-Camera-Executor").apply { isDaemon = true }
    }
    private val encoderExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CamCraft-Encoder-Lifecycle").apply { isDaemon = true }
    }
    private val encoderReleaseExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CamCraft-Encoder-Release").apply { isDaemon = true }
    }
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isReleased = AtomicBoolean(false)
    private val surfaceLock = Any()
    private val pipelineGeneration = AtomicLong(0L)

    @Volatile
    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile
    private var activeCamera: Camera? = null
    private var activeSlot: ActiveEncoderSlot? = null
    private var startingSlot: ActiveEncoderSlot? = null
    private var lastDetachedSlot: ActiveEncoderSlot? = null
    private val deferredRetireSlots = mutableSetOf<ActiveEncoderSlot>()

    @Volatile
    private var activeSessionId: Long = 0L
    @Volatile
    private var lastAppliedConfigSeq: Long = -1L

    // Requested camera geometry & adjustment lifecycle under surfaceLock
    private var requestedCameraId: String = "0"
    private var requestedWidth: Int = 1920
    private var requestedHeight: Int = 1080
    private var requestedFps: Int = 60
    private var requestedGeometrySeq: Long = 0L
    private var pipelineHealthyOrStarting: Boolean = false
    private var resolutionAdjustmentInFlight: Boolean = false

    // Monotonic control ordering tokens under surfaceLock (targets mutated sequentially on encoderExecutor)
    private var nextControlToken: Long = 0L
    private var latestZoomToken: Long = 0L
    private var latestExposureToken: Long = 0L
    private var latestTorchToken: Long = 0L
    private var latestAutoFocusToken: Long = 0L

    private var encoderPreview: Preview? = null
    private var uiPreview: Preview? = null
    private var attachedPreviewViewRef: WeakReference<PreviewView>? = null
    private var currentFpsPlan: TargetFpsPlan? = null

    // Active streaming parameters (mutated sequentially on encoderExecutor)
    @Volatile
    var currentCameraId: String = "0"
        private set
    @Volatile
    var currentWidth: Int = 1920
        private set
    @Volatile
    var currentHeight: Int = 1080
        private set
    @Volatile
    var currentFps: Int = 60
        private set
    @Volatile
    var currentBitrateKbps: Int = 12_000
        private set

    // Control states
    @Volatile
    private var targetZoomRatio: Float = 1.0f
    @Volatile
    private var targetExposureEv: Float = 0.0f
    @Volatile
    private var targetTorch: Boolean = false
    @Volatile
    private var targetAutoFocus: Boolean = true

    @Volatile
    var isStreaming: Boolean = false
        private set

    val cachedConfig: ByteArray?
        get() = cachedConfigForSession(activeSessionId)

    /**
     * Returns cached SPS/PPS codec config only if [sessionId] owns the active non-retiring encoder slot.
     */
    fun cachedConfigForSession(sessionId: Long): ByteArray? {
        if (isReleased.get()) return null
        val encoder = synchronized(surfaceLock) {
            if (isSlotValidForSession(activeSessionId, sessionId, activeSlot)) {
                activeSlot?.encoder
            } else {
                null
            }
        } ?: return null
        return encoder.cachedConfig
    }

    internal fun isVideoPipelineCurrent(
        sessionId: Long,
        encoderGeneration: Long,
    ): Boolean = synchronized(surfaceLock) {
        val currentSlot = activeSlot ?: startingSlot
        isVideoPacketEligibleForWrite(
            isReleased = isReleased.get(),
            isStreaming = isStreaming,
            activeSessionId = activeSessionId,
            packetSessionId = sessionId,
            currentGeneration = pipelineGeneration.get(),
            packetGeneration = encoderGeneration,
            currentSlotSessionId = currentSlot?.sessionId,
            currentSlotGeneration = currentSlot?.generation,
            currentSlotRetiring = currentSlot?.isRetiring ?: true,
        )
    }

    init {
        initCameraProvider()
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainExecutor.execute(block)
        }
    }

    private fun runOnMainBlockingWithOutcome(
        timeoutMs: Long = DEFAULT_MAIN_UNBIND_TIMEOUT_MS,
        onDeferredComplete: () -> Unit = {},
        block: () -> Unit,
    ): MainWaitOutcome = runOnMainBlockingWithOutcome(
        isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
        post = { runnable -> mainHandler.post(runnable) },
        removeCallbacks = { runnable -> mainHandler.removeCallbacks(runnable) },
        timeoutMs = timeoutMs,
        onWarn = { message -> Log.w(TAG, message) },
        onDeferredComplete = onDeferredComplete,
        block = block,
    )

    private fun executeOnEncoderExecutor(task: () -> Unit) {
        try {
            encoderExecutor.execute(task)
        } catch (e: Exception) {
            Log.w(TAG, "Failed scheduling encoder lifecycle task: ${e.message}")
        }
    }

    private fun initCameraProvider() {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    if (isReleased.get()) {
                        return@addListener
                    }
                    cameraProvider = provider
                    Log.i(TAG, "ProcessCameraProvider initialized")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to get ProcessCameraProvider: ${e.message}", e)
                }
            }, mainExecutor)
        } catch (e: Exception) {
            Log.w(TAG, "Cannot initialize ProcessCameraProvider: ${e.message}")
        }
    }

    /**
     * Starts the H.264 encoder off the Main thread and binds CameraX to stream directly
     * to the encoder's input surface.
     */
    fun startStream(
        sessionId: Long = DEFAULT_SESSION_ID,
        configSeq: Long = 0L,
        cameraConfigId: Long = 0L,
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
        zoomRatio: Float? = null,
        exposureEv: Float? = null,
        torchOn: Boolean? = null,
    ) {
        val generation: Long
        val geometrySeq: Long
        val zoomToken: Long
        val exposureToken: Long
        val torchToken: Long
        synchronized(surfaceLock) {
            if (isReleased.get()) return
            activeSessionId = sessionId
            lastAppliedConfigSeq = configSeq
            isStreaming = true
            requestedCameraId = cameraId
            requestedWidth = width
            requestedHeight = height
            requestedFps = fps
            geometrySeq = ++requestedGeometrySeq
            pipelineHealthyOrStarting = true
            resolutionAdjustmentInFlight = false
            activeSlot?.isRetiring = true
            startingSlot?.isRetiring = true
            generation = pipelineGeneration.incrementAndGet()
            zoomToken = if (zoomRatio != null) {
                val token = ++nextControlToken
                latestZoomToken = token
                token
            } else {
                0L
            }
            exposureToken = if (exposureEv != null) {
                val token = ++nextControlToken
                latestExposureToken = token
                token
            } else {
                0L
            }
            torchToken = if (torchOn != null) {
                val token = ++nextControlToken
                latestTorchToken = token
                token
            } else {
                0L
            }
        }
        executeOnEncoderExecutor {
            var shouldStart = false
            var applyZoom = false
            var applyExposure = false
            var applyTorch = false
            synchronized(surfaceLock) {
                if (isCurrentSessionGenerationLocked(sessionId, generation)) {
                    shouldStart = true
                    applyZoom = zoomRatio != null && shouldApplyControlToken(zoomToken, latestZoomToken)
                    applyExposure = exposureEv != null && shouldApplyControlToken(exposureToken, latestExposureToken)
                    applyTorch = torchOn != null && shouldApplyControlToken(torchToken, latestTorchToken)
                }
            }
            if (!shouldStart) {
                return@executeOnEncoderExecutor
            }
            if (applyZoom && zoomRatio != null) targetZoomRatio = zoomRatio
            if (applyExposure && exposureEv != null) targetExposureEv = exposureEv
            if (applyTorch && torchOn != null) targetTorch = torchOn
            currentCameraId = cameraId
            currentWidth = width
            currentHeight = height
            currentFps = fps
            currentBitrateKbps = bitrateKbps

            setupEncoderAndBindSync(
                sessionId = sessionId,
                generation = generation,
                cameraConfigId = cameraConfigId,
                geometryRequestSeq = geometrySeq,
                cameraId = cameraId,
                width = width,
                height = height,
                fps = fps,
                bitrateKbps = bitrateKbps,
            )
        }
    }

    fun startStream(
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
    ) = startStream(
        sessionId = DEFAULT_SESSION_ID,
        configSeq = 0L,
        cameraConfigId = 0L,
        cameraId = cameraId,
        width = width,
        height = height,
        fps = fps,
        bitrateKbps = bitrateKbps,
    )

    internal fun willRestartPipelineForRebind(
        sessionId: Long,
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
    ): Boolean = synchronized(surfaceLock) {
        shouldAdvanceCameraConfigForRebind(
            isReleased = isReleased.get(),
            isStreaming = isStreaming,
            activeSessionId = activeSessionId,
            requestSessionId = sessionId,
            pipelineHealthyOrStarting = pipelineHealthyOrStarting,
            resolutionAdjustmentInFlight = resolutionAdjustmentInFlight,
            requestedCameraId = requestedCameraId,
            requestedWidth = requestedWidth,
            requestedHeight = requestedHeight,
            requestedFps = requestedFps,
            newCameraId = cameraId,
            newWidth = width,
            newHeight = height,
            newFps = fps,
        )
    }

    private fun isCurrentSessionGenerationLocked(sessionId: Long, generation: Long): Boolean =
        shouldActivateLifecycleOnBind(
            isReleased = isReleased.get(),
            isStreaming = isStreaming,
            activeSessionId = activeSessionId,
            snapshotSessionId = sessionId,
            currentGeneration = pipelineGeneration.get(),
            snapshotGeneration = generation,
        )

    /**
     * Unbinds CameraX use cases owned by this manager on Main and retires the encoder off Main.
     */
    fun stopStream(sessionId: Long? = null) {
        val generation = synchronized(surfaceLock) {
            if (sessionId != null && activeSessionId != sessionId) {
                return
            }
            isStreaming = false
            activeSessionId = 0L
            lastAppliedConfigSeq = -1L
            requestedGeometrySeq++
            pipelineHealthyOrStarting = false
            resolutionAdjustmentInFlight = false
            activeSlot?.isRetiring = true
            startingSlot?.isRetiring = true
            pipelineGeneration.incrementAndGet()
        }

        executeOnEncoderExecutor {
            cleanupActivePipelineOnEncoderThread()
            runOnMain {
                if (!isReleased.get() && !isStreaming) {
                    lifecycleOwner.stop()
                }
                Log.i(TAG, "CameraStreamManager stopped (gen=$generation)")
            }
        }
    }

    private fun unbindOwnedUseCasesOnMain(
        provider: ProcessCameraProvider? = cameraProvider,
        vararg candidates: Preview?,
    ) {
        val toUnbind = distinctOwnedUseCases(*candidates)
        if (provider != null && toUnbind.isNotEmpty()) {
            try {
                provider.unbind(*toUnbind.toTypedArray())
            } catch (e: Exception) {
                Log.w(TAG, "Exception unbinding owned use cases: ${e.message}")
            }
        }
    }

    private fun cleanupActivePipelineOnEncoderThread(
        expectedSlot: ActiveEncoderSlot? = null,
    ): ActiveEncoderSlot? {
        val (slotToRetire, ownedUseCases, retiringSlotToWaitFor) = synchronized(surfaceLock) {
            if (expectedSlot != null && activeSlot !== expectedSlot) {
                return null
            }
            val detached = activeSlot
            activeSlot = null
            if (detached != null) {
                detached.isRetiring = true
                deferredRetireSlots.add(detached)
                lastDetachedSlot = detached
            }
            val owned = listOfNotNull(encoderPreview, uiPreview)
            encoderPreview = null
            uiPreview = null
            activeCamera = null
            currentFpsPlan = null
            val waitTarget = detached ?: lastDetachedSlot?.takeIf { !it.isTeardownFinished }
            Triple(detached, owned, waitTarget)
        }

        if (ownedUseCases.isEmpty()) {
            if (slotToRetire != null) {
                retireSlot(slotToRetire)
            }
            return retiringSlotToWaitFor
        }

        retirePipelineOnUnbindOutcome(
            runBlockingWait = { onDeferredComplete, block ->
                runOnMainBlockingWithOutcome(
                    onDeferredComplete = onDeferredComplete,
                    block = block,
                )
            },
            unbindOnMain = {
                unbindOwnedUseCasesOnMain(cameraProvider, *ownedUseCases.toTypedArray())
            },
            scheduleMainRetry = { retryRunnable ->
                try {
                    mainExecutor.execute(retryRunnable)
                    true
                } catch (e: Exception) {
                    Log.w(TAG, "Failed scheduling main-thread unbind retry: ${e.message}")
                    false
                }
            },
            retire = {
                if (slotToRetire != null) {
                    retireSlot(slotToRetire)
                }
            },
        )
        return retiringSlotToWaitFor
    }

    private fun retireSlot(slot: ActiveEncoderSlot) {
        if (slot.surfaceTracker.requestRetire()) {
            finalizeSlotRetirement(slot)
        }
    }

    private fun finalizeSlotRetirement(slot: ActiveEncoderSlot) {
        synchronized(surfaceLock) {
            deferredRetireSlots.remove(slot)
        }
        releaseEncoderOffMain(slot)
        maybeShutdownCameraExecutorIfReleased()
    }

    private fun releaseEncoderOffMain(slot: ActiveEncoderSlot) {
        val encoder = slot.encoder
        if (encoder == null) {
            slot.markReleased()
            return
        }
        val releaseTask = Runnable {
            try {
                encoder.releaseWithResult(onTeardownCompleted = slot::markReleaseFinished)
            } catch (e: Throwable) {
                Log.w(TAG, "Unexpected error requesting encoder release: ${e.message}")
                slot.markReleaseFinished(codecReleased = false)
            }
        }
        try {
            encoderReleaseExecutor.execute(releaseTask)
        } catch (_: RejectedExecutionException) {
            Thread(releaseTask, "CamCraft-Encoder-Release-Fallback").apply {
                isDaemon = true
                start()
            }
        } catch (_: Exception) {
            Thread(releaseTask, "CamCraft-Encoder-Release-Fallback").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun maybeShutdownCameraExecutorIfReleased() {
        if (!isReleased.get()) return
        val canShutdown = synchronized(surfaceLock) {
            activeSlot == null && deferredRetireSlots.isEmpty()
        }
        if (canShutdown) {
            cameraProvider = null
            cameraExecutor.shutdown()
            encoderReleaseExecutor.shutdown()
        }
    }

    /**
     * Attaches a [PreviewView] from the UI to display the camera viewfinder on phone screen.
     * Deduplicated if the same [PreviewView] is already attached and bound; binds only `uiPreview`
     * when `encoderPreview` is already healthy to avoid interrupting live H.264 encoding.
     */
    fun attachPreviewView(previewView: PreviewView) {
        if (isReleased.get()) return
        runOnMain {
            if (isReleased.get()) return@runOnMain
            val existingView = attachedPreviewViewRef?.get()
            val sameView = existingView === previewView
            val provider = cameraProvider
            val (encPrev, existingUi, snapshot) = synchronized(surfaceLock) {
                val slot = activeSlot
                val snap = if (slot != null &&
                    !slot.isRetiring &&
                    slot.sessionId == activeSessionId
                ) {
                    slot.encoder?.inputSurface?.takeIf { it.isValid }?.let { surface ->
                        BindSnapshot(
                            sessionId = slot.sessionId,
                            generation = slot.generation,
                            slot = slot,
                            surface = surface,
                            cameraId = slot.cameraId,
                            width = slot.width,
                            height = slot.height,
                            fps = slot.fps,
                            cameraConfigId = slot.cameraConfigId,
                            geometryRequestSeq = slot.geometryRequestSeq,
                            fallbackWidth = slot.fallbackWidth,
                            fallbackHeight = slot.fallbackHeight,
                        )
                    }
                } else {
                    null
                }
                Triple(encoderPreview, uiPreview, snap)
            }
            val encoderBound = provider != null && encPrev != null && provider.isBound(encPrev)
            val uiBound = provider != null && existingUi != null && provider.isBound(existingUi)

            val action = decideAttachPreviewAction(
                isStreaming = isStreaming,
                isReleased = isReleased.get(),
                sameViewAlreadyAttached = sameView,
                uiPreviewBound = uiBound,
                encoderPreviewBound = encoderBound,
            )
            if (!sameView) {
                attachedPreviewViewRef = WeakReference(previewView)
            }

            when (action) {
                PreviewSyncAction.NO_OP -> return@runOnMain
                PreviewSyncAction.REBIND_UI_ONLY -> {
                    val activeProvider = provider ?: return@runOnMain
                    val activeSnap = snapshot ?: return@runOnMain
                    if (existingUi != null) {
                        unbindOwnedUseCasesOnMain(activeProvider, existingUi)
                        synchronized(surfaceLock) {
                            if (uiPreview === existingUi) {
                                uiPreview = null
                            }
                        }
                    }
                    val plan = currentFpsPlan ?: CameraFpsCapabilities.resolveForCamera(
                        context = context,
                        cameraId = activeSnap.cameraId,
                        width = activeSnap.width,
                        height = activeSnap.height,
                        requestedFps = activeSnap.fps,
                    )
                    val newUiPrev = buildUiPreview(previewView, activeSnap, plan)
                    try {
                        val boundCamera = activeProvider.bindToLifecycle(
                            lifecycleOwner,
                            buildCameraSelector(activeSnap.cameraId),
                            newUiPrev,
                        )
                        val keepBound = synchronized(surfaceLock) {
                            if (isSnapshotStillActiveLocked(activeSnap)) {
                                activeCamera = boundCamera
                                uiPreview = newUiPrev
                                true
                            } else {
                                false
                            }
                        }
                        if (keepBound) {
                            applyActiveControls()
                        } else {
                            unbindOwnedUseCasesOnMain(activeProvider, newUiPrev)
                        }
                    } catch (dualError: Exception) {
                        Log.w(
                            TAG,
                            "Incremental UI preview binding failed (${dualError.message})",
                        )
                        unbindOwnedUseCasesOnMain(activeProvider, newUiPrev)
                        synchronized(surfaceLock) {
                            uiPreview = null
                        }
                        val encoderStillBound = encPrev != null && activeProvider.isBound(encPrev)
                        if (shouldFallbackFullRebindAfterUiFailure(encoderStillBound)) {
                            Log.w(TAG, "Encoder preview was lost during UI bind failure; falling back to bindUseCases")
                            bindUseCases()
                        }
                    }
                }
                PreviewSyncAction.FULL_REBIND -> bindUseCases()
                PreviewSyncAction.UNBIND_UI_ONLY -> Unit
            }
        }
    }

    /**
     * Detaches the UI [PreviewView] when screen is dimmed or Activity is backgrounded.
     * Idempotent when already detached, and unbinds only `uiPreview` when `encoderPreview`
     * is healthy so H.264 streaming to PC continues uninterrupted.
     */
    fun detachPreviewView() {
        runOnMain {
            val (encPrev, existingUi) = synchronized(surfaceLock) {
                encoderPreview to uiPreview
            }
            val hasAttachedOrUi = attachedPreviewViewRef != null || existingUi != null
            val provider = cameraProvider
            val encoderBound = provider != null && encPrev != null && provider.isBound(encPrev)

            val action = decideDetachPreviewAction(
                isStreaming = isStreaming,
                isReleased = isReleased.get(),
                hasAttachedOrUiPreview = hasAttachedOrUi,
            )
            attachedPreviewViewRef = null
            if (action == PreviewSyncAction.NO_OP) {
                return@runOnMain
            }

            synchronized(surfaceLock) {
                if (uiPreview === existingUi) {
                    uiPreview = null
                }
            }
            if (existingUi != null && provider != null) {
                unbindOwnedUseCasesOnMain(provider, existingUi)
            } else if (!encoderBound && isStreaming && !isReleased.get()) {
                bindUseCases()
            }
        }
    }

    /**
     * Dynamically reconfigures resolution, framerate, bitrate, or switches lens.
     * Bitrate-only updates adjust [MediaCodec] dynamically without restarting CameraX;
     * camera/geometry/FPS changes restart the encoder so fresh `VIDEO_CONFIG` + IDR are sent.
     *
     * Optional [zoomRatio], [exposureEv], and [torchOn] are applied atomically on [encoderExecutor]
     * in the same ordered camera task as this rebind.
     */
    fun rebindCamera(
        sessionId: Long,
        configSeq: Long,
        cameraConfigId: Long = 0L,
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
        zoomRatio: Float? = null,
        exposureEv: Float? = null,
        torchOn: Boolean? = null,
    ) {
        if (isReleased.get()) return
        var advancedGeometry = false
        var acceptedGeometrySeq: Long
        val zoomToken: Long
        val exposureToken: Long
        val torchToken: Long
        synchronized(surfaceLock) {
            if (!shouldApplyRebind(
                    isReleased = isReleased.get(),
                    isStreaming = isStreaming,
                    activeSessionId = activeSessionId,
                    requestSessionId = sessionId,
                    configSeq = configSeq,
                    lastAppliedConfigSeq = lastAppliedConfigSeq,
                )
            ) {
                return
            }
            if (shouldAdvanceCameraConfigForRebind(
                    isReleased = false,
                    isStreaming = true,
                    activeSessionId = activeSessionId,
                    requestSessionId = sessionId,
                    pipelineHealthyOrStarting = pipelineHealthyOrStarting,
                    resolutionAdjustmentInFlight = resolutionAdjustmentInFlight,
                    requestedCameraId = requestedCameraId,
                    requestedWidth = requestedWidth,
                    requestedHeight = requestedHeight,
                    requestedFps = requestedFps,
                    newCameraId = cameraId,
                    newWidth = width,
                    newHeight = height,
                    newFps = fps,
                )
            ) {
                requestedCameraId = cameraId
                requestedWidth = width
                requestedHeight = height
                requestedFps = fps
                acceptedGeometrySeq = ++requestedGeometrySeq
                pipelineHealthyOrStarting = true
                resolutionAdjustmentInFlight = false
                advancedGeometry = true
            } else {
                acceptedGeometrySeq = requestedGeometrySeq
            }
            zoomToken = if (zoomRatio != null) {
                val token = ++nextControlToken
                latestZoomToken = token
                token
            } else {
                0L
            }
            exposureToken = if (exposureEv != null) {
                val token = ++nextControlToken
                latestExposureToken = token
                token
            } else {
                0L
            }
            torchToken = if (torchOn != null) {
                val token = ++nextControlToken
                latestTorchToken = token
                token
            } else {
                0L
            }
        }

        executeOnEncoderExecutor {
            var currentSlot: ActiveEncoderSlot? = null
            var canApply = false
            var applyZoom = false
            var applyExposure = false
            var applyTorch = false
            synchronized(surfaceLock) {
                if (shouldApplyRebind(
                        isReleased = isReleased.get(),
                        isStreaming = isStreaming,
                        activeSessionId = activeSessionId,
                        requestSessionId = sessionId,
                        configSeq = configSeq,
                        lastAppliedConfigSeq = lastAppliedConfigSeq,
                    )
                ) {
                    canApply = true
                    lastAppliedConfigSeq = configSeq
                    currentSlot = activeSlot
                    applyZoom = zoomRatio != null && shouldApplyControlToken(zoomToken, latestZoomToken)
                    applyExposure = exposureEv != null && shouldApplyControlToken(exposureToken, latestExposureToken)
                    applyTorch = torchOn != null && shouldApplyControlToken(torchToken, latestTorchToken)
                }
            }
            if (!canApply) {
                return@executeOnEncoderExecutor
            }

            if (applyZoom && zoomRatio != null) targetZoomRatio = zoomRatio
            if (applyExposure && exposureEv != null) targetExposureEv = exposureEv
            if (applyTorch && torchOn != null) targetTorch = torchOn

            val slotSnapshot = currentSlot
            val configChanged = cameraConfigId != 0L &&
                slotSnapshot != null &&
                slotSnapshot.cameraConfigId != cameraConfigId
            val cameraChanged = cameraId != currentCameraId || configChanged
            val geometryOrFpsChanged = width != currentWidth ||
                height != currentHeight ||
                fps != currentFps
            val bitrateChanged = bitrateKbps != currentBitrateKbps

            val decision = evaluateRebindDecision(
                cameraChanged = cameraChanged,
                geometryOrFpsChanged = geometryOrFpsChanged,
                bitrateChanged = bitrateChanged,
                hasEncoder = isSlotUsableForRebind(slotSnapshot),
            )

            currentCameraId = cameraId
            currentWidth = width
            currentHeight = height
            currentFps = fps
            currentBitrateKbps = bitrateKbps

            if (decision.needsEncoderRestart) {
                var generation = 0L
                var geomSeq = 0L
                val canRestart = synchronized(surfaceLock) {
                    if (!isStreaming || isReleased.get() || activeSessionId != sessionId) {
                        false
                    } else {
                        if (!advancedGeometry && acceptedGeometrySeq == requestedGeometrySeq) {
                            requestedCameraId = cameraId
                            requestedWidth = width
                            requestedHeight = height
                            requestedFps = fps
                            requestedGeometrySeq++
                            pipelineHealthyOrStarting = true
                            resolutionAdjustmentInFlight = false
                            geomSeq = requestedGeometrySeq
                        } else {
                            geomSeq = acceptedGeometrySeq
                        }
                        generation = pipelineGeneration.incrementAndGet()
                        true
                    }
                }
                if (!canRestart) {
                    return@executeOnEncoderExecutor
                }
                setupEncoderAndBindSync(
                    sessionId = sessionId,
                    generation = generation,
                    cameraConfigId = cameraConfigId,
                    geometryRequestSeq = geomSeq,
                    cameraId = cameraId,
                    width = width,
                    height = height,
                    fps = fps,
                    bitrateKbps = bitrateKbps,
                )
            } else {
                if (applyZoom) {
                    runOnMain {
                        if (!isReleased.get()) applyZoomRatioToActiveCamera(targetZoomRatio)
                    }
                }
                if (applyExposure) {
                    runOnMain {
                        if (!isReleased.get()) applyExposureEvToActiveCamera(targetExposureEv)
                    }
                }
                if (applyTorch) {
                    runOnMain {
                        if (!isReleased.get()) applyTorchToActiveCamera(targetTorch)
                    }
                }
                if (decision.needsBitrateUpdate) {
                    slotSnapshot?.encoder?.updateBitrate(bitrateKbps)
                }
            }
        }
    }

    private fun reconfigureForSelectedResolution(
        expectedSessionId: Long,
        expectedGeneration: Long,
        expectedCameraConfigId: Long,
        expectedGeometryRequestSeq: Long,
        expectedCameraId: String,
        previousWidth: Int,
        previousHeight: Int,
        expectedFps: Int,
        selectedWidth: Int,
        selectedHeight: Int,
    ) {
        val nextGeneration = synchronized(surfaceLock) {
            if (!isCurrentSessionGenerationLocked(expectedSessionId, expectedGeneration) ||
                !shouldApplyNegotiatedDimensionsToRequest(expectedGeometryRequestSeq, requestedGeometrySeq)
            ) {
                return
            }
            resolutionAdjustmentInFlight = true
            pipelineGeneration.incrementAndGet()
        }
        currentWidth = selectedWidth
        currentHeight = selectedHeight
        setupEncoderAndBindSync(
            sessionId = expectedSessionId,
            generation = nextGeneration,
            cameraConfigId = expectedCameraConfigId,
            geometryRequestSeq = expectedGeometryRequestSeq,
            cameraId = expectedCameraId,
            width = selectedWidth,
            height = selectedHeight,
            fps = expectedFps,
            bitrateKbps = currentBitrateKbps,
            markResolutionAdjusted = true,
            previousWidth = previousWidth,
            previousHeight = previousHeight,
        )
    }

    private fun setupEncoderAndBindSync(
        sessionId: Long,
        generation: Long,
        cameraConfigId: Long = 0L,
        geometryRequestSeq: Long = 0L,
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
        markResolutionAdjusted: Boolean = false,
        previousWidth: Int = width,
        previousHeight: Int = height,
        hasRetriedAfterRelease: Boolean = false,
    ) {
        val stillValidBeforeCleanup = synchronized(surfaceLock) {
            isCurrentSessionGenerationLocked(sessionId, generation)
        }
        if (!stillValidBeforeCleanup) return

        // Unbind CameraX before releasing the old encoder so CameraX never renders to a closed Surface.
        val retiringSlot = cleanupActivePipelineOnEncoderThread()

        val stillValidAfterCleanup = synchronized(surfaceLock) {
            isCurrentSessionGenerationLocked(sessionId, generation)
        }
        if (!stillValidAfterCleanup) return

        // Wait bounded for the prior codec release off Main so single-encoder devices can allocate
        // the replacement encoder across lens switches without deadlocking encoderExecutor.
        val priorSlotAlreadyReleased = retiringSlot?.awaitReleased(ENCODER_RELEASE_WAIT_TIMEOUT_MS) ?: true

        val stillValidAfterWait = synchronized(surfaceLock) {
            isCurrentSessionGenerationLocked(sessionId, generation)
        }
        if (!stillValidAfterWait) return

        var newEncoder: H264SurfaceEncoder? = null
        val slotHolder = AtomicReference<ActiveEncoderSlot?>(null)
        try {
            newEncoder = H264SurfaceEncoder(
                width = width,
                height = height,
                fps = fps,
                bitrateKbps = bitrateKbps,
                onVideoPacketSlice = { packetType, flags, ptsUs, payload, offset, length ->
                    val capturedSlot = slotHolder.get() ?: return@H264SurfaceEncoder
                    if (isReleased.get() ||
                        activeSessionId != sessionId ||
                        capturedSlot.isRetiring ||
                        pipelineGeneration.get() != capturedSlot.generation
                    ) {
                        return@H264SurfaceEncoder
                    }
                    onVideoPacketSlice(
                        sessionId,
                        capturedSlot.cameraConfigId,
                        capturedSlot.generation,
                        packetType,
                        flags,
                        ptsUs,
                        payload,
                        offset,
                        length,
                    )
                    if (packetType == PacketType.VIDEO_FRAME) {
                        maybePublishResolutionAdjustedOnFirstFrame(capturedSlot)
                    }
                },
            )
            val newSlot = ActiveEncoderSlot(
                sessionId = sessionId,
                generation = generation,
                encoder = newEncoder,
                width = width,
                height = height,
                fps = fps,
                cameraId = cameraId,
                surfaceTracker = EncoderSurfaceRetirementTracker(),
                cameraConfigId = cameraConfigId,
                geometryRequestSeq = geometryRequestSeq,
                pendingResolutionAdjustment = markResolutionAdjusted,
                fallbackWidth = previousWidth,
                fallbackHeight = previousHeight,
                resolutionAdjusted = markResolutionAdjusted,
            )
            slotHolder.set(newSlot)

            val canStart = synchronized(surfaceLock) {
                if (isCurrentSessionGenerationLocked(sessionId, generation)) {
                    startingSlot = newSlot
                    true
                } else {
                    false
                }
            }
            if (!canStart) {
                newSlot.isRetiring = true
                newEncoder.release()
                return
            }

            val published = try {
                startEncoderHandlingDrainThreadOom {
                    newEncoder.start()
                }
                synchronized(surfaceLock) {
                    if (startingSlot === newSlot) {
                        startingSlot = null
                    }
                    if (isCurrentSessionGenerationLocked(sessionId, generation)) {
                        activeSlot = newSlot
                        if (shouldApplyNegotiatedDimensionsToRequest(geometryRequestSeq, requestedGeometrySeq)) {
                            pipelineHealthyOrStarting = true
                        }
                        true
                    } else {
                        newSlot.isRetiring = true
                        false
                    }
                }
            } finally {
                synchronized(surfaceLock) {
                    if (startingSlot === newSlot) {
                        startingSlot = null
                    }
                }
            }
            if (!published) {
                newEncoder.release()
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate H264SurfaceEncoder: ${e.message}", e)
            slotHolder.get()?.isRetiring = true
            runCatching { newEncoder?.release() }
            val failureReason = if (markResolutionAdjusted) {
                "Failed to start replacement encoder for ${width}x$height: ${e.message}"
            } else {
                "Failed to start encoder for ${width}x$height: ${e.message}"
            }
            val (shouldObserveRetirement, failNow) = synchronized(surfaceLock) {
                if (activeSlot?.encoder === newEncoder) {
                    activeSlot = null
                }
                val retry = isRetryableEncoderSetupFailure(e) &&
                    retiringSlot != null &&
                    shouldRetryEncoderSetupAfterFailure(
                        isReleased = isReleased.get(),
                        isStreaming = isStreaming,
                        activeSessionId = activeSessionId,
                        requestSessionId = sessionId,
                        currentGeneration = pipelineGeneration.get(),
                        expectedGeneration = generation,
                        hasRetriedAfterRelease = hasRetriedAfterRelease,
                        priorSlotAlreadyReleased = priorSlotAlreadyReleased,
                        priorSlotReleaseFailed = retiringSlot.isReleaseFailed,
                    )
                val terminalFailNow = shouldReportTerminalEncoderSetupFailure(
                    hasPendingRetry = retry,
                    isCurrentGeneration = isCurrentSessionGenerationLocked(sessionId, generation),
                )
                if (terminalFailNow &&
                    shouldApplyNegotiatedDimensionsToRequest(geometryRequestSeq, requestedGeometrySeq)
                ) {
                    pipelineHealthyOrStarting = false
                    resolutionAdjustmentInFlight = false
                }
                retry to terminalFailNow
            }
            if (shouldObserveRetirement && retiringSlot != null) {
                Log.w(TAG, "Waiting for retiring codec release before retry/failure (session=$sessionId, gen=$generation)")
                retiringSlot.onReleaseFinished { codecReleased ->
                    if (codecReleased) {
                        executeOnEncoderExecutor {
                            val stillCurrent = synchronized(surfaceLock) {
                                isCurrentSessionGenerationLocked(sessionId, generation)
                            }
                            if (stillCurrent) {
                                setupEncoderAndBindSync(
                                    sessionId = sessionId,
                                    generation = generation,
                                    cameraConfigId = cameraConfigId,
                                    geometryRequestSeq = geometryRequestSeq,
                                    cameraId = cameraId,
                                    width = width,
                                    height = height,
                                    fps = fps,
                                    bitrateKbps = bitrateKbps,
                                    markResolutionAdjusted = markResolutionAdjusted,
                                    previousWidth = previousWidth,
                                    previousHeight = previousHeight,
                                    hasRetriedAfterRelease = true,
                                )
                            }
                        }
                    } else {
                        val reportDeferredFailure = synchronized(surfaceLock) {
                            val stillCurrent = isCurrentSessionGenerationLocked(sessionId, generation)
                            if (stillCurrent &&
                                shouldApplyNegotiatedDimensionsToRequest(geometryRequestSeq, requestedGeometrySeq)
                            ) {
                                pipelineHealthyOrStarting = false
                                resolutionAdjustmentInFlight = false
                            }
                            stillCurrent
                        }
                        if (reportDeferredFailure) {
                            if (markResolutionAdjusted) {
                                executeOnEncoderExecutor {
                                    val stillCurrent = synchronized(surfaceLock) {
                                        isCurrentSessionGenerationLocked(sessionId, generation)
                                    }
                                    if (stillCurrent) {
                                        currentWidth = previousWidth
                                        currentHeight = previousHeight
                                    }
                                }
                            }
                            onResolutionAdjustmentFailed?.invoke(
                                sessionId,
                                cameraConfigId,
                                cameraId,
                                failureReason,
                            )
                        }
                    }
                }
            } else if (failNow) {
                if (markResolutionAdjusted) {
                    currentWidth = previousWidth
                    currentHeight = previousHeight
                }
                onResolutionAdjustmentFailed?.invoke(
                    sessionId,
                    cameraConfigId,
                    cameraId,
                    failureReason,
                )
            }
            return
        }

        runOnMain {
            val canBind = synchronized(surfaceLock) {
                isCurrentSessionGenerationLocked(sessionId, generation)
            }
            if (canBind) {
                lifecycleOwner.start()
                bindUseCases(expectedGeneration = generation)
            }
        }
    }

    private fun isNegotiatedDimensionsCommitEligibleLocked(slot: ActiveEncoderSlot): Boolean {
        val ownsLatestGeometry = shouldApplyNegotiatedDimensionsToRequest(
            slotGeometryRequestSeq = slot.geometryRequestSeq,
            latestGeometryRequestSeq = requestedGeometrySeq,
        )
        return ownsLatestGeometry &&
            shouldPublishResolutionOnVideoPacket(
                packetType = PacketType.VIDEO_FRAME,
                isReleased = isReleased.get(),
                isStreaming = isStreaming,
                activeSessionId = activeSessionId,
                snapshotSessionId = slot.sessionId,
                activeSlotMatchesSnapshot = activeSlot === slot,
                snapshotRetiring = slot.isRetiring,
                pendingResolutionAdjustment = slot.pendingResolutionAdjustment,
            ) &&
            slot.hasPendingResolutionAdjustmentUnreported()
    }

    private fun commitNegotiatedDimensions(slot: ActiveEncoderSlot): Boolean = synchronized(surfaceLock) {
        val eligible = isNegotiatedDimensionsCommitEligibleLocked(slot) &&
            slot.markResolutionAdjustmentReported()
        if (eligible) {
            slot.negotiatedDimensionsCommitted = true
            requestedWidth = slot.width
            requestedHeight = slot.height
            resolutionAdjustmentInFlight = false
            pipelineHealthyOrStarting = true
        }
        eligible
    }

    private fun maybePublishResolutionAdjustedOnFirstFrame(slot: ActiveEncoderSlot) {
        if (!slot.hasPendingResolutionAdjustmentUnreported()) return
        val resolutionCallback = onResolutionAdjusted
        if (resolutionCallback == null) {
            commitNegotiatedDimensions(slot)
            return
        }
        val shouldAttemptPublish = synchronized(surfaceLock) {
            isNegotiatedDimensionsCommitEligibleLocked(slot)
        }
        if (shouldAttemptPublish) {
            resolutionCallback.invoke(
                slot.sessionId,
                slot.cameraConfigId,
                slot.cameraId,
                slot.width,
                slot.height,
            ) {
                commitNegotiatedDimensions(slot)
            }
        }
    }

    private fun handleResolutionAdjustmentFailureIfPending(
        snapshot: BindSnapshot,
        reason: String,
    ) {
        reportSlotAdjustmentFailureIfPending(snapshot.slot, reason)
    }

    private fun reportSlotAdjustmentFailureIfPending(
        slot: ActiveEncoderSlot,
        reason: String,
    ) {
        val shouldNotifyFailed = synchronized(surfaceLock) {
            val ownsLatestGeometry = shouldApplyNegotiatedDimensionsToRequest(
                slotGeometryRequestSeq = slot.geometryRequestSeq,
                latestGeometryRequestSeq = requestedGeometrySeq,
            )
            val publishFailure = ownsLatestGeometry &&
                shouldPublishResolutionAdjustment(
                    isReleased = isReleased.get(),
                    isStreaming = isStreaming,
                    activeSessionId = activeSessionId,
                    snapshotSessionId = slot.sessionId,
                    activeSlotMatchesSnapshot = activeSlot === slot,
                    snapshotRetiring = slot.isRetiring,
                    pendingResolutionAdjustment = slot.pendingResolutionAdjustment,
                ) &&
                slot.markResolutionAdjustmentReported() &&
                slot.markTerminalFailureReported()
            if (publishFailure) {
                pipelineHealthyOrStarting = false
                resolutionAdjustmentInFlight = false
            }
            publishFailure
        }
        if (shouldNotifyFailed) {
            cleanupAndNotifySlotFailure(slot, reason)
        }
    }

    private fun handlePipelineBindFailure(
        snapshot: BindSnapshot,
        reason: String,
    ) {
        reportSlotPipelineFailure(snapshot.slot, reason)
    }

    private fun reportSlotPipelineFailure(
        slot: ActiveEncoderSlot,
        reason: String,
    ) {
        val shouldNotifyFailed = synchronized(surfaceLock) {
            val publishFailure = shouldReportCurrentSlotPipelineFailure(
                isReleased = isReleased.get(),
                isStreaming = isStreaming,
                activeSessionId = activeSessionId,
                snapshotSessionId = slot.sessionId,
                activeSlotMatchesSnapshot = activeSlot === slot,
                snapshotRetiring = slot.isRetiring,
                slotGeometryRequestSeq = slot.geometryRequestSeq,
                latestGeometryRequestSeq = requestedGeometrySeq,
            ) && slot.markTerminalFailureReported()
            if (publishFailure) {
                pipelineHealthyOrStarting = false
                resolutionAdjustmentInFlight = false
            }
            publishFailure
        }
        if (shouldNotifyFailed) {
            cleanupAndNotifySlotFailure(slot, reason)
        }
    }

    private fun cleanupAndNotifySlotFailure(
        slot: ActiveEncoderSlot,
        reason: String,
    ) {
        executeOnEncoderExecutor {
            val (shouldRestoreFallbackDims, shouldCleanupSlot) = synchronized(surfaceLock) {
                val slotStillAttached = activeSlot === slot
                val restoreDims = slotStillAttached &&
                    slot.pendingResolutionAdjustment &&
                    !slot.negotiatedDimensionsCommitted &&
                    !isReleased.get() &&
                    isStreaming &&
                    activeSessionId == slot.sessionId &&
                    slot.generation == pipelineGeneration.get() &&
                    shouldApplyNegotiatedDimensionsToRequest(slot.geometryRequestSeq, requestedGeometrySeq)
                restoreDims to slotStillAttached
            }
            if (shouldRestoreFallbackDims) {
                currentWidth = slot.fallbackWidth
                currentHeight = slot.fallbackHeight
            }
            if (shouldCleanupSlot) {
                cleanupActivePipelineOnEncoderThread(expectedSlot = slot)
            }
        }
        onResolutionAdjustmentFailed?.invoke(
            slot.sessionId,
            slot.cameraConfigId,
            slot.cameraId,
            reason,
        )
    }

    private fun createFpsTelemetryCallback(): CameraCaptureSession.CaptureCallback {
        val hasLoggedFirstFrame = AtomicBoolean(false)
        val frameCount = AtomicLong(0L)
        val lastRangePacked = AtomicLong(Long.MIN_VALUE)

        return object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val count = frameCount.incrementAndGet()
                val aeRange = result.get(CaptureResult.CONTROL_AE_TARGET_FPS_RANGE)
                val packed = if (aeRange != null) {
                    (aeRange.lower.toLong() shl 32) or (aeRange.upper.toLong() and 0xFFFF_FFFFL)
                } else {
                    Long.MIN_VALUE + 1L
                }
                val previousPacked = lastRangePacked.getAndSet(packed)
                val isFirst = hasLoggedFirstFrame.compareAndSet(false, true)
                val rangeChanged = aeRange != null && previousPacked != packed

                if (isFirst || rangeChanged || (count % 180L == 0L)) {
                    val frameDurationNs = result.get(CaptureResult.SENSOR_FRAME_DURATION)
                    Log.i(
                        TAG,
                        "CaptureResult telemetry: frame=$count aeFpsRange=$aeRange frameDurationNs=$frameDurationNs",
                    )
                }
            }
        }
    }

    @SuppressLint("RestrictedApi") // Needed to request TEMPLATE_RECORD on Preview for Qualcomm/OEM 60fps sessions
    @OptIn(ExperimentalCamera2Interop::class)
    private fun applyVideoFpsInterop(
        builder: Preview.Builder,
        plan: TargetFpsPlan?,
        streamUseCase: Long?,
        attachTelemetryCallback: Boolean,
    ) {
        val extender = Camera2Interop.Extender(builder)
        if (plan != null) {
            val androidRange = plan.range.toAndroidRange()
            if (plan.isStandardAeRange) {
                builder.setTargetFrameRate(androidRange)
            }
            try {
                extender.setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    androidRange,
                )
                if (plan.useVideoSessionMode) {
                    extender.setCaptureRequestTemplate(CameraDevice.TEMPLATE_RECORD)
                    extender.setCaptureRequestOption(
                        CaptureRequest.CONTROL_CAPTURE_INTENT,
                        CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD,
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed configuring Camera2 FPS options (${plan.range}): ${e.message}")
            }
        }

        if (streamUseCase != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            try {
                extender.setStreamUseCase(streamUseCase)
            } catch (e: Exception) {
                Log.w(TAG, "Failed setting stream use case $streamUseCase: ${e.message}")
            }
        }

        if (attachTelemetryCallback) {
            extender.setSessionCaptureCallback(createFpsTelemetryCallback())
        }
    }

    private fun isSnapshotStillActiveLocked(snapshot: BindSnapshot): Boolean =
        !isReleased.get() &&
            isStreaming &&
            activeSessionId == snapshot.sessionId &&
            activeSlot === snapshot.slot &&
            !snapshot.slot.isRetiring &&
            snapshot.generation == pipelineGeneration.get()

    private fun buildEncoderPreview(
        snapshot: BindSnapshot,
        fpsPlan: TargetFpsPlan?,
    ): Preview {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(snapshot.width, snapshot.height),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val encPreviewBuilder = Preview.Builder()
            .setResolutionSelector(resolutionSelector)

        val videoStreamUseCase = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            CameraFpsCapabilities.supportsStreamUseCase(
                context,
                snapshot.cameraId,
                CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong(),
            )
        ) {
            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD.toLong()
        } else {
            null
        }

        applyVideoFpsInterop(
            builder = encPreviewBuilder,
            plan = fpsPlan,
            streamUseCase = videoStreamUseCase,
            attachTelemetryCallback = true,
        )

        return encPreviewBuilder.build().apply {
            setSurfaceProvider(cameraExecutor) { request ->
                val selectedWidth = request.resolution.width
                val selectedHeight = request.resolution.height
                val (decision, reportUnacceptedNegotiation) = synchronized(surfaceLock) {
                    val evaluated = evaluateSurfaceRequestResolution(
                        isReleased = isReleased.get(),
                        isStreaming = isStreaming,
                        activeSessionId = activeSessionId,
                        snapshotSessionId = snapshot.sessionId,
                        activeSlotMatchesSnapshot = activeSlot === snapshot.slot,
                        snapshotRetiring = snapshot.slot.isRetiring,
                        surfaceValid = snapshot.surface.isValid,
                        snapshotWidth = snapshot.width,
                        snapshotHeight = snapshot.height,
                        requestWidth = selectedWidth,
                        requestHeight = selectedHeight,
                        resolutionAlreadyAdjusted = snapshot.slot.resolutionAdjusted,
                    )
                    when (evaluated) {
                        SurfaceResolutionDecision.PROVIDE_SURFACE -> {
                            if (snapshot.slot.surfaceTracker.onSurfaceProvided()) {
                                snapshot.slot.setCurrentSurfaceRequest(request)
                                SurfaceResolutionDecision.PROVIDE_SURFACE to false
                            } else {
                                SurfaceResolutionDecision.REJECT_STALE to false
                            }
                        }
                        SurfaceResolutionDecision.RECONFIGURE_ENCODER -> {
                            snapshot.slot.resolutionAdjusted = true
                            SurfaceResolutionDecision.RECONFIGURE_ENCODER to false
                        }
                        SurfaceResolutionDecision.REJECT_STALE -> {
                            val unaccepted = shouldReportResolutionNegotiationFailure(
                                isReleased = isReleased.get(),
                                isStreaming = isStreaming,
                                activeSessionId = activeSessionId,
                                snapshotSessionId = snapshot.sessionId,
                                activeSlotMatchesSnapshot = activeSlot === snapshot.slot,
                                snapshotRetiring = snapshot.slot.isRetiring,
                                surfaceValid = snapshot.surface.isValid,
                                pendingResolutionAdjustment = snapshot.slot.pendingResolutionAdjustment,
                                snapshotWidth = snapshot.width,
                                snapshotHeight = snapshot.height,
                                requestWidth = selectedWidth,
                                requestHeight = selectedHeight,
                            )
                            SurfaceResolutionDecision.REJECT_STALE to unaccepted
                        }
                    }
                }
                when (decision) {
                    SurfaceResolutionDecision.PROVIDE_SURFACE -> {
                        try {
                            request.provideSurface(snapshot.surface, cameraExecutor) { result ->
                                Log.d(TAG, "Encoder surface provider result: ${result.resultCode}")
                                val isCurrentRequest = snapshot.slot.clearCurrentSurfaceRequestIfMatches(request)
                                if (snapshot.slot.surfaceTracker.onSurfaceReturned()) {
                                    finalizeSlotRetirement(snapshot.slot)
                                }
                                if (isSurfaceResultRejection(result.resultCode, isCurrentRequest = isCurrentRequest)) {
                                    handleResolutionAdjustmentFailureIfPending(
                                        snapshot = snapshot,
                                        reason = "Encoder surface rejected by CameraX (resultCode=${result.resultCode}) for ${snapshot.width}x${snapshot.height}",
                                    )
                                }
                            }
                        } catch (e: Throwable) {
                            Log.w(TAG, "provideSurface failed unexpectedly: ${e.message}")
                            snapshot.slot.clearCurrentSurfaceRequestIfMatches(request)
                            if (snapshot.slot.surfaceTracker.onSurfaceReturned()) {
                                finalizeSlotRetirement(snapshot.slot)
                            }
                            handleResolutionAdjustmentFailureIfPending(
                                snapshot = snapshot,
                                reason = "Encoder surface acceptance failed for ${snapshot.width}x${snapshot.height}: ${e.message}",
                            )
                        }
                    }
                    SurfaceResolutionDecision.RECONFIGURE_ENCODER -> {
                        Log.w(
                            TAG,
                            "CameraX selected ${selectedWidth}x$selectedHeight instead of ${snapshot.width}x${snapshot.height}; reconfiguring encoder",
                        )
                        request.willNotProvideSurface()
                        executeOnEncoderExecutor {
                            reconfigureForSelectedResolution(
                                expectedSessionId = snapshot.sessionId,
                                expectedGeneration = snapshot.generation,
                                expectedCameraConfigId = snapshot.cameraConfigId,
                                expectedGeometryRequestSeq = snapshot.geometryRequestSeq,
                                expectedCameraId = snapshot.cameraId,
                                previousWidth = snapshot.width,
                                previousHeight = snapshot.height,
                                expectedFps = snapshot.fps,
                                selectedWidth = selectedWidth,
                                selectedHeight = selectedHeight,
                            )
                        }
                    }
                    SurfaceResolutionDecision.REJECT_STALE -> {
                        request.willNotProvideSurface()
                        if (reportUnacceptedNegotiation) {
                            reportSlotPipelineFailure(
                                slot = snapshot.slot,
                                reason = "CameraX selected unaccepted replacement resolution ${selectedWidth}x$selectedHeight for ${snapshot.width}x${snapshot.height}",
                            )
                        }
                    }
                }
            }
        }
    }

    private fun buildUiPreview(
        view: PreviewView,
        snapshot: BindSnapshot,
        fpsPlan: TargetFpsPlan?,
    ): Preview {
        val previewTargetSize = Size(minOf(snapshot.width, 1280), minOf(snapshot.height, 720))
        val uiResolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    previewTargetSize,
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val uiBuilder = Preview.Builder()
            .setResolutionSelector(uiResolutionSelector)

        val previewUseCase = if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            CameraFpsCapabilities.supportsStreamUseCase(
                context,
                snapshot.cameraId,
                CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong(),
            )
        ) {
            CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_PREVIEW.toLong()
        } else {
            null
        }

        applyVideoFpsInterop(
            builder = uiBuilder,
            plan = fpsPlan,
            streamUseCase = previewUseCase,
            attachTelemetryCallback = false,
        )

        return uiBuilder.build().apply {
            setSurfaceProvider(view.surfaceProvider)
        }
    }

    private fun detachAndUnbindOwnedUseCasesOnMain(provider: ProcessCameraProvider) {
        val (oldEnc, oldUi) = synchronized(surfaceLock) {
            val prevEnc = encoderPreview
            val prevUi = uiPreview
            activeSlot?.clearCurrentSurfaceRequest()
            encoderPreview = null
            uiPreview = null
            activeCamera = null
            prevEnc to prevUi
        }
        unbindOwnedUseCasesOnMain(provider, oldEnc, oldUi)
    }

    private fun bindEncoderOnly(
        provider: ProcessCameraProvider,
        snapshot: BindSnapshot,
        cameraSelector: CameraSelector,
        fpsPlan: TargetFpsPlan?,
    ) {
        detachAndUnbindOwnedUseCasesOnMain(provider)
        val stillActive = synchronized(surfaceLock) { isSnapshotStillActiveLocked(snapshot) }
        if (!stillActive) return

        val encPreview = buildEncoderPreview(snapshot, fpsPlan)
        try {
            val boundCamera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                encPreview,
            )
            val keepBound = synchronized(surfaceLock) {
                if (isSnapshotStillActiveLocked(snapshot)) {
                    encoderPreview = encPreview
                    uiPreview = null
                    activeCamera = boundCamera
                    true
                } else {
                    false
                }
            }
            if (!keepBound) {
                unbindOwnedUseCasesOnMain(provider, encPreview)
            }
        } catch (e: Exception) {
            unbindOwnedUseCasesOnMain(provider, encPreview)
            throw e
        }
    }

    private fun bindWithFallback(
        provider: ProcessCameraProvider,
        snapshot: BindSnapshot,
        cameraSelector: CameraSelector,
        fpsPlan: TargetFpsPlan?,
    ) {
        detachAndUnbindOwnedUseCasesOnMain(provider)
        val stillActive = synchronized(surfaceLock) { isSnapshotStillActiveLocked(snapshot) }
        if (!stillActive) return

        val encPreview = buildEncoderPreview(snapshot, fpsPlan)
        val view = attachedPreviewViewRef?.get()
        if (view != null) {
            val uiPrev = buildUiPreview(view, snapshot, fpsPlan)
            try {
                val boundCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    encPreview,
                    uiPrev,
                )
                val keepBound = synchronized(surfaceLock) {
                    if (isSnapshotStillActiveLocked(snapshot)) {
                        encoderPreview = encPreview
                        uiPreview = uiPrev
                        activeCamera = boundCamera
                        true
                    } else {
                        false
                    }
                }
                if (!keepBound) {
                    unbindOwnedUseCasesOnMain(provider, encPreview, uiPrev)
                }
                return
            } catch (dualError: Exception) {
                Log.w(
                    TAG,
                    "Dual preview binding failed (${dualError.message}); retrying encoder-only at target FPS",
                )
                snapshot.slot.clearCurrentSurfaceRequest()
                unbindOwnedUseCasesOnMain(provider, encPreview, uiPrev)
                try {
                    bindEncoderOnly(provider, snapshot, cameraSelector, fpsPlan)
                    return
                } catch (singleError: Exception) {
                    if (fpsPlan == null) throw singleError
                    Log.w(
                        TAG,
                        "Encoder-only target FPS binding failed (${singleError.message}); falling back to default 30 FPS",
                    )
                }
            }
        } else {
            try {
                val boundCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    encPreview,
                )
                val keepBound = synchronized(surfaceLock) {
                    if (isSnapshotStillActiveLocked(snapshot)) {
                        encoderPreview = encPreview
                        uiPreview = null
                        activeCamera = boundCamera
                        true
                    } else {
                        false
                    }
                }
                if (!keepBound) {
                    unbindOwnedUseCasesOnMain(provider, encPreview)
                }
                return
            } catch (primaryError: Exception) {
                if (fpsPlan != null) {
                    snapshot.slot.clearCurrentSurfaceRequest()
                }
                unbindOwnedUseCasesOnMain(provider, encPreview)
                if (fpsPlan == null) throw primaryError
                Log.w(
                    TAG,
                    "Primary target FPS binding failed (${primaryError.message}); falling back to default 30 FPS",
                )
            }
        }

        bindEncoderOnly(provider, snapshot, cameraSelector, fpsPlan = null)
    }

    private fun bindUseCases(expectedGeneration: Long? = null) {
        if (!isStreaming || isReleased.get()) return
        if (expectedGeneration != null && pipelineGeneration.get() != expectedGeneration) return

        val provider = cameraProvider
        if (provider == null) {
            Log.w(TAG, "ProcessCameraProvider not ready yet, retrying...")
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val resolved = future.get()
                    if (isReleased.get()) {
                        return@addListener
                    }
                    cameraProvider = resolved
                    if (isStreaming && !isReleased.get()) {
                        bindUseCases(expectedGeneration = expectedGeneration)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed retrying ProcessCameraProvider: ${e.message}", e)
                }
            }, mainExecutor)
            return
        }

        var invalidSurfaceSlot: ActiveEncoderSlot? = null
        val snapshot = synchronized(surfaceLock) {
            val slot = activeSlot ?: return@synchronized null
            if (isReleased.get() ||
                !isStreaming ||
                slot.isRetiring ||
                slot.sessionId != activeSessionId ||
                (expectedGeneration != null && slot.generation != expectedGeneration)
            ) {
                return@synchronized null
            }
            val surface = slot.encoder?.inputSurface?.takeIf { it.isValid }
            if (surface == null) {
                invalidSurfaceSlot = slot
                return@synchronized null
            }
            BindSnapshot(
                sessionId = slot.sessionId,
                generation = slot.generation,
                slot = slot,
                surface = surface,
                cameraId = slot.cameraId,
                width = slot.width,
                height = slot.height,
                fps = slot.fps,
                cameraConfigId = slot.cameraConfigId,
                geometryRequestSeq = slot.geometryRequestSeq,
                fallbackWidth = slot.fallbackWidth,
                fallbackHeight = slot.fallbackHeight,
            )
        } ?: run {
            Log.w(TAG, "Encoder snapshot not valid for binding")
            invalidSurfaceSlot?.let { slot ->
                val failureReason = if (slot.pendingResolutionAdjustment) {
                    "Replacement encoder surface unavailable for ${slot.width}x${slot.height}"
                } else {
                    "Encoder surface unavailable for ${slot.width}x${slot.height}"
                }
                reportSlotPipelineFailure(
                    slot = slot,
                    reason = failureReason,
                )
            }
            return
        }

        val fpsPlan = CameraFpsCapabilities.resolveForCamera(
            context = context,
            cameraId = snapshot.cameraId,
            width = snapshot.width,
            height = snapshot.height,
            requestedFps = snapshot.fps,
        )
        val canActivateLifecycle = synchronized(surfaceLock) {
            if (!isSnapshotStillActiveLocked(snapshot)) {
                false
            } else {
                currentFpsPlan = fpsPlan
                shouldActivateLifecycleOnBind(
                    isReleased = isReleased.get(),
                    isStreaming = isStreaming,
                    activeSessionId = activeSessionId,
                    snapshotSessionId = snapshot.sessionId,
                    currentGeneration = pipelineGeneration.get(),
                    snapshotGeneration = snapshot.generation,
                )
            }
        }
        if (!canActivateLifecycle) {
            return
        }
        lifecycleOwner.start()
        Log.i(
            TAG,
            "Resolved FPS plan for cam=${snapshot.cameraId} ${snapshot.width}x${snapshot.height}@${snapshot.fps}fps: " +
                if (fpsPlan != null) {
                    "range=[${fpsPlan.range.minFps}, ${fpsPlan.range.maxFps}], " +
                        "standardAe=${fpsPlan.isStandardAeRange}, " +
                        "videoSession=${fpsPlan.useVideoSessionMode}, " +
                        "source=${fpsPlan.source}"
                } else {
                    "none (HAL default)"
                },
        )

        try {
            val cameraSelector = buildCameraSelector(snapshot.cameraId)
            bindWithFallback(provider, snapshot, cameraSelector, fpsPlan)
            applyActiveControls()
            Log.i(
                TAG,
                "Camera bound successfully to camera ID ${snapshot.cameraId} (${snapshot.width} x ${snapshot.height} @ ${snapshot.fps} fps)",
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind CameraX use cases: ${e.message}", e)
            val failurePrefix = if (snapshot.slot.pendingResolutionAdjustment) {
                "Failed to bind replacement pipeline for ${snapshot.width}x${snapshot.height}"
            } else {
                "Failed to bind camera pipeline for ${snapshot.width}x${snapshot.height}"
            }
            handlePipelineBindFailure(
                snapshot = snapshot,
                reason = "$failurePrefix: ${e.message}",
            )
        }
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun buildCameraSelector(targetId: String): CameraSelector {
        return CameraSelector.Builder()
            .addCameraFilter { cameraInfos ->
                val matched = cameraInfos.filter { info ->
                    try {
                        Camera2CameraInfo.from(info).cameraId == targetId
                    } catch (_: Exception) {
                        false
                    }
                }
                if (matched.isNotEmpty()) matched else cameraInfos
            }
            .build()
    }

    private fun applyActiveControls() {
        applyZoomRatioToActiveCamera(targetZoomRatio)
        applyExposureEvToActiveCamera(targetExposureEv)
        applyTorchToActiveCamera(targetTorch)
        applyAutoFocusToActiveCamera(targetAutoFocus)
    }

    private fun applyZoomRatioToActiveCamera(ratio: Float) {
        activeCamera?.cameraControl?.setZoomRatio(ratio)
    }

    private fun applyExposureEvToActiveCamera(ev: Float) {
        val camera = activeCamera ?: return
        val state = camera.cameraInfo.exposureState
        val step = state.exposureCompensationStep.toFloat()
        if (step > 0f) {
            val index = (ev / step).roundToInt()
            val clamped = index.coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
            camera.cameraControl.setExposureCompensationIndex(clamped)
        }
    }

    private fun applyTorchToActiveCamera(torchOn: Boolean) {
        activeCamera?.cameraControl?.enableTorch(torchOn)
    }

    private fun applyAutoFocusToActiveCamera(autofocus: Boolean) {
        val control = activeCamera?.cameraControl ?: return
        if (autofocus) {
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val point = factory.createPoint(0.5f, 0.5f)
            val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF).build()
            control.startFocusAndMetering(action)
        } else {
            control.cancelFocusAndMetering()
        }
    }

    fun setZoomRatio(ratio: Float) {
        if (isReleased.get()) return
        val token = synchronized(surfaceLock) {
            if (isReleased.get()) return
            val next = ++nextControlToken
            latestZoomToken = next
            next
        }
        executeOnEncoderExecutor {
            val apply = synchronized(surfaceLock) {
                !isReleased.get() && shouldApplyControlToken(token, latestZoomToken)
            }
            if (!apply) return@executeOnEncoderExecutor
            targetZoomRatio = ratio
            runOnMain {
                if (!isReleased.get()) applyZoomRatioToActiveCamera(targetZoomRatio)
            }
        }
    }

    fun setExposureEv(ev: Float) {
        if (isReleased.get()) return
        val token = synchronized(surfaceLock) {
            if (isReleased.get()) return
            val next = ++nextControlToken
            latestExposureToken = next
            next
        }
        executeOnEncoderExecutor {
            val apply = synchronized(surfaceLock) {
                !isReleased.get() && shouldApplyControlToken(token, latestExposureToken)
            }
            if (!apply) return@executeOnEncoderExecutor
            targetExposureEv = ev
            runOnMain {
                if (!isReleased.get()) applyExposureEvToActiveCamera(targetExposureEv)
            }
        }
    }

    fun setTorch(torchOn: Boolean) {
        if (isReleased.get()) return
        val token = synchronized(surfaceLock) {
            if (isReleased.get()) return
            val next = ++nextControlToken
            latestTorchToken = next
            next
        }
        executeOnEncoderExecutor {
            val apply = synchronized(surfaceLock) {
                !isReleased.get() && shouldApplyControlToken(token, latestTorchToken)
            }
            if (!apply) return@executeOnEncoderExecutor
            targetTorch = torchOn
            runOnMain {
                if (!isReleased.get()) applyTorchToActiveCamera(targetTorch)
            }
        }
    }

    fun setAutoFocus(autofocus: Boolean) {
        if (isReleased.get()) return
        val token = synchronized(surfaceLock) {
            if (isReleased.get()) return
            val next = ++nextControlToken
            latestAutoFocusToken = next
            next
        }
        executeOnEncoderExecutor {
            val apply = synchronized(surfaceLock) {
                !isReleased.get() && shouldApplyControlToken(token, latestAutoFocusToken)
            }
            if (!apply) return@executeOnEncoderExecutor
            targetAutoFocus = autofocus
            runOnMain {
                val applyOnMain = synchronized(surfaceLock) {
                    shouldApplyAutoFocusOnMain(
                        isReleased = isReleased.get(),
                        token = token,
                        latestAutoFocusToken = latestAutoFocusToken,
                    )
                }
                if (applyOnMain) {
                    applyAutoFocusToActiveCamera(autofocus)
                }
            }
        }
    }

    /**
     * Triggers tap-to-focus and auto-exposure metering at the provided [MeteringPoint].
     */
    fun focusAtPoint(point: MeteringPoint) {
        if (isReleased.get()) return
        val control = activeCamera?.cameraControl ?: return
        try {
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
            )
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            control.startFocusAndMetering(action)
            val token = synchronized(surfaceLock) {
                if (isReleased.get()) return
                val next = ++nextControlToken
                latestAutoFocusToken = next
                next
            }
            executeOnEncoderExecutor {
                val apply = synchronized(surfaceLock) {
                    !isReleased.get() && shouldApplyControlToken(token, latestAutoFocusToken)
                }
                if (apply) {
                    targetAutoFocus = true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to focus at point: ${e.message}")
        }
    }

    /**
     * Requests an IDR sync keyframe only when [sessionId] owns the active non-retiring encoder slot.
     */
    fun requestKeyFrame(sessionId: Long = activeSessionId) {
        if (isReleased.get()) return
        val activeEncoder = synchronized(surfaceLock) {
            if (isSlotValidForSession(activeSessionId, sessionId, activeSlot)) {
                activeSlot?.encoder
            } else {
                null
            }
        } ?: return
        activeEncoder.requestKeyFrame()
    }

    fun release() {
        if (!isReleased.compareAndSet(false, true)) return
        synchronized(surfaceLock) {
            isStreaming = false
            activeSessionId = 0L
            lastAppliedConfigSeq = -1L
            requestedGeometrySeq++
            pipelineHealthyOrStarting = false
            resolutionAdjustmentInFlight = false
            activeSlot?.isRetiring = true
            startingSlot?.isRetiring = true
            lastDetachedSlot = null
            pipelineGeneration.incrementAndGet()
        }

        runOnMain {
            attachedPreviewViewRef = null
        }

        executeOnEncoderExecutor {
            cleanupActivePipelineOnEncoderThread()
            runOnMain {
                lifecycleOwner.destroy()
                maybeShutdownCameraExecutorIfReleased()
                Log.i(TAG, "CameraStreamManager released")
            }
        }
        encoderExecutor.shutdown()
    }

    internal data class RebindDecision(
        val needsEncoderRestart: Boolean,
        val needsBitrateUpdate: Boolean,
    )

    internal enum class PreviewSyncAction {
        NO_OP,
        REBIND_UI_ONLY,
        FULL_REBIND,
        UNBIND_UI_ONLY,
    }

    internal enum class SurfaceResolutionDecision {
        PROVIDE_SURFACE,
        RECONFIGURE_ENCODER,
        REJECT_STALE,
    }

    internal enum class MainWaitResult {
        COMPLETED,
        TIMED_OUT,
        INTERRUPTED,
        POST_REJECTED,
    }

    internal data class MainWaitOutcome(
        val result: MainWaitResult,
        val wasRunningOnMain: Boolean,
    )

    companion object {
        private const val TAG = "CameraStreamManager"
        private const val DEFAULT_MAIN_UNBIND_TIMEOUT_MS = 1_500L
        private const val ENCODER_RELEASE_WAIT_TIMEOUT_MS = 1_500L
        internal const val DEFAULT_SESSION_ID = 1L

        private fun logWarnSafe(message: String) {
            try {
                Log.w(TAG, message)
            } catch (_: RuntimeException) {
                // Allow companion helper tests to run on JVM without mocked android.util.Log.
            }
        }

        /**
         * Posts [block] to the main thread and awaits completion up to [timeoutMs].
         * Cancels queued work on timeout or interruption and guards execution with an atomic
         * state machine so a late-firing [Runnable] cannot unbind a newer stream generation,
         * while invoking [onDeferredComplete] if [block] was already running when the wait aborted.
         */
        internal fun runOnMainBlocking(
            isMainThread: () -> Boolean,
            post: (Runnable) -> Boolean,
            removeCallbacks: (Runnable) -> Unit,
            timeoutMs: Long = DEFAULT_MAIN_UNBIND_TIMEOUT_MS,
            onWarn: (String) -> Unit = { message -> logWarnSafe(message) },
            onDeferredComplete: () -> Unit = {},
            block: () -> Unit,
        ): MainWaitResult = runOnMainBlockingWithOutcome(
            isMainThread = isMainThread,
            post = post,
            removeCallbacks = removeCallbacks,
            timeoutMs = timeoutMs,
            onWarn = onWarn,
            onDeferredComplete = onDeferredComplete,
            block = block,
        ).result

        internal fun runOnMainBlockingWithOutcome(
            isMainThread: () -> Boolean,
            post: (Runnable) -> Boolean,
            removeCallbacks: (Runnable) -> Unit,
            timeoutMs: Long = DEFAULT_MAIN_UNBIND_TIMEOUT_MS,
            onWarn: (String) -> Unit = { message -> logWarnSafe(message) },
            onDeferredComplete: () -> Unit = {},
            block: () -> Unit,
        ): MainWaitOutcome {
            if (isMainThread()) {
                block()
                return MainWaitOutcome(MainWaitResult.COMPLETED, wasRunningOnMain = false)
            }

            // 0 = PENDING, 1 = RUNNING, 2 = COMPLETED_ON_TIME, 3 = CANCELED_BEFORE_START, 4 = WAITER_LEFT_WHILE_RUNNING
            val latch = CountDownLatch(1)
            val state = AtomicInteger(0)
            val runnable = Runnable {
                if (!state.compareAndSet(0, 1)) {
                    latch.countDown()
                    return@Runnable
                }
                var completedDeferred = false
                try {
                    block()
                } finally {
                    if (!state.compareAndSet(1, 2)) {
                        completedDeferred = (state.get() == 4)
                    }
                    latch.countDown()
                    if (completedDeferred) {
                        onDeferredComplete()
                    }
                }
            }

            if (!post(runnable)) {
                state.compareAndSet(0, 3)
                onWarn("Failed posting main-thread camera unbind task; skipping blocking wait")
                return MainWaitOutcome(MainWaitResult.POST_REJECTED, wasRunningOnMain = false)
            }

            val completedInTime = try {
                latch.await(timeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return resolveAbortedWaitOutcome(
                    state = state,
                    abortedResult = MainWaitResult.INTERRUPTED,
                    onPendingCanceled = {
                        removeCallbacks(runnable)
                        onWarn("Interrupted waiting for Main camera unbind")
                    },
                    onRunningDeferred = {
                        onWarn("Interrupted waiting for Main camera unbind while executing")
                    },
                )
            }

            if (completedInTime) {
                return MainWaitOutcome(MainWaitResult.COMPLETED, wasRunningOnMain = false)
            }

            return resolveAbortedWaitOutcome(
                state = state,
                abortedResult = MainWaitResult.TIMED_OUT,
                onPendingCanceled = {
                    removeCallbacks(runnable)
                    onWarn("Timed out waiting ${timeoutMs}ms for Main camera unbind")
                },
                onRunningDeferred = {
                    onWarn("Timed out waiting ${timeoutMs}ms for Main camera unbind while executing")
                },
            )
        }

        private inline fun resolveAbortedWaitOutcome(
            state: AtomicInteger,
            abortedResult: MainWaitResult,
            onPendingCanceled: () -> Unit,
            onRunningDeferred: () -> Unit,
        ): MainWaitOutcome {
            while (true) {
                when (state.get()) {
                    0 -> if (state.compareAndSet(0, 3)) {
                        onPendingCanceled()
                        return MainWaitOutcome(abortedResult, wasRunningOnMain = false)
                    }
                    1 -> if (state.compareAndSet(1, 4)) {
                        onRunningDeferred()
                        return MainWaitOutcome(abortedResult, wasRunningOnMain = true)
                    }
                    2 -> return MainWaitOutcome(MainWaitResult.COMPLETED, wasRunningOnMain = false)
                    else -> return MainWaitOutcome(abortedResult, wasRunningOnMain = false)
                }
            }
        }

        internal fun retirePipelineOnUnbindOutcome(
            runBlockingWait: (onDeferredComplete: () -> Unit, block: () -> Unit) -> MainWaitOutcome,
            unbindOnMain: () -> Unit,
            scheduleMainRetry: (Runnable) -> Boolean,
            retire: () -> Unit,
        ) {
            val retired = AtomicBoolean(false)
            val outcome = runBlockingWait(
                {
                    if (retired.compareAndSet(false, true)) {
                        retire()
                    }
                },
                unbindOnMain,
            )

            when (outcome.result) {
                MainWaitResult.COMPLETED -> {
                    if (retired.compareAndSet(false, true)) {
                        retire()
                    }
                }
                MainWaitResult.TIMED_OUT,
                MainWaitResult.INTERRUPTED,
                MainWaitResult.POST_REJECTED -> {
                    if (outcome.wasRunningOnMain) {
                        return
                    }
                    val retryRunnable = Runnable {
                        try {
                            unbindOnMain()
                        } finally {
                            if (retired.compareAndSet(false, true)) {
                                retire()
                            }
                        }
                    }
                    if (!scheduleMainRetry(retryRunnable)) {
                        logWarnSafe("Failed scheduling async main unbind retry; retiring encoder slot")
                        if (retired.compareAndSet(false, true)) {
                            retire()
                        }
                    }
                }
            }
        }

        internal fun <T> distinctOwnedUseCases(vararg candidates: T?): List<T> =
            candidates.filterNotNull().distinct()

        internal fun isSessionSlotValid(
            activeSessionId: Long,
            requestSessionId: Long,
            slotSessionId: Long?,
            slotRetiring: Boolean,
        ): Boolean = requestSessionId != 0L &&
            activeSessionId == requestSessionId &&
            slotSessionId == requestSessionId &&
            !slotRetiring

        internal fun isSlotValidForSession(
            activeSessionId: Long,
            requestSessionId: Long,
            slot: ActiveEncoderSlot?,
        ): Boolean = isSessionSlotValid(
            activeSessionId = activeSessionId,
            requestSessionId = requestSessionId,
            slotSessionId = slot?.sessionId,
            slotRetiring = slot?.isRetiring ?: true,
        )

        internal fun shouldApplyRebind(
            isReleased: Boolean,
            isStreaming: Boolean,
            activeSessionId: Long,
            requestSessionId: Long,
            configSeq: Long,
            lastAppliedConfigSeq: Long,
        ): Boolean = !isReleased &&
            isStreaming &&
            requestSessionId != 0L &&
            activeSessionId == requestSessionId &&
            configSeq >= lastAppliedConfigSeq

        internal fun shouldActivateLifecycleOnBind(
            isReleased: Boolean,
            isStreaming: Boolean,
            activeSessionId: Long,
            snapshotSessionId: Long,
            currentGeneration: Long,
            snapshotGeneration: Long,
        ): Boolean = !isReleased &&
            isStreaming &&
            snapshotSessionId != 0L &&
            activeSessionId == snapshotSessionId &&
            currentGeneration == snapshotGeneration

        internal fun shouldRetryEncoderSetupAfterFailure(
            isReleased: Boolean,
            isStreaming: Boolean,
            activeSessionId: Long,
            requestSessionId: Long,
            currentGeneration: Long,
            expectedGeneration: Long,
            hasRetriedAfterRelease: Boolean,
            priorSlotAlreadyReleased: Boolean,
            priorSlotReleaseFailed: Boolean = false,
        ): Boolean = !hasRetriedAfterRelease &&
            !priorSlotAlreadyReleased &&
            !priorSlotReleaseFailed &&
            shouldActivateLifecycleOnBind(
                isReleased = isReleased,
                isStreaming = isStreaming,
                activeSessionId = activeSessionId,
                snapshotSessionId = requestSessionId,
                currentGeneration = currentGeneration,
                snapshotGeneration = expectedGeneration,
            )

        internal fun evaluateSurfaceRequestResolution(
            isReleased: Boolean,
            isStreaming: Boolean,
            activeSessionId: Long,
            snapshotSessionId: Long,
            activeSlotMatchesSnapshot: Boolean,
            snapshotRetiring: Boolean,
            surfaceValid: Boolean,
            snapshotWidth: Int,
            snapshotHeight: Int,
            requestWidth: Int,
            requestHeight: Int,
            resolutionAlreadyAdjusted: Boolean,
        ): SurfaceResolutionDecision {
            if (isReleased ||
                !isStreaming ||
                snapshotSessionId == 0L ||
                activeSessionId != snapshotSessionId ||
                !activeSlotMatchesSnapshot ||
                snapshotRetiring ||
                !surfaceValid
            ) {
                return SurfaceResolutionDecision.REJECT_STALE
            }
            if (requestWidth == snapshotWidth && requestHeight == snapshotHeight) {
                return SurfaceResolutionDecision.PROVIDE_SURFACE
            }
            val validEvenResolution = requestWidth > 0 &&
                requestHeight > 0 &&
                requestWidth % 2 == 0 &&
                requestHeight % 2 == 0
            return if (!resolutionAlreadyAdjusted && validEvenResolution) {
                SurfaceResolutionDecision.RECONFIGURE_ENCODER
            } else {
                SurfaceResolutionDecision.REJECT_STALE
            }
        }

        internal fun isSurfaceProvideEligible(
            isReleased: Boolean,
            isStreaming: Boolean,
            activeSessionId: Long,
            snapshotSessionId: Long,
            activeSlotMatchesSnapshot: Boolean,
            snapshotRetiring: Boolean,
            surfaceValid: Boolean,
            snapshotWidth: Int,
            snapshotHeight: Int,
            requestWidth: Int,
            requestHeight: Int,
        ): Boolean = evaluateSurfaceRequestResolution(
            isReleased = isReleased,
            isStreaming = isStreaming,
            activeSessionId = activeSessionId,
            snapshotSessionId = snapshotSessionId,
            activeSlotMatchesSnapshot = activeSlotMatchesSnapshot,
            snapshotRetiring = snapshotRetiring,
            surfaceValid = surfaceValid,
            snapshotWidth = snapshotWidth,
            snapshotHeight = snapshotHeight,
            requestWidth = requestWidth,
            requestHeight = requestHeight,
            resolutionAlreadyAdjusted = true,
        ) == SurfaceResolutionDecision.PROVIDE_SURFACE

        internal fun canProvideEncoderSurface(
            isReleased: Boolean,
            isStreaming: Boolean,
            activeSessionId: Long,
            activeSlot: ActiveEncoderSlot?,
            snapshot: BindSnapshot,
            requestWidth: Int,
            requestHeight: Int,
        ): Boolean = isSurfaceProvideEligible(
            isReleased = isReleased,
            isStreaming = isStreaming,
            activeSessionId = activeSessionId,
            snapshotSessionId = snapshot.sessionId,
            activeSlotMatchesSnapshot = activeSlot === snapshot.slot,
            snapshotRetiring = snapshot.slot.isRetiring,
            surfaceValid = snapshot.surface.isValid,
            snapshotWidth = snapshot.width,
            snapshotHeight = snapshot.height,
            requestWidth = requestWidth,
            requestHeight = requestHeight,
        )

        internal fun shouldPublishResolutionAdjustment(
            isReleased: Boolean,
            isStreaming: Boolean,
            activeSessionId: Long,
            snapshotSessionId: Long,
            activeSlotMatchesSnapshot: Boolean,
            snapshotRetiring: Boolean,
            pendingResolutionAdjustment: Boolean,
        ): Boolean = !isReleased &&
            isStreaming &&
            snapshotSessionId != 0L &&
            activeSessionId == snapshotSessionId &&
            activeSlotMatchesSnapshot &&
            !snapshotRetiring &&
            pendingResolutionAdjustment

        internal fun shouldReportResolutionNegotiationFailure(
            isReleased: Boolean,
            isStreaming: Boolean,
            activeSessionId: Long,
            snapshotSessionId: Long,
            activeSlotMatchesSnapshot: Boolean,
            snapshotRetiring: Boolean,
            surfaceValid: Boolean,
            pendingResolutionAdjustment: Boolean,
            snapshotWidth: Int,
            snapshotHeight: Int,
            requestWidth: Int,
            requestHeight: Int,
        ): Boolean = shouldPublishResolutionAdjustment(
            isReleased = isReleased,
            isStreaming = isStreaming,
            activeSessionId = activeSessionId,
            snapshotSessionId = snapshotSessionId,
            activeSlotMatchesSnapshot = activeSlotMatchesSnapshot,
            snapshotRetiring = snapshotRetiring,
            pendingResolutionAdjustment = pendingResolutionAdjustment,
        ) && surfaceValid && (requestWidth != snapshotWidth || requestHeight != snapshotHeight)

        internal fun shouldPublishResolutionOnVideoPacket(
            packetType: PacketType,
            isReleased: Boolean,
            isStreaming: Boolean,
            activeSessionId: Long,
            snapshotSessionId: Long,
            activeSlotMatchesSnapshot: Boolean,
            snapshotRetiring: Boolean,
            pendingResolutionAdjustment: Boolean,
        ): Boolean = packetType == PacketType.VIDEO_FRAME &&
            shouldPublishResolutionAdjustment(
                isReleased = isReleased,
                isStreaming = isStreaming,
                activeSessionId = activeSessionId,
                snapshotSessionId = snapshotSessionId,
                activeSlotMatchesSnapshot = activeSlotMatchesSnapshot,
                snapshotRetiring = snapshotRetiring,
                pendingResolutionAdjustment = pendingResolutionAdjustment,
            )

        internal fun isSurfaceResultRejection(
            resultCode: Int,
            isCurrentRequest: Boolean = true,
        ): Boolean = when (resultCode) {
            SurfaceRequest.Result.RESULT_SURFACE_USED_SUCCESSFULLY -> false
            SurfaceRequest.Result.RESULT_REQUEST_CANCELLED -> isCurrentRequest
            else -> true
        }

        internal fun shouldReportTerminalEncoderSetupFailure(
            hasPendingRetry: Boolean,
            isCurrentGeneration: Boolean,
        ): Boolean = !hasPendingRetry && isCurrentGeneration

        internal inline fun startEncoderHandlingDrainThreadOom(
            startEncoder: () -> Unit,
        ) {
            try {
                startEncoder()
            } catch (oom: OutOfMemoryError) {
                throw EncoderDrainThreadStartupException(oom)
            }
        }

        internal fun isRetryableEncoderSetupFailure(failure: Throwable): Boolean =
            failure !is EncoderDrainThreadStartupException

        internal fun shouldReportCurrentSlotPipelineFailure(
            isReleased: Boolean,
            isStreaming: Boolean,
            activeSessionId: Long,
            snapshotSessionId: Long,
            activeSlotMatchesSnapshot: Boolean,
            snapshotRetiring: Boolean,
            slotGeometryRequestSeq: Long,
            latestGeometryRequestSeq: Long,
        ): Boolean = !isReleased &&
            isStreaming &&
            snapshotSessionId != 0L &&
            activeSessionId == snapshotSessionId &&
            activeSlotMatchesSnapshot &&
            !snapshotRetiring &&
            shouldApplyNegotiatedDimensionsToRequest(
                slotGeometryRequestSeq = slotGeometryRequestSeq,
                latestGeometryRequestSeq = latestGeometryRequestSeq,
            )

        internal fun shouldApplyNegotiatedDimensionsToRequest(
            slotGeometryRequestSeq: Long,
            latestGeometryRequestSeq: Long,
        ): Boolean = slotGeometryRequestSeq != 0L && slotGeometryRequestSeq == latestGeometryRequestSeq

        internal fun shouldApplyControlToken(
            token: Long,
            latestToken: Long,
        ): Boolean = token > 0L && token == latestToken

        internal fun shouldApplyAutoFocusOnMain(
            isReleased: Boolean,
            token: Long,
            latestAutoFocusToken: Long,
        ): Boolean = !isReleased && shouldApplyControlToken(token, latestAutoFocusToken)

        internal fun isVideoPacketEligibleForWrite(
            isReleased: Boolean,
            isStreaming: Boolean,
            activeSessionId: Long,
            packetSessionId: Long,
            currentGeneration: Long,
            packetGeneration: Long,
            currentSlotSessionId: Long?,
            currentSlotGeneration: Long?,
            currentSlotRetiring: Boolean,
        ): Boolean = !isReleased &&
            isStreaming &&
            packetSessionId != 0L &&
            activeSessionId == packetSessionId &&
            packetGeneration != 0L &&
            currentGeneration == packetGeneration &&
            currentSlotSessionId == packetSessionId &&
            currentSlotGeneration == packetGeneration &&
            !currentSlotRetiring

        internal fun shouldAdvanceCameraConfigForRebind(
            isReleased: Boolean,
            isStreaming: Boolean,
            activeSessionId: Long,
            requestSessionId: Long,
            pipelineHealthyOrStarting: Boolean,
            resolutionAdjustmentInFlight: Boolean,
            requestedCameraId: String,
            requestedWidth: Int,
            requestedHeight: Int,
            requestedFps: Int,
            newCameraId: String,
            newWidth: Int,
            newHeight: Int,
            newFps: Int,
        ): Boolean {
            if (isReleased || !isStreaming || requestSessionId == 0L || activeSessionId != requestSessionId) {
                return true
            }
            if (!pipelineHealthyOrStarting || resolutionAdjustmentInFlight) {
                return true
            }
            return requestedCameraId != newCameraId ||
                requestedWidth != newWidth ||
                requestedHeight != newHeight ||
                requestedFps != newFps
        }

        internal fun isSlotUsableForRebind(slot: ActiveEncoderSlot?): Boolean =
            slot != null && !slot.isRetiring && !slot.isTerminalFailureReported

        internal fun shouldFallbackFullRebindAfterUiFailure(encoderStillBound: Boolean): Boolean =
            !encoderStillBound

        internal fun needsEncoderRestart(
            cameraChanged: Boolean,
            geometryOrFpsChanged: Boolean,
            hasEncoder: Boolean,
        ): Boolean = cameraChanged || geometryOrFpsChanged || !hasEncoder

        internal fun evaluateRebindDecision(
            cameraChanged: Boolean,
            geometryOrFpsChanged: Boolean,
            bitrateChanged: Boolean,
            hasEncoder: Boolean,
        ): RebindDecision {
            val restart = needsEncoderRestart(
                cameraChanged = cameraChanged,
                geometryOrFpsChanged = geometryOrFpsChanged,
                hasEncoder = hasEncoder,
            )
            return RebindDecision(
                needsEncoderRestart = restart,
                needsBitrateUpdate = !restart && bitrateChanged,
            )
        }

        internal fun decideAttachPreviewAction(
            isStreaming: Boolean,
            isReleased: Boolean,
            sameViewAlreadyAttached: Boolean,
            uiPreviewBound: Boolean,
            encoderPreviewBound: Boolean,
        ): PreviewSyncAction = when {
            isReleased || !isStreaming -> PreviewSyncAction.NO_OP
            sameViewAlreadyAttached && uiPreviewBound -> PreviewSyncAction.NO_OP
            encoderPreviewBound -> PreviewSyncAction.REBIND_UI_ONLY
            else -> PreviewSyncAction.FULL_REBIND
        }

        internal fun decideDetachPreviewAction(
            isStreaming: Boolean,
            isReleased: Boolean,
            hasAttachedOrUiPreview: Boolean,
        ): PreviewSyncAction = when {
            !hasAttachedOrUiPreview -> PreviewSyncAction.NO_OP
            isReleased || !isStreaming -> PreviewSyncAction.NO_OP
            else -> PreviewSyncAction.UNBIND_UI_ONLY
        }
    }
}
