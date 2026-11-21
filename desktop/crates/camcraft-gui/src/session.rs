//! Lean background networking, ADB forwarding, H.264 decoding, and V4L2/PipeWire
//! session supervisor for `camcraft-gui`.

use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use camcraft_core::AudioOutputSink;
use camcraft_core::adb;
use camcraft_core::client::{
    self, ClientConfig, ClientError, ClientEvent, ClientSender, ReconnectConfig, StreamStats,
};
use camcraft_core::decoder::{
    DecoderOptions, DecoderSender, KEYFRAME_REQUEST_COOLDOWN, KeyframeRequestReason,
    LIVE_DECODER_QUEUE_CAPACITY, LowLatencyWorkerOptions, PreviewFrame, PreviewRuntimeControl,
    spawn_decoder_worker_optimized,
};
use camcraft_core::discovery::{DiscoveryEvent, MdnsScanner};
use camcraft_core::updater::{self, ReleaseInfo, UpdateCheckOutcome, UpdateConfig};
use camcraft_core::vcam::{V4l2LoopbackSink, VirtualCameraSink, find_camcraft_v4l2_device};
use camcraft_core::vmic::{
    AudioLevelMetrics, AudioWorkerSender, DEFAULT_SOURCE_DESCRIPTION, VirtualMicSink,
    ensure_virtual_mic, run_audio_worker_loop,
};
use camcraft_protocol::{ControlAck, ControlCmd, ControlHello, DEFAULT_PORT};
use crossbeam_channel::{Receiver, Sender};
use eframe::egui;
use tokio::sync::mpsc;
use tracing::{error, info, warn};

const AUDIO_METER_REPAINT_MIN_INTERVAL: Duration = Duration::from_millis(66);

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

/// Laptop CPU/GPU preview scaling & cadence preset (does not affect `/dev/video*` output).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum PerformanceProfile {
    Eco,
    #[default]
    Balanced,
    Crisp,
}

impl PerformanceProfile {
    /// Short UI label for the profile.
    #[must_use]
    pub const fn label(self) -> &'static str {
        match self {
            Self::Eco => "Eco",
            Self::Balanced => "Balanced",
            Self::Crisp => "Crisp",
        }
    }

    /// Preview maximum width in pixels.
    #[must_use]
    pub const fn max_width(self) -> u32 {
        match self {
            Self::Eco => 480,
            Self::Balanced => 640,
            Self::Crisp => 960,
        }
    }

    /// Minimum interval between preview frame uploads in milliseconds.
    #[must_use]
    pub const fn min_interval_ms(self) -> u32 {
        match self {
            Self::Eco => 66,
            Self::Balanced => 42,
            Self::Crisp => 33,
        }
    }

    /// Concise human-readable description for tooltips and inspector hints.
    #[must_use]
    pub const fn description(self) -> &'static str {
        match self {
            Self::Eco => "480p • ~15 FPS preview (lowest CPU/GPU)",
            Self::Balanced => "640p • ~24 FPS preview (balanced)",
            Self::Crisp => "960p • ~30 FPS preview (sharpest)",
        }
    }
}

/// Lifecycle state of the in-app desktop updater exposed to the GUI.
#[derive(Debug, Clone, PartialEq)]
pub enum UpdateUiState {
    Idle,
    Checking,
    UpToDate,
    Available(ReleaseInfo),
    Downloading {
        version: String,
        progress: f32,
        downloaded_bytes: u64,
        total_bytes: u64,
    },
    Installing {
        version: String,
    },
    RestartRequired {
        version: String,
    },
    Failed {
        message: String,
        retry_release: Option<ReleaseInfo>,
    },
}

/// Commands sent from the UI thread to the background session worker.
#[derive(Debug, Clone)]
pub enum GuiCommand {
    ConnectUsb {
        port: u16,
        vcam_device: String,
        auto_reconnect: bool,
    },
    ConnectWifi {
        endpoint: String,
        vcam_device: String,
        auto_reconnect: bool,
    },
    RefreshWifiDiscovery,
    Disconnect,
    SendControlCmd(ControlCmd),
    SetAudioState {
        enabled: bool,
        muted: bool,
    },
    CheckForUpdates {
        silent: bool,
    },
    InstallUpdate(ReleaseInfo),
}

