# Audio Editor Phase 1 — Timeline Core Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a navigable post-conversion editor with a validated five-track/five-minute session model, single-clip selection, timeline gestures, core clip edits, and 50-step undo/redo.

**Architecture:** Pure Kotlin editor models and commands are independent of Compose and Android playback. `EditorViewModel` owns one transient `EditorSession`; Compose renders the session and translates gestures into commands. This phase deliberately stops before mixed-audio preview and export, which are added in Phase 2.

**Tech Stack:** Kotlin, Coroutines/StateFlow, Jetpack Compose Canvas, Navigation Compose, Media3 metadata extraction, JUnit, Robolectric, Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-09-14-post-conversion-audio-editor-design.md`

## Global Constraints

- Android minimum SDK remains 24.
- A session contains at most 5 tracks and its timeline duration is at most 300,000 ms.
- Only one clip may be selected at a time.
- Clips may overlap across tracks but not within the same track.
- Editing is non-destructive; source files are never rewritten.
- Ripple delete moves later clips only within the active track.
- Undo/redo retains at most 50 mutating commands.
- Output and audio rendering are outside this phase; keep transport controls disabled until Phase 2 supplies real playback.

---

## File Structure

**Create:**

- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/model/EditorModels.kt` — immutable session, track, clip, effect, selection, and limits.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/model/TimelineOperations.kt` — validated pure timeline transformations.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/command/EditorCommand.kt` — command contract and concrete mutations.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/command/CommandHistory.kt` — bounded execute/undo/redo stack.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModel.kt` — transient session state and command dispatch.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorScreen.kt` — complete screen shell and toolbar.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/TimelineCanvas.kt` — stacked tracks, waveform clips, playhead, pan, zoom, selection, drag and trim handles.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorToolSheets.kt` — fade/pitch/speed sheet shells backed by real model edits.
- `app/src/test/java/com/aistudio/voicenote/cvtr/editor/model/TimelineOperationsTest.kt`
- `app/src/test/java/com/aistudio/voicenote/cvtr/editor/command/CommandHistoryTest.kt`
- `app/src/test/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModelTest.kt`
- `app/src/test/java/com/aistudio/voicenote/cvtr/editor/ui/EditorScreenTest.kt`

**Modify:**

- `app/src/main/java/com/aistudio/voicenote/cvtr/ui/AppNavigation.kt` — add editor route and typed launch arguments.
- `app/src/main/java/com/aistudio/voicenote/cvtr/ui/MainScreen.kt` — expose “Edit lebih lanjut” after conversion.
- `app/src/main/java/com/aistudio/voicenote/cvtr/ui/HistoryScreen.kt` — expose “Edit” for a history item.
- `app/src/main/java/com/aistudio/voicenote/cvtr/ui/AppViewModelFactory.kt` — construct `EditorViewModel`.
- `app/src/main/java/com/aistudio/voicenote/cvtr/data/repository/ConversionHistoryRepository.kt` — provide editor source lookup by history id.

---

### Task 1: Immutable Timeline Model and Invariants

**Files:**

- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/model/EditorModels.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/model/TimelineOperations.kt`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/model/TimelineOperationsTest.kt`

**Interfaces:**

- Produces: `EditorSession`, `EditorTrack`, `AudioClip`, `ClipEffects`, `AudioSourceRef`, `ExportPreset`, `TimelineResult`.
- Produces: `TimelineOperations.addTrack`, `splitClip`, `trimClip`, `moveClip`, `deleteClip`, `setTrackVolume`, and `validate`.

- [ ] **Step 1: Write failing invariant and split tests**

```kotlin
@Test fun `session rejects a sixth track`() {
    val session = sessionWithTracks(5)
    val result = TimelineOperations.addTrack(session, emptyTrack("six"))
    assertEquals(TimelineError.TRACK_LIMIT, (result as TimelineResult.Rejected).reason)
}

