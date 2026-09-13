<aside>
🚨

**Kesimpulan utama**

Konversi tunggal dan batch melewati jalur eksekusi yang sama: `ConversionWorker → VoiceNoteConverter.convertToTelegramVoiceNote()`. Titik kegagalan bersama yang paling kuat adalah ketergantungan mutlak pada encoder Opus milik perangkat melalui `MediaCodec`. Aplikasi tidak membawa encoder Opus sendiri dan tidak memiliki fallback. Pada perangkat/ROM yang tidak mengekspos encoder `audio/opus`, semua pekerjaan pasti berhenti sebelum decoding dimulai—baik tunggal maupun batch.

</aside>

## Status implementasi

Perbaikan source telah diterapkan:

- **CVT-01/CVT-02:** `libopus` 1.5.2 dibundel melalui NDK dan menjadi backend utama. `MediaCodec` Opus hanya menjadi fallback bila inisialisasi software gagal.
- **CVT-03:** item batch dibentuk sebagai satu unique WorkManager chain. `ConversionCoordinator` juga memberi batas global satu engine aktif untuk konversi tunggal dan batch.
- **CVT-04:** foreground worker memakai `mediaProcessing` pada API 35+; API 29–34 memakai `dataSync`, sedangkan API 24–28 memakai `ForegroundInfo` tanpa type flag.
- **CVT-05:** owned input dihapus pada sukses, gagal, pembatalan ketika sedang berjalan, dan pembatalan ketika menunggu mutex.
- **CVT-06:** ditambahkan instrumentation smoke test yang menjalankan PCM melalui bundled libopus lalu memvalidasi output Ogg Opus dengan `MediaExtractor`. Matriks codec/API/ABI lengkap tetap memerlukan device farm dan fixture.
- **CVT-07:** worker sekarang mengeluarkan `errorCode`, `stage`, pesan aman yang dapat ditindaklanjuti, backend encoder, serta log teknis API/ABI dan cause.
- Verifikasi host berhasil: `:app:testDebugUnitTest`, `:app:lintDebug`, `:app:assembleDebug`, `:app:assembleRelease`, dan `:app:compileDebugAndroidTestKotlin`. Layout batch juga dirender melalui Roborazzi dan diperiksa secara visual; smoke artifact sementara telah dihapus.

Perbaikan UI batch juga diterapkan: antrean dapat ditutup per item atau sekaligus, state UI lama dibersihkan pada process start baru, daftar dibatasi empat item terlihat agar tidak memanjangkan section tanpa batas, dan retry hanya ditampilkan untuk kegagalan storage yang transient.


## Ruang lingkup pemeriksaan

- Source: repository GitHub dan ZIP source yang dilampirkan.
- Metode: inspeksi statis alur UI, WorkManager, worker, decoder, encoder, Ogg writer, JNI pitch shifter, storage, manifest, serta test yang tersedia.
- Build/test lokal setelah patch dijalankan dengan Gradle Wrapper 9.3.1 dan JDK Android Studio.
- Tidak ada device/emulator terhubung, sehingga instrumentation, inspeksi codec aktual, `logcat`, dan pemutaran Telegram nyata belum dapat dijalankan. Hasil implementasi dan batas verifikasi perangkat dibedakan secara eksplisit.

## Ringkasan temuan

