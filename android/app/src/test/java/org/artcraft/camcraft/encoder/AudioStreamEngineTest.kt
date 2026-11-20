package org.artcraft.camcraft.encoder

import org.artcraft.camcraft.protocol.AudioConfigPayload
import org.artcraft.camcraft.protocol.CcftJson
import org.artcraft.camcraft.protocol.CcftPacket
import org.artcraft.camcraft.protocol.PacketType
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioStreamEngineTest {

    @Test
    fun audioStreamConstantsMatchProtocolSpec() {
        assertEquals(48_000, AudioStreamEngine.SAMPLE_RATE_HZ)
        assertEquals(1, AudioStreamEngine.CHANNEL_COUNT)
        assertEquals(20, AudioStreamEngine.FRAME_DURATION_MS)
        assertEquals(960, AudioStreamEngine.SAMPLES_PER_FRAME)
        assertEquals(1_920, AudioStreamEngine.FRAME_SIZE_BYTES)
        assertEquals("pcm_s16le", AudioStreamEngine.CODEC_PCM_S16LE)
    }

    @Test
    fun buildAudioConfigBytesMatchesProtocolJsonAndPacketType() {
        val bytes = AudioStreamEngine.buildAudioConfigBytes()
        val parsed = CcftJson.decodeFromString<AudioConfigPayload>(bytes.toString(Charsets.UTF_8))
        assertEquals(48_000, parsed.sampleRate)
        assertEquals(1, parsed.channels)
        assertEquals("pcm_s16le", parsed.codec)

        val packet = CcftPacket.audioConfig(1_234L, AudioStreamEngine.buildAudioConfig())
        assertEquals(PacketType.AUDIO_CONFIG, packet.header.packetType)
        assertEquals(0x03.toByte(), packet.header.packetType.id)
        assertArrayEquals(bytes, packet.payload)
    }

    @Test
    fun selectFramePayloadSwitchesZeroAllocationBetweenLiveAndSilence() {
        val captured = ByteArray(AudioStreamEngine.FRAME_SIZE_BYTES) { 0x42 }
        val silence = ByteArray(AudioStreamEngine.FRAME_SIZE_BYTES)

        val unmutedPayload = AudioStreamEngine.selectFramePayload(captured, silence, muted = false)
        assertSame(captured, unmutedPayload)

        val mutedPayload = AudioStreamEngine.selectFramePayload(captured, silence, muted = true)
        assertSame(silence, mutedPayload)
        assertTrue(mutedPayload.all { it == 0.toByte() })
    }

    @Test
    fun calculateBufferSizeBytesAlignsToFrameMultiple() {
        val minSmall = AudioStreamEngine.calculateBufferSizeBytes(512)
        assertEquals(AudioStreamEngine.FRAME_SIZE_BYTES * 4, minSmall)
        assertEquals(0, minSmall % AudioStreamEngine.FRAME_SIZE_BYTES)

        val minLarge = AudioStreamEngine.calculateBufferSizeBytes(10_000)
        assertTrue(minLarge >= 10_000)
        assertEquals(0, minLarge % AudioStreamEngine.FRAME_SIZE_BYTES)

        val invalidMin = AudioStreamEngine.calculateBufferSizeBytes(-1)
        assertEquals(AudioStreamEngine.FRAME_SIZE_BYTES * 4, invalidMin)
    }
}
