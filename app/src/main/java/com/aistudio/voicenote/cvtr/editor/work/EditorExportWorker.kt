package com.aistudio.voicenote.cvtr.editor.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentValues
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.content.pm.ServiceInfo
import android.os.Build
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import androidx.core.app.NotificationCompat
import com.aistudio.voicenote.cvtr.editor.audio.DefaultTimelineRenderer
import com.aistudio.voicenote.cvtr.editor.audio.EditorRenderManifest
import com.aistudio.voicenote.cvtr.editor.audio.MediaCodecPcmSourceReaderFactory
import com.aistudio.voicenote.cvtr.audio.OggOpusWriter
import com.aistudio.voicenote.cvtr.audio.SoftwareOpusEncoder
import com.aistudio.voicenote.cvtr.editor.audio.TimelineRenderer
import com.aistudio.voicenote.cvtr.audio.VoiceNoteStorage
import com.aistudio.voicenote.cvtr.audio.WaveformCodec
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.editor.model.ExportPreset
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

private fun Throwable.rethrowIfFatal() {
    if (this is CancellationException || this is VirtualMachineError || this is ThreadDeath || this is LinkageError) {
        throw this
    }
}

/** Small seam around an encoder so export tests never need to load native libopus. */
internal interface EditorExportEncoder : AutoCloseable {
    fun write(samples: ShortArray)
    fun finish()
    override fun close()
}

internal fun interface EditorExportEncoderFactory {
    fun open(output: OutputStream, preset: ExportPreset, checkActive: () -> Unit): EditorExportEncoder
}

internal interface EditorExportStorage {
    fun createPartialFile(workId: String): File
    fun publish(partialFile: File, outputName: String): Uri
    fun publishReserved(partialFile: File, outputName: String, outputUri: String?): Uri =
        publish(partialFile, outputName)
    /** Makes an app-owned reserved target visible; safe to call after it is already visible. */
    fun finalizeReserved(uri: Uri): Boolean = true
    fun validatePublished(uri: Uri): Boolean
    fun sizeBytes(uri: Uri): Long = 0L
    fun deletePublished(uri: Uri): Boolean
    fun deletePartial(file: File): Boolean

    fun resolveOutputName(requestedName: String?, manifest: EditorRenderManifest): String =
        safeExportName(requestedName, manifest)
}

internal data class EditorExportOutputIdentity(
    val name: String,
    val uri: String,
)

internal interface EditorExportReservationStore {
    fun reserve(manifest: EditorRenderManifest, requestedName: String?): EditorExportOutputIdentity
    fun release(identity: EditorExportOutputIdentity): Boolean
}

internal interface EditorExportHistory {
    suspend fun insert(item: ConversionHistory): Long
    suspend fun delete(id: Long)

    suspend fun findByExportAttemptId(attemptId: String): ConversionHistory? = null

    /** Durable idempotent insert. The unique attempt id makes a process retry converge. */
    suspend fun commit(item: ConversionHistory): EditorExportCommit {
        findByExportAttemptId(item.editorExportAttemptId ?: return EditorExportCommit(insert(item), true))
            ?.let { return EditorExportCommit(it.id, inserted = false) }
        return try {
            EditorExportCommit(insert(item), inserted = true)
        } catch (error: Throwable) {
            error.rethrowIfFatal()
            findByExportAttemptId(item.editorExportAttemptId)?.let {
                EditorExportCommit(it.id, inserted = false)
            } ?: throw error
        }
    }
}

internal data class EditorExportCommit(val id: Long, val inserted: Boolean)

internal sealed interface EditorExportResult {
    data class Success(
        val uri: Uri,
        val historyId: Long,
        val outputName: String,
        val durationSeconds: Int,
        val bitrateKbps: Int,
        val cleanupWarning: String? = null,
    ) : EditorExportResult

    data class Failure(
        val message: String,
        val canRetry: Boolean = true,
        val cleanupWarning: String? = null,
    ) : EditorExportResult

    data class Cancelled(val cleanupWarning: String? = null) : EditorExportResult
}

/**
 * Transactional, bounded-chunk export pipeline. It is separate from WorkManager solely to keep
 * all resource and rollback behavior deterministic in unit tests.
 */
