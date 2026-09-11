# Voice Note Converter V2 — Improvements Design Spec

> **Date:** 2026-09-10
> **Status:** Draft — Awaiting User Review
> **Scope:** Bug fixes, UX/polish, new features, quality & stability improvements
> **Baseline:** Post-V1 polish (all G1–G8 goals implemented)

---

## 1. Problem Statement

The Voice Note Converter V1 polish delivered interactive waveform preview, audio trim, conversion history, fixed Telegram sharing, and cleaned-up architecture. The app is functional for personal use but has several bugs, UX gaps, missing features, and quality/stability concerns that limit daily comfort:

### Bugs
1. **Zombie History Items** — When an audio file is externally deleted, the history item becomes permanently undeletable from the database.
2. **Trim Handle Collision** — When both trim handles are at the same position, the end handle becomes unreachable and locked.
3. **Converted Audio Seek Disabled** — After conversion, the waveform becomes non-interactive, preventing seek on the converted result.
4. **Opus Pre-Skip Crash** — Some Android hardware encoders don't report pre-skip delay, causing `ConversionFailedException` on certain devices.
5. **URI Permission Lost on Chooser** — When multiple Telegram apps are installed, the chooser intent doesn't forward `FLAG_GRANT_READ_URI_PERMISSION`, causing `SecurityException`.

### UX Gaps
- No dark mode support (light-only, ignoring system preference)
- No haptic feedback on trim handle interactions
- No exit confirmation when conversion is in progress
- No manual timestamp input for precise trim positioning
- No state transition animations (hard cuts between states)
- No notification when conversion completes in background

### Missing Features
- No batch conversion support
- No volume boost/normalization for quiet recordings
- No noise reduction for voice clarity
- No URL-based conversion (from web links)
- No WhatsApp sharing option (Telegram only)
- No search/filter on conversion history

### Quality & Stability
- No ViewModel unit tests
- No Compose UI interaction tests
- No ProGuard/R8 minification
- No foreground service for long conversions (at risk of being killed)
- No error recovery mechanism (stuck in error state)
- No crash reporting
- Package namespace still uses `com.example`

---

## 2. Goals

### Bug Fixes
- **B1:** Fix zombie history — always remove DB record when file is already missing
- **B2:** Fix trim handle collision — enforce minimum gap and improve hit detection
- **B3:** Fix converted seek — allow seeking on converted audio waveform
- **B4:** Fix pre-skip fallback — use safe default (312 samples) when encoder doesn't report delay
- **B5:** Fix chooser URI permission — attach `ClipData` and flags to chooser intent

### UX & Polish
- **UX-1:** Dark mode support following system preference
- **UX-2:** Haptic feedback on trim handle drag interactions
- **UX-3:** Exit confirmation dialog when conversion is in progress
- **UX-4:** Manual timestamp input fields for precise trim start/end
- **UX-5:** Animated state transitions using `AnimatedContent`/`Crossfade`
- **UX-6:** Notification when conversion completes while app is in background

### New Features
- **FT-1:** Batch conversion — queue multiple files for sequential conversion
- **FT-4:** Volume boost/normalization on PCM stream before encoding
- **FT-5:** Simple noise reduction (high-pass filter) on PCM stream
- **FT-6:** URL-based conversion — paste a URL, download, and convert
- **FT-8:** WhatsApp sharing — extend sharing beyond Telegram
- **FT-10:** History search & filter by filename, date, sent status

### Quality & Stability
- **QA-1:** ViewModel unit tests for MainViewModel and HistoryViewModel
- **QA-2:** Compose UI interaction tests using ComposeTestRule
- **QA-3:** Enable ProGuard/R8 for release builds
- **QA-4:** Foreground service with notification for conversion process
- **QA-5:** Error recovery — retry and auto-reset stuck states
- **QA-6:** Crash reporting integration
- **QA-7:** Package name migration from `com.example` to proper namespace

---

## 3. Detailed Design

### 3.1 Bug Fix: B1 — Zombie History

**File:** `HistoryViewModel.kt` — `deleteItem()` method (lines 69–93)

**Current Problem:**
```kotlin
val deleted = fileAlreadyRemoved || VoiceNoteStorage.deleteFromStorage(
    getApplication(), item.outputFilePath
)
if (!deleted) {
    _message.value = "File tidak dapat dihapus; entri riwayat tetap disimpan."
    return@launch
}
```
When `deleteFromStorage()` returns `false` (file already externally deleted), the DB record is never removed — creating a permanent zombie entry.

**Fix:**
Replace the logic to always attempt DB deletion when the file is missing:

```kotlin
fun deleteItem(item: ConversionHistory) {
    viewModelScope.launch {
        val fileAlreadyRemoved = item.id in pendingDatabaseDeletion
        val storageResult = fileAlreadyRemoved || VoiceNoteStorage.deleteFromStorage(
            getApplication(), item.outputFilePath
        )
        // If deleteFromStorage returns false, check if file actually exists
        val fileGone = storageResult || !VoiceNoteStorage.fileExists(
            getApplication(), item.outputFilePath
        )
        if (!fileGone) {
            _message.value = "File tidak dapat dihapus; entri riwayat tetap disimpan."
            return@launch
        }
        // Proceed with DB deletion...
    }
}
```

