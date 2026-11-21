//! Hardware-accelerated and software H.264 video decoding with color conversion.
//!
//! Follows `docs/DESIGN.md` §3.1 and `docs/PLAN.md` (Stage 4):
//! - Decodes H.264 Annex-B packets (`VIDEO_CONFIG` SPS/PPS and `VIDEO_FRAME` NAL units).
//! - Configures low-latency decoding (`AV_CODEC_FLAG_LOW_DELAY`).
//! - Scales/converts decoded frames to contiguous `YUV420P` (for V4L2) and optionally `RGBA` (for GUI preview).
//! - Runs in a dedicated worker thread (`camcraft-vdec`) with a bounded queue (`crossbeam_channel::bounded(3)`).
//! - Drops stale non-keyframes on backpressure to prevent latency accumulation.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU64, Ordering};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use bytes::Bytes;
use camcraft_protocol::FrameFlags;
use crossbeam_channel::{Receiver, RecvTimeoutError, Sender, TrySendError};
use ffmpeg_next::codec::flag::Flags as CodecFlags;
use ffmpeg_next::software::scaling::{Context as ScaleContext, flag::Flags as ScaleFlags};
use ffmpeg_next::util::error::EAGAIN;
use ffmpeg_next::util::format::Pixel;
use ffmpeg_next::util::frame::video::Video as VideoFrame;
use thiserror::Error;
use tracing::{debug, info, trace, warn};

use crate::vcam::{StandbyKind, VirtualCameraSink, fill_standby_frame};

/// Maximum number of unrendered frames queued before dropping non-keyframes (default unit capacity).
pub const DECODER_QUEUE_CAPACITY: usize = 3;

/// Queue capacity used by live streaming sessions to absorb network/Wi-Fi jitter without
/// triggering premature keyframe recovery storms while still capping worst-case delay.
pub const LIVE_DECODER_QUEUE_CAPACITY: usize = 6;

/// Maximum width (in pixels) used for the desktop GUI live preview texture.
pub const DEFAULT_PREVIEW_MAX_WIDTH: u32 = 960;

/// Minimum interval between desktop GUI preview uploads (~33 FPS) while `/dev/video10` receives full FPS.
pub const PREVIEW_MIN_INTERVAL: Duration = Duration::from_millis(30);

/// Cadence for keepalive standby frames while reconnecting (5 FPS keeps V4L2 consumers alive).
pub const STANDBY_FRAME_INTERVAL: Duration = Duration::from_millis(200);

/// Minimum interval between outbound keyframe recovery requests.
pub const KEYFRAME_REQUEST_COOLDOWN: Duration = Duration::from_secs(1);

/// Maximum expected inter-frame PTS gap (microseconds) before suspecting dropped packets.
pub const MAX_INTER_FRAME_GAP_US: u64 = 500_000;

/// Number of consecutive P-frames producing zero decoded pictures before requesting a keyframe.
pub const MAX_EMPTY_P_FRAMES: u32 = 3;

/// Creates a bounded crossbeam channel used to drop stale frames under backpressure (`docs/DESIGN.md` §3.1).
#[must_use]
pub fn bounded_video_channel<T>() -> (crossbeam_channel::Sender<T>, crossbeam_channel::Receiver<T>)
{
    crossbeam_channel::bounded(DECODER_QUEUE_CAPACITY)
}

/// Errors that can occur during H.264 video decoding and color conversion.
#[derive(Debug, Error)]
pub enum DecoderError {
    #[error("FFmpeg error: {0}")]
    Ffmpeg(#[from] ffmpeg_next::Error),

    #[error("H.264 decoder not found in FFmpeg")]
    CodecNotFound,

    #[error("Failed to initialize decoder context: {0}")]
    ContextInit(String),

    #[error("Color conversion scaler error: {0}")]
    ScaleError(String),

    #[error("Decoder worker channel disconnected")]
    ChannelDisconnected,

    #[error("I/O error during sink output: {0}")]
    SinkIo(String),

    #[error("Corrupted or incomplete video frame decoded: {0}")]
    CorruptedFrame(String),
}

/// Reason why the decoder recovery tracker requested a fresh IDR keyframe.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeyframeRequestReason {
    MissingInitialKeyframe,
    FrameDropped,
    PtsDiscontinuity,
    DecodeError,
    ConsecutiveEmptyPFrames,
}

/// Decision produced by [`KeyframeRecoveryTracker::on_before_decode`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PreDecodeAction {
    Decode,
    DecodeAndRequest(KeyframeRequestReason),
    SkipUntilKeyframe {
        request: Option<KeyframeRequestReason>,
    },
}

/// Pure, time-injectable state machine governing H.264 decoder recovery and keyframe requests.
#[derive(Debug, Clone)]
pub struct KeyframeRecoveryTracker {
    cooldown: Duration,
    has_decoded_keyframe: bool,
    awaiting_keyframe: bool,
    awaiting_reason: KeyframeRequestReason,
    last_pts_us: Option<u64>,
    consecutive_empty_p_frames: u32,
    last_request_at: Option<Instant>,
}

impl Default for KeyframeRecoveryTracker {
    fn default() -> Self {
        Self::new(KEYFRAME_REQUEST_COOLDOWN)
    }
}

impl KeyframeRecoveryTracker {
    /// Creates a new recovery tracker enforcing `cooldown` between emitted requests.
    #[must_use]
    pub fn new(cooldown: Duration) -> Self {
        Self {
            cooldown,
            has_decoded_keyframe: false,
            awaiting_keyframe: false,
            awaiting_reason: KeyframeRequestReason::MissingInitialKeyframe,
            last_pts_us: None,
            consecutive_empty_p_frames: 0,
            last_request_at: None,
        }
    }

    /// Resets stream-level baseline when new SPS/PPS `VIDEO_CONFIG` parameter sets arrive.
    pub fn on_config_received(&mut self) {
        self.has_decoded_keyframe = false;
        self.consecutive_empty_p_frames = 0;
        self.last_pts_us = None;
    }

    /// Records a frame drop detected via sequence discontinuity or queue backpressure.
    pub fn on_frame_dropped(&mut self, now: Instant) -> Option<KeyframeRequestReason> {
        self.awaiting_keyframe = true;
        self.awaiting_reason = KeyframeRequestReason::FrameDropped;
        self.consecutive_empty_p_frames = 0;
        self.try_request(KeyframeRequestReason::FrameDropped, now)
    }

    /// Evaluates whether an incoming packet should be fed to FFmpeg or skipped until an IDR arrives.
    pub fn on_before_decode(
        &mut self,
        pts_us: u64,
        is_keyframe: bool,
        now: Instant,
    ) -> PreDecodeAction {
        if is_keyframe {
            self.last_pts_us = Some(pts_us);
            return PreDecodeAction::Decode;
        }

        if !self.has_decoded_keyframe {
            let request = self.try_request(KeyframeRequestReason::MissingInitialKeyframe, now);
            return PreDecodeAction::SkipUntilKeyframe { request };
        }

        if self.awaiting_keyframe {
            let request = self.try_request(self.awaiting_reason, now);
            return PreDecodeAction::SkipUntilKeyframe { request };
        }

        if let Some(prev_pts) = self.last_pts_us {
            let is_discontinuous =
                pts_us < prev_pts || pts_us.saturating_sub(prev_pts) > MAX_INTER_FRAME_GAP_US;
            self.last_pts_us = Some(pts_us);
            if is_discontinuous
                && let Some(reason) = self.try_request(KeyframeRequestReason::PtsDiscontinuity, now)
            {
                return PreDecodeAction::DecodeAndRequest(reason);
            }
        } else {
            self.last_pts_us = Some(pts_us);
        }

        PreDecodeAction::Decode
    }

    /// Updates state after FFmpeg returns `Ok(())` for a packet that yielded `decoded_count` pictures.
    pub fn on_decode_success(
        &mut self,
        pts_us: u64,
        is_keyframe: bool,
        decoded_count: usize,
        now: Instant,
    ) -> Option<KeyframeRequestReason> {
        if decoded_count > 0 {
            self.consecutive_empty_p_frames = 0;
            self.last_pts_us = Some(pts_us);
            if is_keyframe {
                self.has_decoded_keyframe = true;
                self.awaiting_keyframe = false;
            }
            return None;
        }

        if is_keyframe {
            self.awaiting_keyframe = true;
            self.awaiting_reason = KeyframeRequestReason::DecodeError;
            return self.try_request(KeyframeRequestReason::DecodeError, now);
        }

        self.consecutive_empty_p_frames = self.consecutive_empty_p_frames.saturating_add(1);
        if self.consecutive_empty_p_frames >= MAX_EMPTY_P_FRAMES {
            self.awaiting_keyframe = true;
            self.awaiting_reason = KeyframeRequestReason::ConsecutiveEmptyPFrames;
            return self.try_request(KeyframeRequestReason::ConsecutiveEmptyPFrames, now);
        }

        None
    }

    /// Updates state when FFmpeg fails to decode or flags a frame as corrupt.
    pub fn on_decode_error(&mut self, now: Instant) -> Option<KeyframeRequestReason> {
        self.awaiting_keyframe = true;
        self.awaiting_reason = KeyframeRequestReason::DecodeError;
        self.consecutive_empty_p_frames = 0;
        self.try_request(KeyframeRequestReason::DecodeError, now)
    }

    fn try_request(
        &mut self,
        reason: KeyframeRequestReason,
        now: Instant,
    ) -> Option<KeyframeRequestReason> {
        match self.last_request_at {
            Some(last) if now.saturating_duration_since(last) < self.cooldown => None,
            _ => {
                self.last_request_at = Some(now);
                Some(reason)
            }
        }
    }
}

/// Lightweight downscaled RGBA frame produced lazily for the desktop GUI preview.
#[derive(Debug, Clone)]
pub struct PreviewFrame {
    /// Original decoded stream width in pixels (e.g. 1920).
    pub source_width: u32,
    /// Original decoded stream height in pixels (e.g. 1080).
    pub source_height: u32,
    /// Scaled preview texture width in pixels (e.g. 960).
    pub preview_width: u32,
    /// Scaled preview texture height in pixels (e.g. 540).
    pub preview_height: u32,
    /// Time spent decoding and packing the frame in milliseconds.
    pub decode_time_ms: f32,
    /// Contiguous RGBA pixel buffer sized `preview_width * preview_height * 4`.
    pub rgba: Vec<u8>,
}

/// Computes even-aligned GUI preview dimensions capped at `max_width` without upscaling.
#[must_use]
pub fn compute_preview_dimensions(src_w: u32, src_h: u32, max_width: u32) -> (u32, u32) {
    if src_w == 0 || src_h == 0 || max_width == 0 || src_w <= max_width {
        return (src_w, src_h);
    }
    let dst_w = (max_width & !1).max(2);
    let scaled_h = (u64::from(src_h) * u64::from(dst_w)) / u64::from(src_w);
    let dst_h = ((scaled_h as u32) & !1).max(2);
    (dst_w, dst_h)
}

/// A decoded video frame with contiguous pixel buffers (owned variant for cross-thread preview).
#[derive(Debug, Clone)]
pub struct DecodedFrame {
    /// Frame width in pixels.
    pub width: u32,
    /// Frame height in pixels.
    pub height: u32,
    /// Presentation timestamp in microseconds.
    pub pts_us: u64,
    /// True if this frame is an IDR keyframe.
    pub is_keyframe: bool,
    /// Time spent decoding and converting this frame in milliseconds.
    pub decode_time_ms: f32,
    /// Contiguous YUV420P (YU12) byte buffer ready for V4L2 loopback.
    pub yuv420p: Vec<u8>,
    /// Optional RGBA byte buffer (populated only if GUI preview is enabled).
    pub rgba: Option<Vec<u8>>,
}

