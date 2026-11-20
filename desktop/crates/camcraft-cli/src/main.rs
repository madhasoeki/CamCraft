//! Headless CLI entrypoint for CamCraft desktop receiver.
//!
//! Provides the `connect` sub-command for USB ADB and TCP Wi-Fi connections,
//! displaying discovered device cameras, real-time FPS, bitrate, and RTT latency.

use std::path::PathBuf;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use anyhow::{Context, Result};
use clap::{Parser, Subcommand};
use tokio::io::AsyncWriteExt;
use tokio::sync::mpsc;
use tracing::{info, warn};

use camcraft_core::adb;
use camcraft_core::client::{self, ClientConfig, ClientEvent, StreamStats};
use camcraft_core::decoder::{DecoderSender, spawn_decoder_worker};
use camcraft_core::vcam::{V4l2LoopbackSink, VirtualCameraSink, find_camcraft_v4l2_device};
use camcraft_protocol::ControlHello;

#[derive(Debug, Parser)]
#[command(
    name = "camcraft-cli",
    version,
    about = "CamCraft headless desktop receiver for Fedora Linux"
)]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Debug, Subcommand)]
enum Command {
    /// Connect to an Android CamCraft sender over USB ADB tunnel or TCP network.
    Connect(ConnectArgs),

    /// Print compiled CamCraft protocol and core configuration.
    Status,
}

/// Arguments for the `connect` subcommand.
#[derive(Debug, Parser)]
struct ConnectArgs {
    /// Connect via USB ADB port forwarding (127.0.0.1:9876).
    #[arg(long)]
    usb: bool,

    /// Target host IP or IP:PORT (e.g. 192.168.1.100 or 127.0.0.1:9876).
    #[arg(long)]
    host: Option<String>,

    /// Connect directly to a Wi-Fi IP address (alias for --host).
    #[arg(long)]
    wifi: Option<String>,

    /// Specific Android device serial for ADB (optional when multiple devices are connected).
    #[arg(long)]
    serial: Option<String>,

    /// Target v4l2loopback device path (e.g. /dev/video10 or 'auto').
    #[arg(long, default_value = "/dev/video10")]
    vcam: String,

    /// Disable virtual camera output to v4l2loopback device.
    #[arg(long)]
    no_vcam: bool,

    /// Target TCP port.
    #[arg(long, default_value_t = camcraft_protocol::DEFAULT_PORT)]
    port: u16,

    /// Optional file path to dump raw received H.264 stream (testable with `ffplay <path.h264>`).
    #[arg(long = "dump-h264")]
    dump_h264: Option<PathBuf>,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let cli = Cli::parse();

    match cli.command {
        Command::Connect(args) => {
            run_connect(args).await?;
        }
        Command::Status => {
            info!(
                protocol_version = camcraft_protocol::PROTOCOL_VERSION,
                default_port = camcraft_protocol::DEFAULT_PORT,
                "CamCraft CLI receiver ready (Stage 4: H.264 Decode & Virtual Camera)"
            );
        }
    }

    Ok(())
}

