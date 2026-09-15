# Phase 3 Task 4 — Draft UI and maintenance report

## Delivered

- Added durable `draftId` launch routing through `AppNavigation`, `AppViewModelFactory`, and `EditorViewModel`; History now shows a `Draft editor` section only when drafts exist, with open/delete actions.
- Added explicit `Simpan sebagai draft` / `Update draft` actions with serialized repository writes, progress/success/error state, clean-baseline advancement only after success, and failure retention in the editor.
- Added the exact dirty-exit choices `Simpan sebagai draft`, `Buang sesi`, and `Batal`; toolbar back, system back, and gesture back share the same decision path.
- Added missing-source/offline clip rendering, `Ganti file`, duration/readability validation, and rollback of the session/draft when replacement persistence fails.
- Added `EditorMaintenanceWorker` with unique periodic storage-not-low scheduling, canonical editor roots, draft/cache lease reconciliation, aged orphan partial/manifest cleanup, and unreferenced processed-cache eviction. Draft sources, history outputs, and owned in-flight artifacts are excluded.
- Added export low-space estimation and the toolbar Trim nudge so Trim is an accessible non-no-op action.

## Verification

- `:app:compileDebugKotlin` — BUILD SUCCESSFUL.
- Focused Task-4 filter command covering draft UI, History draft actions, source replacement validation, maintenance, migration, draft repository, timeline, editor screen/ViewModel: **57 tests, 0 failures**.
- `:app:assembleDebug` — BUILD SUCCESSFUL for arm64-v8a, armeabi-v7a, and x86_64.
- `git diff --check` — clean.

## Final review Fix Round 1

- Export preset changes now go through command history, participate in dirty-baseline comparisons, and initialize the export UI from the loaded/undone/redone session. Save now publishes the repository's committed normalized session with private source paths and intentionally rebases history at that boundary.
- Cleanup effects persist strength, normalized mode, and algorithm version in draft rows and render manifests. Missing or corrupt cache output is surfaced as `Efek perlu diterapkan ulang`, blocks export, and can be explicitly rebuilt from the persisted configuration.
- Added focused regressions for 64 kbps save/reopen, private-source promotion after external deletion, cache recovery/reapply, and the v6 draft cleanup columns.

Verification:

- `EditorFinalReviewStateTest` — **3 tests, 0 failures**.
- Focused draft/ViewModel/command/manifest/export/UI/migration suite — **65 tests, 0 failures**.
- `:app:assembleDebug` — BUILD SUCCESSFUL for arm64-v8a, armeabi-v7a, and x86_64.

## Risks (max 5)

1. Valid export manifests are conservatively retained until a later maintenance pass; malformed/temporary orphan manifests and unowned aged partials are removed.
2. Full device validation of WorkManager cancellation and URI-provider duration probing remains pending.

## Fix Round 1

- Wired History draft deletion through `AppNavigation` and the real `HistoryViewModel` callback.
- Reworked `CommandHistory` around a durable rendered-content baseline so undo/redo recomputes dirty state while selection/playhead remain transient; initial save failures retain `Simpan sebagai draft`.
- Serialized source replacement from probe through persistence and publish, with durable-draft reload after post-commit/lease failures.
- Draft loads now probe metadata and waveform decode for every private source and mark corrupt/short audio offline.
- Maintenance rethrows cancellation; export rechecks the explicit storage estimate immediately before rendering a partial.

Verification:

- Focused Fix Round 1 suite: **82 tests, 0 failures**.
- `:app:assembleDebug` — BUILD SUCCESSFUL for arm64-v8a, armeabi-v7a, and x86_64.

## Fix Round 1 follow-up

- Replacement persistence failures now reload the complete `EditorDraftLoad`, re-derive missing/corrupt offline clips and source error from durable truth, then retain the replacement failure status/message instead of forcing success or clearing `Ganti file` state.
- Added a pre-commit replacement-failure regression covering a durable draft that still references an offline private source.

Verification:

- Isolated replacement tests: **3 tests, 0 failures**.
- Focused Task-4/ViewModel/maintenance/export suite: **40 tests, 0 failures**.
- `git diff --check` — clean.

## Final cache-promotion and inspection reliability pass

- Processed-audio identity now hashes the complete source bytes only. URI/path, display name, and
  provider metadata no longer affect the cache key; range, PCM format key version, cleanup effect,
  normalize flag, and algorithm version remain part of the canonical key.
- Draft load publishes a synchronous cleanup-cache inspection gate before the suspending source/cache
  validation. Export, draft save, cleanup start, and cleanup reapply are rejected while inspection is
  pending; validation then clears the gate and either preserves the valid key or exposes recovery IDs.
- Added the combined regression for cleanup-before-save -> private source promotion -> reopen, plus the
  immediate export/save rejection while inspection was forced pending.

Verification:

- `git diff --check` — passed.
- `:app:testDebugUnitTest --tests '*.EditorFinalReviewStateTest' --tests '*.EditorViewModelTest' --tests '*.ProcessedAudioCacheTest' --tests '*.CleanupEffectWorkerTest'` —
  blocked during project configuration because this shell has no installed Android NDK; AGP fails in
  `NdkLocatorKt.getNdkVersionedFolders` before tests execute.
- `:app:assembleDebug` — same Android NDK configuration blocker; rerun in a provisioned Android
  environment.

Residual risk: final Gradle test/build evidence still requires the configured SDK/NDK toolchain.

## Final cleanup-inspection completion pass

- Corrected ViewModel cache sample-count validation and storage estimation to use the editor's
  48 kHz sample rate. The cleanup-before-save regression now accepts the canonical one-second
  output instead of silently transitioning to `FAILED` and timing out while waiting for success.
- Draft cleanup inspection now clears its synchronous pending gate on cancellation as well as
  success/failure, retaining persisted cleanup clip IDs and a recovery error for the next load.
- Made the final-review scheduler fake replay terminal work state deterministically and aligned its
  recovery fixture with the canonical 48 kHz output so the test is independent of suite order.

Verification:

- `git diff --check` — passed.
- Isolated `EditorFinalReviewStateTest.cleanup before save keeps cache key across private promotion and reopen` —
  blocked before test execution by the host's missing Android NDK (`NdkLocatorKt.getNdkVersionedFolders`).
- Full `EditorFinalReviewStateTest` and focused ViewModel/cache/worker filters — same NDK configuration
  blocker; no test task was started.
- `:app:assembleDebug` — same NDK configuration blocker; production changes require rerun in a
  provisioned Android environment.

Residual risk: Gradle test and assemble evidence remains pending the configured SDK/NDK toolchain.
