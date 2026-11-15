//! Asynchronous TCP receiver client for CamCraft desktop.
//!
//! Handles connection to the Android sender over USB ADB tunnel or Wi-Fi LAN,
//! decodes CCFT v1 packet frames, sends periodic heartbeat pings, measures RTT latency,
//! and tracks real-time video frame rate and network bitrate metrics.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use bytes::BytesMut;
use camcraft_protocol::{
    ControlAck, ControlCmd, ControlHello, Packet, PacketHeader, PacketType, ProtocolError,
};
use thiserror::Error;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};

/// Errors encountered in client networking and packet processing.
#[derive(Debug, Error)]
pub enum ClientError {
    /// Network I/O failure.
    #[error("Network I/O error: {0}")]
    Io(#[from] std::io::Error),

    /// Protocol framing or serialization violation.
    #[error("Protocol error: {0}")]
    Protocol(#[from] ProtocolError),

    /// JSON serialization/deserialization failure.
    #[error("JSON serialization error: {0}")]
    Json(#[from] serde_json::Error),

    /// Connection was terminated by the remote peer.
    #[error("Connection closed by remote sender")]
    ConnectionClosed,

    /// Channel send failure (internal pipeline issue).
    #[error("Internal pipeline channel error: {0}")]
    Channel(String),
}

/// Real-time streaming metrics aggregated over a 1-second sliding window.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct StreamStats {
    /// Instantaneous frame rate (frames per second).
    pub fps: f64,
    /// Instantaneous network throughput (Megabits per second).
    pub bitrate_mbps: f64,
    /// Round-Trip Time latency measured via HEARTBEAT_PING/PONG in milliseconds.
    pub rtt_ms: f64,
    /// Cumulative count of received video frames.
    pub total_frames: u64,
    /// Cumulative payload bytes received for video frames.
    pub total_video_bytes: u64,
    /// Count of received IDR keyframes.
    pub keyframe_count: u64,
}

impl Default for StreamStats {
    fn default() -> Self {
        Self {
            fps: 0.0,
            bitrate_mbps: 0.0,
            rtt_ms: 0.0,
            total_frames: 0,
            total_video_bytes: 0,
            keyframe_count: 0,
        }
    }
}

/// Events dispatched by the client receiver loop.
#[derive(Debug, Clone)]
pub enum ClientEvent {
    /// Initial device discovery message containing camera sensors and current state.
    Hello(ControlHello),
    /// Video frame packet received.
    VideoFrame {
        header: PacketHeader,
        payload_len: usize,
    },
    /// Video codec configuration (SPS/PPS parameter sets).
    VideoConfig {
        header: PacketHeader,
        payload_len: usize,
    },
    /// Audio stream configuration.
    AudioConfig {
        header: PacketHeader,
        payload_len: usize,
    },
    /// Audio sample packet.
    AudioFrame {
        header: PacketHeader,
        payload_len: usize,
    },
    /// Control command acknowledgment.
    ControlAck(ControlAck),
    /// Periodic 1-second streaming metrics update.
    Stats(StreamStats),
    /// Disconnection event with reason description.
    Disconnected(String),
}

/// Computes the current Unix timestamp in microseconds.
#[must_use]
pub fn current_time_us() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_micros() as u64)
        .unwrap_or(0)
}

/// Configuration options for the TCP receiver client.
#[derive(Debug, Clone)]
pub struct ClientConfig {
    /// Remote address (e.g. `127.0.0.1:9876` or `192.168.1.100:9876`).
    pub endpoint: String,
    /// Heartbeat ping interval (defaults to 1 second).
    pub ping_interval: Duration,
    /// Initial read buffer capacity in bytes (defaults to 64 KB).
    pub read_buffer_capacity: usize,
}

impl ClientConfig {
    /// Creates configuration for the default loopback USB tunnel endpoint.
    #[must_use]
    pub fn usb_default() -> Self {
        Self {
            endpoint: format!("127.0.0.1:{}", camcraft_protocol::DEFAULT_PORT),
            ping_interval: Duration::from_secs(1),
            read_buffer_capacity: 65536,
        }
    }

    /// Creates configuration for a custom endpoint string.
    #[must_use]
    pub fn new(endpoint: impl Into<String>) -> Self {
        Self {
            endpoint: endpoint.into(),
            ping_interval: Duration::from_secs(1),
            read_buffer_capacity: 65536,
        }
    }
}

