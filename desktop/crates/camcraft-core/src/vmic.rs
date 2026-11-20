//! PipeWire / PulseAudio virtual microphone sink and audio stream worker.
//!
//! Follows `docs/PROTOCOL.md` (`AUDIO_CONFIG 0x03`, `AUDIO_FRAME 0x04`),
//! `docs/DESIGN.md` §3.1, and `docs/PLAN.md` (Stage 7):
//! - Automatically provisions a PipeWire null sink (`camcraft_sink`) and remapped
//!   virtual microphone source (`"CamCraft Virtual Mic"`) via `pactl`.
//! - Streams 48 kHz Mono S16LE PCM audio frames via `pacat` (or `pw-cat` fallback)
//!   without any C FFI or `unsafe` blocks.
//! - Computes real-time RMS / Peak dB level metrics for the desktop GUI meter.
//! - Runs in a dedicated bounded-channel worker thread (`camcraft-vmic`) to keep
//!   the async Tokio receiver loop non-blocking and drop stale audio under load.

use std::io::Write;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::JoinHandle;
use std::time::Duration;

use bytes::Bytes;
use camcraft_protocol::AudioConfigPayload;
use crossbeam_channel::{Receiver, RecvTimeoutError, SendTimeoutError, Sender, TrySendError};
use thiserror::Error;
use tracing::{debug, info, warn};

use crate::{AudioOutputSink, CoreError};

/// Default PipeWire/PulseAudio null sink name receiving decoded CamCraft audio.
pub const DEFAULT_SINK_NAME: &str = "camcraft_sink";

/// Default PipeWire/PulseAudio virtual microphone source name.
pub const DEFAULT_SOURCE_NAME: &str = "camcraft_virtual_mic";

/// Legacy virtual microphone source name used in earlier Stage 0 setup scripts.
pub const LEGACY_SOURCE_NAME: &str = "camcraft_mic";

/// Human-friendly description for the backing null sink.
pub const DEFAULT_SINK_DESCRIPTION: &str = "CamCraft Audio Sink";

/// Human-friendly description displayed in GNOME Settings / `pavucontrol` / Vesktop / OBS.
pub const DEFAULT_SOURCE_DESCRIPTION: &str = "CamCraft Virtual Mic";

/// Default PCM sample rate in Hz (`docs/PROTOCOL.md` §3).
pub const DEFAULT_SAMPLE_RATE: u32 = 48_000;

/// Default PCM channel count (`1` = Mono).
pub const DEFAULT_CHANNELS: u16 = 1;

/// Default audio frame duration in milliseconds.
pub const PCM_FRAME_DURATION_MS: u32 = 20;

/// Number of PCM samples per 20 ms frame at 48 kHz Mono.
pub const DEFAULT_FRAME_SAMPLES: usize = 960;

/// Number of PCM S16LE bytes per 20 ms frame at 48 kHz Mono (960 * 2 bytes).
pub const DEFAULT_FRAME_BYTES: usize = 1_920;

/// Maximum queued audio packets before dropping stale frames to cap latency.
pub const AUDIO_QUEUE_CAPACITY: usize = 6;

/// Noise/silence floor in dBFS used for volume level meter normalization.
pub const DB_FLOOR: f32 = -60.0;

const MIN_SAMPLE_RATE: u32 = 8_000;
const MAX_SAMPLE_RATE: u32 = 192_000;
const WORKER_POLL_TIMEOUT: Duration = Duration::from_millis(50);

/// Errors encountered while provisioning or writing to the virtual microphone.
#[derive(Debug, Error)]
pub enum VmicError {
    #[error("required audio CLI tool '{tool}' was not found in PATH: {source}")]
    ToolNotFound {
        tool: &'static str,
        #[source]
        source: std::io::Error,
    },

    #[error("audio command '{command}' failed with exit code {code:?}: {stderr}")]
    CommandFailed {
        command: String,
        code: Option<i32>,
        stderr: String,
    },

    #[error("unsupported audio codec '{0}' (expected 'pcm_s16le')")]
    UnsupportedCodec(String),

    #[error("invalid PCM audio format ({sample_rate} Hz, {channels} ch): {details}")]
    InvalidFormat {
        sample_rate: u32,
        channels: u16,
        details: String,
    },

    #[error("failed to start virtual microphone backend '{backend}': {details}")]
    BackendStart { backend: String, details: String },

    #[error("failed writing {bytes} audio bytes to virtual microphone: {details}")]
    WriteError { bytes: usize, details: String },

    #[error("audio worker channel disconnected")]
    ChannelDisconnected,

