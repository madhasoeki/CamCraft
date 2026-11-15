# Rencana Eksekusi Vertical Slices (`PLAN.md`)

Dokumen ini membagi pengembangan **CamCraft** menjadi **8 Tahap Berurutan (Vertical Slices)**. Saat melakukan *vibecoding* dengan model Hydrogen di Zed, **selesaikan dan verifikasi satu tahap hingga tuntas sebelum berpindah ke tahap berikutnya**.

---

## Ringkasan Tahapan (Milestones)

| Tahap | Nama Milestone | Fokus Utama | Output yang Dapat Diverifikasi |
| :--- | :--- | :--- | :--- |
| **Tahap 0** | Monorepo Scaffolding & Graphify | Kerangka Cargo + Gradle + Protokol Bersama | `just check-all` sukses & `/graphify .` menghasilkan graf awal |
| **Tahap 1** | Protokol Biner `CCFT v1` (Rust & Kotlin) | Serializer/Parser header 20-byte & JSON Control | Unit test *round-trip* di Rust dan JUnit di Kotlin 100% lulus |
| **Tahap 2** | Koneksi USB (`adb forward`) & Dummy Stream | Jembatan soket TCP 9876 + Synthetic Test Pattern | CLI Rust menerima paket dari Android via USB & mencetak FPS/RTT |
| **Tahap 3** | Pipeline CameraX + Hardware H.264 (Android) | Capture 1080p/4K -> `MediaCodec` -> Stream `SPS/PPS` + NAL | Android mengirimkan stream H.264 nyata ke PC melalui USB |
| **Tahap 4** | Hardware/FFmpeg Decode + `v4l2loopback` (Fedora) | Decode H.264 -> `YUV420P` -> `/dev/video10` | Kamera muncul di OBS Studio, Vesktop/Discord, dan Google Meet |
| **Tahap 5** | Native Desktop GUI & Two-Way Camera Controls | Aplikasi GUI Rust (`egui`/`iced`) + Kontrol Lensa/EV/Zoom | Klik tombol di GUI Fedora langsung mengubah lensa/exposure HP |
| **Tahap 6** | Koneksi Nirkabel Wi-Fi & mDNS Discovery | Streaming tanpa kabel + QR/Auto-discovery LAN | Streaming 1080p@60fps berjalan mulus tanpa kabel USB |
| **Tahap 7** | Audio Streaming (Opus) & PipeWire Virtual Mic | `AudioRecord` -> Opus -> PipeWire Virtual Source | Suara dari mikrofon HP masuk sebagai input mikrofon di Fedora |
| **Tahap 8** | Optimasi Zero-Copy, OLED Saver & Polish | Profiling memori/latensi, mode layar gelap HP, auto-reconnect | Latensi USB < 35ms, pemakaian CPU rendah, siap rilis v0.1 |

---

## Detail Setiap Tahap & Kriteria Verifikasi (Acceptance Criteria)

### Tahap 0: Monorepo Scaffolding & Graphify Baseline
- **Tugas:**
  - Membuat workspace Cargo di `desktop/` dengan 4 crate: `camcraft-protocol`, `camcraft-core`, `camcraft-cli`, dan `camcraft-gui`.
  - Membuat proyek Gradle Kotlin di `android/` menggunakan `gradle/libs.versions.toml` (Version Catalog) dengan dependensi CameraX, Jetpack Compose Material 3, Kotlinx Serialization, dan Coroutines.
  - Menjalankan indeksasi awal Graphify.
- **Kriteria Lulus:**
  - `cargo check --workspace` di dalam `desktop/` selesai dengan kode keluar `0`.
  - `./gradlew assembleDebug` di dalam `android/` (atau struktur modulnya) valid.
  - File `graphify-out/GRAPH_REPORT.md` terbentuk.

