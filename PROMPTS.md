# Playbook Prompt Vibecoding CamCraft (`PROMPTS.md`)

File ini berisi **urutan prompt lengkap dari awal hingga akhir** yang siap Anda *copy-paste* ke panel AI Agent (**Hydrogen / Antigravity**) di **Zed IDE**.

### Tips Sebelum Memulai di Zed:
1. Pastikan seluruh file dari paket `.zip` ini sudah berada di root folder proyek Anda (`~/Projects/camcraft`).
2. Di Zed Agent Panel, pastikan Anda menjalankan satu tahap hingga benar-benar terverifikasi (*build & test* hijau) sebelum melanjutkan ke prompt tahap berikutnya.
3. Setiap kali satu tahap selesai, jalankan `/graphify .` (atau minta agen menjalankan `just graph`) agar *knowledge graph* di `graphify-out/` selalu mutakhir.

---

## TAHAP 0: Inisialisasi Monorepo & Pemetaan Graphify Awal

### Prompt 0.1 — Stress-Test Arsitektur & Setup Kerangka Monorepo
> **Copy-paste prompt berikut ke Zed Agent:**

```text
Baca file AGENTS.md, docs/PRD.md, docs/DESIGN.md, docs/PROTOCOL.md, dan docs/PLAN.md di repositori ini.
Gunakan skill arsitektur dan Rust/Android yang kamu miliki.

Tugas kita sekarang adalah menyelesaikan **Tahap 0 (Monorepo Scaffolding & Graphify Baseline)** sesuai `docs/PLAN.md`:
1. Buat Cargo Workspace di `desktop/Cargo.toml` (Rust 2024 edition) beserta 4 kerangka crate:
   - `desktop/crates/camcraft-protocol` (library murni: `bytes`, `serde`, `serde_json`, `thiserror`)
   - `desktop/crates/camcraft-core` (library core: `tokio`, `crossbeam-channel`, `tracing`, `thiserror`, `anyhow`)
   - `desktop/crates/camcraft-cli` (binary CLI berbasis `clap` dan `tokio`)
   - `desktop/crates/camcraft-gui` (binary GUI berbasis `eframe`/`egui` dengan kerangka window tema gelap ArtCraft)
2. Buat kerangka proyek Android Native Kotlin di `android/` lengkap dengan:
   - `android/settings.gradle.kts`, `android/build.gradle.kts`, `android/gradle.properties`
   - `android/gradle/libs.versions.toml` (menggunakan Kotlin 2.x, Jetpack Compose Material 3, CameraX `camera-core`, `camera-camera2`, `camera-lifecycle`, `camera-view`, Coroutines, dan `kotlinx-serialization-json`)
   - `android/app/build.gradle.kts` (minSdk 29, targetSdk 35, package `org.artcraft.camcraft`)
   - `android/app/src/main/AndroidManifest.xml` (dengan permission `CAMERA`, `RECORD_AUDIO`, `INTERNET`, `ACCESS_NETWORK_STATE`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`)
   - Package kosong/stub terstruktur di `org/artcraft/camcraft/{protocol,camera,encoder,network,ui}` beserta `MainActivity.kt`.
3. Buat file `.gitignore` di root monorepo yang mengabaikan `desktop/target/`, `android/.gradle/`, `android/app/build/`, `local.properties`, dan file artefak sementara.
4. Jalankan `cargo check --workspace` di dalam `desktop/` untuk memastikan workspace Rust terkompilasi tanpa error, lalu perbarui indeks Graphify.
```

---

## TAHAP 1: Implementasi Wire Protocol `CCFT v1` (Rust & Kotlin)

### Prompt 1.1 — Implementasi Crate `camcraft-protocol` di Rust
> **Copy-paste prompt berikut ke Zed Agent:**

```text
Sekarang kita masuk ke **Tahap 1 (Bagian A — Rust Protocol Crate)** berdasarkan `docs/PROTOCOL.md` dan `docs/PLAN.md`.

