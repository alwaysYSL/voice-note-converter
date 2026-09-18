package com.aistudio.voicenote.cvtr.ui

import android.app.Application
import androidx.lifecycle.viewmodel.CreationExtras
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.aistudio.voicenote.cvtr.audio.AudioPreviewPlayer
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.repository.ConversionHistoryRepository
import com.aistudio.voicenote.cvtr.telegram.TelegramSender
import kotlinx.coroutines.CoroutineScope

class AppViewModelFactory(
    private val application: Application,
    private val historyRepository: ConversionHistoryRepository = ConversionHistoryRepository(
        AppDatabase.getDatabase(application).conversionHistoryDao()
    ),
    private val telegramSender: TelegramSender = TelegramSender(),
    private val audioPlayerFactory: (android.content.Context, CoroutineScope) -> AudioPreviewPlayer =
        ::AudioPreviewPlayer
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(
        modelClass: Class<T>,
        extras: CreationExtras
    ): T = when {
        modelClass.isAssignableFrom(MainViewModel::class.java) -> MainViewModel(
            application = application,
            savedStateHandle = extras.createSavedStateHandle(),
            history = historyRepository,
            telegramSender = telegramSender,
            audioPlayerFactory = audioPlayerFactory
        ) as T

        modelClass.isAssignableFrom(HistoryViewModel::class.java) -> HistoryViewModel(
            app = application,
            repository = historyRepository,
            telegramSender = telegramSender,
            audioPlayerFactory = audioPlayerFactory
        ) as T

        modelClass.isAssignableFrom(com.aistudio.voicenote.cvtr.editor.ui.EditorViewModel::class.java) -> com.aistudio.voicenote.cvtr.editor.ui.EditorViewModel(
            application = application,
            historyRepository = historyRepository
        ) as T

        else -> throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
