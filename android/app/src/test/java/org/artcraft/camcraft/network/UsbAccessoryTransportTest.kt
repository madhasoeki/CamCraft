package org.artcraft.camcraft.network

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbAccessoryTransportTest {

    private class RecordingOutputStream : OutputStream() {
        val writeSizes = mutableListOf<Int>()
        val singleByteWrites = mutableListOf<Int>()
        val buffer = ByteArrayOutputStream()
        var flushCount = 0
        var closeCount = 0

        override fun write(b: Int) {
            singleByteWrites.add(b)
            writeSizes.add(1)
            buffer.write(b)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            writeSizes.add(len)
            buffer.write(b, off, len)
        }

        override fun flush() {
            flushCount++
        }

        override fun close() {
            closeCount++
        }
    }

    @Test
    fun constantsMatchDesktopAoaSpec() {
        assertEquals("ArtCraft", UsbAccessoryTransport.MANUFACTURER)
        assertEquals("CamCraft", UsbAccessoryTransport.MODEL)
        assertEquals("1.0", UsbAccessoryTransport.VERSION)
        assertEquals(16_384, UsbAccessoryTransport.MAX_AOA_WRITE_CHUNK)
    }

    @Test
    fun isCamCraftAccessoryMatchesExactManufacturerAndModel() {
        assertTrue(UsbAccessoryTransport.isCamCraftAccessory("ArtCraft", "CamCraft"))
        assertFalse(UsbAccessoryTransport.isCamCraftAccessory(null, "CamCraft"))
        assertFalse(UsbAccessoryTransport.isCamCraftAccessory("ArtCraft", null))
        assertFalse(UsbAccessoryTransport.isCamCraftAccessory(null, null))
        assertFalse(UsbAccessoryTransport.isCamCraftAccessory("artcraft", "CamCraft"))
        assertFalse(UsbAccessoryTransport.isCamCraftAccessory("ArtCraft", "camcraft"))
        assertFalse(UsbAccessoryTransport.isCamCraftAccessory("Other", "CamCraft"))
        assertFalse(UsbAccessoryTransport.isCamCraftAccessory("ArtCraft", "Other"))
        assertFalse(UsbAccessoryTransport.isCamCraftAccessory(null))
    }

    @Test
    fun aoaChunkedOutputStreamSplits50KiBIntoExpectedChunks() {
        val delegate = RecordingOutputStream()
        val chunked = AoaChunkedOutputStream(delegate)
        val payload = ByteArray(50 * 1024) { idx -> (idx * 31 + 7).toByte() }

        chunked.write(payload)

        assertEquals(listOf(16_384, 16_384, 16_384, 2_048), delegate.writeSizes)
        assertArrayEquals(payload, delegate.buffer.toByteArray())
    }

    @Test
    fun aoaChunkedOutputStreamHandlesExact16384Boundary() {
        val delegate = RecordingOutputStream()
        val chunked = AoaChunkedOutputStream(delegate)
        val oneChunk = ByteArray(16_384) { idx -> (idx and 0xFF).toByte() }
        val twoChunks = ByteArray(32_768) { idx -> ((idx + 13) and 0xFF).toByte() }

        chunked.write(oneChunk)
        assertEquals(listOf(16_384), delegate.writeSizes)
        assertArrayEquals(oneChunk, delegate.buffer.toByteArray())

        delegate.writeSizes.clear()
        delegate.buffer.reset()

        chunked.write(twoChunks)
        assertEquals(listOf(16_384, 16_384), delegate.writeSizes)
        assertArrayEquals(twoChunks, delegate.buffer.toByteArray())
    }

    @Test
    fun aoaChunkedOutputStreamHandlesSmallAndZeroLengthAndSingleByteWrites() {
        val delegate = RecordingOutputStream()
        val chunked = AoaChunkedOutputStream(delegate)
        val small = byteArrayOf(1, 2, 3, 4, 5)

        chunked.write(small, 0, 0)
        assertTrue(delegate.writeSizes.isEmpty())

        chunked.write(small)
        chunked.write(0x42)

        assertEquals(listOf(5, 1), delegate.writeSizes)
        assertEquals(listOf(0x42), delegate.singleByteWrites)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 0x42), delegate.buffer.toByteArray())
    }

    @Test
    fun aoaChunkedOutputStreamRespectsOffsetAndLengthSlices() {
        val delegate = RecordingOutputStream()
        val chunked = AoaChunkedOutputStream(delegate)
        val backing = ByteArray(60 * 1024) { idx -> (idx * 17 + 3).toByte() }
        val offset = 137
        val length = 40_000

        chunked.write(backing, offset, length)

        assertEquals(listOf(16_384, 16_384, 7_232), delegate.writeSizes)
        assertArrayEquals(backing.copyOfRange(offset, offset + length), delegate.buffer.toByteArray())
    }

    @Test
    fun aoaChunkedOutputStreamRejectsInvalidBoundsAndChunkSize() {
        val delegate = RecordingOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            AoaChunkedOutputStream(delegate, maxChunkSize = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            AoaChunkedOutputStream(delegate, maxChunkSize = -1)
        }

        val chunked = AoaChunkedOutputStream(delegate)
        val buf = ByteArray(16)
        assertThrows(IndexOutOfBoundsException::class.java) { chunked.write(buf, -1, 4) }
        assertThrows(IndexOutOfBoundsException::class.java) { chunked.write(buf, 0, -1) }
        assertThrows(IndexOutOfBoundsException::class.java) { chunked.write(buf, 10, 8) }
        assertThrows(IndexOutOfBoundsException::class.java) { chunked.write(buf, 1, Int.MAX_VALUE) }
    }

    @Test
    fun aoaChunkedOutputStreamPropagatesFlushAndClose() {
        val delegate = RecordingOutputStream()
        val chunked = AoaChunkedOutputStream(delegate)

        chunked.flush()
        chunked.close()

        assertEquals(1, delegate.flushCount)
        assertEquals(1, delegate.closeCount)
    }
}