/// Borrowed view of a decoded video frame backed by reusable decoder scratch buffers.
#[derive(Debug, Clone, Copy)]
pub struct DecodedFrameRef<'a> {
    /// Frame width in pixels.
    pub width: u32,
    /// Frame height in pixels.
    pub height: u32,
    /// Presentation timestamp in microseconds.
    pub pts_us: u64,
    /// True if this frame is an IDR keyframe.
    pub is_keyframe: bool,
    /// Time spent decoding and converting this frame in milliseconds.
    pub decode_time_ms: f32,
    /// Contiguous YUV420P (YU12) byte slice ready for V4L2 loopback.
    pub yuv420p: &'a [u8],
    /// Optional contiguous RGBA byte slice (populated only if GUI preview is enabled).
    pub rgba: Option<&'a [u8]>,
}

impl DecodedFrameRef<'_> {
    /// Converts this borrowed frame view into an owned [`DecodedFrame`].
    #[must_use]
    pub fn to_owned_frame(&self) -> DecodedFrame {
        DecodedFrame {
            width: self.width,
            height: self.height,
            pts_us: self.pts_us,
            is_keyframe: self.is_keyframe,
            decode_time_ms: self.decode_time_ms,
            yuv420p: self.yuv420p.to_vec(),
            rgba: self.rgba.map(<[u8]>::to_vec),
        }
    }
}

/// Configuration options for [`H264Decoder`].
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DecoderOptions {
    /// Whether legacy [`H264Decoder::decode_frame`] / [`H264Decoder::decode_frame_with`]
    /// should also convert every decoded frame to full-resolution RGBA.
    pub enable_rgba: bool,
    /// Number of FFmpeg slice decoding threads (clamped to `1..=4`).
    pub thread_count: usize,
    /// Enables `AV_CODEC_FLAG2_FAST` on the FFmpeg codec context to skip non-essential
    /// post-processing on low-power hosts.
    pub fast_decode: bool,
}

impl Default for DecoderOptions {
    fn default() -> Self {
        Self {
            enable_rgba: false,
            thread_count: 2,
            fast_decode: true,
        }
    }
}

/// Geometric mirror and clockwise rotation transform applied to decoded YUV420P frames.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub struct VideoTransform {
    /// Mirror pixels horizontally before applying rotation (matches GUI UV transform order).
    pub mirror_horizontal: bool,
    /// Clockwise rotation in degrees (`0`, `90`, `180`, or `270`).
    pub rotation_deg: u16,
}

impl VideoTransform {
    /// Returns `true` if neither horizontal mirroring nor non-zero rotation is requested.
    #[inline]
    #[must_use]
    pub const fn is_identity(self) -> bool {
        !self.mirror_horizontal && self.rotation_deg.is_multiple_of(360)
    }

    /// Extracts [`VideoTransform`] from CCFT [`FrameFlags`].
    #[inline]
    #[must_use]
    pub const fn from_flags(flags: FrameFlags) -> Self {
        Self {
            mirror_horizontal: flags.is_mirrored_h(),
            rotation_deg: flags.rotation_degrees(),
        }
    }
}

/// Runtime-adjustable controls for lazy GUI preview generation.
#[derive(Debug, Clone)]
pub struct PreviewRuntimeControl {
    /// Whether preview RGBA generation is currently active.
    pub enabled: Arc<AtomicBool>,
    /// Maximum width in pixels for downscaled preview frames (`0` keeps full width).
    pub max_width: Arc<AtomicU32>,
    /// Minimum interval between queued preview frames in milliseconds.
    pub min_interval_ms: Arc<AtomicU32>,
}

impl PreviewRuntimeControl {
    /// Creates a new [`PreviewRuntimeControl`] backed by shared atomic values.
    #[must_use]
    pub fn new(enabled: bool, max_width: u32, min_interval_ms: u32) -> Self {
        Self {
            enabled: Arc::new(AtomicBool::new(enabled)),
            max_width: Arc::new(AtomicU32::new(max_width)),
            min_interval_ms: Arc::new(AtomicU32::new(min_interval_ms)),
        }
    }
}

impl Default for PreviewRuntimeControl {
    fn default() -> Self {
        Self::new(
            true,
            DEFAULT_PREVIEW_MAX_WIDTH,
            PREVIEW_MIN_INTERVAL.as_millis() as u32,
        )
    }
}

/// Configuration options for [`spawn_decoder_worker_optimized`].
#[derive(Clone, Default)]
pub struct LowLatencyWorkerOptions {
    /// Bounded channel for delivering downscaled RGBA [`PreviewFrame`]s to the GUI.
    pub preview_tx: Option<Sender<PreviewFrame>>,
    /// Optional return channel yielding spent RGBA buffers from the GUI for zero-allocation reuse.
    pub preview_recycle_rx: Option<Receiver<Vec<u8>>>,
    /// Shared runtime atomics controlling preview enablement, resolution cap, and cadence.
    pub preview_ctrl: PreviewRuntimeControl,
    /// Optional notification callback invoked right after a [`PreviewFrame`] is queued.
    pub on_preview_pushed: Option<Arc<dyn Fn() + Send + Sync>>,
    /// Optional callback invoked when the decoder recovery tracker requests an IDR keyframe.
    pub on_keyframe_request: Option<Arc<dyn Fn(KeyframeRequestReason) + Send + Sync>>,
    /// FFmpeg H.264 decoder tuning options.
    pub decoder_options: DecoderOptions,
}

impl std::fmt::Debug for LowLatencyWorkerOptions {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("LowLatencyWorkerOptions")
            .field("has_preview_tx", &self.preview_tx.is_some())
            .field("has_preview_recycle_rx", &self.preview_recycle_rx.is_some())
            .field("preview_ctrl", &self.preview_ctrl)
            .field("has_on_preview_pushed", &self.on_preview_pushed.is_some())
            .field(
                "has_on_keyframe_request",
                &self.on_keyframe_request.is_some(),
            )
            .field("decoder_options", &self.decoder_options)
            .finish()
    }
}

/// Commands dispatched to the dedicated video decoder worker thread.
#[derive(Debug)]
pub enum DecoderCommand {
    /// Codec configuration containing SPS and PPS Annex-B NAL units.
    Config { pts_us: u64, data: Bytes },
    /// Video frame containing Annex-B NAL unit(s).
    Frame {
        seq: u64,
        pts_us: u64,
        is_keyframe: bool,
        flags: FrameFlags,
        data: Bytes,
    },
    /// Flush decoder buffers.
    Flush,
    /// Reset decoder and continuously emit standby frames on the V4L2 sink until live frames resume.
    EnterStandby,
    /// Terminate decoder worker.
    Stop,
}

struct CachedScaler {
    ctx: ScaleContext,
    dst_frame: VideoFrame,
    dst_width: u32,
    dst_height: u32,
}

struct FrameConverter {
    yuv_scaler: Option<CachedScaler>,
    rgba_scaler: Option<CachedScaler>,
    yuv_scratch: Vec<u8>,
    rgba_scratch: Vec<u8>,
    enable_rgba: bool,
    #[cfg(test)]
    rgba_scaler_rebuilds: usize,
}

impl FrameConverter {
    fn new(enable_rgba: bool) -> Self {
        Self {
            yuv_scaler: None,
            rgba_scaler: None,
            yuv_scratch: Vec::new(),
            rgba_scratch: Vec::new(),
            enable_rgba,
            #[cfg(test)]
            rgba_scaler_rebuilds: 0,
        }
    }

    fn extract_yuv420p(&mut self, raw: &VideoFrame) -> Result<(u32, u32, &[u8]), DecoderError> {
        self.extract_yuv420p_transformed(raw, VideoTransform::default())
    }

    fn extract_yuv420p_transformed(
        &mut self,
        raw: &VideoFrame,
        transform: VideoTransform,
    ) -> Result<(u32, u32, &[u8]), DecoderError> {
        let width = raw.width();
        let height = raw.height();
        let format = raw.format();

        if width == 0 || height == 0 {
            return Err(DecoderError::ContextInit(
                "Invalid zero frame dimensions from decoder".to_string(),
            ));
        }

        // YUV420P and YUVJ420P share identical planar 4:2:0 memory layout; avoid libswscale copy.
        let (out_w, out_h) = if matches!(format, Pixel::YUV420P | Pixel::YUVJ420P) {
            copy_transformed_yuv420p(raw, width, height, transform, &mut self.yuv_scratch)
        } else {
            let (scaler, _rebuilt) = get_or_init_scaler(
                &mut self.yuv_scaler,
                format,
                width,
                height,
                Pixel::YUV420P,
                width,
                height,
            )?;
            scaler
                .ctx
                .run(raw, &mut scaler.dst_frame)
                .map_err(DecoderError::Ffmpeg)?;
            copy_transformed_yuv420p(
                &scaler.dst_frame,
                width,
                height,
                transform,
                &mut self.yuv_scratch,
            )
        };

        Ok((out_w, out_h, self.yuv_scratch.as_slice()))
    }

    fn convert_preview_rgba_into(
        &mut self,
        raw: &VideoFrame,
        max_width: u32,
        dst: &mut Vec<u8>,
    ) -> Result<(u32, u32), DecoderError> {
        let width = raw.width();
        let height = raw.height();
        let format = raw.format();
        let (dst_w, dst_h) = compute_preview_dimensions(width, height, max_width);

        let (scaler, rebuilt) = get_or_init_scaler(
            &mut self.rgba_scaler,
            format,
            width,
            height,
            Pixel::RGBA,
            dst_w,
            dst_h,
        )?;
        #[cfg(test)]
        if rebuilt {
            self.rgba_scaler_rebuilds += 1;
        }
        #[cfg(not(test))]
        let _ = rebuilt;

        scaler
            .ctx
            .run(raw, &mut scaler.dst_frame)
            .map_err(DecoderError::Ffmpeg)?;
        copy_contiguous_rgba(&scaler.dst_frame, dst_w, dst_h, dst);
        Ok((dst_w, dst_h))
    }

    fn convert_preview_rgba(
        &mut self,
        raw: &VideoFrame,
        max_width: u32,
    ) -> Result<(u32, u32, &[u8]), DecoderError> {
        let mut scratch = std::mem::take(&mut self.rgba_scratch);
        let res = self.convert_preview_rgba_into(raw, max_width, &mut scratch);
        self.rgba_scratch = scratch;
        let (dst_w, dst_h) = res?;
        Ok((dst_w, dst_h, self.rgba_scratch.as_slice()))
    }

    fn convert(
        &mut self,
        raw: &VideoFrame,
        pts_us: u64,
        is_keyframe: bool,
        decode_time_ms: f32,
    ) -> Result<DecodedFrameRef<'_>, DecoderError> {
        let (width, height, _) = self.extract_yuv420p(raw)?;

        // Convert to full-resolution RGBA only if explicitly requested by legacy callers
        let has_rgba = if self.enable_rgba {
            let _ = self.convert_preview_rgba(raw, width)?;
            true
        } else {
            false
        };

        Ok(DecodedFrameRef {
            width,
            height,
            pts_us,
            is_keyframe,
            decode_time_ms,
            yuv420p: self.yuv_scratch.as_slice(),
            rgba: has_rgba.then_some(self.rgba_scratch.as_slice()),
        })
    }
}

