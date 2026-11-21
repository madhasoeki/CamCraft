//! ArtCraft Studio v2 Design System for `camcraft-gui`.
//!
//! Provides WCAG AA (>= 4.5:1) verified dark color tokens, theme setup,
//! crisp vector icon rendering via [`egui::Painter`], layout frames,
//! segmented tab controls, pill badges, and a segmented LED dBFS VU meter.

use std::f32::consts::{FRAC_PI_2, PI, TAU};

use eframe::egui::{self, Color32, CornerRadius, FontId, Pos2, Rect, Stroke, StrokeKind, Vec2};

/// Primary window/panel canvas background (`#0D0F14`).
pub const COLOR_BG_CANVAS: Color32 = Color32::from_rgb(0x0D, 0x0F, 0x14);
/// Cinema preview stage & recessed input/meter background (`#080A0E`).
pub const COLOR_BG_STAGE: Color32 = Color32::from_rgb(0x08, 0x0A, 0x0E);
/// Standard inspector/header card fill (`#161A22`).
pub const COLOR_SURFACE_CARD: Color32 = Color32::from_rgb(0x16, 0x1A, 0x22);
/// Subtle 1px hairline border for cards, stage, and controls (`#252B37`).
pub const COLOR_BORDER_SUBTLE: Color32 = Color32::from_rgb(0x25, 0x2B, 0x37);
/// Elevated button, input, and chip fill (`#1F2530`).
pub const COLOR_SURFACE_ELEVATED: Color32 = Color32::from_rgb(0x1F, 0x25, 0x30);
/// Interactive hover surface fill (`#293140`).
pub const COLOR_SURFACE_HOVER: Color32 = Color32::from_rgb(0x29, 0x31, 0x40);

/// Primary electric blue brand & CTA accent (`#3B82F6`).
pub const COLOR_ACCENT_BLUE: Color32 = Color32::from_rgb(0x3B, 0x82, 0xF6);
/// Hover/active variant of primary blue (`#2563EB`).
pub const COLOR_ACCENT_BLUE_HOVER: Color32 = Color32::from_rgb(0x25, 0x63, 0xEB);

/// Live / healthy status accent (`#10B981`).
pub const COLOR_STATUS_EMERALD: Color32 = Color32::from_rgb(0x10, 0xB9, 0x81);
/// Warning / reconnecting / moderate latency accent (`#F59E0B`).
pub const COLOR_STATUS_AMBER: Color32 = Color32::from_rgb(0xF5, 0x9E, 0x0B);
/// Error / muted / stop / high latency accent (`#EF4444`).
pub const COLOR_STATUS_ROSE: Color32 = Color32::from_rgb(0xEF, 0x44, 0x44);

/// Primary crisp foreground text (`#F8FAFC`).
pub const COLOR_TEXT_PRIMARY: Color32 = Color32::from_rgb(0xF8, 0xFA, 0xFC);
/// Secondary body / inactive interactive label (`#CBD5E1`).
pub const COLOR_TEXT_SECONDARY: Color32 = Color32::from_rgb(0xCB, 0xD5, 0xE1);
/// Muted metadata / helper text (`#8B95A7`).
pub const COLOR_TEXT_MUTED: Color32 = Color32::from_rgb(0x8B, 0x95, 0xA7);

const VU_SEGMENT_COUNT: usize = 24;

/// Vector icons painted cleanly with [`egui::Painter`] (no emoji glyphs).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum StudioIcon {
    BrandMark,
    Usb,
    Wifi,
    Camera,
    Mic,
    MicOff,
    Bolt,
    Rotate,
    Mirror,
    Torch,
    Pause,
    Play,
    Grid,
    Refresh,
    Download,
}

