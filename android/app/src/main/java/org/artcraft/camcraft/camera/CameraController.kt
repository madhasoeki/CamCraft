package org.artcraft.camcraft.camera

import android.content.Context
import androidx.camera.core.MeteringPoint
import org.artcraft.camcraft.protocol.PacketType

/**
 * Controller providing high-level camera lifecycle and controls,
 * delegating to [CameraStreamManager].
 */
class CameraController(
    context: Context,
    onVideoPacketSlice: (
        packetType: PacketType,
        flags: Int,
        ptsUs: Long,
        payload: ByteArray,
        offset: Int,
        length: Int,
    ) -> Unit,
) {
    constructor(
        context: Context,
        onVideoPacket: (packetType: PacketType, flags: Int, ptsUs: Long, payload: ByteArray) -> Unit,
    ) : this(
        context = context,
        onVideoPacketSlice = { packetType, flags, ptsUs, payload, offset, length ->
            val standalone = if (offset == 0 && length == payload.size) {
                payload
            } else {
                payload.copyOfRange(offset, offset + length)
            }
            onVideoPacket(packetType, flags, ptsUs, standalone)
        },
    )

    val streamManager = CameraStreamManager(context, onVideoPacketSlice)

    fun startStream(cameraId: String, width: Int, height: Int, fps: Int, bitrateKbps: Int) {
        streamManager.startStream(cameraId, width, height, fps, bitrateKbps)
    }

    fun stopStream() {
        streamManager.stopStream()
    }

    fun setZoomRatio(ratio: Float) {
        streamManager.setZoomRatio(ratio)
    }

    fun setExposureEv(ev: Float) {
        streamManager.setExposureEv(ev)
    }

    fun setTorch(torchOn: Boolean) {
        streamManager.setTorch(torchOn)
    }

    fun setAutoFocus(autofocus: Boolean) {
        streamManager.setAutoFocus(autofocus)
    }

    fun focusAtPoint(point: MeteringPoint) {
        streamManager.focusAtPoint(point)
    }

    fun requestKeyFrame() {
        streamManager.requestKeyFrame()
    }

    fun release() {
        streamManager.release()
    }
}
