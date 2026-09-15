package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Observer
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequest
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import com.aistudio.voicenote.cvtr.editor.audio.DefaultTimelineRenderer
import com.aistudio.voicenote.cvtr.editor.audio.EditorPlaybackState
import com.aistudio.voicenote.cvtr.editor.audio.EditorPreviewEngine
import com.aistudio.voicenote.cvtr.editor.audio.MediaCodecPcmSourceReaderFactory
import com.aistudio.voicenote.cvtr.editor.audio.EditorRenderManifest
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import com.aistudio.voicenote.cvtr.editor.model.MAX_TIMELINE_MS
import com.aistudio.voicenote.cvtr.editor.model.TimelineError
import com.aistudio.voicenote.cvtr.editor.model.TimelineResult
import com.aistudio.voicenote.cvtr.editor.model.value
import com.aistudio.voicenote.cvtr.editor.work.EditorExportWork
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.filterNotNull

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

internal enum class EditorExportStatus {
    IDLE,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

internal data class EditorExportUiState(
    val sheetOpen: Boolean = false,
    val outputName: String = "",
    val preset: com.aistudio.voicenote.cvtr.editor.model.ExportPreset =
        com.aistudio.voicenote.cvtr.editor.model.ExportPreset.VOICE_NOTE_32,
    val estimatedSizeBytes: Long = 0L,
    val status: EditorExportStatus = EditorExportStatus.IDLE,
    val progress: Float = 0f,
    val resultUri: String? = null,
    val resultHistoryId: Long? = null,
    val error: String? = null,
    val canRetry: Boolean = false,
    val cleanupWarning: String? = null,
    val exportAttemptId: String? = null,
)

internal interface EditorExportScheduler {
    fun enqueue(request: OneTimeWorkRequest): UUID
    /** Enqueue once for a durable attempt key; test seams may use the basic enqueue fallback. */
    fun enqueueUnique(
        uniqueName: String,
        request: OneTimeWorkRequest,
        replaceExisting: Boolean = false,
    ): UUID = enqueue(request)
    fun cancel(id: UUID)
    fun observe(id: UUID): Flow<WorkInfo?>
}

private class WorkManagerEditorExportScheduler(
    private val workManager: WorkManager,
) : EditorExportScheduler {
    override fun enqueue(request: OneTimeWorkRequest): UUID {
        workManager.enqueue(request)
        return request.id
    }
    override fun enqueueUnique(
        uniqueName: String,
        request: OneTimeWorkRequest,
        replaceExisting: Boolean,
    ): UUID {
        workManager.beginUniqueWork(
            uniqueName,
            if (replaceExisting) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,
            request,
        ).enqueue()
        return request.id
    }
    override fun cancel(id: UUID) {
        workManager.cancelWorkById(id)
    }
    override fun observe(id: UUID): Flow<WorkInfo?> = callbackFlow {
        val liveData = workManager.getWorkInfoByIdLiveData(id)
        val observer = Observer<WorkInfo?> { trySend(it) }
        liveData.observeForever(observer)
        awaitClose { liveData.removeObserver(observer) }
    }
}

/** Intents shared by the editor shell and its timeline controls. */
internal sealed interface EditorIntent {
    data object Play : EditorIntent
    data object Pause : EditorIntent
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
    data class ShowExportSheet(val open: Boolean) : EditorIntent
    data class SetExportName(val name: String) : EditorIntent
    data class SetExportPreset(val preset: com.aistudio.voicenote.cvtr.editor.model.ExportPreset) : EditorIntent
    data object StartExport : EditorIntent
    data object CancelExport : EditorIntent
    data object RetryExport : EditorIntent
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
    val playback: EditorPlaybackState = EditorPlaybackState(),
    val export: EditorExportUiState = EditorExportUiState(),
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
    private val previewEngine: EditorPreviewEngine = EditorPreviewEngine(
        renderer = DefaultTimelineRenderer(MediaCodecPcmSourceReaderFactory(application)),
    ),
    private val exportScheduler: EditorExportScheduler = WorkManagerEditorExportScheduler(
        WorkManager.getInstance(application)
    ),
) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private val ready = CompletableDeferred<Unit>()
    private val mutationMutex = Mutex()
    private val importsInFlight = AtomicInteger(0)
    private var commandHistory: CommandHistory? = null
    private var launchJob: Job
    private var exportObservationJob: Job? = null
    private var exportWorkId: UUID? = null
    private var exportManifestPath: String? = null
    private var exportWorkerStarted = false
    private var editorSourceHistoryId: Long? = null
    private val exportGate = Any()
    private var exportEnqueueInFlight = false
    private var exportCancelRequested = false

