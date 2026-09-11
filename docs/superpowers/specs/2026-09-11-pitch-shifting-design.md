# Voice Note Converter — Vocal Pitch Shifting Design Spec

> **Date:** 2026-09-11
> **Status:** Draft — Awaiting User Review
> **Scope:** Pitch shifting vokal menggunakan Signalsmith Stretch (C++/NDK), integrasi ke pipeline konversi, UI slider di area preview, dan perbaikan layout UI

---

## 1. Problem Statement

Aplikasi Voice Note Converter saat ini mengonversi audio/video menjadi voice note OGG Opus tanpa kemampuan mengubah pitch vokal. Pengguna tidak bisa menaikkan atau menurunkan nada suara sebelum konversi.

Pitch shifting vokal yang berkualitas memerlukan **formant preservation** — tanpa itu, menaikkan pitch membuat suara "chipmunk" dan menurunkan pitch membuat suara "monster". Library bawaan Android (Sonic/ExoPlayer PlaybackParams) tidak memiliki kemampuan ini.

---

## 2. Goals

- **G1:** Pengguna bisa mengatur pitch vokal dengan slider sebelum konversi.
- **G2:** Hasil pitch shifting bersih dan natural (formant preserved) — tidak chipmunk/monster.
- **G3:** Range pitch tipis dan aman: **±4 semitone** (cukup untuk variasi vokal natural).
- **G4:** Preview real-time pitch sebelum konversi (via ExoPlayer `PlaybackParams`).
- **G5:** UI pitch slider terintegrasi di bawah waveform bersama trim controls, dengan perbaikan layout keseluruhan area preview agar lebih nyaman dioperasikan.
- **G6:** Setup NDK/CMake pertama kali untuk project ini.

---

## 3. Technology Decision: Signalsmith Stretch

### Mengapa Signalsmith Stretch?

| Kriteria | Signalsmith Stretch | Sonic.java | Rubber Band | SoundTouch |
|:---------|:------------------:|:----------:|:-----------:|:----------:|
| Kualitas vokal | ⭐⭐⭐⭐⭐ | ⭐⭐ | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ |
| Formant preservation | ✅ (tonality limit) | ❌ | ✅ | ❌ |
| Lisensi | **MIT** | Apache 2.0 | **GPL** ❌ | LGPL |
| APK overhead | ~200 KB/ABI | 0 KB | ~1.5 MB | ~250 KB |
| Kompleksitas | Header-only C++11 | Pure Java | NDK + FFT dep | NDK + JNI |

**Keputusan:** Langsung Signalsmith Stretch — kualitas premium, MIT license, header-only C++11, dan ringan.

### Cara Kerja Signalsmith Stretch

Signalsmith Stretch adalah phase vocoder modern yang beroperasi pada domain frekuensi:

1. **Input:** PCM float buffer (per-channel, non-interleaved)
2. **Configure:** `presetDefault(channels, sampleRate)` atau `presetCheaper()` untuk performa
3. **Pitch shift:** `setTransposeSemitones(semitones, tonalityLimit)` — parameter `tonalityLimit` mempertahankan karakter vokal (formant preservation)
4. **Process:** `process(inputBuffers, inputSamples, outputBuffers, outputSamples)` — untuk pitch-only (tanpa time stretch), input dan output sample count sama
5. **Latency:** `inputLatency()` dan `outputLatency()` — perlu di-handle untuk pre-roll dan tail

API kunci:
```cpp
#include "signalsmith-stretch.h"

using Stretch = signalsmith::stretch::SignalsmithStretch<float>;
Stretch stretch;

// Konfigurasi
stretch.presetDefault(1, 48000);  // mono, 48kHz

// Set pitch shift: +2 semitones, dengan tonality limit untuk preservasi formant
// tonalityLimit = 8000/sampleRate melindungi frekuensi vokal di bawah 8kHz
stretch.setTransposeSemitones(2.0f, 8000.0f / 48000.0f);

// Process (pitch-only = input dan output sama panjang)
float* inputBuf[1] = { inputData };
float* outputBuf[1] = { outputData };
stretch.process(inputBuf, sampleCount, outputBuf, sampleCount);
```

