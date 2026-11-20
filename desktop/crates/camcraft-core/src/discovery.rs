//! Lightweight, zero-panic RFC 6762 / RFC 6763 UDP mDNS scanner for CamCraft.
//!
//! Discovers Android CamCraft senders advertising `_camcraft._tcp.local.` on the local
//! IPv4 network without relying on `avahi-daemon` DBus bindings or external runtime services.

use std::collections::{HashMap, HashSet};
use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use crossbeam_channel::Sender;
use socket2::{Domain, Protocol, SockAddr, SockRef, Socket, Type};
use thiserror::Error;
use tracing::{debug, info, trace, warn};

/// DNS-SD service type advertised by Android `NsdManager`.
pub const MDNS_SERVICE_TYPE: &str = "_camcraft._tcp.local.";

/// Standard IPv4 mDNS link-local multicast group (`224.0.0.251`).
pub const MDNS_MULTICAST_V4: Ipv4Addr = Ipv4Addr::new(224, 0, 0, 251);

/// Standard mDNS UDP port (`5353`).
pub const MDNS_PORT: u16 = 5353;

const QTYPE_A: u16 = 1;
const QTYPE_PTR: u16 = 12;
const QTYPE_TXT: u16 = 16;
const QTYPE_SRV: u16 = 33;

const CLASS_IN: u16 = 1;
const CLASS_UNICAST_BIT: u16 = 0x8000;
const CLASS_MASK: u16 = 0x7FFF;

const MAX_COMPRESSION_JUMPS: usize = 32;
const MAX_DECODED_NAME_LEN: usize = 1024;
const MAX_CACHED_ADDRS: usize = 512;
const FOLLOWUP_COOLDOWN: Duration = Duration::from_secs(1);
const PERIODIC_QUERY_INTERVAL: Duration = Duration::from_secs(5);
const EVICTION_CHECK_INTERVAL: Duration = Duration::from_secs(1);
const RECV_TIMEOUT: Duration = Duration::from_millis(250);

/// Errors emitted by the mDNS discovery subsystem.
#[derive(Debug, Error)]
pub enum DiscoveryError {
    /// Socket creation, binding, multicast membership, or UDP I/O failure.
    #[error("mDNS socket I/O error: {0}")]
    Io(#[from] std::io::Error),

    /// Malformed or truncated DNS/mDNS wire packet.
    #[error("malformed DNS/mDNS packet: {0}")]
    MalformedPacket(&'static str),
}

/// A CamCraft Android sender discovered on the local Wi-Fi/LAN network.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DiscoveredDevice {
    /// Full DNS-SD instance name (e.g. `"Pixel 8 Pro._camcraft._tcp.local."`).
    pub service_fullname: String,
    /// Human-friendly display name (from TXT `device=` or instance label).
    pub device_name: String,
    /// Resolved IPv4 address of the Android sender.
    pub ip: Ipv4Addr,
    /// TCP port where the Android CCFT server is listening.
    pub port: u16,
    /// Target mDNS hostname from SRV record (e.g. `"android-1234.local."`).
    pub hostname: String,
    /// Optional CCFT protocol version from TXT `ver=` attribute.
    pub protocol_version: Option<u8>,
}

impl DiscoveredDevice {
    /// Formats the `IP:PORT` TCP endpoint string for `ClientConfig`.
    #[must_use]
    pub fn endpoint(&self) -> String {
        format!("{}:{}", self.ip, self.port)
    }
}

/// Discovery lifecycle events sent to CLI / GUI subscribers.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DiscoveryEvent {
    /// A new device was discovered or an existing device's metadata/IP changed.
    Found(DiscoveredDevice),
    /// A previously discovered device sent a TTL=0 goodbye packet or timed out.
    Removed { service_fullname: String },
}

/// Parsed DNS resource record payload variants relevant to mDNS service discovery.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DnsRecordData {
    Ptr(String),
    Srv {
        priority: u16,
        weight: u16,
        port: u16,
        target: String,
    },
    Txt(Vec<(String, Option<String>)>),
    A(Ipv4Addr),
}

/// A parsed DNS resource record from an mDNS response packet.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DnsRecord {
    pub name: String,
    pub ttl: u32,
    pub data: DnsRecordData,
}

/// Background UDP mDNS scanner thread handle.
pub struct MdnsScanner {
    stop_flag: Arc<AtomicBool>,
    trigger_flag: Arc<AtomicBool>,
    handle: Option<JoinHandle<()>>,
}

impl MdnsScanner {
    /// Opens an mDNS UDP socket, joins multicast groups on active IPv4 interfaces,
    /// and starts the `camcraft-mdns` background scanner thread.
    pub fn start(event_tx: Sender<DiscoveryEvent>) -> Result<Self, DiscoveryError> {
        let (socket, bound_to_mdns_port) = open_mdns_socket()?;
        let qu_socket = if bound_to_mdns_port {
            open_qu_socket()
        } else {
            None
        };
        let stop_flag = Arc::new(AtomicBool::new(false));
        let trigger_flag = Arc::new(AtomicBool::new(false));

        let stop_c = stop_flag.clone();
        let trigger_c = trigger_flag.clone();

        let handle = std::thread::Builder::new()
            .name("camcraft-mdns".to_owned())
            .spawn(move || {
                run_scanner_loop(
                    socket,
                    qu_socket,
                    bound_to_mdns_port,
                    event_tx,
                    stop_c,
                    trigger_c,
                );
            })?;

        Ok(Self {
            stop_flag,
            trigger_flag,
            handle: Some(handle),
        })
    }

    /// Schedules an immediate active mDNS scan burst.
    pub fn trigger_scan(&self) {
        self.trigger_flag.store(true, Ordering::Release);
    }

    /// Signals the scanner thread to exit and waits for it to join cleanly.
    pub fn stop(&mut self) {
        self.stop_flag.store(true, Ordering::Release);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for MdnsScanner {
    fn drop(&mut self) {
        self.stop();
    }
}

fn open_mdns_socket() -> Result<(UdpSocket, bool), DiscoveryError> {
    let socket = Socket::new(Domain::IPV4, Type::DGRAM, Some(Protocol::UDP))?;
    socket.set_reuse_address(true)?;
    #[cfg(unix)]
    socket.set_reuse_port(true)?;

    let bind_5353 = SockAddr::from(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, MDNS_PORT));
    let bound_to_mdns_port = match socket.bind(&bind_5353) {
        Ok(()) => {
            info!(port = MDNS_PORT, "Bound mDNS UDP socket to port 5353");
            true
        }
        Err(err) => {
            warn!(
                error = %err,
                "Could not bind mDNS port 5353; falling back to ephemeral port with QU queries"
            );
            let bind_ephemeral = SockAddr::from(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0));
            socket.bind(&bind_ephemeral)?;
            false
        }
    };

    let udp: UdpSocket = socket.into();
    udp.set_read_timeout(Some(RECV_TIMEOUT))?;
    let _ = udp.set_multicast_loop_v4(true);

