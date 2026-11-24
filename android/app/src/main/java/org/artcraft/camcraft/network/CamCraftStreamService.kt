package org.artcraft.camcraft.network

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.camera.core.MeteringPoint
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.artcraft.camcraft.MainActivity
import org.artcraft.camcraft.R
import org.artcraft.camcraft.camera.CameraEnumerator
import org.artcraft.camcraft.camera.CameraStreamManager
import org.artcraft.camcraft.encoder.AudioStreamEngine
import org.artcraft.camcraft.protocol.ActiveCameraState
import org.artcraft.camcraft.protocol.CameraInfo
import org.artcraft.camcraft.protocol.CcftFlags
import org.artcraft.camcraft.protocol.CcftPacket
import org.artcraft.camcraft.protocol.CcftProtocolConstants
import org.artcraft.camcraft.protocol.ControlAck
import org.artcraft.camcraft.protocol.ControlCmd
import org.artcraft.camcraft.protocol.ControlHello
import org.artcraft.camcraft.protocol.PacketType
import org.artcraft.camcraft.protocol.ResolutionOption

/**
 * Connection and streaming states exposed to the UI.
 */
enum class StreamConnectionStatus {
    STOPPED,
    LISTENING,
    CONNECTED,
    ERROR,
}

/**
 * Service observable state consumed by Jetpack Compose.
 */
data class StreamServiceState(
    val status: StreamConnectionStatus = StreamConnectionStatus.STOPPED,
    val clientAddress: String? = null,
    val localIpAddress: String? = null,
    val listenPort: Int = CcftProtocolConstants.DEFAULT_PORT,
    val mdnsServiceName: String? = null,
    val mdnsRegistered: Boolean = false,
    val cameras: List<CameraInfo> = emptyList(),
    val activeState: ActiveCameraState = defaultActiveCameraState(),
    val framesSent: Long = 0,
    val currentFps: Float = 0f,
    val bitrateKbps: Int = 12_000,
    val errorMessage: String? = null,
    val hasActiveSession: Boolean = false,
    val isVideoPipelineHealthy: Boolean = true,
) {
    val wifiEndpoint: String?
        get() = formatWifiEndpoint(localIpAddress, listenPort)

    val isServerActive: Boolean
        get() = status == StreamConnectionStatus.LISTENING ||
            status == StreamConnectionStatus.CONNECTED ||
            hasActiveSession

    val isMicControlAvailable: Boolean
        get() = isServerActive && activeState.audioEnabled
}

/**
 * Default initial camera configuration matching `docs/PROTOCOL.md` §5.1.
 */
fun defaultActiveCameraState(): ActiveCameraState = ActiveCameraState(
    cameraId = "0",
    width = 1920,
    height = 1080,
    fps = 60,
    bitrateKbps = 12_000,
    zoomRatio = 1.0f,
    exposureEv = 0.0f,
    autofocus = true,
    torchOn = false,
    mirrorHorizontal = false,
    rotationDeg = 0,
    audioEnabled = true,
    audioMuted = false,
)

/**
 * Foreground Service hosting the CamCraft TCP Server (Port 9876).
 *
 * Implements Stage 2:
 * 1. Opens ServerSocket(9876) with TCP_NODELAY.
 * 2. Handshakes with PC receiver via CONTROL_HELLO (0x10).
 * 3. Answers HEARTBEAT_PING with HEARTBEAT_PONG mirroring timestamp.
 * 4. Handles CONTROL_CMD and updates ActiveCameraState with CONTROL_ACK.
 * 5. Streams synthetic test video frames (60 FPS) to measure network performance.
 */
