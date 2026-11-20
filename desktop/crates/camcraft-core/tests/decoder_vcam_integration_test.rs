use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::time::Duration;

use bytes::Bytes;
use camcraft_core::decoder::{
    DecodedFrame, DecoderOptions, DecoderSender, KeyframeRequestReason,
    LIVE_DECODER_QUEUE_CAPACITY, LowLatencyWorkerOptions, PreviewFrame, PreviewRuntimeControl,
    spawn_decoder_worker, spawn_decoder_worker_low_latency, spawn_decoder_worker_optimized,
};
use camcraft_core::vcam::{VcamError, VirtualCameraSink, generate_standby_frame};
use camcraft_protocol::FrameFlags;

const SPS_PPS_64X64: &[u8] = &[
    0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xc0, 0x0a, 0xda, 0x10, 0x9b, 0x01, 0x10, 0x00, 0x00, 0x03,
    0x00, 0x10, 0x00, 0x00, 0x03, 0x03, 0x20, 0xf1, 0x22, 0x6a, 0x00, 0x00, 0x00, 0x01, 0x68, 0xce,
    0x0f, 0x2c, 0x80,
];
const IDR_64X64_RED: &[u8] = &[
    0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x04, 0xbc, 0x46, 0x28, 0x00, 0x0a, 0x8b, 0xc7, 0x00, 0x01,
    0x28, 0xd8, 0xe0, 0x00, 0x2f, 0xad, 0x27, 0x27, 0x27, 0x5d, 0x75, 0xd7, 0x5d, 0x75, 0xd7, 0x5d,
    0x75, 0xd7, 0x80,
];
const SPS_PPS_32X32: &[u8] = &[
    0x00, 0x00, 0x00, 0x01, 0x67, 0x42, 0xc0, 0x0a, 0xda, 0x25, 0xb0, 0x11, 0x00, 0x00, 0x03, 0x00,
    0x01, 0x00, 0x00, 0x03, 0x00, 0x32, 0x0f, 0x12, 0x26, 0xa0, 0x00, 0x00, 0x00, 0x01, 0x68, 0xce,
    0x0f, 0x2c, 0x80,
];
const IDR_32X32_BLUE: &[u8] = &[
    0x00, 0x00, 0x01, 0x65, 0x88, 0x84, 0x04, 0xbc, 0x46, 0x28, 0x00, 0x0c, 0xc4, 0xc7, 0x00, 0x01,
    0x7d, 0x68, 0xe0, 0x00, 0x22, 0x43, 0x27, 0x5d, 0x78,
];

/// Mock virtual camera sink recording frame counts and dimension history.
struct MockCameraSink {
    open_called: bool,
    frame_count: Arc<AtomicUsize>,
    close_count: Arc<AtomicUsize>,
    last_dimensions: Arc<Mutex<(u32, u32)>>,
    recorded_dimensions: Arc<Mutex<Vec<(u32, u32)>>>,
    path: PathBuf,
}

impl MockCameraSink {
    fn new(
        frame_count: Arc<AtomicUsize>,
        last_dimensions: Arc<Mutex<(u32, u32)>>,
        recorded_dimensions: Arc<Mutex<Vec<(u32, u32)>>>,
    ) -> Self {
        Self {
            open_called: false,
            frame_count,
            close_count: Arc::new(AtomicUsize::new(0)),
            last_dimensions,
            recorded_dimensions,
            path: PathBuf::from("/dev/mock_video0"),
        }
    }

    fn with_close_counter(mut self, close_count: Arc<AtomicUsize>) -> Self {
        self.close_count = close_count;
        self
    }
}

impl VirtualCameraSink for MockCameraSink {
    fn open(&mut self) -> Result<(), VcamError> {
        self.open_called = true;
        Ok(())
    }

    fn write_frame(&mut self, width: u32, height: u32, data: &[u8]) -> Result<(), VcamError> {
        assert_eq!(data.len(), (width * height * 3 / 2) as usize);
        self.frame_count.fetch_add(1, Ordering::SeqCst);
        let mut dims = self.last_dimensions.lock().unwrap();
        *dims = (width, height);
        self.recorded_dimensions
            .lock()
            .unwrap()
            .push((width, height));
        Ok(())
    }

