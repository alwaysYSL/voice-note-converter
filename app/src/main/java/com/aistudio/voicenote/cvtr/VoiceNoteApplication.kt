package com.aistudio.voicenote.cvtr

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.aistudio.voicenote.cvtr.work.MaintenanceScheduler
import com.aistudio.voicenote.cvtr.editor.work.EditorMaintenanceScheduler
import com.aistudio.voicenote.cvtr.ui.BatchQueueStore

class VoiceNoteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        BatchQueueStore(this).clear()
        try {
            MaintenanceScheduler.schedule(this)
            EditorMaintenanceScheduler.schedule(this)
        } catch (_: IllegalStateException) {
            WorkManager.initialize(this, Configuration.Builder().build())
            MaintenanceScheduler.schedule(this)
            EditorMaintenanceScheduler.schedule(this)
        }
    }
}