Baca `docs/PROTOCOL.md` dengan teliti dan implementasikan secara lengkap crate `desktop/crates/camcraft-protocol`:
1. Buat konstanta `MAGIC: [u8; 4] = *b"CCFT"`, `PROTOCOL_VERSION: u8 = 1`, `HEADER_LEN: usize = 20`, dan `MAX_PAYLOAD_LEN: u32 = 16 * 1024 * 1024`.
2. Buat enum `PacketType` (`u8`) untuk `VideoConfig (0x01)`, `VideoFrame (0x02)`, `AudioConfig (0x03)`, `AudioFrame (0x04)`, `ControlHello (0x10)`, `ControlCmd (0x11)`, `ControlAck (0x12)`, `HeartbeatPing (0xFE)`, dan `HeartbeatPong (0xFF)`.
3. Buat konstanta dan helper bitmask `FrameFlags` (`u16`) untuk `FLAG_KEYFRAME`, `FLAG_CODEC_HEVC`, rotasi (90/180/270), dan `FLAG_MIRROR_H`.
4. Buat struct `PacketHeader` dan `Packet` (menggunakan `bytes::Bytes`) beserta fungsi `encode(&self, dst: &mut BytesMut)` dan `decode(src: &mut BytesMut) -> Result<Option<Packet>, ProtocolError>` untuk streaming TCP framing yang menangani *partial reads* dan *multiple packets in buffer*.
5. Buat seluruh model data JSON `ControlHello`, `CameraInfo`, `ResolutionOption`, `ActiveCameraState`, `ControlCmd` (`#[serde(tag = "cmd")]`), dan `ControlAck` persis sesuai skema di `docs/PROTOCOL.md` Bagian 5.
6. Tulis unit test lengkap di `desktop/crates/camcraft-protocol/src/lib.rs` (termasuk test vektor byte heksadesimal untuk header 20-byte, test partial TCP stream chunking, dan test serialisasi JSON).
7. Jalankan `cargo test -p camcraft-protocol` dan `cargo clippy -p camcraft-protocol -- -D warnings` sampai 100% lulus.
```

### Prompt 1.2 — Implementasi Package `protocol` di Android Kotlin
> **Copy-paste prompt berikut ke Zed Agent:**

```text
Sekarang lanjutkan ke **Tahap 1 (Bagian B — Android Kotlin Protocol)** agar 100% kompatibel dengan `camcraft-protocol` di Rust dan spesifikasi `docs/PROTOCOL.md`.

Implementasikan di `android/app/src/main/java/org/artcraft/camcraft/protocol/`:
1. `CcftHeader.kt` & `CcftPacket.kt`:
   - Gunakan `java.nio.ByteBuffer` dengan `ByteOrder.BIG_ENDIAN` untuk menulis dan membaca header 20-byte (`magic = "CCFT"`, `version = 1`, `packetType`, `flags`, `ptsUs`, `payloadLen`).
   - Sediakan fungsi `writePacket(output: OutputStream, type: PacketType, flags: Int, ptsUs: Long, payload: ByteArray)` yang bebas alokasi berlebih (gunakan reusable header buffer 20-byte per thread/channel) dan melakukan `flush()` jika diperlukan.
   - Sediakan fungsi `readPacket(input: DataInputStream): CcftPacket` yang memvalidasi magic bytes, versi, dan batas `MAX_PAYLOAD_LEN` (16 MB).
2. `ControlModels.kt`:
   - Buat data class `@Serializable` (`kotlinx.serialization.json`) untuk `ControlHello`, `CameraInfo`, `ResolutionOption`, `ActiveCameraState`, sealed interface `ControlCmd` (`@OptIn(ExperimentalSerializationApi::class) @JsonClassDiscriminator("cmd")`), dan `ControlAck` yang cocok 1:1 dengan format JSON di `docs/PROTOCOL.md`.