/// Applies the ArtCraft Studio v2 dark theme, 4px/8px spacing rhythm, and crisp
/// focus/hover/active widget states to the [`egui::Context`].
pub fn apply_artcraft_theme(ctx: &egui::Context) {
    ctx.set_theme(egui::ThemePreference::Dark);
    ctx.style_mut_of(egui::Theme::Dark, |style| {
        style.spacing.item_spacing = Vec2::new(8.0, 8.0);
        style.spacing.button_padding = Vec2::new(10.0, 6.0);
        style.spacing.window_margin = egui::Margin::same(12);
        style.spacing.menu_margin = egui::Margin::same(8);
        style.spacing.indent = 16.0;
        style.spacing.interact_size = Vec2::new(32.0, 26.0);
        style.spacing.slider_rail_height = 6.0;
        style.spacing.icon_width = 16.0;
        style.spacing.icon_width_inner = 8.0;
        style.spacing.icon_spacing = 6.0;

        let mut visuals = egui::Visuals::dark();
        visuals.dark_mode = true;
        visuals.override_text_color = None;
        visuals.weak_text_color = Some(COLOR_TEXT_MUTED);
        visuals.panel_fill = COLOR_BG_CANVAS;
        visuals.window_fill = COLOR_SURFACE_CARD;
        visuals.window_stroke = Stroke::new(1.0, COLOR_BORDER_SUBTLE);
        visuals.window_corner_radius = CornerRadius::same(8);
        visuals.menu_corner_radius = CornerRadius::same(8);
        visuals.extreme_bg_color = COLOR_BG_STAGE;
        visuals.text_edit_bg_color = Some(COLOR_BG_STAGE);
        visuals.faint_bg_color = COLOR_SURFACE_ELEVATED;
        visuals.code_bg_color = COLOR_SURFACE_ELEVATED;
        visuals.warn_fg_color = COLOR_STATUS_AMBER;
        visuals.error_fg_color = COLOR_STATUS_ROSE;
        visuals.hyperlink_color = COLOR_ACCENT_BLUE;
        visuals.slider_trailing_fill = true;
        visuals.interact_cursor = Some(egui::CursorIcon::PointingHand);
        visuals.selection = egui::style::Selection {
            bg_fill: COLOR_ACCENT_BLUE,
            stroke: Stroke::new(1.5, COLOR_TEXT_PRIMARY),
        };

        visuals.widgets.noninteractive.bg_fill = COLOR_SURFACE_CARD;
        visuals.widgets.noninteractive.weak_bg_fill = COLOR_SURFACE_CARD;
        visuals.widgets.noninteractive.bg_stroke = Stroke::new(1.0, COLOR_BORDER_SUBTLE);
        visuals.widgets.noninteractive.corner_radius = CornerRadius::same(6);
        visuals.widgets.noninteractive.fg_stroke = Stroke::new(1.0, COLOR_TEXT_PRIMARY);
        visuals.widgets.noninteractive.expansion = 0.0;

        visuals.widgets.inactive.bg_fill = COLOR_SURFACE_ELEVATED;
        visuals.widgets.inactive.weak_bg_fill = COLOR_SURFACE_ELEVATED;
        visuals.widgets.inactive.bg_stroke = Stroke::new(1.0, COLOR_BORDER_SUBTLE);
        visuals.widgets.inactive.corner_radius = CornerRadius::same(6);
        visuals.widgets.inactive.fg_stroke = Stroke::new(1.0, COLOR_TEXT_SECONDARY);
        visuals.widgets.inactive.expansion = 0.0;

        visuals.widgets.hovered.bg_fill = COLOR_SURFACE_HOVER;
        visuals.widgets.hovered.weak_bg_fill = COLOR_SURFACE_HOVER;
        visuals.widgets.hovered.bg_stroke = Stroke::new(1.0, COLOR_ACCENT_BLUE);
        visuals.widgets.hovered.corner_radius = CornerRadius::same(6);
        visuals.widgets.hovered.fg_stroke = Stroke::new(1.5, COLOR_TEXT_PRIMARY);
        visuals.widgets.hovered.expansion = 0.0;

        visuals.widgets.active.bg_fill = COLOR_ACCENT_BLUE;
        visuals.widgets.active.weak_bg_fill = COLOR_ACCENT_BLUE_HOVER;
        visuals.widgets.active.bg_stroke = Stroke::new(1.5, COLOR_TEXT_PRIMARY);
        visuals.widgets.active.corner_radius = CornerRadius::same(6);
        visuals.widgets.active.fg_stroke = Stroke::new(1.5, COLOR_TEXT_PRIMARY);
        visuals.widgets.active.expansion = 0.0;

        visuals.widgets.open.bg_fill = COLOR_SURFACE_HOVER;
        visuals.widgets.open.weak_bg_fill = COLOR_SURFACE_HOVER;
        visuals.widgets.open.bg_stroke = Stroke::new(1.0, COLOR_ACCENT_BLUE);
        visuals.widgets.open.corner_radius = CornerRadius::same(6);
        visuals.widgets.open.fg_stroke = Stroke::new(1.0, COLOR_TEXT_PRIMARY);
        visuals.widgets.open.expansion = 0.0;

        style.visuals = visuals;
    });
}

/// Returns the standard studio card frame (`#161A22` fill + `1px` `#252B37` border).
pub fn card_frame() -> egui::Frame {
    egui::Frame::new()
        .fill(COLOR_SURFACE_CARD)
        .stroke(Stroke::new(1.0, COLOR_BORDER_SUBTLE))
        .corner_radius(8.0)
        .inner_margin(12.0)
}

/// Returns the deep stage frame for the cinema preview canvas (`#080A0E` + `1px` `#252B37`).
pub fn stage_frame() -> egui::Frame {
    egui::Frame::new()
        .fill(COLOR_BG_STAGE)
        .stroke(Stroke::new(1.0, COLOR_BORDER_SUBTLE))
        .corner_radius(8.0)
        .inner_margin(0.0)
}

/// Renders a crisp section heading inside a card.
pub fn section_title(ui: &mut egui::Ui, title: &str) {
    ui.label(
        egui::RichText::new(title)
            .color(COLOR_TEXT_PRIMARY)
            .size(14.0)
            .strong(),
    );
}

/// Renders a muted form field label.
pub fn field_label(ui: &mut egui::Ui, label: &str) {
    ui.label(egui::RichText::new(label).color(COLOR_TEXT_MUTED).small());
}

/// Maps frame decode duration (ms) to WCAG-clear semantic telemetry color.
#[must_use]
pub fn decode_latency_color(decode_ms: f32) -> Color32 {
    if decode_ms <= 0.0 {
        COLOR_TEXT_MUTED
    } else if decode_ms < 8.0 {
        COLOR_STATUS_EMERALD
    } else if decode_ms <= 15.0 {
        COLOR_STATUS_AMBER
    } else {
        COLOR_STATUS_ROSE
    }
}