    fn current_dimensions(&self) -> Option<(u32, u32)> {
        let dims = *self.last_dimensions.lock().unwrap();
        if dims.0 > 0 && dims.1 > 0 {
            Some(dims)
        } else {
            None
        }
    }

    fn close(&mut self) -> Result<(), VcamError> {
        self.open_called = false;
        self.close_count.fetch_add(1, Ordering::SeqCst);
        Ok(())
    }

    fn is_open(&self) -> bool {
        self.open_called
    }

    fn device_path(&self) -> &Path {
        &self.path
    }
}

#[test]
fn test_decoder_worker_lifecycle_with_virtual_camera_sink() {
    let frame_count = Arc::new(AtomicUsize::new(0));
    let last_dimensions = Arc::new(Mutex::new((0, 0)));
    let recorded_dimensions = Arc::new(Mutex::new(Vec::new()));

    let mut mock_sink = MockCameraSink::new(
        frame_count.clone(),
        last_dimensions.clone(),
        recorded_dimensions,
    );

    // Write standby frame before stream starts (simulating OBS initialization)
    let standby_res = mock_sink.write_standby_frame(1280, 720);
    assert!(standby_res.is_ok());
    assert_eq!(frame_count.load(Ordering::SeqCst), 1);
    assert_eq!(*last_dimensions.lock().unwrap(), (1280, 720));

    // Spawn decoder worker with mock sink
    let (decoder_sender, decoder_rx) = DecoderSender::new_bounded();
    let running = Arc::new(AtomicBool::new(true));

    let handle = spawn_decoder_worker(
        decoder_rx,
        Some(Box::new(mock_sink)),
        None,
        false,
        running.clone(),
    )
    .expect("Worker thread should spawn successfully");

    // Push dummy config and frames (non-decodable synthetic bytes will be safely discarded by FFmpeg)
    let config_res =
        decoder_sender.push_config(0, Bytes::from_static(&[0x00, 0x00, 0x00, 0x01, 0x67]));
    assert!(config_res.is_ok());

    let frame_res = decoder_sender.push_frame(
        16_666,
        true,
        Bytes::from_static(&[0x00, 0x00, 0x00, 0x01, 0x65]),
    );
    assert!(frame_res.is_ok());

    // Give worker time to process
    std::thread::sleep(Duration::from_millis(50));

    // Clean shutdown
    decoder_sender.stop();
    running.store(false, Ordering::Relaxed);

    let join_res = handle.join();
    assert!(join_res.is_ok());
}

#[test]
fn test_standby_pattern_generation() {
    let frame = generate_standby_frame(1920, 1080);
    // YUV420P: Y plane (1920*1080) + U plane (960*540) + V plane (960*540)
    assert_eq!(frame.len(), 1920 * 1080 * 3 / 2);

    // Verify Y background (32) and UV neutral (128)
    assert_eq!(frame[0], 32);
    let u_start = 1920 * 1080;
    assert_eq!(frame[u_start], 128);
}

