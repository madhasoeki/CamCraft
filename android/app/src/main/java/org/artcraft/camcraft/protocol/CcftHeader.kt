package org.artcraft.camcraft.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Discriminant byte for CCFT wire packet types matching `docs/PROTOCOL.md` §3.
 */
enum class PacketType(val id: Byte) {
    VIDEO_CONFIG(0x01.toByte()),
    VIDEO_FRAME(0x02.toByte()),
    AUDIO_CONFIG(0x03.toByte()),
    AUDIO_FRAME(0x04.toByte()),
    CONTROL_HELLO(0x10.toByte()),
    CONTROL_CMD(0x11.toByte()),
    CONTROL_ACK(0x12.toByte()),
    HEARTBEAT_PING(0xFE.toByte()),
    HEARTBEAT_PONG(0xFF.toByte());

    val rawUByte: Int
        get() = id.toInt() and 0xFF

    companion object {
        fun fromId(raw: Byte): PacketType = when (raw.toInt() and 0xFF) {
            0x01 -> VIDEO_CONFIG
            0x02 -> VIDEO_FRAME
            0x03 -> AUDIO_CONFIG
            0x04 -> AUDIO_FRAME
            0x10 -> CONTROL_HELLO
            0x11 -> CONTROL_CMD
            0x12 -> CONTROL_ACK
            0xFE -> HEARTBEAT_PING
            0xFF -> HEARTBEAT_PONG
            else -> throw CcftProtocolException.UnknownPacketType(raw)
        }

        fun fromInt(raw: Int): PacketType = fromId((raw and 0xFF).toByte())
    }
}

/**
 * Fixed 20-byte CCFT binary frame header.
 * Layout (Big-Endian):
 * - Bytes 0..4:   ASCII "CCFT" (0x43, 0x43, 0x46, 0x54)
 * - Byte 4:       Version (1)
 * - Byte 5:       PacketType discriminant
 * - Bytes 6..8:   Flags bitmask (u16 BE)
 * - Bytes 8..16:  Presentation timestamp in microseconds (u64 BE)
 * - Bytes 16..20: Payload length in bytes (u32 BE)
 */
data class CcftHeader(
    val packetType: PacketType,
    val flags: Int = CcftFlags.NONE,
    val ptsUs: Long = 0L,
    val payloadLen: Int = 0,
    val magic: ByteArray = CcftProtocolConstants.MAGIC_BYTES.copyOf(),
    val version: Byte = CcftProtocolConstants.VERSION,
) {
    val isKeyframe: Boolean
        get() = (flags and CcftFlags.KEYFRAME) != 0

    val isHevc: Boolean
        get() = (flags and CcftFlags.CODEC_HEVC) != 0

    val isMirroredH: Boolean
        get() = (flags and CcftFlags.MIRROR_H) != 0

    val rotationDegrees: Int
        get() = CcftFlags.rotationDegrees(flags)

    /**
     * Serializes this 20-byte header into the provided [ByteBuffer].
     * Configures the buffer order to [ByteOrder.BIG_ENDIAN].
     */
    fun writeTo(buffer: ByteBuffer) {
        if (buffer.remaining() < CcftProtocolConstants.HEADER_SIZE_BYTES) {
            throw CcftProtocolException.BufferTooShort(
                expected = CcftProtocolConstants.HEADER_SIZE_BYTES,
                actual = buffer.remaining(),
            )
        }
        buffer.order(ByteOrder.BIG_ENDIAN)
        buffer.put(magic)
        buffer.put(version)
        buffer.put(packetType.id)
        buffer.putShort((flags and 0xFFFF).toShort())
        buffer.putLong(ptsUs)
        buffer.putInt(payloadLen)
    }

    /**
     * Serializes this 20-byte header to a standalone [ByteArray].
     */
    fun toByteArray(): ByteArray {
        val bytes = ByteArray(CcftProtocolConstants.HEADER_SIZE_BYTES)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        writeTo(buffer)
        return bytes
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CcftHeader) return false

        if (packetType != other.packetType) return false
        if (flags != other.flags) return false
        if (ptsUs != other.ptsUs) return false
        if (payloadLen != other.payloadLen) return false
        if (version != other.version) return false
        if (!magic.contentEquals(other.magic)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = packetType.hashCode()
        result = 31 * result + flags
        result = 31 * result + ptsUs.hashCode()
        result = 31 * result + payloadLen
        result = 31 * result + magic.contentHashCode()
        result = 31 * result + version
        return result
    }

    companion object {
        /**
         * Parses a [CcftHeader] from a [ByteBuffer] at its current position.
         * Validates magic bytes, protocol version, and payload length limits.
         */
        fun fromByteBuffer(buffer: ByteBuffer): CcftHeader {
            if (buffer.remaining() < CcftProtocolConstants.HEADER_SIZE_BYTES) {
                throw CcftProtocolException.BufferTooShort(
                    expected = CcftProtocolConstants.HEADER_SIZE_BYTES,
                    actual = buffer.remaining(),
                )
            }

            buffer.order(ByteOrder.BIG_ENDIAN)

            val magicBytes = ByteArray(4)
            buffer.get(magicBytes)
            if (!magicBytes.contentEquals(CcftProtocolConstants.MAGIC_BYTES)) {
                throw CcftProtocolException.InvalidMagic(magicBytes)
            }

            val version = buffer.get()
            if (version != CcftProtocolConstants.VERSION) {
                throw CcftProtocolException.UnsupportedVersion(version)
            }

            val packetType = PacketType.fromId(buffer.get())
            val flags = buffer.short.toInt() and 0xFFFF
            val ptsUs = buffer.long
            val payloadLenInt = buffer.int
            val payloadLenLong = payloadLenInt.toLong() and 0xFFFFFFFFL

            if (payloadLenLong > CcftProtocolConstants.MAX_PAYLOAD_BYTES) {
                throw CcftProtocolException.PayloadTooLarge(payloadLenLong)
            }

            return CcftHeader(
                packetType = packetType,
                flags = flags,
                ptsUs = ptsUs,
                payloadLen = payloadLenInt,
                magic = magicBytes,
                version = version,
            )
        }

        /**
         * Parses a [CcftHeader] from a [ByteArray].
         */
        fun fromByteArray(bytes: ByteArray, offset: Int = 0): CcftHeader {
            val length = bytes.size - offset
            if (length < CcftProtocolConstants.HEADER_SIZE_BYTES) {
                throw CcftProtocolException.BufferTooShort(
                    expected = CcftProtocolConstants.HEADER_SIZE_BYTES,
                    actual = length,
                )
            }
            val buffer = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN)
            return fromByteBuffer(buffer)
        }
    }
}
