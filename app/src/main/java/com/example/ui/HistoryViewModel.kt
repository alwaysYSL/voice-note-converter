package com.example.ui

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.audio.AudioPreviewPlayer
import com.example.audio.VoiceNoteStorage
import com.example.data.local.AppDatabase
import com.example.data.local.ConversionHistory
import com.example.data.repository.ConversionHistoryRepository
import com.example.telegram.TelegramSender
import com.example.telegram.SendResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ConversionHistoryRepository(
        AppDatabase.getDatabase(app).conversionHistoryDao()
    )
    private val audioPlayer = AudioPreviewPlayer(app, viewModelScope)

    val historyItems = repository.allHistory.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )
    val playbackState = audioPlayer.playbackState
    val playbackProgress = playbackState.map { it.progress }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        0f
    )

    private val _currentlyPlayingId = MutableStateFlow<Long?>(null)
    val currentlyPlayingId = _currentlyPlayingId.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message = _message.asStateFlow()
    private val pendingDatabaseDeletion = mutableSetOf<Long>()

    fun playPause(item: ConversionHistory) {
        if (_currentlyPlayingId.value == item.id) {
            audioPlayer.togglePlayPause()
        } else {
            _currentlyPlayingId.value = item.id
            audioPlayer.play(item.outputFilePath.toUri())
        }
    }

    fun shareItem(item: ConversionHistory) {
        when (val result = TelegramSender().sendVoiceNoteViaTelegramApp(
            getApplication(),
            item.outputFilePath.toUri()
        )) {
            is SendResult.IntentLaunched -> {
                _message.value = result.details
                viewModelScope.launch {
                    repository.updateSendStatus(item.id, item.sentTo ?: "Telegram")
                }
            }
            is SendResult.Failure -> _message.value = result.errorMessage
        }
    }

    fun deleteItem(item: ConversionHistory) {
        viewModelScope.launch {
            val fileAlreadyRemoved = item.id in pendingDatabaseDeletion
            val storageDeleted = fileAlreadyRemoved || VoiceNoteStorage.deleteFromStorage(
                getApplication(),
                item.outputFilePath
            )
            val canProceed = storageDeleted || !VoiceNoteStorage.fileExists(
                getApplication(),
                item.outputFilePath
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

    fun clearMessage() {
        _message.value = null
    }

    fun pausePlayback() {
        audioPlayer.pause()
    }

    override fun onCleared() {
        audioPlayer.release()
    }
}
