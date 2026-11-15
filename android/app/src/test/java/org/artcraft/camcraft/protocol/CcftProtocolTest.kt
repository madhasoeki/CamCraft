package org.artcraft.camcraft.protocol

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Protocol conformance unit tests matching `desktop/crates/camcraft-protocol/src/lib.rs::tests`.
 * Guarantees zero byte discrepancies between Rust desktop receiver and Kotlin Android sender.
 */
class CcftProtocolTest {

    companion object {
        /**
         * Canonical 20-byte header + 4-byte payload test vector from `docs/PROTOCOL.md`.
         *
         * Header layout (20 bytes big-endian):
         * - Bytes 0..4:   "CCFT" (0x43, 0x43, 0x46, 0x54)
         * - Byte 4:       Version 1 (0x01)
         * - Byte 5:       PacketType::VideoFrame (0x02)
         * - Bytes 6..8:   Flags 0x0011 (KEYFRAME 0x0001 | ROTATE_90 0x0010)
         * - Bytes 8..16:  pts_us = 1_000_000 (0x0000_0000_000f_4240)
         * - Bytes 16..20: payload_len = 4 (0x0000_0004)
         * - Bytes 20..24: Payload "TEST" (0x54, 0x45, 0x53, 0x54)
         */
        val CANONICAL_TEST_FRAME = byteArrayOf(
            0x43.toByte(), 0x43.toByte(), 0x46.toByte(), 0x54.toByte(), // Magic "CCFT"
            0x01.toByte(), // Version 1
            0x02.toByte(), // VideoFrame
            0x00.toByte(), 0x11.toByte(), // Flags: KEYFRAME | ROTATE_90
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x0f.toByte(), 0x42.toByte(), 0x40.toByte(), // pts_us: 1000000
            0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x04.toByte(), // payload_len: 4
            0x54.toByte(), 0x45.toByte(), 0x53.toByte(), 0x54.toByte(), // Payload "TEST"
        )
    }

    @Test
    fun headerSizeAndConstantsMatchSpec() {
        assertEquals("CCFT", CcftProtocolConstants.MAGIC)
        assertArrayEquals(
            byteArrayOf(0x43, 0x43, 0x46, 0x54),
            CcftProtocolConstants.MAGIC_BYTES,
        )
        assertEquals(1.toByte(), CcftProtocolConstants.VERSION)
        assertEquals(20, CcftProtocolConstants.HEADER_SIZE_BYTES)
        assertEquals(9876, CcftProtocolConstants.DEFAULT_PORT)
        assertEquals(16_777_216, CcftProtocolConstants.MAX_PAYLOAD_BYTES)
    }

    @Test
    fun packetTypeRoundtripAndDiscriminants() {
        val cases = listOf(
            PacketType.VIDEO_CONFIG to 0x01,
            PacketType.VIDEO_FRAME to 0x02,
            PacketType.AUDIO_CONFIG to 0x03,
            PacketType.AUDIO_FRAME to 0x04,
            PacketType.CONTROL_HELLO to 0x10,
            PacketType.CONTROL_CMD to 0x11,
            PacketType.CONTROL_ACK to 0x12,
            PacketType.HEARTBEAT_PING to 0xFE,
            PacketType.HEARTBEAT_PONG to 0xFF,
        )

        for ((pt, raw) in cases) {
            assertEquals(raw, pt.rawUByte)
            assertEquals(raw.toByte(), pt.id)
            assertEquals(pt, PacketType.fromId(raw.toByte()))
            assertEquals(pt, PacketType.fromInt(raw))
        }

        try {
            PacketType.fromInt(0x00)
            fail("Expected UnknownPacketType for 0x00")
        } catch (e: CcftProtocolException.UnknownPacketType) {
            assertEquals(0x00.toByte(), e.packetType)
        }

        try {
            PacketType.fromInt(0x55)
            fail("Expected UnknownPacketType for 0x55")
        } catch (e: CcftProtocolException.UnknownPacketType) {
            assertEquals(0x55.toByte(), e.packetType)
        }
    }