internal class EditorExportRunner(
    private val rendererFactory: (EditorRenderManifest) -> TimelineRenderer,
    private val encoderFactory: EditorExportEncoderFactory,
    private val storage: EditorExportStorage,
    private val history: EditorExportHistory,
    private val reservationStore: EditorExportReservationStore? = null,
    private val workId: String = UUID.randomUUID().toString(),
) {
    suspend fun run(
        manifest: EditorRenderManifest,
        requestedOutputName: String?,
        preset: ExportPreset,
        onProgress: suspend (completedFrames: Long, totalFrames: Long) -> Unit = { _, _ -> },
        isActive: () -> Boolean = { true },
    ): EditorExportResult {
        if (manifest.preset != preset) {
            return EditorExportResult.Failure(
                message = "Manifest preset does not match export request",
                canRetry = false,
            )
        }
        if (manifest.timelineDurationFrames <= 0L || manifest.tracks.none { it.clips.isNotEmpty() }) {
            return EditorExportResult.Failure(
                message = "Editor timeline contains no rendered audio",
                canRetry = false,
            )
        }

        val existing = try {
            history.findByExportAttemptId(manifest.exportAttemptId)
        } catch (error: CancellationException) {
            return EditorExportResult.Cancelled()
        } catch (error: Throwable) {
            error.rethrowIfFatal()
            return EditorExportResult.Failure(error.message ?: "Could not inspect export history")
        }
        if (existing != null) {
            return existingResult(existing)
        }
        val workingManifest = try {
            if (manifest.reservedOutputName != null && manifest.reservedOutputUri != null) {
                manifest
            } else {
                reservationStore?.reserve(manifest, requestedOutputName)?.let { identity ->
                    manifest.copy(
                        reservedOutputName = identity.name,
                        reservedOutputUri = identity.uri,
                    )
                } ?: manifest
            }
        } catch (error: CancellationException) {
            return EditorExportResult.Cancelled()
        } catch (error: Throwable) {
            error.rethrowIfFatal()
            return EditorExportResult.Failure(error.message ?: "Could not reserve editor output")
        }
        return runReserved(workingManifest, requestedOutputName, preset, onProgress, isActive)
    }

    private suspend fun runReserved(
        manifest: EditorRenderManifest,
        requestedOutputName: String?,
        preset: ExportPreset,
        onProgress: suspend (completedFrames: Long, totalFrames: Long) -> Unit,
        isActive: () -> Boolean,
    ): EditorExportResult {
        val reservedUri = manifest.reservedOutputUri?.let(Uri::parse)
        var recoveredUri: Uri? = null
        var renderer: TimelineRenderer? = null
        var partialFile: File? = null
        var publishedUri: Uri? = reservedUri
        var historyId: Long? = null
        var encoder: EditorExportEncoder? = null
        var finished = false
        var historyCreatedByRun = false
        var cleanupWarning: String? = null
        var outcome: EditorExportResult? = null

        fun checkActive() {
            if (!isActive()) throw CancellationException("Editor export cancelled")
        }

        try {
            checkActive()
            recoveredUri = reservedUri?.let { uri ->
                if (!storage.validatePublished(uri)) {
                    null
                } else {
                    check(storage.finalizeReserved(uri)) { "Reserved editor output could not be finalized" }
                    uri
                }
            }
            renderer = if (recoveredUri == null) rendererFactory(manifest) else null
            val outputName = manifest.reservedOutputName
                ?: storage.resolveOutputName(requestedOutputName, manifest)
            if (recoveredUri != null) {
                // A prior process published the reserved target but died before history commit.
                // Recreate the deterministic partial handle only to remove any leftover cache.
                partialFile = storage.createPartialFile(workId)
                publishedUri = recoveredUri
            } else {
                partialFile = storage.createPartialFile(workId)
                // Own the reserved identity throughout rendering, so cancellation or encoder
                // failure can remove a pending MediaStore/file reservation as well.
                publishedUri = reservedUri
                FileOutputStream(partialFile).use { output ->
                    encoder = encoderFactory.open(output, preset, ::checkActive)
                    try {
                        var completed = 0L
                        val total = manifest.timelineDurationFrames
                        while (completed < total) {
                            checkActive()
                            val count = minOf(RENDER_CHUNK_FRAMES.toLong(), total - completed).toInt()
                            val pcm = renderer?.render(manifest.renderSession, completed, count)
                                ?: error("Editor renderer is unavailable")
                            check(pcm.size == count) { "Renderer returned ${pcm.size} frames for $count" }
                            currentCoroutineContext().ensureActive()
                            encoder?.write(pcm)
                            completed += count
                            onProgress(completed, total)
                        }
                        checkActive()
                        encoder?.finish()
                        finished = true
                    } finally {
                        if (!finished) encoder?.close()
                        encoder = null
                    }
                }
            }
            checkActive()
            if (recoveredUri == null) {
                publishedUri = storage.publishReserved(
                    partialFile ?: error("Editor partial output is unavailable"),
                    outputName,
                    manifest.reservedOutputUri,
                )
            }
            checkActive()
            val published = publishedUri ?: error("Editor output is unavailable")
            check(storage.validatePublished(published)) { "Published OGG metadata is invalid" }
            checkActive()
            val durationSeconds = ceil(manifest.timelineDurationFrames / SAMPLE_RATE.toDouble())
                .toInt()
            val item = ConversionHistory(
                originalFileName = manifest.sourceFileName,
                outputFileName = outputName,
                outputFilePath = published.toString(),
                durationSeconds = durationSeconds,
                fileSizeBytes = storage.sizeBytes(published),
                waveform = WaveformCodec.encode(emptyList()),
                bitrateKbps = preset.bitrateKbps,
                createdAt = System.currentTimeMillis(),
                editorSourceHistoryId = manifest.sourceHistoryId,
                editorExportAttemptId = manifest.exportAttemptId,
            )
            val committed = withContext(NonCancellable) {
                val commit = history.commit(item)
                // For a successful insert the DAO id is the durable capture; querying again
                // would reopen a cancellation/process window after the row is committed.
                val row = if (commit.inserted) {
                    item.copy(id = commit.id)
                } else {
                    history.findByExportAttemptId(manifest.exportAttemptId)
                        ?: error("Committed editor export history row is missing")
                }
                commit to row
            }
            historyId = committed.first.id
            historyCreatedByRun = committed.first.inserted
            checkActive()
            val committedRow = committed.second
            val resultUri = Uri.parse(committedRow.outputFilePath)
            if (!committed.first.inserted) {
                // A concurrent/process retry won the durable commit. Preserve its output and
                // history row; only this run's output is eligible for cleanup.
                check(storage.validatePublished(resultUri)) { "Existing export output is invalid" }
                if (publishedUri != resultUri) {
                    publishedUri?.let { ownUri ->
                        cleanupWarning = cleanupWarning ?: safeDeletePublished(ownUri)
                    }
                    publishedUri = null
                }
            }
            outcome = EditorExportResult.Success(
                uri = resultUri,
                historyId = committedRow.id,
                outputName = committedRow.outputFileName,
                durationSeconds = committedRow.durationSeconds,
                bitrateKbps = committedRow.bitrateKbps,
                cleanupWarning = cleanupWarning,
            )
        } catch (error: CancellationException) {
            cleanupWarning = rollback(partialFile, publishedUri, historyId, historyCreatedByRun)
            outcome = EditorExportResult.Cancelled(cleanupWarning)
        } catch (error: Throwable) {
            error.rethrowIfFatal()
            cleanupWarning = rollback(partialFile, publishedUri, historyId, historyCreatedByRun)
            outcome = EditorExportResult.Failure(
                message = error.message ?: "Editor export failed",
                canRetry = true,
                cleanupWarning = cleanupWarning,
            )
        } finally {
            encoder?.let { cleanupWarning = cleanupWarning ?: safeClose(it, "encoder") }
            renderer?.let { cleanupWarning = cleanupWarning ?: safeClose(it, "renderer") }
            if (outcome != null) {
                if (outcome is EditorExportResult.Success) {
                    partialFile?.let {
                        cleanupWarning = cleanupWarning ?: safeDeletePartial(it)
                    }
                }
                outcome = when (val result = outcome) {
                    is EditorExportResult.Success -> result.copy(cleanupWarning = cleanupWarning)
                    is EditorExportResult.Failure -> result.copy(cleanupWarning = cleanupWarning)
                    is EditorExportResult.Cancelled -> result.copy(cleanupWarning = cleanupWarning)
                    null -> null
                }
            }
        }
        return outcome ?: EditorExportResult.Failure("Editor export did not produce a result")
    }

    private suspend fun rollback(
        partial: File?,
        published: Uri?,
        historyId: Long?,
        historyCreatedByRun: Boolean,
    ): String? =
        withContext(NonCancellable) {
            val warnings = ArrayList<String>(3)
            var historyDeleted = true
            if (historyCreatedByRun) {
                historyId?.let {
                    safeDeleteHistory(it)?.let {
                        warnings += it
                        historyDeleted = false
                    }
                }
            }
            if (historyDeleted) {
                published?.let {
                    cleanupWarning(storage.deletePublished(it), "published output cleanup failed")?.let(warnings::add)
                }
            } else if (published != null) {
                warnings += "published output retained because history cleanup failed"
            }
            partial?.let {
                cleanupWarning(storage.deletePartial(it), "partial output cleanup failed")?.let(warnings::add)
            }
            warnings.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }

    private fun existingResult(row: ConversionHistory): EditorExportResult {
        val uri = Uri.parse(row.outputFilePath)
        return try {
            check(storage.validatePublished(uri)) { "Existing export output is invalid" }
            EditorExportResult.Success(
                uri = uri,
                historyId = row.id,
                outputName = row.outputFileName,
                durationSeconds = row.durationSeconds,
                bitrateKbps = row.bitrateKbps,
            )
        } catch (error: CancellationException) {
            EditorExportResult.Cancelled()
        } catch (error: Throwable) {
            error.rethrowIfFatal()
            EditorExportResult.Failure(error.message ?: "Existing export output is invalid")
        }
    }

    private fun safeDeletePublished(uri: Uri): String? =
        cleanupWarning(storage.deletePublished(uri), "published output cleanup failed")

    private fun safeDeletePartial(file: File): String? =
        cleanupWarning(storage.deletePartial(file), "Export partial cleanup could not be confirmed")

    private fun safeClose(resource: AutoCloseable, label: String): String? = try {
        resource.close()
        null
    } catch (error: Throwable) {
        error.rethrowIfFatal()
        "$label cleanup failed"
    }

    private fun cleanupWarning(success: Boolean, message: String): String? =
        if (success) null else message

    private suspend fun safeDeleteHistory(id: Long): String? = try {
        history.delete(id)
        null
    } catch (error: Throwable) {
        error.rethrowIfFatal()
        "history cleanup failed"
    }

    private companion object {
        const val RENDER_CHUNK_FRAMES = 960
        const val SAMPLE_RATE = 48_000L
    }
}

