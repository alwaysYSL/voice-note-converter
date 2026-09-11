# Voice Note Converter V2 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix 5 critical bugs, add 6 UX improvements, 5 new features, and 7 quality/stability enhancements to the Voice Note Converter app.

**Architecture:** Existing MVVM with Jetpack Compose + Material3. Two-tab bottom navigation. Audio pipeline: MediaExtractor → MediaCodec → StreamingPcmProcessor → StreamingOpusEncoder → OggOpusWriter.

**Tech Stack:** Kotlin, Jetpack Compose + Material3, Media3 ExoPlayer, Room 2.7, Navigation Compose, MediaStore API.

## Global Constraints

- `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36`
- Java 11 source/target compatibility
- All dependencies via version catalog (`gradle/libs.versions.toml`)
- All source files under `app/src/main/java/com/example/` (until QA-7 migration)
- Package structure: `audio/`, `data/local/`, `data/repository/`, `telegram/`, `ui/`, `ui/components/`, `ui/theme/`
- Commits after each task

---

## Phase 1: Critical Bug Fixes

Implementation completed on 2026-09-11. Automated verification: 41 tests passed and debug APK built. Manual device checks and commit steps remain unchecked; see [phase-1 implementation report](2026-09-11-voice-note-v2-phase-1-report.md).

---

### Task 1: B1 — Fix Zombie History Items

**Files:**
- Modify: `app/src/main/java/com/example/audio/VoiceNoteStorage.kt`
- Modify: `app/src/main/java/com/example/ui/HistoryViewModel.kt`

**Interfaces:**
- Consumes: `VoiceNoteStorage.deleteFromStorage()`, `ConversionHistoryRepository.delete()`
- Produces: History items can always be deleted even when underlying file is missing

- [x] **Step 1: Add `fileExists()` method to `VoiceNoteStorage`**

In `VoiceNoteStorage.kt`, after the `deleteFromStorage()` method, add:

```kotlin
fun fileExists(context: Context, uriString: String): Boolean {
    return try {
        val uri = uriString.toUri()
        if (uri.scheme == "content") {
            context.contentResolver.openInputStream(uri)?.use { true } ?: false
        } else if (uri.scheme == "file") {
            val path = uri.path ?: return false
            File(path).exists()
        } else {
            false
        }
    } catch (_: Exception) {
        false
    }
}
```

Add import at top if not present:
```kotlin
import androidx.core.net.toUri
```

- [x] **Step 2: Fix `deleteItem()` in `HistoryViewModel.kt`**

Replace lines 69–93 in `HistoryViewModel.kt`:

```kotlin
fun deleteItem(item: ConversionHistory) {
    viewModelScope.launch {
        val fileAlreadyRemoved = item.id in pendingDatabaseDeletion
        val storageDeleted = fileAlreadyRemoved || VoiceNoteStorage.deleteFromStorage(
            getApplication(), item.outputFilePath
        )
        // If storage deletion failed, check if the file even exists
        val canProceed = storageDeleted || !VoiceNoteStorage.fileExists(
            getApplication(), item.outputFilePath
        )
        if (!canProceed) {
            _message.value = "File tidak dapat dihapus; entri riwayat tetap disimpan."
            return@launch
        }
        try {
            repository.delete(item.id)
            pendingDatabaseDeletion.remove(item.id)
            if (_currentlyPlayingId.value == item.id) {
                audioPlayer.stop()
                _currentlyPlayingId.value = null
            }
        } catch (error: Exception) {
            pendingDatabaseDeletion += item.id
            _message.value =
                "File sudah dihapus, tetapi riwayat belum terhapus. Coba hapus lagi."
        }
    }
}
```

- [ ] **Step 3: Verify**

Run: `./gradlew test`
Manual: Delete a voice note file externally from file manager → open app → verify the history item can be swiped/deleted successfully.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/audio/VoiceNoteStorage.kt app/src/main/java/com/example/ui/HistoryViewModel.kt
git commit -m "fix: delete zombie history items when audio file is externally removed"
```

---

### Task 2: B2 — Fix Trim Handle Collision

**Files:**
- Modify: `app/src/main/java/com/example/ui/components/WaveformVisualizer.kt`

**Interfaces:**
- Consumes: `trimRange`, `onTrimRangeChange`
- Produces: Trim handles can never collide, minimum 2% gap enforced

- [x] **Step 1: Fix handle selection and enforce minimum gap**

In `WaveformVisualizer.kt`, replace lines 59–85 (the `showTrimHandles` branch of the gesture modifier):

```kotlin
if (showTrimHandles && trimRange != null && onTrimRangeChange != null) {
    Modifier.pointerInput(showTrimHandles) {
        var draggingStart = true
        val minGap = 0.02f // minimum 2% gap between handles
        detectHorizontalDragGestures(
            onDragStart = { offset ->
                val range = currentTrimRange.value ?: return@detectHorizontalDragGestures
                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                val distToStart = abs(fraction - range.start)
                val distToEnd = abs(fraction - range.endInclusive)
                // Strict < so ties (overlapping handles) go to end handle
                draggingStart = distToStart < distToEnd
            },
            onHorizontalDrag = { change, _ ->
                change.consume()
                val range = currentTrimRange.value
                    ?: return@detectHorizontalDragGestures
                val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                if (draggingStart) {
                    val maxStart = (range.endInclusive - minGap).coerceAtLeast(0f)
                    currentOnTrimRangeChange.value?.invoke(
                        fraction.coerceIn(0f, maxStart)..range.endInclusive
                    )
                } else {
                    val minEnd = (range.start + minGap).coerceAtMost(1f)
                    currentOnTrimRangeChange.value?.invoke(
                        range.start..fraction.coerceIn(minEnd, 1f)
                    )
                }
            }
        )
    }
}
```

- [ ] **Step 2: Verify**

Manual: Open an audio file → enable trim → drag start handle all the way to the right → verify it stops before reaching end handle. Drag end handle to the left → verify it stops before reaching start handle.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/ui/components/WaveformVisualizer.kt
git commit -m "fix: prevent trim handle collision with minimum gap enforcement"
```

