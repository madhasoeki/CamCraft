# Product Requirements Document (PRD): CamCraft

| Metadata | Detail |
| :--- | :--- |
| **Nama Produk** | CamCraft |
| **Ekosistem** | ArtCraft Ecosystem |
| **Lisensi** | Open Source (MIT / Apache-2.0) |
| **Target Rilis Awal (v0.1 MVP)** | Fedora Linux (Desktop Receiver) + Android (Mobile Sender) |
| **Visi Jangka Panjang (v1.0+)** | Cross-platform (Linux, Windows, macOS + Android, iOS) |
| **Tech Stack Utama** | Desktop: Native Rust \| Android: Native Kotlin (Jetpack Compose + CameraX) |

---

## 1. Latar Belakang & Masalah
Pengguna PC sering membutuhkan webcam berkualitas tinggi untuk rapat daring (Zoom, Google Meet, Discord/Vesktop) maupun pembuatan konten dan *live streaming* (OBS Studio). Meskipun setiap pengguna sudah memiliki kamera berkualitas tinggi di smartphone mereka, solusi yang ada saat ini (seperti DroidCam, Iriun Webcam, atau Camo) memiliki kelemahan mendasar:
1. **Paywall Resolusi Buatan:** Membatasi versi gratis pada resolusi standar (480p/720p) atau menyematkan *watermark* kecuali pengguna membayar langganan bulanan/tahunan.
2. **Dukungan Linux Kelas Dua:** Banyak solusi proprietari tidak mendukung Wayland, PipeWire, atau kernel Fedora terbaru secara mulus.
3. **Privasi & Ketergantungan Cloud:** Beberapa aplikasi modern mengharuskan pembuatan akun atau telemetri latar belakang.

