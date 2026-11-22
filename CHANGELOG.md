# Changelog

All notable changes to **CamCraft** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- **Android CameraX, H.264 Encoder & Streaming Service Lifecycle:**
  - Restarted `H264SurfaceEncoder` (`VIDEO_CONFIG` + IDR) on camera lens switches as well as geometry/FPS changes and serialized stream state updates (`BUG-05`).
  - Made `attachPreviewView`/`detachPreviewView` idempotent and rebound only `uiPreview` without tearing down live `encoderPreview` (`BUG-06`).
  - Moved `H264SurfaceEncoder` create/release off the Main thread, coordinated safe deferred codec/surface release when `drainThread` stalls via `DrainReleaseCoordinator`, and closed transports before stopping audio/camera on teardown (`BUG-07`).
  - Isolated stale-session `IOException` cleanup in `sendStreamPacket` so old writers cannot close newer active sessions (`BUG-09`).
  - Ensured `handleClientSession` always exits `CONNECTED` on disconnect/bind failure and `startServer()` checks `activeSessionCloser != null` (`BUG-10`).
  - Clamped `zoomRatio`, `exposureEv`, and `torchOn` to target camera capabilities on `ConfigureStream` (`BUG-14`), serialized `activeState` updates and ordered `CONTROL_ACK` emissions (`BUG-15`), and guarded `WifiNetworkMonitor`/mDNS registration/unregistration during Wi-Fi drops and `onDestroy()` (`BUG-16`).

## [0.0.3] - 2026-11-21

### Fixed
- **Cross-Distro Linux Packaging & FFmpeg SONAME Compatibility:**
  - Bundled minimal LGPL FFmpeg 7.1.3 shared libraries (`h264` decoder/parser + `swscale` only) privately under `/usr/lib/camcraft/` with `$ORIGIN` RPATH (`packaging/linux/build-minimal-ffmpeg.sh`), eliminating cross-distro SONAME conflicts (`libavcodec.so.63` vs `.62`/`.60`/`.58`) across Fedora 40–45+, Ubuntu 22.04–26.04, Debian 12/13, and derivatives.
  - Updated `camcraft.spec` and `build-deb.sh` so `.rpm` and `.deb` packages require only `glibc >= 2.35` and audio utilities without locking to distro-specific `libav*` SONAMEs.
  - Updated `build-appimage.sh` and `.github/workflows/release.yml` to package the minimal private FFmpeg libraries directly instead of pulling host codec bloat, significantly reducing `.AppImage` size.

## [0.0.2] - 2026-11-21

### Added
- **Zero-Setup PipeWire Virtual Camera (`camcraft-core`):**
  - Added native PipeWire `Video/Source` sink (`PipeWireCameraSink`) loaded dynamically at runtime via `libc::dlopen("libpipewire-0.3.so.0")` with zero compile-time or `DT_NEEDED` PipeWire dependencies.
  - Added `VcamBackendMode` (`Auto` defaulting to PipeWire with automatic fallback to `v4l2loopback`, `PipeWire`, and `V4l2`) plus a 1-click Polkit helper (`enable_v4l2loopback_via_pkexec`) for legacy V4L2-only apps.
- **Plug-and-Play USB Without USB Debugging (`AOA v1`):**
  - Implemented pure-Rust Linux `usbdevfs` Android Open Accessory (`usb_aoa.rs`) and transport-agnostic async streaming (`run_client_stream_loop`, `run_aoa_client_loop`, `run_aoa_client_with_reconnect`), plus 1-click udev permission installation (`70-camcraft-aoa.rules` / `install_udev_rules_via_pkexec`).
  - Implemented Android `UsbAccessory` transport (`UsbAccessoryTransport.kt`, `accessory_filter.xml`, `AoaChunkedOutputStream` with 16 KiB chunking, `ACTION_USB_ACCESSORY_DETACHED` cleanup, and concurrent Wi-Fi + USB session handling in `CamCraftStreamService`).
  - Updated GUI and CLI defaults to **Direct USB (Plug & Play)** while preserving **Legacy ADB** (`--adb`) as fallback.
- **Multi-Distro Linux Packaging & Install-Aware Updater:**
  - Added RPM (`packaging/linux/camcraft.spec`, `just build-rpm`), Debian/Ubuntu `.deb` (`packaging/linux/build-deb.sh`), universal `.AppImage` (`packaging/linux/build-appimage.sh` built on Ubuntu 22.04), and udev rules (`70-camcraft-aoa.rules`).
  - Updated `updater.rs`, `camcraft-gui`, and `camcraft-cli` to detect `InstallKind` (`AppImage`, `RpmPackage`, `DebPackage`, `PortableArchive`), update AppImages in place, and verify/open downloaded `.rpm`/`.deb` packages cleanly without permission errors.

