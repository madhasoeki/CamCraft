# CamCraft — Brand & Logo Guidelines

> Panduan ringkas penggunaan identitas visual **CamCraft** (ekosistem **ArtCraft Studio v2**).

---

## 1. Konsep & Varian Resmi

- **Ide Inti (*Miter Frame*):** Dua siku *viewfinder* sinema berpotongan tepat `45°` (*miter joinery*) yang saling mengunci di sekeliling inti lensa optik *LIVE* — melambangkan presisi perangkat keras (*Craft*) dan jembatan latensi ultra-rendah antara Android dan Linux Desktop.
- **Varian Bentuk:**
  - **Symbol Only (`camcraft-symbol-*`):** Ikon aplikasi Android/Fedora, avatar profil, penanda bilah header GUI.
  - **Horizontal Lockup (`camcraft-horizontal-*`):** Lockup utama untuk dokumentasi, header situs, rilis, dan materi presentasi.
  - **Stacked Lockup (`camcraft-stacked-*`):** Format persegi/vertikal untuk kartu fitur, stiker, dan media sosial.
  - **Wordmark Only (`camcraft-wordmark-*`):** Saat simbol sudah hadir di dekatnya atau pada ruang horisontal sangat sempit.
  - **Small-Size Optical Cut (`camcraft-symbol-small*`):** Gunakan khusus ukuran `16–24 px` (ikon notifikasi status bar, favicon, tray kecil) agar celah diagonal `45°` tetap terbuka.
- **Lokasi File:**
  - `assets/branding/masters/` — Master editable `.vectorcraft` & `.pdf` (teks sudah di-outline).
  - `assets/branding/svg/` — Aset vektor produksi bersih (full color light/dark, small-size, thinned white, black, brand-blue mono, README light/dark banner).
  - `assets/branding/png/` — Raster transparan siap pakai (`16`–`512 px` app icon, lockup `1200 px` / `800 px`, banner `1280 px`).
  - `assets/branding/web/` — Set lengkap web & PWA (`favicon.ico`, `favicon.svg`, `apple-touch-icon.png`, `icon-192.png`, `icon-512.png`, `maskable-512.png`, `site.webmanifest`, `head-snippet.html`).
  - `assets/branding/linux/` — File `.desktop` Freedesktop (`org.artcraft.camcraft.desktop`) untuk Linux desktop environment.

---

## 2. Ruang Kosong (*Clear Space*)

Pertahankan area bebas di keempat sisi logo minimal **`1 × D`** (dengan `D` = diameter inti lensa hijau di tengah simbol; pada kanvas simbol `256×256`, `D = 68 px`, atau `0.38 ×` tinggi simbol). Jangan letakkan teks, badge, atau tepi bingkai masuk ke zona ini.

---

## 3. Ukuran Minimum

| Varian | Layar (*Digital*) | Cetak (*Print*) |
| :--- | :--- | :--- |
| **Horizontal Lockup** | Lebar min. `120 px` | Lebar min. `32 mm` |
| **Stacked Lockup** | Lebar min. `96 px` | Lebar min. `25 mm` |
| **Symbol (Standard)** | `>= 28 px` | `>= 8 mm` |
| **Symbol (Small Cut)** | `16–24 px` (`camcraft-symbol-small*.svg`) | `6 mm` |

---

## 4. Palet Warna Resmi (*ArtCraft Studio v2*)

| Nama Warna | HEX | RGB | CMYK (perkiraan) | Pantone (perkiraan) | Peran |
| :--- | :--- | :--- | :--- | :--- | :--- |
| **Studio Carbon** | `#0D0F14` | `13, 15, 20` | `35, 25, 0, 92` | Black 6 C | Siku kiri-atas & wordmark (latar terang); latar tile ikon OLED |
| **Crisp Studio White** | `#F8FAFC` | `248, 250, 252` | `2, 1, 0, 1` | — | Siku kiri-atas & wordmark (latar gelap) |
| **Electric Studio Blue** | `#3B82F6` | `59, 130, 246` | `76, 47, 0, 4` | 2727 C | Siku kanan-bawah (aksen jembatan & versi monokrom merek) |
| **Signal Emerald** | `#10B981` | `16, 185, 129` | `91, 0, 30, 27` | 3395 C | Inti lensa optik (*LIVE tally signal*) |
| **Studio Bezel** | `#252B37` | `37, 43, 55` | `33, 22, 0, 78` | 432 C | Garis bingkai tipis (`1–3 px`) pada kartu/ikon |

**Pasangan Latar yang Disetujui:**
- **Latar Terang (`#FFFFFF` / `#F8FAFC`):** Gunakan varian `*-color.svg`, `*-black.svg`, atau `*-mono-3b82f6.svg`.
- **Latar Gelap (`#0D0F14` / `#161A22`):** Gunakan varian `*-color-dark.svg` atau versi putih terkompensasi iradiasi (`camcraft-symbol-white.svg`, ketebalan dinding ditipiskan ~8% agar tidak terlihat menggembung di latar hitam).
- **Latar Foto / Ramai:** Gunakan `camcraft-symbol-app-icon.svg` (simbol di dalam tile `#0D0F14`) atau versi monokrom kontras tinggi.

---

## 5. Tipografi

- **Logo Wordmark:** Diubah dari **Montserrat SemiBold** (tracking optik `-18` sampai `-20` per 1000 em) dan **seluruhnya telah dikonversi menjadi kurva vektor (*outlined paths*)** pada seluruh SVG & PDF master sehingga tidak bergantung pada font terpasang.
- **Lisensi Font:** Montserrat dirilis di bawah **SIL Open Font License (OFL 1.1)** — aman untuk penggunaan komersial maupun proyek open-source.
- **UI & Dokumentasi:** Gunakan **Inter / Adwaita Sans / Cantarell** untuk antarmuka pengguna dan **JetBrains Mono / Noto Sans Mono** dengan angka tabular untuk telemetri real-time (`FPS`, `Mbps`, `ms`, `dB`).

---

## 6. Larangan Penggunaan (*Don'ts*)

- Jangan meregangkan, memipihkan, atau memutar logo secara bebas (sudut miter `45°` sudah terkunci).
- Jangan mengganti warna elemen di luar palet resmi atau menambahkan *drop shadow*, *outer glow*, bevel, maupun gradien.
- Jangan mengubah proporsi atau jarak antara simbol dan wordmark pada lockup resmi.
- Jangan mengetik ulang kata `CamCraft` dengan font acak sebagai pengganti wordmark vektor.
- Jangan memakai varian latar terang (`#0D0F14`) langsung di atas latar gelap tanpa beralih ke varian `-dark`.

---

## 7. Catatan Hukum & Pemeriksaan Merek

Pembuatan aset menggunakan metrik desain bebas font dan bebas plagiasi dari pustaka referensi. Namun sebelum pendaftaran merek dagang komersial atau publikasi ke Google Play / Flathub, disarankan tetap melakukan penelusuran kesamaan merek (*trademark search* dan *reverse image search*) di yurisdiksi target.
