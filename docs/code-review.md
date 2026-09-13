<aside>
📌

Tinjauan awal ini berdasarkan inspeksi statis seluruh source utama Kotlin/C++, konfigurasi
Android, manifest, database, dan test. Pada saat review awal, paket ZIP belum menyertakan
skrip `gradlew` dan `gradle-wrapper.jar`, sementara Gradle tidak tersedia di lingkungan
pemeriksaan. Temuan di bawah menjadi backlog implementasi; status aktual setelah pengerjaan
dicatat pada bagian berikut.

Implementasi review berikutnya memulihkan Gradle Wrapper, menambah CI, dan memverifikasi
test unit, lint, serta build debug menggunakan toolchain yang didokumentasikan.

</aside>

## Status implementasi review

Temuan correctness dan performa utama sudah diterapkan:

- Konversi memakai generation token, pemeriksaan cancellation, dan rollback row/file.
- Status Telegram dipisah menjadi `READY`, `SHARE_OPENED`, dan `CONFIRMED_SENT`; konfirmasi tetap manual.
- Decoder PCM mendukung 16-bit dan float secara eksplisit serta menolak encoding yang belum didukung.
- Waveform ditunda, diagregasikan ke bucket tetap, dan dikodekan sebagai JSON bounded.
- History memakai Room SQL, Paging 3, query agregat, index, dan debounce pencarian.
- Migrasi destruktif dihapus, migrasi v4 memetakan schema lama, dan schema Room diekspor.
- Flush pitch shifter, batas JNI, fade trim, progress relatif trim, intent MIME, URI cache, dan `SavedStateHandle` diperbaiki.
- Gradle Wrapper, CI, toolchain Android, release shrinking, namespace, dependency injection, dan pembersihan `RecentContact` diterapkan.
- Batch memakai WorkManager queue dengan retry per item; konversi tunggal berjalan sebagai foreground worker.
- Output memiliki nama editable yang disanitasi, cache input tahan process death, dan cleanup berkala.
- Normalization, silence trim, A/B preview, serta validasi header OGG Opus dan parameter Telegram tersedia sebagai state UI.

Verifikasi lokal terakhir menjalankan `:app:testDebugUnitTest lintDebug assembleDebug assembleRelease`
dengan Gradle Wrapper `9.3.1` dan JDK `21`; seluruh task berhasil.

Validasi yang masih memerlukan perangkat atau fixture media nyata belum diklaim selesai:
matriks codec AAC/MP3/Opus/WAV/M4A/MP4, `ffprobe`, instrumentation lintas API/ABI,
uji cancellation dan foreground notification/WorkManager pada perangkat, serta
profiling file panjang.

## Ringkasan eksekutif

Fondasi aplikasi sudah cukup baik untuk proyek personal: arsitektur sederhana, operasi berat umumnya ditempatkan di coroutine IO, akses storage Android 10+ memakai MediaStore, encoder berjalan streaming, resource MediaCodec dibersihkan, dan UI menggunakan state yang lifecycle-aware. Review awal menemukan risiko correctness yang kini sudah ditangani pada source; validasi terhadap perangkat, codec nyata, dan profiling tetap menjadi pekerjaan sebelum production release.

**Prioritas tertinggi:**

1. Cegah file/riwayat yatim ketika konversi dibatalkan atau input diganti.
2. Jangan menandai voice note sebagai “terkirim” hanya karena Telegram dibuka.
3. Validasi format PCM keluaran decoder; saat ini kode selalu menganggap PCM 16-bit.
4. Pulihkan build yang reproducible dengan Gradle Wrapper lengkap dan CI.
5. Hindari decode audio dua kali serta pemuatan seluruh riwayat ke memori.

## Temuan prioritas

