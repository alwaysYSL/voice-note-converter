# Voice Note Converter Polish — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Polish the Voice Note Converter app with interactive waveform preview (play/pause/seek/trim), persistent conversion history with playback, fixed Telegram sharing, and cleaned-up architecture.

**Architecture:** MVVM with Jetpack Compose. Two-tab bottom navigation (Converter + History). ExoPlayer replaces MediaPlayer for playback. Room DB extended with `ConversionHistory` table. Audio conversion refactored to stream in chunks with trim support. Dead TDLib code and unused dependencies removed.

**Tech Stack:** Kotlin, Jetpack Compose + Material3, Media3 ExoPlayer, Room 2.7, Navigation Compose, MediaStore API.

## Global Constraints

- `minSdk = 24`, `targetSdk = 36`, `compileSdk = 36`
- Java 11 source/target compatibility
- All dependencies via version catalog (`gradle/libs.versions.toml`)
- All source files under `app/src/main/java/com/example/`
- Package structure: `audio/`, `data/local/`, `data/repository/`, `telegram/`, `ui/`, `ui/components/`
- No new third-party dependencies beyond Media3 and Navigation (both already in catalog)
- Commits after each task

---

## Task 1: Dependencies & Build Config Cleanup

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/build.gradle.kts`
- Modify: `settings.gradle.kts` (if google-services plugin is declared there)

**Interfaces:**
- Consumes: Nothing
- Produces: Clean build config with Media3 + Navigation available, unused deps removed

- [ ] **Step 1: Add Media3 to version catalog**

In `gradle/libs.versions.toml`, add under `[versions]`:

```toml
media3 = "1.6.0"
```

Add under `[libraries]`:

```toml
androidx-media3-exoplayer = { group = "androidx.media3", name = "media3-exoplayer", version.ref = "media3" }
androidx-media3-common = { group = "androidx.media3", name = "media3-common", version.ref = "media3" }
```

- [ ] **Step 2: Update build.gradle.kts — remove unused deps, add new ones**

In `app/build.gradle.kts`:

Remove the `google-services` plugin:
```kotlin
// DELETE this line:
alias(libs.plugins.google.services)
```

Remove the `import` at the top:
```kotlin
// DELETE this line:
import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
```

Remove `googleServices { ... }` block (line 74).

Remove unused dependencies from the `dependencies` block:
```kotlin
// DELETE these:
implementation(platform(libs.firebase.bom))
implementation(libs.converter.moshi)
implementation(libs.firebase.ai)
implementation(libs.firebase.appcheck.recaptcha)
implementation(libs.firebase.appcheck.debug)
implementation(libs.logging.interceptor)
implementation(libs.moshi.kotlin)
implementation(libs.okhttp)
implementation(libs.retrofit)
"ksp"(libs.moshi.kotlin.codegen)
```

Add new dependencies:
```kotlin
// ADD these:
implementation(libs.androidx.media3.exoplayer)
implementation(libs.androidx.media3.common)
implementation(libs.androidx.navigation.compose)
```

Uncomment the navigation line (line 98 has it commented):
```kotlin
// Change from:
// implementation(libs.androidx.navigation.compose)
// To:
implementation(libs.androidx.navigation.compose)
```

Also remove the `secrets` block referencing `FIREBASE_APPCHECK_DEBUG_TOKEN` entry:
```kotlin
// In secrets block, remove:
ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL (warnings OK, no errors)

- [ ] **Step 4: Commit**

```bash
git add gradle/libs.versions.toml app/build.gradle.kts
git commit -m "build: add Media3 + Navigation deps, remove unused Firebase/Retrofit/Moshi"
```

---

## Task 2: Telegram Send Fix & Manifest

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/java/com/example/telegram/TelegramSender.kt`

**Interfaces:**
- Consumes: Nothing
- Produces: `TelegramSender.sendVoiceNoteViaTelegramApp(context, oggFile, contactNameHint): SendResult` — same signature, now works on Android 11+

- [ ] **Step 1: Add queries block and storage permission to AndroidManifest.xml**

Add before `<application>` tag:

```xml
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
        android:maxSdkVersion="28" />

    <queries>
        <package android:name="org.telegram.messenger" />
        <package android:name="org.telegram.messenger.web" />
        <package android:name="org.thunderdog.challegram" />
        <package android:name="org.telegram.plus" />
    </queries>
```

- [ ] **Step 2: Update TelegramSender for multi-client detection**

Replace `isTelegramInstalled()` with multi-client detection. Replace the entire `TelegramSender.kt`:

```kotlin
package com.example.telegram

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import java.io.File

sealed class SendResult {
    data class IntentLaunched(val details: String) : SendResult()
    data class Failure(val errorMessage: String, val canRetry: Boolean) : SendResult()
}

class TelegramSender {

