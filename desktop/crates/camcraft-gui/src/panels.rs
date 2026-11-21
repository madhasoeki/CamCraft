//! ArtCraft Studio v2 UI panels for `camcraft-gui`:
//! - Top Studio Header & Telemetry Strip
//! - Left 70% Cinema Preview Canvas with Rule-of-Thirds grid & floating quick actions
//! - Right 30% Tabbed Inspector (`Connect` | `Camera` | `Audio`)

use camcraft_protocol::{CameraInfo, ControlCmd};
use eframe::egui::{self, Color32, Pos2, Rect, Stroke, Vec2};

use crate::session::{GuiCommand, PerformanceProfile, UpdateUiState};
use crate::theme::{
    self, COLOR_ACCENT_BLUE, COLOR_BG_CANVAS, COLOR_BG_STAGE, COLOR_BORDER_SUBTLE,
    COLOR_STATUS_AMBER, COLOR_STATUS_EMERALD, COLOR_STATUS_ROSE, COLOR_SURFACE_CARD,
    COLOR_SURFACE_ELEVATED, COLOR_TEXT_MUTED, COLOR_TEXT_PRIMARY, COLOR_TEXT_SECONDARY, StudioIcon,
};
use crate::{CamCraftApp, ConnectionStatus, InspectorTab, STANDARD_RESOLUTIONS};

/// Formats a resolution label cleanly for dropdown display.
#[must_use]
pub(crate) fn format_resolution_label(width: u32, height: u32, max_fps: Option<u32>) -> String {
    let tag = match (width, height) {
        (1280, 720) => "720p",
        (1920, 1080) => "1080p",
        (2560, 1440) => "1440p",
        (3840, 2160) => "4K UHD",
        _ => "",
    };
    match (tag.is_empty(), max_fps) {
        (false, Some(fps)) => format!("{width}x{height} ({tag} • max {fps}fps)"),
        (false, None) => format!("{width}x{height} ({tag})"),
        (true, Some(fps)) => format!("{width}x{height} (max {fps}fps)"),
        (true, None) => format!("{width}x{height}"),
    }
}

/// Returns deduplicated, sorted `(width, height, label)` resolution choices for `cam`,
/// falling back to [`STANDARD_RESOLUTIONS`] when capabilities are unavailable.
#[must_use]
pub(crate) fn resolution_options_for_camera(cam: Option<&CameraInfo>) -> Vec<(u32, u32, String)> {
    let Some(info) = cam else {
        return STANDARD_RESOLUTIONS
            .iter()
            .map(|&(w, h, label)| (w, h, label.to_owned()))
            .collect();
    };

    if info.resolutions.is_empty() {
        return STANDARD_RESOLUTIONS
            .iter()
            .map(|&(w, h, label)| (w, h, label.to_owned()))
            .collect();
    }

    let mut deduped: Vec<(u32, u32, u32)> = Vec::new();
    for opt in &info.resolutions {
        if opt.width == 0 || opt.height == 0 {
            continue;
        }
        if let Some(existing) = deduped
            .iter_mut()
            .find(|(w, h, _)| *w == opt.width && *h == opt.height)
        {
            existing.2 = existing.2.max(opt.max_fps);
        } else {
            deduped.push((opt.width, opt.height, opt.max_fps));
        }
    }

    if deduped.is_empty() {
        return STANDARD_RESOLUTIONS
            .iter()
            .map(|&(w, h, label)| (w, h, label.to_owned()))
            .collect();
    }

    deduped.sort_by_key(|&(w, h, _)| (w.saturating_mul(h), w));
    deduped
        .into_iter()
        .map(|(w, h, max_fps)| (w, h, format_resolution_label(w, h, Some(max_fps))))
        .collect()
}

