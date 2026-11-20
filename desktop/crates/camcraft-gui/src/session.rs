//! Background networking, ADB forwarding, H.264 decoding, and V4L2 session manager
//! for `camcraft-gui`.

use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::JoinHandle;
use std::time::Instant;

use camcraft_core::adb;
use camcraft_core::client::{
    self, ClientConfig, ClientError, ClientEvent, ClientSender, StreamStats,
};
use camcraft_core::decoder::{
    DecodedFrame, DecoderSender, KEYFRAME_REQUEST_COOLDOWN, KeyframeRequestReason,
    spawn_decoder_worker_with_recovery,
};
use camcraft_core::discovery::{DiscoveryEvent, MdnsScanner};
use camcraft_core::vcam::{V4l2LoopbackSink, VirtualCameraSink, find_camcraft_v4l2_device};
use camcraft_protocol::{ControlAck, ControlCmd, ControlHello, DEFAULT_PORT};
use crossbeam_channel::{Receiver, Sender};
use eframe::egui;
use tokio::sync::mpsc;
use tracing::{error, info, warn};

/// Transport mode used for the current or pending connection.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ConnectionMode {
    Usb,
    Wifi,
}

impl ConnectionMode {
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Usb => "USB",
            Self::Wifi => "Wi-Fi",
        }
    }
}

/// Commands sent from the UI thread to the background session worker.
#[derive(Debug, Clone)]
pub enum GuiCommand {
    ConnectUsb {
        port: u16,
        vcam_device: String,
    },
    ConnectWifi {
        endpoint: String,
        vcam_device: String,
    },
    RefreshWifiDiscovery,
    Disconnect,
    SendControlCmd(ControlCmd),
}

/// Status updates sent from the background session worker to the UI thread.
#[derive(Debug, Clone)]
pub enum GuiUpdate {
    Connecting {
        mode: ConnectionMode,
        endpoint: String,
    },
    Connected(ConnectionMode),
    Hello(ControlHello),
    ControlAck(ControlAck),
    Stats(StreamStats),
    Discovery(DiscoveryEvent),
    VcamStatus {
        path: String,
        active: bool,
        error: Option<String>,
    },
    Disconnected(String),
    Error(String),
}

/// Handle owned by the `eframe` application to interact with the background pipeline.
pub struct GuiSession {
    cmd_tx: mpsc::UnboundedSender<GuiCommand>,
    update_rx: Receiver<GuiUpdate>,
    frame_rx: Receiver<DecodedFrame>,
    _runtime_thread: Option<JoinHandle<()>>,
}

impl GuiSession {
    /// Spawns the background Tokio runtime thread and initializes communication channels.
    #[must_use]
    pub fn start(ctx: egui::Context) -> Self {
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel::<GuiCommand>();
        let (update_tx, update_rx) = crossbeam_channel::unbounded::<GuiUpdate>();
        let (frame_tx, frame_rx) = crossbeam_channel::bounded::<DecodedFrame>(2);

        let runtime_thread = std::thread::Builder::new()
            .name("camcraft-gui-rt".to_owned())
            .spawn(move || {
                let rt = match tokio::runtime::Builder::new_multi_thread()
                    .enable_all()
                    .worker_threads(2)
                    .build()
                {
                    Ok(rt) => rt,
                    Err(e) => {
                        let _ = update_tx.send(GuiUpdate::Error(format!(
                            "Failed to start async runtime: {e}"
                        )));
                        ctx.request_repaint();
                        return;
                    }
                };

                rt.block_on(run_session_supervisor(cmd_rx, update_tx, frame_tx, ctx));
            })
            .ok();

        Self {
            cmd_tx,
            update_rx,
            frame_rx,
            _runtime_thread: runtime_thread,
        }
    }

    /// Sends a command to the background session supervisor.
    pub fn send(&self, cmd: GuiCommand) {
        if let Err(e) = self.cmd_tx.send(cmd) {
            warn!(error = %e, "Failed to send command to GUI session supervisor");
        }
    }

    /// Drains all pending status updates.
    pub fn drain_updates(&self) -> Vec<GuiUpdate> {
        let mut updates = Vec::new();
        while let Ok(upd) = self.update_rx.try_recv() {
            updates.push(upd);
        }
        updates
    }