## 2. Solusi & Proposisi Nilai CamCraft
**CamCraft** adalah alternatif *free & open-source* yang memungkinkan pengguna mengubah smartphone mereka menjadi webcam dan mikrofon PC hingga resolusi **1080p (Full HD) dan 4K pada 60fps** tanpa batasan buatan:
- **100% Lokal & Privat:** Seluruh pemrosesan video dan audio dilakukan secara lokal melalui kabel USB atau jaringan Wi-Fi lokal (LAN) tanpa server cloud.
- **Sangat Ringan (Native Rust & Kotlin):** Menggunakan *hardware encoder* (`MediaCodec` di Android) dan *hardware/FFmpeg decoder* di Rust dengan konsumsi CPU dan RAM minimal.
- **Integrasi Native Linux:** Terhubung langsung ke kernel [`v4l2loopback`](https://github.com/umlaeute/v4l2loopback) (`/dev/video*`) dan sistem audio PipeWire di Fedora Linux sehingga langsung dikenali oleh Discord, Vesktop, OBS, Zoom, dan peramban web.

---

## 3. Target Pengguna & Use Cases Utama
1. **Linux & Open-Source Enthusiast (Fedora User):** Menginginkan webcam 1080p/4K untuk panggilan video di Vesktop/Discord atau Google Meet di atas Wayland + PipeWire tanpa konfigurasi manual yang rumit.
2. **Streamer & Content Creator:** Menggunakan kamera belakang smartphone (lensa utama/ultrawide) sebagai *facecam* tajam di OBS Studio melalui koneksi kabel USB berlatensi ekstra rendah.
3. **Remote Worker:** Membutuhkan kamera dan mikrofon cadangan yang andal saat bekerja dari rumah atau saat bepergian.

---

## 4. Spesifikasi Fitur (Functional Requirements)

### P0 — Fitur Inti (MVP v0.1: Fedora Linux + Android via USB & Wi-Fi)
- **FR-01: High-Resolution Video Streaming:** Mendukung resolusi 720p, 1080p (Full HD), dan 4K (2160p — jika didukung sensor HP) dengan pilihan *frame rate* 30fps dan 60fps menggunakan kompresi perangkat keras H.264.
- **FR-02: Koneksi Kabel USB (Ultra-Low Latency):** Mendukung koneksi melalui kabel USB menggunakan jembatan [ADB Port Forwarding](https://hacktricks.wiki/en/mobile-pentesting/android-app-pentesting/adb-commands.html) (`adb forward tcp:9876 tcp:9876`) untuk stabilitas tinggi dan latensi di bawah 35 ms.
- **FR-03: Koneksi Nirkabel (Wi-Fi LAN):** Mendukung koneksi melalui jaringan Wi-Fi lokal yang sama menggunakan soket TCP/UDP dan penemuan perangkat otomatis (*mDNS / Zeroconf*) atau input IP / pemindaian QR Code.
- **FR-04: Virtual Camera Linux (`v4l2loopback`):** Meneruskan *decoded frame* (format `YUV420p` / `YUYV`) langsung ke device `/dev/videoX` berlabel `"CamCraft Virtual Camera"` dengan kompatibilitas penuh terhadap Chromium, Firefox, Discord/Vesktop, dan OBS.
- **FR-05: Kamera Kontrol Dua Arah (Two-Way Camera Controls):** Baik dari layar HP maupun dari aplikasi GUI Desktop di Fedora, pengguna dapat:
  - Beralih antara kamera depan (*front*) dan belakang (*rear* / *ultrawide*).
  - Mengubah resolusi (720p, 1080p, 4K) dan *frame rate* (30/60 fps) secara dinamis.
  - Mengatur *autofocus* vs *manual focus*, *exposure compensation* (EV), *zoom*, orientasi (rotasi 90°/180°/270°), *mirror/flip*, dan menyalakan *flashlight* (LED torch).

### P1 — Fitur Audio & Kenyamanan (v0.2)
- **FR-06: Audio Microphone Forwarding:** Mengirimkan input mikrofon smartphone (dikompresi dengan codec Opus berlatensi rendah, 48kHz mono/stereo) ke PC Fedora sebagai *PipeWire Virtual Audio Source* (`"CamCraft Virtual Mic"`), lengkap dengan tombol *Mute/Unmute*.
- **FR-07: Mode Hemat Baterai (Battery Saver / Dim Screen):** Layar aplikasi Android dapat digelapkan (*black overlay* dengan teks status minimal) saat sedang streaming agar layar OLED hemat baterai dan HP tidak cepat panas.
- **FR-08: Auto-Reconnect & Heartbeat:** Mendeteksi apabila kabel USB tercabut atau sinyal Wi-Fi terputus sementara, menjaga virtual camera di Linux tetap menampilkan *freeze frame* atau *placeholder frame* agar aplikasi seperti Zoom/OBS tidak *crash*.

### P2 — Fitur Masa Depan (v1.0+ Cross-Platform)
- **FR-09:** Dukungan codec HEVC (H.265) dan AV1 untuk efisiensi bandwidth 4K di Wi-Fi.
- **FR-10:** Driver kamera virtual untuk Windows (DirectShow / Media Foundation) dan macOS (CoreMediaIO DAL / Camera Extension), serta aplikasi pengirim iOS (Swift + AVFoundation).

---

## 5. Kebutuhan Non-Fungsional (Performance & Quality)
| Parameter | Target Metrik |
| :--- | :--- |
| **Latensi End-to-End (USB)** | `<= 35 ms` pada 1080p @ 60fps |
| **Latensi End-to-End (Wi-Fi 5GHz)** | `<= 60 ms` pada 1080p @ 60fps |
| **Penggunaan CPU Desktop (Fedora)** | `< 8%` pada prosesor modern saat menggunakan VA-API / hardware decode |
| **Penggunaan Memori Desktop** | `< 80 MB` RSS untuk daemon + GUI |
| **Stabilitas Sesi** | Mampu berjalan nonstop `>= 4 jam` tanpa *memory leak* atau pergeseran sinkronisasi (*audio-video drift*) |

---

## 6. Batasan Ruang Lingkup Awal (Non-Goals untuk MVP v0.1)
Agar pengembangan dengan AI (*vibecoding*) tetap tajam dan tidak melebar:
1. **Tidak ada layanan Cloud / Relay Internet:** Koneksi hanya melalui USB lokal atau LAN lokal.
2. **Tidak ada Filter AI / Background Blur Berat di MVP v0.1:** Fokus utama v0.1 adalah kualitas gambar murni, ketajaman resolusi 1080p/4K, dan latensi serendah mungkin.
3. **Belum menyertakan installer Windows/macOS/iOS di kode v0.1:** Namun abstraksi *trait* di Rust (`VideoOutputSink`, `AudioOutputSink`) sudah disiapkan sejak awal agar *porting* ke OS lain nantinya mudah.