---

### Task 3: B3 — Fix Converted Audio Seek Disabled

**Files:**
- Modify: `app/src/main/java/com/example/ui/MainScreen.kt`

**Interfaces:**
- Consumes: `processStatus`, `hasConvertedResult`
- Produces: Waveform is interactive for seek after conversion

- [x] **Step 1: Fix `isInteractive` condition in `PreviewCard`**

In `MainScreen.kt`, find the `WaveformVisualizer` call inside `PreviewCard` (approximately line 349). Change:

```kotlin
// FROM:
isInteractive = processStatus == ProcessStatus.IDLE && !hasConvertedResult
// TO:
isInteractive = processStatus != ProcessStatus.CONVERTING &&
    processStatus != ProcessStatus.SENDING &&
    processStatus != ProcessStatus.ANALYZING
```

This allows seeking in IDLE, CONVERTED, SENT, and FAILED states.

- [ ] **Step 2: Verify**

Manual: Convert an audio file → after conversion completes → tap on waveform → verify playhead moves and audio seeks.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/ui/MainScreen.kt
git commit -m "fix: enable waveform seek after conversion completes"
```

---

### Task 4: B4 — Fix Opus Pre-Skip Fallback

**Files:**
- Modify: `app/src/main/java/com/example/audio/VoiceNoteConverter.kt`

**Interfaces:**
- Consumes: `MediaFormat` output from Opus encoder
- Produces: Always returns a valid pre-skip value (never throws)

- [x] **Step 1: Replace throw with default fallback**

In `VoiceNoteConverter.kt`, replace lines 90–94 of `opusPreSkipSamples()`:

```kotlin
// FROM:
return (fromNanoseconds ?: fromDelayKey ?: fromIdentificationHeader)
    ?.takeIf { it in 0..65_535 }
    ?: throw ConversionFailedException(
        "Encoder Opus tidak melaporkan delay yang diperlukan untuk container Ogg."
    )

// TO:
val DEFAULT_OPUS_PRE_SKIP = 312 // Standard Opus encoder lookahead: 6.5ms at 48kHz
return (fromNanoseconds ?: fromDelayKey ?: fromIdentificationHeader)
    ?.takeIf { it in 0..65_535 }
    ?: run {
        Log.w("VoiceNoteConverter", "Encoder did not report pre-skip delay; using default $DEFAULT_OPUS_PRE_SKIP samples")
        DEFAULT_OPUS_PRE_SKIP
    }
```

Ensure `import android.util.Log` is present (it already is at line 11).

- [ ] **Step 2: Verify**

Run existing tests: `./gradlew test` — no regressions.
Manual test on device: Convert a file → verify it completes without crash.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/audio/VoiceNoteConverter.kt
git commit -m "fix: use default pre-skip when Opus encoder doesn't report delay"
```

---

### Task 5: B5 — Fix URI Permission on Chooser Intent

**Files:**
- Modify: `app/src/main/java/com/example/telegram/TelegramSender.kt`

**Interfaces:**
- Consumes: `oggUri` (content URI from FileProvider)
- Produces: Chooser intent with proper URI permissions

- [x] **Step 1: Add `ClipData` import**

At the top of `TelegramSender.kt`, add:
```kotlin
import android.content.ClipData
```

- [x] **Step 2: Fix chooser intent**

In `TelegramSender.kt`, replace lines 86–92:

```kotlin
// FROM:
val launchIntent = if (intents.size == 1) {
    intents.first()
} else {
    Intent.createChooser(intents.first(), "Pilih aplikasi Telegram").apply {
        putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
    }
}.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

// TO:
val launchIntent = if (intents.size == 1) {
    intents.first()
} else {
    Intent.createChooser(intents.first(), "Pilih aplikasi Telegram").apply {
        putExtra(Intent.EXTRA_INITIAL_INTENTS, intents.drop(1).toTypedArray())
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipData = ClipData.newRawUri("", oggUri)
    }
}.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
```

- [ ] **Step 3: Verify**

