//! Native Fedora desktop GUI for CamCraft (ArtCraft Studio Dark Theme).

mod session;

use std::time::{Duration, Instant};

use anyhow::{Result, anyhow};
use camcraft_core::client::StreamStats;
use camcraft_core::discovery::{DiscoveredDevice, DiscoveryEvent};
use camcraft_core::vmic::{AudioLevelMetrics, DEFAULT_SOURCE_DESCRIPTION};
use camcraft_protocol::{ActiveCameraState, CameraInfo, ControlCmd, ControlHello, DEFAULT_PORT};
use eframe::egui;
use session::{ConnectionMode, GuiCommand, GuiSession, GuiUpdate};
use tracing::info;

const COLOR_BG_CARBON: egui::Color32 = egui::Color32::from_rgb(0x11, 0x13, 0x18);
const COLOR_SURFACE_CARD: egui::Color32 = egui::Color32::from_rgb(0x1C, 0x20, 0x26);
const COLOR_SURFACE_ELEVATED: egui::Color32 = egui::Color32::from_rgb(0x26, 0x2B, 0x33);
const COLOR_ACCENT_BLUE: egui::Color32 = egui::Color32::from_rgb(0x3B, 0x82, 0xF6);
const COLOR_STATUS_EMERALD: egui::Color32 = egui::Color32::from_rgb(0x10, 0xB9, 0x81);
const COLOR_STATUS_AMBER: egui::Color32 = egui::Color32::from_rgb(0xF5, 0x9E, 0x0B);
const COLOR_STATUS_ROSE: egui::Color32 = egui::Color32::from_rgb(0xEF, 0x44, 0x44);
const COLOR_TEXT_PRIMARY: egui::Color32 = egui::Color32::from_rgb(0xF3, 0xF4, 0xF6);
const COLOR_TEXT_MUTED: egui::Color32 = egui::Color32::from_rgb(0x9C, 0xA3, 0xAF);

const DEBOUNCE_DURATION: Duration = Duration::from_millis(50);

const STANDARD_RESOLUTIONS: &[(u32, u32, &str)] = &[
    (1280, 720, "1280x720 (720p)"),
    (1920, 1080, "1920x1080 (1080p)"),
    (3840, 2160, "3840x2160 (4K UHD)"),
];

fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    info!(
        protocol_version = camcraft_protocol::PROTOCOL_VERSION,
        "Launching CamCraft GUI"
    );

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("CamCraft — ArtCraft Studio")
            .with_inner_size([1240.0, 760.0])
            .with_min_inner_size([960.0, 600.0]),
        ..Default::default()
    };

    eframe::run_native(
        "CamCraft",
        options,
        Box::new(|cc| Ok(Box::new(CamCraftApp::new(&cc.egui_ctx)))),
    )
    .map_err(|err| anyhow!("failed to run CamCraft GUI: {err}"))
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum ConnectionStatus {
    Disconnected,
    Connecting(ConnectionMode),
    Connected(ConnectionMode),
    Reconnecting { mode: ConnectionMode, attempt: u32 },
}

struct CamCraftApp {
    session: GuiSession,
    status: ConnectionStatus,
    status_message: Option<String>,
    device_name: Option<String>,
    cameras: Vec<CameraInfo>,

    // Connection settings
    vcam_device: String,
    vcam_active: bool,
    vcam_standby: bool,
    auto_reconnect: bool,
    wifi_endpoint: String,
    discovered_devices: Vec<DiscoveredDevice>,

    // Stream & camera controls (synchronized with ActiveCameraState)
    selected_camera_id: String,
    selected_width: u32,
    selected_height: u32,
    selected_fps: u32,
    bitrate_mbps: u32,

    zoom_ratio: f32,
    exposure_ev: f32,
    autofocus: bool,
    torch_on: bool,
    mirror_horizontal: bool,
    rotation_deg: i32,

    // Audio forwarding & virtual microphone state
    audio_enabled: bool,
    audio_muted: bool,
    vmic_active: bool,
    vmic_name: String,
    vmic_error: Option<String>,
    audio_level: AudioLevelMetrics,
    smoothed_level_norm: f32,
    peak_hold_norm: f32,

    // 50ms debounce tracking for live image adjustments
    params_dirty: bool,
    last_params_edit: Instant,

    // Live preview & telemetry
    preview_enabled: bool,
    preview_texture: Option<egui::TextureHandle>,
    frame_width: u32,
    frame_height: u32,
    decode_time_ms: f32,
    stats: StreamStats,
}

impl CamCraftApp {
    fn new(ctx: &egui::Context) -> Self {
        apply_artcraft_dark_theme(ctx);
        let session = GuiSession::start(ctx.clone());

        Self {
            session,
            status: ConnectionStatus::Disconnected,
            status_message: None,
            device_name: None,
            cameras: Vec::new(),
            vcam_device: "/dev/video10".to_owned(),
            vcam_active: false,
            vcam_standby: false,
            auto_reconnect: true,
            wifi_endpoint: String::new(),
            discovered_devices: Vec::new(),
            selected_camera_id: "0".to_owned(),
            selected_width: 1920,
            selected_height: 1080,
            selected_fps: 60,
            bitrate_mbps: 12,
            zoom_ratio: 1.0,
            exposure_ev: 0.0,
            autofocus: true,
            torch_on: false,
            mirror_horizontal: false,
            rotation_deg: 0,
            audio_enabled: true,
            audio_muted: false,
            vmic_active: false,
            vmic_name: DEFAULT_SOURCE_DESCRIPTION.to_owned(),
            vmic_error: None,
            audio_level: AudioLevelMetrics::silence(false),
            smoothed_level_norm: 0.0,
            peak_hold_norm: 0.0,
            params_dirty: false,
            last_params_edit: Instant::now(),
            preview_enabled: true,
            preview_texture: None,
            frame_width: 0,
            frame_height: 0,
            decode_time_ms: 0.0,
            stats: StreamStats::default(),
        }
    }