    /// Drains the preview frame channel and returns only the newest decoded frame.
    #[must_use]
    pub fn take_latest_frame(&self) -> Option<DecodedFrame> {
        let mut latest = None;
        while let Ok(frame) = self.frame_rx.try_recv() {
            latest = Some(frame);
        }
        latest
    }
}

impl Drop for GuiSession {
    fn drop(&mut self) {
        let _ = self.cmd_tx.send(GuiCommand::Disconnect);
    }
}

struct ActiveConnection {
    shutdown: Arc<AtomicBool>,
    decoder_running: Arc<AtomicBool>,
    decoder_tx: DecoderSender,
    decoder_thread: Option<JoinHandle<Result<(), camcraft_core::decoder::DecoderError>>>,
    kf_bridge_thread: Option<JoinHandle<()>>,
    client_task: tokio::task::JoinHandle<()>,
    sender: Option<ClientSender>,
    last_keyframe_req: Option<Instant>,
}

impl ActiveConnection {
    fn stop(mut self) {
        self.decoder_running.store(false, Ordering::Relaxed);
        self.shutdown.store(true, Ordering::Relaxed);
        self.decoder_tx.stop();
        self.client_task.abort();
        if let Some(handle) = self.decoder_thread.take() {
            let _ = handle.join();
        }
        if let Some(handle) = self.kf_bridge_thread.take() {
            let _ = handle.join();
        }
    }
}

