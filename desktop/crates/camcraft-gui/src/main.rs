//! Native Fedora desktop GUI shell for CamCraft (ArtCraft Studio Dark Theme).

use anyhow::{Result, anyhow};
use eframe::egui;
use tracing::info;

const COLOR_BG_CARBON: egui::Color32 = egui::Color32::from_rgb(0x11, 0x13, 0x18);
const COLOR_SURFACE_CARD: egui::Color32 = egui::Color32::from_rgb(0x1C, 0x20, 0x26);
const COLOR_ACCENT_BLUE: egui::Color32 = egui::Color32::from_rgb(0x3B, 0x82, 0xF6);
const COLOR_STATUS_EMERALD: egui::Color32 = egui::Color32::from_rgb(0x10, 0xB9, 0x81);
const COLOR_TEXT_PRIMARY: egui::Color32 = egui::Color32::from_rgb(0xF3, 0xF4, 0xF6);
const COLOR_TEXT_MUTED: egui::Color32 = egui::Color32::from_rgb(0x9C, 0xA3, 0xAF);

fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    info!(
        protocol_version = camcraft_protocol::PROTOCOL_VERSION,
        "Launching CamCraft GUI shell"
    );

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("CamCraft — ArtCraft Studio")
            .with_inner_size([1180.0, 720.0])
            .with_min_inner_size([900.0, 560.0]),
        ..Default::default()
    };

    eframe::run_native(
        "CamCraft",
        options,
        Box::new(|cc| Ok(Box::new(CamCraftApp::new(&cc.egui_ctx)))),
    )
    .map_err(|err| anyhow!("failed to run CamCraft GUI: {err}"))
}

struct CamCraftApp {
    vcam_device: String,
    wifi_endpoint: String,
}

impl CamCraftApp {
    fn new(ctx: &egui::Context) -> Self {
        apply_artcraft_dark_theme(ctx);
        Self {
            vcam_device: "/dev/video10".to_owned(),
            wifi_endpoint: format!("192.168.1.100:{}", camcraft_protocol::DEFAULT_PORT),
        }
    }
}

impl eframe::App for CamCraftApp {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        egui::CentralPanel::default()
            .frame(egui::Frame::central_panel(ui.style()).fill(COLOR_BG_CARBON))
            .show(ui, |ui| {
                ui.horizontal(|ui| {
                    ui.heading(
                        egui::RichText::new("CamCraft Studio")
                            .color(COLOR_TEXT_PRIMARY)
                            .strong(),
                    );
                    ui.add_space(12.0);
                    ui.label(
                        egui::RichText::new("STANDBY")
                            .color(COLOR_STATUS_EMERALD)
                            .strong(),
                    );
                });
                ui.add_space(12.0);

                let available = ui.available_size();
                let preview_width = (available.x * 0.68).max(360.0);

                ui.horizontal_top(|ui| {
                    egui::Frame::new()
                        .fill(COLOR_SURFACE_CARD)
                        .corner_radius(8.0)
                        .inner_margin(16.0)
                        .show(ui, |ui| {
                            ui.set_min_size(egui::vec2(preview_width, available.y - 16.0));
                            ui.centered_and_justified(|ui| {
                                ui.label(
                                    egui::RichText::new(format!(
                                        "Video Preview Canvas (1080p / 4K @ 60fps)\nTarget Sink: {}",
                                        self.vcam_device
                                    ))
                                    .color(COLOR_TEXT_MUTED),
                                );
                            });
                        });

                    ui.add_space(12.0);

                    ui.vertical(|ui| {
                        egui::Frame::new()
                            .fill(COLOR_SURFACE_CARD)
                            .corner_radius(8.0)
                            .inner_margin(16.0)
                            .show(ui, |ui| {
                                ui.set_min_width(ui.available_width());
                                ui.label(
                                    egui::RichText::new("Connection")
                                        .color(COLOR_TEXT_PRIMARY)
                                        .strong(),
                                );
                                ui.add_space(8.0);
                                let connect_btn = egui::Button::new(
                                    egui::RichText::new("Connect via USB (ADB)")
                                        .color(COLOR_TEXT_PRIMARY),
                                )
                                .fill(COLOR_ACCENT_BLUE);
                                let _ = ui.add_sized([ui.available_width(), 32.0], connect_btn);
                                ui.add_space(8.0);
                                ui.label(
                                    egui::RichText::new("Wi-Fi IP:Port").color(COLOR_TEXT_MUTED),
                                );
                                ui.text_edit_singleline(&mut self.wifi_endpoint);
                            });

                        ui.add_space(12.0);

                        egui::Frame::new()
                            .fill(COLOR_SURFACE_CARD)
                            .corner_radius(8.0)
                            .inner_margin(16.0)
                            .show(ui, |ui| {
                                ui.set_min_width(ui.available_width());
                                ui.label(
                                    egui::RichText::new("Camera Controls (Stage 5)")
                                        .color(COLOR_TEXT_PRIMARY)
                                        .strong(),
                                );
                                ui.add_space(6.0);
                                ui.label(
                                    egui::RichText::new(
                                        "Lens, Resolution, FPS, Zoom, EV, and Torch controls will connect here.",
                                    )
                                    .color(COLOR_TEXT_MUTED),
                                );
                            });
                    });
                });
            });
    }
}

fn apply_artcraft_dark_theme(ctx: &egui::Context) {
    let mut visuals = egui::Visuals::dark();
    visuals.window_fill = COLOR_BG_CARBON;
    visuals.panel_fill = COLOR_BG_CARBON;
    visuals.extreme_bg_color = egui::Color32::from_rgb(0x0B, 0x0D, 0x11);
    visuals.widgets.noninteractive.bg_fill = COLOR_SURFACE_CARD;
    visuals.widgets.inactive.bg_fill = egui::Color32::from_rgb(0x26, 0x2B, 0x33);
    visuals.widgets.hovered.bg_fill = COLOR_ACCENT_BLUE;
    visuals.selection.bg_fill = COLOR_ACCENT_BLUE;
    ctx.set_visuals(visuals);
}