### Tahap 1: Implementasi Kontrak `PROTOCOL.md` (Rust & Kotlin)
- **Tugas:**
  - Di `desktop/crates/camcraft-protocol`: Implementasikan struct `PacketHeader` (20 byte), enum `PacketType`, bitmask `flags`, decoder streaming berbasis `tokio_util::codec::Decoder` / `bytes::BytesMut`, serta struct serde untuk `ControlHello`, `ControlCommand`, dan `ControlAck`.
  - Di `android/app/src/main/java/org/artcraft/camcraft/protocol`: Implementasikan class `CcftPacketHeader`, `CcftPacketIO` (`DataInputStream`/`DataOutputStream` atau `ByteBuffer` Big-Endian), dan model `@Serializable` untuk pesan JSON kontrol.
- **Kriteria Lulus:**
  - Test vektor heksadesimal yang sama persis diuji di Rust (`cargo test -p camcraft-protocol`) dan di Kotlin (`./gradlew testDebugUnitTest`). Byte array hasil serialisasi Kotlin dapat di-parse oleh Rust secara akurat.

### Tahap 2: Koneksi USB (`adb forward`) & Synthetic Frame Ping-Pong
- **Tugas:**
  - Di Android: Buat `StreamServer` di dalam Foreground Service yang mendengarkan `ServerSocket(9876)`, mengirimkan `CONTROL_HELLO`, meladeni `HEARTBEAT_PING`/`PONG`, dan mengirimkan paket `VIDEO_FRAME` palsu (simulasi 60 paket per detik).
  - Di Rust (`camcraft-core` & `camcraft-cli`): Buat fungsi otomatis `setup_adb_forward(9876)`, hubungkan `TcpStream` ke `127.0.0.1:9876` dengan `set_nodelay(true)`, baca `CONTROL_HELLO`, dan tampilkan statistik throughput serta RTT latensi di terminal setiap detik.
- **Kriteria Lulus:**
  - Hubungkan HP Android ke Fedora via USB, jalankan `just usb-tunnel` dan `cargo run -p camcraft-cli -- connect --usb`, terminal menampilkan nama HP, daftar kamera, kecepatan 60 fps, dan RTT < 5 ms.

### Tahap 3: Pipeline CameraX + MediaCodec H.264 di Android
- **Tugas:**
  - Bangun `CameraXManager` dan `H264SurfaceEncoder` di Android.
  - Hubungkan `Preview` ke `PreviewView` di layar HP, dan hubungkan output kedua CameraX ke `MediaCodec.createInputSurface()`.
  - Konfigurasikan `MediaCodec` (`video/avc`, 1920x1080, 60fps, 12 Mbps, `COLOR_FormatSurface`, `KEY_I_FRAME_INTERVAL = 1`).
  - Saat `INFO_OUTPUT_FORMAT_CHANGED`, gabungkan buffer `csd-0` (SPS) dan `csd-1` (PPS) dan kirimkan sebagai paket `VIDEO_CONFIG (0x01)`.
  - Kirim setiap output buffer sebagai `VIDEO_FRAME (0x02)` dengan flag `FLAG_KEYFRAME` jika `BUFFER_FLAG_KEY_FRAME` aktif.
- **Kriteria Lulus:**
  - `camcraft-cli` di Fedora menerima paket `VIDEO_CONFIG` diikuti aliran `VIDEO_FRAME` nyata dari sensor kamera HP pada 60fps dan dapat menyimpan sampel `.h264` yang valid (bisa diputar dengan `ffplay`).

### Tahap 4: Decoding H.264 & Injeksi Virtual Camera (`v4l2loopback`) di Fedora
- **Tugas:**
  - Di `camcraft-core`: Tambahkan modul decoder H.264 menggunakan `ffmpeg-next` (mendukung akselerasi hardware VA-API jika tersedia, dengan fallback software `h264` multi-thread) dan konversi piksel ke `YUV420P` / `YUYV422`.
  - Buat modul `v4l2_sink` yang membuka `/dev/video10` (dibuat oleh `just setup-vcam`), memanggil `VIDIOC_S_FMT` sesuai resolusi stream (1920x1080 atau 3840x2160), dan menulis frame mentah hasil decode ke file descriptor `/dev/video10`.