    companion object {
        private val TELEGRAM_PACKAGES = listOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram",
            "org.telegram.plus"
        )
    }

    fun findInstalledTelegramPackage(context: Context): String? {
        val pm = context.packageManager
        for (pkg in TELEGRAM_PACKAGES) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: PackageManager.NameNotFoundException) {
                continue
            }
        }
        return null
    }

    fun sendVoiceNoteViaTelegramApp(
        context: Context,
        oggFile: File,
        contactNameHint: String? = null
    ): SendResult {
        val telegramPackage = findInstalledTelegramPackage(context)
            ?: return SendResult.Failure(
                errorMessage = "Telegram tidak ditemukan. Silakan install Telegram terlebih dahulu.",
                canRetry = false
            )

        return try {
            val authority = "${context.packageName}.fileprovider"
            val contentUri = FileProvider.getUriForFile(context, authority, oggFile)

            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/ogg"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                setPackage(telegramPackage)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            context.startActivity(sendIntent)
            SendResult.IntentLaunched(
                details = "Voice note dikirim ke Telegram" +
                    (contactNameHint?.let { " untuk $it" } ?: "")
            )
        } catch (e: Exception) {
            SendResult.Failure(
                errorMessage = "Gagal membuka Telegram: ${e.localizedMessage}",
                canRetry = true
            )
        }
    }
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/example/telegram/TelegramSender.kt
git commit -m "fix: add queries block for Telegram detection on Android 11+, multi-client support"
```

---

## Task 3: Audio Engine Fixes — Streaming Decode & Remove Fake Encoder

**Files:**
- Modify: `app/src/main/java/com/example/audio/VoiceNoteConverter.kt`
- Modify: `app/src/main/java/com/example/audio/OggOpusWriter.kt`

**Interfaces:**
- Consumes: Nothing
- Produces:
  - `VoiceNoteConverter.convertToTelegramVoiceNote(context: Context, inputUri: Uri, trimStartMs: Long = 0L, trimEndMs: Long = Long.MAX_VALUE, onProgress: (Float) -> Unit): AudioConversionResult` — same return type, new trim params, now streams in chunks
  - `OggOpusWriter` — same API, internally buffers multiple frames per page

- [ ] **Step 1: Add trim parameters to VoiceNoteConverter**

In `VoiceNoteConverter.kt`, change the `convertToTelegramVoiceNote` method signature:

```kotlin
fun convertToTelegramVoiceNote(
    context: Context,
    inputUri: Uri,
    trimStartMs: Long = 0L,
    trimEndMs: Long = Long.MAX_VALUE,
    onProgress: (Float) -> Unit
): AudioConversionResult
```

- [ ] **Step 2: Refactor decode loop to stream in chunks**

Replace the current approach that collects all PCM into `mutableListOf<Short>()` with a chunked streaming approach:

After `MediaExtractor` selects the audio track and `MediaCodec` is configured:

1. If `trimStartMs > 0`, call `extractor.seekTo(trimStartMs * 1000, MediaExtractor.SEEK_TO_CLOSEST_SYNC)`
2. In the decode output loop, check presentation time:
   ```kotlin
   val presentationTimeUs = bufferInfo.presentationTimeUs
   val presentationTimeMs = presentationTimeUs / 1000
   if (presentationTimeMs > trimEndMs) {
       // Stop decoding — we've passed the trim end
       break
   }
   if (presentationTimeMs < trimStartMs) {
       // Skip — before trim start (due to SEEK_TO_CLOSEST_SYNC imprecision)
       codec.releaseOutputBuffer(outputIndex, false)
       continue
   }
   ```
3. Process decoded PCM in the output buffer directly (downmix + resample per-chunk) instead of accumulating into a giant list
4. Feed resampled chunks directly to the Opus encoder

For waveform computation:
- Accumulate per-chunk absolute peak amplitude in a `MutableList<Float>` of chunk peaks
- After all chunks, normalize to 100 bars (0..31) by grouping chunk peaks into 100 buckets

- [ ] **Step 3: Remove fake fallback encoder**

Delete the `encodeFallbackPackets()` method entirely.

In the encoding flow, if `findOpusEncoder()` returns null:

```kotlin
val codecName = findOpusEncoder()
    ?: throw AudioConversionException.UnsupportedAudioFormatException(
        "Perangkat ini tidak memiliki encoder Opus. Konversi tidak dapat dilakukan."
    )
```

- [ ] **Step 4: Optimize OggOpusWriter — batch frames per page**

In `OggOpusWriter.kt`, add a buffering mechanism:

```kotlin
class OggOpusWriter(private val outputStream: OutputStream) {
    private val pendingPackets = mutableListOf<ByteArray>()
    private var pendingGranule = 0L
    private var pendingSamples = 0L
    private val maxPayloadBytes = 4000 // flush page when payload nears 4KB

    fun writeAudioPacket(packetData: ByteArray, samplesInPacket: Long, isLast: Boolean = false) {
        pendingPackets.add(packetData)
        pendingSamples += samplesInPacket
        pendingGranule += samplesInPacket

        val currentPayloadSize = pendingPackets.sumOf { it.size }
        if (isLast || currentPayloadSize >= maxPayloadBytes) {
            val headerType = if (isLast) 0x04 else 0x00
            writePage(headerType, pendingGranule, pendingPackets.toList())
            pendingPackets.clear()
        }
    }
    // ... rest stays the same, but remove flush() call from writePage()
    // Only flush in close()
}
```

- [ ] **Step 5: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/example/audio/VoiceNoteConverter.kt app/src/main/java/com/example/audio/OggOpusWriter.kt
git commit -m "fix: streaming decode to prevent OOM, add trim params, remove fake Opus fallback"
```

---

## Task 4: ExoPlayer Migration — AudioPreviewPlayer Rewrite

**Files:**
- Modify: `app/src/main/java/com/example/audio/AudioPreviewPlayer.kt`

**Interfaces:**
- Consumes: Media3 ExoPlayer dependency (Task 1)
- Produces:
  - `class AudioPreviewPlayer(context: Context, scope: CoroutineScope)`
  - `val playbackState: StateFlow<PlaybackState>`
  - `fun play(uri: Uri)`, `fun play(file: File)`
  - `fun pause()`, `fun resume()`, `fun togglePlayPause()`
  - `fun seekTo(positionMs: Long)`
  - `fun setClipping(startMs: Long, endMs: Long)`
  - `fun clearClipping()`
  - `fun stop()`, `fun release()`
  - `data class PlaybackState(isPlaying: Boolean, currentPositionMs: Long, totalDurationMs: Long, progress: Float)`

- [ ] **Step 1: Rewrite AudioPreviewPlayer with ExoPlayer**

Replace the entire contents of `AudioPreviewPlayer.kt`:

```kotlin
package com.example.audio

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentPositionMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val progress: Float = 0f
)

class AudioPreviewPlayer(
    context: Context,
    private val scope: CoroutineScope
) {
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState: StateFlow<PlaybackState> = _playbackState.asStateFlow()

    private val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build(),
            /* handleAudioFocus = */ true
        )
        .build()

    private var progressJob: Job? = null
    private var currentUri: Uri? = null
    private var clippingStartMs: Long = 0L
    private var clippingEndMs: Long = Long.MAX_VALUE

    init {
        exoPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                when (playbackState) {
                    Player.STATE_READY -> {
                        updateState()
                        if (exoPlayer.playWhenReady) startProgressTracker()
                    }
                    Player.STATE_ENDED -> {
                        stopProgressTracker()
                        _playbackState.value = _playbackState.value.copy(
                            isPlaying = false,
                            progress = 1f,
                            currentPositionMs = _playbackState.value.totalDurationMs
                        )
                    }
                    Player.STATE_IDLE -> {
                        stopProgressTracker()
                        _playbackState.value = PlaybackState()
                    }
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                if (isPlaying) startProgressTracker() else stopProgressTracker()
            }
        })
    }

    fun play(uri: Uri) {
        stop()
        currentUri = uri
        val mediaItem = if (clippingStartMs > 0 || clippingEndMs < Long.MAX_VALUE) {
            MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(clippingStartMs)
                        .setEndPositionMs(
                            if (clippingEndMs == Long.MAX_VALUE) C.TIME_END_OF_SOURCE
                            else clippingEndMs
                        )
                        .build()
                )
                .build()
        } else {
            MediaItem.fromUri(uri)
        }
        exoPlayer.setMediaItem(mediaItem)
        exoPlayer.prepare()
        exoPlayer.playWhenReady = true
    }

    fun play(file: File) {
        play(file.toUri())
    }

    fun pause() {
        exoPlayer.playWhenReady = false
    }

    fun resume() {
        if (exoPlayer.playbackState == Player.STATE_ENDED) {
            exoPlayer.seekTo(0)
        }
        exoPlayer.playWhenReady = true
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) {
            pause()
        } else {
            if (currentUri != null && exoPlayer.playbackState == Player.STATE_IDLE) {
                play(currentUri!!)
            } else {
                resume()
            }
        }
    }

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs)
        updateState()
    }

    fun setClipping(startMs: Long, endMs: Long) {
        clippingStartMs = startMs
        clippingEndMs = endMs
        // Re-prepare with clipping if currently has a source
        currentUri?.let { uri ->
            val wasPlaying = exoPlayer.isPlaying
            val mediaItem = MediaItem.Builder()
                .setUri(uri)
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(startMs)
                        .setEndPositionMs(if (endMs == Long.MAX_VALUE) C.TIME_END_OF_SOURCE else endMs)
                        .build()
                )
                .build()
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = wasPlaying
        }
    }

    fun clearClipping() {
        clippingStartMs = 0L
        clippingEndMs = Long.MAX_VALUE
        currentUri?.let { uri ->
            val wasPlaying = exoPlayer.isPlaying
            exoPlayer.setMediaItem(MediaItem.fromUri(uri))
            exoPlayer.prepare()
            exoPlayer.playWhenReady = wasPlaying
        }
    }

    fun stop() {
        stopProgressTracker()
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
        _playbackState.value = PlaybackState()
    }

    fun release() {
        stop()
        exoPlayer.release()
    }

    private fun startProgressTracker() {
        stopProgressTracker()
        progressJob = scope.launch {
            while (isActive && exoPlayer.isPlaying) {
                updateState()
                delay(50)
            }
        }
    }

    private fun stopProgressTracker() {
        progressJob?.cancel()
        progressJob = null
    }

    private fun updateState() {
        val duration = exoPlayer.duration.coerceAtLeast(0L)
        val position = exoPlayer.currentPosition.coerceIn(0L, duration)
        val progress = if (duration > 0) position.toFloat() / duration.toFloat() else 0f
        _playbackState.value = PlaybackState(
            isPlaying = exoPlayer.isPlaying,
            currentPositionMs = position,
            totalDurationMs = duration,
            progress = progress.coerceIn(0f, 1f)
        )
    }
}
```

- [ ] **Step 2: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/example/audio/AudioPreviewPlayer.kt
git commit -m "refactor: replace MediaPlayer with Media3 ExoPlayer, add seek and clipping support"
```

---

## Task 5: Interactive Waveform + Trim UI

**Files:**
- Modify: `app/src/main/java/com/example/ui/components/WaveformVisualizer.kt`
- Create: `app/src/main/java/com/example/ui/components/TrimControls.kt`

**Interfaces:**
- Consumes: `PlaybackState` from Task 4, `AudioPreviewPlayer.seekTo()` and `setClipping()` from Task 4
- Produces:
  - `WaveformVisualizer(waveform, progress, onSeek, trimRange, onTrimRangeChange, showPlayhead, showTrimHandles, isInteractive)` composable
  - `TrimControls(trimState, onTrimChange, onPreviewTrim, onResetTrim, isTrimActive, onToggleTrim)` composable
  - `data class TrimState(startMs, endMs, totalDurationMs, isActive)`

- [ ] **Step 1: Create TrimState data class and TrimControls composable**

Create `app/src/main/java/com/example/ui/components/TrimControls.kt`:

```kotlin
package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

data class TrimState(
    val startMs: Long = 0L,
    val endMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val isActive: Boolean = false
) {
    val startFraction: Float
        get() = if (totalDurationMs > 0) startMs.toFloat() / totalDurationMs else 0f
    val endFraction: Float
        get() = if (totalDurationMs > 0) endMs.toFloat() / totalDurationMs else 1f
    val trimmedDurationMs: Long
        get() = endMs - startMs
    val trimRange: ClosedFloatingPointRange<Float>
        get() = startFraction..endFraction
}

private fun formatTime(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%02d:%02d".format(minutes, seconds)
}