Also add `fileExists()` to `VoiceNoteStorage`:
```kotlin
fun fileExists(context: Context, uriString: String): Boolean {
    return try {
        val uri = uriString.toUri()
        context.contentResolver.openInputStream(uri)?.use { true } ?: false
    } catch (_: Exception) { false }
}
```

**Files Changed:**
- `VoiceNoteStorage.kt` — add `fileExists()` method
- `HistoryViewModel.kt` — update `deleteItem()` logic

---

### 3.2 Bug Fix: B2 — Trim Handle Collision

**File:** `WaveformVisualizer.kt` — gesture handling (lines 59–85)

**Current Problems:**
1. `abs(fraction - range.start) <= abs(fraction - range.endInclusive)` — when handles overlap, `<=` always selects start handle, permanently locking end handle.
2. No minimum gap enforcement — `fraction.coerceAtMost(range.endInclusive)` allows `start == end`, collapsing to zero length.

**Fix:**
```kotlin
// 1. Add minimum gap constant
private const val MIN_TRIM_GAP = 0.02f // 2% of total duration

// 2. Fix handle selection: use strict < for start, resolving tie to closest
onDragStart = { offset ->
    val range = currentTrimRange.value ?: return@detectHorizontalDragGestures
    val fraction = (offset.x / size.width).coerceIn(0f, 1f)
    val distToStart = abs(fraction - range.start)
    val distToEnd = abs(fraction - range.endInclusive)
    draggingStart = distToStart < distToEnd // strict < so ties go to end handle
},

// 3. Enforce minimum gap in drag handler
onHorizontalDrag = { change, _ ->
    change.consume()
    val range = currentTrimRange.value ?: return@detectHorizontalDragGestures
    val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
    if (draggingStart) {
        val maxStart = (range.endInclusive - MIN_TRIM_GAP).coerceAtLeast(0f)
        currentOnTrimRangeChange.value?.invoke(
            fraction.coerceIn(0f, maxStart)..range.endInclusive
        )
    } else {
        val minEnd = (range.start + MIN_TRIM_GAP).coerceAtMost(1f)
        currentOnTrimRangeChange.value?.invoke(
            range.start..fraction.coerceIn(minEnd, 1f)
        )
    }
}
```

**Files Changed:**
- `WaveformVisualizer.kt` — fix gesture detection logic

---

### 3.3 Bug Fix: B3 — Converted Seek Disabled

**File:** `MainScreen.kt` — `PreviewCard` composable (line 349)

**Current Problem:**
```kotlin
isInteractive = processStatus == ProcessStatus.IDLE && !hasConvertedResult
```
When `hasConvertedResult == true`, interactivity is disabled, preventing seek on converted audio.

**Fix:**
```kotlin
isInteractive = processStatus == ProcessStatus.IDLE ||
    processStatus == ProcessStatus.CONVERTED ||
    processStatus == ProcessStatus.SENT
```
This allows seeking whenever the app is not actively processing (converting/sending/analyzing).

**Files Changed:**
- `MainScreen.kt` — update `isInteractive` condition in `PreviewCard`

---

### 3.4 Bug Fix: B4 — Pre-Skip Fallback

**File:** `VoiceNoteConverter.kt` — `opusPreSkipSamples()` function (lines 69–95)

**Current Problem:**
```kotlin
return (fromNanoseconds ?: fromDelayKey ?: fromIdentificationHeader)
    ?.takeIf { it in 0..65_535 }
    ?: throw ConversionFailedException(
        "Encoder Opus tidak melaporkan delay yang diperlukan untuk container Ogg."
    )
```
If no delay source is available, conversion fails. Some hardware encoders on certain Android devices don't report any of the three delay sources.

**Fix:**
Replace the throw with a safe default of 312 samples (standard Opus encoder lookahead: 6.5ms at 48kHz):

```kotlin
private const val DEFAULT_OPUS_PRE_SKIP = 312 // 6.5ms at 48kHz — standard Opus lookahead

return (fromNanoseconds ?: fromDelayKey ?: fromIdentificationHeader)
    ?.takeIf { it in 0..65_535 }
    ?: DEFAULT_OPUS_PRE_SKIP
```

Add a `Log.w` for diagnostics:
```kotlin
?: run {
    Log.w("VoiceNoteConverter", "Encoder did not report pre-skip; using default $DEFAULT_OPUS_PRE_SKIP")
    DEFAULT_OPUS_PRE_SKIP
}
```

**Files Changed:**
- `VoiceNoteConverter.kt` — replace throw with default in `opusPreSkipSamples()`

---

### 3.5 Bug Fix: B5 — URI Permission Chooser