/// Executes the connection workflow: setup ADB forward if needed, connect to sender,
/// and display incoming stream stats.
async fn run_connect(args: ConnectArgs) -> Result<()> {
    let is_usb = args.usb || (args.host.is_none() && args.wifi.is_none());

    let endpoint = if is_usb {
        info!(
            "Setting up USB tunnel via ADB port forwarding (port {})...",
            args.port
        );
        let device = adb::setup_adb_forward(args.serial.as_deref(), args.port, args.port)
            .context("Failed to configure ADB port forwarding")?;
        info!(
            serial = %device.serial,
            model = ?device.model,
            "Android USB tunnel established successfully"
        );
        format!("127.0.0.1:{}", args.port)
    } else {
        let raw_addr = args
            .host
            .or(args.wifi)
            .unwrap_or_else(|| "127.0.0.1".to_string());
        if raw_addr.contains(':') {
            raw_addr
        } else {
            format!("{raw_addr}:{}", args.port)
        }
    };

    info!(%endpoint, vcam = %args.vcam, "Connecting to CamCraft stream sender");

    // Initialize v4l2loopback virtual camera sink (unless disabled)
    let sink: Option<Box<dyn VirtualCameraSink>> = if args.no_vcam {
        info!("Virtual camera output disabled via --no-vcam");
        None
    } else {
        let vcam_path = if args.vcam == "auto" {
            find_camcraft_v4l2_device()
        } else {
            PathBuf::from(&args.vcam)
        };

        let mut vcam_sink = V4l2LoopbackSink::new(vcam_path.clone());
        match vcam_sink.open() {
            Ok(()) => {
                info!(
                    device = %vcam_path.display(),
                    "Initialized v4l2loopback virtual camera sink"
                );
                // Emit initial standby frame (1920x1080) matching default CamCraft stream resolution
                // so the device node is immediately queryable and active in OBS/Discord
                if let Err(e) = vcam_sink.write_standby_frame(1920, 1080) {
                    warn!(
                        error = %e,
                        "Failed to write initial standby frame to virtual camera"
                    );
                }
                Some(Box::new(vcam_sink) as Box<dyn VirtualCameraSink>)
            }
            Err(e) => {
                warn!(
                    device = %vcam_path.display(),
                    error = %e,
                    "Failed to open virtual camera device (v4l2loopback module might not be loaded); continuing in headless mode without virtual camera injection"
                );
                None
            }
        }
    };

    // Initialize H.264 decoder worker with bounded channel for smart frame dropping
    let (decoder_sender, decoder_rx) = DecoderSender::new_bounded();
    let decoder_running = Arc::new(AtomicBool::new(true));
    let decoder_handle =
        spawn_decoder_worker(decoder_rx, sink, None, false, decoder_running.clone())
            .context("Failed to spawn video decoder worker thread")?;

    let config = ClientConfig::new(&endpoint);
    let (event_tx, mut event_rx) = mpsc::channel::<ClientEvent>(64);
    let shutdown = Arc::new(AtomicBool::new(false));

    let mut dump_file =
        if let Some(ref path) = args.dump_h264 {
            info!(path = %path.display(), "Opening file to dump raw H.264 stream");
            Some(tokio::fs::File::create(path).await.with_context(|| {
                format!("Failed to create H.264 dump file at {}", path.display())
            })?)
        } else {
            None
        };
    let mut dumped_bytes: u64 = 0;

    // Spawn client network loop in background
    let client_shutdown = shutdown.clone();
    let client_handle =
        tokio::spawn(
            async move { client::run_client_loop(config, event_tx, client_shutdown).await },
        );

    // Handle Ctrl+C for clean shutdown
    let sig_shutdown = shutdown.clone();
    tokio::spawn(async move {
        if tokio::signal::ctrl_c().await.is_ok() {
            info!("Received Ctrl+C interrupt, shutting down connection...");
            sig_shutdown.store(true, Ordering::Relaxed);
        }
    });

    // Event consumption loop
    while let Some(event) = event_rx.recv().await {
        match event {
            ClientEvent::Connected(_) => {}
            ClientEvent::Hello(hello) => {
                print_device_banner(&hello);
            }
            ClientEvent::Stats(stats) => {
                print_stats(&stats);
            }
            ClientEvent::VideoConfig {
                payload_len,
                payload,
                header,
            } => {
                info!(payload_len, "Received VIDEO_CONFIG SPS/PPS parameters");
                if let Some(ref mut file) = dump_file {
                    file.write_all(&payload)
                        .await
                        .context("Failed to write VIDEO_CONFIG to dump file")?;
                    dumped_bytes += payload.len() as u64;
                }
                if let Err(e) = decoder_sender.push_config(header.pts_us, payload) {
                    warn!(error = %e, "Failed to push VIDEO_CONFIG to decoder worker");
                }
            }
            ClientEvent::VideoFrame {
                header, payload, ..
            } => {
                if let Some(ref mut file) = dump_file {
                    file.write_all(&payload)
                        .await
                        .context("Failed to write VIDEO_FRAME to dump file")?;
                    dumped_bytes += payload.len() as u64;
                }
                let is_keyframe = header.flags.is_keyframe();
                if let Err(e) = decoder_sender.push_frame(header.pts_us, is_keyframe, payload) {
                    warn!(error = %e, "Failed to push VIDEO_FRAME to decoder worker");
                }
            }
            ClientEvent::AudioConfig { .. } | ClientEvent::AudioFrame { .. } => {}
            ClientEvent::ControlAck(ack) => {
                if ack.ok {
                    info!("Camera control acknowledged by device");
                } else {
                    warn!(error = ?ack.error, "Camera control rejected by device");
                }
            }
            ClientEvent::Disconnected(reason) => {
                warn!(reason = %reason, "Disconnected from CamCraft sender");
                break;
            }
        }
    }

    shutdown.store(true, Ordering::Relaxed);
    let _ = client_handle.await;

    // Gracefully terminate decoder worker thread
    decoder_sender.stop();
    decoder_running.store(false, Ordering::Relaxed);
    if let Err(e) = decoder_handle.join() {
        warn!("Decoder worker thread join error: {:?}", e);
    }

    if let (Some(mut file), Some(path)) = (dump_file, args.dump_h264.as_ref()) {
        file.flush()
            .await
            .context("Failed to flush H.264 dump file")?;
        info!(
            dumped_bytes,
            path = %path.display(),
            "H.264 stream dumped successfully (playable with `ffplay <path>`)"
        );
    }

    if is_usb {
        let _ = adb::remove_adb_forward(args.serial.as_deref(), args.port);
    }

    info!("CamCraft CLI connection session ended");
    Ok(())
}

