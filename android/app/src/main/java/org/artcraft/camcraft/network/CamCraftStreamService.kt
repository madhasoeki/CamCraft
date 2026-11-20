package org.artcraft.camcraft.network

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.artcraft.camcraft.MainActivity
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

    @Volatile
    private var activeClientSocket: Socket? = null
    private val isStopping = AtomicBoolean(false)
    private val lifecycleLock = Any()

    private var cameraStreamManager: CameraStreamManager? = null
    private var audioStreamEngine: AudioStreamEngine? = null
    private var mdnsAdvertiser: MdnsAdvertiser? = null
    private var wifiNetworkMonitor: WifiNetworkMonitor? = null

    @Volatile
    private var activeClientOutputStream: OutputStream? = null
    private val clientOutputLock = Any()

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
            val previousState = _serviceState.getAndUpdate { it.copy(localIpAddress = updatedIp) }
            val currentState = _serviceState.value
            if (currentState.status == StreamConnectionStatus.LISTENING &&
                updatedIp != previousState.localIpAddress
            ) {
                if (!updatedIp.isNullOrBlank()) {
                    mdnsAdvertiser?.register(
                        deviceName = resolveDeviceName(),
                        port = currentState.listenPort,
                        localIpAddress = updatedIp,
                    )
                }
                updateNotification(buildListeningNotificationText(updatedIp, currentState.listenPort))
            }
        }

        try {
            val appContext = applicationContext ?: this
            val cameras = CameraEnumerator.enumerateCameras(appContext)
            _serviceState.update { it.copy(cameras = cameras) }
            val manager = CameraStreamManager(appContext) { packetType, flags, ptsUs, payload ->
                handleVideoPacketFromEncoder(packetType, flags, ptsUs, payload)
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
        stopServer()
        mdnsAdvertiser?.unregister()
        mdnsAdvertiser = null
        wifiNetworkMonitor?.stop()
        wifiNetworkMonitor = null
        audioStreamEngine?.release()
        audioStreamEngine = null
        cameraStreamManager?.release()
        cameraStreamManager = null
        pendingPreviewView = null
        if (activeInstance == this) {
            activeInstance = null
        }
        serviceScope.cancel()
        val preservedIp = LocalIpResolver.resolveLocalIpv4Address(this)
            ?: _serviceState.value.localIpAddress
        _serviceState.value = StreamServiceState(
            status = StreamConnectionStatus.STOPPED,
            localIpAddress = preservedIp,
        )
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
                _serviceState.update {
                    it.copy(
                        status = StreamConnectionStatus.LISTENING,
                        clientAddress = null,
                        localIpAddress = currentIp,
                        listenPort = port,
                        errorMessage = null,
                    )
                }
                mdnsAdvertiser?.register(
                    deviceName = resolveDeviceName(),
                    port = port,
                    localIpAddress = currentIp,
                )
                updateNotification(buildListeningNotificationText(currentIp, port))
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
                _serviceState.update {
                    it.copy(
                        status = StreamConnectionStatus.ERROR,
                        errorMessage = e.message ?: "Server error",
                    )
                }
                updateNotification("Error: ${e.message}")
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
                    offset = 0,
                    length = length,
                    flush = true,
                )
            }
            true
        } catch (e: IOException) {
            Log.w(TAG, "Failed writing $tagLabel packet to client: ${e.message}")
            if (activeClientOutputStream === stream) {
                activeClientOutputStream = null
            }
            try {
                activeClientSocket?.close()
            } catch (_: IOException) {}
            false
        }
    }

    private fun handleVideoPacketFromEncoder(
        packetType: PacketType,
        flags: Int,
        ptsUs: Long,
        payload: ByteArray,
    ) {
        if (sendStreamPacket(packetType, flags, ptsUs, payload, payload.size, "video")) {
            onFrameSent(packetType)
        }
    }

    private fun handleAudioPacketFromEngine(
        packetType: PacketType,
        ptsUs: Long,
        payload: ByteArray,
        length: Int,
    ) {
        sendStreamPacket(packetType, CcftFlags.NONE, ptsUs, payload, length, "audio")
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
        activeClientSocket = clientSocket
        if (isStopping.get()) {
            try {
                clientSocket.close()
            } catch (_: IOException) {}
            if (activeClientSocket === clientSocket) {
                activeClientSocket = null
            }
            return
        }

        val clientIp = clientSocket.inetAddress?.hostAddress ?: "unknown"
        Log.i(TAG, "PC Client connected from $clientIp")

        clientSocket.tcpNoDelay = true
        clientSocket.keepAlive = true
        clientSocket.soTimeout = CLIENT_READ_TIMEOUT_MS

        _serviceState.update {
            it.copy(
                status = StreamConnectionStatus.CONNECTED,
                clientAddress = clientIp,
            )
        }
        updateNotification("Connected to PC ($clientIp) • 60 FPS")

        var inputStream: DataInputStream? = null
        var outputStream: OutputStream? = null

        try {
            outputStream = BufferedOutputStream(clientSocket.getOutputStream(), 65536)
            inputStream = DataInputStream(BufferedInputStream(clientSocket.getInputStream(), 16384))

            // 1. Send CONTROL_HELLO (0x10)
            val hello = createDeviceHello(_serviceState.value.activeState, this)
            val helloPacket = CcftPacket.controlHello(System.nanoTime() / 1_000L, hello)
            synchronized(clientOutputLock) {
                helloPacket.encodeTo(outputStream, flush = true)
            }
            Log.i(TAG, "Sent CONTROL_HELLO to client")

            // 2. Set active output stream for encoder video packets
            activeClientOutputStream = outputStream

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
                    val updatedAudioState = activeState.copy(audioEnabled = false)
                    _serviceState.update { state ->
                        state.copy(activeState = updatedAudioState)
                    }
                    sendControlAck(updatedAudioState, outputStream, clientOutputLock)
                }
            }

            // 6. Run reader loop to process incoming heartbeats and control commands
            runPacketReader(inputStream, outputStream, clientOutputLock)
        } catch (e: Exception) {
            Log.w(TAG, "Client session terminated: ${e.message}")
        } finally {
            audioStreamEngine?.stop()
            cameraStreamManager?.stopStream()
            activeClientOutputStream = null
            if (activeClientSocket === clientSocket) {
                activeClientSocket = null
            }

            try {
                inputStream?.close()
            } catch (_: IOException) {}
            try {
                outputStream?.close()
            } catch (_: IOException) {}
            try {
                clientSocket.close()
            } catch (_: IOException) {}

            val isActiveCoroutine = currentCoroutineContext().isActive
            val revertedToListening = synchronized(lifecycleLock) {
                val canRevertToListening = !isStopping.get() &&
                    isActiveCoroutine &&
                    serverSocket?.isClosed == false
                if (canRevertToListening) {
                    val refreshedIp = LocalIpResolver.resolveLocalIpv4Address(this)
                    val listenPort = _serviceState.value.listenPort
                    _serviceState.update {
                        it.copy(
                            status = StreamConnectionStatus.LISTENING,
                            clientAddress = null,
                            localIpAddress = refreshedIp,
                            currentFps = 0f,
                        )
                    }
                    updateNotification(buildListeningNotificationText(refreshedIp, listenPort))
                    true
                } else {
                    false
                }
            }
            if (revertedToListening) {
                Log.i(TAG, "Client disconnected, reverting to LISTENING state")
            } else {
                Log.i(TAG, "Client disconnected during server stop; skipping LISTENING revert")
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
        while (serviceScope.isActive) {
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

            val current = _serviceState.value.activeState
            val updated = when (cmd) {
                is ControlCmd.ConfigureStream -> {
                    cameraStreamManager?.rebindCamera(
                        cameraId = cmd.cameraId,
                        width = cmd.width,
                        height = cmd.height,
                        fps = cmd.fps,
                        bitrateKbps = cmd.bitrateKbps,
                    )
                    current.copy(
                        cameraId = cmd.cameraId,
                        width = cmd.width,
                        height = cmd.height,
                        fps = cmd.fps,
                        bitrateKbps = cmd.bitrateKbps,
                    )
                }
                is ControlCmd.SetCameraParams -> {
                    cmd.zoomRatio?.let { cameraStreamManager?.setZoomRatio(it) }
                    cmd.exposureEv?.let { cameraStreamManager?.setExposureEv(it) }
                    cmd.torchOn?.let { cameraStreamManager?.setTorch(it) }
                    cmd.autofocus?.let { cameraStreamManager?.setAutoFocus(it) }

                    current.copy(
                        zoomRatio = cmd.zoomRatio ?: current.zoomRatio,
                        exposureEv = cmd.exposureEv ?: current.exposureEv,
                        autofocus = cmd.autofocus ?: current.autofocus,
                        torchOn = cmd.torchOn ?: current.torchOn,
                        mirrorHorizontal = cmd.mirrorHorizontal ?: current.mirrorHorizontal,
                        rotationDeg = cmd.rotationDeg ?: current.rotationDeg,
                    )
                }
                is ControlCmd.SetAudioState -> {
                    if (cmd.enabled) {
                        audioStreamEngine?.setMuted(cmd.muted)
                        val engineActive = audioStreamEngine?.isActive == true ||
                            (audioStreamEngine?.start(initiallyMuted = cmd.muted) == true)
                        if (!engineActive) {
                            val fallbackState = current.copy(
                                audioEnabled = false,
                                audioMuted = cmd.muted,
                            )
                            _serviceState.update {
                                it.copy(activeState = fallbackState, bitrateKbps = fallbackState.bitrateKbps)
                            }
                            val errorAck = CcftPacket.controlAck(
                                ptsUs = System.nanoTime() / 1_000L,
                                ack = ControlAck(
                                    ok = false,
                                    error = "Microphone capture unavailable (grant RECORD_AUDIO permission on Android)",
                                    activeState = fallbackState,
                                ),
                            )
                            synchronized(outputLock) {
                                errorAck.encodeTo(output, flush = true)
                            }
                            return
                        }
                        current.copy(
                            audioEnabled = true,
                            audioMuted = cmd.muted,
                        )
                    } else {
                        audioStreamEngine?.stop()
                        current.copy(
                            audioEnabled = false,
                            audioMuted = cmd.muted,
                        )
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
                    current
                }
            }

            _serviceState.update { it.copy(activeState = updated, bitrateKbps = updated.bitrateKbps) }

            sendControlAck(updated, output, outputLock)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse or apply CONTROL_CMD", e)
            val errorAck = CcftPacket.controlAck(
                ptsUs = System.nanoTime() / 1_000L,
                ack = ControlAck.failure(e.message ?: "Unknown control error"),
            )
            synchronized(outputLock) {
                errorAck.encodeTo(output, flush = true)
            }
        }
    }



    private fun sendControlAck(
        state: ActiveCameraState,
        output: OutputStream,
        outputLock: Any,
    ) {
        val ack = CcftPacket.controlAck(
            ptsUs = System.nanoTime() / 1_000L,
            ack = ControlAck.success(state),
        )
        synchronized(outputLock) {
            ack.encodeTo(output, flush = true)
        }
    }

    private fun pushActiveStateToClient(updated: ActiveCameraState) {
        val stream = activeClientOutputStream ?: return
        serviceScope.launch {
            try {
                sendControlAck(updated, stream, clientOutputLock)
            } catch (e: IOException) {
                Log.w(TAG, "Failed to push CONTROL_ACK to PC: ${e.message}")
            }
        }
    }

    fun switchCameraFromUi() {
        val state = _serviceState.value
        val cameras = state.cameras.ifEmpty { CameraEnumerator.enumerateCameras(this) }
        if (cameras.isEmpty()) return

        val currentIdx = cameras.indexOfFirst { it.id == state.activeState.cameraId }
        val nextCam = cameras[(currentIdx + 1) % cameras.size]
        val current = state.activeState
        val clampedZoom = current.zoomRatio.coerceIn(1.0f, nextCam.maxZoom.coerceAtLeast(1.0f))
        val torchAllowed = if (nextCam.hasFlash) current.torchOn else false

        cameraStreamManager?.setZoomRatio(clampedZoom)
        cameraStreamManager?.setTorch(torchAllowed)
        cameraStreamManager?.rebindCamera(
            cameraId = nextCam.id,
            width = current.width,
            height = current.height,
            fps = current.fps,
            bitrateKbps = current.bitrateKbps,
        )

        val updated = current.copy(
            cameraId = nextCam.id,
            zoomRatio = clampedZoom,
            torchOn = torchAllowed,
        )
        _serviceState.update { it.copy(cameras = cameras, activeState = updated) }
        pushActiveStateToClient(updated)
    }

    fun toggleTorchFromUi() {
        val state = _serviceState.value
        val activeCam = state.cameras.find { it.id == state.activeState.cameraId }
        if (activeCam != null && !activeCam.hasFlash) return

        val nextTorch = !state.activeState.torchOn
        cameraStreamManager?.setTorch(nextTorch)
        val updated = state.activeState.copy(torchOn = nextTorch)
        _serviceState.update { it.copy(activeState = updated) }
        pushActiveStateToClient(updated)
    }

    fun toggleMuteFromUi() {
        val current = _serviceState.value.activeState
        val updated = current.copy(audioMuted = !current.audioMuted)
        audioStreamEngine?.setMuted(updated.audioMuted)
        _serviceState.update { it.copy(activeState = updated) }
        pushActiveStateToClient(updated)
    }

    private fun stopServer() {
        synchronized(lifecycleLock) {
            isStopping.set(true)
            mdnsAdvertiser?.unregister()
            try {
                activeClientSocket?.close()
            } catch (ignored: IOException) {}
            activeClientSocket = null
            try {
                serverSocket?.close()
            } catch (ignored: IOException) {}
            serverSocket = null
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
            .setSmallIcon(android.R.drawable.ic_menu_camera)
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
        const val ACTION_STOP = "org.artcraft.camcraft.action.STOP"

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
            pendingPreviewView = WeakReference(previewView)
            activeInstance?.cameraStreamManager?.attachPreviewView(previewView)
        }

        fun detachPreviewView() {
            pendingPreviewView = null
            activeInstance?.cameraStreamManager?.detachPreviewView()
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
    }
}