Manual: Install 2 Telegram apps (official + Telegram X) → convert a file → tap "Kirim ke Telegram" → verify chooser appears → select one → verify no SecurityException and voice note is sent.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/telegram/TelegramSender.kt
git commit -m "fix: attach ClipData and URI permission to chooser intent"
```

---

## Phase 2: Foundation Quality

---

### Task 6: QA-7 — Package Name Migration

**Files:**
- Modify: `app/build.gradle.kts` — namespace
- Modify: ALL source files under `app/src/main/java/com/example/` — package declarations + imports
- Modify: ALL test files — package declarations + imports
- Move: Directory structure from `com/example/` to `com/aistudio/voicenote/`

**Interfaces:**
- Consumes: All existing source
- Produces: Clean package namespace, all references updated

- [ ] **Step 1: Update namespace in `build.gradle.kts`**

```kotlin
// FROM:
namespace = "com.example"
// TO:
namespace = "com.aistudio.voicenote"
```

- [ ] **Step 2: Create new directory structure**

```
mkdir -p app/src/main/java/com/aistudio/voicenote/audio
mkdir -p app/src/main/java/com/aistudio/voicenote/data/local
mkdir -p app/src/main/java/com/aistudio/voicenote/data/repository
mkdir -p app/src/main/java/com/aistudio/voicenote/telegram
mkdir -p app/src/main/java/com/aistudio/voicenote/ui/components
mkdir -p app/src/main/java/com/aistudio/voicenote/ui/theme
```

- [ ] **Step 3: Move and update all source files**

For each file, update:
1. `package com.example.*` → `package com.aistudio.voicenote.*`
2. All `import com.example.*` → `import com.aistudio.voicenote.*`
3. Move file to new directory

Files to migrate (24 source files):
- `MainActivity.kt` → `com.aistudio.voicenote`
- `audio/AudioPreviewPlayer.kt` → `com.aistudio.voicenote.audio`
- `audio/OggOpusWriter.kt` → `com.aistudio.voicenote.audio`
- `audio/VoiceNoteConverter.kt` → `com.aistudio.voicenote.audio`
- `audio/VoiceNoteStorage.kt` → `com.aistudio.voicenote.audio`
- `data/local/AppDatabase.kt` → `com.aistudio.voicenote.data.local`
- `data/local/ConversionHistory.kt` → `com.aistudio.voicenote.data.local`
- `data/local/ConversionHistoryDao.kt` → `com.aistudio.voicenote.data.local`
- `data/local/RecentContact.kt` → `com.aistudio.voicenote.data.local`
- `data/local/RecentContactDao.kt` → `com.aistudio.voicenote.data.local`
- `data/repository/ContactRepository.kt` → `com.aistudio.voicenote.data.repository`
- `data/repository/ConversionHistoryRepository.kt` → `com.aistudio.voicenote.data.repository`
- `telegram/TelegramSender.kt` → `com.aistudio.voicenote.telegram`
- `ui/AppNavigation.kt` → `com.aistudio.voicenote.ui`
- `ui/HistoryScreen.kt` → `com.aistudio.voicenote.ui`
- `ui/HistoryViewModel.kt` → `com.aistudio.voicenote.ui`
- `ui/MainScreen.kt` → `com.aistudio.voicenote.ui`
- `ui/MainViewModel.kt` → `com.aistudio.voicenote.ui`
- `ui/components/AddContactDialog.kt` → `com.aistudio.voicenote.ui.components`
- `ui/components/TrimControls.kt` → `com.aistudio.voicenote.ui.components`
- `ui/components/WaveformVisualizer.kt` → `com.aistudio.voicenote.ui.components`
- `ui/theme/Color.kt` → `com.aistudio.voicenote.ui.theme`
- `ui/theme/Theme.kt` → `com.aistudio.voicenote.ui.theme`
- `ui/theme/Type.kt` → `com.aistudio.voicenote.ui.theme`

- [ ] **Step 4: Update `AndroidManifest.xml`**

Activity reference uses short name `.MainActivity` which maps from namespace. After namespace change, this should still resolve correctly. Verify.

- [ ] **Step 5: Update test files**

Move test files from `com/example/` to `com/aistudio/voicenote/` and update package declarations and imports.

- [ ] **Step 6: Delete old `com/example/` directory**

```bash
rm -rf app/src/main/java/com/example/
rm -rf app/src/test/java/com/example/
```

- [ ] **Step 7: Verify build**

```bash
./gradlew assembleDebug
./gradlew test
```

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "refactor: migrate package namespace from com.example to com.aistudio.voicenote"
```

---

### Task 7: QA-3 — Enable ProGuard/R8

**Files:**
- Modify: `app/build.gradle.kts`
- Modify: `app/proguard-rules.pro`

**Interfaces:**
- Consumes: Release build config
- Produces: Minified APK with necessary keep rules

- [ ] **Step 1: Enable minification in `build.gradle.kts`**

```kotlin
// FROM:
release {
    isCrunchPngs = false
    isMinifyEnabled = false
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    signingConfig = signingConfigs.getByName("release")
}

// TO:
release {
    isCrunchPngs = false
    isMinifyEnabled = true
    isShrinkResources = true
    proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    signingConfig = signingConfigs.getByName("release")
}
```

- [ ] **Step 2: Add ProGuard rules**

In `app/proguard-rules.pro`, add:

```proguard
# Room entities & DAOs
-keep class com.aistudio.voicenote.data.local.** { *; }

# Keep Opus codec names for runtime discovery
-keep class android.media.MediaCodecList { *; }
-keep class android.media.MediaCodecInfo { *; }
-keep class android.media.MediaCodecInfo$CodecCapabilities { *; }

# Coroutines
-dontwarn kotlinx.coroutines.**

# Keep SendResult sealed class for when-exhaustiveness
-keep class com.aistudio.voicenote.telegram.SendResult { *; }
-keep class com.aistudio.voicenote.telegram.SendResult$* { *; }

# Keep AudioConversionException hierarchy
-keep class com.aistudio.voicenote.audio.AudioConversionException { *; }
-keep class com.aistudio.voicenote.audio.*Exception { *; }
```

- [ ] **Step 3: Verify release build**

```bash
./gradlew assembleRelease
```
Install release APK on device and test basic conversion flow.

- [ ] **Step 4: Commit**

```bash
git add app/build.gradle.kts app/proguard-rules.pro
git commit -m "build: enable R8 minification and resource shrinking for release"
```

---

### Task 8: QA-4 — Foreground Service for Conversion

**Files:**
- New: `app/src/main/java/com/aistudio/voicenote/audio/ConversionNotifier.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/MainActivity.kt`

