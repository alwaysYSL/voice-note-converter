# Voice Note Converter — Polish & Feature Enhancement Design Spec

> **Date:** 2026-09-10
> **Status:** Draft — Awaiting User Review
> **Scope:** Converter UX polish, audio trim, conversion history, Telegram send fix, codebase cleanup

---

## 1. Problem Statement

The Voice Note Converter app converts MP4/MP3 audio into Telegram-compatible OGG Opus voice notes. The current implementation has several UX gaps and critical bugs:

1. **Preview is non-interactive:** No seek/scrub on waveform, no trim capability.
2. **No conversion history:** Converted files are written to temporary cache and lost on next conversion or app clear.
3. **Telegram send always fails:** Missing `<queries>` block in AndroidManifest causes Telegram to be undetectable on Android 11+ (API 30+).
4. **Conversion only runs on Send:** User cannot preview the actual OGG Opus result before sending.
5. **Critical engine bugs:** Fake fallback Opus encoder outputs noise; entire audio decoded into memory risking OOM.
6. **Dead code:** Fake TDLib session manager, unused Firebase/Retrofit dependencies.

---

## 2. Goals

- **G1:** Interactive audio preview with play/pause, seek via waveform drag, and visual playhead.
- **G2:** Audio trim before conversion with draggable start/end handles.
- **G3:** Separate "Convert" and "Send" actions — preview result before sending.
- **G4:** Persistent conversion history with playback, stored in public `VoiceNoteConverter/` folder.
- **G5:** Fix Telegram detection and share intent on Android 11+.
- **G6:** Replace MediaPlayer with Media3 ExoPlayer for reliable OGG Opus playback.
- **G7:** Fix OOM risk by streaming audio decoding in chunks.
- **G8:** Remove dead code and unused dependencies.

---

## 3. User Flow (New)

```
┌─────────────────────────────────────────────────────────┐
│              Floating Bottom Navigation                  │
│  ┌────────────────────┐  ┌────────────────────┐         │
│  │   🎙 Converter     │  │   📋 History        │         │
│  └────────────────────┘  └────────────────────┘         │
└─────────────────────────────────────────────────────────┘

Converter Tab Flow:
  1. Pick File (audio/* or video/* only)
  2. Preview original audio — interactive waveform (tap/drag to seek)
  3. Optional: Set trim markers (start/end handles on waveform)
  4. Tap "Convert" → decode, trim, encode to OGG Opus 32kbps mono
  5. Preview converted result — real Telegram waveform
  6. Select contact → Tap "Send" → Android Intent share to Telegram
  7. Result auto-saved to VoiceNoteConverter/ folder + database

History Tab:
  - Scrollable list of past conversions
  - Each item: file name, date, duration, size, send status
  - Inline play/pause with mini waveform
  - Share button to re-send via Telegram
  - Swipe-to-delete (removes file + DB record)
```

---

## 4. Detailed Design

### 4.1 Interactive Waveform + Play/Pause/Seek

#### AudioPreviewPlayer Refactor

**Current:** Wraps `android.media.MediaPlayer` with coroutine polling.
**New:** Wraps `androidx.media3.exoplayer.ExoPlayer`.

Changes:
- Replace `MediaPlayer` with `ExoPlayer` instance created via `ExoPlayer.Builder(context).build()`
- Set `AudioAttributes` with `USAGE_MEDIA` and `CONTENT_TYPE_MUSIC` for automatic audio focus handling
- Add `seekTo(positionMs: Long)` method
- Replace coroutine polling with `Player.Listener.onPositionDiscontinuity` + periodic position updates via `Handler.postDelayed` (16ms interval for smooth playhead)
- Lifecycle: Accept `CoroutineScope` as constructor parameter (from `viewModelScope`)
- Add `setClipping(startMs: Long, endMs: Long)` for trim preview using `ClippingMediaSource`