class CamCraftStreamService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null
    private var usbJob: Job? = null

    private val sessionMutex = Mutex()

    @Volatile
    private var activeSession: ClientSessionHandle? = null

    private val activeTcpSockets = mutableSetOf<Socket>()

    private val usbAttemptTracker = UsbSessionAttemptTracker<ParcelFileDescriptor>(
        pendingCloseTracker = pendingUsbCloseTracker,
    ) { pfd ->
        try {
            pfd.close()
        } catch (e: IOException) {
            Log.w(TAG, "Error closing USB accessory descriptor: ${e.message}")
        }
    }
    private var isUsbReceiverRegistered = false

    private val usbDetachedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_DETACHED) return
            val detachedAccessory = IntentCompat.getParcelableExtra(
                intent,
                UsbManager.EXTRA_ACCESSORY,
                UsbAccessory::class.java,
            )
            val currentAttempt = usbAttemptTracker.currentActiveAttempt() ?: return
            val currentAccessory = currentAttempt.accessoryTag as? UsbAccessory
            if (detachedAccessory == null ||
                currentAccessory == null ||
                detachedAccessory == currentAccessory ||
                UsbAccessoryTransport.isCamCraftAccessory(detachedAccessory)
            ) {
                Log.i(TAG, "USB accessory detached; closing USB AOA transport (attempt=${currentAttempt.attemptId})")
                usbAttemptTracker.closeAttempt(currentAttempt)
            }
        }
    }

    private val isStopping = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var serverBindError: String? = null

    private var cameraStreamManager: CameraStreamManager? = null
    private var mdnsAdvertiser: MdnsAdvertiser? = null
    private var wifiNetworkMonitor: WifiNetworkMonitor? = null

    private val controlCoordinator = ControlStateAckCoordinator(_serviceState) {
        // Outbound session ACKs are dispatched atomically via ownedSession under orderLock.
    }

    @Volatile
    private var activeTransformFlags: Int = CcftFlags.NONE

    @Volatile
    private var totalFramesSent: Long = 0
    @Volatile
    private var windowFrames: Long = 0
    @Volatile
    private var windowStartNs: Long = System.nanoTime()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startInForeground()

        synchronized(stateOwnershipLock) {
            activeInstance = this
            activeStateOwnerSessionId = 0L
        }

        ContextCompat.registerReceiver(
            this,
            usbDetachedReceiver,
            IntentFilter(UsbManager.ACTION_USB_ACCESSORY_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isUsbReceiverRegistered = true

        val initialIp = LocalIpResolver.resolveLocalIpv4Address(this)
        _serviceState.update { it.copy(localIpAddress = initialIp) }

        mdnsAdvertiser = MdnsAdvertiser(this) { registered, serviceName ->
            _serviceState.update {
                it.copy(
                    mdnsRegistered = registered,
                    mdnsServiceName = serviceName,
                )
            }
        }

        wifiNetworkMonitor = WifiNetworkMonitor(this) { updatedIp ->
            handleWifiIpChangeUnderLock(
                lock = lifecycleLock,
                isStopping = { isStopping.get() },
                serviceState = _serviceState,
                updatedIp = updatedIp,
                onRegisterMdns = { port, ip ->
                    mdnsAdvertiser?.register(
                        deviceName = resolveDeviceName(),
                        port = port,
                        localIpAddress = ip,
                    )
                },
                onUnregisterMdns = { mdnsAdvertiser?.unregister() },
                onUpdateNotification = { ip, port ->
                    updateNotification(buildListeningNotificationText(ip, port))
                },
            )
        }

        try {
            val appContext = applicationContext ?: this
            val cameras = CameraEnumerator.enumerateCameras(appContext)
            _serviceState.update { it.copy(cameras = cameras) }
            val manager = CameraStreamManager(
                context = appContext,
                onVideoPacketSlice = { sessionId, cameraConfigId, encoderGeneration, packetType, flags, ptsUs, payload, offset, length ->
                    handleVideoPacketFromEncoder(
                        sessionId = sessionId,
                        cameraConfigId = cameraConfigId,
                        encoderGeneration = encoderGeneration,
                        packetType = packetType,
                        flags = flags,
                        ptsUs = ptsUs,
                        payload = payload,
                        offset = offset,
                        length = length,
                    )
                },
                onResolutionAdjusted = { sessionId, cameraConfigId, cameraId, width, height, commitNegotiatedDimensions ->
                    handleEncoderResolutionAdjusted(
                        sessionId = sessionId,
                        cameraConfigId = cameraConfigId,
                        cameraId = cameraId,
                        width = width,
                        height = height,
                        commitNegotiatedDimensions = commitNegotiatedDimensions,
                    )
                },
                onResolutionAdjustmentFailed = { sessionId, cameraConfigId, cameraId, reason ->
                    handleEncoderResolutionAdjustmentFailed(sessionId, cameraConfigId, cameraId, reason)
                },
            )
            cameraStreamManager = manager
            pendingPreviewView?.get()?.let { previewView ->
                manager.attachPreviewView(previewView)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize CameraStreamManager: ${e.message}", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "Stop command received, shutting down CamCraft service")
                val capturedTransports = synchronized(lifecycleLock) {
                    val stopTargets = stopServerLocked()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                    stopTargets
                }
                dispatchCapturedStopTransports(capturedTransports)
            }
            ACTION_START_USB_ACCESSORY -> {
                Log.i(TAG, "USB accessory start command received")
                startInForeground()
                startServer()
                val accessory = IntentCompat.getParcelableExtra(
                    intent,
                    UsbManager.EXTRA_ACCESSORY,
                    UsbAccessory::class.java,
                ) ?: UsbAccessoryTransport.findAttachedCamCraftAccessory(this)
                if (accessory != null) {
                    startUsbAccessorySession(accessory)
                } else {
                    Log.w(TAG, "ACTION_START_USB_ACCESSORY received without an attached CamCraft accessory")
                }
            }
            else -> {
                Log.i(TAG, "Start command received, initializing CamCraft server")
                startInForeground()
                startServer()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "Destroying CamCraft service")
        val preservedIp = LocalIpResolver.resolveLocalIpv4Address(this)
            ?: _serviceState.value.localIpAddress
        val audioToRelease: AudioStreamEngine?
        val cameraToRelease: CameraStreamManager?
        var stopTargets = CapturedStopTransports(null, emptyList(), null)
        synchronized(lifecycleLock) {
            isStopping.set(true)
            if (isUsbReceiverRegistered) {
                try {
                    unregisterReceiver(usbDetachedReceiver)
                } catch (_: IllegalArgumentException) {
                }
                isUsbReceiverRegistered = false
            }
            audioToRelease = activeSession?.audioEngine
            shutdownWifiAndMdnsUnderLock(
                lock = lifecycleLock,
                setStopping = { isStopping.set(true) },
                stopWifiMonitor = {
                    wifiNetworkMonitor?.stop()
                    wifiNetworkMonitor = null
                },
                stopServerAndTransport = {
                    stopTargets = stopServerLocked()
                    activeTransformFlags = CcftFlags.NONE
                },
                resetServiceState = {
                    synchronized(stateOwnershipLock) {
                        if (activeInstance === this) {
                            _serviceState.value = StreamServiceState(
                                status = StreamConnectionStatus.STOPPED,
                                localIpAddress = preservedIp,
                            )
                            activeStateOwnerSessionId = 0L
                            activeInstance = null
                        }
                    }
                },
                unregisterMdns = {
                    mdnsAdvertiser?.unregister()
                    mdnsAdvertiser = null
                },
            )
            cameraToRelease = cameraStreamManager
            cameraStreamManager = null
            pendingPreviewView = null
        }
        dispatchCapturedStopTransports(stopTargets)
        audioToRelease?.release()
        cameraToRelease?.release()
        serviceScope.cancel()
    }

    private fun startInForeground() {
        val notification = buildNotification("Starting CamCraft Server...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hasMicPermission = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            val serviceTypes = if (hasMicPermission) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            } else {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            startForeground(
                NOTIFICATION_ID,
                notification,
                serviceTypes,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startServer() {
        synchronized(lifecycleLock) {
            if (serverJob?.isActive == true) return
            isStopping.set(false)

            serverJob = serviceScope.launch {
                val currentJob = coroutineContext[Job]
                var socket: ServerSocket? = null
                try {
                    val port = CcftProtocolConstants.DEFAULT_PORT
                    val boundSocket = bindAndPublishListenerSocket(
                        listenerTracker = pendingListenerCloseTracker,
                        isCancelledOrStopped = {
                            synchronized(lifecycleLock) {
                                isStopping.get() || !isActive || serverJob !== currentJob
                            }
                        },
                        bindSocket = {
                            ServerSocket(port).apply {
                                reuseAddress = true
                            }
                        },
                        publishSocket = { candidate ->
                            synchronized(lifecycleLock) {
                                if (isStopping.get() || !isActive || serverJob !== currentJob) {
                                    false
                                } else {
                                    serverSocket = candidate
                                    socket = candidate
                                    true
                                }
                            }
                        },
                    ) ?: return@launch

                    val currentIp = LocalIpResolver.resolveLocalIpv4Address(this@CamCraftStreamService)
                    var becameListening = false
                    var abortServer = false
                    synchronized(lifecycleLock) {
                        if (isStopping.get() ||
                            !isActive ||
                            serverJob !== currentJob ||
                            serverSocket !== boundSocket
                        ) {
                            abortServer = true
                            return@synchronized
                        }
                        serverBindError = null
                        val sessionActive = activeSession != null
                        val updatedListening = synchronized(stateOwnershipLock) {
                            if (activeInstance !== this@CamCraftStreamService) {
                                false
                            } else {
                                _serviceState.update {
                                    if (sessionActive) {
                                        it.copy(
                                            localIpAddress = currentIp,
                                            listenPort = port,
                                        )
                                    } else {
                                        becameListening = true
                                        activeStateOwnerSessionId = 0L
                                        it.copy(
                                            status = StreamConnectionStatus.LISTENING,
                                            hasActiveSession = false,
                                            isVideoPipelineHealthy = true,
                                            clientAddress = null,
                                            localIpAddress = currentIp,
                                            listenPort = port,
                                            errorMessage = null,
                                        )
                                    }
                                }
                                true
                            }
                        }
                        if (updatedListening) {
                            mdnsAdvertiser?.register(
                                deviceName = resolveDeviceName(),
                                port = port,
                                localIpAddress = currentIp,
                            )
                            if (becameListening) {
                                updateNotification(buildListeningNotificationText(currentIp, port))
                            }
                        }
                    }
                    if (abortServer) {
                        return@launch
                    }
                    Log.i(TAG, "CamCraft TCP Server listening on port $port (Wi-Fi IP: $currentIp)")

                    while (isActive && !boundSocket.isClosed) {
                        try {
                            val clientSocket = boundSocket.accept()
                            handleClientConnection(clientSocket)
                        } catch (e: SocketException) {
                            if (!boundSocket.isClosed) {
                                Log.e(TAG, "Socket exception during accept", e)
                            }
                            break
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) {
                        throw e
                    }
                    if (isStopping.get()) {
                        Log.i(TAG, "Server stopped")
                        return@launch
                    }
                    Log.e(TAG, "Fatal server socket exception", e)
                    val errMsg = e.message ?: "Server error"
                    synchronized(lifecycleLock) {
                        if (isStopping.get() || serverJob !== currentJob) return@synchronized
                        mdnsAdvertiser?.unregister()
                        serverBindError = errMsg
                        if (activeSession == null) {
                            val updatedError = synchronized(stateOwnershipLock) {
                                if (activeInstance !== this@CamCraftStreamService) {
                                    false
                                } else {
                                    activeStateOwnerSessionId = 0L
                                    _serviceState.update {
                                        it.copy(
                                            status = StreamConnectionStatus.ERROR,
                                            hasActiveSession = false,
                                            isVideoPipelineHealthy = true,
                                            clientAddress = null,
                                            errorMessage = errMsg,
                                        )
                                    }
                                    true
                                }
                            }
                            if (updatedError) {
                                updateNotification("Error: $errMsg")
                            }
                        }
                    }
                } finally {
                    val ownedSocket = socket
                    var listenerCompletion: CompletableDeferred<Unit>? = null
                    val socketToClose = synchronized(lifecycleLock) {
                        if (ownedSocket != null && serverSocket === ownedSocket) {
                            serverSocket = null
                        }
                        if (ownedSocket != null && !ownedSocket.isClosed) {
                            listenerCompletion = pendingListenerCloseTracker.register()
                        }
                        ownedSocket
                    }
                    try {
                        if (socketToClose != null) {
                            try {
                                socketToClose.close()
                            } catch (_: IOException) {}
                        }
                    } finally {
                        listenerCompletion?.complete(Unit)
                    }
                }
            }
        }
    }

    private fun sendStreamPacket(
        sessionId: Long,
        packetType: PacketType,
        flags: Int,
        ptsUs: Long,
        payload: ByteArray,
        offset: Int,
        length: Int,
        tagLabel: String,
        canWriteLocked: () -> Boolean = { true },
    ): Boolean = sendStreamPacketForSession(
        activeSessionProvider = { activeSession },
        clearActiveSessionIfCurrent = { failedSession ->
            synchronized(lifecycleLock) {
                if (activeSession === failedSession) {
                    activeSession = null
                }
                controlCoordinator.clearActiveSessionIfCurrent(failedSession)
            }
        },
        sessionId = sessionId,
        packetType = packetType,
        flags = flags,
        ptsUs = ptsUs,
        payload = payload,
        offset = offset,
        length = length,
        tagLabel = tagLabel,
        canWriteLocked = canWriteLocked,
    )

    private fun handleVideoPacketFromEncoder(
        sessionId: Long,
        cameraConfigId: Long,
        encoderGeneration: Long,
        packetType: PacketType,
        flags: Int,
        ptsUs: Long,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) {
        val outboundFlags = if (packetType == PacketType.VIDEO_FRAME) {
            flags or activeTransformFlags
        } else {
            flags
        }
        val sent = sendStreamPacket(
            sessionId = sessionId,
            packetType = packetType,
            flags = outboundFlags,
            ptsUs = ptsUs,
            payload = payload,
            offset = offset,
            length = length,
            tagLabel = "video",
            canWriteLocked = {
                cameraStreamManager?.isVideoPipelineCurrent(
                    sessionId = sessionId,
                    encoderGeneration = encoderGeneration,
                ) == true
            },
        )
        if (sent) {
            onFrameSent(sessionId, cameraConfigId, packetType)
        }
    }

    private fun handleEncoderResolutionAdjusted(
        sessionId: Long,
        cameraConfigId: Long,
        cameraId: String,
        width: Int,
        height: Int,
        commitNegotiatedDimensions: () -> Boolean,
    ) {
        commitEncoderResolutionAdjusted(
            coordinator = controlCoordinator,
            expectedSessionId = sessionId,
            expectedCameraConfigId = cameraConfigId,
            expectedCameraId = cameraId,
            width = width,
            height = height,
            isStopping = { isStopping.get() },
            commitNegotiatedDimensions = commitNegotiatedDimensions,
        ) ?: return
        publishConnectedNotificationFromCurrentState(expectedSessionId = sessionId)
    }

    private fun handleEncoderResolutionAdjustmentFailed(
        sessionId: Long,
        cameraConfigId: Long,
        cameraId: String,
        reason: String,
    ) {
        if (isStopping.get()) return
        Log.w(TAG, "Resolution adjustment failed (session=$sessionId, config=$cameraConfigId, cam=$cameraId): $reason")
        applyVideoPipelineFailureIfCurrentConfig(
            lock = lifecycleLock,
            coordinator = controlCoordinator,
            expectedSessionId = sessionId,
            expectedCameraConfigId = cameraConfigId,
            expectedCameraId = cameraId,
            isStopping = { isStopping.get() },
            isSessionActive = { activeSession?.sessionId == sessionId },
            reason = reason,
            serviceState = _serviceState,
            onUpdateNotification = ::updateNotification,
        )
    }

    private fun publishConnectedNotificationFromCurrentState(expectedSessionId: Long) {
        publishConnectedNotificationFromSnapshot(
            lock = lifecycleLock,
            isStopping = { isStopping.get() },
            isSessionActive = { activeSession?.sessionId == expectedSessionId },
            serviceState = _serviceState,
            onUpdateNotification = ::updateNotification,
        )
    }

    private fun handleAudioPacketFromEngine(
        sessionId: Long,
        packetType: PacketType,
        ptsUs: Long,
        payload: ByteArray,
        length: Int,
    ) {
        if (sessionId == 0L) return
        sendStreamPacket(sessionId, packetType, CcftFlags.NONE, ptsUs, payload, 0, length, "audio")
    }

    private fun onFrameSent(
        sessionId: Long,
        cameraConfigId: Long,
        packetType: PacketType,
    ) {
        if (packetType != PacketType.VIDEO_FRAME) return
        if (!isActiveSessionMatching(isStopping.get(), activeSession, sessionId)) return
        if (_serviceState.value.status == StreamConnectionStatus.ERROR) {
            val recovered = restoreConnectedStatusIfRecovering(
                lock = lifecycleLock,
                coordinator = controlCoordinator,
                expectedSessionId = sessionId,
                expectedCameraConfigId = cameraConfigId,
                isStopping = { isStopping.get() },
                isSessionActive = { activeSession?.sessionId == sessionId },
                serviceState = _serviceState,
                onUpdateNotification = ::updateNotification,
            )
            if (!recovered && _serviceState.value.status == StreamConnectionStatus.ERROR) {
                return
            }
        }
        if (!isActiveSessionMatching(isStopping.get(), activeSession, sessionId)) return
        totalFramesSent++
        windowFrames++

        val now = System.nanoTime()
        val elapsedSec = (now - windowStartNs) / 1_000_000_000.0
        if (elapsedSec >= 1.0) {
            val fps = (windowFrames / elapsedSec).toFloat()
            if (isActiveSessionMatching(isStopping.get(), activeSession, sessionId)) {
                _serviceState.update {
                    it.copy(
                        framesSent = totalFramesSent,
                        currentFps = fps,
                    )
                }
            }
            windowFrames = 0
            windowStartNs = now
        }
    }

    private suspend fun handleClientConnection(clientSocket: Socket) {
        synchronized(lifecycleLock) {
            if (isStopping.get() || isUsbBlockingTcpAdmission(usbJob?.isActive == true, usbAttemptTracker)) {
                Log.i(TAG, "Ignoring incoming TCP connection (stopping or USB AOA active)")
                try {
                    clientSocket.close()
                } catch (_: IOException) {}
                return
            }
            activeTcpSockets.add(clientSocket)
        }

        val clientIp = clientSocket.inetAddress?.hostAddress ?: "unknown"
        Log.i(TAG, "PC Client connected from $clientIp")

        var inputStream: DataInputStream? = null
        var outputStream: OutputStream? = null
        var sessionEntered = false
        val closeTransport: () -> Unit = {
            synchronized(lifecycleLock) {
                activeTcpSockets.remove(clientSocket)
            }
            try {
                clientSocket.close()
            } catch (_: IOException) {}
        }

        try {
            clientSocket.tcpNoDelay = true
            clientSocket.keepAlive = true
            clientSocket.soTimeout = CLIENT_READ_TIMEOUT_MS

            outputStream = BufferedOutputStream(clientSocket.getOutputStream(), 65536)
            inputStream = DataInputStream(BufferedInputStream(clientSocket.getInputStream(), 16384))

            sessionEntered = true
            handleClientSession(
                clientLabel = clientIp,
                inputStream = inputStream,
                outputStream = outputStream,
                closeTransport = closeTransport,
                isTransportStillCurrent = {
                    val usbActive = isUsbBlockingTcpAdmission(usbJob?.isActive == true, usbAttemptTracker)
                    !usbActive && activeTcpSockets.contains(clientSocket) && !clientSocket.isClosed
                },
            )
        } catch (e: Exception) {
            Log.w(TAG, "Client TCP connection setup/session error: ${e.message}")
        } finally {
            if (!sessionEntered) {
                closeTransportBeforeStreams(closeTransport, inputStream, outputStream)
            }
            synchronized(lifecycleLock) {
                activeTcpSockets.remove(clientSocket)
            }
        }
    }

    private fun startUsbAccessorySession(accessory: UsbAccessory) {
        if (!UsbAccessoryTransport.isCamCraftAccessory(accessory)) {
            Log.w(
                TAG,
                "Ignoring non-CamCraft USB accessory (${accessory.manufacturer}/${accessory.model})",
            )
            return
        }

        val previousUsbJob: Job?
        val attempt: UsbSessionAttempt<ParcelFileDescriptor>
        synchronized(lifecycleLock) {
            isStopping.set(false)
            val existing = usbJob
            if (existing?.isActive == true && usbAttemptTracker.hasActiveNonClosingAttempt()) {
                Log.i(TAG, "USB AOA session already active; skipping duplicate start")
                return
            }
            previousUsbJob = existing
            attempt = usbAttemptTracker.beginAttempt(accessoryTag = accessory)

            usbJob = serviceScope.launch {
                var inputStream: DataInputStream? = null
                var outputStream: OutputStream? = null
                var sessionEntered = false
                try {
                    if (previousUsbJob != null && previousUsbJob.isActive) {
                        previousUsbJob.join()
                    }
                    val pfd = openUsbAccessoryAfterRetirement(
                        usbCloseTracker = pendingUsbCloseTracker,
                        attemptTracker = usbAttemptTracker,
                        attempt = attempt,
                        isCancelledOrStopped = { isStopping.get() },
                        onRetirementTimeout = { timeoutAttempt ->
                            if (timeoutAttempt == 1) {
                                Log.w(
                                    TAG,
                                    "Timed out waiting for previous USB descriptor retirement; keeping USB startup pending for retry",
                                )
                            }
                        },
                        openAccessory = {
                            val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
                            if (usbManager == null) {
                                Log.w(TAG, "UsbManager unavailable; cannot open USB accessory")
                                null
                            } else {
                                try {
                                    usbManager.openAccessory(accessory).also { opened ->
                                        if (opened == null) {
                                            Log.w(TAG, "UsbManager.openAccessory returned null for ${accessory.model}")
                                        }
                                    }
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to open USB accessory: ${e.message}", e)
                                    null
                                }
                            }
                        },
                    ) ?: return@launch

                    if (!attachUsbDescriptorAndPreemptTcp(
                            lifecycleLock = lifecycleLock,
                            attemptTracker = usbAttemptTracker,
                            attempt = attempt,
                            descriptor = pfd,
                            isCancelledOrStopped = { isStopping.get() || !isActive },
                            activeTcpSockets = activeTcpSockets,
                            detachActiveTcpSessionLocked = {
                                val current = activeSession
                                if (current != null) {
                                    activeSession = null
                                    controlCoordinator.clearActiveSessionIfCurrent(current)
                                }
                                current
                            },
                        )
                    ) {
                        return@launch
                    }

                    val fd = pfd.fileDescriptor
                    inputStream = DataInputStream(BufferedInputStream(FileInputStream(fd), 16_384))
                    outputStream = BufferedOutputStream(
                        AoaChunkedOutputStream(FileOutputStream(fd)),
                        65_536,
                    )

                    sessionEntered = true
                    handleClientSession(
                        clientLabel = USB_CLIENT_LABEL,
                        inputStream = inputStream,
                        outputStream = outputStream,
                        closeTransport = { usbAttemptTracker.closeAttempt(attempt) },
                        isTransportStillCurrent = { usbAttemptTracker.isAttemptCurrent(attempt, pfd) },
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "USB AOA session setup/teardown error: ${e.message}")
                } finally {
                    if (!sessionEntered) {
                        closeTransportBeforeStreams(
                            closeTransport = { usbAttemptTracker.closeAttempt(attempt) },
                            inputStream = inputStream,
                            outputStream = outputStream,
                        )
                    }
                }
            }
        }
    }

    private suspend fun handleClientSession(
        clientLabel: String,
        inputStream: DataInputStream,
        outputStream: OutputStream,
        closeTransport: () -> Unit,
        isTransportStillCurrent: () -> Boolean,
    ) {
        var enteredMutex = false
        try {
            sessionMutex.withLock {
                enteredMutex = true
                if (isStopping.get() || !currentCoroutineContext().isActive) {
                    closeTransportBeforeStreams(closeTransport, inputStream, outputStream)
                    return
                }

                val sessionId = nextSessionId.getAndIncrement()
                val sessionOutputLock = Any()
                val sessionHandle = ClientSessionHandle(
                    sessionId = sessionId,
                    output = outputStream,
                    outputLock = sessionOutputLock,
                    closeTransport = closeTransport,
                    ackQueueCapacity = CONTROL_ACK_QUEUE_CAPACITY,
                    dispatchClose = { closeTask ->
                        Dispatchers.IO.asExecutor().execute(closeTask)
                    },
                )
                val capturedSessionId = sessionHandle.sessionId
                val appContext = applicationContext ?: this
                val helloCameras = CameraEnumerator.enumerateCameras(appContext)
                val sessionAudioEngine = AudioStreamEngine(appContext) { packetType, ptsUs, payload, length ->
                    handleAudioPacketFromEngine(capturedSessionId, packetType, ptsUs, payload, length)
                }

                var initialHelloState: ActiveCameraState? = null
                val isCoroutineActive = currentCoroutineContext().isActive
                val admitted = synchronized(lifecycleLock) {
                    val canAdmit = !isStopping.get() &&
                        isCoroutineActive &&
                        !sessionHandle.isClosed &&
                        isTransportStillCurrent()
                    if (!canAdmit) {
                        false
                    } else {
                        val published = synchronized(stateOwnershipLock) {
                            if (activeInstance !== this) {
                                false
                            } else {
                                activeSession = sessionHandle
                                activeStateOwnerSessionId = sessionId
                                _serviceState.update {
                                    it.copy(
                                        status = StreamConnectionStatus.CONNECTED,
                                        hasActiveSession = true,
                                        isVideoPipelineHealthy = true,
                                        clientAddress = clientLabel,
                                        errorMessage = null,
                                    )
                                }
                                true
                            }
                        }
                        if (published) {
                            val capturedState = controlCoordinator.attachSessionAndCaptureState(sessionHandle)
                            if (capturedState != null) {
                                initialHelloState = capturedState
                                activeTransformFlags = computeTransformFlags(
                                    mirrorHorizontal = capturedState.mirrorHorizontal,
                                    rotationDeg = capturedState.rotationDeg,
                                )
                                publishConnectedNotificationFromCurrentState(expectedSessionId = sessionId)
                                true
                            } else {
                                synchronized(stateOwnershipLock) {
                                    if (activeSession === sessionHandle) {
                                        activeSession = null
                                    }
                                    if (activeStateOwnerSessionId == sessionId) {
                                        activeStateOwnerSessionId = 0L
                                    }
                                }
                                false
                            }
                        } else {
                            false
                        }
                    }
                }
                val helloActiveState = initialHelloState
                if (!admitted || helloActiveState == null) {
                    closeTransportBeforeStreams(closeTransport, inputStream, outputStream)
                    return
                }

                var ackWriterJob: Job? = null
                var writeWatchdogJob: Job? = null
                var helloWriterJob: Job? = null
                var helloWriteCompleted = false

                try {
                    // 1. Start write watchdog BEFORE the first tracked write (CONTROL_HELLO) so a
                    //    stalled initial write/flush cannot block indefinitely.
                    writeWatchdogJob = serviceScope.launch {
                        while (isActive && !isStopping.get() && !sessionHandle.isClosed) {
                            delay(WRITE_WATCHDOG_POLL_MS)
                            val stalled = checkAndCloseStalledSessionWrite(
                                session = sessionHandle,
                                nowNs = System.nanoTime(),
                                timeoutNs = WRITE_STALL_TIMEOUT_MS * 1_000_000L,
                                beforeClose = {
                                    synchronized(lifecycleLock) {
                                        if (activeSession === sessionHandle) {
                                            activeSession = null
                                        }
                                        controlCoordinator.clearActiveSessionIfCurrent(sessionHandle)
                                    }
                                },
                            )
                            if (stalled) {
                                Log.w(
                                    TAG,
                                    "Closed stalled client session=$sessionId after write blocked > ${WRITE_STALL_TIMEOUT_MS}ms",
                                )
                                break
                            }
                        }
                    }

                    // 2. Send and flush CONTROL_HELLO (0x10) BEFORE starting ackWriterJob so any ACKs
                    //    queued during setup follow HELLO in order, while aborting promptly if the
                    //    watchdog closes the session due to a stalled transport write.
                    val hello = createDeviceHello(helloActiveState, helloCameras)
                    val helloPacket = CcftPacket.controlHello(System.nanoTime() / 1_000L, hello)
                    helloWriterJob = writeInitialHelloCancellable(
                        scope = serviceScope,
                        session = sessionHandle,
                    ) {
                        helloPacket.encodeTo(sessionHandle.output, flush = true)
                    }
                    helloWriteCompleted = true
                    Log.i(TAG, "Sent CONTROL_HELLO to $clientLabel (session=$sessionId)")

                    ackWriterJob = serviceScope.launch {
                        for (ack in sessionHandle.ackChannel) {
                            if (isStopping.get() || activeSession !== sessionHandle) {
                                break
                            }
                            try {
                                val packet = CcftPacket.controlAck(
                                    ptsUs = System.nanoTime() / 1_000L,
                                    ack = ack,
                                )
                                synchronized(sessionHandle.outputLock) {
                                    sessionHandle.withTrackedWrite {
                                        packet.encodeTo(sessionHandle.output, flush = true)
                                    }
                                }
                            } catch (e: IOException) {
                                Log.w(TAG, "Failed writing CONTROL_ACK to client (session=$sessionId): ${e.message}")
                                sessionHandle.close()
                                break
                            }
                        }
                    }

                    // 2. Start CameraX + H.264 encoder serialized with UI lens/config commits under orderLock.
                    controlCoordinator.runIfSessionActive(
                        session = sessionHandle,
                        advanceCameraConfig = true,
                    ) { activeState, seq, configId ->
                        if (!isStopping.get()) {
                            activeTransformFlags = computeTransformFlags(
                                mirrorHorizontal = activeState.mirrorHorizontal,
                                rotationDeg = activeState.rotationDeg,
                            )
                            cameraStreamManager?.startStream(
                                sessionId = sessionHandle.sessionId,
                                configSeq = seq,
                                cameraConfigId = configId,
                                cameraId = activeState.cameraId,
                                width = activeState.width,
                                height = activeState.height,
                                fps = activeState.fps,
                                bitrateKbps = activeState.bitrateKbps,
                                zoomRatio = activeState.zoomRatio,
                                exposureEv = activeState.exposureEv,
                                torchOn = activeState.torchOn,
                            )
                        }
                    }

                    // 3. If encoder already cached SPS/PPS config for this session, immediately dispatch it
                    var sentCachedConfig = false
                    synchronized(sessionHandle.outputLock) {
                        cameraStreamManager?.cachedConfigForSession(sessionHandle.sessionId)?.let { spsPps ->
                            sessionHandle.withTrackedWrite {
                                CcftPacket.writePacket(
                                    output = sessionHandle.output,
                                    type = PacketType.VIDEO_CONFIG,
                                    flags = CcftFlags.NONE,
                                    ptsUs = System.nanoTime() / 1_000L,
                                    payload = spsPps,
                                    offset = 0,
                                    length = spsPps.size,
                                    flush = true,
                                )
                            }
                            sentCachedConfig = true
                        }
                    }
                    if (sentCachedConfig) {
                        cameraStreamManager?.requestKeyFrame(sessionHandle.sessionId)
                    }

                    // 4. Start AudioStreamEngine initially muted off orderLock, then apply committed mute under orderLock
                    val shouldStartAudio = controlCoordinator.runIfSessionActive(sessionHandle) { state, _, _ ->
                        state.audioEnabled && !isStopping.get()
                    } ?: false
                    if (shouldStartAudio) {
                        val audioStarted = sessionAudioEngine.start(initiallyMuted = true)
                        if (audioStarted) {
                            val keepAudioRunning = controlCoordinator.runIfSessionActive(sessionHandle) { state, _, _ ->
                                if (state.audioEnabled && !isStopping.get()) {
                                    sessionHandle.audioEngine = sessionAudioEngine
                                    sessionAudioEngine.setMuted(state.audioMuted)
                                    true
                                } else {
                                    false
                                }
                            } ?: false
                            if (!keepAudioRunning) {
                                sessionAudioEngine.stop()
                            }
                        } else {
                            Log.w(TAG, "AudioStreamEngine failed to start (check RECORD_AUDIO permission)")
                            controlCoordinator.updateIfSessionOwned(
                                expectedSessionId = sessionHandle.sessionId,
                                advanceCameraConfigOnGeometryChange = false,
                                ackBuilder = { next ->
                                    if (!isStopping.get()) ControlAck.success(next) else null
                                },
                            ) { cur ->
                                cur.copy(audioEnabled = false)
                            }
                        }
                    }

                    // 5. Run reader loop to process incoming heartbeats and control commands
                    runPacketReader(sessionHandle, inputStream, clientLabel)
                } catch (e: Exception) {
                    Log.w(TAG, "Client session ($clientLabel, session=$sessionId) terminated: ${e.message}")
                } finally {
                    // Clear session ownership and close transport FIRST so stalled socket/pfd writes unblock.
                    synchronized(lifecycleLock) {
                        if (activeSession === sessionHandle) {
                            activeSession = null
                        }
                        controlCoordinator.clearActiveSessionIfCurrent(sessionHandle)
                    }

                    sessionHandle.close()
                    closeTransportBeforeStreams(
                        closeTransport = closeTransport,
                        inputStream = inputStream,
                        outputStream = outputStream,
                        deferOutputClose = !helloWriteCompleted || sessionHandle.hasInFlightWrite,
                        dispatchDeferredClose = { closeTask ->
                            Dispatchers.IO.asExecutor().execute(closeTask)
                        },
                    )
                    withContext(NonCancellable) {
                        writeWatchdogJob?.cancel()
                        ackWriterJob?.cancel()
                        helloWriterJob?.cancel()
                        withTimeoutOrNull(1_000L) {
                            writeWatchdogJob?.join()
                        }
                        withTimeoutOrNull(1_000L) {
                            ackWriterJob?.join()
                        }
                        if (helloWriteCompleted) {
                            withTimeoutOrNull(1_000L) {
                                helloWriterJob?.join()
                            }
                        }
                    }

                    sessionHandle.audioEngine?.release()
                    sessionAudioEngine.release()
                    sessionHandle.audioEngine = null
                    cameraStreamManager?.stopStream(sessionHandle.sessionId)

                    val isActiveCoroutine = currentCoroutineContext().isActive
                    val transition = synchronized(lifecycleLock) {
                        val outcome = computePostSessionTransition(
                            isStopping = isStopping.get(),
                            isActiveCoroutine = isActiveCoroutine,
                            hasActiveServerSocket = serverSocket?.isClosed == false,
                            serverBindError = serverBindError,
                        )
                        val refreshedIp = if (outcome == PostSessionStatus.LISTENING) {
                            LocalIpResolver.resolveLocalIpv4Address(this)
                        } else {
                            null
                        }
                        val listenPort = _serviceState.value.listenPort
                        val ownedAndUpdated = applyPostSessionStateIfOwned(
                            callerInstance = this,
                            sessionId = sessionId,
                            outcome = outcome,
                            listenPort = listenPort,
                            refreshedIp = refreshedIp,
                            serverBindError = serverBindError,
                            serviceState = _serviceState,
                        )
                        if (ownedAndUpdated) {
                            when (outcome) {
                                PostSessionStatus.LISTENING -> {
                                    reconcileMdnsForListening(
                                        refreshedIp = refreshedIp,
                                        listenPort = listenPort,
                                        onRegisterMdns = { port, ip ->
                                            mdnsAdvertiser?.register(
                                                deviceName = resolveDeviceName(),
                                                port = port,
                                                localIpAddress = ip,
                                            )
                                        },
                                        onUnregisterMdns = { mdnsAdvertiser?.unregister() },
                                    )
                                    updateNotification(buildListeningNotificationText(refreshedIp, listenPort))
                                }
                                PostSessionStatus.ERROR -> {
                                    val err = serverBindError ?: "Server error"
                                    updateNotification("Error: $err")
                                }
                                PostSessionStatus.STOPPED -> {
                                    if (!isStopping.get()) {
                                        updateNotification("Standby • Ready to connect")
                                    }
                                }
                            }
                        }
                        outcome
                    }
                    Log.i(TAG, "Client disconnected ($clientLabel), transitioned status to $transition")
                }
            }
        } finally {
            if (!enteredMutex) {
                closeTransportBeforeStreams(closeTransport, inputStream, outputStream)
            }
        }
    }

    /**
     * Reads and dispatches incoming control and heartbeat packets from the PC receiver.
     */
    private fun runPacketReader(
        sessionHandle: ClientSessionHandle,
        input: DataInputStream,
        clientLabel: String,
    ) {
        while (serviceScope.isActive && !isStopping.get()) {
            val packet = try {
                CcftPacket.readPacket(input)
            } catch (e: EOFException) {
                Log.i(TAG, "Client closed connection (EOF)")
                break
            } catch (e: IOException) {
                Log.i(TAG, "Client connection read error: ${e.message}")
                break
            }

            when (packet.header.packetType) {
                PacketType.HEARTBEAT_PING -> {
                    // Respond immediately with PONG mirroring ptsUs
                    val pong = CcftPacket.heartbeatPong(packet.header.ptsUs)
                    synchronized(sessionHandle.outputLock) {
                        sessionHandle.withTrackedWrite {
                            pong.encodeTo(sessionHandle.output, flush = true)
                        }
                    }
                }
                PacketType.CONTROL_CMD -> {
                    if (!handleControlCommand(packet, sessionHandle, clientLabel)) {
                        break
                    }
                }
                PacketType.HEARTBEAT_PONG -> {
                    // Handled if bidirectional ping is ever enabled
                }
                else -> {
                    Log.d(TAG, "Unhandled packet type received: ${packet.header.packetType}")
                }
            }
        }
    }

    /**
     * Decodes and applies control commands from PC, then replies with CONTROL_ACK (0x12).
     * Returns `false` if a transport write failure closed the session and the reader loop should stop.
     */
    private fun handleControlCommand(
        packet: CcftPacket,
        sessionHandle: ClientSessionHandle,
        clientLabel: String,
    ): Boolean {
        try {
            val cmd = packet.decodeJson<ControlCmd>()
            Log.i(TAG, "Received CONTROL_CMD: $cmd")

            when (cmd) {
                is ControlCmd.ConfigureStream -> {
                    val cameras = _serviceState.value.cameras.ifEmpty {
                        CameraEnumerator.enumerateCameras(this)
                    }
                    val targetCam = cameras.find { it.id == cmd.cameraId }
                    if (targetCam == null) {
                        enqueueUnknownCameraFailureAck(
                            coordinator = controlCoordinator,
                            expectedSessionId = sessionHandle.sessionId,
                            cameras = cameras,
                            cameraId = cmd.cameraId,
                        )
                        return true
                    }

                    controlCoordinator.updateIfSessionOwned(
                        expectedSessionId = sessionHandle.sessionId,
                        camerasOverride = cameras,
                        shouldAdvanceCameraConfig = { previous, next ->
                            isCameraGeometryChanged(previous, next) ||
                                cameraStreamManager?.willRestartPipelineForRebind(
                                    sessionId = sessionHandle.sessionId,
                                    cameraId = next.cameraId,
                                    width = next.width,
                                    height = next.height,
                                    fps = next.fps,
                                ) == true
                        },
                        onCommittedLocked = { next, seq, configId ->
                            cameraStreamManager?.rebindCamera(
                                sessionId = sessionHandle.sessionId,
                                configSeq = seq,
                                cameraConfigId = configId,
                                cameraId = next.cameraId,
                                width = next.width,
                                height = next.height,
                                fps = next.fps,
                                bitrateKbps = next.bitrateKbps,
                                zoomRatio = next.zoomRatio,
                                exposureEv = next.exposureEv,
                                torchOn = next.torchOn,
                            )
                        },
                    ) { cur ->
                        applyConfigureStreamPatch(cur, cmd, targetCam)
                    } ?: return true
                    publishConnectedNotificationFromCurrentState(expectedSessionId = sessionHandle.sessionId)
                }
                is ControlCmd.SetCameraParams -> {
                    controlCoordinator.updateIfSessionOwned(
                        expectedSessionId = sessionHandle.sessionId,
                        advanceCameraConfigOnGeometryChange = false,
                        onCommittedLocked = { next, _, _ ->
                            activeTransformFlags = computeTransformFlags(
                                mirrorHorizontal = next.mirrorHorizontal,
                                rotationDeg = next.rotationDeg,
                            )
                            cmd.zoomRatio?.let { cameraStreamManager?.setZoomRatio(next.zoomRatio) }
                            cmd.exposureEv?.let { cameraStreamManager?.setExposureEv(next.exposureEv) }
                            cmd.torchOn?.let { cameraStreamManager?.setTorch(next.torchOn) }
                            cmd.autofocus?.let { cameraStreamManager?.setAutoFocus(next.autofocus) }
                        },
                    ) { cur ->
                        applyCameraParamsPatch(cur, cmd)
                    } ?: return true
                }
                is ControlCmd.SetAudioState -> {
                    if (cmd.enabled) {
                        val capturedSessionId = sessionHandle.sessionId
                        val engine = sessionHandle.audioEngine ?: AudioStreamEngine(
                            applicationContext ?: this,
                        ) { packetType, ptsUs, payload, length ->
                            handleAudioPacketFromEngine(capturedSessionId, packetType, ptsUs, payload, length)
                        }
                        val engineActive = engine.isActive || engine.start(initiallyMuted = true)
                        if (!engineActive) {
                            controlCoordinator.updateIfSessionOwned(
                                expectedSessionId = sessionHandle.sessionId,
                                advanceCameraConfigOnGeometryChange = false,
                                ackBuilder = { fallbackState ->
                                    ControlAck(
                                        ok = false,
                                        error = "Microphone capture unavailable (grant RECORD_AUDIO permission on Android)",
                                        activeState = fallbackState,
                                    )
                                },
                                onCommittedLocked = { next, _, _ ->
                                    sessionHandle.audioEngine?.setMuted(next.audioMuted)
                                },
                                mutate = { cur ->
                                    applyAudioStatePatch(cur, enabled = false, muted = cmd.muted)
                                },
                            )
                            return true
                        }
                        val updatedAudio = controlCoordinator.updateIfSessionOwned(
                            expectedSessionId = sessionHandle.sessionId,
                            advanceCameraConfigOnGeometryChange = false,
                            onCommittedLocked = { next, _, _ ->
                                if (!isStopping.get()) {
                                    sessionHandle.audioEngine = engine
                                    engine.setMuted(next.audioMuted)
                                }
                            },
                        ) { cur ->
                            applyAudioStatePatch(cur, enabled = true, muted = cmd.muted)
                        }
                        if (updatedAudio == null || isStopping.get() || activeSession !== sessionHandle) {
                            engine.stop()
                        }
                    } else {
                        sessionHandle.audioEngine?.stop()
                        controlCoordinator.updateIfSessionOwned(
                            expectedSessionId = sessionHandle.sessionId,
                            advanceCameraConfigOnGeometryChange = false,
                            onCommittedLocked = { next, _, _ ->
                                sessionHandle.audioEngine?.setMuted(next.audioMuted)
                            },
                        ) { cur ->
                            applyAudioStatePatch(cur, enabled = false, muted = cmd.muted)
                        }
                    }
                }
                is ControlCmd.RequestKeyframe -> {
                    return handleRequestKeyframeCommand(
                        session = sessionHandle,
                        cachedConfigProvider = {
                            cameraStreamManager?.cachedConfigForSession(sessionHandle.sessionId)
                        },
                        clearActiveSessionIfCurrent = { failedSession ->
                            synchronized(lifecycleLock) {
                                if (activeSession === failedSession) {
                                    activeSession = null
                                }
                                controlCoordinator.clearActiveSessionIfCurrent(failedSession)
                            }
                        },
                        requestKeyFrame = {
                            cameraStreamManager?.requestKeyFrame(sessionHandle.sessionId)
                        },
                        enqueueSuccessAck = {
                            controlCoordinator.enqueueAckIfSessionOwned(
                                expectedSessionId = sessionHandle.sessionId,
                            ) { cur ->
                                ControlAck.success(cur)
                            }
                        },
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse or apply CONTROL_CMD", e)
            sessionHandle.enqueueAck(
                ControlAck.failure(e.message ?: "Unknown control error"),
            )
        }
        return true
    }

    fun switchCameraFromUi() {
        val cameras = _serviceState.value.cameras.ifEmpty { CameraEnumerator.enumerateCameras(this) }
        if (cameras.isEmpty()) return

        var targetSessionId = 0L
        controlCoordinator.updateWithCustomAckAndConfig(
            camerasOverride = cameras,
            ackBuilder = { next -> ControlAck.success(next) },
            onCommittedLocked = { next, seq, configId ->
                targetSessionId = controlCoordinator.currentActiveSessionId()
                cameraStreamManager?.rebindCamera(
                    sessionId = targetSessionId,
                    configSeq = seq,
                    cameraConfigId = configId,
                    cameraId = next.cameraId,
                    width = next.width,
                    height = next.height,
                    fps = next.fps,
                    bitrateKbps = next.bitrateKbps,
                    zoomRatio = next.zoomRatio,
                    exposureEv = next.exposureEv,
                    torchOn = next.torchOn,
                )
            },
        ) { cur ->
            val currentIdx = cameras.indexOfFirst { it.id == cur.cameraId }
            val nextCam = cameras[(currentIdx + 1) % cameras.size]
            clampActiveStateForCamera(cur.copy(cameraId = nextCam.id), nextCam)
        }
        if (targetSessionId != 0L) {
            publishConnectedNotificationFromCurrentState(expectedSessionId = targetSessionId)
        }
    }

    fun toggleTorchFromUi() {
        var appliedTorch: Boolean? = null
        controlCoordinator.updateWithCustomAck(
            ackBuilder = { next ->
                if (appliedTorch != null) ControlAck.success(next) else null
            },
            onCommittedLocked = { next, _ ->
                if (appliedTorch != null) {
                    cameraStreamManager?.setTorch(next.torchOn)
                }
            },
            mutate = { cur ->
                val activeCam = _serviceState.value.cameras.find { it.id == cur.cameraId }
                if (activeCam != null && !activeCam.hasFlash) {
                    appliedTorch = null
                    cur
                } else {
                    val nextTorch = !cur.torchOn
                    appliedTorch = nextTorch
                    cur.copy(torchOn = nextTorch)
                }
            },
        )
    }

    fun toggleMuteFromUi() {
        controlCoordinator.updateWithCustomAckAndConfig(
            ackBuilder = { next -> ControlAck.success(next) },
            onCommittedLocked = { next, _, _ ->
                controlCoordinator.currentOwnedSession()?.audioEngine?.setMuted(next.audioMuted)
            },
        ) { cur ->
            cur.copy(audioMuted = !cur.audioMuted)
        }
    }

    private fun stopServer() {
        val capturedTransports = synchronized(lifecycleLock) {
            stopServerLocked()
        }
        dispatchCapturedStopTransports(capturedTransports)
    }

    private fun stopServerLocked(): CapturedStopTransports {
        isStopping.set(true)
        mdnsAdvertiser?.unregister()
        val sessionToClose = activeSession
        activeSession = null
        controlCoordinator.setActiveSession(null)
        val detachedUsb = usbAttemptTracker.detachActiveAttemptForClose()
        val socketsToClose = activeTcpSockets.toList()
        activeTcpSockets.clear()
        val listenerToClose = serverSocket
        serverSocket = null
        val listenerCompletion = if (listenerToClose != null) {
            pendingListenerCloseTracker.register()
        } else {
            null
        }
        usbJob?.cancel()
        usbJob = null
        serverJob?.cancel()
        serverJob = null
        return CapturedStopTransports(
            session = sessionToClose,
            tcpSockets = socketsToClose,
            serverSocket = listenerToClose,
            usbCloseAction = detachedUsb?.let { detached ->
                { usbAttemptTracker.closeDetachedDescriptor(detached) }
            },
            listenerCompletion = listenerCompletion,
            usbCompletion = detachedUsb?.completion,
        )
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "CamCraft Streaming",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows live streaming status for CamCraft USB/Wi-Fi connection"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CamCraft Active")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_stat_camcraft)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.notify(NOTIFICATION_ID, buildNotification(contentText))
    }

    companion object {
        const val TAG = "CamCraftStreamService"
        const val CHANNEL_ID = "camcraft_stream_channel"
        const val NOTIFICATION_ID = 9876
        private const val CLIENT_READ_TIMEOUT_MS = 10_000
        internal const val CONTROL_ACK_QUEUE_CAPACITY = 64
        internal const val WRITE_STALL_TIMEOUT_MS = 2_500L
        private const val WRITE_WATCHDOG_POLL_MS = 500L
        internal const val USB_RETIRE_WAIT_TIMEOUT_MS = 2_000L
        internal const val LISTENER_RETIRE_WAIT_TIMEOUT_MS = 1_000L
        internal const val MAX_LISTENER_BIND_RETRIES = 3

        const val ACTION_START = "org.artcraft.camcraft.action.START"
        const val ACTION_START_USB_ACCESSORY = "org.artcraft.camcraft.action.START_USB_ACCESSORY"
        const val ACTION_STOP = "org.artcraft.camcraft.action.STOP"
        const val USB_CLIENT_LABEL = "USB Direct"

        private val nextSessionId = AtomicLong(1L)
        private val stateOwnershipLock = Any()
        private val pendingListenerCloseTracker = PendingCloseTracker()
        private val pendingUsbCloseTracker = PendingCloseTracker()

        @Volatile
        private var activeStateOwnerSessionId: Long = 0L

        @Volatile
        private var activeInstance: CamCraftStreamService? = null
        private var pendingPreviewView: WeakReference<PreviewView>? = null

        private val _serviceState = MutableStateFlow(StreamServiceState())
        val serviceState: StateFlow<StreamServiceState> = _serviceState.asStateFlow()

        private fun logWarnSafe(message: String) {
            try {
                Log.w(TAG, message)
            } catch (_: RuntimeException) {
                // Allow companion/helper unit tests to run on JVM without mocked android.util.Log.
            }
        }

        /**
         * Resolves the current local Wi-Fi/LAN IPv4 address and updates [serviceState]
         * so the UI displays the device endpoint before the server starts.
         */
        fun refreshLocalIp(context: Context) {
            val resolvedIp = LocalIpResolver.resolveLocalIpv4Address(context)
            _serviceState.update { state ->
                if (state.localIpAddress == resolvedIp) {
                    state
                } else {
                    state.copy(localIpAddress = resolvedIp)
                }
            }
        }

        /**
         * Resolves a human-readable device model string for CCFT handshake and mDNS advertisement.
         */
        fun resolveDeviceName(): String {
            val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
            val model = Build.MODEL ?: "CamCraft Device"
            val deviceName = if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model".trim()
            }
            return deviceName.ifBlank { "Android CamCraft Device" }
        }

        fun attachPreviewView(previewView: PreviewView) {
            if (pendingPreviewView?.get() === previewView) {
                activeInstance?.cameraStreamManager?.attachPreviewView(previewView)
                return
            }
            pendingPreviewView = WeakReference(previewView)
            activeInstance?.cameraStreamManager?.attachPreviewView(previewView)
        }

        fun detachPreviewView() {
            val hadPending = pendingPreviewView != null
            pendingPreviewView = null
            val manager = activeInstance?.cameraStreamManager
            if (manager != null) {
                manager.detachPreviewView()
            } else if (!hadPending) {
                return
            }
        }

        fun focusAtPoint(point: MeteringPoint) {
            activeInstance?.cameraStreamManager?.focusAtPoint(point)
        }

        /**
         * Computes CCFT `VIDEO_FRAME` header transform flags matching `docs/PROTOCOL.md` §4.
         */
        fun computeTransformFlags(mirrorHorizontal: Boolean, rotationDeg: Int): Int {
            val rotationFlag = CcftFlags.flagsForRotation(rotationDeg)
            val mirrorFlag = if (mirrorHorizontal) CcftFlags.MIRROR_H else CcftFlags.NONE
            return rotationFlag or mirrorFlag
        }

        /**
         * Builds the foreground notification text when connected to a PC client.
         */
        fun buildConnectedNotificationText(
            clientIp: String,
            activeState: ActiveCameraState,
        ): String =
            "Connected ($clientIp) • ${activeState.width}x${activeState.height} @ ${activeState.fps} FPS"

        internal fun buildListeningNotificationText(ip: String?, port: Int): String =
            if (!ip.isNullOrBlank()) {
                "Wi-Fi $ip:$port • Waiting for PC..."
            } else {
                "Port $port • Waiting for PC..."
            }

        fun switchCamera() {
            activeInstance?.switchCameraFromUi()
        }

        fun toggleTorch() {
            activeInstance?.toggleTorchFromUi()
        }

        fun toggleMute() {
            activeInstance?.toggleMuteFromUi()
        }

        fun start(context: Context) {
            val intent = Intent(context, CamCraftStreamService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun startUsbAccessory(context: Context, accessory: UsbAccessory) {
            val intent = Intent(context, CamCraftStreamService::class.java).apply {
                action = ACTION_START_USB_ACCESSORY
                putExtra(UsbManager.EXTRA_ACCESSORY, accessory)
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, CamCraftStreamService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Builds device metadata matching `docs/PROTOCOL.md` §5.1.
         */
        fun createDeviceHello(activeState: ActiveCameraState, context: Context? = null): ControlHello {
            val cameras = CameraEnumerator.enumerateCameras(context)
            return createDeviceHello(activeState, cameras)
        }

        internal fun createDeviceHello(
            activeState: ActiveCameraState,
            cameras: List<CameraInfo>,
        ): ControlHello = ControlHello(
            deviceName = resolveDeviceName(),
            protocolVersion = CcftProtocolConstants.VERSION.toInt(),
            cameras = cameras,
            activeState = activeState,
        )

        internal enum class PostSessionStatus {
            LISTENING,
            ERROR,
            STOPPED,
        }

        internal enum class WifiIpChangeAction {
            REGISTER_MDNS,
            UNREGISTER_MDNS,
            IGNORE,
        }

        internal data class CapturedStopTransports(
            val session: ClientSessionHandle?,
            val tcpSockets: List<Closeable>,
            val serverSocket: Closeable?,
            val usbCloseAction: (() -> Unit)? = null,
            val listenerCompletion: CompletableDeferred<Unit>? = null,
            val usbCompletion: CompletableDeferred<Unit>? = null,
        ) {
            fun hasTargets(): Boolean =
                session != null ||
                    tcpSockets.isNotEmpty() ||
                    serverSocket != null ||
                    usbCloseAction != null

            fun closeAll() {
                try {
                    try {
                        try {
                            serverSocket?.close()
                        } catch (_: IOException) {}
                    } finally {
                        listenerCompletion?.complete(Unit)
                    }
                    try {
                        usbCloseAction?.invoke()
                    } finally {
                        usbCompletion?.complete(Unit)
                    }
                    try {
                        session?.close()
                    } catch (_: Throwable) {}
                    for (socket in tcpSockets) {
                        try {
                            socket.close()
                        } catch (_: IOException) {}
                    }
                } finally {
                    listenerCompletion?.complete(Unit)
                    usbCompletion?.complete(Unit)
                }
            }
        }

        internal fun dispatchCapturedStopTransports(
            captured: CapturedStopTransports,
            completion: CompletableDeferred<Unit>? = null,
            dispatchIo: (() -> Unit) -> Unit = { task ->
                Dispatchers.IO.asExecutor().execute(task)
            },
        ) {
            if (!captured.hasTargets()) {
                captured.listenerCompletion?.complete(Unit)
                captured.usbCompletion?.complete(Unit)
                completion?.complete(Unit)
                return
            }
            dispatchIo {
                try {
                    captured.closeAll()
                } finally {
                    captured.listenerCompletion?.complete(Unit)
                    captured.usbCompletion?.complete(Unit)
                    completion?.complete(Unit)
                }
            }
        }

        internal suspend fun awaitPendingUsbDescriptorRetirement(
            usbCloseTracker: PendingCloseTracker,
            timeoutMs: Long = USB_RETIRE_WAIT_TIMEOUT_MS,
            onPendingCloseObserved: (() -> Unit)? = null,
        ): Boolean {
            val (initialPending, _) = usbCloseTracker.snapshot()
            if (initialPending.isEmpty()) {
                return true
            }
            var notified = false
            return withTimeoutOrNull(timeoutMs) {
                while (true) {
                    val (pending, _) = usbCloseTracker.snapshot()
                    if (pending.isEmpty()) {
                        break
                    }
                    if (!notified) {
                        notified = true
                        onPendingCloseObserved?.invoke()
                    }
                    for (deferred in pending) {
                        deferred.await()
                    }
                }
                true
            } ?: false
        }

        internal suspend fun <T : Any> openUsbAccessoryAfterRetirement(
            usbCloseTracker: PendingCloseTracker,
            attemptTracker: UsbSessionAttemptTracker<T>,
            attempt: UsbSessionAttempt<T>,
            isCancelledOrStopped: () -> Boolean,
            retireWaitTimeoutMs: Long = USB_RETIRE_WAIT_TIMEOUT_MS,
            onPendingCloseObserved: (() -> Unit)? = null,
            onRetirementTimeout: ((Int) -> Unit)? = null,
            openAccessory: () -> T?,
        ): T? {
            var timeoutCount = 0
            while (true) {
                if (isCancelledOrStopped() ||
                    !currentCoroutineContext().isActive ||
                    !attemptTracker.isAttemptPendingOrActive(attempt)
                ) {
                    return null
                }
                val retired = awaitPendingUsbDescriptorRetirement(
                    usbCloseTracker = usbCloseTracker,
                    timeoutMs = retireWaitTimeoutMs,
                    onPendingCloseObserved = onPendingCloseObserved,
                )
                if (isCancelledOrStopped() ||
                    !currentCoroutineContext().isActive ||
                    !attemptTracker.isAttemptPendingOrActive(attempt)
                ) {
                    return null
                }
                if (retired) {
                    return openAccessory()
                }
                timeoutCount++
                onRetirementTimeout?.invoke(timeoutCount)
            }
        }

        internal fun <T : Any> isUsbBlockingTcpAdmission(
            usbJobActive: Boolean,
            attemptTracker: UsbSessionAttemptTracker<T>,
        ): Boolean = usbJobActive && attemptTracker.hasActiveDescriptor()

        internal fun <T : Any, S : Closeable> attachUsbDescriptorAndPreemptTcp(
            lifecycleLock: Any,
            attemptTracker: UsbSessionAttemptTracker<T>,
            attempt: UsbSessionAttempt<T>,
            descriptor: T,
            isCancelledOrStopped: () -> Boolean,
            activeTcpSockets: MutableSet<S>,
            detachActiveTcpSessionLocked: () -> ClientSessionHandle? = { null },
            closeSocket: (S) -> Unit = { socket ->
                try {
                    socket.close()
                } catch (_: IOException) {}
            },
        ): Boolean {
            var sessionToPreempt: ClientSessionHandle? = null
            var socketsToPreempt: List<S> = emptyList()
            var rejectedUsbToClose: DetachedUsbDescriptor<T>? = null
            val committed = synchronized(lifecycleLock) {
                if (isCancelledOrStopped()) {
                    rejectedUsbToClose = attemptTracker.rejectUnattachedDescriptorForClose(attempt, descriptor)
                    false
                } else {
                    val (attached, rejected) = attemptTracker.attachDescriptorOrDetachRejected(attempt, descriptor)
                    if (!attached) {
                        rejectedUsbToClose = rejected
                        false
                    } else {
                        sessionToPreempt = detachActiveTcpSessionLocked()
                        socketsToPreempt = activeTcpSockets.toList()
                        activeTcpSockets.clear()
                        true
                    }
                }
            }
            if (!committed) {
                rejectedUsbToClose?.let(attemptTracker::closeDetachedDescriptor)
                return false
            }
            try {
                sessionToPreempt?.close()
            } catch (_: Throwable) {}
            for (socket in socketsToPreempt) {
                closeSocket(socket)
            }
            return true
        }

        internal suspend fun <S : Closeable> bindServerSocketWithRetiringListenerRetry(
            listenerCloseTracker: PendingCloseTracker,
            isCancelledOrStopped: () -> Boolean,
            maxRetryAttempts: Int = MAX_LISTENER_BIND_RETRIES,
            retryWaitTimeoutMs: Long = LISTENER_RETIRE_WAIT_TIMEOUT_MS,
            ownPendingToken: CompletableDeferred<Unit>? = null,
            onPreBindWaitEntered: (() -> Unit)? = null,
            bindSocket: () -> S,
        ): S {
            var attempt = 0
            while (true) {
                val (pendingBeforeBind, _) = listenerCloseTracker.snapshot(exclude = ownPendingToken)
                if (pendingBeforeBind.isNotEmpty()) {
                    withTimeoutOrNull(retryWaitTimeoutMs) {
                        onPreBindWaitEntered?.invoke()
                        for (deferred in pendingBeforeBind) {
                            deferred.await()
                        }
                    }
                }
                if (isCancelledOrStopped()) {
                    throw CancellationException("Server start cancelled while waiting for retiring listener")
                }
                val completionsAtBind = listenerCloseTracker.currentCompletionsCount()
                try {
                    return bindSocket()
                } catch (e: BindException) {
                    if (isCancelledOrStopped()) {
                        throw CancellationException("Server start cancelled while retrying listener bind")
                    }
                    val (pendingAfterBind, completionsAfterBind) =
                        listenerCloseTracker.snapshot(exclude = ownPendingToken)
                    val retiringListenerActiveOrJustClosed =
                        pendingAfterBind.isNotEmpty() || completionsAfterBind > completionsAtBind
                    if (!retiringListenerActiveOrJustClosed || attempt >= maxRetryAttempts) {
                        throw e
                    }
                    attempt++
                    if (pendingAfterBind.isNotEmpty()) {
                        withTimeoutOrNull(retryWaitTimeoutMs) {
                            for (deferred in pendingAfterBind) {
                                deferred.await()
                            }
                        }
                    }
                    if (isCancelledOrStopped()) {
                        throw CancellationException("Server start cancelled while retrying listener bind")
                    }
                }
            }
        }

        internal suspend fun <S : Closeable> bindAndPublishListenerSocket(
            listenerTracker: PendingCloseTracker,
            isCancelledOrStopped: () -> Boolean,
            maxRetryAttempts: Int = MAX_LISTENER_BIND_RETRIES,
            retryWaitTimeoutMs: Long = LISTENER_RETIRE_WAIT_TIMEOUT_MS,
            onPreBindWaitEntered: (() -> Unit)? = null,
            bindSocket: () -> S,
            publishSocket: (S) -> Boolean,
            closeSocket: (S) -> Unit = { candidate ->
                try {
                    candidate.close()
                } catch (_: IOException) {}
            },
        ): S? {
            if (isCancelledOrStopped()) {
                return null
            }
            val startupToken = listenerTracker.register()
            var boundSocket: S? = null
            var published = false
            try {
                val candidate = bindServerSocketWithRetiringListenerRetry(
                    listenerCloseTracker = listenerTracker,
                    isCancelledOrStopped = isCancelledOrStopped,
                    maxRetryAttempts = maxRetryAttempts,
                    retryWaitTimeoutMs = retryWaitTimeoutMs,
                    ownPendingToken = startupToken,
                    onPreBindWaitEntered = onPreBindWaitEntered,
                    bindSocket = {
                        bindSocket().also { boundSocket = it }
                    },
                )
                published = publishSocket(candidate)
                return if (published) candidate else null
            } finally {
                val unpublishedSocket = if (!published) boundSocket else null
                try {
                    if (unpublishedSocket != null) {
                        closeSocket(unpublishedSocket)
                    }
                } finally {
                    startupToken.complete(Unit)
                }
            }
        }

        internal fun sendStreamPacketForSession(
            activeSessionProvider: () -> ClientSessionHandle?,
            clearActiveSessionIfCurrent: (ClientSessionHandle) -> Unit,
            sessionId: Long,
            packetType: PacketType,
            flags: Int,
            ptsUs: Long,
            payload: ByteArray,
            offset: Int,
            length: Int,
            tagLabel: String,
            canWriteLocked: () -> Boolean = { true },
            onWriteError: (IOException) -> Unit = { e ->
                logWarnSafe("Failed writing $tagLabel packet to client (session=$sessionId): ${e.message}")
            },
        ): Boolean {
            val session = resolveActiveSessionForPacket(activeSessionProvider(), sessionId) ?: return false
            return try {
                synchronized(session.outputLock) {
                    if (resolveActiveSessionForPacket(activeSessionProvider(), sessionId) !== session ||
                        session.isClosed ||
                        !canWriteLocked()
                    ) {
                        false
                    } else {
                        session.withTrackedWrite {
                            CcftPacket.writePacket(
                                output = session.output,
                                type = packetType,
                                flags = flags,
                                ptsUs = ptsUs,
                                payload = payload,
                                offset = offset,
                                length = length,
                                flush = true,
                            )
                        }
                        true
                    }
                }
            } catch (e: IOException) {
                onWriteError(e)
                clearActiveSessionIfCurrent(session)
                session.close()
                false
            }
        }

        internal fun handleRequestKeyframeCommand(
            session: ClientSessionHandle,
            cachedConfigProvider: () -> ByteArray?,
            clearActiveSessionIfCurrent: (ClientSessionHandle) -> Unit,
            requestKeyFrame: () -> Unit,
            enqueueSuccessAck: () -> Unit,
            ptsUsProvider: () -> Long = { System.nanoTime() / 1_000L },
            onWriteError: (IOException) -> Unit = { e ->
                logWarnSafe(
                    "Failed writing cached VIDEO_CONFIG for RequestKeyframe (session=${session.sessionId}): ${e.message}",
                )
            },
        ): Boolean {
            try {
                synchronized(session.outputLock) {
                    if (session.isClosed) {
                        return false
                    }
                    cachedConfigProvider()?.let { spsPps ->
                        session.withTrackedWrite {
                            CcftPacket.writePacket(
                                output = session.output,
                                type = PacketType.VIDEO_CONFIG,
                                flags = CcftFlags.NONE,
                                ptsUs = ptsUsProvider(),
                                payload = spsPps,
                                offset = 0,
                                length = spsPps.size,
                                flush = true,
                            )
                        }
                    }
                }
            } catch (e: IOException) {
                onWriteError(e)
                clearActiveSessionIfCurrent(session)
                session.close()
                return false
            }
            requestKeyFrame()
            enqueueSuccessAck()
            return true
        }

        internal fun enqueueUnknownCameraFailureAck(
            coordinator: ControlStateAckCoordinator,
            expectedSessionId: Long,
            cameras: List<CameraInfo>,
            cameraId: String,
        ): Boolean = coordinator.enqueueAckIfSessionOwned(
            expectedSessionId = expectedSessionId,
        ) { cur ->
            validateConfigureStreamCamera(
                cameras = cameras,
                cameraId = cameraId,
                currentState = cur,
            ).second ?: ControlAck(
                ok = false,
                error = "Unknown camera_id '$cameraId'",
                activeState = cur,
            )
        }

        internal fun reconcileMdnsForListening(
            refreshedIp: String?,
            listenPort: Int,
            onRegisterMdns: (port: Int, ip: String) -> Unit,
            onUnregisterMdns: () -> Unit,
        ) {
            val normalizedIp = refreshedIp?.trim()
            if (!normalizedIp.isNullOrEmpty()) {
                onRegisterMdns(listenPort, normalizedIp)
            } else {
                onUnregisterMdns()
            }
        }

        internal fun resolveActiveSessionForPacket(
            activeSession: ClientSessionHandle?,
            packetSessionId: Long,
        ): ClientSessionHandle? =
            if (packetSessionId == 0L) null else activeSession?.takeIf { it.sessionId == packetSessionId }

        internal fun closeTransportBeforeStreams(
            closeTransport: () -> Unit,
            inputStream: Closeable?,
            outputStream: Closeable?,
            deferOutputClose: Boolean = false,
            dispatchDeferredClose: (() -> Unit) -> Unit = { it() },
        ) {
            try {
                closeTransport()
            } catch (_: Throwable) {}
            try {
                inputStream?.close()
            } catch (_: IOException) {}
            if (outputStream != null) {
                if (deferOutputClose) {
                    dispatchDeferredClose {
                        try {
                            outputStream.close()
                        } catch (_: IOException) {}
                    }
                } else {
                    try {
                        outputStream.close()
                    } catch (_: IOException) {}
                }
            }
        }

        internal suspend fun writeInitialHelloCancellable(
            scope: CoroutineScope,
            session: ClientSessionHandle,
            writeHello: () -> Unit,
        ): Job {
            val helloResult = CompletableDeferred<Result<Unit>>()
            val writerJob = scope.launch {
                val outcome = try {
                    synchronized(session.outputLock) {
                        if (session.isClosed) {
                            throw IOException("Session ${session.sessionId} closed before CONTROL_HELLO write")
                        }
                        session.withTrackedWrite {
                            writeHello()
                        }
                    }
                    Result.success(Unit)
                } catch (e: Throwable) {
                    if (e is CancellationException) throw e
                    Result.failure(e)
                }
                helloResult.complete(outcome)
            }
            writerJob.invokeOnCompletion { cause ->
                if (cause != null && !helloResult.isCompleted) {
                    helloResult.complete(Result.failure(cause))
                }
            }
            val outcome = try {
                select<Result<Unit>> {
                    helloResult.onAwait { it }
                    session.closedDeferred.onAwait {
                        Result.failure(IOException("Session ${session.sessionId} closed while writing CONTROL_HELLO"))
                    }
                }
            } catch (e: CancellationException) {
                writerJob.cancel()
                throw e
            }
            if (outcome.isFailure) {
                writerJob.cancel()
                throw outcome.exceptionOrNull() ?: IOException("CONTROL_HELLO write failed")
            }
            if (session.isClosed) {
                writerJob.cancel()
                throw IOException("Session ${session.sessionId} closed after CONTROL_HELLO write")
            }
            return writerJob
        }

        internal fun commitEncoderResolutionAdjusted(
            coordinator: ControlStateAckCoordinator,
            expectedSessionId: Long,
            expectedCameraConfigId: Long,
            expectedCameraId: String,
            width: Int,
            height: Int,
            isStopping: () -> Boolean,
            commitNegotiatedDimensions: () -> Boolean,
        ): ActiveCameraState? {
            if (isStopping()) return null
            return coordinator.updateIfSessionOwned(
                expectedSessionId = expectedSessionId,
                expectedCameraConfigId = expectedCameraConfigId,
                expectedCameraId = expectedCameraId,
                advanceCameraConfigOnGeometryChange = false,
                shouldCommitLocked = {
                    !isStopping() && commitNegotiatedDimensions()
                },
                ackBuilder = { next ->
                    if (!isStopping()) ControlAck.success(next) else null
                },
                mutate = { cur ->
                    if (cur.width == width && cur.height == height) {
                        cur
                    } else {
                        cur.copy(width = width, height = height)
                    }
                },
            )
        }

        internal fun validateConfigureStreamCamera(
            cameras: List<CameraInfo>,
            cameraId: String,
            currentState: ActiveCameraState,
        ): Pair<CameraInfo?, ControlAck?> {
            val targetCam = cameras.find { it.id == cameraId }
            if (targetCam == null) {
                return null to ControlAck(
                    ok = false,
                    error = "Unknown camera_id '$cameraId'",
                    activeState = currentState,
                )
            }
            return targetCam to null
        }

        internal fun shutdownWifiAndMdnsUnderLock(
            lock: Any,
            setStopping: () -> Unit,
            stopWifiMonitor: () -> Unit,
            stopServerAndTransport: () -> Unit = {},
            resetServiceState: () -> Unit,
            unregisterMdns: () -> Unit,
        ) {
            synchronized(lock) {
                setStopping()
                stopWifiMonitor()
                stopServerAndTransport()
                resetServiceState()
                unregisterMdns()
            }
        }

        internal fun handleWifiIpChangeUnderLock(
            lock: Any,
            isStopping: () -> Boolean,
            serviceState: MutableStateFlow<StreamServiceState>,
            updatedIp: String?,
            onRegisterMdns: (port: Int, ip: String?) -> Unit,
            onUnregisterMdns: () -> Unit,
            onUpdateNotification: (ip: String?, port: Int) -> Unit,
        ) {
            synchronized(lock) {
                if (isStopping()) return
                val previousState = serviceState.getAndUpdate { it.copy(localIpAddress = updatedIp) }
                val currentState = serviceState.value
                when (
                    decideWifiIpChangeAction(
                        isStopping = isStopping(),
                        status = currentState.status,
                        previousIp = previousState.localIpAddress,
                        updatedIp = updatedIp,
                    )
                ) {
                    WifiIpChangeAction.REGISTER_MDNS -> {
                        onRegisterMdns(currentState.listenPort, updatedIp)
                        onUpdateNotification(updatedIp, currentState.listenPort)
                    }
                    WifiIpChangeAction.UNREGISTER_MDNS -> {
                        onUnregisterMdns()
                        onUpdateNotification(updatedIp, currentState.listenPort)
                    }
                    WifiIpChangeAction.IGNORE -> Unit
                }
            }
        }

        internal fun computePostSessionTransition(
            isStopping: Boolean,
            isActiveCoroutine: Boolean,
            hasActiveServerSocket: Boolean,
            serverBindError: String?,
        ): PostSessionStatus = when {
            !isStopping && isActiveCoroutine && hasActiveServerSocket -> PostSessionStatus.LISTENING
            !isStopping && !serverBindError.isNullOrBlank() -> PostSessionStatus.ERROR
            else -> PostSessionStatus.STOPPED
        }

        internal fun canMutateServiceStateForSession(
            activeInstance: Any?,
            callerInstance: Any?,
            activeOwnerSessionId: Long,
            sessionId: Long,
        ): Boolean = activeInstance != null &&
            activeInstance === callerInstance &&
            sessionId != 0L &&
            activeOwnerSessionId == sessionId

        internal fun applyPostSessionStateWithOwnership(
            ownershipLock: Any,
            isOwned: () -> Boolean,
            clearSessionOwnership: () -> Unit,
            outcome: PostSessionStatus,
            listenPort: Int,
            refreshedIp: String?,
            serverBindError: String?,
            serviceState: MutableStateFlow<StreamServiceState>,
        ): Boolean = synchronized(ownershipLock) {
            if (!isOwned()) {
                return@synchronized false
            }
            clearSessionOwnership()
            when (outcome) {
                PostSessionStatus.LISTENING -> {
                    serviceState.update {
                        it.copy(
                            status = StreamConnectionStatus.LISTENING,
                            hasActiveSession = false,
                            isVideoPipelineHealthy = true,
                            clientAddress = null,
                            localIpAddress = refreshedIp,
                            listenPort = listenPort,
                            currentFps = 0f,
                            errorMessage = null,
                        )
                    }
                }
                PostSessionStatus.ERROR -> {
                    val err = serverBindError ?: "Server error"
                    serviceState.update {
                        it.copy(
                            status = StreamConnectionStatus.ERROR,
                            hasActiveSession = false,
                            isVideoPipelineHealthy = true,
                            clientAddress = null,
                            currentFps = 0f,
                            errorMessage = err,
                        )
                    }
                }
                PostSessionStatus.STOPPED -> {
                    serviceState.update {
                        it.copy(
                            status = StreamConnectionStatus.STOPPED,
                            hasActiveSession = false,
                            isVideoPipelineHealthy = true,
                            clientAddress = null,
                            currentFps = 0f,
                            errorMessage = null,
                        )
                    }
                }
            }
            true
        }

        internal fun applyPostSessionStateIfOwned(
            callerInstance: CamCraftStreamService,
            sessionId: Long,
            outcome: PostSessionStatus,
            listenPort: Int,
            refreshedIp: String?,
            serverBindError: String?,
            serviceState: MutableStateFlow<StreamServiceState>,
        ): Boolean = applyPostSessionStateWithOwnership(
            ownershipLock = stateOwnershipLock,
            isOwned = {
                canMutateServiceStateForSession(
                    activeInstance = activeInstance,
                    callerInstance = callerInstance,
                    activeOwnerSessionId = activeStateOwnerSessionId,
                    sessionId = sessionId,
                )
            },
            clearSessionOwnership = { activeStateOwnerSessionId = 0L },
            outcome = outcome,
            listenPort = listenPort,
            refreshedIp = refreshedIp,
            serverBindError = serverBindError,
            serviceState = serviceState,
        )

        internal fun decideWifiIpChangeAction(
            isStopping: Boolean,
            status: StreamConnectionStatus,
            previousIp: String?,
            updatedIp: String?,
        ): WifiIpChangeAction {
            if (isStopping || status != StreamConnectionStatus.LISTENING || updatedIp == previousIp) {
                return WifiIpChangeAction.IGNORE
            }
            return if (!updatedIp.isNullOrBlank()) {
                WifiIpChangeAction.REGISTER_MDNS
            } else {
                WifiIpChangeAction.UNREGISTER_MDNS
            }
        }

        internal fun clampActiveStateForCamera(
            state: ActiveCameraState,
            targetCamera: CameraInfo?,
        ): ActiveCameraState {
            if (targetCamera == null) return state
            val clampedZoom = state.zoomRatio.coerceIn(1.0f, targetCamera.maxZoom.coerceAtLeast(1.0f))
            val clampedEv = if (targetCamera.evRange.size >= 2) {
                val minEv = minOf(targetCamera.evRange[0], targetCamera.evRange[1])
                val maxEv = maxOf(targetCamera.evRange[0], targetCamera.evRange[1])
                state.exposureEv.coerceIn(minEv, maxEv)
            } else {
                state.exposureEv
            }
            val torchAllowed = if (targetCamera.hasFlash) state.torchOn else false
            return state.copy(
                zoomRatio = clampedZoom,
                exposureEv = clampedEv,
                torchOn = torchAllowed,
            )
        }

        internal fun applyConfigureStreamPatch(
            current: ActiveCameraState,
            cmd: ControlCmd.ConfigureStream,
            targetCamera: CameraInfo?,
        ): ActiveCameraState = clampActiveStateForCamera(
            current.copy(
                cameraId = cmd.cameraId,
                width = cmd.width,
                height = cmd.height,
                fps = cmd.fps,
                bitrateKbps = cmd.bitrateKbps,
            ),
            targetCamera,
        )

        internal fun applyCameraParamsPatch(
            current: ActiveCameraState,
            cmd: ControlCmd.SetCameraParams,
        ): ActiveCameraState = current.copy(
            zoomRatio = cmd.zoomRatio ?: current.zoomRatio,
            exposureEv = cmd.exposureEv ?: current.exposureEv,
            autofocus = cmd.autofocus ?: current.autofocus,
            torchOn = cmd.torchOn ?: current.torchOn,
            mirrorHorizontal = cmd.mirrorHorizontal ?: current.mirrorHorizontal,
            rotationDeg = cmd.rotationDeg ?: current.rotationDeg,
        )

        internal fun applyAudioStatePatch(
            current: ActiveCameraState,
            enabled: Boolean,
            muted: Boolean,
        ): ActiveCameraState = current.copy(
            audioEnabled = enabled,
            audioMuted = muted,
        )

        internal fun isCameraGeometryChanged(
            previous: ActiveCameraState,
            next: ActiveCameraState,
        ): Boolean = previous.cameraId != next.cameraId ||
            previous.width != next.width ||
            previous.height != next.height ||
            previous.fps != next.fps

        internal fun publishConnectedNotificationFromSnapshot(
            lock: Any,
            isStopping: () -> Boolean,
            isSessionActive: () -> Boolean,
            serviceState: StateFlow<StreamServiceState>,
            onUpdateNotification: (String) -> Unit,
        ): Boolean = synchronized(lock) {
            if (isStopping() || !isSessionActive()) {
                return@synchronized false
            }
            val snapshot = serviceState.value
            val clientAddress = snapshot.clientAddress
            if (snapshot.status != StreamConnectionStatus.CONNECTED || clientAddress.isNullOrBlank()) {
                return@synchronized false
            }
            onUpdateNotification(buildConnectedNotificationText(clientAddress, snapshot.activeState))
            true
        }

        internal fun isActiveSessionMatching(
            isStopping: Boolean,
            activeSession: ClientSessionHandle?,
            expectedSessionId: Long,
        ): Boolean = !isStopping &&
            expectedSessionId != 0L &&
            activeSession != null &&
            activeSession.sessionId == expectedSessionId &&
            !activeSession.isClosed

        private fun markVideoPipelineErrorLocked(
            serviceState: MutableStateFlow<StreamServiceState>,
            reason: String,
        ): String? {
            val updated = serviceState.updateAndGet { state ->
                if (state.status == StreamConnectionStatus.STOPPED || state.clientAddress.isNullOrBlank()) {
                    state
                } else {
                    state.copy(
                        status = StreamConnectionStatus.ERROR,
                        hasActiveSession = true,
                        isVideoPipelineHealthy = false,
                        currentFps = 0f,
                        errorMessage = reason,
                    )
                }
            }
            val clientAddress = updated.clientAddress
            return if (updated.status == StreamConnectionStatus.ERROR && !clientAddress.isNullOrBlank()) {
                clientAddress
            } else {
                null
            }
        }

        internal fun applyVideoPipelineFailureIfCurrentConfig(
            lock: Any,
            coordinator: ControlStateAckCoordinator,
            expectedSessionId: Long,
            expectedCameraConfigId: Long,
            expectedCameraId: String,
            isStopping: () -> Boolean,
            isSessionActive: () -> Boolean,
            reason: String,
            serviceState: MutableStateFlow<StreamServiceState>,
            onUpdateNotification: (String) -> Unit,
        ): Boolean = synchronized(lock) {
            if (isStopping() || !isSessionActive()) {
                return@synchronized false
            }
            var errorClientAddress: String? = null
            val ownedAndNotified = coordinator.enqueueAckIfSessionOwned(
                expectedSessionId = expectedSessionId,
                expectedCameraConfigId = expectedCameraConfigId,
                expectedCameraId = expectedCameraId,
                onOwnedLocked = {
                    if (!isStopping()) {
                        errorClientAddress = markVideoPipelineErrorLocked(serviceState, reason)
                    }
                },
            ) { cur ->
                if (!isStopping()) {
                    ControlAck(
                        ok = false,
                        error = reason,
                        activeState = cur,
                    )
                } else {
                    null
                }
            }
            val clientAddress = errorClientAddress
            if (!ownedAndNotified || clientAddress == null) {
                return@synchronized false
            }
            onUpdateNotification("Video unavailable ($clientAddress) • $reason")
            true
        }

        internal fun applyVideoPipelineFailureIfActive(
            lock: Any,
            isStopping: () -> Boolean,
            isSessionActive: () -> Boolean,
            reason: String,
            serviceState: MutableStateFlow<StreamServiceState>,
            onUpdateNotification: (String) -> Unit,
        ): Boolean = synchronized(lock) {
            if (isStopping() || !isSessionActive()) {
                return@synchronized false
            }
            val clientAddress = markVideoPipelineErrorLocked(serviceState, reason) ?: return@synchronized false
            onUpdateNotification("Video unavailable ($clientAddress) • $reason")
            true
        }

        internal fun restoreConnectedStatusIfRecovering(
            lock: Any,
            coordinator: ControlStateAckCoordinator,
            expectedSessionId: Long,
            expectedCameraConfigId: Long = 0L,
            isStopping: () -> Boolean,
            isSessionActive: () -> Boolean,
            serviceState: MutableStateFlow<StreamServiceState>,
            onUpdateNotification: (String) -> Unit,
            onAfterConfigCheckBeforeWriteForTest: (() -> Unit)? = null,
        ): Boolean = synchronized(lock) {
            if (isStopping() || !isSessionActive()) {
                return@synchronized false
            }
            val recoveredSnapshot = coordinator.runWithCameraConfigOrderLock(
                expectedSessionId = expectedSessionId,
                expectedCameraConfigId = expectedCameraConfigId,
            ) {
                if (isStopping() || !isSessionActive()) {
                    return@runWithCameraConfigOrderLock null
                }
                onAfterConfigCheckBeforeWriteForTest?.invoke()
                var recovered = false
                val snapshot = serviceState.updateAndGet { state ->
                    if (state.status == StreamConnectionStatus.ERROR && !state.clientAddress.isNullOrBlank()) {
                        recovered = true
                        state.copy(
                            status = StreamConnectionStatus.CONNECTED,
                            hasActiveSession = true,
                            isVideoPipelineHealthy = true,
                            errorMessage = null,
                        )
                    } else {
                        state
                    }
                }
                if (recovered &&
                    snapshot.status == StreamConnectionStatus.CONNECTED &&
                    !snapshot.clientAddress.isNullOrBlank()
                ) {
                    snapshot
                } else {
                    null
                }
            } ?: return@synchronized false

            val clientAddress = recoveredSnapshot.clientAddress ?: return@synchronized false
            onUpdateNotification(buildConnectedNotificationText(clientAddress, recoveredSnapshot.activeState))
            true
        }

        internal inline fun checkAndCloseStalledSessionWrite(
            session: ClientSessionHandle,
            nowNs: Long,
            timeoutNs: Long,
            beforeClose: () -> Unit = {},
        ): Boolean {
            if (!session.isWriteStalled(nowNs, timeoutNs)) {
                return false
            }
            beforeClose()
            session.close()
            return true
        }
    }
}

internal class UsbSessionAttempt<T : Any>(
    val attemptId: Long,
    val accessoryTag: Any? = null,
) {
    private val closing = AtomicBoolean(false)
    private val descriptorClosed = AtomicBoolean(false)

    @Volatile
    var descriptor: T? = null

    val isClosing: Boolean
        get() = closing.get()

    fun markClosing() {
        closing.set(true)
    }

    fun markDescriptorClosed(): Boolean = descriptorClosed.compareAndSet(false, true)
}

/**
 * Tracks in-flight asynchronous transport closures and listener startups prior to publish across
 * Service instances so replacement listener binds and USB accessory starts can wait for OS
 * descriptor/socket release.
 */
internal class PendingCloseTracker {
    private val lock = Any()
    private val inFlight = mutableSetOf<CompletableDeferred<Unit>>()
    private var completionsCount: Long = 0L

    fun register(): CompletableDeferred<Unit> {
        val deferred = CompletableDeferred<Unit>()
        synchronized(lock) {
            inFlight.add(deferred)
        }
        deferred.invokeOnCompletion {
            synchronized(lock) {
                if (inFlight.remove(deferred)) {
                    completionsCount++
                }
            }
        }
        return deferred
    }

    fun snapshot(
        exclude: CompletableDeferred<Unit>? = null,
    ): Pair<List<CompletableDeferred<Unit>>, Long> = synchronized(lock) {
        val pending = if (exclude == null) {
            inFlight.toList()
        } else {
            inFlight.filter { it !== exclude }
        }
        pending to completionsCount
    }

    fun currentCompletionsCount(): Long = synchronized(lock) {
        completionsCount
    }
}

internal data class DetachedUsbDescriptor<T : Any>(
    val descriptor: T,
    val completion: CompletableDeferred<Unit>?,
)

/**
 * Tracks per-attempt USB AOA transport ownership and close state so a detached or
 * deferred-overflow teardown of an old attempt cannot poison or close a replacement attempt.
 */
internal class UsbSessionAttemptTracker<T : Any>(
    private val pendingCloseTracker: PendingCloseTracker? = null,
    private val closeDescriptor: (T) -> Unit,
) {
    private val lock = Any()
    private var nextAttemptId: Long = 0L
    private var activeAttempt: UsbSessionAttempt<T>? = null

    fun beginAttempt(accessoryTag: Any? = null): UsbSessionAttempt<T> = synchronized(lock) {
        UsbSessionAttempt<T>(
            attemptId = ++nextAttemptId,
            accessoryTag = accessoryTag,
        ).also { activeAttempt = it }
    }

    fun currentActiveAttempt(): UsbSessionAttempt<T>? = synchronized(lock) { activeAttempt }

    fun hasActiveNonClosingAttempt(): Boolean = synchronized(lock) {
        val current = activeAttempt
        current != null && !current.isClosing
    }

    fun hasActiveDescriptor(): Boolean = synchronized(lock) {
        val current = activeAttempt
        current != null && !current.isClosing && current.descriptor != null
    }

    fun isAttemptPendingOrActive(attempt: UsbSessionAttempt<T>): Boolean = synchronized(lock) {
        !attempt.isClosing && activeAttempt === attempt
    }

    fun attachDescriptorOrDetachRejected(
        attempt: UsbSessionAttempt<T>,
        descriptor: T,
    ): Pair<Boolean, DetachedUsbDescriptor<T>?> = synchronized(lock) {
        if (attempt.isClosing || activeAttempt !== attempt) {
            val detached = if (attempt.markDescriptorClosed()) {
                DetachedUsbDescriptor(descriptor, pendingCloseTracker?.register())
            } else {
                null
            }
            false to detached
        } else {
            attempt.descriptor = descriptor
            true to null
        }
    }

    fun rejectUnattachedDescriptorForClose(
        attempt: UsbSessionAttempt<T>,
        descriptor: T,
    ): DetachedUsbDescriptor<T>? = synchronized(lock) {
        attempt.markClosing()
        if (activeAttempt === attempt) {
            activeAttempt = null
        }
        if (attempt.markDescriptorClosed()) {
            DetachedUsbDescriptor(descriptor, pendingCloseTracker?.register())
        } else {
            null
        }
    }

    fun attachDescriptor(attempt: UsbSessionAttempt<T>, descriptor: T): Boolean {
        val (accepted, rejectedToClose) = attachDescriptorOrDetachRejected(attempt, descriptor)
        if (!accepted && rejectedToClose != null) {
            closeDetachedDescriptor(rejectedToClose)
        }
        return accepted
    }

    fun isAttemptCurrent(attempt: UsbSessionAttempt<T>, descriptor: T): Boolean = synchronized(lock) {
        !attempt.isClosing && activeAttempt === attempt && attempt.descriptor === descriptor
    }

    fun closeAttempt(attempt: UsbSessionAttempt<T>) {
        var completionToSignal: CompletableDeferred<Unit>? = null
        val descriptorToClose = synchronized(lock) {
            attempt.markClosing()
            if (activeAttempt === attempt) {
                activeAttempt = null
            }
            val desc = attempt.descriptor
            if (desc != null && attempt.markDescriptorClosed()) {
                completionToSignal = pendingCloseTracker?.register()
                desc
            } else {
                null
            }
        }
        if (descriptorToClose != null) {
            try {
                closeDescriptor(descriptorToClose)
            } finally {
                completionToSignal?.complete(Unit)
            }
        }
    }

    fun detachActiveAttemptForClose(): DetachedUsbDescriptor<T>? = synchronized(lock) {
        val current = activeAttempt ?: return null
        current.markClosing()
        activeAttempt = null
        val desc = current.descriptor
        if (desc != null && current.markDescriptorClosed()) {
            val completion = pendingCloseTracker?.register()
            DetachedUsbDescriptor(desc, completion)
        } else {
            null
        }
    }

    fun closeDetachedDescriptor(detached: DetachedUsbDescriptor<T>) {
        try {
            closeDescriptor(detached.descriptor)
        } finally {
            detached.completion?.complete(Unit)
        }
    }

    fun closeActiveAttempt() {
        detachActiveAttemptForClose()?.let(::closeDetachedDescriptor)
    }
}

/**
 * Per-session transport and bounded `CONTROL_ACK` queue handle.
 * Closes only its own transport on ACK queue overflow or write failure so newer
 * or pending sessions are never disrupted.
 */
internal class ClientSessionHandle(
    val sessionId: Long,
    val output: OutputStream,
    val outputLock: Any,
    val closeTransport: () -> Unit,
    private val ackQueueCapacity: Int = CamCraftStreamService.CONTROL_ACK_QUEUE_CAPACITY,
    private val onQueueOverflow: ((ClientSessionHandle) -> Unit)? = null,
    private val dispatchClose: (() -> Unit) -> Unit = { it() },
    private val nanoTimeProvider: () -> Long = System::nanoTime,
) {
    val ackChannel = Channel<ControlAck>(ackQueueCapacity)
    private val closed = AtomicBoolean(false)
    private val transportClosed = AtomicBoolean(false)
    private val activeWriteStartedNs = AtomicLong(0L)
    private val closeDeferred = CompletableDeferred<Unit>()

    @Volatile
    var audioEngine: AudioStreamEngine? = null

    val isClosed: Boolean
        get() = closed.get()

    val closedDeferred: Deferred<Unit>
        get() = closeDeferred

    val hasInFlightWrite: Boolean
        get() = activeWriteStartedNs.get() != 0L

    fun markWriteStarted() {
        markWriteStarted(nanoTimeProvider())
    }

    fun markWriteStarted(nowNs: Long) {
        activeWriteStartedNs.set(if (nowNs == 0L) 1L else nowNs)
    }

    fun markWriteFinished() {
        activeWriteStartedNs.set(0L)
    }

    inline fun <T> withTrackedWrite(block: () -> T): T {
        markWriteStarted()
        return try {
            block()
        } finally {
            markWriteFinished()
        }
    }

    fun isWriteStalled(nowNs: Long, timeoutNs: Long): Boolean {
        if (closed.get()) return false
        val startedNs = activeWriteStartedNs.get()
        if (startedNs == 0L) return false
        return (nowNs - startedNs) >= timeoutNs
    }

    private fun closeTransportOnce() {
        if (transportClosed.compareAndSet(false, true)) {
            closeTransport()
        }
    }

    fun enqueueAck(ack: ControlAck): Boolean {
        if (closed.get()) return false
        val result = ackChannel.trySend(ack)
        if (result.isSuccess) return true
        val channelClosed = result.isClosed || closed.get()
        if (!channelClosed && closed.compareAndSet(false, true)) {
            ackChannel.close()
            try {
                Log.w(
                    CamCraftStreamService.TAG,
                    "CONTROL_ACK queue overflow (capacity=$ackQueueCapacity) for session $sessionId; closing stalled session",
                )
            } catch (_: RuntimeException) {
                // JVM unit tests do not mock android.util.Log.
            }
            onQueueOverflow?.invoke(this)
            closeDeferred.complete(Unit)
            dispatchClose {
                closeTransportOnce()
            }
        }
        return false
    }

    fun close() {
        closed.compareAndSet(false, true)
        ackChannel.close()
        closeTransportOnce()
        closeDeferred.complete(Unit)
    }
}

/**
 * Serializes atomic [MutableStateFlow.updateAndGet] field mutations with monotonic commit
 * sequence numbers, camera-geometry config IDs, active session ownership, committed side-effects,
 * and outbound [ControlAck] enqueueing so concurrent PC control commands, encoder fallbacks, and
 * Android UI actions stay strictly ordered.
 */
internal class ControlStateAckCoordinator(
    private val serviceState: MutableStateFlow<StreamServiceState>,
    private val onEnqueueAck: (ControlAck) -> Unit,
) {
    private val orderLock = Any()
    private var commitSeq: Long = 0L
    private var cameraConfigId: Long = 0L
    private var ownedSession: ClientSessionHandle? = null

    fun setActiveSession(session: ClientSessionHandle?) {
        synchronized(orderLock) {
            ownedSession = session
        }
    }

    fun attachSessionAndCaptureState(session: ClientSessionHandle): ActiveCameraState? =
        synchronized(orderLock) {
            if (session.isClosed) {
                return@synchronized null
            }
            ownedSession = session
            serviceState.value.activeState
        }

    fun clearActiveSessionIfCurrent(session: ClientSessionHandle) {
        synchronized(orderLock) {
            if (ownedSession === session) {
                ownedSession = null
            }
        }
    }

    fun currentActiveSessionId(): Long = synchronized(orderLock) {
        ownedSession?.sessionId ?: 0L
    }

    fun currentOwnedSession(): ClientSessionHandle? = synchronized(orderLock) {
        ownedSession
    }

    fun currentCameraConfigId(): Long = synchronized(orderLock) {
        cameraConfigId
    }

    fun <T> runLocked(block: (ActiveCameraState, Long) -> T): T = synchronized(orderLock) {
        val state = serviceState.value.activeState
        val seq = ++commitSeq
        block(state, seq)
    }

    fun <T> runWithCameraConfigOrderLock(
        expectedSessionId: Long,
        expectedCameraConfigId: Long = 0L,
        block: (ActiveCameraState) -> T,
    ): T? = synchronized(orderLock) {
        val currentSession = ownedSession
        if (expectedSessionId == 0L ||
            currentSession == null ||
            currentSession.sessionId != expectedSessionId ||
            currentSession.isClosed
        ) {
            return@synchronized null
        }
        if (expectedCameraConfigId != 0L && cameraConfigId != expectedCameraConfigId) {
            return@synchronized null
        }
        block(serviceState.value.activeState)
    }

    fun <T> runIfSessionActive(
        session: ClientSessionHandle,
        advanceCameraConfig: Boolean = false,
        block: (ActiveCameraState, Long, Long) -> T,
    ): T? = synchronized(orderLock) {
        if (ownedSession !== session || session.isClosed) {
            return@synchronized null
        }
        val state = serviceState.value.activeState
        val seq = ++commitSeq
        if (advanceCameraConfig) {
            cameraConfigId++
        }
        block(state, seq, cameraConfigId)
    }

    fun updateAndEnqueue(
        camerasOverride: List<CameraInfo>? = null,
        onCommittedLocked: (ActiveCameraState, Long) -> Unit = { _, _ -> },
        mutate: (ActiveCameraState) -> ActiveCameraState,
    ): ActiveCameraState = updateWithCustomAck(
        camerasOverride = camerasOverride,
        ackBuilder = { next -> ControlAck.success(next) },
        onCommittedLocked = onCommittedLocked,
        mutate = mutate,
    )

    fun updateWithCustomAck(
        camerasOverride: List<CameraInfo>? = null,
        ackBuilder: (ActiveCameraState) -> ControlAck?,
        onCommittedLocked: (ActiveCameraState, Long) -> Unit = { _, _ -> },
        mutate: (ActiveCameraState) -> ActiveCameraState,
    ): ActiveCameraState = updateWithCustomAckAndConfig(
        camerasOverride = camerasOverride,
        ackBuilder = ackBuilder,
        onCommittedLocked = { next, seq, _ -> onCommittedLocked(next, seq) },
        mutate = mutate,
    )

    fun updateWithCustomAckAndConfig(
        camerasOverride: List<CameraInfo>? = null,
        ackBuilder: (ActiveCameraState) -> ControlAck?,
        onCommittedLocked: (ActiveCameraState, Long, Long) -> Unit = { _, _, _ -> },
        mutate: (ActiveCameraState) -> ActiveCameraState,
    ): ActiveCameraState = synchronized(orderLock) {
        val previous = serviceState.value.activeState
        val next = serviceState.updateAndGet { state ->
            val patched = mutate(state.activeState)
            state.copy(
                cameras = camerasOverride ?: state.cameras,
                activeState = patched,
                bitrateKbps = patched.bitrateKbps,
            )
        }.activeState
        val seq = ++commitSeq
        if (CamCraftStreamService.isCameraGeometryChanged(previous, next)) {
            cameraConfigId++
        }
        onCommittedLocked(next, seq, cameraConfigId)
        ackBuilder(next)?.let { ack ->
            val active = ownedSession
            if (active != null) {
                active.enqueueAck(ack)
            } else {
                onEnqueueAck(ack)
            }
        }
        next
    }

    fun updateIfSessionOwned(
        expectedSessionId: Long,
        expectedCameraConfigId: Long? = null,
        expectedCameraId: String? = null,
        advanceCameraConfigOnGeometryChange: Boolean = true,
        shouldAdvanceCameraConfig: ((previous: ActiveCameraState, next: ActiveCameraState) -> Boolean)? = null,
        shouldCommitLocked: (() -> Boolean)? = null,
        camerasOverride: List<CameraInfo>? = null,
        ackBuilder: (ActiveCameraState) -> ControlAck? = { next -> ControlAck.success(next) },
        onCommittedLocked: (ActiveCameraState, Long, Long) -> Unit = { _, _, _ -> },
        mutate: (ActiveCameraState) -> ActiveCameraState,
    ): ActiveCameraState? = synchronized(orderLock) {
        val currentSession = ownedSession
        if (expectedSessionId == 0L ||
            currentSession == null ||
            currentSession.sessionId != expectedSessionId ||
            currentSession.isClosed
        ) {
            return@synchronized null
        }
        if (expectedCameraConfigId != null && cameraConfigId != expectedCameraConfigId) {
            return@synchronized null
        }
        val previous = serviceState.value.activeState
        if (expectedCameraId != null && previous.cameraId != expectedCameraId) {
            return@synchronized null
        }
        if (shouldCommitLocked != null && !shouldCommitLocked()) {
            return@synchronized null
        }
        val next = serviceState.updateAndGet { state ->
            val patched = mutate(state.activeState)
            state.copy(
                cameras = camerasOverride ?: state.cameras,
                activeState = patched,
                bitrateKbps = patched.bitrateKbps,
            )
        }.activeState
        val seq = ++commitSeq
        val advanceConfig = shouldAdvanceCameraConfig?.invoke(previous, next)
            ?: (advanceCameraConfigOnGeometryChange && CamCraftStreamService.isCameraGeometryChanged(previous, next))
        if (advanceConfig) {
            cameraConfigId++
        }
        onCommittedLocked(next, seq, cameraConfigId)
        ackBuilder(next)?.let { ack ->
            currentSession.enqueueAck(ack)
        }
        next
    }

    fun enqueueAckIfSessionOwned(
        expectedSessionId: Long,
        expectedCameraConfigId: Long? = null,
        expectedCameraId: String? = null,
        onOwnedLocked: (ActiveCameraState) -> Unit = {},
        ackBuilder: (ActiveCameraState) -> ControlAck?,
    ): Boolean = synchronized(orderLock) {
        val currentSession = ownedSession
        if (expectedSessionId == 0L ||
            currentSession == null ||
            currentSession.sessionId != expectedSessionId ||
            currentSession.isClosed
        ) {
            return@synchronized false
        }
        if (expectedCameraConfigId != null && cameraConfigId != expectedCameraConfigId) {
            return@synchronized false
        }
        val currentState = serviceState.value.activeState
        if (expectedCameraId != null && currentState.cameraId != expectedCameraId) {
            return@synchronized false
        }
        onOwnedLocked(currentState)
        ackBuilder(currentState)?.let { ack ->
            currentSession.enqueueAck(ack)
        }
        true
    }

    fun enqueueCurrentStateAck() {
        synchronized(orderLock) {
            val ack = ControlAck.success(serviceState.value.activeState)
            val active = ownedSession
            if (active != null) {
                active.enqueueAck(ack)
            } else {
                onEnqueueAck(ack)
            }
        }
    }
}