/// Computes `(width, height)` fitted within `avail_w x avail_h` preserving `aspect`.
#[must_use]
pub(crate) fn fit_aspect(avail_w: f32, avail_h: f32, aspect: f32) -> (f32, f32) {
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

/// Renders the Top Studio Header & Telemetry Strip.
pub(crate) fn render_telemetry_bar(app: &mut CamCraftApp, ui: &mut egui::Ui) {
    egui::Frame::new()
        .fill(COLOR_SURFACE_CARD)
        .stroke(Stroke::new(1.0, COLOR_BORDER_SUBTLE))
        .corner_radius(8.0)
        .inner_margin(egui::Margin::symmetric(12, 8))
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.horizontal(|ui| {
                // Brand mark: CamCraft + STUDIO tag
                theme::icon_widget(ui, 18.0, StudioIcon::BrandMark, COLOR_ACCENT_BLUE);
                ui.label(
                    egui::RichText::new("CamCraft")
                        .color(COLOR_TEXT_PRIMARY)
                        .size(15.0)
                        .strong(),
                );
                theme::pill_badge(
                    ui,
                    "STUDIO",
                    COLOR_ACCENT_BLUE,
                    COLOR_ACCENT_BLUE.gamma_multiply(0.14),
                );

                separator_dot(ui);

                // Connection status pill
                let (conn_dot, conn_text) = match app.status {
                    ConnectionStatus::Connected(mode) => (
                        COLOR_STATUS_EMERALD,
                        format!("Connected ({})", mode.label()),
                    ),
                    ConnectionStatus::Connecting(mode) => (
                        COLOR_STATUS_AMBER,
                        format!("Connecting ({})...", mode.label()),
                    ),
                    ConnectionStatus::Reconnecting { mode, attempt } => (
                        COLOR_STATUS_AMBER,
                        format!("Reconnecting ({} #{attempt})", mode.label()),
                    ),
                    ConnectionStatus::Disconnected => (COLOR_TEXT_MUTED, "Standby".to_owned()),
                };
                theme::pill_badge_dot(
                    ui,
                    &conn_text,
                    conn_dot,
                    if app.is_connected() {
                        COLOR_TEXT_PRIMARY
                    } else {
                        COLOR_TEXT_SECONDARY
                    },
                    COLOR_BG_STAGE,
                );

                let dev = app.device_name.as_deref().unwrap_or("No Device");
                ui.label(egui::RichText::new(dev).color(COLOR_TEXT_PRIMARY).strong());

                separator_dot(ui);

                // Tabular monospace stream telemetry
                let (w, h) = if app.frame_width > 0 && app.frame_height > 0 {
                    (app.frame_width, app.frame_height)
                } else {
                    (app.selected_width, app.selected_height)
                };
                ui.label(
                    egui::RichText::new(format!("{w}x{h} • {:.1} FPS", app.stats.fps))
                        .color(COLOR_TEXT_PRIMARY)
                        .monospace(),
                );

                separator_dot(ui);

                ui.label(
                    egui::RichText::new(format!("{:.2} Mbps", app.stats.bitrate_mbps))
                        .color(COLOR_ACCENT_BLUE)
                        .monospace(),
                );

                separator_dot(ui);

                let decode_color = theme::decode_latency_color(app.decode_time_ms);
                ui.label(
                    egui::RichText::new(format!("Decode {:.1} ms", app.decode_time_ms))
                        .color(decode_color)
                        .monospace(),
                );

                separator_dot(ui);

                ui.label(
                    egui::RichText::new(format!("RTT {:.1} ms", app.stats.rtt_ms))
                        .color(COLOR_TEXT_SECONDARY)
                        .monospace(),
                );

                separator_dot(ui);

                let (vcam_label, vcam_dot) = if app.vcam_active && app.vcam_standby {
                    (format!("{} Standby", app.vcam_device), COLOR_STATUS_AMBER)
                } else if app.vcam_active {
                    (format!("{} Active", app.vcam_device), COLOR_STATUS_EMERALD)
                } else {
                    (format!("{} Inactive", app.vcam_device), COLOR_TEXT_MUTED)
                };
                theme::pill_badge_dot(
                    ui,
                    &vcam_label,
                    vcam_dot,
                    COLOR_TEXT_SECONDARY,
                    COLOR_BG_STAGE,
                );

                let (mic_label, mic_dot) = if !app.is_connected() || !app.vmic_active {
                    ("Mic Standby", COLOR_TEXT_MUTED)
                } else if !app.audio_enabled {
                    ("Mic OFF", COLOR_TEXT_MUTED)
                } else if app.audio_muted {
                    ("Mic Muted", COLOR_STATUS_ROSE)
                } else {
                    ("Mic Active", COLOR_STATUS_EMERALD)
                };
                theme::pill_badge_dot(ui, mic_label, mic_dot, COLOR_TEXT_SECONDARY, COLOR_BG_STAGE);

                // Right-aligned quick Performance Mode pill
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let (perf_label, perf_color) = if !app.preview_enabled {
                        ("Preview Off", COLOR_STATUS_AMBER)
                    } else {
                        (app.perf_profile.label(), COLOR_STATUS_EMERALD)
                    };
                    let perf_resp = theme::icon_action_button(
                        ui,
                        StudioIcon::Bolt,
                        perf_label,
                        COLOR_SURFACE_ELEVATED,
                        perf_color,
                        Vec2::new(108.0, 26.0),
                    )
                    .on_hover_text(
                        "Preview performance profile (does not affect /dev/video10 quality)",
                    );

                    egui::Popup::menu(&perf_resp).show(|ui| {
                        ui.set_min_width(195.0);
                        for profile in [
                            PerformanceProfile::Eco,
                            PerformanceProfile::Balanced,
                            PerformanceProfile::Crisp,
                        ] {
                            let selected = app.preview_enabled && app.perf_profile == profile;
                            let label = format!("{} — {}", profile.label(), profile.description());
                            if ui.selectable_label(selected, label).clicked() {
                                app.set_performance_profile(profile);
                                if !app.preview_enabled {
                                    app.set_preview_enabled(true);
                                }
                            }
                        }
                        ui.separator();
                        let toggle_label = if app.preview_enabled {
                            "Preview Off (Pause [P])"
                        } else {
                            "Resume Live Preview [P]"
                        };
                        if ui
                            .selectable_label(!app.preview_enabled, toggle_label)
                            .clicked()
                        {
                            app.toggle_preview();
                        }
                    });
                });
            });

            if let Some(ref msg) = app.status_message {
                ui.add_space(4.0);
                let msg_color = if msg.to_ascii_lowercase().contains("error") {
                    COLOR_STATUS_ROSE
                } else {
                    COLOR_STATUS_AMBER
                };
                ui.label(egui::RichText::new(msg).color(msg_color).small());
            }
        });
}

/// Renders a compact non-intrusive update notification/progress banner below the telemetry header.
pub(crate) fn render_update_banner(app: &mut CamCraftApp, ui: &mut egui::Ui) {
    if app.update_banner_dismissed {
        return;
    }

    let state = app.update_state.clone();
    let should_render = matches!(
        state,
        UpdateUiState::Available(_)
            | UpdateUiState::Downloading { .. }
            | UpdateUiState::Installing { .. }
            | UpdateUiState::RestartRequired { .. }
            | UpdateUiState::Failed { .. }
    );
    if !should_render {
        return;
    }

    let accent_border = match &state {
        UpdateUiState::RestartRequired { .. } => COLOR_STATUS_EMERALD.gamma_multiply(0.5),
        UpdateUiState::Failed { .. } => COLOR_STATUS_ROSE.gamma_multiply(0.55),
        _ => COLOR_ACCENT_BLUE.gamma_multiply(0.5),
    };

    ui.add_space(6.0);
    egui::Frame::new()
        .fill(COLOR_SURFACE_CARD)
        .stroke(Stroke::new(1.0, accent_border))
        .corner_radius(8.0)
        .inner_margin(egui::Margin::symmetric(12, 7))
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            match state {
                UpdateUiState::Available(release) => {
                    ui.horizontal(|ui| {
                        theme::icon_widget(ui, 15.0, StudioIcon::Download, COLOR_ACCENT_BLUE);
                        theme::pill_badge(
                            ui,
                            &format!("Update v{}", release.version),
                            COLOR_ACCENT_BLUE,
                            COLOR_ACCENT_BLUE.gamma_multiply(0.16),
                        );
                        let summary = summarize_release_notes(&release.release_notes);
                        if !summary.is_empty() {
                            ui.label(
                                egui::RichText::new(summary)
                                    .color(COLOR_TEXT_SECONDARY)
                                    .small(),
                            );
                        } else {
                            ui.label(
                                egui::RichText::new(
                                    "A newer CamCraft release is ready to install.",
                                )
                                .color(COLOR_TEXT_SECONDARY)
                                .small(),
                            );
                        }

                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            let update_btn = egui::Button::new(
                                egui::RichText::new("Update Now")
                                    .color(COLOR_TEXT_PRIMARY)
                                    .strong(),
                            )
                            .fill(COLOR_ACCENT_BLUE);
                            if ui.add_sized([102.0, 24.0], update_btn).clicked() {
                                app.session.send(GuiCommand::InstallUpdate(release));
                            }
                            if ui
                                .add_sized(
                                    [56.0, 24.0],
                                    egui::Button::new(
                                        egui::RichText::new("Later")
                                            .color(COLOR_TEXT_SECONDARY)
                                            .small(),
                                    )
                                    .fill(COLOR_SURFACE_ELEVATED),
                                )
                                .clicked()
                            {
                                app.update_banner_dismissed = true;
                            }
                        });
                    });
                }
                UpdateUiState::Downloading {
                    version,
                    progress,
                    downloaded_bytes,
                    total_bytes,
                } => {
                    ui.horizontal(|ui| {
                        theme::icon_widget(ui, 15.0, StudioIcon::Download, COLOR_ACCENT_BLUE);
                        ui.label(
                            egui::RichText::new(format!("Downloading CamCraft v{version}..."))
                                .color(COLOR_TEXT_PRIMARY)
                                .strong(),
                        );
                        let dl_mb = downloaded_bytes as f64 / (1024.0 * 1024.0);
                        let total_mb = total_bytes as f64 / (1024.0 * 1024.0);
                        ui.label(
                            egui::RichText::new(format!("{dl_mb:.1} / {total_mb:.1} MB"))
                                .color(COLOR_TEXT_SECONDARY)
                                .monospace()
                                .small(),
                        );
                        ui.add_space(6.0);
                        ui.add(
                            egui::ProgressBar::new(progress)
                                .desired_width(ui.available_width().max(120.0))
                                .fill(COLOR_ACCENT_BLUE)
                                .show_percentage(),
                        );
                    });
                }
                UpdateUiState::Installing { version } => {
                    ui.horizontal(|ui| {
                        ui.spinner();
                        ui.label(
                            egui::RichText::new(format!(
                                "Verifying SHA-256 & installing v{version}..."
                            ))
                            .color(COLOR_TEXT_PRIMARY)
                            .strong(),
                        );
                    });
                }
                UpdateUiState::RestartRequired { version } => {
                    ui.horizontal(|ui| {
                        theme::pill_badge(
                            ui,
                            &format!("Updated to v{version}"),
                            COLOR_STATUS_EMERALD,
                            COLOR_STATUS_EMERALD.gamma_multiply(0.16),
                        );
                        ui.label(
                            egui::RichText::new(
                                "Restart CamCraft to switch to the updated binary.",
                            )
                            .color(COLOR_TEXT_PRIMARY)
                            .small(),
                        );
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            let restart_btn = egui::Button::new(
                                egui::RichText::new("Restart Now")
                                    .color(COLOR_BG_STAGE)
                                    .strong(),
                            )
                            .fill(COLOR_STATUS_EMERALD);
                            if ui.add_sized([104.0, 24.0], restart_btn).clicked() {
                                app.restart_after_update(ui.ctx());
                            }
                            if ui
                                .add_sized(
                                    [56.0, 24.0],
                                    egui::Button::new(
                                        egui::RichText::new("Later")
                                            .color(COLOR_TEXT_SECONDARY)
                                            .small(),
                                    )
                                    .fill(COLOR_SURFACE_ELEVATED),
                                )
                                .clicked()
                            {
                                app.update_banner_dismissed = true;
                            }
                        });
                    });
                }
                UpdateUiState::Failed {
                    message,
                    retry_release,
                } => {
                    ui.horizontal(|ui| {
                        ui.label(
                            egui::RichText::new(format!("Update failed: {message}"))
                                .color(COLOR_STATUS_ROSE)
                                .small(),
                        );
                        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                            if ui
                                .add_sized(
                                    [62.0, 24.0],
                                    egui::Button::new(
                                        egui::RichText::new("Dismiss")
                                            .color(COLOR_TEXT_SECONDARY)
                                            .small(),
                                    )
                                    .fill(COLOR_SURFACE_ELEVATED),
                                )
                                .clicked()
                            {
                                app.update_banner_dismissed = true;
                            }
                            if ui
                                .add_sized(
                                    [62.0, 24.0],
                                    egui::Button::new(
                                        egui::RichText::new("Retry")
                                            .color(COLOR_TEXT_PRIMARY)
                                            .small(),
                                    )
                                    .fill(COLOR_ACCENT_BLUE),
                                )
                                .clicked()
                            {
                                if let Some(rel) = retry_release {
                                    app.session.send(GuiCommand::InstallUpdate(rel));
                                } else {
                                    app.session
                                        .send(GuiCommand::CheckForUpdates { silent: false });
                                }
                            }
                        });
                    });
                }
                UpdateUiState::Idle | UpdateUiState::Checking | UpdateUiState::UpToDate => {}
            }
        });
}

