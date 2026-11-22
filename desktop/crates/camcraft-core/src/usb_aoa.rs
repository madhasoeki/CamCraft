//! Pure-Rust Linux `usbdevfs` Android Open Accessory (AOA v1) transport.
//!
//! Enables zero-ADB USB streaming between CamCraft Android and Linux desktops
//! by enumerating `/sys/bus/usb/devices`, performing the AOA v1 control handshake
//! (`AOA_GET_PROTOCOL`, `AOA_SEND_STRING`, `AOA_START_ACCESSORY`), claiming the
//! bulk interface via `/dev/bus/usb/BBB/DDD`, and bridging Bulk IN/OUT transfers
//! to an async [`tokio::net::UnixStream`] over two dedicated OS worker threads
//! with pre-allocated 16 KiB scratch buffers (zero per-transfer heap allocations).

use std::fs::{self, File, OpenOptions};
use std::io::{ErrorKind, Read, Write};
use std::os::fd::{AsRawFd, RawFd};
use std::os::unix::fs::OpenOptionsExt;
use std::os::unix::net::UnixStream as StdUnixStream;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::thread;
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use thiserror::Error;
use tokio::net::UnixStream as TokioUnixStream;
use tracing::{debug, info, warn};

/// Shared AOA v1 manufacturer string (must match Android `accessory_filter.xml`).
pub const AOA_MANUFACTURER: &str = "ArtCraft";
/// Shared AOA v1 model string (must match Android `accessory_filter.xml`).
pub const AOA_MODEL: &str = "CamCraft";
/// Human-readable AOA v1 accessory description.
pub const AOA_DESCRIPTION: &str = "CamCraft Low-Latency USB Webcam";
/// Shared AOA v1 accessory version string (must match Android `accessory_filter.xml`).
pub const AOA_VERSION: &str = "1.0";
/// Project URI sent during AOA v1 handshake.
pub const AOA_URI: &str = "https://github.com/madhasoeki/CamCraft";
/// Accessory serial string sent during AOA v1 handshake.
pub const AOA_SERIAL: &str = "CCFT-AOA-01";

/// Google USB Vendor ID assigned to Android devices in AOA mode.
pub const AOA_GOOGLE_VID: u16 = 0x18D1;
/// USB Product IDs exposing an AOA v1 bulk accessory interface.
pub const AOA_PIDS: &[u16] = &[0x2D00, 0x2D01, 0x2D04, 0x2D05];

/// AOA v1 control request (`0xC0`): query supported AOA protocol version.
pub const AOA_GET_PROTOCOL: u8 = 51;
/// AOA v1 control request (`0x40`): send accessory identification string (`wIndex` 0..=5).
pub const AOA_SEND_STRING: u8 = 52;
/// AOA v1 control request (`0x40`): switch device into Android Open Accessory mode.
pub const AOA_START_ACCESSORY: u8 = 53;
/// Maximum bulk chunk size matching Android `f_accessory` / `AoaChunkedOutputStream` (16 KiB).
pub const AOA_MAX_BULK_CHUNK: usize = 16_384;

/// Filename for the CamCraft Linux udev rules file.
pub const UDEV_RULES_FILENAME: &str = "70-camcraft-aoa.rules";

/// Linux udev rules content granting non-root `uaccess` permissions to Google AOA
/// and major Android OEM USB vendor IDs.
pub const UDEV_RULES_CONTENT: &str = "\
# CamCraft Android Open Accessory (AOA v1) USB permissions
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"18d1\", ATTR{idProduct}==\"2d00\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"18d1\", ATTR{idProduct}==\"2d01\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"18d1\", ATTR{idProduct}==\"2d04\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"18d1\", ATTR{idProduct}==\"2d05\", MODE=\"0660\", TAG+=\"uaccess\"

# Major Android OEM Vendor IDs (pre-AOA handshake)
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"18d1\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"04e8\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"2717\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"2a70\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"22d9\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"22b8\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"0fce\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"12d1\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"0b05\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"2970\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"2d95\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"17ef\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"1004\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"0bb4\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"19d2\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"2ae5\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"0e8d\", MODE=\"0660\", TAG+=\"uaccess\"
SUBSYSTEM==\"usb\", ATTR{idVendor}==\"05c6\", MODE=\"0660\", TAG+=\"uaccess\"
";

const LINUX_ROOT_HUB_VID: u16 = 0x1d6b;
const USB_CLASS_HUB: u8 = 9;
const CTRL_REQ_TYPE_IN_VENDOR: u8 = 0xC0;
const CTRL_REQ_TYPE_OUT_VENDOR: u8 = 0x40;
const CTRL_TIMEOUT_MS: u32 = 1_000;
const BULK_IN_TIMEOUT_MS: u32 = 250;
const BULK_OUT_TIMEOUT_MS: u32 = 250;
const UNIX_IO_TIMEOUT: Duration = Duration::from_millis(250);
const REENUMERATION_TIMEOUT: Duration = Duration::from_secs(5);
const REENUMERATION_POLL_INTERVAL: Duration = Duration::from_millis(150);
const REENUMERATION_SHUTDOWN_SLICE: Duration = Duration::from_millis(50);
const MAX_DESCRIPTOR_READ_BYTES: u64 = 128 * 1024;

/// Known Android phone/SoC USB Vendor IDs prioritized during candidate probing.
pub const ANDROID_VENDOR_IDS: &[u16] = &[
    0x18d1, 0x04e8, 0x2717, 0x2a70, 0x22d9, 0x22b8, 0x0fce, 0x12d1, 0x0b05, 0x2970, 0x2d95, 0x17ef,
    0x1004, 0x0bb4, 0x19d2, 0x2ae5, 0x0e8d, 0x05c6,
];

const UDEV_RULES_DIRS: &[&str] = &[
    "/etc/udev/rules.d",
    "/usr/lib/udev/rules.d",
    "/lib/udev/rules.d",
];

static TEMP_FILE_SEQ: AtomicU64 = AtomicU64::new(0);

/// Errors encountered during USB AOA enumeration, handshake, permission checks, or bulk I/O.
#[derive(Debug, Error)]
pub enum UsbAoaError {
    /// No AOA-capable Android device was detected on USB.
    #[error(
        "no AOA-capable Android device found over USB; plug in your phone and unlock the screen"
    )]
    NoDeviceFound,

    /// Opening the `/dev/bus/usb/BBB/DDD` device node failed with `EACCES` or `EPERM`.
    #[error(
        "USB permission denied on '{}'; install udev rules ({UDEV_RULES_FILENAME}) to allow non-root access",
        .0.display()
    )]
    PermissionDenied(PathBuf),

    /// Device accepted `AOA_START_ACCESSORY`, but did not re-enumerate as an AOA device in time.
    #[error(
        "device entered AOA mode, but no AOA bulk accessory re-enumerated within {:.1}s",
        .0.as_secs_f64()
    )]
    ReenumerationTimeout(Duration),

    /// Claiming the USB accessory bulk interface failed.
    #[error("failed to claim USB interface {interface}: {source}")]
    ClaimInterface {
        interface: u8,
        #[source]
        source: std::io::Error,
    },

    /// USB accessory descriptor did not expose a Bulk IN + Bulk OUT endpoint pair.
    #[error("USB AOA device {vid:04x}:{pid:04x} exposes no Bulk IN/OUT endpoint pair")]
    MissingBulkEndpoints { vid: u16, pid: u16 },

    /// USB device disconnected or halted permanently.
    #[error("USB AOA device disconnected")]
    Disconnected,

    /// Installing udev rules via `pkexec` failed.
    #[error("failed to install udev rules via pkexec: {0}")]
    UdevInstall(String),

    /// General USB/socket I/O failure.
    #[error("USB AOA I/O error: {0}")]
    Io(#[from] std::io::Error),
}

// ---------------------------------------------------------------------------
// Linux `<linux/usbdevice_fs.h>` ABI structs and `<asm-generic/ioctl.h>` codes
// ---------------------------------------------------------------------------

#[repr(C)]
#[derive(Clone, Copy)]
struct UsbdevfsCtrlTransfer {
    b_request_type: u8,
    b_request: u8,
    w_value: u16,
    w_index: u16,
    w_length: u16,
    timeout: u32,
    data: *mut libc::c_void,
}

#[repr(C)]
struct UsbdevfsBulkTransfer {
    ep: u32,
    len: u32,
    timeout: u32,
    data: *mut libc::c_void,
}

#[repr(C)]
struct UsbdevfsUrb {
    urb_type: u8,
    endpoint: u8,
    status: i32,
    flags: u32,
    buffer: *mut libc::c_void,
    buffer_length: i32,
    actual_length: i32,
    start_frame: i32,
    number_of_packets: u32,
    error_count: i32,
    signr: u32,
    usercontext: *mut libc::c_void,
}

impl UsbdevfsUrb {
    fn zeroed() -> Self {
        Self {
            urb_type: 0,
            endpoint: 0,
            status: 0,
            flags: 0,
            buffer: std::ptr::null_mut(),
            buffer_length: 0,
            actual_length: 0,
            start_frame: 0,
            number_of_packets: 0,
            error_count: 0,
            signr: 0,
            usercontext: std::ptr::null_mut(),
        }
    }

    fn reset_bulk_in(&mut self, ep_in: u8, buf: &mut [u8]) {
        self.urb_type = USBDEVFS_URB_TYPE_BULK;
        self.endpoint = ep_in;
        self.status = 0;
        self.flags = 0;
        self.buffer = if buf.is_empty() {
            std::ptr::null_mut()
        } else {
            buf.as_mut_ptr().cast::<libc::c_void>()
        };
        self.buffer_length = i32::try_from(buf.len()).unwrap_or(i32::MAX);
        self.actual_length = 0;
        self.start_frame = 0;
        self.number_of_packets = 0;
        self.error_count = 0;
        self.signr = 0;
        self.usercontext = std::ptr::null_mut();
    }
}

#[repr(C)]
struct UsbdevfsDisconnectClaim {
    interface: u32,
    flags: u32,
    driver: [u8; 256],
}

const USBDEVFS_URB_TYPE_BULK: u8 = 3;

const IOC_WRITE: u32 = 1;
const IOC_READ: u32 = 2;
const IOC_NRSHIFT: u32 = 0;
const IOC_TYPESHIFT: u32 = 8;
const IOC_SIZESHIFT: u32 = 16;
const IOC_DIRSHIFT: u32 = 30;

