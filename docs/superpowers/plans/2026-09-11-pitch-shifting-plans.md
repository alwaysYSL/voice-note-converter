# Voice Note Converter — Pitch Shifting Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Menambahkan fitur pitch shifting vokal berkualitas tinggi menggunakan Signalsmith Stretch (C++/NDK) ke pipeline konversi voice note, dengan UI slider di area preview dan perbaikan layout PreviewCard.

**Architecture:** Signalsmith Stretch (MIT, header-only C++11) diintegrasikan via JNI bridge ke streaming PCM pipeline yang sudah ada. Pitch shifter disisipkan antara `StreamingPcmProcessor` (mono + resample 48kHz) dan `StreamingOpusEncoder`. Preview menggunakan ExoPlayer `PlaybackParams`. UI slider ditambahkan di area tools di bawah waveform.

**Tech Stack:** C++11 (Signalsmith Stretch), Android NDK, CMake, JNI, Kotlin, Jetpack Compose.

## Global Constraints

- `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36`
- Java 11 source/target compatibility
- NDK ABI targets: `arm64-v8a`, `armeabi-v7a`, `x86_64`
- Signalsmith Stretch dari: `https://github.com/Signalsmith-Audio/signalsmith-stretch`
- Semua Kotlin source di `app/src/main/java/com/example/`
- Native source di `app/src/main/cpp/`
- Commits setelah setiap task

---

## Task 1: NDK/CMake Setup & Signalsmith Stretch Integration

**Files:**
- New: `app/src/main/cpp/CMakeLists.txt`
- New: `app/src/main/cpp/pitch_shifter_jni.cpp`
- New: `app/src/main/cpp/signalsmith-stretch/` (library source)
- Modify: `app/build.gradle.kts`

**Interfaces:**
- Consumes: Nothing
- Produces: Native shared library `libpitchshifter.so` yang bisa di-load dari Kotlin

- [ ] **Step 1: Download Signalsmith Stretch library**

Clone atau download dari `https://github.com/Signalsmith-Audio/signalsmith-stretch` dan letakkan di `app/src/main/cpp/signalsmith-stretch/`. Struktur yang diperlukan:

```
app/src/main/cpp/signalsmith-stretch/
├── signalsmith-stretch.h        ← header utama
├── LICENSE.txt                  ← MIT license
└── dsp/                         ← signalsmith DSP library (dependency)
    ├── signalsmith-dsp/
    │   ├── envelopes.h
    │   ├── fft.h
    │   ├── perf.h
    │   ├── spectral.h
    │   └── windows.h
    └── LICENSE.txt              ← MIT license
```

Pastikan hanya copy file header yang diperlukan, bukan seluruh repo (tests, examples, docs tidak perlu).

- [ ] **Step 2: Create CMakeLists.txt**

Buat `app/src/main/cpp/CMakeLists.txt`:

```cmake
cmake_minimum_required(VERSION 3.22.1)
project(pitchshifter LANGUAGES CXX)

add_library(pitchshifter SHARED pitch_shifter_jni.cpp)

set_target_properties(pitchshifter PROPERTIES CXX_STANDARD 14)

target_include_directories(pitchshifter PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/signalsmith-stretch
    ${CMAKE_CURRENT_SOURCE_DIR}/signalsmith-stretch/dsp
)

# Signalsmith Stretch is ~10x slower without optimisation
target_compile_options(pitchshifter PRIVATE -O2)

find_library(log-lib log)
target_link_libraries(pitchshifter ${log-lib})
```

- [ ] **Step 3: Create pitch_shifter_jni.cpp**

Buat `app/src/main/cpp/pitch_shifter_jni.cpp`:

