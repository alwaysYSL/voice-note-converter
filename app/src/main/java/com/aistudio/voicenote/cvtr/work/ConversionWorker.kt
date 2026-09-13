package com.aistudio.voicenote.cvtr.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.aistudio.voicenote.cvtr.audio.ConversionErrorCode
import com.aistudio.voicenote.cvtr.audio.ConversionPipelineException
import com.aistudio.voicenote.cvtr.audio.ConversionStage
import com.aistudio.voicenote.cvtr.audio.MediaInputCache
import com.aistudio.voicenote.cvtr.audio.VoiceNoteConverter
import com.aistudio.voicenote.cvtr.audio.VoiceNoteStorage
import com.aistudio.voicenote.cvtr.audio.WaveformCodec
import com.aistudio.voicenote.cvtr.audio.toConversionFailureDetails
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.telegram.TelegramCompatibilityValidator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.consumeAsFlow
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
            ?: return failure(
                ConversionErrorCode.INPUT_UNREADABLE,
                ConversionStage.COPY_INPUT,
                "Tahap menyalin input: input media tidak tersedia. Pilih ulang file."
            )
        val sourceFileName = inputData.getString(ConversionWork.SOURCE_FILE_NAME)
        val inputFileName = sourceFileName ?: inputUri.lastPathSegment ?: "media"

        try {
            setForeground(createForegroundInfo(inputFileName))
        } catch (error: Throwable) {
            MediaInputCache.delete(applicationContext, inputUri)
            return reportFailure(
                ConversionPipelineException(
                    ConversionErrorCode.FOREGROUND_START_FAILED,
                    ConversionStage.START_FOREGROUND,
                    "Konversi tidak dapat berjalan di latar belakang. Buka aplikasi lalu coba lagi.",
                    error
                )
            )
        }

        return try {
            ConversionCoordinator.runExclusive {
                executeConversion(inputUri, sourceFileName)
            }
        } finally {
            MediaInputCache.delete(applicationContext, inputUri)
        }
    }

    private suspend fun executeConversion(
        inputUri: android.net.Uri,
        sourceFileName: String?
    ): Result {
        val requestedOutputName = inputData.getString(ConversionWork.OUTPUT_FILE_NAME)
        val trimStartMs = inputData.getLong(ConversionWork.TRIM_START_MS, 0L)
        val trimEndMs = inputData.getLong(ConversionWork.TRIM_END_MS, Long.MAX_VALUE)
        val pitchSemitones = inputData.getFloat(ConversionWork.PITCH_SEMITONES, 0f)
        val options = ConversionWork.options(inputData)
        var stage = ConversionStage.OPEN_EXTRACTOR
        var result: com.aistudio.voicenote.cvtr.audio.AudioConversionResult? = null
        var savedUri: android.net.Uri? = null
        var historyId: Long? = null

        return try {
            val converted = coroutineScope {
                val progressChannel = Channel<Float>(Channel.CONFLATED)
                val progressReporter = launch {
                    progressChannel.consumeAsFlow().collect { progress ->
                        setProgress(workDataOf(ConversionWork.PROGRESS to progress))
                    }
                }
                try {
                    stage = ConversionStage.DECODE
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
            stage = ConversionStage.WRITE_OGG
            val compatibility = TelegramCompatibilityValidator.validate(converted)
            if (!compatibility.isShareable) {
                throw ConversionPipelineException(
                    ConversionErrorCode.OUTPUT_INVALID,
                    ConversionStage.WRITE_OGG,
                    compatibility.warning ?: "Output tidak kompatibel dengan Telegram."
                )
            }
            val outputName = VoiceNoteStorage.resolveOutputFileName(
                context = applicationContext,
                requestedName = requestedOutputName ?: sourceFileName ?: converted.originalFileName
            )
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            stage = ConversionStage.SAVE_MEDIASTORE
            val publicUri = try {
                VoiceNoteStorage.saveToPublicStorage(
                    applicationContext,
                    converted.outputFile,
                    outputName
                )
            } catch (error: Throwable) {
                throw ConversionPipelineException(
                    ConversionErrorCode.STORAGE_WRITE_FAILED,
                    ConversionStage.SAVE_MEDIASTORE,
                    "Hasil tidak dapat disimpan. Periksa ruang penyimpanan lalu coba lagi.",
                    error
                )
            }
            savedUri = publicUri
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            stage = ConversionStage.INSERT_HISTORY
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
                    .putString(ConversionWork.ENCODER_BACKEND, converted.encoderBackend)
                    .build()
            )
        } catch (error: CancellationException) {
            rollback(historyId, savedUri)
            throw error
        } catch (error: Throwable) {
            rollback(historyId, savedUri)
            reportFailure(error, stage)
        } finally {
            result?.outputFile?.delete()
            MediaInputCache.delete(applicationContext, inputUri)
        }
    }

    private suspend fun rollback(historyId: Long?, savedUri: android.net.Uri?) {
        withContext(NonCancellable) {
            historyId?.let { database.conversionHistoryDao().deleteById(it) }
            savedUri?.let { VoiceNoteStorage.deleteFromStorage(applicationContext, it.toString()) }
        }
    }

    private fun reportFailure(
        error: Throwable,
        fallbackStage: ConversionStage = ConversionStage.UNKNOWN
    ): Result {
        val details = error.toConversionFailureDetails(fallbackStage)
        Log.e(
            TAG,
            "Conversion failed code=${details.code}, stage=${details.stage}, " +
                "api=${Build.VERSION.SDK_INT}, abi=${Build.SUPPORTED_ABIS.joinToString()}",
            error
        )
        return failure(
            details.code,
            details.stage,
            "Tahap ${stageLabel(details.stage)}: ${details.message}"
        )
    }

    private fun failure(
        code: ConversionErrorCode,
        stage: ConversionStage,
        message: String
    ): Result {
        val data = workDataOf(
            ConversionWork.ERROR_MESSAGE to message,
            ConversionWork.ERROR_CODE to code.name,
            ConversionWork.ERROR_STAGE to stage.name,
            ConversionWork.CAN_RETRY to (code == ConversionErrorCode.STORAGE_WRITE_FAILED),
            ConversionWork.LOGICAL_FAILURE to true
        )
        return if (inputData.getString(ConversionWork.BATCH_ITEM_ID) != null) {
            Result.success(data)
        } else {
            Result.failure(data)
        }
    }

    private fun stageLabel(stage: ConversionStage): String = when (stage) {
        ConversionStage.COPY_INPUT -> "menyalin input"
        ConversionStage.START_FOREGROUND -> "memulai proses latar"
        ConversionStage.OPEN_EXTRACTOR -> "membuka media"
        ConversionStage.CREATE_DECODER -> "menyiapkan decoder"
        ConversionStage.CREATE_ENCODER -> "menyiapkan encoder"
        ConversionStage.DECODE -> "decoding"
        ConversionStage.ENCODE -> "encoding"
        ConversionStage.WRITE_OGG -> "menulis OGG"
        ConversionStage.SAVE_MEDIASTORE -> "menyimpan hasil"
        ConversionStage.INSERT_HISTORY -> "menyimpan riwayat"
        ConversionStage.UNKNOWN -> "konversi"
    }

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
        val notificationId = (id.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        return when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
            else -> ForegroundInfo(notificationId, notification)
        }
    }

    private companion object {
        const val TAG = "ConversionWorker"
        const val CHANNEL_ID = "voice_note_conversion"
    }
}