/// Status updates sent from the background session worker to the UI thread.
#[derive(Debug, Clone)]
pub enum GuiUpdate {
    Connecting {
        mode: ConnectionMode,
        endpoint: String,
    },
    Connected(ConnectionMode),
    Reconnecting {
        mode: ConnectionMode,
        endpoint: String,
        attempt: u32,
        reason: String,
    },
    Hello(ControlHello),
    ControlAck(ControlAck),
    Stats(StreamStats),
    Discovery(DiscoveryEvent),
    VcamStatus {
        path: String,
        active: bool,
        standby: bool,
        error: Option<String>,
    },
    VmicStatus {
        source_name: String,
        description: String,
        active: bool,
        error: Option<String>,
    },
    UpdateState(UpdateUiState),
    Disconnected(String),
    Error(String),
}

/// Handle owned by the `eframe` application to interact with the background pipeline.
pub struct GuiSession {
    cmd_tx: mpsc::UnboundedSender<GuiCommand>,
    update_rx: Receiver<GuiUpdate>,
    frame_rx: Receiver<PreviewFrame>,
    preview_recycle_tx: Sender<Vec<u8>>,
    audio_level_rx: Receiver<AudioLevelMetrics>,
    preview_ctrl: PreviewRuntimeControl,
    user_preview_enabled: AtomicBool,
    window_hidden: AtomicBool,
    audio_meter_active: Arc<AtomicBool>,
    _runtime_thread: Option<JoinHandle<()>>,
}

impl GuiSession {
    /// Spawns the single-threaded background Tokio runtime and initializes communication channels.
    #[must_use]
    pub fn start(ctx: egui::Context) -> Self {
        let (cmd_tx, cmd_rx) = mpsc::unbounded_channel::<GuiCommand>();
        let (update_tx, update_rx) = crossbeam_channel::unbounded::<GuiUpdate>();
        let (frame_tx, frame_rx) = crossbeam_channel::bounded::<PreviewFrame>(1);
        let (preview_recycle_tx, preview_recycle_rx) = crossbeam_channel::bounded::<Vec<u8>>(2);
        let (audio_level_tx, audio_level_rx) = crossbeam_channel::bounded::<AudioLevelMetrics>(4);

        let default_profile = PerformanceProfile::default();
        let preview_ctrl = PreviewRuntimeControl::new(
            true,
            default_profile.max_width(),
            default_profile.min_interval_ms(),
        );
        let preview_ctrl_rt = preview_ctrl.clone();
        let audio_meter_active = Arc::new(AtomicBool::new(false));
        let audio_meter_active_rt = audio_meter_active.clone();

        let runtime_thread = std::thread::Builder::new()
            .name("camcraft-gui-rt".to_owned())
            .spawn(move || {
                let rt = match tokio::runtime::Builder::new_current_thread()
                    .enable_all()
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

                rt.block_on(run_session_supervisor(
                    cmd_rx,
                    update_tx,
                    frame_tx,
                    preview_recycle_rx,
                    preview_ctrl_rt,
                    audio_level_tx,
                    audio_meter_active_rt,
                    ctx,
                ));
            })
            .ok();

        Self {
            cmd_tx,
            update_rx,
            frame_rx,
            preview_recycle_tx,
            audio_level_rx,
            preview_ctrl,
            user_preview_enabled: AtomicBool::new(true),
            window_hidden: AtomicBool::new(false),
            audio_meter_active,
            _runtime_thread: runtime_thread,
        }
    }

    /// Updates the live preview scaling width and upload cadence without restarting the decoder.
    pub fn set_performance_profile(&self, profile: PerformanceProfile) {
        self.preview_ctrl
            .max_width
            .store(profile.max_width(), Ordering::Relaxed);
        self.preview_ctrl
            .min_interval_ms
            .store(profile.min_interval_ms(), Ordering::Relaxed);
    }

    /// Enables or pauses downscaled RGBA GUI preview generation without affecting `/dev/video*`.
    pub fn set_preview_enabled(&self, enabled: bool) {
        self.user_preview_enabled.store(enabled, Ordering::Relaxed);
        self.sync_effective_preview();
    }

    /// Temporarily suppresses decoder preview output while the window is minimized without
    /// altering the user's explicit preview preference, restoring it when un-minimized.
    pub fn set_window_hidden(&self, hidden: bool) {
        let prev = self.window_hidden.swap(hidden, Ordering::Relaxed);
        if prev != hidden {
            self.sync_effective_preview();
        }
    }

    /// Returns whether decoder preview output is currently active.
    #[cfg(test)]
    #[must_use]
    pub fn is_effective_preview_enabled(&self) -> bool {
        self.preview_ctrl.enabled.load(Ordering::Relaxed)
    }