fn summarize_release_notes(notes: &str) -> String {
    let first_line = notes
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty() && !line.starts_with('#') && !line.starts_with("---"))
        .unwrap_or("")
        .trim_start_matches(['-', '*', ' ']);
    let mut out: String = first_line.chars().take(76).collect();
    if first_line.chars().count() > 76 {
        out.push_str("...");
    }
    out
}

/// Renders the Left 70% Cinema Preview Canvas.
pub(crate) fn render_preview_canvas(app: &mut CamCraftApp, ui: &mut egui::Ui, size: Vec2) {
    let inner_w = (size.x - 4.0).max(160.0);
    let inner_h = (size.y - 4.0).max(140.0);

    theme::stage_frame().show(ui, |ui| {
        ui.set_min_size(Vec2::new(inner_w, inner_h));
        ui.set_max_size(Vec2::new(inner_w, inner_h));

        let stage_rect = ui.available_rect_before_wrap();
        let _ = ui.allocate_rect(stage_rect, egui::Sense::hover());

        if app.is_connected() && !app.preview_enabled {
            render_paused_preview_card(app, ui, stage_rect);
        } else if app.is_connected()
            && let Some(ref tex) = app.preview_texture
        {
            let tex_size = tex.size_vec2();
            let swapped = app.rotation_deg == 90 || app.rotation_deg == 270;
            let logical_aspect = if tex_size.y > 0.0 {
                if swapped {
                    tex_size.y / tex_size.x
                } else {
                    tex_size.x / tex_size.y
                }
            } else {
                16.0 / 9.0
            };

            let padded_w = (stage_rect.width() - 16.0).max(80.0);
            let padded_h = (stage_rect.height() - 16.0).max(80.0);
            let (draw_w, draw_h) = fit_aspect(padded_w, padded_h, logical_aspect);
            let center = stage_rect.center();

            let uv = if app.mirror_horizontal {
                Rect::from_min_max(Pos2::new(1.0, 0.0), Pos2::new(0.0, 1.0))
            } else {
                Rect::from_min_max(Pos2::new(0.0, 0.0), Pos2::new(1.0, 1.0))
            };

            let (img_w, img_h) = if swapped {
                (draw_h, draw_w)
            } else {
                (draw_w, draw_h)
            };
            let img_rect = Rect::from_center_size(center, Vec2::new(img_w, img_h));

            let mut image = egui::Image::new((tex.id(), Vec2::new(img_w, img_h))).uv(uv);
            if app.rotation_deg != 0 {
                let radians = (app.rotation_deg as f32).to_radians();
                image = image.rotate(radians, Vec2::splat(0.5));
            }
            image.paint_at(ui, img_rect);

            if app.show_grid {
                let visual_rect = Rect::from_center_size(center, Vec2::new(draw_w, draw_h));
                paint_rule_of_thirds(ui.painter(), visual_rect);
            }

            render_live_canvas_overlays(app, ui, stage_rect);
        } else if let ConnectionStatus::Reconnecting { mode, attempt } = app.status {
            render_reconnecting_stage_card(app, ui, stage_rect, mode.label(), attempt);
        } else if app.is_connected() {
            render_centered_stage_card(ui, stage_rect, |ui| {
                ui.vertical_centered(|ui| {
                    theme::icon_widget(ui, 26.0, StudioIcon::Camera, COLOR_ACCENT_BLUE);
                    ui.add_space(8.0);
                    ui.label(
                        egui::RichText::new("Connected — Waiting for H.264 Keyframe")
                            .color(COLOR_TEXT_PRIMARY)
                            .strong(),
                    );
                    ui.add_space(4.0);
                    ui.label(
                        egui::RichText::new(
                            "Video will appear as soon as the first IDR frame arrives.",
                        )
                        .color(COLOR_TEXT_MUTED)
                        .small(),
                    );
                });
            });
        } else {
            render_disconnected_quickstart_card(app, ui, stage_rect);
        }
    });
}