#[test]
fn test_decoder_worker_decodes_real_h264_and_resizes_in_vcam() {
    let frame_count = Arc::new(AtomicUsize::new(0));
    let last_dimensions = Arc::new(Mutex::new((0, 0)));
    let recorded_dimensions = Arc::new(Mutex::new(Vec::new()));

    let mock_sink = MockCameraSink::new(
        frame_count.clone(),
        last_dimensions.clone(),
        recorded_dimensions.clone(),
    );

    let (decoder_sender, decoder_rx) = DecoderSender::new_bounded();
    let (frame_tx, frame_rx) = crossbeam_channel::bounded::<DecodedFrame>(4);
    let running = Arc::new(AtomicBool::new(true));

    let handle = spawn_decoder_worker(
        decoder_rx,
        Some(Box::new(mock_sink)),
        Some(frame_tx),
        true,
        running.clone(),
    )
    .expect("Worker thread should spawn successfully");

    // 1. Send 64x64 red H.264 stream
    decoder_sender
        .push_config(1_000, Bytes::from_static(SPS_PPS_64X64))
        .unwrap();
    assert!(
        decoder_sender
            .push_frame(1_000, true, Bytes::from_static(IDR_64X64_RED))
            .unwrap()
    );

    let frame_1 = frame_rx
        .recv_timeout(Duration::from_secs(2))
        .expect("Should decode 64x64 frame");
    assert_eq!(frame_1.width, 64);
    assert_eq!(frame_1.height, 64);
    assert_eq!(frame_1.yuv420p.len(), 64 * 64 * 3 / 2);
    let rgba_1 = frame_1.rgba.expect("RGBA output must be populated");
    assert_eq!(rgba_1.len(), 64 * 64 * 4);
    assert!(rgba_1[0] > 200 && rgba_1[1] < 30 && rgba_1[2] < 30 && rgba_1[3] == 255);

    // 2. Switch resolution dynamically to 32x32 blue H.264 stream
    decoder_sender
        .push_config(2_000, Bytes::from_static(SPS_PPS_32X32))
        .unwrap();
    assert!(
        decoder_sender
            .push_frame(2_000, true, Bytes::from_static(IDR_32X32_BLUE))
            .unwrap()
    );

    let frame_2 = frame_rx
        .recv_timeout(Duration::from_secs(2))
        .expect("Should decode 32x32 frame");
    assert_eq!(frame_2.width, 32);
    assert_eq!(frame_2.height, 32);
    assert_eq!(frame_2.yuv420p.len(), 32 * 32 * 3 / 2);
    let rgba_2 = frame_2.rgba.expect("RGBA output must be populated");
    assert_eq!(rgba_2.len(), 32 * 32 * 4);
    assert!(rgba_2[0] < 30 && rgba_2[1] < 30 && rgba_2[2] > 200 && rgba_2[3] == 255);

    assert_eq!(frame_count.load(Ordering::SeqCst), 2);
    assert_eq!(*last_dimensions.lock().unwrap(), (32, 32));
    assert_eq!(
        *recorded_dimensions.lock().unwrap(),
        vec![(64, 64), (32, 32)]
    );

    decoder_sender.stop();
    running.store(false, Ordering::Relaxed);
    let join_res = handle.join().expect("Worker thread should join");
    assert!(join_res.is_ok());
}

#[test]
fn test_decoder_worker_emits_standby_frames_and_resumes_live() {
    let frame_count = Arc::new(AtomicUsize::new(0));
    let close_count = Arc::new(AtomicUsize::new(0));
    let last_dimensions = Arc::new(Mutex::new((0, 0)));
    let recorded_dimensions = Arc::new(Mutex::new(Vec::new()));

    let mut mock_sink = MockCameraSink::new(
        frame_count.clone(),
        last_dimensions.clone(),
        recorded_dimensions.clone(),
    )
    .with_close_counter(close_count.clone());
    mock_sink.open().unwrap();

    let (decoder_sender, decoder_rx) = DecoderSender::new_bounded();
    let (frame_tx, frame_rx) = crossbeam_channel::bounded::<DecodedFrame>(8);
    let running = Arc::new(AtomicBool::new(true));

    let handle = spawn_decoder_worker(
        decoder_rx,
        Some(Box::new(mock_sink)),
        Some(frame_tx),
        false,
        running.clone(),
    )
    .expect("Worker thread should spawn successfully");

    // 1. Decode initial 64x64 frame
    decoder_sender
        .push_config(1_000, Bytes::from_static(SPS_PPS_64X64))
        .unwrap();
    assert!(
        decoder_sender
            .push_frame(1_000, true, Bytes::from_static(IDR_64X64_RED))
            .unwrap()
    );
    let first = frame_rx
        .recv_timeout(Duration::from_secs(2))
        .expect("Should decode initial 64x64 frame");
    assert_eq!((first.width, first.height), (64, 64));
    let count_before_standby = frame_count.load(Ordering::SeqCst);
    assert_eq!(count_before_standby, 1);

    // 2. Enter standby on disconnect -> sink must remain open and keep receiving 64x64 standby frames
    decoder_sender.enter_standby();
    std::thread::sleep(Duration::from_millis(550));

    assert_eq!(
        close_count.load(Ordering::SeqCst),
        0,
        "Sink must stay open across standby"
    );
    let count_during_standby = frame_count.load(Ordering::SeqCst);
    assert!(
        count_during_standby >= count_before_standby + 2,
        "Expected continuous standby ticks, got before={count_before_standby} during={count_during_standby}"
    );
    assert_eq!(*last_dimensions.lock().unwrap(), (64, 64));

    // 3. Reconnect with a fresh 32x32 stream -> live decoding resumes seamlessly
    decoder_sender
        .push_config(2_000, Bytes::from_static(SPS_PPS_32X32))
        .unwrap();
    assert!(
        decoder_sender
            .push_frame(2_000, true, Bytes::from_static(IDR_32X32_BLUE))
            .unwrap()
    );
    let resumed = frame_rx
        .recv_timeout(Duration::from_secs(2))
        .expect("Should resume live decoding at 32x32");
    assert_eq!((resumed.width, resumed.height), (32, 32));
    assert_eq!(*last_dimensions.lock().unwrap(), (32, 32));

    decoder_sender.stop();
    running.store(false, Ordering::Relaxed);
    let join_res = handle.join().expect("Worker thread should join");
    assert!(join_res.is_ok());
    assert_eq!(close_count.load(Ordering::SeqCst), 1);
}

