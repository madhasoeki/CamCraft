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
    Connect {
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

        /// Target v4l2loopback device path (used for virtual camera in Stage 4+).
        #[arg(long, default_value = "/dev/video10")]
        vcam: String,

        /// Target TCP port.
        #[arg(long, default_value_t = camcraft_protocol::DEFAULT_PORT)]
        port: u16,

        /// Optional file path to dump raw received H.264 stream (testable with `ffplay <path.h264>`).
        #[arg(long = "dump-h264")]
        dump_h264: Option<PathBuf>,
    },

    /// Print compiled CamCraft protocol and core configuration.
    Status,
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
        Command::Connect {
            usb,
            host,
            wifi,
            serial,
            vcam,
            port,
            dump_h264,
        } => {
            run_connect(usb, host, wifi, serial, vcam, port, dump_h264).await?;
        }
        Command::Status => {
            info!(
                protocol_version = camcraft_protocol::PROTOCOL_VERSION,
                default_port = camcraft_protocol::DEFAULT_PORT,
                "CamCraft CLI receiver ready (Stage 2: USB & Synthetic Stream)"
            );
        }
    }

    Ok(())
}

/// Executes the connection workflow: setup ADB forward if needed, connect to sender,
/// and display incoming stream stats.
async fn run_connect(
    use_usb: bool,
    host: Option<String>,
    wifi: Option<String>,
    serial: Option<String>,
    vcam: String,
    port: u16,
    dump_h264: Option<PathBuf>,
) -> Result<()> {
    let is_usb = use_usb || (host.is_none() && wifi.is_none());

    let endpoint = if is_usb {
        info!("Setting up USB tunnel via ADB port forwarding (port {port})...");
        let device = adb::setup_adb_forward(serial.as_deref(), port, port)
            .context("Failed to configure ADB port forwarding")?;
        info!(
            serial = %device.serial,
            model = ?device.model,
            "Android USB tunnel established successfully"
        );
        format!("127.0.0.1:{port}")
    } else {
        let raw_addr = host.or(wifi).unwrap_or_else(|| "127.0.0.1".to_string());
        if raw_addr.contains(':') {
            raw_addr
        } else {
            format!("{raw_addr}:{port}")
        }
    };

    info!(%endpoint, %vcam, "Connecting to CamCraft stream sender");

    let config = ClientConfig::new(&endpoint);
    let (event_tx, mut event_rx) = mpsc::channel::<ClientEvent>(64);
    let shutdown = Arc::new(AtomicBool::new(false));

    let mut dump_file =
        if let Some(ref path) = dump_h264 {
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
            ClientEvent::Hello(hello) => {
                print_device_banner(&hello);
            }
            ClientEvent::Stats(stats) => {
                print_stats(&stats);
            }
            ClientEvent::VideoConfig {
                payload_len,
                payload,
                ..
            } => {
                info!(payload_len, "Received VIDEO_CONFIG SPS/PPS parameters");
                if let Some(ref mut file) = dump_file {
                    file.write_all(&payload)
                        .await
                        .context("Failed to write VIDEO_CONFIG to dump file")?;
                    dumped_bytes += payload.len() as u64;
                }
            }
            ClientEvent::VideoFrame { payload, .. } => {
                if let Some(ref mut file) = dump_file {
                    file.write_all(&payload)
                        .await
                        .context("Failed to write VIDEO_FRAME to dump file")?;
                    dumped_bytes += payload.len() as u64;
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

    if let Some(mut file) = dump_file {
        file.flush()
            .await
            .context("Failed to flush H.264 dump file")?;
        info!(
            dumped_bytes,
            path = %dump_h264.as_ref().unwrap().display(),
            "H.264 stream dumped successfully (playable with `ffplay <path>`)"
        );
    }

    if is_usb {
        let _ = adb::remove_adb_forward(serial.as_deref(), port);
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
