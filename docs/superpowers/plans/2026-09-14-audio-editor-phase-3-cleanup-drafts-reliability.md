# Audio Editor Phase 3 — Cleanup, Drafts, and Reliability Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Complete the editor with two-pass normalization, RNNoise cleanup presets, processed-audio caching, explicit durable drafts, atomic cleanup, and low-resource recovery behavior.

**Architecture:** Expensive cleanup effects render into content-addressed private cache files and become active only after validation. Explicit draft save copies sources into app-private storage and persists a Room snapshot transactionally; ordinary sessions remain transient. Cache entries are rebuildable and evictable, while draft source copies are durable until the draft is deleted.

**Tech Stack:** Kotlin, Coroutines, Room/KSP, WorkManager, CMake/NDK, RNNoise BSD-3-Clause, MediaCodec, JUnit, Robolectric, Android instrumentation tests.

**Spec:** `docs/superpowers/specs/2026-09-14-post-conversion-audio-editor-design.md`

## Global Constraints

- Phases 1 and 2 must be complete and green.
- Normalize and noise reduction use “Terapkan lalu preview”; failed work never replaces the previous audible source.
- RNNoise presets are Ringan, Sedang, and Kuat and apply to one selected clip or a whole track.
- Cleanup cache is rebuildable LRU data; draft source copies are not cache and must never be auto-evicted.
- Drafts exist only after explicit save.
- Saving, updating, and deleting drafts must be atomic from the user's perspective.
- A saved draft must reopen after original external files are moved or deleted.
- Low-storage checks run before copying sources, rendering cleanup cache, or exporting.
- Orphan manifests and partial files are cleaned without touching valid history outputs or draft sources.

---

## File Structure

**Create:**

- `app/src/main/cpp/rnnoise/` — pinned upstream RNNoise sources and BSD license.
- `app/src/main/cpp/rnnoise_jni.cpp` — mono 48 kHz PCM16 JNI bridge.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/RnNoiseProcessor.kt`
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/PeakNormalizer.kt`
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/cache/ProcessedAudioCache.kt`
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/work/CleanupEffectWork.kt`
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/work/CleanupEffectWorker.kt`
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/data/EditorDraft.kt`
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/data/EditorDraftDao.kt`
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/data/DraftSourceStorage.kt`
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/data/EditorDraftRepository.kt`
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/work/EditorMaintenanceWorker.kt`
- Tests under matching `app/src/test/java/com/aistudio/voicenote/cvtr/editor/` packages.

**Modify:**

- `app/src/main/cpp/CMakeLists.txt` — compile and link RNNoise.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/model/EditorModels.kt` — cleanup cache references and draft id.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/TimelineRenderer.kt` — resolve processed source before real-time effects.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModel.kt` — cleanup, draft, exit, and offline-source state.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorToolSheets.kt` — Cleanup sheet.
- `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorScreen.kt` — save draft and dirty-exit dialog.
- `app/src/main/java/com/aistudio/voicenote/cvtr/ui/HistoryScreen.kt` — Draft editor section.
- `app/src/main/java/com/aistudio/voicenote/cvtr/ui/AppViewModelFactory.kt` — draft/cache dependencies.
- `app/src/main/java/com/aistudio/voicenote/cvtr/data/local/AppDatabase.kt` — draft entities and migration.
- `app/src/main/java/com/aistudio/voicenote/cvtr/VoiceNoteApplication.kt` — schedule maintenance.
- `app/src/main/res/values/strings.xml` — cleanup, draft, missing-source, and storage messages.

---

### Task 1: RNNoise JNI and Two-Pass Peak Normalization

**Files:**

- Create: `app/src/main/cpp/rnnoise/`
- Create: `app/src/main/cpp/rnnoise_jni.cpp`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/RnNoiseProcessor.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/PeakNormalizer.kt`
- Modify: `app/src/main/cpp/CMakeLists.txt`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/audio/CleanupProcessorsTest.kt`