fn paint_rule_of_thirds(painter: &egui::Painter, rect: Rect) {
    let stroke = Stroke::new(1.0, Color32::from_rgba_unmultiplied(248, 250, 252, 68));
    for frac in [1.0 / 3.0, 2.0 / 3.0] {
        let x = rect.min.x + rect.width() * frac;
        let y = rect.min.y + rect.height() * frac;
        painter.line_segment([Pos2::new(x, rect.min.y), Pos2::new(x, rect.max.y)], stroke);
        painter.line_segment([Pos2::new(rect.min.x, y), Pos2::new(rect.max.x, y)], stroke);
    }
}

fn render_live_canvas_overlays(app: &mut CamCraftApp, ui: &mut egui::Ui, stage_rect: Rect) {
    // Top-left LIVE pill
    let top_left_rect = Rect::from_min_size(
        Pos2::new(stage_rect.min.x + 12.0, stage_rect.min.y + 12.0),
        Vec2::new(220.0, 30.0),
    );
    let mut tl_ui = ui.new_child(
        egui::UiBuilder::new()
            .id_salt("canvas_tl_overlay")
            .max_rect(top_left_rect),
    );
    tl_ui.horizontal(|ui| {
        let (w, h) = if app.frame_width > 0 && app.frame_height > 0 {
            (app.frame_width, app.frame_height)
        } else {
            (app.selected_width, app.selected_height)
        };
        let pill_bg = Color32::from_rgba_unmultiplied(0x0D, 0x0F, 0x14, 215);
        theme::pill_badge_dot(
            ui,
            &format!("LIVE • {w}x{h}"),
            COLOR_STATUS_EMERALD,
            COLOR_TEXT_PRIMARY,
            pill_bg,
        );
    });

    // Top-right Rule-of-Thirds Grid toggle
    let top_right_rect = Rect::from_min_size(
        Pos2::new(stage_rect.max.x - 94.0, stage_rect.min.y + 12.0),
        Vec2::new(82.0, 30.0),
    );
    let mut tr_ui = ui.new_child(
        egui::UiBuilder::new()
            .id_salt("canvas_tr_overlay")
            .max_rect(top_right_rect)
            .layout(egui::Layout::right_to_left(egui::Align::Center)),
    );
    if theme::pill_toggle_chip(&mut tr_ui, app.show_grid, Some(StudioIcon::Grid), "Grid")
        .on_hover_text("Toggle Rule-of-Thirds composition grid")
        .clicked()
    {
        app.show_grid = !app.show_grid;
    }

    // Bottom-right compact floating quick-action bar
    let bar_rect = Rect::from_min_max(
        Pos2::new(stage_rect.min.x + 12.0, stage_rect.max.y - 46.0),
        Pos2::new(stage_rect.max.x - 12.0, stage_rect.max.y - 12.0),
    );
    let mut br_ui = ui.new_child(
        egui::UiBuilder::new()
            .id_salt("canvas_br_overlay")
            .max_rect(bar_rect)
            .layout(egui::Layout::right_to_left(egui::Align::Center)),
    );
    egui::Frame::new()
        .fill(Color32::from_rgba_unmultiplied(0x16, 0x1A, 0x22, 228))
        .stroke(Stroke::new(1.0, COLOR_BORDER_SUBTLE))
        .corner_radius(8.0)
        .inner_margin(egui::Margin::symmetric(6, 4))
        .show(&mut br_ui, |ui| {
            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing.x = 6.0;

                let pause_resp = theme::pill_toggle_chip(
                    ui,
                    false,
                    Some(StudioIcon::Pause),
                    "Pause Preview [P]",
                )
                .on_hover_text("Pause GPU preview for lowest CPU/GPU usage (P)");
                if pause_resp.clicked() {
                    app.toggle_preview();
                }

                let mirror_resp = theme::pill_toggle_chip(
                    ui,
                    app.mirror_horizontal,
                    Some(StudioIcon::Mirror),
                    "Mirror [H]",
                )
                .on_hover_text("Flip video horizontally on preview & /dev/video10 (H)");
                if mirror_resp.clicked() {
                    app.toggle_mirror();
                }

                let rot_resp = theme::pill_toggle_chip(
                    ui,
                    app.rotation_deg != 0,
                    Some(StudioIcon::Rotate),
                    &format!("Rotate {}° [R]", app.rotation_deg),
                )
                .on_hover_text("Rotate +90° clockwise on preview & /dev/video10 (R)");
                if rot_resp.clicked() {
                    app.rotate_cw_90();
                }

                ui.add_enabled_ui(app.audio_enabled, |ui| {
                    let mute_icon = if app.audio_muted {
                        StudioIcon::MicOff
                    } else {
                        StudioIcon::Mic
                    };
                    let mute_label = if app.audio_muted {
                        "Unmute [M]"
                    } else {
                        "Mute [M]"
                    };
                    let mute_resp =
                        theme::pill_toggle_chip(ui, app.audio_muted, Some(mute_icon), mute_label)
                            .on_hover_text("Mute / unmute virtual microphone (M)");
                    if mute_resp.clicked() {
                        app.toggle_mute();
                    }
                });
            });
        });
}

fn render_paused_preview_card(app: &mut CamCraftApp, ui: &mut egui::Ui, stage_rect: Rect) {
    render_centered_stage_card(ui, stage_rect, |ui| {
        ui.vertical_centered(|ui| {
            ui.horizontal(|ui| {
                theme::icon_widget(ui, 18.0, StudioIcon::Bolt, COLOR_STATUS_EMERALD);
                ui.label(
                    egui::RichText::new("Preview Paused — Eco / OBS Low-Latency Mode")
                        .color(COLOR_TEXT_PRIMARY)
                        .size(15.0)
                        .strong(),
                );
            });
            ui.add_space(8.0);

            let (w, h) = if app.frame_width > 0 && app.frame_height > 0 {
                (app.frame_width, app.frame_height)
            } else {
                (app.selected_width, app.selected_height)
            };
            ui.label(
                egui::RichText::new(format!(
                    "{w}x{h} • {:.1} FPS • {:.2} Mbps",
                    app.stats.fps, app.stats.bitrate_mbps
                ))
                .color(COLOR_ACCENT_BLUE)
                .monospace()
                .strong(),
            );
            ui.add_space(4.0);

            let vcam_status = if app.vcam_active {
                format!("{} Active (OBS / Browser / Discord Ready)", app.vcam_device)
            } else {
                format!("{} Inactive", app.vcam_device)
            };
            ui.label(
                egui::RichText::new(vcam_status)
                    .color(if app.vcam_active {
                        COLOR_STATUS_EMERALD
                    } else {
                        COLOR_TEXT_MUTED
                    })
                    .small(),
            );
            ui.add_space(12.0);
            if theme::icon_action_button(
                ui,
                StudioIcon::Play,
                "Resume Preview [P]",
                COLOR_ACCENT_BLUE,
                COLOR_TEXT_PRIMARY,
                Vec2::new(175.0, 30.0),
            )
            .clicked()
            {
                app.set_preview_enabled(true);
            }
        });
    });
}

