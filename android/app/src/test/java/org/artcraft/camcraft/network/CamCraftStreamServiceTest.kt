package org.artcraft.camcraft.network

import java.io.BufferedOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.management.ManagementFactory
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
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
import org.artcraft.camcraft.protocol.CcftProtocolConstants
import org.artcraft.camcraft.protocol.ControlAck
import org.artcraft.camcraft.protocol.ControlCmd
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
        val oldStream = ByteArrayOutputStream()
        val newStream = ByteArrayOutputStream()
        val newCloserInvocations = AtomicInteger(0)
        val newSocketCloses = AtomicInteger(0)
        val newCloser: () -> Unit = { newCloserInvocations.incrementAndGet() }
        val newSocket = java.io.Closeable { newSocketCloses.incrementAndGet() }

        var activeStreamRef: java.io.OutputStream? = newStream
        val (staleCloser, staleSocket) = CamCraftStreamService.takeTeardownTargetsForFailedStream(
            activeStream = activeStreamRef,
            failedStream = oldStream,
            activeCloser = newCloser,
            activeSocket = newSocket,
            clearActiveStream = { activeStreamRef = null },
        )
        assertNull(staleCloser)
        assertNull(staleSocket)
        assertEquals(0, newCloserInvocations.get())
        assertEquals(0, newSocketCloses.get())
        assertEquals(newStream, activeStreamRef)

        val (matchedCloser, matchedSocket) = CamCraftStreamService.takeTeardownTargetsForFailedStream(
            activeStream = activeStreamRef,
            failedStream = newStream,
            activeCloser = newCloser,
            activeSocket = newSocket,
            clearActiveStream = { activeStreamRef = null },
        )
        matchedCloser?.invoke()
        matchedSocket?.close()
        assertNull(activeStreamRef)
        assertEquals(1, newCloserInvocations.get())
        assertEquals(1, newSocketCloses.get())
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
}
