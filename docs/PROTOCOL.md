# Spesifikasi Wire Protocol CamCraft v1 (`PROTOCOL.md`)

> **PERINGATAN UNTUK AGEN AI:** Dokumen ini adalah **Single Source of Truth** untuk komunikasi jaringan antara pengirim Android (`android/app/.../protocol/`) dan penerima Desktop Rust (`desktop/crates/camcraft-protocol/`). Setiap perubahan byte offset, endianness, atau nama field JSON di satu sisi **WAJIB** diikuti perubahan identik di sisi lainnya.

---

## 1. Arsitektur Transport & Port Default
- **Transport Utama:** Satu koneksi TCP tunggal yang di-multiplex pada **Port `9876`** (baik untuk USB via `adb forward tcp:9876 tcp:9876` maupun untuk Wi-Fi LAN via `<android_ip>:9876`).
- **Alasan Pemilihan TCP Multiplexed pada MVP:**
  - Di atas USB (`adb forward`), TCP memiliki *zero packet loss* dan *overhead* yang dapat diabaikan (< 1 ms).
  - Di atas Wi-Fi lokal dengan socket flag `TCP_NODELAY = true`, satu koneksi TCP memudahkan sinkronisasi paket `SPS/PPS`, video H.264, audio Opus, dan pesan kontrol JSON tanpa kerumitan *NAT traversal* atau penyusunan ulang paket UDP yang terpecah (*fragmented NAL units*).
- **Socket Options Wajib:** Baik Kotlin `Socket` maupun Rust `TcpStream` **WAJIB** mengaktifkan `setTcpNoDelay(true)` (`TCP_NODELAY`) agar algoritma Nagle tidak menahan paket kecil.
- **Byte Order (Endianness):** Seluruh field integer multi-byte di dalam header biner menggunakan **Big-Endian (Network Byte Order)**.

---

## 2. Format Header Biner (`CCFT` Frame Header — 18 Bytes Fixed)

Setiap paket yang dikirim dari Android ke Desktop maupun dari Desktop ke Android **WAJIB** diawali dengan header berukuran tepat **18 byte** berikut:

```text
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|         Magic Bytes: "CCFT" (0x43, 0x43, 0x46, 0x54)          |  Bytes 0..3   (4B)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|  Version (1)  |  Packet Type  |        Flags (u16 BE)         |  Bytes 4..7   (4B)
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+               Timestamp Microseconds (u64 BE)                 +  Bytes 8..15  (8B)
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                  Payload Length in Bytes (u32 BE)             |  Bytes 16..19 -> WAIT: see exact layout below!
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

### Tabel Rincian Offset Byte (Total Header = 20 Bytes)
Agar selaras dengan batas *alignment* 4-byte, ukuran header ditetapkan tepat **20 Bytes**:

| Byte Offset | Nama Field | Tipe Data | Ukuran | Deskripsi |
| :--- | :--- | :--- | :--- | :--- |
| `0..4` | `magic` | `[u8; 4]` | 4 Bytes | Selalu berisi ASCII `"CCFT"` (`[0x43, 0x43, 0x46, 0x54]`). Jika tidak cocok, koneksi harus direset. |
| `4` | `version` | `u8` | 1 Byte | Versi protokol. Saat ini selalu `0x01`. |
| `5` | `packet_type` | `u8` | 1 Byte | Jenis muatan paket (lihat tabel `PacketType` di bawah). |
| `6..8` | `flags` | `u16` (BE) | 2 Bytes | Bitmask flag (mis. `0x0001` = Keyframe / IDR, `0x0002` = End of Stream). |
| `8..16` | `pts_us` | `u64` (BE) | 8 Bytes | *Presentation Timestamp* dalam mikrodetik (`microseconds`) dari `System.nanoTime() / 1000` atau `MediaCodec.BufferInfo.presentationTimeUs`. |
| `16..20` | `payload_len` | `u32` (BE) | 4 Bytes | Panjang byte `payload` yang mengikuti langsung setelah byte ke-19. Maksimum yang diizinkan: `16_777_216` (16 MB). |
| `20..(20 + payload_len)` | `payload` | `[u8]` | Variabel | Data biner (H.264 Annex-B NAL unit, frame Opus/PCM, atau string UTF-8 JSON). |

---

## 3. Daftar Nilai `PacketType` (`u8` pada Offset 5)

| Nilai Hex | Nama Konstanta | Arah Aliran | Format Payload | Deskripsi |
| :--- | :--- | :--- | :--- | :--- |
| `0x01` | `VIDEO_CONFIG` | Android -> PC | Raw H.264 Annex-B (`SPS` + `PPS`) | Dikirim di awal sesi dan setiap kali resolusi/kamera berubah. Berisi *Codec Specific Data* (`csd-0` dan `csd-1` yang digabung dalam format Annex-B dengan *start code* `00 00 00 01`). |
| `0x02` | `VIDEO_FRAME` | Android -> PC | Raw H.264 Annex-B NAL Unit |Satu frame video terkompresi. Jika frame tersebut adalah *IDR Keyframe*, bit `0x0001` pada `flags` wajib diset (`flags = 0x0001`). |
| `0x03` | `AUDIO_CONFIG` | Android -> PC | UTF-8 JSON | Konfigurasi stream audio (sample rate, channel count, codec `"opus"` atau `"pcm_s16le"`). |
| `0x04` | `AUDIO_FRAME` | Android -> PC | Raw Opus Packet / PCM S16LE | Satu paket audio (default: durasi 20 ms @ 48.000 Hz). |
| `0x10` | `CONTROL_HELLO` | Dua Arah | UTF-8 JSON | Dikirim saat koneksi TCP pertama kali terbentuk untuk bertukar kemampuan perangkat (`DeviceCapabilities`). |
| `0x11` | `CONTROL_CMD` | PC -> Android (atau sebaliknya) | UTF-8 JSON | Perintah kontrol kamera secara real-time (ganti lensa, resolusi, FPS, zoom, EV, torch, mute). |
| `0x12` | `CONTROL_ACK` | Android -> PC | UTF-8 JSON | Konfirmasi status terbaru kamera setelah menjalankan `CONTROL_CMD`. |
| `0xFE` | `HEARTBEAT_PING` | Dua Arah | Kosong (`payload_len = 0`) | Dikirim setiap 1.000 ms jika tidak ada lalu lintas kontrol untuk mendeteksi putusnya koneksi. |
| `0xFF` | `HEARTBEAT_PONG` | Dua Arah | Kosong (`payload_len = 0`) | Balasan langsung terhadap `HEARTBEAT_PING` dengan menyalin `pts_us` dari ping (digunakan untuk menghitung *Round-Trip Time / Latency*). |

---

## 4. Definisi Bitmask `flags` (`u16` pada Offset 6..8)

```rust
pub const FLAG_NONE: u16        = 0x0000;
pub const FLAG_KEYFRAME: u16    = 0x0001; // Frame video ini adalah IDR Keyframe
pub const FLAG_CODEC_HEVC: u16  = 0x0002; // Jika diset, payload video adalah H.265/HEVC (default 0 = H.264/AVC)
pub const FLAG_ROTATE_90: u16   = 0x0010; // Metadata rotasi 90 derajat searah jarum jam
pub const FLAG_ROTATE_180: u16  = 0x0020; // Metadata rotasi 180 derajat
pub const FLAG_ROTATE_270: u16  = 0x0030; // Metadata rotasi 270 derajat
pub const FLAG_MIRROR_H: u16    = 0x0040; // Flip horizontal
```

---

## 5. Skema JSON untuk Kanal Kontrol (`0x10`, `0x11`, `0x12`)

Semua payload untuk paket `0x10` (`CONTROL_HELLO`), `0x11` (`CONTROL_CMD`), dan `0x12` (`CONTROL_ACK`) dikodekan sebagai UTF-8 JSON tanpa *trailing null byte*.

### 5.1 `CONTROL_HELLO` (`0x10`) — Dikirim Android ke PC Saat Terhubung
```json
{
  "device_name": "Pixel 8 Pro",
  "protocol_version": 1,
  "cameras": [
    {
      "id": "0",
      "facing": "back",
      "label": "Rear Main Wide",
      "resolutions": [
        { "width": 1280, "height": 720, "max_fps": 60 },
        { "width": 1920, "height": 1080, "max_fps": 60 },
        { "width": 3840, "height": 2160, "max_fps": 60 }
      ],
      "max_zoom": 8.0,
      "ev_range": [-4.0, 4.0],
      "has_flash": true
    },
    {
      "id": "1",
      "facing": "front",
      "label": "Front Selfie",
      "resolutions": [
        { "width": 1280, "height": 720, "max_fps": 60 },
        { "width": 1920, "height": 1080, "max_fps": 60 }
      ],
      "max_zoom": 2.0,
      "ev_range": [-2.0, 2.0],
      "has_flash": false
    }
  ],
  "active_state": {
    "camera_id": "0",
    "width": 1920,
    "height": 1080,
    "fps": 60,
    "bitrate_kbps": 12000,
    "zoom_ratio": 1.0,
    "exposure_ev": 0.0,
    "autofocus": true,
    "torch_on": false,
    "mirror_horizontal": false,
    "rotation_deg": 0,
    "audio_enabled": true,
    "audio_muted": false
  }
}
```

### 5.2 `CONTROL_CMD` (`0x11`) — Dikirim PC ke Android untuk Mengubah Pengaturan
Menggunakan representasi *tagged enum* (`"cmd"`):

```json
{
  "cmd": "ConfigureStream",
  "camera_id": "0",
  "width": 1920,
  "height": 1080,
  "fps": 60,
  "bitrate_kbps": 15000
}
```

Daftar lengkap varian `"cmd"` yang wajib didukung:
1. **`ConfigureStream`**: Mengubah `camera_id`, `width`, `height`, `fps`, `bitrate_kbps`. (Memicu re-inisialisasi `MediaCodec` dan pengiriman paket `VIDEO_CONFIG (0x01)` baru).
2. **`SetCameraParams`**: Mengubah parameter instan tanpa merestart encoder:
   ```json
   {
     "cmd": "SetCameraParams",
     "zoom_ratio": 1.5,
     "exposure_ev": 0.5,
     "autofocus": true,
     "torch_on": false,
     "mirror_horizontal": false,
     "rotation_deg": 0
   }
   ```
3. **`SetAudioState`**:
   ```json
   {
     "cmd": "SetAudioState",
     "enabled": true,
     "muted": false
   }
   ```
4. **`RequestKeyframe`**:
   ```json
   {
     "cmd": "RequestKeyframe"
   }
   ```
   *(Saat diterima Android, panggil `mediaCodec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })` agar decoder PC yang baru terhubung langsung mendapat IDR frame).*

### 5.3 `CONTROL_ACK` (`0x12`) — Balasan Status dari Android
```json
{
  "ok": true,
  "error": null,
  "active_state": {
    "camera_id": "0",
    "width": 1920,
    "height": 1080,
    "fps": 60,
    "bitrate_kbps": 15000,
    "zoom_ratio": 1.5,
    "exposure_ev": 0.5,
    "autofocus": true,
    "torch_on": false,
    "mirror_horizontal": false,
    "rotation_deg": 0,
    "audio_enabled": true,
    "audio_muted": false
  }
}
```
