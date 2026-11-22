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
    val encoder: H264SurfaceEncoder,
    val width: Int,
    val height: Int,
    val fps: Int,
    val cameraId: String,
    val surfaceTracker: EncoderSurfaceRetirementTracker,
    @Volatile var isRetiring: Boolean = false,
)

internal data class BindSnapshot(
    val sessionId: Long,
    val generation: Long,
    val slot: ActiveEncoderSlot,
    val surface: android.view.Surface,
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
)

/**
 * Manages the CameraX lifecycle, GPU surface routing to [H264SurfaceEncoder],
 * real-time camera controls (Zoom, EV, Torch, AF, Tap-to-Focus), and stream reconfiguration.
 */
class CameraStreamManager(
    private val context: Context,
    private val onVideoPacketSlice: (
        sessionId: Long,
        packetType: PacketType,
        flags: Int,
        ptsUs: Long,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) -> Unit,
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
        onVideoPacketSlice = { _, packetType, flags, ptsUs, payload, offset, length ->
            onVideoPacketSlice(packetType, flags, ptsUs, payload, offset, length)
        },
    )

    constructor(
        context: Context,
        onVideoPacket: (packetType: PacketType, flags: Int, ptsUs: Long, payload: ByteArray) -> Unit,
    ) : this(
        context = context,
        onVideoPacketSlice = { _, packetType, flags, ptsUs, payload, offset, length ->
            val standalone = if (offset == 0 && length == payload.size) {
                payload
            } else {
                payload.copyOfRange(offset, offset + length)
            }
            onVideoPacket(packetType, flags, ptsUs, standalone)
        },
    )

    private val lifecycleOwner = ServiceLifecycleOwner()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CamCraft-Camera-Executor").apply { isDaemon = true }
    }
    @Volatile
    private var encoderExecutorThread: Thread? = null
    private val encoderExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "CamCraft-Encoder-Lifecycle").apply {
            isDaemon = true
            encoderExecutorThread = this
        }
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
    private val deferredRetireSlots = mutableSetOf<ActiveEncoderSlot>()

    @Volatile
    private var activeSessionId: Long = 0L
    @Volatile
    private var lastAppliedConfigSeq: Long = -1L

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
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
    ) {
        val generation = synchronized(surfaceLock) {
            if (isReleased.get()) return
            activeSessionId = sessionId
            lastAppliedConfigSeq = -1L
            isStreaming = true
            activeSlot?.isRetiring = true
            pipelineGeneration.incrementAndGet()
        }
        executeOnEncoderExecutor {
            val shouldStart = synchronized(surfaceLock) {
                !isReleased.get() &&
                    isStreaming &&
                    activeSessionId == sessionId &&
                    pipelineGeneration.get() == generation
            }
            if (!shouldStart) {
                return@executeOnEncoderExecutor
            }
            currentCameraId = cameraId
            currentWidth = width
            currentHeight = height
            currentFps = fps
            currentBitrateKbps = bitrateKbps

            runOnMain {
                if (!isReleased.get() && isStreaming && activeSessionId == sessionId) {
                    lifecycleOwner.start()
                }
            }
            setupEncoderAndBindSync(
                sessionId = sessionId,
                generation = generation,
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
        cameraId = cameraId,
        width = width,
        height = height,
        fps = fps,
        bitrateKbps = bitrateKbps,
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
            activeSlot?.isRetiring = true
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

    private fun cleanupActivePipelineOnEncoderThread() {
        val (slotToRetire, ownedUseCases) = synchronized(surfaceLock) {
            val detached = activeSlot
            activeSlot = null
            if (detached != null) {
                detached.isRetiring = true
                deferredRetireSlots.add(detached)
            }
            val owned = listOfNotNull(encoderPreview, uiPreview)
            encoderPreview = null
            uiPreview = null
            activeCamera = null
            currentFpsPlan = null
            detached to owned
        }

        if (ownedUseCases.isEmpty()) {
            if (slotToRetire != null) {
                retireSlot(slotToRetire)
            }
            return
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
        releaseEncoderOffMain(slot.encoder)
        maybeShutdownCameraExecutorIfReleased()
    }

    private fun releaseEncoderOffMain(encoder: H264SurfaceEncoder) {
        if (Thread.currentThread() === encoderExecutorThread) {
            encoder.release()
            return
        }
        try {
            encoderExecutor.execute {
                encoder.release()
            }
        } catch (_: RejectedExecutionException) {
            Thread({
                encoder.release()
            }, "CamCraft-Encoder-Release-Fallback").apply {
                isDaemon = true
                start()
            }
        } catch (_: Exception) {
            Thread({
                encoder.release()
            }, "CamCraft-Encoder-Release-Fallback").apply {
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
                    slot.encoder.inputSurface?.takeIf { it.isValid }?.let { surface ->
                        BindSnapshot(
                            sessionId = slot.sessionId,
                            generation = slot.generation,
                            slot = slot,
                            surface = surface,
                            cameraId = slot.cameraId,
                            width = slot.width,
                            height = slot.height,
                            fps = slot.fps,
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
     */
    fun rebindCamera(
        sessionId: Long,
        configSeq: Long,
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
    ) {
        if (isReleased.get()) return

        executeOnEncoderExecutor {
            val currentSlot = synchronized(surfaceLock) {
                if (!shouldApplyRebind(
                        isReleased = isReleased.get(),
                        isStreaming = isStreaming,
                        activeSessionId = activeSessionId,
                        requestSessionId = sessionId,
                        configSeq = configSeq,
                        lastAppliedConfigSeq = lastAppliedConfigSeq,
                    )
                ) {
                    return@executeOnEncoderExecutor
                }
                activeSlot
            }

            val cameraChanged = cameraId != currentCameraId
            val geometryOrFpsChanged = width != currentWidth ||
                height != currentHeight ||
                fps != currentFps
            val bitrateChanged = bitrateKbps != currentBitrateKbps

            val decision = evaluateRebindDecision(
                cameraChanged = cameraChanged,
                geometryOrFpsChanged = geometryOrFpsChanged,
                bitrateChanged = bitrateChanged,
                hasEncoder = currentSlot != null && !currentSlot.isRetiring,
            )

            currentCameraId = cameraId
            currentWidth = width
            currentHeight = height
            currentFps = fps
            currentBitrateKbps = bitrateKbps
            lastAppliedConfigSeq = configSeq

            if (decision.needsEncoderRestart) {
                val generation = pipelineGeneration.incrementAndGet()
                setupEncoderAndBindSync(
                    sessionId = sessionId,
                    generation = generation,
                    cameraId = cameraId,
                    width = width,
                    height = height,
                    fps = fps,
                    bitrateKbps = bitrateKbps,
                )
            } else if (decision.needsBitrateUpdate) {
                currentSlot?.encoder?.updateBitrate(bitrateKbps)
            }
        }
    }

    private fun setupEncoderAndBindSync(
        sessionId: Long,
        generation: Long,
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
    ) {
        val stillValidBeforeCleanup = synchronized(surfaceLock) {
            !isReleased.get() &&
                isStreaming &&
                activeSessionId == sessionId &&
                pipelineGeneration.get() == generation
        }
        if (!stillValidBeforeCleanup) return

        // Unbind CameraX before releasing the old encoder so CameraX never renders to a closed Surface.
        cleanupActivePipelineOnEncoderThread()

        val stillValidAfterCleanup = synchronized(surfaceLock) {
            !isReleased.get() &&
                isStreaming &&
                activeSessionId == sessionId &&
                pipelineGeneration.get() == generation
        }
        if (!stillValidAfterCleanup) return

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
                    if (isReleased.get() || activeSessionId != sessionId || capturedSlot.isRetiring) {
                        return@H264SurfaceEncoder
                    }
                    onVideoPacketSlice(sessionId, packetType, flags, ptsUs, payload, offset, length)
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
            )
            slotHolder.set(newSlot)

            val canStart = synchronized(surfaceLock) {
                !isReleased.get() &&
                    isStreaming &&
                    activeSessionId == sessionId &&
                    pipelineGeneration.get() == generation
            }
            if (!canStart) {
                newSlot.isRetiring = true
                newEncoder.release()
                return
            }

            newEncoder.start()
            val published = synchronized(surfaceLock) {
                if (!isReleased.get() &&
                    isStreaming &&
                    activeSessionId == sessionId &&
                    pipelineGeneration.get() == generation
                ) {
                    activeSlot = newSlot
                    true
                } else {
                    newSlot.isRetiring = true
                    false
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
            synchronized(surfaceLock) {
                if (activeSlot?.encoder === newEncoder) {
                    activeSlot = null
                }
            }
            return
        }

        runOnMain {
            if (isStreaming &&
                !isReleased.get() &&
                activeSessionId == sessionId &&
                pipelineGeneration.get() == generation
            ) {
                bindUseCases(expectedGeneration = generation)
            }
        }
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
                val canProvide = synchronized(surfaceLock) {
                    canProvideEncoderSurface(
                        isReleased = isReleased.get(),
                        isStreaming = isStreaming,
                        activeSessionId = activeSessionId,
                        activeSlot = activeSlot,
                        snapshot = snapshot,
                        requestWidth = request.resolution.width,
                        requestHeight = request.resolution.height,
                    ) && snapshot.slot.surfaceTracker.onSurfaceProvided()
                }
                if (canProvide) {
                    try {
                        request.provideSurface(snapshot.surface, cameraExecutor) { result ->
                            Log.d(TAG, "Encoder surface provider result: ${result.resultCode}")
                            if (snapshot.slot.surfaceTracker.onSurfaceReturned()) {
                                finalizeSlotRetirement(snapshot.slot)
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "provideSurface failed unexpectedly: ${e.message}")
                        if (snapshot.slot.surfaceTracker.onSurfaceReturned()) {
                            finalizeSlotRetirement(snapshot.slot)
                        }
                    }
                } else {
                    request.willNotProvideSurface()
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
            val surface = slot.encoder.inputSurface?.takeIf { it.isValid } ?: return@synchronized null
            BindSnapshot(
                sessionId = slot.sessionId,
                generation = slot.generation,
                slot = slot,
                surface = surface,
                cameraId = slot.cameraId,
                width = slot.width,
                height = slot.height,
                fps = slot.fps,
            )
        } ?: run {
            Log.w(TAG, "Encoder snapshot not valid for binding")
            return
        }

        val fpsPlan = CameraFpsCapabilities.resolveForCamera(
            context = context,
            cameraId = snapshot.cameraId,
            width = snapshot.width,
            height = snapshot.height,
            requestedFps = snapshot.fps,
        )
        synchronized(surfaceLock) {
            if (!isSnapshotStillActiveLocked(snapshot)) {
                return
            }
            currentFpsPlan = fpsPlan
        }
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
        setZoomRatio(targetZoomRatio)
        setExposureEv(targetExposureEv)
        setTorch(targetTorch)
        setAutoFocus(targetAutoFocus)
    }

    fun setZoomRatio(ratio: Float) {
        targetZoomRatio = ratio
        activeCamera?.cameraControl?.setZoomRatio(ratio)
    }

    fun setExposureEv(ev: Float) {
        targetExposureEv = ev
        val camera = activeCamera ?: return
        val state = camera.cameraInfo.exposureState
        val step = state.exposureCompensationStep.toFloat()
        if (step > 0f) {
            val index = (ev / step).roundToInt()
            val clamped = index.coerceIn(state.exposureCompensationRange.lower, state.exposureCompensationRange.upper)
            camera.cameraControl.setExposureCompensationIndex(clamped)
        }
    }

    fun setTorch(torchOn: Boolean) {
        targetTorch = torchOn
        activeCamera?.cameraControl?.enableTorch(torchOn)
    }

    fun setAutoFocus(autofocus: Boolean) {
        targetAutoFocus = autofocus
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

    /**
     * Triggers tap-to-focus and auto-exposure metering at the provided [MeteringPoint].
     */
    fun focusAtPoint(point: MeteringPoint) {
        val control = activeCamera?.cameraControl ?: return
        try {
            val action = FocusMeteringAction.Builder(
                point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE,
            )
                .setAutoCancelDuration(3, TimeUnit.SECONDS)
                .build()
            control.startFocusAndMetering(action)
            targetAutoFocus = true
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
            activeSlot?.isRetiring = true
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
        ): Boolean = !isReleased &&
            isStreaming &&
            snapshotSessionId != 0L &&
            activeSessionId == snapshotSessionId &&
            activeSlotMatchesSnapshot &&
            !snapshotRetiring &&
            surfaceValid &&
            requestWidth == snapshotWidth &&
            requestHeight == snapshotHeight

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
