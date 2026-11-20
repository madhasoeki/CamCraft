package org.artcraft.camcraft.camera

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import java.lang.ref.WeakReference
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
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
 * real-time camera controls (Zoom, EV, Torch, AF), and stream reconfiguration.
 */
class CameraStreamManager(
    private val context: Context,
    private val onVideoPacket: (packetType: PacketType, flags: Int, ptsUs: Long, payload: ByteArray) -> Unit,
) {

    private val lifecycleOwner = ServiceLifecycleOwner()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val isReleased = AtomicBoolean(false)

    private var cameraProvider: ProcessCameraProvider? = null
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
     */
    fun rebindCamera(
        cameraId: String,
        width: Int,
        height: Int,
        fps: Int,
        bitrateKbps: Int,
    ) {
        if (isReleased.get()) return

        val encoderConfigChanged = width != currentWidth ||
            height != currentHeight ||
            fps != currentFps ||
            bitrateKbps != currentBitrateKbps

        val cameraChanged = cameraId != currentCameraId

        currentCameraId = cameraId
        currentWidth = width
        currentHeight = height
        currentFps = fps
        currentBitrateKbps = bitrateKbps

        if (!isStreaming) return

        runOnMain {
            if (!isStreaming || isReleased.get()) return@runOnMain
            if (encoderConfigChanged || encoder == null) {
                setupEncoderAndBind()
            } else if (cameraChanged) {
                bindUseCases()
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
                onVideoPacket = onVideoPacket,
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

        try {
            provider.unbindAll()

            // 1. Configure encoder Preview use case
            @Suppress("DEPRECATION")
            val encPreview = Preview.Builder()
                .setTargetResolution(Size(currentWidth, currentHeight))
                .build()

            encPreview.setSurfaceProvider(cameraExecutor) { request ->
                if (!isReleased.get() && surface.isValid) {
                    request.provideSurface(surface, cameraExecutor) { result ->
                        Log.d(TAG, "Encoder surface provider result: ${result.resultCode}")
                    }
                } else {
                    request.willNotProvideSurface()
                }
            }
            encoderPreview = encPreview

            // 2. Camera selector for currentCameraId
            val cameraSelector = buildCameraSelector(currentCameraId)

            // 3. Optional UI PreviewView use case
            val view = attachedPreviewViewRef?.get()
            val useCases = mutableListOf<androidx.camera.core.UseCase>(encPreview)

            if (view != null) {
                val uiPrev = Preview.Builder().build()
                uiPrev.setSurfaceProvider(view.surfaceProvider)
                uiPreview = uiPrev
                useCases.add(uiPrev)
            } else {
                uiPreview = null
            }

            try {
                activeCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    *useCases.toTypedArray(),
                )
            } catch (e: Exception) {
                Log.w(TAG, "Dual preview binding failed, falling back to encoder-only: ${e.message}")
                activeCamera = provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    encPreview,
                )
            }

            applyActiveControls()
            Log.i(TAG, "Camera bound successfully to camera ID $currentCameraId ($currentWidth x $currentHeight)")
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
                    } catch (e: Exception) {
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
