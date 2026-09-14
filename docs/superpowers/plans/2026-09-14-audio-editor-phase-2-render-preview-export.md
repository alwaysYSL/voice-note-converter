# Audio Editor Phase 2 — Render, Preview, and Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn the Phase 1 timeline into audible mixed preview and cancellable OGG Opus export with volume, mute, fade, pitch, speed, and master limiting.

**Architecture:** A single chunk-based `TimelineRenderer` interprets the immutable session for both preview and export. `PreviewEngine` feeds rolling PCM to `AudioTrack`; `EditorExportWorker` renders the same session manifest through the existing software Opus encoder and Ogg writer. Phase 3 adds offline cleanup caches and draft persistence without changing this renderer contract.

**Tech Stack:** Kotlin, Coroutines, Android MediaExtractor/MediaCodec, AudioTrack, Signalsmith Stretch JNI, libopus, OggOpusWriter, WorkManager, MediaStore, JUnit, Robolectric.

**Spec:** `docs/superpowers/specs/2026-09-14-post-conversion-audio-editor-design.md`

## Global Constraints

- Phase 1 must be complete and its tests green.
- Render mono PCM signed 16-bit at 48,000 Hz.
- Process audio in 20–40 ms chunks; never hold a full five-minute multi-track PCM session in RAM.
- Preview and export must share `TimelineRenderer` and effect ordering.
- Track volume range is 0–150%; mute contributes silence.
- Pitch range is −4 to +4 semitones without duration change.
- Speed range is 0.5×–2× while preserving pitch.
- Fade duration is 0–5,000 ms and never exceeds half the clip duration.
- Export presets are OGG Opus mono 32 kbps and 64 kbps.
- The original conversion result is never overwritten.

---

## File Structure

**Create:**

- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/PcmSourceReader.kt` — seekable chunk decoder abstraction and MediaCodec implementation.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/ClipTimeMapper.kt` — timeline/source frame mapping.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/ClipProcessor.kt` — speed, pitch, gain, and fade chain.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/TrackMixer.kt` — float accumulation and mute/volume.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/MasterLimiter.kt` — peak-safe output conversion.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/TimelineRenderer.kt` — shared chunk renderer.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/EditorPreviewEngine.kt` — rolling buffer and AudioTrack lifecycle.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/EditorRenderManifest.kt` — stable worker snapshot format.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/work/EditorExportWork.kt`
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/work/EditorExportWorker.kt`
- Tests mirroring each production class under `app/src/test/java/com/aistudio/voicenote/cvtr/editor/`.

**Modify:**

- `app/src/main/java/com/aistudio/voicenote/cvtr/audio/StreamingPitchShifter.kt` — expose independent tempo and pitch processing.
- `app/src/main/java/com/aistudio/voicenote/cvtr/audio/PitchShifterJni.kt` — JNI API for time ratio plus pitch.
- `app/src/main/cpp/pitch_shifter_jni.cpp` — configure Signalsmith input/output ratio.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModel.kt` — preview lifecycle and export observation.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorScreen.kt` — live transport and export sheet.
- `app/src/main/java/com/aistudio/voicenote/cvtr/data/local/ConversionHistory.kt` — add nullable `editorSourceHistoryId` for edited exports.
- `app/src/main/java/com/aistudio/voicenote/cvtr/data/local/AppDatabase.kt` — migrate database 4→5 for editor-origin metadata.
- `app/src/main/AndroidManifest.xml` — reuse the existing media-processing foreground service configuration.

---

### Task 1: Seekable PCM Source and Timeline-Time Mapping

**Files:**

- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/PcmSourceReader.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/ClipTimeMapper.kt`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/audio/ClipTimeMapperTest.kt`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/audio/PcmSourceReaderTest.kt`

**Interfaces:**

- Produces: `PcmSourceReader.read(sourceFrame: Long, frameCount: Int): ShortArray` and `close()`.
- Produces: `PcmSourceReaderFactory.open(AudioSourceRef): PcmSourceReader`.
- Produces: `ClipTimeMapper.sourceFrameForTimelineFrame` and `activeOutputRange`.

- [ ] **Step 1: Write failing frame-mapping tests**

```kotlin
@Test fun `half speed maps output frames to half as many source frames`() {
    val mapper = ClipTimeMapper(sourceStartFrame = 48_000, timelineStartFrame = 96_000, speed = 0.5f)
    assertEquals(72_000L, mapper.sourceFrameForTimelineFrame(144_000))
}

