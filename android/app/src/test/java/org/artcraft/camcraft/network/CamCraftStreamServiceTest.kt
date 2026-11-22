package org.artcraft.camcraft.network

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

            // Extend the USB close race: pause A's dispatched closer, install USB attempt B while A's
            // close is still in progress, then resume A's closer and assert B passes admission.
            val usbACloses = AtomicInteger(0)
            val usbBCloses = AtomicInteger(0)
            val attemptACloseEntered = CountDownLatch(1)
            val allowAttemptACloseFinish = CountDownLatch(1)
            val closeRaceExecutor = Executors.newSingleThreadExecutor { r ->
                Thread(r, "test-usb-close-race").apply { isDaemon = true }
            }
            val tracker = UsbSessionAttemptTracker<String> { descriptor ->
                when (descriptor) {
                    "usb-desc-A" -> {
                        attemptACloseEntered.countDown()
                        allowAttemptACloseFinish.await(2, TimeUnit.SECONDS)
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
            // while A's close is still in progress.
            val closeFutureA = closeRaceExecutor.submit { pendingClose.get()!!.invoke() }
            assertTrue(attemptACloseEntered.await(2, TimeUnit.SECONDS))
            assertFalse(tracker.hasActiveNonClosingAttempt())

            val attemptB = tracker.beginAttempt(accessoryTag = "acc-B")
            assertTrue(tracker.attachDescriptor(attemptB, "usb-desc-B"))
            assertTrue(tracker.isAttemptCurrent(attemptB, "usb-desc-B"))
            assertTrue(tracker.hasActiveNonClosingAttempt())

            // Resume A's closer; B must continue passing admission and remain open.
            allowAttemptACloseFinish.countDown()
            closeFutureA.get(2, TimeUnit.SECONDS)
            racingHandle.close()

            assertEquals(1, usbACloses.get())
            assertEquals(0, usbBCloses.get())
            assertFalse(tracker.isAttemptCurrent(attemptA, "usb-desc-A"))
            assertTrue(tracker.isAttemptCurrent(attemptB, "usb-desc-B"))
            assertTrue(tracker.hasActiveNonClosingAttempt())
            closeRaceExecutor.shutdownNow()
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
}