**Interfaces:**
- Consumes: Conversion progress from `VoiceNoteConverter`
- Produces: Foreground notification during conversion, completion notification

- [ ] **Step 1: Add permissions to `AndroidManifest.xml`**

After the existing `WRITE_EXTERNAL_STORAGE` permission:

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

- [ ] **Step 2: Create `ConversionNotifier.kt`**

```kotlin
package com.aistudio.voicenote.audio

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object ConversionNotifier {
    private const val CHANNEL_ID = "conversion_progress"
    private const val COMPLETE_CHANNEL_ID = "conversion_complete"
    const val PROGRESS_NOTIFICATION_ID = 2001
    const val COMPLETE_NOTIFICATION_ID = 2002

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Proses Konversi", NotificationManager.IMPORTANCE_LOW)
            )
            manager.createNotificationChannel(
                NotificationChannel(COMPLETE_CHANNEL_ID, "Konversi Selesai", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }
    }

    fun progressNotification(context: Context, progress: Float): android.app.Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Mengonversi voice note")
            .setContentText("${(progress * 100).toInt()}% selesai")
            .setProgress(100, (progress * 100).toInt(), false)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun notifyComplete(context: Context, fileName: String) {
        try {
            val notification = NotificationCompat.Builder(context, COMPLETE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle("Konversi selesai")
                .setContentText("$fileName siap dikirim")
                .setAutoCancel(true)
                .build()
            NotificationManagerCompat.from(context).notify(COMPLETE_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not granted, silently skip
        }
    }

    fun cancelProgress(context: Context) {
        NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID)
    }
}
```

- [ ] **Step 3: Create notification channels on app startup**

In `MainActivity.onCreate()`, add:
```kotlin
ConversionNotifier.createChannels(this)
```

- [ ] **Step 4: Update `MainViewModel.startConversion()`**

Add progress notification updates in the conversion coroutine:
```kotlin
onProgress = { progress ->
    _uiState.update { it.copy(progress = progress) }
    // Update notification if permission granted
    try {
        NotificationManagerCompat.from(context).notify(
            ConversionNotifier.PROGRESS_NOTIFICATION_ID,
            ConversionNotifier.progressNotification(context, progress)
        )
    } catch (_: SecurityException) {}
}
```

After successful conversion, cancel progress and show completion:
```kotlin
ConversionNotifier.cancelProgress(context)
```

- [ ] **Step 5: Verify**

Manual: Start conversion → switch to another app → verify notification shows progress. Wait for completion → verify completion notification appears.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add conversion progress notification and completion alert"
```

---

### Task 9: QA-5 — Error Recovery

**Files:**
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainViewModel.kt`

**Interfaces:**
- Consumes: `processStatus`, conversion errors
- Produces: Auto-recovery from stuck states, conversion timeout

- [ ] **Step 1: Add conversion timeout**

In `MainViewModel`, add timeout watchdog in `startConversion()`:

```kotlin
private var timeoutJob: Job? = null

fun startConversion() {
    // ... existing code ...
    conversionJob = viewModelScope.launch(Dispatchers.IO) {
        // ... existing conversion logic ...
    }

    // Start timeout watchdog
    timeoutJob?.cancel()
    timeoutJob = viewModelScope.launch {
        kotlinx.coroutines.delay(5 * 60 * 1000L) // 5 minutes
        if (_uiState.value.processStatus == ProcessStatus.CONVERTING) {
            conversionJob?.cancel()
            ConversionNotifier.cancelProgress(context)
            fail("Konversi terlalu lama. Silakan coba lagi.", canRetry = true)
        }
    }
}
```

Cancel timeout when conversion completes normally:
```kotlin
// After successful conversion:
timeoutJob?.cancel()
```

- [ ] **Step 2: Verify**

Verify timeout doesn't interfere with normal conversions (should only fire after 5 minutes).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aistudio/voicenote/ui/MainViewModel.kt
git commit -m "feat: add conversion timeout watchdog for error recovery"
```

---

## Phase 3: UX Essentials

---

### Task 10: UX-1 — Dark Mode Support

**Files:**
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/theme/Theme.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/HistoryScreen.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/AppNavigation.kt`

**Interfaces:**
- Consumes: System dark mode preference
- Produces: Full dark mode theme that follows system setting

- [ ] **Step 1: Add `DarkColorScheme` to `Theme.kt`**

Add imports at top:
```kotlin
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.darkColorScheme
```

Add dark scheme definition after `LightColorScheme`:

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
```

- [ ] **Step 2: Update `MyApplicationTheme` to respect system preference**

```kotlin
@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val windowInsetsController = WindowCompat.getInsetsController(window, view)
                windowInsetsController.isAppearanceLightStatusBars = !darkTheme
                windowInsetsController.isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = SoftUiShapes,
        content = content
    )
}
```

- [ ] **Step 3: Replace hardcoded colors in `MainScreen.kt`**

Replace direct color references with `MaterialTheme.colorScheme` equivalents:
- `AppCanvasBackground` → `MaterialTheme.colorScheme.background`
- `CardSurfaceWhite` → `MaterialTheme.colorScheme.surface`
- `DeepNavyDisplay` → `MaterialTheme.colorScheme.onSurface`
- `SubtitleSlate` → `MaterialTheme.colorScheme.onSurfaceVariant`
- `SubtleBorder` → `MaterialTheme.colorScheme.outline`

Note: Keep accent colors (`AccentCoral`, `AccentRoyalBlue`, etc.) as-is — they work in both themes.

- [ ] **Step 4: Replace hardcoded colors in `HistoryScreen.kt`**

Same replacements as Step 3 for `HistoryScreen.kt`.

- [ ] **Step 5: Replace hardcoded colors in `AppNavigation.kt`**

Update `NavigationBar` colors:
```kotlin
containerColor = MaterialTheme.colorScheme.surface
// Update NavigationBarItemDefaults.colors:
selectedIconColor = MaterialTheme.colorScheme.onSurface,
selectedTextColor = MaterialTheme.colorScheme.onSurface,
unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
```

Update the Box background:
```kotlin
.background(MaterialTheme.colorScheme.background)
```

- [ ] **Step 6: Verify**

Manual: Toggle system dark mode on device → verify all screens adapt correctly. Check readability, contrast, and accent colors in both modes.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add dark mode support following system preference"
```