| Prioritas | Temuan | Dampak | Lokasi utama |
| --- | --- | --- | --- |
| P0 | Konversi yang dibatalkan dapat meninggalkan file publik dan row riwayat | Data yatim, hasil lama muncul walau pengguna sudah memilih file baru | `MainViewModel.kt:175–231` |
| P0 | Status “terkirim” dicatat setelah share intent diluncurkan, bukan setelah pengiriman | Riwayat tidak akurat jika pengguna menutup chooser/Telegram | `MainViewModel.kt:236–245`, `HistoryViewModel.kt:96–105` |
| P1 | Output decoder selalu dibaca sebagai PCM 16-bit | Audio bisa rusak atau analisis waveform salah pada decoder yang menghasilkan float/encoding lain | `VoiceNoteConverter.kt:467–483, 685–715` |
| P1 | Gradle Wrapper tidak lengkap | Build/test/lint tidak reproducible di mesin baru atau CI | root proyek dan `gradle/wrapper` |
| P1 | File dianalisis penuh, lalu didecode penuh lagi saat konversi | Waktu tunggu, CPU, baterai, dan panas hampir dua kali untuk file panjang | `MainViewModel.kt:75–76`, `VoiceNoteConverter.kt` |
| P1 | Seluruh history diambil, difilter, diurutkan, dan diagregasi di memori | Melambat seiring jumlah riwayat; query Room dapat dijalankan beberapa kali | `ConversionHistoryDao.kt:10–11`, `HistoryViewModel.kt:38–60` |
| P1 | Migrasi database boleh menghancurkan seluruh data | Riwayat bisa hilang jika jalur migrasi terlewat | `AppDatabase.kt:26–27` |
| P2 | Flush pitch shifter membuang output dari tahap pemberian zero-padding | Ekor audio berpotensi terpotong/bergeser atau berdurasi tidak tepat | `pitch_shifter_jni.cpp:148–166` |
| P2 | Exception native tidak dijaga pada batas JNI | Exception C++ dapat mengakhiri proses aplikasi | `pitch_shifter_jni.cpp` |
| P2 | Progress konversi memakai timestamp absolut sumber | Setelah trim dari tengah, progress langsung melonjak; UX menyesatkan | `VoiceNoteConverter.kt:728–733` |
| P2 | Beberapa alur intent di kode tidak sesuai manifest | Cabang `ACTION_VIEW` dan `application/*` praktis tidak terdaftar sebagai entry point | `MainActivity.kt:50–65`, `AndroidManifest.xml` |
| P2 | Waveform disimpan sebagai hasil `List.toString()` | Format rapuh, boros, dan butuh parsing manual | `MainViewModel.kt:200`, `HistoryScreen.kt:548–550` |

## Detail bug dan cara memperbaikinya

### 1. P0 — Resource yatim pada cancellation atau pergantian input

Alur saat ini menyimpan hasil ke MediaStore dan memasukkan row history sebelum memeriksa apakah coroutine masih aktif dan input masih sama. Jika job dibatalkan setelah penyimpanan, atau input berubah sebelum pemeriksaan terakhir, fungsi keluar tetapi file publik dan row database tetap ada.

**Perbaikan:**

- Simpan `savedUri` dan `historyId` sebagai resource sementara di scope konversi.
- Jalankan `ensureActive()` sebelum dan sesudah setiap side effect.
- Pada `CancellationException` atau hasil yang sudah stale, kompensasikan secara berurutan: hapus row history, lalu hapus file MediaStore.
- Gunakan token/generation ID per pemilihan file, bukan hanya membandingkan URI.
- Bungkus commit hasil sebagai operasi eksplisit: convert cache → save publik → insert DB → publish ke UI. Bila tahap apa pun gagal, rollback tahap yang sudah selesai.

Contoh arah implementasi:

```kotlin
val generation = conversionGeneration.incrementAndGet()
var savedUri: Uri? = null
var historyId: Long? = null
try {
    val result = convert(...)
    ensureActive()
    check(generation == conversionGeneration.get())
    savedUri = storage.save(...)
    ensureActive()
    historyId = history.insert(...)
    ensureActive()
    check(generation == conversionGeneration.get())
    publishResult(...)
} catch (cancelled: CancellationException) {
    historyId?.let { history.delete(it) }
    savedUri?.let { storage.delete(it.toString()) }
    throw cancelled
} catch (error: Throwable) {
    historyId?.let { history.delete(it) }
    savedUri?.let { storage.delete(it.toString()) }
    throw error
}
```

### 2. P0 — Status pengiriman merupakan false positive

Android share intent hanya memberi tahu bahwa aplikasi tujuan dibuka. Tidak ada konfirmasi bahwa pesan benar-benar dikirim. Saat ini row langsung diberi `sentTo = Telegram` setelah `startActivity()` berhasil.

**Perbaikan yang jujur terhadap batas platform:**

- Ganti state menjadi `OPENED_IN_TELEGRAM` atau `SHARE_STARTED`, bukan `SENT`.
- Setelah pengguna kembali ke aplikasi, tampilkan aksi “Tandai sudah dikirim”.
- Ubah field database menjadi enum/status: `READY`, `SHARE_OPENED`, `CONFIRMED_SENT`.
- Jangan menyebut “terkirim” pada UI tanpa konfirmasi pengguna.