const fn ioc(dir: u32, ty: u8, nr: u8, size: usize) -> libc::Ioctl {
    let raw = (dir << IOC_DIRSHIFT)
        | ((ty as u32) << IOC_TYPESHIFT)
        | ((nr as u32) << IOC_NRSHIFT)
        | ((size as u32) << IOC_SIZESHIFT);
    raw as libc::Ioctl
}

const fn io_ioctl(ty: u8, nr: u8) -> libc::Ioctl {
    ioc(0, ty, nr, 0)
}

const fn ior<T>(ty: u8, nr: u8) -> libc::Ioctl {
    ioc(IOC_READ, ty, nr, std::mem::size_of::<T>())
}

const fn iow<T>(ty: u8, nr: u8) -> libc::Ioctl {
    ioc(IOC_WRITE, ty, nr, std::mem::size_of::<T>())
}

const fn iowr<T>(ty: u8, nr: u8) -> libc::Ioctl {
    ioc(IOC_READ | IOC_WRITE, ty, nr, std::mem::size_of::<T>())
}

const USBDEVFS_CONTROL: libc::Ioctl = iowr::<UsbdevfsCtrlTransfer>(b'U', 0);
const USBDEVFS_BULK: libc::Ioctl = iowr::<UsbdevfsBulkTransfer>(b'U', 2);
const USBDEVFS_SUBMITURB: libc::Ioctl = ior::<UsbdevfsUrb>(b'U', 10);
const USBDEVFS_DISCARDURB: libc::Ioctl = io_ioctl(b'U', 11);
const USBDEVFS_REAPURB: libc::Ioctl = iow::<*mut UsbdevfsUrb>(b'U', 12);
const USBDEVFS_REAPURBNDELAY: libc::Ioctl = iow::<*mut UsbdevfsUrb>(b'U', 13);
const USBDEVFS_CLAIMINTERFACE: libc::Ioctl = ior::<u32>(b'U', 15);
const USBDEVFS_RELEASEINTERFACE: libc::Ioctl = ior::<u32>(b'U', 16);
const USBDEVFS_CLEAR_HALT: libc::Ioctl = ior::<u32>(b'U', 21);
const USBDEVFS_DISCONNECT_CLAIM: libc::Ioctl = ior::<UsbdevfsDisconnectClaim>(b'U', 27);

// ---------------------------------------------------------------------------
// USB Descriptor & Sysfs Enumeration
// ---------------------------------------------------------------------------

/// Bulk IN / Bulk OUT endpoints and interface number parsed from raw USB descriptors.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BulkEndpoints {
    pub interface: u8,
    pub ep_in: u8,
    pub ep_out: u8,
}

/// Metadata extracted from a `/sys/bus/usb/devices/<dev>` entry.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct UsbDeviceInfo {
    pub sysfs_name: String,
    pub vid: u16,
    pub pid: u16,
    pub busnum: u32,
    pub devnum: u32,
    pub device_class: u8,
    pub product: Option<String>,
    pub manufacturer: Option<String>,
    pub serial: Option<String>,
    pub descriptors: Vec<u8>,
}

impl UsbDeviceInfo {
    /// Returns the `/dev/bus/usb/BBB/DDD` character device node path for this USB device.
    #[must_use]
    pub fn dev_path(&self) -> PathBuf {
        PathBuf::from(format!(
            "/dev/bus/usb/{:03}/{:03}",
            self.busnum, self.devnum
        ))
    }
}

/// Returns `true` if `(vid, pid)` matches an Android device already in AOA v1 mode.
#[must_use]
pub const fn is_aoa_device(vid: u16, pid: u16) -> bool {
    if vid != AOA_GOOGLE_VID {
        return false;
    }
    let mut i = 0;
    while i < AOA_PIDS.len() {
        if AOA_PIDS[i] == pid {
            return true;
        }
        i += 1;
    }
    false
}

/// Returns `true` if `vid` is in the prioritized Android OEM/SoC vendor ID list.
#[must_use]
pub const fn is_known_android_vid(vid: u16) -> bool {
    let mut i = 0;
    while i < ANDROID_VENDOR_IDS.len() {
        if ANDROID_VENDOR_IDS[i] == vid {
            return true;
        }
        i += 1;
    }
    false
}

/// Walks concatenated USB TLV descriptors to locate the first interface providing
/// both a Bulk IN and a Bulk OUT endpoint.
#[must_use]
pub fn parse_bulk_endpoints(raw_descriptors: &[u8]) -> Option<BulkEndpoints> {
    let mut offset = 0usize;
    let mut current_interface = 0u8;
    let mut ep_in: Option<u8> = None;
    let mut ep_out: Option<u8> = None;

    while let Some(header) = raw_descriptors.get(offset..offset + 2) {
        let len = usize::from(header[0]);
        let desc_type = header[1];
        if len < 2 {
            break;
        }
        let Some(desc) = raw_descriptors.get(offset..offset + len) else {
            break;
        };

        if desc_type == 4 && desc.len() >= 9 {
            // Interface descriptor: track `bInterfaceNumber` and reset endpoint pairing
            // so endpoints from separate interfaces are never mixed.
            current_interface = desc[2];
            ep_in = None;
            ep_out = None;
        } else if desc_type == 5 && desc.len() >= 7 {
            let ep_addr = desc[2];
            let bm_attributes = desc[3];
            if (bm_attributes & 0x03) == 2 {
                if (ep_addr & 0x80) != 0 {
                    if ep_in.is_none() {
                        ep_in = Some(ep_addr);
                    }
                } else if ep_out.is_none() {
                    ep_out = Some(ep_addr);
                }

                if let (Some(in_addr), Some(out_addr)) = (ep_in, ep_out) {
                    return Some(BulkEndpoints {
                        interface: current_interface,
                        ep_in: in_addr,
                        ep_out: out_addr,
                    });
                }
            }
        }

        offset += len;
    }

    None
}

/// Sorts USB candidate devices so known Android vendor IDs are probed first.
pub fn prioritize_android_candidates(candidates: &mut [UsbDeviceInfo]) {
    candidates.sort_by(|a, b| {
        is_known_android_vid(b.vid)
            .cmp(&is_known_android_vid(a.vid))
            .then_with(|| a.busnum.cmp(&b.busnum))
            .then_with(|| a.devnum.cmp(&b.devnum))
    });
}

fn read_trimmed_string(path: &Path) -> Option<String> {
    fs::read_to_string(path)
        .ok()
        .map(|s| s.trim().to_owned())
        .filter(|s| !s.is_empty())
}

fn read_hex_u16(path: &Path) -> Option<u16> {
    let raw = read_trimmed_string(path)?;
    u16::from_str_radix(raw.trim_start_matches("0x"), 16).ok()
}

fn read_hex_u8(path: &Path) -> Option<u8> {
    let raw = read_trimmed_string(path)?;
    u8::from_str_radix(raw.trim_start_matches("0x"), 16).ok()
}

fn read_decimal_u32(path: &Path) -> Option<u32> {
    read_trimmed_string(path)?.parse::<u32>().ok()
}

fn read_descriptors_file(path: &Path) -> Vec<u8> {
    // Sysfs `descriptors` reports `st_size == 65553` via `stat(2)` even when only ~80 bytes
    // are readable, so avoid `fs::read` (which pre-allocates 64 KiB per device).
    let Ok(file) = File::open(path) else {
        return Vec::new();
    };
    let mut buf = Vec::with_capacity(512);
    let _ = file.take(MAX_DESCRIPTOR_READ_BYTES).read_to_end(&mut buf);
    buf
}

/// Enumerates attached USB devices from `/sys/bus/usb/devices`, skipping interfaces (`:`),
/// `usb*` root hub entries, and Linux Foundation root hubs (`0x1d6b`).
#[must_use]
pub fn enumerate_usb_devices() -> Vec<UsbDeviceInfo> {
    enumerate_usb_devices_in(Path::new("/sys/bus/usb/devices"))
}

/// Enumerates USB devices from an arbitrary sysfs directory (used in unit tests).
#[must_use]
pub fn enumerate_usb_devices_in(sysfs_root: &Path) -> Vec<UsbDeviceInfo> {
    let Ok(entries) = fs::read_dir(sysfs_root) else {
        return Vec::new();
    };

    let mut devices = Vec::new();
    for entry in entries.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if name.contains(':') || name.starts_with("usb") {
            continue;
        }

        let dir = entry.path();
        let Some(vid) = read_hex_u16(&dir.join("idVendor")) else {
            continue;
        };
        if vid == LINUX_ROOT_HUB_VID {
            continue;
        }
        let Some(pid) = read_hex_u16(&dir.join("idProduct")) else {
            continue;
        };
        let Some(busnum) = read_decimal_u32(&dir.join("busnum")) else {
            continue;
        };
        let Some(devnum) = read_decimal_u32(&dir.join("devnum")) else {
            continue;
        };

        let device_class = read_hex_u8(&dir.join("bDeviceClass")).unwrap_or(0);
        let product = read_trimmed_string(&dir.join("product"));
        let manufacturer = read_trimmed_string(&dir.join("manufacturer"));
        let serial = read_trimmed_string(&dir.join("serial"));
        let descriptors = read_descriptors_file(&dir.join("descriptors"));

        devices.push(UsbDeviceInfo {
            sysfs_name: name,
            vid,
            pid,
            busnum,
            devnum,
            device_class,
            product,
            manufacturer,
            serial,
            descriptors,
        });
    }

    devices
}

// ---------------------------------------------------------------------------
// `usbdevfs` Connection, AOA Handshake & Async UnixStream Bridge
// ---------------------------------------------------------------------------

#[derive(Debug)]
struct InterfaceReleaseGuard {
    file: File,
    interface: u8,
}

impl Drop for InterfaceReleaseGuard {
    fn drop(&mut self) {
        let mut iface = u32::from(self.interface);
        // SAFETY: `self.file` owns a valid open `usbdevfs` file descriptor and `iface` is a
        // valid aligned stack `u32` matching `USBDEVFS_RELEASEINTERFACE`.
        let rc = unsafe {
            libc::ioctl(
                self.file.as_raw_fd(),
                USBDEVFS_RELEASEINTERFACE,
                (&raw mut iface).cast::<libc::c_void>(),
            )
        };
        if rc < 0 {
            let err = std::io::Error::last_os_error();
            debug!(
                interface = self.interface,
                error = %err,
                "USBDEVFS_RELEASEINTERFACE returned error on drop"
            );
        }
    }
}

