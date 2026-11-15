//! CCFT v1 wire protocol definitions and framing stubs for CamCraft.
//!
//! Full binary codec and control JSON serialization are implemented in Stage 1
//! per `docs/PROTOCOL.md`.

use serde::{Deserialize, Serialize};
use thiserror::Error;

/// Fixed 4-byte ASCII magic header (`"CCFT"`).
pub const PROTOCOL_MAGIC: [u8; 4] = *b"CCFT";

/// Current CCFT wire protocol version.
pub const PROTOCOL_VERSION: u8 = 0x01;

/// Fixed CCFT frame header size in bytes (20 bytes, 4-byte aligned).
pub const HEADER_SIZE: usize = 20;

/// Default multiplexed TCP port for USB ADB and Wi-Fi LAN connections.
pub const DEFAULT_PORT: u16 = 9876;

/// Maximum permitted payload length per frame (16 MiB).
pub const MAX_PAYLOAD_LEN: u32 = 16_777_216;

/// Errors raised while parsing or encoding CCFT v1 frames.
#[derive(Debug, Error)]
pub enum ProtocolError {
    #[error("invalid CCFT magic header: expected {PROTOCOL_MAGIC:?}, got {0:?}")]
    InvalidMagic([u8; 4]),

    #[error("unsupported protocol version: {0}")]
    UnsupportedVersion(u8),

    #[error("unknown packet type: 0x{0:02x}")]
    UnknownPacketType(u8),

    #[error("payload length {0} exceeds maximum {MAX_PAYLOAD_LEN}")]
    PayloadTooLarge(u32),

    #[error("JSON control payload error: {0}")]
    Json(#[from] serde_json::Error),
}

/// Placeholder packet type discriminant matching `docs/PROTOCOL.md` §3.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
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

/// Touch `bytes::Bytes` in Stage 0 to verify workspace dependency wiring.
#[must_use]
pub fn empty_payload() -> bytes::Bytes {
    bytes::Bytes::new()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protocol_constants_match_spec() {
        assert_eq!(&PROTOCOL_MAGIC, b"CCFT");
        assert_eq!(PROTOCOL_VERSION, 1);
        assert_eq!(HEADER_SIZE, 20);
        assert_eq!(DEFAULT_PORT, 9876);
        assert!(empty_payload().is_empty());
        assert_eq!(
            PacketType::try_from(0x10).ok(),
            Some(PacketType::ControlHello)
        );
        assert!(PacketType::try_from(0x99).is_err());
    }
}
