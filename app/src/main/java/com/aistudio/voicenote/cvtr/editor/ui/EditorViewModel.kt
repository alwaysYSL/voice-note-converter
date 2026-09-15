package com.aistudio.voicenote.cvtr.editor.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequest
import androidx.work.ExistingWorkPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.lifecycle.Observer
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
import com.aistudio.voicenote.cvtr.editor.audio.CleanupStrength
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioKey
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioCache
import com.aistudio.voicenote.cvtr.editor.cache.readValidatedCachedWav
import com.aistudio.voicenote.cvtr.editor.work.CleanupEffectWork
import com.aistudio.voicenote.cvtr.editor.work.StableSourceFingerprint
import com.aistudio.voicenote.cvtr.editor.work.estimateRequiredBytes
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
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.withContext

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
    PROCESSED_AUDIO_UNAVAILABLE,
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

internal enum class EditorCleanupStatus {
    IDLE,
    QUEUED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
}

internal data class EditorCleanupUiState(
    val status: EditorCleanupStatus = EditorCleanupStatus.IDLE,
    val progress: Float = 0f,
    val error: String? = null,
    val canRetry: Boolean = false,
    val targetClipId: String? = null,
    val wholeTrack: Boolean = false,
    val normalize: Boolean = false,
    val preset: CleanupStrength = CleanupStrength.OFF,
)

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
    /** Immutable request values associated with [exportAttemptId] for safe retry decisions. */
    val attemptOutputName: String? = null,
    val attemptPreset: com.aistudio.voicenote.cvtr.editor.model.ExportPreset? = null,
)

internal interface EditorExportScheduler {
    fun enqueue(request: OneTimeWorkRequest): UUID
    /** Enqueue once for a durable attempt key; test seams may use the basic enqueue fallback. */
    fun enqueueUnique(
        uniqueName: String,
        request: OneTimeWorkRequest,
        replaceExisting: Boolean,
    ): UUID = enqueue(request)
    fun cancel(id: UUID)
    fun observe(id: UUID): Flow<WorkInfo?>
}

/** Small WorkManager seam used by cleanup tests and by the all-or-nothing track workflow. */
internal interface EditorCleanupScheduler {
    fun enqueueUnique(uniqueName: String, request: OneTimeWorkRequest, replaceExisting: Boolean): UUID
    fun cancel(id: UUID)
    fun observe(id: UUID): Flow<WorkInfo?>
}

private class WorkManagerEditorExportScheduler(
    private val workManager: WorkManager,
) : EditorExportScheduler, EditorCleanupScheduler {
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
        val observer = Observer<WorkInfo?> { info -> trySend(info) }
        // LiveData requires both observer registration and removal on the main thread, while
        // cleanup/export collectors intentionally run on Dispatchers.IO.
        withContext(Dispatchers.Main.immediate) { liveData.observeForever(observer) }
        val mainHandler = Handler(Looper.getMainLooper())
        awaitClose {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                liveData.removeObserver(observer)
            } else {
                mainHandler.post { liveData.removeObserver(observer) }
            }
        }
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
    data class StartCleanup(
        val targetClipId: String?,
        val normalize: Boolean,
        val preset: CleanupStrength,
        val wholeTrack: Boolean = false,
    ) : EditorIntent
    data object CancelCleanup : EditorIntent
    data object RetryCleanup : EditorIntent
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
    val cleanup: EditorCleanupUiState = EditorCleanupUiState(),
)

private data class CleanupTarget(
    val clipId: String,
    val sourceUri: String,
    val sourceStartMs: Long,
    val sourceEndMs: Long,
    val fingerprint: String = "",
    var workId: UUID? = null,
) {
    constructor(clip: AudioClip) : this(
        clipId = clip.id,
        sourceUri = clip.source.uri,
        sourceStartMs = clip.sourceStartMs,
        sourceEndMs = clip.sourceEndMs,
    )
}

private data class CleanupBatch(
    val id: String,
    val targetClipId: String,
    val wholeTrack: Boolean,
    val normalize: Boolean,
    val preset: CleanupStrength,
    val clips: List<CleanupTarget>,
    val completed: MutableMap<String, String> = LinkedHashMap(),
)

