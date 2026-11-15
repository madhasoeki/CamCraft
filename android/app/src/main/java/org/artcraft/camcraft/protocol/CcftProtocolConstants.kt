package org.artcraft.camcraft.protocol

/**
 * CCFT v1 wire protocol constants and bitmask flags matching `docs/PROTOCOL.md`.
 * Single source of truth across Android sender and Rust receiver.
 */
object CcftProtocolConstants {
    const val MAGIC: String = "CCFT"
    val MAGIC_BYTES: ByteArray = byteArrayOf(0x43, 0x43, 0x46, 0x54)

    const val VERSION: Byte = 0x01
    const val HEADER_SIZE_BYTES: Int = 20
    const val DEFAULT_PORT: Int = 9876
    const val MAX_PAYLOAD_BYTES: Int = 16_777_216 // 16 MiB

    // Frame flag bitmasks (u16 on offset 6..8)
    const val FLAG_NONE: Int = 0x0000
    const val FLAG_KEYFRAME: Int = 0x0001
    const val FLAG_CODEC_HEVC: Int = 0x0002
    const val FLAG_ROTATE_90: Int = 0x0010
    const val FLAG_ROTATE_180: Int = 0x0020
    const val FLAG_ROTATE_270: Int = 0x0030
    const val FLAG_MIRROR_H: Int = 0x0040
    const val ROTATION_MASK: Int = 0x0030
}

/**
 * Helper object for inspecting and combining CCFT frame flags.
 */
object CcftFlags {
    const val NONE: Int = CcftProtocolConstants.FLAG_NONE
    const val KEYFRAME: Int = CcftProtocolConstants.FLAG_KEYFRAME
    const val CODEC_HEVC: Int = CcftProtocolConstants.FLAG_CODEC_HEVC
    const val ROTATE_90: Int = CcftProtocolConstants.FLAG_ROTATE_90
    const val ROTATE_180: Int = CcftProtocolConstants.FLAG_ROTATE_180
    const val ROTATE_270: Int = CcftProtocolConstants.FLAG_ROTATE_270
    const val MIRROR_H: Int = CcftProtocolConstants.FLAG_MIRROR_H
    const val ROTATION_MASK: Int = CcftProtocolConstants.ROTATION_MASK

    fun rotationDegrees(flags: Int): Int = when (flags and ROTATION_MASK) {
        ROTATE_90 -> 90
        ROTATE_180 -> 180
        ROTATE_270 -> 270
        else -> 0
    }

    fun flagsForRotation(degrees: Int): Int = when ((degrees % 360 + 360) % 360) {
        90 -> ROTATE_90
        180 -> ROTATE_180
        270 -> ROTATE_270
        else -> 0
    }
}