- **Kriteria Lulus:**
  - Buka OBS Studio, Firefox/Chromium (Google Meet), atau Vesktop di Fedora, pilih **"CamCraft Virtual Camera"**, dan video dari HP tampil secara *real-time* pada resolusi 1080p/4K yang tajam.

### Tahap 5: Aplikasi GUI Desktop (`camcraft-gui`) & Two-Way Camera Control
- **Tugas:**
  - Bangun aplikasi GUI di `desktop/crates/camcraft-gui` menggunakan `eframe`/`egui` dengan tema gelap ArtCraft.
  - Tampilkan *live preview* kamera di sebelah kiri dan panel kontrol di sebelah kanan.
  - Hubungkan tombol UI (Pilih Lensa Depan/Belakang/Ultrawide, Resolusi 720p/1080p/4K, FPS 30/60, Slider Zoom, Slider Exposure EV, Toggle Torch, Flip/Rotate) agar mengirimkan paket `CONTROL_CMD (0x11)` ke Android.
  - Di Android: Terapkan handler `CONTROL_CMD` yang memanggil API `CameraControl` (`setZoomRatio`, `setExposureCompensationIndex`, `enableTorch`) atau merestart sesi `CameraX + MediaCodec` ketika resolusi/lensa diubah.
- **Kriteria Lulus:**
  - Menggeser slider Zoom/Exposure atau menekan tombol ganti lensa di GUI Fedora langsung mengubah kamera HP dalam waktu < 100 ms dan memperbarui state di kedua layar.

### Tahap 6: Koneksi Nirkabel Wi-Fi & Penemuan Otomatis (mDNS)
- **Tugas:**
  - Di Android: Tambahkan `NsdManager` untuk mengiklankan `_camcraft._tcp.` di jaringan Wi-Fi lokal serta tampilkan alamat IP lokal HP di layar utama.
  - Di Rust (`camcraft-core` & `camcraft-gui`): Tambahkan `mdns-sd` untuk memindai perangkat CamCraft di LAN secara otomatis dan menampilkannya di daftar perangkat pada GUI Fedora, disertai opsi input IP manual.
- **Kriteria Lulus:**
  - Cabut kabel USB, pilih perangkat Android yang muncul otomatis di tab Wi-Fi pada GUI Fedora, dan streaming 1080p@60fps berjalan lancar melalui Wi-Fi.

### Tahap 7: Streaming Audio Mikrofon (Opus) & PipeWire Virtual Mic
- **Tugas:**
  - Di Android: Buat `AudioStreamEngine` dengan `AudioRecord` (48kHz, Mono, 16-bit PCM) dan enkoder Opus (atau raw PCM frame 20ms) yang mengirimkan `AUDIO_CONFIG (0x03)` dan `AUDIO_FRAME (0x04)`.
  - Di Fedora (`camcraft-core`): Dekode paket `AUDIO_FRAME` dan tulis aliran PCM ke *PipeWire Virtual Source* (`CamCraft Virtual Mic` yang disiapkan oleh skrip setup PipeWire).
- **Kriteria Lulus:**
  - Di pengaturan Sound Fedora (GNOME Settings / `pavucontrol` / Vesktop), pilih input mikrofon **"CamCraft Virtual Mic"**, dan suara dari mikrofon HP terdengar jernih tanpa *delay* yang mengganggu.

### Tahap 8: Polish, OLED Battery Saver, Auto-Reconnect & Rilis v0.1
- **Tugas:**
  - Tambahkan mode **OLED Dim Screen** di aplikasi Android (layar hitam pekat dengan indikator kecil yang bergeser agar bebas *burn-in*, ketuk 2x untuk bangun).
  - Tambahkan *Standby Frame Generator* di Rust agar ketika koneksi HP terputus atau sedang berganti resolusi, `/dev/video10` tetap menerima frame statis bertuliskan *"CamCraft — Reconnecting..."* sehingga OBS/Discord tidak pernah *freeze*.
  - Audit memori dan latensi menggunakan `cargo clippy` dan Android Profiler.