@Test fun `split preserves source mapping and resets inner fades`() {
    val clip = clip(sourceStartMs = 1_000, sourceEndMs = 11_000, timelineStartMs = 5_000)
    val updated = TimelineOperations.splitClip(sessionWith(clip), clip.id, 9_000).value
    assertEquals(listOf(1_000L to 5_000L, 5_000L to 11_000L), updated.clipSourceRanges())
    assertEquals(0L, updated.clips[0].fadeOutMs)
    assertEquals(0L, updated.clips[1].fadeInMs)
}
```

- [ ] **Step 2: Run the model tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.TimelineOperationsTest"`

Expected: FAIL because the editor model and operations do not exist.

- [ ] **Step 3: Implement immutable models and validation**

```kotlin
internal const val MAX_TRACKS = 5
internal const val MAX_TIMELINE_MS = 300_000L

internal data class AudioClip(
    val id: String,
    val source: AudioSourceRef,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val timelineStartMs: Long,
    val effects: ClipEffects = ClipEffects()
) {
    val timelineDurationMs: Long
        get() = ((sourceEndMs - sourceStartMs) / effects.speed).toLong()
    val timelineEndMs: Long get() = timelineStartMs + timelineDurationMs
}

internal data class EditorTrack(
    val id: String,
    val name: String,
    val volume: Float = 1f,
    val muted: Boolean = false,
    val clips: List<AudioClip> = emptyList()
)

internal data class EditorSession(
    val id: String,
    val tracks: List<EditorTrack>,
    val selectedClipId: String? = null,
    val playheadMs: Long = 0L,
    val exportPreset: ExportPreset = ExportPreset.VOICE_NOTE_32,
    val dirty: Boolean = false
)
```

Return `TimelineResult.Rejected` for track limit, duration limit, invalid source ranges, same-track overlap, or missing ids. Clamp volume to `0f..1.5f`, pitch to `-4f..4f`, speed to `0.5f..2f`, and each fade to `min(5_000L, clipDuration / 2)`.

- [ ] **Step 4: Run all model tests**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.TimelineOperationsTest"`

Expected: PASS for track/duration limits, overlap rejection, split mapping, trim, move, ripple delete, gap delete, volume bounds, fade bounds, pitch bounds, and speed-derived duration.

- [ ] **Step 5: Commit the model slice**

```bash
git add app/src/main/java/com/aistudio/voicenote/cvtr/editor/model app/src/test/java/com/aistudio/voicenote/cvtr/editor/model
git commit -m "feat: add validated audio editor timeline model"
```

### Task 2: Command History and Core Edit Commands

**Files:**

- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/command/EditorCommand.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/command/CommandHistory.kt`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/command/CommandHistoryTest.kt`

**Interfaces:**

- Consumes: immutable operations from Task 1.
- Produces: `EditorCommand.applyTo(EditorSession): TimelineResult`.
- Produces: `CommandHistory.execute`, `undo`, `redo`, `canUndo`, and `canRedo`.

- [ ] **Step 1: Write failing command-history tests**

```kotlin
@Test fun `undo restores a ripple-deleted clip and shifted siblings`() {
    val history = CommandHistory(initialSession())
    val before = history.session
    history.execute(DeleteClipCommand("track-1", "clip-b", ripple = true))
    history.undo()
    assertEquals(before, history.session)
}

@Test fun `new command after undo clears redo and history is capped`() {
    val history = CommandHistory(initialSession(), capacity = 50)
    repeat(55) { history.execute(SetTrackVolumeCommand("track-1", 0.5f + it / 200f)) }
    history.undo()
    history.execute(SetTrackMutedCommand("track-1", true))
    assertFalse(history.canRedo)
    assertEquals(50, history.undoDepth)
}
```

- [ ] **Step 2: Run the history tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.CommandHistoryTest"`

Expected: FAIL because command types are missing.

- [ ] **Step 3: Implement snapshot-backed commands and bounded history**

```kotlin
internal interface EditorCommand {
    fun applyTo(session: EditorSession): TimelineResult
}

internal class CommandHistory(
    initial: EditorSession,
    private val capacity: Int = 50
) {
    var session: EditorSession = initial
        private set
    private val undo = ArrayDeque<EditorSession>()
    private val redo = ArrayDeque<EditorSession>()

    fun execute(command: EditorCommand): TimelineResult
    fun undo(): EditorSession
    fun redo(): EditorSession
}
```