**File:** `TelegramSender.kt` — chooser intent creation (lines 86–92)

**Current Problem:**
`Intent.createChooser()` does NOT inherit `FLAG_GRANT_READ_URI_PERMISSION` from the target intent, nor does it carry `ClipData`. When the chosen app reads the `EXTRA_STREAM` URI, it throws `SecurityException`.

**Fix:**
Attach `ClipData` and read URI flag to the chooser intent:

```kotlin
val launchIntent = if (intents.size == 1) {
    intents.first()
} else {
    Intent.createChooser(intents.first(), "Pilih aplikasi Telegram").apply {
        putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
        // Fix: forward URI permission to chooser
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri("", oggUri)
    }
}.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
```

**New Import Required:**
```kotlin
import android.content.ClipData
```

**Files Changed:**
- `TelegramSender.kt` — add `ClipData` and flag to chooser intent

---

### 3.6 UX: UX-1 — Dark Mode

**Files:** `Theme.kt`, `Color.kt`, `MainActivity.kt`

**Current State:**
- `Color.kt` already defines dark theme colors: `DarkCanvasBackground`, `DarkCardSurface`, `DarkSurfaceVariant`, `DarkBorder`, `DarkTextPrimary`, `DarkTextSecondary`
- `Theme.kt` hardcodes `val colorScheme = LightColorScheme` and light status bars

**Design:**
1. Define `DarkColorScheme` in `Theme.kt` using existing dark palette colors
2. Detect system dark mode via `isSystemInDarkTheme()`
3. Toggle status/navigation bar appearance based on dark mode
4. Pass `darkTheme` parameter through to composables that need it

```kotlin
private val DarkColorScheme = darkColorScheme(
    primary = AccentCoral,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF4D1F16),
    onPrimaryContainer = AccentCoralSoft,
    secondary = AccentPeriwinkle,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1E2A4E),
    onSecondaryContainer = AccentLavenderLight,
    tertiary = AccentAqua,
    onTertiary = DarkCanvasBackground,
    tertiaryContainer = Color(0xFF1A3A3C),
    onTertiaryContainer = AccentAquaSoft,
    background = DarkCanvasBackground,
    surface = DarkCardSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurface = DarkTextPrimary,
    onSurfaceVariant = DarkTextSecondary,
    outline = DarkBorder,
    outlineVariant = Color(0xFF3A2520),
    error = Color(0xFFFF6B6B)
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    // ...
    windowInsetsController.isAppearanceLightStatusBars = !darkTheme
    windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
}
```

**Impact on other files:**
- Components that use hardcoded color values (e.g., `AppCanvasBackground`, `CardSurfaceWhite`) should use `MaterialTheme.colorScheme.background` and `MaterialTheme.colorScheme.surface` instead. Files affected:
  - `MainScreen.kt` — background colors in `ConverterHeader`, `SoftCard`
  - `HistoryScreen.kt` — background colors in history cards, empty state
  - `AppNavigation.kt` — nav bar colors

**New Imports in Theme.kt:**
```kotlin
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
```

---

### 3.7 UX: UX-2 — Haptic Feedback on Trim

**File:** `WaveformVisualizer.kt`

**Design:**
Add haptic feedback when trim handle drag starts using `HapticFeedback`:

```kotlin
val haptic = LocalHapticFeedback.current

// In onDragStart callback:
haptic.performHapticFeedback(HapticFeedbackType.LongPress)
```

**New Imports:**
```kotlin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
```

---

### 3.8 UX: UX-3 — Exit Confirmation During Conversion

**File:** `MainActivity.kt`

**Design:**
When conversion is in progress, intercept back press:

```kotlin
// In MainActivity, observe conversion status via a shared mechanism
// Option A: Use OnBackPressedCallback
val callback = object : OnBackPressedCallback(true) {
    override fun handleOnBackPressed() {
        if (isConverting) {
            showExitConfirmationDialog = true
        } else {
            isEnabled = false
            onBackPressedDispatcher.onBackPressed()
        }
    }
}
```

Or at Compose level using `BackHandler`:
```kotlin
// In MainScreen.kt
if (state.processStatus == ProcessStatus.CONVERTING) {
    BackHandler {
        showExitDialog = true
    }
}

// Exit confirmation dialog
if (showExitDialog) {
    AlertDialog(
        onDismissRequest = { showExitDialog = false },
        title = { Text("Konversi sedang berjalan") },
        text = { Text("Keluar akan membatalkan proses konversi. Lanjutkan keluar?") },
        confirmButton = {
            TextButton(onClick = { /* cancel conversion & exit */ }) {
                Text("Keluar", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = { showExitDialog = false }) {
                Text("Tetap di sini")
            }
        }
    )
}
```

---

### 3.9 UX: UX-4 — Manual Timestamp Input for Trim

**File:** `TrimControls.kt`

**Design:**
Add editable text fields for start/end timestamps next to the current display:

```kotlin
@Composable
fun TrimControls(
    trimState: TrimState,
    onToggleTrim: () -> Unit,
    onPreviewTrim: () -> Unit,
    onResetTrim: () -> Unit,
    onManualTimestampChange: (startMs: Long, endMs: Long) -> Unit, // NEW
    modifier: Modifier = Modifier
)
```

Replace the static `Text("${time(trimState.startMs)} sampai ${time(trimState.endMs)}")` with:

```kotlin
Row(
    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    TimestampField(
        label = "Mulai",
        valueMs = trimState.startMs,
        maxMs = trimState.endMs - 100,
        onValueChange = { newStart ->
            onManualTimestampChange(newStart, trimState.endMs)
        }
    )
    Text("—", color = SubtitleSlate)
    TimestampField(
        label = "Akhir",
        valueMs = trimState.endMs,
        maxMs = trimState.totalDurationMs,
        onValueChange = { newEnd ->
            onManualTimestampChange(trimState.startMs, newEnd)
        }
    )
    Text("(${time(trimState.trimmedDurationMs)})", color = SubtitleSlate)
}
```

`TimestampField` is a small composable with `BasicTextField` using `MM:SS` format, with input validation (parse back to ms, clamp to valid range).

**Files Changed:**
- `TrimControls.kt` — add `TimestampField` composable, update `TrimControls` signature
- `MainScreen.kt` — pass `onManualTimestampChange` callback
- `MainViewModel.kt` — add `updateTrimManual(startMs: Long, endMs: Long)` method

---

### 3.10 UX: UX-5 — State Transition Animations

**File:** `MainScreen.kt`

**Design:**
Wrap `ProcessActionArea` content in `AnimatedContent` with fade+slide transitions:

```kotlin
AnimatedContent(
    targetState = state.processStatus,
    transitionSpec = {
        fadeIn(animationSpec = tween(300)) +
            slideInVertically { it / 4 } togetherWith
            fadeOut(animationSpec = tween(200))
    }
) { status ->
    when (status) { /* existing content */ }
}
```

Also animate `PreviewCard` background color change when switching from original to converted:
```kotlin
val cardColor by animateColorAsState(
    targetValue = if (hasConvertedResult) PastelMintCardBg else CardSurfaceWhite,
    animationSpec = tween(400)
)
```

**New Imports:**
```kotlin
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
```

---

### 3.11 UX: UX-6 — Conversion Complete Notification

**Files:** `MainViewModel.kt`, `AndroidManifest.xml`

**Design:**
When conversion completes and the app is in background, show a notification:

1. Add `POST_NOTIFICATIONS` permission to manifest (API 33+)
2. Create `ConversionNotifier` utility class:

```kotlin
object ConversionNotifier {
    private const val CHANNEL_ID = "conversion_complete"
    private const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Konversi Selesai",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    fun notifyComplete(context: Context, fileName: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification) // need to create this
            .setContentTitle("Konversi selesai")
            .setContentText("$fileName siap dikirim")
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }
}
```

3. In `MainViewModel.startConversion()`, after successful conversion:
```kotlin
if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED).not()) {
    ConversionNotifier.notifyComplete(context, result.originalFileName)
}
```

**New Dependencies:** `androidx.core:core-ktx` (already included)

---

### 3.12 Feature: FT-1 — Batch Conversion

**New Files:** `BatchConversionManager.kt`
**Modified:** `MainViewModel.kt`, `MainScreen.kt`, `MainUiState`

**Design:**
Allow user to select multiple files and convert them sequentially:

1. Add `BatchUiState` to track queue:
```kotlin
data class BatchState(
    val queue: List<BatchItem> = emptyList(),
    val currentIndex: Int = 0,
    val isActive: Boolean = false
)
data class BatchItem(
    val uri: Uri,
    val fileName: String,
    val status: BatchItemStatus = BatchItemStatus.PENDING
)
enum class BatchItemStatus { PENDING, CONVERTING, DONE, FAILED }
```

2. Add batch file picker using `ActivityResultContracts.OpenMultipleDocuments()`
3. Process queue sequentially in `MainViewModel`, updating progress per item
4. Show batch progress UI with item list showing individual statuses

**User Flow:**
1. Long-press "Pilih audio atau video" to enter batch mode
2. Select multiple files
3. Review queue → Tap "Konversi Semua"
4. Sequential processing with per-file progress
5. Summary screen showing results

---

### 3.13 Feature: FT-4 — Volume Boost/Normalization

**File:** `VoiceNoteConverter.kt` — `StreamingPcmProcessor`

**Design:**
Add optional volume normalization to the PCM processing pipeline:

```kotlin
internal class StreamingPcmProcessor(
    private val channels: Int,
    private val sourceSampleRate: Int,
    private val targetSampleRate: Int = 48_000,
    private val volumeBoostDb: Float = 0f  // NEW: 0 = no boost, positive = amplify
) {
    private val boostFactor = 10f.pow(volumeBoostDb / 20f)  // dB to linear

    fun process(interleavedSamples: ShortArray): ShortArray {
        // ... existing mono conversion and resampling ...
        // After mono conversion, apply volume boost:
        if (boostFactor != 1f) {
            for (i in mono.indices) {
                mono[i] = (mono[i] * boostFactor)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
        // ... rest of processing ...
    }
}
```

**Two-pass normalization option:**
- First pass: scan peak amplitude via `extractWaveform()`
- Calculate gain needed to reach target peak (-1 dBFS)
- Apply calculated gain in second pass (conversion)

**UI:** Add volume slider/toggle in `TrimControls` area:
```
☑ Normalisasi volume   [slider: +0 dB to +12 dB]
```

**Files Changed:**
- `VoiceNoteConverter.kt` — add `volumeBoostDb` parameter to `StreamingPcmProcessor` and `convertToTelegramVoiceNote()`
- `MainViewModel.kt` — add volume boost state and pass to converter
- `MainScreen.kt` — add volume control UI

---

### 3.14 Feature: FT-5 — Noise Reduction (High-Pass Filter)

**File:** `VoiceNoteConverter.kt` — `StreamingPcmProcessor`

**Design:**
Apply a simple first-order high-pass filter to remove low-frequency noise (hum, rumble) below ~80 Hz:

```kotlin
private class HighPassFilter(sampleRate: Int, cutoffHz: Float = 80f) {
    private val rc = 1f / (2f * Math.PI.toFloat() * cutoffHz)
    private val dt = 1f / sampleRate
    private val alpha = rc / (rc + dt)
    private var prevInput = 0f
    private var prevOutput = 0f

    fun process(sample: Short): Short {
        val input = sample.toFloat()
        val output = alpha * (prevOutput + input - prevInput)
        prevInput = input
        prevOutput = output
        return output.roundToInt()
            .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            .toShort()
    }
}
```

Integrate into `StreamingPcmProcessor.process()` after mono conversion.

**UI:** Add toggle in `TrimControls` area:
```
☑ Kurangi noise latar
```

**Files Changed:**
- `VoiceNoteConverter.kt` — add `HighPassFilter` class, integrate into `StreamingPcmProcessor`
- `MainViewModel.kt` — add noise reduction toggle state
- `MainScreen.kt` — add noise reduction toggle UI

---

### 3.15 Feature: FT-6 — URL Conversion

**New Files:** `UrlDownloader.kt`
**Modified:** `MainViewModel.kt`, `MainScreen.kt`

**Design:**
Allow pasting a URL to download and convert audio directly:

1. Add URL input field below the file picker:
```
┌────────────────────────────────────────┐
│ 🔗  Atau tempel URL audio/video        │
│ ┌──────────────────────────────────┐   │
│ │ https://...                      │   │
│ └──────────────────────────────────┘   │
│ [Unduh & Konversi]                     │
└────────────────────────────────────────┘
```

2. Download using `HttpURLConnection` to cache dir:
```kotlin
object UrlDownloader {
    suspend fun download(
        url: String,
        cacheDir: File,
        onProgress: (Float) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val contentLength = connection.contentLength.toLong()
        val extension = MimeTypeMap.getFileExtensionFromUrl(url) ?: "tmp"
        val outputFile = File(cacheDir, "url_download_${System.currentTimeMillis()}.$extension")
        connection.inputStream.use { input ->
            FileOutputStream(outputFile).use { output ->
                val buffer = ByteArray(8192)
                var totalRead = 0L
                while (true) {
                    val bytesRead = input.read(buffer)
                    if (bytesRead == -1) break
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (contentLength > 0) onProgress(totalRead.toFloat() / contentLength)
                }
            }
        }
        outputFile
    }
}
```

3. After download, convert using existing `VoiceNoteConverter.convertToTelegramVoiceNote()` with the file URI.

**Permissions:** Add `android.permission.INTERNET` (already implied by default).

---

### 3.16 Feature: FT-8 — WhatsApp Sharing

**Files:** `TelegramSender.kt` → renamed/refactored to `MessagingSender.kt`, `AndroidManifest.xml`

**Design:**
Extend sharing to support WhatsApp alongside Telegram:

1. Rename `TelegramSender` to `MessagingSender` (or keep and extend)
2. Add WhatsApp package detection:
```kotlin
private val WHATSAPP_PACKAGES = listOf(
    "com.whatsapp",
    "com.whatsapp.w4b"  // WhatsApp Business
)
```

3. Add to `<queries>` in `AndroidManifest.xml`:
```xml
<package android:name="com.whatsapp" />
<package android:name="com.whatsapp.w4b" />
```

4. Update UI to show platform choice:
```kotlin
sealed class ShareTarget {
    data object Telegram : ShareTarget()
    data object WhatsApp : ShareTarget()
}
```

5. In `MainScreen.kt`, replace single "Kirim ke Telegram" button with platform selector:
```
[🔵 Telegram]  [🟢 WhatsApp]  ← segmented button or chips
```