/// Asynchronous handle to send commands to the connected Android device.
#[derive(Clone)]
pub struct ClientSender {
    tx: mpsc::Sender<Packet>,
}

impl ClientSender {
    /// Sends a [`ControlCmd`] to the Android sender.
    pub async fn send_cmd(&self, cmd: ControlCmd) -> Result<(), ClientError> {
        let packet = Packet::control_cmd(current_time_us(), &cmd)?;
        self.tx
            .send(packet)
            .await
            .map_err(|e| ClientError::Channel(e.to_string()))
    }

    /// Sends a manual heartbeat ping packet.
    pub async fn send_ping(&self) -> Result<(), ClientError> {
        let packet = Packet::heartbeat_ping(current_time_us());
        self.tx
            .send(packet)
            .await
            .map_err(|e| ClientError::Channel(e.to_string()))
    }
}

/// Connects to the Android device, establishes bidirectional CCFT v1 streaming,
/// runs heartbeat and stats loops, and forwards events to `event_tx`.
pub async fn run_client_loop(
    config: ClientConfig,
    event_tx: mpsc::Sender<ClientEvent>,
    shutdown: Arc<AtomicBool>,
) -> Result<(), ClientError> {
    info!(endpoint = %config.endpoint, "Connecting to CamCraft Android sender");
    let stream = TcpStream::connect(&config.endpoint).await?;
    stream.set_nodelay(true)?;
    info!(endpoint = %config.endpoint, "TCP connection established (TCP_NODELAY enabled)");

    let (mut reader, mut writer) = stream.into_split();

    // Outbound packet channel
    let (cmd_tx, mut cmd_rx) = mpsc::channel::<Packet>(32);
    let sender_handle = ClientSender { tx: cmd_tx.clone() };

    // Spawn outbound writer task
    let shutdown_writer = shutdown.clone();
    let writer_task = tokio::spawn(async move {
        let mut encode_buf = BytesMut::with_capacity(1024);
        while !shutdown_writer.load(Ordering::Relaxed) {
            match cmd_rx.recv().await {
                Some(packet) => {
                    encode_buf.clear();
                    packet.encode(&mut encode_buf);
                    if let Err(e) = writer.write_all(&encode_buf).await {
                        error!(error = %e, "Failed to write packet to network socket");
                        break;
                    }
                    if let Err(e) = writer.flush().await {
                        error!(error = %e, "Failed to flush network socket");
                        break;
                    }
                }
                None => break,
            }
        }
    });

    // Spawn periodic 1-second heartbeat ping task
    let ping_shutdown = shutdown.clone();
    let ping_sender = sender_handle.clone();
    let ping_interval = config.ping_interval;
    let ping_task = tokio::spawn(async move {
        let mut interval = tokio::time::interval(ping_interval);
        // First tick completes immediately, skip it to avoid zero-interval ping at connect
        interval.tick().await;

        while !ping_shutdown.load(Ordering::Relaxed) {
            interval.tick().await;
            if ping_sender.send_ping().await.is_err() {
                break;
            }
            debug!("Sent HEARTBEAT_PING (0xFE)");
        }
    });

    // Receiver loop state
    let mut read_buf = BytesMut::with_capacity(config.read_buffer_capacity);

    let mut stats = StreamStats::default();
    let mut interval_frames: u64 = 0;
    let mut interval_bytes: u64 = 0;
    let mut last_stats_tick = Instant::now();
    let mut last_rtt_ms: f64 = 0.0;

    let loop_result: Result<(), ClientError> = async {
        loop {
            if shutdown.load(Ordering::Relaxed) {
                info!("Client shutdown requested, exiting receiver loop");
                break;
            }

            // Check if 1-second sliding window has elapsed for stats
            let elapsed = last_stats_tick.elapsed();
            if elapsed >= Duration::from_secs(1) {
                let secs = elapsed.as_secs_f64();
                stats.fps = interval_frames as f64 / secs;
                stats.bitrate_mbps = (interval_bytes as f64 * 8.0) / (secs * 1_000_000.0);
                stats.rtt_ms = last_rtt_ms;

                let _ = event_tx.send(ClientEvent::Stats(stats)).await;

                interval_frames = 0;
                interval_bytes = 0;
                last_stats_tick = Instant::now();
            }

            // Read available bytes from TCP socket
            tokio::select! {
                read_res = reader.read_buf(&mut read_buf) => {
                    let n = read_res?;
                    if n == 0 {
                        info!("Remote sender closed the TCP connection");
                        return Err(ClientError::ConnectionClosed);
                    }
                }
                _ = tokio::time::sleep(Duration::from_millis(100)) => {
                    // Periodic wake-up to allow stats update and shutdown check even when idle
                    continue;
                }
            }

            // Decode all complete CCFT packets in read_buf
            while let Some(packet) = Packet::decode(&mut read_buf)? {
                match packet.header.packet_type {
                    PacketType::ControlHello => {
                        let hello: ControlHello = packet.decode_json()?;
                        info!(
                            device_name = %hello.device_name,
                            protocol_version = hello.protocol_version,
                            cameras = hello.cameras.len(),
                            "Received CONTROL_HELLO from Android device"
                        );
                        let _ = event_tx.send(ClientEvent::Hello(hello)).await;
                    }
                    PacketType::HeartbeatPong => {
                        let now_us = current_time_us();
                        let ping_pts = packet.header.pts_us;
                        let rtt_us = now_us.saturating_sub(ping_pts);
                        last_rtt_ms = rtt_us as f64 / 1000.0;
                        stats.rtt_ms = last_rtt_ms;
                        debug!(rtt_ms = %format!("{:.2}", last_rtt_ms), "Received HEARTBEAT_PONG (0xFF)");
                    }
                    PacketType::HeartbeatPing => {
                        // In case remote also sends pings, respond with pong
                        let pong = Packet::heartbeat_pong(packet.header.pts_us);
                        let _ = cmd_tx.send(pong).await;
                    }
                    PacketType::VideoFrame => {
                        stats.total_frames += 1;
                        stats.total_video_bytes += packet.payload.len() as u64;
                        interval_frames += 1;
                        interval_bytes += packet.payload.len() as u64;

                        if packet.header.flags.is_keyframe() {
                            stats.keyframe_count += 1;
                        }

                        let _ = event_tx
                            .send(ClientEvent::VideoFrame {
                                header: packet.header,
                                payload_len: packet.payload.len(),
                            })
                            .await;
                    }
                    PacketType::VideoConfig => {
                        info!(
                            payload_len = packet.payload.len(),
                            "Received VIDEO_CONFIG (SPS/PPS parameter sets)"
                        );
                        let _ = event_tx
                            .send(ClientEvent::VideoConfig {
                                header: packet.header,
                                payload_len: packet.payload.len(),
                            })
                            .await;
                    }
                    PacketType::AudioConfig => {
                        info!(
                            payload_len = packet.payload.len(),
                            "Received AUDIO_CONFIG"
                        );
                        let _ = event_tx
                            .send(ClientEvent::AudioConfig {
                                header: packet.header,
                                payload_len: packet.payload.len(),
                            })
                            .await;
                    }
                    PacketType::AudioFrame => {
                        let _ = event_tx
                            .send(ClientEvent::AudioFrame {
                                header: packet.header,
                                payload_len: packet.payload.len(),
                            })
                            .await;
                    }
                    PacketType::ControlAck => {
                        let ack: ControlAck = packet.decode_json()?;
                        info!(ok = ack.ok, error = ?ack.error, "Received CONTROL_ACK");
                        let _ = event_tx.send(ClientEvent::ControlAck(ack)).await;
                    }
                    PacketType::ControlCmd => {
                        warn!("Received unexpected CONTROL_CMD from sender peer");
                    }
                }
            }
        }

        Ok(())
    }
    .await;

    // Cleanup background tasks
    shutdown.store(true, Ordering::Relaxed);
    let _ = ping_task.await;
    let _ = writer_task.await;

    if let Err(ref e) = loop_result {
        let _ = event_tx
            .send(ClientEvent::Disconnected(e.to_string()))
            .await;
    }

    loop_result
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn current_time_us_is_strictly_positive() {
        let t1 = current_time_us();
        assert!(t1 > 0);
    }

    #[test]
    fn stream_stats_default_is_zeroed() {
        let stats = StreamStats::default();
        assert_eq!(stats.fps, 0.0);
        assert_eq!(stats.bitrate_mbps, 0.0);
        assert_eq!(stats.rtt_ms, 0.0);
        assert_eq!(stats.total_frames, 0);
    }
}