    Ok((udp, bound_to_mdns_port))
}

fn open_qu_socket() -> Option<UdpSocket> {
    let sock = UdpSocket::bind(SocketAddrV4::new(Ipv4Addr::UNSPECIFIED, 0)).ok()?;
    sock.set_nonblocking(true).ok()?;
    let _ = sock.set_multicast_loop_v4(true);
    Some(sock)
}

fn active_multicast_ipv4_interfaces() -> Vec<Ipv4Addr> {
    let mut result = Vec::new();
    for iface in crate::net::list_active_ipv4_interfaces() {
        if iface.is_multicast && !result.contains(&iface.ip) {
            result.push(iface.ip);
        }
    }
    result
}

fn sync_multicast_memberships(socket: &UdpSocket, joined: &mut HashSet<Ipv4Addr>) -> Vec<Ipv4Addr> {
    let ifaces = active_multicast_ipv4_interfaces();
    if ifaces.is_empty() {
        if joined.insert(Ipv4Addr::UNSPECIFIED)
            && let Err(e) = socket.join_multicast_v4(&MDNS_MULTICAST_V4, &Ipv4Addr::UNSPECIFIED)
        {
            debug!(error = %e, "Multicast join on unspecified interface failed");
        }
        return vec![Ipv4Addr::UNSPECIFIED];
    }

    let mut joined_any = false;
    for &iface in &ifaces {
        if joined.insert(iface) {
            match socket.join_multicast_v4(&MDNS_MULTICAST_V4, &iface) {
                Ok(()) => {
                    debug!(%iface, group = %MDNS_MULTICAST_V4, "Joined mDNS multicast group");
                    joined_any = true;
                }
                Err(e) => {
                    debug!(%iface, error = %e, "Failed to join mDNS multicast on interface");
                }
            }
        } else {
            joined_any = true;
        }
    }

    if !joined_any && joined.insert(Ipv4Addr::UNSPECIFIED) {
        let _ = socket.join_multicast_v4(&MDNS_MULTICAST_V4, &Ipv4Addr::UNSPECIFIED);
    }

    ifaces
}

fn send_mdns_query_all_ifaces(
    socket: &UdpSocket,
    ifaces: &[Ipv4Addr],
    qname: &str,
    qtype: u16,
    unicast_response: bool,
) {
    let query = build_mdns_query(qname, qtype, unicast_response);
    let dest = SocketAddrV4::new(MDNS_MULTICAST_V4, MDNS_PORT);

    let sock_ref = SockRef::from(socket);

    if ifaces.is_empty() {
        let _ = sock_ref.set_multicast_if_v4(&Ipv4Addr::UNSPECIFIED);
        let _ = socket.send_to(&query, dest);
        return;
    }

    for &iface in ifaces {
        if let Err(e) = sock_ref.set_multicast_if_v4(&iface) {
            trace!(%iface, error = %e, "Failed to set multicast outgoing IPv4 interface");
            continue;
        }
        if let Err(e) = socket.send_to(&query, dest) {
            trace!(%iface, qname, qtype, error = %e, "Failed to send mDNS query");
        }
    }
}

struct ScannerSockets<'a> {
    primary: &'a UdpSocket,
    qu: Option<&'a UdpSocket>,
    bound_to_mdns_port: bool,
}

impl ScannerSockets<'_> {
    fn send_query(&self, ifaces: &[Ipv4Addr], qname: &str, qtype: u16, include_qu: bool) {
        if self.bound_to_mdns_port {
            send_mdns_query_all_ifaces(self.primary, ifaces, qname, qtype, false);
            if include_qu {
                // Send QU from a dedicated ephemeral socket so SO_REUSEPORT peers (avahi/adb)
                // on port 5353 do not intercept unicast responses back from Android phones.
                if let Some(qu) = self.qu {
                    send_mdns_query_all_ifaces(qu, ifaces, qname, qtype, true);
                } else {
                    send_mdns_query_all_ifaces(self.primary, ifaces, qname, qtype, true);
                }
            }
        } else {
            send_mdns_query_all_ifaces(self.primary, ifaces, qname, qtype, true);
        }
    }
}

fn process_incoming_packet(
    packet: &[u8],
    src_addr: SocketAddr,
    cache: &mut DiscoveryCache,
    event_tx: &Sender<DiscoveryEvent>,
    sockets: &ScannerSockets<'_>,
    active_ifaces: &[Ipv4Addr],
) -> bool {
    let src_ip = match src_addr {
        SocketAddr::V4(v4) => Some(*v4.ip()),
        SocketAddr::V6(_) => None,
    };
    match parse_mdns_response(packet) {
        Ok(records) if !records.is_empty() => {
            let outcome = cache.ingest(&records, src_ip, Instant::now());
            for ev in outcome.events {
                if event_tx.send(ev).is_err() {
                    return false;
                }
            }
            for (qname, qtype) in outcome.follow_up_queries {
                sockets.send_query(active_ifaces, &qname, qtype, sockets.qu.is_some());
            }
        }
        Ok(_) => {}
        Err(e) => {
            trace!(error = %e, bytes = packet.len(), "Ignoring malformed mDNS packet");
        }
    }
    true
}

fn run_scanner_loop(
    socket: UdpSocket,
    qu_socket: Option<UdpSocket>,
    bound_to_mdns_port: bool,
    event_tx: Sender<DiscoveryEvent>,
    stop_flag: Arc<AtomicBool>,
    trigger_flag: Arc<AtomicBool>,
) {
    let sockets = ScannerSockets {
        primary: &socket,
        qu: qu_socket.as_ref(),
        bound_to_mdns_port,
    };
    let mut joined_ifaces = HashSet::new();
    let mut active_ifaces = sync_multicast_memberships(&socket, &mut joined_ifaces);
    let mut cache = DiscoveryCache::new();
    let mut buf = [0u8; 4096];

    let start = Instant::now();
    // Initial query schedule: 0s, 1s, 3s, then every 5s.
    let mut initial_stage: u8 = 0;
    let mut next_query_at = start;
    let mut last_evict_check = start;

    while !stop_flag.load(Ordering::Acquire) {
        let now = Instant::now();

        if trigger_flag.swap(false, Ordering::AcqRel) {
            active_ifaces = sync_multicast_memberships(&socket, &mut joined_ifaces);
            sockets.send_query(&active_ifaces, MDNS_SERVICE_TYPE, QTYPE_PTR, true);
            next_query_at = now + PERIODIC_QUERY_INTERVAL;
            initial_stage = 3;
        } else if now >= next_query_at {
            active_ifaces = sync_multicast_memberships(&socket, &mut joined_ifaces);
            let is_initial = initial_stage < 3;
            sockets.send_query(&active_ifaces, MDNS_SERVICE_TYPE, QTYPE_PTR, is_initial);
            match initial_stage {
                0 => {
                    initial_stage = 1;
                    next_query_at = start + Duration::from_secs(1);
                }
                1 => {
                    initial_stage = 2;
                    next_query_at = start + Duration::from_secs(3);
                }
                _ => {
                    initial_stage = 3;
                    next_query_at = now + PERIODIC_QUERY_INTERVAL;
                }
            }
        }

        if now.saturating_duration_since(last_evict_check) >= EVICTION_CHECK_INTERVAL {
            last_evict_check = now;
            for ev in cache.evict_expired(now) {
                if event_tx.send(ev).is_err() {
                    return;
                }
            }
        }

        if let Some(ref qu) = qu_socket {
            loop {
                match qu.recv_from(&mut buf) {
                    Ok((len, src_addr)) => {
                        if !process_incoming_packet(
                            &buf[..len],
                            src_addr,
                            &mut cache,
                            &event_tx,
                            &sockets,
                            &active_ifaces,
                        ) {
                            return;
                        }
                    }
                    Err(e) if e.kind() == std::io::ErrorKind::WouldBlock => break,
                    Err(e) => {
                        debug!(error = %e, "mDNS QU UDP recv_from error");
                        break;
                    }
                }
            }
        }

        match socket.recv_from(&mut buf) {
            Ok((len, src_addr)) => {
                if !process_incoming_packet(
                    &buf[..len],
                    src_addr,
                    &mut cache,
                    &event_tx,
                    &sockets,
                    &active_ifaces,
                ) {
                    return;
                }
            }
            Err(e)
                if matches!(
                    e.kind(),
                    std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
                ) => {}
            Err(e) => {
                debug!(error = %e, "mDNS UDP recv_from error");
            }
        }
    }
}