```cpp
#include <jni.h>
#include <cstring>
#include <cmath>
#include <algorithm>
#include <android/log.h>
#include "signalsmith-stretch.h"

#define LOG_TAG "PitchShifter"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using Stretch = signalsmith::stretch::SignalsmithStretch<float>;

// Conversion helpers: Short PCM 16-bit <-> Float [-1.0, 1.0]
static constexpr float SHORT_TO_FLOAT = 1.0f / 32768.0f;
static constexpr float FLOAT_TO_SHORT = 32767.0f;

struct PitchShifterContext {
    Stretch stretch;
    int sampleRate;
    int channels;
    // Reusable buffers to avoid per-call allocation
    std::vector<float> inputFloat;
    std::vector<float> outputFloat;
};

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_example_audio_PitchShifterJni_create(
    JNIEnv* env, jobject /* this */,
    jint sampleRate, jint channels
) {
    auto* ctx = new PitchShifterContext();
    ctx->sampleRate = sampleRate;
    ctx->channels = channels;
    ctx->stretch.presetDefault(channels, sampleRate);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_example_audio_PitchShifterJni_setTranspose(
    JNIEnv* env, jobject /* this */,
    jlong handle, jfloat semitones, jfloat tonalityLimit
) {
    auto* ctx = reinterpret_cast<PitchShifterContext*>(handle);
    if (!ctx) return;
    ctx->stretch.setTransposeSemitones(semitones, tonalityLimit);
}

JNIEXPORT jshortArray JNICALL
Java_com_example_audio_PitchShifterJni_process(
    JNIEnv* env, jobject /* this */,
    jlong handle, jshortArray inputArray, jint inputFrames
) {
    auto* ctx = reinterpret_cast<PitchShifterContext*>(handle);
    if (!ctx || inputFrames <= 0) {
        return env->NewShortArray(0);
    }

    const int channels = ctx->channels;
    const int totalSamples = inputFrames; // mono: frames == samples

    // Get input data
    jshort* inputData = env->GetShortArrayElements(inputArray, nullptr);
    if (!inputData) return env->NewShortArray(0);

    // Convert Short -> Float
    ctx->inputFloat.resize(totalSamples);
    for (int i = 0; i < totalSamples; ++i) {
        ctx->inputFloat[i] = inputData[i] * SHORT_TO_FLOAT;
    }
    env->ReleaseShortArrayElements(inputArray, inputData, JNI_ABORT);

    // Prepare output buffer (same length for pitch-only, no time-stretch)
    ctx->outputFloat.resize(totalSamples);

    // Process: mono = 1 channel
    float* inputBuf[1] = { ctx->inputFloat.data() };
    float* outputBuf[1] = { ctx->outputFloat.data() };
    ctx->stretch.process(inputBuf, inputFrames, outputBuf, inputFrames);

    // Convert Float -> Short
    jshortArray result = env->NewShortArray(totalSamples);
    jshort* resultData = env->GetShortArrayElements(result, nullptr);
    for (int i = 0; i < totalSamples; ++i) {
        float sample = ctx->outputFloat[i] * FLOAT_TO_SHORT;
        sample = std::max(-32768.0f, std::min(32767.0f, sample));
        resultData[i] = static_cast<jshort>(sample);
    }
    env->ReleaseShortArrayElements(result, resultData, 0);

    return result;
}

JNIEXPORT jshortArray JNICALL
Java_com_example_audio_PitchShifterJni_flush(
    JNIEnv* env, jobject /* this */,
    jlong handle
) {
    auto* ctx = reinterpret_cast<PitchShifterContext*>(handle);
    if (!ctx) return env->NewShortArray(0);

    // Flush: feed silence for outputLatency() samples to drain internal buffers
    int flushSamples = ctx->stretch.outputLatency() + ctx->stretch.inputLatency();
    if (flushSamples <= 0) return env->NewShortArray(0);

    ctx->inputFloat.assign(flushSamples, 0.0f);
    ctx->outputFloat.resize(flushSamples);

    float* inputBuf[1] = { ctx->inputFloat.data() };
    float* outputBuf[1] = { ctx->outputFloat.data() };
    ctx->stretch.process(inputBuf, flushSamples, outputBuf, flushSamples);

    jshortArray result = env->NewShortArray(flushSamples);
    jshort* resultData = env->GetShortArrayElements(result, nullptr);
    for (int i = 0; i < flushSamples; ++i) {
        float sample = ctx->outputFloat[i] * FLOAT_TO_SHORT;
        sample = std::max(-32768.0f, std::min(32767.0f, sample));
        resultData[i] = static_cast<jshort>(sample);
    }
    env->ReleaseShortArrayElements(result, resultData, 0);

    return result;
}

JNIEXPORT void JNICALL
Java_com_example_audio_PitchShifterJni_destroy(
    JNIEnv* env, jobject /* this */,
    jlong handle
) {
    auto* ctx = reinterpret_cast<PitchShifterContext*>(handle);
    delete ctx;
}

} // extern "C"
```

