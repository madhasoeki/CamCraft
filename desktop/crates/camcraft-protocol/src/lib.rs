//! CCFT v1 wire protocol framing and JSON control schemas for CamCraft.
//!
//! Defined in `docs/PROTOCOL.md` as the single source of truth for communication
//! between Android sender (`org.artcraft.camcraft.protocol`) and desktop receiver.

use bytes::{BufMut, Bytes, BytesMut};
use serde::{Deserialize, Serialize};
use thiserror::Error;

/// Fixed 4-byte ASCII magic header (`"CCFT"`).
pub const MAGIC: [u8; 4] = *b"CCFT";

/// Alias for [`MAGIC`] to maintain backward compatibility across crates.
pub const PROTOCOL_MAGIC: [u8; 4] = MAGIC;

/// Current CCFT wire protocol version (Stage 1 = v1).
pub const PROTOCOL_VERSION: u8 = 1;

/// Fixed CCFT frame header size in bytes (20 bytes, 4-byte aligned).
pub const HEADER_LEN: usize = 20;

/// Alias for [`HEADER_LEN`] to maintain backward compatibility across crates.
pub const HEADER_SIZE: usize = HEADER_LEN;

/// Default multiplexed TCP port for USB ADB and Wi-Fi LAN connections.
pub const DEFAULT_PORT: u16 = 9876;

/// Maximum permitted payload length per frame (16 MiB: 16_777_216 bytes).
pub const MAX_PAYLOAD_LEN: u32 = 16 * 1024 * 1024;

/// Bitmask flag: no flags set.
pub const FLAG_NONE: u16 = 0x0000;

/// Bitmask flag: video frame is an IDR keyframe.
pub const FLAG_KEYFRAME: u16 = 0x0001;

/// Bitmask flag: video payload is H.265/HEVC (default 0 is H.264/AVC).
pub const FLAG_CODEC_HEVC: u16 = 0x0002;

/// Bitmask flag: 90 degrees clockwise rotation metadata.
pub const FLAG_ROTATE_90: u16 = 0x0010;

/// Bitmask flag: 180 degrees rotation metadata.
pub const FLAG_ROTATE_180: u16 = 0x0020;

/// Bitmask flag: 270 degrees rotation metadata.
pub const FLAG_ROTATE_270: u16 = 0x0030;

/// Bitmask flag: horizontal flip / mirror metadata.
pub const FLAG_MIRROR_H: u16 = 0x0040;

/// Bitmask covering all rotation bits (bits 4 and 5).
pub const ROTATION_MASK: u16 = 0x0030;

/// Errors raised during CCFT v1 packet encoding, decoding, or JSON parsing.
#[derive(Debug, Error)]
pub enum ProtocolError {
    #[error("buffer too short: expected at least {expected} bytes, got {actual}")]
    BufferTooShort { expected: usize, actual: usize },

    #[error("invalid CCFT magic header: expected {MAGIC:?}, got {0:?}")]
    InvalidMagic([u8; 4]),

    #[error("unsupported protocol version: {0} (expected {PROTOCOL_VERSION})")]
    UnsupportedVersion(u8),

    #[error("unknown packet type: 0x{0:02x}")]
    UnknownPacketType(u8),

    #[error("payload length {0} exceeds maximum {MAX_PAYLOAD_LEN}")]
    PayloadTooLarge(u32),

    #[error("JSON control payload error: {0}")]
    Json(#[from] serde_json::Error),
}

/// Packet type discriminant matching `docs/PROTOCOL.md` §3.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[repr(u8)]
pub enum PacketType {
    VideoConfig = 0x01,
    VideoFrame = 0x02,
    AudioConfig = 0x03,
    AudioFrame = 0x04,
    ControlHello = 0x10,
    ControlCmd = 0x11,
    ControlAck = 0x12,
    HeartbeatPing = 0xFE,
    HeartbeatPong = 0xFF,
}

impl PacketType {
    /// Returns the raw `u8` wire value.
    #[inline]
    #[must_use]
    pub const fn as_u8(self) -> u8 {
        self as u8
    }
}

impl From<PacketType> for u8 {
    #[inline]
    fn from(packet_type: PacketType) -> Self {
        packet_type.as_u8()
    }
}

impl TryFrom<u8> for PacketType {
    type Error = ProtocolError;

    fn try_from(value: u8) -> Result<Self, Self::Error> {
        match value {
            0x01 => Ok(Self::VideoConfig),
            0x02 => Ok(Self::VideoFrame),
            0x03 => Ok(Self::AudioConfig),
            0x04 => Ok(Self::AudioFrame),
            0x10 => Ok(Self::ControlHello),
            0x11 => Ok(Self::ControlCmd),
            0x12 => Ok(Self::ControlAck),
            0xFE => Ok(Self::HeartbeatPing),
            0xFF => Ok(Self::HeartbeatPong),
            other => Err(ProtocolError::UnknownPacketType(other)),
        }
    }
}

/// Bitmask helper representing CCFT frame metadata flags (`u16`).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default, Hash, Serialize, Deserialize)]
pub struct FrameFlags(pub u16);

impl FrameFlags {
    pub const NONE: Self = Self(FLAG_NONE);
    pub const KEYFRAME: Self = Self(FLAG_KEYFRAME);
    pub const CODEC_HEVC: Self = Self(FLAG_CODEC_HEVC);
    pub const ROTATE_90: Self = Self(FLAG_ROTATE_90);
    pub const ROTATE_180: Self = Self(FLAG_ROTATE_180);
    pub const ROTATE_270: Self = Self(FLAG_ROTATE_270);
    pub const MIRROR_H: Self = Self(FLAG_MIRROR_H);