/// Builds a standard RFC 6762 mDNS query packet for `qname` and `qtype`.
#[must_use]
pub fn build_mdns_query(qname: &str, qtype: u16, unicast_response: bool) -> Vec<u8> {
    let mut packet = Vec::with_capacity(32 + qname.len());
    // Header: ID=0, Flags=0, QDCOUNT=1, ANCOUNT=0, NSCOUNT=0, ARCOUNT=0
    packet.extend_from_slice(&[
        0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    ]);

    let trimmed = qname.trim_matches('.');
    if !trimmed.is_empty() {
        for label in trimmed.split('.') {
            if label.is_empty() {
                continue;
            }
            let bytes = label.as_bytes();
            let capped_len = bytes.len().min(63);
            packet.push(capped_len as u8);
            packet.extend_from_slice(&bytes[..capped_len]);
        }
    }
    packet.push(0x00);

    packet.extend_from_slice(&qtype.to_be_bytes());
    let qclass = if unicast_response {
        CLASS_IN | CLASS_UNICAST_BIT
    } else {
        CLASS_IN
    };
    packet.extend_from_slice(&qclass.to_be_bytes());

    packet
}

/// Decodes a DNS domain name starting at `offset`, supporting RFC 1035 compression pointers
/// with full bounds checks and cycle detection (`max_jumps = 32`).
pub fn decode_dns_name(packet: &[u8], offset: usize) -> Result<(String, usize), DiscoveryError> {
    if offset >= packet.len() {
        return Err(DiscoveryError::MalformedPacket(
            "DNS name offset out of bounds",
        ));
    }

    let mut out = String::new();
    let mut cursor = offset;
    let mut next_offset: Option<usize> = None;
    let mut jumps = 0usize;

    loop {
        let len_byte = *packet
            .get(cursor)
            .ok_or(DiscoveryError::MalformedPacket("truncated DNS name"))?;

        if len_byte == 0 {
            if next_offset.is_none() {
                next_offset = Some(cursor + 1);
            }
            break;
        }

        let tag = len_byte & 0xC0;
        if tag == 0xC0 {
            let low_byte = *packet
                .get(cursor + 1)
                .ok_or(DiscoveryError::MalformedPacket(
                    "truncated DNS compression pointer",
                ))?;
            let ptr = (usize::from(len_byte & 0x3F) << 8) | usize::from(low_byte);
            if ptr >= packet.len() {
                return Err(DiscoveryError::MalformedPacket(
                    "DNS compression pointer out of bounds",
                ));
            }

            jumps += 1;
            if jumps > MAX_COMPRESSION_JUMPS {
                return Err(DiscoveryError::MalformedPacket(
                    "DNS compression pointer loop detected",
                ));
            }

            if next_offset.is_none() {
                next_offset = Some(cursor + 2);
            }
            cursor = ptr;
        } else if tag != 0 {
            return Err(DiscoveryError::MalformedPacket("invalid DNS label flags"));
        } else {
            let label_len = usize::from(len_byte);
            let start = cursor + 1;
            let end = start
                .checked_add(label_len)
                .ok_or(DiscoveryError::MalformedPacket("DNS label length overflow"))?;
            let label_bytes = packet
                .get(start..end)
                .ok_or(DiscoveryError::MalformedPacket("truncated DNS label"))?;

            if out.len() + label_len + 1 > MAX_DECODED_NAME_LEN {
                return Err(DiscoveryError::MalformedPacket("decoded DNS name too long"));
            }

            let label_str = String::from_utf8_lossy(label_bytes);
            out.push_str(&label_str);
            out.push('.');
            cursor = end;
        }
    }

    if out.is_empty() {
        out.push('.');
    }

    Ok((out, next_offset.unwrap_or(cursor + 1)))
}