| ID | Prioritas | Temuan | Dampak |
| --- | --- | --- | --- |
| CVT-01 | P0 | Encoder Opus perangkat menjadi hard dependency tanpa fallback | Semua konversi gagal pada perangkat yang tidak menyediakan encoder Opus |
| CVT-02 | P0 | Jalur tunggal dan batch sama-sama masuk ke worker/engine yang sama | Satu kegagalan engine mematikan kedua mode sekaligus |
| CVT-03 | P1 | Batch tidak benar-benar sequential; setiap file dijadwalkan sebagai unique work independen | Beberapa codec berjalan paralel, meningkatkan contention dan kegagalan resource |
| CVT-04 | P1 | Foreground worker memakai tipe `dataSync`, bukan `mediaProcessing` | Salah klasifikasi pada Android modern dan berisiko dibatasi oleh kebijakan OS |
| CVT-05 | P1 | Input cache hanya dihapus ketika worker sukses | Retry/kegagalan berulang meninggalkan file privat dan dapat memenuhi storage |
| CVT-06 | P1 | Test tidak menjalankan konversi end-to-end dengan MediaCodec nyata | Build/test dapat hijau sementara konversi di perangkat tetap rusak |
| CVT-07 | P2 | Pesan error worker terlalu mentah dan observabilitas codec minim | Sulit membedakan codec hilang, FGS gagal, decoder gagal, atau storage gagal |

## Alur kegagalan bersama

1. Tombol konversi tunggal memanggil `MainViewModel.startConversion()`.
2. Input disalin ke private files directory, lalu dibuat `OneTimeWorkRequest&lt;ConversionWorker&gt;`.
3. Batch melakukan hal yang sama untuk setiap URI melalui `MainViewModel.enqueueBatch()`.
4. Kedua mode akhirnya memanggil `ConversionWorker.doWork()`.
5. Worker memanggil `VoiceNoteConverter.convertToTelegramVoiceNote()`.
6. Converter mencari encoder Opus dengan `MediaCodecList(REGULAR_CODECS)`.
7. Bila tidak ditemukan, converter melempar error dan worker mengembalikan `Result.failure`.

Lokasi penting:

- Tunggal: `app/src/main/java/com/aistudio/voicenote/cvtr/ui/MainViewModel.kt:426–477`
- Batch: `app/src/main/java/com/aistudio/voicenote/cvtr/ui/MainViewModel.kt:646–690`
- Worker bersama: `app/src/main/java/com/aistudio/voicenote/cvtr/work/ConversionWorker.kt:36–75`
- Pencarian encoder: `app/src/main/java/com/aistudio/voicenote/cvtr/audio/VoiceNoteConverter.kt:623–630, 868–879`

---

## CVT-01 — P0: ketergantungan mutlak pada encoder Opus perangkat

### Bukti pada source

Converter menjalankan:

```kotlin
val opusCodecName = findOpusEncoder()
    ?: run {
        extractor.release()
        decoder.release()
        throw UnsupportedAudioFormatException(
            "Perangkat ini tidak memiliki encoder Opus. Konversi tidak dapat dilakukan."
        )
    }
```

`findOpusEncoder()` hanya menelusuri `MediaCodecList.REGULAR_CODECS` dan mengembalikan codec yang mengiklankan `MediaFormat.MIMETYPE_AUDIO_OPUS`. Tidak ada libopus, FFmpeg, atau encoder software lain sebagai fallback.

### Mengapa ini menjelaskan kegagalan tunggal dan batch

Kedua mode menggunakan fungsi converter yang sama. Batch memang mengirim `pitchSemitones = 0f`, tetapi itu tidak melewati pencarian encoder. Jika daftar codec perangkat tidak memiliki encoder Opus, setiap item worker akan gagal di lokasi yang identik sebelum output dibuat.

Android mendukung decoding Opus pada banyak versi/perangkat, tetapi keberadaan *encoder* Opus yang dapat dipakai aplikasi tidak boleh diasumsikan seragam di seluruh OEM/ROM. Keberhasilan build juga tidak membuktikan codec tersedia saat runtime.

### Arah perbaikan wajib

**Solusi yang direkomendasikan: bundle encoder software yang deterministik.**

1. Tambahkan `libopus` melalui NDK atau gunakan FFmpeg build minimal yang hanya membawa komponen yang diperlukan.
2. Pisahkan kontrak encoder dari implementasi:

```kotlin
interface OpusEncoder {
    fun encode(samples: ShortArray)
    fun finish()
    fun close()
}
```