/// Renders a compact rounded status pill badge.
pub fn pill_badge(ui: &mut egui::Ui, text: &str, fg: Color32, bg: Color32) -> egui::Response {
    egui::Frame::new()
        .fill(bg)
        .stroke(Stroke::new(1.0, fg.gamma_multiply(0.35)))
        .corner_radius(10.0)
        .inner_margin(egui::Margin::symmetric(8, 3))
        .show(ui, |ui| {
            ui.label(egui::RichText::new(text).color(fg).small().strong())
        })
        .response
}

/// Renders a rounded status pill badge prefixed with a colored status dot.
pub fn pill_badge_dot(
    ui: &mut egui::Ui,
    text: &str,
    dot: Color32,
    fg: Color32,
    bg: Color32,
) -> egui::Response {
    egui::Frame::new()
        .fill(bg)
        .stroke(Stroke::new(1.0, dot.gamma_multiply(0.4)))
        .corner_radius(10.0)
        .inner_margin(egui::Margin::symmetric(8, 3))
        .show(ui, |ui| {
            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing.x = 5.0;
                let (rect, _) = ui.allocate_exact_size(Vec2::splat(7.0), egui::Sense::hover());
                ui.painter().circle_filled(rect.center(), 3.2, dot);
                ui.label(egui::RichText::new(text).color(fg).small().strong());
            });
        })
        .response
}

/// Renders an equal-width segmented control for mutually exclusive choices.
pub fn segmented_tab<T: Copy + PartialEq>(
    ui: &mut egui::Ui,
    current: &mut T,
    options: &[(T, &str)],
) -> bool {
    render_segmented_control(ui, current, options, false)
}

/// Renders a width-proportional segmented control for options with varying label lengths.
pub fn segmented_tab_proportional<T: Copy + PartialEq>(
    ui: &mut egui::Ui,
    current: &mut T,
    options: &[(T, &str)],
) -> bool {
    render_segmented_control(ui, current, options, true)
}

fn render_segmented_control<T: Copy + PartialEq>(
    ui: &mut egui::Ui,
    current: &mut T,
    options: &[(T, &str)],
    proportional: bool,
) -> bool {
    if options.is_empty() {
        return false;
    }

    let mut changed = false;
    let enabled = ui.is_enabled();
    let font_size = if proportional { 12.0 } else { 12.5 };

    egui::Frame::new()
        .fill(COLOR_BG_STAGE)
        .stroke(Stroke::new(1.0, COLOR_BORDER_SUBTLE))
        .corner_radius(8.0)
        .inner_margin(3.0)
        .show(ui, |ui| {
            let count = options.len() as f32;
            let gap = 4.0;
            let total_gap = gap * (count - 1.0);
            let avail_for_segs = (ui.available_width() - total_gap).max(40.0 * count);

            let widths: Vec<f32> = if proportional {
                let raw: Vec<f32> = options
                    .iter()
                    .map(|(_, label)| {
                        let galley = ui.painter().layout_no_wrap(
                            (*label).to_owned(),
                            FontId::proportional(font_size),
                            COLOR_TEXT_PRIMARY,
                        );
                        (galley.size().x + 12.0).max(40.0)
                    })
                    .collect();
                let raw_sum: f32 = raw.iter().sum();
                if raw_sum <= avail_for_segs {
                    let extra = (avail_for_segs - raw_sum) / count;
                    raw.into_iter().map(|w| w + extra).collect()
                } else {
                    let scale = avail_for_segs / raw_sum;
                    raw.into_iter().map(|w| (w * scale).max(32.0)).collect()
                }
            } else {
                let seg_w = (avail_for_segs / count).max(40.0);
                vec![seg_w; options.len()]
            };

            ui.horizontal(|ui| {
                ui.spacing_mut().item_spacing.x = gap;
                for (&(value, label), seg_w) in options.iter().zip(widths) {
                    let selected = *current == value;
                    let (rect, resp) =
                        ui.allocate_exact_size(Vec2::new(seg_w, 26.0), egui::Sense::click());
                    let fill = if !enabled {
                        if selected {
                            COLOR_ACCENT_BLUE.gamma_multiply(0.45)
                        } else {
                            Color32::TRANSPARENT
                        }
                    } else if selected {
                        COLOR_ACCENT_BLUE
                    } else if resp.hovered() {
                        COLOR_SURFACE_HOVER
                    } else {
                        Color32::TRANSPARENT
                    };
                    let text_color = if !enabled {
                        if selected {
                            COLOR_TEXT_PRIMARY.gamma_multiply(0.7)
                        } else {
                            COLOR_TEXT_MUTED
                        }
                    } else if selected || resp.hovered() {
                        COLOR_TEXT_PRIMARY
                    } else {
                        COLOR_TEXT_SECONDARY
                    };

                    if fill != Color32::TRANSPARENT {
                        ui.painter().rect_filled(rect, CornerRadius::same(6), fill);
                    }
                    if enabled && resp.has_focus() {
                        ui.painter().rect_stroke(
                            rect,
                            CornerRadius::same(6),
                            Stroke::new(1.5, COLOR_TEXT_PRIMARY),
                            StrokeKind::Inside,
                        );
                    }
                    ui.painter().text(
                        rect.center(),
                        egui::Align2::CENTER_CENTER,
                        label,
                        FontId::proportional(font_size),
                        text_color,
                    );

                    if enabled && resp.clicked() && !selected {
                        *current = value;
                        changed = true;
                    }
                }
            });
        });

    changed
}