    init {
        viewModelScope.launch {
            previewEngine.state.collect { playback ->
                _uiState.update { state ->
                    state.copy(
                        playback = playback,
                        session = state.session.copy(playheadMs = playback.positionMs),
                    )
                }
            }
        }
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
            EditorIntent.Play -> previewEngine.play()
            EditorIntent.Pause -> previewEngine.pause()
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
            is EditorIntent.ShowExportSheet -> _uiState.update {
                it.copy(export = it.export.copy(sheetOpen = intent.open))
            }
            is EditorIntent.SetExportName -> _uiState.update {
                it.copy(export = it.export.copy(outputName = intent.name))
            }
            is EditorIntent.SetExportPreset -> _uiState.update {
                it.copy(export = it.export.copy(
                    preset = intent.preset,
                    estimatedSizeBytes = estimateExportSize(it.session, intent.preset),
                ))
            }
            EditorIntent.StartExport -> startExport()
            EditorIntent.CancelExport -> cancelExport()
            EditorIntent.RetryExport -> retryExport()
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
        editorSourceHistoryId = null
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
        editorSourceHistoryId = historyId
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
        previewEngine.load(session)
        _uiState.update {
            it.copy(
                loading = false,
                session = session,
                canUndo = false,
                canRedo = false,
                export = it.export.copy(
                    outputName = defaultExportName(session),
                    estimatedSizeBytes = estimateExportSize(session, it.export.preset),
                ),
            )
        }
    }

    private fun startExport(
        requestedAttemptId: String? = null,
        replaceExisting: Boolean = false,
    ) {
        val current: EditorUiState
        val exportAttemptId: String
        synchronized(exportGate) {
            current = _uiState.value
            if (exportEnqueueInFlight ||
                current.export.status == EditorExportStatus.QUEUED ||
                current.export.status == EditorExportStatus.RUNNING
            ) return
            if (!hasRenderableAudio(current.session)) {
                _uiState.update {
                    it.copy(export = it.export.copy(
                        status = EditorExportStatus.FAILED,
                        error = "Editor timeline contains no rendered audio",
                        canRetry = false,
                        progress = 0f,
                    ))
                }
                return
            }
            exportEnqueueInFlight = true
            exportCancelRequested = false
            exportWorkerStarted = false
            exportAttemptId = requestedAttemptId ?: UUID.randomUUID().toString()
        }
        viewModelScope.launch(Dispatchers.IO) {
            var manifest: File? = null
            try {
                manifest = EditorRenderManifest.writePrivate(
                    context = getApplication(),
                    session = current.session,
                    sourceHistoryId = editorSourceHistoryId,
                    preset = current.export.preset,
                    exportAttemptId = exportAttemptId,
                )
                val manifestFile = manifest ?: error("Editor manifest is unavailable")
                synchronized(exportGate) {
                    exportManifestPath = manifestFile.absolutePath
                    if (exportCancelRequested) throw CancellationException("Editor export cancelled")
                }
                val request = EditorExportWork.request(
                    manifestPath = manifestFile.absolutePath,
                    outputName = current.export.outputName.ifBlank { defaultExportName(current.session) },
                    preset = current.export.preset,
                    exportAttemptId = exportAttemptId,
                )
                val id = exportScheduler.enqueueUnique(
                    uniqueName = EditorExportWork.uniqueWorkName(exportAttemptId),
                    request = request,
                    replaceExisting = replaceExisting,
                )
                val cancelledAfterEnqueue = synchronized(exportGate) {
                    exportWorkId = id
                    exportCancelRequested
                }
                if (cancelledAfterEnqueue) {
                    exportScheduler.cancel(id)
                    manifestFile.delete()
                    synchronized(exportGate) {
                        exportManifestPath = null
                        exportWorkId = null
                    }
                    _uiState.update { it.copy(export = it.export.copy(
                        status = EditorExportStatus.CANCELLED,
                        canRetry = true,
                    )) }
                    return@launch
                }
                _uiState.update {
                    it.copy(export = it.export.copy(
                        status = EditorExportStatus.QUEUED,
                        error = null,
                        canRetry = false,
                        progress = 0f,
                        exportAttemptId = exportAttemptId,
                    ))
                }
                observeExport(id)
            } catch (error: CancellationException) {
                manifest?.delete()
                throw error
            } catch (error: Throwable) {
                manifest?.delete()
                error.rethrowIfFatal()
                _uiState.update {
                    it.copy(export = it.export.copy(status = EditorExportStatus.FAILED, error = error.message ?: "Export failed", canRetry = true))
                }
            } finally {
                synchronized(exportGate) { exportEnqueueInFlight = false }
            }
        }
    }