### Catatan Penting

- **Tonality Limit:** Nilai `8000.0f / sampleRate` adalah sweet spot untuk vokal — frekuensi di bawah 8kHz (area formant vokal) diperlakukan dengan preservasi lebih baik.
- **Float conversion:** Signalsmith bekerja dengan `float` (-1.0 s/d 1.0), sedangkan pipeline kita menggunakan `Short` (PCM 16-bit). Konversi `Short ↔ Float` diperlukan.
- **Buffer requirement:** Input dan output buffer tidak boleh sama (tidak bisa in-place).
- **Optimisasi:** ~10x lebih lambat tanpa compiler optimization — **wajib** `-O2` atau `-O3` di CMake release/debug.

---

## 4. Architecture

### 4.1 Pipeline Konversi (Sebelum vs Sesudah)

**Sebelum (saat ini):**
```
MediaExtractor → MediaCodec (Decoder)
  → StreamingPcmProcessor [Mono + Resample 48kHz]
  → StreamingOpusEncoder
  → OggOpusWriter → .ogg
```

**Sesudah (dengan pitch shifting):**
```
MediaExtractor → MediaCodec (Decoder)
  → StreamingPcmProcessor [Mono + Resample 48kHz]
  → ★ PitchShifter (Signalsmith Stretch via JNI) ★
  → StreamingOpusEncoder
  → OggOpusWriter → .ogg
```

PitchShifter hanya aktif ketika `pitchSemitones != 0.0f`. Jika pitch = 0, pipeline bypass langsung ke encoder (zero overhead).

### 4.2 Komponen Baru

#### A. Native Library (`app/src/main/cpp/`)

```
app/src/main/cpp/
├── CMakeLists.txt
├── pitch_shifter_jni.cpp        ← JNI bridge
└── signalsmith-stretch/         ← Git submodule atau copy
    ├── signalsmith-stretch.h
    └── dsp/
        └── (signalsmith DSP headers)
```

#### B. JNI Bridge (`PitchShifterJni.kt`)

```kotlin
// com.example.audio.PitchShifterJni
internal object PitchShifterJni {
    init { System.loadLibrary("pitchshifter") }

    /** Buat instance stretcher. Return handle (pointer). */
    external fun create(sampleRate: Int, channels: Int): Long

    /**
     * Set pitch dalam semitones.
     * tonalityLimit: frekuensi batas preservasi formant (0.0 = off).
     * Rekomendasi: 8000.0 / sampleRate untuk vokal.
     */
    external fun setTranspose(handle: Long, semitones: Float, tonalityLimit: Float)

    /**
     * Proses batch PCM 16-bit.
     * Input: ShortArray (mono 48kHz).
     * Return: ShortArray pitch-shifted (panjang sama dengan input).
     */
    external fun process(handle: Long, input: ShortArray, inputFrames: Int): ShortArray

    /**
     * Flush internal buffers (akhir stream).
     * Return: ShortArray sisa samples di internal buffer.
     */
    external fun flush(handle: Long): ShortArray

    /** Hapus instance dan bebaskan memory. */
    external fun destroy(handle: Long)
}
```

#### C. StreamingPitchShifter (Kotlin wrapper)

```kotlin
// com.example.audio.StreamingPitchShifter
internal class StreamingPitchShifter(
    sampleRate: Int = 48_000,
    channels: Int = 1,
    semitones: Float = 0f,
    tonalityLimit: Float = 8000f / 48_000f
) : AutoCloseable {
    private var handle: Long = PitchShifterJni.create(sampleRate, channels)

    init {
        PitchShifterJni.setTranspose(handle, semitones, tonalityLimit)
    }

    fun process(samples: ShortArray): ShortArray {
        check(handle != 0L) { "PitchShifter sudah di-destroy" }
        return PitchShifterJni.process(handle, samples, samples.size)
    }

    fun flush(): ShortArray {
        check(handle != 0L) { "PitchShifter sudah di-destroy" }
        return PitchShifterJni.flush(handle)
    }

    override fun close() {
        if (handle != 0L) {
            PitchShifterJni.destroy(handle)
            handle = 0L
        }
    }
}
```