@Composable
fun TrimControls(
    trimState: TrimState,
    onToggleTrim: () -> Unit,
    onPreviewTrim: () -> Unit,
    onResetTrim: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxWidth()) {
        FilterChip(
            selected = trimState.isActive,
            onClick = onToggleTrim,
            label = { Text("✂ Trim") },
            leadingIcon = {
                Icon(Icons.Default.ContentCut, contentDescription = "Trim", modifier = Modifier)
            }
        )

        if (trimState.isActive) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${formatTime(trimState.startMs)} — ${formatTime(trimState.endMs)}",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Durasi: ${formatTime(trimState.trimmedDurationMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onPreviewTrim) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Text("Preview Trim", modifier = Modifier.padding(start = 4.dp))
                }
                OutlinedButton(onClick = onResetTrim) {
                    Icon(Icons.Default.Refresh, contentDescription = null)
                    Text("Reset", modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
    }
}
```

- [ ] **Step 2: Upgrade WaveformVisualizer with gesture support, playhead, and trim visuals**

Replace the entire contents of `WaveformVisualizer.kt`:

```kotlin
package com.example.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.sin

@Composable
fun WaveformVisualizer(
    waveform: List<Int>,
    progress: Float,
    modifier: Modifier = Modifier,
    activeColor: Color = MaterialTheme.colorScheme.primary,
    inactiveColor: Color = MaterialTheme.colorScheme.surfaceVariant,
    playheadColor: Color = MaterialTheme.colorScheme.tertiary,
    barWidth: Dp = 3.dp,
    barCornerRadius: Dp = 1.5.dp,
    minBarHeight: Dp = 2.dp,
    height: Dp = 48.dp,
    onSeek: ((Float) -> Unit)? = null,
    trimRange: ClosedFloatingPointRange<Float>? = null,
    showPlayhead: Boolean = true,
    showTrimHandles: Boolean = false,
    onTrimStartDrag: ((Float) -> Unit)? = null,
    onTrimEndDrag: ((Float) -> Unit)? = null,
    isInteractive: Boolean = true
) {
    val displayWaveform = remember(waveform) {
        if (waveform.isEmpty()) {
            List(38) { i -> (15 + 15 * sin(i * 0.5)).toInt() }
        } else {
            waveform
        }
    }

    val safeProgress = progress.coerceIn(0f, 1f)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .then(
                if (isInteractive && onSeek != null) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                                onSeek(fraction)
                            }
                        }
                        .pointerInput(Unit) {
                            detectHorizontalDragGestures { change, _ ->
                                change.consume()
                                val fraction =
                                    (change.position.x / size.width).coerceIn(0f, 1f)
                                onSeek(fraction)
                            }
                        }
                } else {
                    Modifier
                }
            )
    ) {
        val count = displayWaveform.size
        if (count == 0) return@Canvas

        val totalWidth = size.width
        val totalHeight = size.height
        val barWidthPx = barWidth.toPx()
        val minBarHeightPx = minBarHeight.toPx()
        val cornerRadiusPx = barCornerRadius.toPx()
        val maxAmplitude = 31f

        val totalBarsWidth = count * barWidthPx
        val spacing = if (count > 1) {
            ((totalWidth - totalBarsWidth) / (count - 1)).coerceAtLeast(0f)
        } else {
            0f
        }

        for (i in 0 until count) {
            val amplitude = displayWaveform[i].coerceIn(0, 31)
            val normalizedHeight =
                minBarHeightPx + (amplitude / maxAmplitude) * (totalHeight - minBarHeightPx)
            val barX = i * (barWidthPx + spacing)
            val barY = (totalHeight - normalizedHeight) / 2f
            val barFraction = if (count > 1) i.toFloat() / (count - 1) else 0f

            // Determine if bar is in trim region
            val inTrimRegion = trimRange == null ||
                    (barFraction >= trimRange.start && barFraction <= trimRange.endInclusive)
            val dimFactor = if (inTrimRegion) 1f else 0.3f

            val barColor = if (barFraction <= safeProgress) {
                activeColor.copy(alpha = dimFactor)
            } else {
                inactiveColor.copy(alpha = dimFactor)
            }

            drawRoundRect(
                color = barColor,
                topLeft = Offset(barX, barY),
                size = Size(barWidthPx, normalizedHeight),
                cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx)
            )
        }

        // Draw playhead
        if (showPlayhead && safeProgress > 0f) {
            val playheadX = safeProgress * totalWidth
            drawLine(
                color = playheadColor,
                start = Offset(playheadX, 0f),
                end = Offset(playheadX, totalHeight),
                strokeWidth = 2.dp.toPx()
            )
        }

        // Draw trim handles
        if (showTrimHandles && trimRange != null) {
            val handleWidth = 4.dp.toPx()
            val handleColor = Color(0xFFFF6B35)

            // Start handle
            val startX = trimRange.start * totalWidth
            drawRoundRect(
                color = handleColor,
                topLeft = Offset(startX - handleWidth / 2, 0f),
                size = Size(handleWidth, totalHeight),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )

            // End handle
            val endX = trimRange.endInclusive * totalWidth
            drawRoundRect(
                color = handleColor,
                topLeft = Offset(endX - handleWidth / 2, 0f),
                size = Size(handleWidth, totalHeight),
                cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
            )
        }
    }
}
```

- [ ] **Step 3: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/example/ui/components/WaveformVisualizer.kt app/src/main/java/com/example/ui/components/TrimControls.kt
git commit -m "feat: interactive waveform with seek/playhead/trim visuals, trim controls UI"
```

---

## Task 6: Conversion History — Database, Storage, and History Screen

**Files:**
- Create: `app/src/main/java/com/example/data/local/ConversionHistory.kt`
- Create: `app/src/main/java/com/example/data/local/ConversionHistoryDao.kt`
- Create: `app/src/main/java/com/example/data/repository/ConversionHistoryRepository.kt`
- Create: `app/src/main/java/com/example/audio/VoiceNoteStorage.kt`
- Create: `app/src/main/java/com/example/ui/HistoryScreen.kt`
- Create: `app/src/main/java/com/example/ui/HistoryViewModel.kt`
- Modify: `app/src/main/java/com/example/data/local/AppDatabase.kt`