    /// Constructs empty flags.
    #[inline]
    #[must_use]
    pub const fn empty() -> Self {
        Self(FLAG_NONE)
    }

    /// Constructs flags from raw `u16` bits.
    #[inline]
    #[must_use]
    pub const fn from_bits(bits: u16) -> Self {
        Self(bits)
    }

    /// Returns the underlying `u16` bitmask.
    #[inline]
    #[must_use]
    pub const fn bits(self) -> u16 {
        self.0
    }

    /// Checks if specific flag bits are set.
    #[inline]
    #[must_use]
    pub const fn contains(self, flag: u16) -> bool {
        (self.0 & flag) == flag
    }

    /// Inserts flag bits in-place.
    #[inline]
    pub fn insert(&mut self, flag: u16) {
        self.0 |= flag;
    }

    /// Clears flag bits in-place.
    #[inline]
    pub fn remove(&mut self, flag: u16) {
        self.0 &= !flag;
    }

    /// Returns true if this frame is marked as an IDR keyframe.
    #[inline]
    #[must_use]
    pub const fn is_keyframe(self) -> bool {
        (self.0 & FLAG_KEYFRAME) != 0
    }

    /// Returns true if this frame is encoded with H.265/HEVC.
    #[inline]
    #[must_use]
    pub const fn is_hevc(self) -> bool {
        (self.0 & FLAG_CODEC_HEVC) != 0
    }

    /// Returns true if horizontal mirror/flip is requested.
    #[inline]
    #[must_use]
    pub const fn is_mirrored_h(self) -> bool {
        (self.0 & FLAG_MIRROR_H) != 0
    }

    /// Returns rotation degrees (0, 90, 180, or 270).
    #[inline]
    #[must_use]
    pub const fn rotation_degrees(self) -> u16 {
        match self.0 & ROTATION_MASK {
            FLAG_ROTATE_90 => 90,
            FLAG_ROTATE_180 => 180,
            FLAG_ROTATE_270 => 270,
            _ => 0,
        }
    }

    /// Returns a new instance with the keyframe flag set or cleared.
    #[inline]
    #[must_use]
    pub const fn with_keyframe(self, keyframe: bool) -> Self {
        if keyframe {
            Self(self.0 | FLAG_KEYFRAME)
        } else {
            Self(self.0 & !FLAG_KEYFRAME)
        }
    }

    /// Returns a new instance with the HEVC codec flag set or cleared.
    #[inline]
    #[must_use]
    pub const fn with_hevc(self, hevc: bool) -> Self {
        if hevc {
            Self(self.0 | FLAG_CODEC_HEVC)
        } else {
            Self(self.0 & !FLAG_CODEC_HEVC)
        }
    }

    /// Returns a new instance with the horizontal mirror flag set or cleared.
    #[inline]
    #[must_use]
    pub const fn with_mirror_h(self, mirror: bool) -> Self {
        if mirror {
            Self(self.0 | FLAG_MIRROR_H)
        } else {
            Self(self.0 & !FLAG_MIRROR_H)
        }
    }

    /// Returns a new instance with rotation degrees (0, 90, 180, 270).
    #[inline]
    #[must_use]
    pub const fn with_rotation(self, degrees: u16) -> Self {
        let cleared = self.0 & !ROTATION_MASK;
        let rot_bits = match degrees {
            90 => FLAG_ROTATE_90,
            180 => FLAG_ROTATE_180,
            270 => FLAG_ROTATE_270,
            _ => 0,
        };
        Self(cleared | rot_bits)
    }
}

impl From<u16> for FrameFlags {
    #[inline]
    fn from(bits: u16) -> Self {
        Self(bits)
    }
}

impl From<FrameFlags> for u16 {
    #[inline]
    fn from(flags: FrameFlags) -> Self {
        flags.bits()
    }
}

impl std::ops::BitOr for FrameFlags {
    type Output = Self;

    #[inline]
    fn bitor(self, rhs: Self) -> Self {
        Self(self.0 | rhs.0)
    }
}

impl std::ops::BitOr<u16> for FrameFlags {
    type Output = Self;

    #[inline]
    fn bitor(self, rhs: u16) -> Self {
        Self(self.0 | rhs)
    }
}

impl std::ops::BitAnd for FrameFlags {
    type Output = Self;

    #[inline]
    fn bitand(self, rhs: Self) -> Self {
        Self(self.0 & rhs.0)
    }
}

impl std::ops::BitAnd<u16> for FrameFlags {
    type Output = Self;

