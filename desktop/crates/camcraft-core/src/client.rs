//! Asynchronous TCP receiver client for CamCraft desktop.
//!
//! Handles connection to the Android sender over USB ADB tunnel or Wi-Fi LAN,
//! decodes CCFT v1 packet frames, sends periodic heartbeat pings, measures RTT latency,
//! and tracks real-time video frame rate and network bitrate metrics.

use std::future::Future;
use std::net::{SocketAddr, SocketAddrV4};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use bytes::{Bytes, BytesMut};
use camcraft_protocol::{
    AudioConfigPayload, ControlAck, ControlCmd, ControlHello, Packet, PacketHeader, PacketType,
    ProtocolError,
};
use thiserror::Error;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpSocket, TcpStream};
use tokio::sync::mpsc;
use tracing::{debug, error, info, warn};

const CONNECT_TIMEOUT: Duration = Duration::from_secs(5);

/// Default read inactivity / heartbeat timeout before considering a connection dead.
pub const DEFAULT_READ_TIMEOUT: Duration = Duration::from_secs(5);

/// Default interval between automatic reconnection attempts after unexpected drops.
pub const DEFAULT_RECONNECT_INTERVAL: Duration = Duration::from_secs(2);

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

    /// No packets or heartbeats were received within the read timeout window.
    #[error("Connection timed out (no data received for {:.1}s)", .0.as_secs_f64())]
    HeartbeatTimeout(Duration),

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
    /// TCP connection established with an outbound command sender handle.
    Connected(ClientSender),
    /// Initial device discovery message containing camera sensors and current state.
    Hello(ControlHello),
    /// Video frame packet received.
    VideoFrame {
        header: PacketHeader,
        payload_len: usize,
        payload: Bytes,
    },
    /// Video codec configuration (SPS/PPS parameter sets).
    VideoConfig {
        header: PacketHeader,
        payload_len: usize,
        payload: Bytes,
    },
    /// Audio stream configuration (`0x03`).
    AudioConfig {
        header: PacketHeader,
        payload_len: usize,
        config: AudioConfigPayload,
    },
    /// Audio sample packet (`0x04`).
    AudioFrame {
        header: PacketHeader,
        payload_len: usize,
        payload: Bytes,
    },
    /// Control command acknowledgment.
    ControlAck(ControlAck),
    /// Periodic 1-second streaming metrics update.
    Stats(StreamStats),
    /// Unexpected drop detected; automatic reconnect scheduled.
    Reconnecting {
        attempt: u32,
        reason: String,
        retry_in: Duration,
    },
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
    /// Optional network interface name (e.g. `"wlp2s0"`) to bind outbound TCP connection.
    pub interface: Option<String>,
    /// Heartbeat ping interval (defaults to 1 second).
    pub ping_interval: Duration,
    /// Read inactivity timeout before considering a silent link dropped (defaults to 5 seconds).
    pub read_timeout: Duration,
    /// Initial read buffer capacity in bytes (defaults to 256 KiB).
    pub read_buffer_capacity: usize,
}

impl ClientConfig {
    /// Creates configuration for the default loopback USB tunnel endpoint.
    #[must_use]
    pub fn usb_default() -> Self {
        Self {
            endpoint: format!("127.0.0.1:{}", camcraft_protocol::DEFAULT_PORT),
            interface: None,
            ping_interval: Duration::from_secs(1),
            read_timeout: DEFAULT_READ_TIMEOUT,
            read_buffer_capacity: 262_144,
        }
    }

    /// Creates configuration for a custom endpoint string.
    #[must_use]
    pub fn new(endpoint: impl Into<String>) -> Self {
        Self {
            endpoint: endpoint.into(),
            interface: None,
            ping_interval: Duration::from_secs(1),
            read_timeout: DEFAULT_READ_TIMEOUT,
            read_buffer_capacity: 262_144,
        }
    }

    /// Pins outbound TCP connection attempts to a specific OS network interface (`SO_BINDTODEVICE`).
    #[must_use]
    pub fn with_interface(mut self, iface: impl Into<String>) -> Self {
        let trimmed = iface.into().trim().to_owned();
        self.interface = if trimmed.is_empty() {
            None
        } else {
            Some(trimmed)
        };
        self
    }
}

