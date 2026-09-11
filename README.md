# Voice Note Converter

Android app untuk mengubah audio atau video menjadi voice note OGG Opus mono 32 kbps,
memotong bagian yang diperlukan, mempreview hasil, menyimpan riwayat, dan membuka hasil
di salah satu klien Telegram yang terpasang.

Hasil konversi disimpan di folder publik `Music/VoiceNoteConverter`. Pada Android 10+
aplikasi memakai MediaStore; Android 7–9 meminta izin penyimpanan saat konversi pertama.

## Run Locally

Prasyarat: [Android Studio](https://developer.android.com/studio) dengan Android SDK 36
dan JDK 11 atau yang kompatibel dengan versi Android Gradle Plugin proyek.


1. Buka direktori proyek di Android Studio.
2. Tunggu sinkronisasi Gradle selesai.
3. Jalankan varian `debug` pada emulator atau perangkat Android 7.0+.

Tidak ada API key, Firebase, TDLib, atau login Telegram yang diperlukan. Pengiriman
menggunakan Android share intent; pengguna tetap memilih chat dan menyelesaikan pengiriman
di aplikasi Telegram.