/// Parses an mDNS response UDP payload (`(flags & 0x8000) != 0`) into resource records.
///
/// Non-response query packets return `Ok(Vec::new())`. Masks the RFC 6762 cache-flush bit
/// (`rclass & 0x7FFF`), parses `PTR`, `SRV`, `TXT`, and `A` records across Answer, Authority,
/// and Additional sections, and skips unknown record types cleanly.
pub fn parse_mdns_response(packet: &[u8]) -> Result<Vec<DnsRecord>, DiscoveryError> {
    if packet.len() < 12 {
        return Err(DiscoveryError::MalformedPacket(
            "mDNS packet shorter than 12-byte header",
        ));
    }

    let flags = u16::from_be_bytes([packet[2], packet[3]]);
    if (flags & 0x8000) == 0 {
        return Ok(Vec::new());
    }

    let qdcount = usize::from(u16::from_be_bytes([packet[4], packet[5]]));
    let ancount = usize::from(u16::from_be_bytes([packet[6], packet[7]]));
    let nscount = usize::from(u16::from_be_bytes([packet[8], packet[9]]));
    let arcount = usize::from(u16::from_be_bytes([packet[10], packet[11]]));

    let mut cursor = 12usize;
    for _ in 0..qdcount {
        let (_, next) = decode_dns_name(packet, cursor)?;
        let qend = next
            .checked_add(4)
            .ok_or(DiscoveryError::MalformedPacket("question offset overflow"))?;
        if qend > packet.len() {
            return Err(DiscoveryError::MalformedPacket("truncated DNS question"));
        }
        cursor = qend;
    }

    let total_rrs = ancount.saturating_add(nscount).saturating_add(arcount);
    let mut records = Vec::new();

    for _ in 0..total_rrs {
        let (name, next) = decode_dns_name(packet, cursor)?;
        let header_end = next
            .checked_add(10)
            .ok_or(DiscoveryError::MalformedPacket("RR header offset overflow"))?;
        let rr_hdr = packet
            .get(next..header_end)
            .ok_or(DiscoveryError::MalformedPacket("truncated DNS RR header"))?;

        let rtype = u16::from_be_bytes([rr_hdr[0], rr_hdr[1]]);
        let rclass = u16::from_be_bytes([rr_hdr[2], rr_hdr[3]]) & CLASS_MASK;
        let ttl = u32::from_be_bytes([rr_hdr[4], rr_hdr[5], rr_hdr[6], rr_hdr[7]]);
        let rdlength = usize::from(u16::from_be_bytes([rr_hdr[8], rr_hdr[9]]));

        let rdata_start = header_end;
        let rdata_end = rdata_start
            .checked_add(rdlength)
            .ok_or(DiscoveryError::MalformedPacket("RDATA length overflow"))?;
        if rdata_end > packet.len() {
            return Err(DiscoveryError::MalformedPacket("truncated DNS RDATA"));
        }

        if rclass == CLASS_IN {
            match rtype {
                QTYPE_PTR => {
                    let (target, after_name) = decode_dns_name(packet, rdata_start)?;
                    if after_name > rdata_end {
                        return Err(DiscoveryError::MalformedPacket(
                            "PTR target extends beyond RDATA",
                        ));
                    }
                    records.push(DnsRecord {
                        name,
                        ttl,
                        data: DnsRecordData::Ptr(target),
                    });
                }
                QTYPE_SRV => {
                    if rdlength < 6 {
                        return Err(DiscoveryError::MalformedPacket(
                            "SRV RDATA shorter than 6 bytes",
                        ));
                    }
                    let priority =
                        u16::from_be_bytes([packet[rdata_start], packet[rdata_start + 1]]);
                    let weight =
                        u16::from_be_bytes([packet[rdata_start + 2], packet[rdata_start + 3]]);
                    let port =
                        u16::from_be_bytes([packet[rdata_start + 4], packet[rdata_start + 5]]);
                    let (target, after_name) = decode_dns_name(packet, rdata_start + 6)?;
                    if after_name > rdata_end {
                        return Err(DiscoveryError::MalformedPacket(
                            "SRV target extends beyond RDATA",
                        ));
                    }
                    records.push(DnsRecord {
                        name,
                        ttl,
                        data: DnsRecordData::Srv {
                            priority,
                            weight,
                            port,
                            target,
                        },
                    });
                }
                QTYPE_TXT => {
                    let entries = parse_txt_rdata(&packet[rdata_start..rdata_end])?;
                    records.push(DnsRecord {
                        name,
                        ttl,
                        data: DnsRecordData::Txt(entries),
                    });
                }
                QTYPE_A => {
                    if rdlength != 4 {
                        return Err(DiscoveryError::MalformedPacket(
                            "A record RDATA must be 4 bytes",
                        ));
                    }
                    let ip = Ipv4Addr::new(
                        packet[rdata_start],
                        packet[rdata_start + 1],
                        packet[rdata_start + 2],
                        packet[rdata_start + 3],
                    );
                    records.push(DnsRecord {
                        name,
                        ttl,
                        data: DnsRecordData::A(ip),
                    });
                }
                _ => {}
            }
        }

        cursor = rdata_end;
    }

    Ok(records)
}

fn parse_txt_rdata(rdata: &[u8]) -> Result<Vec<(String, Option<String>)>, DiscoveryError> {
    let mut entries = Vec::new();
    let mut pos = 0usize;

    while pos < rdata.len() {
        let len = usize::from(rdata[pos]);
        pos += 1;
        let end = pos
            .checked_add(len)
            .ok_or(DiscoveryError::MalformedPacket("TXT item length overflow"))?;
        let slice = rdata.get(pos..end).ok_or(DiscoveryError::MalformedPacket(
            "truncated TXT record string",
        ))?;
        pos = end;

        if slice.is_empty() {
            continue;
        }
        let item = String::from_utf8_lossy(slice);
        if let Some((key, val)) = item.split_once('=') {
            entries.push((key.trim().to_ascii_lowercase(), Some(val.to_owned())));
        } else {
            entries.push((item.trim().to_ascii_lowercase(), None));
        }
    }

    Ok(entries)
}

#[derive(Debug, Clone)]
struct PtrEntry {
    fullname: String,
    source_ip: Option<Ipv4Addr>,
    last_seen: Instant,
    ttl_secs: u32,
}

#[derive(Debug, Clone)]
struct SrvEntry {
    port: u16,
    target: String,
    target_key: String,
    source_ip: Option<Ipv4Addr>,
    last_seen: Instant,
    ttl_secs: u32,
}

#[derive(Debug, Clone)]
struct TxtEntry {
    device_name: Option<String>,
    ip: Option<Ipv4Addr>,
    protocol_version: Option<u8>,
    port_hint: Option<u16>,
    source_ip: Option<Ipv4Addr>,
    last_seen: Instant,
    ttl_secs: u32,
}

#[derive(Debug, Clone)]
struct AddrEntry {
    ip: Ipv4Addr,
    last_seen: Instant,
    ttl_secs: u32,
}

/// Result returned by [`DiscoveryCache::ingest`].
#[derive(Debug, Default)]
pub struct CacheIngestResult {
    pub events: Vec<DiscoveryEvent>,
    pub follow_up_queries: Vec<(String, u16)>,
}

/// Pure, time-injectable state machine correlating mDNS PTR, SRV, TXT, and A records
/// across single or split response packets.
#[derive(Debug, Default)]
pub struct DiscoveryCache {
    ptrs: HashMap<String, PtrEntry>,
    srvs: HashMap<String, SrvEntry>,
    txts: HashMap<String, TxtEntry>,
    addrs: HashMap<String, AddrEntry>,
    active_devices: HashMap<String, DiscoveredDevice>,
    last_followup_at: HashMap<(String, u16), Instant>,
}

impl DiscoveryCache {
    /// Creates an empty mDNS correlation cache.
    #[must_use]
    pub fn new() -> Self {
        Self::default()
    }

    /// Returns a snapshot of currently active discovered devices.
    #[must_use]
    pub fn devices(&self) -> Vec<DiscoveredDevice> {
        self.active_devices.values().cloned().collect()
    }