    private fun observeExport(id: UUID) {
        exportObservationJob?.cancel()
        exportObservationJob = viewModelScope.launch {
            exportScheduler.observe(id).filterNotNull().collect { info ->
                val progress = info.progress.getFloat(EditorExportWork.PROGRESS, _uiState.value.export.progress)
                when (info.state) {
                    WorkInfo.State.ENQUEUED -> _uiState.update { it.copy(export = it.export.copy(status = EditorExportStatus.QUEUED, progress = progress)) }
                    WorkInfo.State.RUNNING -> {
                        synchronized(exportGate) { exportWorkerStarted = true }
                        _uiState.update { it.copy(export = it.export.copy(status = EditorExportStatus.RUNNING, progress = progress)) }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _uiState.update {
                            it.copy(export = it.export.copy(
                                status = EditorExportStatus.SUCCEEDED,
                                progress = 1f,
                                resultUri = info.outputData.getString(EditorExportWork.RESULT_URI),
                                resultHistoryId = info.outputData.getLong(EditorExportWork.RESULT_HISTORY_ID, 0L).takeIf { id -> id > 0L },
                                error = null,
                                canRetry = false,
                                cleanupWarning = info.outputData.getString(EditorExportWork.CLEANUP_WARNING),
                            ))
                        }
                        synchronized(exportGate) {
                            exportManifestPath = null
                            exportWorkId = null
                        }
                        exportObservationJob?.cancel()
                    }
                    WorkInfo.State.FAILED -> {
                        _uiState.update {
                            it.copy(export = it.export.copy(
                                status = EditorExportStatus.FAILED,
                                error = info.outputData.getString(EditorExportWork.ERROR_MESSAGE) ?: "Export failed",
                                canRetry = info.outputData.getBoolean(EditorExportWork.CAN_RETRY, true),
                                cleanupWarning = info.outputData.getString(EditorExportWork.CLEANUP_WARNING),
                            ))
                        }
                        synchronized(exportGate) {
                            exportManifestPath = null
                            exportWorkId = null
                        }
                        exportObservationJob?.cancel()
                    }
                    WorkInfo.State.CANCELLED -> {
                        _uiState.update { it.copy(export = it.export.copy(status = EditorExportStatus.CANCELLED)) }
                        synchronized(exportGate) {
                            exportManifestPath = null
                            exportWorkId = null
                        }
                        exportObservationJob?.cancel()
                    }
                    WorkInfo.State.BLOCKED -> Unit
                }
            }
        }
    }

    private fun cancelExport() {
        val workId: UUID?
        val manifestPath: String?
        synchronized(exportGate) {
            if (!exportEnqueueInFlight && exportWorkId == null) return
            exportCancelRequested = true
            workId = exportWorkId
            manifestPath = exportManifestPath.takeIf { workId == null || !exportWorkerStarted }
            if (manifestPath != null) exportManifestPath = null
        }
        workId?.let(exportScheduler::cancel)
        manifestPath?.let { File(it).delete() }
        _uiState.update { it.copy(export = it.export.copy(
            status = EditorExportStatus.CANCELLED,
            canRetry = true,
        )) }
    }