    #[inline]
    fn bitand(self, rhs: u16) -> Self {
        Self(self.0 & rhs)
    }
}

impl std::ops::BitOrAssign for FrameFlags {
    #[inline]
    fn bitor_assign(&mut self, rhs: Self) {
        self.0 |= rhs.0;
    }
}

impl std::ops::BitOrAssign<u16> for FrameFlags {
    #[inline]
    fn bitor_assign(&mut self, rhs: u16) {
        self.0 |= rhs;
    }
}

/// Fixed 20-byte CCFT binary frame header.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct PacketHeader {
    pub magic: [u8; 4],
    pub version: u8,
    pub packet_type: PacketType,
    pub flags: FrameFlags,
    pub pts_us: u64,
    pub payload_len: u32,
}

impl PacketHeader {
    /// Constructs a new packet header with current magic and version.
    #[inline]
    #[must_use]
    pub const fn new(
        packet_type: PacketType,
        flags: FrameFlags,
        pts_us: u64,
        payload_len: u32,
    ) -> Self {
        Self {
            magic: MAGIC,
            version: PROTOCOL_VERSION,
            packet_type,
            flags,
            pts_us,
            payload_len,
        }
    }

    /// Serializes this header into a fixed 20-byte array (big-endian).
    #[inline]
    #[must_use]
    pub fn to_bytes(&self) -> [u8; HEADER_LEN] {
        let mut bytes = [0u8; HEADER_LEN];
        bytes[0..4].copy_from_slice(&self.magic);
        bytes[4] = self.version;
        bytes[5] = self.packet_type.as_u8();
        bytes[6..8].copy_from_slice(&self.flags.bits().to_be_bytes());
        bytes[8..16].copy_from_slice(&self.pts_us.to_be_bytes());
        bytes[16..20].copy_from_slice(&self.payload_len.to_be_bytes());
        bytes
    }

    /// Encodes this header into the destination [`BytesMut`] buffer.
    #[inline]
    pub fn encode(&self, dst: &mut BytesMut) {
        dst.reserve(HEADER_LEN);
        dst.extend_from_slice(&self.to_bytes());
    }

    /// Decodes a header from a 20-byte fixed array.
    pub fn from_bytes(bytes: &[u8; HEADER_LEN]) -> Result<Self, ProtocolError> {
        let magic = [bytes[0], bytes[1], bytes[2], bytes[3]];
        if magic != MAGIC {
            return Err(ProtocolError::InvalidMagic(magic));
        }

        let version = bytes[4];
        if version != PROTOCOL_VERSION {
            return Err(ProtocolError::UnsupportedVersion(version));
        }

        let packet_type = PacketType::try_from(bytes[5])?;
        let flags = FrameFlags::from_bits(u16::from_be_bytes([bytes[6], bytes[7]]));
        let pts_us = u64::from_be_bytes([
            bytes[8], bytes[9], bytes[10], bytes[11], bytes[12], bytes[13], bytes[14], bytes[15],
        ]);
        let payload_len = u32::from_be_bytes([bytes[16], bytes[17], bytes[18], bytes[19]]);

        if payload_len > MAX_PAYLOAD_LEN {
            return Err(ProtocolError::PayloadTooLarge(payload_len));
        }

        Ok(Self {
            magic,
            version,
            packet_type,
            flags,
            pts_us,
            payload_len,
        })
    }

    /// Decodes a header from a slice containing at least 20 bytes.
    pub fn from_slice(src: &[u8]) -> Result<Self, ProtocolError> {
        let Some(fixed) = src.first_chunk::<HEADER_LEN>() else {
            return Err(ProtocolError::BufferTooShort {
                expected: HEADER_LEN,
                actual: src.len(),
            });
        };
        Self::from_bytes(fixed)
    }
}

/// A complete CCFT v1 packet comprising a 20-byte header and binary payload.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Packet {
    pub header: PacketHeader,
    pub payload: Bytes,
}

impl Packet {
    /// Creates a packet, aligning header `payload_len` with `payload.len()`.
    #[must_use]
    pub fn new(mut header: PacketHeader, payload: Bytes) -> Self {
        header.payload_len = payload.len() as u32;
        Self { header, payload }
    }

    /// Creates a packet from individual components.
    #[must_use]
    pub fn from_parts(
        packet_type: PacketType,
        flags: impl Into<FrameFlags>,
        pts_us: u64,
        payload: Bytes,
    ) -> Self {
        let payload_len = payload.len() as u32;
        let header = PacketHeader::new(packet_type, flags.into(), pts_us, payload_len);
        Self { header, payload }
    }

    /// Creates a video frame packet.
    #[must_use]
    pub fn video_frame(flags: impl Into<FrameFlags>, pts_us: u64, payload: Bytes) -> Self {
        Self::from_parts(PacketType::VideoFrame, flags, pts_us, payload)
    }

    /// Creates a video configuration packet (SPS/PPS parameter sets).
    #[must_use]
    pub fn video_config(pts_us: u64, payload: Bytes) -> Self {
        Self::from_parts(
            PacketType::VideoConfig,
            FrameFlags::empty(),
            pts_us,
            payload,
        )
    }

    /// Encodes an [`AudioConfigPayload`] message into an `AUDIO_CONFIG` (`0x03`) packet.
    pub fn audio_config(pts_us: u64, config: &AudioConfigPayload) -> Result<Self, ProtocolError> {
        let bytes = serde_json::to_vec(config)?;
        Ok(Self::from_parts(
            PacketType::AudioConfig,
            FrameFlags::empty(),
            pts_us,
            Bytes::from(bytes),
        ))
    }