fn render_reconnecting_stage_card(
    app: &CamCraftApp,
    ui: &mut egui::Ui,
    stage_rect: Rect,
    mode_label: &str,
    attempt: u32,
) {
    render_centered_stage_card(ui, stage_rect, |ui| {
        ui.vertical_centered(|ui| {
            theme::pill_badge_dot(
                ui,
                "Reconnecting",
                COLOR_STATUS_AMBER,
                COLOR_STATUS_AMBER,
                COLOR_BG_STAGE,
            );
            ui.add_space(8.0);
            ui.label(
                egui::RichText::new(format!(
                    "Retrying {mode_label} connection every 2s (attempt #{attempt})"
                ))
                .color(COLOR_TEXT_PRIMARY)
                .strong(),
            );
            if app.vcam_active {
                ui.add_space(4.0);
                ui.label(
                    egui::RichText::new(format!(
                        "{} remains active in Standby Frame mode (OBS/Discord safe)",
                        app.vcam_device
                    ))
                    .color(COLOR_TEXT_MUTED)
                    .small(),
                );
            }
        });
    });
}

fn render_disconnected_quickstart_card(app: &mut CamCraftApp, ui: &mut egui::Ui, stage_rect: Rect) {
    render_centered_stage_card(ui, stage_rect, |ui| {
        ui.set_max_width(420.0);
        ui.horizontal(|ui| {
            theme::icon_widget(ui, 22.0, StudioIcon::BrandMark, COLOR_ACCENT_BLUE);
            ui.label(
                egui::RichText::new("CamCraft Studio Preview")
                    .color(COLOR_TEXT_PRIMARY)
                    .size(16.0)
                    .strong(),
            );
        });
        ui.add_space(8.0);

        step_row(ui, "1", "Start CamCraft Server on your Android phone.");
        ui.add_space(6.0);
        step_row(
            ui,
            "2",
            "Connect via USB (ADB) or pick a Wi-Fi device in the Connect tab.",
        );
        ui.add_space(12.0);

        ui.add_enabled_ui(!app.is_busy(), |ui| {
            if theme::icon_action_button(
                ui,
                StudioIcon::Usb,
                "Quick Connect via USB (ADB)",
                COLOR_ACCENT_BLUE,
                COLOR_TEXT_PRIMARY,
                Vec2::new(ui.available_width().min(240.0), 32.0),
            )
            .clicked()
            {
                app.connect_usb();
            }
        });
    });
}

fn step_row(ui: &mut egui::Ui, num: &str, text: &str) {
    ui.horizontal(|ui| {
        theme::pill_badge(
            ui,
            num,
            COLOR_ACCENT_BLUE,
            COLOR_ACCENT_BLUE.gamma_multiply(0.16),
        );
        ui.label(egui::RichText::new(text).color(COLOR_TEXT_SECONDARY));
    });
}

fn render_centered_stage_card(
    ui: &mut egui::Ui,
    stage_rect: Rect,
    add_contents: impl FnOnce(&mut egui::Ui),
) {
    let card_w = 440.0_f32.min((stage_rect.width() - 32.0).max(200.0));
    let card_h = 190.0_f32.min((stage_rect.height() - 32.0).max(120.0));
    let card_rect = Rect::from_center_size(stage_rect.center(), Vec2::new(card_w, card_h));
    let mut child = ui.new_child(
        egui::UiBuilder::new()
            .id_salt("stage_centered_card")
            .max_rect(card_rect),
    );
    theme::card_frame().show(&mut child, |ui| {
        ui.set_min_width(ui.available_width());
        add_contents(ui);
    });
}

/// Renders the Right 30% Tabbed Inspector (`Connect` | `Camera` | `Audio`).
pub(crate) fn render_inspector_tabs(app: &mut CamCraftApp, ui: &mut egui::Ui) {
    theme::segmented_tab(
        ui,
        &mut app.active_tab,
        &[
            (InspectorTab::Connect, "Connect"),
            (InspectorTab::Camera, "Camera"),
            (InspectorTab::Audio, "Audio"),
        ],
    );
    ui.add_space(10.0);

    egui::ScrollArea::vertical()
        .id_salt("inspector_scroll")
        .auto_shrink([false, false])
        .show(ui, |ui| match app.active_tab {
            InspectorTab::Connect => render_connect_tab(app, ui),
            InspectorTab::Camera => render_camera_tab(app, ui),
            InspectorTab::Audio => render_audio_tab(app, ui),
        });
}

