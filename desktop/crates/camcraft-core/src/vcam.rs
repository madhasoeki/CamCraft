//! Linux `v4l2loopback` virtual camera sink and abstraction traits.
//!
//! Follows `docs/DESIGN.md` §3.1 and `docs/PLAN.md` (Stage 4):
//! - Provides the cross-platform [`VirtualCameraSink`] trait.
//! - Implements [`V4l2LoopbackSink`] for Linux `/dev/video*` devices.
//! - Auto-discovers devices with card label `"CamCraft"`.
//! - Dynamically reconfigures `V4L2_BUF_TYPE_VIDEO_OUTPUT` formats (`YU12` / `YUV420P`).
//! - Emits standalone standby frames when the phone is not streaming or reconnecting.

use std::fs;
use std::path::{Path, PathBuf};
use thiserror::Error;
use tracing::{debug, info, warn};
use v4l::FourCC;
use v4l::capability::Flags as CapFlags;
use v4l::format::Format;
use v4l::video::traits::Output;

use crate::{CoreError, VideoOutputSink};

/// Errors encountered in virtual camera device interactions.
#[derive(Debug, Error)]
pub enum VcamError {
    /// Device node could not be opened or initialized.
    #[error("Failed to open V4L2 device '{path}': {details}")]
    DeviceOpen { path: PathBuf, details: String },

    /// Querying device capabilities failed or device lacks video output capabilities.
    #[error("Device '{path}' is not a valid V4L2 video output device: {details}")]
    InvalidCapabilities { path: PathBuf, details: String },

    /// Format negotiation (VIDIOC_S_FMT) failed.
    #[error("Failed to set video format on '{path}' ({width}x{height}): {details}")]
    FormatError {
        path: PathBuf,
        width: u32,
        height: u32,
        details: String,
    },

    /// Writing frame bytes to the device failed.
    #[error("Failed to write {bytes} bytes to '{path}': {details}")]
    WriteError {
        path: PathBuf,
        bytes: usize,
        details: String,
    },

    /// Standard I/O error.
    #[error("I/O error on virtual camera device: {0}")]
    Io(#[from] std::io::Error),

    /// Device is not currently open.
    #[error("Virtual camera device is not opened")]
    NotOpen,
}

/// Platform-independent virtual camera sink abstraction.
pub trait VirtualCameraSink: Send {
    /// Opens or connects to the virtual camera device.
    fn open(&mut self) -> Result<(), VcamError>;

    /// Writes one raw video frame in contiguous YUV420P format.
    ///
    /// Dynamically reconfigures the virtual camera if dimensions differ from the current format.
    fn write_frame(&mut self, width: u32, height: u32, data: &[u8]) -> Result<(), VcamError>;

    /// Generates and writes one standby pattern frame at the given resolution.
    fn write_standby_frame(&mut self, width: u32, height: u32) -> Result<(), VcamError> {
        let frame = generate_standby_frame(width, height);
        self.write_frame(width, height, &frame)
    }

    /// Closes and releases the virtual camera device.
    fn close(&mut self) -> Result<(), VcamError>;

    /// Returns true if the device is open and ready to receive frames.
    fn is_open(&self) -> bool;