/// Configuration for automatic client reconnection after unexpected drops.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ReconnectConfig {
    /// Delay between reconnection attempts (defaults to 2 seconds).
    pub retry_interval: Duration,
    /// Whether to retry if the initial connection fails before `CONTROL_HELLO`.
    pub reconnect_on_initial_failure: bool,
}

impl Default for ReconnectConfig {
    fn default() -> Self {
        Self {
            retry_interval: DEFAULT_RECONNECT_INTERVAL,
            reconnect_on_initial_failure: false,
        }
    }
}

/// Asynchronous handle to send commands to the connected Android device.
#[derive(Debug, Clone)]
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
    run_client_loop_inner(config, event_tx, shutdown, None, true).await
}

/// Runs [`run_client_loop`] with an automatic reconnect loop on unexpected disconnections.
///
/// Invokes `before_reconnect(attempt)` before each retry attempt (e.g. to refresh `adb forward`
/// on USB connections) and emits [`ClientEvent::Reconnecting`] until the device reconnects or
/// `shutdown` is signaled.
pub async fn run_client_with_reconnect<F, Fut>(
    config: ClientConfig,
    reconnect: ReconnectConfig,
    event_tx: mpsc::Sender<ClientEvent>,
    shutdown: Arc<AtomicBool>,
    mut before_reconnect: F,
) -> Result<(), ClientError>
where
    F: FnMut(u32) -> Fut,
    Fut: Future<Output = Result<(), String>>,
{
    let mut established_once = false;
    let mut attempt: u32 = 0;

    loop {
        if shutdown.load(Ordering::Relaxed) {
            return Ok(());
        }

        if attempt > 0 {
            if !sleep_until_retry_or_shutdown(reconnect.retry_interval, &shutdown).await {
                return Ok(());
            }

            if let Err(reason) = before_reconnect(attempt).await {
                if shutdown.load(Ordering::Relaxed) {
                    return Ok(());
                }
                attempt = attempt.saturating_add(1);
                let _ = event_tx
                    .send(ClientEvent::Reconnecting {
                        attempt,
                        reason,
                        retry_in: reconnect.retry_interval,
                    })
                    .await;
                continue;
            }
        }

        let hello_seen = AtomicBool::new(false);
        let res = run_client_loop_inner(
            config.clone(),
            event_tx.clone(),
            shutdown.clone(),
            Some(&hello_seen),
            false,
        )
        .await;

        match res {
            Ok(()) => return Ok(()),
            Err(err) => {
                if shutdown.load(Ordering::Relaxed) {
                    return Ok(());
                }

                if hello_seen.load(Ordering::Relaxed) {
                    established_once = true;
                    attempt = 1;
                } else if established_once || reconnect.reconnect_on_initial_failure {
                    attempt = attempt.saturating_add(1).max(1);
                } else {
                    let _ = event_tx
                        .send(ClientEvent::Disconnected(err.to_string()))
                        .await;
                    return Err(err);
                }

                let _ = event_tx
                    .send(ClientEvent::Reconnecting {
                        attempt,
                        reason: err.to_string(),
                        retry_in: reconnect.retry_interval,
                    })
                    .await;
            }
        }
    }
}

async fn sleep_until_retry_or_shutdown(duration: Duration, shutdown: &AtomicBool) -> bool {
    let deadline = Instant::now() + duration;
    while !shutdown.load(Ordering::Relaxed) {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return true;
        }
        tokio::time::sleep(remaining.min(Duration::from_millis(50))).await;
    }
    false
}