3. Buat unit test JUnit di `android/app/src/test/java/org/artcraft/camcraft/protocol/CcftProtocolTest.kt` yang menguji vektor byte heksadesimal yang sama persis dengan unit test di Rust (`camcraft-protocol`), sehingga kita yakin Rust dan Kotlin dapat saling membaca paket tanpa selisih 1 byte pun.
4. Perbarui indeks Graphify (`/graphify .`) setelah selesai.
```

---

## TAHAP 2: Koneksi USB (`adb forward`) & Synthetic Frame Stream

### Prompt 2.1 — Implementasi TCP Server, ADB Tunnel, & Dummy Streamer
> **Copy-paste prompt berikut ke Zed Agent:**

```text
Kita masuk ke **Tahap 2 (Koneksi USB via ADB Port Forwarding & Synthetic Stream)** sesuai `docs/PLAN.md`.

Tugasmu:
1. **Di Sisi Rust (`desktop/crates/camcraft-core` & `camcraft-cli`):**
   - Buat modul `adb.rs` di `camcraft-core` yang dapat memanggil perintah `adb devices` (memeriksa apakah ada perangkat Android yang terhubung) dan menjalankan `adb forward tcp:9876 tcp:9876`.
   - Buat modul `client.rs` di `camcraft-core` menggunakan `tokio::net::TcpStream` (dengan `set_nodelay(true)`) yang terhubung ke `127.0.0.1:9876` (atau IP:port kustom).
   - Saat terhubung, baca paket `CONTROL_HELLO (0x10)` dari Android, cetak daftar kamera dan resolusi perangkat, jalankan task pengirim `HEARTBEAT_PING (0xFE)` setiap 1 detik, hitung RTT dari `HEARTBEAT_PONG (0xFF)`, dan hitung FPS serta bitrate (Mbps) dari paket `VIDEO_FRAME (0x02)` yang masuk.
   - Hubungkan semua ini ke sub-command `camcraft-cli connect --usb` (dan `--host <ip:port>`).

2. **Di Sisi Android (`android/app/src/main/java/org/artcraft/camcraft/network/`):**
   - Buat `CamCraftStreamService.kt` (Foreground Service dengan notifikasi status aktif) yang membuka `ServerSocket(9876)` dengan `tcpNoDelay = true`.
   - Saat klien PC terhubung:
     a. Kirimkan paket `CONTROL_HELLO (0x10)` berisi informasi perangkat nyata atau simulasi.
     b. Jalankan loop pembaca paket dari PC: jika menerima `HEARTBEAT_PING (0xFE)`, langsung balas dengan `HEARTBEAT_PONG (0xFF)` menggunakan `ptsUs` yang sama; jika menerima `CONTROL_CMD (0x11)`, parse JSON-nya, perbarui `ActiveCameraState`, dan balas dengan `CONTROL_ACK (0x12)`.
     c. Buat mode **Synthetic Test Streamer** (sebelum CameraX diaktifkan di Tahap 3) yang mengirimkan 60 paket `VIDEO_FRAME` per detik ke PC untuk menguji stabilitas pipa jaringan dan akurasi penghitung FPS/Bitrate/RTT di Rust.
   - Perbarui `MainActivity.kt` dengan UI Jetpack Compose sederhana untuk menyalakan/mematikan `CamCraftStreamService` dan menampilkan status koneksi klien PC.

3. Pastikan `just rust-check` lulus tanpa warning.
```

---

## TAHAP 3: Pipeline CameraX + Hardware H.264 (`MediaCodec`) di Android

### Prompt 3.1 — Implementasi CameraX & Hardware H.264 Surface Encoder
> **Copy-paste prompt berikut ke Zed Agent:**

```text
Sekarang kita masuk ke **Tahap 3 (Pipeline CameraX + MediaCodec H.264 di Android)** sesuai `docs/DESIGN.md`, `docs/PROTOCOL.md`, dan `docs/PLAN.md`.
Gunakan skill `camerax` dan `android-profiler` sebagai acuan praktik terbaik.