    /// Ingests parsed DNS records from a single mDNS response packet and returns
    /// emitted [`DiscoveryEvent`]s plus any follow-up `(qname, qtype)` queries needed.
    pub fn ingest(
        &mut self,
        records: &[DnsRecord],
        src_ip: Option<Ipv4Addr>,
        now: Instant,
    ) -> CacheIngestResult {
        let usable_src_ip = src_ip.filter(|ip| !ip.is_loopback() && !ip.is_unspecified());
        let mut ptr_touched: Vec<(String, String)> = Vec::new();
        let mut srv_touched: Vec<String> = Vec::new();

        for rec in records {
            match &rec.data {
                DnsRecordData::Ptr(target) if is_camcraft_service_type(&rec.name) => {
                    let key = canonical_dns_key(target);
                    if rec.ttl == 0 {
                        self.ptrs.remove(&key);
                        self.srvs.remove(&key);
                        self.txts.remove(&key);
                    } else {
                        self.ptrs.insert(
                            key.clone(),
                            PtrEntry {
                                fullname: target.clone(),
                                source_ip: usable_src_ip,
                                last_seen: now,
                                ttl_secs: rec.ttl,
                            },
                        );
                        ptr_touched.push((key, target.clone()));
                    }
                }
                DnsRecordData::Srv { port, target, .. } if is_camcraft_instance(&rec.name) => {
                    let key = canonical_dns_key(&rec.name);
                    if rec.ttl == 0 {
                        self.srvs.remove(&key);
                    } else {
                        self.srvs.insert(
                            key.clone(),
                            SrvEntry {
                                port: *port,
                                target: target.clone(),
                                target_key: canonical_dns_key(target),
                                source_ip: usable_src_ip,
                                last_seen: now,
                                ttl_secs: rec.ttl,
                            },
                        );
                        srv_touched.push(key);
                    }
                }
                DnsRecordData::Txt(entries) if is_camcraft_instance(&rec.name) => {
                    let key = canonical_dns_key(&rec.name);
                    if rec.ttl == 0 {
                        self.txts.remove(&key);
                    } else {
                        let parsed = parse_camcraft_txt(entries, usable_src_ip, now, rec.ttl);
                        self.txts.insert(key, parsed);
                    }
                }
                DnsRecordData::A(ip) => {
                    let host_key = canonical_dns_key(&rec.name);
                    if rec.ttl == 0 {
                        self.addrs.remove(&host_key);
                    } else if !ip.is_unspecified() {
                        if self.addrs.len() >= MAX_CACHED_ADDRS
                            && !self.addrs.contains_key(&host_key)
                        {
                            self.evict_addrs_only(now);
                        }
                        self.addrs.insert(
                            host_key,
                            AddrEntry {
                                ip: *ip,
                                last_seen: now,
                                ttl_secs: rec.ttl,
                            },
                        );
                    }
                }
                _ => {}
            }
        }

        let mut events = self.reconcile_devices();
        let mut follow_up_queries = Vec::new();

        for (key, fullname) in ptr_touched {
            if !self.srvs.contains_key(&key) && self.should_query_followup(&key, QTYPE_SRV, now) {
                follow_up_queries.push((fullname.clone(), QTYPE_SRV));
            }
            if !self.txts.contains_key(&key) && self.should_query_followup(&key, QTYPE_TXT, now) {
                follow_up_queries.push((fullname, QTYPE_TXT));
            }
            if let Some(srv) = self.srvs.get(&key).cloned()
                && !self.addrs.contains_key(&srv.target_key)
                && self.should_query_followup(&srv.target_key, QTYPE_A, now)
            {
                follow_up_queries.push((srv.target, QTYPE_A));
            }
        }

        for key in srv_touched {
            if let Some(srv) = self.srvs.get(&key).cloned()
                && !self.addrs.contains_key(&srv.target_key)
                && self.should_query_followup(&srv.target_key, QTYPE_A, now)
            {
                follow_up_queries.push((srv.target, QTYPE_A));
            }
        }

        // Preserve deterministic ordering while deduplicating follow-ups
        let mut seen = HashSet::new();
        follow_up_queries.retain(|item| seen.insert((canonical_dns_key(&item.0), item.1)));

        events.shrink_to_fit();
        CacheIngestResult {
            events,
            follow_up_queries,
        }
    }

    /// Evicts stale records whose clamped TTL (`ttl.clamp(15, 30)` seconds) has elapsed
    /// without a refresh, returning [`DiscoveryEvent::Removed`] for evicted active devices.
    pub fn evict_expired(&mut self, now: Instant) -> Vec<DiscoveryEvent> {
        self.ptrs
            .retain(|_, entry| !is_expired(entry.last_seen, entry.ttl_secs, now));
        self.srvs
            .retain(|_, entry| !is_expired(entry.last_seen, entry.ttl_secs, now));
        self.txts
            .retain(|_, entry| !is_expired(entry.last_seen, entry.ttl_secs, now));
        self.evict_addrs_only(now);
        self.last_followup_at
            .retain(|_, at| now.saturating_duration_since(*at) < FOLLOWUP_COOLDOWN * 10);

        self.reconcile_devices()
    }

    fn evict_addrs_only(&mut self, now: Instant) {
        self.addrs
            .retain(|_, entry| !is_expired(entry.last_seen, entry.ttl_secs, now));
    }

    fn should_query_followup(&mut self, key: &str, qtype: u16, now: Instant) -> bool {
        let lookup = (key.to_owned(), qtype);
        if let Some(last) = self.last_followup_at.get(&lookup)
            && now.saturating_duration_since(*last) < FOLLOWUP_COOLDOWN
        {
            return false;
        }
        self.last_followup_at.insert(lookup, now);
        true
    }

    fn reconcile_devices(&mut self) -> Vec<DiscoveryEvent> {
        let mut events = Vec::new();
        let mut resolved_now: HashMap<String, DiscoveredDevice> = HashMap::new();

        for (key, ptr) in &self.ptrs {
            if let Some(dev) = self.resolve_device(key, ptr) {
                resolved_now.insert(key.clone(), dev);
            }
        }

        // 1. Detect removals
        let removed_keys: Vec<String> = self
            .active_devices
            .keys()
            .filter(|k| !resolved_now.contains_key(*k))
            .cloned()
            .collect();
        for key in removed_keys {
            if let Some(old) = self.active_devices.remove(&key) {
                events.push(DiscoveryEvent::Removed {
                    service_fullname: old.service_fullname,
                });
            }
        }

        // 2. Detect additions or updates
        for (key, dev) in resolved_now {
            let changed = self.active_devices.get(&key) != Some(&dev);
            if changed {
                self.active_devices.insert(key, dev.clone());
                events.push(DiscoveryEvent::Found(dev));
            }
        }

        events
    }

