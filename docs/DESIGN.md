# Dokumen Desain Teknis & Arsitektur Sistem (DESIGN.md)

Dokumen ini menjabarkan arsitektur internal **CamCraft**, pemilihan pustaka (*crates* & *libraries*), manajemen *thread*, serta desain antarmuka pengguna (UI/UX) untuk aplikasi Android dan Desktop Fedora.

---

## 1. Arsitektur End-to-End Pipeline

```text
+-------------------------------------------------------------------------+
| ANDROID SMARTPHONE (Native Kotlin, minSdk 29)                           |
|                                                                         |
|  [CameraX Preview/Video] ---> (GPU Surface) ---> [MediaCodec H.264 HW]  |
|         ^                                                 |             |
|         | (Camera2Interop: ISO, EV, Zoom, Lens)           v (NAL Units) |
|  [Control Handler] <--------+                    [CCFT Packet Framer]   |
|                             |                             |             |
|  [AudioRecord 48kHz] ---> [Opus/PCM Encoder] -------------+             |
+-----------------------------|-----------------------------|-------------+
                              | (TCP Port 9876:             |
                              |  USB ADB Tunnel or Wi-Fi)   v
+-----------------------------|-----------------------------|-------------+
| FEDORA LINUX DESKTOP (Native Rust, 2024 Edition)          |             |
|                             |                             v             |
|  [GUI Control Panel] -------+              [Tokio Async Socket Reader]  |
|  (iced / egui)                                            |             |
|       ^                                                   v             |
|       | (Preview RGB/RGBA)                 [CCFT Frame Demuxer]         |
|       |                                      /                   \      |
|  [Video Decode Worker] <--------------------+ (Video Queue)       +--> [Audio Decode Worker]
|  (ffmpeg-next / VA-API)                                                 (opus -> S16LE PCM)
|       |                                                                       |
|       v (YUV420P / YUYV)                                                      v
|  [/dev/videoX (v4l2loopback)]                                      [PipeWire Virtual Mic Sink]
+-------------------------------------------------------------------------+
```

---

## 2. Desain Sisi Android (`android/`)

