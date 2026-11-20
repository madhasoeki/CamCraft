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
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
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
 * Manages the CameraX lifecycle, GPU surface routing to [H264SurfaceEncoder],
 * real-time camera controls (Zoom, EV, Torch, AF, Tap-to-Focus), and stream reconfiguration.
 */
class CameraStreamManager(
    private val context: Context,
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
        context: Context,
        onVideoPacket: (packetType: PacketType, flags: Int, ptsUs: Long, payload: ByteArray) -> Unit,
    ) : this(
        context = context,
        onVideoPacketSlice = { packetType, flags, ptsUs, payload, offset, length ->
            val standalone = if (offset == 0 && length == payload.size) {
                payload
            } else {
                payload.copyOfRange(offset, offset + length)
            }
            onVideoPacket(packetType, flags, ptsUs, standalone)
        },
    )

    private val lifecycleOwner = ServiceLifecycleOwner()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val isReleased = AtomicBoolean(false)

    private var cameraProvider: ProcessCameraProvider? = null
    @Volatile
    private var activeCamera: Camera? = null
    private var encoder: H264SurfaceEncoder? = null

    private var encoderPreview: Preview? = null
    private var uiPreview: Preview? = null
    private var attachedPreviewViewRef: WeakReference<PreviewView>? = null

    // Active streaming parameters
    var currentCameraId: String = "0"
        private set
    var currentWidth: Int = 1920
        private set
    var currentHeight: Int = 1080
        private set
    var currentFps: Int = 60
        private set
    var currentBitrateKbps: Int = 12_000
        private set

    // Control states
    private var targetZoomRatio: Float = 1.0f
    private var targetExposureEv: Float = 0.0f
    private var targetTorch: Boolean = false
    private var targetAutoFocus: Boolean = true

    @Volatile
    var isStreaming: Boolean = false
        private set

    val cachedConfig: ByteArray?
        get() = encoder?.cachedConfig

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

    private fun initCameraProvider() {
        try {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    if (isReleased.get()) {
                        runCatching { provider.unbindAll() }
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
     * Starts the H.264 encoder and binds CameraX to stream directly to the encoder's input surface.
     */
    fun startStream(
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
    ) {
        if (isReleased.get()) return

        currentCameraId = cameraId
        currentWidth = width
        currentHeight = height
        currentFps = fps
        currentBitrateKbps = bitrateKbps

        isStreaming = true

        runOnMain {
            if (isReleased.get() || !isStreaming) return@runOnMain
            lifecycleOwner.start()
            setupEncoderAndBind()
        }
    }

    /**
     * Unbinds CameraX, stops the encoder, and releases resources.
     */
    fun stopStream() {
        isStreaming = false

        runOnMain {
            cleanupActivePipeline()
            lifecycleOwner.stop()
            Log.i(TAG, "CameraStreamManager stopped")
        }
    }

    private fun cleanupActivePipeline() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Log.w(TAG, "Exception unbinding use cases: ${e.message}")
        }
        activeCamera = null
        encoderPreview = null
        uiPreview = null

        encoder?.release()
        encoder = null
    }

    /**
     * Attaches a [PreviewView] from the UI to display the camera viewfinder on phone screen.
     */
    fun attachPreviewView(previewView: PreviewView) {
        if (isReleased.get()) return
        attachedPreviewViewRef = WeakReference(previewView)
        if (isStreaming) {
            runOnMain {
                if (isStreaming && !isReleased.get()) {
                    bindUseCases()
                }
            }
        }
    }

    /**
     * Detaches the UI [PreviewView] when screen is dimmed or Activity is backgrounded.
     */
    fun detachPreviewView() {
        attachedPreviewViewRef = null
        if (isStreaming && !isReleased.get()) {
            runOnMain {
                if (isStreaming && !isReleased.get()) {
                    bindUseCases()
                }
            }
        }
    }

    /**
     * Dynamically reconfigures resolution, framerate, bitrate, or switches lens.
     * Bitrate-only updates adjust [MediaCodec] dynamically without restarting CameraX.
     */
    fun rebindCamera(
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
    ) {
        if (isReleased.get()) return

        val streamGeometryOrFpsChanged = width != currentWidth ||
            height != currentHeight ||
            fps != currentFps
        val bitrateChanged = bitrateKbps != currentBitrateKbps
        val cameraChanged = cameraId != currentCameraId

        currentCameraId = cameraId
        currentWidth = width
        currentHeight = height
        currentFps = fps
        currentBitrateKbps = bitrateKbps

        if (!isStreaming) return

        runOnMain {
            if (!isStreaming || isReleased.get()) return@runOnMain
            val activeEncoder = encoder
            if (streamGeometryOrFpsChanged || activeEncoder == null) {
                setupEncoderAndBind()
            } else {
                if (bitrateChanged) {
                    activeEncoder.updateBitrate(bitrateKbps)
                }
                if (cameraChanged) {
                    bindUseCases()
                }
            }
        }
    }

    private fun setupEncoderAndBind() {
        if (!isStreaming || isReleased.get()) return

        // Unbind CameraX before releasing the old encoder so CameraX never renders to a closed Surface.
        cleanupActivePipeline()

        var newEncoder: H264SurfaceEncoder? = null
        try {
            newEncoder = H264SurfaceEncoder(
                width = currentWidth,
                height = currentHeight,
                fps = currentFps,
                bitrateKbps = currentBitrateKbps,
                onVideoPacketSlice = onVideoPacketSlice,
            )
            newEncoder.start()
            encoder = newEncoder
        } catch (e: Exception) {
            Log.e(TAG, "Failed to instantiate H264SurfaceEncoder: ${e.message}", e)
            runCatching { newEncoder?.release() }
            encoder = null
            return
        }

        bindUseCases()
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

    private fun buildEncoderPreview(
        surface: android.view.Surface,
        fpsPlan: TargetFpsPlan?,
    ): Preview {
        val resolutionSelector = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(currentWidth, currentHeight),
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
                currentCameraId,
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
                if (!isReleased.get() && surface.isValid) {
                    request.provideSurface(surface, cameraExecutor) { result ->
                        Log.d(TAG, "Encoder surface provider result: ${result.resultCode}")
                    }
                } else {
                    request.willNotProvideSurface()
                }
            }
        }
    }

    private fun buildUiPreview(
        view: PreviewView,
        fpsPlan: TargetFpsPlan?,
    ): Preview {
        val previewTargetSize = Size(minOf(currentWidth, 1280), minOf(currentHeight, 720))
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
                currentCameraId,
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

    private fun bindEncoderOnly(
        provider: ProcessCameraProvider,
        surface: android.view.Surface,
        cameraSelector: CameraSelector,
        fpsPlan: TargetFpsPlan?,
    ) {
        provider.unbindAll()
        val encPreview = buildEncoderPreview(surface, fpsPlan)
        encoderPreview = encPreview
        uiPreview = null
        activeCamera = provider.bindToLifecycle(
            lifecycleOwner,
            cameraSelector,
            encPreview,
        )
    }

    private fun bindWithFallback(
        provider: ProcessCameraProvider,
        surface: android.view.Surface,
        cameraSelector: CameraSelector,
        fpsPlan: TargetFpsPlan?,
    ) {
        provider.unbindAll()
        val encPreview = buildEncoderPreview(surface, fpsPlan)
        encoderPreview = encPreview

        val view = attachedPreviewViewRef?.get()
        if (view != null) {
            val uiPrev = buildUiPreview(view, fpsPlan)
            uiPreview = uiPrev
            try {
                activeCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    encPreview,
                    uiPrev,
                )
                return
            } catch (dualError: Exception) {
                Log.w(
                    TAG,
                    "Dual preview binding failed (${dualError.message}); retrying encoder-only at target FPS",
                )
                try {
                    bindEncoderOnly(provider, surface, cameraSelector, fpsPlan)
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
            uiPreview = null
            try {
                activeCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    encPreview,
                )
                return
            } catch (primaryError: Exception) {
                if (fpsPlan == null) throw primaryError
                Log.w(
                    TAG,
                    "Primary target FPS binding failed (${primaryError.message}); falling back to default 30 FPS",
                )
            }
        }

        bindEncoderOnly(provider, surface, cameraSelector, fpsPlan = null)
    }

    private fun bindUseCases() {
        if (!isStreaming || isReleased.get()) return

        val provider = cameraProvider
        if (provider == null) {
            Log.w(TAG, "ProcessCameraProvider not ready yet, retrying...")
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val resolved = future.get()
                    if (isReleased.get()) {
                        runCatching { resolved.unbindAll() }
                        return@addListener
                    }
                    cameraProvider = resolved
                    if (isStreaming && !isReleased.get()) {
                        bindUseCases()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed retrying ProcessCameraProvider: ${e.message}", e)
                }
            }, mainExecutor)
            return
        }

        val activeEncoder = encoder
        val surface = activeEncoder?.inputSurface
        if (surface == null || !surface.isValid) {
            Log.w(TAG, "Encoder surface not valid for binding")
            return
        }

        val fpsPlan = CameraFpsCapabilities.resolveForCamera(
            context = context,
            cameraId = currentCameraId,
            width = currentWidth,
            height = currentHeight,
            requestedFps = currentFps,
        )
        Log.i(
            TAG,
            "Resolved FPS plan for cam=$currentCameraId ${currentWidth}x$currentHeight@${currentFps}fps: " +
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
            val cameraSelector = buildCameraSelector(currentCameraId)
            bindWithFallback(provider, surface, cameraSelector, fpsPlan)
            applyActiveControls()
            Log.i(TAG, "Camera bound successfully to camera ID $currentCameraId ($currentWidth x $currentHeight @ $currentFps fps)")
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

    fun requestKeyFrame() {
        encoder?.requestKeyFrame()
    }

    fun release() {
        if (!isReleased.compareAndSet(false, true)) return
        isStreaming = false
        attachedPreviewViewRef = null

        runOnMain {
            cleanupActivePipeline()
            cameraProvider = null
            lifecycleOwner.destroy()
            cameraExecutor.shutdown()
            Log.i(TAG, "CameraStreamManager released")
        }
    }

    companion object {
        private const val TAG = "CameraStreamManager"
    }
}