#[test]
fn test_low_latency_worker_skips_preview_when_disabled_and_caps_preview_size() {
    let frame_count = Arc::new(AtomicUsize::new(0));
    let last_dimensions = Arc::new(Mutex::new((0, 0)));
    let recorded_dimensions = Arc::new(Mutex::new(Vec::new()));

    let mut mock_sink = MockCameraSink::new(
        frame_count.clone(),
        last_dimensions.clone(),
        recorded_dimensions,
    );
    mock_sink.open().unwrap();

    let (decoder_sender, decoder_rx) =
        DecoderSender::new_with_capacity(LIVE_DECODER_QUEUE_CAPACITY);
    let (preview_tx, preview_rx) = crossbeam_channel::bounded::<PreviewFrame>(1);
    let preview_enabled = Arc::new(AtomicBool::new(false));
    let running = Arc::new(AtomicBool::new(true));

    let handle = spawn_decoder_worker_low_latency(
        decoder_rx,
        Some(Box::new(mock_sink)),
        Some(preview_tx),
        preview_enabled.clone(),
        32,
        running.clone(),
        None,
    )
    .expect("Low-latency worker should spawn");

    // 1. With preview_enabled = false, V4L2 sink receives full 64x64 YUV420P and no PreviewFrame is produced
    decoder_sender
        .push_config(1_000, Bytes::from_static(SPS_PPS_64X64))
        .unwrap();
    assert!(
        decoder_sender
            .push_frame(1_000, true, Bytes::from_static(IDR_64X64_RED))
            .unwrap()
    );

    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    while frame_count.load(Ordering::SeqCst) < 1 && std::time::Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(10));
    }
    assert_eq!(frame_count.load(Ordering::SeqCst), 1);
    assert_eq!(*last_dimensions.lock().unwrap(), (64, 64));
    assert!(
        preview_rx.try_recv().is_err(),
        "PreviewFrame must not be built when preview_enabled is false"
    );

    // 2. Enable preview dynamically -> sink continues receiving full 64x64 while preview is capped at 32x32
    preview_enabled.store(true, Ordering::Relaxed);
    std::thread::sleep(Duration::from_millis(35));
    assert!(
        decoder_sender
            .push_frame(2_000, true, Bytes::from_static(IDR_64X64_RED))
            .unwrap()
    );

    let preview = preview_rx
        .recv_timeout(Duration::from_secs(2))
        .expect("Should emit downscaled PreviewFrame when enabled");
    assert_eq!((preview.source_width, preview.source_height), (64, 64));
    assert_eq!((preview.preview_width, preview.preview_height), (32, 32));
    assert_eq!(preview.rgba.len(), 32 * 32 * 4);
    assert_eq!(frame_count.load(Ordering::SeqCst), 2);

    decoder_sender.stop();
    running.store(false, Ordering::Relaxed);
    assert!(handle.join().expect("Worker thread should join").is_ok());
}

