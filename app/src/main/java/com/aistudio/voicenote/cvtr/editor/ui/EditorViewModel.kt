package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.voicenote.cvtr.audio.VoiceNoteConverter
import com.aistudio.voicenote.cvtr.audio.WaveformCodec
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.repository.ConversionHistoryRepository
import com.aistudio.voicenote.cvtr.editor.command.AddTrackCommand
import com.aistudio.voicenote.cvtr.editor.command.CommandHistory
import com.aistudio.voicenote.cvtr.editor.command.DeleteClipCommand
import com.aistudio.voicenote.cvtr.editor.command.EditorCommand
import com.aistudio.voicenote.cvtr.editor.command.MoveClipCommand
import com.aistudio.voicenote.cvtr.editor.command.RemoveTrackCommand
import com.aistudio.voicenote.cvtr.editor.command.SetClipFadeCommand
import com.aistudio.voicenote.cvtr.editor.command.SetClipPitchCommand
import com.aistudio.voicenote.cvtr.editor.command.SetClipSpeedCommand
import com.aistudio.voicenote.cvtr.editor.command.SetTrackMutedCommand
import com.aistudio.voicenote.cvtr.editor.command.SetTrackVolumeCommand
import com.aistudio.voicenote.cvtr.editor.command.SplitClipCommand
import com.aistudio.voicenote.cvtr.editor.command.TrimClipCommand
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.editor.model.MAX_TIMELINE_MS
import com.aistudio.voicenote.cvtr.editor.model.TimelineError
import com.aistudio.voicenote.cvtr.editor.model.TimelineResult
import com.aistudio.voicenote.cvtr.editor.model.value
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/** Metadata extracted before a source is added to the timeline. */
internal data class AudioSourceInfo(
    val displayName: String,
    val durationMs: Long,
)

sealed interface EditorLaunchSource {
    data class Converted(
        val resultUri: Uri,
        val originalUri: Uri?,
        val displayName: String,
    ) : EditorLaunchSource

    data class History(val historyId: Long) : EditorLaunchSource
}

internal enum class EditorMessage {
    TIMELINE_LIMIT,
    TRACK_LIMIT,
    HISTORY_NOT_FOUND,
    IMPORT_FAILED,
}

internal enum class EditorSheet {
    FADE,
    PITCH,
    SPEED,
    CLEANUP,
}

/** Intents shared by the editor shell and its timeline controls. */
internal sealed interface EditorIntent {
    data class Seek(val positionMs: Long) : EditorIntent
    data class SelectClip(val clipId: String?) : EditorIntent
    data class Split(val splitTimelineMs: Long) : EditorIntent
    data class Trim(
        val sourceStartMs: Long,
        val sourceEndMs: Long,
    ) : EditorIntent
    data class Move(val timelineStartMs: Long) : EditorIntent
    data class Delete(val ripple: Boolean = true) : EditorIntent
    data class SetFade(val fadeInMs: Long, val fadeOutMs: Long) : EditorIntent
    data class SetPitch(val pitchSemitones: Float) : EditorIntent
    data class SetSpeed(val speed: Float) : EditorIntent
    data class SetTrackVolume(val trackId: String, val volume: Float) : EditorIntent
    data class SetTrackMuted(val trackId: String, val muted: Boolean) : EditorIntent
    data class RemoveTrack(val trackId: String) : EditorIntent
    data object Undo : EditorIntent
    data object Redo : EditorIntent
    data object ClearMessage : EditorIntent
    data class ShowSheet(val sheet: EditorSheet?) : EditorIntent
}

internal data class EditorUiState(
    val loading: Boolean = true,
    val session: EditorSession = EditorSession.empty(),
    val waveformBySource: Map<String, List<Int>> = emptyMap(),
    val message: EditorMessage? = null,
    val activeSheet: EditorSheet? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val importInFlight: Boolean = false,
)

/**
 * Owns one transient editor session. Sources remain URI-backed for this phase; no source is
 * copied until draft storage is introduced in Phase 3.
 */
