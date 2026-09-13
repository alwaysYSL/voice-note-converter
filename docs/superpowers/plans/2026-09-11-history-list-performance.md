# History List and Performance Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the history menu scalable for many converted files with compact rows, search/filter/sort, multi-select deletion, collapsible date groups, and smoother scrolling/playback.

**Architecture:** Keep Room as the source of truth, transform and sort history in a pure Kotlin presentation layer on `Dispatchers.Default`, and expose the visible list plus summary state from `HistoryViewModel`. `HistoryScreen` will render compact lazy rows, cache derived sections and waveform parsing, and only render the waveform for the active item.

**Tech Stack:** Kotlin, Android Jetpack Compose, Material 3, Room Flow, Kotlin Coroutines, Robolectric, Compose UI tests.

**Spec:** `docs/superpowers/specs/2026-09-10-voice-note-v2-improvements-design.md` (FT-10 baseline), plus the user-approved compact-list/file-manager design in this conversation.

## Global Constraints

- Preserve existing local changes in `master`; do not reset or overwrite unrelated work.
- Keep existing single-item playback, share, swipe-delete, missing-file handling, and empty-state behavior working.
- Search matches both `originalFileName` and `outputFileName`, case-insensitively.
- Bulk deletion must remove physical files when possible and remove database records when the file is already missing.
- No new dependency is required for this iteration; use `LazyColumn` with stable keys and background list transforms.
- Do not claim scroll performance is fixed without running the available build/tests and recording the environment limitation if profiling is unavailable.

### Task 1: Add pure history presentation transforms

**Files:**
- Create: `app/src/main/java/com/example/ui/HistoryPresentation.kt`
- Test: `app/src/test/java/com/example/ui/HistoryPresentationTest.kt`

**Interfaces:**
- Produces `HistoryFilter`, `HistorySort`, `HistorySection`, `filterAndSortHistory(...)`, and `groupHistoryItems(...)` for the ViewModel and screen.

- [x] **Step 1: Write the failing tests**

Add tests for case-insensitive filename matching, sent/not-sent filtering, deterministic newest/oldest/name/size sorting, and date grouping order.

- [x] **Step 2: Run the focused test to verify it fails**

Run: `gradle test --tests "com.example.ui.HistoryPresentationTest"`

Expected: FAIL because the presentation types and functions do not exist yet.

- [x] **Step 3: Write the minimal implementation**

Implement pure functions that:

```kotlin
enum class HistoryFilter { ALL, SENT, NOT_SENT }
enum class HistorySort { NEWEST, OLDEST, NAME, SIZE }

data class HistorySection(
    val title: String,
    val items: List<ConversionHistory>
)

fun filterAndSortHistory(
    items: List<ConversionHistory>,
    query: String,
    filter: HistoryFilter,
    sort: HistorySort
): List<ConversionHistory>

fun groupHistoryItems(
    items: List<ConversionHistory>,
    now: Long = System.currentTimeMillis()
): List<HistorySection>
```

Keep the existing relative groups (`Hari ini`, `Kemarin`, `Minggu ini`, `Lebih lama`) and preserve the selected sort inside every group.

- [x] **Step 4: Run the focused test to verify it passes**

Run: `gradle test --tests "com.example.ui.HistoryPresentationTest"`

Expected: PASS.

- [x] **Step 5: Refactor only after green**

Use stable lowercase comparisons and shared group-title logic without changing the tested behavior.

### Task 2: Move filtering, sorting, summaries, and bulk deletion into the ViewModel

**Files:**
- Modify: `app/src/main/java/com/example/ui/HistoryViewModel.kt`
- Test: `app/src/test/java/com/example/ui/HistoryViewModelHistoryListTest.kt`

**Interfaces:**
- Consumes `filterAndSortHistory(...)` from Task 1.
- Produces `historyItems`, `searchQuery`, `historyFilter`, `historySort`, `totalHistoryCount`, `totalHistoryBytes`, `updateSearch(...)`, `setFilter(...)`, `setSort(...)`, and `deleteItems(...)`.

- [x] **Step 1: Write the failing tests**

Cover these behaviors:

```kotlin
fun `search and filter state expose only matching history items`()
fun `summary counts all history even when a search is active`()
fun `bulk deletion removes every selected database record when files are missing`()
```

- [x] **Step 2: Run the focused test to verify it fails**

Run: `gradle test --tests "com.example.ui.HistoryViewModelHistoryListTest"`

Expected: FAIL because the new state and bulk method do not exist.

- [x] **Step 3: Write the minimal implementation**