**Interfaces:**

- Produces: `RnNoiseProcessor.process(ShortArray, CleanupStrength): ShortArray` and `close()`.
- Produces: `PeakNormalizer.analyze(Sequence<ShortArray>): NormalizationStats`.
- Produces: `PeakNormalizer.apply(chunk, stats, targetDbFs = -1f): ShortArray`.

- [ ] **Step 1: Vendor a pinned RNNoise revision with its license**

Copy only the sources and model data needed for inference into `app/src/main/cpp/rnnoise/`. Record the exact upstream commit and source URL in `app/src/main/cpp/rnnoise/UPSTREAM.md`. Preserve `COPYING` and do not include training scripts or datasets in the APK build.

- [ ] **Step 2: Write failing processor tests**

```kotlin
@Test fun `normalizer uses one fixed gain for every chunk`() {
    val stats = PeakNormalizer.analyze(sequenceOf(shortArrayOf(1_000), shortArrayOf(10_000)))
    val first = PeakNormalizer.apply(shortArrayOf(1_000), stats)
    val second = PeakNormalizer.apply(shortArrayOf(10_000), stats)
    assertEquals(10, second[0] / first[0])
}

@Test fun `neutral cleanup strength bypasses samples exactly`() {
    val input = speechFixture()
    assertArrayEquals(input, processor.process(input, CleanupStrength.OFF))
}
```

- [ ] **Step 3: Run cleanup tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.CleanupProcessorsTest"`

Expected: FAIL because cleanup processors are absent.

- [ ] **Step 4: Implement JNI framing, presets, and normalization**

```kotlin
internal enum class CleanupStrength(val wetMix: Float) {
    OFF(0f), LIGHT(0.35f), MEDIUM(0.65f), STRONG(1f)
}

internal data class NormalizationStats(val peak: Int, val gain: Float)
```

RNNoise consumes its required 48 kHz mono frame size. Buffer incomplete JNI input and flush the tail with zero padding while returning only real sample count. Blend dry and denoised samples by preset wet mix. Analyze absolute peak across the complete target before applying one fixed gain capped to avoid excessive amplification; target −1 dBFS.

- [ ] **Step 5: Run cleanup tests and native assembly**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.CleanupProcessorsTest"`

Run: `gradlew.bat :app:assembleDebug`

Expected: PASS; RNNoise loads on all configured ABIs, output sample count equals input count, and neutral paths are exact bypasses.

- [ ] **Step 6: Commit cleanup processors**

```bash
git add app/src/main/cpp app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/RnNoiseProcessor.kt app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/PeakNormalizer.kt app/src/test/java/com/aistudio/voicenote/cvtr/editor/audio/CleanupProcessorsTest.kt
git commit -m "feat: add RNNoise and two-pass audio normalization"
```

### Task 2: Processed-Audio Cache and Apply Workflow

**Files:**

- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/cache/ProcessedAudioCache.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/work/CleanupEffectWork.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/work/CleanupEffectWorker.kt`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/cache/ProcessedAudioCacheTest.kt`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/work/CleanupEffectWorkerTest.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/model/EditorModels.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/audio/TimelineRenderer.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModel.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorToolSheets.kt`

**Interfaces:**

- Produces: `ProcessedAudioKey(sourceFingerprint, range, cleanup, normalize)`.
- Produces: `ProcessedAudioCache.find`, `commit`, `release`, and `evictToSize`.
- Produces: cleanup worker results activated through an editor command only after validation.

- [ ] **Step 1: Write failing atomic-cache tests**

```kotlin
@Test fun `failed render never replaces active cache`() = runTest {
    cache.commit(key, validFixture)
    val worker = failingCleanupWorker(key)
    worker.doWork()
    assertEquals(validFixture, cache.find(key))
    assertTrue(cache.partialFiles().isEmpty())
}

