package com.aistudio.voicenote.cvtr.ui

import android.app.Application
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.aistudio.voicenote.cvtr.audio.AudioPreviewPlayer
import com.aistudio.voicenote.cvtr.audio.VoiceNoteStorage
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.data.repository.ConversionHistoryRepository
import com.aistudio.voicenote.cvtr.telegram.SendResult
import com.aistudio.voicenote.cvtr.telegram.TelegramSender
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(
    kotlinx.coroutines.FlowPreview::class,
    kotlinx.coroutines.ExperimentalCoroutinesApi::class
)
class HistoryViewModel(
    app: Application,
    private val repository: ConversionHistoryRepository = ConversionHistoryRepository(
        AppDatabase.getDatabase(app).conversionHistoryDao()
    ),
    private val telegramSender: TelegramSender = TelegramSender(),
    audioPlayerFactory: (android.content.Context, CoroutineScope) -> AudioPreviewPlayer =
        ::AudioPreviewPlayer
) : AndroidViewModel(app) {
    private val _searchQuery = MutableStateFlow("")
    val searchQuery = _searchQuery.asStateFlow()
    private val _historyFilter = MutableStateFlow(HistoryFilter.ALL)
    val historyFilter = _historyFilter.asStateFlow()
    private val _historySort = MutableStateFlow(HistorySort.NEWEST)
    val historySort = _historySort.asStateFlow()

    private val debouncedQuery = merge(
        flowOf(_searchQuery.value.trim()),
        _searchQuery
            .map(String::trim)
            .debounce(250)
    ).distinctUntilChanged().flowOn(Dispatchers.Default)

    val historyItems: Flow<PagingData<ConversionHistory>> = combine(
        debouncedQuery,
        _historyFilter,
        _historySort
    ) { query, filter, sort ->
        Triple(query, filter.databaseValue(), sort.databaseValue())
    }.flatMapLatest { (query, filter, sort) ->
        Pager(
            config = PagingConfig(
                pageSize = 40,
                initialLoadSize = 40,
                prefetchDistance = 10,
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                repository.pagedHistory(query, filter, sort)
            }
        ).flow
    }.cachedIn(viewModelScope)

    private val historySummary = repository.observeSummary("", "ALL").stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        com.aistudio.voicenote.cvtr.data.local.HistorySummary(count = 0, totalBytes = 0L)
    )
    val totalHistoryCount = historySummary.map { it.count }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        0
    )
    val totalHistoryBytes = historySummary.map { it.totalBytes }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        0L
    )

    private val audioPlayer = audioPlayerFactory(getApplication(), viewModelScope)
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
        viewModelScope.launch {
            when (val result = withContext(Dispatchers.IO) {
                telegramSender.sendVoiceNoteViaTelegramApp(
                    getApplication(),
                    item.outputFilePath.toUri()
                )
            }) {
                is SendResult.IntentLaunched -> {
                    withContext(Dispatchers.IO) {
                        repository.markShareOpened(item.id, "Telegram")
                    }
                    _message.value = result.details
                }
                is SendResult.Failure -> _message.value = result.errorMessage
            }
        }
    }

    fun confirmSent(item: ConversionHistory) {
        viewModelScope.launch(Dispatchers.IO) {
            _message.value = if (repository.confirmSent(item.id) > 0) {
                "Ditandai sudah dikirim."
            } else {
                "Buka Telegram terlebih dahulu sebelum mengonfirmasi pengiriman."
            }
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
    fun cleanupOlderThan(days: Int = 30) {
        val safeDays = days.coerceIn(1, 3_650)
        viewModelScope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - safeDays * 24L * 60L * 60L * 1_000L
            val candidates = repository.getCreatedBefore(cutoff)
            var deletedCount = 0
            var failedCount = 0
            candidates.forEach { item ->
                if (deleteItemInternal(item)) deletedCount++ else failedCount++
            }
            _message.value = when {
                candidates.isEmpty() -> "Tidak ada hasil lebih lama dari $safeDays hari."
                failedCount == 0 -> "$deletedCount hasil lama dihapus."
                deletedCount == 0 -> "Hasil lama tidak dapat dihapus."
                else -> "$deletedCount hasil lama dihapus; $failedCount gagal."
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
        super.onCleared()
    }
}