### 3. P1 — Asumsi PCM 16-bit

Kedua pipeline membaca `ByteBuffer` dengan `asShortBuffer()`. MediaCodec dapat mengeluarkan PCM float atau encoding lain dan menginformasikannya melalui `MediaFormat.KEY_PCM_ENCODING`.

**Perbaikan:**

- Setelah `INFO_OUTPUT_FORMAT_CHANGED`, baca dan simpan `KEY_PCM_ENCODING`.
- Buat adapter PCM untuk `ENCODING_PCM_16BIT` dan `ENCODING_PCM_FLOAT`; tolak encoding yang belum didukung dengan pesan jelas.
- Uji mono/stereo, 44,1 kHz/48 kHz, AAC/MP3/Opus, PCM float, serta file video tanpa audio.

### 4. P1 — Build tidak reproducible

ZIP memiliki `gradle-wrapper.properties`, tetapi tidak memiliki `gradlew`, `gradlew.bat`, atau `gradle-wrapper.jar`. Akibatnya test dan lint tidak bisa dijalankan hanya dari source yang dikirim.

**Perbaikan:**

- Regenerasi dan commit Gradle Wrapper lengkap: `gradle wrapper --gradle-version 9.3.1`.
- Tambahkan CI untuk `testDebugUnitTest`, `lintDebug`, `assembleDebug`, dan build native seluruh ABI.
- Tambahkan README untuk JDK/SDK/NDK/CMake yang persis digunakan.
- Jangan memaksa release signing saat secret tidak tersedia; aktifkan signing release hanya bila semua environment variable dan keystore tersedia.

### 5. P1 — Decode audio dilakukan dua kali

Pemilihan file memanggil `getMediaInfo()` lalu `extractWaveform()`. Saat konversi, seluruh media didecode kembali dan waveform hasil kembali dihitung.

**Perbaikan bertahap:**

- Cepat: tampilkan metadata dulu dan tunda waveform sampai pengguna menekan play/edit/convert.
- Lebih baik: gunakan satu pipeline analisis dengan sampling jarang dan cache hasil berdasarkan URI + ukuran + last-modified.
- Terbaik: saat konversi, hasilkan waveform final secara streaming; waveform awal cukup berupa placeholder atau preview resolusi rendah.
- Jangan simpan semua peak; agregasikan langsung ke 100–200 bucket tetap.

### 6. P1 — History tidak scalable

DAO mengembalikan seluruh row, kemudian tiga flow terpisah menghitung list, jumlah, dan total ukuran. Pencarian dan sorting berjalan di Kotlin untuk setiap perubahan query.

**Perbaikan:**

- Gunakan Room Paging 3 untuk daftar.
- Pindahkan filter/sort ke SQL dengan query terparametrisasi.
- Tambahkan query agregat tunggal untuk `COUNT(*)` dan `SUM(fileSizeBytes)`.
- Tambahkan index pada `createdAt`, `sentAt`, dan bila diperlukan kolom nama yang dinormalisasi.
- Debounce pencarian sekitar 200–300 ms.

### 7. P1 — Risiko kehilangan database

`fallbackToDestructiveMigration(dropAllTables = true)` menghapus data bila migrasi lengkap tidak tersedia.

**Perbaikan:**

- Hapus destructive fallback pada build produksi.
- Aktifkan `exportSchema = true`, simpan schema JSON ke repository.
- Tambahkan `MigrationTestHelper` untuk jalur 1→2→3 dan upgrade langsung dari setiap versi lama.
- Jika proyek personal memang menerima reset, batasi destructive fallback ke build debug saja dan jelaskan konsekuensinya.

### 8. P2 — Finalisasi pitch shifter

Pada native `flush()`, hasil dari pemrosesan zero-padding tidak dikembalikan; hanya output dari panggilan `stretch.flush()` berikutnya yang dipakai. Ini perlu diverifikasi terhadap kontrak Signalsmith Stretch karena dapat membuang sampel valid.

**Perbaikan:**

- Tampung output dari kedua tahap dan gabungkan sesuai latency yang dilaporkan library.
- Tambahkan test end-to-end dengan impuls, sinus, dan durasi pendek; bandingkan jumlah frame input/output dan posisi puncak.
- Tambahkan fade pendek di batas trim untuk mencegah click.

### 9. P2 — Progress trim tidak proporsional