    fn process_updates(&mut self, ctx: &egui::Context) {
        for update in self.session.drain_updates() {
            match update {
                GuiUpdate::Connecting { mode, endpoint } => {
                    self.status = ConnectionStatus::Connecting(mode);
                    self.vcam_standby = false;
                    self.status_message = Some(format!("Connecting to {endpoint}..."));
                }
                GuiUpdate::Connected(mode) => {
                    self.status = ConnectionStatus::Connected(mode);
                    self.vcam_standby = false;
                    self.status_message = None;
                }
                GuiUpdate::Reconnecting {
                    mode,
                    endpoint,
                    attempt,
                    reason,
                } => {
                    self.status = ConnectionStatus::Reconnecting { mode, attempt };
                    self.stats = StreamStats::default();
                    self.decode_time_ms = 0.0;
                    self.audio_level = AudioLevelMetrics::silence(self.audio_muted);
                    self.smoothed_level_norm = 0.0;
                    self.peak_hold_norm = 0.0;
                    self.status_message = Some(format!(
                        "Connection lost ({reason}). Reconnecting to {endpoint} (attempt #{attempt})..."
                    ));
                }
                GuiUpdate::Hello(hello) => {
                    self.apply_hello(hello);
                }
                GuiUpdate::ControlAck(ack) => {
                    if let Some(state) = ack.active_state {
                        if ack.ok && !self.params_dirty {
                            self.sync_from_active_state(&state);
                        } else if !ack.ok {
                            self.audio_enabled = state.audio_enabled;
                            self.audio_muted = state.audio_muted;
                        }
                    }
                    if ack.ok {
                        self.status_message = None;
                    } else if let Some(err) = ack.error {
                        self.status_message = Some(format!("Control error: {err}"));
                    }
                }
                GuiUpdate::Stats(stats) => {
                    self.stats = stats;
                }
                GuiUpdate::Discovery(DiscoveryEvent::Found(device)) => {
                    upsert_discovered_device(&mut self.discovered_devices, device);
                }
                GuiUpdate::Discovery(DiscoveryEvent::Removed { service_fullname }) => {
                    self.discovered_devices
                        .retain(|d| d.service_fullname != service_fullname);
                }
                GuiUpdate::VcamStatus {
                    path,
                    active,
                    standby,
                    error,
                } => {
                    if !path.is_empty() {
                        self.vcam_device = path;
                    }
                    self.vcam_active = active;
                    self.vcam_standby = standby;
                    if let Some(err) = error {
                        self.status_message = Some(format!("Virtual camera inactive: {err}"));
                    }
                }
                GuiUpdate::VmicStatus {
                    source_name,
                    description,
                    active,
                    error,
                } => {
                    if !description.is_empty() && !source_name.is_empty() {
                        self.vmic_name = format!("{description} ({source_name})");
                    } else if !description.is_empty() {
                        self.vmic_name = description;
                    }
                    self.vmic_active = active;
                    self.vmic_error = error;
                }
                GuiUpdate::Disconnected(reason) => {
                    self.status = ConnectionStatus::Disconnected;
                    self.vcam_active = false;
                    self.vcam_standby = false;
                    self.vmic_active = false;
                    self.vmic_error = None;
                    self.audio_level = AudioLevelMetrics::silence(false);
                    self.smoothed_level_norm = 0.0;
                    self.peak_hold_norm = 0.0;
                    self.stats = StreamStats::default();
                    self.decode_time_ms = 0.0;
                    self.status_message = Some(reason);
                }
                GuiUpdate::Error(err) => {
                    if matches!(
                        self.status,
                        ConnectionStatus::Connecting(_) | ConnectionStatus::Reconnecting { .. }
                    ) {
                        self.status = ConnectionStatus::Disconnected;
                        self.vcam_active = false;
                        self.vcam_standby = false;
                        self.vmic_active = false;
                        self.vmic_error = None;
                        self.audio_level = AudioLevelMetrics::silence(false);
                        self.smoothed_level_norm = 0.0;
                        self.peak_hold_norm = 0.0;
                    }
                    self.status_message = Some(err);
                }
            }
        }

        if let Some(frame) = self.session.take_latest_frame() {
            self.frame_width = frame.source_width;
            self.frame_height = frame.source_height;
            self.decode_time_ms = frame.decode_time_ms;

            if self.preview_enabled {
                let image = egui::ColorImage::from_rgba_unmultiplied(
                    [frame.preview_width as usize, frame.preview_height as usize],
                    &frame.rgba,
                );
                match self.preview_texture {
                    Some(ref mut tex) => {
                        tex.set(image, egui::TextureOptions::LINEAR);
                    }
                    None => {
                        self.preview_texture = Some(ctx.load_texture(
                            "camcraft-preview",
                            image,
                            egui::TextureOptions::LINEAR,
                        ));
                    }
                }
            }
        }

        let dt = ctx.input(|i| i.stable_dt).clamp(0.001, 0.1);
        if let Some(level) = self.session.take_latest_audio_level() {
            self.audio_level = level;
            let target = if self.audio_enabled && !self.audio_muted {
                level.normalized
            } else {
                0.0
            };
            if target >= self.smoothed_level_norm {
                self.smoothed_level_norm = target;
            } else {
                let decay = (dt * 6.0).min(1.0);
                self.smoothed_level_norm = (self.smoothed_level_norm - decay * 0.5).max(target);
            }
            if target >= self.peak_hold_norm {
                self.peak_hold_norm = target;
            } else {
                self.peak_hold_norm =
                    (self.peak_hold_norm - dt * 0.35).max(self.smoothed_level_norm);
            }
        } else if !self.is_connected() || !self.audio_enabled || self.audio_muted {
            self.audio_level = AudioLevelMetrics::silence(self.audio_muted);
            self.smoothed_level_norm = 0.0;
            self.peak_hold_norm = 0.0;
        } else {
            self.smoothed_level_norm = (self.smoothed_level_norm - dt * 1.5).max(0.0);
            self.peak_hold_norm = (self.peak_hold_norm - dt * 0.4).max(self.smoothed_level_norm);
        }
    }

    fn apply_hello(&mut self, hello: ControlHello) {
        self.device_name = Some(hello.device_name);
        self.cameras = hello.cameras;
        self.sync_from_active_state(&hello.active_state);
    }