@Test fun `eviction keeps referenced entries`() {
    cache.retain(referencedKey)
    cache.evictToSize(0)
    assertNotNull(cache.find(referencedKey))
    assertNull(cache.find(unreferencedKey))
}
```

- [ ] **Step 2: Run cache/worker tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.ProcessedAudioCacheTest" --tests "*.CleanupEffectWorkerTest"`

Expected: FAIL because cache and worker are absent.

- [ ] **Step 3: Implement content-addressed cache and apply semantics**

Render to `<key>.partial`, fsync/close, validate PCM header and sample count, then atomically rename to `<key>.pcm`. Keep reference counts for active session commands and drafts; LRU eviction may remove only unreferenced entries. Resolve cached cleanup before speed, pitch, gain, and fade in `TimelineRenderer`.

```kotlin
internal data class ProcessedAudioKey(
    val sourceFingerprint: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val cleanup: CleanupStrength,
    val normalized: Boolean
)
```

The UI sheet selects clip or track target, normalize toggle, and RNNoise preset. Enqueue work after a storage check. Dispatch `ApplyProcessedSourceCommand` only when the worker succeeds; cancellation/failure preserves the old clip effects and cache reference.

- [ ] **Step 4: Run cache, renderer, command, and UI tests**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.ProcessedAudioCacheTest" --tests "*.CleanupEffectWorkerTest" --tests "*.TimelineRendererTest" --tests "*.CommandHistoryTest" --tests "*.EditorScreenTest"`

Expected: PASS; apply is atomic, undo/redo switches references correctly, and pitch/speed changes reuse the same cleanup cache.

- [ ] **Step 5: Commit cleanup workflow**

```bash
git add app/src/main/java/com/aistudio/voicenote/cvtr/editor app/src/test/java/com/aistudio/voicenote/cvtr/editor
git commit -m "feat: add cached normalize and noise reduction workflow"
```

### Task 3: Room Draft Schema and Atomic Source Storage

**Files:**

- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/data/EditorDraft.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/data/EditorDraftDao.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/data/DraftSourceStorage.kt`
- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/data/EditorDraftRepository.kt`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/data/EditorDraftRepositoryTest.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/data/local/AppDatabase.kt`
- Modify: `app/src/test/java/com/aistudio/voicenote/cvtr/data/local/AppDatabaseMigrationTest.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/model/EditorModels.kt`

**Interfaces:**

- Produces: `EditorDraftRepository.observeAll`, `save`, `load`, and `delete`.
- Produces: `DraftSourceStorage.stageSources`, `commitStage`, and `deleteDraftSources`.
- Produces: Room migration 5→6 and exported schema `app/schemas/com.aistudio.voicenote.cvtr.data.local.AppDatabase/6.json`.

- [ ] **Step 1: Write failing migration and repository tests**

```kotlin
@Test fun `saved draft opens after external sources disappear`() = runTest {
    val id = repository.save(sessionUsingExternalSources())
    externalFiles.deleteAll()
    val restored = repository.load(id)
    assertTrue(restored.tracks.flatMap { it.clips }.all { privateStorage.exists(it.source.uri) })
}

@Test fun `failed source copy preserves previous draft`() = runTest {
    val id = repository.save(validSession())
    copier.failOnSecondSource = true
    assertFails { repository.save(changedSession(draftId = id)) }
    assertEquals(validSession(), repository.load(id))
}
```

- [ ] **Step 2: Run draft/migration tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.EditorDraftRepositoryTest" --tests "*.AppDatabaseMigrationTest"`

Expected: FAIL because draft tables and repository do not exist.

- [ ] **Step 3: Add normalized draft entities and migration**