    #[error("virtual microphone I/O error: {0}")]
    Io(#[from] std::io::Error),
}

/// Details about the active PipeWire/PulseAudio virtual microphone nodes.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct VirtualMicInfo {
    pub sink_name: String,
    pub source_name: String,
    pub description: String,
    pub created_sink_module: Option<u32>,
    pub created_source_module: Option<u32>,
}

/// Real-time audio volume level telemetry derived from incoming PCM S16LE frames.
#[derive(Debug, Clone, Copy, PartialEq)]
pub struct AudioLevelMetrics {
    /// Root-mean-square signal level in dBFS (`-60.0` .. `0.0`).
    pub rms_db: f32,
    /// Instantaneous sample peak level in dBFS (`-60.0` .. `0.0`).
    pub peak_db: f32,
    /// Normalized RMS level mapped linearly from `[DB_FLOOR, 0.0]` into `0.0..=1.0`.
    pub normalized: f32,
    /// Whether audio is currently muted.
    pub muted: bool,
    /// True if peak approaches digital clipping (`>= -1.0 dBFS`).
    pub clipping: bool,
}

impl AudioLevelMetrics {
    /// Returns a zero-level silence reading at [`DB_FLOOR`].
    #[must_use]
    pub const fn silence(muted: bool) -> Self {
        Self {
            rms_db: DB_FLOOR,
            peak_db: DB_FLOOR,
            normalized: 0.0,
            muted,
            clipping: false,
        }
    }
}

/// Parses node/module names from the second column of `pactl list short <kind>` output.
#[must_use]
pub fn parse_pactl_short_names(output: &str) -> Vec<String> {
    output
        .lines()
        .filter_map(|line| {
            let mut cols = line.split_whitespace();
            let _index = cols.next()?;
            let name = cols.next()?;
            Some(name.to_owned())
        })
        .collect()
}

/// Returns `true` if `name` appears in `pactl list short <kind>` output.
#[must_use]
pub fn has_pactl_entry(output: &str, name: &str) -> bool {
    parse_pactl_short_names(output)
        .iter()
        .any(|entry| entry == name)
}

/// Computes RMS/peak dBFS and normalized meter levels from little-endian 16-bit PCM samples.
///
/// Allocates zero heap memory.
#[must_use]
pub fn compute_pcm_s16le_level(pcm_s16le: &[u8], muted: bool) -> AudioLevelMetrics {
    if muted || pcm_s16le.len() < 2 {
        return AudioLevelMetrics::silence(muted);
    }

    let mut sum_sq: f64 = 0.0;
    let mut peak: f64 = 0.0;
    let mut sample_count: usize = 0;

    for &pair in pcm_s16le.as_chunks::<2>().0 {
        let raw = i16::from_le_bytes(pair);
        let norm = f64::from(raw) / 32_768.0;
        let abs = norm.abs();
        sum_sq += norm * norm;
        if abs > peak {
            peak = abs;
        }
        sample_count += 1;
    }

    if sample_count == 0 {
        return AudioLevelMetrics::silence(muted);
    }

    let rms = (sum_sq / sample_count as f64).sqrt();
    let rms_db = amplitude_to_dbfs(rms);
    let peak_db = amplitude_to_dbfs(peak);
    let normalized = ((rms_db - DB_FLOOR) / -DB_FLOOR).clamp(0.0, 1.0);
    let clipping = peak_db >= -1.0;

    AudioLevelMetrics {
        rms_db,
        peak_db,
        normalized,
        muted: false,
        clipping,
    }
}

#[inline]
fn amplitude_to_dbfs(amplitude: f64) -> f32 {
    if amplitude <= 0.0 {
        return DB_FLOOR;
    }
    let db = 20.0 * amplitude.log10();
    (db as f32).clamp(DB_FLOOR, 0.0)
}

fn run_pactl(args: &[&str]) -> Result<String, VmicError> {
    let output = Command::new("pactl")
        .args(args)
        .output()
        .map_err(|source| VmicError::ToolNotFound {
            tool: "pactl",
            source,
        })?;

    if !output.status.success() {
        return Err(VmicError::CommandFailed {
            command: format!("pactl {}", args.join(" ")),
            code: output.status.code(),
            stderr: String::from_utf8_lossy(&output.stderr).trim().to_owned(),
        });
    }

    Ok(String::from_utf8_lossy(&output.stdout).into_owned())
}

fn load_pactl_module(
    primary_args: &[&str],
    fallback_args: &[&str],
) -> Result<Option<u32>, VmicError> {
    let stdout = match run_pactl(primary_args) {
        Ok(out) => out,
        Err(primary_err) => {
            debug!(error = %primary_err, "Primary pactl module args failed; retrying fallback");
            run_pactl(fallback_args)?
        }
    };
    Ok(stdout.trim().parse::<u32>().ok())
}

