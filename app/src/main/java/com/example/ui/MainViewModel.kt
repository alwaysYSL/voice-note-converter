package com.example.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPreviewPlayer
import com.example.audio.PlaybackState
import com.example.audio.VoiceNoteConverter
import com.example.audio.VoiceNoteStorage
import com.example.data.local.AppDatabase
import com.example.data.local.ConversionHistory
import com.example.data.local.RecentContact
import com.example.data.repository.ContactRepository
import com.example.data.repository.ConversionHistoryRepository
import com.example.telegram.SendResult
import com.example.telegram.TelegramSender
import com.example.ui.components.TrimState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.pow

enum class ProcessStatus { IDLE, ANALYZING, CONVERTING, CONVERTED, SENDING, SENT, FAILED }

data class MainUiState(
    val selectedFileUri: Uri? = null,
    val fileName: String? = null,
    val fileDurationSec: Int = 0,
    val processStatus: ProcessStatus = ProcessStatus.IDLE,
    val progress: Float = 0f,
    val statusMessage: String = "",
    val errorMessage: String? = null,
    val canRetry: Boolean = false,
    val selectedContact: RecentContact? = null,
    val trimState: TrimState = TrimState(),
    val waveform: List<Int> = emptyList(),
    val convertedUri: Uri? = null,
    val lastConversionId: Long? = null,
    val pitchSemitones: Float = 0f
) {
    fun previewUri(): Uri? = convertedUri ?: selectedFileUri
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val context: Context
        get() = getApplication<Application>().applicationContext
    private val database = AppDatabase.getDatabase(context)
    private val contacts = ContactRepository(database.recentContactDao())
    private val history = ConversionHistoryRepository(database.conversionHistoryDao())
    private val telegramSender = TelegramSender()

    val audioPlayer = AudioPreviewPlayer(context, viewModelScope)
    val playbackState: StateFlow<PlaybackState> = audioPlayer.playbackState
    val recentContacts: StateFlow<List<RecentContact>> = contacts.recentContacts.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
    private var loadedPlaybackUri: Uri? = null
    private var analysisJob: Job? = null
    private var conversionJob: Job? = null

    fun handleIncomingUri(uri: Uri) {
        analysisJob?.cancel()
        conversionJob?.cancel()
        audioPlayer.stop()
        audioPlayer.setPitchPreview(1f)
        loadedPlaybackUri = null
        _uiState.value = MainUiState(selectedFileUri = uri, processStatus = ProcessStatus.ANALYZING)
        analysisJob = viewModelScope.launch {
            try {
                val (name, durationMs) = VoiceNoteConverter.getMediaInfo(context, uri)
                val waveform = VoiceNoteConverter.extractWaveform(context, uri)
                if (_uiState.value.selectedFileUri != uri) return@launch
                _uiState.update {
                    it.copy(
                        fileName = name,
                        fileDurationSec = ((durationMs + 999L) / 1_000L).toInt(),
                        processStatus = ProcessStatus.IDLE,
                        trimState = TrimState(endMs = durationMs, totalDurationMs = durationMs),
                        waveform = waveform,
                        statusMessage = "File siap dikonversi"
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                fail("Gagal memproses file: ${error.localizedMessage}", canRetry = false)
            }
        }
    }

    fun selectContact(contact: RecentContact) {
        _uiState.update { it.copy(selectedContact = contact) }
    }

    fun addNewContactAndSelect(
        name: String,
        chatId: Long,
        username: String? = null,
        phone: String? = null
    ) {
        viewModelScope.launch {
            contacts.saveContactUsage(name, chatId, username, phone)
            selectContact(RecentContact(chatId = chatId, name = name, username = username, phone = phone))
        }
    }

    fun toggleTrim() {
        _uiState.update { state ->
            val trim = state.trimState
            if (trim.totalDurationMs <= 0L) return@update state
            state.copy(trimState = trim.copy(
                isActive = !trim.isActive,
                endMs = if (trim.endMs == 0L) trim.totalDurationMs else trim.endMs
            ))
        }
    }

    fun updateTrimRange(startFraction: Float, endFraction: Float) {
        _uiState.update { state ->
            val trim = state.trimState
            if (trim.totalDurationMs <= 0L) return@update state
            val minimumFraction = (100f / trim.totalDurationMs).coerceAtMost(1f)
            val safeStart = startFraction.coerceIn(0f, 1f - minimumFraction)
            val safeEnd = endFraction.coerceIn(safeStart + minimumFraction, 1f)
            state.copy(trimState = trim.copy(
                startMs = (safeStart * trim.totalDurationMs).toLong(),
                endMs = (safeEnd * trim.totalDurationMs).toLong(),
                isActive = true
            ))
        }
    }

    fun resetTrim() {
        _uiState.update { state ->
            state.copy(trimState = state.trimState.copy(startMs = 0L, endMs = state.trimState.totalDurationMs))
        }
        audioPlayer.clearClipping()
    }

    fun updatePitch(semitones: Float) {
        val snapped = if (kotlin.math.abs(semitones) < 0.15f) 0f else semitones
        val clamped = snapped.coerceIn(-4f, 4f)
        _uiState.update { it.copy(pitchSemitones = clamped) }
        val factor = 2.0.pow((clamped / 12f).toDouble()).toFloat()
        audioPlayer.setPitchPreview(factor)
    }

    fun previewTrim() {
        val state = _uiState.value
        val uri = state.selectedFileUri ?: return
        audioPlayer.setClipping(state.trimState.startMs, state.trimState.endMs)
        audioPlayer.play(uri)
        loadedPlaybackUri = uri
    }

    fun seekToFraction(fraction: Float) {
        val duration = playbackState.value.totalDurationMs
        if (duration > 0L) audioPlayer.seekTo((fraction.coerceIn(0f, 1f) * duration).toLong())
    }

    fun togglePlayback() {
        val source = _uiState.value.previewUri() ?: return
        if (loadedPlaybackUri == source && playbackState.value.totalDurationMs > 0L) {
            audioPlayer.togglePlayPause()
        } else {
            audioPlayer.clearClipping()
            audioPlayer.play(source)
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
        ) return
        val trim = state.trimState
        _uiState.update {
            it.copy(processStatus = ProcessStatus.CONVERTING, progress = 0f, errorMessage = null)
        }
        conversionJob?.cancel()
        conversionJob = viewModelScope.launch(Dispatchers.IO) {
            var cacheFile: java.io.File? = null
            try {
                val result = VoiceNoteConverter.convertToTelegramVoiceNote(
                    context = context,
                    inputUri = uri,
                    trimStartMs = if (trim.isActive) trim.startMs else 0L,
                    trimEndMs = if (trim.isActive) trim.endMs else Long.MAX_VALUE,
                    pitchSemitones = state.pitchSemitones,
                    onProgress = { progress -> _uiState.update { it.copy(progress = progress) } }
                )
                cacheFile = result.outputFile
                val outputName = VoiceNoteStorage.generateFileName()
                val savedUri = VoiceNoteStorage.saveToPublicStorage(
                    context,
                    result.outputFile,
                    outputName
                )
                val historyItem = ConversionHistory(
                        originalFileName = result.originalFileName,
                        outputFileName = outputName,
                        outputFilePath = savedUri.toString(),
                        durationSeconds = result.durationSeconds,
                        fileSizeBytes = result.outputFile.length(),
                        waveform = result.waveform.toString(),
                        bitrateKbps = result.bitrateKbps,
                        trimStartMs = trim.startMs.takeIf { trim.isActive },
                        trimEndMs = trim.endMs.takeIf { trim.isActive },
                        createdAt = System.currentTimeMillis(),
                        pitchSemitones = state.pitchSemitones.takeIf { it != 0f }
                    )
                val historyId = try {
                    history.insert(historyItem)
                } catch (error: Throwable) {
                    VoiceNoteStorage.deleteFromStorage(context, savedUri.toString())
                    throw error
                }
                currentCoroutineContext().ensureActive()
                if (_uiState.value.selectedFileUri != uri) return@launch
                _uiState.update {
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
                throw error
            } catch (error: Exception) {
                fail(error.message ?: "Gagal mengonversi audio", canRetry = true)
            } finally {
                cacheFile?.delete()
            }
        }
    }

    fun sendConverted() {
        val state = _uiState.value
        val uri = state.convertedUri ?: return
        _uiState.update { it.copy(processStatus = ProcessStatus.SENDING, errorMessage = null) }
        when (val result = telegramSender.sendVoiceNoteViaTelegramApp(context, uri, state.selectedContact?.name)) {
            is SendResult.IntentLaunched -> {
                _uiState.update { it.copy(processStatus = ProcessStatus.SENT, statusMessage = result.details) }
                viewModelScope.launch {
                    state.lastConversionId?.let { history.updateSendStatus(it, state.selectedContact?.name ?: "Telegram") }
                    state.selectedContact?.let { contacts.saveContactUsage(it.name, it.chatId, it.username, it.phone) }
                }
            }
            is SendResult.Failure -> fail(result.errorMessage, result.canRetry)
        }
    }

    fun resetForNewFile() {
        audioPlayer.stop()
        loadedPlaybackUri = null
        _uiState.value = MainUiState()
    }

    private fun fail(message: String, canRetry: Boolean) {
        _uiState.update { it.copy(processStatus = ProcessStatus.FAILED, errorMessage = message, canRetry = canRetry) }
    }

    override fun onCleared() {
        audioPlayer.release()
        super.onCleared()
    }

    companion object {
        fun formatDuration(seconds: Int): String = "%d:%02d".format(seconds / 60, seconds % 60)
    }
}