fn render_connect_tab(app: &mut CamCraftApp, ui: &mut egui::Ui) {
    theme::card_frame().show(ui, |ui| {
        ui.set_min_width(ui.available_width());
        theme::section_title(ui, "Device Connection");
        ui.add_space(8.0);

        ui.add_enabled_ui(!app.is_busy(), |ui| {
            let usb_resp = theme::icon_action_button(
                ui,
                StudioIcon::Usb,
                "Connect via USB (ADB)",
                COLOR_ACCENT_BLUE,
                COLOR_TEXT_PRIMARY,
                Vec2::new(ui.available_width(), 32.0),
            )
            .on_hover_text("Runs adb forward tcp:9876 tcp:9876 and connects");
            if usb_resp.clicked() {
                app.connect_usb();
            }
        });

        ui.add_space(10.0);
        ui.horizontal(|ui| {
            theme::icon_widget(ui, 13.0, StudioIcon::Wifi, COLOR_TEXT_MUTED);
            theme::field_label(ui, "Wi-Fi Discovered Devices (mDNS)");
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if theme::icon_action_button(
                    ui,
                    StudioIcon::Refresh,
                    "Refresh",
                    COLOR_SURFACE_ELEVATED,
                    COLOR_TEXT_PRIMARY,
                    Vec2::new(78.0, 24.0),
                )
                .on_hover_text("Send mDNS _camcraft._tcp scan query")
                .clicked()
                {
                    app.session.send(GuiCommand::RefreshWifiDiscovery);
                }
            });
        });
        ui.add_space(4.0);

        let mut connect_discovered_endpoint: Option<String> = None;
        egui::Frame::new()
            .fill(COLOR_BG_STAGE)
            .stroke(Stroke::new(1.0, COLOR_BORDER_SUBTLE))
            .corner_radius(6.0)
            .inner_margin(8.0)
            .show(ui, |ui| {
                ui.set_min_width(ui.available_width());
                if app.discovered_devices.is_empty() {
                    ui.label(
                        egui::RichText::new(
                            "No phone detected yet.\nTap 'Start Server' on CamCraft Android (same Wi-Fi).",
                        )
                        .color(COLOR_TEXT_MUTED)
                        .small(),
                    );
                } else {
                    for (idx, device) in app.discovered_devices.iter().enumerate() {
                        let endpoint = device.endpoint();
                        egui::Frame::new()
                            .fill(COLOR_SURFACE_ELEVATED)
                            .stroke(Stroke::new(1.0, COLOR_BORDER_SUBTLE))
                            .corner_radius(6.0)
                            .inner_margin(8.0)
                            .show(ui, |ui| {
                                ui.set_min_width(ui.available_width());
                                ui.horizontal(|ui| {
                                    let (rect, _) = ui.allocate_exact_size(
                                        Vec2::splat(8.0),
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
                                                .add_enabled(!app.is_busy(), connect_btn)
                                                .clicked()
                                            {
                                                connect_discovered_endpoint =
                                                    Some(endpoint.clone());
                                            }
                                        },
                                    );
                                });
                            });
                        if idx + 1 < app.discovered_devices.len() {
                            ui.add_space(4.0);
                        }
                    }
                }
            });

        if let Some(endpoint) = connect_discovered_endpoint {
            app.connect_wifi(endpoint);
        }

        ui.add_space(10.0);
        theme::field_label(ui, "Manual Phone IP:Port (Enter to connect)");
        let can_connect_wifi = !app.is_busy() && !app.wifi_endpoint.trim().is_empty();
        let mut submit_manual_wifi = false;
        ui.add_enabled_ui(!app.is_busy(), |ui| {
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if ui
                    .add_enabled_ui(can_connect_wifi, |ui| {
                        theme::icon_action_button(
                            ui,
                            StudioIcon::Wifi,
                            "Connect",
                            COLOR_SURFACE_ELEVATED,
                            COLOR_TEXT_PRIMARY,
                            Vec2::new(86.0, 28.0),
                        )
                    })
                    .inner
                    .clicked()
                {
                    submit_manual_wifi = true;
                }
                let edit_resp = ui.add(
                    egui::TextEdit::singleline(&mut app.wifi_endpoint)
                        .hint_text("e.g. 192.168.1.7:9876")
                        .desired_width(ui.available_width()),
                );
                if edit_resp.lost_focus()
                    && ui.input(|i| i.key_pressed(egui::Key::Enter))
                    && can_connect_wifi
                {
                    submit_manual_wifi = true;
                }
            });
        });

        if submit_manual_wifi {
            let endpoint = app.wifi_endpoint.clone();
            app.connect_wifi(endpoint);
        }

        ui.add_space(10.0);
        ui.add_enabled_ui(app.is_busy(), |ui| {
            let disconnect_label = if app.is_reconnecting() {
                "Cancel Reconnect"
            } else {
                "Disconnect"
            };
            let disconnect_btn = egui::Button::new(
                egui::RichText::new(disconnect_label)
                    .color(COLOR_TEXT_PRIMARY)
                    .strong(),
            )
            .fill(if app.is_busy() {
                COLOR_STATUS_ROSE
            } else {
                COLOR_SURFACE_ELEVATED
            });
            if ui
                .add_sized([ui.available_width(), 30.0], disconnect_btn)
                .clicked()
            {
                app.session.send(GuiCommand::Disconnect);
            }
        });
    });

    ui.add_space(10.0);
    theme::card_frame().show(ui, |ui| {
        ui.set_min_width(ui.available_width());
        theme::section_title(ui, "Virtual Camera & Resilience");
        ui.add_space(6.0);

        theme::field_label(ui, "V4L2 Loopback Device");
        ui.add_enabled_ui(!app.is_busy(), |ui| {
            ui.add(egui::TextEdit::singleline(&mut app.vcam_device).desired_width(f32::INFINITY));
            ui.add_space(6.0);
            ui.checkbox(&mut app.auto_reconnect, "Auto-Reconnect on Drop (2s)");
        });
    });

    ui.add_space(10.0);
    theme::card_frame().show(ui, |ui| {
        ui.set_min_width(ui.available_width());
        ui.horizontal(|ui| {
            theme::icon_widget(ui, 14.0, StudioIcon::Bolt, COLOR_ACCENT_BLUE);
            theme::section_title(ui, "Laptop Performance Profile");
        });
        ui.add_space(6.0);

        let mut profile = app.perf_profile;
        if theme::segmented_tab(
            ui,
            &mut profile,
            &[
                (PerformanceProfile::Eco, "Eco"),
                (PerformanceProfile::Balanced, "Balanced"),
                (PerformanceProfile::Crisp, "Crisp"),
            ],
        ) {
            app.set_performance_profile(profile);
        }
        ui.add_space(4.0);
        ui.label(
            egui::RichText::new(app.perf_profile.description())
                .color(COLOR_TEXT_MUTED)
                .small(),
        );
        ui.add_space(8.0);

        let mut pause_preview = !app.preview_enabled;
        if ui
            .checkbox(
                &mut pause_preview,
                "Pause Preview (OBS / Lowest CPU Latency) [P]",
            )
            .changed()
        {
            app.set_preview_enabled(!pause_preview);
        }
    });

    ui.add_space(10.0);
    theme::card_frame().show(ui, |ui| {
        ui.set_min_width(ui.available_width());
        ui.horizontal(|ui| {
            theme::icon_widget(ui, 14.0, StudioIcon::Download, COLOR_ACCENT_BLUE);
            theme::section_title(ui, "App Version & Updates");
        });
        ui.add_space(6.0);

        ui.horizontal(|ui| {
            ui.label(
                egui::RichText::new(format!("CamCraft v{}", env!("CARGO_PKG_VERSION")))
                    .color(COLOR_TEXT_PRIMARY)
                    .monospace()
                    .strong(),
            );
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let (badge_text, badge_color) = match &app.update_state {
                    UpdateUiState::Checking => ("Checking...", COLOR_STATUS_AMBER),
                    UpdateUiState::UpToDate => ("Up to date", COLOR_STATUS_EMERALD),
                    UpdateUiState::Available(r) => {
                        return theme::pill_badge(
                            ui,
                            &format!("v{} available", r.version),
                            COLOR_ACCENT_BLUE,
                            COLOR_ACCENT_BLUE.gamma_multiply(0.16),
                        );
                    }
                    UpdateUiState::Downloading { .. } | UpdateUiState::Installing { .. } => {
                        ("Updating...", COLOR_ACCENT_BLUE)
                    }
                    UpdateUiState::RestartRequired { .. } => {
                        ("Restart ready", COLOR_STATUS_EMERALD)
                    }
                    UpdateUiState::Failed { .. } => ("Check failed", COLOR_STATUS_ROSE),
                    UpdateUiState::Idle => ("Ready", COLOR_TEXT_MUTED),
                };
                theme::pill_badge(
                    ui,
                    badge_text,
                    badge_color,
                    badge_color.gamma_multiply(0.14),
                )
            });
        });

        ui.add_space(8.0);
        let check_busy = matches!(
            app.update_state,
            UpdateUiState::Checking
                | UpdateUiState::Downloading { .. }
                | UpdateUiState::Installing { .. }
        );
        ui.add_enabled_ui(!check_busy, |ui| {
            if theme::icon_action_button(
                ui,
                StudioIcon::Refresh,
                "Check for Updates",
                COLOR_SURFACE_ELEVATED,
                COLOR_TEXT_PRIMARY,
                Vec2::new(ui.available_width(), 28.0),
            )
            .on_hover_text("Check GitHub Releases for a newer CamCraft version")
            .clicked()
            {
                app.update_banner_dismissed = false;
                app.session
                    .send(GuiCommand::CheckForUpdates { silent: false });
            }
        });
    });
}