6. WhatsApp sharing uses same `Intent.ACTION_SEND` with `audio/ogg` type but targets WhatsApp packages.

---

### 3.17 Feature: FT-10 — History Search & Filter

**Files:** `ConversionHistoryDao.kt`, `ConversionHistoryRepository.kt`, `HistoryViewModel.kt`, `HistoryScreen.kt`

**Design:**

1. Add search query to DAO:
```kotlin
@Query("""
    SELECT * FROM conversion_history
    WHERE originalFileName LIKE '%' || :query || '%'
       OR outputFileName LIKE '%' || :query || '%'
    ORDER BY createdAt DESC
""")
fun searchHistory(query: String): Flow<List<ConversionHistory>>
```

2. Add filter options:
```kotlin
enum class HistoryFilter { ALL, SENT, NOT_SENT }
```

3. In `HistoryViewModel`:
```kotlin
private val _searchQuery = MutableStateFlow("")
private val _filter = MutableStateFlow(HistoryFilter.ALL)

val filteredHistory = combine(_searchQuery, _filter, repository.allHistory) { query, filter, items ->
    items.filter { item ->
        val matchesQuery = query.isBlank() ||
            item.originalFileName.contains(query, ignoreCase = true) ||
            item.outputFileName.contains(query, ignoreCase = true)
        val matchesFilter = when (filter) {
            HistoryFilter.ALL -> true
            HistoryFilter.SENT -> item.sentTo != null
            HistoryFilter.NOT_SENT -> item.sentTo == null
        }
        matchesQuery && matchesFilter
    }
}.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

4. In `HistoryScreen`, add search bar above the list:
```kotlin
OutlinedTextField(
    value = searchQuery,
    onValueChange = viewModel::updateSearch,
    placeholder = { Text("Cari voice note...") },
    leadingIcon = { Icon(Icons.Default.Search, null) },
    singleLine = true,
    modifier = Modifier.fillMaxWidth()
)
// Filter chips: Semua | Terkirim | Belum Terkirim
```

---

### 3.18 Quality: QA-1 — ViewModel Unit Tests

**New Files:**
- `test/.../ui/MainViewModelTest.kt`
- `test/.../ui/HistoryViewModelTest.kt`

**Design:**
Use Robolectric (already configured) + `kotlinx-coroutines-test`:

**MainViewModel tests:**
- `handleIncomingUri()` → sets `ProcessStatus.ANALYZING`, then `IDLE` on success
- `startConversion()` → sets `ProcessStatus.CONVERTING`, progress updates, `CONVERTED` on success
- `toggleTrim()` → toggles `TrimState.isActive`
- `updateTrimRange()` → validates boundaries, enforces minimum fraction
- `sendConverted()` → handles success and failure
- `resetForNewFile()` → clears all state

**HistoryViewModel tests:**
- `deleteItem()` with existing file → deletes both file and DB record
- `deleteItem()` with missing file → still deletes DB record (B1 fix verification)
- `playPause()` → toggles playback for correct item
- `shareItem()` → launches intent and updates send status

---

### 3.19 Quality: QA-2 — Compose UI Tests

**New Files:**
- `test/.../ui/MainScreenTest.kt`
- `test/.../ui/HistoryScreenTest.kt`

**Design:**
Use `ComposeTestRule` with Robolectric:

**MainScreen tests:**
- File picker button is displayed and clickable
- Conversion button appears after file selection
- Progress indicator shown during conversion
- Send button appears after conversion
- Trim controls toggle correctly
- Error state shows retry button

**HistoryScreen tests:**
- Empty state shown when no history
- History items render correctly
- Delete dialog appears on action
- Search bar filters results (after FT-10)

---

### 3.20 Quality: QA-3 — ProGuard/R8

**File:** `app/build.gradle.kts`, `proguard-rules.pro`

**Design:**
Enable R8 minification for release builds:

```kotlin
release {
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
    )
}
```

Add rules to `proguard-rules.pro`:
```proguard
# Room
-keep class com.example.data.local.** { *; }