### 2.1 Komponen & Tanggung Jawab
1. **`CameraController` (`camera/`):**
   - Mengelola `ProcessCameraProvider` dari [Jetpack CameraX](https://developer.android.com/training/camerax).
   - Mengekspos kemampuan perangkat (daftar kamera depan/belakang/ultrawide, daftar resolusi yang didukung per lensa, rentang zoom, dan rentang *exposure compensation*).
   - Menggunakan `Camera2Interop` untuk pengaturan tingkat lanjut (mengunci fokus manual, mengatur *frame rate range* `Range(60, 60)`).
2. **`HardwareVideoEncoder` (`encoder/`):**
   - Menginisialisasi `MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)` (H.264) dengan profil `CodecProfileLevel.AVCProfileBaseline` atau `AVCProfileMain` dan `KEY_LATENCY = 0` / `KEY_PRIORITY = 0` (real-time).
   - Memanggil `createInputSurface()` untuk menghasilkan `Surface` yang langsung diberikan ke CameraX (`Preview.SurfaceProvider` kedua atau `VideoCapture`), sehingga konversi warna kamera ke encoder terjadi sepenuhnya di perangkat keras tanpa menyalin buffer ke heap JVM.
   - Membaca output dari `MediaCodec` pada *dedicated coroutine thread* (`Dispatchers.Default`), memisahkan konfigurasi `SPS/PPS` (`BUFFER_FLAG_CODEC_CONFIG`) dan *keyframe* (`BUFFER_FLAG_KEY_FRAME`), lalu membungkusnya ke dalam paket `CCFT`.
3. **`AudioCaptureEngine` (`encoder/`):**
   - Membaca sampel suara dari `AudioRecord` (`AudioSource.MIC` atau `AudioSource.CAMCORDER`, 48.000 Hz, 16-bit PCM Mono).
   - Mengenkode setiap frame 20 ms (960 sampel) ke dalam format Opus (atau PCM mentah untuk mode USB *ultra-low latency*) dan mengirimkannya melalui `CCFT Packet Framer`.
4. **`StreamConnectionService` (`network/`):**
   - Berjalan sebagai **Android Foreground Service** (`foregroundServiceType="camera|microphone|connectedDevice"`) agar OS Android tidak menghentikan kamera saat pengguna mematikan/meredupkan layar.
   - Mendukung dua mode koneksi:
     - **Mode Server (USB & Wi-Fi Direct Connect):** Membuka `ServerSocket(9876)` di Android. Pada koneksi USB, PC Fedora menjalankan `adb forward tcp:9876 tcp:9876` lalu melakukan `TcpStream::connect("127.0.0.1:9876")`. Pada koneksi Wi-Fi, PC Fedora dapat langsung terhubung ke `<IP_HP>:9876`.
     - **Mode Discovery (mDNS):** Mengiklankan layanan `_camcraft._tcp.local.` menggunakan `NsdManager` Android agar PC Fedora langsung menemukan HP di jaringan Wi-Fi yang sama.

---

## 3. Desain Sisi Desktop Fedora (`desktop/`)

### 3.1 Struktur Workspace Cargo (4 Crates)
1. **`crates/camcraft-protocol`:**
   - Crate murni tanpa dependensi OS/FFI berat.
   - Berisi struktur header biner `PacketHeader` (18 byte), enum `PacketType`, parser *zero-copy* menggunakan crate `bytes`, serta tipe pesan JSON `ControlMessage` dan `DeviceCapabilities` menggunakan `serde` + `serde_json`.
   - Memiliki *unit test* dan *round-trip property test* lengkap.
2. **`crates/camcraft-core`:**
   - **`net`:** Klien jaringan asinkron berbasis `tokio::net::TcpStream` yang membaca aliran byte, memisahkan paket `CCFT`, dan mendistribusikannya ke *bounded channel* (`crossbeam_channel::bounded(4)` untuk video agar jika terjadi *lag*, frame lama langsung di-drop demi menjaga latensi tetap *real-time*).
   - **`adb`:** Wrapper untuk menjalankan `adb devices` dan `adb forward tcp:9876 tcp:9876` secara otomatis dari dalam kode Rust.
   - **`decoder`:** Menggunakan `ffmpeg-next` (`libavcodec` + `libswscale`) untuk mendekode aliran NAL H.264 menjadi frame `YUV420P` atau `YUYV422` (untuk V4L2) serta `RGBA` (untuk preview GUI).
   - **`vcam` (Linux `v4l2loopback` Output Sink):** Membuka `/dev/videoX` yang dibuat oleh [`v4l2loopback`](https://github.com/umlaeute/v4l2loopback), mengatur format piksel V4L2 (`VIDIOC_S_FMT` dengan `V4L2_PIX_FMT_YUV420` atau `V4L2_PIX_FMT_YUYV`), dan menulis byte frame hasil decode secara berkesinambungan. Jika koneksi HP terputus, *worker thread* tetap mengirimkan frame statis (*standby card*) agar aplikasi pemanggil seperti OBS/Discord tidak mengalami error I/O.
   - **`vmic` (PipeWire Virtual Audio Sink):** Mengirimkan audio PCM 48kHz S16LE hasil decode Opus ke PipeWire virtual sink/source (`camcraft_mic`) menggunakan `libpulse-simple-binding` atau `pipewire` crate / `pacat` pipe.
3. **`crates/camcraft-cli`:**
   - Binary ringan berbasis `clap` untuk menjalankan CamCraft dari terminal (`camcraft-cli connect --usb --vcam /dev/video10 --resolution 1080p --fps 60`), sangat berguna untuk pengujian otomatis oleh agen AI saat *vibecoding*.
4. **`crates/camcraft-gui`:**
   - Aplikasi desktop native menggunakan `egui` (`eframe`) atau `iced`.
   - Menampilkan *live preview* kamera, indikator metrik real-time (FPS, Bitrate Mbps, Latensi ms, Status `/dev/videoX`), pemilih mode koneksi (USB ADB vs Wi-Fi IP), dan panel kontrol kamera jarak jauh (Lens Switcher, Resolution, FPS, Exposure Slider, Zoom Slider, Mirror/Rotate, Torch toggle).

---

## 4. Desain Antarmuka Pengguna (UI/UX Guidelines — ArtCraft Aesthetic)

### 4.1 Prinsip Visual
- **Tema Gelap Profesional (Studio Dark Theme):** Menggunakan latar belakang karbon gelap (`#111318`), permukaan kartu abu-abu tua (`#1C2026`), dan aksen biru elektrik (`#3B82F6`) serta hijau emerald (`#10B981`) untuk indikator *"LIVE / STREAMING"*.
- **Zero Clutter:** Semua kontrol kamera dapat diakses dalam satu layar tanpa menu bertumpuk.

### 4.2 Tampilan Aplikasi Android
- **Bagian Atas (Status Bar):** Badge status koneksi (`USB Connected` / `Wi-Fi: 192.168.1.x:9876` / `Waiting for PC...`), indikator resolusi & FPS aktif (`1080p • 60 FPS • 12.5 Mbps`), dan persentase baterai/suhu HP.
- **Bagian Tengah:** Viewfinder `PreviewView` CameraX dengan *grid overlay* opsional.
- **Bagian Bawah (Quick Controls):**
  - Tombol **Switch Lens** (Back Wide / Back Ultrawide / Front)
  - Tombol **Torch (Flashlight)**
  - Tombol **Dim Screen (OLED Saver)** — menggelapkan layar sepenuhnya; ketuk dua kali untuk membuka kembali.
  - Tombol **Start / Stop Server**

### 4.3 Tampilan Aplikasi Desktop Fedora
- **Panel Kiri (70% Lebar):** Canvas *Video Preview* berakselerasi GPU dengan *overlay* statistik opsional (`Resolution`, `Decode Time ms`, `v4l2loopback Target: /dev/video10`).
- **Panel Kanan (30% Lebar — Inspector & Remote Control):**
  - **Connection Card:** Tombol `Connect via USB (ADB)` (otomatis mendeteksi perangkat ADB) dan input `Wi-Fi IP:Port`.
  - **Stream Preset Card:** Dropdown Resolusi (`720p`, `1080p`, `4K`), Radio FPS (`30`, `60`), Slider Bitrate (`4 Mbps` – `40 Mbps`).
  - **Camera Adjustment Card:** Pemilih Lensa, Slider Zoom (`1.0x` – `5.0x`), Slider Exposure (`-2.0 EV` – `+2.0 EV`), Toggle Autofocus, Tombol Rotate & Flip Horizontal, serta Toggle Microphone Forwarding.