Public API:
```kotlin
class AudioPreviewPlayer(context: Context, scope: CoroutineScope) {
    val playbackState: StateFlow<PlaybackState>

    fun play(uri: Uri)
    fun play(file: File)
    fun pause()
    fun resume()
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun setClipping(startMs: Long, endMs: Long)  // for trim preview
    fun clearClipping()
    fun stop()
    fun release()
}

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val progress: Float = 0f  // 0.0..1.0
)
```

#### WaveformVisualizer Upgrade

Add pointer/gesture input to existing `Canvas` composable:

```kotlin
@Composable
fun WaveformVisualizer(
    waveform: List<Int>,
    progress: Float,
    modifier: Modifier = Modifier,
    // NEW parameters:
    onSeek: ((Float) -> Unit)? = null,        // callback with 0.0..1.0 position
    trimRange: ClosedFloatingPointRange<Float>? = null,  // 0.0..1.0 trim region
    onTrimRangeChange: ((ClosedFloatingPointRange<Float>) -> Unit)? = null,
    showPlayhead: Boolean = true,
    showTrimHandles: Boolean = false,
    isInteractive: Boolean = true
)
```

Gesture handling:
- `Modifier.pointerInput`: detect `tap` → calculate relative X position → call `onSeek(position)`
- `Modifier.pointerInput`: detect horizontal `drag` → continuous seek updates
- When `showTrimHandles = true`: render two draggable handle markers, dimmed regions outside trim range

Visual additions:
- **Playhead:** Thin vertical line (2dp, accent color) at current progress position
- **Trim region:** Bars outside `trimRange` rendered at 30% opacity
- **Trim handles:** Small rounded rectangles at start/end of trim region, draggable

Fix negative spacing bug: Cap minimum spacing at 0f, use `ScrollableRow` or adaptive bar count for narrow screens.

---

### 4.2 Audio Trim

#### TrimState

```kotlin
data class TrimState(
    val startMs: Long = 0L,
    val endMs: Long = 0L,         // defaults to totalDurationMs
    val totalDurationMs: Long = 0L,
    val isActive: Boolean = false  // true when user has moved handles
) {
    val startFraction: Float get() = if (totalDurationMs > 0) startMs.toFloat() / totalDurationMs else 0f
    val endFraction: Float get() = if (totalDurationMs > 0) endMs.toFloat() / totalDurationMs else 1f
    val trimmedDurationMs: Long get() = endMs - startMs
}
```

#### Trim UI Components

Below the waveform, show trim controls when a file is loaded:

```
[✂ Trim]  ← toggle trim mode on/off

When active:
  Start: 00:03  ←→  End: 01:45   Duration: 01:42
  [▶ Preview Trim]  [↺ Reset]
```

- **"Preview Trim" button:** Calls `audioPlayer.setClipping(trimStartMs, trimEndMs)` and plays only the selected region
- **"Reset" button:** Clears trim markers back to full duration

#### VoiceNoteConverter Trim Integration

Add trim parameters to the conversion method:

```kotlin
fun convertToTelegramVoiceNote(
    context: Context,
    inputUri: Uri,
    trimStartMs: Long = 0L,       // NEW
    trimEndMs: Long = Long.MAX_VALUE,  // NEW (MAX_VALUE = no trim)
    onProgress: (Float) -> Unit
): AudioConversionResult
```

Implementation:
- After `MediaExtractor` selects audio track, call `extractor.seekTo(trimStartMs, SEEK_TO_CLOSEST_SYNC)`
- During decode loop, stop when presentation time exceeds `trimEndMs`
- Waveform calculation uses only the trimmed PCM samples

---

### 4.3 Conversion Flow Refactor

**Current:** Convert + Send are one action (`startConvertAndSend()`).
**New:** Separate into two distinct actions.

#### MainViewModel Changes

```kotlin
enum class ProcessStatus {
    IDLE,
    ANALYZING,
    CONVERTING,   // convert only
    CONVERTED,    // NEW — conversion done, ready to preview/send
    SENDING,
    SENT,
    FAILED
}
```

New methods:
```kotlin
fun startConversion()   // convert only, save to storage + DB
fun sendConverted()     // share the already-converted file via Telegram
```

