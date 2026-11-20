//! Hardware-accelerated and software H.264 video decoding with color conversion.
//!
//! Follows `docs/DESIGN.md` §3.1 and `docs/PLAN.md` (Stage 4):
//! - Decodes H.264 Annex-B packets (`VIDEO_CONFIG` SPS/PPS and `VIDEO_FRAME` NAL units).
//! - Configures low-latency decoding (`AV_CODEC_FLAG_LOW_DELAY`).
//! - Scales/converts decoded frames to contiguous `YUV420P` (for V4L2) and optionally `RGBA` (for GUI preview).
//! - Runs in a dedicated worker thread (`camcraft-vdec`) with a bounded queue (`crossbeam_channel::bounded(3)`).
//! - Drops stale non-keyframes on backpressure to prevent latency accumulation.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::JoinHandle;

use bytes::Bytes;
use crossbeam_channel::{Receiver, Sender, TrySendError};
use ffmpeg_next::codec::flag::Flags as CodecFlags;
use ffmpeg_next::format::Pixel;
use ffmpeg_next::software::scaling::{Context as ScaleContext, flag::Flags as ScaleFlags};
use ffmpeg_next::util::error::EAGAIN;
use ffmpeg_next::util::frame::video::Video as VideoFrame;
use thiserror::Error;
use tracing::{debug, info, trace, warn};

use crate::vcam::VirtualCameraSink;

/// Maximum number of unrendered frames queued before dropping non-keyframes.
pub const DECODER_QUEUE_CAPACITY: usize = 3;

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

/// Commands dispatched to the dedicated video decoder worker thread.
#[derive(Debug)]
pub enum DecoderCommand {
    /// Codec configuration containing SPS and PPS Annex-B NAL units.
    Config { pts_us: u64, data: Bytes },
    /// Video frame containing Annex-B NAL unit(s).
    Frame {
        pts_us: u64,
        is_keyframe: bool,
        data: Bytes,
    },
    /// Flush decoder buffers.
    Flush,
    /// Terminate decoder worker.
    Stop,
}

struct CachedScaler {
    ctx: ScaleContext,
    dst_frame: VideoFrame,
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

    fn convert(
        &mut self,
        raw: &VideoFrame,
        pts_us: u64,
        is_keyframe: bool,
        decode_time_ms: f32,
    ) -> Result<DecodedFrameRef<'_>, DecoderError> {
        let width = raw.width();
        let height = raw.height();
        let format = raw.format();

        if width == 0 || height == 0 {
            return Err(DecoderError::ContextInit(
                "Invalid zero frame dimensions from decoder".to_string(),
            ));
        }

        // 1. Convert/pack to contiguous YUV420P buffer for V4L2 without per-frame allocation
        if format == Pixel::YUV420P {
            copy_contiguous_yuv420p(raw, width, height, &mut self.yuv_scratch);
        } else {
            let (scaler, _rebuilt) =
                get_or_init_scaler(&mut self.yuv_scaler, format, width, height, Pixel::YUV420P)?;
            scaler
                .ctx
                .run(raw, &mut scaler.dst_frame)
                .map_err(DecoderError::Ffmpeg)?;
            copy_contiguous_yuv420p(&scaler.dst_frame, width, height, &mut self.yuv_scratch);
        }

        // 2. Convert to RGBA if GUI preview is enabled
        let rgba = if self.enable_rgba {
            let (scaler, rebuilt) =
                get_or_init_scaler(&mut self.rgba_scaler, format, width, height, Pixel::RGBA)?;
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
            copy_contiguous_rgba(&scaler.dst_frame, width, height, &mut self.rgba_scratch);
            Some(self.rgba_scratch.as_slice())
        } else {
            None
        };

        Ok(DecodedFrameRef {
            width,
            height,
            pts_us,
            is_keyframe,
            decode_time_ms,
            yuv420p: self.yuv_scratch.as_slice(),
            rgba,
        })
    }
}