    fn sync_from_active_state(&mut self, state: &ActiveCameraState) {
        self.selected_camera_id = state.camera_id.clone();
        self.selected_width = state.width;
        self.selected_height = state.height;
        self.selected_fps = state.fps;
        self.bitrate_mbps = (state.bitrate_kbps / 1000).clamp(4, 40);
        self.zoom_ratio = state.zoom_ratio;
        self.exposure_ev = state.exposure_ev;
        self.autofocus = state.autofocus;
        self.torch_on = state.torch_on;
        self.mirror_horizontal = state.mirror_horizontal;
        self.rotation_deg = state.rotation_deg;
        self.audio_enabled = state.audio_enabled;
        self.audio_muted = state.audio_muted;
    }

    fn active_camera_info(&self) -> Option<&CameraInfo> {
        self.cameras
            .iter()
            .find(|c| c.id == self.selected_camera_id)
            .or_else(|| self.cameras.first())
    }

    fn mark_params_dirty(&mut self) {
        self.params_dirty = true;
        self.last_params_edit = Instant::now();
    }

    fn flush_debounced_params(&mut self, ctx: &egui::Context) {
        if !self.params_dirty {
            return;
        }
        let elapsed = self.last_params_edit.elapsed();
        if elapsed >= DEBOUNCE_DURATION {
            self.params_dirty = false;
            if matches!(self.status, ConnectionStatus::Connected(_)) {
                self.session
                    .send(GuiCommand::SendControlCmd(ControlCmd::SetCameraParams {
                        zoom_ratio: Some(self.zoom_ratio),
                        exposure_ev: Some(self.exposure_ev),
                        autofocus: Some(self.autofocus),
                        torch_on: Some(self.torch_on),
                        mirror_horizontal: Some(self.mirror_horizontal),
                        rotation_deg: Some(self.rotation_deg),
                    }));
            }
        } else {
            ctx.request_repaint_after(DEBOUNCE_DURATION - elapsed);
        }
    }

    fn is_connected(&self) -> bool {
        matches!(self.status, ConnectionStatus::Connected(_))
    }

    fn is_reconnecting(&self) -> bool {
        matches!(self.status, ConnectionStatus::Reconnecting { .. })
    }

    fn is_busy(&self) -> bool {
        !matches!(self.status, ConnectionStatus::Disconnected)
    }