**Interfaces:**
- Consumes: `AudioPreviewPlayer` from Task 4, `TelegramSender` from Task 2, `WaveformVisualizer` from Task 5
- Produces:
  - `ConversionHistory` entity, `ConversionHistoryDao`, `ConversionHistoryRepository`
  - `VoiceNoteStorage.saveToPublicStorage(context, cacheFile, fileName): String` returns saved file path
  - `VoiceNoteStorage.deleteFromStorage(context, filePath): Boolean`
  - `@Composable HistoryScreen(viewModel: HistoryViewModel)`
  - `HistoryViewModel` with `historyItems`, `playPause()`, `shareItem()`, `deleteItem()`

- [ ] **Step 1: Create ConversionHistory entity**

Create `app/src/main/java/com/example/data/local/ConversionHistory.kt`:

```kotlin
package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversion_history")
data class ConversionHistory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val originalFileName: String,
    val outputFileName: String,
    val outputFilePath: String,
    val durationSeconds: Int,
    val fileSizeBytes: Long,
    val waveform: String,
    val bitrateKbps: Int,
    val trimStartMs: Long? = null,
    val trimEndMs: Long? = null,
    val createdAt: Long,
    val sentTo: String? = null,
    val sentAt: Long? = null
)
```

- [ ] **Step 2: Create ConversionHistoryDao**

Create `app/src/main/java/com/example/data/local/ConversionHistoryDao.kt`:

```kotlin
package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

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

- [ ] **Step 3: Update AppDatabase — add ConversionHistory entity and migration**

In `AppDatabase.kt`, update:

```kotlin
package com.example.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [RecentContact::class, ConversionHistory::class],
    version = 2,
    autoMigrations = [
        AutoMigration(from = 1, to = 2)
    ],
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun recentContactDao(): RecentContactDao
    abstract fun conversionHistoryDao(): ConversionHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "voice_note_converter.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
```

Note: Using `fallbackToDestructiveMigration()` as safety net. For auto-migration, also set `exportSchema = true` in the `@Database` annotation. This requires adding `room { schemaDirectory("$projectDir/schemas") }` in `build.gradle.kts` — add this block:

```kotlin
// In app/build.gradle.kts, add after android { ... } block:
room {
    schemaDirectory("$projectDir/schemas")
}
```

And add the Room Gradle plugin. Alternatively, keep `exportSchema = false` and use `fallbackToDestructiveMigration()` only (simpler for v1 app with no production users):

```kotlin
@Database(
    entities = [RecentContact::class, ConversionHistory::class],
    version = 2,
    exportSchema = false
)
```

Use the simpler approach (`exportSchema = false` + `fallbackToDestructiveMigration()`) since this app has no production user data to preserve.

- [ ] **Step 4: Create ConversionHistoryRepository**

Create `app/src/main/java/com/example/data/repository/ConversionHistoryRepository.kt`:

```kotlin
package com.example.data.repository

import com.example.data.local.ConversionHistory
import com.example.data.local.ConversionHistoryDao
import kotlinx.coroutines.flow.Flow

class ConversionHistoryRepository(private val dao: ConversionHistoryDao) {

    val allHistory: Flow<List<ConversionHistory>> = dao.getAllHistory()

    suspend fun insert(history: ConversionHistory): Long {
        return dao.insert(history)
    }

    suspend fun updateSendStatus(id: Long, sentTo: String) {
        dao.updateSendStatus(id, sentTo, System.currentTimeMillis())
    }

    suspend fun delete(id: Long) {
        dao.deleteById(id)
    }

    suspend fun getById(id: Long): ConversionHistory? {
        return dao.getById(id)
    }
}
```

- [ ] **Step 5: Create VoiceNoteStorage utility**

Create `app/src/main/java/com/example/audio/VoiceNoteStorage.kt`:

```kotlin
package com.example.audio

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object VoiceNoteStorage {

    private const val FOLDER_NAME = "VoiceNoteConverter"

    fun generateFileName(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "VN_$timestamp.ogg"
    }

    fun saveToPublicStorage(context: Context, cacheFile: File, fileName: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveViaMediaStore(context, cacheFile, fileName)
        } else {
            saveDirectly(cacheFile, fileName)
        }
    }

    private fun saveViaMediaStore(context: Context, cacheFile: File, fileName: String): String {
        val contentValues = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/$FOLDER_NAME")
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: throw IllegalStateException("Gagal membuat file di MediaStore")

        resolver.openOutputStream(uri)?.use { outputStream ->
            FileInputStream(cacheFile).use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }

        contentValues.clear()
        contentValues.put(MediaStore.Audio.Media.IS_PENDING, 0)
        resolver.update(uri, contentValues, null, null)

        // Return the actual file path
        val cursor = resolver.query(uri, arrayOf(MediaStore.Audio.Media.DATA), null, null, null)
        val path = cursor?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
        return path ?: "Music/$FOLDER_NAME/$fileName"
    }

    private fun saveDirectly(cacheFile: File, fileName: String): String {
        @Suppress("DEPRECATION")
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val targetDir = File(musicDir, FOLDER_NAME)
        if (!targetDir.exists()) targetDir.mkdirs()

        val targetFile = File(targetDir, fileName)
        FileInputStream(cacheFile).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
        return targetFile.absolutePath
    }

    fun deleteFromStorage(context: Context, filePath: String): Boolean {
        return try {
            val file = File(filePath)
            if (file.exists()) {
                file.delete()
            } else {
                // Try MediaStore removal for scoped storage
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val selection = "${MediaStore.Audio.Media.DATA} = ?"
                    val deleted = context.contentResolver.delete(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        selection,
                        arrayOf(filePath)
                    )
                    deleted > 0
                } else {
                    false
                }
            }
        } catch (_: Exception) {
            false
        }
    }
}
```

- [ ] **Step 6: Create HistoryViewModel**

Create `app/src/main/java/com/example/ui/HistoryViewModel.kt`:

```kotlin
package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPreviewPlayer
import com.example.audio.VoiceNoteStorage
import com.example.data.local.AppDatabase
import com.example.data.local.ConversionHistory
import com.example.data.repository.ConversionHistoryRepository
import com.example.telegram.TelegramSender
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

