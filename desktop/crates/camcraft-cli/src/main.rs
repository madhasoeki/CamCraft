//! Headless CLI entrypoint for CamCraft desktop receiver.

use anyhow::Result;
use clap::{Parser, Subcommand};
use tracing::info;

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
    /// Connect to an Android CamCraft sender over USB ADB or Wi-Fi LAN.
    Connect {
        /// Connect via USB ADB port forwarding (127.0.0.1:9876).
        #[arg(long)]
        usb: bool,

        /// Connect directly to a Wi-Fi IP address.
        #[arg(long)]
        wifi: Option<String>,

        /// Target v4l2loopback device path.
        #[arg(long, default_value = "/dev/video10")]
        vcam: String,

        /// Target TCP port.
        #[arg(long, default_value_t = camcraft_protocol::DEFAULT_PORT)]
        port: u16,
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
            wifi,
            vcam,
            port,
        } => {
            let mode = if usb {
                format!("USB ({})", camcraft_core::net::usb_loopback_addr())
            } else if let Some(ip) = wifi {
                format!("Wi-Fi ({ip}:{port})")
            } else {
                format!("TCP (127.0.0.1:{port})")
            };
            info!(%mode, %vcam, "Stage 0 CLI stub: transport will be wired in Stage 2");
        }
        Command::Status => {
            info!(
                protocol_version = camcraft_protocol::PROTOCOL_VERSION,
                default_port = camcraft_protocol::DEFAULT_PORT,
                "CamCraft CLI ready (Stage 0 scaffolding)"
            );
        }
    }

    Ok(())
}