Flow:
1. `startConversion()` → status: CONVERTING → calls `VoiceNoteConverter` with trim params → saves OGG to `VoiceNoteConverter/` folder → inserts `ConversionHistory` record → status: CONVERTED
2. In CONVERTED state, UI shows: converted file preview (real waveform, play/pause), Send button enabled
3. `sendConverted()` → status: SENDING → calls `TelegramSender` → updates `ConversionHistory.sentTo/sentAt` → status: SENT

#### UI Changes in MainScreen

- **Pre-conversion:** Show original audio preview + trim controls + "Convert" button
- **Post-conversion (CONVERTED):** Show converted file preview with real Telegram waveform + "Send to Telegram" button + contact selector
- **SENT state:** Show success confirmation + option to go back or view in History

---

### 4.4 Conversion History

#### Database Schema

New Room Entity:

```kotlin
@Entity(tableName = "conversion_history")
data class ConversionHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalFileName: String,
    val outputFileName: String,
    val outputFilePath: String,
    val durationSeconds: Int,
    val fileSizeBytes: Long,
    val waveform: String,           // JSON array string "[12,8,15,31,...]"
    val bitrateKbps: Int,
    val trimStartMs: Long?,
    val trimEndMs: Long?,
    val createdAt: Long,
    val sentTo: String? = null,
    val sentAt: Long? = null
)
```

DAO:

```kotlin
@Dao
interface ConversionHistoryDao {
    @Query("SELECT * FROM conversion_history ORDER BY createdAt DESC")
    fun getAllHistory(): Flow<List<ConversionHistory>>

    @Insert
    suspend fun insert(history: ConversionHistory): Long

    @Query("UPDATE conversion_history SET sentTo = :sentTo, sentAt = :sentAt WHERE id = :id")
    suspend fun updateSendStatus(id: Long, sentTo: String, sentAt: Long)

    @Query("DELETE FROM conversion_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM conversion_history WHERE id = :id")
    suspend fun getById(id: Long): ConversionHistory?
}
```

Database migration: Room version 1 → 2 with auto-migration (additive table, no changes to existing `recent_contacts`).

#### File Storage

- **Target folder:** `Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)` / `VoiceNoteConverter/`
- **Android 10+ (API 29+):** Use `MediaStore.Audio.Media` with `RELATIVE_PATH = "Music/VoiceNoteConverter/"` for scoped storage compliance
- **Android 9 and below:** Direct file write with `WRITE_EXTERNAL_STORAGE` permission (already min SDK 24)
- **File naming:** `VN_yyyyMMdd_HHmmss.ogg`
- **On delete from history:** Delete both the physical file and the database record

New utility class:

```kotlin
object VoiceNoteStorage {
    fun saveToPublicStorage(context: Context, cacheFile: File, fileName: String): Uri
    fun deleteFromStorage(context: Context, filePath: String): Boolean
    fun getStorageFolder(): File
}
```

#### History Screen

New composable: `HistoryScreen`

```kotlin
@Composable
fun HistoryScreen(
    historyItems: List<ConversionHistory>,
    currentlyPlayingId: Long?,
    playbackProgress: Float,
    onPlayPause: (ConversionHistory) -> Unit,
    onShare: (ConversionHistory) -> Unit,
    onDelete: (ConversionHistory) -> Unit
)
```

Each history item card shows:
- Original file name (secondary text)
- Output file name (primary text)
- Duration • File size • Date
- Mini waveform with inline play/pause button
- Share button (re-send via Telegram)
- Swipe-to-delete with confirmation

Empty state: Illustration + "Belum ada konversi. Mulai convert voice note pertama Anda!"

#### HistoryViewModel

Separate ViewModel for the History tab:

```kotlin
class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    val historyItems: StateFlow<List<ConversionHistory>>
    val currentlyPlayingId: StateFlow<Long?>
    val playbackProgress: StateFlow<Float>

    fun playPause(item: ConversionHistory)
    fun shareItem(item: ConversionHistory)
    fun deleteItem(item: ConversionHistory)
}
```

