package org.artcraft.camcraft.protocol

/**
 * CCFT v1 wire protocol constants matching `docs/PROTOCOL.md`.
 * Full binary header serializer/parser and JSON schemas are implemented in Stage 1.
 */
object CcftProtocolConstants {
    const val MAGIC: String = "CCFT"
    const val VERSION: Byte = 0x01
    const val HEADER_SIZE_BYTES: Int = 20
    const val DEFAULT_PORT: Int = 9876
    const val MAX_PAYLOAD_BYTES: Int = 16_777_216
}
