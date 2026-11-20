package org.artcraft.camcraft.network

import org.artcraft.camcraft.protocol.CcftFlags
import org.artcraft.camcraft.protocol.CcftProtocolConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
}
