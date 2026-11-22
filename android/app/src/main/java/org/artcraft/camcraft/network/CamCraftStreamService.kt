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
import java.io.DataInputStream
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.lang.ref.WeakReference
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
) {
    val wifiEndpoint: String?
        get() = formatWifiEndpoint(localIpAddress, listenPort)
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
    private var activeClientSocket: Socket? = null

    @Volatile
    private var activeSessionCloser: (() -> Unit)? = null

    private val usbTransportLock = Any()

    @Volatile
    private var activeUsbPfd: ParcelFileDescriptor? = null

    @Volatile
    private var activeUsbPfdClosed = AtomicBoolean(false)

    @Volatile
    private var activeUsbAccessory: UsbAccessory? = null
    private val usbSessionClosing = AtomicBoolean(true)
    private var isUsbReceiverRegistered = false

    private val usbDetachedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != UsbManager.ACTION_USB_ACCESSORY_DETACHED) return
            val detachedAccessory = IntentCompat.getParcelableExtra(
                intent,
                UsbManager.EXTRA_ACCESSORY,
                UsbAccessory::class.java,
            )
            val currentAccessory = activeUsbAccessory
            if (detachedAccessory == null ||
                currentAccessory == null ||
                detachedAccessory == currentAccessory ||
                UsbAccessoryTransport.isCamCraftAccessory(detachedAccessory)
            ) {
                Log.i(TAG, "USB accessory detached; closing USB AOA transport")
                closeActiveUsbTransport()
            }
        }
    }

    private val isStopping = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private var serverBindError: String? = null

    private var cameraStreamManager: CameraStreamManager? = null
    private var audioStreamEngine: AudioStreamEngine? = null
    private var mdnsAdvertiser: MdnsAdvertiser? = null
    private var wifiNetworkMonitor: WifiNetworkMonitor? = null

    @Volatile
    private var activeClientOutputStream: OutputStream? = null
    private val clientOutputLock = Any()
    private val controlAckChannel = Channel<QueuedControlAck>(Channel.UNLIMITED)
    private val controlCoordinator = ControlStateAckCoordinator(_serviceState) { ack ->
        activeClientOutputStream?.let { stream ->
            enqueueControlAck(stream, clientOutputLock, ack)
        }
    }

    private data class QueuedControlAck(
        val output: OutputStream,
        val outputLock: Any,
        val ack: ControlAck,
    )

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
            if (isStopping.get()) return@WifiNetworkMonitor
            val previousState = _serviceState.getAndUpdate { it.copy(localIpAddress = updatedIp) }
            val currentState = _serviceState.value
            val action = decideWifiIpChangeAction(
                isStopping = isStopping.get(),
                status = currentState.status,
                previousIp = previousState.localIpAddress,
                updatedIp = updatedIp,
            )
            when (action) {
                WifiIpChangeAction.REGISTER_MDNS -> {
                    mdnsAdvertiser?.register(
                        deviceName = resolveDeviceName(),
                        port = currentState.listenPort,
                        localIpAddress = updatedIp,
                    )
                    updateNotification(buildListeningNotificationText(updatedIp, currentState.listenPort))
                }
                WifiIpChangeAction.UNREGISTER_MDNS -> {
                    mdnsAdvertiser?.unregister()
                    updateNotification(buildListeningNotificationText(updatedIp, currentState.listenPort))
                }
                WifiIpChangeAction.IGNORE -> Unit
            }
        }

        serviceScope.launch {
            for (queued in controlAckChannel) {
                if (isStopping.get() || activeClientOutputStream !== queued.output) {
                    continue
                }
                try {
                    val packet = CcftPacket.controlAck(
                        ptsUs = System.nanoTime() / 1_000L,
                        ack = queued.ack,
                    )
                    synchronized(queued.outputLock) {
                        packet.encodeTo(queued.output, flush = true)
                    }
                } catch (e: IOException) {
                    Log.w(TAG, "Failed writing CONTROL_ACK to client: ${e.message}")
                }
            }
        }

        try {
            val appContext = applicationContext ?: this
            val cameras = CameraEnumerator.enumerateCameras(appContext)
            _serviceState.update { it.copy(cameras = cameras) }
            val manager = CameraStreamManager(appContext) { packetType, flags, ptsUs, payload, offset, length ->
                handleVideoPacketFromEncoder(packetType, flags, ptsUs, payload, offset, length)
            }
            cameraStreamManager = manager
            audioStreamEngine = AudioStreamEngine(appContext) { packetType, ptsUs, payload, length ->
                handleAudioPacketFromEngine(packetType, ptsUs, payload, length)
            }
            activeInstance = this
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
                stopServer()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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
        isStopping.set(true)
        if (isUsbReceiverRegistered) {
            try {
                unregisterReceiver(usbDetachedReceiver)
            } catch (_: IllegalArgumentException) {
            }
            isUsbReceiverRegistered = false
        }
        wifiNetworkMonitor?.stop()
        wifiNetworkMonitor = null
        stopServer()
        closeActiveUsbTransport()
        activeTransformFlags = CcftFlags.NONE
        val preservedIp = LocalIpResolver.resolveLocalIpv4Address(this)
            ?: _serviceState.value.localIpAddress
        _serviceState.value = StreamServiceState(
            status = StreamConnectionStatus.STOPPED,
            localIpAddress = preservedIp,
        )
        mdnsAdvertiser?.unregister()
        mdnsAdvertiser = null
        audioStreamEngine?.release()
        audioStreamEngine = null
        cameraStreamManager?.release()
        cameraStreamManager = null
        pendingPreviewView = null
        if (activeInstance == this) {
            activeInstance = null
        }
        controlAckChannel.close()
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
        if (serverJob?.isActive == true) return
        isStopping.set(false)

        serverJob = serviceScope.launch {
            var socket: ServerSocket? = null
            try {
                val port = CcftProtocolConstants.DEFAULT_PORT
                socket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                serverSocket = socket

                val currentIp = LocalIpResolver.resolveLocalIpv4Address(this@CamCraftStreamService)
                var becameListening = false
                synchronized(lifecycleLock) {
                    serverBindError = null
                    val sessionActive = activeSessionCloser != null
                    _serviceState.update {
                        if (sessionActive) {
                            it.copy(
                                localIpAddress = currentIp,
                                listenPort = port,
                                errorMessage = null,
                            )
                        } else {
                            becameListening = true
                            it.copy(
                                status = StreamConnectionStatus.LISTENING,
                                clientAddress = null,
                                localIpAddress = currentIp,
                                listenPort = port,
                                errorMessage = null,
                            )
                        }
                    }
                }
                mdnsAdvertiser?.register(
                    deviceName = resolveDeviceName(),
                    port = port,
                    localIpAddress = currentIp,
                )
                if (becameListening) {
                    updateNotification(buildListeningNotificationText(currentIp, port))
                }
                Log.i(TAG, "CamCraft TCP Server listening on port $port (Wi-Fi IP: $currentIp)")

                while (isActive && !socket.isClosed) {
                    try {
                        val clientSocket = socket.accept()
                        handleClientConnection(clientSocket)
                    } catch (e: SocketException) {
                        if (!socket.isClosed) {
                            Log.e(TAG, "Socket exception during accept", e)
                        }
                        break
                    }
                }
            } catch (e: Exception) {
                if (isStopping.get()) {
                    Log.i(TAG, "Server stopped")
                    return@launch
                }
                Log.e(TAG, "Fatal server socket exception", e)
                mdnsAdvertiser?.unregister()
                val errMsg = e.message ?: "Server error"
                synchronized(lifecycleLock) {
                    serverBindError = errMsg
                    if (activeSessionCloser == null) {
                        _serviceState.update {
                            it.copy(
                                status = StreamConnectionStatus.ERROR,
                                errorMessage = errMsg,
                            )
                        }
                        updateNotification("Error: $errMsg")
                    }
                }
            } finally {
                if (socket != null) {
                    try {
                        socket.close()
                    } catch (_: IOException) {}
                    if (serverSocket === socket) {
                        serverSocket = null
                    }
                }
            }
        }
    }

    private fun sendStreamPacket(
        packetType: PacketType,
        flags: Int,
        ptsUs: Long,
        payload: ByteArray,
        offset: Int,
        length: Int,
        tagLabel: String,
    ): Boolean {
        val stream = activeClientOutputStream ?: return false
        return try {
            synchronized(clientOutputLock) {
                CcftPacket.writePacket(
                    output = stream,
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
        } catch (e: IOException) {
            Log.w(TAG, "Failed writing $tagLabel packet to client: ${e.message}")
            val (closer, socket) = synchronized(lifecycleLock) {
                takeTeardownTargetsForFailedStream(
                    activeStream = activeClientOutputStream,
                    failedStream = stream,
                    activeCloser = activeSessionCloser,
                    activeSocket = activeClientSocket,
                    clearActiveStream = { activeClientOutputStream = null },
                )
            }
            closer?.invoke()
            try {
                socket?.close()
            } catch (_: IOException) {}
            false
        }
    }

    private fun handleVideoPacketFromEncoder(
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
        if (sendStreamPacket(packetType, outboundFlags, ptsUs, payload, offset, length, "video")) {
            onFrameSent(packetType)
        }
    }

    private fun handleAudioPacketFromEngine(
        packetType: PacketType,
        ptsUs: Long,
        payload: ByteArray,
        length: Int,
    ) {
        sendStreamPacket(packetType, CcftFlags.NONE, ptsUs, payload, 0, length, "audio")
    }

    private fun onFrameSent(packetType: PacketType) {
        if (packetType != PacketType.VIDEO_FRAME) return
        if (isStopping.get() || activeClientOutputStream == null) return
        totalFramesSent++
        windowFrames++

        val now = System.nanoTime()
        val elapsedSec = (now - windowStartNs) / 1_000_000_000.0
        if (elapsedSec >= 1.0) {
            val fps = (windowFrames / elapsedSec).toFloat()
            _serviceState.update {
                it.copy(
                    framesSent = totalFramesSent,
                    currentFps = fps,
                )
            }
            windowFrames = 0
            windowStartNs = now
        }
    }

    private suspend fun handleClientConnection(clientSocket: Socket) {
        if (isStopping.get()) {
            try {
                clientSocket.close()
            } catch (_: IOException) {}
            return
        }
        if (usbJob?.isActive == true && !usbSessionClosing.get()) {
            Log.i(TAG, "Ignoring incoming TCP connection while USB AOA session is active")
            try {
                clientSocket.close()
            } catch (_: IOException) {}
            return
        }

        activeClientSocket = clientSocket
        val clientIp = clientSocket.inetAddress?.hostAddress ?: "unknown"
        Log.i(TAG, "PC Client connected from $clientIp")

        var inputStream: DataInputStream? = null
        var outputStream: OutputStream? = null
        val closeTransport: () -> Unit = {
            try {
                clientSocket.close()
            } catch (_: IOException) {}
            if (activeClientSocket === clientSocket) {
                activeClientSocket = null
            }
        }

        try {
            clientSocket.tcpNoDelay = true
            clientSocket.keepAlive = true
            clientSocket.soTimeout = CLIENT_READ_TIMEOUT_MS

            outputStream = BufferedOutputStream(clientSocket.getOutputStream(), 65536)
            inputStream = DataInputStream(BufferedInputStream(clientSocket.getInputStream(), 16384))

            handleClientSession(
                clientLabel = clientIp,
                inputStream = inputStream,
                outputStream = outputStream,
                closeTransport = closeTransport,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Client TCP connection setup/session error: ${e.message}")
            try {
                inputStream?.close()
            } catch (_: IOException) {}
            try {
                outputStream?.close()
            } catch (_: IOException) {}
            closeTransport()
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
        synchronized(lifecycleLock) {
            isStopping.set(false)
            val existing = usbJob
            if (existing?.isActive == true && !usbSessionClosing.get()) {
                Log.i(TAG, "USB AOA session already active; skipping duplicate start")
                return
            }
            previousUsbJob = existing
            usbSessionClosing.set(false)

            // Preempt any active Wi-Fi client socket so USB Direct can take ownership immediately.
            activeClientSocket?.let { socket ->
                try {
                    socket.close()
                } catch (_: IOException) {}
            }

            usbJob = serviceScope.launch {
                if (previousUsbJob != null && previousUsbJob.isActive) {
                    previousUsbJob.join()
                }
                if (isStopping.get() || !currentCoroutineContext().isActive) {
                    return@launch
                }

                val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
                if (usbManager == null) {
                    Log.w(TAG, "UsbManager unavailable; cannot open USB accessory")
                    return@launch
                }

                val pfd = try {
                    usbManager.openAccessory(accessory)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to open USB accessory: ${e.message}", e)
                    null
                }
                if (pfd == null) {
                    Log.w(TAG, "UsbManager.openAccessory returned null for ${accessory.model}")
                    return@launch
                }

                val pfdClosedFlag = AtomicBoolean(false)
                synchronized(usbTransportLock) {
                    activeUsbPfd = pfd
                    activeUsbPfdClosed = pfdClosedFlag
                    activeUsbAccessory = accessory
                }

                val closeTransport: () -> Unit = {
                    usbSessionClosing.set(true)
                    if (pfdClosedFlag.compareAndSet(false, true)) {
                        try {
                            pfd.close()
                        } catch (e: IOException) {
                            Log.w(TAG, "Error closing USB accessory descriptor: ${e.message}")
                        }
                    }
                    synchronized(usbTransportLock) {
                        if (activeUsbPfd === pfd) {
                            activeUsbPfd = null
                            activeUsbAccessory = null
                        }
                    }
                }

                var inputStream: DataInputStream? = null
                var outputStream: OutputStream? = null
                try {
                    val fd = pfd.fileDescriptor
                    inputStream = DataInputStream(BufferedInputStream(FileInputStream(fd), 16_384))
                    outputStream = BufferedOutputStream(
                        AoaChunkedOutputStream(FileOutputStream(fd)),
                        65_536,
                    )

                    handleClientSession(
                        clientLabel = USB_CLIENT_LABEL,
                        inputStream = inputStream,
                        outputStream = outputStream,
                        closeTransport = closeTransport,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "USB AOA session setup/teardown error: ${e.message}")
                } finally {
                    try {
                        inputStream?.close()
                    } catch (_: IOException) {}
                    try {
                        outputStream?.close()
                    } catch (_: IOException) {}
                    closeTransport()
                }
            }
        }
    }

    private fun closeActiveUsbTransport() {
        val pfd: ParcelFileDescriptor?
        val closedFlag: AtomicBoolean
        synchronized(usbTransportLock) {
            usbSessionClosing.set(true)
            pfd = activeUsbPfd
            closedFlag = activeUsbPfdClosed
            activeUsbPfd = null
            activeUsbAccessory = null
        }
        if (pfd != null && closedFlag.compareAndSet(false, true)) {
            try {
                pfd.close()
            } catch (e: IOException) {
                Log.w(TAG, "Error closing active USB accessory descriptor: ${e.message}")
            }
        }
    }

    private suspend fun handleClientSession(
        clientLabel: String,
        inputStream: DataInputStream,
        outputStream: OutputStream,
        closeTransport: () -> Unit,
    ) {
        sessionMutex.withLock {
            if (isStopping.get() || !currentCoroutineContext().isActive) {
                closeTransport()
                return
            }

            val initialActiveState = _serviceState.value.activeState
            activeTransformFlags = computeTransformFlags(
                mirrorHorizontal = initialActiveState.mirrorHorizontal,
                rotationDeg = initialActiveState.rotationDeg,
            )

            synchronized(lifecycleLock) {
                activeSessionCloser = closeTransport
                _serviceState.update {
                    it.copy(
                        status = StreamConnectionStatus.CONNECTED,
                        clientAddress = clientLabel,
                    )
                }
            }
            updateNotification(buildConnectedNotificationText(clientLabel, initialActiveState))

            try {
                // 1. Send CONTROL_HELLO (0x10)
                val hello = createDeviceHello(_serviceState.value.activeState, this)
                val helloPacket = CcftPacket.controlHello(System.nanoTime() / 1_000L, hello)
                synchronized(clientOutputLock) {
                    helloPacket.encodeTo(outputStream, flush = true)
                }
                Log.i(TAG, "Sent CONTROL_HELLO to $clientLabel")

                // 2. Set active output stream for encoder video packets
                synchronized(lifecycleLock) {
                    activeClientOutputStream = outputStream
                }

                // 3. Start real CameraX + H.264 hardware encoder pipeline
                val activeState = _serviceState.value.activeState
                cameraStreamManager?.startStream(
                    cameraId = activeState.cameraId,
                    width = activeState.width,
                    height = activeState.height,
                    fps = activeState.fps,
                    bitrateKbps = activeState.bitrateKbps,
                )

                // 4. If encoder already cached SPS/PPS config, immediately dispatch it
                cameraStreamManager?.cachedConfig?.let { spsPps ->
                    synchronized(clientOutputLock) {
                        CcftPacket.writePacket(
                            output = outputStream,
                            type = PacketType.VIDEO_CONFIG,
                            flags = CcftFlags.NONE,
                            ptsUs = System.nanoTime() / 1_000L,
                            payload = spsPps,
                            offset = 0,
                            length = spsPps.size,
                            flush = true,
                        )
                    }
                    cameraStreamManager?.requestKeyFrame()
                }

                // 5. Start AudioStreamEngine if audio forwarding is enabled
                if (activeState.audioEnabled) {
                    val audioStarted = audioStreamEngine?.start(initiallyMuted = activeState.audioMuted) ?: false
                    if (!audioStarted) {
                        Log.w(TAG, "AudioStreamEngine failed to start (check RECORD_AUDIO permission)")
                        controlCoordinator.updateAndEnqueue { cur ->
                            cur.copy(audioEnabled = false)
                        }
                    }
                }

                // 6. Run reader loop to process incoming heartbeats and control commands
                runPacketReader(inputStream, outputStream, clientOutputLock)
            } catch (e: Exception) {
                Log.w(TAG, "Client session ($clientLabel) terminated: ${e.message}")
            } finally {
                // Close transport first so any stalled encoder/audio writes throw IOException and unblock.
                synchronized(lifecycleLock) {
                    if (activeClientOutputStream === outputStream) {
                        activeClientOutputStream = null
                    }
                    if (activeSessionCloser === closeTransport) {
                        activeSessionCloser = null
                    }
                }

                try {
                    inputStream.close()
                } catch (_: IOException) {}
                try {
                    outputStream.close()
                } catch (_: IOException) {}
                closeTransport()

                audioStreamEngine?.stop()
                cameraStreamManager?.stopStream()

                val isActiveCoroutine = currentCoroutineContext().isActive
                val transition = synchronized(lifecycleLock) {
                    val outcome = computePostSessionTransition(
                        isStopping = isStopping.get(),
                        isActiveCoroutine = isActiveCoroutine,
                        hasActiveServerSocket = serverSocket?.isClosed == false,
                        serverBindError = serverBindError,
                    )
                    when (outcome) {
                        PostSessionStatus.LISTENING -> {
                            val refreshedIp = LocalIpResolver.resolveLocalIpv4Address(this)
                            val listenPort = _serviceState.value.listenPort
                            _serviceState.update {
                                it.copy(
                                    status = StreamConnectionStatus.LISTENING,
                                    clientAddress = null,
                                    localIpAddress = refreshedIp,
                                    currentFps = 0f,
                                    errorMessage = null,
                                )
                            }
                            updateNotification(buildListeningNotificationText(refreshedIp, listenPort))
                        }
                        PostSessionStatus.ERROR -> {
                            val err = serverBindError ?: "Server error"
                            _serviceState.update {
                                it.copy(
                                    status = StreamConnectionStatus.ERROR,
                                    clientAddress = null,
                                    currentFps = 0f,
                                    errorMessage = err,
                                )
                            }
                            updateNotification("Error: $err")
                        }
                        PostSessionStatus.STOPPED -> {
                            _serviceState.update {
                                it.copy(
                                    status = StreamConnectionStatus.STOPPED,
                                    clientAddress = null,
                                    currentFps = 0f,
                                    errorMessage = null,
                                )
                            }
                            if (!isStopping.get()) {
                                updateNotification("Standby • Ready to connect")
                            }
                        }
                    }
                    outcome
                }
                Log.i(TAG, "Client disconnected ($clientLabel), transitioned status to $transition")
            }
        }
    }

    /**
     * Reads and dispatches incoming control and heartbeat packets from the PC receiver.
     */
    private fun runPacketReader(
        input: DataInputStream,
        output: OutputStream,
        outputLock: Any,
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
                    synchronized(outputLock) {
                        pong.encodeTo(output, flush = true)
                    }
                }
                PacketType.CONTROL_CMD -> {
                    handleControlCommand(packet, output, outputLock)
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
     */
    private fun handleControlCommand(
        packet: CcftPacket,
        output: OutputStream,
        outputLock: Any,
    ) {
        try {
            val cmd = packet.decodeJson<ControlCmd>()
            Log.i(TAG, "Received CONTROL_CMD: $cmd")

            when (cmd) {
                is ControlCmd.ConfigureStream -> {
                    val cameras = _serviceState.value.cameras.ifEmpty {
                        CameraEnumerator.enumerateCameras(this)
                    }
                    val targetCam = cameras.find { it.id == cmd.cameraId }
                    val updated = mutateActiveStateAndEnqueueAck(
                        targetStream = output,
                        targetOutputLock = outputLock,
                        camerasOverride = cameras,
                    ) { cur ->
                        applyConfigureStreamPatch(cur, cmd, targetCam)
                    }
                    cameraStreamManager?.setZoomRatio(updated.zoomRatio)
                    cameraStreamManager?.setExposureEv(updated.exposureEv)
                    cameraStreamManager?.setTorch(updated.torchOn)
                    cameraStreamManager?.rebindCamera(
                        cameraId = updated.cameraId,
                        width = updated.width,
                        height = updated.height,
                        fps = updated.fps,
                        bitrateKbps = updated.bitrateKbps,
                    )
                    val clientIp = activeClientSocket?.inetAddress?.hostAddress
                        ?: _serviceState.value.clientAddress
                    if (!clientIp.isNullOrBlank()) {
                        updateNotification(buildConnectedNotificationText(clientIp, updated))
                    }
                }
                is ControlCmd.SetCameraParams -> {
                    cmd.zoomRatio?.let { cameraStreamManager?.setZoomRatio(it) }
                    cmd.exposureEv?.let { cameraStreamManager?.setExposureEv(it) }
                    cmd.torchOn?.let { cameraStreamManager?.setTorch(it) }
                    cmd.autofocus?.let { cameraStreamManager?.setAutoFocus(it) }

                    val updated = mutateActiveStateAndEnqueueAck(
                        targetStream = output,
                        targetOutputLock = outputLock,
                    ) { cur ->
                        applyCameraParamsPatch(cur, cmd)
                    }
                    activeTransformFlags = computeTransformFlags(
                        mirrorHorizontal = updated.mirrorHorizontal,
                        rotationDeg = updated.rotationDeg,
                    )
                }
                is ControlCmd.SetAudioState -> {
                    if (cmd.enabled) {
                        audioStreamEngine?.setMuted(cmd.muted)
                        val engineActive = audioStreamEngine?.isActive == true ||
                            (audioStreamEngine?.start(initiallyMuted = cmd.muted) == true)
                        if (!engineActive) {
                            controlCoordinator.updateWithCustomAck(
                                ackBuilder = { fallbackState ->
                                    ControlAck(
                                        ok = false,
                                        error = "Microphone capture unavailable (grant RECORD_AUDIO permission on Android)",
                                        activeState = fallbackState,
                                    )
                                },
                                mutate = { cur ->
                                    applyAudioStatePatch(cur, enabled = false, muted = cmd.muted)
                                },
                            )
                            return
                        }
                        mutateActiveStateAndEnqueueAck(
                            targetStream = output,
                            targetOutputLock = outputLock,
                        ) { cur ->
                            applyAudioStatePatch(cur, enabled = true, muted = cmd.muted)
                        }
                    } else {
                        audioStreamEngine?.stop()
                        mutateActiveStateAndEnqueueAck(
                            targetStream = output,
                            targetOutputLock = outputLock,
                        ) { cur ->
                            applyAudioStatePatch(cur, enabled = false, muted = cmd.muted)
                        }
                    }
                }
                is ControlCmd.RequestKeyframe -> {
                    cameraStreamManager?.cachedConfig?.let { spsPps ->
                        synchronized(outputLock) {
                            CcftPacket.writePacket(
                                output = output,
                                type = PacketType.VIDEO_CONFIG,
                                flags = CcftFlags.NONE,
                                ptsUs = System.nanoTime() / 1_000L,
                                payload = spsPps,
                                offset = 0,
                                length = spsPps.size,
                                flush = true,
                            )
                        }
                    }
                    cameraStreamManager?.requestKeyFrame()
                    controlCoordinator.enqueueCurrentStateAck()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse or apply CONTROL_CMD", e)
            enqueueControlAck(
                output,
                outputLock,
                ControlAck.failure(e.message ?: "Unknown control error"),
            )
        }
    }

    private fun enqueueControlAck(
        output: OutputStream,
        outputLock: Any,
        ack: ControlAck,
    ) {
        controlAckChannel.trySend(QueuedControlAck(output = output, outputLock = outputLock, ack = ack))
    }

    private fun mutateActiveStateAndEnqueueAck(
        targetStream: OutputStream? = null,
        targetOutputLock: Any? = null,
        camerasOverride: List<CameraInfo>? = null,
        mutate: (ActiveCameraState) -> ActiveCameraState,
    ): ActiveCameraState = controlCoordinator.updateWithCustomAck(
        camerasOverride = camerasOverride,
        ackBuilder = { next ->
            val stream = targetStream ?: activeClientOutputStream
            val lock = targetOutputLock ?: clientOutputLock
            if (stream != null) {
                enqueueControlAck(stream, lock, ControlAck.success(next))
            }
            null
        },
        mutate = mutate,
    )

    fun switchCameraFromUi() {
        val cameras = _serviceState.value.cameras.ifEmpty { CameraEnumerator.enumerateCameras(this) }
        if (cameras.isEmpty()) return

        val updated = mutateActiveStateAndEnqueueAck(camerasOverride = cameras) { cur ->
            val currentIdx = cameras.indexOfFirst { it.id == cur.cameraId }
            val nextCam = cameras[(currentIdx + 1) % cameras.size]
            clampActiveStateForCamera(cur.copy(cameraId = nextCam.id), nextCam)
        }

        cameraStreamManager?.setZoomRatio(updated.zoomRatio)
        cameraStreamManager?.setExposureEv(updated.exposureEv)
        cameraStreamManager?.setTorch(updated.torchOn)
        cameraStreamManager?.rebindCamera(
            cameraId = updated.cameraId,
            width = updated.width,
            height = updated.height,
            fps = updated.fps,
            bitrateKbps = updated.bitrateKbps,
        )
    }

    fun toggleTorchFromUi() {
        var appliedTorch: Boolean? = null
        controlCoordinator.updateWithCustomAck(
            ackBuilder = { next ->
                if (appliedTorch != null) ControlAck.success(next) else null
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
        appliedTorch?.let { cameraStreamManager?.setTorch(it) }
    }

    fun toggleMuteFromUi() {
        val updated = mutateActiveStateAndEnqueueAck { cur ->
            cur.copy(audioMuted = !cur.audioMuted)
        }
        audioStreamEngine?.setMuted(updated.audioMuted)
    }

    private fun stopServer() {
        synchronized(lifecycleLock) {
            isStopping.set(true)
            mdnsAdvertiser?.unregister()
            activeSessionCloser?.invoke()
            activeSessionCloser = null
            closeActiveUsbTransport()
            try {
                activeClientSocket?.close()
            } catch (ignored: IOException) {}
            activeClientSocket = null
            try {
                serverSocket?.close()
            } catch (ignored: IOException) {}
            serverSocket = null
            usbJob?.cancel()
            usbJob = null
            serverJob?.cancel()
            serverJob = null
        }
    }

    private fun buildListeningNotificationText(ip: String?, port: Int): String =
        if (!ip.isNullOrBlank()) {
            "Wi-Fi $ip:$port • Waiting for PC..."
        } else {
            "Port $port • Waiting for PC..."
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

        const val ACTION_START = "org.artcraft.camcraft.action.START"
        const val ACTION_START_USB_ACCESSORY = "org.artcraft.camcraft.action.START_USB_ACCESSORY"
        const val ACTION_STOP = "org.artcraft.camcraft.action.STOP"
        const val USB_CLIENT_LABEL = "USB Direct"

        @Volatile
        private var activeInstance: CamCraftStreamService? = null
        private var pendingPreviewView: WeakReference<PreviewView>? = null

        private val _serviceState = MutableStateFlow(StreamServiceState())
        val serviceState: StateFlow<StreamServiceState> = _serviceState.asStateFlow()

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

            return ControlHello(
                deviceName = resolveDeviceName(),
                protocolVersion = CcftProtocolConstants.VERSION.toInt(),
                cameras = cameras,
                activeState = activeState,
            )
        }

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

        internal inline fun <C, S> takeTeardownTargetsForFailedStream(
            activeStream: OutputStream?,
            failedStream: OutputStream,
            activeCloser: C?,
            activeSocket: S?,
            clearActiveStream: () -> Unit,
        ): Pair<C?, S?> {
            if (activeStream !== failedStream) {
                return null to null
            }
            clearActiveStream()
            return activeCloser to activeSocket
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
    }
}

/**
 * Serializes atomic [MutableStateFlow.updateAndGet] field mutations with outbound
 * [ControlAck] enqueueing so concurrent PC control commands and Android UI actions
 * never overwrite unrelated fields or emit ACKs out of order.
 */
internal class ControlStateAckCoordinator(
    private val serviceState: MutableStateFlow<StreamServiceState>,
    private val onEnqueueAck: (ControlAck) -> Unit,
) {
    private val orderLock = Any()

    fun updateAndEnqueue(
        camerasOverride: List<CameraInfo>? = null,
        mutate: (ActiveCameraState) -> ActiveCameraState,
    ): ActiveCameraState = synchronized(orderLock) {
        val next = serviceState.updateAndGet { state ->
            val patched = mutate(state.activeState)
            state.copy(
                cameras = camerasOverride ?: state.cameras,
                activeState = patched,
                bitrateKbps = patched.bitrateKbps,
            )
        }.activeState
        onEnqueueAck(ControlAck.success(next))
        next
    }

    fun updateWithCustomAck(
        camerasOverride: List<CameraInfo>? = null,
        ackBuilder: (ActiveCameraState) -> ControlAck?,
        mutate: (ActiveCameraState) -> ActiveCameraState,
    ): ActiveCameraState = synchronized(orderLock) {
        val next = serviceState.updateAndGet { state ->
            val patched = mutate(state.activeState)
            state.copy(
                cameras = camerasOverride ?: state.cameras,
                activeState = patched,
                bitrateKbps = patched.bitrateKbps,
            )
        }.activeState
        ackBuilder(next)?.let(onEnqueueAck)
        next
    }

    fun enqueueCurrentStateAck() {
        synchronized(orderLock) {
            onEnqueueAck(ControlAck.success(serviceState.value.activeState))
        }
    }
}