```kotlin
@Entity(tableName = "editor_drafts")
internal data class EditorDraftEntity(
    @PrimaryKey val id: String,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
    val exportPreset: String
)

@Entity(tableName = "editor_draft_tracks", primaryKeys = ["draftId", "trackId"])
internal data class EditorDraftTrackEntity(
    val draftId: String,
    val trackId: String,
    val sortOrder: Int,
    val name: String,
    val volume: Float,
    val muted: Boolean
)

@Entity(tableName = "editor_draft_clips", primaryKeys = ["draftId", "clipId"])
internal data class EditorDraftClipEntity(
    val draftId: String,
    val trackId: String,
    val clipId: String,
    val sourcePath: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val timelineStartMs: Long,
    val fadeInMs: Long,
    val fadeOutMs: Long,
    val gain: Float,
    val pitchSemitones: Float,
    val speed: Float,
    val processedCacheKey: String?
)
```

Raise `AppDatabase` from version 5 to 6. Use explicit typed columns for timing and effect parameters rather than an opaque serialized blob. Export schema `6.json` and test migration from every supported historical version through `ALL_MIGRATIONS`.

- [ ] **Step 4: Implement staged source copy and transactional repository save**

Check available bytes before copying. Copy sources to `files/editor-drafts/<draftId>.staging/`, validate size/readability, transact Room rows, rename staging to the active draft directory, and remove the previous directory only after success. On delete, remove database rows first inside a transaction, then delete that exact validated draft directory. Never delete a computed path outside `files/editor-drafts`.

- [ ] **Step 5: Run draft, migration, and storage tests**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.EditorDraftRepositoryTest" --tests "*.AppDatabaseMigrationTest"`

Expected: PASS for create/update/load/delete, failed-copy rollback, missing source reporting, and migration.

- [ ] **Step 6: Commit draft persistence**

```bash
git add app/src/main/java/com/aistudio/voicenote/cvtr/editor/data app/src/test/java/com/aistudio/voicenote/cvtr/editor/data app/src/main/java/com/aistudio/voicenote/cvtr/data/local/AppDatabase.kt app/src/test/java/com/aistudio/voicenote/cvtr/data/local/AppDatabaseMigrationTest.kt app/schemas app/src/main/java/com/aistudio/voicenote/cvtr/editor/model/EditorModels.kt
git commit -m "feat: add durable explicit audio editor drafts"
```

### Task 4: Draft UI, Dirty Exit, Missing Sources, and Maintenance

**Files:**

- Create: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/work/EditorMaintenanceWorker.kt`
- Create: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/work/EditorMaintenanceWorkerTest.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorViewModel.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/editor/ui/EditorScreen.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/ui/HistoryScreen.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/ui/AppViewModelFactory.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/cvtr/VoiceNoteApplication.kt`
- Modify: `app/src/main/res/values/strings.xml`
- Test: `app/src/test/java/com/aistudio/voicenote/cvtr/editor/ui/EditorDraftUiTest.kt`

**Interfaces:**

- Consumes: `EditorDraftRepository` and `ProcessedAudioCache`.
- Produces: explicit save/update/delete UI, dirty-exit dialog, Draft editor history section, replace-source flow, and periodic orphan cleanup.

- [ ] **Step 1: Write failing draft UI and maintenance tests**

```kotlin
@Test fun `back with dirty session offers three approved actions`() {
    compose.setContent { EditorScreen(dirtyState(), ::recordIntent) }
    compose.onNodeWithTag("editor_back").performClick()
    compose.onNodeWithText("Simpan sebagai draft").assertExists()
    compose.onNodeWithText("Buang sesi").assertExists()
    compose.onNodeWithText("Batal").assertExists()
}

@Test fun `maintenance removes old partials but preserves draft sources`() = runTest {
    worker.doWork()
    assertFalse(oldPartial.exists())
    assertTrue(validDraftSource.exists())
    assertTrue(historyOutput.exists())
}
```

- [ ] **Step 2: Run UI/maintenance tests and confirm they fail**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.EditorDraftUiTest" --tests "*.EditorMaintenanceWorkerTest"`