async fn run_session_supervisor(
    mut cmd_rx: mpsc::UnboundedReceiver<GuiCommand>,
    update_tx: Sender<GuiUpdate>,
    frame_tx: Sender<DecodedFrame>,
    ctx: egui::Context,
) {
    let mut active: Option<ActiveConnection> = None;
    let (internal_tx, mut internal_rx) = mpsc::channel::<InternalEvent>(64);

    let (mdns_tx, mdns_rx) = crossbeam_channel::unbounded::<DiscoveryEvent>();
    let mut scanner = match MdnsScanner::start(mdns_tx) {
        Ok(s) => Some(s),
        Err(e) => {
            warn!(error = %e, "Failed to start mDNS Wi-Fi discovery scanner");
            None
        }
    };
    let mdns_bridge = if scanner.is_some() {
        let update_tx_mdns = update_tx.clone();
        let ctx_mdns = ctx.clone();
        std::thread::Builder::new()
            .name("camcraft-gui-mdns".to_owned())
            .spawn(move || {
                while let Ok(ev) = mdns_rx.recv() {
                    emit(&update_tx_mdns, &ctx_mdns, GuiUpdate::Discovery(ev));
                }
            })
            .ok()
    } else {
        None
    };

    loop {
        tokio::select! {
            maybe_cmd = cmd_rx.recv() => {
                let Some(cmd) = maybe_cmd else {
                    break;
                };
                match cmd {
                    GuiCommand::ConnectUsb { port, vcam_device } => {
                        if let Some(conn) = active.take() {
                            conn.stop();
                        }
                        let endpoint = format!("127.0.0.1:{port}");
                        emit(&update_tx, &ctx, GuiUpdate::Connecting {
                            mode: ConnectionMode::Usb,
                            endpoint: endpoint.clone(),
                        });

                        let fwd_res = tokio::task::spawn_blocking(move || {
                            adb::setup_adb_forward(None, port, port)
                        }).await;

                        match fwd_res {
                            Ok(Ok(device)) => {
                                info!(serial = %device.serial, model = ?device.model, "ADB forward established");
                                active = start_connection(
                                    ConnectionMode::Usb,
                                    endpoint,
                                    vcam_device,
                                    &update_tx,
                                    &frame_tx,
                                    &internal_tx,
                                    &ctx,
                                );
                            }
                            Ok(Err(e)) => {
                                emit(&update_tx, &ctx, GuiUpdate::Error(format!("ADB error: {e}")));
                            }
                            Err(e) => {
                                emit(&update_tx, &ctx, GuiUpdate::Error(format!("ADB task failed: {e}")));
                            }
                        }
                    }
                    GuiCommand::ConnectWifi { endpoint, vcam_device } => {
                        if let Some(conn) = active.take() {
                            conn.stop();
                        }
                        let normalized = normalize_endpoint(&endpoint);
                        emit(&update_tx, &ctx, GuiUpdate::Connecting {
                            mode: ConnectionMode::Wifi,
                            endpoint: normalized.clone(),
                        });
                        active = start_connection(
                            ConnectionMode::Wifi,
                            normalized,
                            vcam_device,
                            &update_tx,
                            &frame_tx,
                            &internal_tx,
                            &ctx,
                        );
                    }
                    GuiCommand::RefreshWifiDiscovery => {
                        if let Some(ref s) = scanner {
                            s.trigger_scan();
                        }
                    }
                    GuiCommand::Disconnect => {
                        if let Some(conn) = active.take() {
                            conn.stop();
                            emit(&update_tx, &ctx, GuiUpdate::Disconnected("Disconnected by user".to_owned()));
                        }
                        emit(&update_tx, &ctx, GuiUpdate::VcamStatus {
                            path: String::new(),
                            active: false,
                            error: None,
                        });
                    }
                    GuiCommand::SendControlCmd(ctrl_cmd) => {
                        if let Some(ref conn) = active
                            && let Some(ref sender) = conn.sender
                        {
                            let sender = sender.clone();
                            let update_tx_clone = update_tx.clone();
                            let ctx_clone = ctx.clone();
                            tokio::spawn(async move {
                                if let Err(e) = sender.send_cmd(ctrl_cmd).await {
                                    emit(&update_tx_clone, &ctx_clone, GuiUpdate::Error(format!(
                                        "Failed to send command: {e}"
                                    )));
                                }
                            });
                        }
                    }
                }
            }
            Some(ev) = internal_rx.recv() => {
                match ev {
                    InternalEvent::SenderReady(sender) => {
                        if let Some(ref mut conn) = active {
                            conn.sender = Some(sender);
                        }
                    }
                    InternalEvent::RequestKeyframe(reason) => {
                        if let Some(ref mut conn) = active
                            && let Some(ref sender) = conn.sender
                        {
                            let now = Instant::now();
                            let cooldown_elapsed = conn.last_keyframe_req.is_none_or(|prev| {
                                now.saturating_duration_since(prev) >= KEYFRAME_REQUEST_COOLDOWN
                            });
                            if cooldown_elapsed {
                                conn.last_keyframe_req = Some(now);
                                let sender = sender.clone();
                                info!(?reason, "Requesting IDR keyframe from Android sender");
                                tokio::spawn(async move {
                                    if let Err(e) = sender.send_cmd(ControlCmd::RequestKeyframe).await {
                                        warn!(error = %e, "Failed to send RequestKeyframe command");
                                    }
                                });
                            }
                        }
                    }
                    InternalEvent::SessionEnded => {
                        if let Some(conn) = active.take() {
                            conn.stop();
                        }
                    }
                }
            }
        }
    }

    if let Some(conn) = active.take() {
        conn.stop();
    }
    if let Some(mut s) = scanner.take() {
        s.stop();
    }
    if let Some(handle) = mdns_bridge {
        let _ = handle.join();
    }
}

enum InternalEvent {
    SenderReady(ClientSender),
    RequestKeyframe(KeyframeRequestReason),
    SessionEnded,
}