1. Jadikan encoder software sebagai jalur utama agar hasil konsisten lintas perangkat. `MediaCodec` boleh dipertahankan sebagai akselerasi opsional setelah capability probe berhasil.
2. Bila masih memakai `MediaCodec`, lakukan probe nyata: buat codec, `configure()`, `start()`, encode fixture PCM pendek, lalu validasi packet. Jangan hanya percaya `supportedTypes`.
3. Bila codec perangkat gagal saat konstruksi atau encoding, fallback otomatis ke software encoder—bukan langsung menggagalkan pekerjaan.
4. Catat nama codec terpilih, ABI, API level, tahap pipeline, dan exception class ke log teknis tanpa mengekspos data pengguna.

### Patch minimum sementara

Jika integrasi libopus belum dapat dilakukan, lakukan preflight sebelum enqueue dan tampilkan pesan yang eksplisit. Ini tidak memperbaiki kompatibilitas, tetapi mencegah UI terlihat seolah konversi sedang berjalan lalu gagal tanpa arah.

```kotlin
sealed interface EncoderAvailability {
    data class Available(val codecName: String) : EncoderAvailability
    data object Missing : EncoderAvailability
    data class Broken(val cause: Throwable) : EncoderAvailability
}
```

<aside>
⚠️

Preflight saja bukan perbaikan final. Selama output bergantung pada encoder OEM, aplikasi tetap tidak dapat menjamin fitur utamanya.

</aside>

## CVT-02 — P0: satu engine failure mematikan dua mode

### Sumber penyebab

`ConversionWorker` adalah satu-satunya executor untuk request tunggal maupun batch. Tidak ada perbedaan engine, fallback, atau kebijakan retry berdasarkan jenis kegagalan. Semua `Throwable` diubah menjadi `Result.failure` pada `ConversionWorker.kt:124–129`.

### Arah perbaikan

- Tambahkan error code terstruktur: `OPUS_ENCODER_UNAVAILABLE`, `OPUS_CONFIGURE_FAILED`, `DECODER_UNAVAILABLE`, `FOREGROUND_START_FAILED`, `INPUT_UNREADABLE`, dan `STORAGE_WRITE_FAILED`.
- Terapkan fallback encoder di dalam engine sehingga tunggal dan batch sama-sama mendapat perbaikan.
- Retry hanya untuk error transient. Codec tidak tersedia adalah permanent failure dan tidak boleh diulang tanpa mengganti backend.

## CVT-03 — P1: batch dijalankan paralel, bukan sequential

### Bukti pada source

Loop batch membuat satu unique work per item:

```kotlin
manager.enqueueUniqueWork(
    ConversionWork.batchTag(batchItemId),
    ExistingWorkPolicy.REPLACE,
    request
)
```

Karena nama unique work berbeda untuk setiap item dan tidak ada `beginUniqueWork().then(...)` atau queue tunggal, WorkManager bebas menjalankan beberapa item secara paralel. Ini bertentangan dengan tujuan “antrean sequential”.

### Dampak

- Beberapa instance decoder dan encoder dapat aktif bersamaan.
- Codec OEM sering memiliki batas instance rendah; instance kedua/ketiga dapat gagal di `configure()` atau `start()`.
- CPU, RAM, I/O, dan foreground notification saling berebut.
- Semua worker menggunakan notification ID `1001`, sehingga notifikasi batch saling menimpa.

### Arah perbaikan

- Gunakan satu unique chain untuk batch:

```kotlin
var continuation = workManager.beginUniqueWork(
    BATCH_CHAIN_NAME,
    ExistingWorkPolicy.APPEND_OR_REPLACE,
    firstRequest
)
continuation = continuation.then(nextRequest)
continuation.enqueue()
```

- Alternatif yang lebih bersih: satu `BatchConversionWorker` membaca daftar item dan memproses satu per satu, dengan checkpoint per item.
- Batasi concurrency engine ke satu menggunakan coordinator/mutex lintas worker bila single dan batch dapat berjalan bersamaan.
- Gunakan notification ID unik per work atau satu summary notification untuk batch.

