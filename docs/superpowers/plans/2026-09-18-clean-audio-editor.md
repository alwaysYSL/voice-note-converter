# Clean 3-Track Audio Editor & Mixer Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (- [ ]) syntax for tracking.

**Goal:** Build a robust, lightweight 3-track audio editor and mixer module that allows users to trim, pitch-shift, position, mix up to 3 audio clips, and export to Telegram-compatible OGG Opus without affecting the existing converter engine.

**Architecture:** Layered, modular design with zero converter interference. Audio clips are cached as 16-bit 48kHz Mono PCM for zero-latency multi-track preview via Android AudioTrack. Mixing and export feed directly into the proven OggOpusWriter and WaveformAccumulator.

**Tech Stack:** Kotlin, Jetpack Compose, Android AudioTrack / MediaCodec, Kotlin Coroutines & Flow, Room Database, Libsoundtouch (PitchShifterJni), Libopus (OggOpusWriter).

## Global Constraints

- Android Min SDK: 24, Target SDK: 36, Compile SDK: 36 (minorApiLevel = 1).
- Audio format: 48,000 Hz, 16-bit PCM Mono, Ogg Opus output.
- Maximum 3 track slots in timeline.
- 1 Clip per track slot.
- Zero modifications to the core conversion engine logic (VoiceNoteConverter).
- Package root: com.aistudio.voicenote.cvtr.editor.

---

### Task 1: Timeline Data Models & Unit Tests

**Files:**
- Create: pp/src/main/java/com/aistudio/voicenote/cvtr/editor/model/EditorModels.kt
- Test: pp/src/test/java/com/aistudio/voicenote/cvtr/editor/model/EditorModelsTest.kt

**Interfaces:**
- Produces: AudioTrackClip, EditorTimelineState

- [ ] **Step 1: Write the failing unit test for data models**

`kotlin
package com.aistudio.voicenote.cvtr.editor.model

import android.net.Uri
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class EditorModelsTest {

    @Test
    fun testAudioTrackClipCalculations() {
        val clip = AudioTrackClip(
            sourceUri = Uri.parse("content://media/audio/1"),
            displayName = "VoiceNote.ogg",
            pcmCacheFile = File("/tmp/pcm1"),
            durationMs = 10000L,
            startOffsetMs = 2000L,
            trimStartMs = 1000L,
            trimEndMs = 6000L,
            pitchSemitones = 2f,
            volumeGain = 1.2f
        )

        assertEquals(5000L, clip.activeDurationMs)
        assertEquals(7000L, clip.timelineEndMs)
    }

    @Test
    fun testEditorTimelineStateTotalDuration() {
        val clip1 = AudioTrackClip(
            sourceUri = Uri.parse("content://media/audio/1"),
            displayName = "Track1",
            pcmCacheFile = File("/tmp/pcm1"),
            durationMs = 5000L,
            startOffsetMs = 0L,
            trimStartMs = 0L,
            trimEndMs = 5000L
        )
        val clip2 = AudioTrackClip(
            sourceUri = Uri.parse("content://media/audio/2"),
            displayName = "Track2",
            pcmCacheFile = File("/tmp/pcm2"),
            durationMs = 4000L,
            startOffsetMs = 3000L,
            trimStartMs = 0L,
            trimEndMs = 4000L
        )

        val state = EditorTimelineState(
            tracks = listOf(clip1, clip2, null)
        )

        assertTrue(state.hasActiveTracks)
        assertEquals(7000L, state.totalDurationMs)
    }
}
`

- [ ] **Step 2: Run test to verify it fails**

Run: ./gradlew testDebugUnitTest --tests "com.aistudio.voicenote.cvtr.editor.model.EditorModelsTest"
Expected: Compilation failure (classes not defined)

- [ ] **Step 3: Implement EditorModels.kt**

`kotlin
package com.aistudio.voicenote.cvtr.editor.model

import android.net.Uri
import java.io.File
import java.util.UUID

data class AudioTrackClip(
    val id: String = UUID.randomUUID().toString(),
    val sourceUri: Uri,
    val displayName: String,
    val pcmCacheFile: File,
    val durationMs: Long,
    val waveformPoints: List<Float> = emptyList(),
    val startOffsetMs: Long = 0L,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = durationMs,
    val pitchSemitones: Float = 0f,
    val volumeGain: Float = 1.0f
) {
    val activeDurationMs: Long 
        get() = (trimEndMs - trimStartMs).coerceAtLeast(0L)
        
    val timelineEndMs: Long 
        get() = startOffsetMs + activeDurationMs
}

data class EditorTimelineState(
    val tracks: List<AudioTrackClip?> = listOf(null, null, null),
    val selectedTrackIndex: Int? = null,
    val playheadPositionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val isLoadingSource: Boolean = false,
    val isExporting: Boolean = false,
    val exportProgress: Float = 0f,
    val exportedFileUri: Uri? = null,
    val errorMessage: String? = null
) {
    val totalDurationMs: Long 
        get() = tracks.filterNotNull().maxOfOrNull { it.timelineEndMs } ?: 0L
        
    val hasActiveTracks: Boolean 
        get() = tracks.any { it != null }
}
`

- [ ] **Step 4: Run test to verify it passes**