@Test fun `reader truncates at the source end`() {
    fake.seekTo(95)
    assertArrayEquals(shortArrayOf(95, 96, 97, 98, 99), fake.read(95, 8))
}
```

- [ ] **Step 2: Run the source tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.ClipTimeMapperTest" --tests "*.PcmSourceReaderTest"`

Expected: FAIL because the reader contract and mapper are absent.

- [ ] **Step 3: Implement the reader contract and MediaCodec adapter**

```kotlin
internal interface PcmSourceReader : AutoCloseable {
    val sampleRate: Int
    fun read(sourceFrame: Long, frameCount: Int): ShortArray
}

internal fun interface PcmSourceReaderFactory {
    fun open(source: AudioSourceRef): PcmSourceReader
}
```

Adapt the extraction/decoding logic currently embedded in `VoiceNoteConverter` without changing existing conversion behavior. Normalize all editor reads to mono 48 kHz PCM16. Seek to the nearest decoder sync point, discard frames before the requested source frame, and return only decoded frames; timeline composition supplies silence for uncovered ranges.

- [ ] **Step 4: Run source and existing converter tests**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.PcmSourceReaderTest" --tests "*.ClipTimeMapperTest" --tests "*.VoiceNoteConverter*"`

Expected: PASS with correct exact-frame reads and no conversion regression.

- [ ] **Step 5: Commit the source reader**

```bash
git add app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/PcmSourceReader.kt app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/ClipTimeMapper.kt app/src/test/java/com/aistudio/voicenote/cvtr/editor/audio app/src/main/java/com/aistudio/voicenote/cvtr/audio/VoiceNoteConverter.kt
git commit -m "feat: add seekable PCM source for editor timeline"
```

### Task 2: Clip Effects, Track Mixing, and Master Limiting

**Files:**

- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/ClipProcessor.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/TrackMixer.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/MasterLimiter.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/audio/StreamingPitchShifter.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/audio/PitchShifterJni.kt`
- Modify: `app/src/main/cpp/pitch_shifter_jni.cpp`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/audio/ClipProcessorTest.kt`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/audio/TrackMixerTest.kt`

**Interfaces:**

- Consumes: `AudioClip`, `ClipEffects`, and PCM16 source chunks.
- Produces: `ClipProcessor.process(clip, sourceFrames, outputStartFrame): FloatArray`.
- Produces: `TrackMixer.mix(chunks: List<TrackChunk>, frameCount: Int): FloatArray`.
- Produces: `MasterLimiter.process(FloatArray): ShortArray`.

- [ ] **Step 1: Write failing deterministic DSP tests**

```kotlin
@Test fun `fade in reaches unity at its boundary`() {
    val output = processor.process(clip(fadeInMs = 100), constantPcm(4_800), 0)
    assertEquals(0f, output.first(), 0.001f)
    assertEquals(1f, output.last(), 0.02f)
}

@Test fun `two loud tracks are limited without wrapping`() {
    val mixed = mixer.mix(listOf(track(30_000), track(30_000)), frameCount = 960)
    val pcm = limiter.process(mixed)
    assertTrue(pcm.all { it in Short.MIN_VALUE..Short.MAX_VALUE })
    assertTrue(pcm.maxOf { it.toInt() } > 0)
}
```

- [ ] **Step 2: Run DSP tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.ClipProcessorTest" --tests "*.TrackMixerTest"`

Expected: FAIL because processing and mixing classes do not exist.

- [ ] **Step 3: Implement the effect and mixer chain**

Process each clip in this order: source PCM → speed → pitch → clip gain/fade → track gain/mute. Convert PCM16 to normalized float before accumulation. Configure Signalsmith with independent input/output ratio and semitone transpose; bypass it when speed is `1f` and pitch is `0f`. Use a short look-ahead peak limiter with release smoothing, then clamp and convert to PCM16.