    /// Returns the target device path or name.
    fn device_path(&self) -> &Path;
}

/// Scans `/dev/video*` to automatically discover a `v4l2loopback` node with card label `"CamCraft"`.
///
/// Falls back to `/dev/video10` if no matching device is found.
#[must_use]
pub fn find_camcraft_v4l2_device() -> PathBuf {
    let mut candidates: Vec<PathBuf> = Vec::new();

    if let Ok(entries) = fs::read_dir("/dev") {
        for entry in entries.flatten() {
            let path = entry.path();
            if let Some(file_name) = path.file_name().and_then(|n| n.to_str())
                && file_name.starts_with("video")
            {
                candidates.push(path);
            }
        }
    }

    // Sort to check /dev/video10 first if present, then lowest index to highest
    candidates.sort_by(|a, b| {
        let num_a = a
            .file_name()
            .and_then(|n| n.to_str())
            .and_then(|s| s.trim_start_matches("video").parse::<u32>().ok())
            .unwrap_or(u32::MAX);
        let num_b = b
            .file_name()
            .and_then(|n| n.to_str())
            .and_then(|s| s.trim_start_matches("video").parse::<u32>().ok())
            .unwrap_or(u32::MAX);

        // Prioritize index 10 (the default CamCraft video_nr)
        let prio_a = if num_a == 10 { 0 } else { num_a + 1 };
        let prio_b = if num_b == 10 { 0 } else { num_b + 1 };
        prio_a.cmp(&prio_b)
    });

    for path in &candidates {
        if let Ok(dev) = v4l::Device::with_path(path)
            && let Ok(caps) = dev.query_caps()
            && caps.card.contains("CamCraft")
        {
            info!(
                path = %path.display(),
                card = %caps.card,
                "Auto-detected CamCraft Virtual Camera device"
            );
            return path.clone();
        }
    }

    let default_path = PathBuf::from("/dev/video10");
    if default_path.exists() {
        debug!(
            path = %default_path.display(),
            "Found default CamCraft loopback node /dev/video10"
        );
        default_path
    } else {
        warn!(
            path = %default_path.display(),
            "CamCraft Virtual Camera not auto-detected; falling back to /dev/video10"
        );
        default_path
    }
}

/// Generates a sleek dark standby pattern frame in contiguous YUV420P format.
///
/// Ensures `/dev/video10` continuously streams a valid video signal even when the phone
/// is disconnected or changing resolutions, preventing calling apps (OBS, Discord) from failing.
#[must_use]
pub fn generate_standby_frame(width: u32, height: u32) -> Vec<u8> {
    let w = width as usize;
    let h = height as usize;
    let half_w = w / 2;
    let half_h = h / 2;
    let total_size = w * h + 2 * (half_w * half_h);

    let mut buf = vec![0u8; total_size];

    // Y plane: default dark charcoal background (Y = 32)
    buf[..w * h].fill(32);

    // Center indicator box (ArtCraft theme styling)
    let box_w = (w / 4).clamp(120, 360).min(w);
    let box_h = (h / 5).clamp(80, 240).min(h);
    let start_x = (w - box_w) / 2;
    let end_x = start_x + box_w;
    let start_y = (h - box_h) / 2;
    let end_y = start_y + box_h;

    // Draw box outline with light border (Y = 160)
    if box_w >= 4 && box_h >= 4 {
        for y in start_y..end_y {
            for x in start_x..end_x {
                let is_border = y == start_y
                    || y == start_y + 1
                    || y == end_y - 1
                    || y == end_y - 2
                    || x == start_x
                    || x == start_x + 1
                    || x == end_x - 1
                    || x == end_x - 2;

                if is_border {
                    buf[y * w + x] = 160;
                }
            }
        }
    }

    // Draw centered crosshair / status dot (Y = 220)
    let center_x = w / 2;
    let center_y = h / 2;
    let dot_radius = (box_h / 8).clamp(6, 16);

    for dy in -(dot_radius as isize)..=(dot_radius as isize) {
        for dx in -(dot_radius as isize)..=(dot_radius as isize) {
            if dx * dx + dy * dy <= (dot_radius * dot_radius) as isize {
                let py = center_y as isize + dy;
                let px = center_x as isize + dx;
                if py >= 0 && px >= 0 {
                    let py_u = py as usize;
                    let px_u = px as usize;
                    if py_u < h && px_u < w {
                        buf[py_u * w + px_u] = 220;
                    }
                }
            }
        }
    }

    // U (Cb) and V (Cr) planes: default neutral chroma (128)
    let u_start = w * h;
    let v_start = u_start + half_w * half_h;
    buf[u_start..u_start + half_w * half_h].fill(128);
    buf[v_start..v_start + half_w * half_h].fill(128);

    // Accent color on the center dot: subtle amber/gold tint (U = 80, V = 180)
    let uv_center_x = half_w / 2;
    let uv_center_y = half_h / 2;
    let uv_radius = dot_radius / 2;

    for dy in -(uv_radius as isize)..=(uv_radius as isize) {
        for dx in -(uv_radius as isize)..=(uv_radius as isize) {
            if dx * dx + dy * dy <= (uv_radius * uv_radius) as isize {
                let py = uv_center_y as isize + dy;
                let px = uv_center_x as isize + dx;
                if py >= 0 && px >= 0 {
                    let py_u = py as usize;
                    let px_u = px as usize;
                    if py_u < half_h && px_u < half_w {
                        buf[u_start + py_u * half_w + px_u] = 80;
                        buf[v_start + py_u * half_w + px_u] = 180;
                    }
                }
            }
        }
    }

    buf
}

/// Linux `v4l2loopback` virtual camera sink writing directly to `/dev/videoX`.
pub struct V4l2LoopbackSink {
    device_path: PathBuf,
    device: Option<v4l::Device>,
    current_width: u32,
    current_height: u32,
}

impl V4l2LoopbackSink {
    /// Creates a new sink pointing to the given device path.
    #[must_use]
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self {
            device_path: path.into(),
            device: None,
            current_width: 0,
            current_height: 0,
        }
    }