Combine the Room flow and the three controls, then apply the pure transform on `Dispatchers.Default` before `stateIn`. Keep the unfiltered Room flow for the total count and total bytes. Refactor the existing single-delete body into a private suspend helper and have `deleteItems(...)` call it sequentially so file/database behavior remains identical. Emit one summary message after bulk deletion and stop playback if the active item was deleted.

- [x] **Step 4: Run the focused test to verify it passes**

Run: `gradle test --tests "com.example.ui.HistoryViewModelHistoryListTest"`

Expected: PASS.

- [x] **Step 5: Run existing history ViewModel tests**

Run: `gradle test --tests "com.example.ui.HistoryViewModelB1Test" --tests "com.example.ExampleRobolectricTest"`

Expected: Existing deletion and share behavior remains PASS.

### Task 3: Replace the large history cards with the compact scalable UI

**Files:**
- Modify: `app/src/main/java/com/example/ui/HistoryScreen.kt`
- Modify: `app/src/test/java/com/example/ui/HistoryScreenPolishTest.kt`
- Test: `app/src/test/java/com/example/ui/HistoryScreenHistoryListTest.kt`

**Interfaces:**
- Consumes the ViewModel state/actions from Task 2.
- Produces visible search, filter chips, sort menu, collapsible groups, long-press/explicit selection mode, bulk-delete confirmation, and compact rows.

- [x] **Step 1: Write the failing Compose tests**

Add tests that verify:

```kotlin
fun `history exposes search and filter controls`()
fun `search query hides nonmatching rows`()
fun `selection mode shows selected count and bulk delete action`()
fun `collapsed history section hides its rows`()
```

Keep the existing tests for empty state, date groups, status badge, inline actions, and delete confirmation.

- [x] **Step 2: Run the focused Compose tests to verify they fail**

Run: `gradle test --tests "com.example.ui.HistoryScreenHistoryListTest" --tests "com.example.ui.HistoryScreenPolishTest"`

Expected: FAIL because the new controls and compact list behavior are not implemented.

- [x] **Step 3: Write the minimal UI implementation**

Implement the screen in this order:

1. Always show a compact header with total file count and total storage size.
2. Add a single-line search field, three filter chips (`Semua`, `Belum dikirim`, `Sudah dikirim`), and a sort menu (`Terbaru`, `Terlama`, `Nama`, `Ukuran`).
3. Cache `groupHistoryItems(historyItems)` with `remember(historyItems)` so playback progress does not regroup the complete list.
4. Render collapsible section headers with stable keys.
5. Use compact rows with stable item keys; keep playback, overflow actions, swipe-delete, and long-press selection.
6. Render `WaveformVisualizer` only when the row is the active item; cache parsed waveform data only for that active item.
7. Add explicit `Pilih file` mode and a bottom selection action for bulk delete. Clear selection when the source list no longer contains an item.
8. Use the existing confirmation dialog tag for one item and a pluralized message for bulk deletion.
9. Show a separate “Tidak ada hasil” state when history exists but the query/filter matches nothing.

- [x] **Step 4: Run focused tests to verify they pass**

Run: `gradle test --tests "com.example.ui.HistoryScreenHistoryListTest" --tests "com.example.ui.HistoryScreenPolishTest"`

Expected: PASS.

- [x] **Step 5: Refactor after green**

Remove duplicate date/size formatting work from composition, preserve stable keys, and keep the compact row readable at the existing theme sizes.

### Task 4: Full verification and performance evidence

**Files:**
- Modify: `docs/superpowers/plans/2026-09-11-history-list-performance.md`

- [x] **Step 1: Run all available unit tests**

Run: local Gradle 9.3.1 `:app:testDebugUnitTest --rerun-tasks`

Result: PASS, 79 tests, 0 failures, 0 errors. The repository has no Gradle wrapper, so verification used the locally installed Gradle 9.3.1 with Android Studio JDK.

- [x] **Step 2: Review the final diff for scope and regressions**

Run: `git diff --check` and inspect only the history-related files. Confirm no unrelated pre-existing modifications were reverted.

- [x] **Step 3: Run the app or Compose tests with a large fixture**

Compose stress test covers 160 history rows and reaches the tail successfully. Search, collapse, selection, and delete flows are covered by focused tests. No emulator/adb or runtime profiler is available in this environment, so FPS was not measured.

- [x] **Step 4: Report evidence**

Summarize the root cause, files changed, tests run and their results, and any remaining limitation such as the missing Gradle wrapper or unavailable runtime profiler.
