# Desain Modul Audio Editor & Mixer (3-Track Clean Architecture)

**Tanggal:** 2026-09-18  
**Status:** Disetujui (Approved)  
**Target Package:** com.aistudio.voicenote.cvtr.editor  

---

## 1. Latar Belakang & Tujuan
Modul ini bertujuan untuk memberikan kemampuan mixing dan editing audio multi-track (maksimal 3 track) pasca-konversi atau dari penyimpanan perangkat secara sederhana, deterministik, dan stabil. 

### Prinsip Utama:
1. **Zero-Interference:** Tidak mengubah alur dan engine inti konversi VoiceNoteConverter yang sudah berjalan dengan baik.
2. **Deterministic Timeline:** 1 Klip per Track (maksimal 3 slot track), bebas dari bug collision/overlap.
3. **Low-Latency Preview:** Menggunakan *PCM Cached Mixing* (48kHz Mono 16-bit) untuk menjamin preview, scrubbing playhead, dan trimming berjalan lancar tanpa stutter.
4. **Telegram Compatibility:** Output akhir di-export menjadi file standard OGG Opus 48kHz Mono dengan 5-bit waveform metadata yang langsung kompatibel dengan Telegram Voice Note.

---

## 2. Alur Pengguna (User Flow) & Navigasi

- **Akses Masuk:**
  - **Dari Converter:** Kartu hasil konversi menyediakan tombol **'Edit & Mix'** yang langsung memasukkan hasil konversi ke Track 1 Editor.
  - **Dari Riwayat:** Tiap item riwayat memiliki opsi **'Buka di Editor'**.
  - **Dari Bottom Navigation:** Tab **'Editor'** tersedia di bar navigasi utama untuk mulai mengedit timeline.
- **Sesi Aktif:**
  - State editor disimpan di level EditorViewModel sehingga perpindahan tab tidak menghilangkan pekerjaan yang sedang berlangsung.
  - Tombol **'Reset Timeline'** disediakan di TopBar untuk mengosongkan track kapan saja.

---

## 3. Struktur Data & State Model

Package: com.aistudio.voicenote.cvtr.editor.model

- **AudioTrackClip**:
  - id: String (UUID)
  - sourceUri: Uri
  - displayName: String
  - pcmCacheFile: File (16-bit 48kHz Mono PCM)
  - durationMs: Long
  - waveformPoints: List<Float> (100 titik amplitudo untuk visualisasi UI)
  - startOffsetMs: Long (posisi mulai di timeline global)
  - 	rimStartMs: Long (in-point trim)
  - 	rimEndMs: Long (out-point trim)
  - pitchSemitones: Float (-12.0 s.d. +12.0 semitones)
  - olumeGain: Float (0.0 s.d. 2.0 / 0% - 200%)
  - ctiveDurationMs = (trimEndMs - trimStartMs).coerceAtLeast(0L)
  - 	imelineEndMs = startOffsetMs + activeDurationMs

- **EditorTimelineState**:
  - 	racks: List<AudioTrackClip?> (3 slot tetap: index 0, 1, 2)
  - selectedTrackIndex: Int?
  - playheadPositionMs: Long
  - isPlaying: Boolean
  - isLoadingSource: Boolean
  - isExporting: Boolean
  - exportProgress: Float
  - exportedFileUri: Uri?
  - errorMessage: String?
  - 	otalDurationMs: Long (max 	imelineEndMs dari track aktif)
  - hasActiveTracks: Boolean

---

## 4. Antarmuka (UI) & Gestur Timeline

Package: com.aistudio.voicenote.cvtr.editor.ui

