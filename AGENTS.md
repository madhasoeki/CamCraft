# AGENTS.md — Konstitusi Agen AI untuk Monorepo CamCraft

Dokumen ini adalah sumber instruksi utama bagi agen AI (**Hydrogen / Antigravity** di dalam **Zed IDE**) saat membaca, merancang, menulis, atau memodifikasi kode di dalam repositori **CamCraft**.

---

## 1. Identitas & Konteks Proyek
- **Nama Proyek:** CamCraft (bagian dari ekosistem **ArtCraft**).
- **Tujuan Utama:** Aplikasi open-source pengubah smartphone menjadi webcam dan mikrofon PC beresolusi tinggi (1080p–4K @ 60fps) secara lokal dengan latensi sangat rendah (< 35 ms melalui USB, < 60 ms melalui Wi-Fi).
- **Target Platform Tahap Awal (MVP v0.1):**
  - **Host / Receiver:** Fedora Linux (Wayland/X11, `v4l2loopback` untuk kamera virtual, PipeWire/PulseAudio untuk mikrofon virtual) — ditulis dengan **Native Rust (2024 Edition)**.
  - **Client / Sender:** Android (minSdk 29 / Android 10+, targetSdk 35+) — ditulis dengan **Native Kotlin**, **Jetpack Compose**, **CameraX**, dan **`MediaCodec`**.

---

