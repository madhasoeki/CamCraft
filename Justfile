# =============================================================================
# CamCraft Monorepo — Unified Command Runner (Justfile)
# =============================================================================
# Pastikan `just` sudah terpasang di Fedora: `sudo dnf install just`
# Jalankan `just` atau `just --list` untuk melihat seluruh perintah yang tersedia.

set shell := ["bash", "-cu"]

# Default: tampilkan daftar perintah
default:
    @just --list

# -----------------------------------------------------------------------------
# 1. Setup Sistem Fedora (Virtual Camera, Virtual Mic, & USB Tunnel)
# -----------------------------------------------------------------------------

# Instal seluruh paket sistem Fedora dan konfigurasi awal
setup-fedora:
    chmod +x scripts/setup-fedora-dev.sh
    ./scripts/setup-fedora-dev.sh

# Aktifkan modul kernel v4l2loopback pada /dev/video10 ("CamCraft Virtual Camera")
setup-vcam:
    @echo "Mengaktifkan v4l2loopback pada /dev/video10..."
    sudo modprobe -r v4l2loopback 2>/dev/null || true
    sudo modprobe v4l2loopback devices=1 video_nr=10 card_label="CamCraft Virtual Camera" exclusive_caps=1
    v4l2-ctl --list-devices || true

# Buat PipeWire Virtual Microphone ("CamCraft Virtual Mic")
setup-vmic:
    @echo "Membuat PipeWire Virtual Microphone (CamCraft Virtual Mic)..."
    pactl load-module module-null-sink sink_name=camcraft_sink rate=48000 channels=1 channel_map=mono sink_properties='device.description="CamCraft Audio Sink"' || true
    pactl load-module module-remap-source master=camcraft_sink.monitor source_name=camcraft_virtual_mic rate=48000 channels=1 channel_map=mono source_properties='device.description="CamCraft Virtual Mic"' || true

# Buat tunnel USB ADB pada port 9876 (Android <-> Fedora PC)
usb-tunnel:
    @echo "Memeriksa perangkat Android via ADB..."
    adb devices
    adb forward tcp:9876 tcp:9876
    @echo "Tunnel USB aktif di localhost:9876 -> Android:9876"

# -----------------------------------------------------------------------------
# 2. Perintah Rust Desktop (Fedora Receiver)
# -----------------------------------------------------------------------------

# Periksa format kode Rust (rustfmt)
rust-fmt:
    cd desktop && cargo fmt --all -- --check

# Periksa kompilasi seluruh workspace Rust
rust-check:
    cd desktop && cargo check --workspace --all-targets

# Jalankan linter Clippy dengan standar ketat
rust-lint:
    cd desktop && cargo clippy --workspace --all-targets -- -D warnings

# Jalankan seluruh unit test dan integration test Rust
rust-test:
    cd desktop && cargo test --workspace

# Jalankan aplikasi GUI Desktop CamCraft (Mode Release agar decoding cepat)
run-gui:
    cd desktop && cargo run --release -p camcraft-gui

# Jalankan CLI receiver via USB (Testing Stage 2)
cli-usb:
    cd desktop && cargo run -p camcraft-cli -- connect --usb

# Jalankan CLI Daemon CamCraft via USB menuju /dev/video10 (Stage 4+)
run-cli-usb: usb-tunnel
    cd desktop && cargo run --release -p camcraft-cli -- connect --usb --vcam /dev/video10

# Bangun paket RPM lokal di direktori dist/ menggunakan rpmbuild
build-rpm:
    #!/usr/bin/env bash
    set -euo pipefail
    VERSION="$(awk -F'"' '/^version = / {print $2; exit}' desktop/Cargo.toml)"
    RPM_TOPDIR="${PWD}/dist/rpmbuild"
    mkdir -p "${RPM_TOPDIR}"/{BUILD,RPMS,SOURCES,SPECS,SRPMS}
    (cd desktop && cargo build --release --locked -p camcraft-gui -p camcraft-cli)
    rpmbuild -bb packaging/linux/camcraft.spec \
        --define "_topdir ${RPM_TOPDIR}" \
        --define "_sourcedir ${PWD}" \
        --define "_version ${VERSION}" \
        --define "_bindir ${PWD}/desktop/target/release"
    find "${RPM_TOPDIR}/RPMS" -name "*.rpm" -exec cp -v {} dist/ \;

# -----------------------------------------------------------------------------
# 3. Perintah Android (Kotlin Native Sender)
# -----------------------------------------------------------------------------

# Jalankan unit test protokol & logika di Android
android-test:
    cd android && ./gradlew testDebugUnitTest

#Jalankan lint check Android
android-lint:
    cd android && ./gradlew lintDebug

# Build APK Debug Android
android-build:
    cd android && ./gradlew assembleDebug

# Build APK Release Android (R8 minified)
android-release:
    cd android && ./gradlew assembleRelease

# Build dan langsung instal APK Debug ke HP Android yang terhubung via USB
android-install:
    cd android && ./gradlew installDebug

# -----------------------------------------------------------------------------
# 4. Graphify, PR Agent & Verifikasi Monorepo Menyeluruh
# -----------------------------------------------------------------------------

# Jalankan unit test PR Agent
pr-agent-test:
    python3 -m unittest discover -s .github/scripts -p "test_*.py" -v

# Perbarui knowledge graph Graphify untuk kode + dokumen
graph:
    graphify .

# Jalankan pemeriksaan kualitas menyeluruh (Rust + Android + PR Agent)
check-all: rust-fmt rust-check rust-lint rust-test android-test pr-agent-test
    @echo "Seluruh pemeriksaan Rust, Android, & PR Agent berhasil!"
