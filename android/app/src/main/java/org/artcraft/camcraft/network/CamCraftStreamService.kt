package org.artcraft.camcraft.network

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.camera.view.PreviewView
import androidx.core.app.NotificationCompat
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.artcraft.camcraft.MainActivity
import org.artcraft.camcraft.camera.CameraEnumerator
import org.artcraft.camcraft.camera.CameraStreamManager
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
    val activeState: ActiveCameraState = defaultActiveCameraState(),
    val framesSent: Long = 0,
    val currentFps: Float = 0f,
    val bitrateKbps: Int = 12_000,
    val errorMessage: String? = null,
)

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

    private var cameraStreamManager: CameraStreamManager? = null

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

        try {
            val manager = CameraStreamManager(this) { packetType, flags, ptsUs, payload ->
                handleVideoPacketFromEncoder(packetType, flags, ptsUs, payload)
            }
            cameraStreamManager = manager
            activeInstance = this
            pendingPreviewView?.let { previewView ->
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
        cameraStreamManager?.release()
        cameraStreamManager = null
        if (activeInstance == this) {
            activeInstance = null
        }
        serviceScope.cancel()
        _serviceState.value = StreamServiceState(status = StreamConnectionStatus.STOPPED)
    }

    private fun startInForeground() {
        val notification = buildNotification("Starting CamCraft Server...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startServer() {
        if (serverJob?.isActive == true) return

        serverJob = serviceScope.launch {
            try {
                val port = CcftProtocolConstants.DEFAULT_PORT
                val socket = ServerSocket(port).apply {
                    reuseAddress = true
                }
                serverSocket = socket

                _serviceState.update {
                    it.copy(
                        status = StreamConnectionStatus.LISTENING,
                        clientAddress = null,
                        errorMessage = null,
                    )
                }
                updateNotification("Port $port • Waiting for PC connection...")
                Log.i(TAG, "CamCraft TCP Server listening on port $port")

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
                Log.e(TAG, "Fatal server socket exception", e)
                _serviceState.update {
                    it.copy(
                        status = StreamConnectionStatus.ERROR,
                        errorMessage = e.message ?: "Server error",
                    )
                }
                updateNotification("Error: ${e.message}")
            }
        }
    }

    private fun handleVideoPacketFromEncoder(
        packetType: PacketType,
        flags: Int,
        ptsUs: Long,
        payload: ByteArray,
    ) {
        val stream = activeClientOutputStream ?: return
        try {
            synchronized(clientOutputLock) {
                CcftPacket.writePacket(
                    output = stream,
                    type = packetType,
                    flags = flags,
                    ptsUs = ptsUs,
                    payload = payload,
                    offset = 0,
                    length = payload.size,
                    flush = true,
                )
            }
            onFrameSent(packetType)
        } catch (e: IOException) {
            Log.w(TAG, "Failed writing video packet to client: ${e.message}")
        }
    }

    private fun onFrameSent(packetType: PacketType) {
        if (packetType != PacketType.VIDEO_FRAME) return
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
        val clientIp = clientSocket.inetAddress?.hostAddress ?: "unknown"
        Log.i(TAG, "PC Client connected from $clientIp")

        clientSocket.tcpNoDelay = true

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

            // 5. Run reader loop to process incoming heartbeats and control commands
            runPacketReader(inputStream, outputStream, clientOutputLock)
        } catch (e: Exception) {
            Log.w(TAG, "Client session terminated: ${e.message}")
        } finally {
            cameraStreamManager?.stopStream()
            activeClientOutputStream = null

            try {
                inputStream?.close()
                outputStream?.close()
                clientSocket.close()
            } catch (ignored: IOException) {}

            Log.i(TAG, "Client disconnected, reverting to LISTENING state")
            _serviceState.update {
                it.copy(
                    status = StreamConnectionStatus.LISTENING,
                    clientAddress = null,
                    currentFps = 0f,
                )
            }
            updateNotification("Port ${CcftProtocolConstants.DEFAULT_PORT} • Waiting for PC...")
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
                    current.copy(
                        audioEnabled = cmd.enabled,
                        audioMuted = cmd.muted,
                    )
                }
                is ControlCmd.RequestKeyframe -> {
                    cameraStreamManager?.requestKeyFrame()
                    current
                }
            }

            _serviceState.update { it.copy(activeState = updated) }

            val ack = CcftPacket.controlAck(
                ptsUs = System.nanoTime() / 1_000L,
                ack = ControlAck.success(updated),
            )
            synchronized(outputLock) {
                ack.encodeTo(output, flush = true)
            }
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



    private fun stopServer() {
        try {
            serverSocket?.close()
        } catch (ignored: IOException) {}
        serverJob?.cancel()
        serverJob = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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

        const val ACTION_START = "org.artcraft.camcraft.action.START"
        const val ACTION_STOP = "org.artcraft.camcraft.action.STOP"

        @Volatile
        private var activeInstance: CamCraftStreamService? = null
        private var pendingPreviewView: PreviewView? = null

        private val _serviceState = MutableStateFlow(StreamServiceState())
        val serviceState: StateFlow<StreamServiceState> = _serviceState.asStateFlow()

        fun attachPreviewView(previewView: PreviewView) {
            pendingPreviewView = previewView
            activeInstance?.cameraStreamManager?.attachPreviewView(previewView)
        }

        fun detachPreviewView() {
            pendingPreviewView = null
            activeInstance?.cameraStreamManager?.detachPreviewView()
        }

        fun start(context: Context) {
            val intent = Intent(context, CamCraftStreamService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
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
            val manufacturer = Build.MANUFACTURER?.replaceFirstChar { it.uppercase() } ?: "Android"
            val model = Build.MODEL ?: "CamCraft Device"
            val deviceName = if (model.startsWith(manufacturer, ignoreCase = true)) {
                model
            } else {
                "$manufacturer $model".trim()
            }

            val cameras = CameraEnumerator.enumerateCameras(context)

            return ControlHello(
                deviceName = deviceName.ifBlank { "Android CamCraft Device" },
                protocolVersion = CcftProtocolConstants.VERSION.toInt(),
                cameras = cameras,
                activeState = activeState,
            )
        }
    }
}