    @Test
    fun frameFlagsOperationsAndRotations() {
        val flags = CcftFlags.NONE or
            CcftFlags.KEYFRAME or
            CcftFlags.CODEC_HEVC or
            CcftFlags.MIRROR_H or
            CcftFlags.ROTATE_90

        assertTrue((flags and CcftFlags.KEYFRAME) != 0)
        assertTrue((flags and CcftFlags.CODEC_HEVC) != 0)
        assertTrue((flags and CcftFlags.MIRROR_H) != 0)
        assertEquals(90, CcftFlags.rotationDegrees(flags))

        val rotated180 = (flags and CcftFlags.ROTATION_MASK.inv()) or CcftFlags.flagsForRotation(180)
        assertEquals(180, CcftFlags.rotationDegrees(rotated180))

        val rotated270 = (flags and CcftFlags.ROTATION_MASK.inv()) or CcftFlags.flagsForRotation(270)
        assertEquals(270, CcftFlags.rotationDegrees(rotated270))

        val rotated0 = (flags and CcftFlags.ROTATION_MASK.inv()) or CcftFlags.flagsForRotation(0)
        assertEquals(0, CcftFlags.rotationDegrees(rotated0))

        val combined = CcftFlags.KEYFRAME or CcftFlags.ROTATE_90
        assertEquals(0x0011, combined)
    }

    @Test
    fun canonicalHexVectorDecodeAndEncode() {
        val inputStream = DataInputStream(ByteArrayInputStream(CANONICAL_TEST_FRAME))
        val packet = CcftPacket.readPacket(inputStream)

        assertArrayEquals(CcftProtocolConstants.MAGIC_BYTES, packet.header.magic)
        assertEquals(1.toByte(), packet.header.version)
        assertEquals(PacketType.VIDEO_FRAME, packet.header.packetType)
        assertTrue(packet.header.isKeyframe)
        assertEquals(90, packet.header.rotationDegrees)
        assertEquals(1_000_000L, packet.header.ptsUs)
        assertEquals(4, packet.header.payloadLen)
        assertEquals("TEST", packet.payload.toString(Charsets.UTF_8))

        // Direct serialization verification
        val encodedBytes = packet.toByteArray()
        assertArrayEquals(CANONICAL_TEST_FRAME, encodedBytes)

        // Zero-allocation stream write verification
        val outputStream = ByteArrayOutputStream()
        CcftPacket.writePacket(
            output = outputStream,
            type = packet.header.packetType,
            flags = packet.header.flags,
            ptsUs = packet.header.ptsUs,
            payload = packet.payload,
            flush = true,
        )
        assertArrayEquals(CANONICAL_TEST_FRAME, outputStream.toByteArray())
    }

    @Test
    fun multiplePacketsInSingleStream() {
        val outputStream = ByteArrayOutputStream()

        val p1 = CcftPacket.heartbeatPing(100L)
        val p2 = CcftPacket.videoConfig(
            200L,
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00),
        )
        val p3 = CcftPacket.videoFrame(CcftFlags.KEYFRAME, 300L, "FRAME_1".toByteArray(Charsets.UTF_8))
        val p4 = CcftPacket.heartbeatPong(100L)

        p1.encodeTo(outputStream)
        p2.encodeTo(outputStream)
        p3.encodeTo(outputStream)
        p4.encodeTo(outputStream)

        val inputStream = DataInputStream(ByteArrayInputStream(outputStream.toByteArray()))

        val d1 = CcftPacket.readPacket(inputStream)
        assertEquals(PacketType.HEARTBEAT_PING, d1.header.packetType)
        assertEquals(100L, d1.header.ptsUs)
        assertEquals(0, d1.payload.size)

