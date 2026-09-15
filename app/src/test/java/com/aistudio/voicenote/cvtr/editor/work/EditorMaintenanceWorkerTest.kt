package com.aistudio.voicenote.cvtr.editor.work

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Data
import androidx.work.ForegroundUpdater
import androidx.work.ListenableWorker
import androidx.work.ProgressUpdater
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.impl.utils.taskexecutor.SerialExecutor
import androidx.work.impl.utils.taskexecutor.TaskExecutor
import com.aistudio.voicenote.cvtr.data.local.AppDatabase
import com.aistudio.voicenote.cvtr.data.local.ConversionHistory
import com.aistudio.voicenote.cvtr.editor.cache.ProcessedAudioCache
import com.aistudio.voicenote.cvtr.editor.data.DraftSourceInput
import com.aistudio.voicenote.cvtr.editor.data.DraftSourceStorage
import com.aistudio.voicenote.cvtr.editor.data.EditorDraftRepository
import com.aistudio.voicenote.cvtr.editor.audio.EditorRenderManifest
import com.aistudio.voicenote.cvtr.editor.model.AudioClip
import com.aistudio.voicenote.cvtr.editor.model.AudioSourceRef
import com.aistudio.voicenote.cvtr.editor.model.EditorSession
import com.aistudio.voicenote.cvtr.editor.model.EditorTrack
import java.io.File
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import com.google.common.util.concurrent.Futures
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EditorMaintenanceWorkerTest {
    @Test
    fun `old orphan partial is removed while draft history and in-flight files survive`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val now = System.currentTimeMillis()
        val old = now - EditorMaintenanceWorker.RETENTION_MS - 1L
        val exportRoot = File(app.cacheDir, "editor-export").apply { mkdirs() }
        val orphan = File(exportRoot, "orphan.partial").apply {
            writeText("stale")
            setLastModified(old)
        }
        val attemptId = "attempt-live-${UUID.randomUUID()}"
        val inFlight = File(exportRoot, "$attemptId.partial").apply {
            writeText("active")
            setLastModified(old)
        }
        val sourceFile = File(app.cacheDir, "task4-source-${UUID.randomUUID()}.wav").apply {
            writeText("source")
        }
        val draftId = "task4-draft-${UUID.randomUUID()}"
        val sourceStorage = DraftSourceStorage(
            root = File(app.filesDir, "editor-drafts"),
            sourceOpener = { sourceFile.inputStream() },
            sourceSizer = { sourceFile.length() },
            freeSpace = { Long.MAX_VALUE },
            safetyBytes = 0L,
        )
        val database = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build()
        val repository = EditorDraftRepository(
            database = database,
            sourceStorage = sourceStorage,
            processedAudioCache = ProcessedAudioCache(File(app.cacheDir, "processed_audio")),
        )
        val sourceUri = "task4-source://${sourceFile.name}"
        repository.save(
            EditorSession(
                id = draftId,
                tracks = listOf(
                    EditorTrack(
                        id = "track-1",
                        name = "Voice",
                        clips = listOf(
                            AudioClip(
                                id = "clip-1",
                                source = AudioSourceRef(sourceUri, 1_000L),
                                sourceStartMs = 0L,
                                sourceEndMs = 1_000L,
                                timelineStartMs = 0L,
                            )
                        ),
                    )
                ),
            )
        )
        val draftSource = File(app.filesDir, "editor-drafts/$draftId")
        val historyOutput = File(app.filesDir, "history/task4-history-${UUID.randomUUID()}.ogg").apply {
            parentFile?.mkdirs()
            writeText("history")
        }
        database.conversionHistoryDao().insert(
            ConversionHistory(
                originalFileName = "input.wav",
                outputFileName = "history.ogg",
                outputFilePath = historyOutput.absolutePath,
                durationSeconds = 1,
                fileSizeBytes = historyOutput.length(),
                waveform = "",
                bitrateKbps = 32,
                createdAt = now,
            )
        )
        val session = EditorSession(
            id = "in-flight",
            tracks = listOf(
                EditorTrack(
                    id = "track-flight",
                    name = "Flight",
                    clips = listOf(
                        AudioClip(
                            id = "clip-flight",
                            source = AudioSourceRef("content://source", 1_000L),
                            sourceStartMs = 0L,
                            sourceEndMs = 1_000L,
                            timelineStartMs = 0L,
                        )
                    ),
                )
            ),
        )
        EditorRenderManifest.writePrivate(app, session, exportAttemptId = attemptId)

        EditorMaintenanceWorker.runOnce(app, nowMs = now, database = database)

        assertFalse(orphan.exists())
        assertTrue(draftSource.exists())
        assertTrue(historyOutput.exists())
        assertTrue(inFlight.exists())
        repository.delete(draftId)
        sourceFile.delete()
        historyOutput.delete()
        database.close()
    }

    @Test
    fun `cancellation is propagated instead of converted to retry`() = runTest {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val worker = EditorMaintenanceWorker(
            app,
            workerParameters(Data.Builder().build()),
            maintenance = { throw CancellationException("cancelled") },
        )

        var propagated = false
        try {
            worker.doWork()
        } catch (_: CancellationException) {
            propagated = true
        }
        assertTrue(propagated)
    }

    private fun workerParameters(input: Data): WorkerParameters {
        val executor = Executors.newSingleThreadExecutor()
        val serial = object : SerialExecutor {
            override fun execute(command: Runnable) = executor.execute(command)
            override fun hasPendingTasks(): Boolean = false
        }
        val taskExecutor = object : TaskExecutor {
            override fun getMainThreadExecutor(): Executor = executor
            override fun getSerialTaskExecutor(): SerialExecutor = serial
        }
        val workerFactory = object : WorkerFactory() {
            override fun createWorker(
                appContext: android.content.Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker? = null
        }
        val progress = ProgressUpdater { _, _, _ -> Futures.immediateVoidFuture() }
        val foreground = ForegroundUpdater { _, _, _ -> Futures.immediateVoidFuture() }
        return WorkerParameters(
            UUID.randomUUID(),
            input,
            emptyList<String>(),
            WorkerParameters.RuntimeExtras(),
            0,
            0,
            executor,
            kotlinx.coroutines.Dispatchers.Default,
            taskExecutor,
            workerFactory,
            progress,
            foreground,
        )
    }
}