/// Active Linux `usbdevfs` connection to an Android device in AOA v1 mode.
#[derive(Debug)]
pub struct AoaConnection {
    guard: Arc<InterfaceReleaseGuard>,
    info: UsbDeviceInfo,
    endpoints: BulkEndpoints,
}

/// Join/stop handle for the OS worker threads backing an AOA [`tokio::net::UnixStream`].
#[derive(Debug)]
pub struct AoaStreamHandle {
    worker_stop: Arc<AtomicBool>,
    cleanup_stream: Option<StdUnixStream>,
    rx_thread: Option<thread::JoinHandle<()>>,
    tx_thread: Option<thread::JoinHandle<()>>,
}

impl AoaStreamHandle {
    /// Signals both AOA RX/TX worker threads to stop and joins them so the claimed
    /// `usbdevfs` interface guard is released before any subsequent reconnect attempt.
    pub fn stop_and_join(mut self) {
        self.worker_stop.store(true, Ordering::Relaxed);
        if let Some(stream) = self.cleanup_stream.take() {
            let _ = stream.shutdown(std::net::Shutdown::Both);
        }
        if let Some(handle) = self.rx_thread.take() {
            let _ = handle.join();
        }
        if let Some(handle) = self.tx_thread.take() {
            let _ = handle.join();
        }
    }
}

impl AoaConnection {
    /// Returns metadata for the connected USB accessory device.
    #[must_use]
    pub fn device_info(&self) -> &UsbDeviceInfo {
        &self.info
    }

    /// Returns the claimed interface and Bulk IN/OUT endpoint addresses.
    #[must_use]
    pub const fn endpoints(&self) -> BulkEndpoints {
        self.endpoints
    }

    /// Bridges this AOA bulk connection into a nonblocking [`tokio::net::UnixStream`]
    /// backed by two dedicated OS threads (`camcraft-aoa-rx` and `camcraft-aoa-tx`)
    /// using pre-allocated 16 KiB buffers (zero per-transfer heap allocations).
    ///
    /// Must be called from within a Tokio runtime context.
    pub fn into_tokio_stream(
        self,
        shutdown: Arc<AtomicBool>,
    ) -> Result<TokioUnixStream, UsbAoaError> {
        let (stream, _handle) = self.into_tokio_stream_with_handle(shutdown)?;
        Ok(stream)
    }

