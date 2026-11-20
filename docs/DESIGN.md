# Dokumen Desain Teknis & Arsitektur Sistem (DESIGN.md)

Dokumen ini menjabarkan arsitektur internal **CamCraft** (CamCraft Studio v2), pemilihan pustaka (*crates* & *libraries*), manajemen *thread*, optimasi sumber daya rendah (*"laptop kentang"*), serta panduan desain antarmuka pengguna (**ArtCraft Studio v2 Design System**) untuk aplikasi Android dan Desktop Fedora.

---

## 1. Arsitektur End-to-End Pipeline

```text
+-----------------------------------------------------------------------------+
| ANDROID SMARTPHONE (Native Kotlin, minSdk 29 / targetSdk 35+)               |
|                                                                             |
|  [CameraX Preview / ResolutionSelector] ---> (GPU Surface)                  |
|         |               ^                                |                  |
|         | (SurfaceView) | (Camera2Interop:               v                  |
|         v               |  AE FPS, Tap-to-Focus,   [MediaCodec H.264 HW]    |
|  [PreviewView UI]       |  Zoom, EV, Torch)        (Scratch Buffer Annex-B) |
|  (Detached on OLED Dim  |                                |                  |
|   or Activity ON_STOP)  |                                v                  |
|                   [Control Handler] <--------+     [CCFT Packet Framer]     |
|                                              |     (+ FLAG_MIRROR_H /       |
|  [AudioRecord 48kHz] ---> [AudioStreamEngine]|      FLAG_ROTATE_* metadata) |
|                           (Zero-alloc PCM) --+           |                  |
+----------------------------------------------|-----------|------------------+
                                               | (TCP Port 9876, TCP_NODELAY:
                                               |  USB ADB Forward or Wi-Fi)
                                               v
+-----------------------------------------------------------------------------+
| FEDORA LINUX DESKTOP (Native Rust, 2024 Edition)                            |
|                                              |                              |
|  [CamCraft Studio GUI] <---------------------+     [Tokio TCP Reader]       |
|  (eframe + glow OpenGL +                     |     (current_thread di GUI,  |
|   winit wayland-csd-adwaita)                 |      256 KiB buffer, biased) |
|         ^                                    |               |              |
|         | (Recycled RGBA Buffer,             |               v              |
|         |  Event-Driven Repaint,             |     [CCFT Frame Demuxer]     |
|         |  Auto-Pause on Minimize)           |       /                \     |
|         |                                    v      v (Video)          v    |
|  [Video Decode Worker] <---------------------+ [Bounded Queue]  [Audio Worker]
|  (ffmpeg-next: slice threads=2,              | (Drop P-frame)   (48kHz PCM) |
|   AV_CODEC_FLAG2_FAST,                       |                        |     |
|   In-Pipeline YUV420P Mirror/Rotate)         |                        v     |
|         |                                    |           [PipeWire VMic]    |
|         v (YUV420P, 1:1 parity w/ Preview)   |           (pacat ->          |
|  [/dev/video10 (v4l2loopback, FD_CLOEXEC)]   |            camcraft_virtual_mic)
+-----------------------------------------------------------------------------+
```

---

## 2. Desain Sisi Android (`android/`)

### 2.1 Komponen & Tanggung Jawab