/// Renders a studio action button with a crisp vector icon and label.
pub fn icon_action_button(
    ui: &mut egui::Ui,
    icon: StudioIcon,
    label: &str,
    fill: Color32,
    fg: Color32,
    size: Vec2,
) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(size, egui::Sense::click());
    let enabled = ui.is_enabled();
    let effective_fill = if !enabled {
        COLOR_SURFACE_ELEVATED.gamma_multiply(0.55)
    } else if resp.is_pointer_button_down_on() || resp.hovered() {
        if fill == COLOR_ACCENT_BLUE {
            COLOR_ACCENT_BLUE_HOVER
        } else {
            COLOR_SURFACE_HOVER
        }
    } else {
        fill
    };
    let effective_fg = if enabled {
        fg
    } else {
        COLOR_TEXT_MUTED.gamma_multiply(0.7)
    };
    let border = if !enabled {
        Stroke::new(1.0, COLOR_BORDER_SUBTLE.gamma_multiply(0.5))
    } else if resp.has_focus() {
        Stroke::new(1.5, COLOR_TEXT_PRIMARY)
    } else if resp.hovered() {
        Stroke::new(1.0, COLOR_ACCENT_BLUE)
    } else {
        Stroke::new(1.0, COLOR_BORDER_SUBTLE)
    };

    ui.painter().rect(
        rect,
        CornerRadius::same(6),
        effective_fill,
        border,
        StrokeKind::Inside,
    );

    let galley =
        ui.painter()
            .layout_no_wrap(label.to_owned(), FontId::proportional(13.0), effective_fg);
    let icon_size = 14.0;
    let gap = 6.0;
    let content_w = icon_size + gap + galley.size().x;
    let start_x = (rect.center().x - content_w * 0.5).max(rect.min.x + 8.0);
    let icon_rect = Rect::from_center_size(
        Pos2::new(start_x + icon_size * 0.5, rect.center().y),
        Vec2::splat(icon_size),
    );
    paint_icon(ui.painter(), icon_rect, icon, effective_fg);
    let text_pos = Pos2::new(
        start_x + icon_size + gap,
        rect.center().y - galley.size().y * 0.5,
    );
    ui.painter().galley(text_pos, galley, effective_fg);

    resp
}

/// Renders a compact pill toggle chip with optional leading icon.
pub fn pill_toggle_chip(
    ui: &mut egui::Ui,
    active: bool,
    icon: Option<StudioIcon>,
    label: &str,
) -> egui::Response {
    let galley = ui.painter().layout_no_wrap(
        label.to_owned(),
        FontId::proportional(12.0),
        COLOR_TEXT_PRIMARY,
    );
    let icon_space = if icon.is_some() { 18.0 } else { 0.0 };
    let desired = Vec2::new(galley.size().x + icon_space + 18.0, 26.0);
    let (rect, resp) = ui.allocate_exact_size(desired, egui::Sense::click());
    let enabled = ui.is_enabled();

    let (fill, fg, stroke) = if !enabled {
        (
            COLOR_SURFACE_ELEVATED.gamma_multiply(0.5),
            COLOR_TEXT_MUTED,
            Stroke::new(1.0, COLOR_BORDER_SUBTLE.gamma_multiply(0.5)),
        )
    } else if active {
        (
            COLOR_ACCENT_BLUE.gamma_multiply(0.22),
            COLOR_TEXT_PRIMARY,
            Stroke::new(1.0, COLOR_ACCENT_BLUE),
        )
    } else if resp.hovered() {
        (
            COLOR_SURFACE_HOVER,
            COLOR_TEXT_PRIMARY,
            Stroke::new(1.0, COLOR_ACCENT_BLUE.gamma_multiply(0.7)),
        )
    } else {
        (
            COLOR_SURFACE_ELEVATED,
            COLOR_TEXT_SECONDARY,
            Stroke::new(1.0, COLOR_BORDER_SUBTLE),
        )
    };

    ui.painter().rect(
        rect,
        CornerRadius::same(6),
        fill,
        stroke,
        StrokeKind::Inside,
    );

    let mut cursor_x = rect.min.x + 9.0;
    if let Some(ic) = icon {
        let icon_rect = Rect::from_center_size(
            Pos2::new(cursor_x + 6.0, rect.center().y),
            Vec2::splat(13.0),
        );
        paint_icon(ui.painter(), icon_rect, ic, fg);
        cursor_x += 18.0;
    }

    let text_pos = Pos2::new(cursor_x, rect.center().y - galley.size().y * 0.5);
    ui.painter().galley(text_pos, galley, fg);
    resp
}

/// Allocates and paints a standalone vector icon widget.
pub fn icon_widget(
    ui: &mut egui::Ui,
    size: f32,
    icon: StudioIcon,
    color: Color32,
) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(size), egui::Sense::hover());
    paint_icon(ui.painter(), rect, icon, color);
    resp
}