fn render_camera_tab(app: &mut CamCraftApp, ui: &mut egui::Ui) {
    let connected = app.is_connected();

    theme::card_frame().show(ui, |ui| {
        ui.set_min_width(ui.available_width());
        theme::section_title(ui, "Stream Configuration");
        ui.add_space(8.0);

        ui.add_enabled_ui(connected, |ui| {
            ui.spacing_mut().slider_width = (ui.available_width() - 95.0).max(110.0);

            theme::field_label(ui, "Camera Lens");
            let selected_label = app
                .cameras
                .iter()
                .find(|c| c.id == app.selected_camera_id)
                .map(|c| format!("{} ({})", c.label, c.facing))
                .unwrap_or_else(|| format!("Camera {}", app.selected_camera_id));

            let prev_cam_id = app.selected_camera_id.clone();
            egui::ComboBox::from_id_salt("camera_lens_combo")
                .selected_text(selected_label)
                .width(ui.available_width())
                .show_ui(ui, |ui| {
                    for cam in &app.cameras {
                        let label = format!("{} ({})", cam.label, cam.facing);
                        ui.selectable_value(&mut app.selected_camera_id, cam.id.clone(), label);
                    }
                });

            if app.selected_camera_id != prev_cam_id {
                let next_res = app.active_camera_info().and_then(|cam| {
                    if cam.resolutions.is_empty()
                        || cam.resolutions.iter().any(|r| {
                            r.width == app.selected_width && r.height == app.selected_height
                        })
                    {
                        None
                    } else {
                        cam.resolutions
                            .iter()
                            .find(|r| r.width == 1920 && r.height == 1080)
                            .or_else(|| cam.resolutions.last())
                            .copied()
                    }
                });
                if let Some(res) = next_res {
                    app.selected_width = res.width;
                    app.selected_height = res.height;
                    if res.max_fps < 60 && app.selected_fps > 30 {
                        app.selected_fps = 30;
                    }
                }
            }

            ui.add_space(8.0);

            theme::field_label(ui, "Resolution");
            let resolution_options = resolution_options_for_camera(app.active_camera_info());
            let selected_res_max_fps = app.active_camera_info().and_then(|cam| {
                cam.resolutions
                    .iter()
                    .find(|r| r.width == app.selected_width && r.height == app.selected_height)
                    .map(|r| r.max_fps)
            });
            let res_label = format_resolution_label(
                app.selected_width,
                app.selected_height,
                selected_res_max_fps,
            );

            egui::ComboBox::from_id_salt("resolution_combo")
                .selected_text(res_label)
                .width(ui.available_width())
                .show_ui(ui, |ui| {
                    for (w, h, label) in &resolution_options {
                        let is_sel = app.selected_width == *w && app.selected_height == *h;
                        if ui.selectable_label(is_sel, label).clicked() {
                            app.selected_width = *w;
                            app.selected_height = *h;
                        }
                    }
                });

            ui.add_space(8.0);

            theme::field_label(ui, "Frame Rate");
            theme::segmented_tab(ui, &mut app.selected_fps, &[(30, "30 FPS"), (60, "60 FPS")]);
            if let Some(max_fps) = selected_res_max_fps
                && max_fps < app.selected_fps
            {
                ui.add_space(2.0);
                ui.label(
                    egui::RichText::new(format!(
                        "Sensor reports max {max_fps} FPS at {}x{}",
                        app.selected_width, app.selected_height
                    ))
                    .color(COLOR_STATUS_AMBER)
                    .small(),
                );
            }

            ui.add_space(8.0);

            ui.horizontal(|ui| {
                theme::field_label(ui, "Bitrate");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    ui.label(
                        egui::RichText::new(format!("{} Mbps", app.bitrate_mbps))
                            .color(COLOR_TEXT_PRIMARY)
                            .monospace()
                            .small()
                            .strong(),
                    );
                });
            });
            ui.add(
                egui::Slider::new(&mut app.bitrate_mbps, 4..=40)
                    .suffix(" Mbps")
                    .step_by(1.0),
            );
            ui.horizontal(|ui| {
                for preset_mbps in [6_u32, 12, 20] {
                    if theme::pill_toggle_chip(
                        ui,
                        app.bitrate_mbps == preset_mbps,
                        None,
                        &format!("{preset_mbps}M"),
                    )
                    .clicked()
                    {
                        app.bitrate_mbps = preset_mbps;
                    }
                }
            });

            ui.add_space(10.0);

            let has_pending = app.has_pending_stream_changes();
            let apply_label = if has_pending {
                "Apply Stream Settings • Pending"
            } else {
                "Apply Stream Settings"
            };
            let apply_fill = if has_pending {
                COLOR_ACCENT_BLUE
            } else {
                COLOR_SURFACE_ELEVATED
            };
            let apply_btn = egui::Button::new(
                egui::RichText::new(apply_label)
                    .color(COLOR_TEXT_PRIMARY)
                    .strong(),
            )
            .fill(apply_fill)
            .stroke(Stroke::new(
                1.0,
                if has_pending {
                    COLOR_ACCENT_BLUE
                } else {
                    COLOR_BORDER_SUBTLE
                },
            ));
            if ui
                .add_sized([ui.available_width(), 32.0], apply_btn)
                .clicked()
            {
                app.session
                    .send(GuiCommand::SendControlCmd(ControlCmd::ConfigureStream {
                        camera_id: app.selected_camera_id.clone(),
                        width: app.selected_width,
                        height: app.selected_height,
                        fps: app.selected_fps,
                        bitrate_kbps: app.bitrate_mbps * 1000,
                    }));
            }
        });
    });

    ui.add_space(10.0);

    let (max_zoom, min_ev, max_ev, has_flash) = match app.active_camera_info() {
        Some(cam) => (
            cam.max_zoom.max(1.0),
            cam.ev_range.0,
            cam.ev_range.1,
            cam.has_flash,
        ),
        None => (5.0, -2.0, 2.0, true),
    };

    theme::card_frame().show(ui, |ui| {
        ui.set_min_width(ui.available_width());
        theme::section_title(ui, "Live Image Adjustments");
        ui.add_space(8.0);

        ui.add_enabled_ui(connected, |ui| {
            ui.spacing_mut().slider_width = (ui.available_width() - 95.0).max(110.0);

            ui.horizontal(|ui| {
                theme::field_label(ui, "Zoom");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    ui.label(
                        egui::RichText::new(format!("{:.2}x", app.zoom_ratio))
                            .color(COLOR_TEXT_PRIMARY)
                            .monospace()
                            .small()
                            .strong(),
                    );
                });
            });
            if ui
                .add(
                    egui::Slider::new(&mut app.zoom_ratio, 1.0..=max_zoom)
                        .suffix("x")
                        .fixed_decimals(2),
                )
                .changed()
            {
                app.mark_params_dirty();
            }
            ui.horizontal(|ui| {
                for z in [1.0_f32, 2.0, 3.0] {
                    let allowed = z <= max_zoom + 0.01;
                    ui.add_enabled_ui(allowed, |ui| {
                        let active = (app.zoom_ratio - z).abs() < 0.05;
                        if theme::pill_toggle_chip(ui, active, None, &format!("{z:.0}x")).clicked()
                        {
                            app.zoom_ratio = z.min(max_zoom);
                            app.mark_params_dirty();
                        }
                    });
                }
            });

            ui.add_space(8.0);

            let ev_range_valid = max_ev > min_ev;
            ui.horizontal(|ui| {
                theme::field_label(ui, "Exposure (EV)");
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui
                        .add_enabled(
                            ev_range_valid && app.exposure_ev.abs() > 0.01,
                            egui::Button::new(
                                egui::RichText::new("Reset 0.0")
                                    .color(COLOR_TEXT_SECONDARY)
                                    .small(),
                            )
                            .fill(COLOR_SURFACE_ELEVATED),
                        )
                        .clicked()
                    {
                        app.exposure_ev = 0.0;
                        app.mark_params_dirty();
                    }
                    ui.label(
                        egui::RichText::new(format!("{:+.1} EV", app.exposure_ev))
                            .color(COLOR_TEXT_PRIMARY)
                            .monospace()
                            .small()
                            .strong(),
                    );
                });
            });
            let ev_min = if ev_range_valid { min_ev } else { -2.0 };
            let ev_max = if ev_range_valid { max_ev } else { 2.0 };
            if ui
                .add_enabled(
                    ev_range_valid,
                    egui::Slider::new(&mut app.exposure_ev, ev_min..=ev_max)
                        .suffix(" EV")
                        .fixed_decimals(1),
                )
                .changed()
            {
                app.mark_params_dirty();
            }

            ui.add_space(8.0);
            theme::field_label(ui, "Optics & Transform");
            ui.horizontal_wrapped(|ui| {
                if theme::pill_toggle_chip(ui, app.autofocus, None, "Autofocus").clicked() {
                    app.autofocus = !app.autofocus;
                    app.mark_params_dirty();
                }

                ui.add_enabled_ui(has_flash, |ui| {
                    if theme::pill_toggle_chip(ui, app.torch_on, Some(StudioIcon::Torch), "Torch")
                        .clicked()
                    {
                        app.torch_on = !app.torch_on;
                        app.mark_params_dirty();
                    }
                });

                if theme::pill_toggle_chip(
                    ui,
                    app.mirror_horizontal,
                    Some(StudioIcon::Mirror),
                    "Mirror Horizontal [H]",
                )
                .clicked()
                {
                    app.toggle_mirror();
                }
            });

            ui.add_space(8.0);
            theme::field_label(ui, "Rotation [R]");
            if theme::segmented_tab(
                ui,
                &mut app.rotation_deg,
                &[(0, "0°"), (90, "90°"), (180, "180°"), (270, "270°")],
            ) {
                app.mark_params_dirty();
            }
        });
    });
}