---

### 4.5 Navigation — Floating Bottom Navigation

Replace single-screen architecture with `Scaffold` + bottom navigation:

```kotlin
@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    
    Scaffold(
        bottomBar = {
            NavigationBar {  // Material3
                NavigationBarItem(
                    icon = { Icon(Icons.Default.Mic, "Converter") },
                    label = { Text("Converter") },
                    selected = currentRoute == "converter",
                    onClick = { navController.navigate("converter") }
                )
                NavigationBarItem(
                    icon = { Icon(Icons.Default.History, "History") },
                    label = { Text("Riwayat") },
                    selected = currentRoute == "history",
                    onClick = { navController.navigate("history") }
                )
            }
        }
    ) {
        NavHost(navController, startDestination = "converter") {
            composable("converter") { MainScreen(converterViewModel) }
            composable("history") { HistoryScreen(historyViewModel) }
        }
    }
}
```

Dependency: `androidx.navigation:navigation-compose`

---

### 4.6 Telegram Send Fix

#### AndroidManifest.xml

Add `<queries>` block (required for Android 11+ package visibility):

```xml
<queries>
    <package android:name="org.telegram.messenger" />
    <package android:name="org.telegram.messenger.web" />
    <package android:name="org.thunderdog.challegram" />
    <package android:name="org.telegram.plus" />
</queries>
```

This covers: Official Telegram, Telegram X, Telegram FOSS (Challegram), and Plus Messenger.

#### TelegramSender Updates

- Add multi-client detection: check all known Telegram package names, use whichever is installed
- If multiple clients installed, use `Intent.createChooser()` filtered to Telegram variants
- Better error messages: distinguish "no Telegram installed" from "share failed"

---

### 4.7 Audio Engine Fixes

#### OOM Fix — Streaming Decode

Refactor `VoiceNoteConverter.convertToTelegramVoiceNote()`:

**Current:** Decode entire file → `mutableListOf<Short>()` → downmix → resample → encode
**New:** Streaming pipeline with fixed-size buffers

```
MediaExtractor → MediaCodec → [4096-sample buffer]
    → downmix chunk → resample chunk → waveform accumulate
    → encode chunk to Opus → OggOpusWriter.writeAudioPacket()
```

- Buffer size: 4096 samples per chunk (~85ms at 48kHz)
- Waveform: accumulate per-chunk peak amplitudes, normalize to 100 bars at the end
- Memory usage: constant ~32KB regardless of input file duration (vs current unbounded)

#### Remove Fake Fallback Encoder

- Delete `encodeFallbackPackets()` method entirely
- In `encodeUsingMediaCodecOpus()`, if `findOpusEncoder()` returns null:
  - Throw `AudioConversionException.UnsupportedAudioFormatException("Perangkat ini tidak memiliki encoder Opus. Silakan gunakan perangkat lain.")`
  - UI shows clear error message with the exception text

#### OggOpusWriter Optimization (Minor)

- Bundle multiple Opus frames per Ogg page (up to ~4KB payload) instead of 1 frame per page
- Remove `flush()` call per page — flush only on close

---

### 4.8 Cleanup & Dead Code Removal

#### Files to DELETE:
- `TelegramSessionManager.kt`
- `TelegramAuthBottomSheet.kt`

#### Files to MODIFY (remove TDLib auth references):
- `MainViewModel.kt`: Remove `sessionManager`, `loginTelegram()`, `submitOtpCode()`, `submit2FaPassword()`, `logoutTelegram()`, all TDLib session state
- `MainScreen.kt`: Remove TDLib status indicator in header, remove auth sheet trigger button
- `MainUiState`: Remove `telegramSessionState`, `showAuthSheet`, related fields

#### build.gradle.kts — Remove unused dependencies:
```kotlin
// DELETE these:
implementation("com.squareup.retrofit2:retrofit:2.12.0")
implementation("com.squareup.retrofit2:converter-moshi:2.12.0")
implementation("com.squareup.okhttp3:okhttp:4.10.0")
implementation("com.squareup.moshi:moshi-kotlin:1.15.2")
implementation("com.google.firebase:firebase-ai:...")
implementation("com.google.firebase:firebase-appcheck:...")
// Remove google-services plugin
```