    fn render_telemetry_bar(&self, ui: &mut egui::Ui) {
        card_frame().show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.horizontal_wrapped(|ui| {
                // Connection status badge
                let (badge_color, badge_text) = match self.status {
                    ConnectionStatus::Connected(mode) => (
                        COLOR_STATUS_EMERALD,
                        format!("Connected via {}", mode.label()),
                    ),
                    ConnectionStatus::Connecting(mode) => (
                        COLOR_STATUS_AMBER,
                        format!("Connecting ({})...", mode.label()),
                    ),
                    ConnectionStatus::Reconnecting { mode, attempt } => (
                        COLOR_STATUS_AMBER,
                        format!("Reconnecting ({} #{attempt})...", mode.label()),
                    ),
                    ConnectionStatus::Disconnected => (COLOR_TEXT_MUTED, "Disconnected".to_owned()),
                };

                let (rect, _) =
                    ui.allocate_exact_size(egui::vec2(10.0, 10.0), egui::Sense::hover());
                ui.painter().circle_filled(rect.center(), 4.5, badge_color);
                ui.label(egui::RichText::new(badge_text).color(badge_color).strong());

                separator_dot(ui);

                let dev = self.device_name.as_deref().unwrap_or("No Device");
                ui.label(egui::RichText::new(dev).color(COLOR_TEXT_PRIMARY).strong());

                separator_dot(ui);

                let res_text = if self.frame_width > 0 && self.frame_height > 0 {
                    format!(
                        "{}x{} • {:.1} FPS",
                        self.frame_width, self.frame_height, self.stats.fps
                    )
                } else {
                    format!(
                        "{}x{} • {:.1} FPS",
                        self.selected_width, self.selected_height, self.stats.fps
                    )
                };
                ui.label(
                    egui::RichText::new(res_text)
                        .color(COLOR_TEXT_PRIMARY)
                        .monospace(),
                );

                separator_dot(ui);

                ui.label(
                    egui::RichText::new(format!("{:.2} Mbps", self.stats.bitrate_mbps))
                        .color(COLOR_ACCENT_BLUE)
                        .monospace(),
                );

                separator_dot(ui);

                ui.label(
                    egui::RichText::new(format!("Decode {:.1} ms", self.decode_time_ms))
                        .color(COLOR_TEXT_MUTED)
                        .monospace(),
                );

                separator_dot(ui);

                ui.label(
                    egui::RichText::new(format!("RTT {:.1} ms", self.stats.rtt_ms))
                        .color(COLOR_TEXT_MUTED)
                        .monospace(),
                );

                separator_dot(ui);

                let (vcam_label, vcam_color) = if self.vcam_active && self.vcam_standby {
                    (format!("{} Standby", self.vcam_device), COLOR_STATUS_AMBER)
                } else if self.vcam_active {
                    (format!("{} Active", self.vcam_device), COLOR_STATUS_EMERALD)
                } else {
                    (format!("{} Inactive", self.vcam_device), COLOR_TEXT_MUTED)
                };
                ui.label(egui::RichText::new(vcam_label).color(vcam_color).strong());

                separator_dot(ui);

                let (mic_label, mic_color) = if !self.is_connected() || !self.vmic_active {
                    ("Mic Standby", COLOR_TEXT_MUTED)
                } else if !self.audio_enabled {
                    ("Mic OFF", COLOR_TEXT_MUTED)
                } else if self.audio_muted {
                    ("Mic Muted", COLOR_STATUS_ROSE)
                } else {
                    ("Mic Active", COLOR_STATUS_EMERALD)
                };
                ui.label(egui::RichText::new(mic_label).color(mic_color).strong());
            });

            if let Some(ref msg) = self.status_message {
                ui.add_space(4.0);
                let msg_color = if msg.to_lowercase().contains("error") {
                    COLOR_STATUS_ROSE
                } else {
                    COLOR_STATUS_AMBER
                };
                ui.label(egui::RichText::new(msg).color(msg_color).small());
            }
        });
    }

    fn render_preview_canvas(&self, ui: &mut egui::Ui, size: egui::Vec2) {
        let inner_size = egui::vec2((size.x - 28.0).max(100.0), (size.y - 28.0).max(100.0));
        card_frame().show(ui, |ui| {
            ui.set_min_size(inner_size);
            ui.set_max_size(inner_size);

            if self.is_connected() && !self.preview_enabled {
                ui.centered_and_justified(|ui| {
                    ui.label(
                        egui::RichText::new(format!(
                            "Live Preview Paused — Ultra-Low Latency Mode\nStreaming directly to {} (OBS/Discord)",
                            self.vcam_device
                        ))
                        .color(COLOR_TEXT_MUTED),
                    );
                });
            } else if self.is_connected()
                && let Some(ref tex) = self.preview_texture
            {
                let tex_size = tex.size_vec2();
                let swapped = self.rotation_deg == 90 || self.rotation_deg == 270;
                let logical_aspect = if tex_size.y > 0.0 {
                    if swapped {
                        tex_size.y / tex_size.x
                    } else {
                        tex_size.x / tex_size.y
                    }
                } else {
                    16.0 / 9.0
                };

                let avail = ui.available_size();
                let (draw_w, draw_h) = fit_aspect(avail.x, avail.y, logical_aspect);
                let (rect, _) =
                    ui.allocate_exact_size(avail, egui::Sense::hover());
                let center = rect.center();

                let uv = if self.mirror_horizontal {
                    egui::Rect::from_min_max(egui::pos2(1.0, 0.0), egui::pos2(0.0, 1.0))
                } else {
                    egui::Rect::from_min_max(egui::pos2(0.0, 0.0), egui::pos2(1.0, 1.0))
                };

                // When rotated 90/270, paint with pre-rotation dimensions centered in rect.
                let (img_w, img_h) = if swapped {
                    (draw_h, draw_w)
                } else {
                    (draw_w, draw_h)
                };
                let img_rect =
                    egui::Rect::from_center_size(center, egui::vec2(img_w, img_h));

                let mut image = egui::Image::new((tex.id(), egui::vec2(img_w, img_h))).uv(uv);
                if self.rotation_deg != 0 {
                    let radians = (self.rotation_deg as f32).to_radians();
                    image = image.rotate(radians, egui::vec2(0.5, 0.5));
                }
                image.paint_at(ui, img_rect);
            } else if let ConnectionStatus::Reconnecting { mode, attempt } = self.status {
                ui.centered_and_justified(|ui| {
                    ui.vertical_centered(|ui| {
                        ui.add_space(20.0);
                        ui.horizontal(|ui| {
                            let (dot_rect, _) = ui.allocate_exact_size(
                                egui::vec2(12.0, 12.0),
                                egui::Sense::hover(),
                            );
                            ui.painter().circle_filled(
                                dot_rect.center(),
                                5.0,
                                COLOR_STATUS_AMBER,
                            );
                            ui.label(
                                egui::RichText::new("CamCraft — Reconnecting...")
                                    .color(COLOR_STATUS_AMBER)
                                    .size(18.0)
                                    .strong(),
                            );
                        });
                        ui.add_space(8.0);
                        ui.label(
                            egui::RichText::new(format!(
                                "Retrying {} connection every 2s (attempt #{attempt})",
                                mode.label()
                            ))
                            .color(COLOR_TEXT_PRIMARY),
                        );
                        if self.vcam_active {
                            ui.add_space(4.0);
                            ui.label(
                                egui::RichText::new(format!(
                                    "{} remains active in Standby Frame mode (OBS/Discord safe)",
                                    self.vcam_device
                                ))
                                .color(COLOR_TEXT_MUTED)
                                .small(),
                            );
                        }
                    });
                });
            } else {
                ui.centered_and_justified(|ui| {
                    let placeholder = if self.is_connected() {
                        "Connected — Waiting for H.264 video frames..."
                    } else {
                        "CamCraft Studio Preview (16:9)\nConnect via USB (ADB) or Wi-Fi to start streaming"
                    };
                    ui.label(egui::RichText::new(placeholder).color(COLOR_TEXT_MUTED));
                });
            }
        });
    }

    fn render_connection_card(&mut self, ui: &mut egui::Ui) {
        card_frame().show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            section_title(ui, "Connection");
            ui.add_space(8.0);

            ui.label(
                egui::RichText::new("Virtual Camera Device")
                    .color(COLOR_TEXT_MUTED)
                    .small(),
            );
            ui.add_enabled_ui(!self.is_busy(), |ui| {
                ui.add(
                    egui::TextEdit::singleline(&mut self.vcam_device).desired_width(f32::INFINITY),
                );
                ui.add_space(6.0);
                ui.checkbox(&mut self.auto_reconnect, "Auto-Reconnect on Drop (2s)");
                ui.add_space(8.0);

                let usb_btn = egui::Button::new(
                    egui::RichText::new("Connect via USB (ADB)").color(COLOR_TEXT_PRIMARY),
                )
                .fill(COLOR_ACCENT_BLUE);
                if ui
                    .add_sized([ui.available_width(), 30.0], usb_btn)
                    .on_hover_text("Runs adb forward tcp:9876 tcp:9876 and connects")
                    .clicked()
                {
                    self.session.send(GuiCommand::ConnectUsb {
                        port: DEFAULT_PORT,
                        vcam_device: self.vcam_device.clone(),
                        auto_reconnect: self.auto_reconnect,
                    });
                }
            });

            ui.add_space(10.0);
            ui.horizontal(|ui| {
                ui.label(
                    egui::RichText::new("Wi-Fi Devices")
                        .color(COLOR_TEXT_MUTED)
                        .small(),
                );
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let refresh_btn = egui::Button::new(
                        egui::RichText::new("Refresh")
                            .color(COLOR_TEXT_PRIMARY)
                            .small(),
                    )
                    .fill(COLOR_SURFACE_ELEVATED);
                    if ui
                        .add(refresh_btn)
                        .on_hover_text("Send mDNS _camcraft._tcp scan query")
                        .clicked()
                    {
                        self.session.send(GuiCommand::RefreshWifiDiscovery);
                    }
                });
            });

            let mut connect_discovered_endpoint: Option<String> = None;
            egui::Frame::new()
                .fill(COLOR_BG_CARBON)
                .corner_radius(6.0)
                .inner_margin(8.0)
                .show(ui, |ui| {
                    ui.set_min_width(ui.available_width());
                    if self.discovered_devices.is_empty() {
                        ui.label(
                            egui::RichText::new(
                                "No phone detected yet.\nTap 'Start Server' on CamCraft Android (same Wi-Fi).",
                            )
                            .color(COLOR_TEXT_MUTED)
                            .small(),
                        );
                    } else {
                        for (idx, device) in self.discovered_devices.iter().enumerate() {
                            let endpoint = device.endpoint();
                            egui::Frame::new()
                                .fill(COLOR_SURFACE_ELEVATED)
                                .corner_radius(6.0)
                                .inner_margin(8.0)
                                .show(ui, |ui| {
                                    ui.set_min_width(ui.available_width());
                                    ui.horizontal(|ui| {
                                        let (rect, _) = ui.allocate_exact_size(
                                            egui::vec2(8.0, 8.0),
                                            egui::Sense::hover(),
                                        );
                                        ui.painter().circle_filled(
                                            rect.center(),
                                            3.5,
                                            COLOR_STATUS_EMERALD,
                                        );

                                        ui.vertical(|ui| {
                                            ui.label(
                                                egui::RichText::new(&device.device_name)
                                                    .color(COLOR_TEXT_PRIMARY)
                                                    .strong(),
                                            );
                                            ui.label(
                                                egui::RichText::new(&endpoint)
                                                    .color(COLOR_TEXT_MUTED)
                                                    .monospace()
                                                    .small(),
                                            );
                                        });

                                        ui.with_layout(
                                            egui::Layout::right_to_left(egui::Align::Center),
                                            |ui| {
                                                let connect_btn = egui::Button::new(
                                                    egui::RichText::new("Connect")
                                                        .color(COLOR_TEXT_PRIMARY),
                                                )
                                                .fill(COLOR_ACCENT_BLUE);
                                                if ui
                                                    .add_enabled(!self.is_busy(), connect_btn)
                                                    .clicked()
                                                {
                                                    connect_discovered_endpoint =
                                                        Some(endpoint.clone());
                                                }
                                            },
                                        );
                                    });
                                });
                            if idx + 1 < self.discovered_devices.len() {
                                ui.add_space(4.0);
                            }
                        }
                    }
                });

            if let Some(endpoint) = connect_discovered_endpoint {
                self.wifi_endpoint = endpoint.clone();
                self.session.send(GuiCommand::ConnectWifi {
                    endpoint,
                    vcam_device: self.vcam_device.clone(),
                    auto_reconnect: self.auto_reconnect,
                });
            }

            ui.add_space(10.0);
            ui.label(
                egui::RichText::new("Manual Phone IP:Port")
                    .color(COLOR_TEXT_MUTED)
                    .small(),
            );
            let can_connect_wifi = !self.is_busy() && !self.wifi_endpoint.trim().is_empty();
            ui.add_enabled_ui(!self.is_busy(), |ui| {
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let wifi_btn = egui::Button::new(
                        egui::RichText::new("Connect Wi-Fi").color(COLOR_TEXT_PRIMARY),
                    )
                    .fill(COLOR_SURFACE_ELEVATED);
                    if ui.add_enabled(can_connect_wifi, wifi_btn).clicked() {
                        self.session.send(GuiCommand::ConnectWifi {
                            endpoint: self.wifi_endpoint.clone(),
                            vcam_device: self.vcam_device.clone(),
                            auto_reconnect: self.auto_reconnect,
                        });
                    }
                    ui.add(
                        egui::TextEdit::singleline(&mut self.wifi_endpoint)
                            .hint_text("e.g. 192.168.1.7:9876")
                            .desired_width(ui.available_width()),
                    );
                });
            });

            ui.add_space(10.0);
            ui.add_enabled_ui(self.is_busy(), |ui| {
                let disconnect_label = if self.is_reconnecting() {
                    "Cancel Reconnect"
                } else {
                    "Disconnect"
                };
                let disconnect_btn = egui::Button::new(
                    egui::RichText::new(disconnect_label).color(COLOR_TEXT_PRIMARY),
                )
                .fill(if self.is_busy() {
                    COLOR_STATUS_ROSE
                } else {
                    COLOR_SURFACE_ELEVATED
                });
                if ui
                    .add_sized([ui.available_width(), 28.0], disconnect_btn)
                    .clicked()
                {
                    self.session.send(GuiCommand::Disconnect);
                }
            });

            ui.add_space(8.0);
            if ui
                .checkbox(
                    &mut self.preview_enabled,
                    "Live Preview (turn off for lowest OBS latency)",
                )
                .changed()
            {
                self.session.set_preview_enabled(self.preview_enabled);
                if !self.preview_enabled {
                    self.preview_texture = None;
                }
            }
        });
    }

    fn render_stream_preset_card(&mut self, ui: &mut egui::Ui) {
        let connected = self.is_connected();

        card_frame().show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            section_title(ui, "Camera & Stream Preset");
            ui.add_space(8.0);

            ui.add_enabled_ui(connected, |ui| {
                ui.spacing_mut().slider_width = (ui.available_width() - 95.0).max(120.0);
                // Camera selector
                ui.label(
                    egui::RichText::new("Camera Lens")
                        .color(COLOR_TEXT_MUTED)
                        .small(),
                );
                let selected_label = self
                    .cameras
                    .iter()
                    .find(|c| c.id == self.selected_camera_id)
                    .map(|c| format!("{} ({})", c.label, c.facing))
                    .unwrap_or_else(|| format!("Camera {}", self.selected_camera_id));

                egui::ComboBox::from_id_salt("camera_lens_combo")
                    .selected_text(selected_label)
                    .width(ui.available_width())
                    .show_ui(ui, |ui| {
                        for cam in &self.cameras {
                            let label = format!("{} ({})", cam.label, cam.facing);
                            ui.selectable_value(
                                &mut self.selected_camera_id,
                                cam.id.clone(),
                                label,
                            );
                        }
                    });

                ui.add_space(8.0);

                // Resolution selector
                ui.label(
                    egui::RichText::new("Resolution")
                        .color(COLOR_TEXT_MUTED)
                        .small(),
                );
                let res_label = format!("{}x{}", self.selected_width, self.selected_height);
                egui::ComboBox::from_id_salt("resolution_combo")
                    .selected_text(res_label)
                    .width(ui.available_width())
                    .show_ui(ui, |ui| {
                        for &(w, h, label) in STANDARD_RESOLUTIONS {
                            let is_sel = self.selected_width == w && self.selected_height == h;
                            if ui.selectable_label(is_sel, label).clicked() {
                                self.selected_width = w;
                                self.selected_height = h;
                            }
                        }
                    });

                ui.add_space(8.0);

                // FPS selector
                ui.label(
                    egui::RichText::new("Frame Rate")
                        .color(COLOR_TEXT_MUTED)
                        .small(),
                );
                ui.horizontal(|ui| {
                    ui.selectable_value(&mut self.selected_fps, 30, "30 FPS");
                    ui.selectable_value(&mut self.selected_fps, 60, "60 FPS");
                });

                ui.add_space(8.0);

                // Bitrate slider
                ui.label(
                    egui::RichText::new(format!("Bitrate: {} Mbps", self.bitrate_mbps))
                        .color(COLOR_TEXT_MUTED)
                        .small(),
                );
                ui.add(
                    egui::Slider::new(&mut self.bitrate_mbps, 4..=40)
                        .suffix(" Mbps")
                        .step_by(1.0),
                );

                ui.add_space(10.0);

                let apply_btn = egui::Button::new(
                    egui::RichText::new("Apply Stream Settings").color(COLOR_TEXT_PRIMARY),
                )
                .fill(COLOR_ACCENT_BLUE);
                if ui
                    .add_sized([ui.available_width(), 30.0], apply_btn)
                    .clicked()
                {
                    self.session
                        .send(GuiCommand::SendControlCmd(ControlCmd::ConfigureStream {
                            camera_id: self.selected_camera_id.clone(),
                            width: self.selected_width,
                            height: self.selected_height,
                            fps: self.selected_fps,
                            bitrate_kbps: self.bitrate_mbps * 1000,
                        }));
                }
            });
        });
    }

    fn render_live_adjustments_card(&mut self, ui: &mut egui::Ui) {
        let connected = self.is_connected();
        let (max_zoom, min_ev, max_ev, has_flash) = match self.active_camera_info() {
            Some(cam) => (
                cam.max_zoom.max(1.0),
                cam.ev_range.0,
                cam.ev_range.1,
                cam.has_flash,
            ),
            None => (5.0, -2.0, 2.0, true),
        };

        card_frame().show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            section_title(ui, "Live Image Adjustments");
            ui.add_space(8.0);

            ui.add_enabled_ui(connected, |ui| {
                ui.spacing_mut().slider_width = (ui.available_width() - 95.0).max(120.0);
                // Zoom slider
                ui.label(
                    egui::RichText::new(format!("Zoom: {:.2}x", self.zoom_ratio))
                        .color(COLOR_TEXT_MUTED)
                        .small(),
                );
                if ui
                    .add(
                        egui::Slider::new(&mut self.zoom_ratio, 1.0..=max_zoom)
                            .suffix("x")
                            .fixed_decimals(2),
                    )
                    .changed()
                {
                    self.mark_params_dirty();
                }

                ui.add_space(6.0);

                // Exposure EV slider
                let ev_range_valid = max_ev > min_ev;
                ui.label(
                    egui::RichText::new(format!("Exposure (EV): {:+.1}", self.exposure_ev))
                        .color(COLOR_TEXT_MUTED)
                        .small(),
                );
                let ev_min = if ev_range_valid { min_ev } else { -2.0 };
                let ev_max = if ev_range_valid { max_ev } else { 2.0 };
                if ui
                    .add_enabled(
                        ev_range_valid,
                        egui::Slider::new(&mut self.exposure_ev, ev_min..=ev_max)
                            .suffix(" EV")
                            .fixed_decimals(1),
                    )
                    .changed()
                {
                    self.mark_params_dirty();
                }

                ui.add_space(8.0);

                // Checkboxes
                if ui.checkbox(&mut self.autofocus, "Autofocus").changed() {
                    self.mark_params_dirty();
                }
                if ui
                    .add_enabled(
                        has_flash,
                        egui::Checkbox::new(&mut self.torch_on, "Torch / Flashlight"),
                    )
                    .changed()
                {
                    self.mark_params_dirty();
                }
                if ui
                    .checkbox(&mut self.mirror_horizontal, "Mirror Horizontal")
                    .changed()
                {
                    self.mark_params_dirty();
                }

                ui.add_space(8.0);

                // Rotation selector
                ui.label(
                    egui::RichText::new("Rotation")
                        .color(COLOR_TEXT_MUTED)
                        .small(),
                );
                ui.horizontal(|ui| {
                    for deg in [0, 90, 180, 270] {
                        if ui
                            .selectable_value(&mut self.rotation_deg, deg, format!("{deg}°"))
                            .changed()
                        {
                            self.mark_params_dirty();
                        }
                    }
                });
            });
        });
    }

    fn render_audio_card(&mut self, ui: &mut egui::Ui) {
        let connected = self.is_connected();

        card_frame().show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.horizontal(|ui| {
                section_title(ui, "Audio & Virtual Microphone");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let (badge_text, badge_color) = if connected && self.vmic_active {
                        ("Active", COLOR_STATUS_EMERALD)
                    } else {
                        ("Standby", COLOR_TEXT_MUTED)
                    };
                    ui.label(
                        egui::RichText::new(badge_text)
                            .color(badge_color)
                            .small()
                            .strong(),
                    );
                });
            });

            ui.label(
                egui::RichText::new(format!("PipeWire Source: {}", self.vmic_name))
                    .color(COLOR_TEXT_MUTED)
                    .small(),
            );
            if let Some(ref err) = self.vmic_error {
                ui.label(
                    egui::RichText::new(format!("Virtual mic unavailable: {err}"))
                        .color(COLOR_STATUS_AMBER)
                        .small(),
                );
            }
            ui.add_space(8.0);

            ui.add_enabled_ui(connected, |ui| {
                if ui
                    .checkbox(&mut self.audio_enabled, "Enable Audio Forwarding")
                    .changed()
                {
                    self.session.send(GuiCommand::SetAudioState {
                        enabled: self.audio_enabled,
                        muted: self.audio_muted,
                    });
                }

                ui.add_space(6.0);

                let can_mute = connected && self.audio_enabled;
                let mute_text = if self.audio_muted {
                    "Mic Muted (Click to Unmute)"
                } else {
                    "Mute Mic"
                };
                let mute_fill = if self.audio_muted {
                    COLOR_STATUS_ROSE
                } else {
                    COLOR_SURFACE_ELEVATED
                };
                let mute_btn =
                    egui::Button::new(egui::RichText::new(mute_text).color(COLOR_TEXT_PRIMARY))
                        .fill(mute_fill);
                if ui
                    .add_enabled_ui(can_mute, |ui| {
                        ui.add_sized([ui.available_width(), 28.0], mute_btn)
                    })
                    .inner
                    .clicked()
                {
                    self.audio_muted = !self.audio_muted;
                    self.session.send(GuiCommand::SetAudioState {
                        enabled: self.audio_enabled,
                        muted: self.audio_muted,
                    });
                }
            });

            ui.add_space(10.0);

            // Volume Level Meter (dB bar)
            let level = if connected && self.audio_enabled && !self.audio_muted {
                self.smoothed_level_norm.clamp(0.0, 1.0)
            } else {
                0.0
            };
            let peak = if connected && self.audio_enabled && !self.audio_muted {
                self.peak_hold_norm.clamp(0.0, 1.0)
            } else {
                0.0
            };

            let (db_text, db_color) = if !connected || !self.audio_enabled {
                ("OFF".to_owned(), COLOR_TEXT_MUTED)
            } else if self.audio_muted {
                ("MUTED".to_owned(), COLOR_STATUS_ROSE)
            } else if self.audio_level.clipping {
                (
                    format!("{:.1} dB", self.audio_level.rms_db),
                    COLOR_STATUS_ROSE,
                )
            } else {
                (
                    format!("{:.1} dB", self.audio_level.rms_db),
                    COLOR_TEXT_PRIMARY,
                )
            };

            ui.horizontal(|ui| {
                ui.label(
                    egui::RichText::new("Volume Level")
                        .color(COLOR_TEXT_MUTED)
                        .small(),
                );
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    ui.label(
                        egui::RichText::new(db_text)
                            .color(db_color)
                            .monospace()
                            .small(),
                    );
                });
            });

            let meter_width = ui.available_width().max(60.0);
            let (meter_rect, _) =
                ui.allocate_exact_size(egui::vec2(meter_width, 14.0), egui::Sense::hover());
            let painter = ui.painter();
            painter.rect_filled(meter_rect, egui::CornerRadius::same(4), COLOR_BG_CARBON);

            let zones = [
                (0.0_f32, 0.70_f32, COLOR_STATUS_EMERALD),
                (0.70_f32, 0.90_f32, COLOR_STATUS_AMBER),
                (0.90_f32, 1.0_f32, COLOR_STATUS_ROSE),
            ];
            for (z_start, z_end, color) in zones {
                if level > z_start {
                    let seg_end = level.min(z_end);
                    let x0 = meter_rect.min.x + meter_rect.width() * z_start;
                    let x1 = meter_rect.min.x + meter_rect.width() * seg_end;
                    let seg_rect = egui::Rect::from_min_max(
                        egui::pos2(x0, meter_rect.min.y),
                        egui::pos2(x1, meter_rect.max.y),
                    );
                    let radius = if z_start == 0.0 && seg_end >= 0.99 {
                        egui::CornerRadius::same(4)
                    } else if z_start == 0.0 {
                        egui::CornerRadius {
                            nw: 4,
                            sw: 4,
                            ne: 0,
                            se: 0,
                        }
                    } else if seg_end >= 0.99 {
                        egui::CornerRadius {
                            nw: 0,
                            sw: 0,
                            ne: 4,
                            se: 4,
                        }
                    } else {
                        egui::CornerRadius::ZERO
                    };
                    painter.rect_filled(seg_rect, radius, color);
                }
            }

            if peak > 0.01 {
                let peak_x =
                    (meter_rect.min.x + meter_rect.width() * peak).min(meter_rect.max.x - 1.0);
                let peak_color = if peak >= 0.90 {
                    COLOR_STATUS_ROSE
                } else if peak >= 0.70 {
                    COLOR_STATUS_AMBER
                } else {
                    COLOR_TEXT_PRIMARY
                };
                painter.line_segment(
                    [
                        egui::pos2(peak_x, meter_rect.min.y + 1.0),
                        egui::pos2(peak_x, meter_rect.max.y - 1.0),
                    ],
                    egui::Stroke::new(2.0, peak_color),
                );
            }

            let (scale_rect, _) =
                ui.allocate_exact_size(egui::vec2(meter_width, 13.0), egui::Sense::hover());
            let font = egui::FontId::monospace(9.5);
            for (frac, align, label) in [
                (0.0_f32, egui::Align2::LEFT_TOP, "-60"),
                (0.40_f32, egui::Align2::CENTER_TOP, "-36"),
                (0.70_f32, egui::Align2::CENTER_TOP, "-18"),
                (0.90_f32, egui::Align2::CENTER_TOP, "-6"),
                (1.0_f32, egui::Align2::RIGHT_TOP, "0 dB"),
            ] {
                let x = scale_rect.min.x + scale_rect.width() * frac;
                ui.painter().text(
                    egui::pos2(x, scale_rect.min.y + 1.0),
                    align,
                    label,
                    font.clone(),
                    COLOR_TEXT_MUTED,
                );
            }
        });
    }
}