    /// Creates an `AUDIO_FRAME` (`0x04`) packet from raw audio bytes (PCM S16LE or Opus).
    #[must_use]
    pub fn audio_frame(pts_us: u64, payload: Bytes) -> Self {
        Self::from_parts(PacketType::AudioFrame, FrameFlags::empty(), pts_us, payload)
    }

    /// Creates a heartbeat ping packet (zero-length payload).
    #[must_use]
    pub fn heartbeat_ping(pts_us: u64) -> Self {
        Self::from_parts(
            PacketType::HeartbeatPing,
            FrameFlags::empty(),
            pts_us,
            Bytes::new(),
        )
    }

    /// Creates a heartbeat pong packet mirroring ping timestamp.
    #[must_use]
    pub fn heartbeat_pong(pts_us: u64) -> Self {
        Self::from_parts(
            PacketType::HeartbeatPong,
            FrameFlags::empty(),
            pts_us,
            Bytes::new(),
        )
    }

    /// Encodes a [`ControlHello`] message into a `0x10` packet.
    pub fn control_hello(pts_us: u64, hello: &ControlHello) -> Result<Self, ProtocolError> {
        let bytes = serde_json::to_vec(hello)?;
        Ok(Self::from_parts(
            PacketType::ControlHello,
            FrameFlags::empty(),
            pts_us,
            Bytes::from(bytes),
        ))
    }

    /// Encodes a [`ControlCmd`] message into a `0x11` packet.
    pub fn control_cmd(pts_us: u64, cmd: &ControlCmd) -> Result<Self, ProtocolError> {
        let bytes = serde_json::to_vec(cmd)?;
        Ok(Self::from_parts(
            PacketType::ControlCmd,
            FrameFlags::empty(),
            pts_us,
            Bytes::from(bytes),
        ))
    }

    /// Encodes a [`ControlAck`] message into a `0x12` packet.
    pub fn control_ack(pts_us: u64, ack: &ControlAck) -> Result<Self, ProtocolError> {
        let bytes = serde_json::to_vec(ack)?;
        Ok(Self::from_parts(
            PacketType::ControlAck,
            FrameFlags::empty(),
            pts_us,
            Bytes::from(bytes),
        ))
    }

    /// Parses JSON payload from this packet.
    pub fn decode_json<T: for<'de> Deserialize<'de>>(&self) -> Result<T, ProtocolError> {
        let parsed = serde_json::from_slice(&self.payload)?;
        Ok(parsed)
    }

    /// Encodes this packet (header + payload) into `dst`.
    pub fn encode(&self, dst: &mut BytesMut) {
        dst.reserve(HEADER_LEN + self.payload.len());
        let mut header = self.header;
        header.payload_len = self.payload.len() as u32;
        header.encode(dst);
        dst.put_slice(&self.payload);
    }

    /// Decodes a packet from a streaming buffer.
    ///
    /// Returns `Ok(None)` without consuming bytes if either header or payload
    /// is incomplete, preserving buffer state across partial TCP reads.
    pub fn decode(src: &mut BytesMut) -> Result<Option<Self>, ProtocolError> {
        let Some(header_bytes) = src.first_chunk::<HEADER_LEN>() else {
            return Ok(None);
        };

        let header = PacketHeader::from_bytes(header_bytes)?;

        let total_len = HEADER_LEN + header.payload_len as usize;
        if src.len() < total_len {
            return Ok(None);
        }

        let _ = src.split_to(HEADER_LEN);
        let payload = src.split_to(header.payload_len as usize).freeze();

        Ok(Some(Self { header, payload }))
    }
}

/// Convenience top-level encoder function.
#[inline]
pub fn encode(packet: &Packet, dst: &mut BytesMut) {
    packet.encode(dst);
}

/// Convenience top-level streaming decoder function.
#[inline]
pub fn decode(src: &mut BytesMut) -> Result<Option<Packet>, ProtocolError> {
    Packet::decode(src)
}

/// Touch `bytes::Bytes` to verify workspace dependency wiring.
#[must_use]
pub fn empty_payload() -> Bytes {
    Bytes::new()
}

// ============================================================================
// Control Channel JSON Data Models (`docs/PROTOCOL.md` §5)
// ============================================================================

/// Resolution and frame rate capability option for a camera sensor.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub struct ResolutionOption {
    pub width: u32,
    pub height: u32,
    pub max_fps: u32,
}

/// Hardware description and capabilities of an Android camera sensor.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct CameraInfo {
    pub id: String,
    pub facing: String,
    pub label: String,
    pub resolutions: Vec<ResolutionOption>,
    pub max_zoom: f32,
    pub ev_range: (f32, f32),
    pub has_flash: bool,
}

/// Real-time operational state of the streaming camera and microphone.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ActiveCameraState {
    pub camera_id: String,
    pub width: u32,
    pub height: u32,
    pub fps: u32,
    pub bitrate_kbps: u32,
    pub zoom_ratio: f32,
    pub exposure_ev: f32,
    pub autofocus: bool,
    pub torch_on: bool,
    pub mirror_horizontal: bool,
    pub rotation_deg: i32,
    pub audio_enabled: bool,
    pub audio_muted: bool,
}

