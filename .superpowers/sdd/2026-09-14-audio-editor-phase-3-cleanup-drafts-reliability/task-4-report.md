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

## Risks (max 5)

1. Valid export manifests are conservatively retained until a later maintenance pass; malformed/temporary orphan manifests and unowned aged partials are removed.
2. Full device validation of WorkManager cancellation and URI-provider duration probing remains pending.