Tugasmu di proyek `android/`:
1. **Enumerasi Kamera Nyata (`camera/CameraEnumerator.kt`):**
   - Gunakan `CameraManager` / `Camera2CameraInfo` untuk memindai seluruh kamera di HP (Rear Main, Rear Ultrawide, Front), daftar resolusi `SurfaceTexture`/`MediaCodec` yang didukung (720p, 1080p, 4K), rentang zoom maksimum, rentang Exposure Compensation (EV), dan ketersediaan Flash/Torch.
   - Gunakan data nyata ini untuk mengisi payload `CONTROL_HELLO (0x10)`.

2. **Hardware H.264 Encoder (`encoder/H264SurfaceEncoder.kt`):**
   - Konfigurasikan `MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)` dengan resolusi, FPS (30/60), dan bitrate (mis. 12 Mbps untuk 1080p, 30 Mbps untuk 4K) dari `ActiveCameraState`.
   - Set `COLOR_FormatSurface`, `KEY_I_FRAME_INTERVAL = 1`, dan pengaturan low-latency (`KEY_LATENCY = 0`, `KEY_PRIORITY = 0` di dalam blok `try-catch` agar aman di semua chipset Snapdragon/MediaTek/Exynos/Tensor).
   - Panggil `createInputSurface()` untuk menyediakan `Surface` bagi CameraX.
   - Pada coroutine penguras output (`drainEncoderLoop`):
     - Saat menerima `MediaCodec.INFO_OUTPUT_FORMAT_CHANGED`, ekstrak `csd-0` (SPS) dan `csd-1` (PPS) dari `outputFormat`, gabungkan keduanya menjadi satu byte array Annex-B (`00 00 00 01 ...`), simpan di memori, dan kirimkan sebagai paket `PacketType.VIDEO_CONFIG (0x01)`.
     - Untuk setiap buffer output reguler, jika `bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0`, kirim sebagai `VIDEO_CONFIG (0x01)`.
     - Untuk frame video biasa, kirimkan sebagai `VIDEO_FRAME (0x02)` dengan menyetel `FLAG_KEYFRAME (0x0001)` jika `BUFFER_FLAG_KEY_FRAME` aktif.
   - Sediakan fungsi `requestKeyFrame()` menggunakan `MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME`.

3. **Integrasi CameraX (`camera/CameraStreamManager.kt`):**
   - Bind `Preview` (untuk `PreviewView` di layar HP) sekaligus *surface kedua* untuk `H264SurfaceEncoder` menggunakan `SurfaceRequest` / `Preview` atau `VideoCapture` raw surface sehingga kamera merender langsung ke encoder di GPU.
   - Sediakan metode instan untuk `setZoomRatio(Float)`, `setExposureEv(Float)`, `setTorch(Boolean)`, `setAutoFocus(Boolean)`, dan metode `rebindCamera(cameraId, width, height, fps, bitrate)` saat menerima perintah `ConfigureStream` dari PC.

4. **Perekaman Sampel H.264 di `camcraft-cli` (Rust):**
   - Tambahkan flag `--dump-h264 <path.h264>` pada `camcraft-cli connect` di Rust agar paket `VIDEO_CONFIG` dan `VIDEO_FRAME` yang diterima dari HP dapat langsung diuji dengan `ffplay <path.h264>` jika pengguna ingin memvalidasi stream mentahnya.
```

---

## TAHAP 4: Decoding H.264 & Injeksi Virtual Camera (`v4l2loopback`) di Fedora

### Prompt 4.1 — Implementasi H.264 Decoder & Linux V4L2 Loopback Output Sink
> **Copy-paste prompt berikut ke Zed Agent:**

```text
Kita masuk ke **Tahap 4 (Decoding H.264 & Injeksi Virtual Camera `v4l2loopback` di Fedora Linux)** sesuai `docs/DESIGN.md` dan `docs/PLAN.md`.
Gunakan skill `rust-pro` dan `rust-patterns` untuk memastikan zero-panic dan performa real-time.

