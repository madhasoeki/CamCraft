#!/usr/bin/env bash
# =============================================================================
# CamCraft — Skrip Otomasi Setup Lingkungan Pengembangan Fedora Linux
# =============================================================================
set -euo pipefail

echo "=================================================================="
echo " Memulai Setup Lingkungan Pengembangan CamCraft (Fedora Linux)    "
echo "=================================================================="

echo "[1/4] Memasang paket sistem, tool multimedia, dan header development..."
sudo dnf install -y \
    just \
    android-tools \
    v4l-utils \
    v4l2loopback \
    akmod-v4l2loopback \
    pipewire-utils \
    pulseaudio-utils \
    pkgconf-pkg-config \
    clang \
    llvm-devel \
    ffmpeg-free-devel \
    alsa-lib-devel \
    pulseaudio-libs-devel \
    openssl-devel \
    glib2-devel \
    gtk3-devel \
    libxcb-devel \
    libxkbcommon-devel \
    libxkbcommon-x11-devel \
    wayland-devel

echo "[2/4] Mengonfigurasi modul kernel v4l2loopback (/dev/video10)..."
if ! lsmod | grep -q v4l2loopback; then
    echo "Memuat modul v4l2loopback (video_nr=10, card_label='CamCraft Virtual Camera', exclusive_caps=1)..."
    sudo akmods --force || true
    sudo modprobe v4l2loopback devices=1 video_nr=10 card_label="CamCraft Virtual Camera" exclusive_caps=1 || {
        echo "PERINGATAN: modprobe v4l2loopback gagal. Jika Secure Boot aktif di Fedora,"
        echo "pastikan modul akmod Anda sudah ditandatangani (mokutil) atau matikan Secure Boot sementara."
    }
else
    echo "Modul v4l2loopback sudah aktif."
fi

# Buat konfigurasi persisten agar otomatis aktif saat boot
sudo tee /etc/modprobe.d/camcraft-v4l2loopback.conf >/dev/null <<'EOF'
options v4l2loopback devices=1 video_nr=10 card_label="CamCraft Virtual Camera" exclusive_caps=1
EOF

echo "[3/4] Menyiapkan PipeWire Virtual Microphone ('CamCraft Virtual Mic')..."
if command -v pactl >/dev/null 2>&1; then
    if ! pactl list short sinks | grep -q "camcraft_sink"; then
        pactl load-module module-null-sink sink_name=camcraft_sink rate=48000 channels=1 channel_map=mono sink_properties='device.description="CamCraft Audio Sink"' || true
    fi
    if ! pactl list short sources | grep -qE "camcraft_virtual_mic|camcraft_mic"; then
        pactl load-module module-remap-source master=camcraft_sink.monitor source_name=camcraft_virtual_mic rate=48000 channels=1 channel_map=mono source_properties='device.description="CamCraft Virtual Mic"' || true
    fi
fi

echo "[4/4] Memeriksa instalasi Rust, Graphify, dan ADB..."
rustc --version || echo "Rust belum terpasang! Pasang via https://rustup.rs"
adb --version | head -n 1 || true
if command -v graphify >/dev/null 2>&1; then
    echo "Graphify CLI terdeteksi: $(command -v graphify)"
else
    echo "Tips: Jalankan 'uv tool install graphifyy && graphify install' untuk mengaktifkan Graphify."
fi

echo "=================================================================="
echo " Setup Fedora selesai! Gunakan 'just --list' untuk melihat perintah."
echo "=================================================================="