## CVT-04 — P1: foreground service salah klasifikasi

### Bukti pada source

- `ConversionWorker.createForegroundInfo()` memakai `ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC` pada API 29+.
- Manifest mendeklarasikan `FOREGROUND_SERVICE_DATA_SYNC` dan `SystemForegroundService` bertipe `dataSync`.
- Pekerjaan sebenarnya adalah transcoding/pemrosesan media.

### Dampak

Pada Android modern, foreground service semakin ketat dan tipe service harus mencerminkan pekerjaan. Salah tipe dapat menimbulkan pembatasan, kegagalan start pada kondisi tertentu, atau masalah kepatuhan saat distribusi.

### Arah perbaikan

- Untuk target/API yang mendukung, gunakan `mediaProcessing` dan permission `android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING`.
- Sediakan cabang kompatibilitas untuk API lama.
- Tangkap dan klasifikasikan kegagalan `setForeground()` agar UI tidak berhenti pada status generik.
- Uji start dari foreground, setelah aplikasi masuk background, dan setelah process recreation.

## CVT-05 — P1: file input persisten bocor saat gagal

### Bukti pada source

`MediaInputCache.copyToPersistent()` menyimpan input di `filesDir/conversion_inputs`. Pada `ConversionWorker.kt:130–133`, file input hanya dihapus bila `completed == true`.

```kotlin
finally {
    result?.outputFile?.delete()
    if (completed) MediaInputCache.delete(applicationContext, inputUri)
}
```

### Dampak

Jika codec tidak tersedia, setiap percobaan tunggal atau batch meninggalkan salinan penuh media. Retry menggandakan konsumsi storage. Setelah storage menipis, constraint `setRequiresStorageNotLow(true)` dapat membuat work berikutnya tertahan, sehingga gejala berubah dari “failed” menjadi “tidak berjalan”.

### Arah perbaikan

- Hapus input ketika worker mencapai terminal state sukses, gagal permanen, atau cancel dan tidak akan diretry.
- Jika retry otomatis diperlukan, simpan ownership/reference count yang jelas dan hapus pada terminal attempt terakhir.
- Jalankan cleanup saat startup dan periodic maintenance dengan batas umur lebih pendek.
- Tampilkan ukuran cache conversion input pada diagnostics.

## CVT-06 — P1: test tidak membuktikan konversi nyata

### Bukti pada source

Test yang ada memeriksa fungsi PCM, Ogg writer, API reflection, keberadaan source native, work tags/options, storage, database, serta UI. Tidak ditemukan test yang menjalankan alur berikut secara utuh:

`fixture media → MediaExtractor → decoder MediaCodec → encoder Opus → Ogg → ffprobe`

`NativePitchShifterSourceTest` hanya memastikan file C++ ada. `PitchConversionApiTest` hanya memeriksa parameter fungsi melalui reflection. `ConversionWorkTest` hanya memeriksa request/tag dan decoding option.

### Arah perbaikan

Tambahkan instrumentation test pada perangkat/emulator untuk:

- AAC/M4A, MP3, WAV, Opus, dan MP4 dengan track audio.
- Video tanpa audio dan input rusak.
- Mono/stereo; 44,1 kHz/48 kHz; PCM 16-bit/float.
- Durasi sangat pendek dan file panjang.
- Pitch -4, 0, +4.
- Single dan batch tiga file.
- Perangkat tanpa encoder Opus atau fake backend yang mensimulasikan `configure()` gagal.

Validasi output dengan `ffprobe`: container Ogg, codec Opus, mono, 48 kHz, durasi masuk akal, packet dapat didecode, dan tidak ada truncation.

## CVT-07 — P2: observabilitas belum cukup

### Masalah

Worker meneruskan `error.message` sebagai satu string. Tidak ada stage marker, codec list, codec terpilih, API/ABI, atau cause chain. Untuk batch, UI hanya menampilkan pesan per work yang berasal dari output worker; bila work tidak sempat masuk `doWork()`, informasi dapat sangat minim.