Tugasmu di `desktop/crates/camcraft-core` dan `camcraft-cli`:
1. **Modul H.264 Decoder (`desktop/crates/camcraft-core/src/decoder.rs`):**
   - Gunakan `ffmpeg-next` (atau `openh264` / `ffmpeg` CLI shared lib sesuai dependensi Fedora `ffmpeg-free-devel`) untuk mendekode aliran paket `VIDEO_CONFIG (0x01)` (SPS/PPS) dan `VIDEO_FRAME (0x02)` (Annex-B NAL units) dengan flag low-delay (`AV_CODEC_FLAG_LOW_DELAY`).
   - Sediakan konversi warna menggunakan `software::scaling::Context` (`libswscale`) dari format piksel asli decoder ke:
     a. `YUV420P` (atau `YUYV422`) dengan buffer kontigu yang siap ditulis ke device V4L2.
     b. `RGBA` (opsional, hanya dieksekusi jika GUI preview sedang aktif).
   - Jalankan proses decode di dalam *dedicated worker thread* (`std::thread::Builder::new().name("camcraft-vdec".into())`) yang membaca dari bounded channel (`crossbeam_channel::bounded(3)`). Jika channel penuh dan datang frame non-keyframe baru, drop frame lama agar latensi tidak pernah menumpuk!

2. **Modul Linux Virtual Camera (`desktop/crates/camcraft-core/src/vcam.rs`):**
   - Buat trait `VirtualCameraSink` (agar nantinya mudah di-porting ke Windows/macOS) dan implementasi `V4l2LoopbackSink` untuk Linux.
   - Buka device target (default `/dev/video10` atau mencari otomatis node `/dev/video*` yang memiliki card label `"CamCraft"`).
   - Konfigurasikan format output V4L2 (`V4L2_BUF_TYPE_VIDEO_OUTPUT`, pixel format `YUV420` / `YU12` atau `YUYV`, lebar dan tinggi sesuai stream).
   - Tulis frame byte secara langsung (`write_all`) ke file descriptor `/dev/videoX`.
   - Buat generator **Standby Pattern Frame** (frame warna gelap dengan pola indikator sederhana dalam format YUV420P) sehingga ketika HP belum terhubung atau sedang berganti resolusi, `/dev/video10` tetap terinisialisasi dan tidak membuat OBS/Discord/Vesktop error.

3. **Integrasi ke `camcraft-cli`:**
   - Tambahkan argumen `--vcam /dev/video10` pada `camcraft-cli connect`.
   - Saat dijalankan bersama HP Android, frame H.264 dari HP didekode dan langsung masuk ke `/dev/video10`.
   - Pastikan `cargo check --workspace` dan `cargo test --workspace` lulus tanpa warning.
```

---

## TAHAP 5: Native Desktop GUI (`camcraft-gui`) & Two-Way Camera Control

### Prompt 5.1 — Bangun Aplikasi Desktop GUI ArtCraft & Kontrol Kamera Dua Arah
> **Copy-paste prompt berikut ke Zed Agent:**

```text
Sekarang kita masuk ke **Tahap 5 (Native Desktop GUI & Two-Way Camera Controls)** sesuai `docs/DESIGN.md` dan `docs/PLAN.md`.

