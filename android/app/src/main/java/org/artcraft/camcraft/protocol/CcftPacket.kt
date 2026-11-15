package org.artcraft.camcraft.protocol

import java.io.DataInputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.encodeToString

/**
 * Empty payload singleton avoiding allocations for zero-length packets (e.g. Heartbeats).
 */
val EMPTY_PAYLOAD: ByteArray = ByteArray(0)

/**
 * Complete CCFT v1 wire packet comprising a 20-byte header and binary payload.
 */
data class CcftPacket(
    val header: CcftHeader,
    val payload: ByteArray = EMPTY_PAYLOAD,
) {
    init {
        require(header.payloadLen == payload.size) {
            "Header payloadLen (${header.payloadLen}) does not match payload array size (${payload.size})"
        }
    }

    /**
     * Serializes this packet directly to the destination [OutputStream].
     */
    fun encodeTo(output: OutputStream, flush: Boolean = false) {
        writePacket(output, this, flush)
    }

    /**
     * Serializes this packet into a contiguous [ByteArray].
     */
    fun toByteArray(): ByteArray {
        val totalLen = CcftProtocolConstants.HEADER_SIZE_BYTES + payload.size
        val bytes = ByteArray(totalLen)
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val alignedHeader = if (header.payloadLen == payload.size) header else header.copy(payloadLen = payload.size)
        alignedHeader.writeTo(buf)
        if (payload.isNotEmpty()) {
            System.arraycopy(payload, 0, bytes, CcftProtocolConstants.HEADER_SIZE_BYTES, payload.size)
        }
        return bytes
    }

    /**
     * Deserializes UTF-8 JSON payload into type [T].
     */
    inline fun <reified T> decodeJson(): T =
        CcftJson.decodeFromString<T>(payload.toString(Charsets.UTF_8))

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CcftPacket) return false
        if (header != other.header) return false
        if (!payload.contentEquals(other.payload)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = header.hashCode()
        result = 31 * result + payload.contentHashCode()
        return result
    }

    companion object {
        /**
         * Thread-local reusable 20-byte ByteBuffer for zero-allocation header writes.
         */
        private val threadLocalHeaderBuffer: ThreadLocal<ByteBuffer> = ThreadLocal.withInitial {
            ByteBuffer.allocate(CcftProtocolConstants.HEADER_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)
        }

        /**
         * Constructs a packet aligning header payloadLen with byte array size.
         */
        fun fromParts(
            packetType: PacketType,
            flags: Int,
            ptsUs: Long,
            payload: ByteArray = EMPTY_PAYLOAD,
        ): CcftPacket {
            val header = CcftHeader(
                packetType = packetType,
                flags = flags,
                ptsUs = ptsUs,
                payloadLen = payload.size,
            )
            return CcftPacket(header, payload)
        }

        /**
         * Creates a video frame packet.
         */
        fun videoFrame(flags: Int, ptsUs: Long, payload: ByteArray): CcftPacket =
            fromParts(PacketType.VIDEO_FRAME, flags, ptsUs, payload)

        /**
         * Creates a video config packet (SPS/PPS parameter sets).
         */
        fun videoConfig(ptsUs: Long, payload: ByteArray): CcftPacket =
            fromParts(PacketType.VIDEO_CONFIG, CcftFlags.NONE, ptsUs, payload)

        /**
         * Creates an audio stream configuration packet.
         */
        fun audioConfig(ptsUs: Long, config: AudioConfigPayload): CcftPacket {
            val payload = CcftJson.encodeToString(config).toByteArray(Charsets.UTF_8)
            return fromParts(PacketType.AUDIO_CONFIG, CcftFlags.NONE, ptsUs, payload)
        }

        /**
         * Creates an audio config packet from raw JSON bytes.
         */
        fun audioConfig(ptsUs: Long, payload: ByteArray): CcftPacket =
            fromParts(PacketType.AUDIO_CONFIG, CcftFlags.NONE, ptsUs, payload)

        /**
         * Creates an audio frame packet (Opus or PCM S16LE).
         */
        fun audioFrame(ptsUs: Long, payload: ByteArray): CcftPacket =
            fromParts(PacketType.AUDIO_FRAME, CcftFlags.NONE, ptsUs, payload)

        /**
         * Creates a heartbeat ping packet with zero payload.
         */
        fun heartbeatPing(ptsUs: Long = System.nanoTime() / 1_000): CcftPacket =
            fromParts(PacketType.HEARTBEAT_PING, CcftFlags.NONE, ptsUs, EMPTY_PAYLOAD)

        /**
         * Creates a heartbeat pong packet mirroring ping timestamp.
         */
        fun heartbeatPong(ptsUs: Long): CcftPacket =
            fromParts(PacketType.HEARTBEAT_PONG, CcftFlags.NONE, ptsUs, EMPTY_PAYLOAD)

        /**
         * Encodes a [ControlHello] message into a 0x10 packet.
         */
        fun controlHello(ptsUs: Long, hello: ControlHello): CcftPacket {
            val payload = CcftJson.encodeToString(hello).toByteArray(Charsets.UTF_8)
            return fromParts(PacketType.CONTROL_HELLO, CcftFlags.NONE, ptsUs, payload)
        }

        /**
         * Encodes a [ControlCmd] message into a 0x11 packet.
         */
        fun controlCmd(ptsUs: Long, cmd: ControlCmd): CcftPacket {
            val payload = CcftJson.encodeToString(cmd).toByteArray(Charsets.UTF_8)
            return fromParts(PacketType.CONTROL_CMD, CcftFlags.NONE, ptsUs, payload)
        }

        /**
         * Encodes a [ControlAck] message into a 0x12 packet.
         */
        fun controlAck(ptsUs: Long, ack: ControlAck): CcftPacket {
            val payload = CcftJson.encodeToString(ack).toByteArray(Charsets.UTF_8)
            return fromParts(PacketType.CONTROL_ACK, CcftFlags.NONE, ptsUs, payload)
        }

        /**
         * Writes a CCFT frame directly to an [OutputStream] with zero heap allocations on the hot path.
         */
        fun writePacket(
            output: OutputStream,
            type: PacketType,
            flags: Int,
            ptsUs: Long,
            payload: ByteArray,
            offset: Int = 0,
            length: Int = payload.size - offset,
            flush: Boolean = false,
        ) {
            require(offset >= 0 && length >= 0 && offset + length <= payload.size) {
                "Invalid payload slice: offset=$offset, length=$length, size=${payload.size}"
            }
            if (length.toLong() and 0xFFFFFFFFL > CcftProtocolConstants.MAX_PAYLOAD_BYTES) {
                throw CcftProtocolException.PayloadTooLarge(length.toLong() and 0xFFFFFFFFL)
            }

            val buf = threadLocalHeaderBuffer.get()
                ?: ByteBuffer.allocate(CcftProtocolConstants.HEADER_SIZE_BYTES).order(ByteOrder.BIG_ENDIAN)

            buf.clear()
            buf.put(CcftProtocolConstants.MAGIC_BYTES)
            buf.put(CcftProtocolConstants.VERSION)
            buf.put(type.id)
            buf.putShort((flags and 0xFFFF).toShort())
            buf.putLong(ptsUs)
            buf.putInt(length)

            output.write(buf.array(), 0, CcftProtocolConstants.HEADER_SIZE_BYTES)
            if (length > 0) {
                output.write(payload, offset, length)
            }
            if (flush) {
                output.flush()
            }
        }

        /**
         * Writes an existing [CcftPacket] to an [OutputStream].
         */
        fun writePacket(
            output: OutputStream,
            packet: CcftPacket,
            flush: Boolean = false,
        ) {
            writePacket(
                output = output,
                type = packet.header.packetType,
                flags = packet.header.flags,
                ptsUs = packet.header.ptsUs,
                payload = packet.payload,
                offset = 0,
                length = packet.payload.size,
                flush = flush,
            )
        }

        /**
         * Reads a complete CCFT packet from a [DataInputStream].
         * Validates magic bytes, protocol version, and maximum payload length.
         */
        fun readPacket(input: DataInputStream): CcftPacket {
            val headerBytes = ByteArray(CcftProtocolConstants.HEADER_SIZE_BYTES)
            input.readFully(headerBytes)
            val header = CcftHeader.fromByteArray(headerBytes)

            val payload = if (header.payloadLen > 0) {
                val data = ByteArray(header.payloadLen)
                input.readFully(data)
                data
            } else {
                EMPTY_PAYLOAD
            }

            return CcftPacket(header, payload)
        }

        /**
         * Convenience overload accepting a generic [InputStream].
         */
        fun readPacket(input: InputStream): CcftPacket {
            val dataInput = (input as? DataInputStream) ?: DataInputStream(input)
            return readPacket(dataInput)
        }
    }
}