    /// Bridges this AOA bulk connection into a nonblocking [`tokio::net::UnixStream`]
    /// and returns an [`AoaStreamHandle`] that joins the RX/TX worker threads on teardown.
    ///
    /// Must be called from within a Tokio runtime context.
    pub fn into_tokio_stream_with_handle(
        self,
        shutdown: Arc<AtomicBool>,
    ) -> Result<(TokioUnixStream, AoaStreamHandle), UsbAoaError> {
        let Self {
            guard,
            info: _,
            endpoints,
        } = self;

        let (tokio_std_stream, rx_stream) = StdUnixStream::pair()?;
        tokio_std_stream.set_nonblocking(true)?;
        let tokio_stream = TokioUnixStream::from_std(tokio_std_stream)?;

        rx_stream.set_nonblocking(false)?;
        rx_stream.set_write_timeout(Some(UNIX_IO_TIMEOUT))?;
        let tx_stream = rx_stream.try_clone()?;
        tx_stream.set_read_timeout(Some(UNIX_IO_TIMEOUT))?;
        let cleanup_stream = rx_stream.try_clone()?;

        let worker_stop = Arc::new(AtomicBool::new(false));

        let rx_guard = Arc::clone(&guard);
        let rx_shutdown = Arc::clone(&shutdown);
        let rx_stop = Arc::clone(&worker_stop);
        let rx_ep = endpoints.ep_in;

        let rx_thread = thread::Builder::new()
            .name("camcraft-aoa-rx".to_owned())
            .spawn(move || {
                run_aoa_rx_worker(rx_guard, rx_ep, rx_stream, rx_shutdown, rx_stop);
            })?;

        let tx_guard = guard;
        let tx_shutdown = shutdown;
        let tx_stop = Arc::clone(&worker_stop);
        let tx_ep = endpoints.ep_out;

        let tx_thread = match thread::Builder::new()
            .name("camcraft-aoa-tx".to_owned())
            .spawn(move || {
                run_aoa_tx_worker(tx_guard, tx_ep, tx_stream, tx_shutdown, tx_stop);
            }) {
            Ok(handle) => handle,
            Err(err) => {
                worker_stop.store(true, Ordering::Relaxed);
                let _ = cleanup_stream.shutdown(std::net::Shutdown::Both);
                let _ = rx_thread.join();
                return Err(UsbAoaError::Io(err));
            }
        };

        Ok((
            tokio_stream,
            AoaStreamHandle {
                worker_stop,
                cleanup_stream: Some(cleanup_stream),
                rx_thread: Some(rx_thread),
                tx_thread: Some(tx_thread),
            },
        ))
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum UrbCompletion {
    Continue { bytes: usize },
    Fatal { bytes: usize, os_error: i32 },
}

fn classify_reaped_urb(status: i32, actual_length: i32, max_len: usize) -> UrbCompletion {
    let bytes = usize::try_from(actual_length).unwrap_or(0).min(max_len);
    if status == 0 {
        return UrbCompletion::Continue { bytes };
    }
    let os_error = status.saturating_neg();
    if matches!(
        os_error,
        libc::ENOENT | libc::ECONNRESET | libc::ETIMEDOUT | libc::EAGAIN
    ) {
        UrbCompletion::Continue { bytes }
    } else {
        UrbCompletion::Fatal { bytes, os_error }
    }
}

fn forward_aoa_rx_bytes(
    scratch: &[u8],
    len: usize,
    stream: &mut StdUnixStream,
    shutdown: &AtomicBool,
    worker_stop: &AtomicBool,
) -> bool {
    let mut written = 0usize;
    while written < len {
        if shutdown.load(Ordering::Relaxed) || worker_stop.load(Ordering::Relaxed) {
            return false;
        }
        match stream.write(&scratch[written..len]) {
            Ok(0) => return false,
            Ok(k) => written += k,
            Err(e) if e.kind() == ErrorKind::Interrupted => continue,
            Err(e) if matches!(e.kind(), ErrorKind::WouldBlock | ErrorKind::TimedOut) => {
                continue;
            }
            Err(e) => {
                debug!(error = %e, "AOA RX UnixStream write closed");
                return false;
            }
        }
    }
    true
}

fn run_aoa_rx_worker(
    guard: Arc<InterfaceReleaseGuard>,
    ep_in: u8,
    mut stream: StdUnixStream,
    shutdown: Arc<AtomicBool>,
    worker_stop: Arc<AtomicBool>,
) {
    if (ep_in & 0x80) == 0 {
        warn!(ep = %format!("0x{ep_in:02x}"), "AOA RX endpoint is not Bulk IN");
        worker_stop.store(true, Ordering::Relaxed);
        let _ = stream.shutdown(std::net::Shutdown::Both);
        return;
    }

    let mut scratch = vec![0u8; AOA_MAX_BULK_CHUNK];
    let mut urb = Box::new(UsbdevfsUrb::zeroed());
    let fd = guard.file.as_raw_fd();

    // Only RX submits async URBs on `fd`; TX uses synchronous `USBDEVFS_BULK` so RX can never reap TX URBs.
    while !shutdown.load(Ordering::Relaxed) && !worker_stop.load(Ordering::Relaxed) {
        urb.reset_bulk_in(ep_in, &mut scratch);
        let urb_ptr: *mut UsbdevfsUrb = &mut *urb;

        // SAFETY: `fd` is an open `usbdevfs` fd kept alive by `guard`, `urb` is heap-pinned and
        // exclusively owned by this thread, and `urb.buffer` points to `scratch` which stays alive
        // and unaccessed until `wait_and_reap_rx_urb` reaps the URB.
        if let Err(err) = unsafe { submit_urb(fd, urb_ptr) } {
            debug!(error = %err, ep = %format!("0x{ep_in:02x}"), "AOA RX submit URB failed");
            break;
        }

        // SAFETY: `urb_ptr` was just submitted on `fd` and has not yet been reaped.
        let completion = match unsafe { wait_and_reap_rx_urb(fd, urb_ptr, &shutdown, &worker_stop) }
        {
            Ok(c) => c,
            Err(err) => {
                debug!(error = %err, ep = %format!("0x{ep_in:02x}"), "AOA RX reap URB failed");
                break;
            }
        };

        let (bytes, fatal_err) = match completion {
            UrbCompletion::Continue { bytes } => (bytes, None),
            UrbCompletion::Fatal { bytes, os_error } => (bytes, Some(os_error)),
        };

        if bytes > 0 && !forward_aoa_rx_bytes(&scratch, bytes, &mut stream, &shutdown, &worker_stop)
        {
            break;
        }

        if let Some(os_error) = fatal_err {
            let err = std::io::Error::from_raw_os_error(os_error);
            debug!(error = %err, ep = %format!("0x{ep_in:02x}"), "AOA RX bulk URB ended");
            break;
        }
    }

    worker_stop.store(true, Ordering::Relaxed);
    let _ = stream.shutdown(std::net::Shutdown::Both);
}

fn run_aoa_tx_worker(
    guard: Arc<InterfaceReleaseGuard>,
    ep_out: u8,
    mut stream: StdUnixStream,
    shutdown: Arc<AtomicBool>,
    worker_stop: Arc<AtomicBool>,
) {
    let mut scratch = vec![0u8; AOA_MAX_BULK_CHUNK];
    let fd = guard.file.as_raw_fd();

    while !shutdown.load(Ordering::Relaxed) && !worker_stop.load(Ordering::Relaxed) {
        match stream.read(&mut scratch) {
            Ok(0) => {
                debug!("AOA TX UnixStream reached EOF");
                break;
            }
            Ok(n) => {
                if let Err(e) =
                    write_bulk_out_chunked(fd, ep_out, &scratch[..n], &shutdown, &worker_stop)
                {
                    debug!(
                        error = %e,
                        ep = %format!("0x{ep_out:02x}"),
                        "AOA TX bulk transfer ended"
                    );
                    break;
                }
            }
            Err(e)
                if matches!(
                    e.kind(),
                    ErrorKind::Interrupted | ErrorKind::WouldBlock | ErrorKind::TimedOut
                ) =>
            {
                continue;
            }
            Err(e) => {
                debug!(error = %e, "AOA TX UnixStream read closed");
                break;
            }
        }
    }

    worker_stop.store(true, Ordering::Relaxed);
    let _ = stream.shutdown(std::net::Shutdown::Both);
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum BulkOutErrorKind {
    Retryable,
    Fatal,
}

fn classify_bulk_out_error(err: &std::io::Error) -> BulkOutErrorKind {
    if matches!(
        err.raw_os_error(),
        Some(libc::ETIMEDOUT | libc::EAGAIN | libc::EINTR)
    ) || matches!(
        err.kind(),
        ErrorKind::TimedOut | ErrorKind::WouldBlock | ErrorKind::Interrupted
    ) {
        BulkOutErrorKind::Retryable
    } else {
        BulkOutErrorKind::Fatal
    }
}

fn write_bulk_out_chunked_with<F>(
    data: &[u8],
    shutdown: &AtomicBool,
    worker_stop: &AtomicBool,
    mut send_chunk: F,
) -> Result<(), std::io::Error>
where
    F: FnMut(&[u8]) -> Result<usize, std::io::Error>,
{
    for chunk in data.chunks(AOA_MAX_BULK_CHUNK) {
        let mut sent = 0usize;
        while sent < chunk.len() {
            if shutdown.load(Ordering::Relaxed) || worker_stop.load(Ordering::Relaxed) {
                return Err(std::io::Error::new(
                    ErrorKind::Interrupted,
                    "AOA bulk OUT stopped by shutdown",
                ));
            }
            match send_chunk(&chunk[sent..]) {
                Ok(0) => {
                    return Err(std::io::Error::new(
                        ErrorKind::WriteZero,
                        "usbdevfs bulk OUT wrote 0 bytes",
                    ));
                }
                Ok(n) => sent += n.min(chunk.len() - sent),
                Err(e) if classify_bulk_out_error(&e) == BulkOutErrorKind::Retryable => continue,
                Err(e) => return Err(e),
            }
        }
    }
    Ok(())
}

fn write_bulk_out_chunked(
    fd: RawFd,
    ep_out: u8,
    data: &[u8],
    shutdown: &AtomicBool,
    worker_stop: &AtomicBool,
) -> Result<(), std::io::Error> {
    write_bulk_out_chunked_with(data, shutdown, worker_stop, |slice| {
        usbdevfs_bulk_out(fd, ep_out, slice, BULK_OUT_TIMEOUT_MS)
    })
}

/// Discovers and connects to an Android device over USB using Android Open Accessory (AOA v1).
///
/// 1. Connects immediately if a device is already in AOA mode (`0x18D1:0x2D00/2D01/2D04/2D05`).
/// 2. Otherwise probes non-hub USB candidates (prioritizing known Android vendor IDs),
///    runs `AOA_GET_PROTOCOL`, `AOA_SEND_STRING` (`0..=5`), and `AOA_START_ACCESSORY`,
///    then polls sysfs for up to 5 seconds for the re-enumerated AOA accessory.
pub fn connect_aoa_device() -> Result<AoaConnection, UsbAoaError> {
    let never = AtomicBool::new(false);
    connect_aoa_device_with_shutdown(&never)
}

/// Discovers and connects to an Android device over USB AOA v1 while honoring `shutdown`
/// to abort candidate probing and re-enumeration polling promptly on cancellation.
pub fn connect_aoa_device_with_shutdown(
    shutdown: &AtomicBool,
) -> Result<AoaConnection, UsbAoaError> {
    if shutdown.load(Ordering::Relaxed) {
        return Err(UsbAoaError::Disconnected);
    }

    let devices = enumerate_usb_devices();
    let mut permission_denied_path: Option<PathBuf> = None;

    // 1. Check for devices already in AOA mode first.
    for dev in devices.iter().filter(|d| is_aoa_device(d.vid, d.pid)) {
        if shutdown.load(Ordering::Relaxed) {
            return Err(UsbAoaError::Disconnected);
        }
        match open_and_claim_aoa(dev) {
            Ok(conn) => return Ok(conn),
            Err(UsbAoaError::PermissionDenied(path)) => {
                permission_denied_path.get_or_insert(path);
            }
            Err(err) => {
                debug!(
                    vid = %format!("{:04x}", dev.vid),
                    pid = %format!("{:04x}", dev.pid),
                    error = %err,
                    "Failed to open existing AOA device"
                );
            }
        }
    }

    // 2. Sort remaining non-hub candidates (known Android VIDs first).
    let mut candidates: Vec<UsbDeviceInfo> = devices
        .into_iter()
        .filter(|d| !is_aoa_device(d.vid, d.pid) && d.device_class != USB_CLASS_HUB)
        .collect();
    prioritize_android_candidates(&mut candidates);

    let mut aoa_start_sent = false;
    for dev in &candidates {
        if shutdown.load(Ordering::Relaxed) {
            return Err(UsbAoaError::Disconnected);
        }
        let dev_path = dev.dev_path();
        let file = match open_usb_device(&dev_path) {
            Ok(f) => f,
            Err(err) => {
                if is_permission_denied(&err) && is_likely_android_device(dev) {
                    permission_denied_path.get_or_insert(dev_path);
                }
                continue;
            }
        };

        match try_start_aoa_handshake(file.as_raw_fd(), dev) {
            Ok(true) => {
                info!(
                    vid = %format!("{:04x}", dev.vid),
                    pid = %format!("{:04x}", dev.pid),
                    product = ?dev.product,
                    "Sent AOA_START_ACCESSORY; waiting for device re-enumeration"
                );
                // Drop the pre-AOA file descriptor before polling for re-enumeration.
                drop(file);
                aoa_start_sent = true;
                break;
            }
            Ok(false) => {
                drop(file);
            }
            Err(err) => {
                debug!(
                    vid = %format!("{:04x}", dev.vid),
                    pid = %format!("{:04x}", dev.pid),
                    error = %err,
                    "AOA handshake attempt failed on candidate"
                );
                drop(file);
            }
        }
    }

    if aoa_start_sent {
        let deadline = Instant::now() + REENUMERATION_TIMEOUT;
        while Instant::now() < deadline {
            if !sleep_with_shutdown(REENUMERATION_POLL_INTERVAL, shutdown) {
                return Err(UsbAoaError::Disconnected);
            }
            for dev in enumerate_usb_devices()
                .into_iter()
                .filter(|d| is_aoa_device(d.vid, d.pid))
            {
                if shutdown.load(Ordering::Relaxed) {
                    return Err(UsbAoaError::Disconnected);
                }
                match open_and_claim_aoa(&dev) {
                    Ok(conn) => return Ok(conn),
                    Err(UsbAoaError::PermissionDenied(path)) => {
                        permission_denied_path.get_or_insert(path);
                    }
                    Err(err) => {
                        debug!(
                            vid = %format!("{:04x}", dev.vid),
                            pid = %format!("{:04x}", dev.pid),
                            error = %err,
                            "Re-enumerated AOA device not ready yet"
                        );
                    }
                }
            }
        }

        if let Some(path) = permission_denied_path {
            return Err(UsbAoaError::PermissionDenied(path));
        }
        return Err(UsbAoaError::ReenumerationTimeout(REENUMERATION_TIMEOUT));
    }

    if let Some(path) = permission_denied_path {
        Err(UsbAoaError::PermissionDenied(path))
    } else {
        Err(UsbAoaError::NoDeviceFound)
    }
}

fn sleep_with_shutdown(duration: Duration, shutdown: &AtomicBool) -> bool {
    let deadline = Instant::now() + duration;
    while !shutdown.load(Ordering::Relaxed) {
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return true;
        }
        thread::sleep(remaining.min(REENUMERATION_SHUTDOWN_SLICE));
    }
    false
}

fn is_likely_android_device(dev: &UsbDeviceInfo) -> bool {
    if is_aoa_device(dev.vid, dev.pid) || is_known_android_vid(dev.vid) {
        return true;
    }
    let contains_android = |opt: &Option<String>| {
        opt.as_deref()
            .is_some_and(|s| s.to_ascii_lowercase().contains("android"))
    };
    contains_android(&dev.manufacturer) || contains_android(&dev.product)
}

fn is_permission_denied(err: &std::io::Error) -> bool {
    err.kind() == ErrorKind::PermissionDenied
        || matches!(err.raw_os_error(), Some(libc::EACCES | libc::EPERM))
}

fn open_usb_device(path: &Path) -> Result<File, std::io::Error> {
    OpenOptions::new()
        .read(true)
        .write(true)
        .custom_flags(libc::O_CLOEXEC)
        .open(path)
}

fn open_and_claim_aoa(dev: &UsbDeviceInfo) -> Result<AoaConnection, UsbAoaError> {
    let endpoints =
        parse_bulk_endpoints(&dev.descriptors).ok_or(UsbAoaError::MissingBulkEndpoints {
            vid: dev.vid,
            pid: dev.pid,
        })?;
    let dev_path = dev.dev_path();
    let file = open_usb_device(&dev_path).map_err(|err| {
        if is_permission_denied(&err) {
            UsbAoaError::PermissionDenied(dev_path.clone())
        } else {
            UsbAoaError::Io(err)
        }
    })?;

    claim_interface(file.as_raw_fd(), endpoints.interface)?;
    let guard = Arc::new(InterfaceReleaseGuard {
        file,
        interface: endpoints.interface,
    });

    clear_halt(guard.file.as_raw_fd(), endpoints.ep_in)?;
    clear_halt(guard.file.as_raw_fd(), endpoints.ep_out)?;

    info!(
        vid = %format!("{:04x}", dev.vid),
        pid = %format!("{:04x}", dev.pid),
        bus = dev.busnum,
        dev = dev.devnum,
        iface = endpoints.interface,
        ep_in = %format!("0x{:02x}", endpoints.ep_in),
        ep_out = %format!("0x{:02x}", endpoints.ep_out),
        "Claimed USB AOA bulk interface"
    );

    Ok(AoaConnection {
        guard,
        info: dev.clone(),
        endpoints,
    })
}

fn try_start_aoa_handshake(fd: RawFd, dev: &UsbDeviceInfo) -> Result<bool, UsbAoaError> {
    let mut proto_buf = [0u8; 2];
    let Ok(n) = usbdevfs_control_in(
        fd,
        CTRL_REQ_TYPE_IN_VENDOR,
        AOA_GET_PROTOCOL,
        0,
        0,
        &mut proto_buf,
        CTRL_TIMEOUT_MS,
    ) else {
        return Ok(false);
    };

    if n < 2 {
        return Ok(false);
    }
    let version = u16::from_le_bytes(proto_buf);
    if version < 1 {
        debug!(
            vid = %format!("{:04x}", dev.vid),
            pid = %format!("{:04x}", dev.pid),
            version,
            "USB device reported unsupported AOA version"
        );
        return Ok(false);
    }

    const AOA_STRINGS: [(&str, u16); 6] = [
        (AOA_MANUFACTURER, 0),
        (AOA_MODEL, 1),
        (AOA_DESCRIPTION, 2),
        (AOA_VERSION, 3),
        (AOA_URI, 4),
        (AOA_SERIAL, 5),
    ];

    for &(value, index) in &AOA_STRINGS {
        let mut payload = Vec::with_capacity(value.len() + 1);
        payload.extend_from_slice(value.as_bytes());
        payload.push(0);
        usbdevfs_control_out(
            fd,
            CTRL_REQ_TYPE_OUT_VENDOR,
            AOA_SEND_STRING,
            0,
            index,
            &payload,
            CTRL_TIMEOUT_MS,
        )?;
    }

    usbdevfs_control_out(
        fd,
        CTRL_REQ_TYPE_OUT_VENDOR,
        AOA_START_ACCESSORY,
        0,
        0,
        &[],
        CTRL_TIMEOUT_MS,
    )?;

    Ok(true)
}

fn claim_interface(fd: RawFd, interface: u8) -> Result<(), UsbAoaError> {
    let mut dc = UsbdevfsDisconnectClaim {
        interface: u32::from(interface),
        flags: 0,
        driver: [0u8; 256],
    };
    // SAFETY: `fd` is an open `usbdevfs` file descriptor and `dc` is a valid initialized
    // `UsbdevfsDisconnectClaim` structure matching `USBDEVFS_DISCONNECT_CLAIM`.
    let rc = unsafe {
        libc::ioctl(
            fd,
            USBDEVFS_DISCONNECT_CLAIM,
            (&raw mut dc).cast::<libc::c_void>(),
        )
    };
    if rc >= 0 {
        return Ok(());
    }

    let dc_err = std::io::Error::last_os_error();
    debug!(
        interface,
        error = %dc_err,
        "USBDEVFS_DISCONNECT_CLAIM failed; falling back to USBDEVFS_CLAIMINTERFACE"
    );

    let mut iface = u32::from(interface);
    // SAFETY: `fd` is an open `usbdevfs` file descriptor and `iface` is a valid aligned stack
    // `u32` matching `USBDEVFS_CLAIMINTERFACE`.
    let rc = unsafe {
        libc::ioctl(
            fd,
            USBDEVFS_CLAIMINTERFACE,
            (&raw mut iface).cast::<libc::c_void>(),
        )
    };
    if rc >= 0 {
        Ok(())
    } else {
        Err(UsbAoaError::ClaimInterface {
            interface,
            source: std::io::Error::last_os_error(),
        })
    }
}

fn clear_halt(fd: RawFd, ep: u8) -> Result<(), UsbAoaError> {
    let mut ep_val = u32::from(ep);
    // SAFETY: `fd` is an open `usbdevfs` file descriptor and `ep_val` is a valid aligned stack
    // `u32` matching `USBDEVFS_CLEAR_HALT`.
    let rc = unsafe {
        libc::ioctl(
            fd,
            USBDEVFS_CLEAR_HALT,
            (&raw mut ep_val).cast::<libc::c_void>(),
        )
    };
    if rc < 0 {
        let err = std::io::Error::last_os_error();
        if matches!(err.raw_os_error(), Some(libc::ENODEV | libc::ESHUTDOWN)) {
            return Err(UsbAoaError::Disconnected);
        }
        warn!(
            ep = %format!("0x{ep:02x}"),
            error = %err,
            "USBDEVFS_CLEAR_HALT failed (continuing)"
        );
    }
    Ok(())
}

/// Executes a raw `USBDEVFS_CONTROL` ioctl, retrying on `EINTR`.
///
/// # Safety
/// - `fd` must be an open `usbdevfs` file descriptor.
/// - `ctrl.data` must be null (if `ctrl.w_length == 0`) or valid for `ctrl.w_length` bytes
///   for the duration of the ioctl (`mut` if `ctrl.b_request_type & 0x80 != 0`).
unsafe fn usbdevfs_control_raw(
    fd: RawFd,
    ctrl: UsbdevfsCtrlTransfer,
) -> Result<usize, std::io::Error> {
    let max_len = usize::from(ctrl.w_length);
    loop {
        let mut req = ctrl;
        // SAFETY: Caller guarantees `fd`, `ctrl.data`, and `ctrl.w_length` invariants for `USBDEVFS_CONTROL`.
        let rc =
            unsafe { libc::ioctl(fd, USBDEVFS_CONTROL, (&raw mut req).cast::<libc::c_void>()) };
        if rc >= 0 {
            return Ok((rc as usize).min(max_len));
        }
        let err = std::io::Error::last_os_error();
        if err.raw_os_error() == Some(libc::EINTR) {
            continue;
        }
        return Err(err);
    }
}

fn usbdevfs_control_in(
    fd: RawFd,
    b_request_type: u8,
    b_request: u8,
    w_value: u16,
    w_index: u16,
    data: &mut [u8],
    timeout_ms: u32,
) -> Result<usize, std::io::Error> {
    if (b_request_type & 0x80) == 0 {
        return Err(std::io::Error::new(
            ErrorKind::InvalidInput,
            "control IN transfer requires device-to-host request type",
        ));
    }
    let w_length = u16::try_from(data.len()).map_err(|_| {
        std::io::Error::new(
            ErrorKind::InvalidInput,
            "control transfer buffer exceeds u16::MAX",
        )
    })?;
    let data_ptr = if data.is_empty() {
        std::ptr::null_mut()
    } else {
        data.as_mut_ptr().cast::<libc::c_void>()
    };

    // SAFETY: `fd` is an open `usbdevfs` fd, `b_request_type` is IN (device-to-host), and `data`
    // is exclusively borrowed mutably for `w_length` bytes across the synchronous ioctl.
    unsafe {
        usbdevfs_control_raw(
            fd,
            UsbdevfsCtrlTransfer {
                b_request_type,
                b_request,
                w_value,
                w_index,
                w_length,
                timeout: timeout_ms,
                data: data_ptr,
            },
        )
    }
}

fn usbdevfs_control_out(
    fd: RawFd,
    b_request_type: u8,
    b_request: u8,
    w_value: u16,
    w_index: u16,
    data: &[u8],
    timeout_ms: u32,
) -> Result<usize, std::io::Error> {
    if (b_request_type & 0x80) != 0 {
        return Err(std::io::Error::new(
            ErrorKind::InvalidInput,
            "control OUT transfer requires host-to-device request type",
        ));
    }
    let w_length = u16::try_from(data.len()).map_err(|_| {
        std::io::Error::new(
            ErrorKind::InvalidInput,
            "control transfer payload exceeds u16::MAX",
        )
    })?;
    let data_ptr = if data.is_empty() {
        std::ptr::null_mut()
    } else {
        data.as_ptr().cast_mut().cast::<libc::c_void>()
    };

    // SAFETY: `fd` is an open `usbdevfs` fd, `b_request_type` is OUT (host-to-device) so the
    // kernel only reads `w_length` bytes from `data` while `data` remains borrowed.
    unsafe {
        usbdevfs_control_raw(
            fd,
            UsbdevfsCtrlTransfer {
                b_request_type,
                b_request,
                w_value,
                w_index,
                w_length,
                timeout: timeout_ms,
                data: data_ptr,
            },
        )
    }
}

/// Executes a raw `USBDEVFS_BULK` ioctl.
///
/// # Safety
/// - `fd` must be an open `usbdevfs` file descriptor.
/// - `data_ptr` must be null (if `len == 0`) or valid for `len` bytes for the duration of the
///   ioctl (`mut` if `ep & 0x80 != 0`).
unsafe fn usbdevfs_bulk_raw(
    fd: RawFd,
    ep: u8,
    len: u32,
    data_ptr: *mut libc::c_void,
    timeout_ms: u32,
) -> Result<usize, std::io::Error> {
    let mut bulk = UsbdevfsBulkTransfer {
        ep: u32::from(ep),
        len,
        timeout: timeout_ms,
        data: data_ptr,
    };

    // SAFETY: Caller guarantees `fd`, `ep` direction, `data_ptr`, and `len` invariants.
    let rc = unsafe { libc::ioctl(fd, USBDEVFS_BULK, (&raw mut bulk).cast::<libc::c_void>()) };
    if rc >= 0 {
        Ok((rc as usize).min(len as usize))
    } else {
        Err(std::io::Error::last_os_error())
    }
}

/// Submits an async URB via `USBDEVFS_SUBMITURB`.
///
/// # Safety
/// - `fd` must be an open `usbdevfs` file descriptor.
/// - `urb_ptr` must be valid, heap-pinned, and remain valid (along with its `buffer`) until reaped
///   or until `fd` is disconnected.
unsafe fn submit_urb(fd: RawFd, urb_ptr: *mut UsbdevfsUrb) -> Result<(), std::io::Error> {
    loop {
        // SAFETY: Caller guarantees `fd` and `urb_ptr` invariants for `USBDEVFS_SUBMITURB`.
        let rc = unsafe { libc::ioctl(fd, USBDEVFS_SUBMITURB, urb_ptr.cast::<libc::c_void>()) };
        if rc >= 0 {
            return Ok(());
        }
        let err = std::io::Error::last_os_error();
        if err.raw_os_error() == Some(libc::EINTR) {
            continue;
        }
        return Err(err);
    }
}

/// Attempts a non-blocking URB reap via `USBDEVFS_REAPURBNDELAY`.
///
/// # Safety
/// - `fd` must be an open `usbdevfs` file descriptor.
unsafe fn reap_urb_ndelay(fd: RawFd) -> Result<Option<*mut UsbdevfsUrb>, std::io::Error> {
    let mut reaped: *mut UsbdevfsUrb = std::ptr::null_mut();
    loop {
        // SAFETY: `fd` is an open `usbdevfs` fd and `reaped` is a valid stack pointer receiving `*mut UsbdevfsUrb`.
        let rc = unsafe {
            libc::ioctl(
                fd,
                USBDEVFS_REAPURBNDELAY,
                (&raw mut reaped).cast::<libc::c_void>(),
            )
        };
        if rc >= 0 {
            return Ok(Some(reaped));
        }
        let err = std::io::Error::last_os_error();
        match err.raw_os_error() {
            Some(libc::EINTR) => continue,
            Some(libc::EAGAIN) => return Ok(None),
            _ => return Err(err),
        }
    }
}

/// Performs a blocking URB reap via `USBDEVFS_REAPURB`, retrying on `EINTR`.
///
/// # Safety
/// - `fd` must be an open `usbdevfs` file descriptor with a pending/completed URB.
unsafe fn reap_urb_blocking(fd: RawFd) -> Result<*mut UsbdevfsUrb, std::io::Error> {
    let mut reaped: *mut UsbdevfsUrb = std::ptr::null_mut();
    loop {
        // SAFETY: `fd` is an open `usbdevfs` fd and `reaped` is a valid stack pointer receiving `*mut UsbdevfsUrb`.
        let rc = unsafe {
            libc::ioctl(
                fd,
                USBDEVFS_REAPURB,
                (&raw mut reaped).cast::<libc::c_void>(),
            )
        };
        if rc >= 0 {
            return Ok(reaped);
        }
        let err = std::io::Error::last_os_error();
        if err.raw_os_error() == Some(libc::EINTR) {
            continue;
        }
        return Err(err);
    }
}

/// Cancels an in-flight URB with `USBDEVFS_DISCARDURB` and reaps it with `USBDEVFS_REAPURB`.
///
/// # Safety
/// - `fd` must be an open `usbdevfs` file descriptor.
/// - `urb_ptr` must correspond to a previously submitted URB that has not yet been reaped.
unsafe fn discard_and_reap_urb(
    fd: RawFd,
    urb_ptr: *mut UsbdevfsUrb,
) -> Result<*mut UsbdevfsUrb, std::io::Error> {
    // SAFETY: Caller guarantees `fd` is open and `urb_ptr` was submitted via `USBDEVFS_SUBMITURB`.
    let rc = unsafe { libc::ioctl(fd, USBDEVFS_DISCARDURB, urb_ptr.cast::<libc::c_void>()) };
    if rc < 0 {
        let err = std::io::Error::last_os_error();
        // `ENOENT` / `EINVAL` indicates the URB completed just before discard; still reap it.
        if !matches!(err.raw_os_error(), Some(libc::ENOENT | libc::EINVAL)) {
            // SAFETY: Best-effort non-blocking reap in case kernel completed the URB during disconnect.
            if let Ok(Some(reaped)) = unsafe { reap_urb_ndelay(fd) } {
                return Ok(reaped);
            }
            return Err(err);
        }
    }

    // SAFETY: The URB was either cancelled by `USBDEVFS_DISCARDURB` or had already finished.
    unsafe { reap_urb_blocking(fd) }
}

/// Polls `fd` for URB completion up to `BULK_IN_TIMEOUT_MS`, reaping immediately or cancelling and
/// reaping on timeout/shutdown while preserving any `actual_length > 0` partial transfer.
///
/// # Safety
/// - `fd` must be an open `usbdevfs` file descriptor.
/// - `urb_ptr` must be a valid, currently submitted URB whose buffer has capacity `>= AOA_MAX_BULK_CHUNK`.
unsafe fn wait_and_reap_rx_urb(
    fd: RawFd,
    urb_ptr: *mut UsbdevfsUrb,
    shutdown: &AtomicBool,
    worker_stop: &AtomicBool,
) -> Result<UrbCompletion, std::io::Error> {
    loop {
        if shutdown.load(Ordering::Relaxed) || worker_stop.load(Ordering::Relaxed) {
            // SAFETY: Caller guarantees `fd` and `urb_ptr` remain valid and submitted.
            let reaped = unsafe { discard_and_reap_urb(fd, urb_ptr)? };
            // SAFETY: `reaped` was returned by `USBDEVFS_REAPURB`, returning ownership to us.
            let (status, actual_length, buffer_length) = unsafe {
                (
                    (*reaped).status,
                    (*reaped).actual_length,
                    (*reaped).buffer_length,
                )
            };
            return Ok(classify_reaped_urb(
                status,
                actual_length,
                usize::try_from(buffer_length).unwrap_or(0),
            ));
        }

        let mut pfd = libc::pollfd {
            fd,
            events: libc::POLLOUT | libc::POLLERR | libc::POLLHUP,
            revents: 0,
        };
        // SAFETY: `pfd` points to a valid single `libc::pollfd` for the duration of `poll`.
        let poll_rc = unsafe { libc::poll(&raw mut pfd, 1, BULK_IN_TIMEOUT_MS as libc::c_int) };
        if poll_rc < 0 {
            let err = std::io::Error::last_os_error();
            if err.raw_os_error() == Some(libc::EINTR) {
                continue;
            }
            // SAFETY: Ensure submitted URB is discarded and reaped before propagating error.
            let _ = unsafe { discard_and_reap_urb(fd, urb_ptr) };
            return Err(err);
        }

        if poll_rc == 0 {
            // Timeout: flush partial exact-512B/1024B-multiple chunks received before idle.
            // SAFETY: Caller guarantees `fd` and `urb_ptr` remain valid and submitted.
            let reaped = unsafe { discard_and_reap_urb(fd, urb_ptr)? };
            // SAFETY: `reaped` was returned by `USBDEVFS_REAPURB`, returning ownership to us.
            let (status, actual_length, buffer_length) = unsafe {
                (
                    (*reaped).status,
                    (*reaped).actual_length,
                    (*reaped).buffer_length,
                )
            };
            return Ok(classify_reaped_urb(
                status,
                actual_length,
                usize::try_from(buffer_length).unwrap_or(0),
            ));
        }

        // SAFETY: `fd` is an open `usbdevfs` file descriptor.
        match unsafe { reap_urb_ndelay(fd) } {
            Ok(Some(reaped)) => {
                if reaped != urb_ptr {
                    warn!("Reaped unexpected URB pointer on usbdevfs fd");
                    continue;
                }
                // SAFETY: `reaped` was returned by `USBDEVFS_REAPURBNDELAY`, returning ownership to us.
                let (status, actual_length, buffer_length) = unsafe {
                    (
                        (*reaped).status,
                        (*reaped).actual_length,
                        (*reaped).buffer_length,
                    )
                };
                return Ok(classify_reaped_urb(
                    status,
                    actual_length,
                    usize::try_from(buffer_length).unwrap_or(0),
                ));
            }
            Ok(None) => {
                if (pfd.revents & (libc::POLLERR | libc::POLLHUP)) != 0 {
                    // SAFETY: Ensure submitted URB is discarded and reaped on hangup/error.
                    let reaped = unsafe { discard_and_reap_urb(fd, urb_ptr)? };
                    // SAFETY: `reaped` was returned by `USBDEVFS_REAPURB`, returning ownership to us.
                    let (status, actual_length, buffer_length) = unsafe {
                        (
                            (*reaped).status,
                            (*reaped).actual_length,
                            (*reaped).buffer_length,
                        )
                    };
                    return Ok(classify_reaped_urb(
                        status,
                        actual_length,
                        usize::try_from(buffer_length).unwrap_or(0),
                    ));
                }
            }
            Err(err) => {
                // SAFETY: Best-effort cleanup of submitted URB before propagating reap error.
                let _ = unsafe { discard_and_reap_urb(fd, urb_ptr) };
                return Err(err);
            }
        }
    }
}

fn usbdevfs_bulk_out(
    fd: RawFd,
    ep: u8,
    data: &[u8],
    timeout_ms: u32,
) -> Result<usize, std::io::Error> {
    if (ep & 0x80) != 0 {
        return Err(std::io::Error::new(
            ErrorKind::InvalidInput,
            "bulk OUT transfer requires OUT endpoint address (bit 7 clear)",
        ));
    }
    let len = u32::try_from(data.len()).map_err(|_| {
        std::io::Error::new(ErrorKind::InvalidInput, "bulk OUT slice exceeds u32::MAX")
    })?;
    let data_ptr = if data.is_empty() {
        std::ptr::null_mut()
    } else {
        data.as_ptr().cast_mut().cast::<libc::c_void>()
    };

    // SAFETY: `fd` is an open `usbdevfs` fd, `ep` has bit 7 clear (OUT) so the kernel only
    // reads `len` bytes from `data` while `data` remains borrowed.
    unsafe { usbdevfs_bulk_raw(fd, ep, len, data_ptr, timeout_ms) }
}

// ---------------------------------------------------------------------------
// Linux udev rule detection & pkexec installation
// ---------------------------------------------------------------------------

/// Returns `true` if `70-camcraft-aoa.rules` is present in standard Linux udev rule directories.
#[must_use]
pub fn is_udev_rule_installed() -> bool {
    let dirs: Vec<&Path> = UDEV_RULES_DIRS.iter().map(Path::new).collect();
    is_udev_rule_installed_in(&dirs)
}

/// Checks whether `70-camcraft-aoa.rules` exists in any of the given directories.
#[must_use]
pub fn is_udev_rule_installed_in(dirs: &[&Path]) -> bool {
    dirs.iter()
        .any(|dir| dir.join(UDEV_RULES_FILENAME).is_file())
}

struct TempFileGuard(PathBuf);

impl Drop for TempFileGuard {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

/// Writes [`UDEV_RULES_CONTENT`] to a unique temporary file and installs it into
/// `/etc/udev/rules.d/70-camcraft-aoa.rules` via `pkexec`, reloading and triggering udev.
pub fn install_udev_rules_via_pkexec() -> Result<(), UsbAoaError> {
    let tmp_dir = std::env::temp_dir();
    let pid = std::process::id();
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_nanos())
        .unwrap_or(0);

    let mut tmp_file_and_path: Option<(File, PathBuf)> = None;
    for _ in 0..16 {
        let seq = TEMP_FILE_SEQ.fetch_add(1, Ordering::Relaxed);
        let candidate = tmp_dir.join(format!("camcraft-udev-{pid}-{nanos}-{seq}.rules"));
        match OpenOptions::new()
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&candidate)
        {
            Ok(file) => {
                tmp_file_and_path = Some((file, candidate));
                break;
            }
            Err(e) if e.kind() == ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(UsbAoaError::Io(e)),
        }
    }

    let Some((mut file, tmp_path)) = tmp_file_and_path else {
        return Err(UsbAoaError::UdevInstall(
            "failed to allocate unique temporary udev rules file".to_owned(),
        ));
    };
    let _cleanup = TempFileGuard(tmp_path.clone());

    file.write_all(UDEV_RULES_CONTENT.as_bytes())?;
    file.flush()?;
    drop(file);

    let output = Command::new("pkexec")
        .args([
            "sh",
            "-c",
            "install -m 0644 \"$1\" /etc/udev/rules.d/70-camcraft-aoa.rules && udevadm control --reload-rules && udevadm trigger --subsystem-match=usb",
            "--",
        ])
        .arg(&tmp_path)
        .output()
        .map_err(|e| UsbAoaError::UdevInstall(format!("failed to execute pkexec: {e}")))?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr).trim().to_owned();
        let code = output
            .status
            .code()
            .map_or_else(|| "signal".to_owned(), |c| c.to_string());
        return Err(UsbAoaError::UdevInstall(format!(
            "pkexec exited with status {code}: {stderr}"
        )));
    }

    info!("Installed udev rules to /etc/udev/rules.d/{UDEV_RULES_FILENAME}");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::os::unix::ffi::OsStrExt;

    fn make_device_info(vid: u16, pid: u16, busnum: u32, devnum: u32) -> UsbDeviceInfo {
        UsbDeviceInfo {
            sysfs_name: format!("{busnum}-{devnum}"),
            vid,
            pid,
            busnum,
            devnum,
            device_class: 0,
            product: None,
            manufacturer: None,
            serial: None,
            descriptors: Vec::new(),
        }
    }

    #[test]
    fn ioctl_request_codes_match_linux_usbdevfs_abi() {
        assert_eq!(USBDEVFS_CONTROL, 0xc018_5500u32 as libc::Ioctl);
        assert_eq!(USBDEVFS_BULK, 0xc018_5502u32 as libc::Ioctl);
        assert_eq!(USBDEVFS_DISCARDURB, 0x0000_550bu32 as libc::Ioctl);
        #[cfg(target_pointer_width = "64")]
        {
            assert_eq!(std::mem::size_of::<UsbdevfsUrb>(), 56);
            assert_eq!(std::mem::offset_of!(UsbdevfsUrb, urb_type), 0);
            assert_eq!(std::mem::offset_of!(UsbdevfsUrb, endpoint), 1);
            assert_eq!(std::mem::offset_of!(UsbdevfsUrb, status), 4);
            assert_eq!(std::mem::offset_of!(UsbdevfsUrb, flags), 8);
            assert_eq!(std::mem::offset_of!(UsbdevfsUrb, buffer), 16);
            assert_eq!(std::mem::offset_of!(UsbdevfsUrb, buffer_length), 24);
            assert_eq!(std::mem::offset_of!(UsbdevfsUrb, actual_length), 28);
            assert_eq!(std::mem::offset_of!(UsbdevfsUrb, start_frame), 32);
            assert_eq!(std::mem::offset_of!(UsbdevfsUrb, number_of_packets), 36);
            assert_eq!(std::mem::offset_of!(UsbdevfsUrb, error_count), 40);
            assert_eq!(std::mem::offset_of!(UsbdevfsUrb, signr), 44);
            assert_eq!(std::mem::offset_of!(UsbdevfsUrb, usercontext), 48);
            assert_eq!(USBDEVFS_SUBMITURB, 0x8038_550au32 as libc::Ioctl);
            assert_eq!(USBDEVFS_REAPURB, 0x4008_550cu32 as libc::Ioctl);
            assert_eq!(USBDEVFS_REAPURBNDELAY, 0x4008_550du32 as libc::Ioctl);
        }
        assert_eq!(USBDEVFS_CLAIMINTERFACE, 0x8004_550fu32 as libc::Ioctl);
        assert_eq!(USBDEVFS_RELEASEINTERFACE, 0x8004_5510u32 as libc::Ioctl);
        assert_eq!(USBDEVFS_CLEAR_HALT, 0x8004_5515u32 as libc::Ioctl);
        assert_eq!(USBDEVFS_DISCONNECT_CLAIM, 0x8108_551bu32 as libc::Ioctl);
    }

    #[test]
    fn is_aoa_device_matches_only_google_aoa_pids() {
        for &pid in AOA_PIDS {
            assert!(is_aoa_device(0x18D1, pid));
            assert!(!is_aoa_device(0x04E8, pid));
        }
        assert!(!is_aoa_device(0x18D1, 0x4EE7)); // Pixel MTP+ADB
        assert!(!is_aoa_device(0x18D1, 0x2D02)); // AOA Audio-only
        assert!(!is_aoa_device(0x18D1, 0x2D03)); // AOA Audio+ADB
    }

    #[test]
    fn parse_bulk_endpoints_single_interface() {
        #[rustfmt::skip]
        let desc: &[u8] = &[
            // Device descriptor (18 bytes, type=1)
            18, 1, 0x00, 0x02, 0x00, 0x00, 0x00, 64,
            0xD1, 0x18, 0x01, 0x2D, 0x00, 0x01, 1, 2, 3, 1,
            // Configuration descriptor (9 bytes, type=2)
            9, 2, 32, 0, 1, 1, 0, 0x80, 250,
            // Interface 0 descriptor (9 bytes, type=4)
            9, 4, 0, 0, 2, 0xFF, 0xFF, 0x00, 0,
            // Endpoint Bulk IN 0x81 (7 bytes, type=5, attr=2)
            7, 5, 0x81, 0x02, 0x00, 0x02, 0,
            // Endpoint Bulk OUT 0x01 (7 bytes, type=5, attr=2)
            7, 5, 0x01, 0x02, 0x00, 0x02, 0,
        ];

        assert_eq!(
            parse_bulk_endpoints(desc),
            Some(BulkEndpoints {
                interface: 0,
                ep_in: 0x81,
                ep_out: 0x01,
            })
        );
    }

    #[test]
    fn parse_bulk_endpoints_tracks_interface_and_out_before_in() {
        #[rustfmt::skip]
        let desc: &[u8] = &[
            // Interface 0: Interrupt only (cannot satisfy Bulk IN + OUT pair)
            9, 4, 0, 0, 1, 0x03, 0x00, 0x00, 0,
            7, 5, 0x81, 0x03, 64, 0, 1,
            // Interface 1: Bulk IN only (should reset when entering Interface 2)
            9, 4, 1, 0, 1, 0xFF, 0x00, 0x00, 0,
            7, 5, 0x82, 0x02, 0x00, 0x02, 0,
            // Interface 2: Bulk OUT 0x03 followed by Bulk IN 0x84
            9, 4, 2, 0, 2, 0xFF, 0xFF, 0x00, 0,
            7, 5, 0x03, 0x02, 0x00, 0x02, 0,
            7, 5, 0x84, 0x02, 0x00, 0x02, 0,
        ];

        assert_eq!(
            parse_bulk_endpoints(desc),
            Some(BulkEndpoints {
                interface: 2,
                ep_in: 0x84,
                ep_out: 0x03,
            })
        );
    }

    #[test]
    fn parse_bulk_endpoints_aoa_plus_adb_selects_first_bulk_interface() {
        #[rustfmt::skip]
        let desc: &[u8] = &[
            // Interface 0: AOA Accessory
            9, 4, 0, 0, 2, 0xFF, 0xFF, 0x00, 0,
            7, 5, 0x81, 0x02, 0x00, 0x02, 0,
            7, 5, 0x01, 0x02, 0x00, 0x02, 0,
            // Interface 1: ADB
            9, 4, 1, 0, 2, 0xFF, 0x42, 0x01, 0,
            7, 5, 0x82, 0x02, 0x00, 0x02, 0,
            7, 5, 0x02, 0x02, 0x00, 0x02, 0,
        ];

        assert_eq!(
            parse_bulk_endpoints(desc),
            Some(BulkEndpoints {
                interface: 0,
                ep_in: 0x81,
                ep_out: 0x01,
            })
        );
    }

    #[test]
    fn parse_bulk_endpoints_handles_malformed_or_incomplete_descriptors() {
        assert_eq!(parse_bulk_endpoints(&[]), None);
        assert_eq!(parse_bulk_endpoints(&[0, 4, 0]), None); // bLength == 0
        assert_eq!(parse_bulk_endpoints(&[1, 4]), None); // bLength < 2
        assert_eq!(parse_bulk_endpoints(&[9, 4, 0, 0]), None); // truncated
    }

    #[test]
    fn android_vid_prioritization_sorts_android_vendors_first() {
        let mut devices = vec![
            make_device_info(0x056a, 0x5148, 1, 5), // Wacom
            make_device_info(0x04e8, 0x6860, 1, 6), // Samsung
            make_device_info(0x8087, 0x0a2b, 1, 2), // Intel BT
            make_device_info(0x2717, 0xff40, 1, 4), // Xiaomi
            make_device_info(0x18d1, 0x4ee7, 2, 3), // Google Pixel
        ];

        prioritize_android_candidates(&mut devices);

        let vids: Vec<u16> = devices.iter().map(|d| d.vid).collect();
        assert_eq!(vids[..3], [0x2717, 0x04e8, 0x18d1]);
        assert_eq!(vids[3..], [0x8087, 0x056a]);
    }

    #[test]
    fn udev_rules_content_covers_aoa_pids_and_all_android_vids() {
        assert_eq!(UDEV_RULES_FILENAME, "70-camcraft-aoa.rules");
        assert!(UDEV_RULES_CONTENT.contains("MODE=\"0660\""));
        assert!(UDEV_RULES_CONTENT.contains("TAG+=\"uaccess\""));

        for &pid in AOA_PIDS {
            let pid_pattern = format!(
                "ATTR{{idVendor}}==\"18d1\", ATTR{{idProduct}}==\"{:04x}\"",
                pid
            );
            assert!(
                UDEV_RULES_CONTENT.contains(&pid_pattern),
                "missing AOA PID rule for {pid_pattern}"
            );
        }

        for &vid in ANDROID_VENDOR_IDS {
            let vid_pattern = format!("ATTR{{idVendor}}==\"{:04x}\"", vid);
            assert!(
                UDEV_RULES_CONTENT.contains(&vid_pattern),
                "missing Android OEM VID rule for {vid_pattern}"
            );
        }
    }

    #[test]
    fn is_udev_rule_installed_in_checks_rule_filename() {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let root =
            std::env::temp_dir().join(format!("camcraft-udev-test-{}-{nanos}", std::process::id()));
        let empty_dir = root.join("empty");
        let populated_dir = root.join("populated");
        fs::create_dir_all(&empty_dir).expect("create empty dir");
        fs::create_dir_all(&populated_dir).expect("create populated dir");

        assert!(!is_udev_rule_installed_in(&[empty_dir.as_path()]));

        fs::write(populated_dir.join(UDEV_RULES_FILENAME), UDEV_RULES_CONTENT)
            .expect("write test udev rules");
        assert!(is_udev_rule_installed_in(&[
            empty_dir.as_path(),
            populated_dir.as_path()
        ]));

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn enumerate_usb_devices_in_skips_interfaces_and_root_hubs() {
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_nanos())
            .unwrap_or(0);
        let root = std::env::temp_dir().join(format!(
            "camcraft-sysfs-test-{}-{nanos}",
            std::process::id()
        ));
        fs::create_dir_all(root.join("usb1")).expect("create usb1");
        fs::create_dir_all(root.join("1-2:1.0")).expect("create interface dir");
        fs::create_dir_all(root.join("1-1")).expect("create hub dir");
        fs::create_dir_all(root.join("1-2")).expect("create phone dir");

        // Root hub entry (should be skipped because name starts with `usb`)
        fs::write(root.join("usb1/idVendor"), "1d6b\n").expect("write usb1 vid");
        fs::write(root.join("usb1/idProduct"), "0002\n").expect("write usb1 pid");
        fs::write(root.join("usb1/busnum"), "1\n").expect("write usb1 busnum");
        fs::write(root.join("usb1/devnum"), "1\n").expect("write usb1 devnum");

        // `1d6b` entry without `usb*` prefix (still skipped via VID check)
        fs::write(root.join("1-1/idVendor"), "1d6b\n").expect("write 1-1 vid");
        fs::write(root.join("1-1/idProduct"), "0003\n").expect("write 1-1 pid");
        fs::write(root.join("1-1/busnum"), "1\n").expect("write 1-1 busnum");
        fs::write(root.join("1-1/devnum"), "2\n").expect("write 1-1 devnum");

        // Valid device entry
        let phone = root.join("1-2");
        fs::write(phone.join("idVendor"), "18d1\n").expect("write vid");
        fs::write(phone.join("idProduct"), "2d01\n").expect("write pid");
        fs::write(phone.join("busnum"), "1\n").expect("write busnum");
        fs::write(phone.join("devnum"), "7\n").expect("write devnum");
        fs::write(phone.join("bDeviceClass"), "00\n").expect("write class");
        fs::write(phone.join("product"), "Pixel 8\n").expect("write product");
        fs::write(phone.join("manufacturer"), "Google\n").expect("write manufacturer");
        fs::write(phone.join("serial"), "ABC123\n").expect("write serial");
        fs::write(
            phone.join("descriptors"),
            [
                9, 4, 0, 0, 2, 0xFF, 0xFF, 0x00, 0, 7, 5, 0x81, 0x02, 0x00, 0x02, 0, 7, 5, 0x01,
                0x02, 0x00, 0x02, 0,
            ],
        )
        .expect("write descriptors");

        let found = enumerate_usb_devices_in(&root);
        assert_eq!(found.len(), 1);
        let dev = &found[0];
        assert_eq!(dev.vid, 0x18d1);
        assert_eq!(dev.pid, 0x2d01);
        assert_eq!(dev.busnum, 1);
        assert_eq!(dev.devnum, 7);
        assert_eq!(dev.product.as_deref(), Some("Pixel 8"));
        assert_eq!(dev.manufacturer.as_deref(), Some("Google"));
        assert_eq!(dev.serial.as_deref(), Some("ABC123"));
        assert_eq!(
            dev.dev_path().as_os_str().as_bytes(),
            b"/dev/bus/usb/001/007"
        );
        assert_eq!(
            parse_bulk_endpoints(&dev.descriptors),
            Some(BulkEndpoints {
                interface: 0,
                ep_in: 0x81,
                ep_out: 0x01,
            })
        );

        let _ = fs::remove_dir_all(&root);
    }

    #[test]
    fn open_usb_device_sets_fd_cloexec() {
        let file = open_usb_device(Path::new("/dev/null")).expect("open /dev/null");
        // SAFETY: `file.as_raw_fd()` is a valid open fd and `F_GETFD` reads its flags.
        let fd_flags = unsafe { libc::fcntl(file.as_raw_fd(), libc::F_GETFD) };
        assert!(fd_flags >= 0, "fcntl(F_GETFD) should succeed");
        assert_ne!(
            fd_flags & libc::FD_CLOEXEC,
            0,
            "opened usbdevfs fd must set O_CLOEXEC/FD_CLOEXEC"
        );
    }

    #[test]
    fn urb_completion_forwards_partial_bytes_on_cancel_and_timeout() {
        assert_eq!(
            classify_reaped_urb(0, 1024, AOA_MAX_BULK_CHUNK),
            UrbCompletion::Continue { bytes: 1024 }
        );
        assert_eq!(
            classify_reaped_urb(-libc::ENOENT, 512, AOA_MAX_BULK_CHUNK),
            UrbCompletion::Continue { bytes: 512 }
        );
        assert_eq!(
            classify_reaped_urb(-libc::ECONNRESET, 1024, AOA_MAX_BULK_CHUNK),
            UrbCompletion::Continue { bytes: 1024 }
        );
        assert_eq!(
            classify_reaped_urb(-libc::ETIMEDOUT, 512, AOA_MAX_BULK_CHUNK),
            UrbCompletion::Continue { bytes: 512 }
        );
        assert_eq!(
            classify_reaped_urb(-libc::ENOENT, 0, AOA_MAX_BULK_CHUNK),
            UrbCompletion::Continue { bytes: 0 }
        );
        assert_eq!(
            classify_reaped_urb(-libc::ENODEV, 512, AOA_MAX_BULK_CHUNK),
            UrbCompletion::Fatal {
                bytes: 512,
                os_error: libc::ENODEV
            }
        );
        assert_eq!(
            classify_reaped_urb(-libc::EPIPE, -5, AOA_MAX_BULK_CHUNK),
            UrbCompletion::Fatal {
                bytes: 0,
                os_error: libc::EPIPE
            }
        );
    }

    #[test]
    fn bulk_out_error_classification_and_retry_shutdown() {
        assert_eq!(
            classify_bulk_out_error(&std::io::Error::from_raw_os_error(libc::ETIMEDOUT)),
            BulkOutErrorKind::Retryable
        );
        assert_eq!(
            classify_bulk_out_error(&std::io::Error::from_raw_os_error(libc::EAGAIN)),
            BulkOutErrorKind::Retryable
        );
        assert_eq!(
            classify_bulk_out_error(&std::io::Error::from_raw_os_error(libc::EINTR)),
            BulkOutErrorKind::Retryable
        );
        assert_eq!(
            classify_bulk_out_error(&std::io::Error::from_raw_os_error(libc::ENODEV)),
            BulkOutErrorKind::Fatal
        );
        assert_eq!(
            classify_bulk_out_error(&std::io::Error::from_raw_os_error(libc::ESHUTDOWN)),
            BulkOutErrorKind::Fatal
        );
        assert_eq!(
            classify_bulk_out_error(&std::io::Error::from_raw_os_error(libc::EPIPE)),
            BulkOutErrorKind::Fatal
        );

        const { assert!(BULK_OUT_TIMEOUT_MS <= 500) };

        // Retries transient ETIMEDOUT/EAGAIN/EINTR and sends remaining tail.
        let shutdown = AtomicBool::new(false);
        let worker_stop = AtomicBool::new(false);
        let payload = b"hello aoa";
        let mut attempt = 0usize;
        let mut received = Vec::new();
        let res = write_bulk_out_chunked_with(payload, &shutdown, &worker_stop, |slice| {
            attempt += 1;
            match attempt {
                1 => Err(std::io::Error::from_raw_os_error(libc::ETIMEDOUT)),
                2 => Err(std::io::Error::from_raw_os_error(libc::EAGAIN)),
                3 => {
                    received.extend_from_slice(&slice[..5]);
                    Ok(5)
                }
                4 => Err(std::io::Error::from_raw_os_error(libc::EINTR)),
                _ => {
                    received.extend_from_slice(slice);
                    Ok(slice.len())
                }
            }
        });
        assert!(res.is_ok(), "retryable errors should recover: {res:?}");
        assert_eq!(received, payload);

        // Fatal error aborts immediately without retrying.
        let mut fatal_attempts = 0usize;
        let fatal_res = write_bulk_out_chunked_with(payload, &shutdown, &worker_stop, |_| {
            fatal_attempts += 1;
            Err(std::io::Error::from_raw_os_error(libc::ENODEV))
        });
        assert!(fatal_res.is_err());
        assert_eq!(fatal_attempts, 1);

        // Retry stops promptly when `shutdown` or `worker_stop` becomes true.
        let mut stop_attempts = 0usize;
        let stop_res = write_bulk_out_chunked_with(payload, &shutdown, &worker_stop, |_| {
            stop_attempts += 1;
            if stop_attempts == 2 {
                worker_stop.store(true, Ordering::Relaxed);
            }
            Err(std::io::Error::from_raw_os_error(libc::ETIMEDOUT))
        });
        assert!(stop_res.is_err());
        assert_eq!(stop_attempts, 2);
    }

    #[test]
    fn connect_aoa_device_with_shutdown_aborts_promptly_when_preset() {
        let shutdown = AtomicBool::new(true);
        let start = Instant::now();
        let res = connect_aoa_device_with_shutdown(&shutdown);
        assert!(matches!(res, Err(UsbAoaError::Disconnected)));
        assert!(
            start.elapsed() < Duration::from_millis(100),
            "pre-set shutdown should abort without sleeping/probing"
        );
    }
}
