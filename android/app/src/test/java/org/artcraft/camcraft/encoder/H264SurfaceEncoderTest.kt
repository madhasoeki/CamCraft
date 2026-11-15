package org.artcraft.camcraft.encoder

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class H264SurfaceEncoderTest {

    @Test
    fun ensureAnnexBKeepsFourByteStartCodeWithoutNewAllocation() {
        val original = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00)
        val result = H264SurfaceEncoder.ensureAnnexB(original)
        assertArrayEquals(original, result)
        assertEquals(System.identityHashCode(original), System.identityHashCode(result))
    }

    @Test
    fun ensureAnnexBNormalizesThreeByteStartCodeToFourBytes() {
        val threeByte = byteArrayOf(0, 0, 1, 0x68, 0xCE.toByte())
        val result = H264SurfaceEncoder.ensureAnnexB(threeByte)
        val expected = byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte())
        assertArrayEquals(expected, result)
    }

    @Test
    fun ensureAnnexBPrependsStartCodeWhenMissing() {
        val raw = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte())
        val result = H264SurfaceEncoder.ensureAnnexB(raw)
        val expected = byteArrayOf(0, 0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte())
        assertArrayEquals(expected, result)
    }

    @Test
    fun combineSpsPpsProducesUnifiedAnnexBSequence() {
        val sps = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00))
        val pps = ByteBuffer.wrap(byteArrayOf(0, 0, 0, 1, 0x68, 0xCE.toByte()))

        val combined = H264SurfaceEncoder.combineSpsPps(sps, pps)
        val expected = byteArrayOf(
            0, 0, 0, 1, 0x67, 0x42, 0x00,
            0, 0, 0, 1, 0x68, 0xCE.toByte(),
        )
        assertArrayEquals(expected, combined)
    }
}