private fun EditorSession.findClipForCleanup(clipId: String): AudioClip? =
    tracks.asSequence().flatMap { it.clips.asSequence() }.firstOrNull { it.id == clipId }

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
        renderer = DefaultTimelineRenderer(
            MediaCodecPcmSourceReaderFactory(application),
            cacheDir = File(application.cacheDir, "processed_audio"),
        ),
    ),
    private val exportScheduler: EditorExportScheduler = WorkManagerEditorExportScheduler(
        WorkManager.getInstance(application)
    ),
    private val cleanupScheduler: EditorCleanupScheduler = WorkManagerEditorExportScheduler(
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
    private val exportObservationJobs = mutableMapOf<UUID, Job>()
    private var exportWorkId: UUID? = null
    private var exportManifestPath: String? = null
    private val exportWorkerStartedByWorkId = mutableSetOf<UUID>()
    private var editorSourceHistoryId: Long? = null
    private val exportGate = Any()
    private var exportEnqueueInFlight = false
    private var exportCancelRequested = false
    /** Attempt leases are released only by enqueue failure or terminal WorkManager observation. */
    private val exportLeaseAttempts = mutableSetOf<String>()
    private val exportLeaseByWorkId = mutableMapOf<UUID, String>()
    private var cleanupObservationJobs: List<Job> = emptyList()
    private var cleanupPreparationJob: Job? = null
    private var cleanupBatch: CleanupBatch? = null
    private val cleanupOwnershipLock = Any()
    private val processedCache = ProcessedAudioCache(File(application.cacheDir, "processed_audio"))

    init {
        viewModelScope.launch {
            previewEngine.state.collect { playback ->
                _uiState.update { state ->
                    state.copy(
                        playback = playback,
                        session = state.session.copy(playheadMs = playback.positionMs),
                        message = playback.error?.takeIf { it.startsWith("Processed audio cache") }
                            ?.let { EditorMessage.PROCESSED_AUDIO_UNAVAILABLE }
                            ?: state.message,
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
            is EditorIntent.StartCleanup -> startCleanup(
                intent.targetClipId,
                intent.normalize,
                intent.preset,
                intent.wholeTrack,
            )
            EditorIntent.CancelCleanup -> cancelCleanup()
            EditorIntent.RetryCleanup -> retryCleanup()
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
        syncCacheReferences()
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
            exportAttemptId = requestedAttemptId ?: UUID.randomUUID().toString()
            acquireExportLease(current.session, exportAttemptId)
        }
        viewModelScope.launch(Dispatchers.IO) {
            var manifest: File? = null
            try {
                val requestedOutputName = current.export.outputName.ifBlank { defaultExportName(current.session) }
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
                    outputName = requestedOutputName,
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
                    exportLeaseByWorkId[id] = exportAttemptId
                    exportCancelRequested
                }
                if (cancelledAfterEnqueue) {
                    exportScheduler.cancel(id)
                    manifestFile.delete()
                    synchronized(exportGate) {
                        exportManifestPath = null
                        exportWorkId = null
                    }
                    // Keep observing the cancelled WorkManager attempt so its terminal state
                    // releases the single attempt lease after WorkManager confirms cancellation.
                    observeExport(id)
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
                        attemptOutputName = requestedOutputName,
                        attemptPreset = current.export.preset,
                    ))
                }
                observeExport(id)
            } catch (error: CancellationException) {
                manifest?.delete()
                releaseExportLease(exportAttemptId)
                throw error
            } catch (error: Throwable) {
                manifest?.delete()
                releaseExportLease(exportAttemptId)
                error.rethrowIfFatal()
                _uiState.update {
                    it.copy(export = it.export.copy(status = EditorExportStatus.FAILED, error = error.message ?: "Export failed", canRetry = true))
                }
            } finally {
                synchronized(exportGate) {
                    exportEnqueueInFlight = false
                }
            }
        }
    }

    private fun startCleanup(
        targetClipId: String?,
        normalize: Boolean,
        preset: CleanupStrength,
        wholeTrack: Boolean,
    ) {
        val session = _uiState.value.session
        val clipId = targetClipId ?: session.selectedClipId ?: return
        val track = session.tracks.firstOrNull { track -> track.clips.any { it.id == clipId } } ?: return
        val clipsToProcess = if (wholeTrack) track.clips else {
            listOf(track.clips.first { it.id == clipId })
        }
        if (clipsToProcess.isEmpty()) return

        cancelCleanup(publishCancelled = false)
        val batchId = UUID.randomUUID().toString()
        val batch = CleanupBatch(
            id = batchId,
            targetClipId = clipId,
            wholeTrack = wholeTrack,
            normalize = normalize,
            preset = preset,
            clips = clipsToProcess.map { clip -> CleanupTarget(clip) },
        )
        synchronized(cleanupOwnershipLock) { cleanupBatch = batch }
        _uiState.update {
            it.copy(cleanup = EditorCleanupUiState(
                status = EditorCleanupStatus.QUEUED,
                targetClipId = clipId,
                wholeTrack = wholeTrack,
                normalize = normalize,
                preset = preset,
            ))
        }
        val preparationJob = viewModelScope.launch(Dispatchers.IO) {
            var ownedBatch = batch
            try {
                val requiredBytes = batch.clips.sumOf { target ->
                    val frames = (target.sourceEndMs - target.sourceStartMs).coerceAtLeast(0L) * 48L / 1_000L
                    estimateRequiredBytes(frames, normalize)
                }
                if (processedCacheDir().usableSpace < requiredBytes) {
                    completeCleanupFailure(batch, "Insufficient storage for cleanup render", cancelled = false)
                    return@launch
                }
                if (!isCurrentCleanup(batch)) return@launch
                val prepared = batch.clips.map { target ->
                    target.copy(
                        fingerprint = StableSourceFingerprint.compute(
                            getApplication(), target.sourceUri, target.sourceUri,
                        )
                    )
                }
                if (!isCurrentCleanup(batch)) return@launch
                val preparedBatch = batch.copy(clips = prepared)
                ownedBatch = preparedBatch
                synchronized(cleanupOwnershipLock) {
                    if (cleanupBatch?.id != batch.id) return@launch
                    cleanupBatch = preparedBatch
                }
                preparedBatch.clips.forEach { target ->
                    synchronized(cleanupOwnershipLock) {
                        if (cleanupBatch?.id != batch.id) return@launch
                        val request = CleanupEffectWork.request(
                            sourceUri = target.sourceUri,
                            sourceFingerprint = target.fingerprint,
                            sourceStartMs = target.sourceStartMs,
                            sourceEndMs = target.sourceEndMs,
                            cleanupStrength = preset,
                            normalized = normalize,
                        )
                        val uniqueName = CleanupEffectWork.uniqueWorkName(
                            sourceFingerprint = target.fingerprint,
                            start = target.sourceStartMs,
                            end = target.sourceEndMs,
                            cleanupStrength = preset,
                            normalized = normalize,
                            attemptIdentity = "${batch.id}-${target.clipId}",
                        )
                        // Enqueue and record the id under one lock. Cancellation cannot detach
                        // between WorkManager enqueue and ownership bookkeeping.
                        target.workId = cleanupScheduler.enqueueUnique(uniqueName, request, replaceExisting = true)
                    }
                }
                if (!isCurrentCleanup(batch)) return@launch
                _uiState.update { it.copy(cleanup = it.cleanup.copy(status = EditorCleanupStatus.RUNNING)) }
                val observations = preparedBatch.clips.map { target ->
                    viewModelScope.launch(Dispatchers.IO) { observeCleanup(preparedBatch, target) }
                }
                synchronized(cleanupOwnershipLock) {
                    if (cleanupBatch?.id == batch.id) {
                        cleanupObservationJobs = observations
                    } else {
                        observations.forEach { it.cancel() }
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                completeCleanupFailure(prepared = ownedBatch, message = error.message ?: "Cleanup could not start", cancelled = false)
            }
        }
        synchronized(cleanupOwnershipLock) {
            if (cleanupBatch?.id == batch.id) {
                cleanupPreparationJob = preparationJob
            } else {
                preparationJob.cancel()
            }
        }
    }

    private fun isCurrentCleanup(batch: CleanupBatch): Boolean = synchronized(cleanupOwnershipLock) {
        cleanupBatch?.id == batch.id
    }

    private suspend fun observeCleanup(batch: CleanupBatch, target: CleanupTarget) {
        cleanupScheduler.observe(target.workId ?: return).filterNotNull().collect { info ->
            if (!isCurrentCleanup(batch)) return@collect
            val progress = info.progress.getFloat(CleanupEffectWork.PROGRESS, 0f)
            val done = synchronized(batch.completed) { batch.completed.size }
            val total = batch.clips.size.coerceAtLeast(1)
            _uiState.update { it.copy(cleanup = it.cleanup.copy(progress = ((done + progress) / total).coerceIn(0f, 1f))) }
            when (info.state) {
                WorkInfo.State.SUCCEEDED -> {
                    val key = info.outputData.getString(CleanupEffectWork.RESULT_CACHE_KEY_FILENAME)
                    val resultFingerprint = info.outputData.getString(CleanupEffectWork.RESULT_CACHE_KEY_FINGERPRINT)
                    val resultStart = info.outputData.getLong(CleanupEffectWork.RESULT_SOURCE_START_MS, Long.MIN_VALUE)
                    val resultEnd = info.outputData.getLong(CleanupEffectWork.RESULT_SOURCE_END_MS, Long.MIN_VALUE)
                    val resultStrength = info.outputData.getString(CleanupEffectWork.RESULT_CLEANUP_STRENGTH)
                    val resultNormalized = info.outputData.getBoolean(CleanupEffectWork.RESULT_NORMALIZED, false)
                    val hasNormalized = info.outputData.keyValueMap.containsKey(CleanupEffectWork.RESULT_NORMALIZED)
                    val resultAlgorithm = info.outputData.getString(CleanupEffectWork.RESULT_ALGORITHM_VERSION)
                    val expectedKey = ProcessedAudioKey(
                        sourceFingerprint = target.fingerprint,
                        sourceStartMs = target.sourceStartMs,
                        sourceEndMs = target.sourceEndMs,
                        cleanup = batch.preset,
                        normalized = batch.normalize,
                        algorithmVersion = ProcessedAudioCache.CACHE_ALGORITHM_VERSION,
                    ).toFilename()
                    if (key.isNullOrBlank() || key != expectedKey || resultFingerprint != target.fingerprint ||
                        resultStart != target.sourceStartMs || resultEnd != target.sourceEndMs ||
                        resultStrength != batch.preset.name || !hasNormalized || resultNormalized != batch.normalize ||
                        resultAlgorithm != ProcessedAudioCache.CACHE_ALGORITHM_VERSION
                    ) {
                        completeCleanupFailure(batch, "Cleanup result is missing", cancelled = false)
                    } else {
                        val complete = synchronized(batch.completed) {
                            batch.completed[target.clipId] = key
                            batch.completed.size == batch.clips.size
                        }
                        if (complete) applyCompletedCleanup(batch)
                    }
                }
                WorkInfo.State.FAILED -> completeCleanupFailure(
                    prepared = batch,
                    message = info.outputData.getString(CleanupEffectWork.ERROR_MESSAGE) ?: "Cleanup failed",
                    cancelled = false,
                )
                WorkInfo.State.CANCELLED -> completeCleanupFailure(batch, "Cleanup cancelled", cancelled = true)
                else -> Unit
            }
        }
    }

    private suspend fun applyCompletedCleanup(batch: CleanupBatch) {
        mutationMutex.withLock {
            if (!isCurrentCleanup(batch)) return@withLock
            val current = _uiState.value.session
            val completed = synchronized(batch.completed) { batch.completed.toMap() }
            val valid = batch.clips.all { target ->
                val clip = current.findClipForCleanup(target.clipId)
                clip != null && clip.source.uri == target.sourceUri &&
                    clip.sourceStartMs == target.sourceStartMs && clip.sourceEndMs == target.sourceEndMs &&
                    StableSourceFingerprint.compute(getApplication(), target.sourceUri, target.fingerprint) == target.fingerprint &&
                    completed[target.clipId]?.let { key ->
                        val expected = (target.sourceEndMs - target.sourceStartMs).coerceAtLeast(0L) * 48L / 1_000L
                        runCatching {
                            readValidatedCachedWav(File(processedCacheDir(), "$key.pcm"), expected)
                        }.isSuccess
                    } == true
            }
            if (!valid) {
                completeCleanupFailure(batch, "Cleanup result is no longer current", cancelled = false)
                return@withLock
            }
            val result = commandHistory?.execute(
                com.aistudio.voicenote.cvtr.editor.command.ApplyProcessedSourcesCommand(completed)
            )
            if (result is TimelineResult.Accepted) {
                publishSession(result.value)
                syncCacheReferences()
                synchronized(cleanupOwnershipLock) {
                    if (cleanupBatch?.id == batch.id) {
                        cleanupBatch = null
                        cleanupPreparationJob = null
                        cleanupObservationJobs.forEach { it.cancel() }
                        cleanupObservationJobs = emptyList()
                    }
                }
                _uiState.update { it.copy(cleanup = it.cleanup.copy(status = EditorCleanupStatus.SUCCEEDED, progress = 1f, error = null, canRetry = false)) }
            } else {
                completeCleanupFailure(batch, "Cleanup result could not be applied", cancelled = false)
            }
        }
    }

    private fun completeCleanupFailure(prepared: CleanupBatch?, message: String, cancelled: Boolean) {
        if (prepared == null) return
        val workIds = synchronized(cleanupOwnershipLock) {
            if (cleanupBatch?.id != prepared.id) return
            cleanupBatch = null
            cleanupPreparationJob = null
            cleanupObservationJobs.forEach { it.cancel() }
            cleanupObservationJobs = emptyList()
            prepared.clips.mapNotNull { it.workId }
        }
        workIds.forEach(cleanupScheduler::cancel)
        // Completed content-addressed outputs remain unreferenced for the cache LRU. Do not
        // delete them here: a newer batch may already be using the same key.
        _uiState.update {
            it.copy(cleanup = it.cleanup.copy(
                status = if (cancelled) EditorCleanupStatus.CANCELLED else EditorCleanupStatus.FAILED,
                error = message,
                canRetry = true,
            ))
        }
    }

    private fun cancelCleanup(publishCancelled: Boolean = true) {
        val cancellation = synchronized(cleanupOwnershipLock) {
            cleanupPreparationJob?.cancel()
            cleanupPreparationJob = null
            val batch = cleanupBatch ?: return@synchronized null
            cleanupBatch = null
            cleanupObservationJobs.forEach { it.cancel() }
            cleanupObservationJobs = emptyList()
            batch to batch.clips.mapNotNull { it.workId }
        } ?: return
        val (_, workIds) = cancellation
        workIds.forEach(cleanupScheduler::cancel)
        // Leave valid outputs for cache eviction; another concurrent batch may share the key.
        if (publishCancelled) {
            _uiState.update { it.copy(cleanup = it.cleanup.copy(status = EditorCleanupStatus.CANCELLED, canRetry = true, error = "Cleanup cancelled")) }
        }
    }

    private fun retryCleanup() {
        val state = _uiState.value.cleanup
        if (!state.canRetry) return
        startCleanup(state.targetClipId, state.normalize, state.preset, state.wholeTrack)
    }

    private fun observeExport(id: UUID) {
        val (previous, _) = synchronized(exportGate) {
            val previousJob = exportObservationJobs.remove(id)
            val observationJob = viewModelScope.launch {
                exportScheduler.observe(id).filterNotNull().collect { info ->
                val isCurrentWork = synchronized(exportGate) { exportWorkId == id }
                if (!isCurrentWork && info.state != WorkInfo.State.SUCCEEDED &&
                    info.state != WorkInfo.State.FAILED && info.state != WorkInfo.State.CANCELLED
                ) return@collect
                val progress = info.progress.getFloat(EditorExportWork.PROGRESS, _uiState.value.export.progress)
                when (info.state) {
                    WorkInfo.State.ENQUEUED -> _uiState.update { it.copy(export = it.export.copy(status = EditorExportStatus.QUEUED, progress = progress)) }
                    WorkInfo.State.RUNNING -> {
                        synchronized(exportGate) { exportWorkerStartedByWorkId += id }
                        _uiState.update { it.copy(export = it.export.copy(status = EditorExportStatus.RUNNING, progress = progress)) }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        if (isCurrentWork) {
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
                        }
                        synchronized(exportGate) { exportWorkerStartedByWorkId.remove(id) }
                        releaseExportLeaseForWork(id)
                        synchronized(exportGate) { exportObservationJobs.remove(id) }?.cancel()
                    }
                    WorkInfo.State.FAILED -> {
                        if (isCurrentWork) {
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
                        }
                        synchronized(exportGate) { exportWorkerStartedByWorkId.remove(id) }
                        releaseExportLeaseForWork(id)
                        synchronized(exportGate) { exportObservationJobs.remove(id) }?.cancel()
                    }
                    WorkInfo.State.CANCELLED -> {
                        if (isCurrentWork) {
                            _uiState.update { it.copy(export = it.export.copy(status = EditorExportStatus.CANCELLED)) }
                            synchronized(exportGate) {
                                exportManifestPath = null
                                exportWorkId = null
                            }
                        }
                        synchronized(exportGate) { exportWorkerStartedByWorkId.remove(id) }
                        releaseExportLeaseForWork(id)
                        synchronized(exportGate) { exportObservationJobs.remove(id) }?.cancel()
                    }
                    WorkInfo.State.BLOCKED -> Unit
                }
                }
            }
            exportObservationJobs[id] = observationJob
            previousJob to observationJob
        }
        previous?.cancel()
    }

    private fun cancelExport() {
        val workId: UUID?
        val manifestPath: String?
        synchronized(exportGate) {
            if (!exportEnqueueInFlight && exportWorkId == null) return
            exportCancelRequested = true
            workId = exportWorkId
            manifestPath = exportManifestPath.takeIf {
                workId == null || workId !in exportWorkerStartedByWorkId
            }
            if (manifestPath != null) exportManifestPath = null
        }
        workId?.let(exportScheduler::cancel)
        manifestPath?.let { File(it).delete() }
        // The attempt lease remains until WorkManager reports a terminal state. Releasing it here
        // would let eviction delete PCM while a worker that raced into RUNNING still needs it.
        _uiState.update { it.copy(export = it.export.copy(
            status = EditorExportStatus.CANCELLED,
            canRetry = true,
        )) }
    }

    private fun retryExport() {
        val export = _uiState.value.export
        if (export.canRetry || export.status == EditorExportStatus.CANCELLED) {
            val requestedOutputName = export.outputName.ifBlank { defaultExportName(_uiState.value.session) }
            val previousAttemptId = export.exportAttemptId
            // A cancelled WorkManager item may still be delivering its terminal callback. A fresh
            // attempt prevents that old callback from releasing the new attempt's single lease.
            val reuseAttempt = export.status != EditorExportStatus.CANCELLED &&
                previousAttemptId != null &&
                export.attemptOutputName == requestedOutputName &&
                export.attemptPreset == export.preset
            _uiState.update { it.copy(export = it.export.copy(status = EditorExportStatus.IDLE, error = null, progress = 0f)) }
            // Reuse a terminal attempt only when the request identity is unchanged. A changed
            // name or preset must get a fresh manifest/attempt so its persisted output identity
            // cannot be accidentally reused or cleaned by the new export.
            startExport(
                requestedAttemptId = previousAttemptId.takeIf { reuseAttempt },
                replaceExisting = reuseAttempt,
            )
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
        syncCacheReferences()
    }

    private fun syncCacheReferences() {
        val history = commandHistory ?: return
        val counts = history.referencedSessions()
            .asSequence()
            .flatMap { session -> session.tracks.asSequence() }
            .flatMap { track -> track.clips.asSequence() }
            .mapNotNull { it.effects.processedCacheKey }
            .groupingBy { it }
            .eachCount()
        processedCache.replaceSessionReferences(counts)
    }

    private fun acquireExportLease(session: EditorSession, attemptId: String) {
        val counts = session.tracks.asSequence()
            .flatMap { it.clips.asSequence() }
            .mapNotNull { it.effects.processedCacheKey }
            .groupingBy { it }
            .eachCount()
        processedCache.acquireLease(attemptId, counts)
        exportLeaseAttempts += attemptId
    }

    private fun releaseExportLease(attemptId: String) {
        val owned = synchronized(exportGate) { exportLeaseAttempts.remove(attemptId) }
        if (owned) processedCache.releaseLease(attemptId)
    }

    private fun releaseExportLeaseForWork(workId: UUID) {
        val attemptId = synchronized(exportGate) { exportLeaseByWorkId.remove(workId) }
        attemptId?.let(::releaseExportLease)
    }

    private fun processedCacheDir(): File = File(getApplication<Application>().cacheDir, "processed_audio")

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
        cancelCleanup(publishCancelled = false)
        processedCache.clearSessionReferences()
        previewEngine.release()
        super.onCleared()
    }
}

private fun String.toUriForEditor(): Uri =
    Uri.parse(this).takeIf { it.scheme != null } ?: Uri.fromFile(File(this))