Progress memakai `presentationTimeUs / durationUs`. Jika trim dimulai di 70% durasi, progress langsung sekitar 67,5% setelah bobot pipeline diterapkan.

**Perbaikan:** hitung terhadap jendela efektif:

```kotlin
val effectiveStartUs = trimStartMs * 1_000L
val effectiveEndUs = minOf(durationUs, trimEndUs * 1_000L)
val fraction = ((ptsUs - effectiveStartUs).toDouble() /
    (effectiveEndUs - effectiveStartUs).coerceAtLeast(1L)).toFloat()
```

### 10. P2 — Intent, URI, dan lifecycle

- Manifest mendaftarkan `ACTION_SEND` hanya untuk audio/video, tetapi kode menerima `application/*` dan `ACTION_VIEW`.
- URI dari share tidak selalu persistable; kegagalan `takePersistableUriPermission` diabaikan.
- State input dan proses tidak dipulihkan setelah process death.

**Perbaikan:**

- Selaraskan manifest dengan behavior yang benar-benar didukung; hapus cabang mati atau tambahkan intent filter yang ketat.
- Validasi MIME dari `ContentResolver.getType()`, bukan hanya MIME intent yang bisa salah.
- Jika izin URI tidak persistable dan proses perlu bertahan lama, salin input ke cache privat lebih awal.
- Simpan state ringan di `SavedStateHandle`; jangan mencoba memulihkan job MediaCodec yang sudah mati.

## Kualitas kode dan maintainability

### Hal yang sudah baik

- Pemisahan audio, storage, data, Telegram, dan UI cukup jelas.
- Resource decoder/encoder/extractor umumnya dibersihkan di `finally`.
- Cancellation diperiksa selama loop panjang.
- MediaStore dipakai untuk Android modern; izin legacy dibatasi sampai API 28.
- Daftar history memakai `LazyColumn` dengan key stabil.
- State Compose dikoleksi dengan `collectAsStateWithLifecycle()`.
- Ada test untuk Ogg page, Opus pre-skip, trim window, pitch state, Room, Telegram intents, dan beberapa UI.

### Kekurangan struktur

- `VoiceNoteConverter.kt` (850 baris) dan `HistoryScreen.kt` (917 baris) terlalu besar. Pecah menjadi extractor/decoder, PCM converter, Opus encoder, Ogg muxer, conversion coordinator, serta komponen UI per section.
- Dependency ViewModel sekarang memakai constructor injection sederhana; pemecahan file besar masih belum selesai.
- `RecentContact` dan DAO yang tidak dipakai sudah dihapus dari schema aktif dan source.
- Namespace kini diselaraskan menjadi `com.aistudio.voicenote.cvtr`.
- Jalur error utama memiliki pesan UI stabil dan log teknis; beberapa fallback low-level tetap sengaja bersifat best-effort.
- Release build sudah memakai R8/resource shrinking dengan aturan Room/JNI eksplisit; verifikasi ukuran per ABI masih tersisa.

## Rencana perbaikan yang direkomendasikan

### Fase 1 — Stabilitas data dan correctness (1–2 hari)

- [x]  Tambahkan rollback konversi dan generation token.
- [x]  Ubah status “terkirim” menjadi “dibuka di Telegram” + konfirmasi manual.
- [x]  Validasi PCM encoding dan format output decoder.
- [x]  Hapus destructive migration dari release.
- [x]  Lengkapi Gradle Wrapper dan jalankan seluruh test/lint.

**Definition of done:** mengganti input atau menutup layar saat konversi tidak meninggalkan file/row; pembatalan share tidak tercatat sebagai sent; fixture PCM float tidak menghasilkan audio rusak.

### Fase 2 — Test dan kompatibilitas perangkat (2–4 hari)

- [ ]  Test instrumentasi pipeline nyata dengan fixture pendek AAC, MP3, Opus, WAV, M4A, dan MP4 tanpa audio.
- [ ]  Verifikasi output dengan `ffprobe`: Ogg Opus, mono, 48 kHz, durasi dan granule benar.
- [ ]  Test API 24, 28, 29, 33, dan 36 serta ABI arm64-v8a, armeabi-v7a, x86_64.
- [ ]  Tambahkan test cancellation di setiap batas side effect.
- [x]  Tambahkan migration tests dan test korupsi URI/file hilang.

### Fase 3 — Performa (2–3 hari)

