# Floating Counter + Jam

Tracker harian: **satu counter = satu tanggal**. Setiap tambahan dicatat jamnya, jadi bisa dilihat grafik per jam, per hari, 7/30 hari, atau per bulan. Counter bisa melayang sebagai bubble di atas aplikasi lain.

## Cara pakai

### Layar utama
- **Tombol + bulat** (kanan bawah): counter baru. Pilih tanggal dengan dua wheel (tanggal | bulan), tahun otomatis (tanggal terbaru yang tidak di masa depan). Catatan, Start value, Read Only, dan warna opsional. Satu tanggal hanya boleh punya satu counter.
- **+ / −** di kartu: tambah / kurangi. **−** menghapus catatan jam terbaru, jadi grafik selalu cocok dengan angka.
- **↶** di kartu: reset tanggal itu (nilai kembali ke Start value, semua catatan jam hilang), selalu ada jendela konfirmasi.
- **Tahan kartu**: Ubah atau Hapus.
- **Ikon grafik** di kartu: statistik tanggal kartu itu. Menu ⋮ → Statistik: 7 hari terakhir.
- **Read Only**: tombol +, −, ↶ (kartu dan bubble) terkunci.
- Tambah di counter yang bukan hari ini dicatat jam **23:00** pada tanggal counter itu.

### Bubble melayang
- **Ketuk** = +1.
- **Tahan** (±0,7 detik) = muncul tombol **−** dan **↶ (reset)** di samping bubble. **Tahan lagi** = tombol hilang.
- **Geser** = pindahkan (posisi diingat). Saat digeser muncul **X** di bawah layar; lepas di atas X = bubble ditutup.
- **Lewat tengah malam:** kalau bubble sedang menampilkan counter hari ini lalu tanggal berganti, muncul notifikasi tanpa suara (status bar + getar singkat) dengan dua pilihan: **Tetap di <tanggal lama>** (klik berikutnya dicatat jam 23:00 tanggal itu) atau **Ganti ke <tanggal baru>** (counter hari baru dibuat otomatis kalau belum ada, dan bubble langsung pindah). Tanpa memilih = tetap.
- Tutup juga bisa lewat ikon bubble di kartu atau tombol "Tutup" di notifikasi.
- Pertama kali, Android meminta izin **Tampil di atas aplikasi lain**. Aktifkan, lalu ketuk ikon bubble lagi.

### Statistik
- Filter: **Hari ini | 7 Hari | 30 Hari | Lainnya ▾** (Kemarin, Pilih tanggal, Custom tanggal, Pilih bulan).
- Aturan sumbu: rentang **1 hari = grafik per jam**, **2 hari atau lebih = grafik per hari**.
- **7 / 30 Hari** dihitung mulai **kemarin** (hari ini tidak ikut). Custom maksimal 92 hari.
- ‹ › menggeser periode; ketuk label tanggal untuk memilih ulang. Sentuh grafik untuk melihat nilai titik.
- Ringkasan: Total (+ persen dibanding periode sebelumnya), Rata-rata per hari aktif, Puncak.

## Build APK

### Opsi A — Android Studio
1. Buka folder ini di Android Studio, tunggu Gradle sync selesai.
2. Menu **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
3. APK ada di `app/build/outputs/apk/debug/app-debug.apk`.

### Opsi B — GitHub Actions
1. Upload seluruh isi folder ini ke branch `main`.
2. Tab **Actions → Build APK** (otomatis, atau **Run workflow**).
3. Unduh `floating-counter-apk` dari **Artifacts**.

### Opsi C — baris perintah
```
./gradlew assembleDebug
```

## Catatan teknis

- Android 8.0 (API 26) ke atas. Tanpa library tambahan.
- Data lokal (SQLite, versi 2). Tabel `counters` (kolom `day` = epoch-day, `note`, `start_value`, `read_only`; kolom `name` berisi teks tanggal, jangan dihapus) dan `events` (hanya +1 per catatan jam).
- Migrasi v1 → v2 otomatis: counter lama diberi tanggal dari catatan pertamanya (atau hari ini; kalau bentrok mundur sehari), nama lama jadi catatan.
- Angka harian = nilai counter; grafik jam = event milik counter tanggal itu. Selisih (Start value) ditampilkan sebagai "tanpa data jam".