- [ ] **Step 4: Update app/build.gradle.kts — NDK configuration**

Tambahkan di dalam blok `android {}`:

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

**Catatan:** Pastikan Android NDK sudah terinstall di Android Studio (SDK Manager → SDK Tools → NDK (Side by side)). Jika belum, install versi terbaru.

- [ ] **Step 5: Verify native build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL — `libpitchshifter.so` terbuild untuk semua target ABI.

Jika gagal:
- Periksa apakah NDK terinstall: `sdk/ndk/` harus ada
- Periksa path header Signalsmith: `signalsmith-stretch.h` harus ada di `app/src/main/cpp/signalsmith-stretch/`
- Periksa JNI function names match dengan fully-qualified Kotlin class name

- [ ] **Step 6: Commit**

```bash
git add app/src/main/cpp/ app/build.gradle.kts
git commit -m "feat: add Signalsmith Stretch NDK integration for pitch shifting"
```

---

## Task 2: Kotlin JNI Bridge & StreamingPitchShifter

**Files:**
- New: `app/src/main/java/com/example/audio/PitchShifterJni.kt`
- New: `app/src/main/java/com/example/audio/StreamingPitchShifter.kt`

**Interfaces:**
- Consumes: `libpitchshifter.so` (Task 1)
- Produces: `StreamingPitchShifter(sampleRate, channels, semitones)` — `.process(ShortArray): ShortArray`, `.flush(): ShortArray`, `.close()`

- [ ] **Step 1: Create PitchShifterJni.kt**

Buat `app/src/main/java/com/example/audio/PitchShifterJni.kt`:

```kotlin
package com.example.audio

/**
 * JNI bridge to the Signalsmith Stretch C++ pitch-shifting engine.
 *
 * Each [create] call returns an opaque handle (native pointer) that must be
 * released with [destroy] when no longer needed.
 */
internal object PitchShifterJni {
    init {
        System.loadLibrary("pitchshifter")
    }

    /** Create a new pitch-shifter instance. Returns an opaque native handle. */
    external fun create(sampleRate: Int, channels: Int): Long

    /**
     * Set the pitch transposition.
     *
     * @param handle        Native handle from [create].
     * @param semitones     Pitch shift in semitones (positive = up, negative = down).
     * @param tonalityLimit Frequency below which formant structure is preserved,
     *                      expressed as a fraction of the sample rate.
     *                      Recommended: `8000f / sampleRate` for vocals.
     */
    external fun setTranspose(handle: Long, semitones: Float, tonalityLimit: Float)

    /**
     * Process a block of mono 16-bit PCM samples.
     *
     * @param handle      Native handle from [create].
     * @param input       Mono PCM 16-bit samples.
     * @param inputFrames Number of frames (samples for mono) in [input].
     * @return Pitch-shifted PCM 16-bit samples (same frame count as input).
     */
    external fun process(handle: Long, input: ShortArray, inputFrames: Int): ShortArray

    /**
     * Flush remaining samples from internal buffers at the end of stream.
     *
     * @param handle Native handle from [create].
     * @return Residual PCM 16-bit samples from internal latency buffers.
     */
    external fun flush(handle: Long): ShortArray

    /** Destroy the native instance and free memory. */
    external fun destroy(handle: Long)
}
```

- [ ] **Step 2: Create StreamingPitchShifter.kt**

Buat `app/src/main/java/com/example/audio/StreamingPitchShifter.kt`:

```kotlin
package com.example.audio

/**
 * Kotlin wrapper around the Signalsmith Stretch pitch-shifting engine.
 *
 * Designed for streaming use: call [process] with successive PCM chunks,
 * then [flush] at the end of stream to drain internal latency buffers.
 *
 * Implements [AutoCloseable] — always close when done to free native memory.
 *
 * @param sampleRate    Audio sample rate (typically 48000 for Opus pipeline).
 * @param channels      Channel count (typically 1 for mono voice notes).
 * @param semitones     Pitch shift in semitones. Positive = higher, negative = lower.
 * @param tonalityLimit Fraction of sample rate below which formant structure is
 *                      preserved. Default `8000f / sampleRate` preserves human
 *                      vocal formants below 8 kHz.
 */
internal class StreamingPitchShifter(
    sampleRate: Int = 48_000,
    channels: Int = 1,
    semitones: Float,
    tonalityLimit: Float = 8000f / sampleRate.toFloat()
) : AutoCloseable {

    private var handle: Long = PitchShifterJni.create(sampleRate, channels)

    init {
        require(handle != 0L) { "Failed to create native pitch shifter instance" }
        PitchShifterJni.setTranspose(handle, semitones, tonalityLimit)
    }

    /**
     * Process a chunk of mono PCM 16-bit samples.
     * Returns pitch-shifted samples of the same frame count.
     */
    fun process(samples: ShortArray): ShortArray {
        check(handle != 0L) { "PitchShifter has been destroyed" }
        if (samples.isEmpty()) return ShortArray(0)
        return PitchShifterJni.process(handle, samples, samples.size)
    }

    /**
     * Flush internal buffers at the end of stream.
     * Returns any residual samples held in internal latency buffers.
     */
    fun flush(): ShortArray {
        check(handle != 0L) { "PitchShifter has been destroyed" }
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

- [ ] **Step 3: Verify JNI loading works**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (verifikasi bahwa JNI method names match antara Kotlin `external fun` dan C++ `JNICALL` function signatures).

Verifikasi nama package: JNI function `Java_com_example_audio_PitchShifterJni_create` harus match dengan `com.example.audio.PitchShifterJni.create`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/audio/PitchShifterJni.kt \
        app/src/main/java/com/example/audio/StreamingPitchShifter.kt
git commit -m "feat: add Kotlin JNI bridge and StreamingPitchShifter wrapper"
```

---

## Task 3: Integrate PitchShifter into Conversion Pipeline

**Files:**
- Modify: `app/src/main/java/com/example/audio/VoiceNoteConverter.kt`

**Interfaces:**
- Consumes: `StreamingPitchShifter` (Task 2)
- Produces: `VoiceNoteConverter.convertToTelegramVoiceNote(..., pitchSemitones: Float)` — same return type `AudioConversionResult`, new pitch param

- [ ] **Step 1: Add pitchSemitones parameter to convertToTelegramVoiceNote**

In `VoiceNoteConverter.kt`, modify the function signature at line 513:

```kotlin
// BEFORE:
suspend fun convertToTelegramVoiceNote(
    context: Context,
    inputUri: Uri,
    trimStartMs: Long = 0L,
    trimEndMs: Long = Long.MAX_VALUE,
    onProgress: (Float) -> Unit = {}
): AudioConversionResult = withContext(Dispatchers.IO) {

// AFTER:
suspend fun convertToTelegramVoiceNote(
    context: Context,
    inputUri: Uri,
    trimStartMs: Long = 0L,
    trimEndMs: Long = Long.MAX_VALUE,
    pitchSemitones: Float = 0f,
    onProgress: (Float) -> Unit = {}
): AudioConversionResult = withContext(Dispatchers.IO) {
```

- [ ] **Step 2: Create and manage PitchShifter instance in conversion**

After the `opusEncoder` is created (around line 610), add pitch shifter initialization:

```kotlin
val pitchShifter: StreamingPitchShifter? = if (pitchSemitones != 0f) {
    StreamingPitchShifter(
        sampleRate = TARGET_SAMPLE_RATE,
        channels = TARGET_CHANNELS,
        semitones = pitchSemitones
    )
} else null
```

Add `pitchShifter` to the `finally` cleanup block:

```kotlin
} finally {
    // ... existing cleanup ...
    pitchShifter?.close()
}
```

- [ ] **Step 3: Insert pitch shifting into the PCM processing loop**

In the main decode loop (around line 693-711), modify the chunk processing:

```kotlin
// BEFORE (lines 704-711):
val encodedChunk = pcmProcessor.process(decodedChunk)
if (encodedChunk.isNotEmpty()) {
    encoder.write(encodedChunk)
    encodedSampleCount += encodedChunk.size
    waveformPeaks += encodedChunk
        .maxOf { abs(it.toInt()) }
        .toFloat()
}

// AFTER:
val resampledChunk = pcmProcessor.process(decodedChunk)
val finalChunk = if (pitchShifter != null && resampledChunk.isNotEmpty()) {
    pitchShifter.process(resampledChunk)
} else {
    resampledChunk
}
if (finalChunk.isNotEmpty()) {
    encoder.write(finalChunk)
    encodedSampleCount += finalChunk.size
    waveformPeaks += finalChunk
        .maxOf { abs(it.toInt()) }
        .toFloat()
}
```

