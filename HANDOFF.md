# HANDOFF untuk AI di PC

Kode sudah selesai ditulis dan **sudah lolos cek kompilasi Kotlin 1.9.24 terhadap android.jar API 34** (dengan stub `R`) di sisi penulis. Logika SQL migrasi dan aturan minus/reset sudah diuji di SQLite. **Belum pernah dijalankan di HP/emulator.** Tugasmu: build, perbaiki kalau ada error, uji di perangkat, laporkan.

## ATURAN KERAS
1. **Jangan refactor, jangan ganti arsitektur, jangan tambah library**, jangan ubah versi Gradle/Kotlin/AGP.
2. Jangan hapus kolom `name` di tabel `counters`.
3. Jangan mengubah perilaku yang tertulis di README.md. Kalau tes gagal, perbaiki seminimal mungkin pada file terkait.
4. Perubahan hanya boleh: (a) memperbaiki error kompilasi, (b) memperbaiki bug yang ditemukan checklist di bawah.
5. Setiap perbaikan: catat file + baris + alasan (satu kalimat). Jangan menulis ulang file utuh.

## Langkah
1. `./gradlew assembleDebug` (JDK 17). Kalau gagal kompilasi, perbaiki seminimal mungkin.
2. Pasang APK ke HP. Beri izin "Tampil di atas aplikasi lain" dan notifikasi.
3. Jalankan checklist.
4. Laporkan hasil per nomor (LULUS/GAGAL + deskripsi singkat + screenshot/log untuk yang GAGAL).

## Peta file
| File | Peran |
|---|---|
| `Db.kt` | DB v2, migrasi, aturan data (change/reset/minus), query statistik |
| `Util.kt` | `dp`, `Palette`, `Days` (waktu), `Fmt` (format tanggal), dialog warna, teks reset |
| `MainActivity.kt` | Daftar kartu (urut tanggal), menu, bubble toggle |
| `CounterFormActivity.kt` | Form baru/ubah: wheel tanggal + bulan, catatan, Start value, Read Only, warna |
| `StatsActivity.kt` | Filter Shopee-style, ringkasan, rentang, pemilih tanggal/custom/bulan |
| `LineChartView.kt` | Grafik garis + area + tooltip sentuh |
| `BubbleService.kt` | Bubble, tombol minus/reset (tahan), target X (tutup), dialog reset overlay |

## Aturan data (jangan diubah)
- 1 counter = 1 tanggal (`day` = epoch-day). Tanggal unik (dicek di kode, bukan UNIQUE index).
- `events` hanya berisi +1. Tombol **minus menghapus event terbaru** (ORDER BY ts DESC, id DESC) dan nilai −1 (min 0). Nilai boleh > jumlah event karena Start value.
- Tambah di counter hari ini = jam sekarang; tanggal lain = 23:00 tanggal itu.
- Reset = hapus semua event counter itu + nilai kembali ke `start_value`. Read Only memblokir change dan reset di level `Db`.
- Grafik harian = nilai counter per tanggal; grafik jam = event counter tanggal itu.
- 1 hari = per jam, 2+ hari = per hari. 7/30 hari mulai KEMARIN.

## Checklist uji
**Form & daftar**
1. Tombol + → form "Counter baru": dua wheel tanggal | bulan, default hari ini; label tanggal lengkap muncul di bawah wheel.
2. Pilih bulan Februari: wheel tanggal maksimal 29. Pilih 31 lalu ganti ke April: otomatis jadi 30.
3. Pilih tanggal yang sudah punya counter: tulisan merah muncul, ikon simpan redup dan tidak bisa disimpan.
4. Simpan dengan catatan "ok" dan Start value 10: kartu berjudul "27 Okt · ok" bernilai 10, daftar menggulir ke kartu itu.
5. Tahan kartu → Ubah: form terisi data lama, tahun asli tanggal lama tidak berubah kalau wheel tidak diubah. Ubah Start value 10 → 12: nilai kartu ikut naik 2.
6. Read Only aktif: +, −, ↶ di kartu redup dan menampilkan toast, nilai tidak berubah.
7. Hapus kartu: ada konfirmasi.

**Data**
8. Counter hari ini: tekan + tiga kali, lalu − sekali: nilai turun 1 dan grafik jam hari ini ikut turun 1 (total grafik = nilai − Start value).
9. Counter tanggal lama: tekan +: grafik jam tanggal itu menunjukkan titik di 23:00.
10. ↶ di kartu: dialog konfirmasi berisi nilai + jumlah catatan; setelah "Reset" nilai kembali ke Start value dan grafik jam kosong.