```kotlin
internal data class TrackChunk(val samples: FloatArray, val volume: Float, val muted: Boolean)

internal class TrackMixer {
    fun mix(chunks: List<TrackChunk>, frameCount: Int): FloatArray
}

internal class MasterLimiter(
    private val ceiling: Float = 0.891f // -1 dBFS
) {
    fun process(input: FloatArray): ShortArray
}
```

- [ ] **Step 4: Run Kotlin DSP tests and native build tests**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.ClipProcessorTest" --tests "*.TrackMixerTest" --tests "*.Pitch*"`

Run: `gradlew.bat :app:assembleDebug`

Expected: PASS; native libraries link for all configured ABIs and bypass output remains sample-identical when effects are neutral.

- [ ] **Step 5: Commit DSP and mixing**

```bash
git add app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio app/src/test/java/com/aistudio/voicenote/cvtr/editor/audio app/src/main/java/com/aistudio/voicenote/cvtr/audio/StreamingPitchShifter.kt app/src/main/java/com/aistudio/voicenote/cvtr/audio/PitchShifterJni.kt app/src/main/cpp/pitch_shifter_jni.cpp
git commit -m "feat: add clip DSP track mixing and master limiter"
```

### Task 3: Shared Timeline Renderer and AudioTrack Preview

**Files:**

- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/TimelineRenderer.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/EditorPreviewEngine.kt`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/audio/TimelineRendererTest.kt`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/audio/EditorPreviewEngineTest.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModel.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorScreen.kt`

**Interfaces:**

- Produces: `TimelineRenderer.render(session, startFrame, frameCount): ShortArray`.
- Produces: `EditorPreviewEngine.load`, `play`, `pause`, `seekTo`, `invalidateFrom`, `release`.
- Produces: `StateFlow<EditorPlaybackState>` with played-frame-derived position.

- [ ] **Step 1: Write failing overlap and seek tests**

```kotlin
@Test fun `renderer mixes only clips active in requested window`() {
    val pcm = renderer.render(overlappingSession(), startFrame = 48_000, frameCount = 960)
    assertArrayEquals(expectedLimitedMix(), pcm)
    assertEquals(setOf("voice", "music"), sourceFactory.openedSources)
}

@Test fun `seek discards queued generation and starts at requested frame`() = runTest {
    engine.load(session())
    engine.play()
    engine.seekTo(120_000)
    assertEquals(120_000, engine.state.value.positionMs)
    assertEquals(2, fakeSink.generationCount)
}
```

- [ ] **Step 2: Run renderer/preview tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.TimelineRendererTest" --tests "*.EditorPreviewEngineTest"`

Expected: FAIL because renderer and preview engine are absent.

- [ ] **Step 3: Implement chunk scheduling and rolling preview**

```kotlin
internal interface TimelineRenderer : AutoCloseable {
    fun render(session: EditorSession, startFrame: Long, frameCount: Int): ShortArray
    fun invalidate(sourceIds: Set<String> = emptySet())
}

internal data class EditorPlaybackState(
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null
)
```

Use a dedicated render dispatcher, 960–1,920 frame chunks, and an `AudioTrack` streaming sink. Maintain two to four seconds of queued audio. A seek or timeline mutation increments a generation id; discard buffers from older generations. Derive `positionMs` from frames accepted/played by AudioTrack. Release readers, AudioTrack, and native effect handles in `EditorViewModel.onCleared`.

- [ ] **Step 4: Run renderer, preview, and editor UI tests**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.TimelineRendererTest" --tests "*.EditorPreviewEngineTest" --tests "*.EditorScreenTest"`

Expected: PASS; playhead state follows the fake sink, stale buffers never play, and UI transport actions call the engine.

- [ ] **Step 5: Commit preview integration**

```bash
git add app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui app/src/test/java/com/aistudio/voicenote/cvtr/editor
git commit -m "feat: add mixed timeline preview engine"
```

### Task 4: Cancellable OGG Export and History Integration

**Files:**

- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/EditorRenderManifest.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/work/EditorExportWork.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/work/EditorExportWorker.kt`
- Create: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/work/EditorExportWorkerTest.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModel.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorScreen.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/data/local/ConversionHistory.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/data/local/AppDatabase.kt`