1. **`CameraController` & `CameraStreamManager` (`camera/`):**
   - Mengelola `ProcessCameraProvider` dari [Jetpack CameraX](https://developer.android.com/training/camerax) dengan `ServiceLifecycleOwner` khusus agar kamera tetap aktif di dalam *Foreground Service* meskipun *Activity* sedang tidak di latar depan.
   - Mengekspos kapabilitas perangkat melalui `CameraEnumerator` (daftar kamera belakang/depan/ultrawide, daftar resolusi per lensa, rentang zoom, dan rentang *exposure compensation*).
   - **Pemilihan Resolusi Modern (`ResolutionSelector`):** Menggunakan `ResolutionSelector.Builder()` dengan `ResolutionStrategy(Size(width, height), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)` (menggantikan API lama `setTargetResolution` yang *deprecated*) untuk mengonfigurasi *use-case* `Preview` encoder secara akurat.
   - **Target Frame Rate & `Camera2Interop`:** Menyetel `Preview.Builder.setTargetFrameRate(Range(fps, fps))` serta menyisipkan `CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE` melalui `Camera2Interop.Extender` hanya apabila rentang `Range(fps, fps)` terverifikasi tersedia di `CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` (dengan fallback aman bagi sensor/lensa yang hanya mendukung 30 fps).
   - **Viewfinder Performa Tinggi & Detach Hemat Daya:** Layar UI memakai `PreviewView` dalam mode **`PreviewView.ImplementationMode.PERFORMANCE`** (`SurfaceView` + `ScaleType.FILL_CENTER`) agar komposisi gambar langsung ditangani *hardware overlay* tanpa *overhead* `TextureView`. Saat **OLED Saver** aktif (`isDimmed == true`) atau `Activity` memasuki `ON_STOP` (*backgrounded*), UI melepaskan `PreviewView` melalui `detachPreviewView()` (`DisposableEffect` / lifecycle observer) untuk memutus *surface* preview UI dan menekan pemakaian GPU, baterai, dan panas tanpa menghentikan streaming H.264 ke PC.
   - **Tap-to-Focus:** Sentuhan pengguna pada viewfinder dikonversi via `previewView.meteringPointFactory.createPoint(x, y)` dan dikirim ke `CameraStreamManager.focusAtPoint(point)` yang memanggil `cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(point, FLAG_AF or FLAG_AE).setAutoCancelDuration(3, SECONDS).build())`.
   - **Zero-Restart Bitrate Update:** Saat `ConfigureStream` hanya mengubah `bitrateKbps` sementara `cameraId`, `width`, `height`, dan `fps` tetap sama, `rebindCamera` langsung memanggil `encoder.updateBitrate(bitrateKbps)` tanpa membongkar ulang sesi CameraX maupun `MediaCodec`.

2. **`HardwareVideoEncoder` / `H264SurfaceEncoder` (`encoder/`):**
   - Menginisialisasi `MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)` (H.264/AVC) dengan `COLOR_FormatSurface`, `KEY_I_FRAME_INTERVAL = 1`, `KEY_MAX_B_FRAMES = 0`, `BITRATE_MODE_CBR`, serta `KEY_LATENCY = 1` dan `KEY_PRIORITY = 0` (*real-time*).
   - Memanggil `createInputSurface()` untuk memasok `Surface` langsung ke `Preview` CameraX encoder, sehingga konversi warna ISP/GPU ke encoder berjalan 100% di perangkat keras tanpa menyalin byte YUV ke heap JVM.
   - **Zero-Allocation Reusable Output Scratch Buffer:** Pada *hot loop* `drainEncoderLoop` (`CamCraft-H264-Drain`), encoder memelihara satu **`ByteArray` *scratch buffer* yang dipakai berulang kali** (kapasitas awal 256 KiB, hanya diperbesar jika NAL frame melebihi kapasitas) dengan cadangan *offset* 4 byte di awal untuk menulis *start code* Annex-B (`00 00 00 01`) secara *in-place* apabila keluaran `MediaCodec` belum berformat Annex-B. Mekanisme ini meneruskan `(packetType, flags, ptsUs, payload, offset, length)` langsung ke penulis soket tanpa alokasi `ByteArray` per frame. Khusus `BUFFER_FLAG_CODEC_CONFIG` (`SPS/PPS`), konfigurasi tetap disalin ke `cachedConfig` terpisah agar siap dikirim ulang saat klien PC baru terhubung.
   - **Seleksi Profil & Level AVC Dinamis (`selectAvcLevel`):** Menetapkan `AVCProfileBaseline` bersama level AVC adaptif sesuai resolusi & FPS: `<= 1280x720@30` -> **`AVCLevel31`**, `<= 1920x1080@30` -> **`AVCLevel4`**, `<= 1920x1080@60` -> **`AVCLevel42`**, `<= 3840x2160@30` -> **`AVCLevel51`**, dan konfigurasi di atasnya -> **`AVCLevel52`**; disertai fallback aman tanpa `KEY_PROFILE`/`KEY_LEVEL`/`KEY_LATENCY` bila driver chipset menolak atribut opsional.
   - **Pembaruan Bitrate Dinamis Tanpa Restart:** `updateBitrate(bitrateKbps)` memanggil `mediaCodec.setParameters(Bundle)` dengan **`MediaCodec.PARAMETER_KEY_VIDEO_BITRATE`** sehingga perubahan bitrate berlaku seketika tanpa memutus aliran frame.
   - **Penandaan Transform Flags pada Header `VIDEO_FRAME` (`docs/PROTOCOL.md` §4):** `CamCraftStreamService` menyimpan `activeTransformFlags` (`computeTransformFlags(mirrorHorizontal, rotationDeg)`) dan menempelkan bitmask **`FLAG_MIRROR_H` (`0x0040`)** serta **`FLAG_ROTATE_90` (`0x0010`) / `FLAG_ROTATE_180` (`0x0020`) / `FLAG_ROTATE_270` (`0x0030`)** ke field `flags` header `VIDEO_FRAME (0x02)` (digabung dengan `FLAG_KEYFRAME` `0x0001` bila IDR).

3. **`AudioStreamEngine` (`encoder/`):**
   - Membaca sampel suara dari `AudioRecord` (`AudioSource.MIC`, 48.000 Hz, 16-bit PCM Mono, 960 sampel / 1.920 byte per frame 20 ms) menggunakan buffer PCM tetap dan buffer senyap terpra-alokasi saat kondisi *mute* (tanpa alokasi per-frame).
   - Mengirimkan `AUDIO_CONFIG (0x03)` (`"pcm_s16le"` sebagai baseline *ultra-low latency*, dengan dukungan ekstensi `"opus"`) diikuti aliran `AUDIO_FRAME (0x04)`.

4. **`CamCraftStreamService` & `MdnsAdvertiser` (`network/`):**
   - Berjalan sebagai **Android Foreground Service** (`foregroundServiceType="camera|microphone|connectedDevice"`) dengan ikon notifikasi monokrom `ic_stat_camcraft` dan teks status dinamis (`Connected (<ip>) • WxH @ FPS`).
   - **Mode Server (USB & Wi-Fi Direct Connect):** Membuka `ServerSocket(9876)` dengan `TCP_NODELAY = true`. Untuk USB, PC Fedora menjalankan `adb forward tcp:9876 tcp:9876` lalu terhubung ke `127.0.0.1:9876`. Untuk Wi-Fi, PC terhubung langsung ke `<IP_HP>:9876`.
   - **Mode Discovery (mDNS):** `MdnsAdvertiser` mengiklankan `_camcraft._tcp.local.` melalui `NsdManager` Android serta memilih alamat IPv4 LAN terbaik via `selectBestLocalIpv4`.
   - **Pemisahan Lifecycle UI:** `MainActivity` mengonsumsi `CamCraftStreamService.serviceState` melalui `collectAsStateWithLifecycle()`, mengamati baterai/suhu OS secara pasif lewat `BroadcastReceiver` (`ACTION_BATTERY_CHANGED`), dan hanya meneruskan state serta callback ke *composable* `ui/` yang bersifat *dumb UI* (`AGENTS.md` §5.1).

---

## 3. Desain Sisi Desktop Fedora (`desktop/`)

### 3.1 Struktur Workspace Cargo (4 Crates)

1. **`crates/camcraft-protocol`:**
   - Crate murni tanpa dependensi OS/FFI berat.
   - Berisi struktur header biner `PacketHeader` (20 byte Big-Endian sesuai `docs/PROTOCOL.md` §2), enum `PacketType`, bitmask `FrameFlags`, parser *zero-copy* berbasis `bytes`, serta tipe pesan JSON `ControlHello`, `ControlCmd`, `ControlAck`, dan `AudioConfigPayload` (`serde` + `serde_json`).
2. **`crates/camcraft-core`:**
   - **`net` & `client`:** Klien TCP asinkron Tokio (`TCP_NODELAY = true`) dengan kapasitas *read buffer* default **256 KiB (`262_144` byte)**, `tokio::select!` berprioritas (`biased;`) memakai `tokio::time::interval` persisten, serta pemindahan kepemilikan `Bytes` payload tanpa `.clone()` pada paket `VIDEO_CONFIG` dan `VIDEO_FRAME` menuju *bounded channel* (`crossbeam_channel::bounded`, membuang frame P non-keyframe saat terjadi *backpressure* agar latensi tetap *real-time*).
   - **`adb`:** Wrapper untuk menjalankan `adb devices` dan `adb forward tcp:9876 tcp:9876` secara otomatis.
   - **`discovery`:** Scanner mDNS RFC 6762/6763 UDP ringan (`_camcraft._tcp.local.`) tanpa dependensi DBus `avahi-daemon`. Mendukung `MdnsScanner::start_with_handler` (pemrosesan event langsung tanpa thread jembatan) serta `set_active_queries_paused(bool)`.
   - **`decoder`:** Dekoder H.264 berbasis `ffmpeg-next` (`libavcodec` + `libswscale`) pada thread `camcraft-vdec` yang memproses frame `YUV420P` (untuk V4L2) dan RGBA opsional (untuk preview GUI), dilengkapi `KeyframeRecoveryTracker` dan generator frame *standby/reconnecting*.
   - **`vcam` (Linux `v4l2loopback` Output Sink):** Membuka `/dev/videoX` (label `"CamCraft"`) yang disediakan [`v4l2loopback`](https://github.com/umlaeute/v4l2loopback), menyetel format piksel `V4L2_PIX_FMT_YUV420` (`YU12`), serta menjaga kelangsungan stream dengan frame statis (*standby/reconnecting card*) saat koneksi terputus agar aplikasi penerima (OBS, Discord/Vesktop, Google Meet/browser) tidak mengalami error I/O.
   - **`vmic` (PipeWire / PulseAudio Virtual Audio Sink):** Mengelola null sink `camcraft_sink` dan virtual microphone source `"CamCraft Virtual Mic"` (`camcraft_virtual_mic`) via `pactl`, menyalurkan PCM 48 kHz Mono S16LE ke `pacat` (fallback `pw-cat`) pada worker berkanal *bounded* (`camcraft-vmic`), serta menghitung metrik RMS/Peak dBFS secara langsung.
3. **`crates/camcraft-cli`:**
   - Binary ringan berbasis `clap` untuk menjalankan CamCraft dari terminal (`camcraft-cli connect --usb --vcam /dev/video10 --resolution 1080p --fps 60`), memakai `push_frame_with_flags` serta callback langsung tanpa thread jembatan.
4. **`crates/camcraft-gui`:**
   - Aplikasi desktop native berbasis `eframe` / `egui` yang dipecah bersih menjadi `main.rs` (*entry point* & *shortcut* keyboard), `session.rs` (*background session manager*), `theme.rs` (*ArtCraft Studio v2 Design System* & ikon vektor `egui::Painter`), dan `panels.rs` (*telemetry strip*, *preview canvas*, dan *tabbed inspector*).

### 3.2 Optimasi Performa & Sumber Daya Rendah ("Laptop Kentang")

Untuk memenuhi target penggunaan CPU dan memori yang sangat ringan pada laptop spesifikasi terbatas di Fedora Linux:

1. **`eframe` + `glow` (OpenGL) + `winit` `wayland-csd-adwaita` (Tanpa `wgpu`):**
   - `desktop/Cargo.toml` menonaktifkan fitur default `eframe` dan hanya mengaktifkan `["accesskit", "default_fonts", "glow", "wayland", "x11"]` bersama `winit` berfitur `wayland-csd-adwaita`. Menghindari backend `wgpu` (Vulkan/shader pipeline berat) memangkas ukuran binary, pemakaian VRAM/RAM, waktu kompilasi, serta mencegah masalah driver GPU terintegrasi/lama sambil tetap menghadirkan dekorasi jendela Wayland native.
   - Profil `[profile.release]` menggunakan `opt-level = 3`, `lto = "thin"`, `codegen-units = 1`, dan `strip = "symbols"`.
2. **Lean `current_thread` Tokio Runtime untuk GUI:**
   - Workspace hanya mengaktifkan fitur `tokio` yang esensial (`rt`, `rt-multi-thread`, `net`, `io-util`, `time`, `sync`, `macros`, `signal`, `fs`) alih-alih `features = ["full"]`.
   - `GuiSession` memakai **`tokio::runtime::Builder::new_current_thread().enable_all().build()`** karena seluruh I/O jaringan GUI hanyalah satu soket TCP asinkron — menghindari alokasi *thread pool* Tokio multi-thread yang mubazir.
3. **Bounded FFmpeg Slice Threading & `AV_CODEC_FLAG2_FAST`:**
   - `DecoderOptions` menetapkan **`thread_count = 2` sebagai default** (di-*clamp* `1..=4`, tidak pernah `0` *auto-detect* yang berisiko menelurkan terlalu banyak thread FFmpeg dan membengkakkan memori/context switch) serta mengaktifkan **`AV_CODEC_FLAG2_FAST`** bersamaan dengan `AV_CODEC_FLAG_LOW_DELAY` untuk mempercepat dekode H.264 pada CPU hemat daya.
4. **Zero Bridge Threads:**
   - Menghilangkan seluruh thread perantara (*forwarding/bridge threads*) baik di `camcraft-gui` maupun `camcraft-cli`: `MdnsScanner::start_with_handler` langsung menyalurkan `DiscoveryEvent` ke callback pemanggil, dan `LowLatencyWorkerOptions::on_keyframe_request` mengirim permintaan IDR langsung ke kanal kontrol tanpa thread `*-mdns` maupun `*-kf`.
5. **Pencegahan Kebocoran File Descriptor (`FD_CLOEXEC`):**
   - Segera setelah `V4l2LoopbackSink::open()` membuka `/dev/video*`, Rust memanggil `fcntl(fd, F_SETFD, FD_CLOEXEC)` (pada target Unix). Langkah ini memastikan *child process* seperti `pacat`, `pactl`, atau `adb` tidak pernah mewarisi file descriptor `/dev/video10` yang berpotensi mengunci perangkat `exclusive_caps=1`.
6. **Event-Driven Repainting & Auto-Pause Preview saat Minimized:**
   - GUI tidak menjalankan loop timer `request_repaint_after(30ms)` secara membabi buta. `egui` hanya dibangunkan melalui callback `on_preview_pushed` saat frame preview baru tersedia, saat ada pembaruan `GuiUpdate`, atau saat meter VU Audio aktif/meluruh di tab `Audio`.
   - Saat jendela aplikasi diminimalkan (`viewport.minimized == true`), `GuiSession::set_window_hidden(true)` otomatis menunda pemrosesan konversi RGBA preview di `decoder` tanpa mengubah status preferensi `preview_enabled` milik pengguna, dan langsung memulihkannya ketika jendela ditampilkan kembali.
7. **Preview RGBA Buffer Recycling:**
   - `GuiSession` memelihara kanal daur ulang *bounded* berkapasitas 2 (`preview_recycle_tx` / `preview_recycle_rx`) melalui `recycle_frame_buffer(buf)`. Setelah tekstur RGBA selesai diunggah ke GPU oleh `egui`, buffer `Vec<u8>` lama dikembalikan ke *decoder worker* agar `copy_contiguous_rgba` langsung menulis ke kapasitas buffer tersebut (**zero per-frame heap allocation**).
8. **Pause Query Aktif mDNS Saat Terhubung:**
   - Ketika sesi berstatus `Connected`, `MdnsScanner::set_active_queries_paused(true)` menghentikan transmisi *query* multicast UDP periodik ke jaringan lokal (sambil tetap mendengarkan paket pasif dan tetap melayani pemicu manual *Refresh*), lalu mengaktifkannya kembali (`false`) saat sesi terputus.
9. **In-Pipeline YUV420P Mirror & Rotation (1:1 Parity dengan Preview):**
   - `decoder.rs` menerjemahkan bitmask `FrameFlags` (`FLAG_MIRROR_H`, `FLAG_ROTATE_90`/`180`/`270`) menjadi `VideoTransform` dan mengeksekusi transformasi langsung pada bidang piksel Y, U, dan V melalui `copy_transformed_yuv420p` (mirror horizontal diterapkan sebelum rotasi searah jarum jam). Dengan demikian, aplikasi penerima `/dev/video10` (OBS, Discord/Vesktop, browser) menerima gambar yang identik **1:1** dengan preview GPU di GUI, sementara `PreviewFrame` tetap membawa frame mentah agar transformasi tampilan di GUI tidak terjadi dua kali (*no double-transform*).

---

## 4. Desain Antarmuka Pengguna (UI/UX Guidelines — ArtCraft Studio v2 Design System)

### 4.1 Prinsip Visual, Token Warna & Tipografi

- **Estetika Dark Mode OLED + Dense Studio Dashboard:** Kanvas gelap mendalam berpresisi tinggi ala perangkat lunak *broadcast/studio* dengan pembatas kartu tipis `1px` (*crisp hairline*), pemisahan peran antara panggung video dan panel kontrol, serta kepadatan informasi tanpa *clutter* maupun scroll vertikal tak berujung.
- **Color Tokens (Terverifikasi kontras `>= 4.5:1` WCAG AA):**

| Token Semantik | Nilai Hex | Penggunaan Utama |
| :--- | :--- | :--- |
| `bg-canvas` | `#0D0F14` | Latar belakang utama aplikasi & ruang kerja |
| `bg-stage` | `#080A0E` | Panggung viewfinder kamera / canvas video preview |
| `surface-card` | `#161A22` | Kartu panel kontrol, header strip, dan dock bawah |
| `border-subtle` | `#252B37` | Garis batas tipis `1px` / `1dp` pada kartu dan pembatas |
| `surface-elevated` | `#1F2530` | Permukaan sekunder, pill badge, input, ubin kontrol |
| `surface-hover` | `#293140` | Status *hover* / *pressed* / fokus elemen interaktif |
| `accent-primary` | `#3B82F6` | Aksi utama, slider aktif, tab terpilih (*hover*: `#2563EB`) |
| `status-live` | `#10B981` | Status `LIVE / CONNECTED`, decode `< 8 ms`, suhu `< 40°C` |
| `status-warn` | `#F59E0B` | Status `READY / RECONNECTING`, decode `8–15 ms`, suhu `40–43.9°C` |
| `status-danger` | `#EF4444` | Status `ERROR / STOP`, clipping audio, decode `> 15 ms`, suhu `>= 44°C` |
| `text-primary` | `#F8FAFC` | Teks judul, angka utama, label kontrol |
| `text-secondary` | `#CBD5E1` | Subjudul, nilai pengaturan pendukung |
| `text-muted` | `#8B95A7` | Metadata halus, satuan, teks *placeholder* |

- **Spacing & Typography:**
  - **Ritme Spasi `4px / 8px`:** Seluruh *padding*, *gap* antar elemen (`4px`, `8px`, `12px`, `16px`), dan radius sudut (`6px`, `8px`, `12px`) konsisten mengikuti grid `4px`/`8px`.
  - **Tabular Monospace Numerals:** Seluruh angka telemetri real-time (**`FPS`**, **`Mbps`**, **`ms`** decode/RTT, **`dB`** VU meter, serta **`IP:Port`**) wajib memakai *font monospace* berangka tabular agar lebar kolom tidak bergeser saat nilai berubah setiap detik.
  - **Ikonografi Vektor + Semantik Aksesibel:** Seluruh ikon menggunakan gambar vektor kustom (`ImageVector` di Android Jetpack Compose, `egui::Painter` di Desktop Rust) yang selalu dipadukan dengan label teks atau atribut semantik aksesibilitas — **dilarang** mengandalkan indikator warna saja (*color-only*) ataupun karakter emoji.

### 4.2 Spesifikasi UI Aplikasi Android (`android/.../ui/`)

1. **Top Studio Status Bar:**
   - Kiri: Pill status koneksi (ikon vektor + titik indikator + label tegas `LIVE`, `READY`, `STANDBY`, atau `ERROR`) beserta subjudul IP/port klien PC.
   - Kanan: Pill resolusi & FPS aktif (`1080p • 60 FPS`) dan **Battery & Thermal Pill** (`84% • 34°C` beserta indikator pengisian daya, berkode warna *emerald* `< 40°C`, *amber* `40–43.9°C`, dan *rose* `>= 44°C`).
   - Baris endpoint Wi-Fi interaktif: Ikon vektor `Wifi` + alamat monospace `192.168.x.x:9876` + aksi **Tap-to-Copy** (memberi umpan balik ikon centang `"Copied"` sesaat) + badge status **`mDNS ON/OFF`**.
2. **Center Viewfinder Stage (`bg-stage` `#080A0E`, border `1dp` `#252B37`):**
   - Saat terhubung (`CONNECTED`) dan viewfinder aktif (`showViewfinder == true`): menampilkan `PreviewView` dengan `ImplementationMode.PERFORMANCE` (`SurfaceView`, `FILL_CENTER`).
   - **Tap-to-Focus Reticle:** Menangkap sentuhan koordinat layar, memetakan ke `previewView.meteringPointFactory.createPoint(x, y)` -> `onTapToFocus(point)`, serta menampilkan indikator *reticle* fokus yang ringkas.
   - **Rule-of-Thirds Grid Overlay:** Kisi 3x3 opsional yang dapat dinyalakan/dimatikan lewat tombol HUD `GridOn` dengan ukuran target sentuh `>= 48dp`.
   - **Floating HUD:** Pill `LIVE • WxH` di kiri atas, badge bitrate + tombol Grid di kanan atas, dan kapsul diagnostik *frosted* gelap di bagian bawah (`FPS` tabular monospace, jumlah frame terkirim, rasio zoom, label lensa, dan kompensasi EV).
   - Status non-aktif (`LISTENING`, `STOPPED`, `ERROR`, maupun `Viewfinder Paused`) menyajikan panduan visual terstruktur dengan ikon vektor.
3. **Bottom 4-Tile Quick Control Dock & Primary CTA:**
   - **4-Tile Quick Control Dock:** 4 ubin kontrol berjajar seragam (tinggi `>= 56dp`, memenuhi standar target sentuh minimal `48dp`, dilengkapi `semantics` `Role` dan `stateDescription`): **`Lens`** (`SwitchCamera`), **`Torch`** (`FlashOn`/`FlashOff`), **`Mic`** (`MicOn`/`MicOff`), dan **`Dim`** (`OledDim`).
   - **Full-Width Start / Stop CTA:** Tombol primer selebar penuh (`height >= 52dp`) untuk **`Start Server`** / **`Stop Server`** dengan ikon vektor `Power`.
4. **Pure-Black OLED Saver (`#000000`):**
   - Menggunakan latar hitam murni `#000000`, menurunkan kecerahan layar ke `0.01f`, menyembunyikan bilah sistem, dan **melepas (`detach`) `PreviewView`** sepenuhnya agar GPU dan panel OLED tidak membuang daya selama streaming berjalan.
   - Menampilkan info minimalis (status stream, resolusi/FPS, dan **`batterySummary`** baterai & suhu) yang bergeser antar 8 *waypoint* setiap 30 detik untuk mencegah *burn-in*; ketuk dua kali (*double-tap*) untuk membuka kembali layar kontrol.
   - Menghormati pengaturan aksesibilitas **Reduced Motion** (`Settings.Global.ANIMATOR_DURATION_SCALE == 0f`) dengan memindahkan posisi teks secara instan (*snap*) tanpa interpolasi animasi.

### 4.3 Spesifikasi UI Aplikasi Desktop Fedora (`desktop/crates/camcraft-gui/`)

1. **Top Studio Telemetry Strip:**
   - Menampilkan wordmark `CamCraft` + lencana `STUDIO`, pill status koneksi + nama perangkat Android, telemetri monospace tabular (**`WxH • FPS`**, **`Mbps`**, **`Decode ms`** berkode warna *emerald* `< 8 ms` / *amber* `8–15 ms` / *rose* `> 15 ms`, dan **`RTT ms`**), badge **`VCam`** (`/dev/video10`), badge **`VMic`**, serta pill interaktif satu-klik untuk memilih **Laptop Performance Profile / Preview Quality** (`Eco` / `Balanced` / `Crisp` / `Preview Off`).
2. **Left 70% Cinema Preview Canvas:**
   - Area panggung `#080A0E` dengan bingkai `1px` `#252B37` yang merender frame GPU preview, pill `LIVE` di sudut kiri atas, toggle kisi *Rule-of-Thirds*, dan bilah aksi cepat mengambang di kanan bawah.
   - **Empty-State & Auto-Pause:** Ketika belum terhubung, menampilkan kartu **2-Step Quick-Start Guide** (koneksi USB ADB atau Wi-Fi LAN); ketika preview dijeda (*Pause Preview* / mode `Eco`) menampilkan kartu ringkas dengan metrik aktif dan status `/dev/video10 Active`; ketika jendela diminimalkan, preview terjeda otomatis (*auto-pause*).
   - **Keyboard Shortcuts** (aktif saat fokus tidak berada di input teks): **`M`** (Mute/Unmute Mic), **`P`** (Pause/Resume Preview), **`H`** (Mirror Horizontal), dan **`R`** (Rotate +90°).
3. **Right 30% Tabbed Inspector (`Connect` | `Camera` | `Audio`):**
   - Menggunakan navigasi tab tersegmen agar seluruh kontrol mudah dijangkau dalam satu pandangan tanpa *endless scrolling*:
     - **`Connect`:** Tombol `Connect via USB (ADB)`, daftar perangkat mDNS Wi-Fi dengan tombol `Refresh` dan koneksi 1-klik, input manual `IP:Port` (mendukung tombol `Enter`), path VCam (`/dev/video10`), checkbox `Auto-Reconnect (2s)`, pemilih **Laptop Performance Profile** (`Eco` 480px/~15fps, `Balanced` 640px/~24fps, `Crisp` 960px/~30fps + `Pause Preview`), dan tombol `Disconnect` / `Cancel Reconnect`.
     - **`Camera`:** Pemilih Lensa, dropdown Resolusi berdasarkan kapabilitas kamera aktif (fallback 720p/1080p/4K), toggle segmen `30 / 60 FPS`, slider Bitrate + chip cepat (`6M` / `12M` / `20M`), tombol `Apply Stream Settings` (disorot saat ada perubahan tertunda), serta *Live Adjustments* (*debounce* 50 ms tanpa restart pipeline: slider Zoom + chip `1x`/`2x`/`3x`, slider Exposure EV + `Reset 0.0`, toggle `Autofocus`, `Torch`, `Mirror Horizontal`, dan segmen Rotasi `0°`/`90°`/`180°`/`270°`).
     - **`Audio`:** Status dan nama *PipeWire Virtual Mic* (`"CamCraft Virtual Mic"`), toggle `Enable Audio Forwarding`, tombol `Mute / Unmute Mic (M)`, serta **Segmented LED dBFS VU Meter** (`-60` hingga `0 dB`) lengkap dengan *peak hold* dan penanda *clipping*.