### Changed
- **Slimmed FFmpeg Runtime Linking:** Disabled unused `ffmpeg-next` features (`device`, `filter`, `software-resampling`), leaving only `libavcodec`, `libavutil`, and `libswscale` in release binary `DT_NEEDED` entries.
- **Documentation:** Updated `README.md` and `docs/DESIGN.md` to feature `.AppImage`, `.rpm`, `.deb`, and universal `.apk` downloads without end-user setup scripts.
- **Dependencies:** Consolidated Dependabot updates including `eframe`/`egui` 0.37.0, `bytes` 1.12.2, `clap` 4.6.9, Gradle Wrapper 9.9.1, AGP 9.5.2, Kotlin 2.4.21, `kotlinx-coroutines-android` 1.11.0, `kotlinx-serialization-json` 1.13.0, and GitHub Actions (`checkout@v7`, `setup-java@v6`, `gradle/actions@v6`, `upload-artifact@v7`, `download-artifact@v8`).

## [0.0.1] - 2026-11-20

### Added
- **CCFT v1 Binary Wire Protocol (`camcraft-protocol` & Android `protocol/`):**
  - 20-byte big-endian binary header framing (`MAGIC = "CCFT"`, `PROTOCOL_VERSION = 1`, `MAX_PAYLOAD_LEN = 16 MiB`) with partial-read streaming decode and cross-language test parity.
  - Typed JSON control channel (`ControlHello`, `ControlCmd`, `ControlAck`) and heartbeat `PING`/`PONG` RTT measurement.
- **Android Mobile Sender (`android/`):**
  - Hardware-accelerated CameraX + `MediaCodec` H.264 surface encoding supporting 720p, 1080p, and 4K UHD up to 60 FPS across detected front/back/ultrawide lenses.
  - Foreground streaming service (`CamCraftStreamService`) over USB ADB (`127.0.0.1:9876`) and Wi-Fi LAN with automatic `NsdManager` mDNS discovery (`_camcraft._tcp.`).
  - Low-latency 48 kHz mono 16-bit PCM microphone streaming (`AudioStreamEngine`) with remote mute/enable control.
  - ArtCraft Studio v2 Compose UI with tap-to-focus, rule-of-thirds overlay, battery/thermal telemetry, and **OLED Battery Saver** (`#000000` burn-in-safe dim screen mode).
  - **In-app update notifier & self-installer (`AppUpdater` / `AppUpdateManager`):** automatically checks GitHub Releases, verifies `SHA256SUMS.txt`, and launches Android package installation via `FileProvider`.
- **Linux Desktop Receiver (`desktop/`):**
  - `camcraft-core`: Async Tokio TCP receiver with bounded real-time frame queues, zero-swscale fast-path YUV420P decode, IDR keyframe recovery, `v4l2loopback` virtual camera sink (`/dev/video10`), PipeWire virtual microphone (`CamCraft Virtual Mic`), dual-NIC mDNS scanner, and 2-second auto-reconnect with Standby Frame keepalive.
  - `camcraft-gui`: Native ArtCraft Studio v2 dark-mode `eframe`/`egui` studio with live preview, Eco/Balanced/Crisp performance profiles, two-way lens/resolution/FPS/bitrate/zoom/EV/torch/flip/rotate controls, dBFS LED VU meter, and **in-app self-updater** banner.
  - `camcraft-cli`: Headless CLI runner (`connect`, `discover`, `update`, `status`) with `--dump-h264` diagnostics.
- **Open-Source Infrastructure & Automation:**
  - GitHub Actions CI (`rustfmt`, `clippy -D warnings`, workspace tests, Android unit tests/lint/debug build, PR Agent unit tests).
  - Maintainer-controlled GitHub Release CD (manual `workflow_dispatch` or `vX.Y.Z` tag push) producing Fedora x86_64 tarballs, generic Linux x86_64 tarballs, signed/fallback Android APKs, and `SHA256SUMS.txt`.
  - Secret-configured AI PR Review Agent (`AI_API_ENDPOINT`, `AI_API_KEY`, `AI_MODEL`).

[Unreleased]: https://github.com/madhasoeki/CamCraft/compare/v0.0.3...HEAD
[0.0.3]: https://github.com/madhasoeki/CamCraft/releases/tag/v0.0.3
[0.0.2]: https://github.com/madhasoeki/CamCraft/releases/tag/v0.0.2
[0.0.1]: https://github.com/madhasoeki/CamCraft/releases/tag/v0.0.1