internal class EditorViewModel(
    application: Application,
    private val launchSource: EditorLaunchSource,
    private val repository: ConversionHistoryRepository = ConversionHistoryRepository(
        AppDatabase.getDatabase(application).conversionHistoryDao()
    ),
    private val sourceAnalyzer: suspend (Uri) -> AudioSourceInfo = { uri ->
        val (name, durationMs) = VoiceNoteConverter.getMediaInfo(application, uri)
        AudioSourceInfo(name, durationMs)
    },
    private val waveformLoader: suspend (Uri) -> List<Int> = { uri ->
        VoiceNoteConverter.extractWaveform(application, uri)
    },
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val ready = CompletableDeferred<Unit>()
    private val mutationMutex = Mutex()
    private val importsInFlight = AtomicInteger(0)
    private var commandHistory: CommandHistory? = null
    private var launchJob: Job

    init {
        launchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                resolveLaunch()
            } finally {
                ready.complete(Unit)
            }
        }
    }

    /** Allows tests and the editor entry point to wait for source metadata resolution. */
    internal suspend fun awaitReady() = ready.await()

    fun dispatch(intent: EditorIntent) {
        when (intent) {
            is EditorIntent.Seek -> seek(intent.positionMs)
            is EditorIntent.SelectClip -> runSerializedMutation {
                commandHistory?.let { history ->
                    when (val result = history.updateSelection(intent.clipId)) {
                        is TimelineResult.Accepted -> publishSession(result.value)
                        is TimelineResult.Rejected -> showMessage(result.reason.toEditorMessage())
                    }
                }
            }
            is EditorIntent.Split -> runSerializedMutation {
                selectedClip()?.let { execute(SplitClipCommand(it, intent.splitTimelineMs)) }
            }
            is EditorIntent.Trim -> runSerializedMutation {
                selectedClip()?.let { execute(TrimClipCommand(it, intent.sourceStartMs, intent.sourceEndMs)) }
            }
            is EditorIntent.Move -> runSerializedMutation {
                selectedClip()?.let { execute(MoveClipCommand(it, intent.timelineStartMs)) }
            }
            is EditorIntent.Delete -> runSerializedMutation {
                selectedLocation()?.let { (trackId, clipId) ->
                    execute(DeleteClipCommand(trackId, clipId, intent.ripple))
                }
            }
            is EditorIntent.SetFade -> runSerializedMutation {
                selectedClip()?.let { execute(SetClipFadeCommand(it, intent.fadeInMs, intent.fadeOutMs)) }
            }
            is EditorIntent.SetPitch -> runSerializedMutation {
                selectedClip()?.let { execute(SetClipPitchCommand(it, intent.pitchSemitones)) }
            }
            is EditorIntent.SetSpeed -> runSerializedMutation {
                selectedClip()?.let { execute(SetClipSpeedCommand(it, intent.speed)) }
            }
            is EditorIntent.SetTrackVolume -> runSerializedMutation {
                execute(SetTrackVolumeCommand(intent.trackId, intent.volume))
            }
            is EditorIntent.SetTrackMuted -> runSerializedMutation {
                execute(SetTrackMutedCommand(intent.trackId, intent.muted))
            }
            is EditorIntent.RemoveTrack -> runSerializedMutation {
                execute(RemoveTrackCommand(intent.trackId))
            }
            EditorIntent.Undo -> runSerializedMutation {
                commandHistory?.let { history ->
                    history.undo()
                    publishSession(history.session)
                }
            }
            EditorIntent.Redo -> runSerializedMutation {
                commandHistory?.let { history ->
                    history.redo()
                    publishSession(history.session)
                }
            }
            EditorIntent.ClearMessage -> _uiState.update { it.copy(message = null) }
            is EditorIntent.ShowSheet -> _uiState.update { it.copy(activeSheet = intent.sheet) }
        }
    }

    /** Imports a source as a new track at the current playhead after metadata validation. */
    fun importTrack(uri: Uri) {
        importsInFlight.incrementAndGet()
        _uiState.update { it.copy(importInFlight = true) }
        viewModelScope.launch(Dispatchers.IO) {
            var waveformUri: Uri? = null
            try {
                mutationMutex.withLock {
                    retainReadPermission(uri)
                    val metadata = sourceAnalyzer(uri)
                    val state = _uiState.value
                    val startMs = state.session.playheadMs.coerceIn(0L, MAX_TIMELINE_MS)
                    val durationMs = metadata.durationMs
                    if (durationMs <= 0L || startMs > MAX_TIMELINE_MS - durationMs) {
                        showMessage(EditorMessage.TIMELINE_LIMIT)
                        return@withLock
                    }
                    if (state.session.tracks.size >= com.aistudio.voicenote.cvtr.editor.model.MAX_TRACKS) {
                        showMessage(EditorMessage.TRACK_LIMIT)
                        return@withLock
                    }
                    val clipId = "clip-${UUID.randomUUID()}"
                    val trackId = nextTrackId(state.session.tracks)
                    val track = EditorTrack(
                        id = trackId,
                        name = metadata.displayName.ifBlank { trackId.replace("track-", "Track ") },
                        clips = listOf(
                            AudioClip(
                                id = clipId,
                                source = AudioSourceRef(uri.toString(), durationMs),
                                sourceStartMs = 0L,
                                sourceEndMs = durationMs,
                                timelineStartMs = startMs,
                            )
                        )
                    )
                    val result = commandHistory?.execute(AddTrackCommand(track))
                        ?: TimelineResult.Rejected(TimelineError.MISSING_ID)
                    if (result is TimelineResult.Accepted) {
                        commandHistory?.updateSelection(clipId)
                        publishSession(commandHistory?.session ?: result.value)
                        waveformUri = uri
                    } else {
                        showMessage((result as TimelineResult.Rejected).reason.toEditorMessage())
                    }
                }
                waveformUri?.let { loadWaveform(it) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                showMessage(EditorMessage.IMPORT_FAILED)
            } finally {
                if (importsInFlight.decrementAndGet() == 0) {
                    _uiState.update { it.copy(importInFlight = false) }
                }
            }
        }
    }

    private suspend fun resolveLaunch() {
        try {
            when (val source = launchSource) {
                is EditorLaunchSource.Converted -> resolveConverted(source)
                is EditorLaunchSource.History -> resolveHistory(source.historyId)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            showMessage(EditorMessage.IMPORT_FAILED)
            finishLoading(EditorSession.empty())
        }
    }

    private suspend fun resolveConverted(source: EditorLaunchSource.Converted) {
        val preferred = source.originalUri
        val resolved = if (preferred == null) {
            source.resultUri to sourceAnalyzer(source.resultUri)
        } else {
            try {
                preferred to sourceAnalyzer(preferred)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                source.resultUri to sourceAnalyzer(source.resultUri)
            }
        }
        val (uri, metadata) = resolved
        if (metadata.durationMs <= 0L || metadata.durationMs > MAX_TIMELINE_MS) {
            showMessage(EditorMessage.TIMELINE_LIMIT)
            finishLoading(EditorSession.empty())
            return
        }
        val session = sessionFor(
            uri = uri,
            displayName = source.displayName.ifBlank { metadata.displayName },
            durationMs = metadata.durationMs,
        )
        finishLoading(session)
        loadWaveform(uri)
    }

    private suspend fun resolveHistory(historyId: Long) {
        val history = repository.getEditorSourceById(historyId)
        if (history == null) {
            showMessage(EditorMessage.HISTORY_NOT_FOUND)
            finishLoading(EditorSession.empty())
            return
        }
        val uri = history.outputFilePath.toUriForEditor()
        val metadata = try {
            sourceAnalyzer(uri)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            AudioSourceInfo(history.outputFileName, history.durationSeconds * 1_000L)
        }
        if (metadata.durationMs <= 0L || metadata.durationMs > MAX_TIMELINE_MS) {
            showMessage(EditorMessage.TIMELINE_LIMIT)
            finishLoading(EditorSession.empty())
            return
        }
        val session = sessionFor(uri, history.outputFileName.ifBlank { metadata.displayName }, metadata.durationMs)
        finishLoading(session)
        _uiState.update {
            it.copy(waveformBySource = it.waveformBySource + (uri.toString() to WaveformCodec.decode(history.waveform)))
        }
    }

    private fun sessionFor(uri: Uri, displayName: String, durationMs: Long): EditorSession {
        val clipId = "clip-1"
        return EditorSession(
            id = "session-${UUID.randomUUID()}",
            tracks = listOf(
                EditorTrack(
                    id = "track-1",
                    name = displayName.ifBlank { "Track 1" },
                    clips = listOf(
                        AudioClip(
                            id = clipId,
                            source = AudioSourceRef(uri.toString(), durationMs),
                            sourceStartMs = 0L,
                            sourceEndMs = durationMs,
                            timelineStartMs = 0L,
                        )
                    )
                )
            ),
            selectedClipId = clipId,
        )
    }

    private fun finishLoading(session: EditorSession) {
        commandHistory = CommandHistory(session)
        _uiState.update {
            it.copy(
                loading = false,
                session = session,
                canUndo = false,
                canRedo = false,
            )
        }
    }

    private fun seek(positionMs: Long) {
        _uiState.update { state ->
            state.copy(session = state.session.copy(playheadMs = positionMs.coerceIn(0L, MAX_TIMELINE_MS)))
        }
    }

    private fun execute(command: EditorCommand) {
        val history = commandHistory ?: return
        when (val result = history.execute(command)) {
            is TimelineResult.Accepted -> publishSession(result.value)
            is TimelineResult.Rejected -> showMessage(result.reason.toEditorMessage())
        }
    }

    /** Queues UI mutations behind an in-flight import without blocking the main thread. */
    private fun runSerializedMutation(block: () -> Unit) {
        if (mutationMutex.tryLock()) {
            try {
                block()
            } finally {
                mutationMutex.unlock()
            }
        } else {
            viewModelScope.launch {
                mutationMutex.withLock { block() }
            }
        }
    }

    private fun publishSession(session: EditorSession) {
        val history = commandHistory
        _uiState.update {
            it.copy(
                session = session,
                message = null,
                canUndo = history?.canUndo == true,
                canRedo = history?.canRedo == true,
            )
        }
    }

    private fun selectedClip(): String? = _uiState.value.session.selectedClipId

    private fun selectedLocation(): Pair<String, String>? {
        val id = selectedClip() ?: return null
        return _uiState.value.session.tracks.firstNotNullOfOrNull { track ->
            id.takeIf { clipId -> track.clips.any { it.id == clipId } }?.let { track.id to it }
        }
    }

    private fun nextTrackId(tracks: List<EditorTrack>): String {
        val existingIds = tracks.map { it.id }.toSet()
        var suffix = 1
        while ("track-$suffix" in existingIds) suffix++
        return "track-$suffix"
    }

    private suspend fun loadWaveform(uri: Uri) {
        val waveform = try {
            waveformLoader(uri)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            emptyList()
        }
        _uiState.update { it.copy(waveformBySource = it.waveformBySource + (uri.toString() to waveform)) }
    }

    private fun retainReadPermission(uri: Uri) {
        if (uri.scheme != "content") return
        runCatching {
            getApplication<Application>().contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }
    }

    private fun showMessage(message: EditorMessage) {
        _uiState.update { it.copy(message = message) }
    }

    private fun TimelineError.toEditorMessage(): EditorMessage = when (this) {
        TimelineError.TRACK_LIMIT -> EditorMessage.TRACK_LIMIT
        TimelineError.DURATION_LIMIT -> EditorMessage.TIMELINE_LIMIT
        else -> EditorMessage.IMPORT_FAILED
    }

    override fun onCleared() {
        launchJob.cancel()
        super.onCleared()
    }
}

private fun String.toUriForEditor(): Uri =
    Uri.parse(this).takeIf { it.scheme != null } ?: Uri.fromFile(File(this))