**Statistik**
11. Ikon grafik di kartu → langsung ke tanggal kartu itu, grafik per jam, tooltip muncul saat grafik disentuh.
12. Hari ini | 7 Hari | 30 Hari: tombol aktif bergaris oranye; 7/30 hari berakhir KEMARIN; ‹ › menggeser 7/30 hari.
13. Lainnya ▾: Kemarin, Pilih tanggal, Custom (dua dialog: Dari lalu Sampai), Pilih bulan (grid 12 bulan + tahun, bulan depan redup).
14. Custom dengan dari = sampai: otomatis jadi grafik per jam. Custom > 92 hari: dipotong dan ada toast.
15. Pilih bulan: grafik harian 28-31 titik; hari yang belum terjadi tidak digambar (garis berhenti).
16. Label sumbu X tidak bertumpuk di 30 hari dan per jam.

**Bubble**
17. Ketuk bubble = +1 (getar). Angka di kartu ikut berubah.
18. Tahan bubble sampai getar: tombol − (atas) dan ↶ (bawah) muncul di sisi yang lapang (kiri kalau bubble di tepi kanan). **Bubble tidak boleh loncat posisi.** Tahan lagi: tombol hilang, bubble tetap di tempatnya.
19. Saat tombol tampil, ketuk −: nilai −1. Ketuk ↶: dialog konfirmasi overlay muncul di atas aplikasi lain, "Reset" menghapus data tanggal itu.
20. Geser bubble: X muncul di bawah tengah; saat bubble di atas X, X membesar dan merah (getar kecil); lepas di sana = bubble tertutup dan ikon di kartu mati. Lepas di tempat lain = posisi tersimpan.
21. Geser saat tombol tampil: tombol otomatis menutup di awal geseran.
22. Tahan lalu langsung geser dengan sentuhan yang sama: tidak terjadi geser (hanya toggle tombol).
23. Counter Read Only: tombol terbuka tapi redup; ketuk menampilkan toast, tidak mengubah data.
24. Aktifkan bubble di counter hari ini, lalu ubah tanggal HP ke hari berikutnya (atau tunggu lewat 00:00): muncul notifikasi "Sudah ganti hari" tanpa suara/pop-up, getar dua kali singkat, dengan tombol "Tetap di ..." dan "Ganti ke ...".
25. Pilih "Tetap": toast tampil; ketuk bubble = +1 pada counter hari lama dan grafik jam hari lama menunjukkan titik di 23:00. Notifikasi tidak muncul lagi untuk bubble itu.
26. Pilih "Ganti": bubble langsung berubah ke counter hari baru (dibuat otomatis kalau belum ada, bernilai 0), kartu di daftar utama ikut muncul dan ikon bubble pindah ke kartu itu.
27. Bubble dibuka untuk counter tanggal lama (bukan hari ini): notifikasi pergantian hari TIDAK boleh muncul.

## Titik rawan (periksa dulu kalau tes gagal)
- **Koordinat overlay (no. 18-22):** kalau bubble loncat saat tombol muncul/hilang, periksa `setExpanded` di `BubbleService.kt` (selisih `vOff` dan `s + g` pada `lp.x/lp.y`). Hit-test X memakai `getLocationOnScreen`, jangan diganti ke koordinat window.
- **Dialog reset dari bubble (no. 19):** memakai `AlertDialog` dengan `window.setType(TYPE_APPLICATION_OVERLAY)` dan tema `Theme_DeviceDefault_Light_Dialog_Alert`. Kalau tidak muncul/crash `BadTokenException`, periksa izin overlay dan tipe window. Jangan diganti dengan Activity (sering diblokir HyperOS/MIUI saat di latar belakang).
- **Pergantian hari (no. 24-27):** deteksi memakai broadcast `ACTION_DATE_CHANGED` (didaftarkan di `onCreate` BubbleService) plus pemeriksaan cadangan saat bubble diketuk (`checkRollover`). Kalau notifikasi tidak muncul tepat 00:00 tapi muncul saat ketukan pertama, berarti broadcast tertunda oleh sistem hemat baterai: itu perilaku yang dapat diterima, jangan diganti dengan alarm/timer.
- **Migrasi DB:** kalau crash "no such column", berarti `onUpgrade` tidak jalan: pastikan versi DB = 2 dan app lama (v1) memang terpasang; kalau perlu uninstall lalu pasang ulang (data lama hilang).
- **Wheel tanggal:** `NumberPicker.setDisplayedValues` harus dipanggil setelah min/max diset (sudah begitu).
