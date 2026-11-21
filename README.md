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
  - **USB Direct (AOA v1 Plug & Play):** Zero-config direct USB bulk transport — no Developer Options or USB Debugging required (just plug in USB and tap **Allow** on Android; Legacy ADB `--adb` remains available as an option).
  - **Wi-Fi LAN:** Automatic `_camcraft._tcp.` mDNS discovery (with dual-NIC subnet routing) plus manual `IP:Port` fallback and instant IDR keyframe recovery.
- **Native Linux Virtual Devices:**
  - **Zero-Setup PipeWire Virtual Camera (+ Legacy V4L2 Fallback):** Streams directly into PipeWire as `"CamCraft Virtual Camera"` via runtime-loaded `libpipewire-0.3` (no kernel modules required!), with automatic fallback to `v4l2loopback` (`/dev/video10`) when needed — ready for OBS Studio, Discord / Vesktop, Chromium, Firefox, and Zoom.
  - **Virtual Microphone:** Forwards 48 kHz mono 16-bit PCM microphone audio into a PipeWire/PulseAudio virtual source (`"CamCraft Virtual Mic"`) with a live dBFS LED VU meter.
- **Two-Way Studio Controls:** Switch lenses (front / back / ultrawide), resolution, FPS, bitrate (4–40 Mbps), zoom, exposure compensation (EV), autofocus, torch, horizontal mirror, 90° rotation, and audio mute from either desktop GUI or phone.
- **Built for Long Sessions:** Automatic 2-second reconnection with a non-freezing Standby Frame (`"CAMCRAFT - RECONNECTING..."`), **Eco / Balanced / Crisp** laptop preview profiles, and an **OLED Battery Saver** black overlay (`0.01f` brightness + anti-burn-in drift) on Android.
- **Seamless In-App Updates:** Desktop (`camcraft-gui` / `camcraft-cli`) and Android check GitHub Releases automatically and verify `SHA256SUMS.txt` — AppImages and portable archives update in place, `.rpm`/`.deb` packages download cleanly with ready-to-run install commands, and Android launches the APK installer directly.

---

## Architecture Overview

CamCraft is a single monorepo sharing a custom **CCFT v1** binary wire protocol (`docs/PROTOCOL.md`) across Rust and Kotlin:

```text
CamCraft/
├── desktop/                     # Rust 2024 Cargo workspace (Linux receiver)
│   └── crates/
│       ├── camcraft-protocol/   # CCFT v1 20-byte framing & JSON control schemas
│       ├── camcraft-core/       # Tokio client, USB AOA v1, ADB fallback, mDNS, H.264 decode, PipeWire/V4L2 vcam, PipeWire vmic, updater
│       ├── camcraft-cli/        # Headless daemon & CLI (`connect`, `discover`, `update`, `status`)
│       └── camcraft-gui/        # Native eframe/egui studio UI with live preview & remote controls
├── android/                     # Native Kotlin + Jetpack Compose sender (minSdk 29, targetSdk 35)
│   └── app/src/main/java/org/artcraft/camcraft/
│       ├── protocol/            # Byte-identical CCFT v1 serializer & JSON models
│       ├── camera/              # CameraX lifecycle, lens & FPS capability enumerator
│       ├── encoder/             # MediaCodec H.264 surface encoder & AudioRecord 48kHz PCM
│       ├── network/             # Foreground service (USB AOA + TCP server) & NsdManager mDNS
│       ├── update/              # GitHub Releases update checker, SHA-256 verifier & APK installer
│       └── ui/                  # Stateless ArtCraft Studio v2 Compose UI + OLED Battery Saver
├── packaging/linux/             # RPM spec, DEB builder, AppImage builder & `70-camcraft-aoa.rules`
├── assets/branding/             # Official Miter Frame logos, app icons, banners & .desktop entry
├── scripts/setup-fedora-dev.sh  # Developer-only Fedora source build & optional V4L2 setup script
└── docs/                        # Architecture (`DESIGN.md`), Wire Spec (`PROTOCOL.md`), PRD (`PRD.md`)
```

---

## Installation

### 1. Download Prebuilt Releases (Recommended)

