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
    pactl load-module module-null-sink sink_name=camcraft_sink sink_properties=device.description="CamCraft_Audio_Sink" || true
    pactl load-module module-remap-source master=camcraft_sink.monitor source_name=camcraft_mic source_properties=device.description="CamCraft_Virtual_Mic" || true

# Buat tunnel USB ADB pada port 9876 (Android <-> Fedora PC)
usb-tunnel:
    @echo "Memeriksa perangkat Android via ADB..."
    adb devices
    adb forward tcp:9876 tcp:9876
    @echo "Tunnel USB aktif di localhost:9876 -> Android:9876"

# -----------------------------------------------------------------------------
# 2. Perintah Rust Desktop (Fedora Receiver)
# -----------------------------------------------------------------------------

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

# Jalankan CLI Daemon CamCraft via USB menuju /dev/video10
run-cli-usb: usb-tunnel
    cd desktop && cargo run --release -p camcraft-cli -- connect --usb --vcam /dev/video10

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

# Build dan langsung instal APK Debug ke HP Android yang terhubung via USB
android-install:
    cd android && ./gradlew installDebug

# -----------------------------------------------------------------------------
# 4. Graphify & Verifikasi Monorepo Menyeluruh
# -----------------------------------------------------------------------------

# Perbarui knowledge graph Graphify untuk kode + dokumen
graph:
    graphify .

# Jalankan pemeriksaan kualitas menyeluruh (Rust + Android)
check-all: rust-check rust-lint rust-test android-test
    @echo "Seluruh pemeriksaan Rust & Android berhasil!"