    private fun retryExport() {
        if (_uiState.value.export.canRetry || _uiState.value.export.status == EditorExportStatus.CANCELLED) {
            val previousAttemptId = _uiState.value.export.exportAttemptId
            _uiState.update { it.copy(export = it.export.copy(status = EditorExportStatus.IDLE, error = null, progress = 0f)) }
            // Reuse a terminal attempt key with REPLACE so an uncertain process-retry that
            // already committed history converges instead of creating a second export.
            startExport(previousAttemptId, replaceExisting = previousAttemptId != null)
        }
    }

    private fun defaultExportName(session: EditorSession): String {
        val base = session.tracks.firstOrNull()?.name?.ifBlank { "voice_note" } ?: "voice_note"
        return com.aistudio.voicenote.cvtr.audio.VoiceNoteStorage.sanitizeOutputFileName("${base}_edited.ogg")
    }

    private fun estimateExportSize(
        session: EditorSession,
        preset: com.aistudio.voicenote.cvtr.editor.model.ExportPreset,
    ): Long {
        val frames = session.tracks.asSequence().flatMap { it.clips.asSequence() }
            .map { it.timelineEndMs.coerceAtLeast(0L) }
            .maxOrNull()?.times(48L) ?: 0L
        val bitrate = if (preset == com.aistudio.voicenote.cvtr.editor.model.ExportPreset.HIGH_QUALITY_64) 64_000L else 32_000L
        return frames * bitrate / 48_000L / 8L + 512L
    }

    private fun hasRenderableAudio(session: EditorSession): Boolean {
        if (session.tracks.none { it.clips.isNotEmpty() }) return false
        val durationMs = session.tracks.asSequence()
            .flatMap { it.clips.asSequence() }
            .map { it.timelineEndMs.coerceAtLeast(0L) }
            .maxOrNull() ?: return false
        return durationMs * 48L / 1_000L > 0L
    }

    private fun Throwable.rethrowIfFatal() {
        if (this is VirtualMachineError || this is ThreadDeath || this is LinkageError) {
            throw this
        }
    }

    private fun seek(positionMs: Long) {
        val bounded = positionMs.coerceIn(0L, MAX_TIMELINE_MS)
        previewEngine.seekTo(bounded)
        _uiState.update { state ->
            state.copy(session = state.session.copy(playheadMs = bounded))
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
        if (!sameAudioContent(_uiState.value.session, session)) {
            previewEngine.updateSession(session)
        }
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

    private fun sameAudioContent(left: EditorSession, right: EditorSession): Boolean {
        if (left.tracks.size != right.tracks.size) return false
        return left.tracks.zip(right.tracks).all { (leftTrack, rightTrack) ->
            leftTrack.id == rightTrack.id &&
                leftTrack.volume == rightTrack.volume &&
                leftTrack.muted == rightTrack.muted &&
                leftTrack.clips.size == rightTrack.clips.size &&
                leftTrack.clips.zip(rightTrack.clips).all { (leftClip, rightClip) ->
                    leftClip.id == rightClip.id &&
                        leftClip.source == rightClip.source &&
                        leftClip.sourceStartMs == rightClip.sourceStartMs &&
                        leftClip.sourceEndMs == rightClip.sourceEndMs &&
                        leftClip.timelineStartMs == rightClip.timelineStartMs &&
                        leftClip.effects == rightClip.effects
                }
        }
    }

    private fun TimelineError.toEditorMessage(): EditorMessage = when (this) {
        TimelineError.TRACK_LIMIT -> EditorMessage.TRACK_LIMIT
        TimelineError.DURATION_LIMIT -> EditorMessage.TIMELINE_LIMIT
        else -> EditorMessage.IMPORT_FAILED
    }

    override fun onCleared() {
        launchJob.cancel()
        previewEngine.release()
        super.onCleared()
    }
}

private fun String.toUriForEditor(): Uri =
    Uri.parse(this).takeIf { it.scheme != null } ?: Uri.fromFile(File(this))