/// Initial handshake sent from Android to desktop upon connection (`0x10`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ControlHello {
    pub device_name: String,
    pub protocol_version: u8,
    pub cameras: Vec<CameraInfo>,
    pub active_state: ActiveCameraState,
}

/// Real-time control command sent from desktop to Android (`0x11`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(tag = "cmd")]
pub enum ControlCmd {
    /// Reconfigures encoder resolution, FPS, or bitrate.
    ConfigureStream {
        camera_id: String,
        width: u32,
        height: u32,
        fps: u32,
        bitrate_kbps: u32,
    },
    /// Updates live camera parameters without restarting encoder.
    SetCameraParams {
        #[serde(default, skip_serializing_if = "Option::is_none")]
        zoom_ratio: Option<f32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        exposure_ev: Option<f32>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        autofocus: Option<bool>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        torch_on: Option<bool>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        mirror_horizontal: Option<bool>,
        #[serde(default, skip_serializing_if = "Option::is_none")]
        rotation_deg: Option<i32>,
    },
    /// Configures audio streaming state.
    SetAudioState { enabled: bool, muted: bool },
    /// Requests an immediate IDR sync keyframe from the encoder.
    RequestKeyframe,
}

/// Type alias for [`ControlCmd`].
pub type ControlCommand = ControlCmd;

impl ControlCmd {
    /// Creates a [`ControlCmd::ConfigureStream`] command.
    #[must_use]
    pub fn configure_stream(
        camera_id: impl Into<String>,
        width: u32,
        height: u32,
        fps: u32,
        bitrate_kbps: u32,
    ) -> Self {
        Self::ConfigureStream {
            camera_id: camera_id.into(),
            width,
            height,
            fps,
            bitrate_kbps,
        }
    }

    /// Creates a [`ControlCmd::SetAudioState`] command.
    #[must_use]
    pub fn set_audio_state(enabled: bool, muted: bool) -> Self {
        Self::SetAudioState { enabled, muted }
    }

    /// Creates a [`ControlCmd::RequestKeyframe`] command.
    #[must_use]
    pub const fn request_keyframe() -> Self {
        Self::RequestKeyframe
    }
}

/// Status acknowledgment sent from Android back to desktop (`0x12`).
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct ControlAck {
    pub ok: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub active_state: Option<ActiveCameraState>,
}

impl ControlAck {
    /// Creates a successful acknowledgment with the latest camera state.
    #[must_use]
    pub fn success(active_state: Option<ActiveCameraState>) -> Self {
        Self {
            ok: true,
            error: None,
            active_state,
        }
    }

    /// Creates a failure acknowledgment with an error message.
    #[must_use]
    pub fn failure(message: impl Into<String>) -> Self {
        Self {
            ok: false,
            error: Some(message.into()),
            active_state: None,
        }
    }
}