Download the latest release assets from [**GitHub Releases**](https://github.com/madhasoeki/CamCraft/releases/latest):

| Platform | Package | Quick Install |
| :--- | :--- | :--- |
| **Any Linux (Universal)** | `CamCraft-vX.Y.Z-x86_64.AppImage` | `chmod +x CamCraft-*.AppImage && ./CamCraft-*.AppImage` |
| **Fedora / RHEL / Nobara (x86_64)** | `camcraft-X.Y.Z-1.x86_64.rpm` | `sudo dnf install -y ./camcraft-*.rpm` |
| **Ubuntu / Debian / Linux Mint (amd64)** | `camcraft_X.Y.Z_amd64.deb` | `sudo apt install -y ./camcraft_*_amd64.deb` |
| **Android (Universal APK, ~2.8 MB)** | `camcraft-android-vX.Y.Z.apk` | Install directly on Android 10+ (API 29+) |

Portable tarballs (`camcraft-desktop-fedora-x86_64.tar.gz`, `camcraft-desktop-linux-x86_64.tar.gz`) and `SHA256SUMS.txt` are also published on every release.

### 2. Zero-Setup Virtual Camera & Plug-and-Play USB

End users installing the `.AppImage`, `.rpm`, or `.deb` do **not** need `setup-fedora-dev.sh`, manual `v4l2loopback` setup, or Android USB Debugging:

- **Zero-Setup PipeWire Virtual Camera:** By default (`Auto (PipeWire)`), CamCraft exposes `"CamCraft Virtual Camera"` natively through PipeWire (`libpipewire-0.3` loaded dynamically at runtime). If your system lacks PipeWire or an app requires a `/dev/video*` node, select **Legacy V4L2** in the **Connect** tab (or pass `--vcam-backend v4l2` in CLI) and click **Enable Legacy V4L2 (Polkit)** if `v4l2loopback` is not yet loaded.
- **Plug-and-Play USB (AOA v1):** Direct USB works out of the box without enabling Developer Options or ADB. Native `.rpm` and `.deb` packages install `70-camcraft-aoa.rules` automatically. If you run the universal `.AppImage` (or a portable tarball) without udev rules installed, click the 1-click **Grant USB Access** button in the **Connect** tab when prompted.

### 3. Build from Source (Developers Only)

The `scripts/setup-fedora-dev.sh` helper is **only for compiling CamCraft from source on Fedora** (installing Rust/Clang/FFmpeg development headers and optional `v4l2loopback` tooling) — end users do not need it:

```bash
# Optional: Fedora developer dependencies for building from source
chmod +x scripts/setup-fedora-dev.sh
./scripts/setup-fedora-dev.sh

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
1. Open **CamCraft** on your Android phone (for Wi-Fi, tap **Start Server**; for USB, simply plug in the cable).
2. Launch `camcraft-gui` on Linux (or `./CamCraft-*.AppImage`):
   - **USB (Plug & Play):** Plug in your phone via USB, tap **Allow** on the Android USB accessory prompt, and click **Connect via USB (Plug & Play)**. (If you prefer ADB forwarding, switch to **Legacy ADB** in the **Connect** tab.)
   - **Wi-Fi:** Pick your phone from **Wi-Fi Discovered Devices (mDNS)** or enter `<phone-ip>:9876`.
3. Select **CamCraft Virtual Camera** and **CamCraft Virtual Mic** inside OBS Studio, Discord / Vesktop, Chromium, or Firefox.

**Keyboard shortcuts (GUI):** `[M]` Mute Mic · `[P]` Pause/Resume Preview (lowest CPU latency) · `[H]` Horizontal Mirror · `[R]` Rotate +90° CW.

### Headless CLI (`camcraft-cli`)
```bash
# Stream over Plug & Play USB AOA to PipeWire Virtual Camera (Auto) + Virtual Mic
camcraft-cli connect --usb

# Force Legacy V4L2 virtual camera (/dev/video10) or PipeWire explicitly
camcraft-cli connect --usb --vcam-backend v4l2 --vcam /dev/video10
camcraft-cli connect --usb --vcam-backend pipewire

# Use legacy ADB port forwarding instead of USB AOA
camcraft-cli connect --usb --adb

# Discover Android devices on Wi-Fi LAN via mDNS
camcraft-cli discover --timeout 5

# Connect over Wi-Fi
camcraft-cli connect --wifi 192.168.1.42:9876

# Check for or apply desktop updates from GitHub Releases
camcraft-cli update --check
camcraft-cli update
```

### In-App Updates
- **Desktop GUI:** Checks GitHub Releases silently at startup and shows a non-intrusive top banner when a new version is available. AppImage and portable installs update in place with a **Restart Now** prompt; `.rpm` / `.deb` installs download and verify the package with `SHA256SUMS.txt`, launch your system installer, and display the exact terminal command if needed. You can also check manually under **Connect → App Version & Updates**.
- **Desktop CLI:** Run `camcraft-cli update` (updates AppImage/portable binaries in place, or downloads and verifies the `.rpm`/`.deb` package and prints the install command).
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