class HistoryViewModel(application: Application) : AndroidViewModel(application) {

    private val db = AppDatabase.getDatabase(application)
    private val historyRepository = ConversionHistoryRepository(db.conversionHistoryDao())
    private val telegramSender = TelegramSender()
    val audioPlayer = AudioPreviewPlayer(application, viewModelScope)

    val historyItems: StateFlow<List<ConversionHistory>> = historyRepository.allHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentlyPlayingId = MutableStateFlow<Long?>(null)
    val currentlyPlayingId: StateFlow<Long?> = _currentlyPlayingId.asStateFlow()

    val playbackState = audioPlayer.playbackState

    fun playPause(item: ConversionHistory) {
        if (_currentlyPlayingId.value == item.id) {
            audioPlayer.togglePlayPause()
        } else {
            _currentlyPlayingId.value = item.id
            val file = File(item.outputFilePath)
            if (file.exists()) {
                audioPlayer.play(file)
            }
        }
    }

    fun shareItem(item: ConversionHistory) {
        val context = getApplication<Application>()
        val file = File(item.outputFilePath)
        if (file.exists()) {
            telegramSender.sendVoiceNoteViaTelegramApp(context, file)
        }
    }

    fun deleteItem(item: ConversionHistory) {
        viewModelScope.launch {
            val context = getApplication<Application>()
            VoiceNoteStorage.deleteFromStorage(context, item.outputFilePath)
            historyRepository.delete(item.id)
            if (_currentlyPlayingId.value == item.id) {
                audioPlayer.stop()
                _currentlyPlayingId.value = null
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        audioPlayer.release()
    }
}
```

- [ ] **Step 7: Create HistoryScreen composable**

Create `app/src/main/java/com/example/ui/HistoryScreen.kt`:

```kotlin
package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.audio.PlaybackState
import com.example.data.local.ConversionHistory
import com.example.ui.components.WaveformVisualizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier
) {
    val historyItems by viewModel.historyItems.collectAsState()
    val currentlyPlayingId by viewModel.currentlyPlayingId.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()

    if (historyItems.isEmpty()) {
        EmptyHistoryState(modifier = modifier.fillMaxSize())
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = "Riwayat Konversi",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                )
            }
            items(historyItems, key = { it.id }) { item ->
                HistoryItemCard(
                    item = item,
                    isPlaying = currentlyPlayingId == item.id,
                    playbackState = if (currentlyPlayingId == item.id) playbackState else PlaybackState(),
                    onPlayPause = { viewModel.playPause(item) },
                    onShare = { viewModel.shareItem(item) },
                    onDelete = { viewModel.deleteItem(item) }
                )
            }
            item { Spacer(modifier = Modifier.height(80.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryItemCard(
    item: ConversionHistory,
    isPlaying: Boolean,
    playbackState: PlaybackState,
    onPlayPause: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = {
            if (it == SwipeToDismissBoxValue.EndToStart) {
                onDelete()
                true
            } else false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val color by animateColorAsState(
                if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart)
                    MaterialTheme.colorScheme.errorContainer
                else MaterialTheme.colorScheme.surface,
                label = "swipe_bg"
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(color, MaterialTheme.shapes.medium)
                    .padding(end = 20.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Hapus",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        },
        enableDismissFromStartToEnd = false
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // File info
                Text(
                    text = item.originalFileName,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.outputFileName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                // Metadata row
                val dateStr = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
                    .format(Date(item.createdAt))
                val durationStr = "%02d:%02d".format(item.durationSeconds / 60, item.durationSeconds % 60)
                val sizeStr = if (item.fileSizeBytes < 1024 * 1024) {
                    "%.1f KB".format(item.fileSizeBytes / 1024f)
                } else {
                    "%.1f MB".format(item.fileSizeBytes / (1024f * 1024f))
                }

                Text(
                    text = "$durationStr • $sizeStr • $dateStr",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )

                // Send status
                if (item.sentTo != null) {
                    Text(
                        text = "✅ Dikirim ke ${item.sentTo}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // Waveform + playback
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(36.dp)
                            .background(
                                MaterialTheme.colorScheme.primaryContainer,
                                CircleShape
                            )
                    ) {
                        Icon(
                            if (isPlaying && playbackState.isPlaying) Icons.Default.Pause
                            else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying && playbackState.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }

                    val waveformData = try {
                        item.waveform.removeSurrounding("[", "]")
                            .split(",")
                            .map { it.trim().toInt() }
                    } catch (_: Exception) {
                        emptyList()
                    }

                    WaveformVisualizer(
                        waveform = waveformData,
                        progress = if (isPlaying) playbackState.progress else 0f,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 8.dp),
                        height = 32.dp,
                        showPlayhead = isPlaying,
                        isInteractive = false
                    )

                    IconButton(onClick = onShare) {
                        Icon(
                            Icons.Default.Share,
                            contentDescription = "Share",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyHistoryState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.History,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Belum ada konversi",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Mulai convert voice note pertama Anda!",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}
```

- [ ] **Step 8: Verify build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 9: Commit**

```bash
git add app/src/main/java/com/example/data/ app/src/main/java/com/example/audio/VoiceNoteStorage.kt app/src/main/java/com/example/ui/HistoryScreen.kt app/src/main/java/com/example/ui/HistoryViewModel.kt
git commit -m "feat: conversion history with Room DB, public storage, history screen with playback"
```

---

## Task 7: Navigation, MainScreen/ViewModel Refactor, Dead Code Cleanup

**Files:**
- Create: `app/src/main/java/com/example/ui/AppNavigation.kt`
- Modify: `app/src/main/java/com/example/ui/MainViewModel.kt`
- Modify: `app/src/main/java/com/example/ui/MainScreen.kt`
- Modify: `app/src/main/java/com/example/MainActivity.kt`
- Modify: `app/src/main/java/com/example/data/repository/ContactRepository.kt`
- Delete: `app/src/main/java/com/example/telegram/TelegramSessionManager.kt`
- Delete: `app/src/main/java/com/example/ui/components/TelegramAuthBottomSheet.kt`

**Interfaces:**
- Consumes: All previous tasks — `AudioPreviewPlayer` (Task 4), `WaveformVisualizer` + `TrimControls` (Task 5), `ConversionHistoryRepository` + `VoiceNoteStorage` + `HistoryScreen` + `HistoryViewModel` (Task 6), `TelegramSender` (Task 2), `VoiceNoteConverter` with trim (Task 3)
- Produces: Complete working app with two-tab navigation

- [ ] **Step 1: Delete dead code files**

Delete these files:
- `app/src/main/java/com/example/telegram/TelegramSessionManager.kt`
- `app/src/main/java/com/example/ui/components/TelegramAuthBottomSheet.kt`

- [ ] **Step 2: Create AppNavigation composable**

Create `app/src/main/java/com/example/ui/AppNavigation.kt`:

```kotlin
package com.example.ui

import android.net.Uri
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

sealed class Screen(val route: String, val label: String) {
    object Converter : Screen("converter", "Converter")
    object History : Screen("history", "Riwayat")
}

@Composable
fun AppNavigation(
    incomingUri: Uri? = null,
    modifier: Modifier = Modifier
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val screens = listOf(Screen.Converter, Screen.History)

    Scaffold(
        bottomBar = {
            NavigationBar {
                screens.forEach { screen ->
                    val selected = currentDestination?.hierarchy
                        ?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        icon = {
                            when (screen) {
                                Screen.Converter -> Icon(
                                    if (selected) Icons.Filled.Mic else Icons.Outlined.Mic,
                                    contentDescription = screen.label
                                )
                                Screen.History -> Icon(
                                    if (selected) Icons.Filled.History else Icons.Outlined.History,
                                    contentDescription = screen.label
                                )
                            }
                        },
                        label = { Text(screen.label) },
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.startDestinationId) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        }
                    )
                }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Converter.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Converter.route) {
                val converterViewModel: MainViewModel = viewModel()
                // Pass incoming URI if available
                if (incomingUri != null) {
                    converterViewModel.handleIncomingUri(incomingUri)
                }
                MainScreen(viewModel = converterViewModel)
            }
            composable(Screen.History.route) {
                val historyViewModel: HistoryViewModel = viewModel()
                HistoryScreen(viewModel = historyViewModel)
            }
        }
    }
}
```

- [ ] **Step 3: Refactor MainViewModel — split convert/send, add trim, remove TDLib, save history**

This is the most complex modification. The key changes to `MainViewModel.kt`:

1. **Remove all TDLib references:** Delete `sessionManager` field, `loginTelegram()`, `submitOtpCode()`, `submit2FaPassword()`, `logoutTelegram()`, all `TelegramSessionManager` imports, `telegramSessionState` from `MainUiState`, `showAuthSheet` from `MainUiState`.

2. **Add new state fields:**
```kotlin
// Add to MainUiState:
val trimState: TrimState = TrimState(),
val convertedFilePath: String? = null,
val lastConversionId: Long? = null,
```

3. **Add CONVERTED to ProcessStatus:**
```kotlin
enum class ProcessStatus { IDLE, ANALYZING, CONVERTING, CONVERTED, SENDING, SENT, FAILED }
```

4. **Add ConversionHistoryRepository and VoiceNoteStorage:**
```kotlin
private val historyRepository = ConversionHistoryRepository(
    AppDatabase.getDatabase(application).conversionHistoryDao()
)
```

5. **Replace AudioPreviewPlayer instantiation** — pass `viewModelScope`:
```kotlin
private val audioPlayer = AudioPreviewPlayer(application, viewModelScope)
```

6. **Add trim methods:**
```kotlin
fun toggleTrim() { /* toggle trimState.isActive, set default endMs = duration */ }
fun updateTrimRange(startFraction: Float, endFraction: Float) { /* update trimState ms values */ }
fun resetTrim() { /* reset to 0..totalDuration */ }
fun previewTrim() { /* audioPlayer.setClipping(trimStartMs, trimEndMs); audioPlayer.togglePlayPause() */ }
```

7. **Split startConvertAndSend() into:**
```kotlin
fun startConversion() {
    viewModelScope.launch(Dispatchers.IO) {
        _uiState.update { it.copy(processStatus = ProcessStatus.CONVERTING) }
        try {
            val result = VoiceNoteConverter.convertToTelegramVoiceNote(
                context = getApplication(),
                inputUri = _uiState.value.selectedFileUri!!,
                trimStartMs = _uiState.value.trimState.startMs,
                trimEndMs = _uiState.value.trimState.endMs,
                onProgress = { progress ->
                    _uiState.update { it.copy(conversionProgress = progress) }
                }
            )
            // Save to public storage
            val fileName = VoiceNoteStorage.generateFileName()
            val savedPath = VoiceNoteStorage.saveToPublicStorage(
                getApplication(), result.outputFile, fileName
            )
            // Save to history DB
            val historyId = historyRepository.insert(
                ConversionHistory(
                    originalFileName = result.originalFileName,
                    outputFileName = fileName,
                    outputFilePath = savedPath,
                    durationSeconds = result.durationSeconds,
                    fileSizeBytes = result.outputFile.length(),
                    waveform = result.waveform.toString(),
                    bitrateKbps = result.bitrateKbps,
                    trimStartMs = _uiState.value.trimState.let { if (it.isActive) it.startMs else null },
                    trimEndMs = _uiState.value.trimState.let { if (it.isActive) it.endMs else null },
                    createdAt = System.currentTimeMillis()
                )
            )
            _uiState.update {
                it.copy(
                    processStatus = ProcessStatus.CONVERTED,
                    waveform = result.waveform,
                    durationSeconds = result.durationSeconds,
                    convertedFilePath = savedPath,
                    lastConversionId = historyId
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    processStatus = ProcessStatus.FAILED,
                    errorMessage = e.message
                )
            }
        }
    }
}

fun sendConverted() {
    val state = _uiState.value
    val filePath = state.convertedFilePath ?: return
    val file = java.io.File(filePath)
    val contactName = state.selectedContact?.name

    val result = telegramSender.sendVoiceNoteViaTelegramApp(
        getApplication(), file, contactName
    )
    when (result) {
        is SendResult.IntentLaunched -> {
            _uiState.update { it.copy(processStatus = ProcessStatus.SENT) }
            // Update history with send info
            viewModelScope.launch {
                state.lastConversionId?.let { id ->
                    contactName?.let { name ->
                        historyRepository.updateSendStatus(id, name)
                    }
                }
            }
        }
        is SendResult.Failure -> {
            _uiState.update {
                it.copy(processStatus = ProcessStatus.FAILED, errorMessage = result.errorMessage)
            }
        }
    }
}
```

8. **Add seekTo method for waveform interaction:**
```kotlin
fun seekToFraction(fraction: Float) {
    val durationMs = audioPlayer.playbackState.value.totalDurationMs
    if (durationMs > 0) {
        audioPlayer.seekTo((fraction * durationMs).toLong())
    }
}
```

9. **Change file picker** — the MIME type filter change will be in MainScreen's `ActivityResultContracts`.

- [ ] **Step 4: Refactor MainScreen — remove TDLib UI, add trim controls, split Convert/Send buttons**

Key changes to `MainScreen.kt`:

1. **Remove all TDLib-related UI:** Header status indicator, auth button, `TelegramAuthBottomSheet` composable call, related imports.

2. **Change file picker** from `GetContent("*/*")` to `OpenDocument`:
```kotlin
val filePicker = rememberLauncherForActivityResult(
    ActivityResultContracts.OpenDocument()
) { uri ->
    uri?.let { viewModel.handleIncomingUri(it) }
}
// Launch with:
filePicker.launch(arrayOf("audio/*", "video/*"))
```

3. **Add waveform seek callback** in `FeaturedMediaCard`:
```kotlin
WaveformVisualizer(
    waveform = uiState.waveform,
    progress = playbackState.progress,
    onSeek = { fraction -> viewModel.seekToFraction(fraction) },
    trimRange = if (uiState.trimState.isActive) uiState.trimState.trimRange else null,
    showPlayhead = true,
    showTrimHandles = uiState.trimState.isActive,
    isInteractive = true
)
```

4. **Add TrimControls** below waveform when file is loaded:
```kotlin
if (uiState.selectedFileUri != null) {
    TrimControls(
        trimState = uiState.trimState,
        onToggleTrim = { viewModel.toggleTrim() },
        onPreviewTrim = { viewModel.previewTrim() },
        onResetTrim = { viewModel.resetTrim() }
    )
}
```

5. **Replace single "Send" button** with conditional buttons:
```kotlin
when (uiState.processStatus) {
    ProcessStatus.IDLE, ProcessStatus.ANALYZING -> {
        // Show "Convert" button
        Button(onClick = { viewModel.startConversion() }) {
            Text("🔄 Convert ke Voice Note")
        }
    }
    ProcessStatus.CONVERTED -> {
        // Show "Send to Telegram" button
        Button(onClick = { viewModel.sendConverted() }) {
            Text("📤 Kirim ke Telegram")
        }
    }
    // ... other states
}
```

- [ ] **Step 5: Update MainActivity — handle URI persistence, use AppNavigation**

In `MainActivity.kt`:

1. Add `takePersistableUriPermission`:
```kotlin
private fun handleIntent(intent: Intent) {
    val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM) ?: intent.data ?: return
    try {
        contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    } catch (_: SecurityException) {
        // Not all URIs support persistable permissions — that's OK
    }
    incomingUri.value = uri
}
```

2. Replace `MainScreen` call with `AppNavigation`:
```kotlin
setContent {
    MyApplicationTheme {
        AppNavigation(
            incomingUri = incomingUri.value
        )
    }
}
```

Use a `mutableStateOf<Uri?>()` to hold the incoming URI and pass it through.

- [ ] **Step 6: Remove mock seed contacts from ContactRepository**

In `ContactRepository.kt`, delete or empty the `seedInitialContactsIfEmpty()` method body:

```kotlin
suspend fun seedInitialContactsIfEmpty() {
    // No longer seeding mock contacts — users add their own
}
```

- [ ] **Step 7: Verify full build compiles**

Run: `./gradlew assembleDebug`
Expected: BUILD SUCCESSFUL with no errors

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat: bottom navigation, split convert/send flow, trim integration, cleanup dead TDLib code"
```

---

## Self-Review Checklist

### Spec Coverage
| Spec Requirement | Task |
|---|---|
| G1: Interactive waveform (play/pause/seek/playhead) | Task 4 (ExoPlayer), Task 5 (WaveformVisualizer) |
| G2: Audio trim before conversion | Task 3 (converter trim params), Task 5 (TrimControls UI), Task 7 (ViewModel integration) |
| G3: Separate Convert and Send | Task 7 (MainViewModel split, MainScreen UI) |
| G4: Persistent history + playback | Task 6 (DB, storage, HistoryScreen, HistoryViewModel) |
| G5: Fix Telegram detection | Task 2 (manifest queries, TelegramSender) |
| G6: ExoPlayer migration | Task 4 (AudioPreviewPlayer rewrite) |
| G7: Streaming decode (OOM fix) | Task 3 (VoiceNoteConverter refactor) |
| G8: Dead code removal | Task 1 (deps), Task 7 (TDLib files, seed contacts) |

### No gaps found. All spec goals mapped to tasks.
