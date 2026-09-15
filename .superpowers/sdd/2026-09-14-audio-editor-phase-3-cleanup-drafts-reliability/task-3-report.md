# Task 3: Room Draft Schema and Atomic Source Storage

## Implementation

- Added normalized Room entities and DAO for draft metadata, ordered tracks, and typed clip timing/effect fields.
- Raised `AppDatabase` from v5 to v6 with an explicit `MIGRATION_5_6`; exported schema is `app/schemas/com.aistudio.voicenote.cvtr.data.local.AppDatabase/6.json`.
- Added `DraftSourceStorage` with bounded streaming copies, free-space estimates, fsync, size/readability validation, canonical path checks, immutable `v<version>` directories, and no eviction path.
- Added `EditorDraftRepository` with source-version-first save, one Room transaction for snapshot replacement, cache draft leases, explicit missing-private-source reporting, and database-first delete/path-safe source cleanup.
- Ordinary editor sessions remain transient; repository rows and source copies are created only through `save`.

## Tests

- RED batch covered migration tables, source persistence after external deletion, failed source-copy rollback, cache lease preservation, and traversal rejection.
- `:app:testDebugUnitTest --tests "*.EditorDraftRepositoryTest" --tests "*.AppDatabaseMigrationTest"`: PASS (5 tests).
- `:app:assembleDebug`: PASS (native debug libraries linked for configured ABIs).

## Residual Task 4 risks

1. UI must surface `MissingDraftSourcesException`/`EditorDraftLoad.missingPrivateSources` as offline tracks and replacement actions.
2. Maintenance must preserve `files/editor-drafts` and clean only stale staging/recovery artifacts.
3. Draft save/delete UI must serialize concurrent actions and show low-storage/copy failures without creating transient-session rows.

## Fix Round 1: recoverable draft persistence

- Added a shared per-draft mutex across repository instances for staging, Room publication, cache lease transitions, and source cleanup; idle lock entries are removed safely.
- Added Room-truth reconciliation for committed/staging source versions and processed-cache draft leases. Live in-process staging is preserved; orphan versions and stale draft leases are repairable at startup/first operation and through `reconcileDraftStorage()`.
- Draft saves now acquire a provisional processed-cache lease before the Room switch, publish the canonical lease after commit, and release provisional/old leases only after successful transitions. Failed transitions retain protection and surface repair-needed state.
- Enforced distinct-source and bounded-byte limits, including cumulative free-space checks for unknown-size providers, with stage cleanup on failure. Loads require canonical containment, readability, and non-empty private source files.
- Added focused concurrency, reconciliation, source-limit/free-space, and out-of-root load coverage.

### Fix Round 1 verification

- `:app:testDebugUnitTest --tests "*.EditorDraftRepositoryTest" --tests "*.AppDatabaseMigrationTest"`: PASS (9 tests).
- `:app:assembleDebug`: PASS (native debug libraries linked for configured ABIs).

### Fix Round 1 residual Task 4 risks

1. Task 4 should invoke maintenance reconciliation on a lifecycle-safe cadence and surface any lease-repair failure for retry.
2. UI should present explicit missing/corrupt private-source state and low-storage errors without substituting external paths.
3. Process-death recovery is limited to filesystem/lease reconciliation; Task 4 still needs user-visible retry/repair flows for interrupted saves.
