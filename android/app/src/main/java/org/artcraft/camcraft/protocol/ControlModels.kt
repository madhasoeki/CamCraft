package org.artcraft.camcraft.protocol

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * Shared JSON serializer instance configured for CCFT control channel framing.
 * Matches `desktop/crates/camcraft-protocol/src/lib.rs` serde options.
 */
val CcftJson: Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
    prettyPrint = false
}

/**
 * Resolution and frame rate capability option for an Android camera sensor.
 */
@Serializable
data class ResolutionOption(
    val width: Int,
    val height: Int,
    @SerialName("max_fps")
    val maxFps: Int,
)

/**
 * Hardware description and capabilities of an Android camera sensor.
 * `evRange` is stored as a 2-element [Float] list to match JSON array `[-4.0, 4.0]`.
 */
@Serializable
data class CameraInfo(
    val id: String,
    val facing: String,
    val label: String,
    val resolutions: List<ResolutionOption>,
    @SerialName("max_zoom")
    val maxZoom: Float,
    @SerialName("ev_range")
    val evRange: List<Float>,
    @SerialName("has_flash")
    val hasFlash: Boolean,
) {
    val minEv: Float
        get() = evRange.getOrElse(0) { 0f }

    val maxEv: Float
        get() = evRange.getOrElse(1) { 0f }

    companion object {
        fun create(
            id: String,
            facing: String,
            label: String,
            resolutions: List<ResolutionOption>,
            maxZoom: Float,
            minEv: Float,
            maxEv: Float,
            hasFlash: Boolean,
        ): CameraInfo = CameraInfo(
            id = id,
            facing = facing,
            label = label,
            resolutions = resolutions,
            maxZoom = maxZoom,
            evRange = listOf(minEv, maxEv),
            hasFlash = hasFlash,
        )
    }
}

/**
 * Real-time operational state of the streaming camera and microphone.
 */
@Serializable
data class ActiveCameraState(
    @SerialName("camera_id")
    val cameraId: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    @SerialName("bitrate_kbps")
    val bitrateKbps: Int,
    @SerialName("zoom_ratio")
    val zoomRatio: Float,
    @SerialName("exposure_ev")
    val exposureEv: Float,
    val autofocus: Boolean,
    @SerialName("torch_on")
    val torchOn: Boolean,
    @SerialName("mirror_horizontal")
    val mirrorHorizontal: Boolean,
    @SerialName("rotation_deg")
    val rotationDeg: Int,
    @SerialName("audio_enabled")
    val audioEnabled: Boolean,
    @SerialName("audio_muted")
    val audioMuted: Boolean,
)

/**
 * Initial handshake sent from Android to desktop receiver upon connection (`0x10`).
 */
@Serializable
data class ControlHello(
    @SerialName("device_name")
    val deviceName: String,
    @SerialName("protocol_version")
    val protocolVersion: Int,
    val cameras: List<CameraInfo>,
    @SerialName("active_state")
    val activeState: ActiveCameraState,
)

/**
 * Real-time control command sent from desktop to Android (`0x11`).
 * Tagged union using `cmd` discriminator field matching `docs/PROTOCOL.md` §5.2.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("cmd")
sealed interface ControlCmd {

    /**
     * Reconfigures encoder resolution, FPS, or bitrate.
     */
    @Serializable
    @SerialName("ConfigureStream")
    data class ConfigureStream(
        @SerialName("camera_id")
        val cameraId: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        @SerialName("bitrate_kbps")
        val bitrateKbps: Int,
    ) : ControlCmd

    /**
     * Updates live camera parameters without restarting encoder.
     */
    @Serializable
    @SerialName("SetCameraParams")
    data class SetCameraParams(
        @SerialName("zoom_ratio")
        val zoomRatio: Float? = null,
        @SerialName("exposure_ev")
        val exposureEv: Float? = null,
        val autofocus: Boolean? = null,
        @SerialName("torch_on")
        val torchOn: Boolean? = null,
        @SerialName("mirror_horizontal")
        val mirrorHorizontal: Boolean? = null,
        @SerialName("rotation_deg")
        val rotationDeg: Int? = null,
    ) : ControlCmd

    /**
     * Configures audio streaming state.
     */
    @Serializable
    @SerialName("SetAudioState")
    data class SetAudioState(
        val enabled: Boolean,
        val muted: Boolean,
    ) : ControlCmd

    /**
     * Requests an immediate IDR sync keyframe from the encoder.
     */
    @Serializable
    @SerialName("RequestKeyframe")
    data object RequestKeyframe : ControlCmd
}

/**
 * Status acknowledgment sent from Android back to desktop receiver (`0x12`).
 */
@Serializable
data class ControlAck(
    val ok: Boolean,
    val error: String? = null,
    @SerialName("active_state")
    val activeState: ActiveCameraState? = null,
) {
    companion object {
        fun success(activeState: ActiveCameraState? = null): ControlAck =
            ControlAck(ok = true, error = null, activeState = activeState)

        fun failure(message: String): ControlAck =
            ControlAck(ok = false, error = message, activeState = null)
    }
}

/**
 * Audio stream configuration payload (`0x03`).
 */
@Serializable
data class AudioConfigPayload(
    @SerialName("sample_rate")
    val sampleRate: Int,
    val channels: Int,
    val codec: String,
)