fn start_connection(
    mode: ConnectionMode,
    endpoint: String,
    vcam_device: String,
    update_tx: &Sender<GuiUpdate>,
    frame_tx: &Sender<DecodedFrame>,
    internal_tx: &mpsc::Sender<InternalEvent>,
    ctx: &egui::Context,
) -> Option<ActiveConnection> {
    let shutdown = Arc::new(AtomicBool::new(false));
    let decoder_running = Arc::new(AtomicBool::new(true));
    let (decoder_tx, decoder_rx) = DecoderSender::new_bounded();

    // Open V4L2 virtual camera if available; fall back gracefully to preview-only mode.
    let vcam_path = if vcam_device.trim().is_empty() || vcam_device.trim() == "auto" {
        find_camcraft_v4l2_device()
    } else {
        PathBuf::from(vcam_device.trim())
    };
    let vcam_display = vcam_path.display().to_string();

    let mut vcam_sink = V4l2LoopbackSink::new(vcam_path);
    let sink: Option<Box<dyn VirtualCameraSink>> = match vcam_sink.open() {
        Ok(()) => {
            if let Err(e) = vcam_sink.write_standby_frame(1920, 1080) {
                warn!(error = %e, "Failed to write initial standby frame to virtual camera");
            }
            emit(
                update_tx,
                ctx,
                GuiUpdate::VcamStatus {
                    path: vcam_display.clone(),
                    active: true,
                    error: None,
                },
            );
            Some(Box::new(vcam_sink))
        }
        Err(e) => {
            warn!(device = %vcam_display, error = %e, "Virtual camera unavailable; running in preview-only mode");
            emit(
                update_tx,
                ctx,
                GuiUpdate::VcamStatus {
                    path: vcam_display,
                    active: false,
                    error: Some(e.to_string()),
                },
            );
            None
        }
    };

    let (kf_tx, kf_rx) = crossbeam_channel::bounded::<KeyframeRequestReason>(4);
    let internal_tx_kf = internal_tx.clone();
    let kf_bridge_thread = std::thread::Builder::new()
        .name("camcraft-gui-kf".to_owned())
        .spawn(move || {
            while let Ok(reason) = kf_rx.recv() {
                let _ = internal_tx_kf.try_send(InternalEvent::RequestKeyframe(reason));
            }
        })
        .ok();

    let decoder_thread = match spawn_decoder_worker_with_recovery(
        decoder_rx,
        sink,
        Some(frame_tx.clone()),
        true,
        decoder_running.clone(),
        Some(kf_tx),
    ) {
        Ok(handle) => Some(handle),
        Err(e) => {
            if let Some(handle) = kf_bridge_thread {
                let _ = handle.join();
            }
            emit(
                update_tx,
                ctx,
                GuiUpdate::Error(format!("Failed to start video decoder: {e}")),
            );
            return None;
        }
    };

    let update_tx_c = update_tx.clone();
    let ctx_c = ctx.clone();
    let shutdown_c = shutdown.clone();
    let decoder_tx_c = decoder_tx.clone();
    let internal_tx_c = internal_tx.clone();

    let client_task = tokio::spawn(async move {
        let (event_tx, mut event_rx) = mpsc::channel::<ClientEvent>(128);
        let cfg = ClientConfig::new(endpoint.clone());
        let client_shutdown = shutdown_c.clone();

        let net_handle =
            tokio::spawn(
                async move { client::run_client_loop(cfg, event_tx, client_shutdown).await },
            );

        let mut disconnected_emitted = false;
        while let Some(event) = event_rx.recv().await {
            match event {
                ClientEvent::Connected(sender) => {
                    let _ = internal_tx_c.send(InternalEvent::SenderReady(sender)).await;
                    emit(&update_tx_c, &ctx_c, GuiUpdate::Connected(mode));
                }
                ClientEvent::Hello(hello) => {
                    emit(&update_tx_c, &ctx_c, GuiUpdate::Hello(hello));
                }
                ClientEvent::ControlAck(ack) => {
                    emit(&update_tx_c, &ctx_c, GuiUpdate::ControlAck(ack));
                }
                ClientEvent::Stats(stats) => {
                    emit(&update_tx_c, &ctx_c, GuiUpdate::Stats(stats));
                }
                ClientEvent::VideoConfig {
                    header, payload, ..
                } => {
                    if let Err(e) = decoder_tx_c.push_config(header.pts_us, payload) {
                        warn!(error = %e, "Failed to queue VIDEO_CONFIG");
                    }
                }
                ClientEvent::VideoFrame {
                    header, payload, ..
                } => {
                    match decoder_tx_c.push_frame(
                        header.pts_us,
                        header.flags.is_keyframe(),
                        payload,
                    ) {
                        Ok(true) => {}
                        Ok(false) => {
                            let _ = internal_tx_c.try_send(InternalEvent::RequestKeyframe(
                                KeyframeRequestReason::FrameDropped,
                            ));
                        }
                        Err(e) => {
                            warn!(error = %e, "Failed to queue VIDEO_FRAME");
                        }
                    }
                }
                ClientEvent::AudioConfig { .. } | ClientEvent::AudioFrame { .. } => {}
                ClientEvent::Disconnected(reason) => {
                    disconnected_emitted = true;
                    emit(
                        &update_tx_c,
                        &ctx_c,
                        GuiUpdate::Disconnected(format_disconnect_reason(&reason)),
                    );
                    break;
                }
            }
        }

        match net_handle.await {
            Ok(Ok(())) => {}
            Ok(Err(e)) => {
                error!(error = %e, "Client network loop exited with error");
                if !disconnected_emitted {
                    emit(
                        &update_tx_c,
                        &ctx_c,
                        GuiUpdate::Error(format_client_error(&e)),
                    );
                }
            }
            Err(e) => {
                error!(error = %e, "Client network task failed");
                if !disconnected_emitted {
                    emit(
                        &update_tx_c,
                        &ctx_c,
                        GuiUpdate::Error(format!("Connection task failed: {e}")),
                    );
                }
            }
        }
        let _ = internal_tx_c.send(InternalEvent::SessionEnded).await;
    });

    Some(ActiveConnection {
        shutdown,
        decoder_running,
        decoder_tx,
        decoder_thread,
        kf_bridge_thread,
        client_task,
        sender: None,
        last_keyframe_req: None,
    })
}