fn render_audio_tab(app: &mut CamCraftApp, ui: &mut egui::Ui) {
    let connected = app.is_connected();

    theme::card_frame().show(ui, |ui| {
        ui.set_min_width(ui.available_width());
        ui.horizontal(|ui| {
            theme::icon_widget(
                ui,
                15.0,
                if app.audio_muted {
                    StudioIcon::MicOff
                } else {
                    StudioIcon::Mic
                },
                if connected && app.vmic_active && !app.audio_muted {
                    COLOR_STATUS_EMERALD
                } else {
                    COLOR_TEXT_MUTED
                },
            );
            theme::section_title(ui, "PipeWire Virtual Microphone");
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let (badge_text, badge_dot) = if connected && app.vmic_active {
                    ("Active", COLOR_STATUS_EMERALD)
                } else {
                    ("Standby", COLOR_TEXT_MUTED)
                };
                theme::pill_badge_dot(
                    ui,
                    badge_text,
                    badge_dot,
                    COLOR_TEXT_SECONDARY,
                    COLOR_BG_STAGE,
                );
            });
        });

        ui.add_space(6.0);
        egui::Frame::new()
            .fill(COLOR_BG_STAGE)
            .stroke(Stroke::new(1.0, COLOR_BORDER_SUBTLE))
            .corner_radius(6.0)
            .inner_margin(8.0)
            .show(ui, |ui| {
                ui.set_min_width(ui.available_width());
                theme::field_label(ui, "PipeWire Source");
                ui.label(
                    egui::RichText::new(&app.vmic_name)
                        .color(COLOR_TEXT_PRIMARY)
                        .monospace()
                        .small(),
                );
            });

        if let Some(ref err) = app.vmic_error {
            ui.add_space(4.0);
            ui.label(
                egui::RichText::new(format!("Virtual mic unavailable: {err}"))
                    .color(COLOR_STATUS_AMBER)
                    .small(),
            );
        }

        ui.add_space(10.0);
        ui.add_enabled_ui(connected, |ui| {
            if ui
                .checkbox(&mut app.audio_enabled, "Enable Audio Forwarding")
                .changed()
            {
                app.send_audio_state();
            }

            ui.add_space(8.0);
            let can_mute = connected && app.audio_enabled;
            let (mute_icon, mute_text, mute_fill) = if app.audio_muted {
                (StudioIcon::MicOff, "Unmute Mic (M)", COLOR_STATUS_ROSE)
            } else {
                (StudioIcon::Mic, "Mute Mic (M)", COLOR_SURFACE_ELEVATED)
            };

            if ui
                .add_enabled_ui(can_mute, |ui| {
                    theme::icon_action_button(
                        ui,
                        mute_icon,
                        mute_text,
                        mute_fill,
                        COLOR_TEXT_PRIMARY,
                        Vec2::new(ui.available_width(), 34.0),
                    )
                })
                .inner
                .clicked()
            {
                app.toggle_mute();
            }
        });

        ui.add_space(12.0);
        theme::render_led_vu_meter(
            ui,
            app.smoothed_level_norm,
            app.peak_hold_norm,
            app.audio_level.rms_db,
            connected && app.audio_enabled,
            app.audio_muted,
            app.audio_level.clipping,
        );
    });

    ui.add_space(10.0);
    egui::Frame::new()
        .fill(COLOR_BG_CANVAS)
        .stroke(Stroke::new(1.0, COLOR_BORDER_SUBTLE))
        .corner_radius(8.0)
        .inner_margin(10.0)
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            theme::field_label(
                ui,
                "Tip: Select \"CamCraft Virtual Mic\" as input in Discord, Vesktop, OBS, or Google Meet.",
            );
        });
}

fn separator_dot(ui: &mut egui::Ui) {
    ui.label(egui::RichText::new("•").color(COLOR_TEXT_MUTED));
}
