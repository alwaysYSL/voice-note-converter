package com.aistudio.voicenote.cvtr.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.aistudio.voicenote.cvtr.audio.AudioProcessingOptions
import com.aistudio.voicenote.cvtr.audio.MediaInputCache
import com.aistudio.voicenote.cvtr.audio.AudioPreviewPlayer
import com.aistudio.voicenote.cvtr.audio.PlaybackState
import com.aistudio.voicenote.cvtr.audio.VoiceNoteConverter
import com.aistudio.voicenote.cvtr.audio.VoiceNoteStorage
import com.aistudio.voicenote.cvtr.audio.WaveformCodec
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.work.ConversionWork
import com.aistudio.voicenote.cvtr.data.repository.ConversionHistoryRepository
import com.aistudio.voicenote.cvtr.telegram.SendResult
import com.aistudio.voicenote.cvtr.telegram.TelegramSender
import com.aistudio.voicenote.cvtr.ui.components.TrimState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.pow

enum class ProcessStatus {
    IDLE,
    ANALYZING,
    CONVERTING,
    CONVERTED,
    SENDING,
    SHARE_OPENED,
    CONFIRMED_SENT,
    FAILED
}

enum class PreviewSource {
    ORIGINAL,
    CONVERTED
}

data class MainUiState(
    val selectedFileUri: Uri? = null,
    val fileName: String? = null,
    val outputFileName: String = "",
    val fileDurationSec: Int = 0,
    val originalDurationMs: Long = 0L,
    val convertedDurationMs: Long = 0L,
    val processStatus: ProcessStatus = ProcessStatus.IDLE,
    val progress: Float = 0f,
    val statusMessage: String = "",
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
    val trimState: TrimState = TrimState(),
    val waveform: List<Int> = emptyList(),
    val originalWaveform: List<Int> = emptyList(),
    val convertedWaveform: List<Int> = emptyList(),
    val previewSource: PreviewSource = PreviewSource.CONVERTED,
    val normalizeAudio: Boolean = false,
    val trimSilence: Boolean = false,
    val compatibilitySummary: String? = null,
    val compatibilityWarning: String? = null,
    val convertedUri: Uri? = null,
    val lastConversionId: Long? = null,
    val pitchSemitones: Float = 0f
) {
    fun previewUri(): Uri? = if (
        previewSource == PreviewSource.CONVERTED && convertedUri != null
    ) {
        convertedUri
    } else {
        selectedFileUri
    }

    fun previewWaveform(): List<Int> = if (previewSource == PreviewSource.CONVERTED) {
        convertedWaveform.ifEmpty { waveform }
    } else {
        originalWaveform.ifEmpty { waveform }
    }
}


private const val STATE_INPUT_URI = "main.inputUri"
private const val STATE_FILE_NAME = "main.fileName"
private const val STATE_OUTPUT_FILE_NAME = "main.outputFileName"
private const val STATE_NORMALIZE_AUDIO = "main.normalizeAudio"
private const val STATE_TRIM_SILENCE = "main.trimSilence"
private const val STATE_CONVERSION_WORK_ID = "main.conversionWorkId"
private const val STATE_PITCH_SEMITONES = "main.pitchSemitones"
private const val TAG = "MainViewModel"
private class StaleConversionException : Exception()

class MainViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle = SavedStateHandle(),
    private val history: ConversionHistoryRepository = ConversionHistoryRepository(
        AppDatabase.getDatabase(application).conversionHistoryDao()
    ),
    private val telegramSender: TelegramSender = TelegramSender(),
    audioPlayerFactory: (Context, kotlinx.coroutines.CoroutineScope) -> AudioPreviewPlayer =
        ::AudioPreviewPlayer
) : AndroidViewModel(application) {
    private val context: Context
        get() = getApplication<Application>().applicationContext

    val audioPlayer = audioPlayerFactory(context, viewModelScope)
    val playbackState: StateFlow<PlaybackState> = audioPlayer.playbackState

    private val restoredInputUri = savedStateHandle
        .get<String>(STATE_INPUT_URI)
        ?.let { Uri.parse(it) }
    private val restoredFileName = savedStateHandle.get<String>(STATE_FILE_NAME)
    private val restoredOutputFileName = savedStateHandle.get<String>(STATE_OUTPUT_FILE_NAME)
    private val restoredNormalizeAudio =
        savedStateHandle.get<Boolean>(STATE_NORMALIZE_AUDIO) ?: false
    private val restoredTrimSilence =
        savedStateHandle.get<Boolean>(STATE_TRIM_SILENCE) ?: false
    private val restoredPitchSemitones =
        savedStateHandle.get<Float>(STATE_PITCH_SEMITONES) ?: 0f
    private val restoredConversionWorkId = savedStateHandle
        .get<String>(STATE_CONVERSION_WORK_ID)
        ?.let { value -> runCatching { UUID.fromString(value) }.getOrNull() }
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedFileUri = restoredInputUri,
            fileName = restoredFileName,
            outputFileName = restoredOutputFileName.orEmpty(),
            processStatus = when {
                restoredConversionWorkId != null -> ProcessStatus.CONVERTING
                restoredInputUri != null -> ProcessStatus.ANALYZING
                else -> ProcessStatus.IDLE
            },
            normalizeAudio = restoredNormalizeAudio,
            trimSilence = restoredTrimSilence,
            pitchSemitones = restoredPitchSemitones
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private val batchStore: BatchQueueStore by lazy { BatchQueueStore(context) }
    private val workManager: WorkManager? by lazy {
        runCatching { WorkManager.getInstance(context) }.getOrNull()
    }
    private val _batchItems = MutableStateFlow<List<BatchQueueItem>>(emptyList())
    val batchItems: StateFlow<List<BatchQueueItem>> = _batchItems.asStateFlow()
    private val _batchMessage = MutableStateFlow<String?>(null)
    val batchMessage: StateFlow<String?> = _batchMessage.asStateFlow()
    private var conversionWorkId: UUID? = null
    private var batchMonitorJob: Job? = null
    private val inputGeneration = AtomicLong(0L)
    private var loadedPlaybackUri: Uri? = null
    private var analysisJob: Job? = null
    private var waveformJob: Job? = null
    private var conversionJob: Job? = null

    init {
        viewModelScope.launch {
            uiState.collect { state ->
                savedStateHandle[STATE_INPUT_URI] = state.selectedFileUri?.toString()
                savedStateHandle[STATE_FILE_NAME] = state.fileName
                savedStateHandle[STATE_OUTPUT_FILE_NAME] = state.outputFileName
                savedStateHandle[STATE_NORMALIZE_AUDIO] = state.normalizeAudio
                savedStateHandle[STATE_TRIM_SILENCE] = state.trimSilence
                savedStateHandle[STATE_PITCH_SEMITONES] = state.pitchSemitones
            }
        }
        workManager?.let { manager ->
            batchMonitorJob = viewModelScope.launch(Dispatchers.IO) {
                monitorBatchQueue(manager)
            }
        }
        restoredInputUri?.let { uri ->
            handleIncomingUri(uri)
            restoredConversionWorkId?.let { workId ->
                conversionWorkId = workId
                savedStateHandle[STATE_CONVERSION_WORK_ID] = workId.toString()
                _uiState.update { it.copy(processStatus = ProcessStatus.CONVERTING) }
                conversionJob = viewModelScope.launch(Dispatchers.IO) {
                    observeConversionWork(workId, inputGeneration.get(), uri)
                }
            }
        }
    }

    fun handleIncomingUri(uri: Uri) {
        val generation = inputGeneration.incrementAndGet()
        analysisJob?.cancel()
        waveformJob?.cancel()
        conversionJob?.cancel()
        cancelConversionWork()
        audioPlayer.stop()
        loadedPlaybackUri = null
        _uiState.value = MainUiState(
            selectedFileUri = uri,
            outputFileName = if (uri == restoredInputUri) restoredOutputFileName.orEmpty() else "",
            processStatus = ProcessStatus.ANALYZING,
            previewSource = PreviewSource.ORIGINAL,
            normalizeAudio = if (uri == restoredInputUri) restoredNormalizeAudio else false,
            trimSilence = if (uri == restoredInputUri) restoredTrimSilence else false,
            pitchSemitones = if (uri == restoredInputUri) restoredPitchSemitones else 0f
        )
        audioPlayer.setPitchPreview(pitchFactorForPreview(_uiState.value))
        analysisJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val (name, durationMs) = VoiceNoteConverter.getMediaInfo(context, uri)
                ensureCurrentInput(generation, uri)
                _uiState.update {
                    it.copy(
                        fileName = name,
                        outputFileName = it.outputFileName.ifBlank {
                            VoiceNoteStorage.suggestedOutputFileName(name)
                        },
                        fileDurationSec = ((durationMs + 999L) / 1_000L).toInt(),
                        originalDurationMs = durationMs,
                        processStatus = ProcessStatus.IDLE,
                        trimState = TrimState(endMs = durationMs, totalDurationMs = durationMs),
                        statusMessage = "File siap dikonversi"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: StaleConversionException) {
                return@launch
            } catch (error: Exception) {
                if (isCurrentInput(generation, uri)) {
                    fail(
                        "Gagal memproses file: ${technicalMessage(error, "metadata tidak tersedia")}",
                        canRetry = false
                    )
                }
            }
        }
    }

    private fun ensureWaveformLoaded(uri: Uri) {
        if (_uiState.value.selectedFileUri != uri ||
            _uiState.value.originalWaveform.isNotEmpty() ||
            waveformJob?.isActive == true
        ) {
            return
        }
        val generation = inputGeneration.get()
        waveformJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val waveform = VoiceNoteConverter.extractWaveform(context, uri)
                ensureCurrentInput(generation, uri)
                _uiState.update {
                    it.copy(
                        waveform = if (it.previewSource == PreviewSource.ORIGINAL) waveform else it.waveform,
                        originalWaveform = waveform
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: StaleConversionException) {
                return@launch
            } catch (error: Exception) {
                if (isCurrentInput(generation, uri)) {
                    fail(
                        "Gagal menganalisis waveform: ${technicalMessage(error, "waveform tidak tersedia")}",
                        canRetry = false
                    )
                }
            }
        }
    }

    fun toggleTrim() {
        val state = _uiState.value
        _uiState.update { current ->
            val trim = current.trimState
            if (trim.totalDurationMs <= 0L) return@update current
            current.copy(
                trimState = trim.copy(
                    isActive = !trim.isActive,
                    endMs = if (trim.endMs == 0L) trim.totalDurationMs else trim.endMs
                )
            )
        }
        state.selectedFileUri?.let(::ensureWaveformLoaded)
    }

    fun updateTrimRange(startFraction: Float, endFraction: Float) {
        val state = _uiState.value
        _uiState.update { current ->
            val trim = current.trimState
            if (trim.totalDurationMs <= 0L) return@update current
            val minimumFraction = (100f / trim.totalDurationMs).coerceAtMost(1f)
            val safeStart = startFraction.coerceIn(0f, 1f - minimumFraction)
            val safeEnd = endFraction.coerceIn(safeStart + minimumFraction, 1f)
            current.copy(
                trimState = trim.copy(
                    startMs = (safeStart * trim.totalDurationMs).toLong(),
                    endMs = (safeEnd * trim.totalDurationMs).toLong(),
                    isActive = true
                )
            )
        }
        state.selectedFileUri?.let(::ensureWaveformLoaded)
    }

    fun resetTrim() {
        _uiState.update { state ->
            state.copy(
                trimState = state.trimState.copy(
                    startMs = 0L,
                    endMs = state.trimState.totalDurationMs
                )
            )
        }
        audioPlayer.clearClipping()
    }

    fun updatePitch(semitones: Float) {
        val snapped = if (kotlin.math.abs(semitones) < 0.15f) 0f else semitones
        val clamped = snapped.coerceIn(-4f, 4f)
        _uiState.update { it.copy(pitchSemitones = clamped) }
        audioPlayer.setPitchPreview(pitchFactorForPreview(_uiState.value))
    }

    fun updateOutputFileName(value: String) {
        if (_uiState.value.convertedUri != null) return
        _uiState.update { it.copy(outputFileName = value.take(120)) }
    }

    fun setNormalizeAudio(enabled: Boolean) {
        _uiState.update { it.copy(normalizeAudio = enabled) }
    }

    fun setTrimSilence(enabled: Boolean) {
        _uiState.update { it.copy(trimSilence = enabled) }
    }

    fun setPreviewSource(source: PreviewSource) {
        val state = _uiState.value
        val outputUri = state.convertedUri
        if (source == state.previewSource || outputUri == null) return
        val currentPlayback = playbackState.value
        val fraction = if (currentPlayback.totalDurationMs > 0L) {
            currentPlayback.currentPositionMs.toFloat() / currentPlayback.totalDurationMs
        } else {
            0f
        }
        val targetUri = if (source == PreviewSource.CONVERTED) {
            outputUri
        } else {
            state.selectedFileUri ?: return
        }
        val targetDuration = if (source == PreviewSource.CONVERTED) {
            state.convertedDurationMs
        } else {
            state.originalDurationMs
        }
        _uiState.update {
            it.copy(
                previewSource = source,
                waveform = if (source == PreviewSource.CONVERTED) {
                    it.convertedWaveform
                } else {
                    it.originalWaveform
                }
            )
        }
        if (source == PreviewSource.ORIGINAL) ensureWaveformLoaded(targetUri)
        audioPlayer.clearClipping()
        audioPlayer.play(
            targetUri,
            pitchFactor = if (source == PreviewSource.ORIGINAL) {
                pitchFactorForPreview(state.copy(previewSource = PreviewSource.ORIGINAL))
            } else {
                1f
            },
            startPositionMs = (fraction * targetDuration).toLong()
        )
        loadedPlaybackUri = targetUri
    }

    fun previewTrim() {
        val state = _uiState.value
        val uri = state.selectedFileUri ?: return
        ensureWaveformLoaded(uri)
        audioPlayer.setClipping(state.trimState.startMs, state.trimState.endMs)
        audioPlayer.play(uri, pitchFactor = pitchFactorForPreview(state))
        loadedPlaybackUri = uri
    }

    fun seekToFraction(fraction: Float) {
        val duration = playbackState.value.totalDurationMs
        if (duration > 0L) {
            audioPlayer.seekTo((fraction.coerceIn(0f, 1f) * duration).toLong())
        }
    }

    fun togglePlayback() {
        val state = _uiState.value
        val source = state.previewUri() ?: return
        if (state.convertedUri == null) ensureWaveformLoaded(source)
        if (loadedPlaybackUri == source && playbackState.value.totalDurationMs > 0L) {
            audioPlayer.togglePlayPause()
        } else {
            audioPlayer.clearClipping()
            audioPlayer.play(source, pitchFactor = pitchFactorForPreview(state))
            loadedPlaybackUri = source
        }
    }

    fun pausePlayback() {
        audioPlayer.pause()
    }

    fun startConversion() {
        val state = _uiState.value
        val uri = state.selectedFileUri ?: return
        if (state.processStatus == ProcessStatus.CONVERTING ||
            state.processStatus == ProcessStatus.SENDING
        ) {
            return
        }
        val manager = workManager ?: run {
            fail("WorkManager tidak tersedia.", canRetry = true)
            return
        }
        val trim = state.trimState
        val generation = inputGeneration.get()
        conversionJob?.cancel()
        cancelConversionWork()
        _uiState.update {
            it.copy(
                processStatus = ProcessStatus.CONVERTING,
                progress = 0f,
                errorMessage = null,
                canRetry = false,
                statusMessage = "Menyiapkan konversi di latar belakang…"
            )
        }
        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            var cachedUri: Uri? = null
            var enqueued = false
            try {
                cachedUri = MediaInputCache.copyToPersistent(context, uri)
                ensureCurrentInput(generation, uri)
                val request = ConversionWork.request(
                    inputUri = cachedUri,
                    requestedOutputName = state.outputFileName,
                    trimStartMs = if (trim.isActive) trim.startMs else 0L,
                    trimEndMs = if (trim.isActive) trim.endMs else Long.MAX_VALUE,
                    pitchSemitones = state.pitchSemitones,
                    options = AudioProcessingOptions(
                        normalizeAudio = state.normalizeAudio,
                        trimSilence = state.trimSilence
                    ),
                    sourceFileName = state.fileName
                )
                conversionWorkId = request.id
                savedStateHandle[STATE_CONVERSION_WORK_ID] = request.id.toString()
                manager.enqueueUniqueWork(
                    ConversionWork.SINGLE_WORK_NAME,
                    androidx.work.ExistingWorkPolicy.REPLACE,
                    request
                )
                enqueued = true
                observeConversionWork(request.id, generation, uri)
            } catch (error: CancellationException) {
                if (!enqueued) cachedUri?.let { MediaInputCache.delete(context, it) }
                throw error
            } catch (error: StaleConversionException) {
                if (!enqueued) cachedUri?.let { MediaInputCache.delete(context, it) }
                manager.cancelUniqueWork(ConversionWork.SINGLE_WORK_NAME)
            } catch (error: Throwable) {
                if (!enqueued) cachedUri?.let { MediaInputCache.delete(context, it) }
                if (isCurrentInput(generation, uri)) {
                    fail(
                        "Gagal memulai konversi: ${technicalMessage(error, "konversi gagal")}",
                        canRetry = true
                    )
                }
            }
        }
    }

    private suspend fun observeConversionWork(
        workId: UUID,
        generation: Long,
        sourceUri: Uri
    ) {
        val manager = workManager ?: return
        while (true) {
            ensureCurrentInput(generation, sourceUri)
            val info = manager.getWorkInfoById(workId).get(2, TimeUnit.SECONDS)
                ?: error("Pekerjaan konversi tidak ditemukan.")
            when (info.state) {
                WorkInfo.State.ENQUEUED,
                WorkInfo.State.BLOCKED,
                WorkInfo.State.RUNNING -> {
                    val progress = info.progress
                        .getFloat(ConversionWork.PROGRESS, 0f)
                        .coerceIn(0f, 1f)
                    _uiState.update {
                        if (isCurrentInput(generation, sourceUri)) {
                            it.copy(
                                processStatus = ProcessStatus.CONVERTING,
                                progress = progress,
                                statusMessage = if (info.state == WorkInfo.State.BLOCKED) {
                                    "Menunggu ruang penyimpanan…"
                                } else {
                                    "Mengonversi di latar belakang…"
                                }
                            )
                        } else {
                            it
                        }
                    }
                    delay(150L)
                }
                WorkInfo.State.SUCCEEDED -> {
                    publishConversionResult(info, generation, sourceUri)
                    return
                }
                WorkInfo.State.FAILED -> {
                    val message = info.outputData
                        .getString(ConversionWork.ERROR_MESSAGE)
                        ?: "Konversi gagal."
                    conversionWorkId = null
                    savedStateHandle[STATE_CONVERSION_WORK_ID] = null
                    if (isCurrentInput(generation, sourceUri)) {
                        fail(message, canRetry = true)
                    }
                    return
                }
                WorkInfo.State.CANCELLED -> {
                    conversionWorkId = null
                    savedStateHandle[STATE_CONVERSION_WORK_ID] = null
                    if (isCurrentInput(generation, sourceUri)) {
                        fail("Konversi dibatalkan.", canRetry = true)
                    }
                    return
                }
            }
        }
    }

    private suspend fun publishConversionResult(
        info: WorkInfo,
        generation: Long,
        sourceUri: Uri
    ) {
        ensureCurrentInput(generation, sourceUri)
        val outputUri = info.outputData.getString(ConversionWork.RESULT_URI)
            ?.let(Uri::parse)
            ?: error("Worker tidak mengembalikan URI hasil.")
        val historyId = info.outputData.getLong(ConversionWork.RESULT_HISTORY_ID, -1L)
            .takeIf { it >= 0L }
        val outputName = info.outputData
            .getString(ConversionWork.RESULT_OUTPUT_FILE_NAME)
            .orEmpty()
        val summary = info.outputData
            .getString(ConversionWork.RESULT_COMPATIBILITY_SUMMARY)
        val waveform = info.outputData
            .getString(ConversionWork.RESULT_WAVEFORM)
            ?.let(WaveformCodec::decode)
            .orEmpty()
        val durationSeconds = info.outputData.getInt(
            ConversionWork.RESULT_DURATION_SECONDS,
            _uiState.value.fileDurationSec
        )
        val warning = info.outputData
            .getString(ConversionWork.RESULT_COMPATIBILITY_WARNING)
        _uiState.update {
            check(isCurrentInput(generation, sourceUri)) {
                throw StaleConversionException()
            }
            it.copy(
                processStatus = ProcessStatus.CONVERTED,
                outputFileName = outputName.ifBlank { it.outputFileName },
                waveform = waveform,
                convertedWaveform = waveform,
                previewSource = PreviewSource.CONVERTED,
                fileDurationSec = durationSeconds,
                convertedDurationMs = durationSeconds * 1_000L,
                convertedUri = outputUri,
                lastConversionId = historyId,
                compatibilitySummary = summary,
                compatibilityWarning = warning,
                progress = 1f,
                canRetry = false,
                statusMessage = "Voice note siap dipreview dan dikirim"
            )
        }
        conversionWorkId = null
        savedStateHandle[STATE_CONVERSION_WORK_ID] = null
    }

    private fun cancelConversionWork(cleanupCompleted: Boolean = true) {
        val workId = conversionWorkId
        val manager = workManager
        if (workId != null && manager != null) {
            manager.cancelWorkById(workId)
            if (cleanupCompleted) {
                cleanupCancelledWork(manager, workId)
            }
        }
        conversionWorkId = null
        savedStateHandle[STATE_CONVERSION_WORK_ID] = null
    }

    private fun cleanupCancelledWork(manager: WorkManager, workId: UUID) {
        viewModelScope.launch(Dispatchers.IO) {
            repeat(10) {
                val info = runCatching {
                    manager.getWorkInfoById(workId).get(1, TimeUnit.SECONDS)
                }.getOrNull() ?: return@launch
                when (info.state) {
                    WorkInfo.State.SUCCEEDED -> {
                        val historyId = info.outputData
                            .getLong(ConversionWork.RESULT_HISTORY_ID, -1L)
                            .takeIf { it >= 0L }
                        historyId?.let { history.delete(it) }
                        info.outputData
                            .getString(ConversionWork.RESULT_URI)
                            ?.let { VoiceNoteStorage.deleteFromStorage(context, it) }
                        return@launch
                    }
                    WorkInfo.State.FAILED,
                    WorkInfo.State.CANCELLED -> return@launch
                    else -> delay(100L)
                }
            }
        }
    }

    fun enqueueBatch(uris: List<Uri>) {
        val manager = workManager ?: run {
            _batchMessage.value = "WorkManager tidak tersedia."
            return
        }
        val distinctUris = uris.distinct()
        if (distinctUris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val prepared = mutableListOf<Pair<BatchQueueMetadata, androidx.work.OneTimeWorkRequest>>()
            var failedCount = 0
            distinctUris.forEach { uri ->
                var cachedUri: Uri? = null
                try {
                    val sourceName = MediaInputCache.displayName(context, uri)
                        .orEmpty()
                        .ifBlank { "voice_note" }
                    cachedUri = MediaInputCache.copyToPersistent(context, uri)
                    val batchItemId = UUID.randomUUID().toString()
                    val outputName = VoiceNoteStorage.suggestedOutputFileName(sourceName)
                    val request = ConversionWork.request(
                        inputUri = cachedUri,
                        requestedOutputName = outputName,
                        trimStartMs = 0L,
                        trimEndMs = Long.MAX_VALUE,
                        pitchSemitones = 0f,
                        options = AudioProcessingOptions(),
                        batchItemId = batchItemId,
                        sourceFileName = sourceName
                    )
                    val metadata = BatchQueueMetadata(
                        id = batchItemId,
                        workId = request.id,
                        sourceFileName = sourceName,
                        outputFileName = outputName,
                        inputUri = cachedUri.toString(),
                        sourceUri = uri.toString()
                    )
                    batchStore.put(metadata)
                    prepared += metadata to request
                } catch (error: Throwable) {
                    failedCount += 1
                    cachedUri?.let { MediaInputCache.delete(context, it) }
                    Log.e(TAG, "Gagal menyiapkan item batch $uri", error)
                }
            }
            if (prepared.isNotEmpty()) {
                runCatching {
                    var continuation = manager.beginUniqueWork(
                        ConversionWork.BATCH_CHAIN_NAME,
                        androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                        prepared.first().second
                    )
                    prepared.drop(1).forEach { (_, request) ->
                        continuation = continuation.then(request)
                    }
                    continuation.enqueue()
                }.onFailure { error ->
                    failedCount += prepared.size
                    prepared.forEach { (metadata, _) ->
                        batchStore.remove(metadata.id)
                        MediaInputCache.delete(context, Uri.parse(metadata.inputUri))
                    }
                    prepared.clear()
                    Log.e(TAG, "Gagal memasukkan antrean batch", error)
                }
            }
            val enqueuedCount = prepared.size
            _batchMessage.value = when {
                failedCount == 0 -> "$enqueuedCount file masuk antrean berurutan."
                enqueuedCount == 0 -> "Tidak ada file yang masuk antrean."
                else -> "$enqueuedCount file masuk antrean; $failedCount gagal."
            }
        }
    }

    fun retryBatch(item: BatchQueueItem) {
        val manager = workManager ?: run {
            _batchMessage.value = "WorkManager tidak tersedia."
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            var cachedUri: Uri? = null
            runCatching {
                cachedUri = MediaInputCache.copyToPersistent(context, Uri.parse(item.sourceUri))
                val request = ConversionWork.request(
                    inputUri = requireNotNull(cachedUri),
                    requestedOutputName = item.outputFileName,
                    trimStartMs = item.trimStartMs,
                    trimEndMs = item.trimEndMs,
                    pitchSemitones = item.pitchSemitones,
                    options = AudioProcessingOptions(
                        normalizeAudio = item.normalizeAudio,
                        trimSilence = item.trimSilence
                    ),
                    batchItemId = item.id,
                    sourceFileName = item.sourceFileName
                )
                batchStore.put(
                    BatchQueueMetadata(
                        id = item.id,
                        workId = request.id,
                        sourceFileName = item.sourceFileName,
                        outputFileName = item.outputFileName,
                        inputUri = requireNotNull(cachedUri).toString(),
                        sourceUri = item.sourceUri,
                        trimStartMs = item.trimStartMs,
                        trimEndMs = item.trimEndMs,
                        pitchSemitones = item.pitchSemitones,
                        normalizeAudio = item.normalizeAudio,
                        trimSilence = item.trimSilence
                    )
                )
                manager.beginUniqueWork(
                    ConversionWork.BATCH_CHAIN_NAME,
                    androidx.work.ExistingWorkPolicy.APPEND_OR_REPLACE,
                    request
                ).enqueue()
            }.onFailure { error ->
                cachedUri?.let { MediaInputCache.delete(context, it) }
                _batchMessage.value = "Gagal mengulang ${item.sourceFileName}: pilih ulang file."
                Log.e(TAG, "Gagal mengulang item batch", error)
            }
        }
    }

    fun cancelBatch(@Suppress("UNUSED_PARAMETER") item: BatchQueueItem) {
        workManager?.cancelUniqueWork(ConversionWork.BATCH_CHAIN_NAME)
        batchStore.all().forEach { metadata ->
            MediaInputCache.delete(context, Uri.parse(metadata.inputUri))
        }
        batchStore.clear()
        _batchItems.value = emptyList()
        _batchMessage.value = "Antrean batch dibatalkan."
    }

    fun dismissBatch(item: BatchQueueItem) {
        batchStore.remove(item.id)
        _batchItems.update { items -> items.filterNot { it.id == item.id } }
    }

    fun dismissAllBatch() {
        batchStore.clear()
        _batchItems.value = emptyList()
        _batchMessage.value = null
    }

    fun clearBatchMessage() {
        _batchMessage.value = null
    }

    private suspend fun monitorBatchQueue(manager: WorkManager) {
        while (true) {
            val items = batchStore.all().mapNotNull { metadata ->
                runCatching {
                    manager.getWorkInfoById(metadata.workId).get()
                }.getOrNull()?.also { info ->
                    if (info.state == WorkInfo.State.CANCELLED) {
                        MediaInputCache.delete(context, Uri.parse(metadata.inputUri))
                    }
                }?.toBatchQueueItem(metadata)
            }.sortedWith(
                compareBy<BatchQueueItem> { it.isTerminal }
                    .thenBy { it.sourceFileName.lowercase() }
            )
            _batchItems.value = items
            delay(500L)
        }
    }

    fun sendConverted() {
        val state = _uiState.value
        val uri = state.convertedUri ?: return
        val historyId = state.lastConversionId
        _uiState.update { it.copy(processStatus = ProcessStatus.SENDING, errorMessage = null) }
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) {
                telegramSender.sendVoiceNoteViaTelegramApp(context, uri)
            }) {
                is SendResult.IntentLaunched -> {
                    historyId?.let {
                        withContext(Dispatchers.IO) {
                            history.markShareOpened(it, "Telegram")
                        }
                    }
                    _uiState.update {
                        it.copy(processStatus = ProcessStatus.SHARE_OPENED, statusMessage = result.details)
                    }
                }
                is SendResult.Failure -> fail(result.errorMessage, result.canRetry)
            }
        }
    }

    fun confirmConvertedSent() {
        val id = _uiState.value.lastConversionId ?: return
        viewModelScope.launch(Dispatchers.IO) {
            if (history.confirmSent(id) <= 0) {
                fail("Buka Telegram terlebih dahulu sebelum mengonfirmasi pengiriman.", canRetry = false)
                return@launch
            }
            _uiState.update {
                it.copy(
                    processStatus = ProcessStatus.CONFIRMED_SENT,
                    statusMessage = "Ditandai sudah dikirim"
                )
            }
        }
    }

    fun resetForNewFile() {
        inputGeneration.incrementAndGet()
        analysisJob?.cancel()
        waveformJob?.cancel()
        conversionJob?.cancel()
        cancelConversionWork()
        audioPlayer.stop()
        loadedPlaybackUri = null
        _uiState.value = MainUiState()
    }

    private fun isCurrentInput(generation: Long, uri: Uri): Boolean =
        inputGeneration.get() == generation && _uiState.value.selectedFileUri == uri

    private suspend fun ensureCurrentInput(generation: Long, uri: Uri) {
        currentCoroutineContext().ensureActive()
        check(isCurrentInput(generation, uri)) { throw StaleConversionException() }
    }

    private fun technicalMessage(error: Throwable, fallback: String): String {
        Log.e(TAG, fallback, error)
        return error.localizedMessage?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun fail(message: String, canRetry: Boolean) {
        _uiState.update {
            it.copy(
                processStatus = ProcessStatus.FAILED,
                errorMessage = message,
                canRetry = canRetry
            )
        }
    }

    private fun pitchFactorForPreview(state: MainUiState): Float {
        if (state.previewSource == PreviewSource.CONVERTED && state.convertedUri != null) {
            return 1f
        }
        return 2.0.pow((state.pitchSemitones / 12f).toDouble()).toFloat()
    }

    override fun onCleared() {
        cancelConversionWork(cleanupCompleted = false)
        audioPlayer.release()
        super.onCleared()
    }
    companion object {
        fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