/// Draws a crisp vector icon inside `rect` using [`egui::Painter`].
pub fn paint_icon(painter: &egui::Painter, rect: Rect, icon: StudioIcon, color: Color32) {
    let side = rect.width().min(rect.height());
    let c = rect.center();
    let r = side * 0.42;
    let sw = (side * 0.105).clamp(1.25, 2.2);
    let stroke = Stroke::new(sw, color);

    match icon {
        StudioIcon::BrandMark => {
            paint_brand_mark(painter, rect);
        }
        StudioIcon::Usb => {
            // Vertical trunk + circle base + fork branches
            let top = Pos2::new(c.x, c.y - r * 0.9);
            let bot = Pos2::new(c.x, c.y + r * 0.65);
            painter.line_segment([top, bot], stroke);
            painter.circle_filled(Pos2::new(c.x, c.y + r * 0.78), sw * 1.15, color);
            // Arrow tip at top
            painter.line_segment([top, Pos2::new(c.x - r * 0.28, top.y + r * 0.32)], stroke);
            painter.line_segment([top, Pos2::new(c.x + r * 0.28, top.y + r * 0.32)], stroke);
            // Left square branch
            let left_elbow = Pos2::new(c.x - r * 0.55, c.y - r * 0.05);
            painter.line_segment([Pos2::new(c.x, c.y + r * 0.32), left_elbow], stroke);
            painter.line_segment(
                [left_elbow, Pos2::new(left_elbow.x, c.y - r * 0.42)],
                stroke,
            );
            painter.rect_filled(
                Rect::from_center_size(
                    Pos2::new(left_elbow.x, c.y - r * 0.48),
                    Vec2::splat(sw * 1.9),
                ),
                1.0,
                color,
            );
            // Right circle branch
            let right_elbow = Pos2::new(c.x + r * 0.55, c.y - r * 0.15);
            painter.line_segment([Pos2::new(c.x, c.y + r * 0.18), right_elbow], stroke);
            painter.circle_filled(Pos2::new(right_elbow.x, c.y - r * 0.36), sw * 1.0, color);
        }
        StudioIcon::Wifi => {
            let origin = Pos2::new(c.x, c.y + r * 0.65);
            painter.circle_filled(origin, sw * 1.05, color);
            for scale in [0.55_f32, 0.95_f32, 1.35_f32] {
                paint_arc(
                    painter,
                    origin,
                    r * scale,
                    -FRAC_PI_2 - 0.72,
                    -FRAC_PI_2 + 0.72,
                    stroke,
                    10,
                );
            }
        }
        StudioIcon::Camera => {
            let body = Rect::from_center_size(
                Pos2::new(c.x, c.y + r * 0.08),
                Vec2::new(r * 1.9, r * 1.35),
            );
            painter.rect_stroke(body, CornerRadius::same(2), stroke, StrokeKind::Inside);
            // Top prism bump
            let bump = Rect::from_center_size(
                Pos2::new(c.x, body.min.y - r * 0.14),
                Vec2::new(r * 0.72, r * 0.28),
            );
            painter.rect_filled(bump, CornerRadius::same(1), color);
            painter.circle_stroke(body.center(), r * 0.38, stroke);
        }
        StudioIcon::Mic => {
            let cap = Rect::from_center_size(
                Pos2::new(c.x, c.y - r * 0.22),
                Vec2::new(r * 0.74, r * 1.1),
            );
            painter.rect_stroke(cap, CornerRadius::same(4), stroke, StrokeKind::Inside);
            paint_arc(
                painter,
                Pos2::new(c.x, c.y - r * 0.05),
                r * 0.66,
                0.0,
                PI,
                stroke,
                12,
            );
            painter.line_segment(
                [
                    Pos2::new(c.x, c.y + r * 0.61),
                    Pos2::new(c.x, c.y + r * 0.92),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    Pos2::new(c.x - r * 0.42, c.y + r * 0.92),
                    Pos2::new(c.x + r * 0.42, c.y + r * 0.92),
                ],
                stroke,
            );
        }
        StudioIcon::MicOff => {
            paint_icon(painter, rect, StudioIcon::Mic, color.gamma_multiply(0.75));
            painter.line_segment(
                [
                    Pos2::new(c.x - r * 0.85, c.y - r * 0.85),
                    Pos2::new(c.x + r * 0.85, c.y + r * 0.85),
                ],
                Stroke::new(sw * 1.15, color),
            );
        }
        StudioIcon::Bolt => {
            let pts = vec![
                Pos2::new(c.x + r * 0.14, c.y - r * 0.92),
                Pos2::new(c.x - r * 0.56, c.y + r * 0.08),
                Pos2::new(c.x - r * 0.02, c.y + r * 0.08),
                Pos2::new(c.x - r * 0.16, c.y + r * 0.92),
                Pos2::new(c.x + r * 0.56, c.y - r * 0.08),
                Pos2::new(c.x + r * 0.02, c.y - r * 0.08),
            ];
            painter.line(pts.clone(), Stroke::new(sw * 0.9, color));
            painter.line_segment([pts[5], pts[0]], Stroke::new(sw * 0.9, color));
        }
        StudioIcon::Rotate => {
            let rad = r * 0.72;
            paint_arc(painter, c, rad, -FRAC_PI_2, TAU * 0.65, stroke, 16);
            let tip = Pos2::new(
                c.x + rad * (-FRAC_PI_2).cos(),
                c.y + rad * (-FRAC_PI_2).sin(),
            );
            painter.line_segment([tip, Pos2::new(tip.x - r * 0.34, tip.y - r * 0.24)], stroke);
            painter.line_segment([tip, Pos2::new(tip.x - r * 0.28, tip.y + r * 0.28)], stroke);
        }
        StudioIcon::Mirror => {
            // Dashed center axis + left filled triangle + right outlined triangle
            painter.line_segment(
                [Pos2::new(c.x, c.y - r * 0.9), Pos2::new(c.x, c.y + r * 0.9)],
                stroke,
            );
            let left_tri = vec![
                Pos2::new(c.x - r * 0.25, c.y - r * 0.62),
                Pos2::new(c.x - r * 0.85, c.y + r * 0.56),
                Pos2::new(c.x - r * 0.25, c.y + r * 0.56),
            ];
            painter.add(egui::Shape::convex_polygon(left_tri, color, Stroke::NONE));

            let p1 = Pos2::new(c.x + r * 0.25, c.y - r * 0.62);
            let p2 = Pos2::new(c.x + r * 0.85, c.y + r * 0.56);
            let p3 = Pos2::new(c.x + r * 0.25, c.y + r * 0.56);
            painter.line(vec![p1, p2, p3, p1], stroke);
        }
        StudioIcon::Torch => {
            // Sun / flashlight bulb rays
            painter.circle_stroke(c, r * 0.36, stroke);
            for i in 0..8 {
                let ang = (i as f32) * (TAU / 8.0);
                let inner = Pos2::new(c.x + ang.cos() * r * 0.58, c.y + ang.sin() * r * 0.58);
                let outer = Pos2::new(c.x + ang.cos() * r * 0.88, c.y + ang.sin() * r * 0.88);
                painter.line_segment([inner, outer], stroke);
            }
        }
        StudioIcon::Pause => {
            let bar_w = (r * 0.42).max(2.0);
            let bar_h = r * 1.45;
            let left =
                Rect::from_center_size(Pos2::new(c.x - r * 0.42, c.y), Vec2::new(bar_w, bar_h));
            let right =
                Rect::from_center_size(Pos2::new(c.x + r * 0.42, c.y), Vec2::new(bar_w, bar_h));
            painter.rect_filled(left, CornerRadius::same(1), color);
            painter.rect_filled(right, CornerRadius::same(1), color);
        }
        StudioIcon::Play => {
            let tri = vec![
                Pos2::new(c.x - r * 0.48, c.y - r * 0.72),
                Pos2::new(c.x + r * 0.72, c.y),
                Pos2::new(c.x - r * 0.48, c.y + r * 0.72),
            ];
            painter.add(egui::Shape::convex_polygon(tri, color, Stroke::NONE));
        }
        StudioIcon::Grid => {
            let box_rect = Rect::from_center_size(c, Vec2::splat(r * 1.7));
            painter.rect_stroke(box_rect, CornerRadius::same(2), stroke, StrokeKind::Inside);
            let third_x1 = box_rect.min.x + box_rect.width() / 3.0;
            let third_x2 = box_rect.min.x + box_rect.width() * 2.0 / 3.0;
            let third_y1 = box_rect.min.y + box_rect.height() / 3.0;
            let third_y2 = box_rect.min.y + box_rect.height() * 2.0 / 3.0;
            painter.line_segment(
                [
                    Pos2::new(third_x1, box_rect.min.y),
                    Pos2::new(third_x1, box_rect.max.y),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    Pos2::new(third_x2, box_rect.min.y),
                    Pos2::new(third_x2, box_rect.max.y),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    Pos2::new(box_rect.min.x, third_y1),
                    Pos2::new(box_rect.max.x, third_y1),
                ],
                stroke,
            );
            painter.line_segment(
                [
                    Pos2::new(box_rect.min.x, third_y2),
                    Pos2::new(box_rect.max.x, third_y2),
                ],
                stroke,
            );
        }
        StudioIcon::Refresh => {
            let rad = r * 0.72;
            paint_arc(painter, c, rad, 0.35, TAU - 0.55, stroke, 16);
            let tip_ang = TAU - 0.55;
            let tip = Pos2::new(c.x + rad * tip_ang.cos(), c.y + rad * tip_ang.sin());
            painter.line_segment([tip, Pos2::new(tip.x - r * 0.38, tip.y - r * 0.1)], stroke);
            painter.line_segment([tip, Pos2::new(tip.x - r * 0.05, tip.y - r * 0.42)], stroke);
        }
        StudioIcon::Download => {
            let shaft_top = Pos2::new(c.x, c.y - r * 0.82);
            let shaft_bot = Pos2::new(c.x, c.y + r * 0.34);
            painter.line_segment([shaft_top, shaft_bot], stroke);
            painter.line_segment(
                [Pos2::new(c.x - r * 0.38, c.y - r * 0.04), shaft_bot],
                stroke,
            );
            painter.line_segment(
                [Pos2::new(c.x + r * 0.38, c.y - r * 0.04), shaft_bot],
                stroke,
            );
            let tray_left_top = Pos2::new(c.x - r * 0.72, c.y + r * 0.44);
            let tray_left_bot = Pos2::new(c.x - r * 0.72, c.y + r * 0.78);
            let tray_right_bot = Pos2::new(c.x + r * 0.72, c.y + r * 0.78);
            let tray_right_top = Pos2::new(c.x + r * 0.72, c.y + r * 0.44);
            painter.line(
                vec![tray_left_top, tray_left_bot, tray_right_bot, tray_right_top],
                stroke,
            );
        }
    }
}