internal class EditorExportWorker(
    appContext: Context,
    workerParams: WorkerParameters,
    private val dependencies: Dependencies = Dependencies.production(appContext),
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val manifestPath = inputData.getString(EditorExportWork.MANIFEST_PATH)
            ?: return failure("Editor manifest is missing", canRetry = false)
        val file = File(manifestPath)
        val manifest = try {
            EditorRenderManifest.readValidated(file)
        } catch (error: Throwable) {
            error.rethrowIfFatal()
            file.delete()
            return failure(error.message ?: "Editor manifest is invalid", canRetry = false)
        }
        val requestedAttemptId = inputData.getString(EditorExportWork.EXPORT_ATTEMPT_ID)
        if (requestedAttemptId != null && requestedAttemptId != manifest.exportAttemptId) {
            file.delete()
            return failure("Editor export attempt does not match its manifest", canRetry = false)
        }
        val requestedOutputUri = inputData.getString(EditorExportWork.OUTPUT_URI)
        if (manifest.reservedOutputUri != null && requestedOutputUri != manifest.reservedOutputUri) {
            file.delete()
            return failure("Editor output identity does not match its manifest", canRetry = false)
        }
        val requestedOutputName = inputData.getString(EditorExportWork.OUTPUT_NAME)
        if (manifest.reservedOutputName != null && requestedOutputName != manifest.reservedOutputName) {
            file.delete()
            return failure("Editor output name does not match its manifest", canRetry = false)
        }
        val requestedPreset = EditorExportWork.preset(inputData)
        try {
            setForeground(createForegroundInfo(inputData.getString(EditorExportWork.OUTPUT_NAME).orEmpty()))
        } catch (error: Throwable) {
            error.rethrowIfFatal()
            file.delete()
            return failure(error.message ?: "Editor export cannot start in background", canRetry = true)
        }
        val runner = EditorExportRunner(
            rendererFactory = dependencies.rendererFactory,
            encoderFactory = dependencies.encoderFactory,
            storage = dependencies.storage,
            history = dependencies.history,
            reservationStore = dependencies.reservation,
            // The attempt id survives WorkManager process recreation, unlike a transient worker
            // instance. This also makes any unfinished private partial path deterministic.
            workId = manifest.exportAttemptId,
        )
        return try {
            when (val result = runner.run(
                manifest = manifest,
                requestedOutputName = requestedOutputName,
                preset = requestedPreset,
                onProgress = { completed, total ->
                    setProgress(
                        Data.Builder()
                            .putFloat(EditorExportWork.PROGRESS, if (total == 0L) 1f else completed.toFloat() / total)
                            .putLong(EditorExportWork.PROGRESS_FRAMES, completed)
                            .putLong(EditorExportWork.TOTAL_FRAMES, total)
                            .build()
                    )
                },
                isActive = { !isStopped },
            )) {
            is EditorExportResult.Success -> {
                setProgress(workDataOf(EditorExportWork.PROGRESS to 1f))
                Result.success(
                    Data.Builder()
                        .putString(EditorExportWork.RESULT_URI, result.uri.toString())
                        .putLong(EditorExportWork.RESULT_HISTORY_ID, result.historyId)
                        .putString(EditorExportWork.RESULT_OUTPUT_NAME, result.outputName)
                        .putInt(EditorExportWork.RESULT_DURATION_SECONDS, result.durationSeconds)
                        .putInt(EditorExportWork.RESULT_BITRATE_KBPS, result.bitrateKbps)
                        .putString(EditorExportWork.CLEANUP_WARNING, result.cleanupWarning)
                        .build()
                )
            }
            is EditorExportResult.Cancelled -> throw CancellationException("Editor export cancelled")
            is EditorExportResult.Failure -> failure(result.message, result.canRetry, result.cleanupWarning)
            }
        } finally {
            // A manifest is a one-shot private input. Retry creates a fresh snapshot from the UI.
            file.delete()
        }
    }

    private fun failure(message: String, canRetry: Boolean, cleanupWarning: String? = null): Result =
        Result.failure(
            workDataOf(
                EditorExportWork.ERROR_MESSAGE to message,
                EditorExportWork.LOGICAL_FAILURE to true,
                EditorExportWork.CAN_RETRY to canRetry,
                EditorExportWork.CLEANUP_WARNING to cleanupWarning,
            )
        )

    private fun createForegroundInfo(fileName: String): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Editor export",
                    NotificationManager.IMPORTANCE_LOW,
                )
            )
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Exporting edited audio")
            .setContentText(fileName)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .build()
        val notificationId = (id.hashCode() and Int.MAX_VALUE).coerceAtLeast(1)
        return when {
            Build.VERSION.SDK_INT >= 35 -> ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING,
            )
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> ForegroundInfo(
                notificationId,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            else -> ForegroundInfo(notificationId, notification)
        }
    }

    data class Dependencies(
        val rendererFactory: (EditorRenderManifest) -> TimelineRenderer,
        val encoderFactory: EditorExportEncoderFactory,
        val storage: EditorExportStorage,
        val history: EditorExportHistory,
        val reservation: EditorExportReservationStore,
    ) {
        companion object {
            fun production(context: Context): Dependencies = Dependencies(
                rendererFactory = { manifest ->
                    DefaultTimelineRenderer(MediaCodecPcmSourceReaderFactory(context))
                },
                encoderFactory = EditorExportEncoderFactory { output, preset, checkActive ->
                    val writer = OggOpusWriter(output)
                    val delegate = SoftwareOpusEncoder(
                        oggWriter = writer,
                        checkActive = checkActive,
                        bitrateKbps = preset.bitrateKbps,
                    )
                    object : EditorExportEncoder {
                        private var closed = false
                        override fun write(samples: ShortArray) = delegate.write(samples)
                        override fun finish() {
                            try {
                                delegate.finish()
                            } finally {
                                writer.close()
                                closed = true
                            }
                        }
                        override fun close() {
                            if (closed) return
                            try {
                                delegate.release()
                            } finally {
                                writer.close()
                                closed = true
                            }
                        }
                    }
                },
                storage = AndroidEditorExportStorage(context),
                history = RoomEditorExportHistory(AppDatabase.getDatabase(context).conversionHistoryDao()),
                reservation = AndroidEditorExportReservationStore(context),
            )
        }
    }

    private companion object {
        const val CHANNEL_ID = "editor_export"
    }
}