#### build.gradle.kts — Add new dependencies:
```kotlin
// ADD these:
implementation("androidx.media3:media3-exoplayer:1.6.0")
implementation("androidx.media3:media3-common:1.6.0")
implementation("androidx.navigation:navigation-compose:2.9.0")
```

#### Other Cleanup:
- `ContactRepository`: Remove `seedInitialContactsIfEmpty()` hardcoded mock contacts
- `MainActivity`: Add `contentResolver.takePersistableUriPermission()` for content URIs
- File picker: Change from `GetContent("*/*")` to `OpenDocument(arrayOf("audio/*", "video/*"))`

---

## 5. Files Changed Summary

### New Files
| File | Purpose |
|---|---|
| `data/local/ConversionHistory.kt` | Room entity for conversion history |
| `data/local/ConversionHistoryDao.kt` | DAO for conversion history queries |
| `data/repository/ConversionHistoryRepository.kt` | Repository for history operations |
| `audio/VoiceNoteStorage.kt` | Utility for saving/deleting files in public storage |
| `ui/HistoryScreen.kt` | History tab composable |
| `ui/HistoryViewModel.kt` | ViewModel for history tab |
| `ui/AppNavigation.kt` | Bottom navigation host |
| `ui/components/TrimControls.kt` | Trim UI (handles, buttons, time display) |

### Modified Files
| File | Changes |
|---|---|
| `AndroidManifest.xml` | Add `<queries>` block, add `WRITE_EXTERNAL_STORAGE` for API < 29 |
| `build.gradle.kts` | Remove unused deps, add Media3 + Navigation |
| `audio/AudioPreviewPlayer.kt` | Full rewrite: MediaPlayer → ExoPlayer, add seekTo, clipping |
| `audio/VoiceNoteConverter.kt` | Streaming decode, trim params, remove fake fallback |
| `audio/OggOpusWriter.kt` | Multi-frame page bundling, remove per-page flush |
| `ui/MainScreen.kt` | Separate Convert/Send buttons, remove TDLib UI, trim controls |
| `ui/MainViewModel.kt` | Split convert/send, trim state, remove TDLib auth, save to history |
| `ui/components/WaveformVisualizer.kt` | Add gesture input, trim range visual, playhead, fix spacing bug |
| `data/local/AppDatabase.kt` | Add ConversionHistory entity, version 1 → 2 migration |
| `data/repository/ContactRepository.kt` | Remove mock seed contacts |
| `telegram/TelegramSender.kt` | Multi-client detection, better error messages |
| `MainActivity.kt` | Add takePersistableUriPermission, fix intent handling |

### Deleted Files
| File | Reason |
|---|---|
| `telegram/TelegramSessionManager.kt` | Entirely fake/mock TDLib session — dead code |
| `ui/components/TelegramAuthBottomSheet.kt` | UI for fake auth — misleading to users |

---

## 6. Verification Plan

### Automated Tests
- `./gradlew assembleDebug` — project compiles without errors
- `./gradlew test` — existing unit tests still pass

### Manual Verification
1. **File picker:** Only shows audio and video files
2. **Preview + Seek:** Play audio, tap/drag waveform, verify playhead moves and audio seeks
3. **Trim:** Set start/end handles, preview trim region, verify only selected region plays
4. **Convert:** Tap Convert, verify progress, preview result with real waveform
5. **Send:** Tap Send → Telegram opens (no "not installed" error on Android 11+)
6. **History:** Navigate to History tab, verify converted file appears with correct metadata
7. **History playback:** Tap play on history item, verify inline playback works
8. **History delete:** Swipe to delete, verify file removed from storage and DB
9. **Large file:** Test with 10+ minute audio, verify no OOM crash
10. **No Opus encoder:** Test on emulator without Opus codec, verify clear error message
