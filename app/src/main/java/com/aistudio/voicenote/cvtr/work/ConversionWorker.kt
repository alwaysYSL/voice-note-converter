package com.aistudio.voicenote.cvtr.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.pm.ServiceInfo
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aistudio.voicenote.cvtr.audio.MediaInputCache
import com.aistudio.voicenote.cvtr.audio.VoiceNoteConverter
import com.aistudio.voicenote.cvtr.audio.VoiceNoteStorage
import com.aistudio.voicenote.cvtr.audio.WaveformCodec
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.telegram.TelegramCompatibilityValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ConversionWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    private val database = AppDatabase.getDatabase(appContext)

    override suspend fun doWork(): Result {
        val inputUri = inputData.getString(ConversionWork.INPUT_URI)
            ?.let(android.net.Uri::parse)
            ?: return failure("Input media tidak tersedia.")
        val sourceFileName = inputData.getString(ConversionWork.SOURCE_FILE_NAME)
        val requestedOutputName = inputData.getString(ConversionWork.OUTPUT_FILE_NAME)
        val trimStartMs = inputData.getLong(ConversionWork.TRIM_START_MS, 0L)
        val trimEndMs = inputData.getLong(ConversionWork.TRIM_END_MS, Long.MAX_VALUE)
        val pitchSemitones = inputData.getFloat(ConversionWork.PITCH_SEMITONES, 0f)
        val options = ConversionWork.options(inputData)
        val inputFileName = sourceFileName ?: inputUri.lastPathSegment ?: "media"
        setForeground(createForegroundInfo(inputFileName))

        var result: com.aistudio.voicenote.cvtr.audio.AudioConversionResult? = null
        var savedUri: android.net.Uri? = null
        var historyId: Long? = null
        var completed = false
        return try {
            val converted = coroutineScope {
                val progressChannel = Channel<Float>(Channel.CONFLATED)
                val progressReporter = launch {
                    progressChannel.consumeAsFlow().collect { progress ->
                        setProgress(workDataOf(ConversionWork.PROGRESS to progress))
                    }
                }
                try {
                    VoiceNoteConverter.convertToTelegramVoiceNote(
                        context = applicationContext,
                        inputUri = inputUri,
                        trimStartMs = trimStartMs,
                        trimEndMs = trimEndMs,
                        pitchSemitones = pitchSemitones,
                        processingOptions = options,
                        onProgress = { progress -> progressChannel.trySend(progress) }
                    )
                } finally {
                    progressChannel.close()
                    progressReporter.join()
                }
            }
            result = converted
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val compatibility = TelegramCompatibilityValidator.validate(converted)
            check(compatibility.isShareable) {
                compatibility.warning ?: "Output tidak kompatibel dengan Telegram."
            }
            val outputName = VoiceNoteStorage.resolveOutputFileName(
                context = applicationContext,
                requestedName = requestedOutputName ?: sourceFileName ?: converted.originalFileName
            )
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val publicUri = VoiceNoteStorage.saveToPublicStorage(
                applicationContext,
                converted.outputFile,
                outputName
            )
            savedUri = publicUri
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val insertedHistoryId = database.conversionHistoryDao().insert(
                ConversionHistory(
                    originalFileName = sourceFileName ?: converted.originalFileName,
                    outputFileName = outputName,
                    outputFilePath = publicUri.toString(),
                    durationSeconds = converted.durationSeconds,
                    fileSizeBytes = converted.outputFile.length(),
                    waveform = WaveformCodec.encode(converted.waveform),
                    bitrateKbps = converted.bitrateKbps,
                    trimStartMs = trimStartMs.takeIf { it > 0L },
                    trimEndMs = trimEndMs.takeIf { it != Long.MAX_VALUE },
                    createdAt = System.currentTimeMillis(),
                    pitchSemitones = pitchSemitones.takeIf { it != 0f }
                )
            )
            historyId = insertedHistoryId
            setProgress(workDataOf(ConversionWork.PROGRESS to 1f))
            completed = true
            Result.success(
                Data.Builder()
                    .putString(ConversionWork.RESULT_URI, publicUri.toString())
                    .putLong(ConversionWork.RESULT_HISTORY_ID, insertedHistoryId)
                    .putString(ConversionWork.RESULT_OUTPUT_FILE_NAME, outputName)
                    .putInt(ConversionWork.RESULT_DURATION_SECONDS, converted.durationSeconds)
                    .putString(ConversionWork.RESULT_WAVEFORM, WaveformCodec.encode(converted.waveform))
                    .putInt(ConversionWork.RESULT_BITRATE_KBPS, converted.bitrateKbps)
                    .putString(ConversionWork.RESULT_COMPATIBILITY_SUMMARY, compatibility.summary)
                    .putString(ConversionWork.RESULT_COMPATIBILITY_WARNING, compatibility.warning)
                    .build()
            )
        } catch (error: CancellationException) {
            rollback(historyId, savedUri)
            throw error
        } catch (error: Throwable) {
            rollback(historyId, savedUri)
            failure(error.message ?: "Gagal mengonversi audio.")
        } finally {
            result?.outputFile?.delete()
            if (completed) MediaInputCache.delete(applicationContext, inputUri)
        }
    }

    private suspend fun rollback(historyId: Long?, savedUri: android.net.Uri?) {
        withContext(NonCancellable) {
            historyId?.let { database.conversionHistoryDao().deleteById(it) }
            savedUri?.let { VoiceNoteStorage.deleteFromStorage(applicationContext, it.toString()) }
        }
    }

    private fun failure(message: String): Result = Result.failure(
        workDataOf(ConversionWork.ERROR_MESSAGE to message)
    )

    private fun createForegroundInfo(fileName: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Konversi voice note",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Mengonversi voice note")
            .setContentText(fileName)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private companion object {
        const val CHANNEL_ID = "voice_note_conversion"
        const val NOTIFICATION_ID = 1001
    }
}