fn get_or_init_scaler(
    slot: &mut Option<CachedScaler>,
    src_format: Pixel,
    src_width: u32,
    src_height: u32,
    dst_format: Pixel,
    dst_width: u32,
    dst_height: u32,
) -> Result<(&mut CachedScaler, bool), DecoderError> {
    let needs_rebuild = match slot.as_ref() {
        Some(cached) => {
            let input = cached.ctx.input();
            input.format != src_format
                || input.width != src_width
                || input.height != src_height
                || cached.dst_width != dst_width
                || cached.dst_height != dst_height
        }
        None => true,
    };

    if needs_rebuild {
        debug!(
            ?src_format,
            ?dst_format,
            src_width,
            src_height,
            dst_width,
            dst_height,
            "Configuring libswscale scaler"
        );
        let ctx = ScaleContext::get(
            src_format,
            src_width,
            src_height,
            dst_format,
            dst_width,
            dst_height,
            ScaleFlags::FAST_BILINEAR,
        )
        .map_err(DecoderError::Ffmpeg)?;

        *slot = Some(CachedScaler {
            ctx,
            dst_frame: VideoFrame::empty(),
            dst_width,
            dst_height,
        });
    }

    let scaler = slot
        .as_mut()
        .ok_or_else(|| DecoderError::ScaleError("scaler not initialized".to_string()))?;
    Ok((scaler, needs_rebuild))
}

/// Core H.264 video decoder wrapping FFmpeg `libavcodec` and `libswscale`.
pub struct H264Decoder {
    decoder: ffmpeg_next::codec::decoder::Video,
    converter: FrameConverter,
    raw_frame: VideoFrame,
    cached_config: Vec<u8>,
    pending_config: bool,
    packet_scratch: Vec<u8>,
    last_pts_us: u64,
    last_is_keyframe: bool,
}

impl H264Decoder {
    /// Creates and configures a new low-delay H.264 video decoder with default
    /// thread count (`2`) and fast decode flags.
    pub fn new(enable_rgba: bool) -> Result<Self, DecoderError> {
        Self::with_options(DecoderOptions {
            enable_rgba,
            ..DecoderOptions::default()
        })
    }

    /// Creates and configures a new low-delay H.264 video decoder with explicit [`DecoderOptions`].
    pub fn with_options(opts: DecoderOptions) -> Result<Self, DecoderError> {
        ffmpeg_next::init().map_err(DecoderError::Ffmpeg)?;

        let codec = ffmpeg_next::codec::decoder::find(ffmpeg_next::codec::Id::H264)
            .ok_or(DecoderError::CodecNotFound)?;

        let mut context = ffmpeg_next::codec::Context::new_with_codec(codec);
        // Set low-delay flag to disable frame reordering buffering for real-time streaming
        context.set_flags(CodecFlags::LOW_DELAY);

        if opts.fast_decode {
            // SAFETY: `context` wraps an initialized `AVCodecContext` created by
            // `new_with_codec` before opening the decoder; mutating `flags2` in place is valid.
            unsafe {
                let ptr = context.as_mut_ptr();
                if !ptr.is_null() {
                    (*ptr).flags2 |= ffmpeg_next::ffi::AV_CODEC_FLAG2_FAST;
                }
            }
        }

        // Configure bounded slice threading for parallel slice decoding without introducing frame latency
        let mut threading =
            ffmpeg_next::codec::threading::Config::kind(ffmpeg_next::codec::threading::Type::Slice);
        threading.count = opts.thread_count.clamp(1, 4);
        context.set_threading(threading);

        let decoder = context.decoder().video().map_err(DecoderError::Ffmpeg)?;

        info!("H.264 low-delay video decoder initialized successfully");

        Ok(Self {
            decoder,
            converter: FrameConverter::new(opts.enable_rgba),
            raw_frame: VideoFrame::empty(),
            cached_config: Vec::new(),
            pending_config: false,
            packet_scratch: Vec::new(),
            last_pts_us: 0,
            last_is_keyframe: false,
        })
    }

    /// Caches a `VIDEO_CONFIG` packet containing SPS/PPS Annex-B NAL units so it is
    /// prepended to the next video Access Unit.
    pub fn decode_config(&mut self, pts_us: u64, data: &[u8]) -> Result<(), DecoderError> {
        if data.is_empty() {
            return Ok(());
        }

        debug!(
            bytes = data.len(),
            pts_us, "Caching VIDEO_CONFIG SPS/PPS for next H.264 Access Unit"
        );
        self.cached_config.clear();
        self.cached_config.extend_from_slice(data);
        self.pending_config = true;
        Ok(())
    }

    fn send_annex_b_packet(
        &mut self,
        pts_us: u64,
        is_keyframe: bool,
        data: &[u8],
    ) -> Result<(), DecoderError> {
        self.last_pts_us = pts_us;
        self.last_is_keyframe = is_keyframe;

        // Prepend pending SPS/PPS to the Access Unit so FFmpeg parses parameter sets cleanly,
        // and use Packet::copy so libavcodec receives AV_INPUT_BUFFER_PADDING_SIZE padding.
        let mut packet = if self.pending_config && !self.cached_config.is_empty() {
            self.packet_scratch.clear();
            self.packet_scratch.extend_from_slice(&self.cached_config);
            self.packet_scratch.extend_from_slice(data);
            self.pending_config = false;
            ffmpeg_next::Packet::copy(&self.packet_scratch)
        } else {
            ffmpeg_next::Packet::copy(data)
        };

        packet.set_pts(i64::try_from(pts_us).ok());
        self.decoder
            .send_packet(&packet)
            .map_err(DecoderError::Ffmpeg)
    }

    fn validate_decoded_raw_frame(
        &self,
        pts_us: u64,
        is_keyframe: bool,
    ) -> Result<(), DecoderError> {
        if self.raw_frame.is_corrupt() || self.raw_frame.has_decode_errors() {
            warn!(
                pts_us,
                is_keyframe,
                corrupt = self.raw_frame.is_corrupt(),
                decode_error_flags = ?self.raw_frame.decode_error_flags(),
                "Discarding corrupted decoded H.264 frame"
            );
            return Err(DecoderError::CorruptedFrame(format!(
                "frame pts={pts_us} corrupt={} decode_errors={}",
                self.raw_frame.is_corrupt(),
                self.raw_frame.has_decode_errors()
            )));
        }
        Ok(())
    }