impl eframe::App for CamCraftApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        self.process_updates(&ctx);
        self.flush_debounced_params(&ctx);

        if self.is_connected() {
            if self.preview_enabled {
                ctx.request_repaint_after(Duration::from_millis(30));
            } else {
                ctx.request_repaint_after(Duration::from_millis(250));
            }
        }

        egui::Panel::right("inspector_panel")
            .resizable(true)
            .default_size(360.0)
            .min_size(300.0)
            .max_size(520.0)
            .frame(
                egui::Frame::new()
                    .fill(COLOR_BG_CARBON)
                    .inner_margin(egui::Margin::symmetric(12, 10)),
            )
            .show(ui, |ui| {
                egui::ScrollArea::vertical()
                    .id_salt("inspector_scroll")
                    .auto_shrink([false, false])
                    .show(ui, |ui| {
                        self.render_connection_card(ui);
                        ui.add_space(10.0);
                        self.render_stream_preset_card(ui);
                        ui.add_space(10.0);
                        self.render_live_adjustments_card(ui);
                        ui.add_space(10.0);
                        self.render_audio_card(ui);
                    });
            });

        egui::CentralPanel::default()
            .frame(
                egui::Frame::central_panel(ui.style())
                    .fill(COLOR_BG_CARBON)
                    .inner_margin(egui::Margin::symmetric(12, 10)),
            )
            .show(ui, |ui| {
                self.render_telemetry_bar(ui);
                ui.add_space(10.0);
                let preview_size = ui.available_size();
                self.render_preview_canvas(ui, preview_size);
            });
    }
}