- [ ] **Step 4: Flush pitch shifter at end of stream**

After the main decode loop ends (after `isDecoderEos = true` — around line 733), before `decoder.stop()`, add:

```kotlin
// Flush any remaining samples from pitch shifter internal buffers
pitchShifter?.let { shifter ->
    val tail = shifter.flush()
    if (tail.isNotEmpty()) {
        encoder.write(tail)
        encodedSampleCount += tail.size
        waveformPeaks += tail.maxOf { abs(it.toInt()) }.toFloat()
    }
}
```

- [ ] **Step 5: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/audio/VoiceNoteConverter.kt
git commit -m "feat: integrate pitch shifting into conversion pipeline"
```

---

## Task 4: State Management — ViewModel & Pitch

**Files:**
- Modify: `app/src/main/java/com/example/ui/MainViewModel.kt`

**Interfaces:**
- Consumes: `VoiceNoteConverter.convertToTelegramVoiceNote(..., pitchSemitones)` (Task 3)
- Produces: `MainUiState.pitchSemitones`, `MainViewModel.updatePitch(Float)`

- [ ] **Step 1: Add pitchSemitones to MainUiState**

In `MainViewModel.kt`, modify `MainUiState` data class (around line 35):

```kotlin
// ADD new field:
data class MainUiState(
    // ... existing fields ...
    val pitchSemitones: Float = 0f,   // -4.0 to +4.0
)
```

- [ ] **Step 2: Add updatePitch method**

Add after `resetTrim()` method (after line 151):

```kotlin
fun updatePitch(semitones: Float) {
    val snapped = if (kotlin.math.abs(semitones) < 0.15f) 0f else semitones
    val clamped = snapped.coerceIn(-4f, 4f)
    _uiState.update { it.copy(pitchSemitones = clamped) }
    // Update preview playback pitch in real-time
    val factor = 2f.toDouble().pow((clamped / 12f).toDouble()).toFloat()
    audioPlayer.setPitchPreview(factor)
}
```

Tambahkan import: `import kotlin.math.pow` (atau gunakan `Math.pow`)

- [ ] **Step 3: Pass pitchSemitones to VoiceNoteConverter in startConversion**

Modify the `VoiceNoteConverter.convertToTelegramVoiceNote` call in `startConversion()` (around line 195-201):

```kotlin
// BEFORE:
val result = VoiceNoteConverter.convertToTelegramVoiceNote(
    context = context,
    inputUri = uri,
    trimStartMs = if (trim.isActive) trim.startMs else 0L,
    trimEndMs = if (trim.isActive) trim.endMs else Long.MAX_VALUE,
    onProgress = { progress -> _uiState.update { it.copy(progress = progress) } }
)

// AFTER:
val pitch = _uiState.value.pitchSemitones
val result = VoiceNoteConverter.convertToTelegramVoiceNote(
    context = context,
    inputUri = uri,
    trimStartMs = if (trim.isActive) trim.startMs else 0L,
    trimEndMs = if (trim.isActive) trim.endMs else Long.MAX_VALUE,
    pitchSemitones = pitch,
    onProgress = { progress -> _uiState.update { it.copy(progress = progress) } }
)
```

- [ ] **Step 4: Reset pitch when loading new file**

In `handleIncomingUri()` (around line 83), ensure pitchSemitones resets:

```kotlin
// The existing code already resets via:
_uiState.value = MainUiState(selectedFileUri = uri, processStatus = ProcessStatus.ANALYZING)
// This naturally resets pitchSemitones to 0f (default).
// Also reset ExoPlayer pitch:
audioPlayer.setPitchPreview(1f)
```

- [ ] **Step 5: Store pitch in ConversionHistory**

In `startConversion()`, tambahkan `pitchSemitones` ke `ConversionHistory` yang disimpan (around line 209). Ini opsional — hanya untuk tracking, data class `ConversionHistory` perlu field baru:

```kotlin
// Tambahkan ke ConversionHistory entity:
val pitchSemitones: Float? = null
```

Dan di insert:
```kotlin
val historyItem = ConversionHistory(
    // ... existing fields ...
    pitchSemitones = pitch.takeIf { it != 0f },
    // ...
)
```

**Catatan:** Ini memerlukan Room database migration (version increment). Karena field nullable dengan default null, bisa pakai `AutoMigration` atau manual `ALTER TABLE`.

- [ ] **Step 6: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/ui/MainViewModel.kt \
        app/src/main/java/com/example/data/local/ConversionHistory.kt \
        app/src/main/java/com/example/data/local/AppDatabase.kt
git commit -m "feat: add pitch state management and pass to conversion pipeline"
```

