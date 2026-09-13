package com.aistudio.voicenote.cvtr.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.aistudio.voicenote.cvtr.audio.AudioPreviewPlayer
import com.aistudio.voicenote.cvtr.audio.PlaybackState
import com.aistudio.voicenote.cvtr.audio.VoiceNoteConverter
import com.aistudio.voicenote.cvtr.audio.VoiceNoteStorage
import com.aistudio.voicenote.cvtr.audio.WaveformCodec
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.data.repository.ConversionHistoryRepository
import com.aistudio.voicenote.cvtr.telegram.SendResult
import com.aistudio.voicenote.cvtr.telegram.TelegramSender
import com.aistudio.voicenote.cvtr.ui.components.TrimState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

data class MainUiState(
    val selectedFileUri: Uri? = null,
    val fileName: String? = null,
    val fileDurationSec: Int = 0,
    val processStatus: ProcessStatus = ProcessStatus.IDLE,
    val progress: Float = 0f,
    val statusMessage: String = "",
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
    val trimState: TrimState = TrimState(),
    val waveform: List<Int> = emptyList(),
    val convertedUri: Uri? = null,
    val lastConversionId: Long? = null,
    val pitchSemitones: Float = 0f
) {
    fun previewUri(): Uri? = convertedUri ?: selectedFileUri
}


private const val STATE_INPUT_URI = "main.inputUri"
private const val STATE_FILE_NAME = "main.fileName"
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
    private val restoredPitchSemitones =
        savedStateHandle.get<Float>(STATE_PITCH_SEMITONES) ?: 0f
    private val _uiState = MutableStateFlow(
        MainUiState(
            selectedFileUri = restoredInputUri,
            fileName = restoredFileName,
            processStatus = if (restoredInputUri != null) {
                ProcessStatus.ANALYZING
            } else {
                ProcessStatus.IDLE
            },
            pitchSemitones = restoredPitchSemitones
        )
    )
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
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
                savedStateHandle[STATE_PITCH_SEMITONES] = state.pitchSemitones
            }
        }
        restoredInputUri?.let(::handleIncomingUri)
    }

    fun handleIncomingUri(uri: Uri) {
        val generation = inputGeneration.incrementAndGet()
        analysisJob?.cancel()
        waveformJob?.cancel()
        conversionJob?.cancel()
        audioPlayer.stop()
        loadedPlaybackUri = null
        _uiState.value = MainUiState(
            selectedFileUri = uri,
            processStatus = ProcessStatus.ANALYZING,
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
                        fileDurationSec = ((durationMs + 999L) / 1_000L).toInt(),
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
            _uiState.value.waveform.isNotEmpty() ||
            waveformJob?.isActive == true
        ) {
            return
        }
        val generation = inputGeneration.get()
        waveformJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val waveform = VoiceNoteConverter.extractWaveform(context, uri)
                ensureCurrentInput(generation, uri)
                _uiState.update { it.copy(waveform = waveform) }
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
        val trim = state.trimState
        val generation = inputGeneration.get()
        _uiState.update {
            it.copy(processStatus = ProcessStatus.CONVERTING, progress = 0f, errorMessage = null)
        }
        conversionJob?.cancel()
        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            var savedUri: Uri? = null
            var historyId: Long? = null
            var cacheFile: java.io.File? = null
            try {
                val result = VoiceNoteConverter.convertToTelegramVoiceNote(
                    context = context,
                    inputUri = uri,
                    trimStartMs = if (trim.isActive) trim.startMs else 0L,
                    trimEndMs = if (trim.isActive) trim.endMs else Long.MAX_VALUE,
                    pitchSemitones = state.pitchSemitones,
                    onProgress = { progress ->
                        if (isCurrentInput(generation, uri)) {
                            _uiState.update { current -> current.copy(progress = progress) }
                        }
                    }
                )
                cacheFile = result.outputFile
                ensureCurrentInput(generation, uri)

                val outputName = VoiceNoteStorage.generateFileName()
                ensureCurrentInput(generation, uri)
                savedUri = VoiceNoteStorage.saveToPublicStorage(
                    context,
                    result.outputFile,
                    outputName
                )
                ensureCurrentInput(generation, uri)

                val historyItem = ConversionHistory(
                    originalFileName = result.originalFileName,
                    outputFileName = outputName,
                    outputFilePath = savedUri.toString(),
                    durationSeconds = result.durationSeconds,
                    fileSizeBytes = result.outputFile.length(),
                    waveform = WaveformCodec.encode(result.waveform),
                    bitrateKbps = result.bitrateKbps,
                    trimStartMs = trim.startMs.takeIf { trim.isActive },
                    trimEndMs = trim.endMs.takeIf { trim.isActive },
                    createdAt = System.currentTimeMillis(),
                    pitchSemitones = state.pitchSemitones.takeIf { it != 0f }
                )
                ensureCurrentInput(generation, uri)
                historyId = history.insert(historyItem)
                ensureCurrentInput(generation, uri)

                _uiState.update {
                    check(isCurrentInput(generation, uri)) { throw StaleConversionException() }
                    it.copy(
                        processStatus = ProcessStatus.CONVERTED,
                        waveform = result.waveform,
                        fileDurationSec = result.durationSeconds,
                        convertedUri = savedUri,
                        lastConversionId = historyId,
                        progress = 1f,
                        statusMessage = "Voice note siap dipreview dan dikirim"
                    )
                }
            } catch (error: CancellationException) {
                rollbackConversion(historyId, savedUri)
                throw error
            } catch (error: StaleConversionException) {
                rollbackConversion(historyId, savedUri)
                return@launch
            } catch (error: Throwable) {
                rollbackConversion(historyId, savedUri)
                if (isCurrentInput(generation, uri) && error is Exception) {
                    fail(
                        "Gagal mengonversi audio: ${technicalMessage(error, "konversi gagal")}",
                        canRetry = true
                    )
                } else if (error !is Exception) {
                    throw error
                }
            } finally {
                cacheFile?.delete()
            }
        }
    }
    private suspend fun rollbackConversion(historyId: Long?, savedUri: Uri?) {
        withContext(NonCancellable) {
            historyId?.let { id ->
                runCatching { history.delete(id) }
                    .onFailure { Log.e(TAG, "Gagal menghapus row rollback $id", it) }
            }
            savedUri?.let { uri ->
                runCatching {
                    val deleted = VoiceNoteStorage.deleteFromStorage(context, uri.toString())
                    if (!deleted && VoiceNoteStorage.fileExists(context, uri.toString())) {
                        error("File hasil rollback masih ada")
                    }
                }.onFailure {
                    Log.e(TAG, "Gagal menghapus file rollback $uri", it)
                }
            }
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
        if (state.convertedUri != null) return 1f
        return 2.0.pow((state.pitchSemitones / 12f).toDouble()).toFloat()
    }

    override fun onCleared() {
        audioPlayer.release()
        super.onCleared()
    }

    companion object {
        fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