### 4.3 Integrasi ke VoiceNoteConverter

Dalam `convertToTelegramVoiceNote()`, setelah `pcmProcessor.process()` dan sebelum `encoder.write()`:

```kotlin
// Parameter baru
suspend fun convertToTelegramVoiceNote(
    context: Context,
    inputUri: Uri,
    trimStartMs: Long = 0L,
    trimEndMs: Long = Long.MAX_VALUE,
    pitchSemitones: Float = 0f,           // ← NEW
    onProgress: (Float) -> Unit = {}
): AudioConversionResult

// Di dalam loop:
val encodedChunk = pcmProcessor.process(decodedChunk)
val finalChunk = if (pitchShifter != null) {
    pitchShifter.process(encodedChunk)
} else {
    encodedChunk
}
if (finalChunk.isNotEmpty()) {
    encoder.write(finalChunk)
    encodedSampleCount += finalChunk.size
    waveformPeaks += finalChunk.maxOf { abs(it.toInt()) }.toFloat()
}

// Setelah loop selesai, flush pitch shifter:
pitchShifter?.flush()?.let { tail ->
    if (tail.isNotEmpty()) {
        encoder.write(tail)
        encodedSampleCount += tail.size
    }
}
```

### 4.4 Preview Real-time

Untuk preview pitch sebelum konversi, kita gunakan **ExoPlayer `PlaybackParams`** (Sonic-based, tanpa formant preservation tapi responsif):

```kotlin
// Di AudioPreviewPlayer
fun setPitchPreview(semitones: Float) {
    val factor = 2f.pow(semitones / 12f)
    player.playbackParameters = PlaybackParameters(/* speed= */ 1f, /* pitch= */ factor)
}
```

> **Catatan:** Preview menggunakan Sonic (tanpa formant preservation), sedangkan konversi akhir menggunakan Signalsmith Stretch (dengan formant preservation). Untuk range ±4 semitone, perbedaannya subtle dan acceptable untuk preview cepat. Pengguna tetap mendengar pitch berubah — hanya karakter vokal sedikit berbeda dari hasil akhir.

---

## 5. UI Design

### 5.1 Masalah Layout Saat Ini

Layout `PreviewCard` saat ini memiliki beberapa masalah ergonomis:

1. **Tombol play terlalu kecil dan menempel ke waveform** — dalam Row yang sama dengan waveform dan durasi text, tombol play 46dp terasa sempit.
2. **Durasi text di kanan waveform memakan ruang** — waveform tidak mendapat full width.
3. **TrimControls muncul di bawah tanpa visual separator** — terasa menumpuk, tidak jelas mana area playback mana area tools.
4. **Tidak ada ruang untuk kontrol tambahan** — menambahkan pitch slider ke layout yang sudah padat akan semakin sesak.

### 5.2 Perbaikan Layout PreviewCard

Restrukturisasi `PreviewCard` menjadi layout yang lebih lapang dan terorganisir:

```
┌─────────────────────────────────────────────────────────┐
│  PreviewCard                                            │
│                                                         │
│  🎙  Preview audio asli                                │
│      filename.mp3                                       │
│                                                         │
│  ┌─────────────────────────────────────────────────┐    │
│  │  ████ ██████ █████ ███ ████████ ███ █████ ██    │    │
│  │  ████ ██████ █████ ███ ████████ ███ █████ ██    │    │
│  └─────────────────────────────────────────────────┘    │
│                                                         │
│  [ ▶ ]    00:03 / 01:45                                │
│                                                         │
│  ─────────────── Tool Controls ───────────────          │
│                                                         │
│  [ ✂ Potong audio ]                                    │
│  00:03 sampai 01:45    Durasi 01:42                     │
│  [▶ Preview]  [↺ Reset]                                │
│                                                         │
│  🎵 Pitch                             -2.0 st          │
│  ──────────────●────────────────────                    │
│  -4          0          +4                              │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

**Perubahan kunci dari layout lama:**

| Aspek | Sebelum | Sesudah |
|:------|:--------|:--------|
| Waveform | Dalam Row bersama play button + durasi text | **Full width** — baris terpisah, tanpa elemen samping |
| Play button + durasi | Sejajar horizontal dengan waveform | **Baris terpisah** di bawah waveform: play button + "current / total" |
| Separator | Tidak ada | **Divider/label** "Tool Controls" memisahkan playback area dari tools |
| TrimControls | Langsung di bawah waveform | Di bawah separator, visual grouping lebih jelas |
| Pitch slider | Tidak ada | Di bawah TrimControls, dalam group tools yang sama |

### 5.3 Playback Controls Row (Baru)

```kotlin
// Baris terpisah di bawah waveform:
Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp)
) {
    // Play/pause button — lebih besar dan mudah ditekan
    IconButton(onClick = onTogglePlayback, modifier = Modifier.size(42.dp)...) {
        Icon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, ...)
    }
    // Current time / total time
    Text(
        text = "${formatTime(currentPositionMs)} / ${formatTime(totalDurationMs)}",
        style = MaterialTheme.typography.bodySmall,
        color = SubtitleSlate
    )
}
```

### 5.4 Pitch Slider UI

Komponen baru `PitchControl`:

```kotlin
@Composable
fun PitchControl(
    pitchSemitones: Float,        // current value: -4f..+4f
    onPitchChange: (Float) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
)
```

Layout:
```
Row [label + value display]
  🎵 Pitch                        -2.0 st

Slider (range -4f..+4f, steps = 0 untuk continuous)
  ──────────────●────────────────────

Row [min / center / max labels]
  -4          0          +4
```

Behavior:
- **Range:** -4.0 s/d +4.0 semitones (0.1 step granularity)
- **Default:** 0.0 (tidak ada pitch shift)
- **Snap ke 0:** Jika value antara -0.15 dan +0.15, snap ke tepat 0.0 (menghindari "hampir zero" yang tetap memproses pitch shift)
- **Display format:** `"0 st"` (center), `"-2.0 st"` (turun), `"+3.5 st"` (naik)
- **Preview on change:** Setiap kali slider bergerak, update `ExoPlayer.playbackParameters.pitch` untuk feedback audio real-time
- **Disable saat converting/sending:** `enabled = false`

### 5.5 Kapan PitchControl Ditampilkan

Sama dengan TrimControls — hanya muncul di `PreviewCard` saat:
- File sudah di-load dan di-analyze (`processStatus == IDLE`)
- Belum ada hasil konversi (`!hasConvertedResult`)

Setelah konversi selesai, pitch controls disembunyikan dan waveform menampilkan hasil akhir.

---

## 6. State Management

### 6.1 MainUiState — Tambahan Field

```kotlin
data class MainUiState(
    // ... existing fields ...
    val pitchSemitones: Float = 0f,   // ← NEW: -4.0 s/d +4.0
)
```

### 6.2 MainViewModel — Metode Baru

```kotlin
fun updatePitch(semitones: Float) {
    // Snap to zero
    val snapped = if (abs(semitones) < 0.15f) 0f else semitones
    _uiState.update { it.copy(pitchSemitones = snapped) }
    // Update preview pitch
    val factor = 2f.pow(snapped / 12f)
    audioPlayer.player.playbackParameters = PlaybackParameters(1f, factor)
}
```

### 6.3 Alur Konversi dengan Pitch

1. User mengatur slider → `viewModel.updatePitch(semitones)`
2. Preview real-time via ExoPlayer `PlaybackParams` (Sonic, approximate)
3. User tap "Konversi" → `startConversion()`
4. `VoiceNoteConverter.convertToTelegramVoiceNote(..., pitchSemitones = state.pitchSemitones)`
5. Jika `pitchSemitones != 0f`: buat `StreamingPitchShifter`, insert ke pipeline
6. Jika `pitchSemitones == 0f`: bypass, tidak ada overhead

---

## 7. NDK/CMake Setup (Pertama Kali)

### 7.1 File Baru di `app/src/main/cpp/`

**CMakeLists.txt:**
```cmake
cmake_minimum_required(VERSION 3.22.1)
project(pitchshifter)