---

## Task 5: AudioPreviewPlayer — Pitch Preview Support

**Files:**
- Modify: `app/src/main/java/com/example/audio/AudioPreviewPlayer.kt`

**Interfaces:**
- Consumes: ExoPlayer `PlaybackParameters`
- Produces: `AudioPreviewPlayer.setPitchPreview(factor: Float)`

- [ ] **Step 1: Add setPitchPreview method**

In `AudioPreviewPlayer.kt`, add method:

```kotlin
/**
 * Set pitch preview factor for real-time listening.
 * Factor 1.0 = normal pitch, > 1.0 = higher, < 1.0 = lower.
 * Uses ExoPlayer's built-in Sonic algorithm (no formant preservation).
 */
fun setPitchPreview(factor: Float) {
    val currentSpeed = player.playbackParameters.speed
    player.playbackParameters = PlaybackParameters(currentSpeed, factor.coerceIn(0.25f, 4f))
}
```

- [ ] **Step 2: Reset pitch on stop/release**

Ensure pitch resets when player stops or new media loads. In `stop()` method, add:

```kotlin
fun stop() {
    player.stop()
    player.clearMediaItems()
    player.playbackParameters = PlaybackParameters.DEFAULT  // reset speed + pitch
    // ... existing cleanup ...
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/audio/AudioPreviewPlayer.kt
git commit -m "feat: add pitch preview support to AudioPreviewPlayer"
```

---

## Task 6: PitchControl UI Component

**Files:**
- New: `app/src/main/java/com/example/ui/components/PitchControl.kt`

**Interfaces:**
- Consumes: Nothing
- Produces: `PitchControl(pitchSemitones, onPitchChange, enabled, modifier)` composable

- [ ] **Step 1: Create PitchControl.kt**

Buat `app/src/main/java/com/example/ui/components/PitchControl.kt`:

```kotlin
package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.ui.theme.AccentCoral
import com.example.ui.theme.AccentRoyalBlue
import com.example.ui.theme.DeepNavyDisplay
import com.example.ui.theme.SubtitleSlate
import com.example.ui.theme.SubtleBorder
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Pitch adjustment slider with label and value display.
 *
 * Range: -4.0 to +4.0 semitones.
 * Snaps to 0.0 when within ±0.15 semitones of center.
 *
 * @param pitchSemitones Current pitch value in semitones.
 * @param onPitchChange  Callback when pitch changes.
 * @param enabled        Whether the slider is interactive.
 */
@Composable
fun PitchControl(
    pitchSemitones: Float,
    onPitchChange: (Float) -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    Column(modifier.fillMaxWidth()) {
        // Header row: label + current value
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "🎵  Pitch",
                style = MaterialTheme.typography.labelLarge,
                color = DeepNavyDisplay
            )
            Text(
                text = formatPitch(pitchSemitones),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = if (pitchSemitones == 0f) SubtitleSlate else AccentRoyalBlue
            )
        }

        // Slider
        Slider(
            value = pitchSemitones,
            onValueChange = { raw ->
                // Round to 0.1 increments
                val rounded = (raw * 10f).roundToInt() / 10f
                // Snap to zero
                val snapped = if (abs(rounded) < 0.15f) 0f else rounded
                onPitchChange(snapped)
            },
            valueRange = -4f..4f,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            colors = SliderDefaults.colors(
                thumbColor = AccentCoral,
                activeTrackColor = AccentCoral,
                inactiveTrackColor = SubtleBorder
            )
        )

        // Range labels
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("-4 st", style = MaterialTheme.typography.labelSmall, color = SubtitleSlate)
            Text("0", style = MaterialTheme.typography.labelSmall, color = SubtitleSlate)
            Text("+4 st", style = MaterialTheme.typography.labelSmall, color = SubtitleSlate)
        }
    }
}

private fun formatPitch(semitones: Float): String = when {
    semitones == 0f -> "0 st"
    semitones > 0f -> "+%.1f st".format(semitones)
    else -> "%.1f st".format(semitones)
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/ui/components/PitchControl.kt
git commit -m "feat: add PitchControl UI slider composable"
```