fn card_frame() -> egui::Frame {
    egui::Frame::new()
        .fill(COLOR_SURFACE_CARD)
        .corner_radius(8.0)
        .inner_margin(14.0)
}

fn section_title(ui: &mut egui::Ui, title: &str) {
    ui.label(
        egui::RichText::new(title)
            .color(COLOR_TEXT_PRIMARY)
            .size(15.0)
            .strong(),
    );
}

fn separator_dot(ui: &mut egui::Ui) {
    ui.label(egui::RichText::new("•").color(COLOR_TEXT_MUTED));
}

fn fit_aspect(avail_w: f32, avail_h: f32, aspect: f32) -> (f32, f32) {
    if avail_w <= 0.0 || avail_h <= 0.0 || aspect <= 0.0 {
        return (avail_w.max(1.0), avail_h.max(1.0));
    }
    let avail_aspect = avail_w / avail_h;
    if avail_aspect > aspect {
        let h = avail_h;
        (h * aspect, h)
    } else {
        let w = avail_w;
        (w, w / aspect)
    }
}

fn apply_artcraft_dark_theme(ctx: &egui::Context) {
    let mut visuals = egui::Visuals::dark();
    visuals.window_fill = COLOR_BG_CARBON;
    visuals.panel_fill = COLOR_BG_CARBON;
    visuals.extreme_bg_color = egui::Color32::from_rgb(0x0B, 0x0D, 0x11);
    visuals.widgets.noninteractive.bg_fill = COLOR_SURFACE_CARD;
    visuals.widgets.noninteractive.corner_radius = egui::CornerRadius::same(8);
    visuals.widgets.inactive.bg_fill = COLOR_SURFACE_ELEVATED;
    visuals.widgets.inactive.corner_radius = egui::CornerRadius::same(8);
    visuals.widgets.hovered.bg_fill = COLOR_ACCENT_BLUE;
    visuals.widgets.hovered.corner_radius = egui::CornerRadius::same(8);
    visuals.widgets.active.bg_fill = COLOR_ACCENT_BLUE;
    visuals.widgets.active.corner_radius = egui::CornerRadius::same(8);
    visuals.selection.bg_fill = COLOR_ACCENT_BLUE;
    ctx.set_visuals(visuals);
}