/// Audio stream configuration payload (`0x03`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AudioConfigPayload {
    pub sample_rate: u32,
    pub channels: u16,
    pub codec: String,
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Canonical 20-byte header + 4-byte payload test vector.
    ///
    /// Header layout (20 bytes big-endian):
    /// - Bytes 0..4:   `"CCFT"` (0x43, 0x43, 0x46, 0x54)
    /// - Byte 4:       Version 1 (0x01)
    /// - Byte 5:       PacketType::VideoFrame (0x02)
    /// - Bytes 6..8:   Flags 0x0011 (KEYFRAME 0x0001 | ROTATE_90 0x0010)
    /// - Bytes 8..16:  pts_us = 1_000_000 (0x0000_0000_000f_4240)
    /// - Bytes 16..20: payload_len = 4 (0x0000_0004)
    /// - Bytes 20..24: Payload `"TEST"` (0x54, 0x45, 0x53, 0x54)
    const CANONICAL_TEST_FRAME: [u8; 24] = [
        0x43, 0x43, 0x46, 0x54, // Magic "CCFT"
        0x01, // Version 1
        0x02, // VideoFrame
        0x00, 0x11, // Flags: KEYFRAME | ROTATE_90
        0x00, 0x00, 0x00, 0x00, 0x00, 0x0f, 0x42, 0x40, // pts_us: 1000000
        0x00, 0x00, 0x00, 0x04, // payload_len: 4
        0x54, 0x45, 0x53, 0x54, // Payload "TEST"
    ];

    #[test]
    fn header_size_and_constants_match_spec() {
        assert_eq!(&MAGIC, b"CCFT");
        assert_eq!(&PROTOCOL_MAGIC, b"CCFT");
        assert_eq!(PROTOCOL_VERSION, 1);
        assert_eq!(HEADER_LEN, 20);
        assert_eq!(HEADER_SIZE, 20);
        assert_eq!(DEFAULT_PORT, 9876);
        assert_eq!(MAX_PAYLOAD_LEN, 16_777_216);
    }

    #[test]
    fn packet_type_roundtrip_and_discriminants() {
        let cases = [
            (PacketType::VideoConfig, 0x01),
            (PacketType::VideoFrame, 0x02),
            (PacketType::AudioConfig, 0x03),
            (PacketType::AudioFrame, 0x04),
            (PacketType::ControlHello, 0x10),
            (PacketType::ControlCmd, 0x11),
            (PacketType::ControlAck, 0x12),
            (PacketType::HeartbeatPing, 0xFE),
            (PacketType::HeartbeatPong, 0xFF),
        ];

        for (pt, raw) in cases {
            assert_eq!(pt.as_u8(), raw);
            assert_eq!(u8::from(pt), raw);
            assert_eq!(PacketType::try_from(raw).unwrap(), pt);
        }

        assert!(matches!(
            PacketType::try_from(0x00),
            Err(ProtocolError::UnknownPacketType(0x00))
        ));
        assert!(matches!(
            PacketType::try_from(0x55),
            Err(ProtocolError::UnknownPacketType(0x55))
        ));
    }

    #[test]
    fn frame_flags_operations_and_rotations() {
        let flags = FrameFlags::empty()
            .with_keyframe(true)
            .with_hevc(true)
            .with_mirror_h(true)
            .with_rotation(90);

        assert!(flags.is_keyframe());
        assert!(flags.is_hevc());
        assert!(flags.is_mirrored_h());
        assert_eq!(flags.rotation_degrees(), 90);
        assert!(flags.contains(FLAG_KEYFRAME));
        assert!(flags.contains(FLAG_CODEC_HEVC));
        assert!(flags.contains(FLAG_ROTATE_90));
        assert!(flags.contains(FLAG_MIRROR_H));

        let rotated_180 = flags.with_rotation(180);
        assert_eq!(rotated_180.rotation_degrees(), 180);
        assert!(!rotated_180.contains(FLAG_ROTATE_90));
        assert!(rotated_180.contains(FLAG_ROTATE_180));

        let rotated_270 = flags.with_rotation(270);
        assert_eq!(rotated_270.rotation_degrees(), 270);
        assert!(rotated_270.contains(FLAG_ROTATE_270));

        let rotated_0 = flags.with_rotation(0);
        assert_eq!(rotated_0.rotation_degrees(), 0);

        let mut mutable = FrameFlags::empty();
        mutable.insert(FLAG_KEYFRAME);
        assert!(mutable.is_keyframe());
        mutable.remove(FLAG_KEYFRAME);
        assert!(!mutable.is_keyframe());

        let combined = FrameFlags::KEYFRAME | FLAG_ROTATE_90;
        assert_eq!(combined.bits(), 0x0011);
    }

    #[test]
    fn canonical_hex_vector_decode_and_encode() {
        let mut buffer = BytesMut::from(&CANONICAL_TEST_FRAME[..]);
        let packet = decode(&mut buffer)
            .expect("decoding canonical vector must succeed")
            .expect("complete packet must be produced");

        assert_eq!(packet.header.magic, MAGIC);
        assert_eq!(packet.header.version, 1);
        assert_eq!(packet.header.packet_type, PacketType::VideoFrame);
        assert!(packet.header.flags.is_keyframe());
        assert_eq!(packet.header.flags.rotation_degrees(), 90);
        assert_eq!(packet.header.pts_us, 1_000_000);
        assert_eq!(packet.header.payload_len, 4);
        assert_eq!(packet.payload.as_ref(), b"TEST");
        assert!(buffer.is_empty(), "all bytes must be consumed");

        let mut re_encoded = BytesMut::new();
        encode(&packet, &mut re_encoded);
        assert_eq!(&re_encoded[..], &CANONICAL_TEST_FRAME[..]);
    }

    #[test]
    fn partial_tcp_stream_chunking_byte_by_byte() {
        let mut buffer = BytesMut::new();

        for (index, &byte) in CANONICAL_TEST_FRAME.iter().enumerate() {
            buffer.put_u8(byte);
            let result = decode(&mut buffer).expect("chunk decoding should not error");

            if index < CANONICAL_TEST_FRAME.len() - 1 {
                assert!(
                    result.is_none(),
                    "expected Ok(None) at incomplete byte offset {index}"
                );
            } else {
                let packet = result.expect("final byte must complete packet");
                assert_eq!(packet.header.packet_type, PacketType::VideoFrame);
                assert_eq!(packet.payload.as_ref(), b"TEST");
            }
        }

        assert!(buffer.is_empty());
    }

    #[test]
    fn multiple_packets_in_single_buffer() {
        let mut buffer = BytesMut::new();

        let p1 = Packet::heartbeat_ping(100);
        let p2 = Packet::video_config(
            200,
            Bytes::from_static(&[0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00]),
        );
        let p3 = Packet::video_frame(FrameFlags::KEYFRAME, 300, Bytes::from_static(b"FRAME_1"));
        let p4 = Packet::heartbeat_pong(100);

        encode(&p1, &mut buffer);
        encode(&p2, &mut buffer);
        encode(&p3, &mut buffer);
        encode(&p4, &mut buffer);

        let d1 = decode(&mut buffer).unwrap().expect("p1 decoded");
        assert_eq!(d1.header.packet_type, PacketType::HeartbeatPing);
        assert_eq!(d1.header.pts_us, 100);
        assert!(d1.payload.is_empty());

        let d2 = decode(&mut buffer).unwrap().expect("p2 decoded");
        assert_eq!(d2.header.packet_type, PacketType::VideoConfig);
        assert_eq!(d2.header.pts_us, 200);
        assert_eq!(
            d2.payload.as_ref(),
            &[0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0x00]
        );

        let d3 = decode(&mut buffer).unwrap().expect("p3 decoded");
        assert_eq!(d3.header.packet_type, PacketType::VideoFrame);
        assert!(d3.header.flags.is_keyframe());
        assert_eq!(d3.payload.as_ref(), b"FRAME_1");

        let d4 = decode(&mut buffer).unwrap().expect("p4 decoded");
        assert_eq!(d4.header.packet_type, PacketType::HeartbeatPong);
        assert_eq!(d4.header.pts_us, 100);
        assert!(d4.payload.is_empty());

        let d5 = decode(&mut buffer).unwrap();
        assert!(d5.is_none());
        assert!(buffer.is_empty());
    }

    #[test]
    fn protocol_header_error_cases() {
        let mut bad_magic = BytesMut::from(&CANONICAL_TEST_FRAME[..]);
        bad_magic[0] = b'X';
        assert!(matches!(
            decode(&mut bad_magic),
            Err(ProtocolError::InvalidMagic([b'X', b'C', b'F', b'T']))
        ));

        let mut bad_ver = BytesMut::from(&CANONICAL_TEST_FRAME[..]);
        bad_ver[4] = 99;
        assert!(matches!(
            decode(&mut bad_ver),
            Err(ProtocolError::UnsupportedVersion(99))
        ));

        let mut bad_type = BytesMut::from(&CANONICAL_TEST_FRAME[..]);
        bad_type[5] = 0x77;
        assert!(matches!(
            decode(&mut bad_type),
            Err(ProtocolError::UnknownPacketType(0x77))
        ));

        let mut oversized = BytesMut::from(&CANONICAL_TEST_FRAME[..]);
        let too_large = (MAX_PAYLOAD_LEN + 1).to_be_bytes();
        oversized[16..20].copy_from_slice(&too_large);
        assert!(matches!(
            decode(&mut oversized),
            Err(ProtocolError::PayloadTooLarge(_))
        ));
    }

    #[test]
    fn control_hello_json_schema_matches_spec() {
        let json_spec = r#"{
          "device_name": "Pixel 8 Pro",
          "protocol_version": 1,
          "cameras": [
            {
              "id": "0",
              "facing": "back",
              "label": "Rear Main Wide",
              "resolutions": [
                { "width": 1280, "height": 720, "max_fps": 60 },
                { "width": 1920, "height": 1080, "max_fps": 60 },
                { "width": 3840, "height": 2160, "max_fps": 60 }
              ],
              "max_zoom": 8.0,
              "ev_range": [-4.0, 4.0],
              "has_flash": true
            },
            {
              "id": "1",
              "facing": "front",
              "label": "Front Selfie",
              "resolutions": [
                { "width": 1280, "height": 720, "max_fps": 60 },
                { "width": 1920, "height": 1080, "max_fps": 60 }
              ],
              "max_zoom": 2.0,
              "ev_range": [-2.0, 2.0],
              "has_flash": false
            }
          ],
          "active_state": {
            "camera_id": "0",
            "width": 1920,
            "height": 1080,
            "fps": 60,
            "bitrate_kbps": 12000,
            "zoom_ratio": 1.0,
            "exposure_ev": 0.0,
            "autofocus": true,
            "torch_on": false,
            "mirror_horizontal": false,
            "rotation_deg": 0,
            "audio_enabled": true,
            "audio_muted": false
          }
        }"#;

        let hello: ControlHello =
            serde_json::from_str(json_spec).expect("spec JSON must deserialize into ControlHello");
        assert_eq!(hello.device_name, "Pixel 8 Pro");
        assert_eq!(hello.protocol_version, 1);
        assert_eq!(hello.cameras.len(), 2);
        assert_eq!(hello.cameras[0].id, "0");
        assert_eq!(hello.cameras[0].facing, "back");
        assert_eq!(hello.cameras[0].resolutions.len(), 3);
        assert!((hello.cameras[0].max_zoom - 8.0).abs() < f32::EPSILON);
        assert_eq!(hello.cameras[0].ev_range, (-4.0, 4.0));
        assert!(hello.cameras[0].has_flash);

        assert_eq!(hello.active_state.camera_id, "0");
        assert_eq!(hello.active_state.width, 1920);
        assert_eq!(hello.active_state.height, 1080);
        assert_eq!(hello.active_state.fps, 60);
        assert_eq!(hello.active_state.bitrate_kbps, 12000);
        assert!(hello.active_state.autofocus);
        assert!(!hello.active_state.torch_on);
        assert!(hello.active_state.audio_enabled);

        let packet =
            Packet::control_hello(42, &hello).expect("packet creation from hello must succeed");
        assert_eq!(packet.header.packet_type, PacketType::ControlHello);
        let roundtrip: ControlHello = packet.decode_json().expect("roundtrip JSON must decode");
        assert_eq!(hello, roundtrip);
    }

    #[test]
    fn control_cmd_variants_serialize_and_deserialize() {
        let configure = ControlCmd::configure_stream("0", 1920, 1080, 60, 15000);
        let cfg_json = serde_json::to_string(&configure).unwrap();
        assert!(cfg_json.contains(r#""cmd":"ConfigureStream""#));
        assert!(cfg_json.contains(r#""bitrate_kbps":15000"#));
        let cfg_roundtrip: ControlCmd = serde_json::from_str(&cfg_json).unwrap();
        assert_eq!(configure, cfg_roundtrip);

        let spec_params_json = r#"{
            "cmd": "SetCameraParams",
            "zoom_ratio": 1.5,
            "exposure_ev": 0.5,
            "autofocus": true,
            "torch_on": false,
            "mirror_horizontal": false,
            "rotation_deg": 0
        }"#;
        let params: ControlCmd = serde_json::from_str(spec_params_json).unwrap();
        match params {
            ControlCmd::SetCameraParams {
                zoom_ratio,
                exposure_ev,
                autofocus,
                torch_on,
                mirror_horizontal,
                rotation_deg,
            } => {
                assert_eq!(zoom_ratio, Some(1.5));
                assert_eq!(exposure_ev, Some(0.5));
                assert_eq!(autofocus, Some(true));
                assert_eq!(torch_on, Some(false));
                assert_eq!(mirror_horizontal, Some(false));
                assert_eq!(rotation_deg, Some(0));
            }
            other => panic!("unexpected variant: {other:?}"),
        }

        let audio_cmd = ControlCmd::set_audio_state(true, false);
        let audio_json = serde_json::to_string(&audio_cmd).unwrap();
        assert!(audio_json.contains(r#""cmd":"SetAudioState""#));
        let audio_roundtrip: ControlCmd = serde_json::from_str(&audio_json).unwrap();
        assert_eq!(audio_cmd, audio_roundtrip);

        let keyframe_cmd = ControlCmd::request_keyframe();
        let kf_json = serde_json::to_string(&keyframe_cmd).unwrap();
        assert_eq!(kf_json, r#"{"cmd":"RequestKeyframe"}"#);
        let kf_roundtrip: ControlCmd =
            serde_json::from_str(r#"{"cmd":"RequestKeyframe"}"#).unwrap();
        assert_eq!(keyframe_cmd, kf_roundtrip);
    }

    #[test]
    fn control_ack_json_schema_matches_spec() {
        let ack_spec_json = r#"{
          "ok": true,
          "error": null,
          "active_state": {
            "camera_id": "0",
            "width": 1920,
            "height": 1080,
            "fps": 60,
            "bitrate_kbps": 15000,
            "zoom_ratio": 1.5,
            "exposure_ev": 0.5,
            "autofocus": true,
            "torch_on": false,
            "mirror_horizontal": false,
            "rotation_deg": 0,
            "audio_enabled": true,
            "audio_muted": false
          }
        }"#;

        let ack: ControlAck = serde_json::from_str(ack_spec_json).unwrap();
        assert!(ack.ok);
        assert!(ack.error.is_none());
        assert!(ack.active_state.is_some());
        let active = ack.active_state.as_ref().unwrap();
        assert_eq!(active.camera_id, "0");
        assert_eq!(active.bitrate_kbps, 15000);

        let failure_ack = ControlAck::failure("Camera device busy");
        assert!(!failure_ack.ok);
        assert_eq!(failure_ack.error.as_deref(), Some("Camera device busy"));
        assert!(failure_ack.active_state.is_none());
    }

    #[test]
    fn audio_config_payload_roundtrip() {
        let payload = AudioConfigPayload {
            sample_rate: 48000,
            channels: 2,
            codec: "opus".to_owned(),
        };

        let json = serde_json::to_string(&payload).unwrap();
        let decoded: AudioConfigPayload = serde_json::from_str(&json).unwrap();
        assert_eq!(payload, decoded);
    }

    #[test]
    fn audio_config_and_frame_packets_roundtrip() {
        let config = AudioConfigPayload {
            sample_rate: 48_000,
            channels: 1,
            codec: "pcm_s16le".to_owned(),
        };
        let cfg_pkt = Packet::audio_config(12_345, &config).expect("encode AUDIO_CONFIG");
        assert_eq!(cfg_pkt.header.packet_type, PacketType::AudioConfig);
        assert_eq!(cfg_pkt.header.pts_us, 12_345);

        let pcm_bytes = vec![0x34_u8; 1_920];
        let frame_pkt = Packet::audio_frame(32_345, Bytes::from(pcm_bytes.clone()));
        assert_eq!(frame_pkt.header.packet_type, PacketType::AudioFrame);
        assert_eq!(frame_pkt.header.payload_len, 1_920);

        let mut buf = BytesMut::new();
        encode(&cfg_pkt, &mut buf);
        encode(&frame_pkt, &mut buf);

        let decoded_cfg = decode(&mut buf)
            .expect("decode config header")
            .expect("complete config packet");
        assert_eq!(decoded_cfg.header.packet_type, PacketType::AudioConfig);
        let parsed_cfg: AudioConfigPayload = decoded_cfg.decode_json().expect("parse JSON config");
        assert_eq!(parsed_cfg.sample_rate, 48_000);
        assert_eq!(parsed_cfg.channels, 1);
        assert_eq!(parsed_cfg.codec, "pcm_s16le");

        let decoded_frame = decode(&mut buf)
            .expect("decode frame header")
            .expect("complete frame packet");
        assert_eq!(decoded_frame.header.packet_type, PacketType::AudioFrame);
        assert_eq!(decoded_frame.header.pts_us, 32_345);
        assert_eq!(decoded_frame.payload.as_ref(), pcm_bytes.as_slice());
        assert!(buf.is_empty());
    }
}