/// Checks the current PipeWire/PulseAudio user session and creates the
/// `"CamCraft Virtual Mic"` source (and backing `camcraft_sink`) if missing.
pub fn ensure_virtual_mic() -> Result<VirtualMicInfo, VmicError> {
    let sinks_out = run_pactl(&["list", "short", "sinks"])?;
    let created_sink_module = if has_pactl_entry(&sinks_out, DEFAULT_SINK_NAME) {
        debug!(
            sink = DEFAULT_SINK_NAME,
            "Found existing CamCraft audio sink"
        );
        None
    } else {
        info!(
            sink = DEFAULT_SINK_NAME,
            "Creating PipeWire null sink for CamCraft"
        );
        let sink_desc_quoted =
            format!(r#"sink_properties=device.description="{DEFAULT_SINK_DESCRIPTION}""#);
        let sink_desc_underscored = "sink_properties=device.description=CamCraft_Audio_Sink";
        let primary = [
            "load-module",
            "module-null-sink",
            "sink_name=camcraft_sink",
            "rate=48000",
            "channels=1",
            "channel_map=mono",
            &sink_desc_quoted,
        ];
        let fallback = [
            "load-module",
            "module-null-sink",
            "sink_name=camcraft_sink",
            sink_desc_underscored,
        ];
        load_pactl_module(&primary, &fallback)?
    };

    let sources_out = run_pactl(&["list", "short", "sources"])?;
    let (source_name, created_source_module) = if has_pactl_entry(&sources_out, DEFAULT_SOURCE_NAME)
    {
        debug!(
            source = DEFAULT_SOURCE_NAME,
            "Found existing CamCraft virtual microphone"
        );
        (DEFAULT_SOURCE_NAME.to_owned(), None)
    } else if has_pactl_entry(&sources_out, LEGACY_SOURCE_NAME) {
        debug!(
            source = LEGACY_SOURCE_NAME,
            "Found existing legacy CamCraft virtual microphone"
        );
        (LEGACY_SOURCE_NAME.to_owned(), None)
    } else {
        info!(
            source = DEFAULT_SOURCE_NAME,
            description = DEFAULT_SOURCE_DESCRIPTION,
            "Creating PipeWire virtual microphone source"
        );
        let source_desc_quoted =
            format!(r#"source_properties=device.description="{DEFAULT_SOURCE_DESCRIPTION}""#);
        let source_desc_underscored = "source_properties=device.description=CamCraft_Virtual_Mic";
        let master_monitor = format!("master={DEFAULT_SINK_NAME}.monitor");
        let source_arg = format!("source_name={DEFAULT_SOURCE_NAME}");
        let primary = [
            "load-module",
            "module-remap-source",
            &master_monitor,
            &source_arg,
            "rate=48000",
            "channels=1",
            "channel_map=mono",
            &source_desc_quoted,
        ];
        let fallback = [
            "load-module",
            "module-remap-source",
            &master_monitor,
            &source_arg,
            source_desc_underscored,
        ];
        let module_id = load_pactl_module(&primary, &fallback)?;
        (DEFAULT_SOURCE_NAME.to_owned(), module_id)
    };

    Ok(VirtualMicInfo {
        sink_name: DEFAULT_SINK_NAME.to_owned(),
        source_name,
        description: DEFAULT_SOURCE_DESCRIPTION.to_owned(),
        created_sink_module,
        created_source_module,
    })
}

fn validate_pcm_format(sample_rate: u32, channels: u16) -> Result<(), VmicError> {
    if !(MIN_SAMPLE_RATE..=MAX_SAMPLE_RATE).contains(&sample_rate) {
        return Err(VmicError::InvalidFormat {
            sample_rate,
            channels,
            details: format!("sample rate must be within {MIN_SAMPLE_RATE}..={MAX_SAMPLE_RATE} Hz"),
        });
    }
    if !(1..=2).contains(&channels) {
        return Err(VmicError::InvalidFormat {
            sample_rate,
            channels,
            details: "channel count must be 1 (Mono) or 2 (Stereo)".to_owned(),
        });
    }
    Ok(())
}

/// PipeWire / PulseAudio virtual microphone sink streaming PCM S16LE audio
/// into `camcraft_sink` via `pacat` (with `pw-cat` fallback).
pub struct VirtualMicSink {
    sink_name: String,
    source_name: String,
    sample_rate: u32,
    channels: u16,
    child: Option<Child>,
    stdin: Option<ChildStdin>,
    active_backend: Option<&'static str>,
}

impl VirtualMicSink {
    /// Creates a sink targeting an explicit PipeWire/PulseAudio sink and source pair.
    #[must_use]
    pub fn new(sink_name: impl Into<String>, source_name: impl Into<String>) -> Self {
        Self {
            sink_name: sink_name.into(),
            source_name: source_name.into(),
            sample_rate: 0,
            channels: 0,
            child: None,
            stdin: None,
            active_backend: None,
        }
    }

    /// Creates a sink configured from [`ensure_virtual_mic`] metadata.
    #[must_use]
    pub fn from_info(info: &VirtualMicInfo) -> Self {
        Self::new(info.sink_name.clone(), info.source_name.clone())
    }

    /// Ensures the PipeWire virtual microphone exists and constructs a [`VirtualMicSink`].
    pub fn ensure_and_create() -> Result<(Self, VirtualMicInfo), VmicError> {
        let info = ensure_virtual_mic()?;
        let sink = Self::from_info(&info);
        Ok((sink, info))
    }

    /// Returns the backing PipeWire/PulseAudio null sink name.
    #[must_use]
    pub fn sink_name(&self) -> &str {
        &self.sink_name
    }

    /// Returns the virtual microphone source name exposed to apps.
    #[must_use]
    pub fn source_name(&self) -> &str {
        &self.source_name
    }

    /// Returns `true` if a child playback process is currently attached.
    #[must_use]
    pub fn is_open(&self) -> bool {
        self.stdin.is_some() && self.child.is_some()
    }

    /// Returns the name of the active audio playback backend (`"pacat"` or `"pw-cat"`).
    #[must_use]
    pub fn active_backend(&self) -> Option<&'static str> {
        self.active_backend
    }

    /// Spawns `pacat` (or `pw-cat` if `pacat` is unavailable) for the specified PCM format.
    pub fn open(&mut self, sample_rate: u32, channels: u16) -> Result<(), VmicError> {
        validate_pcm_format(sample_rate, channels)?;

        if self.stdin.is_some()
            && self.sample_rate == sample_rate
            && self.channels == channels
            && let Some(ref mut child) = self.child
        {
            match child.try_wait() {
                Ok(None) => return Ok(()),
                Ok(Some(status)) => {
                    warn!(
                        ?status,
                        "Virtual mic playback backend exited unexpectedly; respawning"
                    );
                }
                Err(e) => {
                    warn!(error = %e, "Failed polling virtual mic backend status; respawning");
                }
            }
        }

        self.close();

        let (backend, mut child) = self.spawn_backend(sample_rate, channels)?;
        let Some(stdin) = child.stdin.take() else {
            let _ = child.kill();
            let _ = child.wait();
            return Err(VmicError::BackendStart {
                backend: backend.to_owned(),
                details: "failed to capture stdin pipe of playback process".to_owned(),
            });
        };

        self.sample_rate = sample_rate;
        self.channels = channels;
        self.child = Some(child);
        self.stdin = Some(stdin);
        self.active_backend = Some(backend);

        info!(
            backend,
            sink = %self.sink_name,
            source = %self.source_name,
            sample_rate,
            channels,
            "Virtual microphone playback pipeline opened"
        );
        Ok(())
    }

    fn spawn_backend(
        &self,
        sample_rate: u32,
        channels: u16,
    ) -> Result<(&'static str, Child), VmicError> {
        let rate_arg = format!("--rate={sample_rate}");
        let ch_arg = format!("--channels={channels}");
        let dev_arg = format!("--device={}", self.sink_name);
        let stream_arg = format!("--stream-name={DEFAULT_SOURCE_DESCRIPTION}");

        let pacat_attempt = Command::new("pacat")
            .args([
                "--playback",
                "--raw",
                "--format=s16le",
                &rate_arg,
                &ch_arg,
                &dev_arg,
                "--latency-msec=20",
                "--process-time-msec=10",
                "--client-name=CamCraft",
                &stream_arg,
            ])
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .spawn();

        match pacat_attempt {
            Ok(child) => Ok(("pacat", child)),
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => {
                debug!("pacat not found; falling back to pw-cat");
                let rate_str = sample_rate.to_string();
                let ch_str = channels.to_string();
                let channel_map = if channels == 1 { "MONO" } else { "Stereo" };
                let pw_child = Command::new("pw-cat")
                    .args([
                        "--playback",
                        "--raw",
                        "--format",
                        "s16",
                        "--rate",
                        &rate_str,
                        "--channels",
                        &ch_str,
                        "--channel-map",
                        channel_map,
                        "--latency",
                        "20ms",
                        "--target",
                        &self.sink_name,
                        "-",
                    ])
                    .stdin(Stdio::piped())
                    .stdout(Stdio::null())
                    .stderr(Stdio::null())
                    .spawn()
                    .map_err(|pw_err| VmicError::BackendStart {
                        backend: "pw-cat".to_owned(),
                        details: pw_err.to_string(),
                    })?;
                Ok(("pw-cat", pw_child))
            }
            Err(err) => Err(VmicError::BackendStart {
                backend: "pacat".to_owned(),
                details: err.to_string(),
            }),
        }
    }

    /// Writes S16LE PCM audio bytes to the backing PipeWire playback process.
    pub fn write_pcm(
        &mut self,
        sample_rate: u32,
        channels: u16,
        pcm_s16le: &[u8],
    ) -> Result<(), VmicError> {
        validate_pcm_format(sample_rate, channels)?;
        if pcm_s16le.is_empty() {
            return Ok(());
        }

        let frame_stride = 2 * usize::from(channels);
        if !pcm_s16le.len().is_multiple_of(frame_stride) {
            return Err(VmicError::WriteError {
                bytes: pcm_s16le.len(),
                details: format!(
                    "PCM S16LE buffer length {} is not aligned to {frame_stride}-byte sample frames",
                    pcm_s16le.len()
                ),
            });
        }

        if !self.is_open() || self.sample_rate != sample_rate || self.channels != channels {
            self.open(sample_rate, channels)?;
        }

        let write_result = match self.stdin.as_mut() {
            Some(stdin) => stdin.write_all(pcm_s16le),
            None => {
                return Err(VmicError::WriteError {
                    bytes: pcm_s16le.len(),
                    details: "virtual mic stdin handle is missing".to_owned(),
                });
            }
        };

        if let Err(err) = write_result {
            self.close();
            return Err(VmicError::WriteError {
                bytes: pcm_s16le.len(),
                details: err.to_string(),
            });
        }

        Ok(())
    }

    /// Closes the stdin pipe and cleanly reaps the playback child process.
    pub fn close(&mut self) {
        drop(self.stdin.take());

        if let Some(mut child) = self.child.take() {
            let mut exited = false;
            for _ in 0..5 {
                match child.try_wait() {
                    Ok(Some(_)) => {
                        exited = true;
                        break;
                    }
                    Ok(None) => std::thread::sleep(Duration::from_millis(10)),
                    Err(_) => break,
                }
            }
            if !exited {
                let _ = child.kill();
                let _ = child.wait();
            }
        }

        self.active_backend = None;
        self.sample_rate = 0;
        self.channels = 0;
    }
}

impl Drop for VirtualMicSink {
    fn drop(&mut self) {
        self.close();
    }
}

impl AudioOutputSink for VirtualMicSink {
    fn write_samples(
        &mut self,
        sample_rate: u32,
        channels: u16,
        pcm_s16le: &[u8],
    ) -> Result<(), CoreError> {
        self.write_pcm(sample_rate, channels, pcm_s16le)
            .map_err(CoreError::Vmic)
    }
}

/// Control/data message sent from the async receiver loop to the `camcraft-vmic` worker.
#[derive(Debug, Clone)]
pub enum AudioWorkerMessage {
    Config(AudioConfigPayload),
    Frame { pts_us: u64, payload: Bytes },
    SetState { enabled: bool, muted: bool },
    Stop,
}

/// Bounded sender handle for feeding audio packets and mute/enable updates to `camcraft-vmic`.
#[derive(Debug, Clone)]
pub struct AudioWorkerSender {
    tx: Sender<AudioWorkerMessage>,
    enabled: Arc<AtomicBool>,
    muted: Arc<AtomicBool>,
}

impl AudioWorkerSender {
    /// Creates a bounded channel (capacity [`AUDIO_QUEUE_CAPACITY`]) paired with shared state flags.
    #[must_use]
    pub fn new_bounded(
        enabled: Arc<AtomicBool>,
        muted: Arc<AtomicBool>,
    ) -> (Self, Receiver<AudioWorkerMessage>) {
        let (tx, rx) = crossbeam_channel::bounded(AUDIO_QUEUE_CAPACITY);
        (Self { tx, enabled, muted }, rx)
    }

    /// Sends an `AUDIO_CONFIG` packet to the worker.
    pub fn push_config(&self, config: AudioConfigPayload) -> Result<(), VmicError> {
        match self.tx.send_timeout(
            AudioWorkerMessage::Config(config),
            Duration::from_millis(50),
        ) {
            Ok(()) => Ok(()),
            Err(SendTimeoutError::Timeout(_)) => {
                warn!("Audio worker queue congested while pushing AUDIO_CONFIG");
                Ok(())
            }
            Err(SendTimeoutError::Disconnected(_)) => Err(VmicError::ChannelDisconnected),
        }
    }

    /// Pushes an `AUDIO_FRAME` packet without blocking; drops if the bounded queue is full.
    pub fn push_frame(&self, pts_us: u64, payload: Bytes) -> Result<bool, VmicError> {
        match self
            .tx
            .try_send(AudioWorkerMessage::Frame { pts_us, payload })
        {
            Ok(()) => Ok(true),
            Err(TrySendError::Full(_)) => {
                debug!("Audio worker queue full; dropping stale audio frame to preserve latency");
                Ok(false)
            }
            Err(TrySendError::Disconnected(_)) => Err(VmicError::ChannelDisconnected),
        }
    }

    /// Atomically updates forwarding/mute flags and notifies the worker immediately.
    pub fn set_state(&self, enabled: bool, muted: bool) {
        self.enabled.store(enabled, Ordering::Relaxed);
        self.muted.store(muted, Ordering::Relaxed);
        let _ = self
            .tx
            .try_send(AudioWorkerMessage::SetState { enabled, muted });
    }

    /// Returns the current audio forwarding enabled state.
    #[must_use]
    pub fn is_enabled(&self) -> bool {
        self.enabled.load(Ordering::Relaxed)
    }

    /// Returns the current audio mute state.
    #[must_use]
    pub fn is_muted(&self) -> bool {
        self.muted.load(Ordering::Relaxed)
    }

    /// Signals the audio worker thread to stop.
    pub fn stop(&self) {
        let _ = self
            .tx
            .send_timeout(AudioWorkerMessage::Stop, Duration::from_millis(50));
    }
}

/// Runs the synchronous audio processing loop inside the current thread.
pub fn run_audio_worker_loop(
    rx: Receiver<AudioWorkerMessage>,
    mut sink: Option<Box<dyn AudioOutputSink>>,
    level_tx: Option<Sender<AudioLevelMetrics>>,
    running: Arc<AtomicBool>,
    enabled: Arc<AtomicBool>,
    muted: Arc<AtomicBool>,
) {
    let mut sample_rate = DEFAULT_SAMPLE_RATE;
    let mut channels = DEFAULT_CHANNELS;
    let mut codec_supported = true;
    let silence_buf = [0u8; DEFAULT_FRAME_BYTES];

    while running.load(Ordering::Relaxed) {
        let msg = match rx.recv_timeout(WORKER_POLL_TIMEOUT) {
            Ok(msg) => msg,
            Err(RecvTimeoutError::Timeout) => continue,
            Err(RecvTimeoutError::Disconnected) => break,
        };

        match msg {
            AudioWorkerMessage::Config(cfg) => {
                if !cfg.codec.eq_ignore_ascii_case("pcm_s16le") {
                    warn!(
                        codec = %cfg.codec,
                        "Unsupported audio codec received; ignoring incoming audio frames"
                    );
                    codec_supported = false;
                    continue;
                }
                if let Err(err) = validate_pcm_format(cfg.sample_rate, cfg.channels) {
                    warn!(error = %err, "Invalid AUDIO_CONFIG parameters");
                    codec_supported = false;
                    continue;
                }
                sample_rate = cfg.sample_rate;
                channels = cfg.channels;
                codec_supported = true;
                info!(
                    codec = %cfg.codec,
                    sample_rate,
                    channels,
                    "Configured virtual microphone audio stream"
                );
            }
            AudioWorkerMessage::Frame { pts_us: _, payload } => {
                let is_enabled = enabled.load(Ordering::Relaxed);
                let is_muted = muted.load(Ordering::Relaxed);

                if !is_enabled || !codec_supported {
                    if let Some(ref tx) = level_tx {
                        let _ = tx.try_send(AudioLevelMetrics::silence(is_muted));
                    }
                    continue;
                }

                if is_muted {
                    if let Some(ref tx) = level_tx {
                        let _ = tx.try_send(AudioLevelMetrics::silence(true));
                    }
                    if let Some(ref mut out_sink) = sink {
                        write_silence_zero_alloc(
                            out_sink.as_mut(),
                            sample_rate,
                            channels,
                            payload.len(),
                            &silence_buf,
                        );
                    }
                    continue;
                }

                if let Some(ref tx) = level_tx {
                    let metrics = compute_pcm_s16le_level(&payload, false);
                    let _ = tx.try_send(metrics);
                }

                if let Some(ref mut out_sink) = sink
                    && let Err(err) = out_sink.write_samples(sample_rate, channels, &payload)
                {
                    warn!(error = %err, "Transient error writing audio samples to virtual mic");
                }
            }
            AudioWorkerMessage::SetState {
                enabled: en,
                muted: mu,
            } => {
                enabled.store(en, Ordering::Relaxed);
                muted.store(mu, Ordering::Relaxed);
                if (!en || mu)
                    && let Some(ref tx) = level_tx
                {
                    let _ = tx.try_send(AudioLevelMetrics::silence(mu));
                }
            }
            AudioWorkerMessage::Stop => break,
        }
    }

    drop(sink);
    debug!("camcraft-vmic audio worker exited cleanly");
}

fn write_silence_zero_alloc(
    sink: &mut dyn AudioOutputSink,
    sample_rate: u32,
    channels: u16,
    total_bytes: usize,
    silence_buf: &[u8; DEFAULT_FRAME_BYTES],
) {
    let mut remaining = total_bytes;
    while remaining > 0 {
        let chunk_len = remaining.min(silence_buf.len());
        if let Err(err) = sink.write_samples(sample_rate, channels, &silence_buf[..chunk_len]) {
            warn!(error = %err, "Transient error writing muted silence to virtual mic");
            break;
        }
        remaining -= chunk_len;
    }
}

/// Spawns the dedicated `camcraft-vmic` audio worker thread with a pre-created sink.
pub fn spawn_audio_worker(
    rx: Receiver<AudioWorkerMessage>,
    sink: Option<Box<dyn AudioOutputSink>>,
    level_tx: Option<Sender<AudioLevelMetrics>>,
    running: Arc<AtomicBool>,
    enabled: Arc<AtomicBool>,
    muted: Arc<AtomicBool>,
) -> Result<JoinHandle<()>, VmicError> {
    std::thread::Builder::new()
        .name("camcraft-vmic".to_owned())
        .spawn(move || {
            run_audio_worker_loop(rx, sink, level_tx, running, enabled, muted);
        })
        .map_err(VmicError::Io)
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;

    use super::*;

    #[derive(Clone, Default)]
    struct SharedAudioCapture {
        samples: Arc<Mutex<Vec<u8>>>,
        formats: Arc<Mutex<Vec<(u32, u16)>>>,
    }

    struct MockAudioSink {
        capture: SharedAudioCapture,
    }

    impl AudioOutputSink for MockAudioSink {
        fn write_samples(
            &mut self,
            sample_rate: u32,
            channels: u16,
            pcm_s16le: &[u8],
        ) -> Result<(), CoreError> {
            self.capture
                .formats
                .lock()
                .expect("lock formats")
                .push((sample_rate, channels));
            self.capture
                .samples
                .lock()
                .expect("lock samples")
                .extend_from_slice(pcm_s16le);
            Ok(())
        }
    }

    #[test]
    fn parse_pactl_short_names_extracts_sink_and_source_names() {
        let sample = "\
42\talsa_output.pci-0000_00_1f.3.analog-stereo\tPipeWire\ts32le 2ch 48000Hz\tSUSPENDED
43\tcamcraft_sink\tPipeWire\ts16le 1ch 48000Hz\tRUNNING
44\tcamcraft_mic\tPipeWire\ts16le 1ch 48000Hz\tSUSPENDED
45\tcamcraft_virtual_mic\tPipeWire\ts16le 1ch 48000Hz\tIDLE
";
        let names = parse_pactl_short_names(sample);
        assert_eq!(
            names,
            vec![
                "alsa_output.pci-0000_00_1f.3.analog-stereo",
                "camcraft_sink",
                "camcraft_mic",
                "camcraft_virtual_mic",
            ]
        );
        assert!(has_pactl_entry(sample, DEFAULT_SINK_NAME));
        assert!(has_pactl_entry(sample, LEGACY_SOURCE_NAME));
        assert!(has_pactl_entry(sample, DEFAULT_SOURCE_NAME));
        assert!(!has_pactl_entry(sample, "missing_source"));
    }

    #[test]
    fn compute_pcm_s16le_level_silence_and_signals() {
        let zeros = vec![0u8; DEFAULT_FRAME_BYTES];
        let silence = compute_pcm_s16le_level(&zeros, false);
        assert_eq!(silence.rms_db, DB_FLOOR);
        assert_eq!(silence.peak_db, DB_FLOOR);
        assert_eq!(silence.normalized, 0.0);
        assert!(!silence.muted);
        assert!(!silence.clipping);

        let mut full_scale = Vec::with_capacity(DEFAULT_FRAME_BYTES);
        for _ in 0..DEFAULT_FRAME_SAMPLES {
            full_scale.extend_from_slice(&i16::MAX.to_le_bytes());
        }
        let loud = compute_pcm_s16le_level(&full_scale, false);
        assert!(loud.rms_db > -0.1 && loud.rms_db <= 0.0);
        assert!(loud.normalized > 0.99);
        assert!(loud.clipping);
        assert!(!loud.muted);

        // -20 dBFS constant amplitude (~0.1 * 32768 = 3277)
        let mut minus_20 = Vec::with_capacity(DEFAULT_FRAME_BYTES);
        for _ in 0..DEFAULT_FRAME_SAMPLES {
            minus_20.extend_from_slice(&3277_i16.to_le_bytes());
        }
        let mid = compute_pcm_s16le_level(&minus_20, false);
        assert!(
            (mid.rms_db - (-20.0)).abs() < 0.2,
            "expected ~-20 dBFS, got {}",
            mid.rms_db
        );
        assert!(!mid.clipping);

        // Muted flag overrides non-zero samples
        let muted = compute_pcm_s16le_level(&full_scale, true);
        assert_eq!(muted.rms_db, DB_FLOOR);
        assert_eq!(muted.normalized, 0.0);
        assert!(muted.muted);
        assert!(!muted.clipping);
    }

    #[test]
    fn audio_worker_forwards_pcm_and_zeros_when_muted() {
        let capture = SharedAudioCapture::default();
        let mock_sink = MockAudioSink {
            capture: capture.clone(),
        };

        let running = Arc::new(AtomicBool::new(true));
        let enabled = Arc::new(AtomicBool::new(true));
        let muted = Arc::new(AtomicBool::new(false));
        let (sender, rx) = AudioWorkerSender::new_bounded(enabled.clone(), muted.clone());
        let (level_tx, level_rx) = crossbeam_channel::bounded(16);

        let handle = spawn_audio_worker(
            rx,
            Some(Box::new(mock_sink)),
            Some(level_tx),
            running.clone(),
            enabled,
            muted,
        )
        .expect("spawn audio worker");

        sender
            .push_config(AudioConfigPayload {
                sample_rate: 48_000,
                channels: 1,
                codec: "pcm_s16le".to_owned(),
            })
            .expect("push config");

        let mut tone_frame = Vec::with_capacity(DEFAULT_FRAME_BYTES);
        for i in 0..DEFAULT_FRAME_SAMPLES {
            let val: i16 = if i % 2 == 0 { 4_000 } else { -4_000 };
            tone_frame.extend_from_slice(&val.to_le_bytes());
        }

        assert!(
            sender
                .push_frame(1_000, Bytes::from(tone_frame.clone()))
                .expect("push tone frame")
        );

        // Wait until first frame is written
        let first_level = level_rx
            .recv_timeout(Duration::from_secs(1))
            .expect("receive unmuted level");
        assert!(!first_level.muted);
        assert!(first_level.rms_db > -30.0);

        // Mute -> next frame must write 1920 zero bytes
        sender.set_state(true, true);
        assert!(
            sender
                .push_frame(21_000, Bytes::from(tone_frame.clone()))
                .expect("push muted frame")
        );

        // Disable forwarding -> third frame must NOT be written at all
        std::thread::sleep(Duration::from_millis(30));
        sender.set_state(false, false);
        assert!(
            sender
                .push_frame(41_000, Bytes::from(tone_frame))
                .expect("push disabled frame")
        );

        std::thread::sleep(Duration::from_millis(30));
        running.store(false, Ordering::Relaxed);
        sender.stop();
        handle.join().expect("join audio worker");

        let written = capture.samples.lock().expect("lock written");
        assert_eq!(written.len(), DEFAULT_FRAME_BYTES * 2);
        assert!(written[..DEFAULT_FRAME_BYTES].iter().any(|&b| b != 0));
        assert!(written[DEFAULT_FRAME_BYTES..].iter().all(|&b| b == 0));

        let formats = capture.formats.lock().expect("lock formats");
        assert_eq!(formats.as_slice(), &[(48_000, 1), (48_000, 1)]);
    }

    #[test]
    fn audio_worker_ignores_unsupported_codec_gracefully() {
        let capture = SharedAudioCapture::default();
        let mock_sink = MockAudioSink {
            capture: capture.clone(),
        };

        let running = Arc::new(AtomicBool::new(true));
        let enabled = Arc::new(AtomicBool::new(true));
        let muted = Arc::new(AtomicBool::new(false));
        let (sender, rx) = AudioWorkerSender::new_bounded(enabled.clone(), muted.clone());

        let handle = spawn_audio_worker(
            rx,
            Some(Box::new(mock_sink)),
            None,
            running.clone(),
            enabled,
            muted,
        )
        .expect("spawn audio worker");

        sender
            .push_config(AudioConfigPayload {
                sample_rate: 48_000,
                channels: 1,
                codec: "unknown_codec".to_owned(),
            })
            .expect("push unsupported config");

        assert!(
            sender
                .push_frame(1_000, Bytes::from_static(&[0x12, 0x34, 0x56, 0x78]))
                .expect("push frame")
        );

        std::thread::sleep(Duration::from_millis(30));
        running.store(false, Ordering::Relaxed);
        sender.stop();
        handle.join().expect("join audio worker");

        let written = capture.samples.lock().expect("lock samples");
        assert!(
            written.is_empty(),
            "unsupported codec frames must not write raw bytes to sink"
        );
    }
}
