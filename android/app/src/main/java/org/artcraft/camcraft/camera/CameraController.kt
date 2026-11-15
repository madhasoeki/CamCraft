package org.artcraft.camcraft.camera

import android.content.Context
import org.artcraft.camcraft.protocol.PacketType

/**
 * Controller providing high-level camera lifecycle and controls,
 * delegating to [CameraStreamManager].
 */
class CameraController(
    context: Context,
    onVideoPacket: (packetType: PacketType, flags: Int, ptsUs: Long, payload: ByteArray) -> Unit,
) {
    val streamManager = CameraStreamManager(context, onVideoPacket)

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

    fun requestKeyFrame() {
        streamManager.requestKeyFrame()
    }

    fun release() {
        streamManager.release()
    }
}
