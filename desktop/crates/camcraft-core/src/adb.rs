//! ADB (Android Debug Bridge) tunnel management for CamCraft.
//!
//! Provides utilities to verify ADB installation, discover connected Android devices,
//! and configure local TCP port forwarding (`adb forward tcp:9876 tcp:9876`).

use std::process::Command;
use thiserror::Error;
use tracing::{debug, info, warn};

/// Errors that can occur during ADB operations.
#[derive(Debug, Error)]
pub enum AdbError {
    /// The `adb` executable was not found on the system PATH.
    #[error(
        "ADB binary not found in PATH: {0}. Please ensure Android platform-tools or android-tools is installed"
    )]
    NotFound(#[source] std::io::Error),

    /// ADB command execution failed with non-zero exit code or stderr output.
    #[error("ADB command '{command}' failed with exit code {code:?}: {stderr}")]
    ExecutionFailed {
        command: String,
        code: Option<i32>,
        stderr: String,
    },

    /// No connected Android devices were detected.
    #[error(
        "No Android devices detected via ADB. Please connect your device via USB and enable USB Debugging"
    )]
    NoDevicesFound,

    /// Device is connected but waiting for USB debugging authorization on the phone screen.
    #[error(
        "Android device '{serial}' is unauthorized. Please accept the 'Allow USB debugging' prompt on your device"
    )]
    DeviceUnauthorized { serial: String },

    /// Multiple Android devices are attached but no target serial was specified.
    #[error(
        "Multiple Android devices detected ({count} devices). Please specify a target device serial"
    )]
    MultipleDevices { count: usize },

    /// General I/O error when spawning process.
    #[error("I/O error during ADB operation: {0}")]
    Io(#[from] std::io::Error),
}

/// Connection state of an Android device as reported by `adb devices`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AdbDeviceState {
    /// Authorized and ready for communication (`device`).
    Device,
    /// Connected but awaiting RSA fingerprint confirmation on phone (`unauthorized`).
    Unauthorized,
    /// Device is powered down, disconnected, or non-responsive (`offline`).
    Offline,
    /// Device is transitioning connection state (`authorizing` or `connecting`).
    Connecting,
    /// Other state string from ADB.
    Other(String),
}

impl AdbDeviceState {
    /// Parses the status string returned by `adb devices`.
    #[must_use]
    pub fn parse(s: &str) -> Self {
        match s.trim() {
            "device" => Self::Device,
            "unauthorized" => Self::Unauthorized,
            "offline" => Self::Offline,
            "authorizing" | "connecting" => Self::Connecting,
            other => Self::Other(other.to_string()),
        }
    }

    /// Returns `true` if the device is fully authorized and ready.
    #[must_use]
    pub const fn is_ready(&self) -> bool {
        matches!(self, Self::Device)
    }
}

/// Represents an attached Android device discovered via `adb devices`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdbDevice {
    /// Device serial number (or network address like `192.168.1.50:5555`).
    pub serial: String,
    /// Current connection authorization state.
    pub state: AdbDeviceState,
    /// Optional device model/product metadata if provided by `adb devices -l`.
    pub model: Option<String>,
}

/// Parses the standard text output of `adb devices -l` or `adb devices`.
pub fn parse_adb_devices_output(output: &str) -> Vec<AdbDevice> {
    let mut devices = Vec::new();

    for line in output.lines() {
        let trimmed = line.trim();
        // Skip header ("List of devices attached") and blank lines
        if trimmed.is_empty()
            || trimmed.starts_with("List of devices attached")
            || trimmed.starts_with('*')
        {
            continue;
        }

        let mut parts = trimmed.split_whitespace();
        let Some(serial) = parts.next() else {
            continue;
        };
        let Some(state_str) = parts.next() else {
            continue;
        };

        let state = AdbDeviceState::parse(state_str);

        // Attempt to find model metadata (e.g. `model:Pixel_8_Pro`)
        let mut model = None;
        for part in parts {
            if let Some(m) = part.strip_prefix("model:") {
                model = Some(m.replace('_', " "));
                break;
            }
        }

        devices.push(AdbDevice {
            serial: serial.to_string(),
            state,
            model,
        });
    }

    devices
}

/// Checks if the `adb` executable is installed and reachable in the PATH.
///
/// Returns the ADB version string on success.
pub fn check_adb_available() -> Result<String, AdbError> {
    debug!("Checking ADB availability in PATH");
    let output = Command::new("adb")
        .arg("version")
        .output()
        .map_err(AdbError::NotFound)?;

    if !output.status.success() {
        return Err(AdbError::ExecutionFailed {
            command: "adb version".to_string(),
            code: output.status.code(),
            stderr: String::from_utf8_lossy(&output.stderr).to_string(),
        });
    }

    let version_str = String::from_utf8_lossy(&output.stdout).trim().to_string();
    debug!(version = %version_str, "ADB binary confirmed available");
    Ok(version_str)
}

/// Lists all currently connected Android devices and their authorization states.
pub fn list_devices() -> Result<Vec<AdbDevice>, AdbError> {
    debug!("Querying connected devices via `adb devices -l`");
    let output = Command::new("adb")
        .args(["devices", "-l"])
        .output()
        .map_err(AdbError::NotFound)?;

    if !output.status.success() {
        return Err(AdbError::ExecutionFailed {
            command: "adb devices -l".to_string(),
            code: output.status.code(),
            stderr: String::from_utf8_lossy(&output.stderr).to_string(),
        });
    }

    let stdout = String::from_utf8_lossy(&output.stdout);
    let devices = parse_adb_devices_output(&stdout);
    info!(count = devices.len(), "Discovered ADB devices");
    Ok(devices)
}

