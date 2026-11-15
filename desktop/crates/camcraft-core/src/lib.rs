//! Core receiver pipeline for CamCraft desktop (Fedora Linux).
//!
//! Modules follow `docs/DESIGN.md` §3.1 (`net`, `adb`, `decoder`, `vcam`, `vmic`)
//! and will be implemented incrementally across Stages 2-8.

use thiserror::Error;

/// Core pipeline error type.
#[derive(Debug, Error)]
pub enum CoreError {
    #[error("protocol error: {0}")]
    Protocol(#[from] camcraft_protocol::ProtocolError),

    #[error("ADB error: {0}")]
    Adb(#[from] adb::AdbError),

    #[error("client network error: {0}")]
    Client(#[from] client::ClientError),

    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("operation not yet implemented in Stage 0 scaffolding: {0}")]
    Unimplemented(&'static str),
}

/// Platform-independent sink abstraction for decoded video frames (`docs/PRD.md` §6.3).
pub trait VideoOutputSink: Send {
    /// Writes one decoded raw video frame (e.g., YUV420p/YUYV) to the virtual camera device.
    fn write_frame(&mut self, width: u32, height: u32, data: &[u8]) -> Result<(), CoreError>;
}

/// Platform-independent sink abstraction for decoded audio samples (`docs/PRD.md` §6.3).
pub trait AudioOutputSink: Send {
    /// Writes decoded S16LE PCM audio samples to the virtual microphone device.
    fn write_samples(
        &mut self,
        sample_rate: u32,
        channels: u16,
        pcm_s16le: &[u8],
    ) -> Result<(), CoreError>;
}

/// Asynchronous network client & CCFT demuxer (Stage 2+).
pub mod net {
    use tracing::debug;

    /// Returns the default CamCraft TCP endpoint address for USB ADB forwarding.
    #[must_use]
    pub fn usb_loopback_addr() -> String {
        debug!("Resolving default USB loopback endpoint");
        format!("127.0.0.1:{}", camcraft_protocol::DEFAULT_PORT)
    }
}

/// ADB tunnel helper (`adb forward`) (Stage 2+).
pub mod adb;

/// Asynchronous network client & CCFT demuxer (Stage 2+).
pub mod client;

/// Bounded video/audio frame queues and H.264/Opus decoders (Stage 4 / Stage 7).
pub mod decoder {
    /// Bounded queue capacity for real-time video frames (`docs/DESIGN.md` §3.1).
    pub const DEFAULT_VIDEO_QUEUE_CAPACITY: usize = 4;

    /// Creates a bounded crossbeam channel used to drop stale frames under backpressure.
    #[must_use]
    pub fn bounded_video_channel<T>()
    -> (crossbeam_channel::Sender<T>, crossbeam_channel::Receiver<T>) {
        crossbeam_channel::bounded(DEFAULT_VIDEO_QUEUE_CAPACITY)
    }
}

/// Linux `v4l2loopback` virtual camera sink (Stage 4+).
pub mod vcam {}

/// PipeWire / PulseAudio virtual microphone sink (Stage 7+).
pub mod vmic {}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn bounded_channel_preserves_realtime_capacity() {
        assert_eq!(net::usb_loopback_addr(), "127.0.0.1:9876");
        let (tx, rx) = decoder::bounded_video_channel::<u8>();
        assert!(tx.try_send(1).is_ok());
        assert_eq!(rx.try_recv().ok(), Some(1));
    }
}