Implement commands for add/remove track, split, trim, move, ripple/gap delete, fade, pitch, speed, volume, mute, and selection. Keep playhead, viewport, and sheet visibility outside the undo stack unless a command changes rendered audio.

- [ ] **Step 4: Run history and model tests**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.CommandHistoryTest" --tests "*.TimelineOperationsTest"`

Expected: PASS with exact restoration after every audio mutation and a maximum undo depth of 50.

- [ ] **Step 5: Commit command history**

```bash
git add app/src/main/java/com/aistudio/voicenote/cvtr/editor/command app/src/test/java/com/aistudio/voicenote/cvtr/editor/command
git commit -m "feat: add audio editor undo and redo commands"
```

### Task 3: Editor ViewModel, Source Import, and Navigation Entry Points

**Files:**

- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModel.kt`
- Create: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModelTest.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/ui/AppNavigation.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/ui/HistoryScreen.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/ui/AppViewModelFactory.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/data/repository/ConversionHistoryRepository.kt`

**Interfaces:**

- Consumes: Task 1 models and Task 2 history.
- Produces: `EditorLaunchSource.Converted(uri, originalUri, name)` and `EditorLaunchSource.History(historyId)`.
- Produces: `EditorUiState`, `EditorViewModel.dispatch(EditorIntent)`, `EditorViewModel.importTrack(Uri)`.

- [ ] **Step 1: Write failing ViewModel tests**

```kotlin
@Test fun `converted result becomes selected track-one clip`() = runTest {
    val vm = editorViewModel(EditorLaunchSource.Converted(resultUri, originalUri, "voice.ogg"))
    vm.awaitReady()
    assertEquals(1, vm.uiState.value.session.tracks.size)
    assertNotNull(vm.uiState.value.session.selectedClipId)
}

@Test fun `track import starts at playhead and rejects duration beyond limit`() = runTest {
    val vm = readyEditorViewModel(durationMs = 290_000)
    vm.dispatch(EditorIntent.Seek(280_000))
    vm.importTrack(uriWithDuration(30_000))
    assertEquals(EditorMessage.TIMELINE_LIMIT, vm.uiState.value.message)
}
```

- [ ] **Step 2: Run ViewModel tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.EditorViewModelTest"`

Expected: FAIL because the ViewModel and launch source are missing.

- [ ] **Step 3: Implement launch resolution and intent dispatch**

```kotlin
internal sealed interface EditorLaunchSource {
    data class Converted(
        val resultUri: Uri,
        val originalUri: Uri?,
        val displayName: String
    ) : EditorLaunchSource
    data class History(val historyId: Long) : EditorLaunchSource
}

internal data class EditorUiState(
    val loading: Boolean = true,
    val session: EditorSession = EditorSession.empty(),
    val waveformBySource: Map<String, List<Int>> = emptyMap(),
    val message: EditorMessage? = null,
    val activeSheet: EditorSheet? = null
)
```

Resolve history ids with `ConversionHistoryRepository.getById`. Within the same conversion session prefer the original URI; history launches use the stored OGG output. Analyze duration before inserting a source. Retain persistable URI permission when available; do not copy sources until Phase 3 draft save.

Add route `editor?historyId={historyId}` plus an in-memory launch payload for freshly converted source/original URIs. Wire **Edit lebih lanjut** on the converted result card and **Edit** on each history item.

- [ ] **Step 4: Run ViewModel and existing navigation/UI tests**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.EditorViewModelTest" --tests "*.MainViewModelFeatureTest" --tests "*.HistoryScreen*"`

Expected: PASS; editor launches from both sources, import respects playhead and limits, and existing converter/history behavior remains intact.

- [ ] **Step 5: Commit navigation and ViewModel**

```bash
git add app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModel.kt app/src/test/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModelTest.kt app/src/main/java/com/aistudio/voicenote/cvtr/ui/AppNavigation.kt app/src/main/java/com/aistudio/voicenote/cvtr/ui/MainScreen.kt app/src/main/java/com/aistudio/voicenote/cvtr/ui/HistoryScreen.kt app/src/main/java/com/aistudio/voicenote/cvtr/ui/AppViewModelFactory.kt app/src/main/java/com/aistudio/voicenote/cvtr/data/repository/ConversionHistoryRepository.kt
git commit -m "feat: add audio editor navigation and session state"
```