---

### Task 11: UX-3 — Exit Confirmation During Conversion

**Files:**
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainScreen.kt`

**Interfaces:**
- Consumes: `processStatus`
- Produces: Back press blocked with confirmation dialog during conversion

- [ ] **Step 1: Add `BackHandler` and exit dialog to `MainScreen`**

Add imports:
```kotlin
import androidx.activity.compose.BackHandler
```

In `MainScreen`, before the `Column`:

```kotlin
var showExitDialog by remember { mutableStateOf(false) }

if (state.processStatus == ProcessStatus.CONVERTING) {
    BackHandler { showExitDialog = true }
}

if (showExitDialog) {
    AlertDialog(
        onDismissRequest = { showExitDialog = false },
        title = { Text("Konversi sedang berjalan") },
        text = { Text("Keluar sekarang akan membatalkan proses konversi. Lanjutkan?") },
        confirmButton = {
            TextButton(onClick = {
                showExitDialog = false
                viewModel.resetForNewFile()
            }) {
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

Add import for `AlertDialog`:
```kotlin
import androidx.compose.material3.AlertDialog
```
(Already imported in HistoryScreen, check if MainScreen has it too)

- [ ] **Step 2: Verify**

Manual: Start conversion → press back → verify dialog appears → tap "Tetap di sini" → verify conversion continues → tap "Keluar" → verify conversion is cancelled.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aistudio/voicenote/ui/MainScreen.kt
git commit -m "feat: add exit confirmation dialog during active conversion"
```

---

### Task 12: UX-2 — Haptic Feedback on Trim Handle Drag

**Files:**
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/components/WaveformVisualizer.kt`

**Interfaces:**
- Consumes: Trim handle drag gesture
- Produces: Haptic feedback on drag start

- [ ] **Step 1: Add haptic feedback**

Add imports:
```kotlin
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
```

Inside the `WaveformVisualizer` composable, add:
```kotlin
val haptic = LocalHapticFeedback.current
```

In the `onDragStart` callback of the trim handles branch, add:
```kotlin
onDragStart = { offset ->
    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
    // ... existing handle selection logic ...
}
```

- [ ] **Step 2: Verify**

Manual: Enable trim → start dragging a handle → feel haptic vibration.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/aistudio/voicenote/ui/components/WaveformVisualizer.kt
git commit -m "feat: add haptic feedback when starting trim handle drag"
```

---

### Task 13: UX-6 — Conversion Complete Notification

**Dependencies:** Task 8 (QA-4) already creates `ConversionNotifier`

**Files:**
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainViewModel.kt`

**Interfaces:**
- Consumes: Conversion completion, app lifecycle state
- Produces: Notification when conversion completes while app is backgrounded

- [ ] **Step 1: Track app lifecycle in MainViewModel**

Add a `ProcessLifecycleOwner` observer or simpler approach — use a flag set from `MainActivity`:

In `MainViewModel`:
```kotlin
var isAppInForeground: Boolean = true
```

In `MainActivity`, add lifecycle observer:
```kotlin
lifecycle.addObserver(object : DefaultLifecycleObserver {
    override fun onStart(owner: LifecycleOwner) {
        // App came to foreground — tell ViewModel
        // (access via ViewModelProvider if needed)
    }
    override fun onStop(owner: LifecycleOwner) {
        // App went to background
    }
})
```

Alternatively, use `ProcessLifecycleOwner` for simplicity.

- [ ] **Step 2: Send notification on conversion complete when backgrounded**

In `MainViewModel.startConversion()`, after successful state update:
```kotlin
if (!isAppInForeground) {
    ConversionNotifier.notifyComplete(context, result.originalFileName)
}
```

- [ ] **Step 3: Verify**

Manual: Start conversion → switch to home screen → wait for completion → verify notification appears.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: show notification when conversion completes in background"
```

---

## Phase 4: Feature Additions

---

### Task 14: FT-8 — WhatsApp Sharing Support

**Files:**
- Modify: `app/src/main/java/com/aistudio/voicenote/telegram/TelegramSender.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/HistoryViewModel.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/HistoryScreen.kt`

**Interfaces:**
- Consumes: Voice note file URI
- Produces: Sharing to WhatsApp and WhatsApp Business in addition to Telegram

- [ ] **Step 1: Add WhatsApp packages to manifest `<queries>`**

```xml
<package android:name="com.whatsapp" />
<package android:name="com.whatsapp.w4b" />
```

- [ ] **Step 2: Extend `TelegramSender` to support WhatsApp**

Add WhatsApp package list:
```kotlin
private val WHATSAPP_PACKAGES = listOf(
    "com.whatsapp",
    "com.whatsapp.w4b"
)
```

Add WhatsApp detection methods:
```kotlin
fun findInstalledWhatsAppPackages(context: Context): List<String> = WHATSAPP_PACKAGES.filter { ... }
fun isWhatsAppInstalled(context: Context): Boolean = findInstalledWhatsAppPackages(context).firstOrNull() != null
```

Add unified share method:
```kotlin
fun sendVoiceNote(
    context: Context,
    oggUri: Uri,
    target: ShareTarget,
    contactNameHint: String? = null
): SendResult {
    val packages = when (target) {
        ShareTarget.Telegram -> findInstalledTelegramPackages(context)
        ShareTarget.WhatsApp -> findInstalledWhatsAppPackages(context)
    }
    // ... rest of intent creation (same pattern as existing) ...
}
```

- [ ] **Step 3: Add `ShareTarget` enum**

```kotlin
enum class ShareTarget { Telegram, WhatsApp }
```

- [ ] **Step 4: Update MainScreen UI**

Replace single "Kirim ke Telegram" button with two options:
```kotlin
Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(onClick = { onSend(ShareTarget.Telegram) }, modifier = Modifier.weight(1f)) {
        Text("Telegram")
    }
    OutlinedButton(onClick = { onSend(ShareTarget.WhatsApp) }, modifier = Modifier.weight(1f)) {
        Text("WhatsApp")
    }
}
```

- [ ] **Step 5: Update ViewModels**

Update `sendConverted()` in `MainViewModel` and `shareItem()` in `HistoryViewModel` to accept `ShareTarget` parameter.

- [ ] **Step 6: Verify**

Manual: Install WhatsApp → convert a file → verify WhatsApp button appears → tap → verify WhatsApp opens with voice note.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat: add WhatsApp sharing support alongside Telegram"
```

---

### Task 15: FT-10 — History Search & Filter

**Files:**
- Modify: `app/src/main/java/com/aistudio/voicenote/data/local/ConversionHistoryDao.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/data/repository/ConversionHistoryRepository.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/HistoryViewModel.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/HistoryScreen.kt`

**Interfaces:**
- Consumes: History list, search query, filter selection
- Produces: Filtered history display

- [ ] **Step 1: Add search query to DAO**

In `ConversionHistoryDao.kt`, add (optional — can filter in-memory for simplicity):
```kotlin
@Query("""
    SELECT * FROM conversion_history
    WHERE originalFileName LIKE '%' || :query || '%'
       OR outputFileName LIKE '%' || :query || '%'
    ORDER BY createdAt DESC
""")
fun searchHistory(query: String): Flow<List<ConversionHistory>>
```

- [ ] **Step 2: Add search and filter state to `HistoryViewModel`**

```kotlin
private val _searchQuery = MutableStateFlow("")
val searchQuery = _searchQuery.asStateFlow()

private val _filter = MutableStateFlow(HistoryFilter.ALL)
val filter = _filter.asStateFlow()

val filteredItems = combine(_searchQuery, _filter, repository.allHistory) { query, filter, items ->
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

fun updateSearch(query: String) { _searchQuery.value = query }
fun updateFilter(filter: HistoryFilter) { _filter.value = filter }

enum class HistoryFilter { ALL, SENT, NOT_SENT }
```

- [ ] **Step 3: Update `HistoryScreen` UI**

In `HistoryScreen`, use `filteredItems` instead of `historyItems`. Add search bar and filter chips above the list:

```kotlin
item {
    HistoryHeader(total = historyItems.size)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = searchQuery,
        onValueChange = viewModel::updateSearch,
        placeholder = { Text("Cari voice note...") },
        leadingIcon = { Icon(Icons.Default.Search, null) },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp)
    )
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = filter == HistoryFilter.ALL,
            onClick = { viewModel.updateFilter(HistoryFilter.ALL) },
            label = { Text("Semua") })
        FilterChip(selected = filter == HistoryFilter.SENT,
            onClick = { viewModel.updateFilter(HistoryFilter.SENT) },
            label = { Text("Terkirim") })
        FilterChip(selected = filter == HistoryFilter.NOT_SENT,
            onClick = { viewModel.updateFilter(HistoryFilter.NOT_SENT) },
            label = { Text("Belum") })
    }
}
```

- [ ] **Step 4: Verify**

Manual: Create several history items → type in search → verify filtering. Toggle filter chips → verify filtering by sent status.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add search and filter to conversion history"
```

---

### Task 16: FT-4 — Volume Boost/Normalization

**Files:**
- Modify: `app/src/main/java/com/aistudio/voicenote/audio/VoiceNoteConverter.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainScreen.kt`

**Interfaces:**
- Consumes: PCM audio stream, user-selected boost level
- Produces: Volume-adjusted audio in output

- [ ] **Step 1: Add volume boost to `StreamingPcmProcessor`**

Add parameter and apply gain in `process()`:

```kotlin
internal class StreamingPcmProcessor(
    private val channels: Int,
    private val sourceSampleRate: Int,
    private val targetSampleRate: Int = 48_000,
    private val volumeBoostDb: Float = 0f
) {
    private val boostFactor = if (volumeBoostDb != 0f) {
        10f.pow(volumeBoostDb / 20f)
    } else 1f

    fun process(interleavedSamples: ShortArray): ShortArray {
        // ... existing mono conversion ...
        // Apply volume boost after mono conversion:
        if (boostFactor != 1f) {
            for (i in mono.indices) {
                mono[i] = (mono[i] * boostFactor)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                    .toShort()
            }
        }
        // ... existing resampling ...
    }
}
```

Add import: `import kotlin.math.pow`

- [ ] **Step 2: Pass volume boost through conversion pipeline**

Update `convertToTelegramVoiceNote()` to accept `volumeBoostDb` parameter and pass to `StreamingPcmProcessor`.

- [ ] **Step 3: Add volume boost state to `MainViewModel`**

```kotlin
// In MainUiState:
val volumeBoostDb: Float = 0f

// In MainViewModel:
fun setVolumeBoost(db: Float) {
    _uiState.update { it.copy(volumeBoostDb = db.coerceIn(0f, 12f)) }
}
```

Pass to conversion:
```kotlin
val result = VoiceNoteConverter.convertToTelegramVoiceNote(
    // ... existing params ...
    volumeBoostDb = state.volumeBoostDb
)
```

- [ ] **Step 4: Add volume boost UI in `MainScreen`**

Below trim controls, add slider:
```kotlin
if (!hasConvertedResult && processStatus == ProcessStatus.IDLE) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Volume", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = volumeBoostDb,
            onValueChange = onVolumeBoostChange,
            valueRange = 0f..12f,
            steps = 11,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
        )
        Text("+${volumeBoostDb.toInt()} dB", style = MaterialTheme.typography.labelSmall)
    }
}
```

- [ ] **Step 5: Verify**

Manual: Select a quiet audio file → boost volume to +6 dB → convert → play → verify output is louder. Test +12 dB → verify no clipping artifacts.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add volume boost/normalization for quiet recordings"
```