/// Paints the full-colour ArtCraft Studio v2 CamCraft Miter Frame emblem inside `rect`.
fn paint_brand_mark(painter: &egui::Painter, rect: Rect) {
    let side = rect.width().min(rect.height());
    let s = side / 256.0;
    let c = rect.center();
    let pt = |x: f32, y: f32| Pos2::new(c.x + (x - 128.0) * s, c.y + (y - 128.0) * s);

    // Top-left viewfinder bracket (#F8FAFC)
    let mut tl_corner = Vec::with_capacity(8);
    tl_corner.push(pt(56.0, 74.0));
    for i in 0..=6 {
        let ang = PI + FRAC_PI_2 * (i as f32 / 6.0);
        tl_corner.push(pt(56.0 + 36.0 * ang.cos(), 74.0 + 36.0 * ang.sin()));
    }
    painter.add(egui::Shape::convex_polygon(
        tl_corner,
        COLOR_TEXT_PRIMARY,
        Stroke::NONE,
    ));
    painter.add(egui::Shape::convex_polygon(
        vec![
            pt(56.0, 38.0),
            pt(202.36, 38.0),
            pt(166.44, 74.0),
            pt(56.0, 74.0),
        ],
        COLOR_TEXT_PRIMARY,
        Stroke::NONE,
    ));
    painter.add(egui::Shape::convex_polygon(
        vec![
            pt(20.0, 74.0),
            pt(56.0, 74.0),
            pt(56.0, 173.0),
            pt(20.0, 208.65),
        ],
        COLOR_TEXT_PRIMARY,
        Stroke::NONE,
    ));

    // Bottom-right bridge bracket (#3B82F6)
    let mut br_corner = Vec::with_capacity(8);
    br_corner.push(pt(200.0, 182.0));
    for i in 0..=6 {
        let ang = FRAC_PI_2 * (i as f32 / 6.0);
        br_corner.push(pt(200.0 + 36.0 * ang.cos(), 182.0 + 36.0 * ang.sin()));
    }
    painter.add(egui::Shape::convex_polygon(
        br_corner,
        COLOR_ACCENT_BLUE,
        Stroke::NONE,
    ));
    painter.add(egui::Shape::convex_polygon(
        vec![
            pt(89.56, 182.0),
            pt(200.0, 182.0),
            pt(200.0, 218.0),
            pt(53.64, 218.0),
        ],
        COLOR_ACCENT_BLUE,
        Stroke::NONE,
    ));
    painter.add(egui::Shape::convex_polygon(
        vec![
            pt(200.0, 83.0),
            pt(236.0, 47.35),
            pt(236.0, 182.0),
            pt(200.0, 182.0),
        ],
        COLOR_ACCENT_BLUE,
        Stroke::NONE,
    ));

    // Center live emerald optical lens core (#10B981)
    painter.circle_filled(c, 34.0 * s, COLOR_STATUS_EMERALD);
}