    /// Feeds a `VIDEO_FRAME` packet and invokes `on_frame` for each decoded frame without
    /// allocating new pixel buffers in steady state.
    pub fn decode_frame_with<F>(
        &mut self,
        pts_us: u64,
        is_keyframe: bool,
        data: &[u8],
        mut on_frame: F,
    ) -> Result<(), DecoderError>
    where
        F: FnMut(DecodedFrameRef<'_>),
    {
        if data.is_empty() {
            return Ok(());
        }

        let decode_start = std::time::Instant::now();
        self.send_annex_b_packet(pts_us, is_keyframe, data)?;

        loop {
            match self.decoder.receive_frame(&mut self.raw_frame) {
                Ok(()) => {
                    self.validate_decoded_raw_frame(pts_us, is_keyframe)?;
                    let frame_pts_us = self
                        .raw_frame
                        .pts()
                        .and_then(|p| u64::try_from(p).ok())
                        .unwrap_or(pts_us);
                    let decode_time_ms = decode_start.elapsed().as_secs_f32() * 1000.0;
                    let decoded = self.converter.convert(
                        &self.raw_frame,
                        frame_pts_us,
                        is_keyframe,
                        decode_time_ms,
                    )?;
                    on_frame(decoded);
                }
                Err(ffmpeg_next::Error::Other { errno }) if errno == EAGAIN => {
                    // Decoder needs more input packets before returning more frames
                    break;
                }
                Err(ffmpeg_next::Error::Eof) => {
                    break;
                }
                Err(e) => {
                    warn!(error = %e, "Decoder receive_frame returned error");
                    return Err(DecoderError::Ffmpeg(e));
                }
            }
        }

        Ok(())
    }

    /// Decodes a `VIDEO_FRAME`, emits contiguous `YUV420P` immediately to `on_yuv` (for V4L2),
    /// and builds a downscaled RGBA [`PreviewFrame`] only when `preview_max_width()` returns
    /// `Some(max_width)`.
    pub fn decode_yuv_and_optional_preview<FYuv, FPreview>(
        &mut self,
        pts_us: u64,
        is_keyframe: bool,
        data: &[u8],
        on_yuv: FYuv,
        preview_max_width: impl FnMut() -> Option<u32>,
        on_preview: FPreview,
    ) -> Result<(), DecoderError>
    where
        FYuv: FnMut(u32, u32, &[u8], f32),
        FPreview: FnMut(PreviewFrame),
    {
        self.decode_yuv_transformed_and_optional_preview(
            pts_us,
            is_keyframe,
            VideoTransform::default(),
            data,
            on_yuv,
            preview_max_width,
            Vec::new,
            on_preview,
        )
    }

    /// Decodes a `VIDEO_FRAME`, applies `transform` when packing contiguous `YUV420P` for V4L2,
    /// and builds a raw downscaled RGBA [`PreviewFrame`] using a buffer supplied by `take_preview_buf`
    /// only when `preview_max_width()` returns `Some(max_width)`.
    #[allow(clippy::too_many_arguments)]
    pub fn decode_yuv_transformed_and_optional_preview<FYuv, FBuf, FPreview>(
        &mut self,
        pts_us: u64,
        is_keyframe: bool,
        transform: VideoTransform,
        data: &[u8],
        mut on_yuv: FYuv,
        mut preview_max_width: impl FnMut() -> Option<u32>,
        mut take_preview_buf: FBuf,
        mut on_preview: FPreview,
    ) -> Result<(), DecoderError>
    where
        FYuv: FnMut(u32, u32, &[u8], f32),
        FBuf: FnMut() -> Vec<u8>,
        FPreview: FnMut(PreviewFrame),
    {
        if data.is_empty() {
            return Ok(());
        }

        let decode_start = std::time::Instant::now();
        self.send_annex_b_packet(pts_us, is_keyframe, data)?;

        loop {
            match self.decoder.receive_frame(&mut self.raw_frame) {
                Ok(()) => {
                    self.validate_decoded_raw_frame(pts_us, is_keyframe)?;
                    let decode_time_ms = decode_start.elapsed().as_secs_f32() * 1000.0;
                    let (out_w, out_h, yuv_slice) = self
                        .converter
                        .extract_yuv420p_transformed(&self.raw_frame, transform)?;
                    on_yuv(out_w, out_h, yuv_slice, decode_time_ms);

                    if let Some(max_width) = preview_max_width() {
                        let source_width = self.raw_frame.width();
                        let source_height = self.raw_frame.height();
                        let mut rgba_buf = take_preview_buf();
                        let (preview_width, preview_height) = self
                            .converter
                            .convert_preview_rgba_into(&self.raw_frame, max_width, &mut rgba_buf)?;
                        on_preview(PreviewFrame {
                            source_width,
                            source_height,
                            preview_width,
                            preview_height,
                            decode_time_ms,
                            rgba: rgba_buf,
                        });
                    }
                }
                Err(ffmpeg_next::Error::Other { errno }) if errno == EAGAIN => {
                    break;
                }
                Err(ffmpeg_next::Error::Eof) => {
                    break;
                }
                Err(e) => {
                    warn!(error = %e, "Decoder receive_frame returned error");
                    return Err(DecoderError::Ffmpeg(e));
                }
            }
        }

        Ok(())
    }

    /// Feeds a `VIDEO_FRAME` packet and returns owned decoded frames.
    pub fn decode_frame(
        &mut self,
        pts_us: u64,
        is_keyframe: bool,
        data: &[u8],
    ) -> Result<Vec<DecodedFrame>, DecoderError> {
        let mut out_frames = Vec::new();
        self.decode_frame_with(pts_us, is_keyframe, data, |frame| {
            out_frames.push(frame.to_owned_frame());
        })?;
        Ok(out_frames)
    }

    /// Flushes any pending frames in the decoder pipeline into `on_frame`.
    pub fn flush_with<F>(&mut self, mut on_frame: F) -> Result<(), DecoderError>
    where
        F: FnMut(DecodedFrameRef<'_>),
    {
        let _ = self.decoder.send_eof();

        while let Ok(()) = self.decoder.receive_frame(&mut self.raw_frame) {
            let frame_pts_us = self
                .raw_frame
                .pts()
                .and_then(|p| u64::try_from(p).ok())
                .unwrap_or(self.last_pts_us);
            let decoded = self.converter.convert(
                &self.raw_frame,
                frame_pts_us,
                self.last_is_keyframe,
                0.0,
            )?;
            on_frame(decoded);
        }

        self.decoder.flush();
        if !self.cached_config.is_empty() {
            self.pending_config = true;
        }
        Ok(())
    }

    /// Flushes any pending frames in the decoder pipeline and returns owned decoded frames.
    pub fn flush(&mut self) -> Result<Vec<DecodedFrame>, DecoderError> {
        let mut out_frames = Vec::new();
        self.flush_with(|frame| {
            out_frames.push(frame.to_owned_frame());
        })?;
        Ok(out_frames)
    }

    /// Flushes decoder internal buffers after a decode error and re-arms cached SPS/PPS
    /// config so the next incoming IDR keyframe receives clean codec parameter sets.
    pub fn reset_after_error(&mut self) {
        self.decoder.flush();
        self.pending_config = !self.cached_config.is_empty();
    }

    /// Flushes decoder state and clears cached SPS/PPS configuration when entering standby
    /// so a reconnected stream initializes cleanly from fresh `VIDEO_CONFIG` parameter sets.
    pub fn reset_for_reconnect(&mut self) {
        self.decoder.flush();
        self.cached_config.clear();
        self.pending_config = false;
    }
}

#[inline]
fn copy_plane_contiguous(src: &[u8], stride: usize, row_bytes: usize, rows: usize, dst: &mut [u8]) {
    if row_bytes == 0 || rows == 0 {
        return;
    }
    if stride == row_bytes {
        let total = row_bytes * rows;
        dst[..total].copy_from_slice(&src[..total]);
    } else {
        for (row, dst_row) in dst.chunks_exact_mut(row_bytes).take(rows).enumerate() {
            let start = row * stride;
            dst_row.copy_from_slice(&src[start..start + row_bytes]);
        }
    }
}

/// Copies plane data line-by-line into `dst` to ensure contiguous YUV420P memory layout
/// without alignment padding, reusing `dst`'s allocation across frames.
pub fn copy_contiguous_yuv420p(frame: &VideoFrame, width: u32, height: u32, dst: &mut Vec<u8>) {
    let w = width as usize;
    let h = height as usize;
    let half_w = w / 2;
    let half_h = h / 2;
    let y_len = w * h;
    let uv_len = half_w * half_h;
    let total_bytes = y_len + 2 * uv_len;

    dst.resize(total_bytes, 0);
    if total_bytes == 0 {
        return;
    }

    let (dst_y, dst_uv) = dst.split_at_mut(y_len);
    let (dst_u, dst_v) = dst_uv.split_at_mut(uv_len);

    copy_plane_contiguous(frame.data(0), frame.stride(0), w, h, dst_y);
    copy_plane_contiguous(frame.data(1), frame.stride(1), half_w, half_h, dst_u);
    copy_plane_contiguous(frame.data(2), frame.stride(2), half_w, half_h, dst_v);
}

#[inline]
fn transform_plane_bytes(
    src: &[u8],
    stride: usize,
    w: usize,
    h: usize,
    mirror_horizontal: bool,
    rotation_deg: u16,
    dst: &mut [u8],
) {
    if w == 0 || h == 0 {
        return;
    }

    match rotation_deg % 360 {
        90 => {
            for dy in 0..w {
                let sx = if mirror_horizontal { w - 1 - dy } else { dy };
                let dst_row = &mut dst[dy * h..(dy + 1) * h];
                for (dx, px) in dst_row.iter_mut().enumerate() {
                    let sy = h - 1 - dx;
                    *px = src[sy * stride + sx];
                }
            }
        }
        180 => {
            for row in 0..h {
                let src_row = &src[row * stride..row * stride + w];
                let dst_y = h - 1 - row;
                let dst_row = &mut dst[dst_y * w..(dst_y + 1) * w];
                dst_row.copy_from_slice(src_row);
                if !mirror_horizontal {
                    dst_row.reverse();
                }
            }
        }
        270 => {
            for dy in 0..w {
                let sx = if mirror_horizontal { dy } else { w - 1 - dy };
                let dst_row = &mut dst[dy * h..(dy + 1) * h];
                for (dx, px) in dst_row.iter_mut().enumerate() {
                    *px = src[dx * stride + sx];
                }
            }
        }
        _ => {
            // 0 deg (with mirror_horizontal == true; identity fast-paths before here)
            for row in 0..h {
                let src_row = &src[row * stride..row * stride + w];
                let dst_row = &mut dst[row * w..(row + 1) * w];
                dst_row.copy_from_slice(src_row);
                if mirror_horizontal {
                    dst_row.reverse();
                }
            }
        }
    }
}

/// Copies and transforms a planar YUV420P [`VideoFrame`] into contiguous `YU12` layout in `dst`,
/// applying `mirror_horizontal` before clockwise rotation (`0°`/`90°`/`180°`/`270°`) to match
/// the desktop GPU preview 1:1. Returns the transformed `(out_width, out_height)`.
pub fn copy_transformed_yuv420p(
    frame: &VideoFrame,
    width: u32,
    height: u32,
    transform: VideoTransform,
    dst: &mut Vec<u8>,
) -> (u32, u32) {
    if transform.is_identity() {
        copy_contiguous_yuv420p(frame, width, height, dst);
        return (width, height);
    }

    let rot = transform.rotation_deg % 360;
    let swapped = rot == 90 || rot == 270;
    let (out_w_u32, out_h_u32) = if swapped {
        (height, width)
    } else {
        (width, height)
    };

    let w = width as usize;
    let h = height as usize;
    let half_w = w / 2;
    let half_h = h / 2;

    let out_w = out_w_u32 as usize;
    let out_h = out_h_u32 as usize;
    let half_out_w = out_w / 2;
    let half_out_h = out_h / 2;

    let y_len = out_w * out_h;
    let uv_len = half_out_w * half_out_h;
    let total_bytes = y_len + 2 * uv_len;

    dst.resize(total_bytes, 0);
    if total_bytes == 0 {
        return (out_w_u32, out_h_u32);
    }

    let (dst_y, dst_uv) = dst.split_at_mut(y_len);
    let (dst_u, dst_v) = dst_uv.split_at_mut(uv_len);

    transform_plane_bytes(
        frame.data(0),
        frame.stride(0),
        w,
        h,
        transform.mirror_horizontal,
        rot,
        dst_y,
    );
    transform_plane_bytes(
        frame.data(1),
        frame.stride(1),
        half_w,
        half_h,
        transform.mirror_horizontal,
        rot,
        dst_u,
    );
    transform_plane_bytes(
        frame.data(2),
        frame.stride(2),
        half_w,
        half_h,
        transform.mirror_horizontal,
        rot,
        dst_v,
    );

    (out_w_u32, out_h_u32)
}

/// Copies plane data line-by-line to ensure contiguous YUV420P memory layout without alignment padding.
pub fn extract_contiguous_yuv420p(frame: &VideoFrame, width: u32, height: u32) -> Vec<u8> {
    let mut out = Vec::new();
    copy_contiguous_yuv420p(frame, width, height, &mut out);
    out
}

/// Copies RGBA data line-by-line into `dst` to ensure contiguous memory layout,
/// reusing `dst`'s allocation across frames.
pub fn copy_contiguous_rgba(frame: &VideoFrame, width: u32, height: u32, dst: &mut Vec<u8>) {
    let w = width as usize;
    let h = height as usize;
    let bytes_per_line = w * 4;
    let total_bytes = bytes_per_line * h;

    dst.resize(total_bytes, 0);
    if total_bytes == 0 {
        return;
    }

    copy_plane_contiguous(frame.data(0), frame.stride(0), bytes_per_line, h, dst);
}

/// Copies RGBA data line-by-line to ensure contiguous memory layout.
pub fn extract_contiguous_rgba(frame: &VideoFrame, width: u32, height: u32) -> Vec<u8> {
    let mut out = Vec::new();
    copy_contiguous_rgba(frame, width, height, &mut out);
    out
}

/// Asynchronous handle used by the networking layer to push H.264 packets into the decoder worker.
#[derive(Clone)]
pub struct DecoderSender {
    cmd_tx: Sender<DecoderCommand>,
    cmd_rx_drain: Receiver<DecoderCommand>,
    next_seq: Arc<AtomicU64>,
    capacity: usize,
}

impl DecoderSender {
    /// Creates a sender/receiver pair with a bounded queue of capacity [`DECODER_QUEUE_CAPACITY`].
    #[must_use]
    pub fn new_bounded() -> (Self, Receiver<DecoderCommand>) {
        Self::new_with_capacity(DECODER_QUEUE_CAPACITY)
    }

    /// Creates a sender/receiver pair with an explicit bounded queue `capacity` (`>= 1`).
    #[must_use]
    pub fn new_with_capacity(capacity: usize) -> (Self, Receiver<DecoderCommand>) {
        let cap = capacity.max(1);
        let (tx, rx) = crossbeam_channel::bounded(cap);
        let sender = Self {
            cmd_tx: tx,
            cmd_rx_drain: rx.clone(),
            next_seq: Arc::new(AtomicU64::new(0)),
            capacity: cap,
        };
        (sender, rx)
    }

    /// Drains queued commands up to capacity, drops the oldest `Frame` (or an older duplicate
    /// `Config` if no `Frame` is present) while preserving relative order of `Config`/`Flush`/`Stop`,
    /// and re-inserts the remaining commands.
    fn drop_one_stale_frame(&self) -> bool {
        let mut drained: Vec<Option<DecoderCommand>> = Vec::with_capacity(self.capacity);
        while drained.len() < self.capacity {
            match self.cmd_rx_drain.try_recv() {
                Ok(cmd) => drained.push(Some(cmd)),
                Err(_) => break,
            }
        }

        let mut freed = false;
        for slot in &mut drained {
            if matches!(slot, Some(DecoderCommand::Frame { .. })) {
                *slot = None;
                freed = true;
                break;
            }
        }

        // If no frames are queued, coalesce any superseded older Config before the latest Config.
        if !freed {
            let last_config_idx = drained
                .iter()
                .rposition(|slot| matches!(slot, Some(DecoderCommand::Config { .. })));
            if let Some(last_idx) = last_config_idx {
                for (idx, slot) in drained.iter_mut().enumerate() {
                    if idx < last_idx && matches!(slot, Some(DecoderCommand::Config { .. })) {
                        *slot = None;
                        freed = true;
                    }
                }
            }
        }

        for slot in &mut drained {
            if let Some(cmd) = slot.take() {
                let _ = self.cmd_tx.try_send(cmd);
            }
        }

        freed
    }

    /// Fallback used by `push_config` / `stop` when no frames are queued: drops the oldest
    /// queued `Config` while preserving relative order of remaining commands.
    fn drop_oldest_config(&self) -> bool {
        let mut drained: Vec<Option<DecoderCommand>> = Vec::with_capacity(self.capacity);
        while drained.len() < self.capacity {
            match self.cmd_rx_drain.try_recv() {
                Ok(cmd) => drained.push(Some(cmd)),
                Err(_) => break,
            }
        }

        let mut freed = false;
        for slot in &mut drained {
            if matches!(slot, Some(DecoderCommand::Config { .. })) {
                *slot = None;
                freed = true;
                break;
            }
        }

        for slot in &mut drained {
            if let Some(cmd) = slot.take() {
                let _ = self.cmd_tx.try_send(cmd);
            }
        }

        freed
    }

    /// Pushes a `VIDEO_CONFIG` packet into the queue without blocking the async network loop.
    pub fn push_config(&self, pts_us: u64, data: Bytes) -> Result<(), DecoderError> {
        let cmd = DecoderCommand::Config { pts_us, data };
        match self.cmd_tx.try_send(cmd) {
            Ok(()) => Ok(()),
            Err(TrySendError::Full(cmd)) => {
                if !self.drop_one_stale_frame() {
                    let _ = self.drop_oldest_config();
                }
                match self.cmd_tx.try_send(cmd) {
                    Ok(()) => Ok(()),
                    Err(TrySendError::Full(_)) => {
                        warn!(
                            "Decoder queue full of non-droppable commands, dropping incoming VIDEO_CONFIG"
                        );
                        Ok(())
                    }
                    Err(TrySendError::Disconnected(_)) => Err(DecoderError::ChannelDisconnected),
                }
            }
            Err(TrySendError::Disconnected(_)) => Err(DecoderError::ChannelDisconnected),
        }
    }

    /// Pushes a `VIDEO_FRAME` packet with smart backpressure handling.
    ///
    /// If the bounded queue is full:
    /// - If the new frame is an IDR keyframe: drops the oldest queued frame (preserving
    ///   queued `Config`/`Flush`/`Stop` commands) to make room.
    /// - If the new frame is a non-keyframe (P-frame): drops the incoming P-frame so the
    ///   in-flight H.264 reference chain stays intact and latency does not build up.
    pub fn push_frame(
        &self,
        pts_us: u64,
        is_keyframe: bool,
        data: Bytes,
    ) -> Result<bool, DecoderError> {
        let flags = FrameFlags::empty().with_keyframe(is_keyframe);
        self.push_frame_with_flags(pts_us, flags, data)
    }

    /// Pushes a `VIDEO_FRAME` packet carrying full CCFT [`FrameFlags`] (including keyframe,
    /// mirror, and rotation metadata) with smart backpressure handling.
    pub fn push_frame_with_flags(
        &self,
        pts_us: u64,
        flags: FrameFlags,
        data: Bytes,
    ) -> Result<bool, DecoderError> {
        let is_keyframe = flags.is_keyframe();
        let seq = self.next_seq.fetch_add(1, Ordering::Relaxed);
        let cmd = DecoderCommand::Frame {
            seq,
            pts_us,
            is_keyframe,
            flags,
            data,
        };

        match self.cmd_tx.try_send(cmd) {
            Ok(()) => Ok(true),
            Err(TrySendError::Full(cmd)) => {
                if is_keyframe {
                    trace!("Dropping oldest frame in queue to make room for critical IDR keyframe");
                    let _ = self.drop_one_stale_frame();
                    match self.cmd_tx.try_send(cmd) {
                        Ok(()) => Ok(true),
                        Err(TrySendError::Full(_)) => {
                            warn!("Queue remained full after drop, dropping frame");
                            Ok(false)
                        }
                        Err(TrySendError::Disconnected(_)) => {
                            Err(DecoderError::ChannelDisconnected)
                        }
                    }
                } else {
                    // Dropping the incoming P-frame preserves the reference chain of already-queued frames.
                    trace!("Dropping non-keyframe due to decoder worker backpressure");
                    Ok(false)
                }
            }
            Err(TrySendError::Disconnected(_)) => Err(DecoderError::ChannelDisconnected),
        }
    }

    /// Switches the decoder worker into standby mode without closing the virtual camera sink.
    pub fn enter_standby(&self) {
        if let Err(TrySendError::Full(cmd)) = self.cmd_tx.try_send(DecoderCommand::EnterStandby) {
            if !self.drop_one_stale_frame() {
                let _ = self.drop_oldest_config();
            }
            let _ = self.cmd_tx.try_send(cmd);
        }
    }

    /// Requests the decoder worker to flush and terminate without blocking.
    pub fn stop(&self) {
        if let Err(TrySendError::Full(cmd)) = self.cmd_tx.try_send(DecoderCommand::Stop) {
            if !self.drop_one_stale_frame() {
                let _ = self.drop_oldest_config();
            }
            let _ = self.cmd_tx.try_send(cmd);
        }
    }
}

fn write_standby_tick(
    sink: &mut Option<Box<dyn VirtualCameraSink>>,
    last_dims: (u32, u32),
    standby_buf: &mut Vec<u8>,
    standby_buf_dims: &mut (u32, u32),
) {
    let Some(vcam_sink) = sink.as_mut() else {
        return;
    };
    if *standby_buf_dims != last_dims {
        fill_standby_frame(
            standby_buf,
            last_dims.0,
            last_dims.1,
            StandbyKind::Reconnecting,
        );
        *standby_buf_dims = last_dims;
    }
    if let Err(e) = vcam_sink.write_frame(last_dims.0, last_dims.1, standby_buf) {
        warn!(error = %e, "Failed to write standby frame to virtual camera sink");
    }
}

fn dispatch_decoded_frame(
    frame: DecodedFrameRef<'_>,
    sink: &mut Option<Box<dyn VirtualCameraSink>>,
    frame_tx: &Option<Sender<DecodedFrame>>,
) {
    // 1. Write directly from borrowed YUV420P slice without extra heap copy
    if let Some(vcam_sink) = sink
        && let Err(e) = vcam_sink.write_frame(frame.width, frame.height, frame.yuv420p)
    {
        warn!(error = %e, "Failed to write frame to virtual camera sink");
    }

    // 2. Forward owned frame to GUI preview only when preview channel is attached
    if let Some(tx) = frame_tx {
        let _ = tx.try_send(frame.to_owned_frame());
    }
}

/// Spawns the dedicated background worker thread (`camcraft-vdec`) for video decoding.
pub fn spawn_decoder_worker(
    cmd_rx: Receiver<DecoderCommand>,
    sink: Option<Box<dyn VirtualCameraSink>>,
    frame_tx: Option<Sender<DecodedFrame>>,
    enable_rgba: bool,
    running: Arc<AtomicBool>,
) -> std::io::Result<JoinHandle<Result<(), DecoderError>>> {
    spawn_decoder_worker_with_recovery(cmd_rx, sink, frame_tx, enable_rgba, running, None)
}

enum WorkerOutputMode {
    Legacy {
        frame_tx: Option<Sender<DecodedFrame>>,
        enable_rgba: bool,
    },
    LowLatency {
        opts: LowLatencyWorkerOptions,
        last_preview_sent: Option<Instant>,
        local_spare_buf: Option<Vec<u8>>,
    },
}

/// Spawns the dedicated background worker thread (`camcraft-vdec`) with keyframe recovery signaling.
pub fn spawn_decoder_worker_with_recovery(
    cmd_rx: Receiver<DecoderCommand>,
    sink: Option<Box<dyn VirtualCameraSink>>,
    frame_tx: Option<Sender<DecodedFrame>>,
    enable_rgba: bool,
    running: Arc<AtomicBool>,
    keyframe_req_tx: Option<Sender<KeyframeRequestReason>>,
) -> std::io::Result<JoinHandle<Result<(), DecoderError>>> {
    let on_keyframe_request = keyframe_req_tx.map(|tx| {
        Arc::new(move |reason| {
            let _ = tx.try_send(reason);
        }) as Arc<dyn Fn(KeyframeRequestReason) + Send + Sync>
    });
    spawn_decoder_worker_inner(
        cmd_rx,
        sink,
        WorkerOutputMode::Legacy {
            frame_tx,
            enable_rgba,
        },
        running,
        on_keyframe_request,
    )
}

/// Spawns a V4L2-first low-latency decoder worker (`camcraft-vdec`) that writes YUV420P frames
/// directly to `/dev/video*` before building downscaled GUI [`PreviewFrame`]s on demand.
pub fn spawn_decoder_worker_low_latency(
    cmd_rx: Receiver<DecoderCommand>,
    sink: Option<Box<dyn VirtualCameraSink>>,
    preview_tx: Option<Sender<PreviewFrame>>,
    preview_enabled: Arc<AtomicBool>,
    preview_max_width: u32,
    running: Arc<AtomicBool>,
    keyframe_req_tx: Option<Sender<KeyframeRequestReason>>,
) -> std::io::Result<JoinHandle<Result<(), DecoderError>>> {
    let on_keyframe_request = keyframe_req_tx.map(|tx| {
        Arc::new(move |reason| {
            let _ = tx.try_send(reason);
        }) as Arc<dyn Fn(KeyframeRequestReason) + Send + Sync>
    });
    let preview_ctrl = PreviewRuntimeControl {
        enabled: preview_enabled,
        max_width: Arc::new(AtomicU32::new(preview_max_width)),
        min_interval_ms: Arc::new(AtomicU32::new(PREVIEW_MIN_INTERVAL.as_millis() as u32)),
    };
    spawn_decoder_worker_optimized(
        cmd_rx,
        sink,
        LowLatencyWorkerOptions {
            preview_tx,
            preview_recycle_rx: None,
            preview_ctrl,
            on_preview_pushed: None,
            on_keyframe_request,
            decoder_options: DecoderOptions::default(),
        },
        running,
    )
}

/// Spawns an optimized V4L2-first low-latency decoder worker (`camcraft-vdec`) with support for
/// runtime preview throttling, zero-allocation RGBA buffer recycling, direct callbacks,
/// in-pipeline YUV420P mirror/rotation transforms, and FFmpeg tuning via [`LowLatencyWorkerOptions`].
pub fn spawn_decoder_worker_optimized(
    cmd_rx: Receiver<DecoderCommand>,
    sink: Option<Box<dyn VirtualCameraSink>>,
    mut opts: LowLatencyWorkerOptions,
    running: Arc<AtomicBool>,
) -> std::io::Result<JoinHandle<Result<(), DecoderError>>> {
    let on_keyframe_request = opts.on_keyframe_request.take();
    spawn_decoder_worker_inner(
        cmd_rx,
        sink,
        WorkerOutputMode::LowLatency {
            opts,
            last_preview_sent: None,
            local_spare_buf: None,
        },
        running,
        on_keyframe_request,
    )
}

fn spawn_decoder_worker_inner(
    cmd_rx: Receiver<DecoderCommand>,
    mut sink: Option<Box<dyn VirtualCameraSink>>,
    mut output_mode: WorkerOutputMode,
    running: Arc<AtomicBool>,
    on_keyframe_request: Option<Arc<dyn Fn(KeyframeRequestReason) + Send + Sync>>,
) -> std::io::Result<JoinHandle<Result<(), DecoderError>>> {
    std::thread::Builder::new()
        .name("camcraft-vdec".to_string())
        .spawn(move || {
            info!("Dedicated video decoder thread started (camcraft-vdec)");
            let decoder_opts = match &output_mode {
                WorkerOutputMode::Legacy { enable_rgba, .. } => DecoderOptions {
                    enable_rgba: *enable_rgba,
                    ..DecoderOptions::default()
                },
                WorkerOutputMode::LowLatency { opts, .. } => opts.decoder_options,
            };
            let mut decoder = H264Decoder::with_options(decoder_opts)?;
            let mut tracker = KeyframeRecoveryTracker::default();
            let mut last_frame_seq: Option<u64> = None;
            let mut in_standby = false;
            let mut last_dims = sink
                .as_ref()
                .and_then(|s| s.current_dimensions())
                .unwrap_or((1920, 1080));
            let mut standby_buf: Vec<u8> = Vec::new();
            let mut standby_buf_dims: (u32, u32) = (0, 0);

            let emit_keyframe_req = |reason: Option<KeyframeRequestReason>| {
                if let (Some(r), Some(cb)) = (reason, on_keyframe_request.as_ref()) {
                    cb(r);
                }
            };

            while running.load(Ordering::Relaxed) {
                let cmd = if in_standby {
                    match cmd_rx.recv_timeout(STANDBY_FRAME_INTERVAL) {
                        Ok(cmd) => cmd,
                        Err(RecvTimeoutError::Timeout) => {
                            write_standby_tick(
                                &mut sink,
                                last_dims,
                                &mut standby_buf,
                                &mut standby_buf_dims,
                            );
                            continue;
                        }
                        Err(RecvTimeoutError::Disconnected) => {
                            info!("Video decoder command channel disconnected");
                            break;
                        }
                    }
                } else {
                    match cmd_rx.recv() {
                        Ok(cmd) => cmd,
                        Err(_) => {
                            info!("Video decoder command channel disconnected");
                            break;
                        }
                    }
                };

                match cmd {
                    DecoderCommand::Config { pts_us, data } => {
                        tracker.on_config_received();
                        if let Err(e) = decoder.decode_config(pts_us, &data) {
                            warn!(error = %e, "Failed to decode VIDEO_CONFIG");
                        }
                    }
                    DecoderCommand::Frame {
                        seq,
                        pts_us,
                        is_keyframe,
                        flags,
                        data,
                    } => {
                        let now = Instant::now();
                        if !is_keyframe
                            && let Some(prev_seq) = last_frame_seq
                            && seq != prev_seq.saturating_add(1)
                        {
                            emit_keyframe_req(tracker.on_frame_dropped(now));
                        }
                        last_frame_seq = Some(seq);

                        match tracker.on_before_decode(pts_us, is_keyframe, now) {
                            PreDecodeAction::SkipUntilKeyframe { request } => {
                                emit_keyframe_req(request);
                                continue;
                            }
                            PreDecodeAction::DecodeAndRequest(reason) => {
                                emit_keyframe_req(Some(reason));
                            }
                            PreDecodeAction::Decode => {}
                        }

                        let mut decoded_count = 0usize;
                        let decode_res = match &mut output_mode {
                            WorkerOutputMode::Legacy { frame_tx, .. } => {
                                decoder.decode_frame_with(pts_us, is_keyframe, &data, |frame| {
                                    decoded_count += 1;
                                    last_dims = (frame.width, frame.height);
                                    in_standby = false;
                                    dispatch_decoded_frame(frame, &mut sink, frame_tx);
                                })
                            }
                            WorkerOutputMode::LowLatency {
                                opts,
                                last_preview_sent,
                                local_spare_buf,
                            } => {
                                let preview_channel_ready =
                                    opts.preview_ctrl.enabled.load(Ordering::Relaxed)
                                        && opts.preview_tx.as_ref().is_some_and(|tx| !tx.is_full());
                                let min_interval = Duration::from_millis(u64::from(
                                    opts.preview_ctrl.min_interval_ms.load(Ordering::Relaxed),
                                ));
                                let interval_elapsed = min_interval.is_zero()
                                    || last_preview_sent
                                        .is_none_or(|t| t.elapsed() >= min_interval);
                                let want_preview = preview_channel_ready && interval_elapsed;

                                let preview_limit = want_preview
                                    .then(|| opts.preview_ctrl.max_width.load(Ordering::Relaxed));
                                let transform = VideoTransform::from_flags(flags);
                                let recycle_rx = opts.preview_recycle_rx.as_ref();
                                let preview_tx = opts.preview_tx.as_ref();
                                let on_preview_pushed = opts.on_preview_pushed.as_ref();
                                let spare_cell = std::cell::Cell::new(local_spare_buf.take());

                                let res = decoder.decode_yuv_transformed_and_optional_preview(
                                    pts_us,
                                    is_keyframe,
                                    transform,
                                    &data,
                                    |width, height, yuv420p, _decode_time_ms| {
                                        decoded_count += 1;
                                        last_dims = (width, height);
                                        in_standby = false;
                                        if let Some(vcam_sink) = sink.as_mut()
                                            && let Err(e) =
                                                vcam_sink.write_frame(width, height, yuv420p)
                                        {
                                            warn!(
                                                error = %e,
                                                "Failed to write frame to virtual camera sink"
                                            );
                                        }
                                    },
                                    || preview_limit,
                                    || {
                                        recycle_rx
                                            .and_then(|rx| rx.try_recv().ok())
                                            .or_else(|| spare_cell.take())
                                            .unwrap_or_default()
                                    },
                                    |preview| {
                                        if let Some(tx) = preview_tx {
                                            match tx.try_send(preview) {
                                                Ok(()) => {
                                                    *last_preview_sent = Some(Instant::now());
                                                    if let Some(cb) = on_preview_pushed {
                                                        cb();
                                                    }
                                                }
                                                Err(TrySendError::Full(rejected)) => {
                                                    spare_cell.set(Some(rejected.rgba));
                                                }
                                                Err(TrySendError::Disconnected(_)) => {}
                                            }
                                        }
                                    },
                                );
                                *local_spare_buf = spare_cell.into_inner();
                                res
                            }
                        };

                        match decode_res {
                            Ok(()) => {
                                emit_keyframe_req(tracker.on_decode_success(
                                    pts_us,
                                    is_keyframe,
                                    decoded_count,
                                    now,
                                ));
                            }
                            Err(e) => {
                                warn!(error = %e, "Failed to decode VIDEO_FRAME; resetting decoder");
                                decoder.reset_after_error();
                                emit_keyframe_req(tracker.on_decode_error(now));
                            }
                        }
                    }
                    DecoderCommand::Flush => {
                        let legacy_tx = match &output_mode {
                            WorkerOutputMode::Legacy { frame_tx, .. } => frame_tx,
                            WorkerOutputMode::LowLatency { .. } => &None,
                        };
                        if let Err(e) = decoder.flush_with(|frame| {
                            last_dims = (frame.width, frame.height);
                            dispatch_decoded_frame(frame, &mut sink, legacy_tx);
                        }) {
                            warn!(error = %e, "Failed to flush video decoder");
                        }
                    }
                    DecoderCommand::EnterStandby => {
                        info!(
                            width = last_dims.0,
                            height = last_dims.1,
                            "Video decoder entering standby frame mode"
                        );
                        decoder.reset_for_reconnect();
                        tracker = KeyframeRecoveryTracker::default();
                        last_frame_seq = None;
                        in_standby = true;
                        write_standby_tick(
                            &mut sink,
                            last_dims,
                            &mut standby_buf,
                            &mut standby_buf_dims,
                        );
                    }
                    DecoderCommand::Stop => {
                        info!("Video decoder worker received stop signal");
                        break;
                    }
                }
            }

            // Cleanup sink
            if let Some(mut vcam_sink) = sink {
                let _ = vcam_sink.close();
            }

            info!("Dedicated video decoder thread (camcraft-vdec) terminated cleanly");
            Ok(())
        })
}

#[cfg(test)]
mod tests {
    use super::*;

    const SPS_PPS_64X64: &[u8] = &[
        0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xc0, 0x0a, 0xda, 0x10, 0x9b, 0x01, 0x10, 0x00, 0x00,
        0x03, 0x00, 0x10, 0x00, 0x00, 0x03, 0x03, 0x20, 0xf1, 0x22, 0x6a, 0x00, 0x00, 0x00, 0x01,
        0x68, 0xce, 0x0f, 0x2c, 0x80,
    ];
    const IDR_64X64_RED: &[u8] = &[
        0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x04, 0xbc, 0x46, 0x28, 0x00, 0x0a, 0x8b, 0xc7, 0x00,
        0x01, 0x28, 0xd8, 0xe0, 0x00, 0x2f, 0xad, 0x27, 0x27, 0x27, 0x5d, 0x75, 0xd7, 0x5d, 0x75,
        0xd7, 0x5d, 0x75, 0xd7, 0x80,
    ];
    const SPS_PPS_32X32: &[u8] = &[
        0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xc0, 0x0a, 0xda, 0x25, 0xb0, 0x11, 0x00, 0x00, 0x03,
        0x00, 0x01, 0x00, 0x00, 0x03, 0x00, 0x32, 0x0f, 0x12, 0x26, 0xa0, 0x00, 0x00, 0x00, 0x01,
        0x68, 0xce, 0x0f, 0x2c, 0x80,
    ];
    const IDR_32X32_BLUE: &[u8] = &[
        0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x04, 0xbc, 0x46, 0x28, 0x00, 0x0c, 0xc4, 0xc7, 0x00,
        0x01, 0x7d, 0x68, 0xe0, 0x00, 0x22, 0x43, 0x27, 0x5d, 0x78,
    ];

    #[test]
    fn test_h264_decoder_init() {
        let dec = H264Decoder::new(false);
        assert!(
            dec.is_ok(),
            "Failed to initialize H264Decoder: {:?}",
            dec.err()
        );
    }

    #[test]
    fn test_decode_real_h264_yuv420p_and_rgba_with_resolution_change() {
        let mut dec = H264Decoder::new(true).expect("H264Decoder should init");

        // Frame 1 & 2: 64x64 red
        dec.decode_config(0, SPS_PPS_64X64).unwrap();
        let frames_1 = dec.decode_frame(1_000, true, IDR_64X64_RED).unwrap();
        assert_eq!(frames_1.len(), 1);
        assert_eq!(frames_1[0].width, 64);
        assert_eq!(frames_1[0].height, 64);
        assert_eq!(frames_1[0].pts_us, 1_000);
        assert_eq!(frames_1[0].yuv420p.len(), 64 * 64 * 3 / 2);
        let rgba_1 = frames_1[0].rgba.as_ref().expect("RGBA should be enabled");
        assert_eq!(rgba_1.len(), 64 * 64 * 4);
        assert!(rgba_1[0] > 200 && rgba_1[1] < 30 && rgba_1[2] < 30 && rgba_1[3] == 255);
        assert_eq!(dec.converter.rgba_scaler_rebuilds, 1);

        let frames_2 = dec.decode_frame(2_000, true, IDR_64X64_RED).unwrap();
        assert_eq!(frames_2.len(), 1);
        assert_eq!(frames_2[0].width, 64);
        assert_eq!(frames_2[0].height, 64);
        // Scaler must NOT be rebuilt for subsequent frames of identical format/dimensions
        assert_eq!(dec.converter.rgba_scaler_rebuilds, 1);

        // Resolution switch: 32x32 blue
        dec.decode_config(3_000, SPS_PPS_32X32).unwrap();
        let frames_3 = dec.decode_frame(4_000, true, IDR_32X32_BLUE).unwrap();
        assert_eq!(frames_3.len(), 1);
        assert_eq!(frames_3[0].width, 32);
        assert_eq!(frames_3[0].height, 32);
        assert_eq!(frames_3[0].pts_us, 4_000);
        assert_eq!(frames_3[0].yuv420p.len(), 32 * 32 * 3 / 2);
        let rgba_3 = frames_3[0].rgba.as_ref().expect("RGBA should be enabled");
        assert_eq!(rgba_3.len(), 32 * 32 * 4);
        assert!(rgba_3[0] < 30 && rgba_3[1] < 30 && rgba_3[2] > 200 && rgba_3[3] == 255);
        assert_eq!(dec.converter.rgba_scaler_rebuilds, 2);
    }

    #[test]
    fn test_decode_empty_payload_does_not_panic() {
        let mut dec = H264Decoder::new(true).expect("H264Decoder should init");
        assert!(dec.decode_config(0, &[]).is_ok());
        let frames = dec.decode_frame(0, false, &[]).unwrap();
        assert!(frames.is_empty());
    }

    #[test]
    fn test_decoder_sender_smart_drop() {
        let (sender, rx) = DecoderSender::new_bounded();
        let payload = Bytes::from_static(b"\x00\x00\x00\x01\x65IDR");

        // Fill queue to capacity (3)
        assert!(sender.push_frame(100, true, payload.clone()).unwrap());
        assert!(sender.push_frame(200, false, payload.clone()).unwrap());
        assert!(sender.push_frame(300, false, payload.clone()).unwrap());

        // Queue is now full (3 items). A non-keyframe should be dropped (returns Ok(false))
        let dropped = sender.push_frame(400, false, payload.clone()).unwrap();
        assert!(
            !dropped,
            "Non-keyframe should be dropped when queue is full"
        );

        // A keyframe should drop oldest and be accepted
        let accepted = sender.push_frame(500, true, payload.clone()).unwrap();
        assert!(accepted, "Keyframe should drop oldest and be queued");

        // Verify remaining count in queue
        assert_eq!(rx.len(), 3);
    }

    #[test]
    fn test_decoder_sender_preserves_config_under_backpressure() {
        let (sender, rx) = DecoderSender::new_bounded();
        let cfg = Bytes::from_static(SPS_PPS_64X64);
        let p_frame = Bytes::from_static(b"\x00\x00\x00\x01\x41P");
        let idr_frame = Bytes::from_static(IDR_64X64_RED);

        sender.push_config(10, cfg).unwrap();
        assert!(sender.push_frame(20, false, p_frame.clone()).unwrap());
        assert!(sender.push_frame(30, false, p_frame).unwrap());

        // Queue is [Config, Frame(20), Frame(30)]. Push IDR keyframe under backpressure.
        assert!(sender.push_frame(40, true, idr_frame).unwrap());

        assert_eq!(rx.len(), 3);
        let first = rx.recv().unwrap();
        let second = rx.recv().unwrap();
        let third = rx.recv().unwrap();

        assert!(matches!(first, DecoderCommand::Config { pts_us: 10, .. }));
        assert!(matches!(second, DecoderCommand::Frame { pts_us: 30, .. }));
        assert!(matches!(
            third,
            DecoderCommand::Frame {
                pts_us: 40,
                is_keyframe: true,
                ..
            }
        ));
    }

    #[test]
    fn test_decoder_sender_push_config_non_blocking_when_full() {
        let (sender, rx) = DecoderSender::new_bounded();
        let frame = Bytes::from_static(b"\x00\x00\x00\x01\x41P");
        let cfg = Bytes::from_static(SPS_PPS_64X64);

        assert!(sender.push_frame(1, false, frame.clone()).unwrap());
        assert!(sender.push_frame(2, false, frame.clone()).unwrap());
        assert!(sender.push_frame(3, false, frame).unwrap());

        assert!(sender.push_config(4, cfg).is_ok());
        assert_eq!(rx.len(), 3);

        let items: Vec<_> = rx.try_iter().collect();
        assert!(matches!(items[0], DecoderCommand::Frame { pts_us: 2, .. }));
        assert!(matches!(items[1], DecoderCommand::Frame { pts_us: 3, .. }));
        assert!(matches!(items[2], DecoderCommand::Config { pts_us: 4, .. }));
    }

    #[test]
    fn keyframe_tracker_normal_stream_requests_nothing() {
        let mut tracker = KeyframeRecoveryTracker::new(KEYFRAME_REQUEST_COOLDOWN);
        let t0 = Instant::now();

        tracker.on_config_received();
        assert_eq!(
            tracker.on_before_decode(0, true, t0),
            PreDecodeAction::Decode
        );
        assert_eq!(tracker.on_decode_success(0, true, 1, t0), None);

        for i in 1..=5 {
            let pts = i * 16_666;
            let now = t0 + Duration::from_millis(i * 16);
            assert_eq!(
                tracker.on_before_decode(pts, false, now),
                PreDecodeAction::Decode
            );
            assert_eq!(tracker.on_decode_success(pts, false, 1, now), None);
        }
    }

    #[test]
    fn keyframe_tracker_missing_initial_idr_skips_p_frames_with_cooldown() {
        let mut tracker = KeyframeRecoveryTracker::new(Duration::from_millis(500));
        let t0 = Instant::now();

        // P-frame before any IDR is skipped and requests MissingInitialKeyframe
        assert_eq!(
            tracker.on_before_decode(16_666, false, t0),
            PreDecodeAction::SkipUntilKeyframe {
                request: Some(KeyframeRequestReason::MissingInitialKeyframe),
            }
        );

        // Next P-frame within 500ms cooldown is skipped without re-requesting
        assert_eq!(
            tracker.on_before_decode(33_333, false, t0 + Duration::from_millis(200)),
            PreDecodeAction::SkipUntilKeyframe { request: None }
        );

        // After 500ms cooldown expires, P-frame still skipped and emits another request
        assert_eq!(
            tracker.on_before_decode(50_000, false, t0 + Duration::from_millis(550)),
            PreDecodeAction::SkipUntilKeyframe {
                request: Some(KeyframeRequestReason::MissingInitialKeyframe),
            }
        );

        // IDR arrives and heals stream
        let t1 = t0 + Duration::from_millis(600);
        assert_eq!(
            tracker.on_before_decode(66_666, true, t1),
            PreDecodeAction::Decode
        );
        assert_eq!(tracker.on_decode_success(66_666, true, 1, t1), None);
        assert_eq!(
            tracker.on_before_decode(83_333, false, t1 + Duration::from_millis(16)),
            PreDecodeAction::Decode
        );
    }

    #[test]
    fn keyframe_tracker_frame_drop_and_decode_error_skip_until_idr() {
        let mut tracker = KeyframeRecoveryTracker::new(Duration::from_millis(500));
        let t0 = Instant::now();

        assert_eq!(
            tracker.on_before_decode(0, true, t0),
            PreDecodeAction::Decode
        );
        assert_eq!(tracker.on_decode_success(0, true, 1, t0), None);

        // Frame dropped triggers immediate request and enters awaiting_keyframe
        let t1 = t0 + Duration::from_millis(30);
        assert_eq!(
            tracker.on_frame_dropped(t1),
            Some(KeyframeRequestReason::FrameDropped)
        );
        assert_eq!(
            tracker.on_before_decode(33_333, false, t1 + Duration::from_millis(10)),
            PreDecodeAction::SkipUntilKeyframe { request: None }
        );

        // IDR heals, then decode error occurs
        let t2 = t0 + Duration::from_millis(600);
        assert_eq!(
            tracker.on_before_decode(50_000, true, t2),
            PreDecodeAction::Decode
        );
        assert_eq!(tracker.on_decode_success(50_000, true, 1, t2), None);

        let t3 = t0 + Duration::from_millis(700);
        assert_eq!(
            tracker.on_decode_error(t3),
            Some(KeyframeRequestReason::DecodeError)
        );
        assert_eq!(
            tracker.on_before_decode(66_666, false, t3 + Duration::from_millis(16)),
            PreDecodeAction::SkipUntilKeyframe { request: None }
        );

        // Cooldown elapsed while still awaiting IDR -> skips & emits DecodeError
        assert_eq!(
            tracker.on_before_decode(83_333, false, t3 + Duration::from_millis(500)),
            PreDecodeAction::SkipUntilKeyframe {
                request: Some(KeyframeRequestReason::DecodeError),
            }
        );

        // Valid IDR clears error state
        let t4 = t3 + Duration::from_millis(550);
        assert_eq!(
            tracker.on_before_decode(100_000, true, t4),
            PreDecodeAction::Decode
        );
        assert_eq!(tracker.on_decode_success(100_000, true, 1, t4), None);
        assert_eq!(
            tracker.on_before_decode(116_666, false, t4 + Duration::from_millis(16)),
            PreDecodeAction::Decode
        );
    }

    #[test]
    fn keyframe_tracker_detects_pts_discontinuity_and_empty_p_frames() {
        let mut tracker = KeyframeRecoveryTracker::new(Duration::from_millis(500));
        let t0 = Instant::now();

        assert_eq!(
            tracker.on_before_decode(100_000, true, t0),
            PreDecodeAction::Decode
        );
        assert_eq!(tracker.on_decode_success(100_000, true, 1, t0), None);

        // Forward gap > MAX_INTER_FRAME_GAP_US (500_000 us) on P-frame
        let t1 = t0 + Duration::from_millis(600);
        assert_eq!(
            tracker.on_before_decode(700_000, false, t1),
            PreDecodeAction::DecodeAndRequest(KeyframeRequestReason::PtsDiscontinuity)
        );
        assert_eq!(tracker.on_decode_success(700_000, false, 1, t1), None);

        // Backward jump on P-frame after cooldown
        let t2 = t1 + Duration::from_millis(600);
        assert_eq!(
            tracker.on_before_decode(650_000, false, t2),
            PreDecodeAction::DecodeAndRequest(KeyframeRequestReason::PtsDiscontinuity)
        );
        assert_eq!(tracker.on_decode_success(650_000, false, 1, t2), None);

        // Consecutive empty P-frames (>= MAX_EMPTY_P_FRAMES = 3)
        let t3 = t2 + Duration::from_millis(600);
        for offset in 0..(MAX_EMPTY_P_FRAMES - 1) {
            let pts = 666_666 + u64::from(offset) * 16_666;
            let now = t3 + Duration::from_millis(u64::from(offset) * 16);
            assert_eq!(
                tracker.on_before_decode(pts, false, now),
                PreDecodeAction::Decode
            );
            assert_eq!(tracker.on_decode_success(pts, false, 0, now), None);
        }
        let trigger_now = t3 + Duration::from_millis(60);
        assert_eq!(
            tracker.on_before_decode(720_000, false, trigger_now),
            PreDecodeAction::Decode
        );
        assert_eq!(
            tracker.on_decode_success(720_000, false, 0, trigger_now),
            Some(KeyframeRequestReason::ConsecutiveEmptyPFrames)
        );
    }

    #[test]
    fn decoder_worker_recovers_from_corrupted_h264_on_valid_config_and_idr() {
        let (sender, rx) = DecoderSender::new_bounded();
        let (frame_tx, frame_rx) = crossbeam_channel::bounded::<DecodedFrame>(4);
        let (kf_tx, kf_rx) = crossbeam_channel::bounded::<KeyframeRequestReason>(8);
        let running = Arc::new(AtomicBool::new(true));

        let handle = spawn_decoder_worker_with_recovery(
            rx,
            None,
            Some(frame_tx),
            true,
            running.clone(),
            Some(kf_tx),
        )
        .expect("spawn decoder worker");

        // 1. Send corrupted keyframe bytes -> must emit DecodeError request
        let corrupt = Bytes::from_static(&[0x00, 0x00, 0x01, 0x65, 0xFF, 0xFF, 0xAA, 0x55, 0x00]);
        assert!(sender.push_frame(1_000, true, corrupt).unwrap());

        let req = kf_rx
            .recv_timeout(Duration::from_secs(2))
            .expect("expected DecodeError keyframe request");
        assert_eq!(req, KeyframeRequestReason::DecodeError);

        // 2. Subsequent P-frame while awaiting recovery should be skipped without producing output
        assert!(
            sender
                .push_frame(
                    17_666,
                    false,
                    Bytes::from_static(&[0x00, 0x00, 0x01, 0x41, 0x01])
                )
                .unwrap()
        );
        assert!(frame_rx.try_recv().is_err());

        // 3. Valid SPS/PPS + IDR frame heals decoding cleanly
        sender
            .push_config(33_000, Bytes::from_static(SPS_PPS_64X64))
            .unwrap();
        assert!(
            sender
                .push_frame(33_333, true, Bytes::from_static(IDR_64X64_RED))
                .unwrap()
        );

        let healed = frame_rx
            .recv_timeout(Duration::from_secs(2))
            .expect("decoder should heal and output 64x64 frame");
        assert_eq!(healed.width, 64);
        assert_eq!(healed.height, 64);

        sender.stop();
        running.store(false, Ordering::Relaxed);
        assert!(handle.join().expect("worker join").is_ok());
    }

    #[test]
    fn compute_preview_dimensions_scales_proportionally_without_upscaling() {
        assert_eq!(compute_preview_dimensions(1920, 1080, 960), (960, 540));
        assert_eq!(compute_preview_dimensions(3840, 2160, 960), (960, 540));
        assert_eq!(compute_preview_dimensions(640, 480, 960), (640, 480));
        assert_eq!(compute_preview_dimensions(1080, 1920, 960), (960, 1706));
        let (odd_w, odd_h) = compute_preview_dimensions(1001, 751, 501);
        assert_eq!(odd_w % 2, 0);
        assert_eq!(odd_h % 2, 0);
        assert_eq!(compute_preview_dimensions(0, 1080, 960), (0, 1080));
    }

    #[test]
    fn decoder_sender_live_capacity_queues_six_frames_before_backpressure() {
        let (sender, rx) = DecoderSender::new_with_capacity(LIVE_DECODER_QUEUE_CAPACITY);
        let payload = Bytes::from_static(b"\x00\x00\x00\x01\x41P");

        for idx in 0..LIVE_DECODER_QUEUE_CAPACITY {
            assert!(
                sender
                    .push_frame((idx as u64) * 16_666, idx == 0, payload.clone())
                    .unwrap()
            );
        }
        assert_eq!(rx.len(), LIVE_DECODER_QUEUE_CAPACITY);
        // 7th P-frame drops under full capacity
        assert!(!sender.push_frame(100_000, false, payload).unwrap());
    }

    #[test]
    fn h264_decoder_avoids_yuv_swscale_for_planar_420_streams() {
        let mut dec = H264Decoder::new(false).expect("H264Decoder should init");
        dec.decode_config(0, SPS_PPS_64X64).unwrap();
        let frames = dec.decode_frame(1_000, true, IDR_64X64_RED).unwrap();
        assert_eq!(frames.len(), 1);
        assert!(
            dec.converter.yuv_scaler.is_none(),
            "YUV420P/YUVJ420P should pack directly without libswscale YUV conversion"
        );
    }

    #[test]
    fn video_transform_from_flags_and_identity() {
        assert!(VideoTransform::default().is_identity());
        assert!(VideoTransform::from_flags(FrameFlags::NONE).is_identity());
        assert!(VideoTransform::from_flags(FrameFlags::KEYFRAME).is_identity());

        let t_mirror = VideoTransform::from_flags(FrameFlags::KEYFRAME | FrameFlags::MIRROR_H);
        assert_eq!(
            t_mirror,
            VideoTransform {
                mirror_horizontal: true,
                rotation_deg: 0,
            }
        );
        assert!(!t_mirror.is_identity());

        let t_rot90 = VideoTransform::from_flags(FrameFlags::ROTATE_90);
        assert_eq!(
            t_rot90,
            VideoTransform {
                mirror_horizontal: false,
                rotation_deg: 90,
            }
        );
        assert!(!t_rot90.is_identity());

        let t_both = VideoTransform::from_flags(FrameFlags::MIRROR_H | FrameFlags::ROTATE_270);
        assert_eq!(
            t_both,
            VideoTransform {
                mirror_horizontal: true,
                rotation_deg: 270,
            }
        );
        assert!(!t_both.is_identity());
    }

    #[test]
    fn h264_decoder_with_options_clamps_threads_and_decodes_fast() {
        for (thread_count, fast_decode) in [(0, true), (2, true), (16, false)] {
            let mut dec = H264Decoder::with_options(DecoderOptions {
                enable_rgba: false,
                thread_count,
                fast_decode,
            })
            .expect("H264Decoder::with_options should init");
            dec.decode_config(0, SPS_PPS_64X64).unwrap();
            let frames = dec.decode_frame(1_000, true, IDR_64X64_RED).unwrap();
            assert_eq!(frames.len(), 1);
            assert_eq!((frames[0].width, frames[0].height), (64, 64));
        }
    }

    #[test]
    fn copy_transformed_yuv420p_exact_pixels_for_all_transforms() {
        let mut frame = VideoFrame::new(Pixel::YUV420P, 8, 4);

        let y_stride = frame.stride(0);
        for row in 0..4 {
            let dst_row = &mut frame.data_mut(0)[row * y_stride..row * y_stride + 8];
            for (col, px) in dst_row.iter_mut().enumerate() {
                *px = (row * 8 + col + 1) as u8;
            }
        }
        let u_stride = frame.stride(1);
        for row in 0..2 {
            let dst_row = &mut frame.data_mut(1)[row * u_stride..row * u_stride + 4];
            for (col, px) in dst_row.iter_mut().enumerate() {
                *px = (101 + row * 4 + col) as u8;
            }
        }
        let v_stride = frame.stride(2);
        for row in 0..2 {
            let dst_row = &mut frame.data_mut(2)[row * v_stride..row * v_stride + 4];
            for (col, px) in dst_row.iter_mut().enumerate() {
                *px = (201 + row * 4 + col) as u8;
            }
        }

        let mut dst = Vec::new();

        // 1. Identity
        let dims_id = copy_transformed_yuv420p(&frame, 8, 4, VideoTransform::default(), &mut dst);
        assert_eq!(dims_id, (8, 4));
        let expected_id: Vec<u8> = (1..=32).chain(101..=108).chain(201..=208).collect();
        assert_eq!(dst, expected_id);

        // 2. Mirror horizontal
        let dims_mh = copy_transformed_yuv420p(
            &frame,
            8,
            4,
            VideoTransform {
                mirror_horizontal: true,
                rotation_deg: 0,
            },
            &mut dst,
        );
        assert_eq!(dims_mh, (8, 4));
        let expected_mh: Vec<u8> = [
            8, 7, 6, 5, 4, 3, 2, 1, 16, 15, 14, 13, 12, 11, 10, 9, 24, 23, 22, 21, 20, 19, 18, 17,
            32, 31, 30, 29, 28, 27, 26, 25, // Y (8x4)
            104, 103, 102, 101, 108, 107, 106, 105, // U (4x2)
            204, 203, 202, 201, 208, 207, 206, 205, // V (4x2)
        ]
        .to_vec();
        assert_eq!(dst, expected_mh);

        // 3. Rotate 90 clockwise
        let dims_r90 = copy_transformed_yuv420p(
            &frame,
            8,
            4,
            VideoTransform {
                mirror_horizontal: false,
                rotation_deg: 90,
            },
            &mut dst,
        );
        assert_eq!(dims_r90, (4, 8));
        let expected_r90: Vec<u8> = [
            25, 17, 9, 1, 26, 18, 10, 2, 27, 19, 11, 3, 28, 20, 12, 4, 29, 21, 13, 5, 30, 22, 14,
            6, 31, 23, 15, 7, 32, 24, 16, 8, // Y (4x8)
            105, 101, 106, 102, 107, 103, 108, 104, // U (2x4)
            205, 201, 206, 202, 207, 203, 208, 204, // V (2x4)
        ]
        .to_vec();
        assert_eq!(dst, expected_r90);

        // 4. Rotate 180
        let dims_r180 = copy_transformed_yuv420p(
            &frame,
            8,
            4,
            VideoTransform {
                mirror_horizontal: false,
                rotation_deg: 180,
            },
            &mut dst,
        );
        assert_eq!(dims_r180, (8, 4));
        let expected_r180: Vec<u8> = (1..=32)
            .rev()
            .chain((101..=108).rev())
            .chain((201..=208).rev())
            .collect();
        assert_eq!(dst, expected_r180);

        // 5. Rotate 270 clockwise
        let dims_r270 = copy_transformed_yuv420p(
            &frame,
            8,
            4,
            VideoTransform {
                mirror_horizontal: false,
                rotation_deg: 270,
            },
            &mut dst,
        );
        assert_eq!(dims_r270, (4, 8));
        let expected_r270: Vec<u8> = [
            8, 16, 24, 32, 7, 15, 23, 31, 6, 14, 22, 30, 5, 13, 21, 29, 4, 12, 20, 28, 3, 11, 19,
            27, 2, 10, 18, 26, 1, 9, 17, 25, // Y (4x8)
            104, 108, 103, 107, 102, 106, 101, 105, // U (2x4)
            204, 208, 203, 207, 202, 206, 201, 205, // V (2x4)
        ]
        .to_vec();
        assert_eq!(dst, expected_r270);

        // 6. Mirror horizontal + Rotate 90 clockwise (mirror applied first)
        let dims_mh_r90 = copy_transformed_yuv420p(
            &frame,
            8,
            4,
            VideoTransform {
                mirror_horizontal: true,
                rotation_deg: 90,
            },
            &mut dst,
        );
        assert_eq!(dims_mh_r90, (4, 8));
        let expected_mh_r90: Vec<u8> = [
            32, 24, 16, 8, 31, 23, 15, 7, 30, 22, 14, 6, 29, 21, 13, 5, 28, 20, 12, 4, 27, 19, 11,
            3, 26, 18, 10, 2, 25, 17, 9, 1, // Y (4x8)
            108, 104, 107, 103, 106, 102, 105, 101, // U (2x4)
            208, 204, 207, 203, 206, 202, 205, 201, // V (2x4)
        ]
        .to_vec();
        assert_eq!(dst, expected_mh_r90);

        // 7. Mirror horizontal + Rotate 180 (vertical flip)
        let dims_mh_r180 = copy_transformed_yuv420p(
            &frame,
            8,
            4,
            VideoTransform {
                mirror_horizontal: true,
                rotation_deg: 180,
            },
            &mut dst,
        );
        assert_eq!(dims_mh_r180, (8, 4));
        let expected_mh_r180: Vec<u8> = (25..=32)
            .chain(17..=24)
            .chain(9..=16)
            .chain(1..=8)
            .chain(105..=108)
            .chain(101..=104)
            .chain(205..=208)
            .chain(201..=204)
            .collect();
        assert_eq!(dst, expected_mh_r180);
    }
}