- **EditorScreen**: Scaffold utama menampung TopAppBar, Player Controls, Timeline Canvas, dan Selected Track Toolbar.
- **TimelineCanvas**:
  - **Ruler Waktu (Header):** Skala waktu dengan penanda detik. Mendukung tap/drag untuk scrubbing playhead.
  - **Playhead Line:** Garis vertikal penunjuk waktu dengan transisi halus saat pemutaran.
  - **3 Track Lanes:**
    - *Slot Kosong:* Tombol kartu '+ Tambah Audio'.
    - *Slot Terisi (Clip Box):*
      - Tampilan nama file & waveform mini.
      - Handle Kiri (*In-point Trim*) & Handle Kanan (*Out-point Trim*) dengan gestur drag horizontal.
      - Body Clip dengan gestur drag horizontal untuk mengubah startOffsetMs (maju/mundur di timeline).
      - Tap untuk memilih slot dan membuka panel kontrol bawah.
- **EditorToolbarSheet**:
  - Muncul di bagian bawah saat sebuah track dipilih:
    - Pitch Shifter Slider (-12 s.d. +12 semitones, dengan tombol reset 0).
    - Volume Slider (0% s.d. 200%).
    - Tombol Ganti Track (Pindah ke Slot 1, 2, atau 3).
    - Tombol Hapus Track & Ganti Audio.
- **AudioSourcePickerSheet**:
  - Tab **'Dari Riwayat'**: Menampilkan daftar audio yang pernah dikonversi dari ConversionHistoryRepository dengan tombol preview dan pilih.
  - Tab **'Pilih File'**: Menggunakan ActivityResult launcher OpenDocument/GetContent untuk mengambil file audio eksternal.

---

## 5. Audio Engine, Mixing & Export Pipeline

Package: com.aistudio.voicenote.cvtr.editor.engine

- **EditorPcmDecoder**:
  - Membaca file audio (OGG/MP3/M4A/WAV) via MediaExtractor dan MediaCodec.
  - Men-decode menjadi format audio uncompressed standar: 16-bit 48.000 Hz Mono PCM.
  - Menyimpan file cache PCM di direktori cache lokal aplikasi (cache/editor_pcm_<id>.pcm).
  - Menghitung nilai downsampled RMS amplitude untuk visualisasi waveform instan di Jetpack Compose.
- **EditorAudioEngine (Real-time Multi-Track Preview)**:
  - Menggunakan ndroid.media.AudioTrack (STREAM_MUSIC, 48kHz, CHANNEL_OUT_MONO, PCM_16BIT).
  - Thread playback looping membaca buffer per 20-40ms:
    - Membaca PCM slice dari setiap track yang aktif pada playhead.
    - Menerapkan pitch shifting via StreamingPitchShifter dan volume gain scaling.
    - Menjumlahkan (mixing) audio buffer dengan soft limiter / clamp (-32768 s.d. 32767).
    - Menulis ke AudioTrack dan memperbarui playheadPositionMs.
- **EditorExporter**:
  - Merender seluruh rentang timeline dari 0ms hingga 	otalDurationMs.
  - Membaca dan menjumlahkan (sum-mix) PCM dari track 1-3.
  - Menyalurkan buffer hasil mixing ke OggOpusWriter yang meng-encode ke Ogg Opus 48kHz Mono.
  - Menghitung 100 bar 5-bit waveform metadata Telegram via WaveformAccumulator.
  - Menyimpan hasil .ogg ke MediaStore publik melalui VoiceNoteStorage.
  - Merekam riwayat konversi ke Room database melalui ConversionHistoryRepository.

---

## 6. Verifikasi & Pengujian
- **Unit Tests:**
  - AudioTrackClipTest: Validasi kalkulasi in/out trim, active duration, dan bounds.
  - EditorPcmDecoderTest: Validasi decode audio ke PCM 48kHz mono.
  - EditorAudioEngineTest: Validasi mixing multi-channel, volume scaling, dan soft limiter.
  - EditorExporterTest: Validasi pembentukan file OGG Opus terpadu yang valid.
- **UI Tests:**
  - Pengujian interaksi pemilihan track, drag trim handles, dan bottom toolbar.
- **Build & Integration:**
  - ./gradlew testDebugUnitTest harus 100% pass.
  - Build Split APKs Debug & Release berjalan mulus.