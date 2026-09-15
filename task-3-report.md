# Phase 2 Task 3 — Render, Preview, and Transport

Implemented the shared timeline render contract and rolling AudioTrack preview.

## Delivered

- `TimelineRenderer` contract with `DefaultTimelineRenderer`: renders only clips intersecting the requested half-open frame window, reuses per-source readers, processes bounded 960-frame chunks through `ClipProcessor` → `TrackMixer` → `MasterLimiter`, and closes/invalidate readers and effect state safely.
- `EditorPreviewEngine`: injectable `PreviewAudioSink`, Android `AudioTrack` adapter with minimum-buffer validation and write-error handling, three-second rolling queue, generation checks for seek/mutation cancellation, played-head-derived position, and pause/flush/stop/release cleanup.
- `EditorViewModel` owns preview load/play/pause/seek/invalidation/release. Published timeline mutations reload the immutable session and invalidate queued PCM.
- `EditorTransport` now dispatches live `Play`/`Pause` intents and displays engine playback state.
- Focused deterministic renderer and preview tests; existing editor ViewModel and Compose transport test sets remain green.

## Verification

- RED batch: focused renderer/preview tests failed at compilation because Task 3 contracts were absent.
- GREEN batch: `TimelineRendererTest` and `EditorPreviewEngineTest` pass.
- Focused verification: `TimelineRendererTest` 1/1, `EditorPreviewEngineTest` 1/1, `EditorViewModelTest` 7/7, `EditorScreenTest` 12/12.
- `:app:assembleDebug` — BUILD SUCCESSFUL.

## Risks (max 3)

1. Android playback-head values are platform-driven; device-level underrun behavior still needs the Phase 2 end-to-end gate.
2. Preview uses a three-second queue target; unusually slow decoders may still underrun before Task 4/export work adds further tuning.
3. Source decoder reads are URI-backed and intentionally remain uncached until Phase 3 cleanup caches.
