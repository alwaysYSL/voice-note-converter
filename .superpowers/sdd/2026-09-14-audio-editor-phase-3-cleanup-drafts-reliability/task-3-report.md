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