### Arah perbaikan

- Tambahkan `ConversionStage`: `COPY_INPUT`, `START_FOREGROUND`, `OPEN_EXTRACTOR`, `CREATE_DECODER`, `CREATE_ENCODER`, `DECODE`, `ENCODE`, `WRITE_OGG`, `SAVE_MEDIASTORE`, `INSERT_HISTORY`.
- Simpan `errorCode`, `stage`, dan pesan aman di `outputData`.
- Log stack trace lengkap di debug build.
- Tambahkan layar diagnostics yang menampilkan daftar encoder `audio/opus` dan hasil probe tanpa memuat data pengguna.

---

## Urutan implementasi yang disarankan

1. **P0 — Ganti hard dependency MediaCodec Opus:** integrasikan libopus/software encoder dan fallback otomatis.
2. **P0 — Tambahkan capability probe + error code:** buktikan backend encoder sebelum pekerjaan panjang dimulai.
3. **P1 — Serialisasi batch:** satu chain/worker dan batas satu engine aktif.
4. **P1 — Koreksi foreground service:** gunakan tipe media processing pada API yang mendukung.
5. **P1 — Perbaiki lifecycle input cache:** cleanup pada semua terminal state.
6. **P1 — Tambahkan E2E instrumentation:** fixture nyata, matriks API/ABI, dan validasi `ffprobe`.
7. **P2 — Tingkatkan diagnostics:** stage, error code, codec name, API, ABI, dan cause.

## Rancangan arsitektur perbaikan

```
ConversionCoordinator
├── InputRepository
│   ├── copyToOwnedInput()
│   └── releaseOwnedInput()
├── AudioDecoder (MediaCodec)
├── OpusEncoder
│   ├── SoftwareOpusEncoder (default/reliable)
│   └── MediaCodecOpusEncoder (optional acceleration)
├── OggMuxer
├── OutputRepository (MediaStore)
└── ConversionDiagnostics
```

Prinsip penting:

- Backend encoder dipilih melalui probe, bukan asumsi.
- Satu konversi aktif per engine pada satu waktu.
- Semua resource memiliki owner dan cleanup terminal.
- Error bersifat terstruktur dan menunjukkan tahap kegagalan.
- Worker hanya mengorkestrasi; detail codec berada di komponen yang dapat diuji.

## Kriteria penerimaan

- [ ] Single conversion berhasil pada device matrix dengan dan tanpa encoder Opus MediaCodec. Backend software sudah dibundel; eksekusi instrumentation pada device belum tersedia di lingkungan ini.
- [x] Batch disusun sebagai chain dan engine dikunci sehingga tiga file tidak dapat diproses paralel.
- [x] `libopus` menjadi backend utama; kegagalan encoder OEM tidak lagi mematikan konversi.
- [ ] Output lolos `ffprobe` dan dapat diputar di Telegram pada fixture/device nyata.
- [x] Owned input dibersihkan setelah sukses, gagal permanen, cancel saat aktif, dan cancel saat menunggu giliran.
- [x] Error UI membawa tahap, kode stabil, serta tindakan yang dapat dilakukan pengguna.
- [ ] Instrumentation lulus pada API 24, 28, 29, 33, 35/36 dan ABI arm64-v8a, armeabi-v7a, x86_64. Smoke test libopus sudah tersedia dan berhasil dikompilasi.
- [ ] Pengujian fixture mencakup AAC/M4A, MP3, WAV, Opus, MP4, input rusak, dan video tanpa audio.

<aside>
✅

**Diagnosis setelah patch:** hard dependency encoder OEM telah dihapus melalui bundled `libopus`; serialisasi engine, tipe foreground service, cleanup terminal, error terstruktur, dan lifecycle UI batch juga telah ditutup. Validasi perangkat lintas API/ABI, matriks fixture, `ffprobe`, dan pemutaran Telegram nyata tetap menjadi pekerjaan verifikasi perangkat—bukan kekurangan jalur implementasi utama.

</aside>