    /// Auto-detects the CamCraft virtual camera device and initializes the sink.
    #[must_use]
    pub fn auto_detect() -> Self {
        let path = find_camcraft_v4l2_device();
        Self::new(path)
    }

    /// Sets the V4L2 output format (VIDIOC_S_FMT) to YUV420P (`YU12`).
    pub fn configure_format(&mut self, width: u32, height: u32) -> Result<(), VcamError> {
        // If device is already open and dimensions differ, close and re-open to reset
        // v4l2loopback internal stream buffers and avoid EBUSY (os error 16).
        if self.device.is_some()
            && self.current_width != 0
            && (self.current_width != width || self.current_height != height)
        {
            debug!(
                path = %self.device_path.display(),
                old_width = self.current_width,
                old_height = self.current_height,
                new_width = width,
                new_height = height,
                "Resolution changed: reopening V4L2 device to renegotiate format cleanly"
            );
            self.device = None;
            self.open()?;
        }

        let dev = self.device.as_ref().ok_or(VcamError::NotOpen)?;

        debug!(
            path = %self.device_path.display(),
            width,
            height,
            "Configuring V4L2 loopback format to YU12"
        );

        let fmt = Format::new(width, height, FourCC::new(b"YU12"));
        let res = Output::set_format(dev, &fmt);

        if let Err(e) = res {
            warn!(
                path = %self.device_path.display(),
                error = %e,
                "VIDIOC_S_FMT negotiation failed; reopening device node to reset driver state"
            );
            self.device = None;
            self.open()?;
            let new_dev = self.device.as_ref().ok_or(VcamError::NotOpen)?;
            Output::set_format(new_dev, &fmt).map_err(|e2| VcamError::FormatError {
                path: self.device_path.clone(),
                width,
                height,
                details: e2.to_string(),
            })?;
        }

        self.current_width = width;
        self.current_height = height;
        info!(
            path = %self.device_path.display(),
            width,
            height,
            "V4L2 format successfully configured (YU12 / Planar YUV 4:2:0)"
        );

        Ok(())
    }
}

impl VirtualCameraSink for V4l2LoopbackSink {
    fn open(&mut self) -> Result<(), VcamError> {
        if self.device.is_some() {
            return Ok(());
        }

        info!(path = %self.device_path.display(), "Opening V4L2 loopback device");

        let dev = v4l::Device::with_path(&self.device_path).map_err(|e| VcamError::DeviceOpen {
            path: self.device_path.clone(),
            details: e.to_string(),
        })?;

        let caps = dev
            .query_caps()
            .map_err(|e| VcamError::InvalidCapabilities {
                path: self.device_path.clone(),
                details: e.to_string(),
            })?;

        if !caps.capabilities.contains(CapFlags::VIDEO_OUTPUT) {
            return Err(VcamError::InvalidCapabilities {
                path: self.device_path.clone(),
                details: format!(
                    "device '{}' (caps: {}) does not support VIDEO_OUTPUT",
                    caps.card, caps.capabilities
                ),
            });
        }

        info!(
            path = %self.device_path.display(),
            card = %caps.card,
            driver = %caps.driver,
            caps = ?caps.capabilities,
            "V4L2 virtual camera opened successfully"
        );

        self.device = Some(dev);
        Ok(())
    }

    fn write_frame(&mut self, width: u32, height: u32, data: &[u8]) -> Result<(), VcamError> {
        if width == 0 || height == 0 || !width.is_multiple_of(2) || !height.is_multiple_of(2) {
            return Err(VcamError::FormatError {
                path: self.device_path.clone(),
                width,
                height,
                details: "YUV420P dimensions must be positive and multiples of 2".to_string(),
            });
        }

        let expected_len = (width as usize) * (height as usize) * 3 / 2;
        if data.len() != expected_len {
            return Err(VcamError::WriteError {
                path: self.device_path.clone(),
                bytes: data.len(),
                details: format!(
                    "invalid YUV420P frame buffer size: expected {expected_len} bytes for {width}x{height}, got {}",
                    data.len()
                ),
            });
        }

        if self.device.is_none() {
            self.open()?;
        }

        // Dynamically reconfigure format if dimensions have changed
        if width != self.current_width || height != self.current_height {
            self.configure_format(width, height)?;
        }

        let dev = self.device.as_ref().ok_or(VcamError::NotOpen)?;
        let fd = dev.handle().fd();

        let mut written_total = 0;
        while written_total < data.len() {
            // SAFETY: `fd` is a valid, open file descriptor owned by `self.device`.
            // `data` is a valid continuous byte slice of size `data.len()`.
            let n = unsafe {
                libc::write(
                    fd,
                    data[written_total..].as_ptr() as *const libc::c_void,
                    data.len() - written_total,
                )
            };

            if n < 0 {
                let err = std::io::Error::last_os_error();
                if err.kind() == std::io::ErrorKind::Interrupted {
                    continue;
                }
                return Err(VcamError::WriteError {
                    path: self.device_path.clone(),
                    bytes: data.len(),
                    details: err.to_string(),
                });
            }

            if n == 0 {
                return Err(VcamError::WriteError {
                    path: self.device_path.clone(),
                    bytes: data.len(),
                    details: "write returned 0 bytes (unexpected zero-length write)".to_string(),
                });
            }

            written_total += n as usize;
        }

        Ok(())
    }