#[test]
fn test_optimized_worker_recycles_preview_buffers_and_handles_flags_and_callbacks() {
    let frame_count = Arc::new(AtomicUsize::new(0));
    let last_dimensions = Arc::new(Mutex::new((0, 0)));
    let recorded_dimensions = Arc::new(Mutex::new(Vec::new()));

    let mut mock_sink = MockCameraSink::new(
        frame_count.clone(),
        last_dimensions.clone(),
        recorded_dimensions,
    );
    mock_sink.open().unwrap();

    let (decoder_sender, decoder_rx) =
        DecoderSender::new_with_capacity(LIVE_DECODER_QUEUE_CAPACITY);
    let (preview_tx, preview_rx) = crossbeam_channel::bounded::<PreviewFrame>(1);
    let (recycle_tx, recycle_rx) = crossbeam_channel::bounded::<Vec<u8>>(2);
    let preview_pushed_count = Arc::new(AtomicUsize::new(0));
    let preview_pushed_c = preview_pushed_count.clone();
    let kf_reasons = Arc::new(Mutex::new(Vec::<KeyframeRequestReason>::new()));
    let kf_reasons_c = kf_reasons.clone();

    let preview_ctrl = PreviewRuntimeControl::new(true, 32, 0);
    let running = Arc::new(AtomicBool::new(true));

    let handle = spawn_decoder_worker_optimized(
        decoder_rx,
        Some(Box::new(mock_sink)),
        LowLatencyWorkerOptions {
            preview_tx: Some(preview_tx),
            preview_recycle_rx: Some(recycle_rx),
            preview_ctrl: preview_ctrl.clone(),
            on_preview_pushed: Some(Arc::new(move || {
                preview_pushed_c.fetch_add(1, Ordering::SeqCst);
            })),
            on_keyframe_request: Some(Arc::new(move |reason| {
                kf_reasons_c.lock().unwrap().push(reason);
            })),
            decoder_options: DecoderOptions::default(),
        },
        running.clone(),
    )
    .expect("Optimized worker should spawn");

    // 1. Trigger a corrupted frame to verify on_keyframe_request callback runs without a bridge thread
    assert!(
        decoder_sender
            .push_frame_with_flags(
                500,
                FrameFlags::KEYFRAME,
                Bytes::from_static(&[0x00, 0x00, 0x01, 0x65, 0xFF, 0xFF, 0xAA, 0x55]),
            )
            .unwrap()
    );
    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    while kf_reasons.lock().unwrap().is_empty() && std::time::Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(5));
    }
    assert_eq!(
        *kf_reasons.lock().unwrap(),
        vec![KeyframeRequestReason::DecodeError]
    );

    // 2. Decode valid frame with MIRROR_H | ROTATE_90 flags and verify preview buffer creation
    decoder_sender
        .push_config(1_000, Bytes::from_static(SPS_PPS_64X64))
        .unwrap();
    assert!(
        decoder_sender
            .push_frame_with_flags(
                1_000,
                FrameFlags::KEYFRAME | FrameFlags::MIRROR_H | FrameFlags::ROTATE_90,
                Bytes::from_static(IDR_64X64_RED),
            )
            .unwrap()
    );

    let preview_1 = preview_rx
        .recv_timeout(Duration::from_secs(2))
        .expect("Should emit first PreviewFrame");
    assert_eq!((preview_1.source_width, preview_1.source_height), (64, 64));
    assert_eq!(
        (preview_1.preview_width, preview_1.preview_height),
        (32, 32)
    );
    let recycled_ptr = preview_1.rgba.as_ptr();
    assert!(recycle_tx.try_send(preview_1.rgba).is_ok());

    // 3. Push a second frame and verify the exact Vec<u8> buffer pointer is reused without realloc
    assert!(
        decoder_sender
            .push_frame_with_flags(
                2_000,
                FrameFlags::KEYFRAME | FrameFlags::ROTATE_180,
                Bytes::from_static(IDR_64X64_RED),
            )
            .unwrap()
    );

    let preview_2 = preview_rx
        .recv_timeout(Duration::from_secs(2))
        .expect("Should emit second PreviewFrame reusing recycled buffer");
    assert_eq!(
        preview_2.rgba.as_ptr(),
        recycled_ptr,
        "PreviewFrame RGBA Vec<u8> should reuse recycled allocation without reallocating"
    );
    assert_eq!(preview_pushed_count.load(Ordering::SeqCst), 2);
    assert_eq!(frame_count.load(Ordering::SeqCst), 2);
    assert_eq!(*last_dimensions.lock().unwrap(), (64, 64));

    decoder_sender.stop();
    running.store(false, Ordering::Relaxed);
    assert!(handle.join().expect("Worker thread should join").is_ok());
}