# Keep Opus codec detection
-keep class android.media.MediaCodecList { *; }
-keep class android.media.MediaCodecInfo { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**
```

---

### 3.21 Quality: QA-4 — Foreground Service for Conversion

**New Files:** `ConversionService.kt`
**Modified:** `AndroidManifest.xml`, `MainViewModel.kt`

**Design:**
Wrap the conversion process in a foreground service to prevent the OS from killing the process:

1. Add permissions to manifest:
```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<service
    android:name=".audio.ConversionService"
    android:foregroundServiceType="mediaProcessing"
    android:exported="false" />
```

2. `ConversionService` starts foreground with a notification showing progress:
```kotlin
class ConversionService : Service() {
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createProgressNotification(0f)
        startForeground(NOTIFICATION_ID, notification)
        // Conversion runs in coroutine scope
        return START_NOT_STICKY
    }
}
```

3. `MainViewModel` communicates with service via bound service or shared flow.

**Alternative (simpler for personal use):** Keep conversion in ViewModel but add `WorkManager` for reliability. Given this is a personal app, the simpler approach is to just request `FOREGROUND_SERVICE` permission and wrap the conversion coroutine in a foreground notification — this can be done directly in `MainActivity` without a separate Service class.

---

### 3.22 Quality: QA-5 — Error Recovery

**File:** `MainViewModel.kt`

**Design:**
Add auto-recovery mechanisms:

1. **Retry with backoff:** On conversion failure, allow retry with exponential backoff
2. **State timeout:** If stuck in CONVERTING for >5 minutes, auto-transition to FAILED
3. **Stale state cleanup:** On app launch, check for stuck CONVERTING state and reset

```kotlin
init {
    // Reset any stuck conversion state on ViewModel init
    if (_uiState.value.processStatus == ProcessStatus.CONVERTING) {
        _uiState.update { it.copy(processStatus = ProcessStatus.FAILED,
            errorMessage = "Konversi sebelumnya terganggu. Silakan coba lagi.",
            canRetry = true) }
    }
}