- [x]  Tunda atau cache waveform analysis.
- [x]  Agregasikan waveform ke bucket tetap secara streaming.
- [x]  Pindahkan history ke SQL + Paging 3 + aggregate query.
- [x]  Turunkan refresh playback dari 16 ms menjadi sekitar 50–100 ms; waveform tidak membutuhkan 60 update/detik.
- [ ]  Profil dengan Android Studio CPU/Memory profiler pada file 5, 30, dan 120 menit.

### Fase 4 — Refactor dan release hygiene (2–4 hari)

- [ ]  Pecah file converter dan layar besar.
- [x]  Inject repository/storage/sender melalui constructor.
- [x]  Selaraskan package/namespace.
- [x]  Bersihkan dependency dan kode `RecentContact` yang tidak dipakai.
- [x]  Aktifkan R8/resource shrinking pada release dan periksa ukuran per ABI.
- [ ]  Tambahkan changelog, privacy note lokal, dan prosedur backup/restore riwayat.

## Rekomendasi fitur

Scope implementasi ini mencakup batch conversion, background conversion,
rename sebelum simpan, pembersihan cache/hasil lama, audio normalization dan
silence trim, A/B preview, serta validasi kompatibilitas Telegram. Preset,
share ke aplikasi lain, dan export/import sengaja dikecualikan sesuai permintaan.

### Bernilai tinggi untuk penggunaan personal

1. **Batch conversion** — `[x]` pilih beberapa audio/video, antrekan konversi, serta retry per item.
2. **Preset** — `[—]` dikecualikan dari scope implementasi ini.
3. **Konversi di background** — `[x]` WorkManager + foreground notification untuk file panjang.
4. **Share ke aplikasi lain** — `[—]` dikecualikan dari scope implementasi ini.
5. **Rename sebelum simpan** — `[x]` nama hasil dapat diedit dan disanitasi sebelum commit.
6. **Pembersihan otomatis** — `[x]` cache yatim dibersihkan berkala; hasil lama dapat dihapus dari History.
7. **Export/import riwayat** — `[—]` dikecualikan dari scope implementasi ini.
8. **Audio normalization dan silence trim** — `[x]` opsional dan diterapkan sebelum hasil disimpan.
9. **A/B preview** — `[x]` original dan hasil dapat diputar dari posisi relatif yang sama.
10. **Validasi kompatibilitas Telegram** — `[x]` ringkasan OGG Opus, channel, sample rate, bitrate, durasi, dan warning.

### Urutan implementasi fitur

Fondasi rollback/cancellation selesai sebelum batch dan background conversion.
Fitur yang dikecualikan tidak menambah shim atau jalur parsial.

## Strategi pengujian minimum

| Area | Skenario wajib |
| --- | --- |
| Input | content URI sementara, OpenDocument URI persistable, file hilang, MIME salah, video tanpa audio |
| Format | AAC/MP3/Opus/WAV, mono/stereo, 44,1/48 kHz, PCM 16-bit/float |
| Trim | awal, tengah, mendekati akhir, 100 ms minimum, file sangat pendek |
| Pitch | -4, 0, +4 semitone; durasi dan frame count tetap masuk akal |
| Cancellation | saat decode, setelah save MediaStore, setelah insert DB, ketika input diganti |
| Storage | API 24–28 permission denied, API 29+ insert/update/delete gagal |
| Share | Telegram tidak ada, satu klien, banyak klien, chooser dibatalkan |
| Database | migrasi tiap versi, file hilang tetapi row ada, delete massal parsial |
| Native | ABI berbeda, library gagal dimuat, input sangat pendek, flush latency |

## Target performa yang realistis

- Pemilihan file menampilkan metadata awal dalam <1 detik tanpa menunggu decode waveform penuh.
- Memori tambahan tetap mendekati konstan terhadap durasi file; tidak menyimpan seluruh PCM atau peak tak terbatas.
- Progress monoton dari 0–100% dan merepresentasikan jendela trim.
- History pertama tampil cepat meski berisi ribuan row karena paging dan index.
- Tidak ada file cache/publik yatim setelah cancel, crash yang dipulihkan, atau ganti input.

## Kesimpulan

Implementasi telah menutup temuan correctness, data, performa, native, dan build yang dapat diverifikasi di source maupun JVM/build environment. Sebelum release production, tetap jalankan fixture media nyata, `ffprobe`, instrumentation lintas API/ABI, dan profiling file panjang; area tersebut memang tidak dapat dibuktikan hanya dengan unit test dan build lokal.