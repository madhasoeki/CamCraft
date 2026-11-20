//! Native Fedora desktop GUI for CamCraft (ArtCraft Studio v2 Theme).

mod panels;
mod session;
mod theme;

use std::time::{Duration, Instant};

use anyhow::{Result, anyhow};
use camcraft_core::client::StreamStats;
use camcraft_core::discovery::{DiscoveredDevice, DiscoveryEvent};
use camcraft_core::vmic::{AudioLevelMetrics, DEFAULT_SOURCE_DESCRIPTION};
use camcraft_protocol::{ActiveCameraState, CameraInfo, ControlCmd, ControlHello, DEFAULT_PORT};
use eframe::egui;
use session::{ConnectionMode, GuiCommand, GuiSession, GuiUpdate, PerformanceProfile};
use theme::COLOR_BG_CANVAS;
use tracing::info;

pub(crate) const DEBOUNCE_DURATION: Duration = Duration::from_millis(50);

pub(crate) const STANDARD_RESOLUTIONS: &[(u32, u32, &str)] = &[
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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(crate) enum InspectorTab {
    Connect,
    Camera,
    Audio,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum ConnectionStatus {
    Disconnected,
    Connecting(ConnectionMode),
    Connected(ConnectionMode),
    Reconnecting { mode: ConnectionMode, attempt: u32 },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct StreamPresetSnapshot {
    camera_id: String,
    width: u32,
    height: u32,
    fps: u32,
    bitrate_mbps: u32,
}

pub(crate) struct CamCraftApp {
    pub(crate) session: GuiSession,
    pub(crate) active_tab: InspectorTab,
    pub(crate) perf_profile: PerformanceProfile,
    pub(crate) show_grid: bool,

    pub(crate) status: ConnectionStatus,
    pub(crate) status_message: Option<String>,
    pub(crate) device_name: Option<String>,
    pub(crate) cameras: Vec<CameraInfo>,

    // Connection settings
    pub(crate) vcam_device: String,
    pub(crate) vcam_active: bool,
    pub(crate) vcam_standby: bool,
    pub(crate) auto_reconnect: bool,
    pub(crate) wifi_endpoint: String,
    pub(crate) discovered_devices: Vec<DiscoveredDevice>,

    // Stream & camera controls (synchronized with ActiveCameraState)
    pub(crate) selected_camera_id: String,
    pub(crate) selected_width: u32,
    pub(crate) selected_height: u32,
    pub(crate) selected_fps: u32,
    pub(crate) bitrate_mbps: u32,
    pub(crate) applied_stream: Option<StreamPresetSnapshot>,

    pub(crate) zoom_ratio: f32,
    pub(crate) exposure_ev: f32,
    pub(crate) autofocus: bool,
    pub(crate) torch_on: bool,
    pub(crate) mirror_horizontal: bool,
    pub(crate) rotation_deg: i32,

    // Audio forwarding & virtual microphone state
    pub(crate) audio_enabled: bool,
    pub(crate) audio_muted: bool,
    pub(crate) vmic_active: bool,
    pub(crate) vmic_name: String,
    pub(crate) vmic_error: Option<String>,
    pub(crate) audio_level: AudioLevelMetrics,
    pub(crate) smoothed_level_norm: f32,
    pub(crate) peak_hold_norm: f32,
    pub(crate) audio_level_updated_this_frame: bool,

    // 50ms debounce tracking for live image adjustments
    pub(crate) params_dirty: bool,
    pub(crate) last_params_edit: Instant,

    // Live preview & telemetry
    pub(crate) preview_enabled: bool,
    pub(crate) preview_texture: Option<egui::TextureHandle>,
    pub(crate) frame_width: u32,
    pub(crate) frame_height: u32,
    pub(crate) decode_time_ms: f32,
    pub(crate) stats: StreamStats,
}

impl CamCraftApp {
    fn new(ctx: &egui::Context) -> Self {
        theme::apply_artcraft_theme(ctx);
        let session = GuiSession::start(ctx.clone());

        Self {
            session,
            active_tab: InspectorTab::Connect,
            perf_profile: PerformanceProfile::default(),
            show_grid: false,
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
            applied_stream: None,
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
            audio_level_updated_this_frame: false,
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
                            self.applied_stream = Some(self.current_stream_preset());
                        } else if !ack.ok {
                            self.audio_enabled = state.audio_enabled;
                            self.audio_muted = state.audio_muted;
                        }
                    } else if ack.ok {
                        self.applied_stream = Some(self.current_stream_preset());
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
                    self.reset_disconnected_state(Some(reason));
                }
                GuiUpdate::Error(err) => {
                    if matches!(
                        self.status,
                        ConnectionStatus::Connecting(_) | ConnectionStatus::Reconnecting { .. }
                    ) {
                        self.reset_disconnected_state(Some(err));
                    } else {
                        self.status_message = Some(err);
                    }
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
            self.session.recycle_frame_buffer(frame.rgba);
        }

        let dt = ctx.input(|i| i.stable_dt).clamp(0.001, 0.1);
        self.audio_level_updated_this_frame = false;
        if let Some(level) = self.session.take_latest_audio_level() {
            self.audio_level_updated_this_frame = true;
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

    fn reset_disconnected_state(&mut self, message: Option<String>) {
        self.status = ConnectionStatus::Disconnected;
        self.vcam_active = false;
        self.vcam_standby = false;
        self.vmic_active = false;
        self.vmic_error = None;
        self.applied_stream = None;
        self.audio_level = AudioLevelMetrics::silence(false);
        self.smoothed_level_norm = 0.0;
        self.peak_hold_norm = 0.0;
        self.stats = StreamStats::default();
        self.decode_time_ms = 0.0;
        self.status_message = message;
    }

    fn apply_hello(&mut self, hello: ControlHello) {
        self.device_name = Some(hello.device_name);
        self.cameras = hello.cameras;
        self.sync_from_active_state(&hello.active_state);
        self.applied_stream = Some(self.current_stream_preset());
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

    pub(crate) fn current_stream_preset(&self) -> StreamPresetSnapshot {
        StreamPresetSnapshot {
            camera_id: self.selected_camera_id.clone(),
            width: self.selected_width,
            height: self.selected_height,
            fps: self.selected_fps,
            bitrate_mbps: self.bitrate_mbps,
        }
    }

    pub(crate) fn has_pending_stream_changes(&self) -> bool {
        self.is_connected()
            && self
                .applied_stream
                .as_ref()
                .is_some_and(|applied| applied != &self.current_stream_preset())
    }

    pub(crate) fn active_camera_info(&self) -> Option<&CameraInfo> {
        self.cameras
            .iter()
            .find(|c| c.id == self.selected_camera_id)
            .or_else(|| self.cameras.first())
    }

    pub(crate) fn mark_params_dirty(&mut self) {
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

    pub(crate) fn connect_usb(&self) {
        self.session.send(GuiCommand::ConnectUsb {
            port: DEFAULT_PORT,
            vcam_device: self.vcam_device.clone(),
            auto_reconnect: self.auto_reconnect,
        });
    }

    pub(crate) fn connect_wifi(&mut self, endpoint: String) {
        self.wifi_endpoint = endpoint.clone();
        self.session.send(GuiCommand::ConnectWifi {
            endpoint,
            vcam_device: self.vcam_device.clone(),
            auto_reconnect: self.auto_reconnect,
        });
    }

    pub(crate) fn set_performance_profile(&mut self, profile: PerformanceProfile) {
        self.perf_profile = profile;
        self.session.set_performance_profile(profile);
    }

    pub(crate) fn set_preview_enabled(&mut self, enabled: bool) {
        self.preview_enabled = enabled;
        self.session.set_preview_enabled(enabled);
        if !enabled {
            self.preview_texture = None;
        }
    }

    pub(crate) fn toggle_preview(&mut self) {
        self.set_preview_enabled(!self.preview_enabled);
    }

    pub(crate) fn send_audio_state(&self) {
        self.session.send(GuiCommand::SetAudioState {
            enabled: self.audio_enabled,
            muted: self.audio_muted,
        });
    }

    pub(crate) fn toggle_mute(&mut self) {
        if self.is_connected() && self.audio_enabled {
            self.audio_muted = !self.audio_muted;
            self.send_audio_state();
        }
    }

    pub(crate) fn toggle_mirror(&mut self) {
        if self.is_connected() {
            self.mirror_horizontal = !self.mirror_horizontal;
            self.mark_params_dirty();
        }
    }

    pub(crate) fn rotate_cw_90(&mut self) {
        if self.is_connected() {
            self.rotation_deg = next_rotation_cw(self.rotation_deg);
            self.mark_params_dirty();
        }
    }

    fn handle_shortcuts(&mut self, ctx: &egui::Context) {
        if ctx.egui_wants_keyboard_input() || ctx.any_popup_open() {
            return;
        }

        let (m, p, h, r) = ctx.input(|i| {
            if i.modifiers.ctrl || i.modifiers.alt || i.modifiers.command {
                return (false, false, false, false);
            }
            (
                i.key_pressed(egui::Key::M),
                i.key_pressed(egui::Key::P),
                i.key_pressed(egui::Key::H),
                i.key_pressed(egui::Key::R),
            )
        });

        if m {
            self.toggle_mute();
        }
        if p {
            self.toggle_preview();
        }
        if h {
            self.toggle_mirror();
        }
        if r {
            self.rotate_cw_90();
        }
    }

    pub(crate) fn is_connected(&self) -> bool {
        matches!(self.status, ConnectionStatus::Connected(_))
    }

    pub(crate) fn is_reconnecting(&self) -> bool {
        matches!(self.status, ConnectionStatus::Reconnecting { .. })
    }

    pub(crate) fn is_busy(&self) -> bool {
        !matches!(self.status, ConnectionStatus::Disconnected)
    }
}

impl eframe::App for CamCraftApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        let ctx = ui.ctx().clone();
        let minimized = ctx.input(|i| i.viewport().minimized.unwrap_or(false));
        self.session.set_window_hidden(minimized);

        self.process_updates(&ctx);
        self.handle_shortcuts(&ctx);
        self.flush_debounced_params(&ctx);

        let audio_meter_visible = self.is_connected()
            && !minimized
            && self.active_tab == InspectorTab::Audio
            && self.audio_enabled
            && !self.audio_muted;
        self.session.set_audio_meter_active(audio_meter_visible);
        if audio_meter_visible
            && (self.audio_level_updated_this_frame
                || self.smoothed_level_norm > 0.005
                || self.peak_hold_norm > 0.005)
        {
            ctx.request_repaint_after(Duration::from_millis(66));
        }

        egui::Panel::top("studio_header")
            .frame(
                egui::Frame::new()
                    .fill(COLOR_BG_CANVAS)
                    .inner_margin(egui::Margin::symmetric(12, 8)),
            )
            .show(ui, |ui| {
                panels::render_telemetry_bar(self, ui);
            });

        let total_w = ui.available_width();
        let default_inspector_w = (total_w * 0.30).clamp(320.0, 460.0);
        egui::Panel::right("inspector_panel")
            .resizable(true)
            .default_size(default_inspector_w)
            .min_size(310.0)
            .max_size(480.0)
            .frame(
                egui::Frame::new()
                    .fill(COLOR_BG_CANVAS)
                    .inner_margin(egui::Margin {
                        left: 6,
                        right: 12,
                        top: 2,
                        bottom: 12,
                    }),
            )
            .show(ui, |ui| {
                panels::render_inspector_tabs(self, ui);
            });

        egui::CentralPanel::default()
            .frame(
                egui::Frame::central_panel(ui.style())
                    .fill(COLOR_BG_CANVAS)
                    .inner_margin(egui::Margin {
                        left: 12,
                        right: 6,
                        top: 2,
                        bottom: 12,
                    }),
            )
            .show(ui, |ui| {
                let preview_size = ui.available_size();
                panels::render_preview_canvas(self, ui, preview_size);
            });
    }
}

#[must_use]
pub(crate) const fn next_rotation_cw(deg: i32) -> i32 {
    match deg {
        0 => 90,
        90 => 180,
        180 => 270,
        _ => 0,
    }
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

    use camcraft_protocol::ResolutionOption;

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

    #[test]
    fn next_rotation_cw_cycles_90_degrees() {
        assert_eq!(next_rotation_cw(0), 90);
        assert_eq!(next_rotation_cw(90), 180);
        assert_eq!(next_rotation_cw(180), 270);
        assert_eq!(next_rotation_cw(270), 0);
        assert_eq!(next_rotation_cw(45), 0);
    }

    #[test]
    fn resolution_options_for_camera_uses_capabilities_and_falls_back() {
        let fallback = panels::resolution_options_for_camera(None);
        assert_eq!(fallback.len(), STANDARD_RESOLUTIONS.len());
        assert_eq!(fallback[1].0, 1920);
        assert_eq!(fallback[1].1, 1080);

        let cam = CameraInfo {
            id: "1".to_owned(),
            facing: "front".to_owned(),
            label: "Front".to_owned(),
            resolutions: vec![
                ResolutionOption {
                    width: 1920,
                    height: 1080,
                    max_fps: 30,
                },
                ResolutionOption {
                    width: 1280,
                    height: 720,
                    max_fps: 60,
                },
                ResolutionOption {
                    width: 1920,
                    height: 1080,
                    max_fps: 60,
                },
            ],
            max_zoom: 2.0,
            ev_range: (-2.0, 2.0),
            has_flash: false,
        };
        let options = panels::resolution_options_for_camera(Some(&cam));
        assert_eq!(options.len(), 2);
        assert_eq!(options[0].0, 1280);
        assert_eq!(options[1].0, 1920);
        assert!(options[1].2.contains("1080p • max 60fps"));
    }

    #[test]
    fn fit_aspect_preserves_ratio_without_overflow() {
        let (w, h) = panels::fit_aspect(800.0, 600.0, 16.0 / 9.0);
        assert!((w - 800.0).abs() < 0.01);
        assert!((h - 450.0).abs() < 0.01);

        let (w2, h2) = panels::fit_aspect(400.0, 600.0, 16.0 / 9.0);
        assert!((w2 - 400.0).abs() < 0.01);
        assert!((h2 - 225.0).abs() < 0.01);

        let (w3, h3) = panels::fit_aspect(1000.0, 500.0, 9.0 / 16.0);
        assert!((h3 - 500.0).abs() < 0.01);
        assert!((w3 - 281.25).abs() < 0.01);
    }
}
