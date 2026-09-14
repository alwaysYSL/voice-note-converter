# Voice Note Converter — Post-Conversion Audio Editor Design

> **Date:** 2026-09-14  
> **Status:** Approved in brainstorming — awaiting written-spec review  
> **Scope:** Editor audio non-destruktif untuk hasil konversi OGG, maksimal lima track dan lima menit, dengan draft opsional serta ekspor OGG Opus.

## 1. Ringkasan

Voice Note Converter mempertahankan alur konversi cepat yang sudah ada. Setelah konversi selesai, pengguna dapat langsung membagikan hasil atau memilih **Edit lebih lanjut** untuk membuka editor timeline. Editor juga dapat dibuka dari aksi **Edit** pada halaman Riwayat.

Editor berfokus pada voice note berdurasi pendek. Pengguna dapat menyusun maksimal lima track, memilih satu klip pada satu waktu, melakukan edit non-destruktif, mempreview hasil campuran, lalu mengekspor OGG baru tanpa mengubah file hasil konversi sebelumnya.

## 2. Tujuan

- Menjaga alur konversi sederhana bagi pengguna yang tidak membutuhkan editing.
- Menyediakan editor audio mobile dengan timeline multi-track yang mudah dipahami.
- Mendukung trim, split, delete, fade, volume, normalize, noise reduction, pitch, speed, undo, dan redo.
- Menjaga preview dan hasil ekspor konsisten dengan memakai renderer audio yang sama.
- Menjaga penggunaan RAM aman melalui decoding dan rendering berbasis chunk.
- Menghasilkan OGG Opus mono yang siap dibagikan sebagai voice note.
- Menyediakan penyimpanan draft hanya ketika diminta secara eksplisit.

## 3. Batas Scope

### Termasuk

- Maksimal lima track per sesi.
- Maksimal durasi timeline lima menit.
- Satu klip aktif pada satu waktu.
- Impor track tambahan dari file audio di perangkat.
- Perpindahan posisi klip secara horizontal.
- Mute dan volume per track.
- Ekspor OGG Opus mono 32 atau 64 kbps.
- Penyimpanan dan pembukaan kembali draft secara eksplisit.

### Tidak termasuk pada versi pertama

- Perekaman audio dari dalam editor.
- Multi-select klip.
- Stereo mixing atau pan kiri/kanan.
- Automation curve untuk volume atau efek.
- Plugin efek eksternal.
- Crossfade otomatis antarklip.
- Penghapusan rentang waktu secara global pada semua track.
- Proyek lebih dari lima menit atau lebih dari lima track.

## 4. User Flow

### 4.1 Jalur utama

```text
Pilih file
  → preview dan pengaturan cepat
  → konversi OGG
  → layar hasil
      ├─ Putar
      ├─ Bagikan
      └─ Edit lebih lanjut
           → editor timeline
           → ekspor OGG baru
           → hasil baru masuk Riwayat
```

Editor tidak menjadi layar awal aplikasi. Pengguna yang hanya membutuhkan konversi dan pembagian cepat tidak perlu berinteraksi dengan timeline.

### 4.2 Titik masuk lain

- Aksi **Edit** pada hasil yang tersimpan di Riwayat membuka hasil tersebut sebagai track utama.
- Bagian **Draft editor** pada Riwayat hanya ditampilkan jika setidaknya ada satu draft.
- Membuka draft memulihkan seluruh track, klip, posisi, parameter efek, dan pilihan preset ekspor.

### 4.3 Keluar dari editor

- Jika belum ada perubahan, tombol kembali menutup editor tanpa dialog.
- Jika ada perubahan, tampilkan pilihan **Simpan sebagai draft**, **Buang sesi**, dan **Batal**.
- Sesi yang dibuang membersihkan manifest sementara dan cache khusus sesi.
- Sesi yang tidak pernah disimpan sebagai draft tidak dijanjikan pulih setelah proses aplikasi dimatikan.

## 5. Keputusan Arsitektur

