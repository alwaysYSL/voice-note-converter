# Voice Note Converter

Android app untuk mengubah audio atau video menjadi voice note OGG Opus mono 32 kbps,
memotong bagian yang diperlukan, mempreview hasil, menyimpan riwayat, dan membuka hasil
di salah satu klien Telegram yang terpasang.

Hasil konversi disimpan di folder publik `Music/VoiceNoteConverter`. Pada Android 10+
aplikasi memakai MediaStore; Android 7–9 meminta izin penyimpanan saat konversi pertama.

## Run Locally

Toolchain yang dikunci oleh proyek:

- Android SDK Platform `36.1`
- Android SDK Build-Tools `36.1.0`
- NDK `28.2.13676358`
- CMake `3.22.1`
- JDK `21`
- Gradle `9.3.1` melalui Gradle Wrapper

Android Studio tetap direkomendasikan karena menyediakan JDK dan SDK Manager.
Proyek membangun native library untuk ABI `arm64-v8a`, `armeabi-v7a`, dan `x86_64`.

1. Buka direktori proyek di Android Studio.
2. Pasang komponen SDK/NDK/CMake di atas melalui SDK Manager.
3. Tunggu sinkronisasi Gradle selesai.
4. Jalankan varian `debug` pada emulator atau perangkat Android 7.0+.

Perintah CI yang sama dapat dijalankan dari Windows:

```bat
gradlew.bat :app:testDebugUnitTest lintDebug assembleDebug
```

Untuk build release bertanda tangan, set environment variable `KEYSTORE_PATH`,
`STORE_PASSWORD`, `KEY_ALIAS`, dan `KEY_PASSWORD`. Signing hanya diaktifkan jika
keempat nilai terisi dan file keystore ada; tanpa itu `assembleRelease` tetap
dapat berjalan sebagai build unsigned.

Hasil konversi disimpan di folder publik `Music/VoiceNoteConverter`. Pada Android 10+
aplikasi memakai MediaStore; Android 7–9 meminta izin penyimpanan saat konversi pertama.
Input `content://` yang tidak dapat dipersistenkan disalin sementara ke cache privat.

Pengiriman ke Telegram hanya mencatat bahwa share intent berhasil dibuka. Riwayat baru
berstatus **Dikonfirmasi terkirim** setelah pengguna menekan aksi konfirmasi. Database,
cache, dan file hasil tidak dicadangkan otomatis; gunakan salinan manual bila perangkat
akan diganti.

Tidak ada API key, Firebase, TDLib, atau login Telegram yang diperlukan. Pengiriman
menggunakan Android share intent; pengguna tetap memilih chat dan menyelesaikan pengiriman
di aplikasi Telegram.