private class RoomEditorExportHistory(
    private val dao: com.aistudio.voicenote.cvtr.data.local.ConversionHistoryDao,
) : EditorExportHistory {
    override suspend fun insert(item: ConversionHistory): Long = dao.insert(item)
    override suspend fun delete(id: Long) = dao.deleteById(id)
    override suspend fun findByExportAttemptId(attemptId: String): ConversionHistory? =
        dao.getByEditorExportAttemptId(attemptId)
}

internal class AndroidEditorExportStorage(
    private val context: Context,
) : EditorExportStorage {
    override fun createPartialFile(workId: String): File {
        val directory = File(context.cacheDir, "editor-export")
        require(directory.exists() || directory.mkdirs()) { "Cannot create editor export cache" }
        return File(directory, "${workId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.ogg.partial")
    }

    override fun publish(partialFile: File, outputName: String): Uri =
        VoiceNoteStorage.saveToPublicStorage(context, partialFile, outputName)

    override fun publishReserved(partialFile: File, outputName: String, outputUri: String?): Uri {
        if (outputUri == null) return publish(partialFile, outputName)
        val uri = Uri.parse(outputUri)
        if (validatePublished(uri)) {
            check(finalizeReserved(uri)) { "Cannot finalize reserved editor output" }
            return uri
        }
        when (uri.scheme) {
            ContentResolver.SCHEME_CONTENT -> {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(partialFile).use { input -> input.copyTo(output) }
                } ?: error("Cannot open reserved editor output")
                val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
                check(context.contentResolver.update(uri, values, null, null) == 1) {
                    "Cannot publish reserved editor output"
                }
            }
            ContentResolver.SCHEME_FILE -> return publishPreQReserved(partialFile, uri)
            else -> error("Reserved editor output identity is invalid")
        }
        check(finalizeReserved(uri)) { "Cannot finalize reserved editor output" }
        return uri
    }

    override fun finalizeReserved(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
            uri.scheme != ContentResolver.SCHEME_CONTENT
        ) return true
        val values = ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }
        return context.contentResolver.update(uri, values, null, null) == 1
    }

    override fun validatePublished(uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE && uri.path.orEmpty().endsWith(PRE_Q_PENDING_SUFFIX)) {
            return false
        }
        return try {
            open(uri).use { input ->
                val bytes = ByteArray(64 * 1024)
                val read = input.read(bytes)
                if (read < 4) return false
                val hasOgg = bytes.copyOf(read).copyOfRange(0, 4).contentEquals(byteArrayOf(0x4f, 0x67, 0x67, 0x53))
                val header = "OpusHead".toByteArray(Charsets.US_ASCII)
                val hasOpus = (0..(read - header.size).coerceAtLeast(-1)).any { offset ->
                    bytes.copyOfRange(offset, offset + header.size).contentEquals(header)
                }
                hasOgg && hasOpus
            }
        } catch (error: Throwable) {
            error.rethrowIfFatal()
            false
        }
    }

    override fun sizeBytes(uri: Uri): Long = try {
        open(uri).use { input -> input.countBytes() }
    } catch (error: Throwable) {
        error.rethrowIfFatal()
        0L
    }

    override fun deletePublished(uri: Uri): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = uri.path ?: return false
            val pending = if (path.endsWith(PRE_Q_PENDING_SUFFIX)) path else "$path$PRE_Q_PENDING_SUFFIX"
            val final = if (path.endsWith(PRE_Q_PENDING_SUFFIX)) path.removeSuffix(PRE_Q_PENDING_SUFFIX) else path
            val temporary = "$final$PRE_Q_RENAME_TEMP_SUFFIX"
            val finalDeleted = !File(final).exists() || File(final).delete()
            val pendingDeleted = !File(pending).exists() || File(pending).delete()
            val temporaryDeleted = !File(temporary).exists() || File(temporary).delete()
            return finalDeleted && pendingDeleted && temporaryDeleted
        }
        return VoiceNoteStorage.deleteFromStorage(context, uri.toString())
    }

    override fun deletePartial(file: File): Boolean = !file.exists() || file.delete()

    override fun resolveOutputName(requestedName: String?, manifest: EditorRenderManifest): String {
        val candidate = safeExportName(requestedName, manifest)
        return VoiceNoteStorage.resolveOutputFileName(context, candidate)
    }

    internal fun reserveOutput(
        requestedName: String?,
        manifest: EditorRenderManifest,
    ): EditorExportOutputIdentity {
        val requested = safeExportName(requestedName, manifest)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            findMediaStoreReservation(manifest.exportAttemptId)?.let { return it }
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, requested)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/ogg")
                put(MediaStore.Audio.Media.RELATIVE_PATH, "Music/VoiceNoteConverter")
                put(MediaStore.Audio.Media.IS_PENDING, 1)
                put(MEDIASTORE_DESCRIPTION, reservationMarker(manifest.exportAttemptId))
            }
            val uri = context.contentResolver.insert(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: error("Cannot reserve editor output")
            return EditorExportOutputIdentity(requested, uri.toString())
        }

        @Suppress("DEPRECATION")
        val directory = VoiceNoteStorage.getStorageFolder()
        check(directory.exists() || directory.mkdirs()) { "Cannot create editor output directory" }
        val suffix = stableAttemptSuffix(manifest.exportAttemptId)
        val stem = requested.removeSuffix(".ogg").take(PRE_Q_OUTPUT_STEM_LIMIT)
        val finalName = "${stem}_$suffix.ogg"
        val finalFile = File(directory, finalName)
        val pendingFile = File(directory, "$finalName$PRE_Q_PENDING_SUFFIX")
        val temporaryFile = File(directory, "$finalName$PRE_Q_RENAME_TEMP_SUFFIX")
        if (finalFile.isFile && validatePreQFile(finalFile)) {
            return EditorExportOutputIdentity(finalName, Uri.fromFile(finalFile).toString())
        }
        if (finalFile.exists()) {
            check(finalFile.delete()) { "Cannot replace invalid editor output" }
        }
        if (temporaryFile.exists()) {
            check(temporaryFile.delete()) { "Cannot clean editor output rename temporary" }
        }
        if (pendingFile.exists() && !pendingFile.delete()) {
            check(validatePreQFile(pendingFile)) { "Cannot replace invalid editor output partial" }
        }
        if (!pendingFile.exists()) {
            check(pendingFile.createNewFile()) { "Cannot reserve editor output partial" }
        }
        return EditorExportOutputIdentity(finalName, Uri.fromFile(pendingFile).toString())
    }

    private fun findMediaStoreReservation(attemptId: String): EditorExportOutputIdentity? {
        val marker = reservationMarker(attemptId)
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
        )
        return context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "$MEDIASTORE_DESCRIPTION = ?",
            arrayOf(marker),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val id = cursor.getLong(0)
            val name = cursor.getString(1).orEmpty().ifBlank { "voice_note.ogg" }
            EditorExportOutputIdentity(
                name = name,
                uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString(),
            )
        }
    }

    private fun reservationMarker(attemptId: String): String =
        "VoiceNoteConverter editor export $attemptId"

    private fun publishPreQReserved(partialFile: File, reservedUri: Uri): Uri {
        val pendingPath = reservedUri.path ?: error("Reserved editor output path is missing")
        val pendingFile = File(pendingPath)
        val finalFile = if (pendingPath.endsWith(PRE_Q_PENDING_SUFFIX)) {
            File(pendingPath.removeSuffix(PRE_Q_PENDING_SUFFIX))
        } else {
            pendingFile
        }
        check(pendingFile.exists() || pendingFile.createNewFile()) { "Cannot open reserved editor output" }
        if (!validatePreQFile(pendingFile)) {
            FileOutputStream(pendingFile).use { output ->
                FileInputStream(partialFile).use { input -> input.copyTo(output) }
            }
        }
        if (finalFile.exists()) {
            if (validatePreQFile(finalFile)) {
                pendingFile.delete()
                return Uri.fromFile(finalFile)
            }
            check(finalFile.delete()) { "Cannot replace invalid editor output" }
        }
        if (!pendingFile.renameTo(finalFile)) {
            val temporary = File(finalFile.parentFile, "${finalFile.name}$PRE_Q_RENAME_TEMP_SUFFIX")
            FileInputStream(pendingFile).use { input ->
                FileOutputStream(temporary).use { output -> input.copyTo(output) }
            }
            check(temporary.renameTo(finalFile)) { "Cannot publish editor output" }
            check(pendingFile.delete()) { "Cannot clean editor output partial" }
        }
        return Uri.fromFile(finalFile)
    }

    private fun validatePreQFile(file: File): Boolean {
        if (!file.isFile || file.length() < 4L) return false
        return try {
            FileInputStream(file).use { input ->
                val bytes = ByteArray(64 * 1024)
                val read = input.read(bytes)
                if (read < 4) return false
                val hasOgg = bytes.copyOfRange(0, 4).contentEquals(byteArrayOf(0x4f, 0x67, 0x67, 0x53))
                val header = "OpusHead".toByteArray(Charsets.US_ASCII)
                val hasOpus = read >= header.size && (0..(read - header.size)).any { offset ->
                    bytes.copyOfRange(offset, offset + header.size).contentEquals(header)
                }
                hasOgg && hasOpus
            }
        } catch (error: Throwable) {
            error.rethrowIfFatal()
            false
        }
    }

    private fun stableAttemptSuffix(attemptId: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(attemptId.toByteArray(Charsets.UTF_8))
            .take(PRE_Q_ATTEMPT_SUFFIX_BYTES)
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val MEDIASTORE_DESCRIPTION = "description"
        const val PRE_Q_PENDING_SUFFIX = ".pending"
        const val PRE_Q_RENAME_TEMP_SUFFIX = ".tmp"
        const val PRE_Q_OUTPUT_STEM_LIMIT = 59
        const val PRE_Q_ATTEMPT_SUFFIX_BYTES = 6
    }

    private fun open(uri: Uri): InputStream {
        return when {
            uri.scheme == ContentResolver.SCHEME_CONTENT ->
                context.contentResolver.openInputStream(uri) ?: error("Cannot open published output")
            uri.scheme == ContentResolver.SCHEME_FILE -> FileInputStream(uri.path ?: error("Missing output path"))
            else -> FileInputStream(uri.toString())
        }
    }

    private fun InputStream.countBytes(): Long {
        val buffer = ByteArray(16 * 1024)
        var count = 0L
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            count += read
        }
        return count
    }
}

