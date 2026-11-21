# Changelog

All notable changes to **CamCraft** are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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

[Unreleased]: https://github.com/madhasoeki/CamCraft/compare/v0.0.1...HEAD
[0.0.1]: https://github.com/madhasoeki/CamCraft/releases/tag/v0.0.1