# Signalsmith Stretch header-only library
add_library(pitchshifter SHARED pitch_shifter_jni.cpp)

target_include_directories(pitchshifter PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/signalsmith-stretch
    ${CMAKE_CURRENT_SOURCE_DIR}/signalsmith-stretch/dsp
)

# Wajib: optimisasi compiler (Signalsmith 10x lebih lambat tanpa ini)
target_compile_options(pitchshifter PRIVATE -O2)

target_link_libraries(pitchshifter
    android
    log
)
```

**pitch_shifter_jni.cpp:**
- Mengimplementasikan JNI functions yang dipanggil `PitchShifterJni.kt`
- Handle konversi `short[] ↔ float[]` (PCM 16-bit ke normalized float)
- Manage `SignalsmithStretch` instance lifecycle via opaque handle (pointer → jlong)

### 7.2 Modifikasi build.gradle.kts

Tambahkan blok `externalNativeBuild` di `android {}`:

```kotlin
android {
    // ... existing ...
    
    defaultConfig {
        // ... existing ...
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}
```

### 7.3 Signalsmith Stretch Source

Download dari GitHub: `https://github.com/Signalsmith-Audio/signalsmith-stretch`

Opsi: Git submodule atau copy langsung ke `app/src/main/cpp/signalsmith-stretch/`.

Rekomendasi: Copy langsung (lebih sederhana, tidak perlu manage submodule).

---

## 8. Files Changed Summary

### New Files
| File | Purpose |
|---|---|
| `app/src/main/cpp/CMakeLists.txt` | CMake build config untuk native library |
| `app/src/main/cpp/pitch_shifter_jni.cpp` | JNI bridge: C++ Signalsmith ↔ Kotlin |
| `app/src/main/cpp/signalsmith-stretch/` | Signalsmith Stretch library (header-only, MIT) |
| `app/src/main/java/com/example/audio/PitchShifterJni.kt` | Kotlin JNI declarations |
| `app/src/main/java/com/example/audio/StreamingPitchShifter.kt` | Kotlin wrapper dengan lifecycle management |
| `app/src/main/java/com/example/ui/components/PitchControl.kt` | UI slider composable |

### Modified Files
| File | Changes |
|---|---|
| `app/build.gradle.kts` | Tambah NDK config, CMake path, ABI filters |
| `app/src/main/java/com/example/audio/VoiceNoteConverter.kt` | Tambah parameter `pitchSemitones`, integrasi `StreamingPitchShifter` di pipeline |
| `app/src/main/java/com/example/ui/MainViewModel.kt` | Tambah `pitchSemitones` di `MainUiState`, method `updatePitch()`, pass pitch ke converter |
| `app/src/main/java/com/example/ui/MainScreen.kt` | Restrukturisasi `PreviewCard` layout, tambah `PitchControl` |
| `app/src/main/java/com/example/ui/components/TrimControls.kt` | Minor: sesuaikan spacing untuk layout baru |

---

## 9. Verification Plan

### Automated Tests
- `./gradlew assembleDebug` — project compiles dengan NDK (verifikasi CMake + JNI linking)
- `./gradlew test` — existing unit tests masih pass

### Manual Verification
1. **Pitch slider:** Geser slider, lihat label value berubah (-4.0 s/d +4.0 st)
2. **Preview pitch:** Putar audio, geser slider — dengarkan pitch berubah real-time
3. **Snap to zero:** Geser slider mendekati 0 — harus snap tepat ke 0 st
4. **Konversi tanpa pitch:** Slider di 0 → konversi → hasil sama dengan sebelumnya
5. **Konversi dengan pitch naik:** Set +2 st → konversi → suara lebih tinggi tapi natural
6. **Konversi dengan pitch turun:** Set -2 st → konversi → suara lebih rendah tapi natural
7. **Konversi dengan pitch + trim:** Set pitch + aktifkan trim → hasil benar
8. **Layout:** Preview card lebih lapang, waveform full width, controls terorganisir
9. **Performa:** Konversi file 5 menit dengan pitch shift < 10 detik di mid-range device
10. **Edge case:** File sangat pendek (< 1 detik) dengan pitch shift → tidak crash