Tugasmu:
1. **Bangun GUI Desktop (`desktop/crates/camcraft-gui`):**
   - Gunakan `eframe` / `egui` dengan tema visual **ArtCraft Studio Dark** (background `#111318`, panel `#1C2026`, aksen biru `#3B82F6`, badge live emerald `#10B981`, sudut membulat `8px`).
   - **Panel Kiri (Live Preview & Telemetry Overlay):**
     - Render tekstur RGBA dari frame kamera yang sudah didekode dengan menjaga rasio aspek (16:9).
     - Tampilkan bar status di atas preview: Status Koneksi (`Connected via USB` / `Disconnected`), nama perangkat HP, Resolusi & FPS aktual, Bitrate (`Mbps`), Waktu Decode (`ms`), RTT Latency (`ms`), dan status Virtual Camera (`/dev/video10 Active`).
   - **Panel Kanan (Inspector & Camera Controls):**
     - **Connection Section:** Tombol `Connect via USB (ADB)` (otomatis menjalankan `adb forward` + connect), input teks `Wi-Fi IP:Port` + tombol `Connect Wi-Fi`, dan tombol `Disconnect`.
     - **Camera & Stream Preset Section:** Dropdown daftar kamera dari `CONTROL_HELLO` (mis. Rear Wide, Rear Ultrawide, Front), Dropdown Resolusi (`1280x720`, `1920x1080`, `3840x2160`), Pilihan FPS (`30 FPS`, `60 FPS`), dan Slider Bitrate (`4 Mbps` s/d `40 Mbps`) + tombol `Apply Stream Settings` (mengirim `ControlCmd::ConfigureStream`).
     - **Live Image Adjustments Section:** Slider `Zoom` (`1.0x` s/d `max_zoom`), Slider `Exposure (EV)` (`min_ev` s/d `max_ev`), Checkbox `Autofocus`, Checkbox `Torch / Flashlight`, Checkbox `Mirror Horizontal`, dan pemilih `Rotation` (`0°`, `90°`, `180°`, `270°`). Setiap perubahan slider/toggle langsung mengirim `ControlCmd::SetCameraParams` dengan *debounce* 50ms.

2. **Lengkapi UI & Control Handler di Android (`android/app/src/main/java/org/artcraft/camcraft/ui/`):**
   - Buat tampilan Jetpack Compose tema gelap yang menampilkan `PreviewView` kamera, badge status koneksi PC, tombol cepat di layar HP (Switch Camera, Toggle Torch, Toggle Mute), dan sinkronkan seluruh state dengan `ActiveCameraState` sehingga perubahan dari PC maupun dari HP langsung tercermin di kedua layar.

3. Pastikan seluruh workspace Rust (`cargo check --workspace`) dan kode Android bersih dari error, lalu jalankan `/graphify .` untuk memperbarui knowledge graph.
```

---

## TAHAP 6: Koneksi Nirkabel Wi-Fi & Penemuan Otomatis (mDNS)

### Prompt 6.1 — Implementasi mDNS Service Discovery & Optimasi Streaming Wi-Fi
> **Copy-paste prompt berikut ke Zed Agent:**

```text
Kita masuk ke **Tahap 6 (Koneksi Nirkabel Wi-Fi & mDNS Discovery)** sesuai `docs/DESIGN.md` dan `docs/PLAN.md`.

Tugasmu:
1. **Penemuan Otomatis mDNS di Android (`network/MdnsAdvertiser.kt`):**
   - Gunakan `NsdManager` Android untuk mendaftarkan layanan `_camcraft._tcp.` pada port `9876` dengan nama perangkat HP saat `CamCraftStreamService` berjalan.
   - Deteksi dan tampilkan alamat IP Wi-Fi lokal HP (mis. `192.168.1.42:9876`) secara jelas di bagian atas UI Android agar pengguna juga bisa mengetiknya secara manual jika jaringan memblokir multicast.

2. **Pemindai mDNS di Rust (`desktop/crates/camcraft-core/src/discovery.rs` & `camcraft-gui`):**
   - Gunakan crate `mdns-sd` (atau pemindai UDP/mDNS ringan) di background thread untuk mendeteksi perangkat `_camcraft._tcp.local.` di jaringan lokal.
   - Tampilkan daftar perangkat yang ditemukan secara otomatis di bagian **Wi-Fi Devices** pada `camcraft-gui` lengkap dengan tombol sekali klik `Connect`.

3. **Ketahanan Streaming Wi-Fi:**
   - Jika decoder di Rust mendeteksi frame yang hilang atau rusak saat pergantian jaringan, otomatis kirimkan `ControlCmd::RequestKeyframe` (`0x11`) ke Android agar gambar pulih seketika tanpa artifak abu-abu.
```

---

## TAHAP 7: Audio Streaming (Opus) & PipeWire Virtual Microphone

### Prompt 7.1 — Implementasi Capture Mikrofon Android & PipeWire Virtual Mic di Fedora
> **Copy-paste prompt berikut ke Zed Agent:**

```text
Kita masuk ke **Tahap 7 (Audio Streaming & PipeWire Virtual Microphone)** sesuai `docs/PROTOCOL.md`, `docs/DESIGN.md`, dan `docs/PLAN.md`.