// Timeout watchdog during conversion
private fun startConversionTimeout() {
    viewModelScope.launch {
        delay(5.minutes)
        if (_uiState.value.processStatus == ProcessStatus.CONVERTING) {
            conversionJob?.cancel()
            fail("Konversi terlalu lama. Silakan coba lagi.", canRetry = true)
        }
    }
}
```

---

### 3.23 Quality: QA-6 — Crash Reporting

**Design:**
For personal use, a lightweight approach:

1. Set `Thread.setDefaultUncaughtExceptionHandler` to log crashes to a file
2. On next launch, show a dialog if crash log exists
3. Option to copy crash log to clipboard for debugging

```kotlin
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        val crashFile = File(context.filesDir, "crash_log.txt")
        crashFile.writeText(buildString {
            appendLine("Time: ${Date()}")
            appendLine("Thread: ${t.name}")
            appendLine(e.stackTraceToString())
        })
        defaultHandler?.uncaughtException(t, e)
    }
}
```

Install in `MainActivity.onCreate()`:
```kotlin
Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))
```

---

### 3.24 Quality: QA-7 — Package Name Migration

**Scope:** All source files, build config, manifest

**Current State:**
- `namespace = "com.example"` in `build.gradle.kts`
- `applicationId = "com.aistudio.voicenote.cvtr"` (already correct for Play Store)
- All source files use `package com.example.*`
- FileProvider authority uses `${applicationId}.fileprovider` — safe, won't break

**Design:**
Migrate source package from `com.example` to `com.aistudio.voicenote`:

1. Update `namespace` in `build.gradle.kts`:
```kotlin
namespace = "com.aistudio.voicenote"
```

2. Rename all package declarations across ~24 source files:
   - `com.example` → `com.aistudio.voicenote`
   - `com.example.audio` → `com.aistudio.voicenote.audio`
   - `com.example.data.local` → `com.aistudio.voicenote.data.local`
   - `com.example.data.repository` → `com.aistudio.voicenote.data.repository`
   - `com.example.telegram` → `com.aistudio.voicenote.telegram`
   - `com.example.ui` → `com.aistudio.voicenote.ui`
   - `com.example.ui.components` → `com.aistudio.voicenote.ui.components`
   - `com.example.ui.theme` → `com.aistudio.voicenote.ui.theme`

3. Update all import statements across all files
4. Move source directories to match new package structure
5. Update `AndroidManifest.xml` activity name reference
6. Update test files package declarations and imports
7. `applicationId` stays as `com.aistudio.voicenote.cvtr` — no change needed

**Risk:** Database migration — Room generates code using the namespace. Need to verify `AppDatabase` entity references still resolve. Since `@Entity(tableName = ...)` uses string table names (not package-qualified), this should be safe.

---

## 4. Priority & Dependency Matrix

| ID    | Priority | Dependencies | Estimated Effort |
|-------|----------|-------------|-----------------|
| B1    | P0 Critical | None | 30 min |
| B2    | P0 Critical | None | 30 min |
| B3    | P0 Critical | None | 10 min |
| B4    | P0 Critical | None | 10 min |
| B5    | P0 Critical | None | 15 min |
| UX-1  | P1 High | None | 2 hrs |
| UX-2  | P2 Medium | B2 (trim fix first) | 15 min |
| UX-3  | P1 High | None | 30 min |
| UX-4  | P2 Medium | B2 (trim fix first) | 1 hr |
| UX-5  | P3 Low | None | 1 hr |
| UX-6  | P2 Medium | QA-4 (notification channel) | 1 hr |
| FT-1  | P2 Medium | None | 3 hrs |
| FT-4  | P2 Medium | None | 2 hrs |
| FT-5  | P3 Low | FT-4 (same pipeline) | 1 hr |
| FT-6  | P3 Low | None | 2 hrs |
| FT-8  | P1 High | B5 (chooser fix first) | 1.5 hrs |
| FT-10 | P2 Medium | None | 2 hrs |
| QA-1  | P1 High | None | 3 hrs |
| QA-2  | P2 Medium | QA-1 | 3 hrs |
| QA-3  | P1 High | QA-7 (after migration) | 1 hr |
| QA-4  | P1 High | None | 2.5 hrs |
| QA-5  | P2 Medium | None | 1 hr |
| QA-6  | P3 Low | None | 30 min |
| QA-7  | P1 High | None | 2 hrs |

---

## 5. Implementation Order (Recommended Phases)

### Phase 1: Critical Bug Fixes (P0)
B1 → B2 → B3 → B4 → B5
_All bugs fixed first for stability._

### Phase 2: Foundation Quality
QA-7 (package migration) → QA-3 (ProGuard) → QA-4 (foreground service) → QA-5 (error recovery)
_Clean foundation before adding features._

### Phase 3: UX Essentials
UX-1 (dark mode) → UX-3 (exit confirmation) → UX-2 (haptic) → UX-6 (notification)
_Core UX improvements that enhance daily use._

### Phase 4: Feature Additions
FT-8 (WhatsApp) → FT-10 (history search) → FT-4 (volume boost) → FT-5 (noise reduction) → FT-1 (batch) → FT-6 (URL)
_New capabilities in order of daily value._

### Phase 5: Polish & Testing
QA-1 (VM tests) → QA-2 (UI tests) → UX-4 (manual timestamp) → UX-5 (animations) → QA-6 (crash reporting)
_Final polish and quality assurance._

---

## 6. File Change Summary

### New Files
| File | Purpose |
|------|---------|
| `ConversionNotifier.kt` | Notification for conversion completion |
| `ConversionService.kt` | Foreground service wrapper |
| `UrlDownloader.kt` | URL download utility |
| `BatchConversionManager.kt` | Batch conversion state management |
| `CrashHandler.kt` | Crash logging utility |
| `MainViewModelTest.kt` | ViewModel unit tests |
| `HistoryViewModelTest.kt` | ViewModel unit tests |
| `MainScreenTest.kt` | UI interaction tests |
| `HistoryScreenTest.kt` | UI interaction tests |

### Modified Files
| File | Changes |
|------|---------|
| `HistoryViewModel.kt` | B1: fix deleteItem(), FT-10: add search/filter |
| `WaveformVisualizer.kt` | B2: fix trim collision, UX-2: haptic feedback |
| `MainScreen.kt` | B3: fix isInteractive, UX-3: exit dialog, UX-4: manual timestamp, UX-5: animations, FT-1: batch UI, FT-4: volume UI, FT-8: share platform UI |
| `VoiceNoteConverter.kt` | B4: pre-skip fallback, FT-4: volume boost, FT-5: noise reduction |
| `TelegramSender.kt` | B5: chooser fix, FT-8: WhatsApp support |
| `Theme.kt` | UX-1: dark mode |
| `Color.kt` | UX-1: dark mode (already has colors) |
| `TrimControls.kt` | UX-4: manual timestamp fields |
| `MainViewModel.kt` | Multiple: conversion flow, batch, volume, noise, URL, error recovery |
| `HistoryScreen.kt` | FT-10: search bar and filter chips |
| `ConversionHistoryDao.kt` | FT-10: search query |
| `ConversionHistoryRepository.kt` | FT-10: search method |
| `VoiceNoteStorage.kt` | B1: fileExists() method |
| `AndroidManifest.xml` | UX-6: notification permission, QA-4: foreground service, FT-8: WhatsApp queries |
| `AppNavigation.kt` | UX-1: dark mode aware colors |
| `MainActivity.kt` | UX-3: back handler, QA-6: crash handler |
| `app/build.gradle.kts` | QA-3: R8, QA-7: namespace |
| `proguard-rules.pro` | QA-3: keep rules |
| All source files | QA-7: package rename |
| All test files | QA-7: package rename |

---

## 7. Verification Plan

### Automated
- Run existing 25 tests: `./gradlew test`
- Run new ViewModel tests: verify all bugs and features
- Run UI tests: verify user flows
- ProGuard build: `./gradlew assembleRelease` — no crashes

### Manual
- Dark mode: Toggle system dark mode → verify all screens
- Trim: Drag handles to overlapping position → verify minimum gap enforced
- Zombie history: Externally delete audio file → verify history item can be deleted
- Converted seek: Convert audio → tap waveform → verify seek works
- Exit during conversion: Press back during conversion → verify dialog
- WhatsApp: Share to WhatsApp → verify no SecurityException
- Notifications: Convert with app in background → verify notification appears
- URL conversion: Paste audio URL → verify download + convert
- Batch: Select 3 files → verify sequential conversion
- Search: Type in history search → verify filtering

---

> **End of Design Spec**