fn paint_arc(
    painter: &egui::Painter,
    center: Pos2,
    radius: f32,
    start_rad: f32,
    end_rad: f32,
    stroke: Stroke,
    steps: usize,
) {
    let steps = steps.max(4);
    let mut pts = Vec::with_capacity(steps + 1);
    for i in 0..=steps {
        let t = i as f32 / steps as f32;
        let ang = start_rad + (end_rad - start_rad) * t;
        pts.push(Pos2::new(
            center.x + radius * ang.cos(),
            center.y + radius * ang.sin(),
        ));
    }
    painter.line(pts, stroke);
}

/// Renders a 24-segment LED dBFS VU meter (`-60` to `0 dB`) with peak hold and clipping highlight.
pub fn render_led_vu_meter(
    ui: &mut egui::Ui,
    level_norm: f32,
    peak_norm: f32,
    rms_db: f32,
    active: bool,
    muted: bool,
    clipping: bool,
) {
    let level = if active && !muted {
        level_norm.clamp(0.0, 1.0)
    } else {
        0.0
    };
    let peak = if active && !muted {
        peak_norm.clamp(0.0, 1.0)
    } else {
        0.0
    };

    let (db_text, db_color) = if !active {
        ("OFF".to_owned(), COLOR_TEXT_MUTED)
    } else if muted {
        ("MUTED".to_owned(), COLOR_STATUS_ROSE)
    } else if clipping {
        (format!("{rms_db:.1} dBFS CLIP"), COLOR_STATUS_ROSE)
    } else {
        (format!("{rms_db:.1} dBFS"), COLOR_TEXT_PRIMARY)
    };

    ui.horizontal(|ui| {
        field_label(ui, "Input Level");
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            ui.label(
                egui::RichText::new(db_text)
                    .color(db_color)
                    .monospace()
                    .small()
                    .strong(),
            );
        });
    });

    let avail_w = ui.available_width().max(80.0);
    let (well_rect, _) = ui.allocate_exact_size(Vec2::new(avail_w, 18.0), egui::Sense::hover());
    let painter = ui.painter();
    painter.rect(
        well_rect,
        CornerRadius::same(4),
        COLOR_BG_STAGE,
        Stroke::new(1.0, COLOR_BORDER_SUBTLE),
        StrokeKind::Inside,
    );

    let inner = well_rect.shrink(3.0);
    let gap = 2.0;
    let total_gap = gap * (VU_SEGMENT_COUNT - 1) as f32;
    let seg_w = ((inner.width() - total_gap) / VU_SEGMENT_COUNT as f32).max(2.0);
    let lit_count = lit_led_count(level, VU_SEGMENT_COUNT);
    let peak_idx = peak_led_index(peak, VU_SEGMENT_COUNT);

    for idx in 0..VU_SEGMENT_COUNT {
        let x0 = inner.min.x + idx as f32 * (seg_w + gap);
        let seg_rect =
            Rect::from_min_size(Pos2::new(x0, inner.min.y), Vec2::new(seg_w, inner.height()));
        let frac = (idx + 1) as f32 / VU_SEGMENT_COUNT as f32;
        let base_color = led_zone_color(frac);

        let is_lit = idx < lit_count;
        let is_peak = peak_idx == Some(idx);
        let is_clip_led = clipping && active && !muted && idx + 1 == VU_SEGMENT_COUNT;

        let fill = if is_clip_led {
            COLOR_STATUS_ROSE
        } else if is_lit || is_peak {
            base_color
        } else {
            base_color.gamma_multiply(0.16)
        };

        painter.rect_filled(seg_rect, CornerRadius::same(2), fill);
        if is_peak || is_clip_led {
            painter.rect_stroke(
                seg_rect,
                CornerRadius::same(2),
                Stroke::new(1.0, COLOR_TEXT_PRIMARY.gamma_multiply(0.8)),
                StrokeKind::Inside,
            );
        }
    }

    let (scale_rect, _) = ui.allocate_exact_size(Vec2::new(avail_w, 13.0), egui::Sense::hover());
    let font = FontId::monospace(9.5);
    for (frac, align, label) in [
        (0.0_f32, egui::Align2::LEFT_TOP, "-60"),
        (0.40_f32, egui::Align2::CENTER_TOP, "-36"),
        (0.70_f32, egui::Align2::CENTER_TOP, "-18"),
        (0.90_f32, egui::Align2::CENTER_TOP, "-6"),
        (1.0_f32, egui::Align2::RIGHT_TOP, "0 dB"),
    ] {
        let x = scale_rect.min.x + scale_rect.width() * frac;
        ui.painter().text(
            Pos2::new(x, scale_rect.min.y + 1.0),
            align,
            label,
            font.clone(),
            COLOR_TEXT_MUTED,
        );
    }
}