### Task 4: Stacked Timeline UI and Core Editing Controls

**Files:**

- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorScreen.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/TimelineCanvas.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorToolSheets.kt`
- Create: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/ui/EditorScreenTest.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/ui/AppNavigation.kt`

**Interfaces:**

- Consumes: `EditorUiState` and `EditorIntent` from Task 3.
- Produces: Compose semantics tags `editor_timeline`, `clip_<id>`, `track_<id>`, `undo`, `redo`, `add_track`, and `editor_export_disabled`.

- [ ] **Step 1: Write failing Compose UI tests**

```kotlin
@Test fun `single selected clip controls contextual toolbar`() {
    compose.setContent { EditorScreen(stateWithTwoClips(), ::recordIntent) }
    compose.onNodeWithTag("clip-b").performClick()
    compose.onNodeWithTag("clip-a").assertIsNotSelected()
    compose.onNodeWithTag("clip-b").assertIsSelected()
    compose.onNodeWithText("Split").assertIsEnabled()
}

@Test fun `fifth track disables add track`() {
    compose.setContent { EditorScreen(stateWithTracks(5), ::recordIntent) }
    compose.onNodeWithTag("add_track").assertIsNotEnabled()
}
```

- [ ] **Step 2: Run UI tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.EditorScreenTest"`

Expected: FAIL because the editor composables do not exist.

- [ ] **Step 3: Implement the approved Layout A**

```kotlin
@Composable
internal fun EditorScreen(
    state: EditorUiState,
    onIntent: (EditorIntent) -> Unit,
    onPickTrack: () -> Unit,
    onBack: () -> Unit
)
```

Render a top bar, sticky time ruler, horizontally pannable/zoomable stacked timeline, sticky track headers, transport shell, contextual toolbar, and bottom actions. Use `Canvas` waveform bars derived from existing waveform peak data. Keep each interactive target at least 48 dp. Implement tap selection, tap-to-seek, clip drag, trim handles, horizontal pan, pinch zoom, track volume, mute, split, delete-mode menu, fade, pitch, speed, undo, and redo. Show export disabled with explanatory semantics until Phase 2 connects rendering.

- [ ] **Step 4: Run Phase 1 tests and screenshot verification**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.editor.*"`

Run: `gradlew.bat :app:verifyRoborazziDebug`

Expected: editor unit/UI tests pass; screenshot shows all track headers, the selected clip outline, playhead, toolbar, and bottom actions without clipping at the smallest supported test viewport.

- [ ] **Step 5: Commit the Phase 1 UI**

```bash
git add app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui app/src/test/java/com/aistudio/voicenote/cvtr/editor/ui app/src/main/java/com/aistudio/voicenote/cvtr/ui/AppNavigation.kt
git commit -m "feat: add stacked audio editor timeline UI"
```

### Task 5: Phase 1 Regression Gate

**Files:**

- Modify only files required by failures discovered in this gate.

**Interfaces:**

- Consumes: all Phase 1 deliverables.
- Produces: a navigable, editable session UI ready for Phase 2 renderer integration.

- [ ] **Step 1: Run the complete unit suite**

Run: `gradlew.bat :app:testDebugUnitTest`

Expected: BUILD SUCCESSFUL with zero failed tests.

- [ ] **Step 2: Run lint and debug assembly**

Run: `gradlew.bat :app:lintDebug :app:assembleDebug`

Expected: BUILD SUCCESSFUL with no new lint errors.

- [ ] **Step 3: Manually verify the core flow**

Open a converted result, tap **Edit lebih lanjut**, add tracks at different playhead positions, split and trim a clip, test both delete modes, move clips, change volume/mute, and undo/redo all mutations. Confirm the original and converted files remain unchanged.

- [ ] **Step 4: Commit gate-only fixes if any exist**

```bash
git add app/src app/build.gradle.kts
git commit -m "fix: stabilize audio editor timeline core"
```

Do not create an empty commit when the gate requires no fixes.