---

## Task 7: Restructure PreviewCard Layout & Integrate Pitch

**Files:**
- Modify: `app/src/main/java/com/example/ui/MainScreen.kt`

**Interfaces:**
- Consumes: `PitchControl` (Task 6), `MainUiState.pitchSemitones`, `MainViewModel.updatePitch` (Task 4)
- Produces: Restructured PreviewCard with full-width waveform, separated playback controls, tools section with trim + pitch

- [ ] **Step 1: Add pitchSemitones and onPitchChange to PreviewCard parameters**

Modify `PreviewCard` function signature to add:

```kotlin
@Composable
internal fun PreviewCard(
    // ... existing params ...
    pitchSemitones: Float,            // NEW
    onPitchChange: (Float) -> Unit,   // NEW
    playbackError: String?
)
```

- [ ] **Step 2: Restructure PreviewCard layout**

Replace the current body of `PreviewCard` with the new layout structure. Key changes:

**A. Waveform — full width (baris sendiri):**

```kotlin
// BEFORE: waveform di dalam Row bersama play button dan durasi text
// AFTER: waveform full width di baris terpisah

// Header (icon + title + filename) — tetap sama
Row(...) { /* existing header */ }

Spacer(Modifier.height(14.dp))

// Waveform — full width
WaveformVisualizer(
    waveform = waveform,
    progress = progress,
    modifier = Modifier.fillMaxWidth().testTag("preview_waveform"),
    activeColor = if (hasConvertedResult) AccentRoyalBlue else AccentCoral,
    inactiveColor = SubtleBorder,
    playheadColor = AccentCoral,
    height = 58.dp,
    onSeek = onSeek,
    trimRange = trimState.takeIf { it.isActive && !hasConvertedResult }?.trimRange,
    onTrimRangeChange = onTrimRangeChange,
    showTrimHandles = trimState.isActive && !hasConvertedResult,
    isInteractive = processStatus != ProcessStatus.CONVERTING &&
        processStatus != ProcessStatus.SENDING &&
        processStatus != ProcessStatus.ANALYZING
)
```

**B. Playback controls — baris terpisah di bawah waveform:**

```kotlin
Spacer(Modifier.height(10.dp))

// Play button + time display
Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp)
) {
    IconButton(
        onClick = onTogglePlayback,
        modifier = Modifier
            .size(42.dp)
            .background(AccentCoral, CircleShape)
    ) {
        Icon(
            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
            contentDescription = if (isPlaying) "Pause" else "Putar",
            tint = Color.White
        )
    }
    Text(
        text = "${MainViewModel.formatDuration(
            if (currentPositionMs > 0) (currentPositionMs / 1000).toInt() else 0
        )} / ${MainViewModel.formatDuration(fallbackDurationSec)}",
        style = MaterialTheme.typography.bodySmall,
        color = SubtitleSlate
    )
}
```

**C. Error message — tetap sama:**

```kotlin
playbackError?.let {
    Spacer(Modifier.height(8.dp))
    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
```

**D. Tool controls section (trim + pitch):**

```kotlin
if (!hasConvertedResult && processStatus == ProcessStatus.IDLE) {
    Spacer(Modifier.height(8.dp))
    // Visual separator
    HorizontalDivider(
        modifier = Modifier.padding(vertical = 4.dp),
        color = SubtleBorder,
        thickness = 0.5.dp
    )
    Spacer(Modifier.height(4.dp))

    // Trim controls
    TrimControls(
        trimState = trimState,
        onToggleTrim = onToggleTrim,
        onPreviewTrim = onPreviewTrim,
        onResetTrim = onResetTrim
    )

    // Pitch control
    Spacer(Modifier.height(12.dp))
    PitchControl(
        pitchSemitones = pitchSemitones,
        onPitchChange = onPitchChange,
        enabled = true
    )
}
```