Run: ./gradlew testDebugUnitTest --tests "com.aistudio.voicenote.cvtr.editor.model.EditorModelsTest"
Expected: PASS

- [ ] **Step 5: Commit**

`ash
git add app/src/main/java/com/aistudio/voicenote/cvtr/editor/model/EditorModels.kt app/src/test/java/com/aistudio/voicenote/cvtr/editor/model/EditorModelsTest.kt
git commit -m "feat(editor): add timeline data models and unit tests"
`

---

### Task 2: PCM Decoding & Waveform Extraction Engine

**Files:**
- Create: pp/src/main/java/com/aistudio/voicenote/cvtr/editor/engine/EditorPcmDecoder.kt
- Test: pp/src/test/java/com/aistudio/voicenote/cvtr/editor/engine/EditorPcmDecoderTest.kt

**Interfaces:**
- Consumes: Android MediaExtractor, MediaCodec
- Produces: EditorPcmDecoder.decodeToPcm(context, sourceUri, outputPcmFile): DecodeResult

- [ ] **Step 1: Write test for EditorPcmDecoder**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement EditorPcmDecoder**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 3: Real-Time Multi-Track Audio Engine (AudioTrack & Mixer)

**Files:**
- Create: pp/src/main/java/com/aistudio/voicenote/cvtr/editor/engine/EditorAudioEngine.kt
- Test: pp/src/test/java/com/aistudio/voicenote/cvtr/editor/engine/EditorAudioEngineTest.kt

**Interfaces:**
- Consumes: EditorTimelineState, StreamingPitchShifter, Android AudioTrack
- Produces: EditorAudioEngine(onPlayheadUpdated: (Long) -> Unit, onPlaybackFinished: () -> Unit)

- [ ] **Step 1: Write unit test for mixing math and buffer clamping**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement EditorAudioEngine**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 4: Timeline Exporter (OGG Opus Telegram Output)

**Files:**
- Create: pp/src/main/java/com/aistudio/voicenote/cvtr/editor/engine/EditorExporter.kt
- Test: pp/src/test/java/com/aistudio/voicenote/cvtr/editor/engine/EditorExporterTest.kt

**Interfaces:**
- Consumes: EditorTimelineState, OggOpusWriter, WaveformAccumulator, VoiceNoteStorage, ConversionHistoryRepository
- Produces: EditorExporter.exportTimeline(state, onProgress): Result<Uri>

- [ ] **Step 1: Write unit test for export rendering**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement EditorExporter**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 5: Editor ViewModel & Session Orchestration

**Files:**
- Create: pp/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModel.kt
- Test: pp/src/test/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModelTest.kt

**Interfaces:**
- Produces: EditorViewModel(application, historyRepository)

- [ ] **Step 1: Write test for track addition, reordering, trim, volume, and playback state**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement EditorViewModel**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 6: Timeline UI Canvas & Handle Gestures

**Files:**
- Create: pp/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/TimelineCanvas.kt
- Test: pp/src/test/java/com/aistudio/voicenote/cvtr/editor/ui/TimelineCanvasTest.kt

**Interfaces:**
- Produces: @Composable fun TimelineCanvas(...)

- [ ] **Step 1: Write test for TimelineCanvas rendering & gestures**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement TimelineCanvas**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 7: Editor Screen, Toolbars & Source Picker BottomSheet

**Files:**
- Create: pp/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorScreen.kt
- Create: pp/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorToolbarSheet.kt
- Create: pp/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/AudioSourcePickerSheet.kt
- Test: pp/src/test/java/com/aistudio/voicenote/cvtr/editor/ui/EditorScreenTest.kt

- [ ] **Step 1: Write UI tests for EditorScreen**
- [ ] **Step 2: Run test to verify it fails**
- [ ] **Step 3: Implement EditorScreen, EditorToolbarSheet, and AudioSourcePickerSheet**
- [ ] **Step 4: Run test to verify it passes**
- [ ] **Step 5: Commit**

---

### Task 8: Navigation Integration (Converter, History, Bottom Navigation)

**Files:**
- Modify: pp/src/main/java/com/aistudio/voicenote/cvtr/ui/AppNavigation.kt
- Modify: pp/src/main/java/com/aistudio/voicenote/cvtr/ui/MainScreen.kt
- Modify: pp/src/main/java/com/aistudio/voicenote/cvtr/ui/HistoryScreen.kt
- Test: pp/src/test/java/com/aistudio/voicenote/cvtr/ui/AppNavigationIntegrationTest.kt

- [ ] **Step 1: Add "Edit & Mix" button on MainScreen conversion result card**
- [ ] **Step 2: Add "Buka di Editor" action in HistoryScreen**
- [ ] **Step 3: Add Editor tab in AppNavigation bottom bar**
- [ ] **Step 4: Verify integration tests**
- [ ] **Step 5: Commit**

---

### Task 9: Full Verification & Split APKs Build

- [ ] **Step 1: Run full unit test suite ./gradlew testDebugUnitTest**
- [ ] **Step 2: Build Debug Split APKs ./gradlew assembleDebug**
- [ ] **Step 3: Build Release Split APKs ./gradlew assembleRelease**
- [ ] **Step 4: Verify generated APK sizes and files**