    fn resolve_device(&self, key: &str, ptr: &PtrEntry) -> Option<DiscoveredDevice> {
        let srv = self.srvs.get(key)?;
        let txt = self.txts.get(key);

        let port = if srv.port > 0 {
            srv.port
        } else {
            txt.and_then(|t| t.port_hint).filter(|&p| p > 0)?
        };

        // IP priority: A record for SRV target -> TXT `ip=` -> packet source IPv4 (non-loopback)
        let ip = self
            .addrs
            .get(&srv.target_key)
            .map(|a| a.ip)
            .filter(|ip| !ip.is_unspecified())
            .or_else(|| txt.and_then(|t| t.ip).filter(|ip| !ip.is_unspecified()))
            .or_else(|| {
                srv.source_ip
                    .or(ptr.source_ip)
                    .or_else(|| txt.and_then(|t| t.source_ip))
                    .filter(|ip| !ip.is_loopback() && !ip.is_unspecified())
            })?;

        // Display name priority: TXT `device=` -> instance label stripped of `._camcraft._tcp.local.`
        let device_name = txt
            .and_then(|t| t.device_name.as_deref())
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(str::to_owned)
            .unwrap_or_else(|| strip_service_suffix(&ptr.fullname));

        let protocol_version = txt.and_then(|t| t.protocol_version);

        Some(DiscoveredDevice {
            service_fullname: ptr.fullname.clone(),
            device_name,
            ip,
            port,
            hostname: srv.target.clone(),
            protocol_version,
        })
    }
}

fn is_expired(last_seen: Instant, ttl_secs: u32, now: Instant) -> bool {
    let clamped = u64::from(ttl_secs.clamp(15, 30));
    now.saturating_duration_since(last_seen) >= Duration::from_secs(clamped)
}

fn canonical_dns_key(name: &str) -> String {
    let trimmed = name.trim_end_matches('.').to_ascii_lowercase();
    if trimmed.is_empty() {
        ".".to_owned()
    } else {
        format!("{trimmed}.")
    }
}

fn is_camcraft_service_type(name: &str) -> bool {
    name.trim_end_matches('.')
        .eq_ignore_ascii_case("_camcraft._tcp.local")
}

fn is_camcraft_instance(name: &str) -> bool {
    let trimmed = name.trim_end_matches('.');
    let suffix = "._camcraft._tcp.local";
    trimmed.len() > suffix.len()
        && trimmed
            .get(trimmed.len() - suffix.len()..)
            .is_some_and(|tail| tail.eq_ignore_ascii_case(suffix))
}

fn strip_service_suffix(fullname: &str) -> String {
    let trimmed = fullname.trim_end_matches('.');
    let suffix = "._camcraft._tcp.local";
    if trimmed.len() > suffix.len()
        && let Some(prefix_idx) = trimmed.len().checked_sub(suffix.len())
        && trimmed
            .get(prefix_idx..)
            .is_some_and(|tail| tail.eq_ignore_ascii_case(suffix))
        && let Some(prefix) = trimmed.get(..prefix_idx)
        && !prefix.is_empty()
    {
        return prefix.to_owned();
    }
    fullname.to_owned()
}

