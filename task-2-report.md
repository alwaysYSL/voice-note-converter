# Phase 2 Task 2 report

Implemented clip DSP, track mixing, master limiting, and independent Signalsmith speed/pitch output sizing.

- ClipProcessor converts PCM16 to normalized float, bypasses neutral speed/pitch, applies speed → pitch → gain → fades, and closes native effect seams safely.
- TrackMixer sums active chunks with volume clamped to 0..1.5 and muted tracks silent.
- MasterLimiter uses bounded gain state, short chunk look-ahead, release smoothing, ceiling clamping, and safe PCM16 conversion.
- StreamingPitchShifter/PitchShifterJni retain the existing equal-frame API and add independent output-frame processing; JNI buffer ownership is released on exceptions.

Verification:

- Direct Kotlin/JUnit harness: 4 focused DSP tests passed.
- `:app:testDebugUnitTest` and `:app:assembleDebug`: not runnable in this shell because the configured Foojay plugin/native Gradle cache is unavailable offline and Android SDK/NDK tools are not installed here.
- Existing `*.Pitch*` tests: not run for the same Gradle environment blocker.

Risks:

1. Native output quality and flush-tail behavior for non-1x tempo require device/ABI verification.
2. Gradle/native assembly still needs to run in a fully provisioned Android environment.

## Fix Round 1

- ClipProcessor now tracks the expected next output frame, recreates effect state after seeks/non-contiguous chunks, drains and trims/pads effect tails to the exact requested boundary, and carries fractional frame remainder when the timeline does not supply an explicit count.
- StreamingPitchShifter now sizes input/output in frames while preserving interleaved sample counts for multichannel streams; its injectable engine seam keeps lifecycle tests JVM-only and native handles exception-safe.

Verification:

- Focused `:app:testDebugUnitTest` (ClipProcessor, StreamingPitchShifter, TrackMixer, MasterLimiter): passed.
- Existing `:app:testDebugUnitTest --tests '*Pitch*'`: passed.
- `:app:assembleDebug --offline`: passed; CMake linked `arm64-v8a`, `armeabi-v7a`, and `x86_64` native ABIs.
- Direct Kotlin/JUnit harness: 6 focused DSP tests passed.

Risks:

1. Native waveform quality remains covered by assembly/API checks rather than a device fixture.
2. Timeline callers must provide exact output frame counts when their mapping is authoritative; fallback carries fractional remainder.