/// Formats and logs the device capability banner upon receiving `CONTROL_HELLO`.
fn print_device_banner(hello: &ControlHello) {
    println!("\n╔════════════════════════════════════════════════════════════════╗");
    println!("║                 CamCraft Android Connected                     ║");
    println!("╠════════════════════════════════════════════════════════════════╣");
    println!("  Device Name      : {}", hello.device_name);
    println!("  Protocol Version : v{}", hello.protocol_version);
    println!("  Available Cameras: {}", hello.cameras.len());
    for cam in &hello.cameras {
        println!("    ├─ [{}] {} (facing: {})", cam.id, cam.label, cam.facing);
        let res_strs: Vec<String> = cam
            .resolutions
            .iter()
            .map(|r| format!("{}x{}@{}fps", r.width, r.height, r.max_fps))
            .collect();
        println!("    │    Resolutions : {}", res_strs.join(", "));
        println!(
            "    │    Max Zoom    : {:.1}x | EV: [{:.1}, {:.1}] | Flash: {}",
            cam.max_zoom,
            cam.ev_range.0,
            cam.ev_range.1,
            if cam.has_flash { "Yes" } else { "No" }
        );
    }
    println!("  Active State:");
    println!(
        "    Camera {} | {}x{} @ {} FPS | Bitrate: {} kbps",
        hello.active_state.camera_id,
        hello.active_state.width,
        hello.active_state.height,
        hello.active_state.fps,
        hello.active_state.bitrate_kbps
    );
    println!(
        "    Zoom: {:.1}x | EV: {:.1} | Torch: {} | Audio: {}",
        hello.active_state.zoom_ratio,
        hello.active_state.exposure_ev,
        if hello.active_state.torch_on {
            "ON"
        } else {
            "OFF"
        },
        if hello.active_state.audio_enabled {
            "Enabled"
        } else {
            "Disabled"
        }
    );
    println!("╚════════════════════════════════════════════════════════════════╝\n");
}

/// Formats and logs the periodic 1-second streaming metrics.
fn print_stats(stats: &StreamStats) {
    let mb = stats.total_video_bytes as f64 / (1024.0 * 1024.0);
    println!(
        "[STATS] FPS: {:>5.1} | Bitrate: {:>6.2} Mbps | RTT: {:>5.2} ms | Total: {:>6} frames ({:.2} MB) | Keyframes: {}",
        stats.fps, stats.bitrate_mbps, stats.rtt_ms, stats.total_frames, mb, stats.keyframe_count
    );
}
