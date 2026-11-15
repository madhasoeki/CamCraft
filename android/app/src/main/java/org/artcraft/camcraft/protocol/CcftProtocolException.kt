package org.artcraft.camcraft.protocol

import java.io.IOException

/**
 * Errors raised during CCFT v1 packet decoding or wire header validation.
 * Compatible with `desktop/crates/camcraft-protocol/src/lib.rs::ProtocolError`.
 */
sealed class CcftProtocolException(message: String) : IOException(message) {
    class BufferTooShort(val expected: Int, val actual: Int) :
        CcftProtocolException("Buffer too short: expected at least $expected bytes, got $actual")

    class InvalidMagic(val actual: ByteArray) :
        CcftProtocolException(
            "Invalid CCFT magic header: expected 'CCFT', got [${actual.joinToString(", ") { "0x%02X".format(it) }}]"
        )

    class UnsupportedVersion(val version: Byte) :
        CcftProtocolException(
            "Unsupported protocol version: $version (expected ${CcftProtocolConstants.VERSION})"
        )

    class UnknownPacketType(val packetType: Byte) :
        CcftProtocolException("Unknown packet type: 0x%02X".format(packetType.toInt() and 0xFF))

    class PayloadTooLarge(val payloadLen: Long) :
        CcftProtocolException(
            "Payload length $payloadLen exceeds maximum ${CcftProtocolConstants.MAX_PAYLOAD_BYTES}"
        )
}
