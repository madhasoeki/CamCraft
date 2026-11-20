//! Core receiver pipeline for CamCraft desktop (Fedora Linux).
//!
//! Modules follow `docs/DESIGN.md` §3.1 (`net`, `adb`, `decoder`, `vcam`, `vmic`)
//! and will be implemented incrementally across Stages 2-8.

use thiserror::Error;

/// Core pipeline error type.
#[derive(Debug, Error)]
pub enum CoreError {
    #[error("protocol error: {0}")]
    Protocol(#[from] camcraft_protocol::ProtocolError),

    #[error("ADB error: {0}")]
    Adb(#[from] adb::AdbError),

    #[error("client network error: {0}")]
    Client(#[from] client::ClientError),

    #[error("video decoder error: {0}")]
    Decoder(#[from] decoder::DecoderError),

    #[error("mDNS discovery error: {0}")]
    Discovery(#[from] discovery::DiscoveryError),

    #[error("virtual camera error: {0}")]
    Vcam(#[from] vcam::VcamError),

    #[error("virtual microphone error: {0}")]
    Vmic(#[from] vmic::VmicError),

    #[error("I/O error: {0}")]
    Io(#[from] std::io::Error),

    #[error("operation not yet implemented in Stage 0 scaffolding: {0}")]
    Unimplemented(&'static str),
}

/// Platform-independent sink abstraction for decoded video frames (`docs/PRD.md` §6.3).
pub trait VideoOutputSink: Send {
    /// Writes one decoded raw video frame (e.g., YUV420p/YUYV) to the virtual camera device.
    fn write_frame(&mut self, width: u32, height: u32, data: &[u8]) -> Result<(), CoreError>;
}

/// Platform-independent sink abstraction for decoded audio samples (`docs/PRD.md` §6.3).
pub trait AudioOutputSink: Send {
    /// Writes decoded S16LE PCM audio samples to the virtual microphone device.
    fn write_samples(
        &mut self,
        sample_rate: u32,
        channels: u16,
        pcm_s16le: &[u8],
    ) -> Result<(), CoreError>;
}

/// Asynchronous network client & CCFT demuxer (Stage 2+).
pub mod net {
    use std::collections::HashSet;
    use std::net::Ipv4Addr;

    use tracing::debug;

    /// An active, non-loopback local IPv4 network interface on this host.
    #[derive(Debug, Clone, PartialEq, Eq)]
    pub struct LocalIpv4Interface {
        pub name: String,
        pub ip: Ipv4Addr,
        pub netmask: Ipv4Addr,
        pub is_multicast: bool,
    }

    impl LocalIpv4Interface {
        /// Returns `true` if `target` falls within this interface's IPv4 subnet.
        #[must_use]
        pub fn contains_ip(&self, target: Ipv4Addr) -> bool {
            let mask = u32::from(self.netmask);
            mask != 0 && (u32::from(self.ip) & mask) == (u32::from(target) & mask)
        }

        /// Returns `true` for Linux wireless interfaces (`wl*`, e.g. `wlp2s0`, `wlan0`).
        #[must_use]
        pub fn is_wireless(&self) -> bool {
            self.name.starts_with("wl")
        }
    }

    /// Returns the default CamCraft TCP endpoint address for USB ADB forwarding.
    #[must_use]
    pub fn usb_loopback_addr() -> String {
        debug!("Resolving default USB loopback endpoint");
        format!("127.0.0.1:{}", camcraft_protocol::DEFAULT_PORT)
    }

    /// Enumerates active, non-loopback IPv4 interfaces on this host.
    #[cfg(unix)]
    #[must_use]
    pub fn list_active_ipv4_interfaces() -> Vec<LocalIpv4Interface> {
        let mut addrs_ptr: *mut libc::ifaddrs = std::ptr::null_mut();
        // SAFETY: `addrs_ptr` is a valid aligned stack slot passed to `getifaddrs(3)`, which
        // initializes it on success (`rc == 0`) with a linked list freed by `freeifaddrs(3)`.
        let rc = unsafe { libc::getifaddrs(&raw mut addrs_ptr) };
        if rc != 0 || addrs_ptr.is_null() {
            return Vec::new();
        }

        struct IfAddrsGuard(*mut libc::ifaddrs);
        impl Drop for IfAddrsGuard {
            fn drop(&mut self) {
                // SAFETY: `self.0` was allocated by a successful `libc::getifaddrs` call and is
                // freed exactly once here when the guard drops.
                unsafe {
                    libc::freeifaddrs(self.0);
                }
            }
        }
        let _guard = IfAddrsGuard(addrs_ptr);

        let mut result = Vec::new();
        let mut seen = HashSet::new();
        let mut cur = addrs_ptr;
        while !cur.is_null() {
            // SAFETY: `cur` is a non-null node in the valid `ifaddrs` list owned by `_guard`.
            let ifa = unsafe { &*cur };
            let flags = ifa.ifa_flags as libc::c_int;
            let is_up = (flags & libc::IFF_UP) != 0;
            let is_loopback = (flags & libc::IFF_LOOPBACK) != 0;
            let is_multicast = (flags & libc::IFF_MULTICAST) != 0;

            if is_up && !is_loopback && !ifa.ifa_addr.is_null() && !ifa.ifa_name.is_null() {
                // SAFETY: `ifa.ifa_addr` is non-null and points to a valid `sockaddr` header
                // within the `ifaddrs` list owned by `_guard`.
                let family = unsafe { (*ifa.ifa_addr).sa_family as libc::c_int };
                if family == libc::AF_INET {
                    // SAFETY: `sa_family == AF_INET` guarantees `ifa.ifa_addr` points to a
                    // `libc::sockaddr_in`; `read_unaligned` avoids alignment assumptions.
                    let sin = unsafe {
                        std::ptr::read_unaligned(ifa.ifa_addr.cast::<libc::sockaddr_in>())
                    };
                    let ip = Ipv4Addr::from(sin.sin_addr.s_addr.to_ne_bytes());
                    if !ip.is_loopback() && !ip.is_unspecified() {
                        // SAFETY: `ifa.ifa_name` was verified non-null and points to a
                        // NUL-terminated C string inside the `ifaddrs` node owned by `_guard`.
                        let name = unsafe { std::ffi::CStr::from_ptr(ifa.ifa_name) }
                            .to_string_lossy()
                            .into_owned();
                        let netmask = if !ifa.ifa_netmask.is_null() {
                            // SAFETY: `ifa.ifa_netmask` is non-null and within the `ifaddrs` node
                            // owned by `_guard`, starting with a valid `sa_family` header.
                            let mask_family =
                                unsafe { (*ifa.ifa_netmask).sa_family as libc::c_int };
                            if mask_family == libc::AF_INET {
                                // SAFETY: `mask_family == AF_INET` guarantees `ifa.ifa_netmask`
                                // points to a `libc::sockaddr_in`; `read_unaligned` avoids alignment issues.
                                let mask_sin = unsafe {
                                    std::ptr::read_unaligned(
                                        ifa.ifa_netmask.cast::<libc::sockaddr_in>(),
                                    )
                                };
                                Ipv4Addr::from(mask_sin.sin_addr.s_addr.to_ne_bytes())
                            } else {
                                Ipv4Addr::UNSPECIFIED
                            }
                        } else {
                            Ipv4Addr::UNSPECIFIED
                        };

                        if seen.insert((name.clone(), ip)) {
                            result.push(LocalIpv4Interface {
                                name,
                                ip,
                                netmask,
                                is_multicast,
                            });
                        }
                    }
                }
            }
            cur = ifa.ifa_next;
        }

        result
    }

    /// Enumerates active, non-loopback IPv4 interfaces on this host.
    #[cfg(not(unix))]
    #[must_use]
    pub fn list_active_ipv4_interfaces() -> Vec<LocalIpv4Interface> {
        Vec::new()
    }

    /// Returns interfaces whose subnet contains `target`, deduplicated by name and sorted
    /// with wireless interfaces (`wl*`) prioritized before wired interfaces.
    #[must_use]
    pub fn matching_interfaces_for_target(
        ifaces: &[LocalIpv4Interface],
        target: Ipv4Addr,
    ) -> Vec<LocalIpv4Interface> {
        if target.is_loopback() || target.is_unspecified() {
            return Vec::new();
        }

        let mut matched = Vec::new();
        let mut seen_names = HashSet::new();
        for iface in ifaces {
            if iface.contains_ip(target) && seen_names.insert(iface.name.clone()) {
                matched.push(iface.clone());
            }
        }

        matched.sort_by(|a, b| {
            b.is_wireless()
                .cmp(&a.is_wireless())
                .then_with(|| a.name.cmp(&b.name))
        });
        matched
    }
}

/// ADB tunnel helper (`adb forward`) (Stage 2+).
pub mod adb;

/// Asynchronous network client & CCFT demuxer (Stage 2+).
pub mod client;

/// Bounded video/audio frame queues and H.264/Opus decoders (Stage 4 / Stage 7).
pub mod decoder;

/// LAN mDNS service discovery for `_camcraft._tcp.local.` (Stage 6).
pub mod discovery;

/// Linux `v4l2loopback` virtual camera sink (Stage 4+).
pub mod vcam;

/// PipeWire / PulseAudio virtual microphone sink (Stage 7+).
pub mod vmic;

#[cfg(test)]
mod tests {
    use std::net::Ipv4Addr;

    use super::*;

    #[test]
    fn bounded_channel_preserves_realtime_capacity() {
        assert_eq!(net::usb_loopback_addr(), "127.0.0.1:9876");
        let (tx, rx) = decoder::bounded_video_channel::<u8>();
        assert!(tx.try_send(1).is_ok());
        assert_eq!(rx.try_recv().ok(), Some(1));
    }

    #[test]
    fn local_interface_subnet_and_dual_nic_matching() {
        let mask_24 = Ipv4Addr::new(255, 255, 255, 0);
        let mask_16 = Ipv4Addr::new(255, 255, 0, 0);
        let ifaces = vec![
            net::LocalIpv4Interface {
                name: "eth0".to_owned(),
                ip: Ipv4Addr::new(192, 168, 1, 19),
                netmask: mask_24,
                is_multicast: true,
            },
            net::LocalIpv4Interface {
                name: "br-docker".to_owned(),
                ip: Ipv4Addr::new(172, 20, 0, 1),
                netmask: mask_16,
                is_multicast: true,
            },
            net::LocalIpv4Interface {
                name: "wlp2s0".to_owned(),
                ip: Ipv4Addr::new(192, 168, 1, 31),
                netmask: mask_24,
                is_multicast: true,
            },
            net::LocalIpv4Interface {
                name: "wlp2s0".to_owned(),
                ip: Ipv4Addr::new(192, 168, 1, 32),
                netmask: mask_24,
                is_multicast: true,
            },
        ];

        assert!(ifaces[0].contains_ip(Ipv4Addr::new(192, 168, 1, 7)));
        assert!(!ifaces[1].contains_ip(Ipv4Addr::new(192, 168, 1, 7)));

        let matched = net::matching_interfaces_for_target(&ifaces, Ipv4Addr::new(192, 168, 1, 7));
        let names: Vec<&str> = matched.iter().map(|i| i.name.as_str()).collect();
        assert_eq!(names, vec!["wlp2s0", "eth0"]);

        assert!(
            net::matching_interfaces_for_target(&ifaces, Ipv4Addr::LOCALHOST).is_empty(),
            "loopback target must not bind to LAN/Wi-Fi interfaces"
        );
    }
}