fn parse_camcraft_txt(
    entries: &[(String, Option<String>)],
    source_ip: Option<Ipv4Addr>,
    now: Instant,
    ttl_secs: u32,
) -> TxtEntry {
    let mut device_name = None;
    let mut ip = None;
    let mut protocol_version = None;
    let mut port_hint = None;

    for (key, val) in entries {
        let Some(v) = val.as_deref() else {
            continue;
        };
        let v_trimmed = v.trim();
        match key.as_str() {
            "device" if !v_trimmed.is_empty() => {
                device_name = Some(v_trimmed.to_owned());
            }
            "ip" => {
                if let Ok(parsed_ip) = v_trimmed.parse::<Ipv4Addr>()
                    && !parsed_ip.is_unspecified()
                {
                    ip = Some(parsed_ip);
                }
            }
            "ver" => {
                protocol_version = v_trimmed.parse::<u8>().ok();
            }
            "port" => {
                port_hint = v_trimmed.parse::<u16>().ok();
            }
            _ => {}
        }
    }

    TxtEntry {
        device_name,
        ip,
        protocol_version,
        port_hint,
        source_ip,
        last_seen: now,
        ttl_secs,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn encode_dns_name_raw(name: &str, out: &mut Vec<u8>) {
        let trimmed = name.trim_matches('.');
        if !trimmed.is_empty() {
            for label in trimmed.split('.') {
                let bytes = label.as_bytes();
                out.push(bytes.len() as u8);
                out.extend_from_slice(bytes);
            }
        }
        out.push(0x00);
    }

    fn build_response_header(qdcount: u16, ancount: u16, nscount: u16, arcount: u16) -> Vec<u8> {
        let mut pkt = Vec::new();
        pkt.extend_from_slice(&0u16.to_be_bytes());
        pkt.extend_from_slice(&0x8400u16.to_be_bytes());
        pkt.extend_from_slice(&qdcount.to_be_bytes());
        pkt.extend_from_slice(&ancount.to_be_bytes());
        pkt.extend_from_slice(&nscount.to_be_bytes());
        pkt.extend_from_slice(&arcount.to_be_bytes());
        pkt
    }

    fn push_rr_header(out: &mut Vec<u8>, rtype: u16, rclass: u16, ttl: u32, rdata: &[u8]) {
        out.extend_from_slice(&rtype.to_be_bytes());
        out.extend_from_slice(&rclass.to_be_bytes());
        out.extend_from_slice(&ttl.to_be_bytes());
        out.extend_from_slice(&(rdata.len() as u16).to_be_bytes());
        out.extend_from_slice(rdata);
    }

    #[test]
    fn build_mdns_query_encodes_qm_and_qu_correctly() {
        let qm = build_mdns_query(MDNS_SERVICE_TYPE, QTYPE_PTR, false);
        assert_eq!(&qm[0..12], &[0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0]);
        // _camcraft (9), _tcp (4), local (5), 0
        assert_eq!(qm[12], 9);
        assert_eq!(&qm[13..22], b"_camcraft");
        assert_eq!(qm[22], 4);
        assert_eq!(&qm[23..27], b"_tcp");
        assert_eq!(qm[27], 5);
        assert_eq!(&qm[28..33], b"local");
        assert_eq!(qm[33], 0);
        assert_eq!(u16::from_be_bytes([qm[34], qm[35]]), QTYPE_PTR);
        assert_eq!(u16::from_be_bytes([qm[36], qm[37]]), 0x0001);

        let qu = build_mdns_query(MDNS_SERVICE_TYPE, QTYPE_PTR, true);
        assert_eq!(u16::from_be_bytes([qu[36], qu[37]]), 0x8001);
    }

    #[test]
    fn decode_dns_name_supports_compression_pointers() {
        let mut pkt = vec![0u8; 12];
        let base_offset = pkt.len();
        encode_dns_name_raw("_camcraft._tcp.local.", &mut pkt);
        let compressed_offset = pkt.len();
        pkt.push(7);
        pkt.extend_from_slice(b"Pixel 8");
        pkt.extend_from_slice(&(0xC000u16 | base_offset as u16).to_be_bytes());

        let (name, next) = decode_dns_name(&pkt, compressed_offset).expect("decode compressed");
        assert_eq!(name, "Pixel 8._camcraft._tcp.local.");
        assert_eq!(next, pkt.len());
    }

    #[test]
    fn decode_dns_name_rejects_compression_pointer_loop() {
        // Self-loop at offset 0: 0xC0 0x00
        let self_loop = [0xC0, 0x00];
        assert!(matches!(
            decode_dns_name(&self_loop, 0),
            Err(DiscoveryError::MalformedPacket(_))
        ));

        // 2-node cycle: offset 0 -> offset 2, offset 2 -> offset 0
        let mutual_loop = [0xC0, 0x02, 0xC0, 0x00];
        assert!(matches!(
            decode_dns_name(&mutual_loop, 0),
            Err(DiscoveryError::MalformedPacket(_))
        ));
    }

    #[test]
    fn parse_mdns_response_parses_full_ptr_srv_txt_a_with_cache_flush_bit() {
        let mut pkt = build_response_header(0, 1, 0, 3);
        let instance_name = "Pixel 8 Pro._camcraft._tcp.local.";
        let host_name = "pixel-8-pro.local.";

        // Answer: PTR _camcraft._tcp.local. -> Pixel 8 Pro._camcraft._tcp.local.
        let service_offset = pkt.len();
        encode_dns_name_raw(MDNS_SERVICE_TYPE, &mut pkt);
        let mut ptr_rdata = Vec::new();
        encode_dns_name_raw(instance_name, &mut ptr_rdata);
        push_rr_header(&mut pkt, QTYPE_PTR, 0x0001, 4500, &ptr_rdata);

        // Additional 1: SRV with cache-flush bit (0x8001) & compression pointer to service type
        let instance_offset = pkt.len();
        pkt.push(11);
        pkt.extend_from_slice(b"Pixel 8 Pro");
        pkt.extend_from_slice(&(0xC000u16 | service_offset as u16).to_be_bytes());
        let mut srv_rdata = Vec::new();
        srv_rdata.extend_from_slice(&0u16.to_be_bytes());
        srv_rdata.extend_from_slice(&0u16.to_be_bytes());
        srv_rdata.extend_from_slice(&9876u16.to_be_bytes());
        encode_dns_name_raw(host_name, &mut srv_rdata);
        push_rr_header(&mut pkt, QTYPE_SRV, 0x8001, 120, &srv_rdata);

        // Additional 2: TXT
        pkt.extend_from_slice(&(0xC000u16 | instance_offset as u16).to_be_bytes());
        let mut txt_rdata = Vec::new();
        for item in [
            "ver=1",
            "device=Pixel 8 Pro",
            "port=9876",
            "ip=192.168.1.42",
        ] {
            txt_rdata.push(item.len() as u8);
            txt_rdata.extend_from_slice(item.as_bytes());
        }
        push_rr_header(&mut pkt, QTYPE_TXT, 0x8001, 120, &txt_rdata);

        // Additional 3: A record
        encode_dns_name_raw(host_name, &mut pkt);
        push_rr_header(&mut pkt, QTYPE_A, 0x8001, 120, &[192, 168, 1, 42]);

        let records = parse_mdns_response(&pkt).expect("parse response");
        assert_eq!(records.len(), 4);

        let mut cache = DiscoveryCache::new();
        let res = cache.ingest(&records, None, Instant::now());
        assert!(res.follow_up_queries.is_empty());
        assert_eq!(
            res.events,
            vec![DiscoveryEvent::Found(DiscoveredDevice {
                service_fullname: instance_name.to_owned(),
                device_name: "Pixel 8 Pro".to_owned(),
                ip: Ipv4Addr::new(192, 168, 1, 42),
                port: 9876,
                hostname: host_name.to_owned(),
                protocol_version: Some(1),
            })]
        );
        assert_eq!(cache.devices()[0].endpoint(), "192.168.1.42:9876");
    }

    #[test]
    fn discovery_cache_emits_followups_across_split_packets() {
        let mut cache = DiscoveryCache::new();
        let t0 = Instant::now();
        let instance = "Galaxy S24._camcraft._tcp.local.";
        let host = "galaxy-s24.local.";

        // Packet 1: PTR only
        let r1 = cache.ingest(
            &[DnsRecord {
                name: "_CAMCRAFT._TCP.LOCAL.".to_owned(),
                ttl: 120,
                data: DnsRecordData::Ptr(instance.to_owned()),
            }],
            None,
            t0,
        );
        assert!(r1.events.is_empty());
        assert_eq!(
            r1.follow_up_queries,
            vec![
                (instance.to_owned(), QTYPE_SRV),
                (instance.to_owned(), QTYPE_TXT),
            ]
        );

        // Packet 2: SRV only -> emits A follow-up
        let r2 = cache.ingest(
            &[DnsRecord {
                name: instance.to_owned(),
                ttl: 120,
                data: DnsRecordData::Srv {
                    priority: 0,
                    weight: 0,
                    port: 9876,
                    target: host.to_owned(),
                },
            }],
            None,
            t0 + Duration::from_millis(10),
        );
        assert!(r2.events.is_empty());
        assert_eq!(r2.follow_up_queries, vec![(host.to_owned(), QTYPE_A)]);

        // Packet 3: A record resolves device (fallback name from stripped service_fullname)
        let r3 = cache.ingest(
            &[DnsRecord {
                name: host.to_owned(),
                ttl: 120,
                data: DnsRecordData::A(Ipv4Addr::new(192, 168, 1, 88)),
            }],
            None,
            t0 + Duration::from_millis(20),
        );
        assert!(r3.follow_up_queries.is_empty());
        assert_eq!(
            r3.events,
            vec![DiscoveryEvent::Found(DiscoveredDevice {
                service_fullname: instance.to_owned(),
                device_name: "Galaxy S24".to_owned(),
                ip: Ipv4Addr::new(192, 168, 1, 88),
                port: 9876,
                hostname: host.to_owned(),
                protocol_version: None,
            })]
        );
    }

    #[test]
    fn discovery_cache_txt_ip_and_src_ip_fallback_priority() {
        let mut cache = DiscoveryCache::new();
        let t0 = Instant::now();
        let instance = "OnePlus._camcraft._tcp.local.";
        let host = "oneplus.local.";

        // 1. No A record, TXT ip= wins over src_ip
        let r1 = cache.ingest(
            &[
                DnsRecord {
                    name: MDNS_SERVICE_TYPE.to_owned(),
                    ttl: 60,
                    data: DnsRecordData::Ptr(instance.to_owned()),
                },
                DnsRecord {
                    name: instance.to_owned(),
                    ttl: 60,
                    data: DnsRecordData::Srv {
                        priority: 0,
                        weight: 0,
                        port: 9876,
                        target: host.to_owned(),
                    },
                },
                DnsRecord {
                    name: instance.to_owned(),
                    ttl: 60,
                    data: DnsRecordData::Txt(vec![
                        ("ip".to_owned(), Some("192.168.50.10".to_owned())),
                        ("ver".to_owned(), Some("1".to_owned())),
                    ]),
                },
            ],
            Some(Ipv4Addr::new(192, 168, 50, 99)),
            t0,
        );
        assert_eq!(
            r1.events,
            vec![DiscoveryEvent::Found(DiscoveredDevice {
                service_fullname: instance.to_owned(),
                device_name: "OnePlus".to_owned(),
                ip: Ipv4Addr::new(192, 168, 50, 10),
                port: 9876,
                hostname: host.to_owned(),
                protocol_version: Some(1),
            })]
        );

        // 2. A record arrives later and overrides TXT ip=
        let r2 = cache.ingest(
            &[DnsRecord {
                name: host.to_owned(),
                ttl: 60,
                data: DnsRecordData::A(Ipv4Addr::new(192, 168, 50, 20)),
            }],
            None,
            t0 + Duration::from_millis(5),
        );
        assert_eq!(
            r2.events,
            vec![DiscoveryEvent::Found(DiscoveredDevice {
                service_fullname: instance.to_owned(),
                device_name: "OnePlus".to_owned(),
                ip: Ipv4Addr::new(192, 168, 50, 20),
                port: 9876,
                hostname: host.to_owned(),
                protocol_version: Some(1),
            })]
        );

        // 3. Separate device with only non-loopback src_ip (loopback src_ip ignored)
        let instance2 = "LoopbackTest._camcraft._tcp.local.";
        let r3 = cache.ingest(
            &[
                DnsRecord {
                    name: MDNS_SERVICE_TYPE.to_owned(),
                    ttl: 60,
                    data: DnsRecordData::Ptr(instance2.to_owned()),
                },
                DnsRecord {
                    name: instance2.to_owned(),
                    ttl: 60,
                    data: DnsRecordData::Srv {
                        priority: 0,
                        weight: 0,
                        port: 9876,
                        target: "loop.local.".to_owned(),
                    },
                },
            ],
            Some(Ipv4Addr::LOCALHOST),
            t0 + Duration::from_millis(10),
        );
        assert!(r3.events.is_empty(), "loopback src_ip must be ignored");
    }

    #[test]
    fn discovery_cache_ttl_zero_goodbye_and_stale_expiry() {
        let mut cache = DiscoveryCache::new();
        let t0 = Instant::now();
        let instance = "Pixel 7._camcraft._tcp.local.";
        let host = "pixel-7.local.";

        let r1 = cache.ingest(
            &[
                DnsRecord {
                    name: MDNS_SERVICE_TYPE.to_owned(),
                    ttl: 5, // clamped to 15s
                    data: DnsRecordData::Ptr(instance.to_owned()),
                },
                DnsRecord {
                    name: instance.to_owned(),
                    ttl: 120, // clamped to 30s
                    data: DnsRecordData::Srv {
                        priority: 0,
                        weight: 0,
                        port: 9876,
                        target: host.to_owned(),
                    },
                },
                DnsRecord {
                    name: host.to_owned(),
                    ttl: 120,
                    data: DnsRecordData::A(Ipv4Addr::new(10, 0, 0, 5)),
                },
            ],
            None,
            t0,
        );
        assert_eq!(r1.events.len(), 1);

        // Not expired before clamped min (15s)
        assert!(cache.evict_expired(t0 + Duration::from_secs(14)).is_empty());
        // Expired at 15s (PTR ttl=5 clamped to 15s)
        let expired = cache.evict_expired(t0 + Duration::from_secs(15));
        assert_eq!(
            expired,
            vec![DiscoveryEvent::Removed {
                service_fullname: instance.to_owned(),
            }]
        );

        // Re-add with high TTL (clamped to 30s), then test immediate TTL=0 goodbye removal
        let t1 = t0 + Duration::from_secs(20);
        let _ = cache.ingest(
            &[
                DnsRecord {
                    name: MDNS_SERVICE_TYPE.to_owned(),
                    ttl: 4500,
                    data: DnsRecordData::Ptr(instance.to_owned()),
                },
                DnsRecord {
                    name: instance.to_owned(),
                    ttl: 120,
                    data: DnsRecordData::Srv {
                        priority: 0,
                        weight: 0,
                        port: 9876,
                        target: host.to_owned(),
                    },
                },
                DnsRecord {
                    name: host.to_owned(),
                    ttl: 120,
                    data: DnsRecordData::A(Ipv4Addr::new(10, 0, 0, 5)),
                },
            ],
            None,
            t1,
        );
        assert!(cache.evict_expired(t1 + Duration::from_secs(29)).is_empty());

        let goodbye = cache.ingest(
            &[DnsRecord {
                name: MDNS_SERVICE_TYPE.to_owned(),
                ttl: 0,
                data: DnsRecordData::Ptr(instance.to_owned()),
            }],
            None,
            t1 + Duration::from_secs(29),
        );
        assert_eq!(
            goodbye.events,
            vec![DiscoveryEvent::Removed {
                service_fullname: instance.to_owned(),
            }]
        );
        assert!(cache.devices().is_empty());
    }

    #[test]
    fn parse_mdns_response_handles_malformed_and_truncated_packets_safely() {
        assert!(matches!(
            parse_mdns_response(&[]),
            Err(DiscoveryError::MalformedPacket(_))
        ));
        assert!(matches!(
            parse_mdns_response(&[0u8; 11]),
            Err(DiscoveryError::MalformedPacket(_))
        ));

        // Query packet (QR=0) returns Ok(empty)
        let query = build_mdns_query(MDNS_SERVICE_TYPE, QTYPE_PTR, false);
        assert_eq!(parse_mdns_response(&query).unwrap(), Vec::new());

        // Response claiming 1 answer with truncated body
        let hdr_only = build_response_header(0, 1, 0, 0);
        assert!(matches!(
            parse_mdns_response(&hdr_only),
            Err(DiscoveryError::MalformedPacket(_))
        ));

        // Unknown RR type (AAAA = 28) is skipped cleanly
        let mut unknown_rr = build_response_header(0, 1, 0, 0);
        encode_dns_name_raw("host.local.", &mut unknown_rr);
        push_rr_header(&mut unknown_rr, 28, 1, 120, &[0u8; 16]);
        assert_eq!(parse_mdns_response(&unknown_rr).unwrap(), Vec::new());
    }

    #[test]
    fn mdns_scanner_starts_triggers_and_stops_cleanly() {
        let (tx, _rx) = crossbeam_channel::unbounded();
        match MdnsScanner::start(tx) {
            Ok(mut scanner) => {
                scanner.trigger_scan();
                std::thread::sleep(Duration::from_millis(50));
                scanner.stop();
            }
            Err(DiscoveryError::Io(_)) => {
                // Acceptable only in environments that restrict UDP socket creation.
            }
            Err(e) => panic!("unexpected discovery error: {e}"),
        }
    }
}