Gunakan **custom timeline dan streaming PCM engine** yang memperluas pipeline native aplikasi saat ini.

Media3 tetap digunakan untuk ekstraksi dan decoding input. Timeline, mixing, rendering, cache efek, undo/redo, dan ekspor dikendalikan oleh aplikasi. Pendekatan ini dipilih karena:

- pipeline MediaCodec, Signalsmith Stretch, Opus encoder, dan Ogg writer sudah tersedia;
- editor memerlukan kontrol presisi atas gap, posisi klip, fade, gain, dan efek native;
- `CompositionPlayer` Media3 masih berstatus experimental;
- preview dan ekspor perlu menggunakan interpretasi timeline yang sama;
- penggunaan FFmpeg akan menambah ukuran distribusi, kompleksitas ABI, dan kewajiban lisensi.

Referensi teknologi:

- [Media3 Composition](https://developer.android.com/media/media3/transformer/composition)
- [Media3 audio transformations](https://developer.android.com/media/media3/transformer/transformations)
- [Media3 supported formats and OggMuxer](https://developer.android.com/media/media3/transformer/supported-formats)
- [RNNoise](https://github.com/xiph/rnnoise)
- [FFmpeg licensing considerations](https://ffmpeg.org/legal.html)

## 6. Komponen Sistem

```text
EditorScreen / EditorViewModel
            │
            ▼
       EditorSession
  tracks · clips · selection
  playhead · effects · dirty state
       │        │        │
       │        │        └── DraftRepository
       │        └─────────── CommandHistory
       └──────────────────── TimelineRenderer
                                  │
                  ┌───────────────┴───────────────┐
                  ▼                               ▼
            PreviewEngine                    ExportWorker
          rolling PCM buffer           Opus encoder + Ogg writer
                  │                               │
              AudioTrack                    MediaStore + History
```

### 6.1 Pembagian modul

```text
editor/
├── model/
│   ├── EditorSession
│   ├── EditorTrack
│   ├── AudioClip
│   ├── ClipEffects
│   └── ExportPreset
├── commands/
│   ├── EditorCommand
│   ├── CommandHistory
│   └── command implementations
├── audio/
│   ├── TimelineRenderer
│   ├── PreviewEngine
│   ├── TrackMixer
│   ├── MasterLimiter
│   ├── WaveformGenerator
│   └── effects/
├── cache/
│   ├── EditorCacheManager
│   └── ProcessedAudioCache
├── data/
│   ├── DraftRepository
│   ├── DraftEntities
│   └── DraftSourceStorage
└── ui/
    ├── EditorScreen
    ├── EditorViewModel
    ├── TimelineCanvas
    ├── TrackHeader
    ├── EditorToolbar
    └── tool sheets
```

Setiap bagian memiliki batas tanggung jawab yang jelas. Model editor tidak bergantung pada Compose atau Android media APIs sehingga operasi timeline dapat diuji sebagai unit Kotlin biasa.

## 7. Model Data

### 7.1 EditorSession

Menyimpan:

- identifier sesi;
- daftar track berurutan;
- identifier klip aktif;
- posisi playhead;
- zoom dan offset viewport;
- preset ekspor yang dipilih;
- status perubahan yang belum disimpan;
- referensi draft jika sesi berasal dari draft.

### 7.2 EditorTrack

Menyimpan:

- identifier dan nama track;
- urutan vertikal;
- volume linear 0–150%;
- status mute;
- daftar klip yang tidak saling overlap dalam track yang sama.

Overlap di antara track berbeda diperbolehkan dan akan dicampur. Overlap klip dalam satu track ditolak pada versi pertama; saat drag berakhir, klip ditempatkan pada posisi valid terdekat.

### 7.3 AudioClip

Menyimpan:

- referensi sumber audio;
- waktu awal dan akhir pada sumber;
- posisi awal pada timeline;
- fade in dan fade out;
- gain klip;
- pitch dalam semitone;
- speed ratio;
- referensi cache normalize dan noise reduction jika tersedia.

Durasi timeline sebuah klip dihitung dari rentang sumber setelah trim dibagi speed ratio. Pitch tidak mengubah durasi.

### 7.4 Sumber audio

Track utama lebih dahulu menggunakan URI sumber asli dan pengaturan konversinya jika sumber masih dapat diakses. Jika sumber asli tidak tersedia, editor menggunakan OGG hasil konversi sebagai sumber. Track tambahan menggunakan URI hasil file picker dan mempertahankan izin URI selama sesi.

Saat draft disimpan, setiap sumber disalin ke penyimpanan privat aplikasi. Draft tidak bergantung pada lokasi atau izin file asli setelah proses simpan berhasil.

## 8. Perilaku Timeline dan Tools

| Tool | Target | Perilaku |
|---|---|---|
| Trim | Klip | Handle mengubah batas sumber tanpa memodifikasi file asli. |
| Split | Klip | Playhead membagi klip menjadi dua; efek diwariskan oleh kedua klip. Fade pada sisi hasil split di-reset ke nol. |
| Delete | Klip | Default ripple hanya pada track aktif. Opsi tambahan menghapus klip dan mempertahankan gap. |
| Move | Klip | Drag horizontal; snap ringan ke playhead, tepi klip, dan grid waktu. |
| Fade | Klip | Fade in/out linear antara 0–5 detik, dibatasi maksimal setengah durasi klip per sisi. |
| Pitch | Klip | −4 hingga +4 semitone; durasi dipertahankan. |
| Speed | Klip | 0,5×–2×; pitch dipertahankan dan durasi timeline berubah. |
| Normalize | Klip atau track | Analisis dua tahap lalu membuat cache hasil dengan peak target −1 dBFS. |
| Noise reduction | Klip atau track | Preset Ringan, Sedang, atau Kuat; diproses ke cache menggunakan RNNoise. |
| Volume | Track | 0–150%, dengan mute terpisah. |
| Undo/redo | Sesi | Maksimal 50 command mutasi timeline. |

### 8.1 Ripple delete

Ripple delete menutup ruang kosong hanya pada track yang sedang diedit. Track lain tidak bergeser agar sinkronisasi musik dan efek yang sudah dibuat tidak berubah. Penghapusan waktu pada seluruh track tidak termasuk versi pertama.

### 8.2 Normalize dan noise reduction

Kedua operasi menggunakan alur **Terapkan lalu preview**:

1. Pengguna memilih klip atau track dan parameter.
2. Aplikasi menganalisis dan memproses audio ke file cache baru.
3. Cache baru menggantikan sumber preview hanya setelah proses selesai dan tervalidasi.
4. Jika proses gagal atau dibatalkan, cache baru dihapus dan preview lama tetap aktif.
5. Perubahan dapat di-undo; cache boleh dipakai kembali selama belum dieviktasi.

## 9. Audio Pipeline

### 9.1 Rantai per klip

```text
MediaExtractor / MediaCodec
  → PCM mono 48 kHz 16-bit
  → source-range mapping dan trim
  → RNNoise cache jika aktif
  → normalize cache jika aktif
  → speed processing
  → pitch processing
  → clip gain dan fade envelope
  → track gain / mute
```

Signalsmith Stretch yang sudah ada digunakan untuk pitch dan time stretching dengan pitch preservation. RNNoise ditambahkan sebagai library native BSD-3-Clause untuk pengurangan noise pada suara. Cache pembersihan dibuat sebelum pitch dan speed sehingga perubahan kedua parameter tersebut tidak memaksa RNNoise berjalan ulang. Perubahan normalize atau noise reduction tetap menginvalidasi cache yang bergantung padanya.

### 9.2 Mixing dan master

Semua track aktif dirender ke accumulator PCM float untuk rentang waktu chunk yang sama. Setelah dijumlahkan, master limiter mencegah clipping sebelum hasil dikonversi kembali menjadi PCM 16-bit.

Versi pertama menghasilkan mono karena target utamanya adalah voice note. Input stereo di-downmix secara konsisten dengan pipeline konversi yang sudah ada.

### 9.3 Preview

- `TimelineRenderer` menghasilkan rolling PCM buffer beberapa detik di depan playhead.
- `PreviewEngine` mengirim PCM ke Android `AudioTrack`.
- Seek membatalkan buffer yang belum dimainkan dan memulai render dari posisi baru.
- Perubahan ringan seperti gain, fade, mute, posisi, pitch, dan speed memicu invalidasi buffer dari titik yang terpengaruh.
- Normalize dan noise reduction menggunakan cache hasil tombol **Terapkan**.
- UI menerima posisi playback dari jumlah frame yang benar-benar diserahkan ke output, bukan dari timer UI bebas.

### 9.4 Penggunaan memori

Audio penuh tidak dimuat ke RAM. Renderer memakai chunk 20–40 ms, rolling preview buffer terbatas, dan accumulator per chunk. Waveform disimpan sebagai peak data ringkas. Ini menghindari kebutuhan sekitar 140 MB yang dapat timbul jika lima track mono PCM 48 kHz berdurasi lima menit dimuat sekaligus.

## 10. Undo dan Redo

Gunakan command pattern. Setiap mutasi menyimpan data minimal yang diperlukan untuk `execute()` dan `undo()`.

Command mencakup:

- split dan merge kembali;
- trim;
- delete ripple atau delete dengan gap;
- move clip;
- fade, pitch, speed, dan gain;
- normalize/noise reduction apply;
- track volume dan mute;
- penambahan atau penghapusan track.

Riwayat dibatasi 50 langkah. Command baru setelah undo menghapus redo branch. Playback, seek, zoom, pemilihan klip, dan membuka/menutup bottom sheet bukan command karena tidak mengubah hasil audio.

## 11. Rancangan UI

### 11.1 Struktur layar editor

```text
Top app bar
  Back · Editor audio · Undo · Redo · More

Time ruler
  posisi / durasi · zoom out · zoom value · zoom in

Stacked timeline
  Track header: nama · volume · mute
  Waveform clips
  Playhead

Transport controls
  previous boundary · play/pause · next boundary · loop

Context toolbar untuk klip aktif
  Split · Delete · Fade · Pitch · Speed · Bersihkan

Bottom actions
  Tambah track · Simpan draft · Ekspor
```

### 11.2 Interaksi timeline

- Tap klip memilih satu klip dan menampilkan outline aktif.
- Tap area kosong memindahkan playhead dan menghapus selection.
- Drag klip memindahkan posisi waktunya.
- Drag handle mengubah trim.
- Pinch mengubah zoom; pan horizontal menggeser viewport.
- Track header tetap terlihat saat timeline digeser horizontal.
- Target sentuh interaktif minimal 48 dp meskipun visual handle lebih kecil.

### 11.3 Tool sheets

Fade, Pitch, Speed, dan Bersihkan membuka modal bottom sheet. Timeline tetap tampak di belakang sehingga konteks klip tidak hilang. Sheet menyediakan reset, preview, serta aksi Terapkan jika pemrosesan cache diperlukan.

Sheet **Bersihkan** berisi:

- normalize toggle dan target tetap −1 dBFS;
- noise reduction dengan pilihan Ringan, Sedang, atau Kuat;
- target proses: klip aktif atau seluruh track;
- estimasi singkat dan tombol Terapkan.

### 11.4 Tambah track

- File baru ditempatkan pada track baru mulai dari posisi playhead.
- Aplikasi menganalisis durasi dan waveform sebelum menampilkan klip.
- Tombol dinonaktifkan saat sudah ada lima track dan menjelaskan batas tersebut.
- Jika hasil impor akan membuat timeline melewati lima menit, impor ditolak dengan pesan yang jelas.

## 12. Draft

Draft memakai Room untuk metadata dan penyimpanan privat untuk salinan sumber.

Proses simpan bersifat atomik:

1. Hitung kebutuhan ruang dan pastikan kapasitas memadai.
2. Salin semua sumber yang belum dikelola aplikasi ke direktori draft sementara.
3. Simpan snapshot timeline dan parameter efek dalam transaksi database.
4. Ubah direktori sementara menjadi draft aktif.
5. Bersihkan versi draft sebelumnya setelah versi baru sukses.

Jika salah satu langkah gagal, draft lama tetap utuh dan file sementara dibersihkan. Menghapus draft menghapus metadata, salinan sumber, waveform, dan cache proses yang tidak lagi direferensikan.

## 13. Ekspor

### 13.1 Preset

| Preset | Format | Kegunaan |
|---|---|---|
| Voice Note | OGG Opus mono, 48 kHz, 32 kbps | Default; berbagi sebagai voice note Telegram. |
| High Quality | OGG Opus mono, 48 kHz, 64 kbps | Mixing beberapa track atau materi musik. |

### 13.2 Alur ekspor

1. Pengguna memilih nama file dan preset.
2. Sesi divalidasi dan dibuat menjadi render manifest sementara.
3. WorkManager menjalankan renderer menggunakan manifest tersebut.
4. PCM campuran dikirim ke software Opus encoder dan `OggOpusWriter` yang sudah ada.
5. Hasil ditulis sebagai file sementara.
6. File diverifikasi dapat dibuka serta memiliki durasi masuk akal.
7. File dipublikasikan ke MediaStore dan entri Riwayat baru dibuat.
8. Manifest serta file parsial dibersihkan.

Hasil awal tidak ditimpa. Pembatalan ekspor menghentikan worker dan membersihkan file parsial. UI menampilkan progres dan memungkinkan pengguna meninggalkan layar editor saat pekerjaan berlanjut.

## 14. Cache dan Maintenance

- Waveform cache menyimpan peak terkompresi per sumber atau klip hasil proses.
- Processed cache menyimpan hasil normalize dan RNNoise.
- Cache memakai kebijakan LRU dan dapat dihitung ulang dari sumber serta parameter efek.
- File draft bukan cache dan tidak boleh dieviktasi otomatis.
- Cache sesi biasa dibersihkan setelah sesi dibuang.
- Worker maintenance berkala menghapus manifest yatim dan file parsial yang melewati batas umur.

## 15. Penanganan Kegagalan

| Kondisi | Respons sistem |
|---|---|
| Import gagal | Jangan membuat track; sesi lama tetap utuh. |
| Sumber sesi tidak dapat dibaca | Tandai track dan tawarkan pilih ulang file. |
| Sumber draft rusak atau hilang | Tampilkan track offline dan aksi Ganti file. |
| Efek gagal atau dibatalkan | Buang cache baru; pertahankan hasil sebelumnya. |
| Ruang hampir habis | Cegah draft/ekspor sebelum penulisan besar dimulai. |
| Preview underrun | Pertahankan playhead yang benar, isi ulang buffer, dan tampilkan pesan hanya jika berulang. |
| Ekspor dibatalkan | Batalkan worker dan hapus output parsial. |
| Ekspor gagal | Jangan membuat entri Riwayat; sesi tetap dapat diedit dan dicoba kembali. |
| Hasil mix clipping | Master limiter membatasi peak sebelum encoding. |

## 16. Library dan Dependensi

### Dipakai atau diperluas

- **Jetpack Compose:** UI editor dan custom timeline canvas.
- **AndroidX Media3 / MediaCodec:** ekstraksi dan decoding input.
- **Android AudioTrack:** playback PCM hasil renderer.
- **Signalsmith Stretch:** pitch shift dan perubahan speed dengan pitch preservation.
- **libopus + OggOpusWriter:** encoding dan container output.
- **Room:** draft, referensi sumber, dan metadata cache.
- **WorkManager:** ekspor serta maintenance cache.
- **Kotlin Coroutines/Flow:** state, render jobs, dan progres.

### Dependensi baru

- **RNNoise:** noise reduction speech, dibangun melalui CMake/NDK dan diakses lewat JNI.

Tidak diperlukan library waveform pihak ketiga atau FFmpeg. Timeline lebih aman dikembangkan dari `WaveformVisualizer` dan Compose Canvas yang sudah ada agar gesture, zoom, selection, dan trim handles sesuai kebutuhan aplikasi.

## 17. Strategi Pengujian

### Unit tests

- Perhitungan source time dan timeline time.
- Split, trim, move, speed, dan ripple delete.
- Invariant tidak ada overlap dalam track yang sama.
- Batas lima track dan lima menit.
- Semua command `execute`, `undo`, dan `redo`.
- Pewarisan dan reset efek ketika split.
- Gain, fade envelope, mix, limiter, dan sample count.
- Eviction cache tanpa kehilangan kemampuan render ulang.

### Integration tests

- Decode → effects → mix → Opus → OGG.
- Dua hingga lima track dengan waktu mulai dan durasi berbeda.
- Kombinasi trim, pitch, speed, fade, normalize, dan RNNoise.
- Pembatalan serta retry ekspor.
- Penyimpanan, pembukaan, pembaruan, dan penghapusan draft.
- Migrasi Room untuk tabel draft baru.

### UI tests

- Pemilihan satu klip.
- Drag, trim handles, seek, pan, dan zoom.
- Enable/disable toolbar berdasarkan selection.
- Bottom sheets dan target klip/track.
- Dialog keluar dengan perubahan.
- Batas track/durasi dan pesan kesalahan.
- Progres serta pembatalan ekspor.

### Manual device tests

- Android 7 sebagai minimum SDK dan perangkat Android modern.
- Perangkat RAM rendah dengan lima track berdurasi lima menit.
- Seek berulang, perubahan efek saat playback, dan perpindahan background/foreground.
- Perbandingan preview dengan hasil ekspor.
- Pemeriksaan subjektif preset RNNoise dan kualitas pitch/speed.

## 18. Urutan Implementasi yang Disarankan

1. Model timeline, invariant, command history, dan unit tests.
2. Timeline UI read-only, selection, playhead, zoom, dan waveform cache.
3. Trim, split, delete, move, undo, dan redo.
4. Streaming renderer, `AudioTrack` preview, track volume, mute, mixing, dan limiter.
5. Fade, pitch, dan speed.
6. Normalize dua tahap dan RNNoise processed cache.
7. Export worker dengan preset 32/64 kbps dan integrasi Riwayat.
8. Draft storage atomik, source copy, dan cleanup.
9. Performance profiling, accessibility, recovery paths, dan end-to-end QA.

Urutan ini menghasilkan vertical slice yang dapat diuji sejak awal dan menunda integrasi DSP paling berat sampai fondasi timeline serta renderer stabil.

## 19. Acceptance Criteria

- Editor dapat dibuka dari hasil konversi dan dari Riwayat.
- Track utama dibuat tanpa mengubah file hasil sebelumnya.
- Pengguna dapat mengimpor hingga total lima track dan menyusun klip dalam timeline maksimal lima menit.
- Semua tool dalam scope bekerja pada target yang telah ditentukan.
- Satu klip aktif terlihat jelas dan toolbar selalu bertindak pada target tersebut.
- Preview campuran tetap sinkron setelah seek, split, trim, move, pitch, dan speed.
- Undo/redo memulihkan hasil timeline dengan benar hingga 50 command.
- Normalize dan noise reduction tidak mengganti preview lama sebelum proses berhasil.
- Draft eksplisit dapat dibuka kembali walaupun file sumber asli telah dipindahkan.
- Ekspor 32 dan 64 kbps menghasilkan OGG Opus mono yang dapat diputar dan tercatat sebagai hasil baru.
- File sumber dan hasil konversi awal tidak pernah dimodifikasi oleh editor.
- Kegagalan, pembatalan, atau ruang penyimpanan rendah tidak meninggalkan entri Riwayat palsu atau file parsial permanen.