Expected: FAIL because the UI and worker are absent.

- [ ] **Step 3: Implement approved draft and recovery UX**

Show the three-option exit dialog only when `session.dirty`. Save uses progress and stays in the editor on failure. Add a Draft editor section to History only when drafts exist, with open and delete actions. A missing draft source renders its track disabled with **Ganti file**; replacement must match or exceed the referenced source duration before committing the new URI.

- [ ] **Step 4: Implement exact-scope maintenance and storage checks**

Schedule unique periodic `EditorMaintenanceWorker` from `VoiceNoteApplication`. Delete only files under validated editor cache/manifest directories that are partial or unreferenced and older than the configured retention window. Query draft source paths and history output paths first and exclude them. Reuse WorkManager's `setRequiresStorageNotLow(true)` and add explicit byte estimates before large foreground operations.

- [ ] **Step 5: Run Phase 3 targeted tests**

Run: `gradlew.bat :app:testDebugUnitTest --tests "*.editor.*" --tests "*.AppDatabaseMigrationTest"`

Expected: PASS; ordinary sessions remain transient, explicit drafts survive source deletion, and maintenance preserves durable data.

- [ ] **Step 6: Commit UI and maintenance**

```bash
git add app/src/main/java/com/aistudio/voicenote/cvtr/editor app/src/test/java/com/aistudio/voicenote/cvtr/editor app/src/main/java/com/aistudio/voicenote/cvtr/ui app/src/main/java/com/aistudio/voicenote/cvtr/VoiceNoteApplication.kt app/src/main/res/values/strings.xml
git commit -m "feat: finish editor draft and maintenance workflows"
```

### Task 5: Final Accessibility, Performance, and Regression Gate

**Files:**

- Modify only files required by measured failures.
- Create: `docs/superpowers/plans/2026-09-14-audio-editor-verification-report.md` — record devices, fixtures, timings, underruns, output durations, and remaining known limitations.

**Interfaces:**

- Consumes: all editor phases.
- Produces: evidence that the design acceptance criteria are met.

- [ ] **Step 1: Run the complete CI-equivalent command**

Run: `gradlew.bat :app:testDebugUnitTest lintDebug assembleDebug`

Expected: BUILD SUCCESSFUL with zero failed tests and no lint errors.

- [ ] **Step 2: Run screenshot verification**

Run: `gradlew.bat :app:verifyRoborazziDebug`

Expected: approved screenshots render without clipped toolbar actions, overlapping labels, or inaccessible selected-state contrast.

- [ ] **Step 3: Verify accessibility semantics**

Confirm every icon-only control has a content description, selected/muted states are announced, touch targets are at least 48 dp, timeline operations have non-gesture alternatives, and TalkBack can reach each track and clip in logical order.

- [ ] **Step 4: Profile the maximum project on low-memory hardware**

Use five overlapping five-minute tracks. Record peak Java/native memory, preview underrun count, seek-to-audio latency, cleanup render duration, and 32/64 kbps export duration. Fail the gate on crashes, ANRs, unbounded memory growth, persistent audio desynchronization, or leftover partial files.

- [ ] **Step 5: Exercise every acceptance flow manually**

Test editor entry from conversion and history; all clip tools; track volume/mute; both delete modes; undo/redo depth; cleanup apply/cancel/fail; both exports; explicit draft save/update/open/delete; missing source replacement; low storage; background/foreground; and Telegram share of edited output.

- [ ] **Step 6: Write the verification report with exact evidence**

Record command exit codes, test counts, device/API/ABI, fixture durations and track count, measured memory/timing values, and any accepted limitation. Do not state that the feature passes unless all mandatory gates above passed.

- [ ] **Step 7: Commit measured fixes and report**

```bash
git add app/src app/build.gradle.kts app/schemas docs/superpowers/plans/2026-09-14-audio-editor-verification-report.md
git commit -m "test: verify post-conversion audio editor"
```