    /// Returns the user's explicit preview toggle preference (unaffected by minimization).
    #[cfg(test)]
    #[must_use]
    pub fn is_user_preview_enabled(&self) -> bool {
        self.user_preview_enabled.load(Ordering::Relaxed)
    }

    fn sync_effective_preview(&self) {
        let effective = self.user_preview_enabled.load(Ordering::Relaxed)
            && !self.window_hidden.load(Ordering::Relaxed);
        self.preview_ctrl
            .enabled
            .store(effective, Ordering::Relaxed);
        if !effective {
            while let Ok(frame) = self.frame_rx.try_recv() {
                self.recycle_frame_buffer(frame.rgba);
            }
        }
    }

    /// Marks whether the Audio VU meter is currently visible and unmuted on screen.
    pub fn set_audio_meter_active(&self, active: bool) {
        self.audio_meter_active.store(active, Ordering::Relaxed);
    }

    /// Returns a spent preview RGBA buffer to the decoder worker for zero-allocation reuse.
    pub fn recycle_frame_buffer(&self, mut buf: Vec<u8>) {
        if buf.capacity() > 0 {
            buf.clear();
            let _ = self.preview_recycle_tx.try_send(buf);
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

    /// Drains the preview frame channel and returns only the newest downscaled preview frame,
    /// recycling any superseded frames immediately.
    #[must_use]
    pub fn take_latest_frame(&self) -> Option<PreviewFrame> {
        let mut latest: Option<PreviewFrame> = None;
        while let Ok(frame) = self.frame_rx.try_recv() {
            if let Some(superseded) = latest.replace(frame) {
                self.recycle_frame_buffer(superseded.rgba);
            }
        }
        latest
    }

    /// Drains the audio level channel and returns only the newest volume reading.
    #[must_use]
    pub fn take_latest_audio_level(&self) -> Option<AudioLevelMetrics> {
        let mut latest = None;
        while let Ok(level) = self.audio_level_rx.try_recv() {
            latest = Some(level);
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
    audio_running: Arc<AtomicBool>,
    audio_tx: AudioWorkerSender,
    audio_thread: Option<JoinHandle<()>>,
    client_task: tokio::task::JoinHandle<()>,
    sender: Option<ClientSender>,
    last_keyframe_req: Option<Instant>,
}

impl ActiveConnection {
    fn stop(mut self) {
        self.decoder_running.store(false, Ordering::Relaxed);
        self.audio_running.store(false, Ordering::Relaxed);
        self.shutdown.store(true, Ordering::Relaxed);
        self.decoder_tx.stop();
        self.audio_tx.stop();
        self.client_task.abort();
        if let Some(handle) = self.decoder_thread.take() {
            let _ = handle.join();
        }
        if let Some(handle) = self.audio_thread.take() {
            let _ = handle.join();
        }
    }
}

#[allow(clippy::too_many_arguments)]
async fn run_session_supervisor(
    mut cmd_rx: mpsc::UnboundedReceiver<GuiCommand>,
    update_tx: Sender<GuiUpdate>,
    frame_tx: Sender<PreviewFrame>,
    preview_recycle_rx: Receiver<Vec<u8>>,
    preview_ctrl: PreviewRuntimeControl,
    audio_level_tx: Sender<AudioLevelMetrics>,
    audio_meter_active: Arc<AtomicBool>,
    ctx: egui::Context,
) {
    let mut active: Option<ActiveConnection> = None;
    let (internal_tx, mut internal_rx) = mpsc::channel::<InternalEvent>(64);

    let update_tx_mdns = update_tx.clone();
    let ctx_mdns = ctx.clone();
    let mut scanner = match MdnsScanner::start_with_handler(move |ev| {
        emit(&update_tx_mdns, &ctx_mdns, GuiUpdate::Discovery(ev));
    }) {
        Ok(s) => Some(s),
        Err(e) => {
            warn!(error = %e, "Failed to start mDNS Wi-Fi discovery scanner");
            None
        }
    };

    // Delay silent startup update check slightly so UI paints and mDNS starts first.
    let update_tx_startup = update_tx.clone();
    let ctx_startup = ctx.clone();
    tokio::spawn(async move {
        tokio::time::sleep(Duration::from_secs(2)).await;
        run_update_check(true, update_tx_startup, ctx_startup).await;
    });

    loop {
        tokio::select! {
            maybe_cmd = cmd_rx.recv() => {
                let Some(cmd) = maybe_cmd else {
                    break;
                };
                match cmd {
                    GuiCommand::ConnectUsb { port, vcam_device, auto_reconnect } => {
                        if let Some(conn) = active.take() {
                            conn.stop();
                        }
                        if let Some(ref s) = scanner {
                            s.set_active_queries_paused(false);
                        }
                        let endpoint = format!("127.0.0.1:{port}");
                        emit(&update_tx, &ctx, GuiUpdate::Connecting {
                            mode: ConnectionMode::Usb,
                            endpoint: endpoint.clone(),
                        });

                        let fwd_res = tokio::task::spawn_blocking(move || {
                            adb::setup_adb_forward(None, port, port)
                        }).await;

                        let channels = SessionChannels {
                            update_tx: &update_tx,
                            frame_tx: &frame_tx,
                            preview_recycle_rx: &preview_recycle_rx,
                            preview_ctrl: &preview_ctrl,
                            audio_level_tx: &audio_level_tx,
                            audio_meter_active: &audio_meter_active,
                            internal_tx: &internal_tx,
                            ctx: &ctx,
                        };

                        match fwd_res {
                            Ok(Ok(device)) => {
                                info!(serial = %device.serial, model = ?device.model, "ADB forward established");
                                active = start_connection(
                                    ConnectionMode::Usb,
                                    endpoint,
                                    port,
                                    vcam_device,
                                    auto_reconnect,
                                    &channels,
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
                    GuiCommand::ConnectWifi { endpoint, vcam_device, auto_reconnect } => {
                        if let Some(conn) = active.take() {
                            conn.stop();
                        }
                        if let Some(ref s) = scanner {
                            s.set_active_queries_paused(false);
                        }
                        let normalized = normalize_endpoint(&endpoint);
                        emit(&update_tx, &ctx, GuiUpdate::Connecting {
                            mode: ConnectionMode::Wifi,
                            endpoint: normalized.clone(),
                        });
                        let channels = SessionChannels {
                            update_tx: &update_tx,
                            frame_tx: &frame_tx,
                            preview_recycle_rx: &preview_recycle_rx,
                            preview_ctrl: &preview_ctrl,
                            audio_level_tx: &audio_level_tx,
                            audio_meter_active: &audio_meter_active,
                            internal_tx: &internal_tx,
                            ctx: &ctx,
                        };
                        active = start_connection(
                            ConnectionMode::Wifi,
                            normalized,
                            DEFAULT_PORT,
                            vcam_device,
                            auto_reconnect,
                            &channels,
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
                        if let Some(ref s) = scanner {
                            s.set_active_queries_paused(false);
                        }
                        emit(&update_tx, &ctx, GuiUpdate::VcamStatus {
                            path: String::new(),
                            active: false,
                            standby: false,
                            error: None,
                        });
                        emit(&update_tx, &ctx, GuiUpdate::VmicStatus {
                            source_name: String::new(),
                            description: DEFAULT_SOURCE_DESCRIPTION.to_owned(),
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
                    GuiCommand::SetAudioState { enabled, muted } => {
                        if let Some(ref conn) = active {
                            conn.audio_tx.set_state(enabled, muted);
                            if let Some(ref sender) = conn.sender {
                                let sender = sender.clone();
                                let update_tx_clone = update_tx.clone();
                                let ctx_clone = ctx.clone();
                                tokio::spawn(async move {
                                    if let Err(e) = sender
                                        .send_cmd(ControlCmd::SetAudioState { enabled, muted })
                                        .await
                                    {
                                        emit(
                                            &update_tx_clone,
                                            &ctx_clone,
                                            GuiUpdate::Error(format!(
                                                "Failed to send audio state command: {e}"
                                            )),
                                        );
                                    }
                                });
                            }
                        }
                    }
                    GuiCommand::CheckForUpdates { silent } => {
                        let update_tx_c = update_tx.clone();
                        let ctx_c = ctx.clone();
                        tokio::spawn(async move {
                            run_update_check(silent, update_tx_c, ctx_c).await;
                        });
                    }
                    GuiCommand::InstallUpdate(release) => {
                        let update_tx_c = update_tx.clone();
                        let ctx_c = ctx.clone();
                        tokio::spawn(async move {
                            run_update_install(release, update_tx_c, ctx_c).await;
                        });
                    }
                }
            }
            Some(ev) = internal_rx.recv() => {
                match ev {
                    InternalEvent::SenderReady(sender) => {
                        if let Some(ref mut conn) = active {
                            conn.sender = Some(sender);
                        }
                        if let Some(ref s) = scanner {
                            s.set_active_queries_paused(true);
                        }
                    }
                    InternalEvent::ConnectionLost => {
                        if let Some(ref mut conn) = active {
                            conn.sender = None;
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
                        if let Some(ref s) = scanner {
                            s.set_active_queries_paused(false);
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
}

enum InternalEvent {
    SenderReady(ClientSender),
    ConnectionLost,
    RequestKeyframe(KeyframeRequestReason),
    SessionEnded,
}

struct SessionChannels<'a> {
    update_tx: &'a Sender<GuiUpdate>,
    frame_tx: &'a Sender<PreviewFrame>,
    preview_recycle_rx: &'a Receiver<Vec<u8>>,
    preview_ctrl: &'a PreviewRuntimeControl,
    audio_level_tx: &'a Sender<AudioLevelMetrics>,
    audio_meter_active: &'a Arc<AtomicBool>,
    internal_tx: &'a mpsc::Sender<InternalEvent>,
    ctx: &'a egui::Context,
}

fn start_connection(
    mode: ConnectionMode,
    endpoint: String,
    port: u16,
    vcam_device: String,
    auto_reconnect: bool,
    channels: &SessionChannels<'_>,
) -> Option<ActiveConnection> {
    let update_tx = channels.update_tx;
    let frame_tx = channels.frame_tx;
    let preview_recycle_rx = channels.preview_recycle_rx;
    let preview_ctrl = channels.preview_ctrl;
    let audio_level_tx = channels.audio_level_tx;
    let audio_meter_active = channels.audio_meter_active;
    let internal_tx = channels.internal_tx;
    let ctx = channels.ctx;

    let shutdown = Arc::new(AtomicBool::new(false));
    let decoder_running = Arc::new(AtomicBool::new(true));
    let (decoder_tx, decoder_rx) = DecoderSender::new_with_capacity(LIVE_DECODER_QUEUE_CAPACITY);

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
                    standby: false,
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
                    path: vcam_display.clone(),
                    active: false,
                    standby: false,
                    error: Some(e.to_string()),
                },
            );
            None
        }
    };
    let vcam_opened = sink.is_some();

    let ctx_preview = ctx.clone();
    let internal_tx_kf = internal_tx.clone();
    let worker_opts = LowLatencyWorkerOptions {
        preview_tx: Some(frame_tx.clone()),
        preview_recycle_rx: Some(preview_recycle_rx.clone()),
        preview_ctrl: preview_ctrl.clone(),
        on_preview_pushed: Some(Arc::new(move || ctx_preview.request_repaint())),
        on_keyframe_request: Some(Arc::new(move |reason| {
            let _ = internal_tx_kf.try_send(InternalEvent::RequestKeyframe(reason));
        })),
        decoder_options: DecoderOptions::default(),
    };

    let decoder_thread = match spawn_decoder_worker_optimized(
        decoder_rx,
        sink,
        worker_opts,
        decoder_running.clone(),
    ) {
        Ok(handle) => Some(handle),
        Err(e) => {
            emit(
                update_tx,
                ctx,
                GuiUpdate::Error(format!("Failed to start video decoder: {e}")),
            );
            return None;
        }
    };

    let audio_running = Arc::new(AtomicBool::new(true));
    let audio_enabled = Arc::new(AtomicBool::new(true));
    let audio_muted = Arc::new(AtomicBool::new(false));
    let (audio_tx, audio_rx) =
        AudioWorkerSender::new_bounded(audio_enabled.clone(), audio_muted.clone());

    let audio_running_c = audio_running.clone();
    let audio_level_tx_c = audio_level_tx.clone();
    let update_tx_vmic = update_tx.clone();
    let ctx_vmic = ctx.clone();
    let audio_thread = match std::thread::Builder::new()
        .name("camcraft-vmic".to_owned())
        .spawn(move || {
            let sink: Option<Box<dyn AudioOutputSink>> = match ensure_virtual_mic() {
                Ok(info) => {
                    emit(
                        &update_tx_vmic,
                        &ctx_vmic,
                        GuiUpdate::VmicStatus {
                            source_name: info.source_name.clone(),
                            description: info.description.clone(),
                            active: true,
                            error: None,
                        },
                    );
                    Some(Box::new(VirtualMicSink::from_info(&info)))
                }
                Err(e) => {
                    warn!(error = %e, "PipeWire virtual microphone unavailable; running in meter-only audio mode");
                    emit(
                        &update_tx_vmic,
                        &ctx_vmic,
                        GuiUpdate::VmicStatus {
                            source_name: String::new(),
                            description: DEFAULT_SOURCE_DESCRIPTION.to_owned(),
                            active: false,
                            error: Some(e.to_string()),
                        },
                    );
                    None
                }
            };

            run_audio_worker_loop(
                audio_rx,
                sink,
                Some(audio_level_tx_c),
                audio_running_c,
                audio_enabled,
                audio_muted,
            );
        }) {
        Ok(handle) => Some(handle),
        Err(e) => {
            warn!(error = %e, "Failed to spawn camcraft-vmic audio worker thread");
            None
        }
    };

    let update_tx_c = update_tx.clone();
    let ctx_c = ctx.clone();
    let shutdown_c = shutdown.clone();
    let decoder_tx_c = decoder_tx.clone();
    let audio_tx_c = audio_tx.clone();
    let audio_meter_active_c = audio_meter_active.clone();
    let internal_tx_c = internal_tx.clone();
    let endpoint_c = endpoint.clone();
    let vcam_display_c = vcam_display.clone();

    let client_task = tokio::spawn(async move {
        let (event_tx, mut event_rx) = mpsc::channel::<ClientEvent>(128);
        let cfg = ClientConfig::new(endpoint_c.clone());
        let client_shutdown = shutdown_c.clone();

        let net_handle = tokio::spawn(async move {
            if auto_reconnect {
                client::run_client_with_reconnect(
                    cfg,
                    ReconnectConfig::default(),
                    event_tx,
                    client_shutdown,
                    move |_attempt| async move {
                        if mode == ConnectionMode::Usb {
                            tokio::task::spawn_blocking(move || {
                                adb::setup_adb_forward(None, port, port)
                            })
                            .await
                            .map_err(|e| format!("ADB forward task failed: {e}"))?
                            .map(|_| ())
                            .map_err(|e| format!("ADB error: {e}"))
                        } else {
                            Ok(())
                        }
                    },
                )
                .await
            } else {
                client::run_client_loop(cfg, event_tx, client_shutdown).await
            }
        });

        let mut disconnected_emitted = false;
        let mut last_audio_repaint: Option<Instant> = None;

        while let Some(event) = event_rx.recv().await {
            match event {
                ClientEvent::Connected(sender) => {
                    let _ = internal_tx_c.send(InternalEvent::SenderReady(sender)).await;
                    if vcam_opened {
                        emit(
                            &update_tx_c,
                            &ctx_c,
                            GuiUpdate::VcamStatus {
                                path: vcam_display_c.clone(),
                                active: true,
                                standby: false,
                                error: None,
                            },
                        );
                    }
                    emit(&update_tx_c, &ctx_c, GuiUpdate::Connected(mode));
                }
                ClientEvent::Reconnecting {
                    attempt, reason, ..
                } => {
                    let _ = internal_tx_c.send(InternalEvent::ConnectionLost).await;
                    decoder_tx_c.enter_standby();
                    if vcam_opened {
                        emit(
                            &update_tx_c,
                            &ctx_c,
                            GuiUpdate::VcamStatus {
                                path: vcam_display_c.clone(),
                                active: true,
                                standby: true,
                                error: None,
                            },
                        );
                    }
                    emit(
                        &update_tx_c,
                        &ctx_c,
                        GuiUpdate::Reconnecting {
                            mode,
                            endpoint: endpoint_c.clone(),
                            attempt,
                            reason: format_disconnect_reason(&reason),
                        },
                    );
                }
                ClientEvent::Hello(hello) => {
                    audio_tx_c.set_state(
                        hello.active_state.audio_enabled,
                        hello.active_state.audio_muted,
                    );
                    emit(&update_tx_c, &ctx_c, GuiUpdate::Hello(hello));
                }
                ClientEvent::ControlAck(ack) => {
                    if let Some(ref st) = ack.active_state {
                        audio_tx_c.set_state(st.audio_enabled, st.audio_muted);
                    }
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
                    match decoder_tx_c.push_frame_with_flags(header.pts_us, header.flags, payload) {
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
                ClientEvent::AudioConfig { config, .. } => {
                    if let Err(e) = audio_tx_c.push_config(config) {
                        warn!(error = %e, "Failed to queue AUDIO_CONFIG");
                    }
                }
                ClientEvent::AudioFrame {
                    header, payload, ..
                } => {
                    if let Err(e) = audio_tx_c.push_frame(header.pts_us, payload) {
                        warn!(error = %e, "Failed to queue AUDIO_FRAME");
                    } else if audio_meter_active_c.load(Ordering::Relaxed)
                        && last_audio_repaint
                            .is_none_or(|prev| prev.elapsed() >= AUDIO_METER_REPAINT_MIN_INTERVAL)
                    {
                        last_audio_repaint = Some(Instant::now());
                        ctx_c.request_repaint();
                    }
                }
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
        audio_running,
        audio_tx,
        audio_thread,
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
    match err {
        ClientError::Io(io_err) => {
            if io_err.kind() == std::io::ErrorKind::ConnectionRefused {
                return "Phone reached, but CamCraft Server is not running (tap 'Start Server' on Android)".to_owned();
            }
            if io_err.raw_os_error() == Some(113) {
                return "No route to phone — check phone Wi-Fi IP, same SSID, or router AP Isolation"
                    .to_owned();
            }
        }
        ClientError::HeartbeatTimeout(dur) => {
            return format!(
                "Heartbeat timed out after {:.1}s — phone unreachable or app suspended",
                dur.as_secs_f64()
            );
        }
        ClientError::ConnectionClosed => {
            return "Connection closed by Android sender".to_owned();
        }
        _ => {}
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
    } else if lower.contains("timed out") {
        "Connection timed out — waiting for phone to respond".to_owned()
    } else {
        reason.to_owned()
    }
}

fn emit(tx: &Sender<GuiUpdate>, ctx: &egui::Context, update: GuiUpdate) {
    let _ = tx.send(update);
    ctx.request_repaint();
}

async fn run_update_check(silent: bool, update_tx: Sender<GuiUpdate>, ctx: egui::Context) {
    if !silent {
        emit(
            &update_tx,
            &ctx,
            GuiUpdate::UpdateState(UpdateUiState::Checking),
        );
    }
    let result =
        tokio::task::spawn_blocking(|| updater::check_for_update(&UpdateConfig::default())).await;
    match result {
        Ok(Ok(UpdateCheckOutcome::UpdateAvailable(info))) => {
            emit(
                &update_tx,
                &ctx,
                GuiUpdate::UpdateState(UpdateUiState::Available(info)),
            );
        }
        Ok(Ok(UpdateCheckOutcome::UpToDate { .. })) => {
            if !silent {
                emit(
                    &update_tx,
                    &ctx,
                    GuiUpdate::UpdateState(UpdateUiState::UpToDate),
                );
            }
        }
        Ok(Err(err)) => {
            if silent {
                tracing::debug!(error = %err, "Silent startup update check did not complete");
            } else {
                emit(
                    &update_tx,
                    &ctx,
                    GuiUpdate::UpdateState(UpdateUiState::Failed {
                        message: err.to_string(),
                        retry_release: None,
                    }),
                );
            }
        }
        Err(join_err) => {
            if !silent {
                emit(
                    &update_tx,
                    &ctx,
                    GuiUpdate::UpdateState(UpdateUiState::Failed {
                        message: format!("Update check task failed: {join_err}"),
                        retry_release: None,
                    }),
                );
            }
        }
    }
}

async fn run_update_install(
    release: ReleaseInfo,
    update_tx: Sender<GuiUpdate>,
    ctx: egui::Context,
) {
    let version = release.version.clone();
    emit(
        &update_tx,
        &ctx,
        GuiUpdate::UpdateState(UpdateUiState::Downloading {
            version: version.clone(),
            progress: 0.0,
            downloaded_bytes: 0,
            total_bytes: release.asset_size,
        }),
    );

    let release_for_task = release.clone();
    let update_tx_prog = update_tx.clone();
    let ctx_prog = ctx.clone();
    let version_prog = version.clone();
    let res = tokio::task::spawn_blocking(move || {
        let mut last_pct: u32 = 0;
        let mut last_emit = Instant::now();
        let mut installing_sent = false;
        updater::download_and_install_update(&release_for_task, "camcraft-gui", |prog| {
            let total = prog.total_bytes.max(1);
            let frac = (prog.downloaded_bytes as f32 / total as f32).clamp(0.0, 1.0);
            let pct = (frac * 100.0) as u32;
            if prog.total_bytes > 0 && prog.downloaded_bytes >= prog.total_bytes {
                if !installing_sent {
                    installing_sent = true;
                    emit(
                        &update_tx_prog,
                        &ctx_prog,
                        GuiUpdate::UpdateState(UpdateUiState::Installing {
                            version: version_prog.clone(),
                        }),
                    );
                }
                return;
            }
            if pct > last_pct || last_emit.elapsed() >= Duration::from_millis(100) {
                last_pct = pct;
                last_emit = Instant::now();
                emit(
                    &update_tx_prog,
                    &ctx_prog,
                    GuiUpdate::UpdateState(UpdateUiState::Downloading {
                        version: version_prog.clone(),
                        progress: frac,
                        downloaded_bytes: prog.downloaded_bytes,
                        total_bytes: prog.total_bytes,
                    }),
                );
            }
        })
    })
    .await;

    match res {
        Ok(Ok(outcome)) => {
            emit(
                &update_tx,
                &ctx,
                GuiUpdate::UpdateState(UpdateUiState::RestartRequired {
                    version: outcome.version,
                }),
            );
        }
        Ok(Err(err)) => {
            emit(
                &update_tx,
                &ctx,
                GuiUpdate::UpdateState(UpdateUiState::Failed {
                    message: err.to_string(),
                    retry_release: Some(release),
                }),
            );
        }
        Err(join_err) => {
            emit(
                &update_tx,
                &ctx,
                GuiUpdate::UpdateState(UpdateUiState::Failed {
                    message: format!("Update install task failed: {join_err}"),
                    retry_release: Some(release),
                }),
            );
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use camcraft_core::decoder::spawn_decoder_worker;

    fn make_test_session_handle() -> (GuiSession, Receiver<Vec<u8>>, Receiver<PreviewFrame>) {
        let (cmd_tx, _cmd_rx) = mpsc::unbounded_channel();
        let (_update_tx, update_rx) = crossbeam_channel::unbounded();
        let (frame_tx, frame_rx) = crossbeam_channel::bounded(1);
        let (preview_recycle_tx, preview_recycle_rx) = crossbeam_channel::bounded(2);
        let (_audio_level_tx, audio_level_rx) = crossbeam_channel::bounded(4);
        let default_profile = PerformanceProfile::default();
        let preview_ctrl = PreviewRuntimeControl::new(
            true,
            default_profile.max_width(),
            default_profile.min_interval_ms(),
        );
        let session = GuiSession {
            cmd_tx,
            update_rx,
            frame_rx: frame_rx.clone(),
            preview_recycle_tx,
            audio_level_rx,
            preview_ctrl,
            user_preview_enabled: AtomicBool::new(true),
            window_hidden: AtomicBool::new(false),
            audio_meter_active: Arc::new(AtomicBool::new(false)),
            _runtime_thread: None,
        };
        drop(frame_tx);
        (session, preview_recycle_rx, frame_rx)
    }

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
    fn performance_profile_matches_specs() {
        assert_eq!(PerformanceProfile::default(), PerformanceProfile::Balanced);
        assert_eq!(PerformanceProfile::Eco.max_width(), 480);
        assert_eq!(PerformanceProfile::Eco.min_interval_ms(), 66);
        assert_eq!(PerformanceProfile::Balanced.max_width(), 640);
        assert_eq!(PerformanceProfile::Balanced.min_interval_ms(), 42);
        assert_eq!(PerformanceProfile::Crisp.max_width(), 960);
        assert_eq!(PerformanceProfile::Crisp.min_interval_ms(), 33);
    }

    #[test]
    fn window_hidden_suppresses_and_restores_without_mutating_user_toggle() {
        let (session, _recycle_rx, _frame_rx) = make_test_session_handle();
        assert!(session.is_user_preview_enabled());
        assert!(session.is_effective_preview_enabled());

        // Minimizing suppresses preview output while preserving user toggle
        session.set_window_hidden(true);
        assert!(session.is_user_preview_enabled());
        assert!(!session.is_effective_preview_enabled());

        // Restoring un-minimized window restores preview output
        session.set_window_hidden(false);
        assert!(session.is_user_preview_enabled());
        assert!(session.is_effective_preview_enabled());

        // If user turns preview off explicitly, un-minimizing keeps it off
        session.set_preview_enabled(false);
        session.set_window_hidden(true);
        session.set_window_hidden(false);
        assert!(!session.is_user_preview_enabled());
        assert!(!session.is_effective_preview_enabled());
    }

    #[test]
    fn recycle_frame_buffer_returns_cleared_buffer_with_capacity() {
        let (session, recycle_rx, _frame_rx) = make_test_session_handle();
        let buf = vec![0xAA; 1024];
        session.recycle_frame_buffer(buf);
        let recycled = recycle_rx
            .try_recv()
            .expect("recycled buffer should arrive");
        assert!(recycled.is_empty());
        assert!(recycled.capacity() >= 1024);
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