internal class AndroidEditorExportReservationStore(
    private val context: Context,
) : EditorExportReservationStore {
    private val storage = AndroidEditorExportStorage(context)

    override fun reserve(
        manifest: EditorRenderManifest,
        requestedName: String?,
    ): EditorExportOutputIdentity = storage.reserveOutput(requestedName, manifest)

    override fun release(identity: EditorExportOutputIdentity): Boolean =
        storage.deletePublished(Uri.parse(identity.uri))
}

private val ExportPreset.bitrateKbps: Int
    get() = when (this) {
        ExportPreset.VOICE_NOTE_32 -> 32
        ExportPreset.HIGH_QUALITY_64 -> 64
    }

private fun safeExportName(requestedName: String?, manifest: EditorRenderManifest): String {
    var candidate = VoiceNoteStorage.sanitizeOutputFileName(
        requestedName,
        fallback = "${manifest.sourceFileName.ifBlank { "voice_note" }}_edited.ogg",
    )
    val sourceNames = manifest.tracks.asSequence()
        .flatMap { it.clips.asSequence() }
        .map { Uri.parse(it.source.uri).lastPathSegment ?: it.source.uri.substringAfterLast('/') }
        .map { VoiceNoteStorage.sanitizeOutputFileName(it) }
        .toSet()
    if (candidate in sourceNames) {
        candidate = VoiceNoteStorage.sanitizeOutputFileName(
            "${candidate.removeSuffix(".ogg")}_edited.ogg"
        )
    }
    return candidate
}
