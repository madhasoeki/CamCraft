package org.artcraft.camcraft.network

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

        ioThread.start()
        uiThread.start()
        startGate.countDown()
        ioThread.join(2_000)
        uiThread.join(2_000)

        // Apply final distinct state and verify ACKs arrive monotonically in state-commit order.
        val finalState = coordinator.updateAndEnqueue { cur ->
            CamCraftStreamService.applyCameraParamsPatch(
                cur,
                ControlCmd.SetCameraParams(rotationDeg = 90, mirrorHorizontal = true),
            )
        }
        ackChannel.close()
        consumerJob.join()

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
}