        val d2 = CcftPacket.readPacket(inputStream)
        assertEquals(PacketType.VIDEO_CONFIG, d2.header.packetType)
        assertEquals(200L, d2.header.ptsUs)
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00),
            d2.payload,
        )

        val d3 = CcftPacket.readPacket(inputStream)
        assertEquals(PacketType.VIDEO_FRAME, d3.header.packetType)
        assertTrue(d3.header.isKeyframe)
        assertEquals("FRAME_1", d3.payload.toString(Charsets.UTF_8))

        val d4 = CcftPacket.readPacket(inputStream)
        assertEquals(PacketType.HEARTBEAT_PONG, d4.header.packetType)
        assertEquals(100L, d4.header.ptsUs)
        assertEquals(0, d4.payload.size)
    }

    @Test
    fun protocolHeaderErrorCases() {
        // Bad Magic
        val badMagic = CANONICAL_TEST_FRAME.copyOf()
        badMagic[0] = 'X'.code.toByte()
        try {
            CcftPacket.readPacket(DataInputStream(ByteArrayInputStream(badMagic)))
            fail("Expected InvalidMagic exception")
        } catch (e: CcftProtocolException.InvalidMagic) {
            assertEquals('X'.code.toByte(), e.actual[0])
        }

        // Bad Version
        val badVer = CANONICAL_TEST_FRAME.copyOf()
        badVer[4] = 99.toByte()
        try {
            CcftPacket.readPacket(DataInputStream(ByteArrayInputStream(badVer)))
            fail("Expected UnsupportedVersion exception")
        } catch (e: CcftProtocolException.UnsupportedVersion) {
            assertEquals(99.toByte(), e.version)
        }

        // Bad PacketType
        val badType = CANONICAL_TEST_FRAME.copyOf()
        badType[5] = 0x77.toByte()
        try {
            CcftPacket.readPacket(DataInputStream(ByteArrayInputStream(badType)))
            fail("Expected UnknownPacketType exception")
        } catch (e: CcftProtocolException.UnknownPacketType) {
            assertEquals(0x77.toByte(), e.packetType)
        }

        // Oversized Payload
        val oversized = CANONICAL_TEST_FRAME.copyOf()
        val tooLarge = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
            .putInt(CcftProtocolConstants.MAX_PAYLOAD_BYTES + 1)
            .array()
        System.arraycopy(tooLarge, 0, oversized, 16, 4)
        try {
            CcftPacket.readPacket(DataInputStream(ByteArrayInputStream(oversized)))
            fail("Expected PayloadTooLarge exception")
        } catch (e: CcftProtocolException.PayloadTooLarge) {
            assertEquals((CcftProtocolConstants.MAX_PAYLOAD_BYTES + 1).toLong(), e.payloadLen)
        }
    }

    @Test
    fun controlHelloJsonSchemaMatchesSpec() {
        val jsonSpec = """
        {
          "device_name": "Pixel 8 Pro",
          "protocol_version": 1,
          "cameras": [
            {
              "id": "0",
              "facing": "back",
              "label": "Rear Main Wide",
              "resolutions": [
                { "width": 1280, "height": 720, "max_fps": 60 },
                { "width": 1920, "height": 1080, "max_fps": 60 },
                { "width": 3840, "height": 2160, "max_fps": 60 }
              ],
              "max_zoom": 8.0,
              "ev_range": [-4.0, 4.0],
              "has_flash": true
            },
            {
              "id": "1",
              "facing": "front",
              "label": "Front Selfie",
              "resolutions": [
                { "width": 1280, "height": 720, "max_fps": 60 },
                { "width": 1920, "height": 1080, "max_fps": 60 }
              ],
              "max_zoom": 2.0,
              "ev_range": [-2.0, 2.0],
              "has_flash": false
            }
          ],
          "active_state": {
            "camera_id": "0",
            "width": 1920,
            "height": 1080,
            "fps": 60,
            "bitrate_kbps": 12000,
            "zoom_ratio": 1.0,
            "exposure_ev": 0.0,
            "autofocus": true,
            "torch_on": false,
            "mirror_horizontal": false,
            "rotation_deg": 0,
            "audio_enabled": true,
            "audio_muted": false
          }
        }
        """.trimIndent()

        val hello = CcftJson.decodeFromString<ControlHello>(jsonSpec)
        assertEquals("Pixel 8 Pro", hello.deviceName)
        assertEquals(1, hello.protocolVersion)
        assertEquals(2, hello.cameras.size)

        val cam0 = hello.cameras[0]
        assertEquals("0", cam0.id)
        assertEquals("back", cam0.facing)
        assertEquals(3, cam0.resolutions.size)
        assertEquals(8.0f, cam0.maxZoom, 0.001f)
        assertEquals(listOf(-4.0f, 4.0f), cam0.evRange)
        assertEquals(-4.0f, cam0.minEv, 0.001f)
        assertEquals(4.0f, cam0.maxEv, 0.001f)
        assertTrue(cam0.hasFlash)

        val active = hello.activeState
        assertEquals("0", active.cameraId)
        assertEquals(1920, active.width)
        assertEquals(1080, active.height)
        assertEquals(60, active.fps)
        assertEquals(12000, active.bitrateKbps)
        assertTrue(active.autofocus)
        assertFalse(active.torchOn)
        assertTrue(active.audioEnabled)

        val packet = CcftPacket.controlHello(42L, hello)
        assertEquals(PacketType.CONTROL_HELLO, packet.header.packetType)
        val roundtrip: ControlHello = packet.decodeJson()
        assertEquals(hello, roundtrip)
    }

    @Test
    fun controlCmdVariantsSerializeAndDeserialize() {
        // ConfigureStream
        val configure = ControlCmd.ConfigureStream(
            cameraId = "0",
            width = 1920,
            height = 1080,
            fps = 60,
            bitrateKbps = 15000,
        )
        val cfgJson = CcftJson.encodeToString(ControlCmd.serializer(), configure)
        assertTrue(cfgJson.contains(""""cmd":"ConfigureStream""""))
        assertTrue(cfgJson.contains(""""bitrate_kbps":15000"""))
        val cfgRoundtrip = CcftJson.decodeFromString(ControlCmd.serializer(), cfgJson)
        assertEquals(configure, cfgRoundtrip)

        // SetCameraParams from Spec JSON
        val specParamsJson = """
        {
          "cmd": "SetCameraParams",
          "zoom_ratio": 1.5,
          "exposure_ev": 0.5,
          "autofocus": true,
          "torch_on": false,
          "mirror_horizontal": false,
          "rotation_deg": 0
        }
        """.trimIndent()
        val params = CcftJson.decodeFromString(ControlCmd.serializer(), specParamsJson)
        assertTrue(params is ControlCmd.SetCameraParams)
        val p = params as ControlCmd.SetCameraParams
        assertEquals(1.5f, p.zoomRatio ?: 0f, 0.001f)
        assertEquals(0.5f, p.exposureEv ?: 0f, 0.001f)
        assertEquals(true, p.autofocus)
        assertEquals(false, p.torchOn)
        assertEquals(false, p.mirrorHorizontal)
        assertEquals(0, p.rotationDeg)

        // SetAudioState
        val audioCmd = ControlCmd.SetAudioState(enabled = true, muted = false)
        val audioJson = CcftJson.encodeToString(ControlCmd.serializer(), audioCmd)
        assertTrue(audioJson.contains(""""cmd":"SetAudioState""""))
        val audioRoundtrip = CcftJson.decodeFromString(ControlCmd.serializer(), audioJson)
        assertEquals(audioCmd, audioRoundtrip)

        // RequestKeyframe
        val keyframeCmd = ControlCmd.RequestKeyframe
        val kfJson = CcftJson.encodeToString(ControlCmd.serializer(), keyframeCmd)
        assertEquals("""{"cmd":"RequestKeyframe"}""", kfJson)
        val kfRoundtrip = CcftJson.decodeFromString(ControlCmd.serializer(), """{"cmd":"RequestKeyframe"}""")
        assertEquals(keyframeCmd, kfRoundtrip)
    }

    @Test
    fun controlAckJsonSchemaMatchesSpec() {
        val ackSpecJson = """
        {
          "ok": true,
          "error": null,
          "active_state": {
            "camera_id": "0",
            "width": 1920,
            "height": 1080,
            "fps": 60,
            "bitrate_kbps": 15000,
            "zoom_ratio": 1.5,
            "exposure_ev": 0.5,
            "autofocus": true,
            "torch_on": false,
            "mirror_horizontal": false,
            "rotation_deg": 0,
            "audio_enabled": true,
            "audio_muted": false
          }
        }
        """.trimIndent()

        val ack = CcftJson.decodeFromString<ControlAck>(ackSpecJson)
        assertTrue(ack.ok)
        assertNull(ack.error)
        assertNotNull(ack.activeState)
        assertEquals("0", ack.activeState?.cameraId)
        assertEquals(15000, ack.activeState?.bitrateKbps)

        val failureAck = ControlAck.failure("Camera device busy")
        assertFalse(failureAck.ok)
        assertEquals("Camera device busy", failureAck.error)
        assertNull(failureAck.activeState)
    }

    @Test
    fun audioConfigPayloadRoundtrip() {
        val payload = AudioConfigPayload(
            sampleRate = 48000,
            channels = 2,
            codec = "opus",
        )

        val json = CcftJson.encodeToString(AudioConfigPayload.serializer(), payload)
        val decoded = CcftJson.decodeFromString(AudioConfigPayload.serializer(), json)
        assertEquals(payload, decoded)
    }
}