    fn write_standby_frame(&mut self, width: u32, height: u32) -> Result<(), VcamError> {
        let frame = generate_standby_frame(width, height);
        VirtualCameraSink::write_frame(self, width, height, &frame)
    }

    fn close(&mut self) -> Result<(), VcamError> {
        if self.device.is_some() {
            info!(path = %self.device_path.display(), "Closing V4L2 virtual camera sink");
            self.device = None;
            self.current_width = 0;
            self.current_height = 0;
        }
        Ok(())
    }

    fn is_open(&self) -> bool {
        self.device.is_some()
    }

    fn device_path(&self) -> &Path {
        &self.device_path
    }
}

impl VideoOutputSink for V4l2LoopbackSink {
    fn write_frame(&mut self, width: u32, height: u32, data: &[u8]) -> Result<(), CoreError> {
        VirtualCameraSink::write_frame(self, width, height, data).map_err(CoreError::Vcam)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_standby_frame_size() {
        let frame_1080p = generate_standby_frame(1920, 1080);
        assert_eq!(frame_1080p.len(), 1920 * 1080 * 3 / 2);

        let frame_720p = generate_standby_frame(1280, 720);
        assert_eq!(frame_720p.len(), 1280 * 720 * 3 / 2);
    }

    #[test]
    fn test_auto_detect_device() {
        let path = find_camcraft_v4l2_device();
        assert!(path.to_str().unwrap().starts_with("/dev/video"));
    }

    #[test]
    fn test_v4l2_loopback_sink_lifecycle() {
        let path = "/dev/video10";
        if Path::new(path).exists() {
            let mut sink = V4l2LoopbackSink::new(path);
            assert!(sink.open().is_ok());
            assert!(sink.is_open());

            // Write 1 standby frame
            let res = sink.write_standby_frame(1920, 1080);
            assert!(res.is_ok(), "Writing standby frame failed: {:?}", res.err());

            assert!(sink.close().is_ok());
            assert!(!sink.is_open());
        }
    }

    #[test]
    fn test_standby_frame_tiny_resolutions_no_panic() {
        for (w, h) in [(2, 2), (16, 16), (64, 64)] {
            let frame = generate_standby_frame(w, h);
            assert_eq!(frame.len(), (w * h * 3 / 2) as usize);
        }
    }

    #[test]
    fn test_write_frame_rejects_invalid_dimensions_and_size() {
        let mut sink = V4l2LoopbackSink::new("/dev/nonexistent_camcraft_video");

        let zero_res = VirtualCameraSink::write_frame(&mut sink, 0, 1080, &[]);
        assert!(matches!(zero_res, Err(VcamError::FormatError { .. })));

        let odd_res = VirtualCameraSink::write_frame(&mut sink, 63, 64, &[0u8; 63 * 64 * 3 / 2]);
        assert!(matches!(odd_res, Err(VcamError::FormatError { .. })));

        let bad_size = VirtualCameraSink::write_frame(&mut sink, 64, 64, &[0u8; 100]);
        assert!(matches!(bad_size, Err(VcamError::WriteError { .. })));
    }

    #[test]
    fn test_open_capture_only_device_rejected_if_present() {
        let path = "/dev/video0";
        if let Ok(dev) = v4l::Device::with_path(path)
            && let Ok(caps) = dev.query_caps()
            && !caps.capabilities.contains(CapFlags::VIDEO_OUTPUT)
        {
            let mut sink = V4l2LoopbackSink::new(path);
            let res = sink.open();
            assert!(
                matches!(res, Err(VcamError::InvalidCapabilities { .. })),
                "Expected InvalidCapabilities for capture-only device, got: {res:?}"
            );
        }
    }
}