async fn run_client_loop_inner(
    config: ClientConfig,
    event_tx: mpsc::Sender<ClientEvent>,
    shutdown: Arc<AtomicBool>,
    hello_seen: Option<&AtomicBool>,
    emit_disconnected: bool,
) -> Result<(), ClientError> {
    info!(
        endpoint = %config.endpoint,
        interface = ?config.interface,
        "Connecting to CamCraft Android sender"
    );
    let stream = connect_tcp(
        &config.endpoint,
        config.interface.as_deref(),
        CONNECT_TIMEOUT,
    )
    .await?;
    stream.set_nodelay(true)?;
    info!(endpoint = %config.endpoint, "TCP connection established (TCP_NODELAY enabled)");

    let (mut reader, mut writer) = stream.into_split();

    // Session-scoped stop flag so a single connection drop does not mutate caller's `shutdown`.
    let session_stop = Arc::new(AtomicBool::new(false));

    // Outbound packet channel
    let (cmd_tx, mut cmd_rx) = mpsc::channel::<Packet>(32);
    let sender_handle = ClientSender { tx: cmd_tx.clone() };
    let _ = event_tx
        .send(ClientEvent::Connected(sender_handle.clone()))
        .await;

    // Spawn outbound writer task
    let shutdown_writer = shutdown.clone();
    let stop_writer = session_stop.clone();
    let writer_task = tokio::spawn(async move {
        let mut encode_buf = BytesMut::with_capacity(1024);
        while !shutdown_writer.load(Ordering::Relaxed) && !stop_writer.load(Ordering::Relaxed) {
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
    let ping_stop = session_stop.clone();
    let ping_sender = sender_handle;
    let ping_interval = config.ping_interval;
    let ping_task = tokio::spawn(async move {
        let mut interval = tokio::time::interval(ping_interval);
        // First tick completes immediately, skip it to avoid zero-interval ping at connect
        interval.tick().await;

        while !ping_shutdown.load(Ordering::Relaxed) && !ping_stop.load(Ordering::Relaxed) {
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
    let mut last_activity = Instant::now();
    let mut last_rtt_ms: f64 = 0.0;
    let idle_poll = Duration::from_millis(100)
        .min(config.read_timeout)
        .max(Duration::from_millis(1));
    let mut idle_tick = tokio::time::interval(idle_poll);
    idle_tick.set_missed_tick_behavior(tokio::time::MissedTickBehavior::Delay);
    idle_tick.tick().await;

    let loop_result: Result<(), ClientError> = async {
        loop {
            if shutdown.load(Ordering::Relaxed) {
                info!("Client shutdown requested, exiting receiver loop");
                break;
            }

            if last_activity.elapsed() >= config.read_timeout {
                warn!(
                    timeout_secs = config.read_timeout.as_secs_f64(),
                    "Connection read inactivity timeout reached"
                );
                return Err(ClientError::HeartbeatTimeout(config.read_timeout));
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
                biased;
                read_res = reader.read_buf(&mut read_buf) => {
                    let n = read_res?;
                    if n == 0 {
                        info!("Remote sender closed the TCP connection");
                        return Err(ClientError::ConnectionClosed);
                    }
                    last_activity = Instant::now();
                }
                _ = idle_tick.tick() => {
                    // Periodic wake-up to allow stats update, timeout check, and shutdown check
                    continue;
                }
            }

            // Decode all complete CCFT packets in read_buf
            while let Some(packet) = Packet::decode(&mut read_buf)? {
                match packet.header.packet_type {
                    PacketType::ControlHello => {
                        let hello: ControlHello = packet.decode_json()?;
                        if let Some(flag) = hello_seen {
                            flag.store(true, Ordering::Relaxed);
                        }
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
                        let payload_len = packet.payload.len();
                        stats.total_frames += 1;
                        stats.total_video_bytes += payload_len as u64;
                        interval_frames += 1;
                        interval_bytes += payload_len as u64;

                        if packet.header.flags.is_keyframe() {
                            stats.keyframe_count += 1;
                        }

                        let _ = event_tx
                            .send(ClientEvent::VideoFrame {
                                header: packet.header,
                                payload_len,
                                payload: packet.payload,
                            })
                            .await;
                    }
                    PacketType::VideoConfig => {
                        let payload_len = packet.payload.len();
                        info!(
                            payload_len,
                            "Received VIDEO_CONFIG (SPS/PPS parameter sets)"
                        );
                        let _ = event_tx
                            .send(ClientEvent::VideoConfig {
                                header: packet.header,
                                payload_len,
                                payload: packet.payload,
                            })
                            .await;
                    }
                    PacketType::AudioConfig => {
                        let config: AudioConfigPayload = packet.decode_json()?;
                        info!(
                            payload_len = packet.payload.len(),
                            codec = %config.codec,
                            sample_rate = config.sample_rate,
                            channels = config.channels,
                            "Received AUDIO_CONFIG"
                        );
                        let _ = event_tx
                            .send(ClientEvent::AudioConfig {
                                header: packet.header,
                                payload_len: packet.payload.len(),
                                config,
                            })
                            .await;
                    }
                    PacketType::AudioFrame => {
                        let payload_len = packet.payload.len();
                        interval_bytes += payload_len as u64;
                        let _ = event_tx
                            .send(ClientEvent::AudioFrame {
                                header: packet.header,
                                payload_len,
                                payload: packet.payload,
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

    // Cleanup session background tasks without modifying caller's `shutdown` flag
    session_stop.store(true, Ordering::Relaxed);
    ping_task.abort();
    writer_task.abort();
    let _ = ping_task.await;
    let _ = writer_task.await;

    if emit_disconnected && let Err(ref e) = loop_result {
        let _ = event_tx
            .send(ClientEvent::Disconnected(e.to_string()))
            .await;
    }

    loop_result
}

async fn connect_plain(endpoint: &str, timeout: Duration) -> Result<TcpStream, ClientError> {
    tokio::time::timeout(timeout, TcpStream::connect(endpoint))
        .await
        .map_err(|_| {
            ClientError::Io(std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                format!(
                    "Connection to {endpoint} timed out after {}s",
                    timeout.as_secs()
                ),
            ))
        })?
        .map_err(ClientError::from)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
async fn connect_via_device(
    dest: SocketAddrV4,
    iface_name: &str,
    timeout: Duration,
) -> Result<TcpStream, std::io::Error> {
    let socket = TcpSocket::new_v4()?;
    socket.bind_device(Some(iface_name.as_bytes()))?;
    tokio::time::timeout(timeout, socket.connect(SocketAddr::V4(dest)))
        .await
        .map_err(|_| {
            std::io::Error::new(
                std::io::ErrorKind::TimedOut,
                format!("Connection to {dest} on {iface_name} timed out"),
            )
        })?
}

async fn connect_tcp(
    endpoint: &str,
    preferred_interface: Option<&str>,
    connect_timeout: Duration,
) -> Result<TcpStream, ClientError> {
    let addrs: Vec<SocketAddr> =
        tokio::time::timeout(connect_timeout, tokio::net::lookup_host(endpoint))
            .await
            .map_err(|_| {
                ClientError::Io(std::io::Error::new(
                    std::io::ErrorKind::TimedOut,
                    format!("DNS lookup for {endpoint} timed out"),
                ))
            })??
            .collect();

    if addrs.is_empty() {
        return Err(ClientError::Io(std::io::Error::new(
            std::io::ErrorKind::AddrNotAvailable,
            format!("No addresses resolved for {endpoint}"),
        )));
    }

    #[cfg(any(target_os = "linux", target_os = "android"))]
    if let Some(SocketAddr::V4(dest_v4)) = addrs.iter().copied().find(|addr| match addr {
        SocketAddr::V4(v4) => !v4.ip().is_loopback() && !v4.ip().is_unspecified(),
        SocketAddr::V6(_) => false,
    }) {
        if let Some(iface) = preferred_interface.map(str::trim).filter(|s| !s.is_empty()) {
            match connect_via_device(dest_v4, iface, connect_timeout).await {
                Ok(stream) => {
                    info!(endpoint = %endpoint, interface = %iface, "TCP connected via pinned interface");
                    return Ok(stream);
                }
                Err(err) if err.kind() == std::io::ErrorKind::PermissionDenied => {
                    warn!(
                        interface = %iface,
                        error = %err,
                        "SO_BINDTODEVICE denied; falling back to default OS routing"
                    );
                    return connect_plain(endpoint, connect_timeout).await;
                }
                Err(err) => return Err(ClientError::Io(err)),
            }
        }

        let matching = crate::net::matching_interfaces_for_target(
            &crate::net::list_active_ipv4_interfaces(),
            *dest_v4.ip(),
        );
        if matching.len() >= 2 {
            let iface_names: Vec<&str> = matching.iter().map(|i| i.name.as_str()).collect();
            debug!(
                endpoint = %endpoint,
                interfaces = ?iface_names,
                "Multiple local interfaces match target subnet; racing outbound TCP connections"
            );

            let mut join_set = tokio::task::JoinSet::new();
            for iface in matching {
                let name = iface.name;
                join_set.spawn(async move {
                    let res = connect_via_device(dest_v4, &name, connect_timeout).await;
                    (name, res)
                });
            }

            let mut errors = Vec::new();
            while let Some(joined) = join_set.join_next().await {
                match joined {
                    Ok((iface_name, Ok(stream))) => {
                        info!(
                            endpoint = %endpoint,
                            interface = %iface_name,
                            "TCP connected via interface (dual-NIC auto-routing)"
                        );
                        join_set.abort_all();
                        return Ok(stream);
                    }
                    Ok((iface_name, Err(err))) => {
                        debug!(
                            endpoint = %endpoint,
                            interface = %iface_name,
                            error = %err,
                            "Interface connect attempt failed"
                        );
                        errors.push(err);
                    }
                    Err(join_err) => {
                        debug!(error = %join_err, "Dual-NIC connect task failed");
                    }
                }
            }

            if !errors.is_empty() {
                if errors
                    .iter()
                    .all(|e| e.kind() == std::io::ErrorKind::PermissionDenied)
                {
                    warn!(
                        "SO_BINDTODEVICE denied on all matching interfaces; falling back to default OS routing"
                    );
                    return connect_plain(endpoint, connect_timeout).await;
                }
                return Err(ClientError::Io(pick_most_informative_connect_error(errors)));
            }
        }
    }

    #[cfg(not(any(target_os = "linux", target_os = "android")))]
    let _ = (preferred_interface, SocketAddrV4::new);

    connect_plain(endpoint, connect_timeout).await
}

fn error_informativeness_rank(err: &std::io::Error) -> u8 {
    let is_unreachable = matches!(
        err.kind(),
        std::io::ErrorKind::HostUnreachable | std::io::ErrorKind::NetworkUnreachable
    ) || {
        #[cfg(unix)]
        {
            matches!(
                err.raw_os_error(),
                Some(libc::EHOSTUNREACH | libc::ENETUNREACH)
            )
        }
        #[cfg(not(unix))]
        {
            false
        }
    };

    if err.kind() == std::io::ErrorKind::ConnectionRefused {
        3
    } else if err.kind() == std::io::ErrorKind::TimedOut {
        2
    } else if is_unreachable {
        0
    } else {
        1
    }
}

fn pick_most_informative_connect_error(errors: Vec<std::io::Error>) -> std::io::Error {
    errors
        .into_iter()
        .max_by_key(error_informativeness_rank)
        .unwrap_or_else(|| {
            std::io::Error::new(
                std::io::ErrorKind::NotConnected,
                "Failed to connect on all matching interfaces",
            )
        })
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

    #[test]
    fn pick_most_informative_connect_error_prefers_refused_over_unreachable_and_timeout() {
        #[cfg(unix)]
        let unreachable = std::io::Error::from_raw_os_error(libc::EHOSTUNREACH);
        #[cfg(not(unix))]
        let unreachable = std::io::Error::new(std::io::ErrorKind::HostUnreachable, "no route");

        let timed_out = std::io::Error::new(std::io::ErrorKind::TimedOut, "timed out");
        let refused = std::io::Error::new(std::io::ErrorKind::ConnectionRefused, "refused");

        let chosen = pick_most_informative_connect_error(vec![unreachable, timed_out, refused]);
        assert_eq!(chosen.kind(), std::io::ErrorKind::ConnectionRefused);

        #[cfg(unix)]
        let unreachable_only = std::io::Error::from_raw_os_error(libc::EHOSTUNREACH);
        #[cfg(not(unix))]
        let unreachable_only = std::io::Error::new(std::io::ErrorKind::HostUnreachable, "no route");

        let timed_out_2 = std::io::Error::new(std::io::ErrorKind::TimedOut, "timed out");
        let chosen_timeout =
            pick_most_informative_connect_error(vec![unreachable_only, timed_out_2]);
        assert_eq!(chosen_timeout.kind(), std::io::ErrorKind::TimedOut);
    }
}
