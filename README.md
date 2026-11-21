<div align="center">

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="assets/branding/svg/camcraft-readme-banner-dark.svg">
  <source media="(prefers-color-scheme: light)" srcset="assets/branding/svg/camcraft-readme-banner-light.svg">
  <img alt="CamCraft — Ultra-low-latency local smartphone webcam and microphone" src="assets/branding/svg/camcraft-readme-banner-dark.svg" width="760">
</picture>

**Turn your Android smartphone into an ultra-low-latency 1080p–4K @ 60 FPS Linux webcam and microphone — 100% locally, with zero paywalls or cloud relays.**

[![CI](https://github.com/madhasoeki/CamCraft/actions/workflows/ci.yml/badge.svg)](https://github.com/madhasoeki/CamCraft/actions/workflows/ci.yml)
[![Latest Release](https://img.shields.io/github/v/release/madhasoeki/CamCraft?include_prereleases&sort=semver)](https://github.com/madhasoeki/CamCraft/releases)
[![License](https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg)](LICENSE-MIT)
[![Rust 2024](https://img.shields.io/badge/Desktop-Rust%202024-orange.svg)](desktop/Cargo.toml)
[![Android Kotlin](https://img.shields.io/badge/Android-API%2029%2B%20Compose-3DDC84.svg)](android/app/build.gradle.kts)

</div>

---

## Highlights

- **High-Resolution, 60 FPS Hardware Pipeline:** Streams `1280x720`, `1920x1080`, and `3840x2160` (device-dependent) up to **60 FPS** using Android CameraX + `MediaCodec` hardware H.264 surface encoding and FFmpeg `AV_CODEC_FLAG_LOW_DELAY` decoding in Rust.
- **USB & Wireless Transport:**
  - **USB (ADB Tunnel):** Ultra-low latency over `127.0.0.1:9876` (`adb forward tcp:9876 tcp:9876`).
  - **Wi-Fi LAN:** Automatic `_camcraft._tcp.` mDNS discovery (with dual-NIC subnet routing) plus manual `IP:Port` fallback and instant IDR keyframe recovery.
- **Native Linux Virtual Devices:**
  - **Virtual Camera:** Injects decoded YUV420P frames directly into `v4l2loopback` (`/dev/video10`, `"CamCraft Virtual Camera"`), ready for OBS Studio, Discord / Vesktop, Chromium, Firefox, and Zoom.
  - **Virtual Microphone:** Forwards 48 kHz mono 16-bit PCM microphone audio into a PipeWire/PulseAudio virtual source (`"CamCraft Virtual Mic"`) with a live dBFS LED VU meter.
- **Two-Way Studio Controls:** Switch lenses (front / back / ultrawide), resolution, FPS, bitrate (4–40 Mbps), zoom, exposure compensation (EV), autofocus, torch, horizontal mirror, 90° rotation, and audio mute from either desktop GUI or phone.
- **Built for Long Sessions:** Automatic 2-second reconnection with a non-freezing `v4l2loopback` Standby Frame (`"CAMCRAFT - RECONNECTING..."`), **Eco / Balanced / Crisp** laptop preview profiles, and an **OLED Battery Saver** black overlay (`0.01f` brightness + anti-burn-in drift) on Android.
- **Seamless In-App Updates:** Both desktop (`camcraft-gui` / `camcraft-cli`) and Android check GitHub Releases automatically, verify `SHA256SUMS.txt`, and update in place without manual browser downloads.

---

## Architecture Overview

CamCraft is a single monorepo sharing a custom **CCFT v1** binary wire protocol (`docs/PROTOCOL.md`) across Rust and Kotlin:

```text
CamCraft/
├── desktop/                     # Rust 2024 Cargo workspace (Linux receiver)
│   └── crates/
│       ├── camcraft-protocol/   # CCFT v1 20-byte framing & JSON control schemas
│       ├── camcraft-core/       # Tokio client, ADB tunnel, mDNS, H.264 decode, V4L2, PipeWire, updater
│       ├── camcraft-cli/        # Headless daemon & CLI (`connect`, `discover`, `update`, `status`)
│       └── camcraft-gui/        # Native eframe/egui studio UI with live preview & remote controls
├── android/                     # Native Kotlin + Jetpack Compose sender (minSdk 29, targetSdk 35)
│   └── app/src/main/java/org/artcraft/camcraft/
│       ├── protocol/            # Byte-identical CCFT v1 serializer & JSON models
│       ├── camera/              # CameraX lifecycle, lens & FPS capability enumerator
│       ├── encoder/             # MediaCodec H.264 surface encoder & AudioRecord 48kHz PCM
│       ├── network/             # Foreground streaming service (TCP server) & NsdManager mDNS
│       ├── update/              # GitHub Releases update checker, SHA-256 verifier & APK installer
│       └── ui/                  # Stateless ArtCraft Studio v2 Compose UI + OLED Battery Saver
├── assets/branding/             # Official Miter Frame logos, app icons, banners & .desktop entry
├── scripts/setup-fedora-dev.sh  # One-time Fedora Linux dependency & virtual device setup
└── docs/                        # Architecture (`DESIGN.md`), Wire Spec (`PROTOCOL.md`), PRD (`PRD.md`)
```

---

## Installation

### 1. Download Prebuilt Releases (Recommended)

Download the latest release assets from [**GitHub Releases**](https://github.com/madhasoeki/CamCraft/releases/latest):

| Target | Asset | Notes |
| :--- | :--- | :--- |
| **Fedora Linux (x86_64)** | `camcraft-desktop-fedora-x86_64.tar.gz` | Contains `camcraft-gui`, `camcraft-cli`, and `setup-fedora-dev.sh` |
| **Generic Linux (x86_64)** | `camcraft-desktop-linux-x86_64.tar.gz` | Contains `camcraft-gui` and `camcraft-cli` |
| **Android (10 / API 29+)** | `camcraft-android-vX.Y.Z.apk` | Install directly on your Android device |
| **Checksums** | `SHA256SUMS.txt` | SHA-256 verification digests for all release assets |

Extract the desktop archive into a writable user folder (such as `~/.local/bin`) so the in-app updater can replace binaries without elevated privileges:

```bash
tar -xzf camcraft-desktop-fedora-x86_64.tar.gz
mkdir -p ~/.local/bin ~/.local/share/applications ~/.local/share/icons/hicolor/scalable/apps
cp camcraft-desktop-fedora-x86_64/camcraft-{gui,cli} ~/.local/bin/
cp camcraft-desktop-fedora-x86_64/org.artcraft.camcraft.desktop ~/.local/share/applications/
cp camcraft-desktop-fedora-x86_64/org.artcraft.camcraft.svg ~/.local/share/icons/hicolor/scalable/apps/
```

### 2. One-Time Linux System Setup (Virtual Camera & Microphone)

On **Fedora Linux**, install `v4l2loopback`, PipeWire utilities, ADB, and runtime FFmpeg libraries:

```bash
chmod +x scripts/setup-fedora-dev.sh
./scripts/setup-fedora-dev.sh
```

Or enable the virtual devices for the current session using [`just`](https://github.com/casey/just):

```bash
just setup-vcam   # Loads v4l2loopback on /dev/video10 ("CamCraft Virtual Camera")
just setup-vmic   # Creates PipeWire virtual source ("CamCraft Virtual Mic")
```

> **Secure Boot note (Fedora):** If `modprobe v4l2loopback` fails when Secure Boot is enabled, enroll your MOK key (`akmods` / `mokutil`) or disable Secure Boot in UEFI.

### 3. Build from Source

```bash
# Desktop (Rust 2024)
cd desktop
cargo build --release --locked -p camcraft-gui -p camcraft-cli

# Android (Debug or Release APK)
cd android
./gradlew assembleDebug
```

---

## Usage

### Desktop GUI (`camcraft-gui`)
1. Open **CamCraft** on your Android phone and tap **Start Server**.
2. Launch `camcraft-gui` on Linux:
   - **USB:** Connect your phone with USB Debugging enabled and click **Connect via USB (ADB)**.
   - **Wi-Fi:** Pick your phone from **Wi-Fi Discovered Devices (mDNS)** or enter `<phone-ip>:9876`.
3. Select **CamCraft Virtual Camera** and **CamCraft Virtual Mic** inside OBS Studio, Discord / Vesktop, Chromium, or Firefox.

**Keyboard shortcuts (GUI):** `[M]` Mute Mic · `[P]` Pause/Resume Preview (lowest CPU latency) · `[H]` Horizontal Mirror · `[R]` Rotate +90° CW.

### Headless CLI (`camcraft-cli`)
```bash
# Stream over USB to /dev/video10 + PipeWire virtual mic
camcraft-cli connect --usb --vcam /dev/video10

# Discover Android devices on Wi-Fi LAN via mDNS
camcraft-cli discover --timeout 5

# Connect over Wi-Fi
camcraft-cli connect --wifi 192.168.1.42:9876

# Check for or install desktop updates from GitHub Releases
camcraft-cli update --check
camcraft-cli update
```

### In-App Updates
- **Desktop GUI:** Checks GitHub Releases silently at startup and shows a non-intrusive top banner when a new version is available. Click **Update Now** to download, verify `SHA256SUMS.txt`, replace the binary, and restart in one click. You can also check manually under **Connect → App Version & Updates**.
- **Desktop CLI:** Run `camcraft-cli update` (updates `camcraft-cli` and sibling `camcraft-gui` in the same directory).
- **Android:** Checks GitHub Releases at startup (or tap the version badge e.g. `v0.0.1` in the top status card), verifies `SHA256SUMS.txt`, and opens the Android installer directly.

---

## Maintainer Operations (GitHub CI/CD, PR Agent & Releases)

### AI PR Review Agent Setup (3 Secrets Only)

Every Pull Request (`opened`, `reopened`, `ready_for_review`, `synchronize`) — or maintainer comment `/review` — triggers `.github/workflows/pr-agent.yml`, which runs `.github/scripts/pr_review_agent.py` against any OpenAI-compatible Chat Completions endpoint configured via **Settings → Secrets and variables → Actions → Repository secrets**:

| Secret Name | Required | Description & Examples |
| :--- | :--- | :--- |
| `AI_API_ENDPOINT` | Yes | Base URL or full `/chat/completions` URL (e.g. `https://api.openai.com/v1`, `https://openrouter.ai/api/v1`, `https://generativelanguage.googleapis.com/v1beta/openai`, `https://api.deepseek.com/v1`) |
| `AI_API_KEY` | Yes | Provider API key / Bearer token |
| `AI_MODEL` | Yes | Model identifier string (e.g. `gpt-4.1-mini`, `gemini-2.5-flash`, `anthropic/claude-sonnet-4`) |

If any of these three secrets are unset (including on external fork PRs where secrets are withheld), the PR Agent exits cleanly without failing the workflow.

### Cutting Releases (Maintainer-Controlled Only)

Merging a Pull Request into `main` **never** publishes a release automatically. Releases are only built and published by `.github/workflows/release.yml` when you explicitly trigger one:

- **Option A — Tag Push:**
  ```bash
  git tag -a v0.0.2 -m "Release v0.0.2"
  git push origin v0.0.2
  ```
- **Option B — GitHub Actions UI:**
  Go to **Actions → Release → Run workflow**, enter the SemVer tag (e.g. `v0.0.2`), and choose whether to mark it as draft/prerelease.

#### Optional: Persistent Android Release Signing Secrets
If you want release APKs signed with a persistent release keystore instead of the CI debug fallback key, add these four Repository Secrets:

```bash
keytool -genkeypair -v -keystore camcraft-release.jks -alias camcraft \
  -keyalg RSA -keysize 2048 -validity 10000
base64 -w 0 camcraft-release.jks
```

| Secret Name | Value |
| :--- | :--- |
| `ANDROID_KEYSTORE_BASE64` | Single-line base64 output of your `.jks` keystore |
| `ANDROID_KEYSTORE_PASSWORD` | Keystore password |
| `ANDROID_KEY_ALIAS` | Key alias (e.g. `camcraft`) |
| `ANDROID_KEY_PASSWORD` | Key password |

---

## Contributing & Security

- Contributions are welcome! See [CONTRIBUTING.md](CONTRIBUTING.md) and our [Code of Conduct](CODE_OF_CONDUCT.md).
- To report a security issue privately, see [SECURITY.md](SECURITY.md).

## License

CamCraft is dual-licensed under either of:

- **MIT License** ([LICENSE-MIT](LICENSE-MIT))
- **Apache License, Version 2.0** ([LICENSE-APACHE](LICENSE-APACHE))

at your option.