fn upsert_discovered_device(list: &mut Vec<DiscoveredDevice>, device: DiscoveredDevice) {
    if let Some(existing) = list
        .iter_mut()
        .find(|d| d.service_fullname == device.service_fullname)
    {
        *existing = device;
    } else {
        list.push(device);
    }
    list.sort_by(|a, b| {
        a.device_name
            .cmp(&b.device_name)
            .then_with(|| a.endpoint().cmp(&b.endpoint()))
    });
}

#[cfg(test)]
mod tests {
    use std::net::Ipv4Addr;

    use super::*;

    fn make_dev(fullname: &str, name: &str, ip: [u8; 4], port: u16) -> DiscoveredDevice {
        DiscoveredDevice {
            service_fullname: fullname.to_owned(),
            device_name: name.to_owned(),
            ip: Ipv4Addr::from(ip),
            port,
            hostname: "android.local.".to_owned(),
            protocol_version: Some(1),
        }
    }

    #[test]
    fn upsert_discovered_device_updates_in_place_and_sorts_stably() {
        let mut devices = Vec::new();
        upsert_discovered_device(
            &mut devices,
            make_dev(
                "z._camcraft._tcp.local.",
                "Zeta Phone",
                [192, 168, 1, 20],
                9876,
            ),
        );
        upsert_discovered_device(
            &mut devices,
            make_dev(
                "a._camcraft._tcp.local.",
                "Alpha Phone",
                [192, 168, 1, 10],
                9876,
            ),
        );
        assert_eq!(devices[0].device_name, "Alpha Phone");
        assert_eq!(devices[1].device_name, "Zeta Phone");

        // Update existing device IP by service_fullname without duplicating
        upsert_discovered_device(
            &mut devices,
            make_dev(
                "a._camcraft._tcp.local.",
                "Alpha Phone",
                [192, 168, 1, 99],
                9876,
            ),
        );
        assert_eq!(devices.len(), 2);
        assert_eq!(devices[0].endpoint(), "192.168.1.99:9876");
    }
}