## 2. Peta Konteks (Context Pointers)
Sebelum melakukan perubahan kode, Anda **WAJIB** memeriksa dokumen referensi yang sesuai:
1. **`docs/PROTOCOL.md`** — Wajib dibaca setiap kali Anda menyentuh *serializer* paket di Kotlin, *frame parser* di Rust, negosiasi *handshake*, pengiriman parameter `SPS/PPS` H.264, atau pesan JSON di *Control Channel*. Jangan pernah mengubah struktur byte di satu bahasa tanpa memperbarui bahasa lainnya dan dokumen `docs/PROTOCOL.md`.
2. **`docs/DESIGN.md`** — Wajib dibaca saat merancang arsitektur modul, *threading model*, pipeline *zero-copy*, pengelolaan *buffer*, atau antarmuka pengguna (UI).
3. **`docs/PRD.md`** — Wajib dibaca untuk memastikan fitur yang dibangun sesuai dengan batasan produk dan *acceptance criteria*.
4. **`docs/PLAN.md`** — Wajib dibaca untuk mengetahui *Vertical Slice* mana yang sedang dikerjakan saat ini dan bagaimana cara memverifikasinya.
5. **`graphify-out/GRAPH_REPORT.md` & `graphify-out/graph.json`** — Dihasilkan oleh [Graphify](https://github.com/Graphify-Labs/graphify). Gunakan graf ini (atau panggil `/graphify .`) untuk menelusuri relasi antar-simbol (`EXTRACTED` dan `INFERRED`) lintas Rust dan Kotlin tanpa melakukan pencarian teks buta (`grep`) di seluruh repositori.

---

## 3. Struktur Direktori Monorepo yang Dikunci
Pertahankan struktur direktori berikut secara ketat:

```text
camcraft/
├── AGENTS.md                     # Instruksi agen AI ini
├── Justfile                      # Task runner tunggal (cargo, gradle, adb, v4l2loopback)
├── PROMPTS.md                    # Kumpulan prompt vibecoding per tahap
├── docs/
│   ├── PRD.md                    # Product Requirements Document
│   ├── DESIGN.md                 # System Architecture & Technical Design
│   ├── PROTOCOL.md               # Wire Protocol Specification (CCFT v1)
│   └── PLAN.md                   # Vertical-Slice Execution Plan
├── scripts/
│   └── setup-fedora-dev.sh       # Setup kernel module v4l2loopback & PipeWire di Fedora
├── desktop/                      # Rust Cargo Workspace (Fedora Receiver)
│   ├── Cargo.toml
│   └── crates/
│       ├── camcraft-protocol/    # Pure Rust crate: CCFT v1 binary framing & Control JSON serde
│       ├── camcraft-core/        # Tokio TCP/UDP receiver, H.264/Opus decoder, v4l2/PipeWire sink
│       ├── camcraft-cli/         # Headless daemon / CLI runner untuk testing cepat
│       └── camcraft-gui/         # Native GUI (iced/egui) untuk preview & remote camera control
└── android/                      # Android Gradle Project (Kotlin Sender)
    ├── settings.gradle.kts
    ├── build.gradle.kts
    ├── gradle/libs.versions.toml
    └── app/
        └── src/main/java/org/artcraft/camcraft/
            ├── protocol/         # Kotlin CCFT v1 packet framing & Control JSON serialization
            ├── camera/           # CameraX lifecycle, lens switching, manual ISO/exposure/zoom
            ├── encoder/          # MediaCodec H.264/HEVC surface encoder & AudioRecord Opus encoder
            ├── network/          # Coroutine TCP/UDP socket client, Heartbeat, & mDNS discovery
            └── ui/               # Jetpack Compose UI (dumb UI, state di ViewModel)
```

---

## 4. Aturan Penulisan Kode: Rust (`desktop/`)
Mengacu pada pola [rust-patterns](https://www.skills.sh/kodrunhq/opencode-autopilot/rust-patterns) dan [rust-pro](https://www.skills.sh/vudovn/ag-kit/rust-pro):
1. **Zero `unwrap()` / `panic!()` di Jalur Produksi:** Semua operasi I/O jaringan, decoding video, dan penulisan device V4L2 wajib mengembalikan `Result<T, E>` menggunakan `thiserror` (untuk library crates: `camcraft-protocol`, `camcraft-core`) dan `anyhow` (untuk aplikasi: `camcraft-cli`, `camcraft-gui`).
2. **Disiplin Memori & Zero-Copy di Hot Loop:**
   - Pada *hot loop* penerima frame video (60 kali per detik), **DILARANG** melakukan alokasi `Vec<u8>` baru di setiap frame. Gunakan `bytes::BytesMut` atau *pre-allocated ring buffer pool* (`crossbeam-channel` bounded).
   - Pisahkan *Tokio async network thread* dari *blocking hardware decode & V4L2 ioctl thread* menggunakan `tokio::task::spawn_blocking` atau `std::thread` khusus agar *event loop* jaringan tidak pernah terblokir oleh decoding video.
3. **Kepatuhan Linter:** Kode wajib lolos `cargo clippy --all-targets -- -D warnings` dan `cargo fmt --check` tanpa pengecualian.
4. **Keamanan `unsafe`:** Hindari `unsafe` kecuali mutlak diperlukan untuk FFI (`ioctl` V4L2 atau `ffmpeg` C-bindings). Setiap blok `unsafe` wajib memiliki komentar `// SAFETY: ...` yang menjelaskan invariannya.

---

## 5. Aturan Penulisan Kode: Android Kotlin (`android/`)
Mengacu pada standar [android/skills](https://www.skills.sh/android/skills) (`camerax`, `android-compose-ui`):
1. **Arsitektur UI (MVVM + Unidirectional Data Flow):**
   - Composable functions bersifat *dumb* (hanya merender `UiState` dan meneruskan `UiEvent`).
   - Jangan pernah mengeksekusi operasi soket jaringan, konfigurasi `CameraX`, atau `MediaCodec` di dalam Composable atau di `Dispatchers.Main`.
2. **Pipeline CameraX + MediaCodec:**
   - Gunakan `Surface` langsung dari `MediaCodec.createInputSurface()` yang dihubungkan ke `Preview` atau `VideoCapture` / `ImageAnalysis` CameraX agar frame kamera diproses di GPU/ISP secara *hardware-accelerated* tanpa menyalin byte YUV ke CPU memory Kotlin.
   - Selalu ekstrak `csd-0` (SPS) dan `csd-1` (PPS) dari `MediaFormat` saat `MediaCodec` mengeluarkan `INFO_OUTPUT_FORMAT_CHANGED` atau `BUFFER_FLAG_CODEC_CONFIG`, lalu kirimkan sebagai paket `STREAM_VIDEO_CONFIG (0x01)` sebelum mengirim frame video reguler.
3. **Manajemen Resource & Lifecycle:**
   - Pastikan `MediaCodec`, `AudioRecord`, dan `Socket` selalu ditutup secara bersih (`release()` / `close()`) saat `ViewModel.onCleared()` atau saat pengguna menekan tombol *Disconnect*.
   - Gunakan *Foreground Service* (`connectedDevice` / `camera` / `microphone` foregroundServiceType) agar streaming tidak dimatikan oleh Android OS saat layar HP diredupkan.

---

## 6. Prosedur Kerja Agen di Setiap Giliran (Vibecoding Loop)
1. **Pahami Konteks & Graf:** Baca dokumen spesifikasi terkait dan periksa status `graphify-out/GRAPH_REPORT.md` jika sudah ada.
2. **Kerjakan Satu Vertical Slice Saja:** Jangan menulis kode untuk Tahap 4 saat pengguna meminta Tahap 1. Ikuti batasan di `docs/PLAN.md`.
3. **Verifikasi Mandiri dengan `just`:** Setelah menulis atau mengubah kode:
   - Untuk perubahan Rust: jalankan `just rust-check` dan `just rust-test`.
   - Untuk perubahan Android: jalankan `just android-lint` atau `just android-build`.
   - Untuk perubahan lintas bahasa atau penambahan file baru: perbarui indeks Graphify dengan menjalankan `/graphify .` atau `just graph`.
4. **Laporkan Hasil Secara Ringkas:** Sebutkan file apa yang dibuat/diubah, hasil kompilasi/tes, dan cara pengguna mengujinya di mesin Fedora dan perangkat Android mereka.