#[must_use]
fn led_zone_color(frac: f32) -> Color32 {
    if frac > 0.90 {
        COLOR_STATUS_ROSE
    } else if frac > 0.70 {
        COLOR_STATUS_AMBER
    } else {
        COLOR_STATUS_EMERALD
    }
}

#[must_use]
fn lit_led_count(level_norm: f32, total_leds: usize) -> usize {
    if level_norm <= 0.005 {
        return 0;
    }
    ((level_norm.clamp(0.0, 1.0) * total_leds as f32).ceil() as usize).min(total_leds)
}

#[must_use]
fn peak_led_index(peak_norm: f32, total_leds: usize) -> Option<usize> {
    if peak_norm <= 0.01 || total_leds == 0 {
        return None;
    }
    let idx = ((peak_norm.clamp(0.0, 1.0) * total_leds as f32).ceil() as usize)
        .saturating_sub(1)
        .min(total_leds - 1);
    Some(idx)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn relative_luminance(c: Color32) -> f64 {
        fn channel(v: u8) -> f64 {
            let s = f64::from(v) / 255.0;
            if s <= 0.03928 {
                s / 12.92
            } else {
                ((s + 0.055) / 1.055).powf(2.4)
            }
        }
        0.2126 * channel(c.r()) + 0.7152 * channel(c.g()) + 0.0722 * channel(c.b())
    }

    fn contrast_ratio(fg: Color32, bg: Color32) -> f64 {
        let l1 = relative_luminance(fg);
        let l2 = relative_luminance(bg);
        let (hi, lo) = if l1 > l2 { (l1, l2) } else { (l2, l1) };
        (hi + 0.05) / (lo + 0.05)
    }

    #[test]
    fn text_tokens_satisfy_wcag_aa_contrast() {
        for bg in [
            COLOR_BG_CANVAS,
            COLOR_BG_STAGE,
            COLOR_SURFACE_CARD,
            COLOR_SURFACE_ELEVATED,
        ] {
            assert!(
                contrast_ratio(COLOR_TEXT_PRIMARY, bg) >= 4.5,
                "COLOR_TEXT_PRIMARY failed WCAG AA on {bg:?}"
            );
            assert!(
                contrast_ratio(COLOR_TEXT_SECONDARY, bg) >= 4.5,
                "COLOR_TEXT_SECONDARY failed WCAG AA on {bg:?}"
            );
            assert!(
                contrast_ratio(COLOR_TEXT_MUTED, bg) >= 4.5,
                "COLOR_TEXT_MUTED failed WCAG AA on {bg:?}"
            );
        }
    }

    #[test]
    fn vu_meter_led_mapping_is_bounded() {
        assert_eq!(lit_led_count(0.0, 24), 0);
        assert_eq!(lit_led_count(0.5, 24), 12);
        assert_eq!(lit_led_count(1.0, 24), 24);
        assert_eq!(peak_led_index(0.0, 24), None);
        assert_eq!(peak_led_index(1.0, 24), Some(23));
        assert_eq!(led_zone_color(0.5), COLOR_STATUS_EMERALD);
        assert_eq!(led_zone_color(0.8), COLOR_STATUS_AMBER);
        assert_eq!(led_zone_color(0.95), COLOR_STATUS_ROSE);
    }

    #[test]
    fn decode_latency_color_thresholds_match_spec() {
        assert_eq!(decode_latency_color(0.0), COLOR_TEXT_MUTED);
        assert_eq!(decode_latency_color(4.2), COLOR_STATUS_EMERALD);
        assert_eq!(decode_latency_color(10.0), COLOR_STATUS_AMBER);
        assert_eq!(decode_latency_color(18.5), COLOR_STATUS_ROSE);
    }
}