**Interfaces:**

- Consumes: `TimelineRenderer`, `SoftwareOpusEncoder`, `OggOpusWriter`, `VoiceNoteStorage`, history repository.
- Produces: `EditorExportWork.request(manifestPath, outputName, preset): OneTimeWorkRequest`.
- Produces: worker progress/result keys compatible with `EditorViewModel` observation.

- [ ] **Step 1: Write failing export rollback tests**

```kotlin
@Test fun `successful 64 kbps export inserts one history row`() = runTest {
    val result = runWorker(preset = ExportPreset.HIGH_QUALITY_64)
    assertTrue(result is ListenableWorker.Result.Success)
    assertEquals(64, history.single().bitrateKbps)
    assertTrue(storage.saved.single().endsWith(".ogg"))
}

@Test fun `cancelled export removes partial file and history`() = runTest {
    val worker = runningWorker()
    worker.stop()
    assertTrue(storage.partialFiles.isEmpty())
    assertTrue(history.isEmpty())
}
```

- [ ] **Step 2: Run worker tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.EditorExportWorkerTest"`

Expected: FAIL because export work does not exist.

- [ ] **Step 3: Implement manifest snapshot and transactional export**

Write the immutable session to a private temporary manifest before enqueueing work. The worker validates all sources, renders sequential chunks, reports frame-based progress, encodes at 32 or 64 kbps, writes a private partial OGG, publishes via `VoiceNoteStorage`, validates readable metadata, and only then inserts history. On failure/cancellation, delete the partial/published URI and any inserted history row, following `ConversionWorker.rollback` patterns.

```kotlin
internal object EditorExportWork {
    const val MANIFEST_PATH = "editor.manifestPath"
    const val OUTPUT_NAME = "editor.outputName"
    const val PRESET = "editor.preset"
    const val PROGRESS = "editor.progress"
    const val RESULT_URI = "editor.resultUri"
    const val RESULT_HISTORY_ID = "editor.resultHistoryId"
}
```

Expose file name, preset, estimated size, progress, cancel, retry, and success navigation in the export bottom sheet. Add nullable `editorSourceHistoryId: Long?` to `ConversionHistory`, migrate Room from version 4 to 5, export schema `5.json`, and use the field to label or trace edited results without changing older rows.

- [ ] **Step 4: Run export tests, Room migration tests, and build**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.EditorExportWorkerTest" --tests "*.AppDatabaseMigrationTest"`

Run: `gradlew.bat :app:lintDebug :app:assembleDebug`

Expected: PASS; successful files are playable OGG, only completed exports enter history, and cancellation leaves no partial artifacts.

- [ ] **Step 5: Commit export integration**

```bash
git add app/src/main/java/com/aistudio/voicenote/cvtr/editor app/src/test/java/com/aistudio/voicenote/cvtr/editor app/src/main/java/com/aistudio/voicenote/cvtr/data/local app/schemas app/src/main/AndroidManifest.xml
git commit -m "feat: export edited timelines as OGG Opus"
```

### Task 5: Phase 2 End-to-End Gate

**Files:**

- Modify only files required by failures discovered in this gate.

**Interfaces:**

- Consumes: all Phase 2 deliverables.
- Produces: a complete editor without cleanup effects or persistent drafts.

- [ ] **Step 1: Run complete automated verification**

Run: `gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`

Expected: BUILD SUCCESSFUL with zero failed tests and no new lint errors.

- [ ] **Step 2: Test five-track playback on a device**

Build a five-minute session with five overlapping tracks. Seek repeatedly, adjust volume/mute, move and split clips, change fade/pitch/speed, background and foreground the app, and confirm playback remains synchronized without a crash or persistent underrun.

- [ ] **Step 3: Compare preview against both exports**

Export the same session at 32 and 64 kbps. Confirm duration matches the timeline within one Opus frame, silence/gaps and fades occur at the same positions, and both files play through the app and Android share flow.

- [ ] **Step 4: Commit only when the gate produced code changes**

```bash
git add app/src app/build.gradle.kts
git commit -m "fix: stabilize editor preview and export"
```

Do not create an empty commit when verification needs no fixes.