---

### Task 17: FT-5 — Simple Noise Reduction (High-Pass Filter)

**Files:**
- Modify: `app/src/main/java/com/aistudio/voicenote/audio/VoiceNoteConverter.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainScreen.kt`

**Interfaces:**
- Consumes: PCM mono samples, user toggle
- Produces: High-pass filtered audio removing low-frequency noise

- [ ] **Step 1: Add `HighPassFilter` class to `VoiceNoteConverter.kt`**

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

- [ ] **Step 2: Integrate filter into `StreamingPcmProcessor`**

Add `enableNoiseReduction` parameter, create filter instance, apply per-sample after mono conversion.

- [ ] **Step 3: Add UI toggle**

In `MainScreen`, add FilterChip:
```kotlin
FilterChip(
    selected = noiseReductionEnabled,
    onClick = onToggleNoiseReduction,
    label = { Text("Kurangi noise latar") },
    leadingIcon = { Icon(Icons.Default.VolumeOff, null) }
)
```

- [ ] **Step 4: Verify**

Manual: Record audio with background hum → enable noise reduction → convert → play → verify low-frequency noise is reduced.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add simple high-pass noise reduction filter"
```

---

### Task 18: FT-1 — Batch Conversion

**Files:**
- New: `app/src/main/java/com/aistudio/voicenote/ui/BatchState.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainScreen.kt`

**Interfaces:**
- Consumes: Multiple file URIs
- Produces: Sequential conversion of all files, batch progress tracking

- [ ] **Step 1: Define batch state model**

```kotlin
data class BatchState(
    val items: List<BatchItem> = emptyList(),
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

- [ ] **Step 2: Add batch file picker**

Add `ActivityResultContracts.OpenMultipleDocuments()` launcher in `MainScreen`.

- [ ] **Step 3: Implement batch conversion logic in `MainViewModel`**

Process queue items one by one, updating batch state after each:
```kotlin
fun startBatchConversion() {
    // iterate over batch items, call convertToTelegramVoiceNote for each
}
```

- [ ] **Step 4: Add batch progress UI**

Show batch progress as a list of items with status indicators.

- [ ] **Step 5: Verify**

Manual: Select 3 files in batch mode → convert all → verify each converts sequentially → check all appear in history.

- [ ] **Step 6: Commit**

```bash
git add -A
git commit -m "feat: add batch conversion for multiple audio files"
```

---

### Task 19: FT-6 — URL Conversion

**Files:**
- New: `app/src/main/java/com/aistudio/voicenote/audio/UrlDownloader.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainScreen.kt`

**Interfaces:**
- Consumes: URL string from user input
- Produces: Downloaded file → passed to existing conversion pipeline

- [ ] **Step 1: Create `UrlDownloader.kt`**

Implement URL download with progress via `HttpURLConnection`.

- [ ] **Step 2: Add URL input field to `MainScreen`**

Add `OutlinedTextField` with "Unduh & Konversi" button.

- [ ] **Step 3: Add download + convert flow to `MainViewModel`**

```kotlin
fun handleUrl(url: String) {
    viewModelScope.launch {
        val downloadedFile = UrlDownloader.download(url, context.cacheDir) { progress -> ... }
        handleIncomingUri(Uri.fromFile(downloadedFile))
    }
}
```

- [ ] **Step 4: Verify**

Manual: Paste an audio URL → tap "Unduh & Konversi" → verify download progress → verify conversion completes.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add URL-based audio download and conversion"
```

---

## Phase 5: Polish & Testing

---

### Task 20: QA-1 — ViewModel Unit Tests

**Files:**
- New: `app/src/test/java/com/aistudio/voicenote/ui/MainViewModelTest.kt`
- New: `app/src/test/java/com/aistudio/voicenote/ui/HistoryViewModelTest.kt`

**Interfaces:**
- Consumes: ViewModels with mocked dependencies
- Produces: Test coverage for key ViewModel behaviors

- [ ] **Step 1: Create `MainViewModelTest.kt`**

Test cases:
- `handleIncomingUri` sets ANALYZING then IDLE
- `startConversion` sets CONVERTING then CONVERTED
- `toggleTrim` toggles TrimState.isActive
- `updateTrimRange` enforces boundaries
- `resetForNewFile` clears all state
- `sendConverted` handles success and failure

Use Robolectric + `kotlinx-coroutines-test`.

- [ ] **Step 2: Create `HistoryViewModelTest.kt`**

Test cases:
- `deleteItem` with existing file deletes both
- `deleteItem` with missing file still deletes DB record (B1 verification)
- `playPause` toggles correctly
- Search filter works (FT-10 verification)

- [ ] **Step 3: Run tests**

```bash
./gradlew test
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test: add ViewModel unit tests for MainViewModel and HistoryViewModel"
```

---

### Task 21: QA-2 — Compose UI Tests

**Files:**
- New: `app/src/test/java/com/aistudio/voicenote/ui/MainScreenTest.kt`
- New: `app/src/test/java/com/aistudio/voicenote/ui/HistoryScreenTest.kt`

**Interfaces:**
- Consumes: Screen composables via ComposeTestRule
- Produces: UI interaction test coverage

- [ ] **Step 1: Create `MainScreenTest.kt`**

Test cases:
- File picker button is displayed
- Conversion button appears after file selection
- Progress indicator shown during conversion
- Trim controls toggle
- Error state shows retry button

- [ ] **Step 2: Create `HistoryScreenTest.kt`**

Test cases:
- Empty state shown when no history
- History items render
- Search bar filters results

- [ ] **Step 3: Run tests**

```bash
./gradlew test
```

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "test: add Compose UI interaction tests for MainScreen and HistoryScreen"
```

---

### Task 22: UX-4 — Manual Timestamp Input for Trim

**Files:**
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/components/TrimControls.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainViewModel.kt`

**Interfaces:**
- Consumes: User-typed timestamp (MM:SS format)
- Produces: Updated trim range from parsed timestamp

- [ ] **Step 1: Create `TimestampField` composable**

Editable field with `MM:SS` format, input validation, auto-formatting.

- [ ] **Step 2: Replace static timestamp display in `TrimControls`**

Replace `Text("${time(trimState.startMs)} sampai ${time(trimState.endMs)}")` with `TimestampField` composables.

- [ ] **Step 3: Add `updateTrimManual()` to `MainViewModel`**

Accepts raw ms values from parsed input, validates range, updates state.

- [ ] **Step 4: Verify**

Manual: Enable trim → type "00:15" in start field → verify handle moves to 15 seconds.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add manual MM:SS timestamp input for trim positioning"
```

---

### Task 23: UX-5 — State Transition Animations

**Files:**
- Modify: `app/src/main/java/com/aistudio/voicenote/ui/MainScreen.kt`

**Interfaces:**
- Consumes: `processStatus` transitions
- Produces: Smooth animated transitions between UI states

- [ ] **Step 1: Wrap `ProcessActionArea` content in `AnimatedContent`**

```kotlin
AnimatedContent(
    targetState = state.processStatus,
    transitionSpec = {
        fadeIn(tween(300)) + slideInVertically { it / 4 } togetherWith fadeOut(tween(200))
    }
) { status -> /* existing when block */ }
```

- [ ] **Step 2: Animate `PreviewCard` background color**

```kotlin
val cardColor by animateColorAsState(
    targetValue = if (hasConvertedResult) PastelMintCardBg else MaterialTheme.colorScheme.surface,
    animationSpec = tween(400)
)
```

- [ ] **Step 3: Verify**

Manual: Go through full conversion flow → verify smooth transitions between states.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/aistudio/voicenote/ui/MainScreen.kt
git commit -m "feat: add smooth animated transitions between process states"
```

---

### Task 24: QA-6 — Crash Reporting

**Files:**
- New: `app/src/main/java/com/aistudio/voicenote/CrashHandler.kt`
- Modify: `app/src/main/java/com/aistudio/voicenote/MainActivity.kt`

**Interfaces:**
- Consumes: Uncaught exceptions
- Produces: Crash log file, on-next-launch dialog

- [ ] **Step 1: Create `CrashHandler.kt`**

```kotlin
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        try {
            val crashFile = File(context.filesDir, "crash_log.txt")
            crashFile.writeText(buildString {
                appendLine("Time: ${java.util.Date()}")
                appendLine("Thread: ${t.name}")
                appendLine(e.stackTraceToString())
            })
        } catch (_: Exception) {}
        defaultHandler?.uncaughtException(t, e)
    }
}
```

- [ ] **Step 2: Install handler in `MainActivity.onCreate()`**

```kotlin
Thread.setDefaultUncaughtExceptionHandler(CrashHandler(applicationContext))
```

- [ ] **Step 3: On launch, check for crash log and show dialog**

```kotlin
val crashFile = File(filesDir, "crash_log.txt")
if (crashFile.exists()) {
    // Show dialog with option to copy log to clipboard
    showCrashDialog = true
}
```

- [ ] **Step 4: Verify**

Force a crash → restart app → verify crash dialog appears with log content.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add lightweight crash logging and on-launch crash dialog"
```

---

## Summary

| Phase | Tasks | Est. Total |
|-------|-------|-----------|
| Phase 1: Bug Fixes | Tasks 1–5 (B1–B5) | ~1.5 hrs |
| Phase 2: Foundation | Tasks 6–9 (QA-7, QA-3, QA-4, QA-5) | ~6 hrs |
| Phase 3: UX | Tasks 10–13 (UX-1, UX-3, UX-2, UX-6) | ~4 hrs |
| Phase 4: Features | Tasks 14–19 (FT-8, FT-10, FT-4, FT-5, FT-1, FT-6) | ~12 hrs |
| Phase 5: Polish | Tasks 20–24 (QA-1, QA-2, UX-4, UX-5, QA-6) | ~8 hrs |
| **Total** | **24 tasks** | **~31.5 hrs** |

---

> **End of Implementation Plan**
