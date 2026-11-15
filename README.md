# CamCraft — Vibecoding Starter Kit (ArtCraft Ecosystem)

**CamCraft** adalah aplikasi open-source berkinerja tinggi yang mengubah smartphone menjadi webcam dan mikrofon PC beresolusi tinggi (1080p hingga 4K @ 60fps) secara lokal tanpa batasan langganan berbayar.

Paket ini dirancang khusus untuk alur kerja **Full AI Vibecoding** menggunakan **[Zed IDE](https://zed.dev/)**, model **Hydrogen (Antigravity)**, **[Graphify](https://github.com/Graphify-Labs/graphify)**, dan ekosistem **[Agent Skills (`npx skills`)](https://www.skills.sh/)**.

---

## Fokus Target Awal (MVP v0.1)
- **Desktop Receiver:** Fedora Linux (Wayland / PipeWire / [`v4l2loopback`](https://github.com/umlaeute/v4l2loopback)) menggunakan **Native Rust** (2024 Edition, `tokio`, `ffmpeg-next`, `iced`/`egui`).
- **Mobile Sender:** Android (API 29+) menggunakan **Native Kotlin** ([Jetpack Compose](https://developer.android.com/compose), [CameraX](https://developer.android.com/training/camerax), `MediaCodec` H.264/HEVC Hardware Encoder, `AudioRecord` + Opus).
- **Arsitektur:** Monorepo tunggal agar model AI dan Graphify dapat memvalidasi kontrak protokol biner antara Rust dan Kotlin secara langsung.

---

## Daftar Isi Paket Ini

```text
camcraft-vibecoding-kit/
├── README.md                     # Panduan utama memulai vibecoding CamCraft
├── AGENTS.md                     # Konstitusi & aturan main agen AI (Hydrogen / Zed)
├── PROMPTS.md                    # Playbook berisi setiap prompt dari Tahap 0 s/d Tahap 8
├── Justfile                      # Unified command runner untuk Rust, Android, ADB, & v4l2loopback
├── docs/
│   ├── PRD.md                    # Product Requirements Document (Visi, Fitur, & Non-Goals)
│   ├── DESIGN.md                 # Arsitektur Sistem, Threading, Zero-Copy Pipeline, & UI/UX
│   ├── PROTOCOL.md               # Spesifikasi Wire Protocol Biner (CCFT v1) & Control JSON
│   └── PLAN.md                   # Rencana Eksekusi Vertical Slices & Kriteria Verifikasi
├── scripts/
│   └── setup-fedora-dev.sh       # Skrip otomatis instalasi dependensi Fedora, v4l2loopback, & PipeWire
└── .zed/
    ├── settings.json             # Konfigurasi Zed IDE (rust-analyzer, Kotlin LSP, Graphify MCP)
    └── tasks.json                # Task satu-klik di Zed untuk build, test, dan tunnel USB
```

---

## Cara Memulai (Quickstart)

### 1. Ekstrak Paket ke Direktori Proyek Baru
```bash
mkdir -p ~/Projects/camcraft
unzip camcraft-vibecoding-kit.zip -d ~/Projects/camcraft
cd ~/Projects/camcraft
git init
```

### 2. Jalankan Setup Sistem Fedora Linux
```bash
chmod +x scripts/setup-fedora-dev.sh
./scripts/setup-fedora-dev.sh
```

### 3. Pastikan Agent Skills & Graphify Sudah Aktif
Pastikan Anda sudah memasang skill dari [skills.sh](https://www.skills.sh/) dan [Graphify](https://github.com/Graphify-Labs/graphify):
```bash
# Agent Skills untuk Android, Rust, dan Arsitektur
npx skills add android/skills@camerax -g -y
npx skills add android/skills@android-cli -g -y
npx skills add android/skills@android-profiler -g -y
npx skills add vudovn/ag-kit@rust-pro -g -y
npx skills add kodrunhq/opencode-autopilot@rust-patterns -g -y
npx skills add mattpocock/skills@grill-me -g -y
npx skills add mattpocock/skills@to-spec -g -y
npx skills add mattpocock/skills@improve-codebase-architecture -g -y

# Graphify Knowledge Graph
uv tool install graphifyy
graphify install
```

### 4. Buka di Zed IDE & Mulai Vibecoding!
```bash
zed .
```
Buka file **`PROMPTS.md`**, lalu salin **Prompt Tahap 0** ke panel Agent (Hydrogen) di Zed untuk menginisialisasi struktur Cargo & Gradle serta membangun grafik pengetahuan pertama dengan `/graphify .`.

---

## Referensi Dokumentasi & Sumber
- **Graphify Knowledge Graph:** [Graphify-Labs/graphify](https://github.com/Graphify-Labs/graphify) & [Graphify MCP](https://github.com/Graphify-Labs/graphify-mcp)
- **Open Agent Skills Directory:** [skills.sh](https://www.skills.sh/) ([android/skills](https://www.skills.sh/android/skills), [mattpocock/skills](https://www.skills.sh/mattpocock/skills), [rust-pro](https://www.skills.sh/vudovn/ag-kit/rust-pro), [rust-patterns](https://www.skills.sh/kodrunhq/opencode-autopilot/rust-patterns))
- **Linux Virtual Camera Driver:** [umlaeute/v4l2loopback](https://github.com/umlaeute/v4l2loopback)
- **ADB Port Forwarding Reference:** [ADB Commands — Port Forwarding](https://hacktricks.wiki/en/mobile-pentesting/android-app-pentesting/adb-commands.html)
