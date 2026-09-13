package com.aistudio.voicenote.cvtr

import android.app.Application
import androidx.work.Configuration
import androidx.work.WorkManager
import com.aistudio.voicenote.cvtr.work.MaintenanceScheduler

class VoiceNoteApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            MaintenanceScheduler.schedule(this)
        } catch (_: IllegalStateException) {
            WorkManager.initialize(this, Configuration.Builder().build())
            MaintenanceScheduler.schedule(this)
        }
    }
}