Tugasmu:
1. **Audio Capture di Android (`encoder/AudioStreamEngine.kt`):**
   - Gunakan `AudioRecord` dengan `MediaRecorder.AudioSource.MIC` (atau `CAMCORDER`), sample rate `48000` Hz, `CHANNEL_IN_MONO`, `ENCODING_PCM_16BIT`, dengan ukuran frame 20 ms (960 sampel = 1920 byte per frame).
   - Saat audio diaktifkan, kirimkan paket `AUDIO_CONFIG (0x03)` dengan JSON `{"codec": "pcm_s16le", "sample_rate": 48000, "channels": 1}` (atau `"opus"` jika menggunakan encoder Opus), diikuti paket `AUDIO_FRAME (0x04)` setiap 20 ms.
   - Hormati status `audio_enabled` dan `audio_muted` dari `ControlCmd::SetAudioState` (jika `muted == true`, kirimkan buffer nol atau hentikan pengiriman paket audio).

2. **PipeWire Virtual Microphone Sink di Fedora (`desktop/crates/camcraft-core/src/vmic.rs`):**
   - Buat modul `VirtualMicSink` di Rust yang menyalurkan sampel PCM 48kHz S16LE Mono ke sink/source virtual PipeWire (`camcraft_virtual_mic` yang dibuat melalui `pactl` / `pw-cat` / `libpulse-simple-binding`).
   - Sediakan helper otomatis di Rust untuk memeriksa dan membuat virtual source PipeWire bernama `"CamCraft Virtual Mic"` jika belum ada di sesi pengguna Fedora.
   - Tambahkan kontrol **Enable Audio Forwarding**, **Mute Mic**, dan **Volume Level Meter (dB bar)** di panel kanan `camcraft-gui`.
```

---

## TAHAP 8: Mode Hemat Baterai OLED, Auto-Reconnect, & Finalisasi Rilis v0.1

### Prompt 8.1 — Implementasi OLED Screen Dimmer, Auto-Reconnect, & Audit Kualitas Akhir
> **Copy-paste prompt berikut ke Zed Agent:**

```text
Kita masuk ke tahap terakhir: **Tahap 8 (OLED Battery Saver, Auto-Reconnect, & Polish v0.1)** sesuai `docs/PLAN.md`.

Tugasmu:
1. **Mode OLED Battery Saver di Android (`ui/OledSaverOverlay.kt`):**
   - Tambahkan tombol **Dim Screen (Battery Saver)** di UI Android.
   - Saat aktif, tampilkan layar hitam murni (`#000000`) di seluruh layar (dan turunkan `screenBrightness` window ke nilai minimum `0.01f`) dengan teks status tipis yang berpindah posisi secara perlahan setiap 30 detik (mencegah OLED burn-in).
   - Ketuk dua kali (*double tap*) di mana saja pada layar untuk keluar dari mode Dim Screen.

2. **Auto-Reconnect & Graceful Standby di Rust (`camcraft-core` & `camcraft-gui`):**
   - Jika koneksi USB atau Wi-Fi terputus tiba-tiba, jangan hentikan penulisan ke `/dev/video10`. Alihkan `V4l2LoopbackSink` ke mode *Standby Frame* secara mulus dan jalankan *auto-reconnect loop* setiap 2 detik hingga HP kembali terhubung.

3. **Audit Akhir & Dokumentasi:**
   - Jalankan `cargo clippy --workspace --all-targets -- -D warnings`, `cargo test --workspace`, dan `cargo fmt --check`.
   - Periksa seluruh modul Android agar tidak ada kebocoran resource (`MediaCodec`, `AudioRecord`, `CameraProvider`, `Socket`).
   - Jalankan `/graphify .` untuk menghasilkan laporan graf arsitektur akhir di `graphify-out/GRAPH_REPORT.md`.
```