- [ ] **Step 3: Update PreviewCard call site in MainScreen**

In `MainScreen` composable, update the `PreviewCard` call (around line 110) to pass new parameters:

```kotlin
PreviewCard(
    // ... existing params ...
    pitchSemitones = state.pitchSemitones,            // NEW
    onPitchChange = viewModel::updatePitch,            // NEW
    playbackError = playback.errorMessage
)
```

- [ ] **Step 4: Add necessary imports**

Add to `MainScreen.kt` imports:

```kotlin
import androidx.compose.material3.HorizontalDivider
import com.example.ui.components.PitchControl
```

- [ ] **Step 5: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Verify existing tests pass**

Run: `./gradlew test`
Expected: All tests pass. Jika ada test yang memanggil `PreviewCard` secara langsung, update parameter list-nya.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/example/ui/MainScreen.kt
git commit -m "feat: restructure PreviewCard layout, add pitch slider to tool controls"
```

---

## Task 8: Database Migration for pitchSemitones

**Files:**
- Modify: `app/src/main/java/com/example/data/local/ConversionHistory.kt`
- Modify: `app/src/main/java/com/example/data/local/AppDatabase.kt`

**Interfaces:**
- Consumes: Existing Room database
- Produces: Updated schema with `pitchSemitones` column

- [ ] **Step 1: Add pitchSemitones field to ConversionHistory entity**

```kotlin
@Entity(tableName = "conversion_history")
data class ConversionHistory(
    // ... existing fields ...
    val pitchSemitones: Float? = null,   // NEW
    // ...
)
```

- [ ] **Step 2: Increment database version and add migration**

In `AppDatabase.kt`:

```kotlin
// Increment version: e.g. from 2 to 3 (or whatever the current version is)
@Database(
    entities = [ConversionHistory::class, RecentContact::class],
    version = 3,   // <-- increment
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3)  // <-- add
    ]
)
```

Karena kita menambahkan kolom nullable dengan default null, Room `AutoMigration` bisa menangani ini otomatis tanpa migration spec manual.

- [ ] **Step 3: Verify build and migration**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL — Room schema generated successfully.

Run: `./gradlew test`
Expected: All tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/data/local/ConversionHistory.kt \
        app/src/main/java/com/example/data/local/AppDatabase.kt \
        app/schemas/
git commit -m "feat: add pitchSemitones column to ConversionHistory"
```

---

## Task Dependency Graph

```
Task 1 (NDK + Signalsmith)
  └── Task 2 (JNI Bridge + Kotlin Wrapper)
        └── Task 3 (Pipeline Integration)
              └── Task 4 (ViewModel State)
                    ├── Task 5 (AudioPreviewPlayer Pitch)
                    │     └── Task 7 (UI Layout + Integration)
                    ├── Task 6 (PitchControl Component)
                    │     └── Task 7
                    └── Task 8 (DB Migration)
```

**Parallel opportunities:**
- Task 5 dan Task 6 bisa dikerjakan parallel setelah Task 4 selesai
- Task 8 bisa dikerjakan parallel dengan Task 5/6

---

## Verification Checklist (Post-Implementation)

- [ ] `./gradlew assembleDebug` — BUILD SUCCESSFUL
- [ ] `./gradlew test` — all existing tests pass
- [ ] Pitch slider muncul di bawah trim controls, range -4 s/d +4 st
- [ ] Snap to zero bekerja (slider dekat 0 → snap ke tepat 0)
- [ ] Preview pitch real-time saat geser slider
- [ ] Konversi tanpa pitch → hasil identik dengan sebelum fitur ini
- [ ] Konversi dengan pitch +2 st → suara lebih tinggi, natural
- [ ] Konversi dengan pitch -2 st → suara lebih rendah, natural
- [ ] Konversi dengan pitch + trim → keduanya bekerja
- [ ] Layout PreviewCard lebih lapang — waveform full width
- [ ] File 5 menit dengan pitch shift < 10 detik pada mid-range device
- [ ] Tidak crash pada file sangat pendek (< 1 detik)
- [ ] ConversionHistory menyimpan pitchSemitones