/// Sets up ADB port forwarding from `127.0.0.1:host_port` to device `device_port`.
///
/// If `serial` is `None`:
/// - Validates that exactly one authorized Android device is connected.
/// - If no device is found, returns [`AdbError::NoDevicesFound`].
/// - If the device is unauthorized, returns [`AdbError::DeviceUnauthorized`].
/// - If multiple devices are found, returns [`AdbError::MultipleDevices`].
pub fn setup_adb_forward(
    serial: Option<&str>,
    host_port: u16,
    device_port: u16,
) -> Result<AdbDevice, AdbError> {
    let devices = list_devices()?;

    let target_device = match serial {
        Some(target_serial) => devices
            .into_iter()
            .find(|d| d.serial == target_serial)
            .ok_or_else(|| AdbError::ExecutionFailed {
                command: format!("adb -s {target_serial} forward"),
                code: None,
                stderr: format!("Device with serial '{target_serial}' not found"),
            })?,
        None => {
            if devices.is_empty() {
                return Err(AdbError::NoDevicesFound);
            }
            if devices.len() > 1 {
                return Err(AdbError::MultipleDevices {
                    count: devices.len(),
                });
            }
            let first = &devices[0];
            if !first.state.is_ready() {
                if first.state == AdbDeviceState::Unauthorized {
                    return Err(AdbError::DeviceUnauthorized {
                        serial: first.serial.clone(),
                    });
                }
                return Err(AdbError::ExecutionFailed {
                    command: "adb devices".to_string(),
                    code: None,
                    stderr: format!(
                        "Device '{}' is in non-ready state: {:?}",
                        first.serial, first.state
                    ),
                });
            }
            let Some(device) = devices.into_iter().next() else {
                return Err(AdbError::NoDevicesFound);
            };
            device
        }
    };

    if !target_device.state.is_ready() {
        if target_device.state == AdbDeviceState::Unauthorized {
            return Err(AdbError::DeviceUnauthorized {
                serial: target_device.serial,
            });
        }
        return Err(AdbError::ExecutionFailed {
            command: "adb devices".to_string(),
            code: None,
            stderr: format!(
                "Device '{}' is in non-ready state: {:?}",
                target_device.serial, target_device.state
            ),
        });
    }

    let forward_arg = format!("tcp:{host_port}");
    let device_arg = format!("tcp:{device_port}");

    let mut cmd = Command::new("adb");
    cmd.args([
        "-s",
        &target_device.serial,
        "forward",
        &forward_arg,
        &device_arg,
    ]);

    debug!(
        serial = %target_device.serial,
        host_port,
        device_port,
        "Executing ADB port forwarding"
    );

    let output = cmd.output().map_err(AdbError::NotFound)?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).to_string();
        return Err(AdbError::ExecutionFailed {
            command: format!(
                "adb -s {} forward {} {}",
                target_device.serial, forward_arg, device_arg
            ),
            code: output.status.code(),
            stderr,
        });
    }

    info!(
        serial = %target_device.serial,
        model = ?target_device.model,
        host_port,
        device_port,
        "ADB port forwarding established successfully"
    );

    Ok(target_device)
}

/// Removes a previously configured ADB port forward on `host_port`.
pub fn remove_adb_forward(serial: Option<&str>, host_port: u16) -> Result<(), AdbError> {
    let mut cmd = Command::new("adb");
    if let Some(s) = serial {
        cmd.args(["-s", s]);
    }
    let forward_arg = format!("tcp:{host_port}");
    cmd.args(["forward", "--remove", &forward_arg]);

    let output = cmd.output().map_err(AdbError::NotFound)?;
    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).to_string();
        warn!(host_port, %stderr, "Failed or already removed ADB forward");
    } else {
        info!(host_port, "Removed ADB port forwarding");
    }

    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_adb_devices_output_single_device() {
        let sample = "List of devices attached\n\
                      emulator-5554          device product:sdk_gphone64_x86_64 model:sdk_gphone64_x86_64 device:emu64x transport_id:1\n";
        let devices = parse_adb_devices_output(sample);
        assert_eq!(devices.len(), 1);
        assert_eq!(devices[0].serial, "emulator-5554");
        assert_eq!(devices[0].state, AdbDeviceState::Device);
        assert_eq!(devices[0].model.as_deref(), Some("sdk gphone64 x86 64"));
    }

    #[test]
    fn test_parse_adb_devices_output_unauthorized_and_offline() {
        let sample = "* daemon not running; starting now at tcp:5037\n\
                      * daemon started successfully\n\
                      List of devices attached\n\
                      R5CT1234567            unauthorized usb:1-1\n\
                      192.168.1.100:5555     offline\n\
                      988954474b344754       device model:Pixel_8_Pro\n";
        let devices = parse_adb_devices_output(sample);
        assert_eq!(devices.len(), 3);
        assert_eq!(devices[0].serial, "R5CT1234567");
        assert_eq!(devices[0].state, AdbDeviceState::Unauthorized);
        assert_eq!(devices[1].serial, "192.168.1.100:5555");
        assert_eq!(devices[1].state, AdbDeviceState::Offline);
        assert_eq!(devices[2].serial, "988954474b344754");
        assert_eq!(devices[2].state, AdbDeviceState::Device);
        assert_eq!(devices[2].model.as_deref(), Some("Pixel 8 Pro"));
    }

    #[test]
    fn test_parse_adb_devices_empty() {
        let sample = "List of devices attached\n\n";
        let devices = parse_adb_devices_output(sample);
        assert!(devices.is_empty());
    }
}
