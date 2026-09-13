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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

class HistoryViewModel(app: Application) : AndroidViewModel(app) {
    private val repository = ConversionHistoryRepository(
        AppDatabase.getDatabase(app).conversionHistoryDao()
    )
    private val audioPlayer = AudioPreviewPlayer(app, viewModelScope)

    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _historyFilter = MutableStateFlow(HistoryFilter.ALL)
    val historyFilter = _historyFilter.asStateFlow()
    private val _historySort = MutableStateFlow(HistorySort.NEWEST)
    val historySort = _historySort.asStateFlow()

    val historyItems = combine(
        _searchQuery,
        _historyFilter,
        _historySort,
        repository.allHistory
    ) { query, filter, sort, items ->
        filterAndSortHistory(items, query, filter, sort)
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        emptyList()
    )
    val totalHistoryCount = repository.allHistory.map { it.size }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        0
    )
    val totalHistoryBytes = repository.allHistory.map { items ->
        items.sumOf { it.fileSizeBytes }
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        0L
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

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    fun setFilter(filter: HistoryFilter) {
        _historyFilter.value = filter
    }

    fun setSort(sort: HistorySort) {
        _historySort.value = sort
    }

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
            deleteItemInternal(item)
        }
    }

    fun deleteItems(items: List<ConversionHistory>) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            var deletedCount = 0
            var failedCount = 0
            items.distinctBy { it.id }.forEach { item ->
                if (deleteItemInternal(item)) deletedCount++ else failedCount++
            }
            _message.value = when {
                failedCount == 0 -> "$deletedCount file dihapus."
                deletedCount == 0 -> "File tidak dapat dihapus; entri riwayat tetap disimpan."
                else -> "$deletedCount file dihapus; $failedCount file gagal dihapus."
            }
        }
    }

    private suspend fun deleteItemInternal(item: ConversionHistory): Boolean {
        val fileAlreadyRemoved = item.id in pendingDatabaseDeletion
        val result = withContext(Dispatchers.IO) {
            val storageDeleted = fileAlreadyRemoved || VoiceNoteStorage.deleteFromStorage(
                getApplication(),
                item.outputFilePath
            )
            val canProceed = storageDeleted || !VoiceNoteStorage.fileExists(
                getApplication(),
                item.outputFilePath
            )
            if (!canProceed) {
                return@withContext DeleteResult(
                    deleted = false,
                    message = "File tidak dapat dihapus; entri riwayat tetap disimpan."
                )
            }

            try {
                repository.delete(item.id)
                DeleteResult(deleted = true)
            } catch (error: Exception) {
                DeleteResult(
                    deleted = false,
                    markDatabaseDeletionPending = true,
                    message = "File sudah dihapus, tetapi riwayat belum terhapus. Coba hapus lagi."
                )
            }
        }
        if (result.deleted) {
            pendingDatabaseDeletion.remove(item.id)
            if (_currentlyPlayingId.value == item.id) {
                audioPlayer.stop()
                _currentlyPlayingId.value = null
            }
        } else if (result.markDatabaseDeletionPending) {
            pendingDatabaseDeletion += item.id
        }
        result.message?.let { _message.value = it }
        return result.deleted
    }

    private data class DeleteResult(
        val deleted: Boolean,
        val markDatabaseDeletionPending: Boolean = false,
        val message: String? = null
    )

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
