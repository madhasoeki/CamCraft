package org.artcraft.camcraft.network

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.net.BindException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.artcraft.camcraft.camera.CameraStreamManager
import org.artcraft.camcraft.protocol.CameraInfo
import org.artcraft.camcraft.protocol.CcftFlags
import org.artcraft.camcraft.protocol.CcftPacket
import org.artcraft.camcraft.protocol.CcftProtocolConstants
import org.artcraft.camcraft.protocol.ControlAck
import org.artcraft.camcraft.protocol.ControlCmd
import org.artcraft.camcraft.protocol.PacketType
import org.artcraft.camcraft.protocol.ResolutionOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CamCraftStreamServiceTest {

    @Test
    fun defaultActiveCameraStateConformsTo1080p60Spec() {
        val state = defaultActiveCameraState()
        assertEquals("0", state.cameraId)
        assertEquals(1920, state.width)
        assertEquals(1080, state.height)
        assertEquals(60, state.fps)
        assertEquals(12_000, state.bitrateKbps)
        assertEquals(1.0f, state.zoomRatio, 0.001f)
        assertEquals(0.0f, state.exposureEv, 0.001f)
        assertTrue(state.autofocus)
        assertFalse(state.torchOn)
        assertFalse(state.mirrorHorizontal)
        assertEquals(0, state.rotationDeg)
        assertTrue(state.audioEnabled)
        assertFalse(state.audioMuted)
    }

    @Test
    fun createDeviceHelloProducesValidHandshake() {
        val state = defaultActiveCameraState()
        val hello = CamCraftStreamService.createDeviceHello(state)

        assertNotNull(hello.deviceName)
        assertTrue(hello.deviceName.isNotBlank())
        assertEquals(CcftProtocolConstants.VERSION.toInt(), hello.protocolVersion)
        assertEquals(2, hello.cameras.size)

        val rearCam = hello.cameras.first { it.facing == "back" }
        assertEquals("0", rearCam.id)
        assertTrue(rearCam.resolutions.any { it.width == 1920 && it.height == 1080 && it.maxFps == 60 })
        assertTrue(rearCam.maxZoom >= 1.0f)
        assertEquals(2, rearCam.evRange.size)

        val frontCam = hello.cameras.first { it.facing == "front" }
        assertEquals("1", frontCam.id)
        assertTrue(frontCam.resolutions.any { it.width == 1920 && it.height == 1080 })

        assertEquals(state, hello.activeState)
    }

    @Test
    fun streamServiceStateInitialStateIsStopped() {
        val initial = StreamServiceState()
        assertEquals(StreamConnectionStatus.STOPPED, initial.status)
        assertEquals(0L, initial.framesSent)
        assertEquals(0f, initial.currentFps, 0.001f)
    }

    @Test
    fun computeTransformFlagsCombinesRotationAndMirror() {
        assertEquals(CcftFlags.NONE, CamCraftStreamService.computeTransformFlags(false, 0))
        assertEquals(CcftFlags.MIRROR_H, CamCraftStreamService.computeTransformFlags(true, 0))
        assertEquals(CcftFlags.ROTATE_90, CamCraftStreamService.computeTransformFlags(false, 90))
        assertEquals(
            CcftFlags.ROTATE_180 or CcftFlags.MIRROR_H,
            CamCraftStreamService.computeTransformFlags(true, 180),
        )
        assertEquals(
            CcftFlags.ROTATE_270 or CcftFlags.MIRROR_H,
            CamCraftStreamService.computeTransformFlags(true, 270),
        )
        assertEquals(CcftFlags.NONE, CamCraftStreamService.computeTransformFlags(false, 360))
    }

    @Test
    fun buildConnectedNotificationTextReflectsActiveResolutionAndFps() {
        val defaultState = defaultActiveCameraState()
        assertEquals(
            "Connected (192.168.1.42) • 1920x1080 @ 60 FPS",
            CamCraftStreamService.buildConnectedNotificationText("192.168.1.42", defaultState),
        )

        val uhd30State = defaultState.copy(width = 3840, height = 2160, fps = 30)
        assertEquals(
            "Connected (127.0.0.1) • 3840x2160 @ 30 FPS",
            CamCraftStreamService.buildConnectedNotificationText("127.0.0.1", uhd30State),
        )
    }

    @Test
    fun staleStreamWriteErrorDoesNotCloseNewActiveSession() {
        val stateFlow = MutableStateFlow(StreamServiceState())
        val coordinator = ControlStateAckCoordinator(stateFlow) {}
        val activeSessionRef = AtomicReference<ClientSessionHandle?>(null)
        val lifecycleLock = Any()

        val writeEntered = CountDownLatch(1)
        val releaseFailure = CountDownLatch(1)
        val sessionACloses = AtomicInteger(0)
        val sessionBCloses = AtomicInteger(0)

        val failingOutputA = object : OutputStream() {
            override fun write(b: Int) {
                writeEntered.countDown()
                releaseFailure.await(2, TimeUnit.SECONDS)
                throw IOException("session A broken pipe")
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                writeEntered.countDown()
                releaseFailure.await(2, TimeUnit.SECONDS)
                throw IOException("session A broken pipe")
            }
        }
        val outputB = ByteArrayOutputStream()

        val sessionA = ClientSessionHandle(
            sessionId = 1L,
            output = failingOutputA,
            outputLock = Any(),
            closeTransport = { sessionACloses.incrementAndGet() },
        )
        val sessionB = ClientSessionHandle(
            sessionId = 2L,
            output = outputB,
            outputLock = Any(),
            closeTransport = { sessionBCloses.incrementAndGet() },
        )

        synchronized(lifecycleLock) {
            activeSessionRef.set(sessionA)
            coordinator.setActiveSession(sessionA)
        }

        val worker = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-stale-stream-write").apply { isDaemon = true }
        }
        try {
            val writeResultFuture = worker.submit<Boolean> {
                CamCraftStreamService.sendStreamPacketForSession(
                    activeSessionProvider = { activeSessionRef.get() },
                    clearActiveSessionIfCurrent = { failed ->
                        synchronized(lifecycleLock) {
                            if (activeSessionRef.get() === failed) {
                                activeSessionRef.set(null)
                            }
                            coordinator.clearActiveSessionIfCurrent(failed)
                        }
                    },
                    sessionId = 1L,
                    packetType = PacketType.VIDEO_FRAME,
                    flags = CcftFlags.KEYFRAME,
                    ptsUs = 1_000L,
                    payload = byteArrayOf(0, 0, 0, 1, 0x65),
                    offset = 0,
                    length = 5,
                    tagLabel = "video",
                )
            }

            assertTrue(writeEntered.await(2, TimeUnit.SECONDS))

            // Switch ownership to session B while session A's write is in flight.
            synchronized(lifecycleLock) {
                activeSessionRef.set(sessionB)
                coordinator.setActiveSession(sessionB)
            }

            // Unblock A's write so it fails with IOException after ownership changed to B.
            releaseFailure.countDown()
            assertFalse(writeResultFuture.get(2, TimeUnit.SECONDS))

            assertTrue(sessionA.isClosed)
            assertEquals(1, sessionACloses.get())
            assertFalse(sessionB.isClosed)
            assertEquals(0, sessionBCloses.get())
            assertTrue(activeSessionRef.get() === sessionB)
            assertTrue(coordinator.currentOwnedSession() === sessionB)

            // Session B continues writing packets normally.
            assertTrue(
                CamCraftStreamService.sendStreamPacketForSession(
                    activeSessionProvider = { activeSessionRef.get() },
                    clearActiveSessionIfCurrent = { failed ->
                        synchronized(lifecycleLock) {
                            if (activeSessionRef.get() === failed) {
                                activeSessionRef.set(null)
                            }
                            coordinator.clearActiveSessionIfCurrent(failed)
                        }
                    },
                    sessionId = 2L,
                    packetType = PacketType.VIDEO_FRAME,
                    flags = CcftFlags.NONE,
                    ptsUs = 2_000L,
                    payload = byteArrayOf(1, 2, 3),
                    offset = 0,
                    length = 3,
                    tagLabel = "video",
                ),
            )
            assertTrue(outputB.size() > CcftProtocolConstants.HEADER_SIZE_BYTES)

            // When current owner session B fails with IOException, B closes and ownership clears.
            val failingSessionB = ClientSessionHandle(
                sessionId = 2L,
                output = object : OutputStream() {
                    override fun write(b: Int) = throw IOException("session B broken pipe")
                    override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("session B broken pipe")
                },
                outputLock = Any(),
                closeTransport = { sessionBCloses.incrementAndGet() },
            )
            synchronized(lifecycleLock) {
                activeSessionRef.set(failingSessionB)
                coordinator.setActiveSession(failingSessionB)
            }
            assertFalse(
                CamCraftStreamService.sendStreamPacketForSession(
                    activeSessionProvider = { activeSessionRef.get() },
                    clearActiveSessionIfCurrent = { failed ->
                        synchronized(lifecycleLock) {
                            if (activeSessionRef.get() === failed) {
                                activeSessionRef.set(null)
                            }
                            coordinator.clearActiveSessionIfCurrent(failed)
                        }
                    },
                    sessionId = 2L,
                    packetType = PacketType.VIDEO_FRAME,
                    flags = CcftFlags.NONE,
                    ptsUs = 3_000L,
                    payload = byteArrayOf(4, 5),
                    offset = 0,
                    length = 2,
                    tagLabel = "video",
                ),
            )
            assertTrue(failingSessionB.isClosed)
            assertEquals(1, sessionBCloses.get())
            assertNull(activeSessionRef.get())
            assertNull(coordinator.currentOwnedSession())
        } finally {
            worker.shutdownNow()
        }
    }

    @Test
    fun computePostSessionTransitionCoversActiveNullErrorAndStopping() {
        assertEquals(
            CamCraftStreamService.Companion.PostSessionStatus.LISTENING,
            CamCraftStreamService.computePostSessionTransition(
                isStopping = false,
                isActiveCoroutine = true,
                hasActiveServerSocket = true,
                serverBindError = null,
            ),
        )
        assertEquals(
            CamCraftStreamService.Companion.PostSessionStatus.STOPPED,
            CamCraftStreamService.computePostSessionTransition(
                isStopping = false,
                isActiveCoroutine = true,
                hasActiveServerSocket = false,
                serverBindError = null,
            ),
        )
        assertEquals(
            CamCraftStreamService.Companion.PostSessionStatus.ERROR,
            CamCraftStreamService.computePostSessionTransition(
                isStopping = false,
                isActiveCoroutine = true,
                hasActiveServerSocket = false,
                serverBindError = "Address already in use",
            ),
        )
        assertEquals(
            CamCraftStreamService.Companion.PostSessionStatus.STOPPED,
            CamCraftStreamService.computePostSessionTransition(
                isStopping = true,
                isActiveCoroutine = true,
                hasActiveServerSocket = true,
                serverBindError = "Address already in use",
            ),
        )
    }

    @Test
    fun configureStreamClampsZoomEvAndTorchToTargetCameraCapabilities() {
        val rearState = defaultActiveCameraState().copy(
            cameraId = "0",
            zoomRatio = 5.5f,
            exposureEv = 3.5f,
            torchOn = true,
        )
        val frontCam = CameraInfo.create(
            id = "1",
            facing = "front",
            label = "Front Selfie",
            resolutions = listOf(ResolutionOption(1920, 1080, 30)),
            maxZoom = 2.0f,
            minEv = -2.0f,
            maxEv = 2.0f,
            hasFlash = false,
        )
        val cmd = ControlCmd.ConfigureStream(
            cameraId = "1",
            width = 1280,
            height = 720,
            fps = 30,
            bitrateKbps = 6_000,
        )

        val updated = CamCraftStreamService.applyConfigureStreamPatch(rearState, cmd, frontCam)
        assertEquals("1", updated.cameraId)
        assertEquals(1280, updated.width)
        assertEquals(720, updated.height)
        assertEquals(30, updated.fps)
        assertEquals(6_000, updated.bitrateKbps)
        assertEquals(2.0f, updated.zoomRatio, 0.001f)
        assertEquals(2.0f, updated.exposureEv, 0.001f)
        assertFalse(updated.torchOn)
    }

    @Test
    fun controlStateCoordinatorPreservesPartialUpdatesAndAckOrderUnderConcurrency() = runBlocking {
        val flow = MutableStateFlow(StreamServiceState())
        val ackChannel = Channel<ControlAck>(Channel.UNLIMITED)
        val coordinator = ControlStateAckCoordinator(flow) { ack ->
            ackChannel.trySend(ack)
        }
        val orderedOut = ArrayList<ControlAck>()
        val consumerJob = launch {
            for (ack in ackChannel) {
                orderedOut.add(ack)
            }
        }

        val startGate = CountDownLatch(1)
        val ioThread = Thread {
            startGate.await(1, TimeUnit.SECONDS)
            for (i in 1..40) {
                coordinator.updateAndEnqueue { cur ->
                    CamCraftStreamService.applyCameraParamsPatch(
                        cur,
                        ControlCmd.SetCameraParams(zoomRatio = 1.0f + i * 0.05f),
                    )
                }
            }
        }
        val uiThread = Thread {
            startGate.await(1, TimeUnit.SECONDS)
            for (i in 1..40) {
                coordinator.updateAndEnqueue { cur ->
                    cur.copy(audioMuted = (i % 2 == 1))
                }
            }
        }

        val finalState: org.artcraft.camcraft.protocol.ActiveCameraState
        try {
            ioThread.start()
            uiThread.start()
            startGate.countDown()
            ioThread.join(2_000)
            uiThread.join(2_000)

            val ioStillAlive = ioThread.isAlive
            val uiStillAlive = uiThread.isAlive
            if (ioStillAlive) {
                ioThread.interrupt()
                ioThread.join(200)
            }
            if (uiStillAlive) {
                uiThread.interrupt()
                uiThread.join(200)
            }
            assertFalse("ioThread failed to finish within 2000ms", ioStillAlive || ioThread.isAlive)
            assertFalse("uiThread failed to finish within 2000ms", uiStillAlive || uiThread.isAlive)

            // Apply final distinct state and verify ACKs arrive monotonically in state-commit order.
            finalState = coordinator.updateAndEnqueue { cur ->
                CamCraftStreamService.applyCameraParamsPatch(
                    cur,
                    ControlCmd.SetCameraParams(rotationDeg = 90, mirrorHorizontal = true),
                )
            }
            ackChannel.close()
            consumerJob.join()
        } finally {
            ackChannel.close()
            if (consumerJob.isActive) {
                consumerJob.cancel()
            }
        }

        assertEquals(81, orderedOut.size)
        assertEquals(finalState, orderedOut.last().activeState)
        assertEquals(3.0f, flow.value.activeState.zoomRatio, 0.001f)
        assertFalse(flow.value.activeState.audioMuted) // 40 flips from false -> false
        assertEquals(90, flow.value.activeState.rotationDeg)
        assertTrue(flow.value.activeState.mirrorHorizontal)

        val zooms = orderedOut.mapNotNull { it.activeState?.zoomRatio }
        for (idx in 1 until zooms.size) {
            assertTrue("zoom ACKs must never regress", zooms[idx] >= zooms[idx - 1] - 0.0001f)
        }

        // Prepend initial false and collapse consecutive duplicate audioMuted values to verify all 40 transitions.
        val collapsedMutes = buildList {
            add(false)
            for (ack in orderedOut) {
                val muted = ack.activeState!!.audioMuted
                if (last() != muted) {
                    add(muted)
                }
            }
        }
        assertEquals(41, collapsedMutes.size)
        for (idx in collapsedMutes.indices) {
            assertEquals(
                "Expected alternating audioMuted transition at index $idx",
                idx % 2 == 1,
                collapsedMutes[idx],
            )
        }
    }

    @Test
    fun wifiIpChangeTransitionCoversValidBlankAndStopping() {
        assertEquals(
            CamCraftStreamService.Companion.WifiIpChangeAction.REGISTER_MDNS,
            CamCraftStreamService.decideWifiIpChangeAction(
                isStopping = false,
                status = StreamConnectionStatus.LISTENING,
                previousIp = "192.168.1.10",
                updatedIp = "192.168.1.20",
            ),
        )
        assertEquals(
            CamCraftStreamService.Companion.WifiIpChangeAction.UNREGISTER_MDNS,
            CamCraftStreamService.decideWifiIpChangeAction(
                isStopping = false,
                status = StreamConnectionStatus.LISTENING,
                previousIp = "192.168.1.20",
                updatedIp = null,
            ),
        )
        assertEquals(
            CamCraftStreamService.Companion.WifiIpChangeAction.UNREGISTER_MDNS,
            CamCraftStreamService.decideWifiIpChangeAction(
                isStopping = false,
                status = StreamConnectionStatus.LISTENING,
                previousIp = "192.168.1.20",
                updatedIp = "",
            ),
        )
        assertEquals(
            CamCraftStreamService.Companion.WifiIpChangeAction.IGNORE,
            CamCraftStreamService.decideWifiIpChangeAction(
                isStopping = true,
                status = StreamConnectionStatus.LISTENING,
                previousIp = "192.168.1.10",
                updatedIp = "192.168.1.20",
            ),
        )
        assertEquals(
            CamCraftStreamService.Companion.WifiIpChangeAction.IGNORE,
            CamCraftStreamService.decideWifiIpChangeAction(
                isStopping = false,
                status = StreamConnectionStatus.CONNECTED,
                previousIp = "192.168.1.10",
                updatedIp = null,
            ),
        )
    }

    @Test
    fun clientSessionHandleEnforcesBoundedCapacityAndClosesStalledTransportOnOverflow() {
        val transportCloses = AtomicInteger(0)
        val overflowCallbacks = AtomicInteger(0)
        val handle = ClientSessionHandle(
            sessionId = 10L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = { transportCloses.incrementAndGet() },
            ackQueueCapacity = CamCraftStreamService.CONTROL_ACK_QUEUE_CAPACITY,
            onQueueOverflow = { overflowCallbacks.incrementAndGet() },
        )

        repeat(CamCraftStreamService.CONTROL_ACK_QUEUE_CAPACITY) {
            assertTrue(handle.enqueueAck(ControlAck.success(defaultActiveCameraState())))
        }
        assertFalse(handle.isClosed)
        assertEquals(0, transportCloses.get())
        assertEquals(0, overflowCallbacks.get())

        // Next ACK exceeds bounded capacity -> triggers overflow callback and closes stalled session once.
        assertFalse(handle.enqueueAck(ControlAck.success(defaultActiveCameraState())))
        assertTrue(handle.isClosed)
        assertTrue(handle.ackChannel.trySend(ControlAck.success(defaultActiveCameraState())).isClosed)
        assertEquals(1, transportCloses.get())
        assertEquals(1, overflowCallbacks.get())

        // Subsequent enqueue after close returns false without re-triggering overflow/close.
        assertFalse(handle.enqueueAck(ControlAck.success(defaultActiveCameraState())))
        assertEquals(1, transportCloses.get())
        assertEquals(1, overflowCallbacks.get())
    }

    @Test
    fun clientSessionHandleFailureAndStaleSessionPacketIsolation() {
        val usbCloses = AtomicInteger(0)
        val tcpCloses = AtomicInteger(0)
        val usbSession = ClientSessionHandle(
            sessionId = 1L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = { usbCloses.incrementAndGet() },
        )
        val tcpSession = ClientSessionHandle(
            sessionId = 2L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = { tcpCloses.incrementAndGet() },
        )

        // Closing USB session (e.g., on CONTROL_ACK or stream IOException) never touches pending/newer TCP session.
        usbSession.close()
        assertTrue(usbSession.isClosed)
        assertFalse(tcpSession.isClosed)
        assertEquals(1, usbCloses.get())
        assertEquals(0, tcpCloses.get())

        // Stale session 1 video/audio packets are dropped when session 2 is active.
        assertNull(CamCraftStreamService.resolveActiveSessionForPacket(tcpSession, packetSessionId = 1L))
        assertNull(CamCraftStreamService.resolveActiveSessionForPacket(tcpSession, packetSessionId = 0L))
        assertEquals(
            tcpSession,
            CamCraftStreamService.resolveActiveSessionForPacket(tcpSession, packetSessionId = 2L),
        )
    }

    @Test
    fun configureStreamRejectsUnknownCameraIdWithoutMutatingState() {
        val initial = defaultActiveCameraState().copy(cameraId = "0", width = 1920, height = 1080)
        val cameras = listOf(
            CameraInfo.create(
                id = "0",
                facing = "back",
                label = "Rear",
                resolutions = listOf(ResolutionOption(1920, 1080, 60)),
                maxZoom = 8.0f,
                minEv = -4.0f,
                maxEv = 4.0f,
                hasFlash = true,
            ),
            CameraInfo.create(
                id = "1",
                facing = "front",
                label = "Front",
                resolutions = listOf(ResolutionOption(1280, 720, 30)),
                maxZoom = 2.0f,
                minEv = -2.0f,
                maxEv = 2.0f,
                hasFlash = false,
            ),
        )

        val (unknownCam, errorAck) = CamCraftStreamService.validateConfigureStreamCamera(
            cameras = cameras,
            cameraId = "99",
            currentState = initial,
        )
        assertNull(unknownCam)
        assertNotNull(errorAck)
        assertFalse(errorAck!!.ok)
        assertTrue(errorAck.error!!.contains("99"))
        assertEquals(initial, errorAck.activeState)

        val (validCam, validError) = CamCraftStreamService.validateConfigureStreamCamera(
            cameras = cameras,
            cameraId = "1",
            currentState = initial,
        )
        assertNotNull(validCam)
        assertEquals("1", validCam!!.id)
        assertNull(validError)
    }

    @Test
    fun closeTransportBeforeStreamsAlwaysClosesTransportBeforeWrappers() {
        val order = mutableListOf<String>()
        val input = object : InputStream() {
            override fun read(): Int = -1
            override fun close() {
                order.add("input")
                throw IOException("simulated wrapper close error")
            }
        }
        val output = object : OutputStream() {
            override fun write(b: Int) {}
            override fun close() {
                order.add("output")
            }
        }

        CamCraftStreamService.closeTransportBeforeStreams(
            closeTransport = { order.add("transport") },
            inputStream = input,
            outputStream = output,
        )

        assertEquals(listOf("transport", "input", "output"), order)
    }

    @Test
    fun wifiCallbackAndShutdownSerializeUnderLifecycleLock() {
        val events = Collections.synchronizedList(mutableListOf<String>())
        val lock = Any()
        val stopping = AtomicBoolean(false)
        val state = MutableStateFlow(
            StreamServiceState(
                status = StreamConnectionStatus.LISTENING,
                localIpAddress = "192.168.1.10",
            ),
        )

        repeat(25) { iter ->
            stopping.set(false)
            events.clear()
            state.value = StreamServiceState(
                status = StreamConnectionStatus.LISTENING,
                localIpAddress = "192.168.1.10",
            )
            val gate = CountDownLatch(1)
            val wifiThread = Thread {
                gate.await(1, TimeUnit.SECONDS)
                CamCraftStreamService.handleWifiIpChangeUnderLock(
                    lock = lock,
                    isStopping = { stopping.get() },
                    serviceState = state,
                    updatedIp = "192.168.1.${20 + iter}",
                    onRegisterMdns = { _, ip -> events.add("register:$ip") },
                    onUnregisterMdns = { events.add("unregister") },
                    onUpdateNotification = { _, _ -> },
                )
            }
            val shutdownThread = Thread {
                gate.await(1, TimeUnit.SECONDS)
                CamCraftStreamService.shutdownWifiAndMdnsUnderLock(
                    lock = lock,
                    setStopping = { stopping.set(true) },
                    stopWifiMonitor = {},
                    resetServiceState = {
                        state.value = StreamServiceState(status = StreamConnectionStatus.STOPPED)
                    },
                    unregisterMdns = { events.add("shutdown_unregister") },
                )
            }
            wifiThread.start()
            shutdownThread.start()
            gate.countDown()
            wifiThread.join(2_000)
            shutdownThread.join(2_000)

            assertFalse(wifiThread.isAlive)
            assertFalse(shutdownThread.isAlive)
            val shutdownIdx = events.indexOf("shutdown_unregister")
            assertTrue(shutdownIdx >= 0)
            val registerAfterShutdown = events.drop(shutdownIdx + 1).any { it.startsWith("register:") }
            assertFalse("registerMdns must never run after shutdown unregister: $events", registerAfterShutdown)
        }
    }

    @Test
    fun controlStateCoordinatorOrdersAudioMuteAndRebindSideEffectsWithAcks() {
        val flow = MutableStateFlow(StreamServiceState())
        val acks = Collections.synchronizedList(mutableListOf<ControlAck>())
        val eventTrace = Collections.synchronizedList(mutableListOf<String>())
        val committedRebinds = Collections.synchronizedList(mutableListOf<Pair<String, Long>>())
        val engineMuted = AtomicBoolean(false)
        val coordinator = ControlStateAckCoordinator(flow) { ack ->
            acks.add(ack)
            val state = ack.activeState!!
            eventTrace.add("ack:${state.cameraId}:${state.audioMuted}")
        }

        val gate = CountDownLatch(1)
        val remoteAudioThread = Thread {
            gate.await(1, TimeUnit.SECONDS)
            for (i in 1..40) {
                coordinator.updateAndEnqueue(
                    onCommittedLocked = { next, _ ->
                        engineMuted.set(next.audioMuted)
                        eventTrace.add("effect:${next.cameraId}:${next.audioMuted}")
                    },
                ) { cur ->
                    CamCraftStreamService.applyAudioStatePatch(cur, enabled = true, muted = (i % 2 == 1))
                }
            }
        }
        val uiMuteThread = Thread {
            gate.await(1, TimeUnit.SECONDS)
            for (i in 1..40) {
                coordinator.updateAndEnqueue(
                    onCommittedLocked = { next, _ ->
                        engineMuted.set(next.audioMuted)
                        eventTrace.add("effect:${next.cameraId}:${next.audioMuted}")
                    },
                ) { cur ->
                    cur.copy(audioMuted = !cur.audioMuted)
                }
            }
        }
        val rebindThread = Thread {
            gate.await(1, TimeUnit.SECONDS)
            for (i in 1..20) {
                val targetCamId = if (i % 2 == 0) "0" else "1"
                coordinator.updateAndEnqueue(
                    onCommittedLocked = { next, seq ->
                        committedRebinds.add(next.cameraId to seq)
                        eventTrace.add("effect:${next.cameraId}:${next.audioMuted}")
                    },
                ) { cur ->
                    cur.copy(cameraId = targetCamId)
                }
            }
        }

        remoteAudioThread.start()
        uiMuteThread.start()
        rebindThread.start()
        gate.countDown()
        remoteAudioThread.join(2_000)
        uiMuteThread.join(2_000)
        rebindThread.join(2_000)

        assertFalse(remoteAudioThread.isAlive)
        assertFalse(uiMuteThread.isAlive)
        assertFalse(rebindThread.isAlive)
        assertEquals(100, acks.size)
        assertEquals(200, eventTrace.size)

        // Verify each committed effect strictly precedes its corresponding ACK in the shared trace.
        for (idx in 0 until 100) {
            val effectEntry = eventTrace[idx * 2]
            val ackEntry = eventTrace[idx * 2 + 1]
            assertTrue("Expected effect entry at ${idx * 2}, got $effectEntry", effectEntry.startsWith("effect:"))
            assertTrue("Expected ack entry at ${idx * 2 + 1}, got $ackEntry", ackEntry.startsWith("ack:"))
            assertEquals(
                effectEntry.removePrefix("effect:"),
                ackEntry.removePrefix("ack:"),
            )
        }

        // Final audioMuted applied inside onCommittedLocked matches committed state and final ACK.
        assertEquals(flow.value.activeState.audioMuted, engineMuted.get())
        assertEquals(flow.value.activeState.audioMuted, acks.last().activeState!!.audioMuted)

        // Rebind commit sequences are strictly monotonic and match the order of cameraIds in ACKs.
        assertEquals(20, committedRebinds.size)
        for (idx in 1 until committedRebinds.size) {
            assertTrue(committedRebinds[idx].second > committedRebinds[idx - 1].second)
        }
        val ackCameraIds = acks.map { it.activeState!!.cameraId }
        val collapsedCommittedCams = committedRebinds.map { it.first }
        // Every committed cameraId at seq (1-based) equals the corresponding ackCameraIds[seq - 1].
        for ((camId, seq) in committedRebinds) {
            assertEquals(camId, ackCameraIds[seq.toInt() - 1])
        }
        assertEquals(20, collapsedCommittedCams.size)
    }

    @Test
    fun perSessionAudioCallbackIsolationDropsStaleSessionPacketsAfterReconnect() {
        val session1Handle = ClientSessionHandle(
            sessionId = 1L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = {},
        )
        val session2Handle = ClientSessionHandle(
            sessionId = 2L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = {},
        )
        var activeSession: ClientSessionHandle? = session1Handle
        val routedSessions = mutableListOf<Long>()
        val emitAudioForSession: (Long) -> Unit = { capturedSessionId ->
            CamCraftStreamService.resolveActiveSessionForPacket(activeSession, capturedSessionId)?.let {
                routedSessions.add(it.sessionId)
            }
        }

        val session1Callback = { emitAudioForSession(session1Handle.sessionId) }
        val session2Callback = { emitAudioForSession(session2Handle.sessionId) }

        session1Callback()
        // Reconnect: session 1 closes and session 2 becomes active.
        session1Handle.close()
        activeSession = session2Handle

        // Delayed packet from session 1's captured callback is dropped; session 2's packet routes cleanly.
        session1Callback()
        session2Callback()
        assertEquals(listOf(1L, 2L), routedSessions)
    }

    @Test
    fun controlCoordinatorRunLockedSerializesStartupCameraAndMuteWithConcurrentUiUpdates() {
        repeat(25) {
            val flow = MutableStateFlow(StreamServiceState())
            val acks = Collections.synchronizedList(mutableListOf<ControlAck>())
            val coordinator = ControlStateAckCoordinator(flow) { acks.add(it) }

            val startCameraId = AtomicReference<String?>(null)
            val startSeq = AtomicLong(0L)
            val uiRebindSeq = AtomicLong(0L)
            val uiCameraId = AtomicReference<String?>(null)
            val engineMuted = AtomicBoolean(true)
            val engineStarted = AtomicBoolean(false)

            val gate = CountDownLatch(1)
            val startupThread = Thread {
                gate.await(1, TimeUnit.SECONDS)
                // [26] Startup reads state and initializes startStream configSeq under orderLock.
                coordinator.runLocked { state, seq ->
                    startCameraId.set(state.cameraId)
                    startSeq.set(seq)
                }
                // [27] Audio capture starts initiallyMuted=true off orderLock, then applies committed mute under orderLock.
                engineStarted.set(true)
                coordinator.runLocked { state, _ ->
                    engineMuted.set(state.audioMuted)
                }
            }
            val uiThread = Thread {
                gate.await(1, TimeUnit.SECONDS)
                coordinator.updateAndEnqueue(
                    onCommittedLocked = { next, seq ->
                        uiCameraId.set(next.cameraId)
                        uiRebindSeq.set(seq)
                    },
                ) { cur ->
                    cur.copy(cameraId = "1")
                }
                coordinator.updateAndEnqueue(
                    onCommittedLocked = { next, _ ->
                        if (engineStarted.get()) {
                            engineMuted.set(next.audioMuted)
                        }
                    },
                ) { cur ->
                    cur.copy(audioMuted = true)
                }
            }

            startupThread.start()
            uiThread.start()
            gate.countDown()
            startupThread.join(2_000)
            uiThread.join(2_000)
            assertFalse(startupThread.isAlive)
            assertFalse(uiThread.isAlive)

            // Either UI lens switch happened BEFORE startStream (so startup got "1" and uiRebindSeq < startSeq is rejected),
            // or AFTER startStream (so uiRebindSeq > startSeq is accepted by CameraStreamManager.shouldApplyRebind).
            val uiWonBeforeStart = uiRebindSeq.get() < startSeq.get()
            if (uiWonBeforeStart) {
                assertEquals("1", startCameraId.get())
                assertFalse(
                    CameraStreamManager.shouldApplyRebind(
                        isReleased = false,
                        isStreaming = true,
                        activeSessionId = 1L,
                        requestSessionId = 1L,
                        configSeq = uiRebindSeq.get(),
                        lastAppliedConfigSeq = startSeq.get(),
                    ),
                )
            } else {
                assertEquals("0", startCameraId.get())
                assertTrue(
                    CameraStreamManager.shouldApplyRebind(
                        isReleased = false,
                        isStreaming = true,
                        activeSessionId = 1L,
                        requestSessionId = 1L,
                        configSeq = uiRebindSeq.get(),
                        lastAppliedConfigSeq = startSeq.get(),
                    ),
                )
            }

            // Committed audioMuted=true is NEVER overwritten by stale false during audio startup.
            assertTrue(flow.value.activeState.audioMuted)
            assertTrue(engineMuted.get())
        }
    }

    @Test
    fun serviceStateOwnershipGuardPreventsStaleSessionAndDestroyedInstanceOverwrites() {
        val ownershipLock = Any()
        val oldInstance = Any()
        val newInstance = Any()
        val activeInstanceRef = AtomicReference<Any?>(oldInstance)
        val ownerSessionIdRef = AtomicLong(10L)

        val liveState = StreamServiceState(
            status = StreamConnectionStatus.CONNECTED,
            clientAddress = "192.168.1.99",
            localIpAddress = "192.168.1.10",
            listenPort = 9876,
        )
        val stateFlow = MutableStateFlow(liveState)

        // Simulate oldInstance.onDestroy() followed by replacement newInstance publishing CONNECTED for session 11.
        activeInstanceRef.set(newInstance)
        ownerSessionIdRef.set(11L)

        // Stale session 10 from oldInstance must NOT overwrite state across any PostSessionStatus branch.
        for (outcome in CamCraftStreamService.Companion.PostSessionStatus.entries) {
            val mutated = CamCraftStreamService.applyPostSessionStateWithOwnership(
                ownershipLock = ownershipLock,
                isOwned = {
                    CamCraftStreamService.canMutateServiceStateForSession(
                        activeInstance = activeInstanceRef.get(),
                        callerInstance = oldInstance,
                        activeOwnerSessionId = ownerSessionIdRef.get(),
                        sessionId = 10L,
                    )
                },
                clearSessionOwnership = { ownerSessionIdRef.set(0L) },
                outcome = outcome,
                listenPort = 9876,
                refreshedIp = "192.168.1.10",
                serverBindError = "bind err",
                serviceState = stateFlow,
            )
            assertFalse("Stale session must not mutate state for $outcome", mutated)
            assertEquals(liveState, stateFlow.value)
            assertEquals(11L, ownerSessionIdRef.get())
        }

        // Stale session ID on the current instance is also rejected.
        assertFalse(
            CamCraftStreamService.canMutateServiceStateForSession(
                activeInstance = activeInstanceRef.get(),
                callerInstance = newInstance,
                activeOwnerSessionId = ownerSessionIdRef.get(),
                sessionId = 10L,
            ),
        )

        // Active owner session 11 on newInstance CAN transition to LISTENING and clears ownerSessionId.
        val currentMutated = CamCraftStreamService.applyPostSessionStateWithOwnership(
            ownershipLock = ownershipLock,
            isOwned = {
                CamCraftStreamService.canMutateServiceStateForSession(
                    activeInstance = activeInstanceRef.get(),
                    callerInstance = newInstance,
                    activeOwnerSessionId = ownerSessionIdRef.get(),
                    sessionId = 11L,
                )
            },
            clearSessionOwnership = { ownerSessionIdRef.set(0L) },
            outcome = CamCraftStreamService.Companion.PostSessionStatus.LISTENING,
            listenPort = 9876,
            refreshedIp = "192.168.1.10",
            serverBindError = null,
            serviceState = stateFlow,
        )
        assertTrue(currentMutated)
        assertEquals(StreamConnectionStatus.LISTENING, stateFlow.value.status)
        assertNull(stateFlow.value.clientAddress)
        assertEquals(0L, ownerSessionIdRef.get())
    }

    @Test
    fun clientSessionHandleOverflowRejectsSubsequentEnqueuesAndDispatchesCloseOffCallerThread() {
        val callerThread = Thread.currentThread()
        val transportCloses = AtomicInteger(0)
        val closeThread = AtomicReference<Thread?>(null)
        val dispatchedTasks = Collections.synchronizedList(mutableListOf<() -> Unit>())
        val ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-io-close").apply { isDaemon = true }
        }
        try {
            val closedLatch = CountDownLatch(1)
            val handle = ClientSessionHandle(
                sessionId = 20L,
                output = ByteArrayOutputStream(),
                outputLock = Any(),
                closeTransport = {
                    closeThread.set(Thread.currentThread())
                    transportCloses.incrementAndGet()
                    closedLatch.countDown()
                },
                ackQueueCapacity = 2,
                dispatchClose = { task ->
                    dispatchedTasks.add(task)
                },
            )

            assertTrue(handle.enqueueAck(ControlAck.success(defaultActiveCameraState())))
            assertTrue(handle.enqueueAck(ControlAck.success(defaultActiveCameraState())))

            // Overflow on caller thread: immediately marks closed and rejects future enqueues,
            // but does NOT run closeTransport synchronously on caller thread.
            assertFalse(handle.enqueueAck(ControlAck.success(defaultActiveCameraState())))
            assertTrue(handle.isClosed)
            assertEquals(0, transportCloses.get())
            assertEquals(1, dispatchedTasks.size)

            // Subsequent enqueue fails immediately without dispatching another close.
            assertFalse(handle.enqueueAck(ControlAck.success(defaultActiveCameraState())))
            assertEquals(1, dispatchedTasks.size)

            // Executing the dispatched task on IO thread closes transport once off caller thread.
            ioExecutor.execute { dispatchedTasks.first().invoke() }
            assertTrue(closedLatch.await(2, TimeUnit.SECONDS))
            assertEquals(1, transportCloses.get())
            assertTrue(closeThread.get() !== callerThread)

            // Verify synchronous close() before pending dispatchClose runs transport once total.
            val syncTransportCloses = AtomicInteger(0)
            val syncPendingClose = AtomicReference<(() -> Unit)?>(null)
            val syncRacingHandle = ClientSessionHandle(
                sessionId = 21L,
                output = ByteArrayOutputStream(),
                outputLock = Any(),
                closeTransport = { syncTransportCloses.incrementAndGet() },
                ackQueueCapacity = 1,
                dispatchClose = { task -> syncPendingClose.set(task) },
            )
            assertTrue(syncRacingHandle.enqueueAck(ControlAck.success(defaultActiveCameraState())))
            assertFalse(syncRacingHandle.enqueueAck(ControlAck.success(defaultActiveCameraState())))
            assertNotNull(syncPendingClose.get())
            assertEquals(0, syncTransportCloses.get())

            // I/O teardown calls close() synchronously -> closes transport once.
            syncRacingHandle.close()
            assertEquals(1, syncTransportCloses.get())

            // Late dispatched task runs -> must NOT close transport a second time.
            syncPendingClose.get()!!.invoke()
            assertEquals(1, syncTransportCloses.get())

            // Extend the USB close race: pause A's dispatched closer deterministically until B's
            // admission is verified, with latch release and executor shutdown guaranteed in finally.
            val usbACloses = AtomicInteger(0)
            val usbBCloses = AtomicInteger(0)
            val attemptACloseEntered = CountDownLatch(1)
            val allowAttemptACloseFinish = CountDownLatch(1)
            val closeRaceExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "test-usb-close-race").apply { isDaemon = true }
            }
            try {
                val tracker = UsbSessionAttemptTracker<String> { descriptor ->
                    when (descriptor) {
                        "usb-desc-A" -> {
                            attemptACloseEntered.countDown()
                            try {
                                allowAttemptACloseFinish.await()
                            } catch (_: InterruptedException) {
                                Thread.currentThread().interrupt()
                            }
                            usbACloses.incrementAndGet()
                        }
                        "usb-desc-B" -> usbBCloses.incrementAndGet()
                    }
                }
                val attemptA = tracker.beginAttempt(accessoryTag = "acc-A")
                assertTrue(tracker.attachDescriptor(attemptA, "usb-desc-A"))

                val pendingClose = AtomicReference<(() -> Unit)?>(null)
                val racingHandle = ClientSessionHandle(
                    sessionId = 22L,
                    output = ByteArrayOutputStream(),
                    outputLock = Any(),
                    closeTransport = {
                        tracker.closeAttempt(attemptA)
                    },
                    ackQueueCapacity = 1,
                    dispatchClose = { task -> pendingClose.set(task) },
                )
                assertTrue(racingHandle.enqueueAck(ControlAck.success(defaultActiveCameraState())))
                assertFalse(racingHandle.enqueueAck(ControlAck.success(defaultActiveCameraState())))
                assertNotNull(pendingClose.get())
                assertEquals(0, usbACloses.get())

                // Pause A's dispatched closer mid-close inside closeDescriptor, then install USB attempt B
                // while A's close is still pending.
                val closeFutureA = closeRaceExecutor.submit { pendingClose.get()!!.invoke() }
                assertTrue(attemptACloseEntered.await(2, TimeUnit.SECONDS))
                assertFalse(tracker.hasActiveNonClosingAttempt())

                val attemptB = tracker.beginAttempt(accessoryTag = "acc-B")
                assertTrue(tracker.attachDescriptor(attemptB, "usb-desc-B"))
                assertTrue(tracker.isAttemptCurrent(attemptB, "usb-desc-B"))
                assertTrue(tracker.hasActiveNonClosingAttempt())

                // Resume A's closer after verifying B's admission; B must stay admitted and unclosed.
                allowAttemptACloseFinish.countDown()
                closeFutureA.get(2, TimeUnit.SECONDS)
                racingHandle.close()

                assertEquals(1, usbACloses.get())
                assertEquals(0, usbBCloses.get())
                assertFalse(tracker.isAttemptCurrent(attemptA, "usb-desc-A"))
                assertTrue(tracker.isAttemptCurrent(attemptB, "usb-desc-B"))
                assertTrue(tracker.hasActiveNonClosingAttempt())
            } finally {
                allowAttemptACloseFinish.countDown()
                closeRaceExecutor.shutdownNow()
            }
        } finally {
            ioExecutor.shutdownNow()
        }
    }

    @Test
    fun usbSessionAttemptTrackerIsolatesDetachedAndEarlyCloseFromReplacementAttempt() {
        val closedDescriptors = Collections.synchronizedList(mutableListOf<String>())
        val tracker = UsbSessionAttemptTracker<String> { descriptor ->
            closedDescriptors.add(descriptor)
        }

        // 1. If attempt A closes before descriptor attachment completes, late attachDescriptor closes and rejects it.
        val attemptA = tracker.beginAttempt(accessoryTag = "acc-A")
        assertTrue(tracker.isAttemptPendingOrActive(attemptA))
        tracker.closeAttempt(attemptA)
        assertTrue(attemptA.isClosing)
        assertFalse(tracker.isAttemptPendingOrActive(attemptA))
        assertFalse(tracker.attachDescriptor(attemptA, "usb-desc-early"))
        assertEquals(listOf("usb-desc-early"), closedDescriptors)

        // 2. Detached close targeting A after B began leaves B active and current.
        val attemptA2 = tracker.beginAttempt(accessoryTag = "acc-A2")
        assertTrue(tracker.attachDescriptor(attemptA2, "usb-desc-A2"))
        val attemptB = tracker.beginAttempt(accessoryTag = "acc-B")
        assertTrue(tracker.attachDescriptor(attemptB, "usb-desc-B"))

        tracker.closeAttempt(attemptA2)
        assertTrue(tracker.isAttemptCurrent(attemptB, "usb-desc-B"))
        assertEquals(listOf("usb-desc-early", "usb-desc-A2"), closedDescriptors)
    }

    @Test
    fun controlCoordinatorRejectsStaleSessionAtomicallyForResolutionAndFailedAudioBranches() {
        val stateFlow = MutableStateFlow(StreamServiceState())
        val coordinator = ControlStateAckCoordinator(stateFlow) { }
        val session1 = ClientSessionHandle(
            sessionId = 1L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = {},
        )
        val session2 = ClientSessionHandle(
            sessionId = 2L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = {},
        )

        coordinator.setActiveSession(session1)
        val initialConfigId = coordinator.runIfSessionActive(session1, advanceCameraConfig = true) { _, _, configId ->
            configId
        }!!

        // Retire session 1 and switch ownership to session 2.
        coordinator.clearActiveSessionIfCurrent(session1)
        coordinator.setActiveSession(session2)

        // Stale session 1 resolution adjustment must reject both _serviceState update and ACK.
        val staleResolutionResult = coordinator.updateIfSessionOwned(
            expectedSessionId = 1L,
            expectedCameraConfigId = initialConfigId,
            expectedCameraId = "0",
            advanceCameraConfigOnGeometryChange = false,
            ackBuilder = { next -> ControlAck.success(next) },
        ) { cur -> cur.copy(width = 1280, height = 720) }
        assertNull(staleResolutionResult)
        assertEquals(1920, stateFlow.value.activeState.width)
        assertEquals(1080, stateFlow.value.activeState.height)
        assertNull(session1.ackChannel.tryReceive().getOrNull())
        assertNull(session2.ackChannel.tryReceive().getOrNull())

        // Stale session 1 failed-audio fallback must also reject both _serviceState update and ACK.
        val staleAudioResult = coordinator.updateIfSessionOwned(
            expectedSessionId = 1L,
            advanceCameraConfigOnGeometryChange = false,
            ackBuilder = { fallbackState ->
                ControlAck(ok = false, error = "Microphone unavailable", activeState = fallbackState)
            },
        ) { cur -> cur.copy(audioEnabled = false, audioMuted = true) }
        assertNull(staleAudioResult)
        assertTrue(stateFlow.value.activeState.audioEnabled)
        assertFalse(stateFlow.value.activeState.audioMuted)
        assertNull(session1.ackChannel.tryReceive().getOrNull())
        assertNull(session2.ackChannel.tryReceive().getOrNull())

        // Active session 2 updates dimensions and failed-audio state atomically and receives its ACKs.
        val activeConfigId = coordinator.runIfSessionActive(session2, advanceCameraConfig = true) { _, _, configId ->
            configId
        }!!
        val currentResolutionResult = coordinator.updateIfSessionOwned(
            expectedSessionId = 2L,
            expectedCameraConfigId = activeConfigId,
            expectedCameraId = "0",
            advanceCameraConfigOnGeometryChange = false,
        ) { cur -> cur.copy(width = 1280, height = 720) }
        assertNotNull(currentResolutionResult)
        assertEquals(1280, stateFlow.value.activeState.width)
        assertEquals(1280, session2.ackChannel.tryReceive().getOrNull()?.activeState?.width)

        val currentAudioResult = coordinator.updateIfSessionOwned(
            expectedSessionId = 2L,
            advanceCameraConfigOnGeometryChange = false,
            ackBuilder = { fallbackState ->
                ControlAck(ok = false, error = "Microphone unavailable", activeState = fallbackState)
            },
        ) { cur -> cur.copy(audioEnabled = false, audioMuted = true) }
        assertNotNull(currentAudioResult)
        assertFalse(stateFlow.value.activeState.audioEnabled)
        val audioAck = session2.ackChannel.tryReceive().getOrNull()!!
        assertFalse(audioAck.ok)
        assertFalse(audioAck.activeState!!.audioEnabled)
    }

    @Test
    fun controlCoordinatorConfigIdSurvivesMuteAndBlocksStaleRearCameraFallback() {
        val stateFlow = MutableStateFlow(StreamServiceState())
        val coordinator = ControlStateAckCoordinator(stateFlow) { }
        val session = ClientSessionHandle(
            sessionId = 10L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = {},
        )
        coordinator.setActiveSession(session)

        // Rear camera ("0") stream starts at 1920x1080 with configId = 1.
        val rearConfigId = coordinator.runIfSessionActive(session, advanceCameraConfig = true) { _, _, configId ->
            configId
        }!!

        // Unrelated mute change must NOT advance cameraConfigId; pending rear fallback must still apply.
        coordinator.updateWithCustomAckAndConfig(
            ackBuilder = { ControlAck.success(it) },
        ) { cur -> cur.copy(audioMuted = true) }
        assertEquals(rearConfigId, coordinator.currentCameraConfigId())

        val validAdjusted = coordinator.updateIfSessionOwned(
            expectedSessionId = 10L,
            expectedCameraConfigId = rearConfigId,
            expectedCameraId = "0",
            advanceCameraConfigOnGeometryChange = false,
        ) { cur -> cur.copy(width = 1280, height = 720) }
        assertNotNull(validAdjusted)
        assertEquals(1280, stateFlow.value.activeState.width)
        assertEquals(720, stateFlow.value.activeState.height)
        assertTrue(stateFlow.value.activeState.audioMuted)

        // Now rebind to front camera ("1") at 1920x1080 -> advances cameraConfigId.
        val frontCommitted = coordinator.updateIfSessionOwned(
            expectedSessionId = 10L,
        ) { cur -> cur.copy(cameraId = "1", width = 1920, height = 1080) }
        assertNotNull(frontCommitted)
        val frontConfigId = coordinator.currentCameraConfigId()
        assertTrue(frontConfigId > rearConfigId)

        // Late rear-camera ("0") fallback (e.g., 640x480 for rearConfigId) must NOT overwrite front dimensions!
        val staleRearFallback = coordinator.updateIfSessionOwned(
            expectedSessionId = 10L,
            expectedCameraConfigId = rearConfigId,
            expectedCameraId = "0",
            advanceCameraConfigOnGeometryChange = false,
        ) { cur -> cur.copy(width = 640, height = 480) }
        assertNull(staleRearFallback)
        assertEquals("1", stateFlow.value.activeState.cameraId)
        assertEquals(1920, stateFlow.value.activeState.width)
        assertEquals(1080, stateFlow.value.activeState.height)
    }

    @Test
    fun publishConnectedNotificationFromSnapshotReadsAtomicCurrentStateAndSkipsStaleSession() {
        val lock = Any()
        val stopping = AtomicBoolean(false)
        val sessionActive = AtomicBoolean(true)
        val stateFlow = MutableStateFlow(
            StreamServiceState(
                status = StreamConnectionStatus.CONNECTED,
                clientAddress = "192.168.1.42",
                activeState = defaultActiveCameraState().copy(
                    cameraId = "1",
                    width = 1280,
                    height = 720,
                    fps = 30,
                ),
            ),
        )
        val publishedNotifications = mutableListOf<String>()

        assertTrue(
            CamCraftStreamService.publishConnectedNotificationFromSnapshot(
                lock = lock,
                isStopping = { stopping.get() },
                isSessionActive = { sessionActive.get() },
                serviceState = stateFlow,
                onUpdateNotification = { publishedNotifications.add(it) },
            ),
        )
        assertEquals(
            listOf("Connected (192.168.1.42) • 1280x720 @ 30 FPS"),
            publishedNotifications,
        )

        // Inactive/retired session skips publication.
        sessionActive.set(false)
        assertFalse(
            CamCraftStreamService.publishConnectedNotificationFromSnapshot(
                lock = lock,
                isStopping = { stopping.get() },
                isSessionActive = { sessionActive.get() },
                serviceState = stateFlow,
                onUpdateNotification = { publishedNotifications.add(it) },
            ),
        )
        assertEquals(1, publishedNotifications.size)

        // Non-CONNECTED state skips publication even if session flag still toggling.
        sessionActive.set(true)
        stateFlow.value = stateFlow.value.copy(status = StreamConnectionStatus.LISTENING, clientAddress = null)
        assertFalse(
            CamCraftStreamService.publishConnectedNotificationFromSnapshot(
                lock = lock,
                isStopping = { stopping.get() },
                isSessionActive = { sessionActive.get() },
                serviceState = stateFlow,
                onUpdateNotification = { publishedNotifications.add(it) },
            ),
        )
        assertEquals(1, publishedNotifications.size)
    }

    @Test
    fun applyVideoPipelineFailureMarksErrorAndRestoreConnectedClearsOnRetry() {
        val lock = Any()
        val stopping = AtomicBoolean(false)
        val sessionActive = AtomicBoolean(true)
        val stateFlow = MutableStateFlow(
            StreamServiceState(
                status = StreamConnectionStatus.CONNECTED,
                clientAddress = "USB Direct",
                hasActiveSession = true,
                activeState = defaultActiveCameraState(),
                currentFps = 59.8f,
            ),
        )
        val coordinator = ControlStateAckCoordinator(stateFlow) { }
        val session = ClientSessionHandle(10L, ByteArrayOutputStream(), Any(), {})
        coordinator.setActiveSession(session)
        val notifications = mutableListOf<String>()

        assertTrue(
            CamCraftStreamService.applyVideoPipelineFailureIfActive(
                lock = lock,
                isStopping = { stopping.get() },
                isSessionActive = { sessionActive.get() },
                reason = "Replacement pipeline failed",
                serviceState = stateFlow,
                onUpdateNotification = { notifications.add(it) },
            ),
        )
        assertEquals(StreamConnectionStatus.ERROR, stateFlow.value.status)
        assertTrue(stateFlow.value.hasActiveSession)
        assertFalse(stateFlow.value.isVideoPipelineHealthy)
        assertTrue(stateFlow.value.isServerActive)
        assertTrue(stateFlow.value.isMicControlAvailable)
        assertEquals(0f, stateFlow.value.currentFps, 0.001f)
        assertEquals("Replacement pipeline failed", stateFlow.value.errorMessage)
        assertEquals("Video unavailable (USB Direct) • Replacement pipeline failed", notifications.single())

        // Retry or frame recovery restores CONNECTED and clears stale errorMessage.
        assertTrue(
            CamCraftStreamService.restoreConnectedStatusIfRecovering(
                lock = lock,
                coordinator = coordinator,
                expectedSessionId = 10L,
                isStopping = { stopping.get() },
                isSessionActive = { sessionActive.get() },
                serviceState = stateFlow,
                onUpdateNotification = { notifications.add(it) },
            ),
        )
        assertEquals(StreamConnectionStatus.CONNECTED, stateFlow.value.status)
        assertTrue(stateFlow.value.hasActiveSession)
        assertTrue(stateFlow.value.isVideoPipelineHealthy)
        assertNull(stateFlow.value.errorMessage)
        assertEquals("Connected (USB Direct) • 1920x1080 @ 60 FPS", notifications.last())
    }

    @Test
    fun checkAndCloseStalledSessionWriteUnblocksBlockedWriterThreadOnce() {
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        val transportCloses = AtomicInteger(0)
        val clockNs = AtomicLong(1_000L)
        val timeoutNs = 2_500_000_000L
        val blockedWriterExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-stalled-writer").apply { isDaemon = true }
        }
        val watchdogExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-watchdog-check").apply { isDaemon = true }
        }
        val blockingOutput = object : OutputStream() {
            override fun write(b: Int) {
                writerEntered.countDown()
                try {
                    releaseWriter.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                throw IOException("transport closed by watchdog")
            }
        }
        val handle = ClientSessionHandle(
            sessionId = 44L,
            output = blockingOutput,
            outputLock = Any(),
            closeTransport = {
                transportCloses.incrementAndGet()
                releaseWriter.countDown()
            },
            nanoTimeProvider = { clockNs.get() },
        )
        try {
            // Not writing -> not stalled even if time elapsed.
            assertFalse(handle.isWriteStalled(nowNs = 10_000_000_000L, timeoutNs = timeoutNs))

            val beforeCloseRuns = AtomicInteger(0)
            val writerFuture = blockedWriterExecutor.submit<Boolean> {
                try {
                    synchronized(handle.outputLock) {
                        handle.withTrackedWrite {
                            handle.output.write(0x43)
                        }
                    }
                    false
                } catch (_: IOException) {
                    true
                }
            }
            assertTrue(writerEntered.await(2, TimeUnit.SECONDS))

            // Just below timeout -> watchdog does NOT close transport (run off test thread with bounded wait).
            clockNs.set(1_000L + timeoutNs - 1L)
            val belowTimeoutFuture = watchdogExecutor.submit<Boolean> {
                CamCraftStreamService.checkAndCloseStalledSessionWrite(
                    session = handle,
                    nowNs = clockNs.get(),
                    timeoutNs = timeoutNs,
                    beforeClose = { beforeCloseRuns.incrementAndGet() },
                )
            }
            assertFalse(belowTimeoutFuture.get(2, TimeUnit.SECONDS))
            assertFalse(handle.isClosed)
            assertEquals(0, beforeCloseRuns.get())
            assertEquals(0, transportCloses.get())

            // Exact timeout boundary -> closes transport without acquiring handle.outputLock, unblocking writer.
            clockNs.set(1_000L + timeoutNs)
            val atTimeoutFuture = watchdogExecutor.submit<Boolean> {
                CamCraftStreamService.checkAndCloseStalledSessionWrite(
                    session = handle,
                    nowNs = clockNs.get(),
                    timeoutNs = timeoutNs,
                    beforeClose = { beforeCloseRuns.incrementAndGet() },
                )
            }
            assertTrue(atTimeoutFuture.get(2, TimeUnit.SECONDS))
            assertTrue(writerFuture.get(2, TimeUnit.SECONDS))
            assertTrue(handle.isClosed)
            assertEquals(1, beforeCloseRuns.get())
            assertEquals(1, transportCloses.get())
        } finally {
            releaseWriter.countDown()
            blockedWriterExecutor.shutdownNow()
            watchdogExecutor.shutdownNow()
        }
    }

    @Test
    fun applyVideoPipelineFailureIfCurrentConfigOrdersAndRechecksConfigId() {
        val lock = Any()
        val stateFlow = MutableStateFlow(
            StreamServiceState(
                status = StreamConnectionStatus.CONNECTED,
                clientAddress = "192.168.1.10",
                activeState = defaultActiveCameraState(),
                currentFps = 59.5f,
            ),
        )
        val coordinator = ControlStateAckCoordinator(stateFlow) { }
        val session = ClientSessionHandle(
            sessionId = 60L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = {},
        )
        coordinator.setActiveSession(session)

        val firstConfigId = coordinator.runIfSessionActive(session, advanceCameraConfig = true) { _, _, id -> id }!!
        coordinator.updateIfSessionOwned(
            expectedSessionId = 60L,
            shouldAdvanceCameraConfig = { _, _ -> true },
        ) { cur -> cur.copy(width = 1280, height = 720) }
        val currentConfigId = coordinator.currentCameraConfigId()
        // Drain ACK from ConfigureStream so channel only contains subsequent failure ACK.
        assertNotNull(session.ackChannel.tryReceive().getOrNull())

        val notifications = mutableListOf<String>()
        // Superseded configId -> must publish neither failure ACK nor ERROR state.
        assertFalse(
            CamCraftStreamService.applyVideoPipelineFailureIfCurrentConfig(
                lock = lock,
                coordinator = coordinator,
                expectedSessionId = 60L,
                expectedCameraConfigId = firstConfigId,
                expectedCameraId = "0",
                isStopping = { false },
                isSessionActive = { true },
                reason = "Stale failure",
                serviceState = stateFlow,
                onUpdateNotification = { notifications.add(it) },
            ),
        )
        assertEquals(StreamConnectionStatus.CONNECTED, stateFlow.value.status)
        assertNull(stateFlow.value.errorMessage)
        assertNull(session.ackChannel.tryReceive().getOrNull())
        assertTrue(notifications.isEmpty())

        // Current configId -> publishes both failure ACK and ERROR state atomically.
        assertTrue(
            CamCraftStreamService.applyVideoPipelineFailureIfCurrentConfig(
                lock = lock,
                coordinator = coordinator,
                expectedSessionId = 60L,
                expectedCameraConfigId = currentConfigId,
                expectedCameraId = "0",
                isStopping = { false },
                isSessionActive = { true },
                reason = "Replacement failed",
                serviceState = stateFlow,
                onUpdateNotification = { notifications.add(it) },
            ),
        )
        assertEquals(StreamConnectionStatus.ERROR, stateFlow.value.status)
        assertEquals("Replacement failed", stateFlow.value.errorMessage)
        val ack = session.ackChannel.tryReceive().getOrNull()!!
        assertFalse(ack.ok)
        assertEquals("Replacement failed", ack.error)
        assertEquals(listOf("Video unavailable (192.168.1.10) • Replacement failed"), notifications)
    }

    @Test
    fun asyncRebindNotificationDoesNotClearErrorUntilCurrentPipelineWritesFrame() {
        val lock = Any()
        val stateFlow = MutableStateFlow(
            StreamServiceState(
                status = StreamConnectionStatus.ERROR,
                clientAddress = "USB Direct",
                hasActiveSession = true,
                isVideoPipelineHealthy = false,
                activeState = defaultActiveCameraState(),
                errorMessage = "Bind failed",
            ),
        )
        val coordinator = ControlStateAckCoordinator(stateFlow) { }
        val active = ClientSessionHandle(70L, ByteArrayOutputStream(), Any(), {})
        coordinator.setActiveSession(active)
        val staleConfigId = coordinator.runIfSessionActive(active, advanceCameraConfig = true) { _, _, id -> id }!!
        val currentConfigId = coordinator.runIfSessionActive(active, advanceCameraConfig = true) { _, _, id -> id }!!
        val notifications = mutableListOf<String>()

        // Submitting an async rebind calls publishConnectedNotificationFromSnapshot -> never clears ERROR.
        assertFalse(
            CamCraftStreamService.publishConnectedNotificationFromSnapshot(
                lock = lock,
                isStopping = { false },
                isSessionActive = { true },
                serviceState = stateFlow,
                onUpdateNotification = { notifications.add(it) },
            ),
        )
        assertEquals(StreamConnectionStatus.ERROR, stateFlow.value.status)
        assertEquals("Bind failed", stateFlow.value.errorMessage)
        assertTrue(notifications.isEmpty())

        // Stale pipeline frame write cannot recover ERROR status.
        assertFalse(
            CamCraftStreamService.restoreConnectedStatusIfRecovering(
                lock = lock,
                coordinator = coordinator,
                expectedSessionId = 70L,
                expectedCameraConfigId = staleConfigId,
                isStopping = { false },
                isSessionActive = { true },
                serviceState = stateFlow,
                onUpdateNotification = { notifications.add(it) },
            ),
        )
        assertEquals(StreamConnectionStatus.ERROR, stateFlow.value.status)
        assertTrue(notifications.isEmpty())

        // Successful video frame write from current pipeline clears ERROR and restores CONNECTED.
        assertTrue(
            CamCraftStreamService.restoreConnectedStatusIfRecovering(
                lock = lock,
                coordinator = coordinator,
                expectedSessionId = 70L,
                expectedCameraConfigId = currentConfigId,
                isStopping = { false },
                isSessionActive = { true },
                serviceState = stateFlow,
                onUpdateNotification = { notifications.add(it) },
            ),
        )
        assertEquals(StreamConnectionStatus.CONNECTED, stateFlow.value.status)
        assertTrue(stateFlow.value.hasActiveSession)
        assertTrue(stateFlow.value.isVideoPipelineHealthy)
        assertNull(stateFlow.value.errorMessage)
        assertEquals(listOf("Connected (USB Direct) • 1920x1080 @ 60 FPS"), notifications)

        // Session ID verification rejects superseded or closed sessions while matching current.
        assertTrue(CamCraftStreamService.isActiveSessionMatching(false, active, 70L))
        assertFalse(CamCraftStreamService.isActiveSessionMatching(false, active, 69L))
        assertFalse(CamCraftStreamService.isActiveSessionMatching(true, active, 70L))
        assertFalse(CamCraftStreamService.isActiveSessionMatching(false, null, 70L))
        active.close()
        assertFalse(CamCraftStreamService.isActiveSessionMatching(false, active, 70L))
    }

    @Test
    fun updateIfSessionOwnedAdvancesCameraConfigIdOnSupersedingRetryWhilePreservingBitrateOnly() {
        val stateFlow = MutableStateFlow(StreamServiceState())
        val coordinator = ControlStateAckCoordinator(stateFlow) { }
        val session = ClientSessionHandle(
            sessionId = 55L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = {},
        )
        coordinator.setActiveSession(session)

        val initialConfigId = coordinator.runIfSessionActive(session, advanceCameraConfig = true) { _, _, configId ->
            configId
        }!!

        // Superseding retry predicate (e.g., negotiation in flight) advances configId even with same dims.
        coordinator.updateIfSessionOwned(
            expectedSessionId = 55L,
            shouldAdvanceCameraConfig = { _, _ -> true },
        ) { cur -> cur.copy(bitrateKbps = 8_000) }
        val retryConfigId = coordinator.currentCameraConfigId()
        assertTrue(retryConfigId > initialConfigId)

        // Prior configId callback is now rejected atomically.
        assertNull(
            coordinator.updateIfSessionOwned(
                expectedSessionId = 55L,
                expectedCameraConfigId = initialConfigId,
                expectedCameraId = "0",
                advanceCameraConfigOnGeometryChange = false,
            ) { cur -> cur.copy(width = 640, height = 480) },
        )

        // Bitrate-only update when predicate is false preserves retryConfigId.
        coordinator.updateIfSessionOwned(
            expectedSessionId = 55L,
            shouldAdvanceCameraConfig = { _, _ -> false },
        ) { cur -> cur.copy(bitrateKbps = 10_000) }
        assertEquals(retryConfigId, coordinator.currentCameraConfigId())
        assertEquals(10_000, stateFlow.value.bitrateKbps)
    }

    @Test
    fun commitEncoderResolutionAdjustedOrdersDimensionCommitUnderCoordinatorOrderLock() {
        val stateFlow = MutableStateFlow(
            StreamServiceState(
                status = StreamConnectionStatus.CONNECTED,
                clientAddress = "192.168.1.10",
                hasActiveSession = true,
                activeState = defaultActiveCameraState(),
            ),
        )
        val coordinator = ControlStateAckCoordinator(stateFlow) { }
        val session = ClientSessionHandle(81L, ByteArrayOutputStream(), Any(), {})
        coordinator.setActiveSession(session)
        val initialConfigId = coordinator.runIfSessionActive(session, advanceCameraConfig = true) { _, _, id -> id }!!

        var requestedWidth = 1920
        var requestedHeight = 1080
        var adjustmentInFlight = true
        val pipelineHealthy = true

        // Commit negotiated 1280x720 atomically with serviceState under coordinator orderLock.
        val committed = CamCraftStreamService.commitEncoderResolutionAdjusted(
            coordinator = coordinator,
            expectedSessionId = 81L,
            expectedCameraConfigId = initialConfigId,
            expectedCameraId = "0",
            width = 1280,
            height = 720,
            isStopping = { false },
            commitNegotiatedDimensions = {
                // Before activeState publishes 1280x720, ConfigureStream cannot observe negotiated dims separately.
                assertEquals(1920, stateFlow.value.activeState.width)
                requestedWidth = 1280
                requestedHeight = 720
                adjustmentInFlight = false
                true
            },
        )
        assertNotNull(committed)
        assertEquals(1280, stateFlow.value.activeState.width)
        assertEquals(720, stateFlow.value.activeState.height)
        assertEquals(initialConfigId, coordinator.currentCameraConfigId())
        val ack = session.ackChannel.tryReceive().getOrNull()!!
        val ackState = ack.activeState!!
        assertEquals(1280, ackState.width)
        assertEquals(720, ackState.height)

        // Subsequent bitrate-only ConfigureStream preserves initialConfigId and does not restart.
        val bitrateOnlyCmd = ControlCmd.ConfigureStream(
            cameraId = "0",
            width = 1280,
            height = 720,
            fps = 60,
            bitrateKbps = 8_000,
        )
        coordinator.updateIfSessionOwned(
            expectedSessionId = 81L,
            shouldAdvanceCameraConfig = { previous, next ->
                CamCraftStreamService.isCameraGeometryChanged(previous, next) ||
                    CameraStreamManager.shouldAdvanceCameraConfigForRebind(
                        isReleased = false,
                        isStreaming = true,
                        activeSessionId = 81L,
                        requestSessionId = 81L,
                        pipelineHealthyOrStarting = pipelineHealthy,
                        resolutionAdjustmentInFlight = adjustmentInFlight,
                        requestedCameraId = "0",
                        requestedWidth = requestedWidth,
                        requestedHeight = requestedHeight,
                        requestedFps = 60,
                        newCameraId = next.cameraId,
                        newWidth = next.width,
                        newHeight = next.height,
                        newFps = next.fps,
                    )
            },
        ) { cur ->
            CamCraftStreamService.applyConfigureStreamPatch(cur, bitrateOnlyCmd, null)
        }
        assertEquals(initialConfigId, coordinator.currentCameraConfigId())
        assertEquals(8_000, stateFlow.value.bitrateKbps)

        // Changing fps (or camera/width/height) advances configId; old configId resolution commits are rejected.
        val fpsChangeCmd = ControlCmd.ConfigureStream(
            cameraId = "0",
            width = 1280,
            height = 720,
            fps = 30,
            bitrateKbps = 8_000,
        )
        coordinator.updateIfSessionOwned(
            expectedSessionId = 81L,
            shouldAdvanceCameraConfig = { previous, next ->
                CamCraftStreamService.isCameraGeometryChanged(previous, next) ||
                    CameraStreamManager.shouldAdvanceCameraConfigForRebind(
                        isReleased = false,
                        isStreaming = true,
                        activeSessionId = 81L,
                        requestSessionId = 81L,
                        pipelineHealthyOrStarting = pipelineHealthy,
                        resolutionAdjustmentInFlight = adjustmentInFlight,
                        requestedCameraId = "0",
                        requestedWidth = requestedWidth,
                        requestedHeight = requestedHeight,
                        requestedFps = 60,
                        newCameraId = next.cameraId,
                        newWidth = next.width,
                        newHeight = next.height,
                        newFps = next.fps,
                    )
            },
        ) { cur ->
            CamCraftStreamService.applyConfigureStreamPatch(cur, fpsChangeCmd, null)
        }
        assertTrue(coordinator.currentCameraConfigId() > initialConfigId)

        var staleCallbackInvoked = false
        assertNull(
            CamCraftStreamService.commitEncoderResolutionAdjusted(
                coordinator = coordinator,
                expectedSessionId = 81L,
                expectedCameraConfigId = initialConfigId,
                expectedCameraId = "0",
                width = 640,
                height = 480,
                isStopping = { false },
                commitNegotiatedDimensions = {
                    staleCallbackInvoked = true
                    true
                },
            ),
        )
        assertFalse(staleCallbackInvoked)
        assertEquals(1280, stateFlow.value.activeState.width)
    }

    @Test
    fun stalledInitialHelloWriteIsAbortedByWatchdogAndAdmitsReplacementSession() = runBlocking {
        val writerEntered = CountDownLatch(1)
        val releaseStuckWriter = CountDownLatch(1)
        val cleanupExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-deferred-output-close").apply { isDaemon = true }
        }
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val sessionMutex = Mutex()
        val transport1Closes = AtomicInteger(0)

        // Underlying sink ignores closeTransport() and remains wedged holding session1.outputLock and
        // BufferedOutputStream's synchronized monitor until releaseStuckWriter is released in finally.
        val stuckUnderlying = object : OutputStream() {
            override fun write(b: Int) {
                writerEntered.countDown()
                try {
                    releaseStuckWriter.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                writerEntered.countDown()
                try {
                    releaseStuckWriter.await()
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        val buffered1 = BufferedOutputStream(stuckUnderlying, 16)
        val clock1 = AtomicLong(1_000L)
        val session1 = ClientSessionHandle(
            sessionId = 90L,
            output = buffered1,
            outputLock = Any(),
            closeTransport = { transport1Closes.incrementAndGet() },
            nanoTimeProvider = { clock1.get() },
        )
        val replacementBytes = ByteArrayOutputStream()
        val session2 = ClientSessionHandle(
            sessionId = 91L,
            output = replacementBytes,
            outputLock = Any(),
            closeTransport = {},
        )

        try {
            val activeOwner = AtomicReference<ClientSessionHandle?>(session1)
            val session1Done = async(Dispatchers.IO) {
                sessionMutex.withLock {
                    var helloWriteCompleted = false
                    try {
                        CamCraftStreamService.writeInitialHelloCancellable(
                            scope = scope,
                            session = session1,
                        ) {
                            buffered1.write(ByteArray(32) { 0x41 })
                            buffered1.flush()
                        }
                        helloWriteCompleted = true
                        false
                    } catch (_: IOException) {
                        true
                    } finally {
                        if (activeOwner.get() === session1) {
                            activeOwner.set(null)
                        }
                        session1.close()
                        CamCraftStreamService.closeTransportBeforeStreams(
                            closeTransport = session1.closeTransport,
                            inputStream = null,
                            outputStream = buffered1,
                            deferOutputClose = !helloWriteCompleted || session1.hasInFlightWrite,
                            dispatchDeferredClose = { task -> cleanupExecutor.execute(task) },
                        )
                    }
                }
            }

            assertTrue(writerEntered.await(2, TimeUnit.SECONDS))
            clock1.set(1_000L + 3_000_000_000L)

            // Watchdog invalidates and closes stalled session1 without acquiring session1.outputLock.
            assertTrue(
                CamCraftStreamService.checkAndCloseStalledSessionWrite(
                    session = session1,
                    nowNs = clock1.get(),
                    timeoutNs = 2_500_000_000L,
                    beforeClose = {
                        if (activeOwner.get() === session1) {
                            activeOwner.set(null)
                        }
                    },
                ),
            )
            assertTrue(withTimeout(2_000L) { session1Done.await() })
            assertEquals(2, transport1Closes.get())
            assertNull(activeOwner.get())

            // Even while session1's blocked writer thread has NOT returned, replacement session2
            // acquires sessionMutex and completes its own CONTROL_HELLO write cleanly.
            val replacementSucceeded = withTimeout(2_000L) {
                sessionMutex.withLock {
                    activeOwner.set(session2)
                    CamCraftStreamService.writeInitialHelloCancellable(
                        scope = scope,
                        session = session2,
                    ) {
                        replacementBytes.write(byteArrayOf(0x43, 0x43, 0x46, 0x54))
                        replacementBytes.flush()
                    }
                    true
                }
            }
            assertTrue(replacementSucceeded)
            assertEquals(4, replacementBytes.size())
            assertTrue(session2 === activeOwner.get())
        } finally {
            releaseStuckWriter.countDown()
            session1.close()
            session2.close()
            scope.cancel()
            cleanupExecutor.shutdownNow()
        }
    }

    @Test
    fun streamServiceStateSeparatesSessionLivenessFromVideoPipelineHealth() {
        val stopped = StreamServiceState(status = StreamConnectionStatus.STOPPED)
        assertFalse(stopped.isServerActive)
        assertFalse(stopped.isMicControlAvailable)

        val listening = StreamServiceState(
            status = StreamConnectionStatus.LISTENING,
            localIpAddress = "192.168.1.25",
            hasActiveSession = false,
        )
        assertTrue(listening.isServerActive)
        assertTrue(listening.isMicControlAvailable)

        val bindError = StreamServiceState(
            status = StreamConnectionStatus.ERROR,
            clientAddress = null,
            hasActiveSession = false,
            errorMessage = "Address already in use",
        )
        assertFalse(bindError.isServerActive)
        assertFalse(bindError.isMicControlAvailable)

        val stateFlow = MutableStateFlow(
            StreamServiceState(
                status = StreamConnectionStatus.CONNECTED,
                clientAddress = "192.168.1.99",
                hasActiveSession = true,
                isVideoPipelineHealthy = true,
                activeState = defaultActiveCameraState(),
            ),
        )
        val lock = Any()
        val coordinator = ControlStateAckCoordinator(stateFlow) { }
        val session = ClientSessionHandle(95L, ByteArrayOutputStream(), Any(), {})
        coordinator.setActiveSession(session)
        val configId = coordinator.runIfSessionActive(session, advanceCameraConfig = true) { _, _, id -> id }!!

        assertTrue(
            CamCraftStreamService.applyVideoPipelineFailureIfCurrentConfig(
                lock = lock,
                coordinator = coordinator,
                expectedSessionId = 95L,
                expectedCameraConfigId = configId,
                expectedCameraId = "0",
                isStopping = { false },
                isSessionActive = { true },
                reason = "Bind failed",
                serviceState = stateFlow,
                onUpdateNotification = {},
            ),
        )
        assertEquals(StreamConnectionStatus.ERROR, stateFlow.value.status)
        assertTrue(stateFlow.value.hasActiveSession)
        assertFalse(stateFlow.value.isVideoPipelineHealthy)
        assertTrue(stateFlow.value.isServerActive)
        assertTrue(stateFlow.value.isMicControlAvailable)

        val bindFailurePostSession = MutableStateFlow(stateFlow.value)
        assertTrue(
            CamCraftStreamService.applyPostSessionStateWithOwnership(
                ownershipLock = Any(),
                isOwned = { true },
                clearSessionOwnership = {},
                outcome = CamCraftStreamService.Companion.PostSessionStatus.ERROR,
                listenPort = 9876,
                refreshedIp = null,
                serverBindError = "Port bind failed",
                serviceState = bindFailurePostSession,
            ),
        )
        assertEquals(StreamConnectionStatus.ERROR, bindFailurePostSession.value.status)
        assertFalse(bindFailurePostSession.value.hasActiveSession)
        assertTrue(bindFailurePostSession.value.isVideoPipelineHealthy)
        assertFalse(bindFailurePostSession.value.isServerActive)
        assertFalse(bindFailurePostSession.value.isMicControlAvailable)
    }

    @Test
    fun restoreConnectedStatusIfRecoveringIsAtomicWithOrderLockAgainstConfigCommits() {
        val lifecycleLock = Any()
        val stateFlow = MutableStateFlow(
            StreamServiceState(
                status = StreamConnectionStatus.ERROR,
                clientAddress = "USB Direct",
                hasActiveSession = true,
                isVideoPipelineHealthy = false,
                activeState = defaultActiveCameraState(),
                errorMessage = "Bind failed",
            ),
        )
        val coordinator = ControlStateAckCoordinator(stateFlow) { }
        val session = ClientSessionHandle(99L, ByteArrayOutputStream(), Any(), {})
        coordinator.setActiveSession(session)
        val config1 = coordinator.runIfSessionActive(session, advanceCameraConfig = true) { _, _, id -> id }!!

        // 1. Commit path never acquires lifecycleLock (orderLock commits immediately even while lifecycleLock is held).
        val commitWhileLifecycleHeld = AtomicBoolean(false)
        val workerExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-config-commit").apply { isDaemon = true }
        }
        try {
            synchronized(lifecycleLock) {
                val f = workerExecutor.submit {
                    coordinator.updateIfSessionOwned(
                        expectedSessionId = 99L,
                        shouldAdvanceCameraConfig = { _, _ -> false },
                    ) { cur -> cur.copy(bitrateKbps = 9_000) }
                    commitWhileLifecycleHeld.set(true)
                }
                f.get(2, TimeUnit.SECONDS)
                assertTrue(commitWhileLifecycleHeld.get())
            }
            assertEquals(config1, coordinator.currentCameraConfigId())

            // 2. Concurrent config commit attempted between check and write in restoreConnectedStatusIfRecovering
            //    is excluded by orderLock until the ERROR-clearing write completes.
            val hookEntered = CountDownLatch(1)
            val commitStarted = CountDownLatch(1)
            val commitFinished = CountDownLatch(1)
            val commitFutureRef = AtomicReference<java.util.concurrent.Future<Long>?>()
            val errorClearedBeforeCommit = AtomicBoolean(false)
            val notifications = mutableListOf<String>()

            assertTrue(
                CamCraftStreamService.restoreConnectedStatusIfRecovering(
                    lock = lifecycleLock,
                    coordinator = coordinator,
                    expectedSessionId = 99L,
                    expectedCameraConfigId = config1,
                    isStopping = { false },
                    isSessionActive = { true },
                    serviceState = stateFlow,
                    onUpdateNotification = { notifications.add(it) },
                    onAfterConfigCheckBeforeWriteForTest = {
                        hookEntered.countDown()
                        val recoveryThreadId = Thread.currentThread().id
                        val workerThreadRef = AtomicReference<Thread?>()
                        val future = workerExecutor.submit<Long> {
                            workerThreadRef.set(Thread.currentThread())
                            commitStarted.countDown()
                            coordinator.updateIfSessionOwned(
                                expectedSessionId = 99L,
                                shouldAdvanceCameraConfig = { _, _ -> true },
                            ) { cur ->
                                errorClearedBeforeCommit.set(
                                    stateFlow.value.status == StreamConnectionStatus.CONNECTED &&
                                        stateFlow.value.errorMessage == null,
                                )
                                cur.copy(cameraId = "1")
                            }
                            commitFinished.countDown()
                            coordinator.currentCameraConfigId()
                        }
                        commitFutureRef.set(future)
                        assertTrue(commitStarted.await(2, TimeUnit.SECONDS))

                        val threadBean = ManagementFactory.getThreadMXBean()
                        val deadlineNs = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                        var blockedOnOrderLock = false
                        while (System.nanoTime() < deadlineNs) {
                            assertFalse(
                                "Concurrent config commit finished before recovery exited orderLock",
                                commitFinished.count == 0L,
                            )
                            val workerThread = workerThreadRef.get()
                            val info = workerThread?.let { threadBean.getThreadInfo(it.id, 12) }
                            if (info != null &&
                                info.threadState == Thread.State.BLOCKED &&
                                info.lockOwnerId == recoveryThreadId &&
                                info.stackTrace.any { frame ->
                                    frame.className.contains("ControlStateAckCoordinator") &&
                                        frame.methodName.startsWith("updateIfSessionOwned")
                                }
                            ) {
                                blockedOnOrderLock = true
                                break
                            }
                            Thread.sleep(5)
                        }
                        assertTrue(
                            "Expected config worker thread to block on ControlStateAckCoordinator orderLock monitor",
                            blockedOnOrderLock,
                        )
                        // orderLock prevents concurrent config commit from interleaving before recovery finishes.
                        assertFalse(commitFinished.await(20, TimeUnit.MILLISECONDS))
                    },
                ),
            )
            assertTrue(hookEntered.await(1, TimeUnit.SECONDS))
            val config2 = commitFutureRef.get()!!.get(2, TimeUnit.SECONDS)
            assertTrue(errorClearedBeforeCommit.get())
            assertTrue(config2 > config1)
            assertEquals("1", stateFlow.value.activeState.cameraId)
            assertEquals(StreamConnectionStatus.CONNECTED, stateFlow.value.status)
            assertNull(stateFlow.value.errorMessage)
            assertEquals(1, notifications.size)
        } finally {
            workerExecutor.shutdownNow()
        }
    }

    @Test
    fun sendStreamPacketValidatesEncoderGenerationUnderOutputLockBeforeConfigAndFrame() {
        val output = ByteArrayOutputStream()
        val sessionCloses = AtomicInteger(0)
        val session = ClientSessionHandle(
            sessionId = 42L,
            output = output,
            outputLock = Any(),
            closeTransport = { sessionCloses.incrementAndGet() },
        )
        val currentGeneration = AtomicLong(2L)
        val configPayload = byteArrayOf(0, 0, 0, 1, 0x67, 0x42)
        val framePayload = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte())

        // Retired encoder (generation=1) in the SAME session is rejected under outputLock without closing session.
        val retiredCheckHeldLock = AtomicBoolean(false)
        assertFalse(
            CamCraftStreamService.sendStreamPacketForSession(
                activeSessionProvider = { session },
                clearActiveSessionIfCurrent = {},
                sessionId = 42L,
                packetType = PacketType.VIDEO_CONFIG,
                flags = CcftFlags.NONE,
                ptsUs = 100L,
                payload = configPayload,
                offset = 0,
                length = configPayload.size,
                tagLabel = "video",
                canWriteLocked = {
                    retiredCheckHeldLock.set(Thread.holdsLock(session.outputLock))
                    currentGeneration.get() == 1L
                },
            ),
        )
        assertTrue(retiredCheckHeldLock.get())
        assertFalse(
            CamCraftStreamService.sendStreamPacketForSession(
                activeSessionProvider = { session },
                clearActiveSessionIfCurrent = {},
                sessionId = 42L,
                packetType = PacketType.VIDEO_FRAME,
                flags = CcftFlags.KEYFRAME,
                ptsUs = 200L,
                payload = framePayload,
                offset = 0,
                length = framePayload.size,
                tagLabel = "video",
                canWriteLocked = { currentGeneration.get() == 1L },
            ),
        )
        assertEquals(0, output.size())
        assertFalse(session.isClosed)
        assertEquals(0, sessionCloses.get())

        // Current encoder (generation=2) writes VIDEO_CONFIG before first VIDEO_FRAME, then VIDEO_FRAME.
        val configCheckHeldLock = AtomicBoolean(false)
        assertTrue(
            CamCraftStreamService.sendStreamPacketForSession(
                activeSessionProvider = { session },
                clearActiveSessionIfCurrent = {},
                sessionId = 42L,
                packetType = PacketType.VIDEO_CONFIG,
                flags = CcftFlags.NONE,
                ptsUs = 300L,
                payload = configPayload,
                offset = 0,
                length = configPayload.size,
                tagLabel = "video",
                canWriteLocked = {
                    configCheckHeldLock.set(Thread.holdsLock(session.outputLock))
                    currentGeneration.get() == 2L
                },
            ),
        )
        assertTrue(configCheckHeldLock.get())
        assertTrue(
            CamCraftStreamService.sendStreamPacketForSession(
                activeSessionProvider = { session },
                clearActiveSessionIfCurrent = {},
                sessionId = 42L,
                packetType = PacketType.VIDEO_FRAME,
                flags = CcftFlags.KEYFRAME,
                ptsUs = 400L,
                payload = framePayload,
                offset = 0,
                length = framePayload.size,
                tagLabel = "video",
                canWriteLocked = { currentGeneration.get() == 2L },
            ),
        )

        val parsedInput = DataInputStream(ByteArrayInputStream(output.toByteArray()))
        val firstPacket = CcftPacket.readPacket(parsedInput)
        val secondPacket = CcftPacket.readPacket(parsedInput)
        assertEquals(PacketType.VIDEO_CONFIG, firstPacket.header.packetType)
        assertEquals(PacketType.VIDEO_FRAME, secondPacket.header.packetType)
    }

    @Test
    fun postSessionListeningReconcilesMdnsAndNotificationForValidAndBlankIp() {
        val registered = mutableListOf<Pair<Int, String>>()
        val unregisterCount = AtomicInteger(0)

        CamCraftStreamService.reconcileMdnsForListening(
            refreshedIp = " 192.168.1.50 ",
            listenPort = 9876,
            onRegisterMdns = { port, ip -> registered.add(port to ip) },
            onUnregisterMdns = { unregisterCount.incrementAndGet() },
        )
        assertEquals(listOf(9876 to "192.168.1.50"), registered)
        assertEquals(0, unregisterCount.get())
        assertEquals(
            "Wi-Fi 192.168.1.50:9876 • Waiting for PC...",
            CamCraftStreamService.buildListeningNotificationText("192.168.1.50", 9876),
        )

        CamCraftStreamService.reconcileMdnsForListening(
            refreshedIp = "   ",
            listenPort = 9876,
            onRegisterMdns = { port, ip -> registered.add(port to ip) },
            onUnregisterMdns = { unregisterCount.incrementAndGet() },
        )
        CamCraftStreamService.reconcileMdnsForListening(
            refreshedIp = null,
            listenPort = 9876,
            onRegisterMdns = { port, ip -> registered.add(port to ip) },
            onUnregisterMdns = { unregisterCount.incrementAndGet() },
        )
        assertEquals(1, registered.size)
        assertEquals(2, unregisterCount.get())
        assertEquals(
            "Port 9876 • Waiting for PC...",
            CamCraftStreamService.buildListeningNotificationText(null, 9876),
        )
    }

    @Test
    fun requestKeyframeIoExceptionClosesOnlyAffectedSessionWithoutFailureAck() {
        val stateFlow = MutableStateFlow(StreamServiceState())
        val coordinator = ControlStateAckCoordinator(stateFlow) {}
        val activeSessionRef = AtomicReference<ClientSessionHandle?>(null)
        val sessionACloses = AtomicInteger(0)
        val sessionBCloses = AtomicInteger(0)

        val sessionA = ClientSessionHandle(
            sessionId = 10L,
            output = object : OutputStream() {
                override fun write(b: Int) = throw IOException("broken pipe during VIDEO_CONFIG")
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("broken pipe during VIDEO_CONFIG")
            },
            outputLock = Any(),
            closeTransport = { sessionACloses.incrementAndGet() },
        )
        val sessionB = ClientSessionHandle(
            sessionId = 11L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = { sessionBCloses.incrementAndGet() },
        )
        activeSessionRef.set(sessionA)
        coordinator.setActiveSession(sessionA)

        var keyframeRequested = false
        var successAckEnqueued = false
        val continueReading = CamCraftStreamService.handleRequestKeyframeCommand(
            session = sessionA,
            cachedConfigProvider = { byteArrayOf(0, 0, 0, 1, 0x67) },
            clearActiveSessionIfCurrent = { failed ->
                if (activeSessionRef.get() === failed) {
                    activeSessionRef.set(null)
                }
                coordinator.clearActiveSessionIfCurrent(failed)
            },
            requestKeyFrame = { keyframeRequested = true },
            enqueueSuccessAck = { successAckEnqueued = true },
        )

        assertFalse(continueReading)
        assertTrue(sessionA.isClosed)
        assertEquals(1, sessionACloses.get())
        assertNull(activeSessionRef.get())
        assertNull(coordinator.currentOwnedSession())
        assertNull(sessionA.ackChannel.tryReceive().getOrNull())
        assertFalse(keyframeRequested)
        assertFalse(successAckEnqueued)

        // When a newer session B is already active, failing session A's teardown leaves B untouched.
        val staleSessionA = ClientSessionHandle(
            sessionId = 10L,
            output = object : OutputStream() {
                override fun write(b: Int) = throw IOException("broken pipe")
                override fun write(b: ByteArray, off: Int, len: Int) = throw IOException("broken pipe")
            },
            outputLock = Any(),
            closeTransport = { sessionACloses.incrementAndGet() },
        )
        activeSessionRef.set(sessionB)
        coordinator.setActiveSession(sessionB)
        assertFalse(
            CamCraftStreamService.handleRequestKeyframeCommand(
                session = staleSessionA,
                cachedConfigProvider = { byteArrayOf(0, 0, 0, 1, 0x67) },
                clearActiveSessionIfCurrent = { failed ->
                    if (activeSessionRef.get() === failed) {
                        activeSessionRef.set(null)
                    }
                    coordinator.clearActiveSessionIfCurrent(failed)
                },
                requestKeyFrame = { keyframeRequested = true },
                enqueueSuccessAck = { successAckEnqueued = true },
            ),
        )
        assertTrue(staleSessionA.isClosed)
        assertFalse(sessionB.isClosed)
        assertEquals(0, sessionBCloses.get())
        assertTrue(activeSessionRef.get() === sessionB)
        assertTrue(coordinator.currentOwnedSession() === sessionB)

        // Successful resend sends VIDEO_CONFIG, triggers keyframe, and enqueues success ACK.
        val okBytes = sessionB.output as ByteArrayOutputStream
        assertTrue(
            CamCraftStreamService.handleRequestKeyframeCommand(
                session = sessionB,
                cachedConfigProvider = { byteArrayOf(0, 0, 0, 1, 0x67, 0x64) },
                clearActiveSessionIfCurrent = {},
                requestKeyFrame = { keyframeRequested = true },
                enqueueSuccessAck = {
                    coordinator.enqueueAckIfSessionOwned(expectedSessionId = sessionB.sessionId) { cur ->
                        ControlAck.success(cur)
                    }
                },
            ),
        )
        assertTrue(keyframeRequested)
        val sentPacket = CcftPacket.readPacket(DataInputStream(ByteArrayInputStream(okBytes.toByteArray())))
        assertEquals(PacketType.VIDEO_CONFIG, sentPacket.header.packetType)
        val ack = sessionB.ackChannel.tryReceive().getOrNull()
        assertNotNull(ack)
        assertTrue(ack!!.ok)
    }

    @Test
    fun helloSnapshotAndAckOwnershipAreAtomicAndGateAckWriterUntilHelloFlushes() = runBlocking {
        val stateFlow = MutableStateFlow(StreamServiceState())
        val coordinator = ControlStateAckCoordinator(stateFlow) {}
        val wireStream = ByteArrayOutputStream()
        val helloFlushStarted = CountDownLatch(1)
        val allowHelloFlush = CountDownLatch(1)
        val gatedOutput = object : OutputStream() {
            override fun write(b: Int) {
                wireStream.write(b)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                wireStream.write(b, off, len)
            }

            override fun flush() {
                helloFlushStarted.countDown()
                val released = allowHelloFlush.await(5, TimeUnit.SECONDS)
                if (!released) {
                    throw IOException("Timed out waiting for allowHelloFlush gate")
                }
                wireStream.flush()
            }
        }
        val session = ClientSessionHandle(
            sessionId = 77L,
            output = gatedOutput,
            outputLock = Any(),
            closeTransport = {},
        )

        // Pre-snapshot mutation happens before HELLO snapshot and ownership attachment.
        coordinator.updateAndEnqueue { cur ->
            cur.copy(torchOn = true, zoomRatio = 2.0f)
        }
        assertNull(session.ackChannel.tryReceive().getOrNull())

        val capturedState = coordinator.attachSessionAndCaptureState(session)
        assertNotNull(capturedState)
        assertTrue(capturedState!!.torchOn)
        assertEquals(2.0f, capturedState.zoomRatio, 0.001f)
        assertNull(session.ackChannel.tryReceive().getOrNull())

        // Closed session cannot acquire ownership.
        val closedSession = ClientSessionHandle(
            sessionId = 78L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = {},
        ).apply { close() }
        assertNull(coordinator.attachSessionAndCaptureState(closedSession))
        assertTrue(coordinator.currentOwnedSession() === session)

        // Flush CONTROL_HELLO using the captured snapshot while a concurrent mutation arrives mid-flush.
        val hello = CamCraftStreamService.createDeviceHello(capturedState, cameras = emptyList())
        val helloPacket = CcftPacket.controlHello(1_000L, hello)
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val helloDeferred = async(Dispatchers.IO) {
                CamCraftStreamService.writeInitialHelloCancellable(
                    scope = scope,
                    session = session,
                ) {
                    helloPacket.encodeTo(session.output, flush = true)
                }
            }
            assertTrue(helloFlushStarted.await(2, TimeUnit.SECONDS))

            // Post-snapshot update during HELLO flush must queue into session.ackChannel, not mutate HELLO.
            coordinator.updateAndEnqueue { cur ->
                cur.copy(audioMuted = true)
            }
            allowHelloFlush.countDown()
            val helloJob = withTimeout(2_000L) { helloDeferred.await() }
            helloJob.join()

            // Only after HELLO finishes flushing do queued post-snapshot ACKs drain onto the wire.
            val ackWriter = launch(Dispatchers.IO) {
                while (true) {
                    val ack = session.ackChannel.tryReceive().getOrNull() ?: break
                    val ackPacket = CcftPacket.controlAck(2_000L, ack)
                    synchronized(session.outputLock) {
                        session.withTrackedWrite {
                            ackPacket.encodeTo(session.output, flush = true)
                        }
                    }
                }
            }
            ackWriter.join()

            val reader = DataInputStream(ByteArrayInputStream(wireStream.toByteArray()))
            val packet1 = CcftPacket.readPacket(reader)
            val packet2 = CcftPacket.readPacket(reader)
            assertEquals(PacketType.CONTROL_HELLO, packet1.header.packetType)
            assertEquals(PacketType.CONTROL_ACK, packet2.header.packetType)

            val decodedHello = packet1.decodeJson<org.artcraft.camcraft.protocol.ControlHello>()
            assertTrue(decodedHello.activeState.torchOn)
            assertEquals(2.0f, decodedHello.activeState.zoomRatio, 0.001f)
            assertFalse(decodedHello.activeState.audioMuted)

            val decodedAck = packet2.decodeJson<ControlAck>()
            val ackState = decodedAck.activeState
            assertNotNull(ackState)
            assertTrue(ackState!!.audioMuted)
            assertTrue(ackState.torchOn)
        } finally {
            allowHelloFlush.countDown()
            scope.cancel()
        }
    }

    @Test
    fun unknownCameraFailureAckUsesCoordinatorLockAndLatestStateWithoutMutatingConfig() {
        val initial = defaultActiveCameraState().copy(cameraId = "0", zoomRatio = 1.5f)
        val stateFlow = MutableStateFlow(StreamServiceState(activeState = initial))
        val coordinator = ControlStateAckCoordinator(stateFlow) {}
        val session = ClientSessionHandle(
            sessionId = 55L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = {},
        )
        coordinator.setActiveSession(session)
        coordinator.updateIfSessionOwned(
            expectedSessionId = 55L,
            advanceCameraConfigOnGeometryChange = false,
            ackBuilder = { null },
        ) { cur ->
            cur.copy(torchOn = true)
        }
        val configIdBefore = coordinator.currentCameraConfigId()
        val cameras = listOf(
            CameraInfo.create(
                id = "0",
                facing = "back",
                label = "Rear",
                resolutions = listOf(ResolutionOption(1920, 1080, 60)),
                maxZoom = 8.0f,
                minEv = -4.0f,
                maxEv = 4.0f,
                hasFlash = true,
            ),
        )

        assertTrue(
            CamCraftStreamService.enqueueUnknownCameraFailureAck(
                coordinator = coordinator,
                expectedSessionId = 55L,
                cameras = cameras,
                cameraId = "99",
            ),
        )
        assertEquals(configIdBefore, coordinator.currentCameraConfigId())
        assertEquals("0", stateFlow.value.activeState.cameraId)
        assertTrue(stateFlow.value.activeState.torchOn)

        val ack = session.ackChannel.tryReceive().getOrNull()
        assertNotNull(ack)
        assertFalse(ack!!.ok)
        assertTrue(ack.error!!.contains("99"))
        val ackState = ack.activeState
        assertNotNull(ackState)
        assertTrue(ackState!!.torchOn)
        assertEquals(1.5f, ackState.zoomRatio, 0.001f)

        // Rejected when session no longer owns the coordinator.
        coordinator.clearActiveSessionIfCurrent(session)
        assertFalse(
            CamCraftStreamService.enqueueUnknownCameraFailureAck(
                coordinator = coordinator,
                expectedSessionId = 55L,
                cameras = cameras,
                cameraId = "99",
            ),
        )
    }

    @Test
    fun stopTransportsCaptureAndAsyncCloseNeverClosesReplacementStartTransports() = runBlocking {
        val oldSessionCloses = AtomicInteger(0)
        val oldTcpCloses = AtomicInteger(0)
        val oldListenerCloses = AtomicInteger(0)
        val oldUsbCloses = AtomicInteger(0)
        val newSessionCloses = AtomicInteger(0)
        val newTcpCloses = AtomicInteger(0)
        val newListenerCloses = AtomicInteger(0)
        val newUsbCloses = AtomicInteger(0)

        val usbTracker = UsbSessionAttemptTracker<Closeable> { it.close() }
        val oldUsbAttempt = usbTracker.beginAttempt()
        val oldUsbDescriptor = Closeable { oldUsbCloses.incrementAndGet() }
        assertTrue(usbTracker.attachDescriptor(oldUsbAttempt, oldUsbDescriptor))

        val oldSession = ClientSessionHandle(
            sessionId = 1L,
            output = ByteArrayOutputStream(),
            outputLock = Any(),
            closeTransport = { oldSessionCloses.incrementAndGet() },
        )
        val oldTcp = Closeable { oldTcpCloses.incrementAndGet() }
        val oldListener = Closeable { oldListenerCloses.incrementAndGet() }

        val detachedUsb = usbTracker.detachActiveAttemptForClose()
        val captured = CamCraftStreamService.Companion.CapturedStopTransports(
            session = oldSession,
            tcpSockets = listOf(oldTcp),
            serverSocket = oldListener,
            usbCloseAction = detachedUsb?.let { desc ->
                { usbTracker.closeDetachedDescriptor(desc) }
            },
            usbCompletion = detachedUsb?.completion,
        )

        // Capturing under lifecycleLock has not performed blocking close() calls synchronously yet.
        assertFalse(oldSession.isClosed)
        assertEquals(0, oldSessionCloses.get())
        assertEquals(0, oldTcpCloses.get())
        assertEquals(0, oldListenerCloses.get())
        assertEquals(0, oldUsbCloses.get())

        val dispatchEntered = CountDownLatch(1)
        val allowDispatch = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>(null)
        val cleanupDone = CompletableDeferred<Unit>()
        val ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-stop-io").apply { isDaemon = true }
        }
        try {
            CamCraftStreamService.dispatchCapturedStopTransports(
                captured = captured,
                completion = cleanupDone,
                dispatchIo = { task ->
                    ioExecutor.execute {
                        try {
                            dispatchEntered.countDown()
                            val released = allowDispatch.await(5, TimeUnit.SECONDS)
                            if (!released) {
                                throw AssertionError("Timed out waiting for allowDispatch gate before Stop cleanup")
                            }
                            task()
                        } catch (t: Throwable) {
                            workerFailure.compareAndSet(null, t)
                            cleanupDone.completeExceptionally(t)
                        }
                    }
                },
            )
            assertTrue(dispatchEntered.await(2, TimeUnit.SECONDS))

            // Simulate a later Start creating brand new transports while async stop cleanup is running.
            val newUsbAttempt = usbTracker.beginAttempt()
            val newUsbDescriptor = Closeable { newUsbCloses.incrementAndGet() }
            assertTrue(usbTracker.attachDescriptor(newUsbAttempt, newUsbDescriptor))
            val newSession = ClientSessionHandle(
                sessionId = 2L,
                output = ByteArrayOutputStream(),
                outputLock = Any(),
                closeTransport = { newSessionCloses.incrementAndGet() },
            )
            val newTcp = Closeable { newTcpCloses.incrementAndGet() }
            val newListener = Closeable { newListenerCloses.incrementAndGet() }

            allowDispatch.countDown()
            withTimeout(2_000L) { cleanupDone.await() }
            workerFailure.get()?.let { throw it }

            assertTrue(oldSession.isClosed)
            assertEquals(1, oldSessionCloses.get())
            assertEquals(1, oldTcpCloses.get())
            assertEquals(1, oldListenerCloses.get())
            assertEquals(1, oldUsbCloses.get())

            assertFalse(newSession.isClosed)
            assertEquals(0, newSessionCloses.get())
            assertEquals(0, newTcpCloses.get())
            assertEquals(0, newListenerCloses.get())
            assertEquals(0, newUsbCloses.get())
            assertTrue(usbTracker.isAttemptCurrent(newUsbAttempt, newUsbDescriptor))
            newTcp.close()
            newListener.close()
            newSession.close()
        } finally {
            allowDispatch.countDown()
            ioExecutor.shutdownNow()
        }
    }

    @Test
    fun usbStartWaitsOffMainForRetiredDescriptorAcrossServiceReplacement() = runBlocking {
        val sharedUsbCloseTracker = PendingCloseTracker()
        val allowOldClose = CountDownLatch(1)
        val allowOldClose2 = CountDownLatch(1)
        val allowOldClose3 = CountDownLatch(1)
        val workerFailure = AtomicReference<Throwable?>(null)

        val instance1Tracker = UsbSessionAttemptTracker<Closeable>(
            pendingCloseTracker = sharedUsbCloseTracker,
        ) { descriptor ->
            descriptor.close()
        }
        val instance2Tracker = UsbSessionAttemptTracker<Closeable>(
            pendingCloseTracker = sharedUsbCloseTracker,
        ) { descriptor ->
            descriptor.close()
        }

        val ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-usb-retire-wait").apply { isDaemon = true }
        }
        try {
            // 1. Immediate proceed to openAccessory when there is no descriptor to retire.
            val emptyAttempt = instance1Tracker.beginAttempt()
            assertNull(instance1Tracker.detachActiveAttemptForClose())
            assertFalse(instance1Tracker.isAttemptPendingOrActive(emptyAttempt))

            val immediateAttempt = instance1Tracker.beginAttempt()
            val immediateObservedPendingClose = AtomicBoolean(false)
            val immediateOpenCount = AtomicInteger(0)
            val openedImmediate = CamCraftStreamService.openUsbAccessoryAfterRetirement(
                usbCloseTracker = sharedUsbCloseTracker,
                attemptTracker = instance1Tracker,
                attempt = immediateAttempt,
                isCancelledOrStopped = { false },
                retireWaitTimeoutMs = 2_000L,
                onPendingCloseObserved = { immediateObservedPendingClose.set(true) },
            ) {
                immediateOpenCount.incrementAndGet()
                Closeable { }
            }
            assertNotNull(openedImmediate)
            openedImmediate?.close()
            assertFalse(immediateObservedPendingClose.get())
            assertEquals(1, immediateOpenCount.get())
            instance1Tracker.closeAttempt(immediateAttempt)

            // 2. Current-attempt wait across service replacement opens accessory once after retirement.
            val oldCloseEntered = CountDownLatch(1)
            val oldCloseCount = AtomicInteger(0)
            val oldAttempt = instance1Tracker.beginAttempt()
            val oldDescriptor = Closeable {
                oldCloseEntered.countDown()
                val released = allowOldClose.await(5, TimeUnit.SECONDS)
                if (!released) {
                    throw AssertionError("Timed out waiting for allowOldClose gate")
                }
                oldCloseCount.incrementAndGet()
            }
            assertTrue(instance1Tracker.attachDescriptor(oldAttempt, oldDescriptor))
            val detached = instance1Tracker.detachActiveAttemptForClose()
            assertNotNull(detached)

            val closeFuture = ioExecutor.submit {
                try {
                    instance1Tracker.closeDetachedDescriptor(detached!!)
                } catch (t: Throwable) {
                    workerFailure.compareAndSet(null, t)
                }
            }
            assertTrue(oldCloseEntered.await(2, TimeUnit.SECONDS))

            val currentAttempt = instance2Tracker.beginAttempt()
            val waiterObservedPendingClose = CountDownLatch(1)
            val currentOpenCount = AtomicInteger(0)
            val openJob = async(Dispatchers.IO) {
                CamCraftStreamService.openUsbAccessoryAfterRetirement(
                    usbCloseTracker = sharedUsbCloseTracker,
                    attemptTracker = instance2Tracker,
                    attempt = currentAttempt,
                    isCancelledOrStopped = { false },
                    retireWaitTimeoutMs = 5_000L,
                    onPendingCloseObserved = { waiterObservedPendingClose.countDown() },
                ) {
                    currentOpenCount.incrementAndGet()
                    Closeable { }
                }
            }
            assertTrue(waiterObservedPendingClose.await(2, TimeUnit.SECONDS))
            assertFalse(openJob.isCompleted)
            assertEquals(0, oldCloseCount.get())
            assertEquals(0, currentOpenCount.get())

            allowOldClose.countDown()
            val openedReplacement = withTimeout(2_000L) { openJob.await() }
            assertNotNull(openedReplacement)
            openedReplacement?.close()
            closeFuture.get(2, TimeUnit.SECONDS)
            workerFailure.get()?.let { throw it }

            assertEquals(1, oldCloseCount.get())
            assertEquals(1, currentOpenCount.get())
            assertTrue(instance2Tracker.isAttemptPendingOrActive(currentAttempt))
            instance2Tracker.closeAttempt(currentAttempt)

            // 3. Ownership revoked before the old descriptor finishes closing aborts before openAccessory.
            val oldCloseEntered2 = CountDownLatch(1)
            val oldCloseCount2 = AtomicInteger(0)
            val oldAttempt2 = instance1Tracker.beginAttempt()
            val oldDescriptor2 = Closeable {
                oldCloseEntered2.countDown()
                val released = allowOldClose2.await(5, TimeUnit.SECONDS)
                if (!released) {
                    throw AssertionError("Timed out waiting for allowOldClose2 gate")
                }
                oldCloseCount2.incrementAndGet()
            }
            assertTrue(instance1Tracker.attachDescriptor(oldAttempt2, oldDescriptor2))
            val detached2 = instance1Tracker.detachActiveAttemptForClose()
            assertNotNull(detached2)

            val closeFuture2 = ioExecutor.submit {
                try {
                    instance1Tracker.closeDetachedDescriptor(detached2!!)
                } catch (t: Throwable) {
                    workerFailure.compareAndSet(null, t)
                }
            }
            assertTrue(oldCloseEntered2.await(2, TimeUnit.SECONDS))

            val revokedAttempt = instance2Tracker.beginAttempt()
            val revokedWaiterObservedPendingClose = CountDownLatch(1)
            val revokedOpenCount = AtomicInteger(0)
            val revokedOpenJob = async(Dispatchers.IO) {
                CamCraftStreamService.openUsbAccessoryAfterRetirement(
                    usbCloseTracker = sharedUsbCloseTracker,
                    attemptTracker = instance2Tracker,
                    attempt = revokedAttempt,
                    isCancelledOrStopped = { false },
                    retireWaitTimeoutMs = 5_000L,
                    onPendingCloseObserved = { revokedWaiterObservedPendingClose.countDown() },
                ) {
                    revokedOpenCount.incrementAndGet()
                    Closeable { }
                }
            }
            assertTrue(revokedWaiterObservedPendingClose.await(2, TimeUnit.SECONDS))
            assertFalse(revokedOpenJob.isCompleted)
            assertEquals(0, revokedOpenCount.get())

            // Revoke newAttempt BEFORE releasing the old descriptor, then verify post-wait decision.
            instance2Tracker.closeAttempt(revokedAttempt)
            assertFalse(instance2Tracker.isAttemptPendingOrActive(revokedAttempt))
            allowOldClose2.countDown()

            val revokedResult = withTimeout(2_000L) { revokedOpenJob.await() }
            assertNull(revokedResult)
            closeFuture2.get(2, TimeUnit.SECONDS)
            workerFailure.get()?.let { throw it }

            assertEquals(1, oldCloseCount2.get())
            assertEquals(0, revokedOpenCount.get())
            assertFalse(instance2Tracker.isAttemptPendingOrActive(revokedAttempt))

            // 4a. Retirement timeout keeps USB startup pending on retry until descriptor finishes closing.
            val oldCloseEntered3 = CountDownLatch(1)
            val oldCloseCount3 = AtomicInteger(0)
            val oldAttempt3 = instance1Tracker.beginAttempt()
            val oldDescriptor3 = Closeable {
                oldCloseEntered3.countDown()
                val released = allowOldClose3.await(5, TimeUnit.SECONDS)
                if (!released) {
                    throw AssertionError("Timed out waiting for allowOldClose3 gate")
                }
                oldCloseCount3.incrementAndGet()
            }
            assertTrue(instance1Tracker.attachDescriptor(oldAttempt3, oldDescriptor3))
            val detached3 = instance1Tracker.detachActiveAttemptForClose()
            assertNotNull(detached3)

            val closeFuture3 = ioExecutor.submit {
                try {
                    instance1Tracker.closeDetachedDescriptor(detached3!!)
                } catch (t: Throwable) {
                    workerFailure.compareAndSet(null, t)
                }
            }
            assertTrue(oldCloseEntered3.await(2, TimeUnit.SECONDS))

            val retryAttempt = instance2Tracker.beginAttempt()
            val firstTimeoutObserved = CountDownLatch(1)
            val timeoutCalls = AtomicInteger(0)
            val retryOpenCount = AtomicInteger(0)
            val retryOpenJob = async(Dispatchers.IO) {
                CamCraftStreamService.openUsbAccessoryAfterRetirement(
                    usbCloseTracker = sharedUsbCloseTracker,
                    attemptTracker = instance2Tracker,
                    attempt = retryAttempt,
                    isCancelledOrStopped = { false },
                    retireWaitTimeoutMs = 40L,
                    onRetirementTimeout = {
                        timeoutCalls.incrementAndGet()
                        firstTimeoutObserved.countDown()
                    },
                ) {
                    retryOpenCount.incrementAndGet()
                    Closeable { }
                }
            }
            assertTrue(firstTimeoutObserved.await(2, TimeUnit.SECONDS))
            assertFalse(retryOpenJob.isCompleted)
            assertEquals(0, retryOpenCount.get())

            allowOldClose3.countDown()
            val retryOpened = withTimeout(2_000L) { retryOpenJob.await() }
            assertNotNull(retryOpened)
            retryOpened?.close()
            closeFuture3.get(2, TimeUnit.SECONDS)
            workerFailure.get()?.let { throw it }

            assertTrue(timeoutCalls.get() >= 1)
            assertEquals(1, oldCloseCount3.get())
            assertEquals(1, retryOpenCount.get())
            instance2Tracker.closeAttempt(retryAttempt)

            // 4b. Repeated timeout remains cancellable without calling openAccessory.
            val stuckToken = sharedUsbCloseTracker.register()
            try {
                val cancelledAttempt = instance2Tracker.beginAttempt()
                val stuckTimeoutObserved = CountDownLatch(1)
                val cancelledOpenCount = AtomicInteger(0)
                val cancelledJob = async(Dispatchers.IO) {
                    CamCraftStreamService.openUsbAccessoryAfterRetirement(
                        usbCloseTracker = sharedUsbCloseTracker,
                        attemptTracker = instance2Tracker,
                        attempt = cancelledAttempt,
                        isCancelledOrStopped = { false },
                        retireWaitTimeoutMs = 40L,
                        onRetirementTimeout = { stuckTimeoutObserved.countDown() },
                    ) {
                        cancelledOpenCount.incrementAndGet()
                        Closeable { }
                    }
                }
                assertTrue(stuckTimeoutObserved.await(2, TimeUnit.SECONDS))
                assertFalse(cancelledJob.isCompleted)
                assertEquals(0, cancelledOpenCount.get())

                cancelledJob.cancel()
                withTimeout(2_000L) { cancelledJob.join() }
                assertTrue(cancelledJob.isCancelled)
                assertEquals(0, cancelledOpenCount.get())
                instance2Tracker.closeAttempt(cancelledAttempt)
            } finally {
                stuckToken.complete(Unit)
            }
        } finally {
            allowOldClose.countDown()
            allowOldClose2.countDown()
            allowOldClose3.countDown()
            ioExecutor.shutdownNow()
        }
    }

    @Test
    fun listenerBindRetriesAfterCrossInstanceRetiringListenerClosesAndFailsOnExternalOwner() = runBlocking {
        val sharedListenerTracker = PendingCloseTracker()
        val ioExecutor = Executors.newSingleThreadExecutor { r ->
            Thread(r, "test-listener-retire-retry").apply { isDaemon = true }
        }
        val allowRetire = CountDownLatch(1)
        val attempt1HitBindException = CountDownLatch(1)
        val allowStartup1Reject = CountDownLatch(1)
        val startup2HitBindConflict = CountDownLatch(1)
        try {
            // A. Retiring listener from previous service instance closes asynchronously -> bind waits cleanly.
            val retireEntered = CountDownLatch(1)
            val retiringCompletion = sharedListenerTracker.register()
            val workerFailure = AtomicReference<Throwable?>(null)
            val closeFuture = ioExecutor.submit {
                try {
                    retireEntered.countDown()
                    val released = allowRetire.await(5, TimeUnit.SECONDS)
                    if (!released) {
                        throw AssertionError("Timed out waiting for allowRetire gate")
                    }
                    retiringCompletion.complete(Unit)
                } catch (t: Throwable) {
                    workerFailure.compareAndSet(null, t)
                    retiringCompletion.completeExceptionally(t)
                }
            }
            assertTrue(retireEntered.await(2, TimeUnit.SECONDS))

            val preBindWaitEnteredA = CountDownLatch(1)
            val retryAttemptsA = AtomicInteger(0)
            val boundA = async(Dispatchers.IO) {
                CamCraftStreamService.bindServerSocketWithRetiringListenerRetry(
                    listenerCloseTracker = sharedListenerTracker,
                    isCancelledOrStopped = { false },
                    maxRetryAttempts = 3,
                    retryWaitTimeoutMs = 5_000L,
                    onPreBindWaitEntered = { preBindWaitEnteredA.countDown() },
                ) {
                    retryAttemptsA.incrementAndGet()
                    Closeable { }
                }
            }
            assertTrue(preBindWaitEnteredA.await(2, TimeUnit.SECONDS))
            assertFalse(boundA.isCompleted)
            assertEquals(0, retryAttemptsA.get())
            allowRetire.countDown()
            withTimeout(2_000L) { boundA.await() }.close()
            closeFuture.get(2, TimeUnit.SECONDS)
            workerFailure.get()?.let { throw it }
            assertEquals(1, retryAttemptsA.get())

            // A2. Pre-bind wait expires while retiring listener is still closing -> BindException triggers retry after closure.
            val retiringCompletionA2 = sharedListenerTracker.register()
            val workerFailureA2 = AtomicReference<Throwable?>(null)
            val closeFutureA2 = ioExecutor.submit {
                try {
                    val hit = attempt1HitBindException.await(5, TimeUnit.SECONDS)
                    if (!hit) {
                        throw AssertionError("Timed out waiting for attempt 1 BindException")
                    }
                    retiringCompletionA2.complete(Unit)
                } catch (t: Throwable) {
                    workerFailureA2.compareAndSet(null, t)
                    retiringCompletionA2.completeExceptionally(t)
                }
            }
            val retryAttemptsA2 = AtomicInteger(0)
            val boundA2 = CamCraftStreamService.bindServerSocketWithRetiringListenerRetry(
                listenerCloseTracker = sharedListenerTracker,
                isCancelledOrStopped = { false },
                maxRetryAttempts = 3,
                retryWaitTimeoutMs = 50L,
            ) {
                if (retryAttemptsA2.incrementAndGet() == 1) {
                    attempt1HitBindException.countDown()
                    throw BindException("Address already in use")
                }
                Closeable { }
            }
            boundA2.close()
            closeFutureA2.get(2, TimeUnit.SECONDS)
            workerFailureA2.get()?.let { throw it }
            assertEquals(2, retryAttemptsA2.get())

            // A3a. Unpublished CamCraft startup tracks ownership before bind until unpublished socket closes.
            val startup1InPublish = CountDownLatch(1)
            val startup2EnteredPreBindWait = CountDownLatch(1)
            val startup1Closed = AtomicBoolean(false)
            val startup1Published = AtomicBoolean(false)
            val startup2Attempts = AtomicInteger(0)
            val startup2Published = AtomicBoolean(false)
            val portInUseA3 = AtomicBoolean(false)

            val startup1Job = async(Dispatchers.IO) {
                CamCraftStreamService.bindAndPublishListenerSocket(
                    listenerTracker = sharedListenerTracker,
                    isCancelledOrStopped = { false },
                    bindSocket = {
                        assertTrue(portInUseA3.compareAndSet(false, true))
                        Closeable {
                            startup1Closed.set(true)
                            portInUseA3.set(false)
                        }
                    },
                    publishSocket = {
                        startup1InPublish.countDown()
                        val released = allowStartup1Reject.await(5, TimeUnit.SECONDS)
                        if (!released) {
                            throw AssertionError("Timed out waiting for allowStartup1Reject gate")
                        }
                        false
                    },
                )?.also {
                    startup1Published.set(true)
                    it.close()
                }
            }
            assertTrue(startup1InPublish.await(2, TimeUnit.SECONDS))

            val startup2Job = async(Dispatchers.IO) {
                CamCraftStreamService.bindAndPublishListenerSocket(
                    listenerTracker = sharedListenerTracker,
                    isCancelledOrStopped = { false },
                    maxRetryAttempts = 3,
                    retryWaitTimeoutMs = 5_000L,
                    onPreBindWaitEntered = { startup2EnteredPreBindWait.countDown() },
                    bindSocket = {
                        startup2Attempts.incrementAndGet()
                        if (!portInUseA3.compareAndSet(false, true)) {
                            throw BindException("Address already in use")
                        }
                        Closeable {
                            portInUseA3.set(false)
                        }
                    },
                    publishSocket = {
                        startup2Published.set(true)
                        true
                    },
                )
            }
            assertTrue(startup2EnteredPreBindWait.await(2, TimeUnit.SECONDS))
            assertFalse(startup2Job.isCompleted)
            assertEquals(0, startup2Attempts.get())

            allowStartup1Reject.countDown()
            assertNull(withTimeout(2_000L) { startup1Job.await() })
            assertTrue(startup1Closed.get())
            assertFalse(startup1Published.get())

            val boundStartup2 = withTimeout(2_000L) { startup2Job.await() }
            assertNotNull(boundStartup2)
            assertTrue(startup2Published.get())
            assertEquals(1, startup2Attempts.get())
            boundStartup2?.close()
            assertTrue(sharedListenerTracker.snapshot().first.isEmpty())

            // A3b. Pre-bind wait expires while unpublished startup still holds port -> retries after socket closes.
            val startup3InPublish = CountDownLatch(1)
            val startup3Closed = AtomicBoolean(false)
            val portInUseA3b = AtomicBoolean(false)
            val startup4Attempts = AtomicInteger(0)
            val startup4Published = AtomicBoolean(false)

            val startup3Job = async(Dispatchers.IO) {
                CamCraftStreamService.bindAndPublishListenerSocket(
                    listenerTracker = sharedListenerTracker,
                    isCancelledOrStopped = { false },
                    bindSocket = {
                        assertTrue(portInUseA3b.compareAndSet(false, true))
                        Closeable {
                            startup3Closed.set(true)
                            portInUseA3b.set(false)
                        }
                    },
                    publishSocket = {
                        startup3InPublish.countDown()
                        val hit = startup2HitBindConflict.await(5, TimeUnit.SECONDS)
                        if (!hit) {
                            throw AssertionError("Timed out waiting for startup2HitBindConflict gate")
                        }
                        false
                    },
                )
            }
            assertTrue(startup3InPublish.await(2, TimeUnit.SECONDS))

            val startup4Job = async(Dispatchers.IO) {
                CamCraftStreamService.bindAndPublishListenerSocket(
                    listenerTracker = sharedListenerTracker,
                    isCancelledOrStopped = { false },
                    maxRetryAttempts = 40,
                    retryWaitTimeoutMs = 40L,
                    bindSocket = {
                        startup4Attempts.incrementAndGet()
                        if (!portInUseA3b.compareAndSet(false, true)) {
                            startup2HitBindConflict.countDown()
                            throw BindException("Address already in use")
                        }
                        Closeable {
                            portInUseA3b.set(false)
                        }
                    },
                    publishSocket = {
                        startup4Published.set(true)
                        true
                    },
                )
            }
            val boundStartup4 = withTimeout(3_000L) { startup4Job.await() }
            assertNull(withTimeout(2_000L) { startup3Job.await() })
            assertTrue(startup3Closed.get())
            assertNotNull(boundStartup4)
            assertTrue(startup4Published.get())
            assertTrue(startup4Attempts.get() >= 2)
            boundStartup4?.close()
            assertTrue(sharedListenerTracker.snapshot().first.isEmpty())

            // B. Microsecond race: retiring listener finishes closing just as first bind throws BindException -> retries & succeeds.
            val raceDeferred = sharedListenerTracker.register()
            val raceAttempts = AtomicInteger(0)
            val boundB = CamCraftStreamService.bindServerSocketWithRetiringListenerRetry(
                listenerCloseTracker = sharedListenerTracker,
                isCancelledOrStopped = { false },
                maxRetryAttempts = 3,
                retryWaitTimeoutMs = 1L,
            ) {
                if (raceAttempts.incrementAndGet() == 1) {
                    raceDeferred.complete(Unit)
                    throw BindException("Address already in use")
                }
                Closeable { }
            }
            boundB.close()
            assertEquals(2, raceAttempts.get())

            // C. External process owns port (no retiring listener or other CamCraft startup pending) -> fails immediately.
            val externalAttempts = AtomicInteger(0)
            val externalFailure = try {
                CamCraftStreamService.bindServerSocketWithRetiringListenerRetry(
                    listenerCloseTracker = sharedListenerTracker,
                    isCancelledOrStopped = { false },
                    maxRetryAttempts = 3,
                    retryWaitTimeoutMs = 100L,
                ) {
                    externalAttempts.incrementAndGet()
                    throw BindException("Address already in use")
                }
                null
            } catch (e: BindException) {
                e
            }
            assertNotNull(externalFailure)
            assertEquals(1, externalAttempts.get())

            val publishExternalAttempts = AtomicInteger(0)
            val publishCalledOnExternalFailure = AtomicBoolean(false)
            val publishExternalFailure = try {
                CamCraftStreamService.bindAndPublishListenerSocket(
                    listenerTracker = sharedListenerTracker,
                    isCancelledOrStopped = { false },
                    maxRetryAttempts = 3,
                    retryWaitTimeoutMs = 100L,
                    bindSocket = {
                        publishExternalAttempts.incrementAndGet()
                        throw BindException("Address already in use")
                    },
                    publishSocket = {
                        publishCalledOnExternalFailure.set(true)
                        true
                    },
                )
                null
            } catch (e: BindException) {
                e
            }
            assertNotNull(publishExternalFailure)
            assertEquals(1, publishExternalAttempts.get())
            assertFalse(publishCalledOnExternalFailure.get())
            assertTrue(sharedListenerTracker.snapshot().first.isEmpty())

            // D. Cancelled/stopped during wait aborts cleanly without binding.
            val stoppedAttempts = AtomicInteger(0)
            val cancelledFailure = try {
                CamCraftStreamService.bindServerSocketWithRetiringListenerRetry(
                    listenerCloseTracker = sharedListenerTracker,
                    isCancelledOrStopped = { true },
                ) {
                    stoppedAttempts.incrementAndGet()
                    Closeable { }
                }
                null
            } catch (e: CancellationException) {
                e
            }
            assertNotNull(cancelledFailure)
            assertEquals(0, stoppedAttempts.get())
        } finally {
            allowRetire.countDown()
            attempt1HitBindException.countDown()
            allowStartup1Reject.countDown()
            startup2HitBindConflict.countDown()
            ioExecutor.shutdownNow()
        }
    }
}