fn normalize_endpoint(raw: &str) -> String {
    let trimmed = raw.trim();
    if trimmed.is_empty() {
        format!("127.0.0.1:{DEFAULT_PORT}")
    } else if trimmed.contains(':') {
        trimmed.to_owned()
    } else {
        format!("{trimmed}:{DEFAULT_PORT}")
    }
}

fn format_client_error(err: &ClientError) -> String {
    if let ClientError::Io(io_err) = err {
        if io_err.kind() == std::io::ErrorKind::ConnectionRefused {
            return "Phone reached, but CamCraft Server is not running (tap 'Start Server' on Android)".to_owned();
        }
        if io_err.raw_os_error() == Some(113) {
            return "No route to phone — check phone Wi-Fi IP, same SSID, or router AP Isolation"
                .to_owned();
        }
    }
    format!("Connection failed: {err}")
}

fn format_disconnect_reason(reason: &str) -> String {
    let lower = reason.to_ascii_lowercase();
    if lower.contains("connection refused") || lower.contains("os error 111") {
        "Phone reached, but CamCraft Server is not running (tap 'Start Server' on Android)"
            .to_owned()
    } else if lower.contains("no route to host") || lower.contains("os error 113") {
        "No route to phone — check phone Wi-Fi IP, same SSID, or router AP Isolation".to_owned()
    } else {
        reason.to_owned()
    }
}

fn emit(tx: &Sender<GuiUpdate>, ctx: &egui::Context, update: GuiUpdate) {
    let _ = tx.send(update);
    ctx.request_repaint();
}

#[cfg(test)]
mod tests {
    use super::*;
    use camcraft_core::decoder::spawn_decoder_worker;

    #[test]
    fn normalize_endpoint_handles_defaults_and_ports() {
        assert_eq!(normalize_endpoint(""), format!("127.0.0.1:{DEFAULT_PORT}"));
        assert_eq!(
            normalize_endpoint("   "),
            format!("127.0.0.1:{DEFAULT_PORT}")
        );
        assert_eq!(
            normalize_endpoint("192.168.1.42"),
            format!("192.168.1.42:{DEFAULT_PORT}")
        );
        assert_eq!(normalize_endpoint("192.168.1.42:9999"), "192.168.1.42:9999");
    }

    #[test]
    fn decoder_worker_stays_running_until_stopped() {
        let (decoder_tx, decoder_rx) = DecoderSender::new_bounded();
        let decoder_running = Arc::new(AtomicBool::new(true));

        let handle = spawn_decoder_worker(decoder_rx, None, None, false, decoder_running.clone())
            .expect("spawn decoder worker");

        std::thread::sleep(std::time::Duration::from_millis(30));
        assert!(
            !handle.is_finished(),
            "decoder worker exited prematurely while running flag was true"
        );

        decoder_running.store(false, Ordering::Relaxed);
        decoder_tx.stop();

        let join_res = handle.join().expect("decoder thread panicked");
        assert!(join_res.is_ok());
    }
}