fn get_or_init_scaler(
    slot: &mut Option<CachedScaler>,
    src_format: Pixel,
    width: u32,
    height: u32,
    dst_format: Pixel,
) -> Result<(&mut CachedScaler, bool), DecoderError> {
    let needs_rebuild = match slot.as_ref() {
        Some(cached) => {
            let input = cached.ctx.input();
            input.format != src_format || input.width != width || input.height != height
        }
        None => true,
    };

    if needs_rebuild {
        debug!(
            ?src_format,
            ?dst_format,
            width,
            height,
            "Configuring libswscale scaler"
        );
        let ctx = ScaleContext::get(
            src_format,
            width,
            height,
            dst_format,
            width,
            height,
            ScaleFlags::FAST_BILINEAR,
        )
        .map_err(DecoderError::Ffmpeg)?;

        *slot = Some(CachedScaler {
            ctx,
            dst_frame: VideoFrame::empty(),
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
    /// Creates and configures a new low-delay H.264 video decoder.
    pub fn new(enable_rgba: bool) -> Result<Self, DecoderError> {
        ffmpeg_next::init().map_err(DecoderError::Ffmpeg)?;

        let codec = ffmpeg_next::codec::decoder::find(ffmpeg_next::codec::Id::H264)
            .ok_or(DecoderError::CodecNotFound)?;

        let mut context = ffmpeg_next::codec::Context::new_with_codec(codec);
        // Set low-delay flag to disable frame reordering buffering for real-time streaming
        context.set_flags(CodecFlags::LOW_DELAY);

        // Configure slice threading for parallel slice decoding without introducing frame latency
        context.set_threading(ffmpeg_next::codec::threading::Config {
            kind: ffmpeg_next::codec::threading::Type::Slice,
            count: 0, // Auto-detect CPU core count
        });

        let decoder = context.decoder().video().map_err(DecoderError::Ffmpeg)?;

        info!("H.264 low-delay video decoder initialized successfully");

        Ok(Self {
            decoder,
            converter: FrameConverter::new(enable_rgba),
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
            .map_err(DecoderError::Ffmpeg)?;

        loop {
            match self.decoder.receive_frame(&mut self.raw_frame) {
                Ok(()) => {
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
}

/// Copies plane data line-by-line into `dst` to ensure contiguous YUV420P memory layout
/// without alignment padding, reusing `dst`'s allocation across frames.
pub fn copy_contiguous_yuv420p(frame: &VideoFrame, width: u32, height: u32, dst: &mut Vec<u8>) {
    let w = width as usize;
    let h = height as usize;
    let half_w = w / 2;
    let half_h = h / 2;
    let total_bytes = w * h + 2 * (half_w * half_h);

    dst.clear();
    dst.reserve(total_bytes);

    // Plane 0: Y
    let y_data = frame.data(0);
    let y_stride = frame.stride(0);
    if y_stride == w {
        dst.extend_from_slice(&y_data[..w * h]);
    } else {
        for row in 0..h {
            let start = row * y_stride;
            dst.extend_from_slice(&y_data[start..start + w]);
        }
    }

    // Plane 1: U (Cb)
    let u_data = frame.data(1);
    let u_stride = frame.stride(1);
    if u_stride == half_w {
        dst.extend_from_slice(&u_data[..half_w * half_h]);
    } else {
        for row in 0..half_h {
            let start = row * u_stride;
            dst.extend_from_slice(&u_data[start..start + half_w]);
        }
    }

    // Plane 2: V (Cr)
    let v_data = frame.data(2);
    let v_stride = frame.stride(2);
    if v_stride == half_w {
        dst.extend_from_slice(&v_data[..half_w * half_h]);
    } else {
        for row in 0..half_h {
            let start = row * v_stride;
            dst.extend_from_slice(&v_data[start..start + half_w]);
        }
    }
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

    dst.clear();
    dst.reserve(total_bytes);

    let data = frame.data(0);
    let stride = frame.stride(0);
    if stride == bytes_per_line {
        dst.extend_from_slice(&data[..total_bytes]);
    } else {
        for row in 0..h {
            let start = row * stride;
            dst.extend_from_slice(&data[start..start + bytes_per_line]);
        }
    }
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
}

impl DecoderSender {
    /// Creates a sender/receiver pair with a bounded queue of capacity [`DECODER_QUEUE_CAPACITY`].
    #[must_use]
    pub fn new_bounded() -> (Self, Receiver<DecoderCommand>) {
        let (tx, rx) = crossbeam_channel::bounded(DECODER_QUEUE_CAPACITY);
        let sender = Self {
            cmd_tx: tx,
            cmd_rx_drain: rx.clone(),
        };
        (sender, rx)
    }

    /// Drains queued commands up to capacity, drops the oldest `Frame` (or an older duplicate
    /// `Config` if no `Frame` is present) while preserving relative order of `Config`/`Flush`/`Stop`,
    /// and re-inserts the remaining commands.
    fn drop_one_stale_frame(&self) -> bool {
        let mut drained: [Option<DecoderCommand>; DECODER_QUEUE_CAPACITY] =
            [const { None }; DECODER_QUEUE_CAPACITY];
        let mut count = 0;

        while count < DECODER_QUEUE_CAPACITY {
            match self.cmd_rx_drain.try_recv() {
                Ok(cmd) => {
                    drained[count] = Some(cmd);
                    count += 1;
                }
                Err(_) => break,
            }
        }

        let mut freed = false;
        for slot in &mut drained[..count] {
            if matches!(slot, Some(DecoderCommand::Frame { .. })) {
                *slot = None;
                freed = true;
                break;
            }
        }

        // If no frames are queued, coalesce any superseded older Config before the latest Config.
        if !freed {
            let last_config_idx = drained[..count]
                .iter()
                .rposition(|slot| matches!(slot, Some(DecoderCommand::Config { .. })));
            if let Some(last_idx) = last_config_idx {
                for (idx, slot) in drained[..count].iter_mut().enumerate() {
                    if idx < last_idx && matches!(slot, Some(DecoderCommand::Config { .. })) {
                        *slot = None;
                        freed = true;
                    }
                }
            }
        }

        for slot in &mut drained[..count] {
            if let Some(cmd) = slot.take() {
                let _ = self.cmd_tx.try_send(cmd);
            }
        }

        freed
    }

    /// Fallback used by `push_config` / `stop` when no frames are queued: drops the oldest
    /// queued `Config` while preserving relative order of remaining commands.
    fn drop_oldest_config(&self) -> bool {
        let mut drained: [Option<DecoderCommand>; DECODER_QUEUE_CAPACITY] =
            [const { None }; DECODER_QUEUE_CAPACITY];
        let mut count = 0;

        while count < DECODER_QUEUE_CAPACITY {
            match self.cmd_rx_drain.try_recv() {
                Ok(cmd) => {
                    drained[count] = Some(cmd);
                    count += 1;
                }
                Err(_) => break,
            }
        }

        let mut freed = false;
        for slot in &mut drained[..count] {
            if matches!(slot, Some(DecoderCommand::Config { .. })) {
                *slot = None;
                freed = true;
                break;
            }
        }

        for slot in &mut drained[..count] {
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
        let cmd = DecoderCommand::Frame {
            pts_us,
            is_keyframe,
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

/// Spawns the dedicated background worker thread (`camcraft-vdec`) for video decoding.
pub fn spawn_decoder_worker(
    cmd_rx: Receiver<DecoderCommand>,
    mut sink: Option<Box<dyn VirtualCameraSink>>,
    frame_tx: Option<Sender<DecodedFrame>>,
    enable_rgba: bool,
    running: Arc<AtomicBool>,
) -> std::io::Result<JoinHandle<Result<(), DecoderError>>> {
    std::thread::Builder::new()
        .name("camcraft-vdec".to_string())
        .spawn(move || {
            info!("Dedicated video decoder thread started (camcraft-vdec)");
            let mut decoder = H264Decoder::new(enable_rgba)?;

            while running.load(Ordering::Relaxed) {
                match cmd_rx.recv() {
                    Ok(DecoderCommand::Config { pts_us, data }) => {
                        if let Err(e) = decoder.decode_config(pts_us, &data) {
                            warn!(error = %e, "Failed to decode VIDEO_CONFIG");
                        }
                    }
                    Ok(DecoderCommand::Frame {
                        pts_us,
                        is_keyframe,
                        data,
                    }) => {
                        if let Err(e) =
                            decoder.decode_frame_with(pts_us, is_keyframe, &data, |frame| {
                                // 1. Write directly from borrowed YUV420P slice without extra heap copy
                                if let Some(ref mut vcam_sink) = sink
                                    && let Err(e) = vcam_sink.write_frame(
                                        frame.width,
                                        frame.height,
                                        frame.yuv420p,
                                    )
                                {
                                    warn!(error = %e, "Failed to write frame to virtual camera sink");
                                }

                                // 2. Forward owned frame to GUI preview only when preview channel is attached
                                if let Some(ref tx) = frame_tx {
                                    let _ = tx.try_send(frame.to_owned_frame());
                                }
                            })
                        {
                            warn!(error = %e, "Failed to decode VIDEO_FRAME");
                        }
                    }
                    Ok(DecoderCommand::Flush) => {
                        if let Err(e) = decoder.flush_with(|frame| {
                            if let Some(ref mut vcam_sink) = sink {
                                let _ = vcam_sink.write_frame(
                                    frame.width,
                                    frame.height,
                                    frame.yuv420p,
                                );
                            }
                            if let Some(ref tx) = frame_tx {
                                let _ = tx.try_send(frame.to_owned_frame());
                            }
                        }) {
                            warn!(error = %e, "Failed to flush video decoder");
                        }
                    }
                    Ok(DecoderCommand::Stop) | Err(_) => {
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
}
